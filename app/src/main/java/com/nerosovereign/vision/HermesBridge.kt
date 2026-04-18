package com.nerosovereign.vision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.msgpack.core.MessagePack
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

class HermesBridge(context: Context) {
    private val contextRef = WeakReference(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconnectMutex = Mutex()
    private val pendingResponses = ConcurrentHashMap<Long, CompletableDeferred<ByteArray>>()

    private var keepAliveJob: Job? = null
    private var webSocket: WebSocket? = null
    private var wsOpenSignal: CompletableDeferred<Unit>? = null
    private var reconnectAttempts = 0

    private val wsClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "HermesBridge"
        private const val UNIX_SOCKET_PATH = "/data/data/com.termux/files/usr/tmp/hermes.sock"
        private const val FALLBACK_WS_URL = "ws://localhost:8765"
        private const val MAX_RECONNECT_RETRIES = 5
    }

    data class HermesEnvelope(
        val dest: String,
        val payload: ByteArray,
        val timestamp: Long
    )

    fun start() {
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = scope.launch {
            while (isActive) {
                try {
                    sendPing()
                } catch (error: Exception) {
                    Log.w(TAG, "Keep-alive ping failed", error)
                }
                delay(30_000L)
            }
        }
    }

    fun stop() {
        keepAliveJob?.cancel()
        webSocket?.close(1000, "shutdown")
        scope.cancel()
    }

    fun isTermuxInstalled(): Boolean {
        val context = contextRef.get() ?: return false
        return runCatching {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        }.getOrDefault(false)
    }

    fun canUseUnixSocket(): Boolean = File(UNIX_SOCKET_PATH).exists()

    suspend fun relay(dest: String, payload: ByteArray): Result<ByteArray> {
        if (!isTermuxInstalled()) {
            return Result.failure(IllegalStateException("Termux is not installed"))
        }

        val timestamp = System.currentTimeMillis()
        val envelope = HermesEnvelope(dest = dest, payload = payload, timestamp = timestamp)
        val packed = encodeEnvelope(envelope)

        val unixError = try {
            return Result.success(sendViaUnixSocket(packed))
        } catch (error: Exception) {
            Log.w(TAG, "Unix socket relay failed, attempting websocket fallback", error)
            error
        }

        val wsError = try {
            return Result.success(sendViaWebSocket(envelope, packed))
        } catch (error: Exception) {
            error
        }

        sendRelayBroadcastFallback(packed)
        return Result.failure(wsError.ifMessageNotBlank() ?: unixError)
    }

    private suspend fun sendPing() {
        val pingEnvelope = HermesEnvelope(
            dest = "nero_core",
            payload = "ping".toByteArray(),
            timestamp = System.currentTimeMillis()
        )
        val packed = encodeEnvelope(pingEnvelope)

        try {
            sendViaUnixSocket(packed)
        } catch (_: Exception) {
            ensureWebSocketConnected()
            webSocket?.send(packed.toByteString())
        }
    }

    private suspend fun sendViaUnixSocket(packedEnvelope: ByteArray): ByteArray {
        if (!canUseUnixSocket()) {
            throw IllegalStateException("Hermes unix socket not found at $UNIX_SOCKET_PATH")
        }

        val socket = LocalSocket()
        return try {
            withTimeout(5_000L) {
                socket.connect(LocalSocketAddress(UNIX_SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM))
                val output = DataOutputStream(socket.outputStream)
                val input = DataInputStream(socket.inputStream)

                output.writeInt(packedEnvelope.size)
                output.write(packedEnvelope)
                output.flush()

                val responseSize = input.readInt()
                require(responseSize in 0..2_000_000) { "Invalid Hermes response size: $responseSize" }
                val responseBytes = ByteArray(responseSize)
                input.readFully(responseBytes)
                responseBytes
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun sendViaWebSocket(envelope: HermesEnvelope, packedEnvelope: ByteArray): ByteArray {
        ensureWebSocketConnected()
        val ws = webSocket ?: throw IllegalStateException("WebSocket not connected")
        val responseDeferred = CompletableDeferred<ByteArray>()
        pendingResponses[envelope.timestamp] = responseDeferred

        val sent = ws.send(packedEnvelope.toByteString())
        if (!sent) {
            pendingResponses.remove(envelope.timestamp)
            throw IllegalStateException("Failed to send websocket payload")
        }

        return try {
            withTimeout(10_000L) { responseDeferred.await() }
        } finally {
            pendingResponses.remove(envelope.timestamp)
        }
    }

    private suspend fun ensureWebSocketConnected() = reconnectMutex.withLock {
        if (wsOpenSignal?.isCompleted == true && webSocket != null) return

        wsOpenSignal = CompletableDeferred()
        val request = Request.Builder().url(FALLBACK_WS_URL).build()
        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                wsOpenSignal?.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { decodeEnvelope(bytes.toByteArray()) }
                    .onSuccess { decoded ->
                        pendingResponses[decoded.timestamp]?.complete(decoded.payload)
                    }
                    .onFailure { Log.e(TAG, "Failed to decode MessagePack websocket response", it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsOpenSignal?.completeExceptionally(t)
                failAllPending(t)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                failAllPending(IllegalStateException("WebSocket closed: $code - $reason"))
                scheduleReconnect()
            }
        })

        withTimeout(10_000L) {
            wsOpenSignal?.await()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_RETRIES) return
        reconnectAttempts++
        val delayMillis = min(30_000.0, 1000.0 * 2.0.pow((reconnectAttempts - 1).toDouble())).toLong()
        scope.launch {
            delay(delayMillis)
            try {
                ensureWebSocketConnected()
            } catch (error: Exception) {
                Log.w(TAG, "Reconnect attempt failed", error)
            }
        }
    }

    private fun failAllPending(error: Throwable) {
        pendingResponses.values.forEach { deferred ->
            if (!deferred.isCompleted) deferred.completeExceptionally(error)
        }
        pendingResponses.clear()
        webSocket = null
        wsOpenSignal = null
    }

    private fun sendRelayBroadcastFallback(payload: ByteArray) {
        val context = contextRef.get() ?: return
        val intent = Intent("com.termux.hermes.RELAY")
            .setPackage("com.termux")
            .putExtra("payload", payload)
        context.sendBroadcast(intent)
    }

    private fun encodeEnvelope(envelope: HermesEnvelope): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(3)
        packer.packString("dest")
        packer.packString(envelope.dest)
        packer.packString("payload")
        packer.packBinaryHeader(envelope.payload.size)
        packer.writePayload(envelope.payload)
        packer.packString("timestamp")
        packer.packLong(envelope.timestamp)
        packer.close()
        return packer.toByteArray()
    }

    private fun decodeEnvelope(raw: ByteArray): HermesEnvelope {
        val unpacker = MessagePack.newDefaultUnpacker(raw)
        var dest = ""
        var payload = ByteArray(0)
        var timestamp = 0L

        val mapSize = unpacker.unpackMapHeader()
        repeat(mapSize) {
            when (val key = unpacker.unpackString()) {
                "dest" -> dest = unpacker.unpackString()
                "payload" -> {
                    val size = unpacker.unpackBinaryHeader()
                    payload = unpacker.readPayload(size)
                }
                "timestamp" -> timestamp = unpacker.unpackLong()
                else -> {
                    Log.w(TAG, "Unknown MessagePack key: $key")
                    unpacker.skipValue()
                }
            }
        }
        unpacker.close()
        return HermesEnvelope(dest = dest, payload = payload, timestamp = timestamp)
    }
}

/**
 * Python reference for Termux `nero_core.py` side MessagePack handling.
 *
 * ```python
 * # pip install msgpack
 * import msgpack
 * import socket
 * import struct
 *
 * sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
 * sock.bind("/data/data/com.termux/files/usr/tmp/hermes.sock")
 * sock.listen(1)
 *
 * while True:
 *     conn, _ = sock.accept()
 *     with conn:
 *         size_raw = conn.recv(4)
 *         if len(size_raw) < 4:
 *             continue
 *         size = struct.unpack(">I", size_raw)[0]
 *         payload = conn.recv(size)
 *         envelope = msgpack.unpackb(payload, raw=False)
 *         # envelope: {"dest": "...", "payload": b"...", "timestamp": 123}
 *         response = msgpack.packb({
 *             "dest": envelope.get("dest", "nero_core"),
 *             "payload": b"ok",
 *             "timestamp": envelope.get("timestamp", 0)
 *         }, use_bin_type=True)
 *         conn.sendall(struct.pack(">I", len(response)) + response)
 * ```
 */
class TermuxRelayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "com.termux.hermes.RELAY") return
        val payload = intent.getByteArrayExtra("payload") ?: return

        val serviceIntent = Intent(context, NeroOverlayService::class.java)
            .setAction(NeroOverlayService.ACTION_TERMUX_RELAY)
            .putExtra(NeroOverlayService.EXTRA_TERMUX_PAYLOAD, payload)
        runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
    }
}

private fun Throwable.ifMessageNotBlank(): Throwable? {
    return if (message.isNullOrBlank()) null else this
}
