package com.rezvani.mesh.data.repositories

import android.content.Context
import com.rezvani.mesh.data.AppDatabase
import com.rezvani.mesh.data.dao.ContactDao
import com.rezvani.mesh.data.entities.ContactEntity
import com.rezvani.mesh.data.entities.TrustLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Repository for managing contact data.
 */
class ContactRepository(context: Context, passphrase: ByteArray) {

    private val contactDao: ContactDao = AppDatabase.getInstance(context, passphrase).contactDao()

    /**
     * Flow of all contacts.
     */
    fun getAllContacts(): Flow<List<ContactEntity>> = contactDao.getAllContacts()

    /**
     * Flow of non-blocked contacts only.
     */
    fun getNonBlockedContacts(): Flow<List<ContactEntity>> = contactDao.getNonBlockedContacts()

    /**
     * Flow of verified contacts.
     */
    fun getVerifiedContacts(): Flow<List<ContactEntity>> = contactDao.getVerifiedContacts()

    /**
     * Gets a specific contact by node ID.
     */
    suspend fun getContact(nodeId: String): ContactEntity? = contactDao.getContactById(nodeId)

    /**
     * Flow of a specific contact.
     */
    fun getContactFlow(nodeId: String): Flow<ContactEntity?> = contactDao.getContactByIdFlow(nodeId)

    /**
     * Searches contacts by name or node ID.
     */
    fun searchContacts(query: String): Flow<List<ContactEntity>> = contactDao.searchContacts(query)

    /**
     * Adds or updates a contact.
     */
    suspend fun saveContact(
        nodeId: String,
        displayName: String,
        trustLevel: Int = TrustLevel.KNOWN,
        publicKey: String? = null
    ) {
        val existing = contactDao.getContactById(nodeId)
        val contact = if (existing != null) {
            existing.copy(
                displayName = displayName,
                trustLevel = trustLevel,
                lastSeen = System.currentTimeMillis(),
                publicKey = publicKey ?: existing.publicKey
            )
        } else {
            ContactEntity(
                nodeId = nodeId,
                displayName = displayName,
                trustLevel = trustLevel,
                lastSeen = System.currentTimeMillis(),
                publicKey = publicKey
            )
        }
        contactDao.insert(contact)
    }

    /**
     * Discovers a new contact from mesh advertisement.
     */
    suspend fun discoverContact(nodeId: String): ContactEntity {
        val existing = contactDao.getContactById(nodeId)
        return if (existing != null) {
            val updated = existing.copy(lastSeen = System.currentTimeMillis())
            contactDao.update(updated)
            updated
        } else {
            val contact = ContactEntity(
                nodeId = nodeId,
                displayName = nodeId.take(8),
                trustLevel = TrustLevel.KNOWN,
                lastSeen = System.currentTimeMillis(),
                publicKey = null
            )
            contactDao.insert(contact)
            contact
        }
    }

    /**
     * Updates the trust level of a contact.
     */
    suspend fun updateTrustLevel(nodeId: String, trustLevel: Int) {
        contactDao.updateTrustLevel(nodeId, trustLevel)
    }

    /**
     * Verifies a contact (sets trust level to VERIFIED).
     */
    suspend fun verifyContact(nodeId: String) {
        contactDao.updateTrustLevel(nodeId, TrustLevel.VERIFIED)
    }

    /**
     * Blocks a contact.
     */
    suspend fun blockContact(nodeId: String) {
        contactDao.updateTrustLevel(nodeId, TrustLevel.BLOCKED)
    }

    /**
     * Unblocks a contact (sets back to KNOWN).
     */
    suspend fun unblockContact(nodeId: String) {
        contactDao.updateTrustLevel(nodeId, TrustLevel.KNOWN)
    }

    /**
     * Updates a contact's display name.
     */
    suspend fun updateDisplayName(nodeId: String, displayName: String) {
        contactDao.updateDisplayName(nodeId, displayName)
    }

    /**
     * Deletes a contact.
     */
    suspend fun deleteContact(nodeId: String) {
        contactDao.deleteById(nodeId)
    }

    /**
     * Deletes all blocked contacts.
     */
    suspend fun deleteBlockedContacts() {
        contactDao.deleteBlockedContacts()
    }

    /**
     * Gets the display name for a node ID, or returns the ID if not found.
     */
    suspend fun getDisplayName(nodeId: String): String {
        return contactDao.getContactById(nodeId)?.displayName ?: nodeId.take(8)
    }

    /**
     * Checks if a contact is blocked.
     */
    suspend fun isBlocked(nodeId: String): Boolean {
        val contact = contactDao.getContactById(nodeId)
        return contact?.trustLevel == TrustLevel.BLOCKED
    }

    /**
     * Gets all contacts that have been seen recently (within last 7 days).
     */
    fun getRecentContacts(): Flow<List<ContactEntity>> {
        val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000
        return contactDao.getAllContacts().map {
            contacts ->
            contacts.filter {
                it.lastSeen > sevenDaysAgo
            }
        }
    }
}
