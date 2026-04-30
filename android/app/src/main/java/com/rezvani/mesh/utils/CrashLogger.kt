package com.rezvani.mesh.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

object CrashLogger {
    private const val TAG = "CrashLogger"
    private var appContext: Context? = null
    private var crashFile: File? = null

    fun init(context: Context) {
        appContext = context.applicationContext

        // Try internal files dir as primary — always writable, accessible via ADB
        val dir = context.filesDir
        crashFile = File(dir, "rezvan_crashes.txt")
        Log.i(TAG, "Crash log location: ${crashFile?.absolutePath}")

        // Also attempt to create in public Documents for user access
        createPublicLogFile()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            saveCrash(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun createPublicLogFile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "rezvan_crashes.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
                }
                val uri = appContext?.contentResolver?.insert(
                    MediaStore.Files.getContentUri("external"),
                    values
                )
                if (uri != null) {
                    Log.i(TAG, "Public crash log created at Documents/rezvan_crashes.txt")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not create public crash log: ${e.message}")
            }
        } else {
            // API 28 and below: direct file access still works
            try {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                if (!docsDir.exists()) docsDir.mkdirs()
                val publicFile = File(docsDir, "rezvan_crashes.txt")
                publicFile.createNewFile()
                Log.i(TAG, "Public crash log at ${publicFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not create public crash log: ${e.message}")
            }
        }
    }

    private fun saveCrash(throwable: Throwable) {
        // Always write to internal file (ADB accessible)
        writeToFile(crashFile, throwable)

        // Also attempt to write to public Documents file
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendToMediaStore(throwable)
        } else {
            try {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val publicFile = File(docsDir, "rezvan_crashes.txt")
                writeToFile(publicFile, throwable)
            } catch (_: Exception) {}
        }
    }

    private fun writeToFile(file: File?, throwable: Throwable) {
        try {
            val f = file ?: return
            f.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            val writer = FileWriter(f, true)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            writer.write("=== CRASH ${dateFormat.format(Date())} ===\n")
            throwable.printStackTrace(PrintWriter(writer))
            writer.write("\n\n")
            writer.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }
    }

    private fun appendToMediaStore(throwable: Throwable) {
        try {
            val context = appContext ?: return
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val crashText = buildString {
                append("=== CRASH ${dateFormat.format(Date())} ===\n")
                val sw = java.io.StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                append(sw.toString())
                append("\n\n")
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "rezvan_crashes.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
            }

            // Try to find existing file
            val collection = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val args = arrayOf("rezvan_crashes.txt")

            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val uri = MediaStore.Files.getContentUri("external", id)
                    context.contentResolver.openOutputStream(uri, "wa")?.use { os ->
                        os.write(crashText.toByteArray())
                    }
                } else {
                    val uri = context.contentResolver.insert(collection, values)
                    uri?.let {
                        context.contentResolver.openOutputStream(it, "w")?.use { os ->
                            os.write(crashText.toByteArray())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to MediaStore", e)
        }
    }

    fun getCrashLog(): String {
        val file = crashFile
        return if (file != null && file.exists()) {
            file.readText()
        } else {
            "No crashes recorded. Pull internal log via: adb shell run-as com.rezvani.mesh cat files/rezvan_crashes.txt"
        }
    }
}
