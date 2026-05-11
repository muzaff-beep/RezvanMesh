package com.rezvani.mesh.utils

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object DiagLogger {

    enum class Level { VERBOSE, INFO, WARN, ERROR }

    data class DiagEntry(
        val timestamp: Long,
        val sessionId: String,
        val tag: String,
        val level: Level,
        val message: String
    ) {
        fun formatted(): String {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
            return "$ts  [$tag/${level.name}] $message"
        }
    }

    private val sessionId = UUID.randomUUID().toString().take(8)
    private var appContext: Context? = null

    private val _entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    val entries: StateFlow<List<DiagEntry>> = _entries.asStateFlow()

    /** One-shot log – fluent API */
    fun log(context: Context, msg: String) {
        log(context, "MAIN", Level.INFO, msg)
    }
    fun log(context: Context, tag: String, level: Level, msg: String) {
        val entry = DiagEntry(System.currentTimeMillis(), sessionId, tag, level, msg)
        _entries.value = (_entries.value + entry).takeLast(2000)

        // ERROR logs are flushed immediately to survive crashes
        appendToDisk(context, entry, flush = level == Level.ERROR)
    }

    fun err(context: Context, tag: String, msg: String, t: Throwable? = null) {
        val full = if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            "$msg\n${sw}"
        } else msg
        log(context, tag, Level.ERROR, full)
    }

    /** Keep older call sites working */
    fun ble(msg: String) { appContext?.let { log(it, "BLE", Level.INFO, msg) } }
    fun rust(msg: String) { appContext?.let { log(it, "RUST", Level.INFO, msg) } }

    // ---- disk persistence ----
    private fun appendToDisk(context: Context, entry: DiagEntry, flush: Boolean) {
        try {
            val filename = "diag.txt"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it, "wa")?.use { os ->
                    os.write("${entry.formatted()}\n".toByteArray())
                    if (flush) os.flush()
                }
            }
        } catch (_: Exception) { /* cannot log, would recurse */ }
    }
}
