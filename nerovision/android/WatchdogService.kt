package com.nerovision.assistant

import android.app.ActivityManager
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Foreground service that monitors the Python health endpoint and
 * restarts dead Android services.
 *
 * Runs a check every [WATCHDOG_INTERVAL_MS] milliseconds:
 * 1. Ping Python health endpoint on port 9001.
 * 2. Verify [OverlayService] and [AudioCaptureService] are running.
 * 3. Trigger GC when heap usage exceeds 85 %.
 */
class WatchdogService : Service() {

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var pythonMissCount = 0

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        handlerThread = HandlerThread("WatchdogThread").also { it.start() }
        handler = Handler(handlerThread!!.looper)
        handler?.post(checkRunnable)

        Log.i(TAG, "WatchdogService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        Log.i(TAG, "WatchdogService stopped")
        super.onDestroy()
    }

    // ── Check loop ───────────────────────────────────────────────────

    private val checkRunnable: Runnable = object : Runnable {
        override fun run() {
            try {
                checkPythonHealth()
                checkAndroidServices()
                checkHeapUsage()
            } catch (e: Exception) {
                Log.w(TAG, "Watchdog check error", e)
            }
            handler?.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // ── Python health ping ───────────────────────────────────────────

    private fun checkPythonHealth() {
        try {
            val ok = pingPythonHealth()
            if (ok) {
                pythonMissCount = 0
                return
            }
        } catch (_: Exception) {
            // fall through to miss handling
        }

        pythonMissCount++
        Log.w(TAG, "Python health miss #$pythonMissCount")

        if (pythonMissCount >= MAX_PYTHON_MISS) {
            Log.e(TAG, "Python unresponsive after $MAX_PYTHON_MISS consecutive misses")
            sendBroadcast(Intent(ACTION_PYTHON_DEAD))
            pythonMissCount = 0
        }
    }

    private fun pingPythonHealth(): Boolean {
        Socket().use { sock ->
            sock.soTimeout = SOCKET_TIMEOUT_MS
            sock.connect(InetSocketAddress("127.0.0.1", PYTHON_HEALTH_PORT), SOCKET_TIMEOUT_MS)

            val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            writer.write("{\"type\":\"ping\"}\n")
            writer.flush()

            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: return false
            return line.contains("\"status\"") && line.contains("\"ok\"")
        }
    }

    // ── Android service monitoring ───────────────────────────────────

    private fun checkAndroidServices() {
        val servicesToMonitor = listOf(
            OverlayService::class.java,
            AudioCaptureService::class.java,
        )

        for (svcClass in servicesToMonitor) {
            if (!isServiceRunning(svcClass)) {
                Log.w(TAG, "${svcClass.simpleName} not running — attempting restart")
                try {
                    val intent = Intent(this, svcClass)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart ${svcClass.simpleName}", e)
                    NeroApp.recordCrash(TAG, "Failed to restart ${svcClass.simpleName}", e)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    // ── Heap monitoring ──────────────────────────────────────────────

    private fun checkHeapUsage() {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()
        val pct = if (max > 0) (used * 100 / max).toInt() else 0

        Log.d(TAG, "Heap: ${used / 1024 / 1024}MB / ${max / 1024 / 1024}MB ($pct%)")

        if (pct > HEAP_GC_THRESHOLD_PCT) {
            Log.w(TAG, "Heap usage $pct% > $HEAP_GC_THRESHOLD_PCT% — triggering GC")
            System.gc()
        }
    }

    // ── Notification ─────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NeroApp.CHANNEL_WATCHDOG)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("NeroVision Watchdog")
            .setContentText("Monitoring service health")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    // ── Constants ────────────────────────────────────────────────────

    companion object {
        private const val TAG = "WatchdogService"
        private const val NOTIFICATION_ID = 3001

        private const val WATCHDOG_INTERVAL_MS = 12_000L
        private const val MAX_PYTHON_MISS = 3
        private const val PYTHON_HEALTH_PORT = 9001
        private const val SOCKET_TIMEOUT_MS = 3_000
        private const val HEAP_GC_THRESHOLD_PCT = 85

        const val ACTION_PYTHON_DEAD = "com.nerovision.PYTHON_DEAD"
    }
}
