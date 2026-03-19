package com.nerovision.production

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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class NeroMediaProjectionService : Service() {
    private var projectionThread: HandlerThread? = null
    private var projectionHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    override fun onCreate() {
        super.onCreate()
        NeroServiceRegistry.projection.set(this)
        startForeground(
            NeroContract.Notifications.PROJECTION_ID,
            NeroNotifications.build(this, "NeroVision Projection", getString(R.string.notification_bootstrap_text)),
        )
        startProjectionIfPossible()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startProjectionIfPossible()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseProjection()
        NeroServiceRegistry.projection.set(null)
        super.onDestroy()
    }

    fun captureFrameBase64(): JSONObject {
        val reader = imageReader ?: return JSONObject().put("ok", false).put("error", "projection_not_ready")
        val image = reader.acquireLatestImage() ?: return JSONObject().put("ok", false).put("error", "no_frame")
        image.use { frame ->
            val plane = frame.planes.firstOrNull()
                ?: return JSONObject().put("ok", false).put("error", "no_plane")
            val buffer = plane.buffer
            val width = frame.width
            val height = frame.height
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 70, out)
            return JSONObject()
                .put("ok", true)
                .put("width", width)
                .put("height", height)
                .put("imageBase64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        }
    }

    private fun startProjectionIfPossible() {
        if (mediaProjection != null && imageReader != null) {
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val data = projectionIntent
        if (manager == null || data == null) {
            NeroJsonLog.warn(this, "NeroMediaProjectionService", "Projection manager or grant data missing")
            return
        }
        projectionThread = HandlerThread("nero-projection").also { it.start() }
        projectionHandler = Handler(projectionThread!!.looper)
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        mediaProjection = manager.getMediaProjection(projectionResultCode, data)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "NeroVisionCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            projectionHandler,
        )
        NeroJsonLog.info(this, "NeroMediaProjectionService", "Media projection initialized")
    }

    private fun releaseProjection() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        runCatching { projectionThread?.quitSafely() }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        projectionHandler = null
        projectionThread = null
    }

    companion object {
        @Volatile
        private var projectionIntent: Intent? = null

        @Volatile
        private var projectionResultCode: Int = 0

        fun configureProjection(resultCode: Int, data: Intent) {
            projectionResultCode = resultCode
            projectionIntent = Intent(data)
        }
    }
}
