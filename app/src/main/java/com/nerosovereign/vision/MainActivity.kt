package com.nerosovereign.vision

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var geminiKeyInput: EditText
    private lateinit var openRouterKeyInput: EditText
    private lateinit var ollamaSwitch: Switch
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val requestScreenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.setProjectionPermission(result.resultCode, result.data!!)
            Toast.makeText(this, getString(R.string.capture_permission_granted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.capture_permission_denied), Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)

        if (intent?.action == ACTION_REQUEST_CAPTURE_PERMISSION) {
            requestScreenCapture.launch(mediaProjectionManager.createScreenCaptureIntent())
            return
        }

        setContentView(R.layout.activity_main)
        bindViews()
        restoreSavedSettings()
        requestNotificationPermissionIfNeeded()
        requestOverlayPermissionIfNeeded()
        setupStartButton()
    }

    private fun bindViews() {
        geminiKeyInput = findViewById(R.id.inputGeminiKey)
        openRouterKeyInput = findViewById(R.id.inputOpenRouterKey)
        ollamaSwitch = findViewById(R.id.switchOllama)
    }

    private fun restoreSavedSettings() {
        val config = ApiClient.loadRuntimeConfig(this)
        geminiKeyInput.setText(config.geminiApiKey)
        openRouterKeyInput.setText(config.openRouterApiKey)
        ollamaSwitch.isChecked = config.useOllama
    }

    private fun setupStartButton() {
        findViewById<Button>(R.id.buttonStartNero).setOnClickListener {
            ApiClient.saveRuntimeConfig(
                context = this,
                geminiApiKey = geminiKeyInput.text?.toString().orEmpty(),
                openRouterApiKey = openRouterKeyInput.text?.toString().orEmpty(),
                useOllama = ollamaSwitch.isChecked
            )

            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermissionIfNeeded()
                Toast.makeText(this, getString(R.string.overlay_permission_required), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val serviceIntent = Intent(this, NeroOverlayService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            finish()
        }
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (Settings.canDrawOverlays(this)) return
        val overlayIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(overlayIntent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
    }

    companion object {
        const val ACTION_REQUEST_CAPTURE_PERMISSION =
            "com.nerosovereign.vision.action.REQUEST_CAPTURE_PERMISSION"
    }
}
