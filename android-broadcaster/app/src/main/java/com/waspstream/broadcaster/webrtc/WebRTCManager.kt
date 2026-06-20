package com.waspstream.broadcaster.webrtc

import android.content.Context
import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.waspstream.broadcaster.firebase.FirebaseRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.tasks.await
import org.webrtc.*

class WebRTCManager(
    private val ctx: Context,
    private val diag: (String, String, Map<String, Any?>?) -> Unit,
    private val onConnState: (PeerConnection.PeerConnectionState) -> Unit
) {
    companion object {
        private const val TAG = "WRTCMgr"
        private val STUN = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
    }
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var vs: VideoSource? = null
    private var vt: VideoTrack? = null
    private var at: AudioTrack? = null
    private var egl: EglBase? = null
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ansL: ChildEventListener? = null
    private var iceL: ChildEventListener? = null
    private var reqL: ChildEventListener? = null
    private var frameCount = 0L

    private fun d(s: String, m: String, x: Map<String,Any?>? = null) { Log.d(TAG, "[$s] $m"); diag(s,m,x) }

    /**
     * Call this from a background thread — EGL / camera operations are blocking.
     */
    fun start() {
        // Let BroadcastService know we're starting
        d("INIT","Starting WebRTC on bg thread")

        try {
            egl = EglBase.create().apply { createDummyPbufferSurface(); makeCurrent() }
            d("EGL","EGL context created and made current")

            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx)
                .setFieldTrials("").createInitializationOptions())
            val f = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext))
                .createPeerConnectionFactory()
            factory = f
            d("FAC","PeerConnectionFactory with hw encoder/decoder created")

            val cap = createCamera()
            capturer = cap
            d("CAM","Camera capturer created")

            vs = f.createVideoSource(cap.isScreencast)
            val sth = SurfaceTextureHelper.create("STH", egl!!.eglBaseContext)
            cap.initialize(sth, ctx, vs!!.capturerObserver)
            cap.startCapture(640, 480, 30)
            d("CAM_OK","Camera started @640x480 30fps")

            vt = f.createVideoTrack("v", vs!!).apply { setEnabled(true) }
            // Frame counter — now uses diag() for visibility everywhere
            vt!!.addSink(object : VideoSink {
                private var lastLog = 0L
                override fun onFrame(frame: VideoFrame) {
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastLog > 5000) {
                        lastLog = now
                        d("FRAMES","${frameCount} frames in last 5s")
                    }
                }
            })
            d("VID","Video track with frame sink created")

            val ac = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            at = f.createAudioTrack("a", f.createAudioSource(ac)).apply { setEnabled(true) }
            d("AUD","Audio track created")

            val cfg = PeerConnection.RTCConfiguration(STUN).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            pc = f.createPeerConnection(cfg, PCObs())
            if (pc == null) { d("PC","createPeerConnection returned null"); return }
            d("PC","PeerConnection ok")

            pc!!.addTransceiver(vt!!, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
            pc!!.addTransceiver(at!!, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
            d("TRK","Transceivers added send-only")

            // Listen for viewer-initiated renegotiation requests
            io.launch { listenForOfferRequests() }

            d("OFFER","Creating SDP offer")
            val mc = MediaConstraints()
            pc!!.createOffer(
                object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        d("OFFER_OK","Offer created")
                        pc!!.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                d("LOCAL","Local desc set, writing to RTDB")
                                io.launch { writeOffer(sdp) }
                            }
                            override fun onSetFailure(e: String) { d("LOCAL_FAIL",e) }
                            override fun onCreateSuccess(s: SessionDescription?) {}
                            override fun onCreateFailure(e: String) {}
                        }, sdp)
                    }
                    override fun onCreateFailure(e: String) { d("OFFER_FAIL",e) }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(e: String) {}
                }, mc)
        } catch(e: Exception) { d("ERR","Start: ${e.message}") }
    }

    private fun createCamera(): CameraVideoCapturer {
        // Try Camera2 first (modern API, works with EGL), fallback to Camera1
        try {
            val e2 = Camera2Enumerator(ctx)
            val names = e2.deviceNames
            if (names.isNotEmpty()) {
                val cap = e2.createCapturer(names[0], null)
                d("CAM_MODE","Camera2 enumerator selected")
                return cap
            }
        } catch (_: Exception) { }
        // Fallback to Camera1
        val e1 = Camera1Enumerator(false)
        val cap = e1.createCapturer(e1.deviceNames.firstOrNull() ?: throw RuntimeException("No camera"), null)
        d("CAM_MODE","Camera1 enumerator fallback")
        return cap
    }

    private suspend fun writeOffer(offer: SessionDescription) {
        try {
            FirebaseRepository.signalingRef.child("offer").setValue(
                mapOf("type" to offer.type.canonicalForm(), "sdp" to offer.description)
            ).await()
            d("RTDB","Offer written to Firebase")
            waitAnswer()
        } catch(e: Exception) { d("RTDB_FAIL",e.message?:"") }
    }

    private suspend fun waitAnswer() {
        val def = CompletableDeferred<SessionDescription>()
        ansL = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, p: String?) {
                if (s.key == "answer" && s.value != null) {
                    val t = s.child("type").value?.toString() ?: return
                    val sd = s.child("sdp").value?.toString() ?: return
                    def.complete(SessionDescription(
                        if (t == "answer") SessionDescription.Type.ANSWER else SessionDescription.Type.OFFER, sd))
                }
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {
                // Handles viewer updating an existing answer (e.g. page refresh)
                if (s.key == "answer" && s.value != null) {
                    val t = s.child("type").value?.toString() ?: return
                    val sd = s.child("sdp").value?.toString() ?: return
                    def.complete(SessionDescription(
                        if (t == "answer") SessionDescription.Type.ANSWER else SessionDescription.Type.OFFER, sd))
                }
            }
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) { def.completeExceptionally(Exception(e.message)) }
        }
        FirebaseRepository.signalingRef.addChildEventListener(ansL!!)
        try {
            val a = withTimeout(30_000L) { def.await() }
            d("ANSWER","Got answer from viewer"); setRemote(a)
        } catch(e: Exception) { d("ANSWER_TMO","No answer within 30s") }
    }

    private fun setRemote(answer: SessionDescription) {
        try {
            pc!!.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { d("REMOTE","Remote desc set"); listenIce() }
                override fun onSetFailure(e: String) { d("REMOTE_FAIL",e) }
                override fun onCreateSuccess(s: SessionDescription?) {}
                override fun onCreateFailure(e: String) {}
            }, answer)
        } catch(e: Exception) { d("REMOTE_ERR",e.message?:"") }
    }

    private fun listenIce() {
        io.launch {
            iceL = object : ChildEventListener {
                override fun onChildAdded(s: DataSnapshot, p: String?) {
                    val c = s.value
                    if (c is Map<*, *>) {
                        val sd = c["candidate"]?.toString() ?: return
                        val sm = c["sdpMid"]?.toString() ?: return
                        val si = (c["sdpMLineIndex"] as? Number)?.toInt() ?: return
                        pc?.addIceCandidate(IceCandidate(sm, si, sd))
                    }
                }
                override fun onChildChanged(s: DataSnapshot, p: String?) {}
                override fun onChildRemoved(s: DataSnapshot) {}
                override fun onChildMoved(s: DataSnapshot, p: String?) {}
                override fun onCancelled(e: DatabaseError) {}
            }
            FirebaseRepository.signalingRef.child("candidates").child("viewer").addChildEventListener(iceL!!)
        }
    }

    private inner class PCObs : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            d("ICE","Local: ${c.sdpMid}")
            io.launch {
                try {
                    FirebaseRepository.signalingRef.child("candidates").child("broadcaster").push()
                        .setValue(mapOf("sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "candidate" to c.sdp)).await()
                } catch(_: Exception) {}
            }
        }
        override fun onIceCandidatesRemoved(c: Array<IceCandidate>) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onSignalingChange(s: PeerConnection.SignalingState) { d("SIG","sig=$s") }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            d("ICE","conn=$s")
            io.launch {
                when(s) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> FirebaseRepository.updateState("connected")
                    PeerConnection.IceConnectionState.DISCONNECTED -> FirebaseRepository.updateState("live")
                    PeerConnection.IceConnectionState.FAILED -> FirebaseRepository.updateState("error")
                    else -> {}
                }
            }
        }
        override fun onIceConnectionReceivingChange(r: Boolean) {}
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { onConnState(s) }
        override fun onAddStream(s: MediaStream) { d("STREAM","remote: ${s.videoTracks.size}v ${s.audioTracks.size}a") }
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(c: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, st: Array<MediaStream>) {}
        override fun onTrack(r: RtpTransceiver) {}
    }

    // ── Offer re-request listener ────────────────────────────────────────────

    /**
     * Listens on signaling/request_offer for viewer-initiated renegotiation.
     * When a viewer writes a timestamp to this node, we tear down the current
     * PeerConnection and generate a fresh offer so the viewer can connect even
     * after the initial handshake window has expired.
     */
    private suspend fun listenForOfferRequests() {
        reqL = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, p: String?) {
                d("REQ_OFFER", "Viewer requested fresh offer — re-initializing")
                io.launch {
                    // Prevent stale ICE callbacks from firing on the old PC
                    pc?.close()
                    pc?.dispose()
                    pc = null
                    // Clear old signaling for a fresh handshake
                    try {
                        FirebaseRepository.clearSignaling()
                    } catch (_: Exception) {}
                    // Re-create PeerConnection with the same tracks and generate a new offer
                    recreatePeerConnectionAndOffer()
                }
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) { d("REQ_OFFER_ERR", e.message ?: "") }
        }
        FirebaseRepository.signalingRef.child("request_offer").addChildEventListener(reqL!!)
    }

    private fun recreatePeerConnectionAndOffer() {
        d("REINIT", "Recreating PeerConnection and generating new offer")
        try {
            val cfg = PeerConnection.RTCConfiguration(STUN).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            pc = factory!!.createPeerConnection(cfg, PCObs())
            if (pc == null) { d("REINIT_FAIL", "createPeerConnection returned null"); return }

            pc!!.addTransceiver(vt!!, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
            pc!!.addTransceiver(at!!, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
            d("REINIT_TRK", "Transceivers re-added")

            val mc = MediaConstraints()
            pc!!.createOffer(
                object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        d("REOFFER_OK", "New offer created")
                        pc!!.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                d("RELOCAL", "Local desc set, writing new offer to RTDB")
                                io.launch { writeOffer(sdp) }
                            }
                            override fun onSetFailure(e: String) { d("REOFFER_FAIL", e) }
                            override fun onCreateSuccess(s: SessionDescription?) {}
                            override fun onCreateFailure(e: String) {}
                        }, sdp)
                    }
                    override fun onCreateFailure(e: String) { d("REOFFER_FAIL", e) }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(e: String) {}
                }, mc)
        } catch (e: Exception) { d("REINIT_ERR", "Re-init failed: ${e.message}") }
    }

    fun dispose() {
        try {
            reqL?.let { FirebaseRepository.signalingRef.removeEventListener(it) }
            ansL?.let { FirebaseRepository.signalingRef.removeEventListener(it) }
            iceL?.let { FirebaseRepository.signalingRef.removeEventListener(it) }
            capturer?.dispose()
            pc?.close()
            pc?.dispose()
            vs?.dispose()
            factory?.dispose()
            egl?.release()
            d("DONE","Cleanup complete")
        } catch(e: Exception) {}
    }
}
