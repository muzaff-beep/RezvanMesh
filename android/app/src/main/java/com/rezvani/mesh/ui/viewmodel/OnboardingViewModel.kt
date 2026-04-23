package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.rezvani.mesh.ui.screens.OnboardingStep
import com.rezvani.mesh.ui.screens.OnboardingUiState

class OnboardingViewModel : ViewModel() {
    val uiState = androidx.compose.runtime.mutableStateOf(OnboardingUiState())

    fun nextStep() { /* TODO: implement */ }
    fun previousStep() { /* TODO: implement */ }
    fun generateIdentity(context: android.content.Context) { /* TODO: implement */ }
    fun showRestoreDialog() { /* TODO: implement */ }
    fun dismissRestoreDialog() { /* TODO: implement */ }
    fun goToRestoreStep() { /* TODO: implement */ }
    fun updateMnemonicInput(input: String) { /* TODO: implement */ }
    fun restoreIdentity(context: android.content.Context, mnemonic: String) { /* TODO: implement */ }
    fun toggleConfirmBackup() { /* TODO: implement */ }
    fun confirmBackup() { /* TODO: implement */ }
}
