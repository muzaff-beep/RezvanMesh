package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainViewModel : ViewModel() {
    val isOnboardingComplete = MutableStateFlow(false)
    fun setOnboardingComplete(complete: Boolean) { isOnboardingComplete.value = complete }
}
