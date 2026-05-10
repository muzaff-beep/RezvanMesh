package com.rezvani.mesh.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

object IdentityBackupHelper {
    private const val PREFS_FILE = "rezvan_secure_identity"
    private const val KEY_SEED = "identity_seed"

    fun generateSeed(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun saveSeed(context: Context, seed: ByteArray) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString(KEY_SEED, Base64.encodeToString(seed, Base64.NO_WRAP)).commit()
    }

    fun loadSeed(context: Context): ByteArray? {
        val prefs = getEncryptedPrefs(context)
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

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
