package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.rezvani.mesh.ui.screens.ActivityItem
import com.rezvani.mesh.ui.screens.ActivityType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StatusUiState(
    val active: Boolean = true,
    val statusDetail: String = "8 devices connected · Range ~120m",
    val signalStrength: String = "-68",
    val avgHops: Int = 2,
    val activityItems: List<ActivityItem> = listOf(
        ActivityItem("RV-7A3F1C joined the mesh", "2 minutes ago", ActivityType.JOIN),
        ActivityItem("Emergency alert received from Neda A.", "8 minutes ago", ActivityType.ALERT),
        ActivityItem("Message delivered to Ali R. (3 hops)", "12 minutes ago")
    )
)

class StatusViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(StatusUiState())
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.value = _uiState.value.copy(
            signalStrength = "-${(60..80).random()}",
            avgHops = (1..4).random()
        )
    }
}
