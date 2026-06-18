package com.waspstream.broadcaster.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.util.*

/**
 * Manages the WebRTC PeerConnection lifecycle on the Broadcaster side.
 *
 * The Broadcaster creates a PeerConnection, adds a VideoTrack from the camera,
 * creates an SDP Offer, and handles the signaling exchange with the Viewer.
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

    private val eglBase = EglBase.create()

    /**
     * Initialize the PeerConnectionFactory.
     * Must be called once before any peer connection is created.
     */
    fun initialize() {
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
    }

    /**
     * Create a new PeerConnection with the given video capturer.
     * The video capturer should be started before calling this.
     */
    fun createPeerConnection(videoCapturer: CameraVideoCapturer, localPreview: SurfaceViewRenderer?) {
        val factory = peerConnectionFactory ?: throw IllegalStateException("PeerConnectionFactory not initialized")

        // Create video source and track
        videoSource = factory.createVideoSource(videoCapturer.isScreencast)
        videoCapturer.initialize(
            SurfaceTextureHelper.create(Thread.currentThread().name, eglBase.eglBaseContext),
            context,
            videoSource?.capturerObserver
        )
        videoCapturer.startCapture(1280, 720, 30)

        videoTrack = factory.createVideoTrack("broadcast_video", videoSource!!)
        videoTrack?.setEnabled(true)

        // Set up local preview
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

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "onIceCandidate: $candidate")
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
        eglBase.release()
    }

    // Reference to video capturer for disposal
    private var videoCapturer: CameraVideoCapturer? = null

    fun setVideoCapturer(capturer: CameraVideoCapturer) {
        this.videoCapturer = capturer
    }
}
