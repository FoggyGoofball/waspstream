package com.waspstream.broadcaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.waspstream.broadcaster.firebase.DiagnosticsLogger
import com.waspstream.broadcaster.firebase.FirebaseRepository
import com.waspstream.broadcaster.webrtc.WebRTCManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that manages WebRTC streaming.
 * - ACTION_START: initialize WebRTC, start camera, begin streaming
 * - ACTION_STOP: clean up WebRTC, stopSelf()
 *
 * Uses START_NOT_STICKY so it never auto-restarts after being stopped.
 */
class BroadcastService : Service() {

    companion object {
        const val ACTION_START = "com.waspstream.broadcaster.START"
        const val ACTION_STOP = "com.waspstream.broadcaster.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "waspstream_broadcast"
        private const val TAG = "BroadcastService"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var webRTCManager: WebRTCManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "ACTION_START received")
                startForeground(NOTIFICATION_ID, buildNotification())
                acquireWakeLock()
                startStreaming()
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received — stopping service")
                DiagnosticsLogger.log("SVC_STOP", "Stopping broadcast service")
                stopStreaming()
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseWakeLock()
                stopSelf()
            }
            else -> {
                // On cold start (null intent) just show notification
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        // Don't auto-restart after being stopped
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        releaseWakeLock()
    }

    // ── Streaming ────────────────────────────────────────────────────────

    private fun startStreaming() {
        scope.launch {
            try {
                DiagnosticsLogger.log("SVC_START", "Broadcast service starting")

                // Authenticate with Firebase (Main-safe)
                val authenticated = FirebaseRepository.signInAnonymously()
                if (!authenticated) {
                    DiagnosticsLogger.logError("SVC_AUTH", "Firebase auth failed")
                    return@launch
                }
                DiagnosticsLogger.log("SVC_AUTH_OK", "Firebase authenticated")

                // Clear any stale signaling data
                FirebaseRepository.clearSignaling()

                // Update state to live
                FirebaseRepository.updateState("live")
                DiagnosticsLogger.log("SVC_LIVE", "Stream state set to live")

            } catch (e: Exception) {
                DiagnosticsLogger.logError("SVC_FAIL", "Setup failed: ${e.message}")
                Log.e(TAG, "startStreaming setup failed", e)
                return@launch
            }

            // WebRTC init (EGL, camera, PeerConnection) on IO thread
            withContext(Dispatchers.IO) {
                try {
                    val wm = WebRTCManager(
                        ctx = this@BroadcastService,
                        diag = { step, msg, data -> DiagnosticsLogger.log(step, msg, data) },
                        onConnState = { state ->
                            DiagnosticsLogger.log("SVC_CONN", "WebRTC connection: $state")
                        }
                    )
                    webRTCManager = wm
                    wm.start()
                } catch (e: Exception) {
                    DiagnosticsLogger.logError("SVC_FAIL", "WebRTC start failed: ${e.message}")
                    Log.e(TAG, "WebRTC start failed", e)
                }
            }
        }
    }

    private fun stopStreaming() {
        webRTCManager?.dispose()
        webRTCManager = null

        scope.launch {
            try {
                FirebaseRepository.markOffline()
                DiagnosticsLogger.log("SVC_OFFLINE", "Stream state set to offline")
            } catch (_: Exception) {}
        }
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ── Wake lock ────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "waspstream:WakeLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
