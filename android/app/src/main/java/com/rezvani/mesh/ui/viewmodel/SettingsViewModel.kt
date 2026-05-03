package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.ui.components.PowerState
import com.rezvani.mesh.utils.LocaleHelper
import com.rezvani.mesh.utils.PowerProfileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val prefs = context.getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
        _uiState.value = SettingsUiState(
            darkMode = prefs.getBoolean("dark_mode", true),
            currentLanguage = LocaleHelper.getSavedLanguage(context),
            nodeId = IdentityBackupHelper.loadNodeId(context) ?: "Unknown",
            powerOverride = prefs.getString("power_override", null)?.let { PowerState.valueOf(it) },
            storageUsed = "N/A",
            versionName = "1.0.0",
            versionCode = 1,
            buildVariant = "civilian"
        )
    }

    fun toggleDarkMode() {
        val newMode = !_uiState.value.darkMode
        _uiState.value = _uiState.value.copy(darkMode = newMode)
        context.getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("dark_mode", newMode).apply()
    }

    fun setLanguage(code: String) {
        LocaleHelper.saveLanguage(context, code)
        _uiState.value = _uiState.value.copy(currentLanguage = code)
    }

    fun setPowerOverride(state: PowerState) {
        context.getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
            .edit().putString("power_override", state.name).apply()
        _uiState.value = _uiState.value.copy(powerOverride = state)
    }

    fun clearPowerOverride() {
        context.getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
            .edit().remove("power_override").apply()
        _uiState.value = _uiState.value.copy(powerOverride = null)
    }

    fun openSystemPowerSettings() {
        PowerProfileManager.openBatterySaverSettings(context)
    }

    fun openWriteSettings() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
    }

    fun clearOldMessages() {
        // TODO: implement message cleanup
    }
}

data class SettingsUiState(
    val darkMode: Boolean = true,
    val currentLanguage: String = "fa",
    val nodeId: String = "Unknown",
    val powerOverride: PowerState? = null,
    val autoPowerState: PowerState = PowerState.BALANCED,
    val storageUsed: String = "0 MB",
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val buildVariant: String = "civilian"
)
