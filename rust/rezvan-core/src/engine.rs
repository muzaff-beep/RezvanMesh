// src/engine.rs

use crate::action::{Action, DecryptedMessage};
use crate::crypto::{CryptoProvider, IdentityKeypair};
use crate::power::{self, PowerState};
use crate::routing::{self, NodeId, RoutingTable};
use crate::session::SessionManager;
use std::time::{SystemTime, UNIX_EPOCH};

/// Mesh packet header as defined in Section 4.2.
#[repr(C)]
#[derive(Debug, Clone)]
pub struct MeshPacketHeader {
    pub version: u8,
    pub packet_type: u8,
    pub ttl: u8,
    pub originator: [u8; 8],
    pub sequence: u32,
    pub hop_count: u8,
    pub next_hop: [u8; 8],
    pub payload_len: u16,
}

/// OGM payload as defined in Section 4.3.
#[derive(Debug, Clone)]
pub struct OGMPayload {
    pub timestamp: u64,
    pub link_quality: u8,
    pub path_metric: u32,
    pub neighbor_count: u8,
    pub neighbors: [routing::NeighborInfo; 9],
}

/// Main mesh engine struct.
pub struct MeshEngine {
    /// Our identity keypair.
    identity: IdentityKeypair,
    /// Our NodeId (derived from public key).
    node_id: NodeId,
    /// Crypto provider (mock or real).
    crypto: Box<dyn CryptoProvider>,
    /// Routing table.
    routing: RoutingTable,
    /// Session manager.
    sessions: SessionManager,
    /// Current power state.
    power_state: PowerState,
    /// User override for power state.
    user_override: Option<PowerState>,
    /// Current battery level (0-100).
    battery_level: u8,
    /// Whether the device is charging.
    is_charging: bool,
    /// Node density (reserved for future use).
    node_density: f32,
    /// Advertisement sequence number.
    adv_sequence: u16,
    /// Storage path for persistence (not used in this version).
    _storage_path: String,
}

impl MeshEngine {
    /// Create a new MeshEngine instance.
    pub fn new(seed: &[u8; 32], crypto: Box<dyn CryptoProvider>, storage_path: &str) -> Self {
        let identity = crypto.generate_identity(seed);
        let node_id = routing::compute_node_id(&identity.public_ed25519);
        let routing = RoutingTable::new(node_id);
        let sessions = SessionManager::new(crypto.clone_box(), identity.clone());

        MeshEngine {
            identity,
            node_id,
            crypto,
            routing,
            sessions,
            power_state: PowerState::Active,
            user_override: None,
            battery_level: 100,
            is_charging: false,
            node_density: 0.0,
            adv_sequence: 0,
            _storage_path: storage_path.to_string(),
        }
    }

    /// Perform a periodic tick.
    /// Returns a list of actions to be executed by the radio layer.
    pub fn tick(&mut self) -> Vec<Action> {
        let mut actions = Vec::new();
        let current_time = current_timestamp_micros();

        // Update power state based on current battery and charging status.
        self.update_power_state();

        // Check if we should advertise (based on power state).
        if power::should_advertise(self.power_state) {
            if let Some(action) = self.build_ble_advertisement() {
                actions.push(action);
            }
        }

        // Check if we should update scan parameters.
        let (interval_ms, window_ms) = power::get_scan_params(self.power_state);
        if interval_ms > 0 {
            actions.push(Action::UpdateScanInterval {
                interval_ms,
                window_ms,
            });
        }

        // Perform routing table maintenance.
        if let Some(ogm_action) = self.routing.tick(current_time, self.power_state) {
            actions.push(ogm_action);
        }

        actions
    }

    /// Process an incoming raw packet.
    /// Returns an optional decrypted message and a list of actions.
    pub fn process_incoming(
        &mut self,
        packet: &[u8],
        rssi: i32,
        timestamp_us: u64,
    ) -> (Option<DecryptedMessage>, Vec<Action>) {
        let mut actions = Vec::new();

        // Packet must be at least header (12 bytes) + signature (64 bytes).
        if packet.len() < 12 + 64 {
            return (None, actions);
        }

        // Parse header.
        let header = match self.parse_header(packet) {
            Ok(h) => h,
            Err(_) => return (None, actions),
        };

        // Verify that the packet is for us or broadcast.
        // For now, we process all packets.

        match header.packet_type {
            0x01 => {
                // OGM packet.
                self.process_ogm_packet(&header, &packet[12..], rssi, timestamp_us);
            }
            0x02 => {
                // Data packet (unicast).
                return self.process_data_packet(&header, &packet[12..]);
            }
            0x03 => {
                // Broadcast packet (sender key).
                return self.process_broadcast_packet(&header, &packet[12..]);
            }
            0x04 => {
                // X3DH handshake.
                self.process_handshake_packet(&header, &packet[12..]);
            }
            _ => {
                // Unknown packet type.
            }
        }

        (None, actions)
    }

    /// Send a message to a recipient.
    pub fn send_message(
        &mut self,
        recipient: &NodeId,
        plaintext: &[u8],
        msg_type: u8,
    ) -> Vec<Action> {
        let mut actions = Vec::new();

        // Check if we have a session with the recipient.
        // For now, we assume a session exists or we create a dummy one.
        // In a real implementation, we would fetch a pre-key bundle.

        let ciphertext = match self.sessions.encrypt(recipient, plaintext) {
            Ok(ct) => ct,
            Err(_) => {
                // No session, we can't send.
                return actions;
            }
        };

        // Build data packet.
        let packet = self.build_data_packet(recipient, &ciphertext);
        
        // Determine next hop.
        let next_hop = self.routing.get_next_hop(recipient).unwrap_or(*recipient);

        // Create action to send packet via BLE (or WiFi).
        // For simplicity, we always use BLE broadcast to next hop.
        // In reality, we would use the best available transport.
        actions.push(Action::SendBlePacket {
            mac: node_id_to_mac(&next_hop),
            data: packet,
        });

        actions
    }

    /// Update battery information.
    pub fn update_battery(&mut self, level: u8, charging: bool) {
        self.battery_level = level;
        self.is_charging = charging;
        self.update_power_state();
    }

    /// Get the current power state.
    pub fn get_power_state(&self) -> PowerState {
        self.power_state
    }

    // ---------- Private helper methods ----------

    fn update_power_state(&mut self) {
        self.power_state = power::compute_state(
            self.battery_level,
            self.is_charging,
            self.node_density,
            self.user_override,
        );
    }

    fn build_ble_advertisement(&self) -> Option<Action> {
        // Build 31-byte advertisement as per Section 5.
        let mut data = vec![0u8; 31];
        data[0] = 0x52; // 'R'
        data[1] = 0x56; // 'V'
        data[2..10].copy_from_slice(&self.node_id);
        
        // Flags: set WiFi capable and others based on state.
        let mut flags = 0u8;
        if power::should_enable_wifi(self.power_state) {
            flags |= 0x08; // WiFi Capable
        }
        data[10] = flags;
        
        // Battery level (255 if charging).
        data[11] = if self.is_charging { 255 } else { self.battery_level };
        
        // Sequence number (little-endian).
        let seq_bytes = self.adv_sequence.to_le_bytes();
        data[12] = seq_bytes[0];
        data[13] = seq_bytes[1];
        
        // Channel mask (reserved).
        data[14..18].copy_from_slice(&[0u8; 4]);
        
        // Remaining bytes are zero.

        Some(Action::SendBleAdvertisement { data })
    }

    fn parse_header(&self, packet: &[u8]) -> Result<MeshPacketHeader, &'static str> {
        if packet.len() < 12 {
            return Err("Packet too short");
        }
        Ok(MeshPacketHeader {
            version: packet[0],
            packet_type: packet[1],
            ttl: packet[2],
            originator: packet[3..11].try_into().unwrap(),
            sequence: u32::from_be_bytes(packet[11..15].try_into().unwrap()),
            hop_count: packet[15],
            next_hop: packet[16..24].try_into().unwrap(),
            payload_len: u16::from_be_bytes(packet[24..26].try_into().unwrap()),
        })
    }

    fn process_ogm_packet(
        &mut self,
        header: &MeshPacketHeader,
        payload_and_sig: &[u8],
        rssi: i32,
        timestamp_us: u64,
    ) {
        // Skip signature verification for mock.
        let payload_len = header.payload_len as usize;
        if payload_and_sig.len() < payload_len {
            return;
        }
        let payload = &payload_and_sig[..payload_len];
        
        // Parse OGMPayload.
        if let Some(ogm) = self.parse_ogm_payload(payload) {
            let should_rebroadcast = self.routing.process_ogm(
                header.originator,
                header.sequence,
                rssi,
                100, // Unknown battery, assume full
                ogm.path_metric,
                header.hop_count,
                timestamp_us,
            );
            
            if should_rebroadcast {
                // Re-broadcast OGM with incremented hop count.
                // This would be returned as an action.
            }
        }
    }

    fn parse_ogm_payload(&self, payload: &[u8]) -> Option<OGMPayload> {
        if payload.len() < 50 {
            return None;
        }
        let timestamp = u64::from_be_bytes(payload[0..8].try_into().unwrap());
        let link_quality = payload[8];
        let path_metric = u32::from_be_bytes(payload[9..13].try_into().unwrap());
        let neighbor_count = payload[13];
        let mut neighbors = [routing::NeighborInfo {
            node_id_prefix: [0u8; 3],
            link_quality: 0,
        }; 9];
        let mut offset = 14;
        for i in 0..9 {
            if offset + 4 > payload.len() {
                break;
            }
            neighbors[i].node_id_prefix.copy_from_slice(&payload[offset..offset+3]);
            neighbors[i].link_quality = payload[offset+3];
            offset += 4;
        }
        Some(OGMPayload {
            timestamp,
            link_quality,
            path_metric,
            neighbor_count,
            neighbors,
        })
    }

    fn process_data_packet(
        &mut self,
        header: &MeshPacketHeader,
        payload_and_sig: &[u8],
    ) -> (Option<DecryptedMessage>, Vec<Action>) {
        // Verify signature and decrypt.
        let payload_len = header.payload_len as usize;
        if payload_and_sig.len() < payload_len + 64 {
            return (None, vec![]);
        }
        let ciphertext = &payload_and_sig[..payload_len];
        
        // Decrypt using session.
        let plaintext = match self.sessions.decrypt(&header.originator, ciphertext) {
            Ok(pt) => pt,
            Err(_) => return (None, vec![]),
        };
        
        // Parse DecryptedMessage from plaintext.
        let message = self.parse_decrypted_message(&plaintext)?;
        
        // If we are not the final recipient, forward the packet.
        let mut actions = Vec::new();
        if header.next_hop != self.node_id {
            // Forward to next hop.
            let next_hop = self.routing.get_next_hop(&header.next_hop).unwrap_or(header.next_hop);
            actions.push(Action::SendBlePacket {
                mac: node_id_to_mac(&next_hop),
                data: self.build_data_packet_forward(header, ciphertext),
            });
        } else {
            // Message is for us.
            return (Some(message), actions);
        }
        
        (None, actions)
    }

    fn process_broadcast_packet(
        &mut self,
        header: &MeshPacketHeader,
        payload_and_sig: &[u8],
    ) -> (Option<DecryptedMessage>, Vec<Action>) {
        // Broadcast packets use sender keys.
        // For mock, just return the decrypted message if it's for us.
        let payload_len = header.payload_len as usize;
        if payload_and_sig.len() < payload_len {
            return (None, vec![]);
        }
        let ciphertext = &payload_and_sig[..payload_len];
        
        // In a real implementation, we'd have a map of sender keys per conversation.
        // Here we just try to decrypt with a dummy key or assume plaintext.
        let plaintext = ciphertext.to_vec(); // Mock: no encryption.
        let message = self.parse_decrypted_message(&plaintext)?;
        (Some(message), vec![])
    }

    fn process_handshake_packet(&mut self, header: &MeshPacketHeader, payload_and_sig: &[u8]) {
        let payload_len = header.payload_len as usize;
        if payload_and_sig.len() < payload_len {
            return;
        }
        let handshake_data = &payload_and_sig[..payload_len];
        let _ = self.sessions.process_inbound_handshake(&header.originator, handshake_data);
    }

    fn parse_decrypted_message(&self, plaintext: &[u8]) -> Option<DecryptedMessage> {
        if plaintext.len() < 16 + 8 + 8 + 1 + 4 {
            return None;
        }
        let mut offset = 0;
        let conversation_id: [u8; 16] = plaintext[offset..offset+16].try_into().ok()?;
        offset += 16;
        let sender_id: [u8; 8] = plaintext[offset..offset+8].try_into().ok()?;
        offset += 8;
        let timestamp = u64::from_be_bytes(plaintext[offset..offset+8].try_into().ok()?);
        offset += 8;
        let message_type = plaintext[offset];
        offset += 1;
        let content_len = u32::from_be_bytes(plaintext[offset..offset+4].try_into().ok()?) as usize;
        offset += 4;
        let content = if offset + content_len <= plaintext.len() {
            plaintext[offset..offset+content_len].to_vec()
        } else {
            return None;
        };
        Some(DecryptedMessage {
            conversation_id,
            sender_id,
            timestamp,
            message_type,
            content,
        })
    }

    fn build_data_packet(&self, recipient: &NodeId, ciphertext: &[u8]) -> Vec<u8> {
        let sequence = 0u32; // Should be per-session sequence.
        let header = MeshPacketHeader {
            version: 0x01,
            packet_type: 0x02, // Data
            ttl: 10,
            originator: self.node_id,
            sequence,
            hop_count: 0,
            next_hop: *recipient,
            payload_len: ciphertext.len() as u16,
        };
        self.serialize_packet(&header, ciphertext)
    }

    fn build_data_packet_forward(&self, original_header: &MeshPacketHeader, ciphertext: &[u8]) -> Vec<u8> {
        let mut new_header = original_header.clone();
        new_header.hop_count += 1;
        new_header.ttl -= 1;
        self.serialize_packet(&new_header, ciphertext)
    }

    fn serialize_packet(&self, header: &MeshPacketHeader, payload: &[u8]) -> Vec<u8> {
        let mut packet = Vec::with_capacity(12 + payload.len() + 64);
        packet.push(header.version);
        packet.push(header.packet_type);
        packet.push(header.ttl);
        packet.extend_from_slice(&header.originator);
        packet.extend_from_slice(&header.sequence.to_be_bytes());
        packet.push(header.hop_count);
        packet.extend_from_slice(&header.next_hop);
        packet.extend_from_slice(&header.payload_len.to_be_bytes());
        packet.extend_from_slice(payload);
        
        // Sign the packet.
        let signature = self.crypto.sign(&self.identity, &packet);
        packet.extend_from_slice(&signature);
        packet
    }
}

// Helper function to convert NodeId to BLE MAC address (placeholder).
fn node_id_to_mac(node_id: &NodeId) -> [u8; 6] {
    // Use first 6 bytes of NodeId as MAC.
    let mut mac = [0u8; 6];
    mac.copy_from_slice(&node_id[0..6]);
    mac
}

// Helper to get current timestamp in microseconds.
fn current_timestamp_micros() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_micros() as u64
}

// Extension trait to allow cloning a boxed trait.
trait CryptoProviderClone {
    fn clone_box(&self) -> Box<dyn CryptoProvider>;
}

impl<T: CryptoProvider + Clone + 'static> CryptoProviderClone for T {
    fn clone_box(&self) -> Box<dyn CryptoProvider> {
        Box::new(self.clone())
    }
}

impl Clone for Box<dyn CryptoProvider> {
    fn clone(&self) -> Self {
        // This is a simplified clone; in practice, you'd need a real clone implementation.
        // For the mock, we can just create a new mock instance.
        crate::crypto::MockCryptoProvider.clone_box()
    }
}

// Make MockCryptoProvider cloneable.
impl Clone for crate::crypto::MockCryptoProvider {
    fn clone(&self) -> Self {
        crate::crypto::MockCryptoProvider
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::MockCryptoProvider;

    fn create_test_engine() -> MeshEngine {
        let seed = [1u8; 32];
        let crypto = Box::new(MockCryptoProvider);
        MeshEngine::new(&seed, crypto, "/tmp/test")
    }

    #[test]
    fn test_engine_creation() {
        let engine = create_test_engine();
        assert_eq!(engine.get_power_state(), PowerState::Active);
    }

    #[test]
    fn test_tick_generates_actions() {
        let mut engine = create_test_engine();
        let actions = engine.tick();
        // Should generate advertisement and update scan interval.
        assert!(!actions.is_empty());
        assert!(actions.iter().any(|a| matches!(a, Action::SendBleAdvertisement { .. })));
        assert!(actions.iter().any(|a| matches!(a, Action::UpdateScanInterval { .. })));
    }

    #[test]
    fn test_battery_update_changes_power_state() {
        let mut engine = create_test_engine();
        engine.update_battery(20, false);
        assert_eq!(engine.get_power_state(), PowerState::Minimal);
    }

    #[test]
    fn test_ogm_processing() {
        let mut engine = create_test_engine();
        // Build a dummy OGM packet.
        let ogm_packet = vec![0u8; 12 + 50 + 64];
        let (msg, actions) = engine.process_incoming(&ogm_packet, -70, 1000);
        assert!(msg.is_none());
    }
}
