package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class ChannelsViewModel : ViewModel() {
    val allChannels = MutableStateFlow<List<com.rezvani.mesh.data.entities.ChannelEntity>>(emptyList())
    val isRefreshing = MutableStateFlow(false)

    fun refreshChannels() { /* TODO */ }
    fun joinPrivateChannel(channelId: Int, password: String, onSuccess: () -> Unit, onError: () -> Unit) { /* TODO */ }
}
