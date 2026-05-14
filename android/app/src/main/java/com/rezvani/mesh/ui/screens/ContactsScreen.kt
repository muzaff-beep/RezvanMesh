// android/app/src/main/java/com/rezvani/mesh/ui/screens/ContactsScreen.kt

package com.rezvani.mesh.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.data.Contact
import com.rezvani.mesh.data.ContactsRepository
import com.rezvani.mesh.utils.BarcodeUtils
import java.util.concurrent.Executors

@Composable
fun ContactsScreen(meshConnection: MeshServiceConnection) {
    val context = LocalContext.current
    val repository = remember { ContactsRepository(context) }
    var contacts by remember { mutableStateOf(repository.loadContacts()) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showOwnQr by remember { mutableStateOf(false) }
    var scannedNodeId by remember { mutableStateOf<String?>(null) }
    var newContactName by remember { mutableStateOf("") }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showQrScanner = true
    }

    val ownNodeIdHex = remember {
        meshConnection.activeService?.ownNodeId?.joinToString("") { "%02x".format(it) } ?: ""
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Contacts", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    showQrScanner = true
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }) {
                Text("Scan QR")
            }
            Button(onClick = { showOwnQr = true }) {
                Text("My QR")
            }
        }

        if (showQrScanner) {
            QrCodeScanner(
                onCodeScanned = { text ->
                    showQrScanner = false
                    scannedNodeId = text
                },
                onDismiss = { showQrScanner = false }
            )
        }

        if (scannedNodeId != null) {
            AlertDialog(
                onDismissRequest = { scannedNodeId = null },
                title = { Text("Add Contact") },
                text = {
                    Column {
                        Text("Node ID: ${scannedNodeId}")
                        OutlinedTextField(
                            value = newContactName,
                            onValueChange = { newContactName = it },
                            label = { Text("Contact name") }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (newContactName.isNotBlank()) {
                            repository.addContact(Contact(newContactName, scannedNodeId!!))
                            contacts = repository.loadContacts()
                            scannedNodeId = null
                            newContactName = ""
                        }
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    Button(onClick = { scannedNodeId = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showOwnQr) {
            AlertDialog(
                onDismissRequest = { showOwnQr = false },
                title = { Text("Your Mesh ID") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val bitmap = remember { BarcodeUtils.generateQrCodeBitmap(ownNodeIdHex) }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier.size(200.dp)
                            )
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
            items(contacts) { contact ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(contact.name)
                            Text(contact.nodeIdHex, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            repository.deleteContact(contact.nodeIdHex)
                            contacts = repository.loadContacts()
                        }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QrCodeScanner(
    onCodeScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var analyzedBarcodes = remember { mutableSetOf<String>() }

    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView?.surfaceProvider)
        }
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(executor) { imageProxy ->
                    val planes = imageProxy.planes
                    if (planes.isNotEmpty()) {
                        val yPlane = planes[0].buffer
                        val ySize = yPlane.remaining()
                        val yData = ByteArray(ySize)
                        yPlane.get(yData)

                        val source = PlanarYUVLuminanceSource(
                            yData,
                            imageProxy.width,
                            imageProxy.height,
                            0, 0,
                            imageProxy.width,
                            imageProxy.height,
                            false
                        )
                        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                        val reader = QRCodeReader()
                        try {
                            val result = reader.decode(binaryBitmap)
                            if (result != null && !analyzedBarcodes.contains(result.text)) {
                                analyzedBarcodes.add(result.text)
                                onCodeScanned(result.text)
                            }
                        } catch (_: Exception) {}
                    }
                    imageProxy.close()
                }
            }
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        } catch (_: Exception) {}
        onDispose {
            executor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { ctx ->
            PreviewView(ctx).also { previewView = it }
        }, modifier = Modifier.fillMaxSize())
        Button(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Text("Cancel")
        }
    }
}