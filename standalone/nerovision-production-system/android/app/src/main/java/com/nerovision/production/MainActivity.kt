package com.nerovision.production

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            updateStatus(if (granted) "Microphone permission granted." else "Microphone permission denied.")
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            updateStatus(if (granted) "Notification permission granted." else "Notification permission denied.")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                NeroMediaProjectionService.configureProjection(result.resultCode, data)
                startCoreServices()
                updateStatus("Media projection granted.")
            } else {
                updateStatus("Media projection was not granted.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            text = getString(R.string.main_status_ready)
            setPadding(32, 32, 32, 32)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(statusView)
            addView(actionButton(R.string.main_action_start) { startCoreServices() })
            addView(actionButton(R.string.main_action_overlay) { requestOverlayPermission() })
            addView(actionButton(R.string.main_action_projection) { requestMediaProjection() })
            addView(actionButton(R.string.main_action_accessibility) { openAccessibilitySettings() })
            addView(actionButton(R.string.main_action_mic) { requestMicrophone() })
        }

        setContentView(ScrollView(this).apply { addView(root) })
        requestNotificationPermissionIfNeeded()
    }

    private fun actionButton(textRes: Int, action: () -> Unit): Button {
        return Button(this).apply {
            text = getString(textRes)
            setOnClickListener { action.invoke() }
        }
    }

    private fun startCoreServices() {
        updateStatus("Starting NeroVision core services.")
        startForegroundServiceCompat(NeroBridgeService::class.java)
        startForegroundServiceCompat(NeroOverlayService::class.java)
        startForegroundServiceCompat(NeroMediaProjectionService::class.java)
        startForegroundServiceCompat(NeroAudioCaptureService::class.java)
        startForegroundServiceCompat(NeroWatchdogService::class.java)
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            updateStatus("Overlay permission already granted.")
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestMediaProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            updateStatus("MediaProjectionManager unavailable on this device.")
            return
        }
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestMicrophone() {
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startForegroundServiceCompat(serviceClass: Class<*>) {
        val intent = Intent(this, serviceClass)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun updateStatus(text: String) {
        statusView.text = text
        NeroJsonLog.info(this, "MainActivity", text)
    }
}
