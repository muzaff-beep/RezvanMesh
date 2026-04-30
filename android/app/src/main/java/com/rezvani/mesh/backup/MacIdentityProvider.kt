package com.rezvani.mesh.backup

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.NetworkInterface
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MacIdentityProvider {
    private const val TAG = "MacIdentityProvider"
    private const val HKDF_SALT = "RezvanMeshFixedSalt2026!"
    private const val HKDF_INFO = "identity-seed"
    private const val SEED_LENGTH = 32

    /**
     * Derive a 32‑byte identity seed from the device's factory MAC address.
     * Returns null if no valid MAC could be obtained.
     */
    fun deriveSeed(context: Context): ByteArray? {
        val mac = getMacAddress(context)
        if (mac == null) {
            Log.e(TAG, "Could not obtain MAC address")
            return null
        }
        Log.i(TAG, "Deriving seed from MAC: $mac")
        return hkdfSha256(mac.toByteArray(Charsets.UTF_8), HKDF_SALT.toByteArray(), HKDF_INFO.toByteArray(), SEED_LENGTH)
    }

    /**
     * Save the derived seed to EncryptedSharedPreferences.
     */
    fun saveSeed(context: Context, seed: ByteArray) {
        IdentityBackupHelper.saveSeed(context, seed)
    }

    /**
     * Load the previously saved seed.
     */
    fun loadSeed(context: Context): ByteArray? {
        return IdentityBackupHelper.loadSeed(context)
    }

    /**
     * Retrieve the factory MAC address.
     * Primary: WifiManager.connectionInfo.macAddress (needs LOCAL_MAC_ADDRESS).
     * Fallback: NetworkInterface.getByName("wlan0").hardwareAddress.
     * Filters out randomised MACs (02:00:00:00:00:00).
     */
    private fun getMacAddress(context: Context): String? {
        // Primary: WifiManager
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            val mac = info?.macAddress
            if (!mac.isNullOrBlank() && mac != "02:00:00:00:00:00" && mac != "00:00:00:00:00:00") {
                return mac
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "LOCAL_MAC_ADDRESS permission not granted")
        } catch (e: Exception) {
            Log.w(TAG, "WifiManager MAC retrieval failed", e)
        }

        // Fallback: NetworkInterface
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.name.equals("wlan0", ignoreCase = true)) {
                        val hardware = iface.hardwareAddress
                        if (hardware != null && hardware.size == 6) {
                            val mac = hardware.joinToString(":") { "%02x".format(it) }
                            if (mac != "02:00:00:00:00:00" && mac != "00:00:00:00:00:00") {
                                return mac
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "NetworkInterface MAC retrieval failed", e)
            }
        }

        Log.e(TAG, "All MAC retrieval methods failed")
        return null
    }

    /**
     * HKDF‑SHA256 (RFC 5869) — simplified implementation.
     *
     * @param ikm   Input keying material (MAC address as UTF‑8 bytes).
     * @param salt  Fixed application salt.
     * @param info  Context‑specific label.
     * @param length Desired output length in bytes.
     */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // Step 1 — Extract: PRK = HMAC‑SHA256(salt, IKM)
        val prk = hmacSha256(salt, ikm)

        // Step 2 — Expand
        val output = ByteArray(length)
        val n = (length + 31) / 32 // ceil(length / 32)
        var t = ByteArray(0)

        var offset = 0
        for (i in 1..n) {
            val input = ByteArray(t.size + info.size + 1)
            System.arraycopy(t, 0, input, 0, t.size)
            System.arraycopy(info, 0, input, t.size, info.size)
            input[input.size - 1] = i.toByte()
            t = hmacSha256(prk, input)
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, output, offset, copyLen)
            offset += copyLen
            if (offset >= length) break
        }
        return output
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(key, "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data)
    }
}
