package com.rezvani.mesh.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val MAX_LINES = 100
    private var crashFile: File? = null
    private val crashLines = Collections.synchronizedList(mutableListOf<String>())

    fun init(context: Context) {
        // Use internal storage — app can always read it
        crashFile = File(context.filesDir, "rezvan_crashes.txt")

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            saveCrash(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "Crash logger initialized at ${crashFile?.absolutePath}")
    }

    private fun saveCrash(throwable: Throwable) {
        try {
            val file = crashFile ?: return
            val writer = FileWriter(file, true)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val header = "=== CRASH ${dateFormat.format(Date())} ==="
            val sw = java.io.StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()

            writer.write("$header\n")
            writer.write(stackTrace)
            writer.write("\n\n")
            writer.close()

            // Keep in memory for in-app display
            synchronized(crashLines) {
                crashLines.add(header)
                crashLines.addAll(stackTrace.lines())
                crashLines.add("")
                while (crashLines.size > MAX_LINES) {
                    crashLines.removeAt(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }
    }

    /**
     * Returns crash history as a single string for in-app display.
     */
    fun getCrashLog(): String {
        // Try to read from file first
        val file = crashFile
        if (file != null && file.exists()) {
            return file.readText().ifEmpty { "No crashes recorded" }
        }
        // Fallback to in-memory
        return if (crashLines.isEmpty()) {
            "No crashes recorded"
        } else {
            crashLines.joinToString("\n")
        }
    }

    /**
     * Clear crash history.
     */
    fun clearCrashLog() {
        crashFile?.delete()
        synchronized(crashLines) { crashLines.clear() }
    }
}
