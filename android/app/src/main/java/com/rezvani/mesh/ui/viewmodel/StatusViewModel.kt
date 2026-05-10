fun injectMockPeer() {
    viewModelScope.launch {
        val mockId = ByteArray(16) { (0xA0 + mockCounter).toByte() }
        mockCounter++
        // Build a minimal OGM packet (adjust to match the Rust engine's expected format)
        val ogm = ByteArray(32).apply {
            // First 16 bytes: node ID
            System.arraycopy(mockId, 0, this, 0, 16)
            this[16] = 0x01   // packet type: OGM
            this[17] = 0x0A   // TTL
            // ... fill in the rest as needed ...
        }
        // Inject through the mesh service – we need a method for this.
        // For now, we simulate by posting directly to onPacketReceived.
        // In a real implementation, MeshServiceConnection needs a public inject method.
        try {
            // Bypass: directly call the native engine
            val ptr = MeshServiceConnection.meshCorePtr.value ?: return@launch
            MeshCore.nativeProcessIncoming(ptr, ogm, -50, System.currentTimeMillis() * 1000)
            DiagLogger.log("Mock peer injected, RSSI=-50")
        } catch (e: Exception) {
            DiagLogger.log("Mock inject failed: ${e.message}")
        }
    }
}
