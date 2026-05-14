// android/app/src/main/java/com/rezvani/mesh/radio/RadioController.kt

package com.rezvani.mesh.radio

interface RadioController {
    fun startBleScan(intervalMs: Long, windowMs: Long)
    fun stopBleScan()
    fun startBleAdvertising(adData: ByteArray, intervalMs: Int)
    fun stopBleAdvertising()
    fun connectToPeer(peerMacAddress: String): Boolean
    fun sendBlePacket(peerMacAddress: String, data: ByteArray): Boolean
    fun disconnectPeer(peerMacAddress: String)
    fun sendBroadcastPacket(data: ByteArray)
    fun isWifiDirectSupported(): Boolean
    fun startWifiDirectDiscovery()
    fun stopWifiDirectDiscovery()
    fun connectWifiDirect(peerMacAddress: String): Boolean
    fun sendWifiPacket(peerIpAddress: String, port: Int, data: ByteArray): Boolean
    fun disconnectWifiDirect(peerIpAddress: String)
    fun getCurrentRssi(peerMacAddress: String): Int
    fun setBleTxPower(dbm: Int)
    fun setWifiTxPower(dbm: Int)
    fun onDestroy()
}