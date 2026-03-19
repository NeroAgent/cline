package com.nerovision.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.snackbar.Snackbar
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central hub — programmatic UI, permission orchestration, service
 * lifecycle, and Python bridge connection.
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val servicesRunning = AtomicBoolean(false)
    private val pythonConnected = AtomicBoolean(false)

    // ── UI elements ──────────────────────────────────────────────────

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var a11yButton: Button
    private lateinit var logView: TextView
    private lateinit var rootLayout: LinearLayout

    // ── Activity result launchers ────────────────────────────────────

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = ScreenCaptureService.buildIntent(
                this, result.resultCode, result.data!!,
            )
            startForegroundServiceCompat(intent)
        } else {
            Log.w(TAG, "Screen capture permission denied")
        }
    }

    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) Log.i(TAG, "RECORD_AUDIO granted")
        else Log.w(TAG, "RECORD_AUDIO denied")
        continuePermissionFlow()
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) Log.i(TAG, "POST_NOTIFICATIONS granted")
        else Log.w(TAG, "POST_NOTIFICATIONS denied")
        continuePermissionFlow()
    }

    private var permStep = 0

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        registerReceivers()
        requestPermissionsSequentially()
    }

    override fun onResume() {
        super.onResume()
        updateA11yButton()
        updateStatus()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(pythonDeadReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Programmatic UI ──────────────────────────────────────────────

    private fun buildUI() {
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            setBackgroundColor(0xFF121212.toInt())
        }

        val title = TextView(this).apply {
            text = "NeroVision"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(0xFF4488FF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        rootLayout.addView(title, lp(pad))

        statusText = TextView(this).apply {
            text = "Initialising..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(statusText, lp(pad / 2))

        toggleButton = Button(this).apply {
            text = "Start Services"
            setOnClickListener { onToggle() }
        }
        rootLayout.addView(toggleButton, lp(pad / 2))

        a11yButton = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        rootLayout.addView(a11yButton, lp(pad / 2))

        val logLabel = TextView(this).apply {
            text = "Python log (tail):"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.DKGRAY)
        }
        rootLayout.addView(logLabel, lp(pad))

        logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF888888.toInt())
            typeface = Typeface.MONOSPACE
            text = "(no logs yet)"
            maxLines = 8
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
        }
        rootLayout.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (120 * dp).toInt(),
        ).apply { setMargins(0, (4 * dp).toInt(), 0, 0) })

        setContentView(rootLayout)
    }

    private fun lp(topMargin: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(0, topMargin, 0, 0) }

    // ── Permissions (sequential) ─────────────────────────────────────

    private fun requestPermissionsSequentially() {
        permStep = 0
        continuePermissionFlow()
    }

    private fun continuePermissionFlow() {
        permStep++
        when (permStep) {
            1 -> requestNotificationPerm()
            2 -> requestOverlayPerm()
            3 -> requestAudioPerm()
            4 -> checkAccessibility()
            5 -> requestProjection()
        }
    }

    private fun requestNotificationPerm() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continuePermissionFlow()
        }
    }

    private fun requestOverlayPerm() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        }
        continuePermissionFlow()
    }

    private fun requestAudioPerm() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            continuePermissionFlow()
        }
    }

    private fun checkAccessibility() {
        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Accessibility Required")
                .setMessage(
                    "NeroVision needs Accessibility Service access to read " +
                        "and interact with the screen. Please enable it in Settings.",
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Later", null)
                .show()
        }
        continuePermissionFlow()
    }

    private fun requestProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabled = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC,
        )
        return enabled.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == NeroAccessibilityService::class.java.name
        }
    }

    // ── Service lifecycle ────────────────────────────────────────────

    private fun onToggle() {
        if (servicesRunning.get()) {
            stopAll()
        } else {
            startAll()
        }
    }

    private fun startAll() {
        startForegroundServiceCompat(Intent(this, WatchdogService::class.java))
        startForegroundServiceCompat(Intent(this, OverlayService::class.java))
        startForegroundServiceCompat(Intent(this, AudioCaptureService::class.java))
        servicesRunning.set(true)
        toggleButton.text = "Stop Services"
        startBridgeConnection()
        startHealthPoll()
        startLogPoll()
        Log.i(TAG, "All services started")
    }

    private fun stopAll() {
        stopService(Intent(this, WatchdogService::class.java))
        stopService(Intent(this, OverlayService::class.java))
        stopService(Intent(this, AudioCaptureService::class.java))
        stopService(Intent(this, ScreenCaptureService::class.java))
        servicesRunning.set(false)
        pythonConnected.set(false)
        toggleButton.text = "Start Services"
        updateStatus()
        Log.i(TAG, "All services stopped")
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ── Python bridge (:9000) ────────────────────────────────────────

    private fun startBridgeConnection() {
        Thread({
            var backoff = BRIDGE_INITIAL_BACKOFF_MS
            var attempts = 0

            while (servicesRunning.get()) {
                try {
                    Socket().use { sock ->
                        sock.soTimeout = 3_000
                        sock.connect(
                            InetSocketAddress("127.0.0.1", BRIDGE_PORT), 3_000,
                        )
                        val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
                        val reader = BufferedReader(
                            InputStreamReader(sock.getInputStream(), Charsets.UTF_8),
                        )

                        writer.write("{\"type\":\"ping\"}\n")
                        writer.flush()
                        val resp = reader.readLine()

                        if (resp != null && resp.contains("\"ok\"")) {
                            pythonConnected.set(true)
                            backoff = BRIDGE_INITIAL_BACKOFF_MS
                            attempts = 0
                            handler.post { updateStatus() }
                            return@Thread
                        }
                    }
                } catch (_: Exception) {}

                pythonConnected.set(false)
                handler.post { updateStatus() }

                attempts++
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(BRIDGE_MAX_BACKOFF_MS)
                if (attempts >= BRIDGE_MAX_ATTEMPTS) {
                    backoff = BRIDGE_INITIAL_BACKOFF_MS
                    attempts = 0
                }
            }
        }, "BridgeConnect").apply { isDaemon = true }.start()
    }

    // ── Health poll (:9001) ──────────────────────────────────────────

    private fun startHealthPoll() {
        Thread({
            while (servicesRunning.get()) {
                try {
                    Thread.sleep(HEALTH_INTERVAL_MS)
                    val ok = pingHealth()
                    pythonConnected.set(ok)
                    handler.post { updateStatus() }
                } catch (_: Exception) {}
            }
        }, "HealthPoll").apply { isDaemon = true }.start()
    }

    private fun pingHealth(): Boolean {
        return try {
            Socket().use { sock ->
                sock.soTimeout = 2_000
                sock.connect(InetSocketAddress("127.0.0.1", HEALTH_PORT), 2_000)
                val w = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
                w.write("{\"type\":\"ping\"}\n"); w.flush()
                val line = BufferedReader(
                    InputStreamReader(sock.getInputStream(), Charsets.UTF_8),
                ).readLine()
                line != null && line.contains("\"ok\"")
            }
        } catch (_: Exception) { false }
    }

    // ── Log tail ─────────────────────────────────────────────────────

    private fun startLogPoll() {
        Thread({
            while (servicesRunning.get()) {
                try {
                    Thread.sleep(LOG_POLL_INTERVAL_MS)
                    val lines = readLastLogLines(5)
                    handler.post { logView.text = lines.ifEmpty { "(no logs yet)" } }
                } catch (_: Exception) {}
            }
        }, "LogPoll").apply { isDaemon = true }.start()
    }

    private fun readLastLogLines(n: Int): String {
        return try {
            val logDir = NeroApp.filesDir("logs")
            val logFile = java.io.File(logDir, "nerovision.log")
            if (!logFile.exists()) return ""
            val all = logFile.readLines()
            all.takeLast(n).joinToString("\n")
        } catch (_: Exception) { "" }
    }

    // ── Status update ────────────────────────────────────────────────

    private fun updateStatus() {
        when {
            !servicesRunning.get() -> {
                statusText.text = "NeroVision — Stopped"
                statusText.setTextColor(Color.GRAY)
            }
            pythonConnected.get() -> {
                statusText.text = "NeroVision — Connected"
                statusText.setTextColor(0xFF44FF88.toInt())
            }
            else -> {
                statusText.text = "NeroVision — Connecting..."
                statusText.setTextColor(0xFFFFAA44.toInt())
            }
        }
    }

    private fun updateA11yButton() {
        a11yButton.visibility = if (isAccessibilityEnabled()) View.GONE else View.VISIBLE
    }

    // ── PYTHON_DEAD receiver ─────────────────────────────────────────

    private val pythonDeadReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            pythonConnected.set(false)
            handler.post {
                updateStatus()
                Snackbar.make(
                    rootLayout,
                    "Python backend is not responding",
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun registerReceivers() {
        LocalBroadcastManager.getInstance(this).registerReceiver(
            pythonDeadReceiver,
            IntentFilter(WatchdogService.ACTION_PYTHON_DEAD),
        )
    }

    // ── Constants ────────────────────────────────────────────────────

    companion object {
        private const val TAG = "MainActivity"

        private const val BRIDGE_PORT = 9000
        private const val HEALTH_PORT = 9001
        private const val BRIDGE_INITIAL_BACKOFF_MS = 1_000L
        private const val BRIDGE_MAX_BACKOFF_MS = 16_000L
        private const val BRIDGE_MAX_ATTEMPTS = 5
        private const val HEALTH_INTERVAL_MS = 5_000L
        private const val LOG_POLL_INTERVAL_MS = 2_000L
    }
}
