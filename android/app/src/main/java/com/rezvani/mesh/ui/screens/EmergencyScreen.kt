// android/app/src/main/java/com/rezvani/mesh/ui/screens/EmergencyScreen.kt

package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.ui.components.EmergencyButton
import com.rezvani.mesh.ui.components.SeverityPicker
import com.rezvani.mesh.ui.viewmodel.EmergencyViewModel
import com.rezvani.mesh.ui.viewmodel.EmergencySendStatus

@Composable
fun EmergencyScreen(
    viewModel: EmergencyViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Emergency Broadcast",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        SeverityPicker(
            selectedLevel = uiState.selectedSeverity,
            onLevelSelected = { viewModel.updateSeverity(it) },
            modifier = Modifier.fillMaxWidth()
        )

        EmergencyButton(
            onClick = { viewModel.sendEmergencyAlert() },
            enabled = uiState.sendStatus !is EmergencySendStatus.Sending,
            modifier = Modifier.fillMaxWidth()
        )

        when (val status = uiState.sendStatus) {
            is EmergencySendStatus.Idle -> {}
            is EmergencySendStatus.Sending -> {
                CircularProgressIndicator()
            }
            is EmergencySendStatus.Success -> {
                Text(
                    text = status.message,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            is EmergencySendStatus.Failed -> {
                Text(
                    text = status.message,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}