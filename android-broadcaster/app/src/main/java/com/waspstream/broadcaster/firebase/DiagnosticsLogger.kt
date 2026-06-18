package com.waspstream.broadcaster.firebase

import android.util.Log
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Logs WebRTC handshake diagnostics to Firebase RTDB under /diagnostics/android.
 * Each entry includes a timestamp and a step description.
 *
 * This allows the viewer dashboard to read live diagnostic data
 * alongside the video, making it easy to trace where the handshake fails.
 *
 * All methods are fire-and-forget (non-suspending), launching their own
 * coroutines internally so they can be called from any context including
 * WebRTC callbacks and SdpObserver implementations.
 */
object DiagnosticsLogger {

    private const val TAG = "DiagLogger"
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Log a diagnostic event to RTDB /diagnostics/android/{step}.
     * Also writes to Android logcat for local debugging.
     *
     * Non-suspending — safe to call from any thread or callback.
     */
    fun log(step: String, message: String, data: Map<String, Any?> = emptyMap()) {
        val entry = linkedMapOf<String, Any?>(
            "timestamp" to ServerValue.TIMESTAMP,
            "step" to step,
            "message" to message
        ).also { it.putAll(data) }

        scope.launch {
            try {
                val ref = FirebaseRepository.databaseRef
                    .child("diagnostics")
                    .child("android")
                    .child(step.replace(" ", "_").replace("/", "_"))

                ref.setValue(entry)
                Log.i(TAG, "[$step] $message")
            } catch (e: Exception) {
                // Don't let logging failures break the app
                Log.w(TAG, "Failed to write diagnostic log for step=$step", e)
            }
        }
    }

    /**
     * Log an error event (no exception thrown, just a descriptive error).
     */
    fun logError(step: String, errorMessage: String, details: Map<String, Any?> = emptyMap()) {
        log(step, "ERROR: $errorMessage", details + mapOf("level" to "error"))
    }

    /**
     * Clear all diagnostics under /diagnostics/android (called on setup).
     */
    fun clear() {
        scope.launch {
            try {
                FirebaseRepository.databaseRef
                    .child("diagnostics")
                    .child("android")
                    .removeValue()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear diagnostics", e)
            }
        }
    }
}
