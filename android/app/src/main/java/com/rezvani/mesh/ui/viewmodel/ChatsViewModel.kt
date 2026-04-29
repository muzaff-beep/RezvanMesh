package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.data.AppDatabase
import com.rezvani.mesh.data.entities.MessageEntity
import com.rezvani.mesh.data.repositories.MessageRepository
import com.rezvani.mesh.ui.components.PowerState
import com.rezvani.mesh.ui.screens.ConversationItem
import com.rezvani.mesh.ui.screens.MessageStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatsViewModel(application: Application) : AndroidViewModel(application) {

    private val dbPassphrase = "rezvan_temp_passphrase".toByteArray() // Replace with Keystore-derived key
    private val messageRepo = MessageRepository(application, dbPassphrase)

    private val _conversations = MutableStateFlow<List<ConversationItem>>(emptyList())
    val conversations: StateFlow<List<ConversationItem>> = _conversations.asStateFlow()

    private val _powerState = MutableStateFlow(PowerState.BALANCED)
    val powerState: StateFlow<PowerState> = _powerState.asStateFlow()

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadConversations()
    }

    private fun loadConversations() {
        viewModelScope.launch {
            messageRepo.getConversationIds().collect { ids ->
                val items = ids.map { convId ->
                    val lastMsg = messageRepo.getLastMessage(convId).first()
                    val unread = messageRepo.getUnreadCount(convId).first()
                    ConversationItem(
                        conversationId = convId,
                        contactName = convId.take(8),
                        lastMessage = lastMsg?.content ?: "",
                        lastMessageTime = lastMsg?.timestamp ?: System.currentTimeMillis(),
                        unreadCount = unread,
                        status = MessageStatus.SENT
                    )
                }
                _conversations.value = items
            }
        }
    }
}
