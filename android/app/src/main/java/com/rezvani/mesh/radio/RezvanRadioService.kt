package com.rezvani.mesh.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rezvani.mesh.MainActivity
import com.rezvani.mesh.MeshCore
import com.rezvani.mesh.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that manages the mesh radio lifecycle.
 * 
 * Responsibilities:
 * - Initialize and hold the native MeshCore pointer.
 * - Run periodic tick() every 1 second to drive the mesh engine.
 * - Manage WakeLock to keep CPU alive during mesh operations.
 * - Expose a LocalBinder for Team D to retrieve the native pointer.
 * - Update battery information to the native core.
 */
class RezvanRadioService : Service() {

    private val binder = LocalBinder()
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var radioController: RadioController
    private val tickHandler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var meshCorePtr: Long = 0

    /**
     * Binder class allowing Team D's UI to access the service instance
     * and retrieve the native mesh core pointer.
     */
    inner class LocalBinder : Binder() {
        fun getService(): RezvanRadioService = this@RezvanRadioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Returns the current native mesh core pointer.
     * Called by Team D to send messages and query power state.
     */
    fun getMeshCorePtr(): Long = meshCorePtr

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "RezvanRadioService onCreate")

        startForegroundWithNotification()
        acquireWakeLock()
        radioController = RadioControllerImpl(this)

        // Initialize ActionDispatcher with application context
        ActionDispatcher.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "RezvanRadioService onStartCommand")

        if (!isRunning.get()) {
            isRunning.set(true)

            val seed = loadIdentitySeed()
            val storagePath = filesDir.absolutePath

            meshCorePtr = MeshCore.nativeInit(seed, storagePath)
            Log.i(TAG, "MeshCore initialized, ptr = $meshCorePtr")

            startPeriodicTick()
            startWifiServerIfNeeded()
        }

        // If the service is killed, restart with the last intent
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "RezvanRadioService onDestroy")
        isRunning.set(false)
        tickHandler.removeCallbacksAndMessages(null)

        if (meshCorePtr != 0L) {
            MeshCore.nativeDestroy(meshCorePtr)
            meshCorePtr = 0
        }

        radioController.onDestroy()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Called by RadioControllerImpl when a raw packet is received from BLE or WiFi.
     * Forwards to native core for processing.
     */
    fun onPacketReceived(rawPacket: ByteArray, rssi: Int) {
        if (meshCorePtr == 0L) return

        val timestampUs = System.currentTimeMillis() * 1000
        val result = MeshCore.nativeProcessIncoming(meshCorePtr, rawPacket, rssi, timestampUs)

        result?.let { actions ->
            ActionDispatcher.dispatch(actions, radioController)
        }
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
            .setSmallIcon(R.drawable.ic_mesh_icon) // Placeholder; replace with actual icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
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
        wakeLock.acquire(24 * 60 * 60 * 1000) // Long timeout; will be released manually
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun startPeriodicTick() {
        tickHandler.post(object : Runnable {
            override fun run() {
                if (!isRunning.get() || meshCorePtr == 0L) return

                try {
                    val actions = MeshCore.nativeTick(meshCorePtr)
                    actions?.let { ActionDispatcher.dispatch(it, radioController) }

                    updateBatteryInfo()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic tick", e)
                }

                tickHandler.postDelayed(this, TICK_INTERVAL_MS)
            }
        })
    }

    private fun updateBatteryInfo() {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL

        if (level >= 0 && scale > 0 && meshCorePtr != 0L) {
            val percent = (level * 100 / scale)
            MeshCore.nativeUpdateBattery(meshCorePtr, percent, isCharging)
        }
    }

    private fun startWifiServerIfNeeded() {
        // WiFi server is started by RadioControllerImpl when WiFi Direct is enabled
        // This is a placeholder for potential future initialization
    }

    /**
     * Loads the 32-byte identity seed from shared encrypted storage.
     * Coordinates with Team D's IdentityBackupHelper.
     */
    private fun loadIdentitySeed(): ByteArray {
        val prefs = getSharedPreferences(PREFS_IDENTITY, Context.MODE_PRIVATE)
        val encoded = prefs.getString(KEY_SEED, null)
            ?: throw IllegalStateException("Identity seed not found. Complete onboarding first.")

        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "RezvanRadioService"
        private const val CHANNEL_ID = "rezvan_mesh_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MS = 1000L
        private const val PREFS_IDENTITY = "rezvan_identity"
        private const val KEY_SEED = "identity_seed"
    }
}
