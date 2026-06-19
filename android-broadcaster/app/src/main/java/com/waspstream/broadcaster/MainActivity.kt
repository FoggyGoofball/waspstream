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
import com.waspstream.broadcaster.firebase.FirebaseRepository
import kotlinx.coroutines.launch

/**
 * Main activity launches the BroadcastService (foreground) that handles
 * CameraX preview, battery/thermal monitoring, optional motion-triggered
 * snapshots, and WebRTC streaming.
 *
 * This activity shows a simple status UI and a "Start" button.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var startButton: View
    private lateinit var stopButton: View
    private var previewView: PreviewView? = null

    // Runtime permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            statusText.text = "Permissions granted"
            tryStartService()
        } else {
            Toast.makeText(this, "Camera & storage permissions required", Toast.LENGTH_LONG).show()
            statusText.text = "Permissions denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        previewView = findViewById(R.id.previewView)

        statusText.text = "Ready"
        detailText.text = "Press Start to begin monitoring"
        stopButton.isEnabled = false

        startButton.setOnClickListener { checkPermissionsAndStart() }
        stopButton.setOnClickListener { stopService() }

        // Check if service is already running
        if (isServiceRunning()) {
            showActiveState()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isServiceRunning()) showActiveState()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>().apply {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            tryStartService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun tryStartService() {
        lifecycleScope.launch {
            try {
                statusText.text = "Authenticating..."
                startButton.isEnabled = false

                val authenticated = FirebaseRepository.signInAnonymously()
                if (!authenticated) {
                    statusText.text = "Auth failed"
                    startButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Firebase auth failed", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val intent = Intent(this@MainActivity, BroadcastService::class.java)
                intent.action = BroadcastService.ACTION_START

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }

                showActiveState()
                Toast.makeText(this@MainActivity, "Broadcast started", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                statusText.text = "Error: ${e.message?.take(50)}"
                startButton.isEnabled = true
            }
        }
    }

    private fun stopService() {
        val intent = Intent(this, BroadcastService::class.java)
        intent.action = BroadcastService.ACTION_STOP
        startService(intent)
        showIdleState()
    }

    private fun showActiveState() {
        statusText.text = "Running"
        detailText.text = "Broadcasting"
        startButton.isEnabled = false
        stopButton.isEnabled = true
    }

    private fun showIdleState() {
        statusText.text = "Stopped"
        detailText.text = "Press Start to begin monitoring"
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        return manager.getRunningServices(Integer.MAX_VALUE).any {
            it.service?.className == BroadcastService::class.java.name
        }
    }
}
