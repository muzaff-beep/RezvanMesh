package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.rezvani.mesh.ui.components.PowerState
import kotlinx.coroutines.flow.MutableStateFlow

class SettingsViewModel : ViewModel() {
    val uiState = MutableStateFlow(SettingsUiState())

    fun setLanguage(code: String) {}
    fun setPowerOverride(state: PowerState) {}
    fun clearPowerOverride() {}
    fun clearOldMessages() {}
}

data class SettingsUiState(
    val currentLanguage: String = "fa",
    val nodeId: String = "Unknown",
    val mnemonicWords: List<String> = emptyList(),
    val powerOverride: PowerState? = null,
    val autoPowerState: PowerState = PowerState.BALANCED,
    val storageUsed: String = "0 MB",
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val buildVariant: String = "civilian"
)
