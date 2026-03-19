package com.nerovision.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accessibility service + socket server on port 9002.
 *
 * Provides the UI tree and executes actions (click, type, scroll, etc.)
 * requested by Python services over JSON-line protocol.
 *
 * Safety contract: every [AccessibilityNodeInfo] is null-checked,
 * refreshed before read, and recycled in a `finally` block.
 * Command handlers never throw — all exceptions are caught and
 * returned as `{"success":false, "detail":"..."}`.
 */
class NeroAccessibilityService : AccessibilityService() {

    private val running = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var actionThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var currentPackage: String = ""

    private val actionQueue =
        LinkedBlockingQueue<Pair<JSONObject, (JSONObject) -> Unit>>()

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        running.set(true)
        startSocketServer()
        startActionProcessor()
        Log.i(TAG, "NeroAccessibilityService connected")
    }

    override fun onDestroy() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverThread?.interrupt()
        actionThread?.interrupt()
        Log.i(TAG, "NeroAccessibilityService destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    // ── Events ───────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != currentPackage) {
                currentPackage = pkg
                Log.d(TAG, "Active package: $pkg")
            }

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) {
                val intent = Intent(ACTION_WINDOW_CHANGED).apply {
                    putExtra("package", pkg)
                    putExtra("event_type", event.eventType)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "onAccessibilityEvent error", e)
        }
    }

    // ── Socket server ────────────────────────────────────────────────

    private fun startSocketServer() {
        serverThread = Thread({
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                serverSocket!!.soTimeout = 1_000
                Log.i(TAG, "Socket server listening on 127.0.0.1:$SERVER_PORT")

                while (running.get()) {
                    val client: Socket = try {
                        serverSocket!!.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        if (running.get()) Log.w(TAG, "Accept error")
                        break
                    }
                    Thread({ handleClient(client) }, "AccSvc-Client").start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket server error", e)
                NeroApp.recordCrash(TAG, "Socket server error", e)
            }
        }, "AccSvc-Server").apply { isDaemon = true; start() }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = CLIENT_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)

            while (running.get()) {
                val line = reader.readLine() ?: break
                val req = try { JSONObject(line) } catch (_: Exception) {
                    sendResponse(writer, errorJson("invalid_json"))
                    continue
                }

                val type = req.optString("type", "")

                if (type == "ping") {
                    sendResponse(writer, JSONObject().apply {
                        put("status", "ok")
                        put("service", "accessibility")
                    })
                    continue
                }

                if (type == "get_tree") {
                    val tree = buildTree()
                    sendResponse(writer, JSONObject().apply {
                        put("tree", tree)
                    })
                    continue
                }

                val latch = java.util.concurrent.CountDownLatch(1)
                var result = errorJson("timeout")
                actionQueue.put(Pair(req) { resp ->
                    result = resp
                    latch.countDown()
                })
                latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                sendResponse(writer, result)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun sendResponse(writer: OutputStreamWriter, json: JSONObject) {
        try {
            writer.write(json.toString())
            writer.write("\n")
            writer.flush()
        } catch (_: Exception) {}
    }

    // ── Action processor ─────────────────────────────────────────────

    private fun startActionProcessor() {
        actionThread = Thread({
            while (running.get()) {
                val pair = try {
                    actionQueue.poll(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    continue
                } ?: continue

                val (req, callback) = pair
                val result = try {
                    executeCommand(req)
                } catch (e: Exception) {
                    Log.e(TAG, "Action execution error", e)
                    errorJson("internal_error: ${e.message}")
                }
                try { callback(result) } catch (_: Exception) {}
            }
        }, "AccSvc-Actions").apply { isDaemon = true; start() }
    }

    // ── Command dispatch ─────────────────────────────────────────────

    private fun executeCommand(req: JSONObject): JSONObject {
        val action = req.optJSONObject("action") ?: req
        val type = action.optString("type", action.optString("action", ""))

        return when (type) {
            "execute_action" -> {
                val inner = req.optJSONObject("action") ?: return errorJson("missing_action")
                executeCommand(inner)
            }
            "click_by_id" -> cmdClickById(action.optString("id", ""))
            "click_by_text" -> cmdClickByText(action.optString("text", ""))
            "click_coords" -> cmdClickCoords(
                action.optInt("x", -1), action.optInt("y", -1),
            )
            "scroll" -> cmdScroll(
                action.optString("direction", "down"),
                action.optInt("steps", 3),
            )
            "type_text" -> cmdTypeText(action.optString("text", ""))
            "back" -> cmdBack()
            "home" -> cmdHome()
            else -> errorJson("unknown_action: $type")
        }
    }

    // ── Tree dump ────────────────────────────────────────────────────

    private fun buildTree(): JSONObject {
        var root: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow ?: return JSONObject()
            return dumpNode(root, 0, intArrayOf(0))
        } catch (e: Exception) {
            Log.w(TAG, "buildTree error", e)
            return JSONObject()
        } finally {
            safeRecycle(root)
        }
    }

    private fun dumpNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        counter: IntArray,
    ): JSONObject {
        val obj = JSONObject()
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) return obj
        counter[0]++

        try {
            node.refresh()
        } catch (_: Exception) {}

        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            obj.put("resourceId", node.viewIdResourceName ?: "")
            obj.put("className", node.className?.toString() ?: "")
            obj.put("text", node.text?.toString() ?: "")
            obj.put("contentDescription", node.contentDescription?.toString() ?: "")
            obj.put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            obj.put("clickable", node.isClickable)
            obj.put("focusable", node.isFocusable)
            obj.put("scrollable", node.isScrollable)
            obj.put("enabled", node.isEnabled)

            val children = JSONArray()
            for (i in 0 until node.childCount) {
                var child: AccessibilityNodeInfo? = null
                try {
                    child = node.getChild(i) ?: continue
                    children.put(dumpNode(child, depth + 1, counter))
                } finally {
                    safeRecycle(child)
                }
            }
            obj.put("children", children)
        } catch (e: Exception) {
            Log.w(TAG, "dumpNode error at depth $depth", e)
        }

        return obj
    }

    // ── Action: click by resource ID ─────────────────────────────────

    private fun cmdClickById(id: String): JSONObject {
        if (id.isBlank()) return errorJson("missing id")
        var root: AccessibilityNodeInfo? = null
        var target: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow ?: return errorJson("no root window")
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            target = nodes?.firstOrNull()
            if (target == null) return errorJson("id not found: $id")
            target.refresh()
            if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return errorJson("click action failed for $id")
            }
            Thread.sleep(VERIFY_WAIT_MS)
            return successJson("clicked id=$id")
        } catch (e: Exception) {
            return errorJson("click_by_id error: ${e.message}")
        } finally {
            safeRecycle(target)
            safeRecycle(root)
        }
    }

    // ── Action: click by text ────────────────────────────────────────

    private fun cmdClickByText(text: String): JSONObject {
        if (text.isBlank()) return errorJson("missing text")
        var root: AccessibilityNodeInfo? = null
        var target: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow ?: return errorJson("no root window")
            val nodes = root.findAccessibilityNodeInfosByText(text)
            target = nodes?.firstOrNull { it.isClickable }
                ?: nodes?.firstOrNull()
            if (target == null) return errorJson("text not found: $text")
            target.refresh()
            if (!target.isClickable) {
                var parent = target.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        safeRecycle(parent)
                        Thread.sleep(VERIFY_WAIT_MS)
                        return if (clicked) successJson("clicked parent of text=$text")
                        else errorJson("click failed on parent of $text")
                    }
                    val prev = parent
                    parent = parent.parent
                    safeRecycle(prev)
                }
                safeRecycle(parent)
                return errorJson("no clickable ancestor for text=$text")
            }
            if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return errorJson("click action failed for text=$text")
            }
            Thread.sleep(VERIFY_WAIT_MS)
            return successJson("clicked text=$text")
        } catch (e: Exception) {
            return errorJson("click_by_text error: ${e.message}")
        } finally {
            safeRecycle(target)
            safeRecycle(root)
        }
    }

    // ── Action: click coordinates ────────────────────────────────────

    private fun cmdClickCoords(x: Int, y: Int): JSONObject {
        if (x < 0 || y < 0) return errorJson("invalid coordinates: $x,$y")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return errorJson("click_coords requires API 24+")
        }
        try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            val latch = java.util.concurrent.CountDownLatch(1)
            var gestureOk = false
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureOk = true; latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            latch.await(2, TimeUnit.SECONDS)
            Thread.sleep(VERIFY_WAIT_MS)
            return if (gestureOk) successJson("clicked coords ($x,$y)")
            else errorJson("gesture cancelled at ($x,$y)")
        } catch (e: Exception) {
            return errorJson("click_coords error: ${e.message}")
        }
    }

    // ── Action: scroll ───────────────────────────────────────────────

    private fun cmdScroll(direction: String, steps: Int): JSONObject {
        var root: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow ?: return errorJson("no root window")
            val scrollable = findScrollable(root)
            if (scrollable != null) {
                val action = when (direction) {
                    "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> return errorJson("invalid direction: $direction")
                }
                var scrolled = false
                for (i in 0 until steps.coerceIn(1, 10)) {
                    if (scrollable.performAction(action)) scrolled = true
                    Thread.sleep(150)
                }
                safeRecycle(scrollable)
                return if (scrolled) successJson("scrolled $direction x$steps")
                else errorJson("scroll $direction failed")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val dm = resources.displayMetrics
                val cx = dm.widthPixels / 2f
                val startY: Float
                val endY: Float
                when (direction) {
                    "down" -> { startY = dm.heightPixels * 0.7f; endY = dm.heightPixels * 0.3f }
                    "up" -> { startY = dm.heightPixels * 0.3f; endY = dm.heightPixels * 0.7f }
                    "left" -> { startY = cx; endY = cx }
                    "right" -> { startY = cx; endY = cx }
                    else -> return errorJson("invalid direction: $direction")
                }
                val startX = if (direction in listOf("left", "right")) {
                    if (direction == "left") dm.widthPixels * 0.7f else dm.widthPixels * 0.3f
                } else cx
                val finalEndX = if (direction in listOf("left", "right")) {
                    if (direction == "left") dm.widthPixels * 0.3f else dm.widthPixels * 0.7f
                } else cx

                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(finalEndX, endY)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
                    .build()
                val latch = java.util.concurrent.CountDownLatch(1)
                var ok = false
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
                    override fun onCancelled(g: GestureDescription?) { latch.countDown() }
                }, null)
                latch.await(2, TimeUnit.SECONDS)
                return if (ok) successJson("gesture scrolled $direction")
                else errorJson("gesture scroll $direction cancelled")
            }

            return errorJson("no scrollable node and gestures unavailable")
        } catch (e: Exception) {
            return errorJson("scroll error: ${e.message}")
        } finally {
            safeRecycle(root)
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            var child: AccessibilityNodeInfo? = null
            try {
                child = node.getChild(i) ?: continue
                val found = findScrollable(child, depth + 1)
                if (found != null) return found
            } finally {
                safeRecycle(child)
            }
        }
        return null
    }

    // ── Action: type text ────────────────────────────────────────────

    private fun cmdTypeText(text: String): JSONObject {
        if (text.isEmpty()) return errorJson("empty text")
        var root: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow ?: return errorJson("no root window")
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                try {
                    focused.refresh()
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text,
                        )
                    }
                    val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    Thread.sleep(VERIFY_WAIT_MS)
                    return if (ok) successJson("typed text (${text.length} chars)")
                    else errorJson("ACTION_SET_TEXT failed")
                } finally {
                    safeRecycle(focused)
                }
            }
            return errorJson("no focused input field")
        } catch (e: Exception) {
            return errorJson("type_text error: ${e.message}")
        } finally {
            safeRecycle(root)
        }
    }

    // ── Action: back / home ──────────────────────────────────────────

    private fun cmdBack(): JSONObject {
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        return if (ok) successJson("back") else errorJson("back failed")
    }

    private fun cmdHome(): JSONObject {
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        return if (ok) successJson("home") else errorJson("home failed")
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun successJson(detail: String) = JSONObject().apply {
        put("status", "ok")
        put("success", true)
        put("detail", detail)
    }

    private fun errorJson(detail: String) = JSONObject().apply {
        put("status", "error")
        put("success", false)
        put("detail", detail)
    }

    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        try {
            @Suppress("DEPRECATION")
            node?.recycle()
        } catch (_: Exception) {}
    }

    // ── Constants ────────────────────────────────────────────────────

    companion object {
        private const val TAG = "NeroAccessibility"
        private const val SERVER_PORT = 9002
        private const val CLIENT_TIMEOUT_MS = 30_000

        private const val MAX_DEPTH = 12
        private const val MAX_NODES = 500
        private const val VERIFY_WAIT_MS = 500L
        private const val ACTION_TIMEOUT_MS = 10_000L

        const val ACTION_WINDOW_CHANGED = "com.nerovision.WINDOW_CHANGED"
    }
}
