package com.nerovision.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Auto-starts [WatchdogService] when the device boots.
 *
 * Accepts ACTION_BOOT_COMPLETED and the vendor-specific QUICKBOOT
 * intents used by HTC and some other OEMs.  All other actions are
 * silently ignored.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action !in ACCEPTED_ACTIONS) {
            Log.d(TAG, "Ignoring action: $action")
            return
        }

        Log.i(TAG, "Boot event received: $action — starting WatchdogService")

        try {
            val svcIntent = Intent(context, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WatchdogService on boot", e)
            NeroApp.recordCrash(TAG, "Failed to start WatchdogService on boot", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        private val ACCEPTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
