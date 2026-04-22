package com.rezvani.mesh.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.SecureRandom

/**
 * Manages identity seed generation, backup, and restoration using BIP39 mnemonics.
 *
 * The 32-byte seed is used to derive the Ed25519 and X25519 keypairs.
 * It is stored encrypted using EncryptedSharedPreferences.
 */
object IdentityBackupHelper {
    private const val PREFS_NAME = "rezvan_identity_secure"
    private const val KEY_SEED = "identity_seed"
    private const val KEY_NODE_ID = "node_id"
    private const val SEED_SIZE_BYTES = 32

    // BIP39 English wordlist (abbreviated - full list would be 2048 words)
    private val BIP39_WORDLIST = listOf(
        "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract",
        "absurd", "abuse", "access", "accident", "account", "accuse", "achieve", "acid",
        "acoustic", "acquire", "across", "act", "action", "actor", "actress", "actual",
        "adapt", "add", "addict", "address", "adjust", "admit", "adult", "advance",
        "advice", "aerobic", "affair", "afford", "afraid", "africa", "after", "again",
        "age", "agent", "agree", "ahead", "aim", "air", "airport", "aisle", "alarm",
        "album", "alcohol", "alert", "alien", "all", "alley", "allow", "almost", "alone",
        "alpha", "already", "also", "alter", "always", "amateur", "amazing", "among",
        "amount", "amused", "analyst", "anchor", "ancient", "anger", "angle", "angry",
        "animal", "ankle", "announce", "annual", "another", "answer", "antenna", "antique",
        "anxiety", "any", "apart", "apology", "appear", "apple", "approve", "april",
        "arch", "arctic", "area", "arena", "argue", "arm", "armed", "armor", "army",
        "around", "arrange", "arrest", "arrive", "arrow", "art", "artefact", "artist",
        "artwork", "ask", "aspect", "assault", "asset", "assist", "assume", "asthma",
        "athlete", "atom", "attack", "attend", "attitude", "attract", "auction", "audit",
        "august", "aunt", "author", "auto", "autumn", "average", "avocado", "avoid",
        "awake", "aware", "away", "awesome", "awful", "awkward", "axis"
    )

    private fun getSecurePreferences(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Generates a new 32-byte random seed using SecureRandom.
     *
     * @return 32-byte seed array.
     */
    fun generateSeed(): ByteArray {
        val seed = ByteArray(SEED_SIZE_BYTES)
        SecureRandom().nextBytes(seed)
        return seed
    }

    /**
     * Converts a 32-byte seed to a 12-word BIP39 mnemonic phrase.
     *
     * @param seed 32-byte seed (256 bits).
     * @return List of 12 mnemonic words.
     */
    fun seedToMnemonic(seed: ByteArray): List<String> {
        require(seed.size == SEED_SIZE_BYTES) { "Seed must be exactly 32 bytes" }

        // Calculate checksum (first 4 bits of SHA-256)
        val hash = sha256(seed)
        val checksumBits = (hash[0].toInt() and 0xFF) shr 4

        // Combine entropy + checksum into a bit string
        val bits = StringBuilder()
        seed.forEach { byte ->
            bits.append(String.format("%8s", Integer.toBinaryString(byte.toInt() and 0xFF)).replace(' ', '0'))
        }
        bits.append(String.format("%4s", Integer.toBinaryString(checksumBits)).replace(' ', '0').take(4))

        // Split into 11-bit words (12 words = 132 bits)
        val words = mutableListOf<String>()
        for (i in 0 until 12) {
            val start = i * 11
            val end = start + 11
            val wordBits = bits.substring(start, end)
            val index = Integer.parseInt(wordBits, 2)
            words.add(BIP39_WORDLIST[index])
        }

        return words
    }

    /**
     * Converts a 12-word mnemonic phrase back to a 32-byte seed.
     *
     * @param words List of 12 mnemonic words.
     * @return 32-byte seed, or null if validation fails.
     */
    fun mnemonicToSeed(words: List<String>): ByteArray? {
        if (words.size != 12) return null

        // Convert words to indices
        val indices = words.map { word ->
            BIP39_WORDLIST.indexOf(word).takeIf { it >= 0 } ?: return null
        }

        // Build bit string
        val bits = StringBuilder()
        indices.forEach { index ->
            bits.append(String.format("%11s", Integer.toBinaryString(index)).replace(' ', '0'))
        }

        // Extract entropy (first 128 bits = 32 bytes)
        val entropyBits = bits.substring(0, 256)
        val seed = ByteArray(32)
        for (i in 0 until 32) {
            val byteBits = entropyBits.substring(i * 8, (i + 1) * 8)
            seed[i] = Integer.parseInt(byteBits, 2).toByte()
        }

        // Verify checksum
        val hash = sha256(seed)
        val expectedChecksum = (hash[0].toInt() and 0xFF) shr 4
        val actualChecksum = Integer.parseInt(bits.substring(256, 260), 2)

        return if (expectedChecksum == actualChecksum) seed else null
    }

    /**
     * Saves the identity seed securely.
     *
     * @param context Application context.
     * @param seed 32-byte identity seed.
     */
    fun saveSeed(context: Context, seed: ByteArray) {
        require(seed.size == SEED_SIZE_BYTES) { "Seed must be exactly 32 bytes" }

        val prefs = getSecurePreferences(context)
        val encoded = Base64.encodeToString(seed, Base64.NO_WRAP)
        prefs.edit().putString(KEY_SEED, encoded).apply()

        // Also compute and save Node ID (first 8 bytes of SHA-256 of public key)
        // This will be populated after identity generation
    }

    /**
     * Loads the saved identity seed.
     *
     * @param context Application context.
     * @return 32-byte seed, or null if not found.
     */
    fun loadSeed(context: Context): ByteArray? {
        val prefs = getSecurePreferences(context)
        val encoded = prefs.getString(KEY_SEED, null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    /**
     * Checks if an identity has been created.
     */
    fun hasIdentity(context: Context): Boolean {
        return loadSeed(context) != null
    }

    /**
     * Saves the Node ID (8-byte hex string).
     */
    fun saveNodeId(context: Context, nodeId: String) {
        val prefs = context.getSharedPreferences("rezvan_identity", Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_NODE_ID, nodeId).apply()
    }

    /**
     * Loads the saved Node ID.
     */
    fun loadNodeId(context: Context): String? {
        val prefs = context.getSharedPreferences("rezvan_identity", Context.MODE_PRIVATE)
        return prefs.getString(KEY_NODE_ID, null)
    }

    /**
     * Clears all identity data (for factory reset).
     */
    fun clearIdentity(context: Context) {
        getSecurePreferences(context).edit().clear().apply()
        context.getSharedPreferences("rezvan_identity", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun sha256(data: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }
}
