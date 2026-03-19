package com.nerovision.production

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object VersionedStorage {
    private val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val counter = AtomicLong(0)

    fun writeJson(context: Context?, category: String, prefix: String, payload: JSONObject): File? {
        if (context == null) {
            return null
        }
        return runCatching {
            val dir = File(context.filesDir, "versioned/$category").apply { mkdirs() }
            val index = counter.incrementAndGet()
            val file = File(dir, "${prefix}_${formatter.format(Date())}_$index.json")
            file.writeText(payload.toString(2))
            trim(dir)
            file
        }.getOrNull()
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(NeroContract.Snapshot.KEEP_FILES).forEach { it.delete() }
    }
}
