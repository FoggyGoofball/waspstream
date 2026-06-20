content = r'''package com.waspstream.broadcaster.webrtc

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

    private fun d(s: String, m: String, x: Map<String,Any?>? = null) { Log.d(TAG, "[$s] $m"); diag(s,m,x) }

    fun start() {
        try {
            d("INIT","Starting WebRTC on main thread")

            egl = EglBase.create(null, EglBase.CONFIG_PIXEL_BUFFER).apply {
                createDummyPbufferSurface()
                makeCurrent()
            }
            d("EGL","EGL with dummy pbuffer ok")

            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx)
                .setFieldTrials("").createInitializationOptions())
            val f = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext))
                .createPeerConnectionFactory()
            factory = f
            d("FAC","PeerConnectionFactory with encoder/decoder created")

            val cap = createCamera()
            capturer = cap
            d("CAM","Camera capturer created")

            vs = f.createVideoSource(cap.isScreencast)
            val sth = SurfaceTextureHelper.create("STH", egl!!.eglBaseContext)
            cap.initialize(sth, ctx, vs!!.capturerObserver)
            cap.startCapture(640, 480, 15)
            vt = f.createVideoTrack("v", vs!!).apply { setEnabled(true) }
            d("VID","Video track created")

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

            pc!!.addTransceiver(vt!!, RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
            pc!!.addTransceiver(at!!, RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
            d("TRK","Transceivers added send-only")

            d("OFFER","Creating SDP offer")
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
                })
        } catch(e: Exception) { d("ERR","Start: ${e.message}") }
    }

    private fun createCamera(): CameraVideoCapturer {
        val e = Camera1Enumerator(false)
        return e.createCapturer(e.deviceNames.firstOrNull() ?: throw RuntimeException("No camera"), null)
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
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
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
                    if (s.key == "iceCandidates" && s.value != null) {
                        @Suppress("UNCHECKED_CAST")
                        (s.value as? List<Map<String,Any>>)?.forEach { ice ->
                            val sd = ice["candidate"]?.toString() ?: return@forEach
                            val sm = ice["sdpMid"]?.toString() ?: return@forEach
                            val si = (ice["sdpMLineIndex"] as? Number)?.toInt() ?: return@forEach
                            pc?.addIceCandidate(IceCandidate(sm, si, sd))
                        }
                    }
                }
                override fun onChildChanged(s: DataSnapshot, p: String?) {}
                override fun onChildRemoved(s: DataSnapshot) {}
                override fun onChildMoved(s: DataSnapshot, p: String?) {}
                override fun onCancelled(e: DatabaseError) {}
            }
            FirebaseRepository.signalingRef.addChildEventListener(iceL!!)
        }
    }

    private inner class PCObs : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            d("ICE","Local: ${c.sdpMid}")
            io.launch {
                try {
                    FirebaseRepository.signalingRef.child("localIceCandidates").push()
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

    fun dispose() {
        try {
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
'''

with open(r'c:\Users\Admin\source\repos\waspstream\android-broadcaster\app\src\main\java\com\waspstream\broadcaster\webrtc\WebRTCManager.kt', 'w', newline='\n') as f:
    f.write(content)
