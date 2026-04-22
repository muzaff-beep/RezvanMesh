package com.rezvani.mesh

import android.util.Log

/**
 * JNI wrapper for the native Rust mesh core library (librezvan_core.so).
 *
 * This object provides Kotlin access to all native mesh engine functions.
 * The native library is loaded automatically when this class is first accessed.
 *
 * All native functions are thread-safe and can be called from any thread.
 */
object MeshCore {
    private const val TAG = "MeshCore"

    init {
        try {
            System.loadLibrary("rezvan_core")
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
            throw RuntimeException("Failed to load Rezvan Mesh core library", e)
        }
    }

    // ========================= INITIALIZATION =========================

    /**
     * Initializes the native mesh engine.
     *
     * @param seed 32-byte identity seed (generated from BIP39 mnemonic).
     * @param storagePath Absolute path to the app's private storage directory.
     * @return Opaque pointer to the native MeshEngine instance (cast to Long).
     */
    @JvmStatic
    external fun nativeInit(seed: ByteArray, storagePath: String): Long

    // ========================= PACKET PROCESSING =========================

    /**
     * Processes an incoming raw packet from BLE or WiFi Direct.
     *
     * @param corePtr Native mesh engine pointer from [nativeInit].
     * @param packet Raw packet bytes (including header, payload, and signature).
     * @param rssi RSSI value in dBm (0 if not available, e.g., for WiFi).
     * @param timestampUs Timestamp in microseconds (Unix epoch).
     * @return Serialized Action list (may be null if no actions generated).
     */
    @JvmStatic
    external fun nativeProcessIncoming(
        corePtr: Long,
        packet: ByteArray,
        rssi: Int,
        timestampUs: Long
    ): ByteArray?

    /**
     * Periodic tick function called every 1 second.
     *
     * Drives the mesh engine: generates OGMs, updates routing table,
     * processes pending retransmissions, and recomputes power state.
     *
     * @param corePtr Native mesh engine pointer.
     * @return Serialized Action list (may be null if no actions generated).
     */
    @JvmStatic
    external fun nativeTick(corePtr: Long): ByteArray?

    // ========================= MESSAGE SENDING =========================

    /**
     * Sends a message to a specific recipient.
     *
     * @param corePtr Native mesh engine pointer.
     * @param recipientId 8-byte Node ID of the recipient.
     * @param plaintext Message content (unencrypted).
     * @param messageType Message type: 0=TEXT, 1=VOICE, 2=FILE_METADATA, 3=FILE_CHUNK.
     * @return Serialized Action list (may be null if no actions generated).
     */
    @JvmStatic
    external fun nativeSendMessage(
        corePtr: Long,
        recipientId: ByteArray,
        plaintext: ByteArray,
        messageType: Int
    ): ByteArray?

    // ========================= POWER STATE =========================

    /**
     * Gets the current power state of the mesh engine.
     *
     * @param corePtr Native mesh engine pointer.
     * @return PowerState ordinal value (0-6).
     *         0=EMERGENCY, 1=ACTIVE, 2=BALANCED, 3=POWER_SAVER,
     *         4=MINIMAL, 5=HIBERNATION, 6=DEAD
     */
    @JvmStatic
    external fun nativeGetPowerState(corePtr: Long): Int

    /**
     * Updates battery information for power state calculations.
     *
     * @param corePtr Native mesh engine pointer.
     * @param levelPercent Battery level (0-100).
     * @param isCharging true if device is currently charging.
     */
    @JvmStatic
    external fun nativeUpdateBattery(
        corePtr: Long,
        levelPercent: Int,
        isCharging: Boolean
    )

    // ========================= CLEANUP =========================

    /**
     * Destroys the native mesh engine instance and frees all resources.
     *
     * @param corePtr Native mesh engine pointer.
     */
    @JvmStatic
    external fun nativeDestroy(corePtr: Long)
}

/**
 * Power state enum matching the Rust core's PowerState.
 */
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

/**
 * Message type enum matching the Rust core's MessageType.
 */
enum class MessageType(val value: Int) {
    TEXT(0),
    VOICE(1),
    FILE_METADATA(2),
    FILE_CHUNK(3)
}
