package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class ContactsViewModel : ViewModel() {
    val allContacts = MutableStateFlow<List<com.rezvani.mesh.data.entities.ContactEntity>>(emptyList())
}
