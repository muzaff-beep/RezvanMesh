package com.rezvani.mesh.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rezvani.mesh.utils.DiagLogger
import java.security.SecureRandom

object IdentityBackupHelper {
    private const val PREFS_FILE = "rezvan_secure_identity"
    private const val KEY_SEED = "identity_seed"
    private const val FALLBACK_PREFS_FILE = "rezvan_identity_fallback"

    fun generateSeed(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun saveSeed(context: Context, seed: ByteArray) {
        val prefs = getPrefs(context)
        prefs.edit().putString(KEY_SEED, Base64.encodeToString(seed, Base64.NO_WRAP)).commit()
    }

    fun loadSeed(context: Context): ByteArray? {
        val prefs = getPrefs(context)
        val encoded = prefs.getString(KEY_SEED, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    fun hasIdentity(context: Context): Boolean = loadSeed(context) != null

    fun loadNodeId(context: Context): String? {
        val seed = loadSeed(context) ?: return null
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(seed)
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Returns the 8‑byte node ID suitable for BLE loopback self‑detection. */
    fun loadNodeIdBytes(context: Context): ByteArray? {
        val hex = loadNodeId(context) ?: return null
        if (hex.length != 16) return null
        return ByteArray(8) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w("IdentityBackupHelper", "EncryptedSharedPreferences unavailable, using fallback plain storage")
            DiagLogger.log(context, "MasterKey FAILED, using fallback seed storage")
            context.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)
        }
    }
}