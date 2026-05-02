package com.rezvani.mesh.utils

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object CrashLogger {
    private const val TAG = "CrashLogger"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        writeStartupLog()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            saveCrash(throwable)
            dumpLogcat()
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "Crash logger initialized")
    }

    private fun writeStartupLog() {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val msg = "=== REZVAN STARTUP $timestamp ===\nApp launched.\n\n"
            writeToDownloads("rezvan_startup_${System.currentTimeMillis()}.txt", msg)
        } catch (e: Exception) {
            Log.e(TAG, "startup log failed", e)
        }
    }

    private fun saveCrash(throwable: Throwable) {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val header = "=== CRASH ${dateFormat.format(Date())} ===\n"
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val body = sw.toString()
            writeToDownloads("rezvan_crash_${System.currentTimeMillis()}.txt", header + body + "\n\n")
        } catch (e: Exception) {
            Log.e(TAG, "crash log failed", e)
        }
    }

    private fun dumpLogcat() {
        try {
            val timestamp = System.currentTimeMillis()
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "300"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            sb.appendLine("=== LOGCAT ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===")
            reader.forEachLine { sb.appendLine(it) }
            reader.close()
            process.waitFor()
            writeToDownloads("rezvan_logcat_$timestamp.txt", sb.toString())
        } catch (e: Exception) {
            writeToDownloads("rezvan_logcat_error_${System.currentTimeMillis()}.txt", "Logcat failed: ${e.message}")
        }
    }

    private fun writeToDownloads(filename: String, content: String) {
        val ctx = appContext ?: return
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                ctx.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(content.toByteArray())
                    os.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeToDownloads failed: $filename", e)
        }
    }
}
