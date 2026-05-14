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
import com.rezvani.mesh.MainActivity
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.R
import com.rezvani.mesh.rust.Action
import com.rezvani.mesh.rust.MeshEngine
import com.rezvani.mesh.rust.NodeId
import com.rezvani.mesh.utils.DiagLogger
import com.rezvani.mesh.utils.IdentityBackupHelper
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RezvanRadioService : Service() {

    companion object {
        const val CHANNEL_ID = "rezvan_mesh"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.rezvani.mesh.STOP_SERVICE"
    }

    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var radioController: RadioControllerImpl? = null
    private var engine: MeshEngine? = null
    var ownNodeId: NodeId? = null
        private set
    private var meshConnection: MeshServiceConnection? = null
    private val pendingPackets = ConcurrentHashMap<String, MutableList<ByteArray>>()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickJob: Job? = null
    private var isDestroyed = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        DiagLogger.service("onCreate START")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        radioController = RadioControllerImpl(this)
        DiagLogger.service("Controllers built")
        DiagLogger.service("Permissions: scan=${checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED} adv=${checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED} sdk=${Build.VERSION.SDK_INT}")
        radioController?.startBleScan(1000, 1000)
        DiagLogger.service("BLE scan requested")

        loadIdentityAndInitEngine()
        startPeriodicTick()
        DiagLogger.service("onCreate COMPLETE")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rezvan Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mesh networking service"
            }
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
        DiagLogger.service("WakeLock acquired")
    }

    private fun loadIdentityAndInitEngine() {
        serviceScope.launch {
            try {
                val seed = IdentityBackupHelper.loadSeed(this@RezvanRadioService)
                if (seed == null) {
                    DiagLogger.service("No identity seed yet")
                    return@launch
                }
                DiagLogger.service("Service seed bytes: ${seed.size}")
                initMeshEngine(seed)
            } catch (e: Exception) {
                DiagLogger.service("Error loading identity: ${e.message}")
            }
        }
    }

    private fun initMeshEngine(seed: ByteArray) {
        if (engine != null) return
        try {
            val cryptoProvider = com.rezvani.mesh.crypto.MockCryptoProvider()
            engine = MeshEngine(seed.copyOf(32), cryptoProvider)
            val nodeId = IdentityBackupHelper.computeNodeIdFromSeed(seed)
            ownNodeId = nodeId
            radioController?.setOwnNodeId(nodeId)
            DiagLogger.service("ownNodeId set for loopback: ${nodeId.take(4).joinToString("") { "%02x".format(it) }}...")
            DiagLogger.service("initializeMeshEngine called")
            DiagLogger.service("MeshCore initialised, ptr=${engine.hashCode()}")
        } catch (e: Exception) {
            DiagLogger.service("Engine init failed: ${e.message}")
        }
    }

    private fun startPeriodicTick() {
        tickJob = serviceScope.launch {
            while (isActive && !isDestroyed.get()) {
                delay(1000L)
                val actions = engine?.tick() ?: continue
                for (action in actions) {
                    when (action) {
                        is Action.SendBleAdvertisement -> {
                            radioController?.startBleAdvertising(action.data, 1000)
                        }
                        is Action.SendBlePacket -> {
                            val macHex = resolveMacForNodeIdBytes(action.mac)
                            if (macHex != null && macHex != "FF:FF:FF:FF:FF:FF") {
                                radioController?.sendBlePacket(macHex, action.data)
                            } else {
                                radioController?.sendBroadcastPacket(action.data)
                            }
                        }
                        is Action.DiagLog -> {
                            DiagLogger.custom(action.tag, action.level, action.message)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun resolveMacForNodeIdBytes(macBytes: ByteArray): String? {
        if (macBytes.size != 6) return null
        val hex = macBytes.joinToString(":") { "%02x".format(it) }.uppercase()
        if (hex == "FF:FF:FF:FF:FF:FF") return "FF:FF:FF:FF:FF:FF"
        return hex
    }

    fun onPacketReceived(data: ByteArray, rssi: Int) {
        serviceScope.launch {
            val result = engine?.processIncoming(data, rssi, System.currentTimeMillis())
            if (result != null) {
                val (decryptedMessage, actions) = result
                if (decryptedMessage != null) {
                    meshConnection?.addReceivedMessage(decryptedMessage)
                }
                for (action in actions) {
                    when (action) {
                        is Action.DiagLog -> DiagLogger.custom(action.tag, action.level, action.message)
                        else -> {}
                    }
                }
            }
        }
    }

    fun setConnection(conn: MeshServiceConnection?) {
        meshConnection = conn
    }

    fun sendMessage(recipient: NodeId, plaintext: ByteArray) {
        val actions = engine?.send_message(recipient, plaintext, 0) ?: return
        for (action in actions) {
            if (action is Action.SendBlePacket) {
                val macBytes = action.mac
                val mac = resolveMacForNodeIdBytes(macBytes) ?: "FF:FF:FF:FF:FF:FF"
                if (mac != "FF:FF:FF:FF:FF:FF") {
                    radioController?.sendBlePacket(mac, action.data)
                } else {
                    radioController?.sendBroadcastPacket(action.data)
                }
            }
        }
    }

    fun sendBroadcast(message: ByteArray) {
        val actions = engine?.send_broadcast(message) ?: return
        for (action in actions) {
            if (action is Action.SendBlePacket) {
                radioController?.sendBroadcastPacket(action.data)
            }
        }
    }

    fun getMacForNodeId(nodeIdHex: String): String? {
        return radioController?.getMacForNodeId(nodeIdHex)
    }

    fun dequeuePendingPackets(address: String): List<ByteArray> {
        return pendingPackets.remove(address)?.toList() ?: emptyList()
    }

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
        radioController?.onDestroy()
        engine = null
        wakeLock?.release()
        DiagLogger.service("Service destroyed")
        super.onDestroy()
    }
}