package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.rezvani.mesh.backup.IdentityBackupHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _isOnboardingComplete = MutableStateFlow(IdentityBackupHelper.hasIdentity(application))
    val isOnboardingComplete: StateFlow<Boolean> = _isOnboardingComplete.asStateFlow()

    fun setOnboardingComplete(complete: Boolean) {
        _isOnboardingComplete.value = complete
    }
}
