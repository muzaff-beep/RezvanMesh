package com.rezvani.mesh.radio

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.rezvani.mesh.BuildConfig
import com.rezvani.mesh.utils.DiagLogger
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RadioControllerImpl(private val context: Context) : RadioController {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private var bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    private val bleGattMap = ConcurrentHashMap<String, BluetoothGatt>()
    private val bleSenderMap = ConcurrentHashMap<String, BlePacketSender>()
    private val cachedRssiMap = ConcurrentHashMap<String, Int>()

    private val isScanning = AtomicBoolean(false)
    private var scanIntervalMs = 0L
    private var scanWindowMs = 0L
    private val scanHandler = Handler(Looper.getMainLooper())

    private val isAdvertising = AtomicBoolean(false)
    private var advertisingSet: AdvertisingSet? = null

    private var ownNodeId: ByteArray? = null
    private var ownBleAddress: String? = null

    private val rxTotal = AtomicLong(0)
    private val rxLoopback = AtomicLong(0)
    private val rxPeer = AtomicLong(0)
    private val txStarts = AtomicLong(0)

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            DiagLogger.ble(
                "heartbeat",
                "scan" to isScanning.get().toString(),
                "adv" to isAdvertising.get().toString(),
                "rx" to rxTotal.get().toString(),
                "self" to rxLoopback.get().toString(),
                "peer" to rxPeer.get().toString(),
                "tx" to txStarts.get().toString()
            )
            heartbeatHandler.postDelayed(this, 10_000L)
        }
    }

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var wifiDirectReceiver: BroadcastReceiver? = null

    private var serverSocket: ServerSocket? = null
    private val wifiClients = ConcurrentHashMap<String, Socket>()
    private val serverThread = AtomicReference<Thread>()

    private val radioService: RezvanRadioService? =
        if (context is RezvanRadioService) context else null

    fun setOwnNodeId(nodeId: ByteArray) {
        if (nodeId.size != NODE_ID_LEN) {
            DiagLogger.ble("setOwnNodeId WRONG LENGTH: got ${nodeId.size}, expected $NODE_ID_LEN")
            return
        }
        ownNodeId = nodeId.copyOf()
        DiagLogger.ble("ownNodeId set", "prefix" to nodeId.take(4).joinToString("") { "%02x".format(it) })
    }

    fun isCurrentlyAdvertising(): Boolean = isAdvertising.get()
    fun isCurrentlyScanning(): Boolean = isScanning.get()

    fun snapshotCounters(): Map<String, Long> = mapOf(
        "rx_total" to rxTotal.get(), "rx_loopback" to rxLoopback.get(),
        "rx_peer" to rxPeer.get(), "tx_starts" to txStarts.get(),
        "scanning" to (if (isScanning.get()) 1L else 0L),
        "advertising" to (if (isAdvertising.get()) 1L else 0L)
    )

    init {
        DiagLogger.ble(
            "RadioController init",
            "adapter" to (bluetoothAdapter != null).toString(),
            "scanner" to (bleScanner != null).toString(),
            "advertiser" to (bleAdvertiser != null).toString(),
            "wifiP2p" to (wifiP2pManager != null).toString()
        )
        if (wifiP2pManager != null) {
            wifiP2pChannel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)
            setupWifiDirectReceiver()
            startWifiServer()
        }
        startHeartbeat()
    }

    override fun startBleScan(intervalMs: Long, windowMs: Long) {
        if (bluetoothAdapter?.isEnabled != true) {
            DiagLogger.ble("startBleScan ABORT: BT disabled or null adapter")
            return
        }
        if (bleScanner == null) {
            DiagLogger.ble("startBleScan ABORT: scanner is null")
            return
        }
        scanIntervalMs = intervalMs
        scanWindowMs = windowMs
        if (isScanning.get()) stopBleScan()
        isScanning.set(true)
        DiagLogger.ble("BLE scan starting", "interval" to intervalMs.toString(), "window" to windowMs.toString(),
            "continuous" to (windowMs >= intervalMs).toString())
        scheduleScanCycle()
    }

    private fun scheduleScanCycle() {
        if (!isScanning.get()) return

        val scanSettingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            scanSettingsBuilder.setLegacy(false)
            scanSettingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        val settings = scanSettingsBuilder.build()

        val scanFilter = ScanFilter.Builder()
            .setManufacturerData(MANUFACTURER_ID, byteArrayOf(MAGIC_BYTE_0, MAGIC_BYTE_1))
            .build()

        try {
            bleScanner?.startScan(listOf(scanFilter), settings, scanCallback)
        } catch (t: Throwable) {
            DiagLogger.ble("startScan threw: ${t.message}")
        }

        scanHandler.postDelayed({
            try { bleScanner?.stopScan(scanCallback) } catch (t: Throwable) {
                DiagLogger.ble("stopBleScan threw: ${t.message}")
            }
            scanHandler.postDelayed({ scheduleScanCycle() },
                (scanIntervalMs - scanWindowMs).coerceAtLeast(0))
        }, scanWindowMs)
    }

    override fun stopBleScan() {
        if (!isScanning.get()) return
        isScanning.set(false)
        try { bleScanner?.stopScan(scanCallback) } catch (t: Throwable) {
            DiagLogger.ble("stopBleScan threw: ${t.message}")
        }
        scanHandler.removeCallbacksAndMessages(null)
        DiagLogger.ble("BLE scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val manufacturerData = scanRecord.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
            if (manufacturerData.size < NODE_ID_OFFSET + NODE_ID_LEN) return
            if (manufacturerData[0] != MAGIC_BYTE_0 || manufacturerData[1] != MAGIC_BYTE_1) return

            rxTotal.incrementAndGet()

            val isSelf = ownNodeId?.let { own ->
                var match = true
                for (i in 0 until NODE_ID_LEN) {
                    if (manufacturerData[NODE_ID_OFFSET + i] != own[i]) { match = false; break }
                }
                match
            } ?: false

            if (isSelf) {
                rxLoopback.incrementAndGet()
                if (BuildConfig.DEBUG_LOOPBACK) {
                    DiagLogger.ble("LOOPBACK rx", "rssi" to result.rssi.toString(), "size" to manufacturerData.size.toString())
                    radioService?.onPacketReceived(manufacturerData, result.rssi)
                }
                return
            }

            rxPeer.incrementAndGet()
            DiagLogger.ble("BLE rx peer", "rssi" to result.rssi.toString(), "size" to manufacturerData.size.toString())
            cachedRssiMap[result.device.address] = result.rssi
            radioService?.onPacketReceived(manufacturerData, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            DiagLogger.ble("BLE scan FAILED", "code" to errorCode.toString())
        }
    }

    override fun startBleAdvertising(adData: ByteArray, intervalMs: Int) {
        if (bluetoothAdapter?.isEnabled != true) {
            DiagLogger.ble("startBleAdvertising ABORT: BT disabled")
            return
        }
        if (bleAdvertiser == null) {
            DiagLogger.ble("startBleAdvertising ABORT: advertiser is null")
            return
        }

        if (bluetoothAdapter?.isLeExtendedAdvertisingSupported == true) {
            startExtendedAdvertising(adData)
        } else {
            DiagLogger.ble("Extended advertising NOT supported – using legacy 24‑byte path")
            startLegacyAdvertising(adData)
        }
    }

    private fun startExtendedAdvertising(adData: ByteArray) {
        if (isAdvertising.get()) stopBleAdvertising()

        val maxLen = bluetoothAdapter?.leMaximumAdvertisingDataLength ?: 254
        val payload = if (adData.size > maxLen) adData.copyOf(maxLen) else adData

        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(MANUFACTURER_ID, payload)
            .build()

        DiagLogger.ble("Extended adv starting", "payload" to payload.size.toString(), "max" to maxLen.toString())

        try {
            bleAdvertiser?.startAdvertisingSet(params, data, null, null, null, extendedCallback)
        } catch (t: Throwable) {
            DiagLogger.ble("startAdvertisingSet threw: ${t.message}")
        }
    }

    private val extendedCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                advertisingSet = set
                isAdvertising.set(true)
                txStarts.incrementAndGet()
                DiagLogger.ble("Extended adv STARTED", "txPower" to txPower.toString())
            } else {
                isAdvertising.set(false)
                DiagLogger.ble("Extended adv FAILED status=$status")
            }
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            isAdvertising.set(false)
            advertisingSet = null
            DiagLogger.ble("Extended adv STOPPED")
        }
    }

    private fun startLegacyAdvertising(adData: ByteArray) {
        if (isAdvertising.get()) stopBleAdvertising()

        val truncated = if (adData.size > 24) adData.copyOf(24) else adData
        DiagLogger.ble("Legacy adv starting", "payload" to truncated.size.toString(),
            "dropped" to (adData.size - truncated.size).toString())

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(MANUFACTURER_ID, truncated)
            .build()

        try {
            bleAdvertiser?.startAdvertising(settings, data, legacyAdvertiseCallback)
        } catch (t: Throwable) {
            DiagLogger.ble("Legacy startAdvertising threw: ${t.message}")
        }
    }

    override fun stopBleAdvertising() {
        if (!isAdvertising.get()) return
        try {
            if (advertisingSet != null) {
                bleAdvertiser?.stopAdvertisingSet(extendedCallback)
                advertisingSet = null
            } else {
                bleAdvertiser?.stopAdvertising(legacyAdvertiseCallback)
            }
        } catch (t: Throwable) {
            DiagLogger.ble("stopAdvertising threw: ${t.message}")
        }
        isAdvertising.set(false)
        DiagLogger.ble("BLE advertising stopped")
    }

    private val legacyAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising.set(true)
            txStarts.incrementAndGet()
            DiagLogger.ble("Legacy adv STARTED")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising.set(false)
            DiagLogger.ble("Legacy adv FAILED status=$errorCode")
        }
    }

    override fun connectToPeer(peerMacAddress: String): Boolean = true
    override fun sendBlePacket(peerMacAddress: String, data: ByteArray): Boolean = false
    override fun disconnectPeer(peerMacAddress: String) {}

    private val gattCallback = object : BluetoothGattCallback() {}

    override fun isWifiDirectSupported() = wifiP2pManager != null
    override fun startWifiDirectDiscovery() {}
    override fun stopWifiDirectDiscovery() {}
    override fun connectWifiDirect(peerMacAddress: String) = false
    override fun sendWifiPacket(peerIpAddress: String, port: Int, data: ByteArray) = false
    override fun disconnectWifiDirect(peerIpAddress: String) {}

    private fun setupWifiDirectReceiver() {}
    private fun startWifiServer() {}
    private fun handleWifiClient(socket: Socket) {}
    private fun stopWifiServer() {}

    override fun getCurrentRssi(peerMacAddress: String) = Int.MIN_VALUE
    override fun setBleTxPower(dbm: Int) {}
    override fun setWifiTxPower(dbm: Int) {}

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, 10_000L)
    }

    private fun stopHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    override fun onDestroy() {
        stopHeartbeat()
        stopBleScan()
        stopBleAdvertising()
        DiagLogger.ble("RadioController destroyed")
    }

    companion object {
        private const val TAG = "RadioControllerImpl"
        private const val MANUFACTURER_ID = 0xFFFF
        private val MAGIC_BYTE_0 = 0x52.toByte()
        private val MAGIC_BYTE_1 = 0x56.toByte()
        private const val NODE_ID_OFFSET = 2
        private const val NODE_ID_LEN = 8
        private val BLE_SERVICE_UUID = ParcelUuid(UUID.fromString("0000a1b2-0000-1000-8000-00805f9b34fb"))
        const val WIFI_PORT = 4237
    }
}