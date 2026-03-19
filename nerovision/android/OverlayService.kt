package com.nerovision.assistant

import android.animation.ValueAnimator
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Foreground overlay service displaying a Canvas-based animated avatar.
 *
 * The [AvatarView] renders five distinct state animations (IDLE,
 * LISTENING, THINKING, SPEAKING, ERROR) plus a particle system.
 * The overlay is draggable and remembers its position across launches.
 *
 * State changes arrive via [LocalBroadcastManager] with action
 * [ACTION_SET_STATE] and extra `"state"` (one of the [AvatarState] names).
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var avatarView: AvatarView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var prefs: SharedPreferences? = null

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        avatarView = AvatarView(this)
        layoutParams = buildLayoutParams()

        try {
            windowManager!!.addView(avatarView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            NeroApp.recordCrash(TAG, "Failed to add overlay view", e)
            stopSelf()
            return
        }

        setupDragListener()
        registerStateReceiver()
        Log.i(TAG, "OverlayService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        } catch (_: Exception) {}
        try {
            windowManager?.removeView(avatarView)
        } catch (_: Exception) {}
        avatarView = null
        Log.i(TAG, "OverlayService stopped")
        super.onDestroy()
    }

    // ── Window params ────────────────────────────────────────────────

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val size = (120 * resources.displayMetrics.density).toInt()
        val margin = (16 * resources.displayMetrics.density).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val savedX = prefs?.getInt(KEY_POS_X, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val savedY = prefs?.getInt(KEY_POS_Y, Int.MIN_VALUE) ?: Int.MIN_VALUE

        val dm = resources.displayMetrics
        val initX = if (savedX != Int.MIN_VALUE) savedX else dm.widthPixels - size - margin
        val initY = if (savedY != Int.MIN_VALUE) savedY else dm.heightPixels - size - margin

        return WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
        }
    }

    // ── Drag listener ────────────────────────────────────────────────

    private fun setupDragListener() {
        avatarView?.setOnTouchListener(DragTouchListener())
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var startParamX = 0
        private var startParamY = 0

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val lp = layoutParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startParamX = lp.x
                    startParamY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startParamX + (event.rawX - startX).toInt()
                    lp.y = startParamY + (event.rawY - startY).toInt()
                    try { windowManager?.updateViewLayout(avatarView, lp) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> {
                    prefs?.edit()
                        ?.putInt(KEY_POS_X, lp.x)
                        ?.putInt(KEY_POS_Y, lp.y)
                        ?.apply()
                    if (abs(event.rawX - startX) < 10 && abs(event.rawY - startY) < 10) {
                        v.performClick()
                    }
                }
            }
            return true
        }
    }

    // ── State receiver ───────────────────────────────────────────────

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val name = intent.getStringExtra("state") ?: return
            try {
                avatarView?.setState(AvatarState.valueOf(name.uppercase()))
            } catch (_: IllegalArgumentException) {
                Log.w(TAG, "Unknown avatar state: $name")
            }
        }
    }

    private fun registerStateReceiver() {
        val filter = IntentFilter(ACTION_SET_STATE)
        LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver, filter)
    }

    // ── Notification ─────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, NeroApp.CHANNEL_FOREGROUND)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("NeroVision")
            .setContentText("Avatar overlay active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    // ── Constants ────────────────────────────────────────────────────

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 3004
        private const val PREFS_NAME = "nero_overlay"
        private const val KEY_POS_X = "pos_x"
        private const val KEY_POS_Y = "pos_y"

        const val ACTION_SET_STATE = "com.nerovision.SET_AVATAR_STATE"
    }
}

// ══════════════════════════════════════════════════════════════════════
// AvatarState
// ══════════════════════════════════════════════════════════════════════

enum class AvatarState(val color: Int) {
    IDLE(0xFF4488FF.toInt()),
    LISTENING(0xFF44FF88.toInt()),
    THINKING(0xFFFFAA44.toInt()),
    SPEAKING(0xFFAA44FF.toInt()),
    ERROR(0xFFFF4444.toInt()),
}

// ══════════════════════════════════════════════════════════════════════
// Particle
// ══════════════════════════════════════════════════════════════════════

private data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var alpha: Float,
    var radius: Float,
    var color: Int,
)

// ══════════════════════════════════════════════════════════════════════
// AvatarView
// ══════════════════════════════════════════════════════════════════════

class AvatarView(context: Context) : View(context) {

    private var state = AvatarState.IDLE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particles = mutableListOf<Particle>()
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 16L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase += 0.04f
            updateParticles()
            invalidate()
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        animator.start()
    }

    fun setState(newState: AvatarState) {
        if (newState != state) {
            state = newState
            spawnParticles()
        }
    }

    // ── Drawing ──────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = width.coerceAtMost(height) / 2f * 0.7f

        drawParticles(canvas)

        when (state) {
            AvatarState.IDLE -> drawIdle(canvas, cx, cy, r)
            AvatarState.LISTENING -> drawListening(canvas, cx, cy, r)
            AvatarState.THINKING -> drawThinking(canvas, cx, cy, r)
            AvatarState.SPEAKING -> drawSpeaking(canvas, cx, cy, r)
            AvatarState.ERROR -> drawError(canvas, cx, cy, r)
        }
    }

    // ── IDLE: breathing pulse ────────────────────────────────────────

    private fun drawIdle(c: Canvas, cx: Float, cy: Float, r: Float) {
        val scale = 0.95f + 0.05f * sin(phase.toDouble()).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = state.color
        paint.alpha = 180
        c.drawCircle(cx, cy, r * scale, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.alpha = 255
        c.drawCircle(cx, cy, r * scale, paint)
    }

    // ── LISTENING: concentric ripples ────────────────────────────────

    private fun drawListening(c: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = state.color
        paint.alpha = 120
        c.drawCircle(cx, cy, r * 0.4f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        for (i in 0 until 3) {
            val ripplePhase = (phase + i * 2.1f) % 6.28f
            val rippleR = r * 0.4f + r * 0.6f * (ripplePhase / 6.28f)
            paint.alpha = (255 * (1f - ripplePhase / 6.28f)).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, rippleR, paint)
        }
    }

    // ── THINKING: spinning arc ───────────────────────────────────────

    private fun drawThinking(c: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = state.color
        paint.alpha = 80
        c.drawCircle(cx, cy, r * 0.35f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.alpha = 255
        val startAngle = (phase * 57.2958f * 3f) % 360f
        val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, startAngle, 100f, false, paint)
        c.drawArc(rect, startAngle + 180f, 60f, false, paint)
    }

    // ── SPEAKING: wave bars ──────────────────────────────────────────

    private fun drawSpeaking(c: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = state.color
        val barWidth = r * 0.22f
        val gap = r * 0.12f
        val totalWidth = 5 * barWidth + 4 * gap
        val startX = cx - totalWidth / 2f

        for (i in 0 until 5) {
            val barPhase = phase + i * 0.7f
            val height = r * 0.3f + r * 0.7f * abs(sin(barPhase.toDouble()).toFloat())
            val x = startX + i * (barWidth + gap)
            val top = cy - height / 2f
            paint.alpha = 180 + (75 * abs(sin(barPhase.toDouble()))).toInt().coerceAtMost(75)
            c.drawRoundRect(x, top, x + barWidth, top + height, barWidth / 2f, barWidth / 2f, paint)
        }
    }

    // ── ERROR: pulsing X ─────────────────────────────────────────────

    private fun drawError(c: Canvas, cx: Float, cy: Float, r: Float) {
        val pulse = 0.8f + 0.2f * sin(phase * 3.0).toFloat()

        paint.style = Paint.Style.FILL
        paint.color = state.color
        paint.alpha = (80 * pulse).toInt()
        c.drawCircle(cx, cy, r * pulse, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.alpha = 255
        paint.strokeCap = Paint.Cap.ROUND
        val armLen = r * 0.35f * pulse
        c.drawLine(cx - armLen, cy - armLen, cx + armLen, cy + armLen, paint)
        c.drawLine(cx + armLen, cy - armLen, cx - armLen, cy + armLen, paint)
    }

    // ── Particles ────────────────────────────────────────────────────

    private fun spawnParticles() {
        val cx = width / 2f
        val cy = height / 2f
        val count = (10..15).random()
        for (i in 0 until count) {
            if (particles.size >= MAX_PARTICLES) break
            particles.add(
                Particle(
                    x = cx + Random.nextFloat() * 30f - 15f,
                    y = cy + Random.nextFloat() * 30f - 15f,
                    vx = Random.nextFloat() * 2f - 1f,
                    vy = -(Random.nextFloat() * 2f + 0.5f),
                    alpha = 1f,
                    radius = Random.nextFloat() * 4f + 2f,
                    color = state.color,
                ),
            )
        }
    }

    private fun updateParticles() {
        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x += p.vx
            p.y += p.vy
            p.alpha -= 0.02f
            if (p.alpha <= 0f) iter.remove()
        }
    }

    private fun drawParticles(c: Canvas) {
        for (p in particles) {
            paint.style = Paint.Style.FILL
            paint.color = p.color
            paint.alpha = (p.alpha * 255).toInt().coerceIn(0, 255)
            c.drawCircle(p.x, p.y, p.radius, paint)
        }
    }

    companion object {
        private const val MAX_PARTICLES = 40
    }
}
