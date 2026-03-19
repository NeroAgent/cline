package com.nerovision.production

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class NeroWatchdogService : Service() {
    private val running = AtomicBoolean(false)
    private var watchdogThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        NeroServiceRegistry.watchdog.set(this)
        startForeground(
            NeroContract.Notifications.WATCHDOG_ID,
            NeroNotifications.build(this, "NeroVision Watchdog", getString(R.string.notification_bootstrap_text)),
        )
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWatchdog()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        runCatching { watchdogThread?.join(800) }
        NeroServiceRegistry.watchdog.set(null)
        super.onDestroy()
    }

    private fun startWatchdog() {
        if (running.get()) {
            return
        }
        running.set(true)
        watchdogThread = Thread({
            while (running.get()) {
                val health = NeroServiceRegistry.healthJson()
                ensureService(NeroBridgeService::class.java, health.optBoolean("bridgeConnected"))
                ensureService(NeroOverlayService::class.java, health.optBoolean("overlayConnected"))
                ensureService(NeroMediaProjectionService::class.java, health.optBoolean("projectionConnected"))
                ensureService(NeroAudioCaptureService::class.java, health.optBoolean("audioConnected"))
                VersionedStorage.writeJson(this, "watchdog", "health", JSONObject().put("health", health))
                Thread.sleep(5_000)
            }
        }, "nero-watchdog").apply { start() }
    }

    private fun ensureService(serviceClass: Class<*>, running: Boolean) {
        if (running) {
            return
        }
        NeroJsonLog.warn(this, "NeroWatchdogService", "Restarting service", mapOf("service" to serviceClass.simpleName))
        ContextCompat.startForegroundService(this, Intent(this, serviceClass))
    }
}
