package com.waspstream.broadcaster.webrtc

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
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
        FirebaseRepository.writeOffer(offer)
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
     * Wait for the viewer's answer using a blocking get (coroutine-friendly).
     */
    suspend fun waitForAnswer(timeoutMs: Long = 30_000L): Map<String, String>? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val snapshot = FirebaseRepository.signalingRef.child("answer").get().await()
            @Suppress("UNCHECKED_CAST")
            val answer = snapshot.value as? Map<String, String>
            if (answer != null && answer["type"] == "answer") {
                return answer
            }
            kotlinx.coroutines.delay(500)
        }
        return null
    }

    /**
     * Clean up all signaling listeners.
     */
    fun cleanup() {
        answerListener?.let {
            FirebaseRepository.signalingRef.child("answer").removeEventListener(it)
        }
        viewerIceListener?.let {
            FirebaseRepository.signalingRef.child("candidates").child("viewer")
                .removeEventListener(it)
        }
    }
}
