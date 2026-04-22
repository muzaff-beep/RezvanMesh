
File: ActionDispatcher.kt

Location: android/app/src/main/java/com/rezvani/mesh/radio/ActionDispatcher.kt

```kotlin
package com.rezvani.mesh.radio

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the binary action format produced by the Rust mesh core and dispatches
 * commands to the appropriate [RadioController] methods or broadcasts to the UI.
 *
 * Action Format (from Team A):
```

· [1 byte: Action Count N]
    · For each action:
    ·
    ·
    ·
    · ```
·
· Action Types:
· · 0x01: Send BLE advertisement (payload: 31-byte ad data)
· · 0x02: Send WiFi Direct packet (payload: 4B IP, 2B port, data)
· · 0x03: Send BLE packet to specific peer (payload: 6B MAC, data)
· · 0x04: Update scan interval (payload: 4B interval_ms, 4B window_ms)
· · 0x05: Notify UI of new message (payload: serialized DecryptedMessage)
    */
    class ActionDispatcher(private val context: Context) {
  private val localBroadcastManager: LocalBroadcastManager by lazy {
  LocalBroadcastManager.getInstance(context.applicationContext)
  }
  /**
  · Dispatches a serialized action byte array to the appropriate handlers.
  ·
  · @param actionBytes The serialized actions from native core (may be null or empty).
  · @param radio The RadioController instance to execute radio commands.
    */
    fun dispatch(actionBytes: ByteArray?, radio: RadioController) {
    if (actionBytes == null || actionBytes.isEmpty()) {
    return
    }
    val buffer = ByteBuffer.wrap(actionBytes).order(ByteOrder.BIG_ENDIAN)
    // Check if there's at least one byte for count
    if (!buffer.hasRemaining()) {
    Log.w(TAG, "Empty action buffer")
    return
    }
    val count = (buffer.get().toInt() and 0xFF)
    Log.d(TAG, "Dispatching $count actions")
    repeat(count) {
    if (buffer.remaining() < 3) {
    Log.e(TAG, "Incomplete action header at index $it")
    return
    }
    }
    }
  private fun handleBleAdvertisement(payload: ByteArray, radio: RadioController) {
  if (payload.size != 31) {
  Log.w(TAG, "BLE advertisement payload must be 31 bytes, got ${payload.size}")
  return
  }
  // Default advertising interval: 5000 ms (5 seconds)
  radio.startBleAdvertising(payload, 5000)
  }
  private fun handleWifiPacket(payload: ByteArray, radio: RadioController) {
  if (payload.size < 6) {
  Log.w(TAG, "WiFi packet payload too short: ${payload.size}")
  return
  }
  }
  private fun handleBlePacket(payload: ByteArray, radio: RadioController) {
  if (payload.size < 6) {
  Log.w(TAG, "BLE packet payload too short: ${payload.size}")
  return
  }
  }
  private fun handleUpdateScan(payload: ByteArray, radio: RadioController) {
  if (payload.size < 8) {
  Log.w(TAG, "UpdateScan payload too short: ${payload.size}")
  return
  }
  }
  private fun handleNotifyUi(payload: ByteArray) {
  val intent = Intent(ACTION_NEW_MESSAGE)
  intent.putExtra(EXTRA_DECRYPTED_MESSAGE, payload)
  localBroadcastManager.sendBroadcast(intent)
  Log.d(TAG, "Broadcasted new message to UI")
  }
  companion object {
  private const val TAG = "ActionDispatcher"
  }
  }

```

    **Integration Note for Team C:**

    In `RezvanRadioService`, instantiate `ActionDispatcher` as a member variable:

```kotlin
class RezvanRadioService : Service() {
    private lateinit var actionDispatcher: ActionDispatcher

    override fun onCreate() {
        super.onCreate()
        actionDispatcher = ActionDispatcher(this)
        // ... rest of initialization
    }

    private fun startPeriodicTick() {
        tickHandler.post(object : Runnable {
            override fun run() {
                val actions = MeshCore.nativeTick(meshCorePtr)
                actionDispatcher.dispatch(actions, radioController)
                // ...
            }
        })
    }

    fun onPacketReceived(rawPacket: ByteArray, rssi: Int) {
        val timestampUs = System.currentTimeMillis() * 1000
        val result = MeshCore.nativeProcessIncoming(meshCorePtr, rawPacket, rssi, timestampUs)
        actionDispatcher.dispatch(result, radioController)
    }
}
