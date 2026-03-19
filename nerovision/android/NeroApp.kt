package com.nerovision.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NeroApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        installCrashHandler()
    }

    // ── Notification channels ────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return

        val foreground = NotificationChannel(
            CHANNEL_FOREGROUND,
            "NeroVision Active",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification while NeroVision services are running"
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "NeroVision Alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Important events and errors"
        }

        val watchdog = NotificationChannel(
            CHANNEL_WATCHDOG,
            "NeroVision Watchdog",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Health-check status"
        }

        manager.createNotificationChannels(listOf(foreground, alerts, watchdog))
    }

    // ── Crash handler ────────────────────────────────────────────────

    private fun installCrashHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception in ${thread.name}", throwable)
                recordCrash(TAG, "Uncaught exception in ${thread.name}", throwable)
            } catch (_: Throwable) {
                // Never let crash-logging itself crash the app
            }
            original?.uncaughtException(thread, throwable)
        }
    }

    // ── Companion ────────────────────────────────────────────────────

    companion object {
        private const val TAG = "NeroApp"

        const val CHANNEL_FOREGROUND = "nero_foreground"
        const val CHANNEL_ALERTS = "nero_alerts"
        const val CHANNEL_WATCHDOG = "nero_watchdog"

        @Volatile
        private var instance: NeroApp? = null

        fun ctx(): Context =
            instance?.applicationContext
                ?: throw IllegalStateException("NeroApp not initialised")

        fun filesDir(sub: String): File {
            val dir = File(ctx().filesDir, sub)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun recordCrash(tag: String, msg: String, throwable: Throwable?) {
            try {
                val crashDir = filesDir("crashes")
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val file = File(crashDir, "crash_$ts.txt")

                file.bufferedWriter().use { w ->
                    w.write("Timestamp: $ts\n")
                    w.write("Tag: $tag\n")
                    w.write("Message: $msg\n")
                    w.write("Thread: ${Thread.currentThread().name}\n")
                    w.write("\n")
                    if (throwable != null) {
                        val sw = StringWriter()
                        throwable.printStackTrace(PrintWriter(sw))
                        w.write(sw.toString())
                    }
                }
            } catch (_: Throwable) {
                // recordCrash must never throw
            }
        }
    }
}
