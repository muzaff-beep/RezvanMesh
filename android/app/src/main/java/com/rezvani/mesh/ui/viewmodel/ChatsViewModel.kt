package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.rezvani.mesh.ui.components.PowerState
import com.rezvani.mesh.ui.screens.ConversationItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ChatsViewModel : ViewModel() {
    val conversations = MutableStateFlow<List<ConversationItem>>(emptyList())
    val powerState = MutableStateFlow(PowerState.BALANCED)
    val batteryLevel = MutableStateFlow(100)
    val isRefreshing = MutableStateFlow(false)
}
