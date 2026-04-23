package com.rezvani.mesh.data.repositories

import android.content.Context
import com.rezvani.mesh.data.AppDatabase
import com.rezvani.mesh.data.dao.ContactDao
import com.rezvani.mesh.data.entities.ContactEntity
import com.rezvani.mesh.data.entities.TrustLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ContactRepository(context: Context, passphrase: ByteArray) {

    private val contactDao: ContactDao = AppDatabase.getInstance(context, passphrase).contactDao()

    fun getAllContacts(): Flow<List<ContactEntity>> = contactDao.getAllContacts()

    fun getNonBlockedContacts(): Flow<List<ContactEntity>> = contactDao.getNonBlockedContacts()

    fun getVerifiedContacts(): Flow<List<ContactEntity>> = contactDao.getVerifiedContacts()

    suspend fun getContact(nodeId: String): ContactEntity? = contactDao.getContactById(nodeId)

    fun getContactFlow(nodeId: String): Flow<ContactEntity?> = contactDao.getContactByIdFlow(nodeId)

    fun searchContacts(query: String): Flow<List<ContactEntity>> = contactDao.searchContacts(query)

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

    suspend fun updateTrustLevel(nodeId: String, trustLevel: Int) {
        contactDao.updateTrustLevel(nodeId, trustLevel)
    }

    suspend fun verifyContact(nodeId: String) {
        contactDao.updateTrustLevel(nodeId, TrustLevel.VERIFIED)
    }

    suspend fun blockContact(nodeId: String) {
        contactDao.updateTrustLevel(nodeId, TrustLevel.BLOCKED)
    }

    suspend fun unblockContact(nodeId: String) {
        contactDao.updateTrustLevel(nodeId, TrustLevel.KNOWN)
    }

    suspend fun updateDisplayName(nodeId: String, displayName: String) {
        contactDao.updateDisplayName(nodeId, displayName)
    }

    suspend fun deleteContact(nodeId: String) {
        contactDao.deleteById(nodeId)
    }

    suspend fun deleteBlockedContacts() {
        contactDao.deleteBlockedContacts()
    }

    suspend fun getDisplayName(nodeId: String): String {
        return contactDao.getContactById(nodeId)?.displayName ?: nodeId.take(8)
    }

    suspend fun isBlocked(nodeId: String): Boolean {
        val contact = contactDao.getContactById(nodeId)
        return contact?.trustLevel == TrustLevel.BLOCKED
    }

    fun getRecentContacts(): Flow<List<ContactEntity>> {
        val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000
        return contactDao.getAllContacts().map { contacts ->
            contacts.filter { it.lastSeen > sevenDaysAgo }
        }
    }
}
