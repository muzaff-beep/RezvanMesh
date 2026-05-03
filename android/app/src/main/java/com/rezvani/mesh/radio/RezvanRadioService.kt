package com.rezvani.mesh.radio

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class RezvanRadioService : Service() {

    private val binder = LocalBinder()
    private val isRunning = AtomicBoolean(false)

    inner class LocalBinder : Binder() {
        fun getService(): RezvanRadioService = this@RezvanRadioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // Keep these methods so that MeshServiceConnection and RadioControllerImpl compile
    fun getMeshCorePtr(): Long = 0L

    fun onPacketReceived(rawPacket: ByteArray, rssi: Int) {
        // Not implemented in this minimal test
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "RezvanRadioService onCreate - bare minimum, no crash expected")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "RezvanRadioService onStartCommand")
        isRunning.set(true)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "RezvanRadioService onDestroy")
        isRunning.set(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RezvanRadioService"
    }
}
