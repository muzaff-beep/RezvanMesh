package com.rezvani.mesh.utils

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

object DiagLogger {

    // Live log for UI display
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    fun log(context: Context, msg: String) {
        // Write to file as before
        try {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val line = "$ts  $msg"
            val filename = "diag.txt"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            uri?.let {
                context.contentResolver.openOutputStream(it, "wa")?.use { os ->
                    os.write("$line\n".toByteArray())
                }
            }
        } catch (_: Exception) {}

        // Update live list
        val currentList = _logLines.value.toMutableList()
        currentList.add(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg)
        // Keep last 200 entries to avoid memory issues
        if (currentList.size > 200) {
            currentList.removeAt(0)
        }
        _logLines.value = currentList
    }
}
