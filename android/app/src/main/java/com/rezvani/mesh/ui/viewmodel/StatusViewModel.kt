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

    /**
     * Injects a synthetic OGM packet directly into the native engine,
     * simulating a peer discovery. Only used in debug builds.
     */
    fun injectMockPeer() {
        viewModelScope.launch {
            try {
                val mockId = ByteArray(16) { (0xA0 + mockCounter).toByte() }
                mockCounter++

                // Build a minimal OGM packet matching MeshPacketHeader + OGMPayload layout
                val ogm = ByteArray(62).apply {
                    // MeshPacketHeader
                    this[0] = 0x01             // version
                    this[1] = 0x01             // packet_type = OGM
                    this[2] = 0x0A             // ttl = 10
                    System.arraycopy(mockId, 0, this, 3, 8)  // originator (8 bytes)
                    this[11] = 0x01            // hop_count = 1
                    // payload_len = 50 at offset 10-11 (big-endian)
                    this[10] = 0x00
                    this[11] = 0x32
                    // OGMPayload
                    this[18] = 200.toByte()     // link_quality
                    this[22] = 0x01            // neighbor_count = 1
                }

                val ptr = MeshServiceConnection.meshCorePtr.value
                if (ptr != null && ptr != 0L) {
                    MeshCore.nativeProcessIncoming(ptr, ogm, -50, System.currentTimeMillis() * 1000)
                    DiagLogger.log("Mock peer injected, RSSI=-50")
                }
            } catch (e: Exception) {
                DiagLogger.log("Mock inject failed: ${e.message}")
            }
        }
    }
}
