package com.waspstream.broadcaster.telemetry

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.waspstream.broadcaster.firebase.FirebaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Monitors battery level and device temperature by registering a BroadcastReceiver
 * for [Intent.ACTION_BATTERY_CHANGED] and pushing telemetry to Firebase RTDB every 60 seconds.
 *
 * Temperature threshold alarm: triggers if temperature > 400 (40.0°C).
 */
class BatteryThermalMonitor(
    private val context: Context,
    private val onTemperatureAlert: (temperatureCelsius: Float) -> Unit = {}
) {

    companion object {
        private const val TELEMETRY_INTERVAL_MS = 60_000L // 60 seconds
        private const val TEMP_THRESHOLD = 400 // 40.0°C in tenths of a degree
        private const val ACTION_PUSH_TELEMETRY = "com.waspstream.broadcaster.PUSH_TELEMETRY"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentBatteryLevel: Int = 0
    private var currentTemperature: Int = 0
    private var isAlarmActive = false

    // Receiver for battery state changes
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)

            if (level >= 0 && scale > 0) {
                currentBatteryLevel = (level * 100) / scale
            }
            if (temp >= 0) {
                currentTemperature = temp
            }
        }
    }

    /**
     * Start monitoring battery and temperature.
     * Registers the battery receiver and schedules periodic telemetry uploads.
     */
    fun start() {
        // Register for battery change events (sticky broadcast provides immediate values)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)

        // Schedule periodic telemetry push using AlarmManager
        scheduleTelemetryPush()
    }

    /**
     * Stop monitoring and clean up.
     */
    fun stop() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver already unregistered
        }
        cancelTelemetryPush()
    }

    private fun scheduleTelemetryPush() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_PUSH_TELEMETRY).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + TELEMETRY_INTERVAL_MS,
            TELEMETRY_INTERVAL_MS,
            pendingIntent
        )
        isAlarmActive = true
    }

    private fun cancelTelemetryPush() {
        if (!isAlarmActive) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_PUSH_TELEMETRY).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        isAlarmActive = false
    }

    /**
     * Internal receiver to handle the periodic alarm for pushing telemetry to Firebase.
     */
    private val telemetryPushReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PUSH_TELEMETRY) {
                pushTelemetryToFirebase()
            }
        }
    }

    /**
     * Push current telemetry values to Firebase and check for temperature alerts.
     */
    private fun pushTelemetryToFirebase() {
        scope.launch {
            try {
                FirebaseRepository.pushTelemetry(currentBatteryLevel, currentTemperature)
            } catch (e: Exception) {
                // Log silently — don't crash on telemetry failure
                android.util.Log.e("BatteryThermalMonitor", "Failed to push telemetry", e)
            }
        }

        // Check temperature threshold
        if (currentTemperature > TEMP_THRESHOLD) {
            val celsius = currentTemperature / 10.0f
            onTemperatureAlert(celsius)
        }
    }

    /**
     * Register the telemetry push receiver (call in onCreate of the servicing component).
     */
    fun registerTelemetryPushReceiver() {
        val filter = IntentFilter(ACTION_PUSH_TELEMETRY)
        context.registerReceiver(telemetryPushReceiver, filter,
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Unregister the telemetry push receiver (call in onDestroy).
     */
    fun unregisterTelemetryPushReceiver() {
        try {
            context.unregisterReceiver(telemetryPushReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered
        }
    }
}
