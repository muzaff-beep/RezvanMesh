package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.utils.DiagLogger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class StatusUiState(
    val active: Boolean = false,
    val statusDetail: String = "No peers detected · Seeking devices...",
    val signalStrength: String = "-68 dBm",
    val nodeCount: Int = 0,
    val logLines: List<String> = emptyList()
)

class StatusViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(StatusUiState())
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                MeshServiceConnection.nodeCount,
                MeshServiceConnection.signalStrength,
                MeshServiceConnection.isServiceConnected,
                DiagLogger.logLines
            ) { count, strength, connected, logs ->
                val active = connected && count > 0
                StatusUiState(
                    active = active,
                    statusDetail = if (active) {
                        "$count device${if (count > 1) "s" else ""} connected"
                    } else if (connected) {
                        "Listening for devices…"
                    } else {
                        "Service disconnected"
                    },
                    signalStrength = strength,
                    nodeCount = count,
                    logLines = logs
                )
            }.collect { _uiState.value = it }
        }
    }
}
