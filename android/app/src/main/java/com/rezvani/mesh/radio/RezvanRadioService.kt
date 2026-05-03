package com.rezvani.mesh.radio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class RezvanRadioService : Service() {

    private val binder = LocalBinder()
    private lateinit var wakeLock: PowerManager.WakeLock
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var meshCorePtr: Long = 0

    inner class LocalBinder : Binder() {
        fun getService(): RezvanRadioService = this@RezvanRadioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun getMeshCorePtr(): Long = meshCorePtr

    fun onPacketReceived(rawPacket: ByteArray, rssi: Int) {
        // stub
    }

    override fun onCreate() {
        super.onCreate()
        try {
            Log.i(TAG, "RezvanRadioService onCreate – notification disabled, only WAKE_LOCK")
            acquireWakeLock()
            // deliberately not showing any notification to test if that's the crash source
        } catch (e: Exception) {
            logCrash(e)
            throw e
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        isRunning.set(true)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isRunning.set(false)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RezvanMesh::RadioWakeLock")
        wakeLock.acquire(24 * 60 * 60 * 1000)
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
    }

    private fun logCrash(e: Exception) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val msg = "=== SERVICE CRASH ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===\n${sw}\n\n"
            val file = File(filesDir, "service_crash.txt")
            file.writeText(msg)
            Log.i(TAG, "Crash log written to ${file.absolutePath}")
        } catch (_: Exception) { }
    }

    companion object {
        private const val TAG = "RezvanRadioService"
    }
}
