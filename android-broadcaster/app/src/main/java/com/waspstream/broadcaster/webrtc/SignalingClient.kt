package com.waspstream.broadcaster.webrtc

import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.waspstream.broadcaster.firebase.DiagnosticsLogger
import com.waspstream.broadcaster.firebase.FirebaseRepository
import kotlinx.coroutines.tasks.await

/**
 * Handles WebRTC signaling over Firebase Realtime Database.
 *
 * The Broadcaster acts as the Offerer:
 * 1. Creates an SDP Offer and writes it to /signaling/offer
 * 2. Listens for an SDP Answer at /signaling/answer
 * 3. Exchange ICE candidates via /signaling/candidates/{broadcaster,viewer}
 */
class SignalingClient {

    private var answerListener: ChildEventListener? = null
    private var viewerIceListener: ChildEventListener? = null

    /**
     * Write the local SDP offer to RTDB signaling path.
     */
    suspend fun sendOffer(offer: Map<String, String>) {
        DiagnosticsLogger.log("SIGNAL_OFFER", "Writing SDP offer to RTDB",
            mapOf("type" to (offer["type"] ?: ""), "sdp_length" to (offer["sdp"]?.length ?: 0)))
        FirebaseRepository.writeOffer(offer)
        DiagnosticsLogger.log("SIGNAL_OFFER_DONE", "SDP offer written to /signaling/offer")
    }

    /**
     * Write the local ICE candidate to RTDB.
     */
    suspend fun sendIceCandidate(candidate: Map<String, Any?>) {
        FirebaseRepository.pushBroadcasterIceCandidate(candidate)
    }

    /**
     * Start listening for the viewer's SDP answer.
     * The callback provides the answer map (type + sdp) or null if not found.
     */
    fun listenForAnswer(onAnswer: (Map<String, String>?) -> Unit) {
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                @Suppress("UNCHECKED_CAST")
                val answer = snapshot.value as? Map<String, String>
                Log.d("SignalingClient", "Answer child added: type=${answer?.get("type")}, hasSdp=${answer?.containsKey("sdp")}")
                onAnswer(answer)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }

        answerListener = listener
        FirebaseRepository.signalingRef.child("answer")
            .addChildEventListener(listener)
    }

    /**
     * Start listening for ICE candidates from the viewer.
     */
    fun listenForViewerIceCandidates(onCandidate: (Map<String, Any?>?) -> Unit) {
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                @Suppress("UNCHECKED_CAST")
                val candidate = snapshot.value as? Map<String, Any?>
                Log.d("SignalingClient", "Viewer ICE candidate received: $candidate")
                onCandidate(candidate)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }

        viewerIceListener = listener
        FirebaseRepository.signalingRef.child("candidates").child("viewer")
            .addChildEventListener(listener)
    }

    /**
     * Wait for the viewer's answer using a polling loop (coroutine-friendly).
     */
    suspend fun waitForAnswer(timeoutMs: Long = 30_000L): Map<String, String>? {
        DiagnosticsLogger.log("SIGNAL_WAIT_ANSWER", "Polling for viewer answer (timeout=${timeoutMs}ms)")
        val startTime = System.currentTimeMillis()
        var pollCount = 0
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            pollCount++
            val snapshot = FirebaseRepository.signalingRef.child("answer").get().await()
            @Suppress("UNCHECKED_CAST")
            val answer = snapshot.value as? Map<String, String>
            if (answer != null && answer["type"] == "answer") {
                DiagnosticsLogger.log("SIGNAL_ANSWER_OK", "Viewer answer received after ${System.currentTimeMillis() - startTime}ms ($pollCount polls)",
                    mapOf("type" to (answer["type"] ?: ""), "sdp_length" to (answer["sdp"]?.length ?: 0)))
                return answer
            }
            kotlinx.coroutines.delay(500)
        }
        DiagnosticsLogger.logError("SIGNAL_ANSWER_TIMEOUT", "Timed out after $timeoutMs ms ($pollCount polls), no answer from viewer")
        return null
    }

    /**
     * Clean up all signaling listeners.
     */
    fun cleanup() {
        Log.d("SignalingClient", "cleanup() — removing RTDB listeners")
        answerListener?.let {
            FirebaseRepository.signalingRef.child("answer").removeEventListener(it)
        }
        viewerIceListener?.let {
            FirebaseRepository.signalingRef.child("candidates").child("viewer")
                .removeEventListener(it)
        }
    }
}
