// android/app/src/main/java/com/rezvani/mesh/ui/screens/ContactsScreen.kt

package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.data.Contact
import com.rezvani.mesh.data.ContactsRepository
import com.rezvani.mesh.utils.BarcodeUtils

@Composable
fun ContactsScreen(meshConnection: MeshServiceConnection) {
    val context = LocalContext.current
    val repository = remember { ContactsRepository(context) }
    val contacts by repository.contacts.collectAsState()
    var showOwnQr by remember { mutableStateOf(false) }
    var showManualAdd by remember { mutableStateOf(false) }
    var newContactName by remember { mutableStateOf("") }
    var manualNodeId by remember { mutableStateOf("") }

    val ownNodeIdHex = remember {
        MeshServiceConnection.activeService?.ownNodeId?.joinToString("") { "%02x".format(it) } ?: ""
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Contacts", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (ownNodeIdHex.isNotBlank()) showOwnQr = true },
                enabled = ownNodeIdHex.isNotBlank()
            ) {
                Text("My QR")
            }
            Button(onClick = { showManualAdd = true }) {
                Text("Add Manually")
            }
        }

        if (showManualAdd) {
            AlertDialog(
                onDismissRequest = { showManualAdd = false },
                title = { Text("Add Contact") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newContactName,
                            onValueChange = { newContactName = it },
                            label = { Text("Contact name") }
                        )
                        OutlinedTextField(
                            value = manualNodeId,
                            onValueChange = { manualNodeId = it },
                            label = { Text("Node ID (hex)") }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (newContactName.isNotBlank() && manualNodeId.length == 16) {
                            repository.addContact(Contact(newContactName, manualNodeId))
                            newContactName = ""
                            manualNodeId = ""
                            showManualAdd = false
                        }
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    Button(onClick = { showManualAdd = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showOwnQr && ownNodeIdHex.isNotBlank()) {
            AlertDialog(
                onDismissRequest = { showOwnQr = false },
                title = { Text("Your Mesh ID") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val bitmap = remember(ownNodeIdHex) {
                            BarcodeUtils.generateQrCodeBitmap(ownNodeIdHex)
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier.size(200.dp)
                            )
                        } else {
                            Text("Unable to generate QR code")
                        }
                        Text(ownNodeIdHex, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Button(onClick = { showOwnQr = false }) {
                        Text("Close")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Saved Contacts:", style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(contacts, key = { it.nodeIdHex }) { contact ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(contact.name)
                            Text(contact.nodeIdHex, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            repository.deleteContact(contact.nodeIdHex)
                        }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}