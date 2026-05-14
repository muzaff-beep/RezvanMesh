// android/app/src/main/java/com/rezvani/mesh/MeshServiceConnection.kt

package com.rezvani.mesh

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.rezvani.mesh.radio.RezvanRadioService
import com.rezvani.mesh.rust.DecryptedMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MeshServiceConnection(private val context: Context) : ServiceConnection {

    var activeService: RezvanRadioService? = null
        private set

    private val _receivedMessages = MutableStateFlow<List<DecryptedMessage>>(emptyList())
    val receivedMessages: StateFlow<List<DecryptedMessage>> = _receivedMessages

    companion object {
        val nodeCount = MutableStateFlow(0)
        val signalStrength = MutableStateFlow("-68 dBm")
        val isServiceConnected = MutableStateFlow(false)
        val meshCorePtr = MutableStateFlow<Long?>(null)

        fun onServiceConnected(service: RezvanRadioService) {
            isServiceConnected.value = true
        }

        fun onServiceDisconnected() {
            isServiceConnected.value = false
        }
    }

    fun sendTextMessage(peerNodeId: ByteArray, text: String) {
        activeService?.sendMessage(peerNodeId, text.toByteArray())
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
        isServiceConnected.value = true
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        activeService?.setConnection(null)
        activeService = null
        isServiceConnected.value = false
    }
}