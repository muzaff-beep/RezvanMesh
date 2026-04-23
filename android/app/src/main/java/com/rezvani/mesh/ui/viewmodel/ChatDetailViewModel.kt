package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class ChatDetailViewModel : ViewModel() {
    val isSending = MutableStateFlow(false)

    fun loadMessages(conversationId: String) { /* TODO */ }
    fun sendTextMessage(conversationId: String, text: String) { /* TODO */ }
    fun getMessages(conversationId: String) = MutableStateFlow<List<com.rezvani.mesh.data.entities.MessageEntity>>(emptyList())
}
