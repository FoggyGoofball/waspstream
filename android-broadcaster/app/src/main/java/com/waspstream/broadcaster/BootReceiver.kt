package com.waspstream.broadcaster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Automatically starts the MainActivity after a device reboot.
 * This ensures the Broadcaster resumes 24/7 operation without manual intervention.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }
}
