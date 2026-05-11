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

    private var isScanning = false
    private var scanIntervalMs = 0L
    private var scanWindowMs = 0L
    private val scanHandler = Handler(Looper.getMainLooper())

    private var isAdvertising = false

    private var ownNodeId: ByteArray? = null
    private var ownBleAddress: String? = null

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
        ownNodeId = nodeId
    }

    init {
        if (wifiP2pManager != null) {
            wifiP2pChannel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)
            setupWifiDirectReceiver()
            startWifiServer()
        }
    }

    override fun startBleScan(intervalMs: Long, windowMs: Long) {
        if (bluetoothAdapter?.isEnabled != true) return
        scanIntervalMs = intervalMs
        scanWindowMs = windowMs
        if (isScanning) stopBleScan()
        isScanning = true
        scheduleScanCycle()
    }

    private fun scheduleScanCycle() {
        if (!isScanning) return
        bleScanner?.startScan(null, scanSettings, scanCallback)
        scanHandler.postDelayed({
            bleScanner?.stopScan(scanCallback)
            if (isScanning) {
                scanHandler.postDelayed({ scheduleScanCycle() }, scanIntervalMs - scanWindowMs)
            }
        }, scanWindowMs)
    }

    override fun stopBleScan() {
        isScanning = false
        bleScanner?.stopScan(scanCallback)
        scanHandler.removeCallbacksAndMessages(null)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Extract manufacturer data from the scan record
            val scanRecord = result.scanRecord ?: return
            val manufacturerData = scanRecord.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
            if (manufacturerData.size < 2) return

            // Check for Rezvan protocol marker 0x52 0x56
            if (manufacturerData[0] != 0x52.toByte() || manufacturerData[1] != 0x56.toByte()) return

            radioService?.onPacketReceived(manufacturerData, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            DiagLogger.ble("BLE scan FAILED code=$errorCode")
        }
    }

    private val scanSettings: ScanSettings
        get() = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

    override fun startBleAdvertising(adData: ByteArray, intervalMs: Int) {
        if (bluetoothAdapter?.isEnabled != true) return
        if (isAdvertising) stopBleAdvertising()

        // BLE manufacturer data can hold at most 29 bytes of payload
        // (2 bytes reserved for manufacturer ID, total 31 bytes)
        val truncatedPayload = if (adData.size > 29) adData.copyOf(29) else adData

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(MANUFACTURER_ID, truncatedPayload)
            .build()

        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        isAdvertising = true
    }

    override fun stopBleAdvertising() {
        bleAdvertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE advertising started")
            ownBleAddress = bluetoothAdapter?.address
            DiagLogger.ble("BLE advertising started (loopback=${BuildConfig.DEBUG_LOOPBACK})")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising FAILED: $errorCode")
            DiagLogger.ble("BLE advertising FAILED: $errorCode")
            isAdvertising = false
        }
    }

    override fun connectToPeer(peerMacAddress: String): Boolean { return true }
    override fun sendBlePacket(peerMacAddress: String, data: ByteArray): Boolean { return false }
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

    override fun onDestroy() {
        stopBleScan()
        stopBleAdvertising()
    }

    companion object {
        private const val TAG = "RadioControllerImpl"
        private const val MANUFACTURER_ID = 0xFFFF
        private val BLE_SERVICE_UUID = ParcelUuid(UUID.fromString("0000a1b2-0000-1000-8000-00805f9b34fb"))
        const val WIFI_PORT = 4237
    }
}
