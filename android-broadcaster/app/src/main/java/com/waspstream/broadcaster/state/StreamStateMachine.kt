package com.waspstream.broadcaster.state

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.waspstream.broadcaster.camera.CameraManager
import com.waspstream.broadcaster.firebase.FirebaseRepository
import kotlinx.coroutines.*

/**
 * Manages the Broadcaster's state machine — IDLE ↔ ACTIVE.
 *
 * STATE A: IDLE (Colony Dormant)
 *   - WebRTC torn down, static snapshots every 5 minutes
 *   - stream_status: "offline", latest_image updated in RTDB
 *
 * STATE B: ACTIVE (Motion Detected)
 *   - WebRTC initialized, signaling for viewer connection
 *   - stream_status: "live"
 *   - Cooldown: 60 seconds of no motion → return to IDLE
 */
class StreamStateMachine(
    private val onEnterActive: suspend () -> Unit,
    private val onExitActive: suspend () -> Unit,
    private val onCaptureSnapshot: () -> Unit
) {

    companion object {
        private const val TAG = "StreamStateMachine"
        private const val SNAPSHOT_INTERVAL_MS = 300_000L // 5 minutes
        private const val COOLDOWN_MS = 60_000L          // 60 seconds
    }

    enum class State { IDLE, ACTIVE }

    @Volatile
    var currentState: State = State.IDLE
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Timer for periodic snapshots during IDLE
    private var snapshotTimer: Runnable? = null

    // Timer for motion cooldown during ACTIVE
    private var cooldownTimer: Runnable? = null

    // Track motion state
    private var motionDetected = false

    /**
     * Called by the MotionDetector when motion state changes.
     */
    fun onMotionChange(motion: Boolean) {
        motionDetected = motion

        when (currentState) {
            State.IDLE -> {
                if (motion) {
                    Log.d(TAG, "Motion detected while IDLE → transitioning to ACTIVE")
                    transitionToActive()
                }
            }
            State.ACTIVE -> {
                if (motion) {
                    // Motion detected while active — reset cooldown timer
                    resetCooldownTimer()
                } else {
                    // No motion — start cooldown if not already running
                    if (cooldownTimer == null) {
                        startCooldownTimer()
                    }
                }
            }
        }
    }

    /**
     * Transition from IDLE to ACTIVE.
     */
    private fun transitionToActive() {
        currentState = State.ACTIVE
        stopSnapshotTimer()

        scope.launch {
            try {
                FirebaseRepository.updateState("live")
                onEnterActive()
            } catch (e: Exception) {
                Log.e(TAG, "Error entering ACTIVE state", e)
                // Fall back to IDLE if setup fails
                currentState = State.IDLE
                startSnapshotTimer()
            }
        }
    }

    /**
     * Transition from ACTIVE to IDLE.
     */
    private fun transitionToIdle() {
        currentState = State.IDLE
        cancelCooldownTimer()

        scope.launch {
            try {
                onExitActive()
                FirebaseRepository.updateState("offline")

                // Capture an immediate snapshot to update the static image
                withContext(Dispatchers.Main) {
                    onCaptureSnapshot()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error entering IDLE state", e)
            }
        }

        startSnapshotTimer()
    }

    /**
     * Start the periodic snapshot timer for IDLE state.
     */
    private fun startSnapshotTimer() {
        stopSnapshotTimer()
        val timer = Runnable {
            onCaptureSnapshot()
            // Re-schedule
            startSnapshotTimer()
        }
        snapshotTimer = timer
        mainHandler.postDelayed(timer, SNAPSHOT_INTERVAL_MS)
    }

    /**
     * Stop the periodic snapshot timer.
     */
    private fun stopSnapshotTimer() {
        snapshotTimer?.let { mainHandler.removeCallbacks(it) }
        snapshotTimer = null
    }

    /**
     * Start the motion cooldown timer (60 seconds of no motion → return to IDLE).
     */
    private fun startCooldownTimer() {
        cancelCooldownTimer()
        val timer = Runnable {
            Log.d(TAG, "Cooldown expired — no motion for 60s, transitioning to IDLE")
            transitionToIdle()
        }
        cooldownTimer = timer
        mainHandler.postDelayed(timer, COOLDOWN_MS)
    }

    /**
     * Reset the cooldown timer (motion was detected again).
     */
    private fun resetCooldownTimer() {
        cancelCooldownTimer()
        startCooldownTimer()
    }

    /**
     * Cancel the cooldown timer.
     */
    private fun cancelCooldownTimer() {
        cooldownTimer?.let { mainHandler.removeCallbacks(it) }
        cooldownTimer = null
    }

    /**
     * Start the state machine in IDLE state with snapshot timer.
     */
    fun start() {
        currentState = State.IDLE
        startSnapshotTimer()
    }

    /**
     * Stop the state machine and clean up all timers.
     */
    fun stop() {
        stopSnapshotTimer()
        cancelCooldownTimer()
        scope.cancel()
    }
}
