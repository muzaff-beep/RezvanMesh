package com.rezvani.mesh.radio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rezvani.mesh.MainActivity
import com.rezvani.mesh.MeshCore
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.R
import com.rezvani.mesh.utils.DiagLogger
import java.util.concurrent.atomic.AtomicBoolean

class RezvanRadioService : Service() {

    private val binder = LocalBinder()
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var radioController: RadioController
    private lateinit var actionDispatcher: ActionDispatcher
    private val tickHandler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var meshCorePtr: Long = 0

    inner class LocalBinder : Binder() {
        fun getService(): RezvanRadioService = this@RezvanRadioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun getMeshCorePtr(): Long = meshCorePtr

    fun initializeMeshEngine(seed: ByteArray) {
        DiagLogger.log(this, "initializeMeshEngine called")
        if (meshCorePtr != 0L) {
            Log.w(TAG, "Mesh engine already initialised")
            DiagLogger.log(this, "Engine already initialised, ptr=$meshCorePtr")
            return
        }
        try {
            val storagePath = filesDir.absolutePath
            meshCorePtr = MeshCore.nativeInit(seed, storagePath)
            DiagLogger.log(this, "MeshCore initialised, ptr=$meshCorePtr")
            startPeriodicTick()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize mesh engine", e)
            DiagLogger.log(this, "nativeInit failed: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            Log.i(TAG, "RezvanRadioService onCreate")
            startForegroundWithNotification()
            acquireWakeLock()
            radioController = RadioControllerImpl(this)
            actionDispatcher = ActionDispatcher(this)

            var scanStarted = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    radioController.startBleScan(5000L, 250L)
                    scanStarted = true
                }
            } else {
                radioController.startBleScan(5000L, 250L)
                scanStarted = true
            }
            DiagLogger.log(this, "BLE scan " + if (scanStarted) "started" else "skipped - no permission")
        } catch (e: Exception) {
            Log.e(TAG, "FATAL in RezvanRadioService.onCreate", e)
            DiagLogger.log(this, "Service onCreate failed: ${e.message}")
            throw e
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "RezvanRadioService onStartCommand")
        if (!isRunning.get()) {
            isRunning.set(true)
            try {
                val seed = loadIdentitySeed()
                if (seed != null) {
                    DiagLogger.log(this, "Seed found, calling initializeMeshEngine")
                    initializeMeshEngine(seed)
                } else {
                    DiagLogger.log(this, "No identity seed yet – waiting for onboarding")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start command", e)
                DiagLogger.log(this, "onStartCommand error: ${e.message}")
            }
        }
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

    fun onPacketReceived(rawPacket: ByteArray, rssi: Int) {
        DiagLogger.log(this, "Packet received, RSSI=$rssi, len=${rawPacket.size}")
        if (meshCorePtr == 0L) return
        val timestampUs = System.currentTimeMillis() * 1000
        val result = MeshCore.nativeProcessIncoming(meshCorePtr, rawPacket, rssi, timestampUs)
        result?.let { actionDispatcher.dispatch(it, radioController) }

        MeshServiceConnection.onPacketReceived(rawPacket, rssi)
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

    private fun startPeriodicTick() {
        tickHandler.post(object : Runnable {
            override fun run() {
                if (!isRunning.get()) {
                    DiagLogger.log(this@RezvanRadioService, "Tick skipped - service not running")
                    return
                }
                if (meshCorePtr == 0L) {
                    DiagLogger.log(this@RezvanRadioService, "Tick skipped - meshCorePtr is 0 (engine not initialised?)")
                    tickHandler.postDelayed(this, TICK_INTERVAL_MS)
                    return
                }
                try {
                    val actions = MeshCore.nativeTick(meshCorePtr)
                    DiagLogger.log(this@RezvanRadioService, "Tick: ${actions?.size ?: 0} actions")
                    actions?.let { actionDispatcher.dispatch(it, radioController) }
                    updateBatteryInfo()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic tick", e)
                    DiagLogger.log(this@RezvanRadioService, "Tick error: ${e.message}")
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

    private fun loadIdentitySeed(): ByteArray? {
        val prefs = getSharedPreferences(PREFS_IDENTITY, Context.MODE_PRIVATE)
        val encoded = prefs.getString(KEY_SEED, null) ?: return null
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
