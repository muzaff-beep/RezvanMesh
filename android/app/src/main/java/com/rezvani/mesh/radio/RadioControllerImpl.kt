package com.rezvani.mesh.radio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
import android.util.Log
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

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
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

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var wifiDirectReceiver: BroadcastReceiver? = null

    private var serverSocket: ServerSocket? = null
    private val wifiClients = ConcurrentHashMap<String, Socket>()
    private val serverThread = AtomicReference<Thread>()

    private val radioService: RezvanRadioService? =
        if (context is RezvanRadioService) context else null

    init {
        if (wifiP2pManager != null) {
            wifiP2pChannel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)
            setupWifiDirectReceiver()
            startWifiServer()
        }
    }

    // ... (many methods follow; advertising callback is near the middle of the file) ...
        override fun startBleScan(intervalMs: Long, windowMs: Long) {
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth not enabled; cannot start scan")
            return
        }
        scanIntervalMs = intervalMs
        scanWindowMs = windowMs
        if (isScanning) {
            stopBleScan()
        }
        isScanning = true
        scheduleScanCycle()
    }

    private fun scheduleScanCycle() {
        if (!isScanning) return
        bleScanner?.startScan(null, scanSettings, scanCallback)
        scanHandler.postDelayed({
            bleScanner?.stopScan(scanCallback)
            if (isScanning) {
                scanHandler.postDelayed({
                    scheduleScanCycle()
                }, scanIntervalMs - scanWindowMs)
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
            val bytes = result.scanRecord?.bytes ?: return
            if (bytes.size >= 2 && bytes[0] == 0x52.toByte() && bytes[1] == 0x56.toByte()) {
                radioService?.onPacketReceived(bytes, result.rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error: $errorCode")
        }
    }

    private val scanSettings: ScanSettings
        get() = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

    override fun startBleAdvertising(adData: ByteArray, intervalMs: Int) {
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth not enabled; cannot advertise")
            return
        }
        if (isAdvertising) {
            stopBleAdvertising()
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(MANUFACTURER_ID, adData)
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
            radioService?.let {
                DiagLogger.log(it, "BLE advertising started")
            }
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
            radioService?.let {
                DiagLogger.log(it, "BLE advertising FAILED: $errorCode")
            }
            isAdvertising = false
        }
    }

    override fun connectToPeer(peerMacAddress: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(peerMacAddress) ?: return false
        if (bleGattMap.containsKey(peerMacAddress)) {
            Log.d(TAG, "Already connected or connecting to $peerMacAddress")
            return true
        }
        val gatt = device.connectGatt(context, false, gattCallback)
        bleGattMap[peerMacAddress] = gatt
        return true
    }

    override fun sendBlePacket(peerMacAddress: String, data: ByteArray): Boolean {
        val sender = bleSenderMap[peerMacAddress]
        if (sender == null) {
            Log.w(TAG, "No BLE connection to $peerMacAddress")
            return false
        }
        sender.send(data)
        return true
    }

    override fun disconnectPeer(peerMacAddress: String) {
        bleGattMap.remove(peerMacAddress)?.disconnect()
        bleSenderMap.remove(peerMacAddress)
        cachedRssiMap.remove(peerMacAddress)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val mac = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "BLE connected to $mac")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "BLE disconnected from $mac")
                    bleGattMap.remove(mac)
                    bleSenderMap.remove(mac)
                    cachedRssiMap.remove(mac)
                    gatt.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val writeChar = service.getCharacteristic(CHARACTERISTIC_WRITE_UUID)
                    val notifyChar = service.getCharacteristic(CHARACTERISTIC_NOTIFY_UUID)
                    if (writeChar != null) {
                        val sender = BlePacketSender(gatt)
                        sender.setCharacteristic(writeChar)
                        bleSenderMap[gatt.device.address] = sender
                        if (notifyChar != null) {
                            gatt.setCharacteristicNotification(notifyChar, true)
                            val descriptor = notifyChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                            descriptor?.let {
                                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(it)
                            }
                        }
                        Log.d(TAG, "BLE services ready for ${gatt.device.address}")
                    } else {
                        Log.e(TAG, "Write characteristic not found")
                        gatt.disconnect()
                    }
                } else {
                    Log.e(TAG, "Service UUID not found")
                    gatt.disconnect()
                }
            } else {
                Log.e(TAG, "Service discovery failed")
                gatt.disconnect()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHARACTERISTIC_NOTIFY_UUID) {
                val data = characteristic.value
                val rssi = cachedRssiMap[gatt.device.address] ?: 0
                radioService?.onPacketReceived(data, rssi)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val sender = bleSenderMap[gatt.device.address]
            sender?.onWriteComplete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cachedRssiMap[gatt.device.address] = rssi
            }
        }
    }

    override fun isWifiDirectSupported(): Boolean = wifiP2pManager != null

    override fun startWifiDirectDiscovery() {
        wifiP2pChannel?.let { channel ->
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "WiFi Direct discovery started")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "WiFi Direct discovery failed: $reason")
                }
            })
        }
    }

    override fun stopWifiDirectDiscovery() {
        wifiP2pChannel?.let { channel ->
            wifiP2pManager?.stopPeerDiscovery(channel, null)
        }
    }

    @Suppress("DEPRECATION")
    override fun connectWifiDirect(peerMacAddress: String): Boolean {
        val config = WifiP2pConfig().apply {
            deviceAddress = peerMacAddress
        }
        wifiP2pChannel?.let { channel ->
            wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "WiFi Direct connection initiated to $peerMacAddress")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "WiFi Direct connection failed: $reason")
                }
            })
            return true
        }
        return false
    }

    override fun sendWifiPacket(peerIpAddress: String, port: Int, data: ByteArray): Boolean {
        val sender = WifiPacketSender(peerIpAddress, port)
        return sender.send(data)
    }

    override fun disconnectWifiDirect(peerIpAddress: String) {
        wifiClients[peerIpAddress]?.close()
        wifiClients.remove(peerIpAddress)
        wifiP2pChannel?.let { channel ->
            wifiP2pManager?.removeGroup(channel, null)
        }
    }

    private fun setupWifiDirectReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        wifiDirectReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        wifiP2pManager?.requestPeers(wifiP2pChannel) { _: WifiP2pDeviceList -> }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> { }
                }
            }
        }
        context.registerReceiver(wifiDirectReceiver, intentFilter)
    }

    private fun startWifiServer() {
        if (serverThread.get()?.isAlive == true) return
        serverThread.set(Thread {
            try {
                serverSocket = ServerSocket(WIFI_PORT)
                Log.d(TAG, "WiFi server listening on port $WIFI_PORT")
                while (!Thread.currentThread().isInterrupted) {
                    val client = serverSocket?.accept() ?: continue
                    handleWifiClient(client)
                }
            } catch (e: IOException) {
                Log.e(TAG, "WiFi server error", e)
            }
        }.apply { start() })
    }

    private fun handleWifiClient(socket: Socket) {
        val clientId = "${socket.inetAddress.hostAddress}:${socket.port}"
        wifiClients[clientId] = socket
        Thread {
            try {
                val input = DataInputStream(socket.getInputStream())
                while (!socket.isClosed) {
                    val length = input.readUnsignedShort()
                    val data = ByteArray(length)
                    input.readFully(data)
                    radioService?.onPacketReceived(data, 0)
                }
            } catch (e: EOFException) {
            } catch (e: IOException) {
                Log.w(TAG, "WiFi client read error", e)
            } finally {
                wifiClients.remove(clientId)
                try { socket.close() } catch (_: IOException) {}
            }
        }.start()
    }

    private fun stopWifiServer() {
        serverThread.get()?.interrupt()
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        wifiClients.values.forEach { try { it.close() } catch (_: IOException) {} }
        wifiClients.clear()
    }

    override fun getCurrentRssi(peerMacAddress: String): Int {
        val gatt = bleGattMap[peerMacAddress] ?: return Int.MIN_VALUE
        gatt.readRemoteRssi()
        return cachedRssiMap[peerMacAddress] ?: Int.MIN_VALUE
    }

    override fun setBleTxPower(dbm: Int) {
        Log.d(TAG, "setBleTxPower: $dbm (not directly supported)")
    }

    override fun setWifiTxPower(dbm: Int) {
        Log.d(TAG, "setWifiTxPower: $dbm (not supported)")
    }

    override fun onDestroy() {
        stopBleScan()
        stopBleAdvertising()
        bleGattMap.values.forEach { it.close() }
        bleGattMap.clear()
        bleSenderMap.clear()
        stopWifiServer()
        wifiDirectReceiver?.let { context.unregisterReceiver(it) }
        wifiP2pChannel?.close()
    }

    companion object {
        private const val TAG = "RadioControllerImpl"
        private const val WIFI_PORT = 4237
        private const val MANUFACTURER_ID = 0xFFFF
        private val SERVICE_UUID = UUID.fromString("0000a1b2-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_WRITE_UUID = UUID.fromString("0000c3d4-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_NOTIFY_UUID = UUID.fromString("0000e5f6-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
