package com.rezvani.mesh.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
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

    // Camera permission
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showQrScanner = true
    }

    // Own Node ID hex
    val ownNodeIdHex = remember {
        meshConnection.activeService?.ownNodeId?.joinToString("") { "%02x".format(it) } ?: ""
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Contacts", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                // Check camera permission
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

        // Add contact dialog
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

        // Own QR display
        if (showOwnQr) {
            AlertDialog(
                onDismissRequest = { showOwnQr = false },
                title = { Text("Your Mesh ID") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val bitmap = remember { BarcodeUtils.generateQrCodeBitmap(ownNodeIdHex) }
                        bitmap?.let {
                            androidx.compose.foundation.Image(
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
                            // Delete contact
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

    // Use camera analysis
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
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val planes = mediaImage.planes
                        val buffer = planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        // Use ZXing to decode QR
                        val source = com.google.zxing.RGBLuminanceSource(imageProxy.width, imageProxy.height, bytes)
                        val binarizer = com.google.zxing.common.HybridBinarizer(source)
                        val binaryBitmap = com.google.zxing.BinaryBitmap(binarizer)
                        val reader = QRCodeReader()
                        try {
                            val result = reader.decode(binaryBitmap)
                            if (result != null && result.text.isNotEmpty() && !analyzedBarcodes.contains(result.text)) {
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
