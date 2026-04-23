package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class EmergencyViewModel : ViewModel() {
    val uiState = MutableStateFlow(EmergencyUiState())
    fun updateSeverity(level: Int) { /* TODO */ }
    fun sendEmergencyAlert() { /* TODO */ }
}

data class EmergencyUiState(
    val selectedSeverity: Int = 1,
    val sendStatus: EmergencySendStatus = EmergencySendStatus.Idle
)

sealed class EmergencySendStatus {
    object Idle : EmergencySendStatus()
    object Sending : EmergencySendStatus()
    data class Success(val message: String = "") : EmergencySendStatus()
    data class Failed(val message: String) : EmergencySendStatus()
}
