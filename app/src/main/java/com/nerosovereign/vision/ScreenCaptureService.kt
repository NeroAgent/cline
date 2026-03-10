package com.nerosovereign.vision

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.util.Base64
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume

class ScreenCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_CAPTURE_ONCE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val receiver = intent.getParcelableExtraCompat<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        if (receiver == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startCaptureForeground()
        serviceScope.launch {
            val result = withContext(Dispatchers.Default) { captureOnceInternal() }
            val payload = Bundle()
            if (result.isSuccess) {
                payload.putString(EXTRA_CAPTURE_BASE64, result.getOrNull())
                receiver.send(Activity.RESULT_OK, payload)
            } else {
                payload.putString(EXTRA_CAPTURE_ERROR, result.exceptionOrNull()?.message ?: "Unknown capture error")
                receiver.send(Activity.RESULT_CANCELED, payload)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCaptureForeground() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CAPTURE_CHANNEL_ID,
                "Nero Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CAPTURE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_message))
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                CAPTURE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(CAPTURE_NOTIFICATION_ID, notification)
        }
    }

    private suspend fun captureOnceInternal(): Result<String> = runCatching {
        val resultCode = projectionResultCode
        val data = projectionData
        require(resultCode == Activity.RESULT_OK && data != null) {
            "Screen capture permission not granted"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val permissionState = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
            )
            require(permissionState == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                "FOREGROUND_SERVICE_MEDIA_PROJECTION permission is not granted"
            }
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = projectionManager.getMediaProjection(resultCode, Intent(data))
            ?: throw IllegalStateException("Unable to initialize MediaProjection")

        var imageReader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null
        var captureThread: HandlerThread? = null

        try {
            val windowManager = getSystemService(WindowManager::class.java)
            val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds
            } else {
                android.graphics.Rect(
                    0,
                    0,
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels
                )
            }

            val targetWidth = (bounds.width() * 0.5f).toInt().coerceAtLeast(1)
            val targetHeight = (bounds.height() * 0.5f).toInt().coerceAtLeast(1)
            val density = resources.displayMetrics.densityDpi

            imageReader = ImageReader.newInstance(
                targetWidth,
                targetHeight,
                PixelFormat.RGBA_8888,
                2
            )

            captureThread = HandlerThread("NeroCaptureThread").apply { start() }
            val handler = Handler(captureThread.looper)

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "NeroCaptureDisplay",
                targetWidth,
                targetHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler
            )

            var captured: Image? = null
            for (attempt in 0 until 10) {
                delay(100L)
                captured = imageReader.acquireLatestImage()
                if (captured != null) break
            }

            val image = captured ?: throw IOException("Could not capture an image from MediaProjection")
            image.useImage {
                imageToBase64Jpeg(image, targetWidth, targetHeight)
            }
        } finally {
            runCatching { virtualDisplay?.release() }
            runCatching { imageReader?.close() }
            runCatching { mediaProjection.stop() }
            runCatching {
                captureThread?.quitSafely()
                captureThread?.join(500)
            }
        }
    }

    private fun imageToBase64Jpeg(image: Image, width: Int, height: Int): String {
        val plane = image.planes.firstOrNull() ?: throw IOException("Image has no planes")
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + (rowPadding / pixelStride),
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        bitmap.recycle()

        val output = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 80, output)
        cropped.recycle()

        val jpegBytes = output.toByteArray()
        return Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
    }

    companion object {
        private const val ACTION_CAPTURE_ONCE = "com.nerosovereign.vision.action.CAPTURE_ONCE"
        private const val EXTRA_RESULT_RECEIVER = "extra_result_receiver"
        private const val EXTRA_CAPTURE_BASE64 = "extra_capture_base64"
        private const val EXTRA_CAPTURE_ERROR = "extra_capture_error"
        private const val CAPTURE_CHANNEL_ID = "nero_capture_channel"
        private const val CAPTURE_NOTIFICATION_ID = 4042

        @Volatile
        private var projectionResultCode: Int? = null

        @Volatile
        private var projectionData: Intent? = null

        fun setProjectionPermission(resultCode: Int, data: Intent) {
            projectionResultCode = resultCode
            projectionData = Intent(data)
        }

        fun hasProjectionPermission(): Boolean {
            return projectionResultCode == Activity.RESULT_OK && projectionData != null
        }

        suspend fun captureBase64(context: Context): Result<String> = suspendCancellableCoroutine { continuation ->
            if (!hasProjectionPermission()) {
                continuation.resume(Result.failure(IllegalStateException("MediaProjection permission missing")))
                return@suspendCancellableCoroutine
            }

            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (continuation.isCompleted) return
                    if (resultCode == Activity.RESULT_OK) {
                        val base64 = resultData?.getString(EXTRA_CAPTURE_BASE64)
                        if (base64.isNullOrBlank()) {
                            continuation.resume(Result.failure(IOException("Capture response was empty")))
                        } else {
                            continuation.resume(Result.success(base64))
                        }
                    } else {
                        val error = resultData?.getString(EXTRA_CAPTURE_ERROR) ?: "Capture canceled"
                        continuation.resume(Result.failure(IOException(error)))
                    }
                }
            }

            val captureIntent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_CAPTURE_ONCE)
                .putExtra(EXTRA_RESULT_RECEIVER, receiver)

            ContextCompat.startForegroundService(context, captureIntent)
        }
    }
}

private inline fun Image.useImage(block: () -> String): String {
    return try {
        block()
    } finally {
        runCatching { close() }
    }
}

private inline fun <reified T> Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}
