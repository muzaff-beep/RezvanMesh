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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rezvani.mesh.MainActivity
import com.rezvani.mesh.MeshCore
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.R
import com.rezvani.mesh.backup.IdentityBackupHelper
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

    private var tickCount = 0L
    private var lastSummaryTickAt = 0L

    inner class LocalBinder : Binder() {
        fun getService(): RezvanRadioService = this@RezvanRadioService
    }

    override fun onBind(intent: Intent?): IBinder = binder
    fun getMeshCorePtr(): Long = meshCorePtr

    /** Expose the radio controller for diagnostics. */
    fun getRadioController(): RadioController = radioController

    fun initializeMeshEngine(seed: ByteArray) {
        DiagLogger.log(this, "SERVICE", DiagLogger.Level.INFO, "initializeMeshEngine called")
        if (meshCorePtr != 0L) {
            DiagLogger.log(this, "SERVICE", DiagLogger.Level.WARN, "Engine already initialised")
            return
        }
        try {
            val storagePath = filesDir.absolutePath
            meshCorePtr = MeshCore.nativeInit(seed, storagePath)
            DiagLogger.log(this, "SERVICE", DiagLogger.Level.INFO, "MeshCore initialised, ptr=$meshCorePtr")
            startPeriodicTick()
        } catch (e: Exception) {
            DiagLogger.err(this, "SERVICE", "nativeInit failed: ${e.message}", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagLogger.log(this, "SERVICE", DiagLogger.Level.INFO, "onCreate START")
        try {
            startForegroundWithNotification()
            DiagLogger.log(this, "SERVICE", DiagLogger.Level.INFO, "Foreground notification posted")

            acquireWakeLock()
            DiagLogger.log(this, "SERVICE", DiagLogger.Level.INFO, "WakeLock acquired")

            radioController = RadioControllerImpl(this)
            actionDispatcher = ActionDispatcher(this)
            DiagLogger.log(this, "SERVICE", DiagLogger.Level.INFO, "Controllers built")

            val hasScanPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }

            val hasAdvPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_ADVERTISE
                ) == PackageManager.PERMISSION_GRANTED
            } else true

            DiagLogger.log(this, "SERVICE", DiagLogger.Level.INFO,
                "Permissions: scan=$hasScanPerm adv=$hasAdvPerm sdk=${Build.VERSION.SDK_INT}")

            if (hasScanPerm) {
                radioController.startBleScan(5000L, 250L)
                DiagLogger.log(this, "SERVICE", DiagLogger.Level.INFO, "BLE scan requested")
            } else {
                DiagLogger.err(this, "SERVICE",
                    "BLE scan SKIPPED: no permission. App will be deaf to peers.")
            }

            DiagLogger.log(this, "SERVICE", DiagLogger.Level.INFO, "onCreate COMPLETE")
        } catch (t: Throwable) {
            DiagLogger.err(this, "SERVICE", "FATAL in onCreate", t)
            throw t
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "RezvanRadioService onStartCommand")
        if (!isRunning.get()) {
            isRunning.set(true)
            try {
                val seed = IdentityBackupHelper.loadSeed(this)
                if (seed != null) {
                    DiagLogger.log(this, "SERVICE", DiagLogger.Level.INFO,
                        "Service seed bytes: ${seed.size}")
                    DiagLogger.log(this, "SERVICE", DiagLogger.Level.INFO,
                        "Seed found, calling initializeMeshEngine")
                    initializeMeshEngine(seed)
                } else {
                    DiagLogger.log(this, "SERVICE", DiagLogger.Level.WARN,
                        "No identity seed yet – waiting for onboarding")
                }
            } catch (e: Exception) {
                DiagLogger.err(this, "SERVICE", "onStartCommand error", e)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        DiagLogger.log(this, "SERVICE", DiagLogger.Level.INFO, "onDestroy")
        isRunning.set(false)
        tickHandler.removeCallbacksAndMessages(null)
        if (meshCorePtr != 0L) {
            MeshCore.nativeDestroy(meshCorePtr)
            meshCorePtr = 0
        }
        if (::radioController.isInitialized) radioController.onDestroy()
        releaseWakeLock()
        super.onDestroy()
    }

    fun onPacketReceived(rawPacket: ByteArray, rssi: Int) {
        DiagLogger.log(this, "MAIN", DiagLogger.Level.INFO,
            "Packet received, RSSI=$rssi, len=${rawPacket.size}")
        if (meshCorePtr == 0L) return
        val timestampUs = System.currentTimeMillis() * 1000
        val result = MeshCore.nativeProcessIncoming(meshCorePtr, rawPacket, rssi, timestampUs)
        result?.let { actionDispatcher.dispatch(it, radioController) }
        MeshServiceConnection.onPacketReceived(rawPacket, rssi)
    }

    private fun startForegroundWithNotification() {
        createNotificationChannel()
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
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
                CHANNEL_ID, getString(R.string.mesh_channel_name),
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
            PowerManager.PARTIAL_WAKE_LOCK, "RezvanMesh::RadioWakeLock"
        )
        wakeLock.acquire(24 * 60 * 60 * 1000)
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
    }

    private fun startPeriodicTick() {
        DiagLogger.log(this, "TICK", DiagLogger.Level.INFO,
            "Periodic tick loop started, interval=${TICK_INTERVAL_MS}ms")
        tickHandler.post(object : Runnable {
            override fun run() {
                if (!isRunning.get()) return
                tickCount++

                if (meshCorePtr == 0L) {
                    if (tickCount % 30L == 0L) {
                        DiagLogger.log(this@RezvanRadioService, "TICK",
                            DiagLogger.Level.WARN,
                            "Tick #$tickCount: meshCorePtr=0, engine not initialized")
                    }
                    tickHandler.postDelayed(this, TICK_INTERVAL_MS)
                    return
                }

                val timestampUs = System.currentTimeMillis() * 1000
                val actions = try {
                    MeshCore.nativeTick(meshCorePtr)
                } catch (t: Throwable) {
                    DiagLogger.err(this@RezvanRadioService, "TICK",
                        "nativeTick threw at #$tickCount", t)
                    null
                }

                actions?.let { actionBytes ->
                    if (actionBytes.isNotEmpty()) {
                        DiagLogger.log(this@RezvanRadioService, "TICK",
                            DiagLogger.Level.INFO,
                            "Tick #$tickCount: ${actionBytes.size} bytes dispatched")
                    }
                    actionDispatcher.dispatch(actionBytes, radioController)
                }

                // Heartbeat every ~30 seconds
                if (tickCount - lastSummaryTickAt >= 30L) {
                    lastSummaryTickAt = tickCount
                    val isAdv = try {
                        (radioController as? RadioControllerImpl)?.isCurrentlyAdvertising() ?: false
                    } catch (_: Exception) { false }
                    val isScan = try {
                        (radioController as? RadioControllerImpl)?.isCurrentlyScanning() ?: false
                    } catch (_: Exception) { false }
                    DiagLogger.log(this@RezvanRadioService, "TICK",
                        DiagLogger.Level.INFO,
                        "HEARTBEAT tick=$tickCount adv=$isAdv scan=$isScan " +
                        "uptime=${(tickCount * TICK_INTERVAL_MS) / 1000}s")
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

    companion object {
        private const val TAG = "RezvanRadioService"
        private const val CHANNEL_ID = "rezvan_mesh_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MS = 1000L
    }
}