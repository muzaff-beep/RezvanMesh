package com.rezvani.mesh.ui.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.backup.MacIdentityProvider
import com.rezvani.mesh.ui.screens.OnboardingStep
import com.rezvani.mesh.ui.screens.OnboardingUiState
import java.security.SecureRandom

class OnboardingViewModel : ViewModel() {

    companion object {
        private const val PERMISSION_LOCAL_MAC_ADDRESS = "android.permission.LOCAL_MAC_ADDRESS"
    }

    private val _uiState = mutableStateOf(OnboardingUiState())
    val uiState: State<OnboardingUiState> = _uiState

    fun enterMesh(context: Context) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        try {
            val existingSeed = IdentityBackupHelper.loadSeed(context)
            if (existingSeed != null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    step = OnboardingStep.DONE
                )
                return
            }

            var seed: ByteArray? = null
            if (hasMacPermission(context)) {
                seed = MacIdentityProvider.deriveSeed(context)
            }

            if (seed == null) {
                seed = ByteArray(32)
                SecureRandom().nextBytes(seed)
            }

            MacIdentityProvider.saveSeed(context, seed)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                step = OnboardingStep.DONE
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = e.message ?: "Failed to create identity"
            )
        }
    }

    private fun hasMacPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, PERMISSION_LOCAL_MAC_ADDRESS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
