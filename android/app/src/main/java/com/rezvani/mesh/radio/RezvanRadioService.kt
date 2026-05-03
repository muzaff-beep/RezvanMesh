package com.rezvani.mesh.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rezvani.mesh.MainActivity
import com.rezvani.mesh.R
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
        // Not implemented
    }

    override fun onCreate() {
        super.onCreate()
        try {
            Log.i(TAG, "RezvanRadioService onCreate (notification only)")
            startForegroundWithNotification()
            acquireWakeLock()
        } catch (e: Exception) {
            Log.e(TAG, "FATAL in onCreate", e)
            throw e
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "RezvanRadioService onStartCommand")
        isRunning.set(true)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "RezvanRadioService onDestroy")
        isRunning.set(false)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.mesh_service_title))
            .setContentText(getString(R.string.mesh_service_running))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mesh_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.mesh_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RezvanMesh::RadioWakeLock"
        )
        wakeLock.acquire(24 * 60 * 60 * 1000)
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    companion object {
        private const val TAG = "RezvanRadioService"
        private const val CHANNEL_ID = "rezvan_mesh_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
