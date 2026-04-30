package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.data.AppDatabase
import com.rezvani.mesh.data.entities.MessageEntity
import com.rezvani.mesh.data.repositories.MessageRepository
import com.rezvani.mesh.MeshServiceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val dbPassphrase = "rezvan_temp_passphrase".toByteArray() // Replace with Keystore-derived key
    private val messageRepo = MessageRepository(application, dbPassphrase)

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    fun loadMessages(conversationId: String) {
        viewModelScope.launch {
            messageRepo.getMessages(conversationId).collect { msgs ->
                _messages.value = msgs
            }
        }
    }

    fun sendTextMessage(conversationId: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _isSending.value = true
            val messageId = messageRepo.insertTextMessage(
                conversationId = conversationId,
                text = text,
                isOutgoing = true
            )
            val recipientId = conversationId.toByteArray().take(8).toByteArray()
            val success = MeshServiceConnection.sendTextMessage(recipientId, text)
            if (!success) {
                messageRepo.updateStatus(messageId, 4) // FAILED
            } else {
                messageRepo.updateStatus(messageId, 1) // SENT
            }
            _isSending.value = false
        }
    }

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> {
        return messageRepo.getMessages(conversationId)
    }

    fun markAsRead(conversationId: String) {
        viewModelScope.launch {
            messageRepo.markConversationAsRead(conversationId)
        }
    }
}
