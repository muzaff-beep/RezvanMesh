// android/app/src/main/java/com/rezvani/mesh/radio/ActionDispatcher.kt

package com.rezvani.mesh.radio

import com.rezvani.mesh.utils.DiagLogger

object ActionDispatcher {

    fun dispatch(action: ByteArray, radio: RadioController) {
        if (action.size < 4) return
        val actionCount = action[0].toInt() and 0xFF
        var offset = 1
        for (i in 0 until actionCount) {
            if (offset + 3 > action.size) break
            val actionType = action[offset].toInt() and 0xFF
            val payloadLen = ((action[offset + 1].toInt() and 0xFF) shl 8) or (action[offset + 2].toInt() and 0xFF)
            offset += 3
            if (offset + payloadLen > action.size) break
            val payload = action.copyOfRange(offset, offset + payloadLen)
            offset += payloadLen
            when (actionType) {
                0x01 -> radio.startBleAdvertising(payload, 1000)
                0x03 -> radio.sendBroadcastPacket(payload)
                0x04 -> {
                    if (payload.size >= 8) {
                        val intervalMs = ((payload[0].toInt() and 0xFF) shl 24) or
                                ((payload[1].toInt() and 0xFF) shl 16) or
                                ((payload[2].toInt() and 0xFF) shl 8) or
                                (payload[3].toInt() and 0xFF)
                        val windowMs = ((payload[4].toInt() and 0xFF) shl 24) or
                                ((payload[5].toInt() and 0xFF) shl 16) or
                                ((payload[6].toInt() and 0xFF) shl 8) or
                                (payload[7].toInt() and 0xFF)
                        radio.startBleScan(intervalMs.toLong(), windowMs.toLong())
                    }
                }
                else -> DiagLogger.ble("Unknown action type: $actionType")
            }
        }
    }
}