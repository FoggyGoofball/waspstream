package com.waspstream.broadcaster.webrtc

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.webrtc.*
import java.util.*
import com.waspstream.broadcaster.firebase.DiagnosticsLogger

/**
 * Manages the WebRTC PeerConnection lifecycle on the Broadcaster side.
 *
 * The Broadcaster creates a PeerConnection, adds a VideoTrack from the camera,
 * creates an SDP Offer, and handles the signaling exchange with the Viewer.
 *
 * CRITICAL: EGL must be initialized from the main thread on Mediatek/Android 8.1.
 * Using a dedicated HandlerThread for SurfaceTextureHelper to avoid EGL thread
 * affinity issues that cause SIGABRT in WebRTC's native signaling_thread.
 */
class WebRTCManager(
    private val context: Context,
    private val onIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.PeerConnectionState) -> Unit
) {

    companion object {
        private const val TAG = "WebRTCManager"
        private val STUN_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null

    // Dedicated thread for SurfaceTextureHelper to keep EGL isolated from coroutine threads
    private var webRtcThread: HandlerThread? = null
    private var webRtcHandler: Handler? = null
    private var eglBase: EglBase? = null

    /** Expose EGL context for local preview SurfaceViewRenderer */
    val eglContext: EglBase.Context?
        get() = eglBase?.eglBaseContext

    /**
     * Initialize the PeerConnectionFactory.
     * Must be called once before any peer connection is created.
     */
    fun initialize() {
        Log.d(TAG, "initialize() — creating HandlerThread for EGL")
        webRtcThread = HandlerThread("WebRTC-EglThread").apply { start() }
        webRtcHandler = Handler(webRtcThread!!.looper)

        // Create EGL base on the main thread (required for Mediatek)
        eglBase = EglBase.create()

        // Initialize WebRTC
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("")
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()

        Log.d(TAG, "initialize() complete — PeerConnectionFactory created")
    }

    /**
     * Create a new PeerConnection with the given video capturer.
     * The video capturer should be started before calling this.
     */
    fun createPeerConnection(videoCapturer: CameraVideoCapturer, localPreview: SurfaceViewRenderer?) {
        val factory = peerConnectionFactory ?: throw IllegalStateException("PeerConnectionFactory not initialized")
        val egl = eglBase ?: throw IllegalStateException("EglBase not initialized")

        Log.d(TAG, "createPeerConnection() — using dedicated HandlerThread for SurfaceTextureHelper")
        DiagnosticsLogger.log("WEBRTC_PC", "Creating PeerConnection on dedicated EGL thread")

        // Create video source and track
        videoSource = factory.createVideoSource(videoCapturer.isScreencast)

        // CRITICAL: Must make EGL context current on THIS thread before sharing with
        // SurfaceTextureHelper. On Mediatek/Android 8.1, the EGL context cannot be shared
        // across threads unless it has been made current at least once on the creating thread.
        // Otherwise the first video frame triggers a SIGABRT in the native GPU pipeline.
        egl.makeCurrent()
        videoCapturer.initialize(
            SurfaceTextureHelper.create("WebRTC-SurfaceTexture", egl.eglBaseContext),
            context,
            videoSource?.capturerObserver
        )
        // Lower resolution and framerate to reduce encoder + EGL pipeline pressure
        // on low-end Mediatek SoCs (MT6739/MT6580 based devices).
        videoCapturer.startCapture(640, 480, 15)

        videoTrack = factory.createVideoTrack("broadcast_video", videoSource!!)
        videoTrack?.setEnabled(true)

        localPreview?.let { preview ->
            videoTrack?.addSink(preview)
        }

        // Create audio source and track
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("broadcast_audio", audioSource)
        audioTrack?.setEnabled(true)

        // Create peer connection
        val rtcConfig = PeerConnection.RTCConfiguration(STUN_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 0
        }

        DiagnosticsLogger.log("WEBRTC_PC_CFG", "PeerConnection.RTCConfiguration built, creating PC")

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "onIceCandidate: $candidate")
                DiagnosticsLogger.log("WEBRTC_ICE", "onIceCandidate generated",
                    mapOf("sdp" to candidate.sdp, "sdpMLineIndex" to candidate.sdpMLineIndex))
                onIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                Log.d(TAG, "onIceCandidatesRemoved: ${candidates.size}")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: $state")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "onIceConnectionReceivingChange: $receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: $state")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "onSignalingChange: $state")
            }

            override fun onAddStream(stream: MediaStream) {
                Log.d(TAG, "onAddStream: ${stream.getId()}")
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                Log.d(TAG, "onAddTrack")
            }

            override fun onRemoveStream(stream: MediaStream) {
                Log.d(TAG, "onRemoveStream: ${stream.getId()}")
            }

            override fun onDataChannel(channel: DataChannel) {
                Log.d(TAG, "onDataChannel: ${channel.label()}")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded")
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "onConnectionChange: $state")
                DiagnosticsLogger.log("WEBRTC_CONNECTION", "Connection state: $state")
                onConnectionStateChanged(state)
            }

            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "onStandardizedIceConnectionChange: $state")
            }
        })

        // Add media tracks
        val stream = factory.createLocalMediaStream("broadcast_stream")
        videoTrack?.let { stream.addTrack(it) }
        audioTrack?.let { stream.addTrack(it) }
        peerConnection?.addStream(stream)

        DiagnosticsLogger.log("WEBRTC_PC_OK", "PeerConnection created with video+audio tracks")
    }

    /**
     * Create an SDP Offer for the current PeerConnection.
     */
    fun createOffer(onSuccess: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local SDP set successfully")
                        onSuccess(sessionDescription)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Failed to set local SDP: $error")
                    }
                    override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
                    override fun onCreateFailure(error: String) {}
                }, sessionDescription)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Failed to create offer: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    /**
     * Set the remote SDP Answer received from the viewer.
     */
    fun setRemoteAnswer(answer: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote SDP set successfully")
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "Failed to set remote SDP: $error")
            }
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
            override fun onCreateFailure(error: String) {}
        }, answer)
    }

    /**
     * Add an ICE candidate received from the viewer.
     */
    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    /**
     * Dispose the peer connection and release all resources.
     */
    fun dispose() {
        Log.d(TAG, "dispose() — cleaning up WebRTC resources")
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing capturer", e)
        }

        peerConnection?.dispose()
        peerConnection = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        videoSource?.dispose()
        videoSource = null
        videoTrack = null
        audioTrack = null
        eglBase?.release()
        eglBase = null

        webRtcThread?.quitSafely()
        webRtcThread = null
        webRtcHandler = null

        Log.d(TAG, "dispose() complete")
    }

    fun setVideoCapturer(capturer: CameraVideoCapturer) {
        this.videoCapturer = capturer
    }
}
