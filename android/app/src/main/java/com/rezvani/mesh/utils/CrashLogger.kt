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
    private var crashFile: File? = null

    fun init(context: Context) {
        // Use the exact path you provided
        val documentsDir = File("/storage/emulated/0/Documents")
        crashFile = File(documentsDir, "rezvan_crashes.txt")
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
            writer.write("=== CRASH ${dateFormat.format(Date())} ===\n")
            throwable.printStackTrace(PrintWriter(writer))
            writer.write("\n\n")
            writer.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }
    }

    fun getCrashLog(): String {
        val file = crashFile ?: return "Crash logger not initialized"
        return if (file.exists()) file.readText() else "No crashes recorded"
    }
}
