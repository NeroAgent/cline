package com.nerovision.production

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class NeroBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }
        NeroJsonLog.info(context, "NeroBootReceiver", "Boot receiver activated", mapOf("action" to intent.action))
        listOf(
            NeroBridgeService::class.java,
            NeroOverlayService::class.java,
            NeroMediaProjectionService::class.java,
            NeroAudioCaptureService::class.java,
            NeroWatchdogService::class.java,
        ).forEach { serviceClass ->
            ContextCompat.startForegroundService(context, Intent(context, serviceClass))
        }
    }
}
