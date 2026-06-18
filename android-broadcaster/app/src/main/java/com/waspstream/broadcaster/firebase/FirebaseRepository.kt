package com.waspstream.broadcaster.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * Singleton repository wrapping all Firebase operations for the Broadcaster node.
 * Handles RTDB reads/writes and anonymous authentication.
 * Images are stored as base64-encoded strings in RTDB (no Cloud Storage needed).
 */
object FirebaseRepository {

    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    // RTDB Reference paths
    val stateRef: DatabaseReference = database.getReference("state")
    val telemetryRef: DatabaseReference = database.getReference("telemetry")
    val signalingRef: DatabaseReference = database.getReference("signaling")

    /**
     * Authenticate anonymously so the broadcaster can write to the database.
     */
    suspend fun signInAnonymously(): Boolean {
        return try {
            auth.signInAnonymously().await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Update the stream status. If imageData (base64 encoded JPEG) is provided,
     * it will also update latest_image_data. Only overwrite image when motion detected.
     */
    suspend fun updateState(status: String, imageData: String? = null) {
        val updates = mutableMapOf<String, Any>(
            "stream_status" to status,
            "last_updated" to System.currentTimeMillis()
        )
        if (imageData != null) {
            updates["latest_image"] = imageData
        }
        stateRef.updateChildren(updates).await()
    }

    /**
     * Push telemetry data (battery level and temperature) to RTDB.
     * Temperature is in tenths of a degree Celsius (e.g., 365 = 36.5°C).
     */
    suspend fun pushTelemetry(batteryLevel: Int, temperature: Int) {
        val telemetry = mapOf<String, Any>(
            "battery_level" to batteryLevel,
            "temperature" to temperature,
            "last_updated" to System.currentTimeMillis()
        )
        telemetryRef.setValue(telemetry).await()
    }

    /**
     * Write the WebRTC SDP offer to the signaling path.
     */
    suspend fun writeOffer(offer: Map<String, String>) {
        signalingRef.child("offer").setValue(offer).await()
    }

    /**
     * Write the WebRTC SDP answer to the signaling path.
     */
    suspend fun writeAnswer(answer: Map<String, String>) {
        signalingRef.child("answer").setValue(answer).await()
    }

    /**
     * Push an ICE candidate from the broadcaster to the signaling path.
     */
    suspend fun pushBroadcasterIceCandidate(candidate: Map<String, Any?>) {
        signalingRef.child("candidates").child("broadcaster")
            .push().setValue(candidate).await()
    }

    /**
     * Clear signaling data when transitioning between states.
     */
    suspend fun clearSignaling() {
        signalingRef.removeValue().await()
    }

    /**
     * Listen for changes to the viewer's answer.
     */
    fun onAnswerChanged(listener: (Map<String, String>?) -> Unit) {
        signalingRef.child("answer").get().addOnSuccessListener { snapshot ->
            @Suppress("UNCHECKED_CAST")
            val answer = snapshot.value as? Map<String, String>
            listener(answer)
        }
    }

    /**
     * Listen for incoming ICE candidates from the viewer.
     */
    fun onViewerIceCandidate(listener: (Map<String, Any?>?) -> Unit) {
        signalingRef.child("candidates").child("viewer")
            .addChildEventListener(object : com.google.firebase.database.ChildEventListener {
                override fun onChildAdded(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                    @Suppress("UNCHECKED_CAST")
                    val candidate = snapshot.value as? Map<String, Any?>
                    listener(candidate)
                }
                override fun onChildChanged(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: com.google.firebase.database.DataSnapshot) {}
                override fun onChildMoved(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
    }

    /**
     * Remove the stream status from RTDB on shutdown.
     */
    suspend fun markOffline() {
        stateRef.child("stream_status").setValue("offline").await()
    }
}
