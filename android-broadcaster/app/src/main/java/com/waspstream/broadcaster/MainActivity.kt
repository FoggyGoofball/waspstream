package com.waspstream.broadcaster

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.waspstream.broadcaster.camera.CameraManager
import com.waspstream.broadcaster.camera.MotionDetector
import com.waspstream.broadcaster.firebase.FirebaseRepository
import com.waspstream.broadcaster.state.StreamStateMachine
import com.waspstream.broadcaster.telemetry.BatteryThermalMonitor
import com.waspstream.broadcaster.webrtc.SignalingClient
import com.waspstream.broadcaster.webrtc.WebRTCManager
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Main orchestrator activity for the WaspStream Broadcaster.
 *
 * Lifecycle:
 * 1. Request camera permissions
 * 2. Initialize Firebase (anonymous auth)
 * 3. Start BatteryThermalMonitor for telemetry
 * 4. Start CameraManager with MotionDetector
 * 5. Start StreamStateMachine (IDLE → ACTIVE transitions)
 * 6. Start foreground service for 24/7 operation
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var temperatureText: TextView
    private lateinit var batteryText: TextView
    private lateinit var motionIndicator: View

    private lateinit var cameraManager: CameraManager
    private lateinit var motionDetector: MotionDetector
    private lateinit var batteryMonitor: BatteryThermalMonitor
    private lateinit var stateMachine: StreamStateMachine
    private var webRTCManager: WebRTCManager? = null
    private var signalingClient: SignalingClient? = null

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeSystem()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        temperatureText = findViewById(R.id.temperatureText)
        batteryText = findViewById(R.id.batteryText)
        motionIndicator = findViewById(R.id.motionIndicator)

        // Start foreground service
        startForegroundService()

        // Request permissions
        requestPermissions()
    }

    private fun startForegroundService() {
        val serviceIntent = Intent(this, BroadcastService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_CAMERA)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            initializeSystem()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /**
     * Initialize all system components after permissions are granted.
     */
    private fun initializeSystem() {
        lifecycleScope.launch {
            // 1. Firebase anonymous auth
            val authSuccess = FirebaseRepository.signInAnonymously()
            if (!authSuccess) {
                Toast.makeText(this@MainActivity, "Firebase auth failed", Toast.LENGTH_LONG).show()
                return@launch
            }

            // 2. Initialize motion detector
            motionDetector = MotionDetector { motion ->
                runOnUiThread {
                    stateMachine.onMotionChange(motion)
                    updateMotionIndicator(motion)
                }
            }

            // 3. Initialize camera manager
            cameraManager = CameraManager(
                context = this@MainActivity,
                lifecycleOwner = this@MainActivity,
                previewView = previewView,
                motionDetector = motionDetector
            )
            cameraManager.onSnapshotCaptured = { imageData ->
                lifecycleScope.launch {
                    FirebaseRepository.updateState(
                        status = if (stateMachine.currentState == StreamStateMachine.State.ACTIVE) "live" else "offline",
                        imageData = imageData
                    )
                }
            }
            cameraManager.start()

            // 4. Initialize battery/thermal monitor
            batteryMonitor = BatteryThermalMonitor(
                context = this@MainActivity,
                onTemperatureAlert = { celsius ->
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "⚠️ High temperature: %.1f°C".format(celsius),
                            Toast.LENGTH_LONG
                        ).show()
                        temperatureText.setTextColor(android.graphics.Color.RED)
                    }
                }
            )
            batteryMonitor.registerTelemetryPushReceiver()
            batteryMonitor.start()

            // 5. Initialize state machine
            stateMachine = StreamStateMachine(
                onEnterActive = { setupWebRTC() },
                onExitActive = { teardownWebRTC() },
                onCaptureSnapshot = { cameraManager.captureSnapshot() }
            )
            stateMachine.start()

            // 6. Initial state update
            FirebaseRepository.updateState("offline")

            runOnUiThread {
                statusText.setTextColor(android.graphics.Color.YELLOW)
                statusText.setText(R.string.status_idle)
            }
        }
    }

    /**
     * Set up WebRTC peer connection and begin signaling.
     */
    private suspend fun setupWebRTC() {
        signalingClient?.cleanup()
        webRTCManager?.dispose()

        signalingClient = SignalingClient()
        webRTCManager = WebRTCManager(
            context = this@MainActivity,
            onIceCandidate = { candidate ->
                lifecycleScope.launch {
                    signalingClient?.sendIceCandidate(mapOf(
                        "candidate" to candidate.sdp,
                        "sdpMLineIndex" to candidate.sdpMLineIndex,
                        "sdpMid" to candidate.sdpMid
                    ))
                }
            },
            onConnectionStateChanged = { state ->
                runOnUiThread {
                    statusText.text = "WebRTC: $state"
                }
            }
        )

        webRTCManager?.initialize()
        webRTCManager?.createPeerConnection(
            videoCapturer = createCameraVideoCapturer(),
            localPreview = null // No local preview for now
        )

        // Create and send offer
        webRTCManager?.createOffer { sessionDescription ->
            lifecycleScope.launch {
                signalingClient?.sendOffer(mapOf(
                    "type" to sessionDescription.type.canonicalForm(),
                    "sdp" to sessionDescription.description
                ))

                // Wait for answer
                val answer = signalingClient?.waitForAnswer()
                if (answer != null) {
                    webRTCManager?.setRemoteAnswer(SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(answer["type"] ?: "answer"),
                        answer["sdp"] ?: ""
                    ))
                }
            }
        }

        // Listen for ICE candidates from viewer
        signalingClient?.listenForViewerIceCandidates { candidate ->
            if (candidate != null) {
                webRTCManager?.addIceCandidate(IceCandidate(
                    candidate["sdpMid"] as? String ?: "video",
                    (candidate["sdpMLineIndex"] as? Number)?.toInt() ?: 0,
                    candidate["candidate"] as? String ?: ""
                ))
            }
        }

        runOnUiThread {
            statusText.setTextColor(android.graphics.Color.GREEN)
            statusText.setText(R.string.status_active)
        }
    }

    /**
     * Tear down WebRTC peer connection and clean up signaling.
     */
    private suspend fun teardownWebRTC() {
        webRTCManager?.dispose()
        webRTCManager = null
        signalingClient?.cleanup()
        signalingClient = null
        FirebaseRepository.clearSignaling()

        runOnUiThread {
            statusText.setTextColor(android.graphics.Color.YELLOW)
            statusText.setText(R.string.status_idle)
        }
    }

    /**
     * Create a CameraVideoCapturer for the WebRTC video source.
     * Uses the front-facing camera by default, or back camera as fallback.
     */
    private fun createCameraVideoCapturer(): org.webrtc.CameraVideoCapturer {
        val enumerator = org.webrtc.Camera2Enumerator(this@MainActivity)
        val deviceNames = enumerator.deviceNames

        // Try back camera first, fall back to any camera
        val deviceName = deviceNames.find { enumerator.isBackFacing(it) }
            ?: deviceNames.firstOrNull()
            ?: throw RuntimeException("No camera found")

        val capturer = enumerator.createCapturer(deviceName, null)
        webRTCManager?.setVideoCapturer(capturer)
        return capturer
    }

    private fun updateMotionIndicator(motion: Boolean) {
        motionIndicator.setBackgroundResource(
            if (motion) R.drawable.indicator_active
            else R.drawable.indicator_idle
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            FirebaseRepository.markOffline()
        }
        stateMachine.stop()
        batteryMonitor.stop()
        batteryMonitor.unregisterTelemetryPushReceiver()
        cameraManager.stop()
        webRTCManager?.dispose()
    }
}
