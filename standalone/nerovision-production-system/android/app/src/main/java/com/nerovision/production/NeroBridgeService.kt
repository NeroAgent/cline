package com.nerovision.production

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class NeroBridgeService : Service() {
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        NeroServiceRegistry.bridge.set(this)
        startForeground(
            NeroContract.Notifications.BRIDGE_ID,
            NeroNotifications.build(this, "NeroVision Bridge", getString(R.string.notification_bootstrap_text)),
        )
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startServer()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { acceptThread?.join(800) }
        executor.shutdownNow()
        NeroServiceRegistry.bridge.set(null)
        super.onDestroy()
    }

    private fun startServer() {
        if (running.get()) {
            return
        }
        running.set(true)
        acceptThread = Thread({
            try {
                serverSocket = ServerSocket(
                    NeroContract.Ports.ANDROID_BRIDGE,
                    50,
                    InetAddress.getByName(NeroContract.LOCALHOST),
                )
                NeroJsonLog.info(this, "NeroBridgeService", "Bridge socket listening", mapOf("port" to NeroContract.Ports.ANDROID_BRIDGE))
                while (running.get()) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleClient(socket) }
                }
            } catch (error: Throwable) {
                running.set(false)
                NeroJsonLog.error(this, "NeroBridgeService", "Bridge socket failed", error)
            }
        }, "nero-bridge-accept").apply { start() }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream()))
            while (running.get()) {
                val line = reader.readLine() ?: break
                val request = runCatching { JSONObject(line) }.getOrElse {
                    JSONObject().put("command", "invalid").put("raw", line)
                }
                val response = route(request)
                writer.write(response.toString())
                writer.write("\n")
                writer.flush()
            }
        }
    }

    private fun route(request: JSONObject): JSONObject {
        VersionedStorage.writeJson(this, "bridge-requests", "request", request)
        val command = request.optString("command")
        val response = try {
            when (command) {
                "health" -> JSONObject().put("ok", true).put("health", NeroServiceRegistry.healthJson())
                "dump_ui" -> NeroServiceRegistry.accessibility.get()?.dumpUiTreeJson()
                    ?: JSONObject().put("ok", false).put("error", "accessibility_unavailable")
                "perform_action" -> NeroServiceRegistry.accessibility.get()?.performVerifiedAction(request.optJSONObject("action"))
                    ?: JSONObject().put("ok", false).put("error", "accessibility_unavailable")
                "screen_capture" -> NeroServiceRegistry.projection.get()?.captureFrameBase64()
                    ?: JSONObject().put("ok", false).put("error", "projection_unavailable")
                "set_overlay_state" -> NeroServiceRegistry.overlay.get()?.applyState(request.optJSONObject("state"))
                    ?: JSONObject().put("ok", false).put("error", "overlay_unavailable")
                "audio_status" -> NeroServiceRegistry.audio.get()?.statusJson()
                    ?: JSONObject().put("ok", false).put("error", "audio_unavailable")
                "start_audio" -> NeroServiceRegistry.audio.get()?.setStreaming(true)
                    ?: JSONObject().put("ok", false).put("error", "audio_unavailable")
                "stop_audio" -> NeroServiceRegistry.audio.get()?.setStreaming(false)
                    ?: JSONObject().put("ok", false).put("error", "audio_unavailable")
                else -> JSONObject().put("ok", false).put("error", "unknown_command").put("command", command)
            }
        } catch (error: Throwable) {
            NeroJsonLog.error(this, "NeroBridgeService", "Command routing failed", error, mapOf("command" to command))
            JSONObject().put("ok", false).put("error", error.message.orEmpty())
        }
        VersionedStorage.writeJson(this, "bridge-responses", "response", response.put("command", command))
        return response
    }
}
