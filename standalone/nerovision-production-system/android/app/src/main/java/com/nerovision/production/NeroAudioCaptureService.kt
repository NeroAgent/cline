package com.nerovision.production

import android.Manifest
import android.app.Service
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class NeroAudioCaptureService : Service() {
    private val streaming = AtomicBoolean(false)
    private val sampleRate = 16_000
    private val streamId = UUID.randomUUID().toString()
    private var recordingThread: Thread? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    override fun onCreate() {
        super.onCreate()
        NeroServiceRegistry.audio.set(this)
        startForeground(
            NeroContract.Notifications.AUDIO_ID,
            NeroNotifications.build(this, "NeroVision Audio", getString(R.string.notification_bootstrap_text)),
        )
    }

    override fun onBind(intent: android.content.Intent?): IBinder? = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopStreaming()
        NeroServiceRegistry.audio.set(null)
        super.onDestroy()
    }

    fun statusJson(): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("streaming", streaming.get())
            .put("sampleRate", sampleRate)
            .put("streamId", streamId)
    }

    fun setStreaming(enabled: Boolean): JSONObject {
        return if (enabled) {
            val started = startStreaming()
            JSONObject().put("ok", started).put("streaming", started)
        } else {
            stopStreaming()
            JSONObject().put("ok", true).put("streaming", false)
        }
    }

    private fun startStreaming(): Boolean {
        if (streaming.get()) {
            return true
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            NeroJsonLog.warn(this, "NeroAudioCaptureService", "Microphone permission missing")
            return false
        }
        streaming.set(true)
        recordingThread = Thread({
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                streaming.set(false)
                return@Thread
            }
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
            val buffer = ByteArray(minBuffer)
            try {
                openSocket()
                audioRecord.startRecording()
                while (streaming.get()) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        Thread.sleep(60)
                        continue
                    }
                    val payload = JSONObject()
                        .put("command", "pcm_chunk")
                        .put("streamId", streamId)
                        .put("sampleRate", sampleRate)
                        .put("pcmBase64", Base64.encodeToString(buffer.copyOf(read), Base64.NO_WRAP))
                    sendChunk(payload)
                    Thread.sleep(40)
                }
            } catch (error: Throwable) {
                NeroJsonLog.error(this, "NeroAudioCaptureService", "Audio streaming failed", error)
            } finally {
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
                closeSocket()
                streaming.set(false)
            }
        }, "nero-audio-stream").apply { start() }
        return true
    }

    private fun stopStreaming() {
        streaming.set(false)
        runCatching { recordingThread?.join(800) }
        recordingThread = null
        closeSocket()
    }

    private fun openSocket() {
        if (socket?.isConnected == true && socket?.isClosed == false) {
            return
        }
        closeSocket()
        socket = Socket().apply {
            connect(
                InetSocketAddress(InetAddress.getByName(NeroContract.LOCALHOST), NeroContract.Ports.VOICE_SERVICE),
                4_000,
            )
            soTimeout = 4_000
        }
        writer = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream()))
        reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
    }

    private fun sendChunk(payload: JSONObject) {
        NeroRetry.run {
            if (socket?.isConnected != true || socket?.isClosed == true) {
                openSocket()
            }
            val currentWriter = writer ?: throw IllegalStateException("Voice writer unavailable")
            val currentReader = reader ?: throw IllegalStateException("Voice reader unavailable")
            currentWriter.write(payload.toString())
            currentWriter.write("\n")
            currentWriter.flush()
            currentReader.readLine()
        }
    }

    private fun closeSocket() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { socket?.close() }
        writer = null
        reader = null
        socket = null
    }
}
