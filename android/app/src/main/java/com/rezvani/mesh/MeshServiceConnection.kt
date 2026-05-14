// android/app/src/main/java/com/rezvani/mesh/MeshServiceConnection.kt

package com.rezvani.mesh

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.rezvani.mesh.radio.RezvanRadioService
import com.rezvani.mesh.utils.DiagLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MeshServiceConnection(private val context: Context) : ServiceConnection {

    var activeService: RezvanRadioService? = null
        private set

    private val _receivedMessages = MutableStateFlow<List<DecryptedMessage>>(emptyList())
    val receivedMessages: StateFlow<List<DecryptedMessage>> = _receivedMessages

    fun sendTextMessage(peerNodeId: ByteArray, text: String) {
        val service = activeService ?: return
        // Resolve NodeId -> MAC via routing table (simplified: use a dummy MAC, GATT will handle via cached devices)
        val mac = service.getMacForNodeId(peerNodeId) ?: return
        service.sendMessage(peerNodeId, text.toByteArray())
        DiagLogger.ble("sendTextMessage enqueued to $mac")
    }

    fun sendEmergencyBroadcast(message: String) {
        activeService?.sendBroadcast(message.toByteArray())
    }

    fun addReceivedMessage(msg: DecryptedMessage) {
        _receivedMessages.value = _receivedMessages.value + msg
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = service as? RezvanRadioService.LocalBinder
        activeService = binder?.getService()
        activeService?.setConnection(this)
        DiagLogger.ble("MeshServiceConnection bound")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        activeService?.setConnection(null)
        activeService = null
    }
}