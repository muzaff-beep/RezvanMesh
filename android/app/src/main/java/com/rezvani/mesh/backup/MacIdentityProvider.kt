package com.rezvani.mesh.backup

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.NetworkInterface
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import javax.crypto.spec.HKDFParameterSpec
import java.security.SecureRandom
import javax.crypto.KeyGenerator
// For HKDF we use the Android Keystore, but a simple HMAC‑SHA256 expansion works fine.

object MacIdentityProvider {
    private const val TAG = "MacIdentityProvider"
    private const val SALT = "RezvanMeshFixedSalt2026!"  // fixed, can be public
    private const val INFO = "identity-seed"

    fun deriveSeed(context: Context): ByteArray? {
        val mac = getDeviceMac(context) ?: return null
        // Use HKDF‑SHA256 to expand the MAC into a 32‑byte seed.
        val hkdf = Mac.getInstance("HmacSHA256")
        hkdf.init(SecretKeySpec(SALT.toByteArray(), "HmacSHA256"))
        val prk = hkdf.doFinal(mac.toByteArray())
        // Expand (simplified HKDF‑Expand for fixed 32‑byte length)
        val output = ByteArray(32)
        val infoBytes = INFO.toByteArray()
        // T(1) = HMAC(PRK, info || 0x01)
        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        expandMac.update(infoBytes)
        expandMac.update(1.toByte())
        val t1 = expandMac.doFinal()
        System.arraycopy(t1, 0, output, 0, 32)
        return output
    }

    private fun getDeviceMac(context: Context): String? {
        // Try WifiInfo first (works on most Android 10+ devices)
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val mac = wifiInfo?.macAddress
            if (mac != null && mac != "02:00:00:00:00:00" && mac.isNotEmpty()) {
                Log.i(TAG, "Using WiFi MAC: $mac")
                return mac
            }
        } catch (e: SecurityException) { }

        // Fallback to NetworkInterface
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name.equals("wlan0", true)) {
                    val hwAddr = iface.hardwareAddress
                    if (hwAddr != null && hwAddr.isNotEmpty()) {
                        val mac = hwAddr.joinToString(":") { "%02X".format(it) }
                        Log.i(TAG, "Using wlan0 MAC: $mac")
                        return mac
                    }
                }
            }
        } catch (e: Exception) { }
        return null
    }

    fun saveSeed(context: Context, seed: ByteArray) {
        IdentityBackupHelper.saveSeed(context, seed) // reuse existing secure storage
    }

    fun loadSeed(context: Context): ByteArray? {
        return IdentityBackupHelper.loadSeed(context)
    }
}
