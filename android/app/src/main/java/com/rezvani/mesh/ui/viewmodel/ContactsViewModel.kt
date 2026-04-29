package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.data.AppDatabase
import com.rezvani.mesh.data.entities.ContactEntity
import com.rezvani.mesh.data.repositories.ContactRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ContactsViewModel(application: Application) : AndroidViewModel(application) {

    private val dbPassphrase = "rezvan_temp_passphrase".toByteArray() // Replace with Keystore-derived key
    private val contactRepo = ContactRepository(application, dbPassphrase)

    private val _allContacts = MutableStateFlow<List<ContactEntity>>(emptyList())
    val allContacts: StateFlow<List<ContactEntity>> = _allContacts.asStateFlow()

    init {
        loadContacts()
    }

    private fun loadContacts() {
        viewModelScope.launch {
            contactRepo.getAllContacts().collect { contacts ->
                _allContacts.value = contacts
            }
        }
    }

    fun discoverContact(nodeId: String) {
        viewModelScope.launch {
            contactRepo.discoverContact(nodeId)
        }
    }

    fun verifyContact(nodeId: String) {
        viewModelScope.launch {
            contactRepo.verifyContact(nodeId)
        }
    }

    fun blockContact(nodeId: String) {
        viewModelScope.launch {
            contactRepo.blockContact(nodeId)
        }
    }

    fun updateDisplayName(nodeId: String, displayName: String) {
        viewModelScope.launch {
            contactRepo.updateDisplayName(nodeId, displayName)
        }
    }
}
