package com.nerovision.assistant

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the screen via [MediaProjection] and sends PNG frames to
 * the Python vision_service on port 9004.
 *
 * Start with [buildIntent] to supply the projection result code and data
 * obtained from the system permission dialog.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var scheduler: ScheduledExecutorService? = null
    private var captureFuture: ScheduledFuture<*>? = null
    private val running = AtomicBoolean(false)

    private var captureWidth = 0
    private var captureHeight = 0
    private var screenDensity = 0

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        handlerThread = HandlerThread("ScreenCapture").also { it.start() }
        imageHandler = Handler(handlerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || running.get()) return START_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (data == null) {
            Log.e(TAG, "Missing projection data — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        resolveDisplayMetrics()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
                running.set(false)
                stopSelf()
            }
        }, imageHandler)

        setupVirtualDisplay()
        startCaptureLoop()
        running.set(true)

        Log.i(TAG, "Screen capture started: ${captureWidth}x$captureHeight @${screenDensity}dpi")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        captureFuture?.cancel(false)
        scheduler?.shutdownNow()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        handlerThread?.quitSafely()
        Log.i(TAG, "ScreenCaptureService destroyed")
        super.onDestroy()
    }

    // ── Display metrics ──────────────────────────────────────────────

    private fun resolveDisplayMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        screenDensity = metrics.densityDpi
        var w = metrics.widthPixels
        var h = metrics.heightPixels

        if (w > MAX_DIMENSION || h > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toFloat() / maxOf(w, h)
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }

        captureWidth = w
        captureHeight = h
    }

    // ── Virtual display + ImageReader ────────────────────────────────

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            captureWidth, captureHeight, PixelFormat.RGBA_8888, IMAGE_BUFFER_COUNT,
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "NeroScreenCapture",
            captureWidth,
            captureHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            imageHandler,
        )
    }

    // ── Capture loop ─────────────────────────────────────────────────

    private fun startCaptureLoop() {
        scheduler = Executors.newSingleThreadScheduledExecutor()
        val intervalMs = 1000L / CAPTURE_FPS
        captureFuture = scheduler!!.scheduleAtFixedRate(
            ::captureFrame, intervalMs, intervalMs, TimeUnit.MILLISECONDS,
        )
    }

    private fun captureFrame() {
        if (!running.get()) return

        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * captureWidth

            val bitmap = Bitmap.createBitmap(
                captureWidth + rowPadding / pixelStride,
                captureHeight,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val cropped = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight)
            } else {
                bitmap
            }

            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val pngBytes = baos.toByteArray()

            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()

            sendToVisionService(pngBytes)
        } catch (e: Exception) {
            Log.w(TAG, "Capture frame error", e)
        } finally {
            image.close()
        }
    }

    // ── Send to Python vision_service ────────────────────────────────

    private fun sendToVisionService(pngBytes: ByteArray) {
        try {
            Socket().use { sock ->
                sock.soTimeout = SOCKET_TIMEOUT_MS
                sock.connect(InetSocketAddress("127.0.0.1", VISION_PORT), SOCKET_TIMEOUT_MS)

                val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
                val header = "{\"type\":\"screenshot\",\"width\":$captureWidth," +
                    "\"height\":$captureHeight,\"len\":${pngBytes.size}}\n"
                writer.write(header)
                writer.flush()

                sock.getOutputStream().write(pngBytes)
                sock.getOutputStream().flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send screenshot to vision_service: ${e.message}")
        }
    }

    // ── Notification ─────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NeroApp.CHANNEL_FOREGROUND)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("NeroVision")
            .setContentText("Screen capture active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    // ── Constants / companion ────────────────────────────────────────

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 3003

        private const val MAX_DIMENSION = 1080
        private const val IMAGE_BUFFER_COUNT = 2
        private const val CAPTURE_FPS = 1
        private const val VISION_PORT = 9004
        private const val SOCKET_TIMEOUT_MS = 3_000

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        fun buildIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
        }
    }
}
