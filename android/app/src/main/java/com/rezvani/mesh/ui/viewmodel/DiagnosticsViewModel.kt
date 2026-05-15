// android/app/src/main/java/com/rezvani/mesh/ui/viewmodel/DiagnosticsViewModel.kt

package com.rezvani.mesh.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rezvani.mesh.MeshCore
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.ui.screens.TestStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val loopbackStatus: TestStatus = TestStatus.IDLE,
    val injectStatus: TestStatus = TestStatus.IDLE,
    val routingStatus: TestStatus = TestStatus.IDLE,
    val crashStatus: TestStatus = TestStatus.IDLE,
    val outputText: String = ""
)

class DiagnosticsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    fun runLoopbackTest() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loopbackStatus = TestStatus.RUNNING, outputText = "Loopback running for 10s…")
            delay(10_000)
            val nodes = MeshServiceConnection.nodeCount.value
            val rssi = MeshServiceConnection.signalStrength.value
            if (nodes > 0) {
                _uiState.value = _uiState.value.copy(
                    loopbackStatus = TestStatus.PASS,
                    outputText = "Loopback PASS: $nodes node(s) seen, RSSI=$rssi"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    loopbackStatus = TestStatus.FAIL,
                    outputText = "Loopback FAIL: no nodes seen in 10s"
                )
            }
        }
    }

    fun injectMockPeers(count: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(injectStatus = TestStatus.RUNNING, outputText = "Injecting $count mock peers…")
            val ptr = MeshServiceConnection.meshCorePtr.value
            if (ptr == null || ptr == 0L) {
                _uiState.value = _uiState.value.copy(injectStatus = TestStatus.FAIL, outputText = "Engine not running")
                return@launch
            }
            for (i in 0 until count) {
                val mock = buildMockOgm(i)
                MeshCore.nativeProcessIncoming(ptr, mock, -50, System.currentTimeMillis() * 1000)
                delay(200)
            }
            _uiState.value = _uiState.value.copy(injectStatus = TestStatus.PASS, outputText = "Injected $count mock peers")
        }
    }

    fun showRoutingTable() {
        _uiState.value = _uiState.value.copy(routingStatus = TestStatus.PASS, outputText = "Routing table: (not yet wired to native)")
    }

    private fun buildMockOgm(index: Int): ByteArray {
        val id = ByteArray(16) { (0xA0 + index).toByte() }
        val ogm = ByteArray(62)
        ogm[0] = 0x01; ogm[1] = 0x01; ogm[2] = 0x0A
        System.arraycopy(id, 0, ogm, 3, 8)
        ogm[10] = 0x00; ogm[11] = 0x32
        ogm[18] = 200.toByte(); ogm[22] = 0x01
        return ogm
    }
}