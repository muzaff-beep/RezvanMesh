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
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        crashFile = File(dir, "rezvan_crashes.txt")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            saveCrash(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "Crash logger ready: ${crashFile?.absolutePath}")
    }

    private fun saveCrash(throwable: Throwable) {
        try {
            val file = crashFile ?: return
            file.parentFile?.mkdirs()
            val writer = FileWriter(file, true)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            writer.write("=== CRASH ${dateFormat.format(Date())} ===\n")
            throwable.printStackTrace(PrintWriter(writer))
            writer.write("\n\n")
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }
    }
}
