package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.MeshCore
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

    private var mockCounter = 0

    init {
        viewModelScope.launch {
            // Convert the entries flow to a list of formatted strings
            val logFlow = DiagLogger.entries.map { list -> list.map { it.formatted() } }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

            combine(
                MeshServiceConnection.nodeCount,
                MeshServiceConnection.signalStrength,
                MeshServiceConnection.isServiceConnected,
                logFlow
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

    fun injectMockPeer() {
        viewModelScope.launch {
            try {
                val mockId = ByteArray(16) { (0xA0 + mockCounter).toByte() }
                mockCounter++

                val ogm = ByteArray(62).apply {
                    this[0] = 0x01; this[1] = 0x01; this[2] = 0x0A
                    System.arraycopy(mockId, 0, this, 3, 8)
                    this[10] = 0x00; this[11] = 0x32
                    this[18] = 200.toByte(); this[22] = 0x01
                }

                val ptr = MeshServiceConnection.meshCorePtr.value
                if (ptr != null && ptr != 0L) {
                    MeshCore.nativeProcessIncoming(ptr, ogm, -50, System.currentTimeMillis() * 1000)
                    DiagLogger.ble("Mock peer injected, RSSI=-50")
                }
            } catch (e: Exception) {
                DiagLogger.rust("Mock inject failed: ${e.message}")
            }
        }
    }
}
