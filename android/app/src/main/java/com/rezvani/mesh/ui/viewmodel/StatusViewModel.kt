package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.ui.screens.ActivityItem
import com.rezvani.mesh.ui.screens.ActivityType
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class StatusUiState(
    val active: Boolean = false,
    val statusDetail: String = "No peers detected · Seeking devices...",
    val signalStrength: String = "-68 dBm",
    val avgHops: Int = 2,
    val activityItems: List<ActivityItem> = emptyList()
)

class StatusViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(StatusUiState())
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                MeshServiceConnection.nodeCount,
                MeshServiceConnection.signalStrength,
                MeshServiceConnection.isServiceConnected
            ) { count, strength, connected ->
                if (connected && count > 0) {
                    StatusUiState(
                        active = true,
                        statusDetail = "$count device${if (count > 1) "s" else ""} connected · Range ~120m",
                        signalStrength = strength,
                        activityItems = listOf(
                            ActivityItem("Mesh established with $count device${if (count > 1) "s" else ""}", "just now", ActivityType.JOIN)
                        )
                    )
                } else if (connected) {
                    StatusUiState(
                        active = false,
                        statusDetail = "Listening for devices…",
                        signalStrength = "-68 dBm"
                    )
                } else {
                    StatusUiState(
                        active = false,
                        statusDetail = "Service disconnected",
                        signalStrength = "-68 dBm"
                    )
                }
            }.collect { _uiState.value = it }
        }
    }
}
