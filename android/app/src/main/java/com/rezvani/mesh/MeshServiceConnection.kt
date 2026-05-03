package com.rezvani.mesh

import android.content.BroadcastReceiver
import android.app.Activity
import com.rezvani.mesh.utils.PowerProfileManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.rezvani.mesh.radio.ActionDispatcher
import com.rezvani.mesh.radio.RezvanRadioService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

object MeshServiceConnection {
    private const val TAG = "MeshServiceConnection"

    private var meshService: RezvanRadioService? = null

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

    private var messageReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun onServiceConnected(service: RezvanRadioService) {
        meshService = service
        val ptr = service.getMeshCorePtr()
        _meshCorePtr.value = ptr
        _isServiceConnected.value = true
        Log.i(TAG, "Service connected, corePtr = $ptr")

        // Only start polling if a valid engine pointer exists
        if (ptr != null && ptr != 0L) {
            startPowerStatePolling()
        }
    }

    fun onServiceDisconnected() {
        meshService = null
        _meshCorePtr.value = null
        _isServiceConnected.value = false
        serviceScope.cancel()
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
            messageReceiver!!,
            IntentFilter(ActionDispatcher.ACTION_NEW_MESSAGE)
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

                    meshService?.let {
                        val ptr = _meshCorePtr.value
                        if (ptr != null && ptr != 0L) {
                            MeshCore.nativeUpdateBattery(ptr, percent, charging)
                        }
                    }
                }
            }
        }
        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
    }

    fun unregisterReceivers(context: Context) {
        messageReceiver?.let {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(it)
        }
        batteryReceiver?.let {
            context.unregisterReceiver(it)
        }
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse decrypted message", e)
        }
    }

    private fun parseDecryptedMessage(data: ByteArray): DecryptedMessage {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val conversationId = ByteArray(16)
        buffer.get(conversationId)

        val senderId = ByteArray(8)
        buffer.get(senderId)

        val timestamp = buffer.long
        val messageType = (buffer.get().toInt() and 0xFF)
        val contentLen = buffer.int

        val content = ByteArray(contentLen)
        buffer.get(content)

        return DecryptedMessage(
            conversationId = conversationId,
            senderId = senderId,
            timestamp = timestamp,
            messageType = messageType,
            content = content
        )
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
