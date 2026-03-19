package com.nerovision.production

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NeroOverlayService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        NeroServiceRegistry.overlay.set(this)
        startForeground(
            NeroContract.Notifications.OVERLAY_ID,
            NeroNotifications.build(this, "NeroVision Overlay", getString(R.string.notification_bootstrap_text)),
        )
        ensureOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureOverlay()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        NeroServiceRegistry.overlay.set(null)
        mainHandler.post {
            runCatching {
                overlayView?.let { view -> windowManager?.removeView(view) }
            }
            overlayView = null
            layoutParams = null
        }
        super.onDestroy()
    }

    fun applyState(state: JSONObject?): JSONObject {
        if (state == null) {
            return JSONObject().put("ok", false).put("error", "missing_state")
        }
        ensureOverlay()
        val result = JSONObject().put("ok", overlayView != null)
        val latch = CountDownLatch(1)
        mainHandler.post {
            val view = overlayView
            if (view == null) {
                latch.countDown()
                return@post
            }
            val visible = state.optBoolean("visible", true)
            view.visibility = if (visible) View.VISIBLE else View.GONE
            val escaped = state.toString().replace("\\", "\\\\").replace("'", "\\'")
            view.evaluateJavascript("window.setAvatarState('$escaped');", null)
            latch.countDown()
        }
        latch.await(500, TimeUnit.MILLISECONDS)
        return result.put("state", state)
    }

    private fun ensureOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            NeroJsonLog.warn(this, "NeroOverlayService", "Overlay permission missing")
            return
        }
        if (overlayView != null) {
            return
        }
        mainHandler.post {
            if (overlayView != null) {
                return@post
            }
            val manager = getSystemService(WINDOW_SERVICE) as? WindowManager
            if (manager == null) {
                NeroJsonLog.warn(this, "NeroOverlayService", "WindowManager unavailable")
                return@post
            }
            windowManager = manager
            val params = WindowManager.LayoutParams(
                320,
                320,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 96
            }
            layoutParams = params
            val webView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(0)
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                loadUrl("file:///android_asset/avatar/index.html")
            }
            overlayView = webView
            manager.addView(webView, params)
        }
    }
}
