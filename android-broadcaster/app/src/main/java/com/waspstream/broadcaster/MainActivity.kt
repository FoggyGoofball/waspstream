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
import com.waspstream.broadcaster.firebase.DiagnosticsLogger
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
    private var motionDetector: MotionDetector? = null
    private lateinit var batteryMonitor: BatteryThermalMonitor
    private var stateMachine: StreamStateMachine? = null
    private var webRTCManager: WebRTCManager? = null
    private var signalingClient: SignalingClient? = null
    private var localPreview: org.webrtc.SurfaceViewRenderer? = null

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

        // Request permissions FIRST — foreground service requires granted permissions on Android 14+
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
     *
     * TEST MODE: Motion detection and state machine are bypassed.
     * The broadcaster immediately starts a WebRTC live stream.
     * CameraX is started briefly to check the camera HAL works,
     * then stopped and WebRTC's own capturer takes over.
     */
    private fun initializeSystem() {
        // Start foreground service AFTER permissions are granted
        startForegroundService()

        lifecycleScope.launch {
            // 1. Firebase anonymous auth
            val authSuccess = FirebaseRepository.signInAnonymously()
            if (!authSuccess) {
                Toast.makeText(this@MainActivity, "Firebase auth failed", Toast.LENGTH_LONG).show()
                return@launch
            }

            // 2. Initialize battery/thermal monitor (always on)
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

            // 3. Set stream to "live" immediately (bypass state machine)
            try {
                FirebaseRepository.updateState("live")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Firebase write failed (expected if Anonymous Auth not enabled)", e)
            }

            // 4. Directly set up WebRTC — no motion detection, no state machine
            setupWebRTC()

            runOnUiThread {
                statusText.setTextColor(android.graphics.Color.GREEN)
                statusText.setText(R.string.status_active)
            }
        }
    }

    /**
     * Set up WebRTC peer connection and begin signaling.
     *
     * CameraX must be stopped first to release the camera HAL,
     * otherwise WebRTC's native capturer will crash.
     */
    private suspend fun setupWebRTC() {
        DiagnosticsLogger.log("SETUP_START", "WebRTC setup beginning")
        DiagnosticsLogger.clear() // clear previous diagnostics

        // Step 1: Stop CameraX (if it was started — test mode skips it)
        DiagnosticsLogger.log("STEP_1", "CameraX stop check (may not be running in test mode)")
        try {
            if (::cameraManager.isInitialized) {
                cameraManager.stop()
                DiagnosticsLogger.log("STEP_1_OK", "CameraX stopped successfully")
            } else {
                DiagnosticsLogger.log("STEP_1_SKIP", "CameraX not started, skipping stop")
            }
        } catch (e: Exception) {
            DiagnosticsLogger.logError("STEP_1_FAIL", "CameraX stop failed", mapOf("error" to (e.message ?: "")))
        }

        // Step 2: Dispose previous WebRTC state
        DiagnosticsLogger.log("STEP_2", "Cleaning up previous signaling + WebRTC")
        signalingClient?.cleanup()
        webRTCManager?.dispose()

        // Step 3: Create fresh client + manager
        DiagnosticsLogger.log("STEP_3", "Creating SignalingClient + WebRTCManager")
        signalingClient = SignalingClient()
        webRTCManager = WebRTCManager(
            context = this@MainActivity,
            onIceCandidate = { candidate ->
                lifecycleScope.launch {
                    DiagnosticsLogger.log("ICE_SEND", "Sending ICE candidate from broadcaster",
                        mapOf("sdpMLineIndex" to candidate.sdpMLineIndex, "sdpMid" to candidate.sdpMid))
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
                lifecycleScope.launch {
                    DiagnosticsLogger.log("CONN_STATE", "PeerConnection state change: $state")
                }
            }
        )

        // Step 4: Initialize WebRTC native layer
        DiagnosticsLogger.log("STEP_4", "Initializing WebRTC PeerConnectionFactory")
        try {
            webRTCManager?.initialize()
            DiagnosticsLogger.log("STEP_4_OK", "PeerConnectionFactory initialized")
        } catch (e: Exception) {
            DiagnosticsLogger.logError("STEP_4_FAIL", "PeerConnectionFactory init failed",
                mapOf("error" to (e.message ?: ""), "exception_type" to e.javaClass.simpleName))
            return  // cannot continue without WebRTC stack
        }

        // Step 5: Create camera capturer for WebRTC
        DiagnosticsLogger.log("STEP_5", "Creating camera capturer for WebRTC")
        var videoCapturer: org.webrtc.CameraVideoCapturer? = null
        try {
            videoCapturer = createCameraVideoCapturer()
            DiagnosticsLogger.log("STEP_5_OK", "Camera capturer created")
        } catch (e: Exception) {
            DiagnosticsLogger.logError("STEP_5_FAIL", "Camera capturer creation failed",
                mapOf("error" to (e.message ?: ""), "exception_type" to e.javaClass.simpleName))
            return
        }

        // Step 6: Create a local preview SurfaceViewRenderer for the Android screen
        DiagnosticsLogger.log("STEP_5_PREVIEW", "Creating local preview SurfaceViewRenderer")
        val localPreview = org.webrtc.SurfaceViewRenderer(this@MainActivity)
        webRTCManager?.eglContext?.let { ctx ->
            localPreview.init(ctx, null)
        } ?: run {
            DiagnosticsLogger.logError("STEP_5_PREVIEW_FAIL", "EGL context null, cannot init local preview")
        }
        localPreview.setMirror(true)
        localPreview.setEnableHardwareScaler(true)

        // Add to the existing PreviewView as an overlay (replacing the CameraX preview)
        previewView.addView(localPreview, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Step 7: Create PeerConnection with media tracks and local preview
        DiagnosticsLogger.log("STEP_6", "Creating PeerConnection with video capturer + local preview")
        try {
            webRTCManager?.createPeerConnection(
                videoCapturer = videoCapturer!!,
                localPreview = localPreview
            )
            DiagnosticsLogger.log("STEP_6_OK", "PeerConnection created successfully")
        } catch (e: Exception) {
            DiagnosticsLogger.logError("STEP_6_FAIL", "PeerConnection creation failed",
                mapOf("error" to (e.message ?: ""), "exception_type" to e.javaClass.simpleName))
            return
        }

        // Step 7: Create and send SDP offer
        DiagnosticsLogger.log("STEP_7", "Creating SDP offer")
        webRTCManager?.createOffer { sessionDescription ->
            DiagnosticsLogger.log("STEP_7_OFFER", "SDP offer created, sending to RTDB",
                mapOf("sdp_type" to sessionDescription.type.canonicalForm(), "sdp_length" to sessionDescription.description.length))
            lifecycleScope.launch {
                try {
                    signalingClient?.sendOffer(mapOf(
                        "type" to sessionDescription.type.canonicalForm(),
                        "sdp" to sessionDescription.description
                    ))
                    DiagnosticsLogger.log("STEP_7_OFFER_SENT", "Offer written to RTDB /signaling/offer")

                    // Wait for answer
                    DiagnosticsLogger.log("STEP_8", "Waiting for viewer answer (timeout: 30s)")
                    val answer = signalingClient?.waitForAnswer()
                    if (answer != null) {
                        DiagnosticsLogger.log("STEP_8_ANSWER_RECEIVED", "Viewer answer received",
                            mapOf("sdp_type" to (answer["type"] ?: ""), "sdp_length" to (answer["sdp"]?.length ?: 0)))
                        webRTCManager?.setRemoteAnswer(SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(answer["type"] ?: "answer"),
                            answer["sdp"] ?: ""
                        ))
                        DiagnosticsLogger.log("STEP_8_OK", "Remote description set successfully")
                    } else {
                        DiagnosticsLogger.logError("STEP_8_TIMEOUT", "Timeout waiting for viewer answer (30s)")
                    }
                } catch (e: Exception) {
                    DiagnosticsLogger.logError("STEP_7_FAIL", "Offer/answer exchange failed",
                        mapOf("error" to (e.message ?: ""), "exception_type" to e.javaClass.simpleName))
                }
            }
        }

        // Step 9: Listen for viewer ICE candidates
        DiagnosticsLogger.log("STEP_9", "Starting ICE candidate listener for viewer")
        signalingClient?.listenForViewerIceCandidates { candidate ->
            if (candidate != null) {
                lifecycleScope.launch {
                    DiagnosticsLogger.log("ICE_RECEIVED", "Received ICE candidate from viewer",
                        mapOf("sdpMid" to (candidate["sdpMid"] ?: ""), "sdpMLineIndex" to (candidate["sdpMLineIndex"] ?: "")))
                }
                webRTCManager?.addIceCandidate(IceCandidate(
                    candidate["sdpMid"] as? String ?: "video",
                    (candidate["sdpMLineIndex"] as? Number)?.toInt() ?: 0,
                    candidate["candidate"] as? String ?: ""
                ))
            }
        }

        DiagnosticsLogger.log("SETUP_COMPLETE", "WebRTC setup finished — waiting for viewer")
        runOnUiThread {
            statusText.setTextColor(android.graphics.Color.GREEN)
            statusText.setText(R.string.status_active)
        }
    }
    /**
     * Tear down WebRTC peer connection and clean up signaling.
     * Restart CameraX afterwards so motion detection resumes in IDLE mode.
     */
    private suspend fun teardownWebRTC() {
        DiagnosticsLogger.log("TEARDOWN_START", "WebRTC teardown beginning")
        webRTCManager?.dispose()
        webRTCManager = null
        signalingClient?.cleanup()
        signalingClient = null
        FirebaseRepository.clearSignaling()

        // Re-acquire the camera with CameraX for viewfinder and motion detection
        try {
            cameraManager.start()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error restarting CameraX after WebRTC", e)
        }

        runOnUiThread {
            statusText.setTextColor(android.graphics.Color.YELLOW)
            statusText.setText(R.string.status_idle)
        }
        DiagnosticsLogger.log("TEARDOWN_DONE", "WebRTC torn down, CameraX restarted")
    }

    /**
     * Create a CameraVideoCapturer for the WebRTC video source.
     *
     * Uses Camera1Enumerator for maximum compatibility (Camera2 HAL can be
     * buggy on older Mediatek/Android 8.1 devices and causes SIGABRT in
     * WebRTC's native layer). Falls back to Camera2 if Camera1 is unavailable.
     *
     * Uses back camera by default, or front camera as fallback.
     */
    private fun createCameraVideoCapturer(): org.webrtc.CameraVideoCapturer {
        // Camera1Enumerator has better compatibility on older Android (8.1 / Mediatek)
        try {
            val enumerator = org.webrtc.Camera1Enumerator(false) // false = no captureToTexture
            val deviceNames = enumerator.deviceNames
            if (deviceNames.isNotEmpty()) {
                val deviceName = deviceNames.find { enumerator.isBackFacing(it) }
                    ?: deviceNames.first()
                android.util.Log.d("MainActivity", "Using Camera1Enumerator, device=$deviceName")
                val capturer = enumerator.createCapturer(deviceName, null)
                webRTCManager?.setVideoCapturer(capturer)
                return capturer
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Camera1Enumerator failed, trying Camera2", e)
        }

        // Fallback to Camera2Enumerator
        val enumerator = org.webrtc.Camera2Enumerator(this@MainActivity)
        val deviceNames = enumerator.deviceNames

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
        stateMachine?.stop()
        batteryMonitor.stop()
        batteryMonitor.unregisterTelemetryPushReceiver()
        if (::cameraManager.isInitialized) {
            cameraManager.stop()
        }
        webRTCManager?.dispose()
    }
}
