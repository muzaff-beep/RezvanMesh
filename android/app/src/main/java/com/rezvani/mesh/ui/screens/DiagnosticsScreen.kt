// android/app/src/main/java/com/rezvani/mesh/ui/screens/DiagnosticsScreen.kt

@file:OptIn(ExperimentalMaterial3Api::class)
package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.BuildConfig
import com.rezvani.mesh.ui.viewmodel.DiagnosticsViewModel

@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Diagnostics") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Self‑Test Harness",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (!BuildConfig.DEBUG_LOOPBACK && !BuildConfig.DEBUG_INJECT_PEERS) {
                Text(
                    text = "Debug features disabled in this build.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            TestButton("Loopback capture (10s)", uiState.loopbackStatus) {
                viewModel.runLoopbackTest()
            }
            TestButton("Inject 5 mock peers", uiState.injectStatus) {
                viewModel.injectMockPeers(5)
            }
            TestButton("Show routing table", uiState.routingStatus) {
                viewModel.showRoutingTable()
            }
            TestButton("Force Kotlin crash", uiState.crashStatus) {
                throw RuntimeException("Diagnostics forced crash")
            }

            if (uiState.outputText.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = uiState.outputText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TestButton(label: String, status: TestStatus, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = status != TestStatus.RUNNING
    ) {
        Text(label)
        Spacer(Modifier.width(8.dp))
        when (status) {
            TestStatus.IDLE -> {}
            TestStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            TestStatus.PASS -> Text("✅")
            TestStatus.FAIL -> Text("❌")
        }
    }
}

enum class TestStatus { IDLE, RUNNING, PASS, FAIL }