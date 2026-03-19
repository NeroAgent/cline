package com.nerovision.assistant

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Persistent microphone capture with energy-based VAD.
 *
 * Streams PCM-16 mono 16 kHz audio to the Python voice_service on
 * port 9003 using Java [DataOutputStream] framing:
 *
 *   writeUTF(headerJson)     — 2-byte length + UTF-8 header
 *   writeInt(chunk.size)     — 4-byte big-endian PCM length
 *   write(chunk)             — raw PCM bytes
 *   writeInt(-1)             — silence marker (end of utterance)
 */
class AudioCaptureService : Service() {

    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running.getAndSet(true)) {
            captureThread = Thread(::captureLoop, "AudioCapture").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "AudioCaptureService started")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        captureThread?.interrupt()
        captureThread = null
        Log.i(TAG, "AudioCaptureService stopped")
        super.onDestroy()
    }

    // ── Capture loop ─────────────────────────────────────────────────

    private fun captureLoop() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                Log.e(TAG, "Invalid minimum buffer size: $minBuf")
                return
            }

            val bufferSize = minBuf * BUFFER_MULTIPLIER

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialise")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord!!.startRecording()
            Log.i(TAG, "Recording started: ${SAMPLE_RATE}Hz mono PCM-16, buf=$bufferSize")

            streamToVoiceService(bufferSize)
        } catch (e: Exception) {
            Log.e(TAG, "Capture loop error", e)
            NeroApp.recordCrash(TAG, "Capture loop error", e)
        }
    }

    private fun streamToVoiceService(bufferSize: Int) {
        val readBuf = ShortArray(bufferSize / 2)

        while (running.get()) {
            var dos: DataOutputStream? = null
            var sock: Socket? = null

            try {
                sock = connectToVoiceService() ?: continue
                dos = DataOutputStream(sock.getOutputStream())

                dos.writeUTF(HEADER_JSON)

                var silentFrames = 0
                var inUtterance = false

                while (running.get()) {
                    val samplesRead = audioRecord?.read(readBuf, 0, readBuf.size) ?: break
                    if (samplesRead <= 0) continue

                    val rms = computeRMS(readBuf, samplesRead)
                    val isSpeech = rms >= VAD_THRESHOLD

                    if (isSpeech) {
                        silentFrames = 0
                        inUtterance = true
                        val bytes = shortsToBytes(readBuf, samplesRead)
                        dos.writeInt(bytes.size)
                        dos.write(bytes)
                    } else if (inUtterance) {
                        silentFrames++
                        val bytes = shortsToBytes(readBuf, samplesRead)
                        dos.writeInt(bytes.size)
                        dos.write(bytes)

                        if (silentFrames >= SILENCE_FRAME_COUNT) {
                            dos.writeInt(-1)
                            dos.flush()
                            inUtterance = false
                            silentFrames = 0
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Voice service connection lost: ${e.message}")
            } finally {
                try { dos?.close() } catch (_: Exception) {}
                try { sock?.close() } catch (_: Exception) {}
            }

            if (running.get()) {
                Log.d(TAG, "Reconnecting to voice service in ${RECONNECT_DELAY_MS}ms")
                Thread.sleep(RECONNECT_DELAY_MS)
            }
        }
    }

    // ── Connection ───────────────────────────────────────────────────

    private fun connectToVoiceService(): Socket? {
        for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
            try {
                val sock = Socket()
                sock.soTimeout = SOCKET_TIMEOUT_MS
                sock.connect(
                    InetSocketAddress("127.0.0.1", VOICE_SERVICE_PORT),
                    SOCKET_TIMEOUT_MS,
                )
                Log.d(TAG, "Connected to voice service on attempt $attempt")
                return sock
            } catch (e: Exception) {
                Log.w(TAG, "Connect attempt $attempt/$MAX_CONNECT_ATTEMPTS failed: ${e.message}")
                if (attempt < MAX_CONNECT_ATTEMPTS) {
                    Thread.sleep(RECONNECT_DELAY_MS)
                }
            }
        }
        return null
    }

    // ── VAD helpers ──────────────────────────────────────────────────

    private fun computeRMS(samples: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / count)
    }

    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = shorts[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        return bytes
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
            .setContentText("Listening for voice commands")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    // ── Constants ────────────────────────────────────────────────────

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val NOTIFICATION_ID = 3002

        private const val SAMPLE_RATE = 16_000
        private const val BUFFER_MULTIPLIER = 4
        private const val VAD_THRESHOLD = 800.0
        private const val SILENCE_FRAME_COUNT = 30

        private const val VOICE_SERVICE_PORT = 9003
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val MAX_CONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 3_000L

        private const val HEADER_JSON =
            """{"sample_rate":16000,"channels":1,"format":"pcm_16"}"""
    }
}
