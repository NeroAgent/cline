package com.nerosovereign.vision

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.JobInfo
import android.app.JobParameters
import android.app.JobScheduler
import android.app.JobService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.msgpack.core.MessagePack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class NeroOverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val processing = AtomicBoolean(false)

    private lateinit var windowManager: WindowManager
    private lateinit var overlayParams: WindowManager.LayoutParams
    private lateinit var overlayView: View
    private lateinit var avatarView: ImageView
    private lateinit var pulseRingView: View
    private var dismissView: View? = null
    private var responseBubbleView: View? = null
    private var breathingAnimator: ObjectAnimator? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var awake = false
    private var dragging = false

    private val bridge by lazy { HermesBridge(this) }
    private val apiClient by lazy { ApiClient(this) }

    private lateinit var gestureDetector: GestureDetector

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startOverlayForegroundService()
        setupOverlayWindow()
        bridge.start()
        scheduleHealthCheckJob()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TERMUX_RELAY) {
            val payload = intent.getByteArrayExtra(EXTRA_TERMUX_PAYLOAD)
            val text = payload?.toString(Charsets.UTF_8).orEmpty()
            if (text.isNotBlank()) showResponseBubble(text)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { breathingAnimator?.cancel() }
        runCatching { pulseAnimator?.cancel() }
        runCatching { dismissView?.let(windowManager::removeViewImmediate) }
        runCatching { responseBubbleView?.let(windowManager::removeViewImmediate) }
        runCatching { windowManager.removeViewImmediate(overlayView) }
        bridge.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startOverlayForegroundService() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Nero Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_message))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                OVERLAY_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(OVERLAY_NOTIFICATION_ID, notification)
        }
    }

    private fun setupOverlayWindow() {
        windowManager = getSystemService(WindowManager::class.java)

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_avatar, null, false)
        avatarView = overlayView.findViewById(R.id.avatarCircle)
        pulseRingView = overlayView.findViewById(R.id.pulseRing)
        pulseRingView.visibility = View.GONE

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 350
        }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleAwake()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                triggerVisionCapture()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                dragging = true
                showDismissMenu()
            }
        })

        overlayView.setOnTouchListener(OverlayTouchListener())
        windowManager.addView(overlayView, overlayParams)
        updateAwakeUi()
    }

    private inner class OverlayTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = overlayParams.x
                    initialY = overlayParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        overlayParams.x = initialX + (event.rawX - touchX).toInt()
                        overlayParams.y = initialY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(overlayView, overlayParams)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val shouldDismiss = isInDismissZone(overlayParams.x, overlayParams.y)
                        hideDismissMenu()
                        dragging = false
                        if (shouldDismiss) stopSelf()
                    }
                }
            }
            return true
        }
    }

    private fun toggleAwake() {
        awake = !awake
        updateAwakeUi()
        Toast.makeText(
            this,
            if (awake) getString(R.string.awake_on) else getString(R.string.awake_off),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateAwakeUi() {
        if (awake) {
            avatarView.setImageResource(R.drawable.nero_awake)
            startBreathingAnimation()
        } else {
            avatarView.setImageResource(R.drawable.nero_circle)
            stopBreathingAnimation()
        }
    }

    private fun startBreathingAnimation() {
        if (breathingAnimator?.isRunning == true) return
        breathingAnimator = ObjectAnimator.ofPropertyValuesHolder(
            avatarView,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f, 1f)
        ).apply {
            duration = 3000L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            start()
        }
    }

    private fun stopBreathingAnimation() {
        breathingAnimator?.cancel()
        avatarView.scaleX = 1f
        avatarView.scaleY = 1f
    }

    private fun startPulseAnimation() {
        pulseRingView.visibility = View.VISIBLE
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            pulseRingView,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.8f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.8f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f)
        ).apply {
            duration = 850L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseRingView.visibility = View.GONE
        pulseRingView.scaleX = 1f
        pulseRingView.scaleY = 1f
        pulseRingView.alpha = 1f
    }

    private fun triggerVisionCapture() {
        if (!processing.compareAndSet(false, true)) return
        startPulseAnimation()

        serviceScope.launch {
            val resultText = try {
                if (!ScreenCaptureService.hasProjectionPermission()) {
                    val permissionIntent = Intent(this@NeroOverlayService, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_REQUEST_CAPTURE_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(permissionIntent)
                    getString(R.string.capture_permission_required)
                } else {
                    val captureResult = ScreenCaptureService.captureBase64(this@NeroOverlayService).getOrThrow()
                    val bridgePayload = buildVisionRelayPayload(captureResult)

                    val bridged = if (bridge.isTermuxInstalled()) {
                        bridge.relay("gemini", bridgePayload).getOrNull()
                    } else {
                        null
                    }

                    if (bridged != null && bridged.isNotEmpty()) {
                        bridged.toString(Charsets.UTF_8)
                    } else {
                        apiClient.analyzeScreen(captureResult).getOrThrow().text
                    }
                }
            } catch (error: Exception) {
                "Processing failed: ${error.message.orEmpty()}"
            }

            showResponseBubble(resultText)
            stopPulseAnimation()
            processing.set(false)
        }
    }

    private fun buildVisionRelayPayload(base64Jpeg: String): ByteArray {
        val prompt = "Analyze the current Android screen and summarize actionable insights in under 120 words."
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2)
        packer.packString("prompt")
        packer.packString(prompt)
        packer.packString("image_base64")
        packer.packString(base64Jpeg)
        packer.close()
        return packer.toByteArray()
    }

    private fun showResponseBubble(text: String) {
        responseBubbleView?.let {
            runCatching { windowManager.removeViewImmediate(it) }
        }

        val bubble = LayoutInflater.from(this).inflate(R.layout.chat_bubble, null, false)
        bubble.findViewById<TextView>(R.id.chatText).text = text.take(450)

        val bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = overlayParams.x + dpToPx(60)
            y = overlayParams.y - dpToPx(20)
        }

        responseBubbleView = bubble
        windowManager.addView(bubble, bubbleParams)
        bubble.postDelayed({
            responseBubbleView?.let { view ->
                runCatching { windowManager.removeViewImmediate(view) }
                responseBubbleView = null
            }
        }, 7000L)
    }

    private fun showDismissMenu() {
        if (dismissView != null) return
        val dismiss = LayoutInflater.from(this).inflate(R.layout.chat_bubble, null, false)
        dismiss.findViewById<TextView>(R.id.chatText).text = getString(R.string.dismiss_hint)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(32)
        }

        dismissView = dismiss
        windowManager.addView(dismiss, params)
    }

    private fun hideDismissMenu() {
        dismissView?.let {
            runCatching { windowManager.removeViewImmediate(it) }
            dismissView = null
        }
    }

    private fun isInDismissZone(overlayX: Int, overlayY: Int): Boolean {
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2
        val closeToCenter = abs((overlayX + dpToPx(50)) - centerX) < dpToPx(90)
        val nearBottom = overlayY > (metrics.heightPixels * 0.72f).toInt()
        return closeToCenter && nearBottom
    }

    private fun scheduleHealthCheckJob() {
        val scheduler = getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(
            HEALTH_JOB_ID,
            ComponentName(this, NeroHealthJobService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(false)
            .setPeriodic(15 * 60 * 1000L)
            .build()

        scheduler.schedule(job)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val ACTION_TERMUX_RELAY = "com.nerosovereign.vision.action.TERMUX_RELAY"
        const val EXTRA_TERMUX_PAYLOAD = "extra_termux_payload"
        private const val NOTIFICATION_CHANNEL_ID = "nero_overlay_channel"
        private const val OVERLAY_NOTIFICATION_ID = 3031
        private const val HEALTH_JOB_ID = 8181
    }
}

class NeroHealthJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        if (usedMb > 50) {
            Log.w("NeroHealthJobService", "Android bridge memory above target: ${usedMb}MB")
        } else {
            Log.d("NeroHealthJobService", "Bridge health check OK: ${usedMb}MB")
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
