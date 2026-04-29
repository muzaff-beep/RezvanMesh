package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.data.AppDatabase
import com.rezvani.mesh.data.entities.ChannelEntity
import com.rezvani.mesh.data.repositories.ChannelRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChannelsViewModel(application: Application) : AndroidViewModel(application) {

    private val dbPassphrase = "rezvan_temp_passphrase".toByteArray() // Replace with Keystore-derived key
    private val channelRepo = ChannelRepository(application, dbPassphrase)

    private val _allChannels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val allChannels: StateFlow<List<ChannelEntity>> = _allChannels.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadChannels()
    }

    private fun loadChannels() {
        viewModelScope.launch {
            channelRepo.getAllChannels().collect { channels ->
                _allChannels.value = channels
            }
        }
    }

    fun refreshChannels() {
        viewModelScope.launch {
            _isRefreshing.value = true
            // In a real mesh, you'd broadcast a channel discovery request.
            // For now, just simulate a refresh delay.
            kotlinx.coroutines.delay(1000)
            _isRefreshing.value = false
        }
    }

    fun createChannel(name: String, description: String, isPrivate: Boolean, password: String?) {
        viewModelScope.launch {
            channelRepo.createChannel(name, description, isPrivate, password)
        }
    }

    fun joinPrivateChannel(channelId: Int, password: String, onSuccess: () -> Unit, onError: () -> Unit) {
        viewModelScope.launch {
            val joined = channelRepo.joinPrivateChannel(channelId, password)
            if (joined) onSuccess() else onError()
        }
    }

    fun joinPublicChannel(channelId: Int) {
        viewModelScope.launch {
            channelRepo.joinChannel(channelId)
        }
    }
}
