package com.rezvani.mesh

import android.util.Log

object MeshCore {
    private const val TAG = "MeshCore"

    private var nativeAvailable = false

    init {
        try {
            System.loadLibrary("rezvan_core")
            nativeAvailable = true
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            nativeAvailable = false
            Log.e(TAG, "Native library not available - mesh engine disabled", e)
        }
    }

    fun isNativeAvailable(): Boolean = nativeAvailable

    @JvmStatic
    external fun nativeInit(seed: ByteArray, storagePath: String): Long

    @JvmStatic
    external fun nativeProcessIncoming(
        corePtr: Long,
        packet: ByteArray,
        rssi: Int,
        timestampUs: Long
    ): ByteArray?

    @JvmStatic
    external fun nativeTick(corePtr: Long): ByteArray?

    @JvmStatic
    external fun nativeSendMessage(
        corePtr: Long,
        recipientId: ByteArray,
        plaintext: ByteArray,
        messageType: Int
    ): ByteArray?

    @JvmStatic
    external fun nativeGetPowerState(corePtr: Long): Int

    @JvmStatic
    external fun nativeUpdateBattery(
        corePtr: Long,
        levelPercent: Int,
        isCharging: Boolean
    )

    @JvmStatic
    external fun nativeDestroy(corePtr: Long)
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
