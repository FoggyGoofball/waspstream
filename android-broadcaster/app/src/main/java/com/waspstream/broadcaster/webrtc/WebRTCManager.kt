package com.waspstream.broadcaster.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import com.waspstream.broadcaster.firebase.FirebaseRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Unified WebRTC manager that handles PeerConnection lifecycle AND signaling
 * over Firebase RTDB in one clean class.
 *
 * Flow:
 *   initialize() → createPeerConnection() (creates offer, sends to RTDB,
 *   waits for viewer answer, sets remote desc, listens for viewer ICE) → dispose()
 *
 * EGL is initialized on the creating thread with an immediate dummy pbuffer
 * surface to ensure makeCurrent() never fails.
 */
class WebRTCManager(
    private val context: Context,
    private val onDiagnostic: (step: String, message: String, data: Map<String, Any?>?) -> Unit,
    private val onConnectionState: (PeerConnection.PeerConnectionState) -> Unit
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
    private var eglBase: EglBase? = null

    private var signalingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Diagnostics shorthand ──────────────────────────────────────────
    private fun diag(step: String, message: String, data: Map<String, Any?>? = null) {
        Log.d(TAG, "[$step] $message")
        onDiagnostic(step, message, data)
    }

    // ── Initialization ─────────────────────────────────────────────────

    /**
     * Initialize the EGL context and PeerConnectionFactory.
     * Must be called once before any peer connection is created.
     *
     * Creates a dummy pbuffer surface immediately and makes it current
     * so the EGL context is ready for SurfaceTextureHelper.
     */
    fun initialize() {
        diag("WIZ_INIT", "Initializing EGL + PeerConnectionFactory")

        // Use the library's default pixel buffer config (includes EGL_PBUFFER_BIT
        // so the internal dummy pbuffer surface creation succeeds).
        // NO custom config array — let EglBase pick the right config for this device.
        eglBase = EglBase.create(null, EglBase.CONFIG_PIXEL_BUFFER)

        // Immediately create a dummy surface and make it current so the EGL context
        // is fully initialized on this thread. This prevents "No EGLSurface" errors
        // when SurfaceTextureHelper later tries to share this context.
        eglBase!!.createDummyPbufferSurface()
        eglBase!!.makeCurrent()
        diag("WIZ_EGL_OK", "EGL initialized with dummy pbuffer surface")

        // Initialize WebRTC native library
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("")
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        diag("WIZ_INIT_OK", "PeerConnectionFactory created")
    }

    // ── Main entry point ───────────────────────────────────────────────

    /**
     * Full streaming setup:
     * 1. Create camera capturer
     * 2. Create PeerConnection with video+audio tracks
     * 3. Create SDP offer → write to RTDB
     * 4. Wait for viewer answer (polling, 30s timeout)
     * 5. Set remote answer
     * 6. Listen for viewer ICE candidates
     */
    fun startStreaming() {
        val factory = peerConnectionFactory ?: run {
            diag("WIZ_ERROR", "PeerConnectionFactory not initialized — call initialize() first")
            return
        }
        val egl = eglBase ?: run {
            diag("WIZ_ERROR", "EGL not initialized — call initialize() first")
            return
        }

        signalingJob = scope.launch {
            try {
                // ── Step 1: Create camera capturer ──
                diag("WIZ_CAM", "Creating camera capturer")
                val capturer = createCameraCapturer()
                videoCapturer = capturer
                diag("WIZ_CAM_OK", "Camera capturer created: ${capturer.javaClass.simpleName}")

                // ── Step 2: Create video source + track ──
                videoSource = factory.createVideoSource(capturer.isScreencast)
                capturer.initialize(
                    SurfaceTextureHelper.create("WebRTC-STH", egl.eglBaseContext),
                    context,
                    videoSource!!.capturerObserver
                )
                capturer.startCapture(640, 480, 15)

                videoTrack = factory.createVideoTrack("video", videoSource!!)
                videoTrack!!.setEnabled(true)

                // ── Step 3: Create audio source + track ──
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                }
                val audioSource = factory.createAudioSource(audioConstraints)
                audioTrack = factory.createAudioTrack("audio", audioSource)
                audioTrack!!.setEnabled(true)

                diag("WIZ_TRACKS", "Video + audio tracks created")

                // ── Step 4: Create PeerConnection ──
                val rtcConfig = PeerConnection.RTCConfiguration(STUN_SERVERS).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                }

                peerConnection = factory.createPeerConnection(rtcConfig, createObserver())
                if (peerConnection == null) {
                    diag("WIZ_PC_FAIL", "createPeerConnection returned null")
                    return@launch
                }

                // Add tracks via local media stream
                val stream = factory.createLocalMediaStream("stream")
                videoTrack?.let { stream.addTrack(it) }
                audioTrack?.let { stream.addTrack(it) }
                peerConnection!!.addStream(stream)

                diag("WIZ_PC_OK", "PeerConnection created with tracks")

                // ── Step 5: Create and send SDP offer ──
                val offerDeferred = CompletableDeferred<SessionDescription>()
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                }
                peerConnection!!.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        peerConnection!!.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() { offerDeferred.complete(sdp) }
                            override fun onSetFailure(e: String) { offerDeferred.completeExceptionally(RuntimeException("setLocalDescription failed: $e")) }
                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                            override fun onCreateFailure(e: String) {}
                        }, sdp)
                    }
                    override fun onCreateFailure(e: String) { offerDeferred.completeExceptionally(RuntimeException("createOffer failed: $e")) }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(e: String) {}
                }, constraints)
                val offerResult = offerDeferred.await()

                diag("WIZ_OFFER", "SDP offer created, writing to RTDB",
                    mapOf("type" to offerResult.type.canonicalForm(), "sdpLen" to offerResult.description.length))

                // Write offer to RTDB
                FirebaseRepository.writeOffer(mapOf(
                    "type" to offerResult.type.canonicalForm(),
                    "sdp" to offerResult.description
                ))
                diag("WIZ_OFFER_SENT", "Offer written to /signaling/offer")

                // ── Step 6: Wait for viewer answer ──
                diag("WIZ_WAIT_ANSWER", "Polling for viewer answer (30s timeout)")
                val answer = waitForAnswer(30_000)
                if (answer == null) {
                    diag("WIZ_TIMEOUT", "No answer from viewer within 30s")
                    return@launch
                }

                diag("WIZ_ANSWER_OK", "Answer received, setting remote description")
                val answerDesc = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(answer["type"] ?: "answer"),
                    answer["sdp"] ?: ""
                )
                peerConnection!!.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() { diag("WIZ_REMOTE_SET", "Remote description set successfully") }
                    override fun onSetFailure(e: String) { diag("WIZ_REMOTE_FAIL", "setRemoteDescription failed: $e") }
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(e: String) {}
                }, answerDesc)

                // ── Step 7: Listen for viewer ICE candidates ──
                diag("WIZ_ICE_LISTEN", "Listening for viewer ICE candidates")
                FirebaseRepository.onViewerIceCandidate { candidate ->
                    if (candidate != null && peerConnection != null) {
                        val iceCandidate = IceCandidate(
                            candidate["sdpMid"] as? String ?: "video",
                            (candidate["sdpMLineIndex"] as? Number)?.toInt() ?: 0,
                            candidate["candidate"] as? String ?: ""
                        )
                        peerConnection!!.addIceCandidate(iceCandidate)
                    }
                }

                diag("WIZ_DONE", "Streaming setup complete — viewer should now connect")
            } catch (e: Exception) {
                diag("WIZ_FAIL", "Streaming setup failed", mapOf("error" to (e.message ?: "")))
                Log.e(TAG, "startStreaming failed", e)
            }
        }
    }

    // ── Signaling helpers ───────────────────────────────────────────────

    private suspend fun waitForAnswer(timeoutMs: Long): Map<String, String>? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val snapshot = FirebaseRepository.signalingRef.child("answer").get().await()
            @Suppress("UNCHECKED_CAST")
            val answer = snapshot.value as? Map<String, String>
            if (answer != null && answer["type"] == "answer") {
                return answer
            }
            delay(500)
        }
        return null
    }

    // ── ICE candidate sender ────────────────────────────────────────────

    private fun sendIceCandidate(candidate: IceCandidate) {
        scope.launch {
            try {
                FirebaseRepository.pushBroadcasterIceCandidate(mapOf(
                    "candidate" to candidate.sdp,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "sdpMid" to candidate.sdpMid
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send ICE candidate", e)
            }
        }
    }

    // ── PeerConnection Observer ─────────────────────────────────────────

    private fun createObserver() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            diag("WIZ_ICE", "Sending ICE candidate", mapOf("sdpMLineIndex" to candidate.sdpMLineIndex))
            sendIceCandidate(candidate)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            diag("WIZ_ICE_CONN", "ICE connection: $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            diag("WIZ_ICE_GATH", "ICE gathering: $state")
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            diag("WIZ_SIG", "Signaling: $state")
        }
        override fun onAddStream(stream: MediaStream) {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            diag("WIZ_CONN", "Connection: $state")
            onConnectionState(state)
        }
        override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    }

    // ── Camera capturer creation ────────────────────────────────────────

    private fun createCameraCapturer(): CameraVideoCapturer {
        // Try Camera1 first (better compat on Android 8.1 / Mediatek)
        try {
            val e = org.webrtc.Camera1Enumerator(false)
            val names = e.deviceNames
            if (names.isNotEmpty()) {
                val name = names.find { e.isBackFacing(it) } ?: names.first()
                diag("WIZ_CAM1", "Using Camera1", mapOf("device" to name))
                return e.createCapturer(name, null)
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Camera1 failed, trying Camera2", ex)
        }

        // Fallback to Camera2
        val e = org.webrtc.Camera2Enumerator(context)
        val names = e.deviceNames
        val name = names.find { e.isBackFacing(it) } ?: names.firstOrNull()
            ?: throw RuntimeException("No camera found")
        diag("WIZ_CAM2", "Using Camera2", mapOf("device" to name))
        return e.createCapturer(name, null)
    }

    // ── Cleanup ─────────────────────────────────────────────────────────

    fun dispose() {
        diag("WIZ_DISPOSE", "Cleaning up WebRTC resources")
        signalingJob?.cancel()
        signalingJob = null

        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (_: Exception) {}

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

        diag("WIZ_DISPOSED", "Cleanup complete")
    }

    fun getEglContext(): EglBase.Context? = eglBase?.eglBaseContext
}
