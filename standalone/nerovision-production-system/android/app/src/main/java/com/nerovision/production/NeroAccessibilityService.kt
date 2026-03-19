package com.nerovision.production

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NeroAccessibilityService : AccessibilityService() {
    private val latestSnapshot = AtomicReference(JSONObject().put("available", false))

    override fun onServiceConnected() {
        super.onServiceConnected()
        NeroServiceRegistry.accessibility.set(this)
        NeroJsonLog.info(this, "NeroAccessibilityService", "Accessibility service connected")
        refreshSnapshot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }
        refreshSnapshot()
    }

    override fun onInterrupt() {
        NeroJsonLog.warn(this, "NeroAccessibilityService", "Accessibility service interrupted")
    }

    override fun onDestroy() {
        NeroServiceRegistry.accessibility.set(null)
        super.onDestroy()
    }

    fun dumpUiTreeJson(): JSONObject {
        val root = rootInActiveWindow
        if (root == null) {
            return JSONObject()
                .put("available", false)
                .put("reason", "root_in_active_window_null")
        }
        val snapshot = JSONObject()
            .put("available", true)
            .put("generatedAt", System.currentTimeMillis())
            .put("packageName", root.packageName?.toString().orEmpty())
            .put("root", buildNodeJson(root, 0))
        snapshot.put("signature", snapshot.optJSONObject("root")?.toString()?.hashCode() ?: 0)
        latestSnapshot.set(snapshot)
        VersionedStorage.writeJson(this, "android-ui", "ui_snapshot", snapshot)
        return snapshot
    }

    fun performVerifiedAction(action: JSONObject?): JSONObject {
        if (action == null) {
            return JSONObject().put("ok", false).put("error", "action_missing")
        }
        val before = dumpUiTreeJson()
        val command = action.optString("type")
        val executed = executeAction(action)
        if (!executed) {
            return JSONObject()
                .put("ok", false)
                .put("verified", false)
                .put("before", before)
                .put("error", "execution_failed")
        }
        val after = waitForUiChange(before)
        val verified = verify(command, action, before, after)
        val response = JSONObject()
            .put("ok", executed)
            .put("verified", verified)
            .put("before", before)
            .put("after", after)
        VersionedStorage.writeJson(this, "android-actions", "action_result", response.put("action", action))
        return response
    }

    private fun refreshSnapshot() {
        runCatching { dumpUiTreeJson() }
            .onFailure { error ->
                NeroJsonLog.error(this, "NeroAccessibilityService", "Failed to refresh snapshot", error)
            }
    }

    private fun buildNodeJson(node: AccessibilityNodeInfo?, depth: Int): JSONObject {
        if (node == null) {
            return JSONObject().put("available", false)
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val result = JSONObject()
            .put("className", node.className?.toString().orEmpty())
            .put("viewId", node.viewIdResourceName.orEmpty())
            .put("packageName", node.packageName?.toString().orEmpty())
            .put("text", node.text?.toString().orEmpty())
            .put("contentDescription", node.contentDescription?.toString().orEmpty())
            .put("clickable", node.isClickable)
            .put("enabled", node.isEnabled)
            .put("focused", node.isFocused)
            .put("checkable", node.isCheckable)
            .put("bounds", JSONObject()
                .put("left", bounds.left)
                .put("top", bounds.top)
                .put("right", bounds.right)
                .put("bottom", bounds.bottom))
        if (depth >= NeroContract.Snapshot.MAX_TREE_DEPTH) {
            result.put("truncated", true)
            return result
        }
        val children = JSONArray()
        val childCount = minOf(node.childCount, NeroContract.Snapshot.MAX_TREE_CHILDREN)
        for (index in 0 until childCount) {
            children.put(buildNodeJson(node.getChild(index), depth + 1))
        }
        result.put("children", children)
        return result
    }

    private fun executeAction(action: JSONObject): Boolean {
        return when (action.optString("type")) {
            "click" -> performClickAction(action)
            "set_text" -> performSetTextAction(action)
            "scroll" -> performScrollAction(action)
            "global_action" -> performGlobalAction(action.optInt("globalAction", GLOBAL_ACTION_BACK))
            else -> false
        }
    }

    private fun performClickAction(action: JSONObject): Boolean {
        val target = findTargetNode(action) ?: return fallbackGestureTap(action)
        var current: AccessibilityNodeInfo? = target
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return fallbackGestureTap(action)
    }

    private fun fallbackGestureTap(action: JSONObject): Boolean {
        val bounds = action.optJSONObject("bounds")
        val centerX = bounds?.optInt("centerX") ?: action.optInt("x", -1)
        val centerY = bounds?.optInt("centerY") ?: action.optInt("y", -1)
        if (centerX < 0 || centerY < 0) {
            return false
        }
        val latch = CountDownLatch(1)
        var completed = false
        val path = Path().apply { moveTo(centerX.toFloat(), centerY.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 30))
            .build()
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null,
        )
        latch.await(500, TimeUnit.MILLISECONDS)
        return completed
    }

    private fun performSetTextAction(action: JSONObject): Boolean {
        val target = findTargetNode(action) ?: return false
        val text = action.optString("text", "")
        if (text.isEmpty()) {
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun performScrollAction(action: JSONObject): Boolean {
        val target = findTargetNode(action) ?: return false
        val direction = action.optString("direction", "forward")
        val actionId = if (direction == "backward") {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return target.performAction(actionId)
    }

    private fun findTargetNode(action: JSONObject): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val searchText = action.optString("text").trim()
        val contentDescription = action.optString("contentDescription").trim()
        val viewId = action.optString("viewId").trim()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val nodeText = node.text?.toString().orEmpty().trim()
            val nodeDescription = node.contentDescription?.toString().orEmpty().trim()
            val nodeViewId = node.viewIdResourceName.orEmpty().trim()
            val matches = when {
                searchText.isNotEmpty() && nodeText.equals(searchText, ignoreCase = true) -> true
                contentDescription.isNotEmpty() && nodeDescription.equals(contentDescription, ignoreCase = true) -> true
                viewId.isNotEmpty() && nodeViewId == viewId -> true
                else -> false
            }
            if (matches) {
                return node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return null
    }

    private fun waitForUiChange(before: JSONObject): JSONObject {
        val beforeSignature = before.optInt("signature")
        val deadline = SystemClock.uptimeMillis() + NeroContract.Snapshot.VERIFY_WINDOW_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val current = dumpUiTreeJson()
            if (current.optInt("signature") != beforeSignature) {
                return current
            }
            SystemClock.sleep(NeroContract.Snapshot.POLL_INTERVAL_MS)
        }
        return latestSnapshot.get()
    }

    private fun verify(command: String, action: JSONObject, before: JSONObject, after: JSONObject): Boolean {
        if (!after.optBoolean("available", false)) {
            return false
        }
        if (before.optInt("signature") != after.optInt("signature")) {
            return true
        }
        return when (command) {
            "set_text" -> after.toString().contains(action.optString("text"))
            else -> false
        }
    }
}
