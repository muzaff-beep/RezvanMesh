package com.rezvani.mesh.radio

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
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
    private var gattServer: BluetoothGattServer? = null
    private var gattServerStartAttempted = false

    private val bleGattMap = ConcurrentHashMap<String, BluetoothGatt>()
    private val bleSenderMap = ConcurrentHashMap<String, BlePacketSender>()
    private val cachedRssiMap = ConcurrentHashMap<String, Int>()
    private val nodeIdToMac = ConcurrentHashMap<String, String>()

    private val isScanning = AtomicBoolean(false)
    private val scanHandler = Handler(Looper.getMainLooper())

    private val isAdvertising = AtomicBoolean(false)
    private var advertisingSet: AdvertisingSet? = null
    private var pendingAdvertiseData: ByteArray? = null

    private var ownNodeId: ByteArray? = null

    private val rxTotal = AtomicLong(0)
    private val rxLoopback = AtomicLong(0)
    private val rxPeer = AtomicLong(0)
    private val rxScanAll = AtomicLong(0)
    private val txStarts = AtomicLong(0)

    private val rawLogLimit = AtomicLong(5)

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
                "all" to rxScanAll.get().toString(),
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

    private val MESH_SERVICE_UUID = UUID.fromString("0000a1b2-0000-1000-8000-00805f9b34fb")
    private val MESH_CHARACTERISTIC_WRITE_UUID = UUID.fromString("0000a1b3-0000-1000-8000-00805f9b34fb")
    private val MESH_CHARACTERISTIC_NOTIFY_UUID = UUID.fromString("0000a1b4-0000-1000-8000-00805f9b34fb")

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    DiagLogger.ble("Bluetooth re-enabled, restarting GATT server")
                    startGattServerIfPermitted()
                    bleScanner = bluetoothAdapter?.bluetoothLeScanner
                    bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
                    if (isScanning.get()) {
                        startBleScan(1000, 1000)
                    }
                    if (isAdvertising.get() && pendingAdvertiseData != null) {
                        startLegacyAdvertising(pendingAdvertiseData!!)
                    }
                }
            }
        }
    }

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
        context.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        // GATT server is deferred to startBleScan or tick – avoids Samsung null-parameter crash
    }

    override fun startBleScan(intervalMs: Long, windowMs: Long) {
        if (!hasScanPermission()) {
            DiagLogger.ble("startBleScan ABORT: missing BLUETOOTH_SCAN permission")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            DiagLogger.ble("startBleScan ABORT: BT disabled or null adapter")
            return
        }
        if (bleScanner == null) {
            DiagLogger.ble("startBleScan ABORT: scanner is null")
            return
        }
        // Retry GATT server if it failed earlier (safe context now)
        if (gattServer == null && !gattServerStartAttempted) {
            startGattServerIfPermitted()
        }
        if (isScanning.get()) return
        isScanning.set(true)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        DiagLogger.ble("BLE scan starting – legacy mode, continuous")
        try {
            bleScanner?.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            DiagLogger.err("BLE", "Scan start failed: permission missing", e)
            isScanning.set(false)
        }
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
            rxScanAll.incrementAndGet()

            val scanRecord = result.scanRecord ?: return

            // Log only the first few raw advertisements for diagnostic purposes
            if (rawLogLimit.get() > 0) {
                val mfrData = scanRecord.getManufacturerSpecificData()
                val ids = mutableListOf<Int>()
                if (mfrData != null) {
                    for (i in 0 until mfrData.size()) {
                        ids.add(mfrData.keyAt(i))
                    }
                }
                DiagLogger.ble("raw_scan",
                    "addr" to result.device.address.takeLast(5),
                    "rssi" to result.rssi.toString(),
                    "mfr_ids" to ids.joinToString(","))
                rawLogLimit.decrementAndGet()
            }

            val manufacturerData = scanRecord.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
            if (manufacturerData.size < 26) return

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
                    DiagLogger.ble("LOOPBACK rx",
                        "rssi" to result.rssi.toString(),
                        "size" to manufacturerData.size.toString())
                    radioService?.onPacketReceived(manufacturerData, result.rssi)
                }
                return
            }

            rxPeer.incrementAndGet()
            DiagLogger.ble("BLE rx peer",
                "rssi" to result.rssi.toString(),
                "size" to manufacturerData.size.toString())
            cachedRssiMap[result.device.address] = result.rssi

            val nodeIdHex = manufacturerData.copyOfRange(NODE_ID_OFFSET, NODE_ID_OFFSET + NODE_ID_LEN)
                .joinToString("") { "%02x".format(it) }
            nodeIdToMac[nodeIdHex] = result.device.address

            radioService?.onPacketReceived(manufacturerData, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            DiagLogger.ble("BLE scan FAILED", "code" to errorCode.toString())
        }
    }

    override fun startBleAdvertising(adData: ByteArray, intervalMs: Int) {
        if (!hasAdvertisePermission()) {
            DiagLogger.ble("startBleAdvertising ABORT: missing BLUETOOTH_ADVERTISE permission")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            DiagLogger.ble("startBleAdvertising ABORT: BT disabled")
            return
        }
        if (bleAdvertiser == null) {
            DiagLogger.ble("startBleAdvertising ABORT: advertiser is null")
            return
        }
        if (isAdvertising.get()) return

        pendingAdvertiseData = adData
        startLegacyAdvertising(adData)
    }

    private fun startLegacyAdvertising(adData: ByteArray) {
        // 26 bytes header exactly fits BLE legacy limit (27 is too large)
        val truncated = if (adData.size > 26) adData.copyOf(26) else adData
        DiagLogger.ble("Legacy adv starting",
            "payload" to truncated.size.toString(),
            "dropped" to (adData.size - truncated.size).toString())

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(MANUFACTURER_ID, truncated)
            .build()

        try {
            bleAdvertiser?.startAdvertising(settings, data, legacyAdvertiseCallback)
        } catch (t: Throwable) {
            DiagLogger.ble("Legacy startAdvertising threw: ${t.message}")
            // Retry is handled by the periodic tick loop – no extra scheduling
        }
    }

    override fun stopBleAdvertising() {
        if (!isAdvertising.get()) return
        try {
            bleAdvertiser?.stopAdvertising(legacyAdvertiseCallback)
        } catch (t: Throwable) {
            DiagLogger.ble("stopAdvertising threw: ${t.message}")
        }
        isAdvertising.set(false)
    }

    private val legacyAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising.set(true)
            txStarts.incrementAndGet()
            DiagLogger.ble("Legacy adv STARTED")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising.set(false)
            DiagLogger.ble("Legacy adv FAILED status=$errorCode – will retry on next tick")
            // No manual retry here – the tick loop naturally retries every second
        }
    }

    private fun startGattServerIfPermitted() {
        if (!hasScanPermission()) {
            DiagLogger.ble("GATT server start deferred: BLUETOOTH_SCAN permission missing")
            return
        }
        gattServerStartAttempted = true
        startGattServer()
    }

    private fun startGattServer() {
        try {
            gattServer?.close()
        } catch (_: Throwable) {}
        try {
            gattServer = bluetoothManager.openGattServer(
                context.applicationContext,
                gattServerCallback
            )
            val service = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val writeChar = BluetoothGattCharacteristic(
                MESH_CHARACTERISTIC_WRITE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(writeChar)
            val notifyChar = BluetoothGattCharacteristic(
                MESH_CHARACTERISTIC_NOTIFY_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            service.addCharacteristic(notifyChar)
            gattServer?.addService(service)
            DiagLogger.ble("GATT server started")
        } catch (e: SecurityException) {
            DiagLogger.err("BLE", "GATT server start failed: permission missing", e)
        } catch (e: IllegalArgumentException) {
            DiagLogger.err("BLE", "GATT server start failed: ${e.message} — will retry when Bluetooth restarts", e)
        } catch (e: Exception) {
            DiagLogger.err("BLE", "GATT server start failed: ${e.message}", e)
        }
    }

    private fun hasScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun hasAdvertisePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == MESH_CHARACTERISTIC_WRITE_UUID) {
                DiagLogger.ble("GATT write rx", "addr" to device.address.takeLast(5), "len" to value.size.toString())
                radioService?.onPacketReceived(value, cachedRssiMap[device.address] ?: -100)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    override fun connectToPeer(peerMacAddress: String): Boolean {
        if (bleGattMap.containsKey(peerMacAddress)) return true
        val device = bluetoothAdapter?.getRemoteDevice(peerMacAddress) ?: return false
        DiagLogger.ble("Connecting GATT to $peerMacAddress")
        val gatt = device.connectGatt(context, false, gattClientCallback)
        bleGattMap[peerMacAddress] = gatt
        return true
    }

    override fun sendBlePacket(peerMacAddress: String, data: ByteArray): Boolean {
        val sender = bleSenderMap[peerMacAddress]
        if (sender == null) {
            DiagLogger.ble("sendBlePacket: no sender for $peerMacAddress, queuing after connect")
            return false
        }
        sender.send(data)
        return true
    }

    override fun disconnectPeer(peerMacAddress: String) {
        bleGattMap.remove(peerMacAddress)?.close()
        bleSenderMap.remove(peerMacAddress)?.close()
    }

    override fun sendBroadcastPacket(data: ByteArray) {
        bleSenderMap.keys.forEach { peer ->
            sendBlePacket(peer, data)
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (gatt == null) return
            val addr = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                DiagLogger.ble("GATT connected to $addr")
                gatt.discoverServices()
            } else {
                DiagLogger.ble("GATT disconnected $addr")
                bleSenderMap.remove(addr)?.close()
                bleGattMap.remove(addr)?.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (gatt == null || status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(MESH_SERVICE_UUID) ?: return
            val writeChar = service.getCharacteristic(MESH_CHARACTERISTIC_WRITE_UUID) ?: return
            val sender = BlePacketSender(gatt)
            sender.setCharacteristic(writeChar)
            bleSenderMap[gatt.device.address] = sender
            DiagLogger.ble("GATT service discovered, sender ready for ${gatt.device.address}")
            val pending = radioService?.dequeuePendingPackets(gatt.device.address) ?: emptyList()
            pending.forEach { sender.send(it) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (gatt == null) return
            val sender = bleSenderMap[gatt.device.address] ?: return
            sender.onWriteComplete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    fun getMacForNodeId(nodeIdHex: String): String? = nodeIdToMac[nodeIdHex]

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
        heartbeatHandler.postDelayed(hea