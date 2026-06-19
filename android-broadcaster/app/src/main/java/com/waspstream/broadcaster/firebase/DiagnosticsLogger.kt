package com.waspstream.broadcaster.firebase

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * In-memory buffered diagnostic logger.
 *
 * Rather than writing each log line individually to RTDB (which caused
 * excessive writes), entries are stored in an in-memory buffer and
 * flushed as a batch periodically or on demand.
 *
 * Call [flush] to write out the current buffer to RTDB.
 */
object DiagnosticsLogger {

    private const val MAX_ENTRIES = 200
    private val buffer = mutableListOf<Map<String, Any>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Log a diagnostic entry. Buffered in-memory until [flush] is called.
     */
    fun log(step: String, message: String, data: Map<String, Any?>? = null) {
        val entry = mutableMapOf<String, Any>(
            "ts" to System.currentTimeMillis(),
            "step" to step,
            "msg" to message,
            "level" to "info"
        )
        data?.forEach { (k, v) ->
            if (v != null) entry[k] = v
        }
        synchronized(buffer) {
            buffer.add(entry)
            if (buffer.size >= 50) {
                // Auto-flush when buffer fills
                scope.launch { flush() }
            }
        }
        println("[DIAG] $step: $message")
    }

    fun logError(step: String, message: String, data: Map<String, Any?>? = null) {
        val entry = mutableMapOf<String, Any>(
            "ts" to System.currentTimeMillis(),
            "step" to step,
            "msg" to message,
            "level" to "error"
        )
        data?.forEach { (k, v) ->
            if (v != null) entry[k] = v
        }
        synchronized(buffer) {
            buffer.add(entry)
            if (buffer.size >= 50) {
                scope.launch { flush() }
            }
        }
        System.err.println("[DIAG] ERROR: $step: $message")
    }

    /**
     * Flush the buffer to RTDB as a batch under /diagnostics/android.
     * Keeps only the last [MAX_ENTRIES] in the buffer after flush.
     */
    suspend fun flush() {
        val snapshot: List<Map<String, Any>>
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            snapshot = buffer.toList()
            buffer.clear()
        }

        try {
            val androidRef = FirebaseDatabase.getInstance().getReference("diagnostics/android")
            // Append new entries alongside existing ones
            val existing = (androidRef.get().await().value as? Map<*, *>)?.toMutableMap() ?: mutableMapOf()
            @Suppress("UNCHECKED_CAST")
            val logArray = (existing["log"] as? List<Map<String, Any>>)?.toMutableList() ?: mutableListOf()
            logArray.addAll(snapshot)
            // Keep only last MAX_ENTRIES
            val trimmed = logArray.takeLast(MAX_ENTRIES)
            androidRef.setValue(mapOf(
                "log" to trimmed,
                "count" to trimmed.size,
                "flushed_at" to System.currentTimeMillis()
            )).await()
        } catch (_: Exception) {
            // If flush fails, re-add entries to buffer
            synchronized(buffer) {
                buffer.addAll(snapshot)
                if (buffer.size > MAX_ENTRIES * 2) {
                    val excess = buffer.size - MAX_ENTRIES
                    repeat(excess) { buffer.removeFirstOrNull() }
                }
            }
        }
    }
}
