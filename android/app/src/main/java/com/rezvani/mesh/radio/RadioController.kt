package com.rezvani.mesh.radio

/**
 * Interface for hardware radio operations in the Rezvan Mesh network.
 */
interface RadioController {
    // BLE scanning
    fun startBleScan(intervalMs: Long, windowMs: Long)
    fun stopBleScan()

    // BLE advertising
    fun startBleAdvertising(adData: ByteArray, intervalMs: Int)
    fun stopBleAdvertising()

    // BLE connections (for unicast data)
    fun connectToPeer(peerMacAddress: String): Boolean
    fun sendBlePacket(peerMacAddress: String, data: ByteArray): Boolean
    fun disconnectPeer(peerMacAddress: String)

    // WiFi Direct
    fun isWifiDirectSupported(): Boolean
    fun startWifiDirectDiscovery()
    fun stopWifiDirectDiscovery()
    fun connectWifiDirect(peerMacAddress: String): Boolean
    fun sendWifiPacket(peerIpAddress: String, port: Int, data: ByteArray): Boolean
    fun disconnectWifiDirect(peerIpAddress: String)

    // RSSI monitoring
    fun getCurrentRssi(peerMacAddress: String): Int

    // Power control
    fun setBleTxPower(dbm: Int) // -20 to +10
    fun setWifiTxPower(dbm: Int)

    // Lifecycle
    fun onDestroy()
}
