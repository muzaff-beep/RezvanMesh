// rezvan-core/src/engine.rs

use crate::action::Action;
use crate::power::{PowerState, compute_state};
use crate::routing::RoutingTable;
use crate::session::SessionManager;
use rezvan_common::{DecryptedMessage, NodeId};
use rezvan_crypto::CryptoProvider;

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
    adv_sequence: u32,
    node_id: NodeId,
}

impl MeshEngine {
    pub fn new(seed: &[u8; 32], crypto: Box<dyn CryptoProvider>) -> Self {
        let identity = crypto.generate_identity(seed);
        let node_id = rezvan_common::compute_node_id(&identity.public_ed25519);

        let mut engine = Self {
            crypto,
            routing: RoutingTable::new(node_id),
            sessions: SessionManager::new(Box::new(crate::crypto::MockCryptoProvider), identity),
            power_state: PowerState::Active,
            user_override: None,
            battery_level: 100,
            is_charging: false,
            node_density: 0.0,
            ogm_sequence: 0,
            adv_sequence: 0,
            node_id,
        };

        engine.sessions = SessionManager::new(engine.crypto.clone_box(), engine.sessions.identity());
        engine
    }

    pub fn tick(&mut self) -> Vec<Action> {
        let mut actions = Vec::new();
        self.adv_sequence = self.adv_sequence.wrapping_add(1);
        actions.push(Action::SendBleAdvertisement {
            data: self.build_advertisement(),
        });
        actions
    }

    pub fn process_incoming(
        &mut self,
        raw_packet: &[u8],
        rssi: i32,
        timestamp: u64,
    ) -> (Option<DecryptedMessage>, Vec<Action>) {
        let packet = raw_packet;

        let header = match rezvan_common::MeshPacketHeader::deserialize(packet) {
            Some(h) => h,
            None => {
                let diag = Action::DiagLog {
                    tag: "RUST".into(),
                    level: 3,
                    message: format!(
                        "deserialize FAILED len={} first_bytes={:02x?}",
                        packet.len(),
                        &packet.get(..8.min(packet.len())).unwrap_or(packet)
                    ),
                };
                return (None, vec![diag]);
            }
        };

        if header.originator == self.node_id {
            let diag = Action::DiagLog {
                tag: "RUST".into(),
                level: 1,
                message: format!(
                    "LOOPBACK ok seq={} type={:#04x} rssi={}",
                    header.sequence, header.packet_type, rssi
                ),
            };
            return (None, vec![diag]);
        }

        match header.packet_type {
            0x01 => {
                let _ = self.routing.process_ogm(packet, rssi);
                (None, Vec::new())
            }
            0x02 => {
                if let Some(payload) = packet.get(26..) {
                    if let Ok(plain) = self.sessions.decrypt(&header.originator, payload) {
                        let msg = DecryptedMessage {
                            conversation_id: [0u8; 16],
                            sender_id: header.originator,
                            timestamp,
                            message_type: 0,
                            content: plain,
                        };
                        return (Some(msg), Vec::new());
                    }
                }
                (None, Vec::new())
            }
            0x03 => {
                // Emergency broadcast – decrypt and return with high priority marker
                if let Some(payload) = packet.get(26..) {
                    let content = if let Ok(plain) = self.sessions.decrypt(&header.originator, payload) {
                        plain
                    } else {
                        payload.to_vec()   // broadcast may be unencrypted
                    };
                    let msg = DecryptedMessage {
                        conversation_id: [0u8; 16],
                        sender_id: header.originator,
                        timestamp,
                        message_type: 3,   // emergency
                        content,
                    };
                    return (Some(msg), Vec::new());
                }
                (None, Vec::new())
            }
            0x04 => {
                if let Some(payload) = packet.get(26..) {
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
            Err(_) => return actions,
        };

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
            mac: [0xFFu8; 6],   // placeholder; will be resolved by recipient's NodeId->MAC mapping
            data: packet,
        });

        actions
    }

    pub fn send_broadcast(&mut self, message: &[u8]) -> Vec<Action> {
        let mut actions = Vec::new();

        // Emergency broadcast packets are NOT encrypted (public safety).
        let header = rezvan_common::MeshPacketHeader {
            version: 0x01,
            packet_type: 0x03,
            ttl: 15,
            originator: self.node_id,
            sequence: self.ogm_sequence,
            hop_count: 0,
            next_hop: [0u8; 8],
            payload_len: message.len() as u16,
        };

        let mut packet = header.serialize();
        packet.extend_from_slice(message);

        // Broadcast via GATT to all known peers (MACs to be filled by radio layer)
        actions.push(Action::SendBlePacket {
            mac: [0xFFu8; 6], // special marker: radio layer will iterate over connected peers
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
        let header = rezvan_common::MeshPacketHeader {
            version: 0x01,
            packet_type: 0x01,
            ttl: 1,
            originator: self.node_id,
            sequence: self.adv_sequence,
            hop_count: self.battery_level,
            next_hop: [0u8; 8],
            payload_len: 0,
        };
        header.serialize()
    }
}