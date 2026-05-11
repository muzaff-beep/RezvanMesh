package com.rezvani.mesh.radio

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.rezvani.mesh.utils.DiagLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ActionDispatcher(private val context: Context) {

    private val localBroadcastManager: LocalBroadcastManager by lazy {
        LocalBroadcastManager.getInstance(context.applicationContext)
    }

    fun dispatch(actionBytes: ByteArray?, radio: RadioController) {
        if (actionBytes == null || actionBytes.isEmpty()) {
            return
        }

        val buffer = ByteBuffer.wrap(actionBytes).order(ByteOrder.BIG_ENDIAN)

        if (!buffer.hasRemaining()) {
            Log.w(TAG, "Empty action buffer")
            return
        }

        val count = (buffer.get().toInt() and 0xFF)
        Log.d(TAG, "Dispatching $count actions")
        DiagLogger.log(context, "Dispatching $count actions")

        repeat(count) {
            if (buffer.remaining() < 3) {
                Log.e(TAG, "Incomplete action header at index $it")
                return
            }

            val type = (buffer.get().toInt() and 0xFF)
            val length = (buffer.short.toInt() and 0xFFFF)

            if (length < 0 || length > buffer.remaining()) {
                Log.e(TAG, "Invalid action length: $length, remaining: ${buffer.remaining()}")
                return
            }

            val payload = ByteArray(length)
            if (length > 0) {
                buffer.get(payload)
            }

            try {
                when (type) {
                    TYPE_BLE_ADVERTISEMENT -> handleBleAdvertisement(payload, radio)
                    TYPE_WIFI_PACKET -> handleWifiPacket(payload, radio)
                    TYPE_BLE_PACKET -> handleBlePacket(payload, radio)
                    TYPE_UPDATE_SCAN -> handleUpdateScan(payload, radio)
                    TYPE_NOTIFY_UI -> handleNotifyUi(payload)
                    TYPE_DIAG_LOG -> handleDiagLog(payload)
                    else -> Log.w(TAG, "Unknown action type: $type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error dispatching action type $type", e)
            }
        }
    }

    private fun handleBleAdvertisement(payload: ByteArray, radio: RadioController) {
        if (payload.size != 31) {
            Log.w(TAG, "BLE advertisement payload must be 31 bytes, got ${payload.size}")
            return
        }
        radio.startBleAdvertising(payload, 5000)
    }

    private fun handleWifiPacket(payload: ByteArray, radio: RadioController) {
        if (payload.size < 6) {
            Log.w(TAG, "WiFi packet payload too short: ${payload.size}")
            return
        }

        val ipBuffer = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.BIG_ENDIAN)
        val ip = ipBuffer.int

        val portBuffer = ByteBuffer.wrap(payload, 4, 2).order(ByteOrder.BIG_ENDIAN)
        val port = (portBuffer.short.toInt() and 0xFFFF)

        val data = payload.sliceArray(6 until payload.size)

        val ipString = String.format(
            "%d.%d.%d.%d",
            (ip shr 24) and 0xFF,
            (ip shr 16) and 0xFF,
            (ip shr 8) and 0xFF,
            ip and 0xFF
        )

        radio.sendWifiPacket(ipString, port, data)
    }

    private fun handleBlePacket(payload: ByteArray, radio: RadioController) {
        if (payload.size < 6) {
            Log.w(TAG, "BLE packet payload too short: ${payload.size}")
            return
        }

        val mac = payload.sliceArray(0..5)
        val data = payload.sliceArray(6 until payload.size)

        val macString = mac.joinToString(":") {
            "%02X".format(it)
        }
        radio.sendBlePacket(macString, data)
    }

    private fun handleUpdateScan(payload: ByteArray, radio: RadioController) {
        if (payload.size < 8) {
            Log.w(TAG, "UpdateScan payload too short: ${payload.size}")
            return
        }

        val intervalBuffer = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.BIG_ENDIAN)
        val intervalMs = intervalBuffer.int.toLong()

        val windowBuffer = ByteBuffer.wrap(payload, 4, 4).order(ByteOrder.BIG_ENDIAN)
        val windowMs = windowBuffer.int.toLong()

        radio.startBleScan(intervalMs, windowMs)
    }

    private fun handleNotifyUi(payload: ByteArray) {
        val intent = Intent(ACTION_NEW_MESSAGE)
        intent.putExtra(EXTRA_DECRYPTED_MESSAGE, payload)
        localBroadcastManager.sendBroadcast(intent)
        Log.d(TAG, "Broadcasted new message to UI")
    }

    private fun handleDiagLog(payload: ByteArray) {
        try {
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            if (buf.remaining() < 1) return
            val levelByte = buf.get().toInt() and 0xFF
            if (buf.remaining() < 2) return
            val tagLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < tagLen) return
            val tag = ByteArray(tagLen).also { buf.get(it) }.toString(Charsets.UTF_8)
            if (buf.remaining() < 2) return
            val msgLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < msgLen) return
            val message = ByteArray(msgLen).also { buf.get(it) }.toString(Charsets.UTF_8)

            val level = when (levelByte) {
                0 -> DiagLogger.Level.VERBOSE
                1 -> DiagLogger.Level.INFO
                2 -> DiagLogger.Level.WARN
                else -> DiagLogger.Level.ERROR
            }
            DiagLogger.log(context, tag, level, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle DiagLog", e)
        }
    }

    companion object {
        private const val TAG = "ActionDispatcher"

        const val TYPE_BLE_ADVERTISEMENT: Int = 0x01
        const val TYPE_WIFI_PACKET: Int = 0x02
        const val TYPE_BLE_PACKET: Int = 0x03
        const val TYPE_UPDATE_SCAN: Int = 0x04
        const val TYPE_NOTIFY_UI: Int = 0x05
        const val TYPE_DIAG_LOG: Int = 0x06

        const val ACTION_NEW_MESSAGE = "com.rezvani.mesh.NEW_MESSAGE"
        const val EXTRA_DECRYPTED_MESSAGE = "decrypted_message"
    }
}
