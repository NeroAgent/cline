package com.nerovision.production

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NeroJsonLog {
    private val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    fun info(context: Context?, tag: String, message: String, extras: Map<String, Any?> = emptyMap()) {
        log(context, "INFO", tag, message, extras)
    }

    fun warn(context: Context?, tag: String, message: String, extras: Map<String, Any?> = emptyMap()) {
        log(context, "WARN", tag, message, extras)
    }

    fun error(
        context: Context?,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        extras: Map<String, Any?> = emptyMap(),
    ) {
        val merged = extras.toMutableMap()
        if (throwable != null) {
            merged["error"] = throwable.javaClass.simpleName
            merged["details"] = throwable.message
        }
        log(context, "ERROR", tag, message, merged)
    }

    private fun log(context: Context?, level: String, tag: String, message: String, extras: Map<String, Any?>) {
        val payload = JSONObject()
            .put("timestamp", formatter.format(Date()))
            .put("level", level)
            .put("tag", tag)
            .put("message", message)
            .put("appVersion", NeroContract.VERSION)
        extras.forEach { (key, value) -> payload.put(key, value) }
        val text = payload.toString()
        when (level) {
            "ERROR" -> Log.e(tag, text)
            "WARN" -> Log.w(tag, text)
            else -> Log.i(tag, text)
        }
        writeToFile(context, text)
    }

    private fun writeToFile(context: Context?, text: String) {
        if (context == null) {
            return
        }
        runCatching {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            File(dir, "runtime.log").appendText(text + "\n")
        }
    }
}
