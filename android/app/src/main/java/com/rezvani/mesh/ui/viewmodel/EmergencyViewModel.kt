// android/app/src/main/java/com/rezvani/mesh/ui/viewmodel/EmergencyViewModel.kt

package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.MeshServiceConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EmergencyViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EmergencyUiState())
    val uiState: StateFlow<EmergencyUiState> = _uiState.asStateFlow()

    fun updateSeverity(level: Int) {
        _uiState.value = _uiState.value.copy(selectedSeverity = level)
    }

    fun sendEmergencyAlert() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(sendStatus = EmergencySendStatus.Sending)
            try {
                // Broadcast to ALL nodes – use the special broadcast ID
                val broadcastId = ByteArray(8) { 0xFF.toByte() }
                val messageText = "EMERGENCY LEVEL ${_uiState.value.selectedSeverity}"
                MeshServiceConnection.activeService?.sendMessage(broadcastId, messageText.toByteArray())
                delay(1000)
                _uiState.value = _uiState.value.copy(sendStatus = EmergencySendStatus.Success("Alert sent"))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(sendStatus = EmergencySendStatus.Failed(e.message ?: "Unknown error"))
            }
        }
    }
}

data class EmergencyUiState(
    val selectedSeverity: Int = 1,
    val sendStatus: EmergencySendStatus = EmergencySendStatus.Idle
)

sealed class EmergencySendStatus {
    object Idle : EmergencySendStatus()
    object Sending : EmergencySendStatus()
    data class Success(val message: String) : EmergencySendStatus()
    data class Failed(val message: String) : EmergencySendStatus()
}