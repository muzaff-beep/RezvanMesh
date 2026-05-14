// android/app/src/main/java/com/rezvani/mesh/radio/RezvanRadioService.kt

package com.rezvani.mesh.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.R
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.utils.DiagLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RezvanRadioService : Service() {

    companion object {
        const val CHANNEL_ID = "rezvan_mesh"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.rezvani.mesh.STOP_SERVICE"
        private const val SEED_RETRY_DELAY_MS = 500L
        private const val SEED_MAX_RETRIES = 10
    }

    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var radioController: RadioControllerImpl? = null
    private var enginePtr: Long = 0L
    var ownNodeId: ByteArray? = null
        private set
    private var meshConnection: MeshServiceConnection? = null
    private val pendingPackets = ConcurrentHashMap<String, MutableList<ByteArray>>()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickJob: Job? = null
    private var isDestroyed = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        radioController = RadioControllerImpl(this)
        DiagLogger.ble("Controllers built")
        val scanOk = checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val advOk = checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        DiagLogger.ble("Permissions scan=$scanOk adv=$advOk sdk=${Build.VERSION.SDK_INT}")
        radioController?.startBleScan(1000, 1000)
        DiagLogger.ble("BLE scan requested")

        loadIdentityAndInitEngine()
        startPeriodicTick()
        DiagLogger.ble("onCreate COMPLETE")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rezvan Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Mesh networking service" }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RezvanRadioService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rezvan Mesh active")
            .setContentText("Scanning & advertising")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun acquireWakeLock() {
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RezvanMesh::Service").apply {
            acquire(10 * 60 * 1000L)
        }
        DiagLogger.ble("WakeLock acquired")
    }

    private fun loadIdentityAndInitEngine() {
        serviceScope.launch {
            var retries = 0
            while (retries < SEED_MAX_RETRIES && !isDestroyed.get()) {
                try {
                    val seed = IdentityBackupHelper.loadSeed(this@RezvanRadioService)
                    if (seed != null) {
                        DiagLogger.ble("Service seed bytes: ${seed.size}")
                        initMeshEngine(seed)
                        return@launch
                    }
                } catch (e: Exception) {
                    DiagLogger.err("SERVICE", "Error loading identity: ${e.message}", e)
                }
                retries++
                DiagLogger.ble("Seed not ready, retry $retries/$SEED_MAX_RETRIES")
                delay(SEED_RETRY_DELAY_MS)
            }
            DiagLogger.ble("Seed loading failed after $SEED_MAX_RETRIES retries")
        }
    }

    private fun initMeshEngine(seed: ByteArray) {
        if (enginePtr != 0L) return
        try {
            enginePtr = com.rezvani.mesh.MeshCore.nativeInit(seed, filesDir.absolutePath)
            ownNodeId = IdentityBackupHelper.loadNodeIdBytes(this)
            if (ownNodeId != null) {
                radioController?.setOwnNodeId(ownNodeId!!)
            }
            DiagLogger.ble("initializeMeshEngine called")
            DiagLogger.ble("MeshCore initialised, ptr=$enginePtr")
            MeshServiceConnection.meshCorePtr.value = enginePtr
        } catch (e: Exception) {
            DiagLogger.err("SERVICE", "Engine init failed: ${e.message}", e)
        }
    }

    private fun startPeriodicTick() {
        tickJob = serviceScope.launch {
            while (isActive && !isDestroyed.get()) {
                delay(1000L)
                if (enginePtr == 0L) continue
                try {
                    val result = withContext(Dispatchers.IO) {
                        com.rezvani.mesh.MeshCore.nativeTick(enginePtr)
                    }
                    if (result != null && result.size >= 1) {
                        val actionCount = result[0].toInt() and 0xFF
                        var offset = 1
                        for (i in 0 until actionCount) {
                            if (offset + 3 > result.size) break
                            val actionType = result[offset].toInt() and 0xFF
                            val payloadLen = ((result[offset + 1].toInt() and 0xFF) shl 8) or (result[offset + 2].toInt() and 0xFF)
                            offset += 3
                            if (offset + payloadLen > result.size) break
                            val payload = result.copyOfRange(offset, offset + payloadLen)
                            offset += payloadLen
                            when (actionType) {
                                0x01 -> radioController?.startBleAdvertising(payload, 1000)
                                0x03 -> radioController?.sendBroadcastPacket(payload)
                            }
                        }
                    }
                } catch (e: Exception) {
                    DiagLogger.err("SERVICE", "Tick error: ${e.message}", e)
                }
            }
        }
    }

    fun onPacketReceived(data: ByteArray, rssi: Int) {
        if (enginePtr == 0L) return
        serviceScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    com.rezvani.mesh.MeshCore.nativeProcessIncoming(enginePtr, data, rssi, System.currentTimeMillis() * 1000)
                }
                if (result != null && result.size >= 1) {
                    val actionCount = result[0].toInt() and 0xFF
                    var offset = 1
                    for (i in 0 until actionCount) {
                        if (offset + 3 > result.size) break
                        val actionType = result[offset].toInt() and 0xFF
                        val payloadLen = ((result[offset + 1].toInt() and 0xFF) shl 8) or (result[offset + 2].toInt() and 0xFF)
                        offset += 3
                        if (offset + payloadLen > result.size) break
                        val payload = result.copyOfRange(offset, offset + payloadLen)
                        offset += payloadLen
                        if (actionType == 0x05) {
                            val msg = com.rezvani.mesh.rust.DecryptedMessage(
                                conversationId = payload.copyOfRange(0, 16),
                                senderId = payload.copyOfRange(16, 24),
                                timestamp = ((payload[24].toLong() and 0xFF) shl 56) or
                                        ((payload[25].toLong() and 0xFF) shl 48) or
                                        ((payload[26].toLong() and 0xFF) shl 40) or
                                        ((payload[27].toLong() and 0xFF) shl 32) or
                                        ((payload[28].toLong() and 0xFF) shl 24) or
                                        ((payload[29].toLong() and 0xFF) shl 16) or
                                        ((payload[30].toLong() and 0xFF) shl 8) or
                                        (payload[31].toLong() and 0xFF),
                                messageType = payload[32],
                                content = payload.copyOfRange(37, payload.size)
                            )
                            meshConnection?.addReceivedMessage(msg)
                        }
                    }
                }
            } catch (e: Exception) {
                DiagLogger.err("SERVICE", "Packet error: ${e.message}", e)
            }
        }
    }

    fun setConnection(conn: MeshServiceConnection?) {
        meshConnection = conn
    }

    fun sendMessage(recipient: ByteArray, plaintext: ByteArray) {
        if (enginePtr == 0L) return
        serviceScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    com.rezvani.mesh.MeshCore.nativeSendMessage(enginePtr, recipient, plaintext, 0)
                }
                if (result != null) {
                    radioController?.sendBroadcastPacket(result)
                }
            } catch (e: Exception) {
                DiagLogger.err("SERVICE", "Send error: ${e.message}", e)
            }
        }
    }

    fun sendBroadcast(message: ByteArray) {
        if (enginePtr == 0L) return
        serviceScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    com.rezvani.mesh.MeshCore.nativeSendMessage(enginePtr, ByteArray(8), message, 3)
                }
                if (result != null) {
                    radioController?.sendBroadcastPacket(result)
                }
            } catch (e: Exception) {
                DiagLogger.err("SERVICE", "Broadcast error: ${e.message}", e)
            }
        }
    }

    fun dequeuePendingPackets(address: String): List<ByteArray> {
        return pendingPackets.remove(address)?.toList() ?: emptyList()
    }

    fun getRadioController(): RadioControllerImpl? = radioController

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): RezvanRadioService = this@RezvanRadioService
    }

    override fun onDestroy() {
        isDestroyed.set(true)
        if (enginePtr != 0L) {
            com.rezvani.mesh.MeshCore.nativeDestroy(enginePtr)
        }
        radioController?.onDestroy()
        wakeLock?.release()
        DiagLogger.ble("Service destroyed")
        super.onDestroy()
    }
}
