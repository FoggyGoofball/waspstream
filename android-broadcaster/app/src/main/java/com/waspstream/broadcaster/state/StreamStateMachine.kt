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

    enum class State { IDLE, ACTIVE }

    companion object {
        private const val TAG = "StreamStateMachine"
        private const val SNAPSHOT_INTERVAL_MS = 300_000L // 5 minutes
        private const val COOLDOWN_MS = 60_000L          // 60 seconds
        /** Minimum time the machine must stay in ACTIVE before it can return to IDLE.
         *  Prevents rapid IDLE↔ACTIVE flickering that breaks WebRTC initialization. */
        private const val MIN_HOLD_MS = 10_000L          // 10 seconds
    }

    @Volatile
    var currentState: State = State.IDLE
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Timer for periodic snapshots during IDLE
    private var snapshotTimer: Runnable? = null

    // Timer for motion cooldown during ACTIVE
    private var cooldownTimer: Runnable? = null

    // Timestamp when we entered ACTIVE state (for minimum hold enforcement)
    private var activeSinceMs = 0L

    // Track motion state
    private var motionDetected = false

    /**
     * Called by the MotionDetector when motion state changes.
     * The MotionDetector has a 2-second debounce, and this state machine
     * additionally enforces a MIN_HOLD_MS in ACTIVE state.
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
                    // No motion — only start cooldown if minimum hold time has elapsed
                    if (cooldownTimer == null && hasMinHoldElapsed()) {
                        startCooldownTimer()
                    }
                }
            }
        }
    }

    /**
     * Returns true if the minimum hold time in ACTIVE state has elapsed.
     * Prevents transitioning back to IDLE within MIN_HOLD_MS of entering ACTIVE.
     */
    private fun hasMinHoldElapsed(): Boolean {
        val elapsed = System.currentTimeMillis() - activeSinceMs
        if (elapsed < MIN_HOLD_MS) {
            Log.d(TAG, "Minimum hold not elapsed yet ($elapsed ms < $MIN_HOLD_MS ms)")
            return false
        }
        return true
    }

    /**
     * Transition from IDLE to ACTIVE.
     */
    private fun transitionToActive() {
        currentState = State.ACTIVE
        activeSinceMs = System.currentTimeMillis()
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
