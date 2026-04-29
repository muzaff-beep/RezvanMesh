package com.rezvani.mesh.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.ui.screens.OnboardingStep
import com.rezvani.mesh.ui.screens.OnboardingUiState

class OnboardingViewModel : ViewModel() {
    var uiState by mutableStateOf(OnboardingUiState())
        private set

    fun nextStep() {
        uiState = uiState.copy(step = OnboardingStep.GENERATE)
    }

    fun previousStep() {
        uiState = uiState.copy(step = OnboardingStep.WELCOME)
    }

    fun generateIdentity(context: Context) {
        uiState = uiState.copy(isGenerating = true, errorMessage = null)
        try {
            val seed = IdentityBackupHelper.generateSeed()
            IdentityBackupHelper.saveSeed(context, seed)
            val mnemonic = IdentityBackupHelper.seedToMnemonic(seed)
            uiState = uiState.copy(
                isGenerating = false,
                mnemonicWords = mnemonic,
                step = OnboardingStep.BACKUP
            )
        } catch (e: Exception) {
            uiState = uiState.copy(
                isGenerating = false,
                errorMessage = e.message ?: "Failed to generate identity"
            )
        }
    }

    fun showRestoreDialog() {
        uiState = uiState.copy(showRestoreDialog = true)
    }

    fun dismissRestoreDialog() {
        uiState = uiState.copy(showRestoreDialog = false)
    }

    fun goToRestoreStep() {
        uiState = uiState.copy(step = OnboardingStep.RESTORE)
    }

    fun updateMnemonicInput(input: String) {
        uiState = uiState.copy(mnemonicInput = input)
    }

    fun restoreIdentity(context: Context, mnemonic: String) {
        uiState = uiState.copy(isRestoring = true, errorMessage = null)
        val words = mnemonic.trim().split("\\s+".toRegex())
        val seed = IdentityBackupHelper.mnemonicToSeed(words)
        if (seed != null) {
            IdentityBackupHelper.saveSeed(context, seed)
            uiState = uiState.copy(isRestoring = false, step = OnboardingStep.BACKUP)
        } else {
            uiState = uiState.copy(
                isRestoring = false,
                errorMessage = "Invalid mnemonic. Check the 12 words and try again."
            )
        }
    }

    fun toggleConfirmBackup() {
        uiState = uiState.copy(hasConfirmedBackup = !uiState.hasConfirmedBackup)
    }

    fun confirmBackup() {
        // Seed is already saved – just mark onboarding as complete in prefs
        // The actual service start is triggered by the completion callback
    }
}
