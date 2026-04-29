package com.rezvani.mesh

object MeshCore {
    fun isNativeAvailable(): Boolean = false

    fun nativeInit(seed: ByteArray, storagePath: String): Long = 0L
    fun nativeProcessIncoming(corePtr: Long, packet: ByteArray, rssi: Int, timestampUs: Long): ByteArray? = null
    fun nativeTick(corePtr: Long): ByteArray? = null
    fun nativeSendMessage(corePtr: Long, recipientId: ByteArray, plaintext: ByteArray, messageType: Int): ByteArray? = null
    fun nativeGetPowerState(corePtr: Long): Int = PowerState.BALANCED.value
    fun nativeUpdateBattery(corePtr: Long, levelPercent: Int, isCharging: Boolean) {}
    fun nativeDestroy(corePtr: Long) {}
}

enum class PowerState(val value: Int) {
    EMERGENCY(0),
    ACTIVE(1),
    BALANCED(2),
    POWER_SAVER(3),
    MINIMAL(4),
    HIBERNATION(5),
    DEAD(6);

    companion object {
        fun fromInt(value: Int): PowerState {
            return values().find { it.value == value } ?: BALANCED
        }
    }
}

enum class MessageType(val value: Int) {
    TEXT(0),
    VOICE(1),
    FILE_METADATA(2),
    FILE_CHUNK(3)
}
