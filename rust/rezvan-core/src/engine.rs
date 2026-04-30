use crate::action::Action;
use crate::power::{PowerState, compute_state};
use crate::routing::RoutingTable;
use crate::session::SessionManager;
use rezvan_common::{DecryptedMessage, NodeId};
use rezvan_crypto::{CryptoProvider, IdentityKeypair};

pub struct MeshEngine {
    crypto: Box<dyn CryptoProvider>,
    routing: RoutingTable,
    sessions: SessionManager,
    power_state: PowerState,
    user_override: Option<PowerState>,
    battery_level: u8,
    is_charging: bool,
    node_density: f32,
    ogm_sequence: u32,
    adv_sequence: u16,
    node_id: NodeId,
}

// Helper trait to enable cloning of Box<dyn CryptoProvider>
trait CloneableCrypto: CryptoProvider {
    fn clone_box(&self) -> Box<dyn CryptoProvider>;
}

impl<T: CryptoProvider + Clone + 'static> CloneableCrypto for T {
    fn clone_box(&self) -> Box<dyn CryptoProvider> {
        Box::new(self.clone())
    }
}

impl Clone for Box<dyn CryptoProvider> {
    fn clone(&self) -> Box<dyn CryptoProvider> {
        (**self).clone_box()
    }
}

impl MeshEngine {
    pub fn new(seed: &[u8; 32], crypto: Box<dyn CryptoProvider>) -> Self {
        let identity = crypto.generate_identity(seed);
        let node_id = rezvan_common::compute_node_id(&identity.public_ed25519);

        Self {
            routing: RoutingTable::new(node_id),
            sessions: SessionManager::new(crypto.clone(), identity),
            power_state: PowerState::Active,
            user_override: None,
            battery_level: 100,
            is_charging: false,
            node_density: 0.0,
            ogm_sequence: 0,
            adv_sequence: 0,
            node_id,
            crypto,
        }
    }

    pub fn tick(&mut self) -> Vec<Action> {
        let mut actions = Vec::new();

        // Generate OGM advertisement if enough time has elapsed (every 5 seconds)
        self.ogm_sequence = self.ogm_sequence.wrapping_add(1);
        actions.push(Action::SendBleAdvertisement {
            data: self.build_advertisement(),
        });

        actions
    }

    pub fn process_incoming(
        &mut self,
        packet: &[u8],
        rssi: i32,
        timestamp: u64,
    ) -> (Option<DecryptedMessage>, Vec<Action>) {
        if packet.len() < 12 {
            return (None, Vec::new());
        }

        let header = match rezvan_common::MeshPacketHeader::deserialize(packet) {
            Some(h) => h,
            None => return (None, Vec::new()),
        };

        match header.packet_type {
            // OGM
            0x01 => {
                let _ = self.routing.process_ogm(packet, rssi);
                (None, Vec::new())
            }
            // Data (unicast or broadcast)
            0x02 => {
                // Let the session manager attempt decryption
                if let Some(payload) = packet.get(12..) {
                    if let Ok(plain) = self.sessions.decrypt(&header.originator, payload) {
                        // Construct a DecryptedMessage (simplified)
                        let msg = DecryptedMessage {
                            conversation_id: [0u8; 16],
                            sender_id: header.originator,
                            timestamp,
                            message_type: 0,
                            content: plain,
                        };
                        (Some(msg), Vec::new())
                    } else {
                        (None, Vec::new())
                    }
                } else {
                    (None, Vec::new())
                }
            }
            // X3DH handshake
            0x04 => {
                if let Some(payload) = packet.get(12..) {
                    let _ = self.sessions.process_inbound_handshake(&header.originator, payload);
                }
                (None, Vec::new())
            }
            _ => (None, Vec::new()),
        }
    }

    pub fn send_message(
        &mut self,
        recipient: &NodeId,
        plaintext: &[u8],
        msg_type: u8,
    ) -> Vec<Action> {
        let mut actions = Vec::new();

        let encrypted = match self.sessions.encrypt(recipient, plaintext) {
            Ok(cipher) => cipher,
            Err(_) => {
                // Session missing — attempt to establish one via X3DH
                // For now, just return an empty action list
                return actions;
            }
        };

        // Build a minimal data packet header
        let header = rezvan_common::MeshPacketHeader {
            version: 0x01,
            packet_type: 0x02,
            ttl: 10,
            originator: self.node_id,
            sequence: self.ogm_sequence,
            hop_count: 0,
            next_hop: *recipient,
            payload_len: encrypted.len() as u16,
        };

        let mut packet = header.serialize();
        packet.extend_from_slice(&encrypted);

        actions.push(Action::SendBlePacket {
            mac: [0xFFu8; 6], // broadcast MAC placeholder
            data: packet,
        });

        actions
    }

    pub fn update_battery(&mut self, level: u8, charging: bool) {
        self.battery_level = level;
        self.is_charging = charging;
        self.power_state = compute_state(level, charging, self.node_density, self.user_override);
    }

    pub fn get_power_state(&self) -> PowerState {
        self.power_state
    }

    fn build_advertisement(&self) -> Vec<u8> {
        let mut adv = vec![0u8; 31];
        adv[0] = 0x52;
        adv[1] = 0x56;
        adv[2..10].copy_from_slice(&self.node_id);
        // Flags: relay + wifi capable (4 + 8)
        adv[10] = 0x04 | 0x08;
        adv[11] = self.battery_level;
        let seq = (self.adv_sequence as u16).to_le_bytes();
        adv[12..14].copy_from_slice(&seq);
        // Channel mask: zero (no active channels for now)
        // Reserved: 2 bytes zero
        // Padding: 11 bytes zero
        adv
    }
}