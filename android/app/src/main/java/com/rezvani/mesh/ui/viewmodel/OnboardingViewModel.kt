package com.rezvani.mesh.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.rezvani.mesh.backup.MacIdentityProvider
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.ui.screens.OnboardingStep
import com.rezvani.mesh.ui.screens.OnboardingUiState

class OnboardingViewModel : ViewModel() {

    private val _uiState = mutableStateOf(OnboardingUiState())
    val uiState: State<OnboardingUiState> = _uiState

    fun enterMesh(context: Context) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        try {
            // Try loading existing seed first
            val existingSeed = IdentityBackupHelper.loadSeed(context)
            if (existingSeed != null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    step = OnboardingStep.DONE
                )
                return
            }

            // Derive new seed from MAC
            val seed = MacIdentityProvider.deriveSeed(context)
            if (seed != null) {
                MacIdentityProvider.saveSeed(context, seed)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    step = OnboardingStep.DONE
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Could not read device MAC address. Please grant all permissions."
                )
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = e.message ?: "Failed to create identity"
            )
        }
    }
}
