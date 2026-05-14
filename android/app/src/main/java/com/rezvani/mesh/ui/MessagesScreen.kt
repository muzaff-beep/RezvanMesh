package com.rezvani.mesh.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.DecryptedMessage

@Composable
fun MessagesScreen(meshConnection: MeshServiceConnection) {
    val messages by meshConnection.receivedMessages.collectAsState()
    var text by remember { mutableStateOf("") }
    var peerInput by remember { mutableStateOf("") }   // target NodeID hex

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Mesh Messages", style = MaterialTheme.typography.headlineMedium)

        // Emergency broadcast button
        Button(onClick = {
            meshConnection.sendEmergencyBroadcast("EMERGENCY: ${text.ifEmpty { "Mayday" }}")
            text = ""
        }) {
            Text("Send Emergency Broadcast")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Direct message
        OutlinedTextField(
            value = peerInput,
            onValueChange = { peerInput = it },
            label = { Text("Peer Node ID (hex)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            val nodeId = try {
                val bytes = peerInput.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (bytes.size == 8) bytes else null
            } catch (_: Exception) { null }
            if (nodeId != null) {
                meshConnection.sendTextMessage(nodeId, text)
                text = ""
            }
        }) {
            Text("Send to Peer")
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Received:", style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(messages) { msg ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("From: ${msg.senderId.joinToString("") { "%02x".format(it) }}")
                        Text("Type: ${if (msg.messageType == 3.toByte()) "EMERGENCY" else "Text"}")
                        Text("Content: ${String(msg.content)}")
                    }
                }
            }
        }
    }
}
