package com.rezvani.mesh

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.rezvani.mesh.radio.ActionDispatcher
import com.rezvani.mesh.radio.RezvanRadioService
import com.rezvani.mesh.utils.PowerProfileManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

object MeshServiceConnection {
    private const val TAG = "MeshServiceConnection"

    /** Exposed for StatusViewModel to poll radio counters. */
    var activeService: RezvanRadioService? = null
        private set

    private val _meshCorePtr = MutableStateFlow<Long?>(null)
    val meshCorePtr: StateFlow<Long?> = _meshCorePtr.asStateFlow()

    private val _powerState = MutableStateFlow(PowerState.BALANCED)
    val powerState: StateFlow<PowerState> = _powerState.asStateFlow()

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val seenNodes = ConcurrentHashMap<String, Int>()
    private val _nodeCount = MutableStateFlow(0)
    val nodeCount: StateFlow<Int> = _nodeCount.asStateFlow()
    private val _signalStrength = MutableStateFlow("-68 dBm")
    val signalStrength: StateFlow<String> = _signalStrength.asStateFlow()

    private var messageReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun onServiceConnected(service: RezvanRadioService) {
        activeService = service
        val ptr = service.getMeshCorePtr()
        _meshCorePtr.value = ptr
        _isServiceConnected.value = true
    }

    fun onServiceDisconnected() {
        activeService = null
        _meshCorePtr.value = null
        _isServiceConnected.value = false
        serviceScope.cancel()
    }

    fun applyPowerState(activity: Activity?) {
        PowerProfileManager.applyPowerState(activity, _powerState.value)
    }

    fun onPacketReceived(packet: ByteArray, rssi: Int) {
        try {
            if (packet.size < 11) return
            val originator = packet.copyOfRange(3, 11)
            val nodeIdHex = originator.joinToString("") { "%02x".format(it) }
            seenNodes[nodeIdHex] = rssi
            _nodeCount.value = seenNodes.size
            val best = seenNodes.values.maxOrNull()?.let { "$it dBm" } ?: "-68 dBm"
            _signalStrength.value = best
        } catch (_: Exception) { }
    }

    fun registerReceivers(context: Context) {
        messageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ActionDispatcher.ACTION_NEW_MESSAGE) {
                    val payload = intent.getByteArrayExtra(ActionDispatcher.EXTRA_DECRYPTED_MESSAGE)
                    payload?.let { handleDecryptedMessage(it) }
                }
            }
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(
            messageReceiver!!, IntentFilter(ActionDispatcher.ACTION_NEW_MESSAGE)
        )

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra("level", -1)
                val scale = intent.getIntExtra("scale", -1)
                val status = intent.getIntExtra("status", -1)
                if (level >= 0 && scale > 0) {
                    val percent = (level * 100 / scale)
                    _batteryLevel.value = percent
                    val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                   status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    _isCharging.value = charging
                    activeService?.let {
                        val ptr = _meshCorePtr.value
                        if (ptr != null && ptr != 0L) {
                            MeshCore.nativeUpdateBattery(ptr, percent, charging)
                        }
                    }
                }
            }
        }
        context.registerReceiver(batteryReceiver!!, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun unregisterReceivers(context: Context) {
        messageReceiver?.let { LocalBroadcastManager.getInstance(context).unregisterReceiver(it) }
        batteryReceiver?.let { context.unregisterReceiver(it) }
    }

    suspend fun sendTextMessage(recipientId: ByteArray, text: String): Boolean {
        return sendMessage(recipientId, text.toByteArray(Charsets.UTF_8), MessageType.TEXT)
    }

    suspend fun sendMessage(
        recipientId: ByteArray,
        data: ByteArray,
        type: MessageType
    ): Boolean = withContext(Dispatchers.IO) {
        val ptr = _meshCorePtr.value ?: return@withContext false
        if (ptr == 0L) return@withContext false
        try {
            val actions = MeshCore.nativeSendMessage(ptr, recipientId, data, type.value)
            actions != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            false
        }
    }

    fun refreshPowerState() {
        val ptr = _meshCorePtr.value ?: return
        if (ptr == 0L) return
        try {
            val stateInt = MeshCore.nativeGetPowerState(ptr)
            _powerState.value = PowerState.fromInt(stateInt)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get power state", e)
        }
    }

    private fun startPowerStatePolling() {
        serviceScope.launch {
            while (isActive) {
                refreshPowerState()
                delay(5000)
            }
        }
    }

    private fun handleDecryptedMessage(payload: ByteArray) {
        try {
            val message = parseDecryptedMessage(payload)
            Log.i(TAG, "Received message from ${message.senderId}")
        } catch (e: Exception) { }
    }

    private fun parseDecryptedMessage(data: ByteArray): DecryptedMessage {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val conversationId = ByteArray(16); buffer.get(conversationId)
        val senderId = ByteArray(8); buffer.get(senderId)
        val timestamp = buffer.long
        val messageType = (buffer.get().toInt() and 0xFF)
        val contentLen = buffer.int
        val content = ByteArray(contentLen); buffer.get(content)
        return DecryptedMessage(conversationId, senderId, timestamp, messageType, content)
    }

    data class DecryptedMessage(
        val conversationId: ByteArray,
        val senderId: ByteArray,
        val timestamp: Long,
        val messageType: Int,
        val content: ByteArray
    ) {
        val conversationIdHex: String get() = conversationId.joinToString("") { "%02x".format(it) }
        val senderIdHex: String get() = senderId.joinToString("") { "%02x".format(it) }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DecryptedMessage
            return conversationId.contentEquals(other.conversationId) &&
                   senderId.contentEquals(other.senderId) &&
                   timestamp == other.timestamp &&
                   messageType == other.messageType &&
                   content.contentEquals(other.content)
        }

        override fun hashCode(): Int {
            var result = conversationId.contentHashCode()
            result = 31 * result + senderId.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + messageType
            result = 31 * result + content.contentHashCode()
            return result
        }
    }
}