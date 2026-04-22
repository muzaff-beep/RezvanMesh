//! Shared data structures and constants for Rezvan Mesh.
//! Used by both `rezvan-core` and `rezvan-crypto` crates.

#![no_std]

extern crate alloc;

use alloc::vec::Vec;

// ========================= CONSTANTS =========================

/// Protocol version
pub const PROTOCOL_VERSION: u8 = 0x01;

/// Node ID length in bytes (first 8 bytes of SHA-256 of Ed25519 public key)
pub const NODE_ID_LEN: usize = 8;

/// Maximum packet size (including header, payload, and signature)
pub const MAX_PACKET_SIZE: usize = 1024;

/// BLE advertisement payload size (fixed)
pub const BLE_ADV_SIZE: usize = 31;

/// Default OGM TTL
pub const DEFAULT_OGM_TTL: u8 = 10;

/// OGM broadcast interval (milliseconds)
pub const OGM_INTERVAL_MS: u64 = 5000;

// ========================= PACKET TYPES =========================

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum PacketType {
    OGM = 0x01,
    Data = 0x02,
    Broadcast = 0x03,
    X3DH = 0x04,
}

impl From<u8> for PacketType {
    fn from(v: u8) -> Self {
        match v {
            0x01 => PacketType::OGM,
            0x02 => PacketType::Data,
            0x03 => PacketType::Broadcast,
            0x04 => PacketType::X3DH,
            _ => PacketType::Data, // fallback
        }
    }
}

// ========================= MESSAGE TYPES =========================

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum MessageType {
    Text = 0,
    Voice = 1,
    FileMetadata = 2,
    FileChunk = 3,
}

impl From<u8> for MessageType {
    fn from(v: u8) -> Self {
        match v {
            0 => MessageType::Text,
            1 => MessageType::Voice,
            2 => MessageType::FileMetadata,
            3 => MessageType::FileChunk,
            _ => MessageType::Text,
        }
    }
}

// ========================= POWER STATE =========================

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum PowerState {
    Emergency = 0,
    Active = 1,
    Balanced = 2,
    PowerSaver = 3,
    Minimal = 4,
    Hibernation = 5,
    Dead = 6,
}

impl PowerState {
    pub fn as_str(&self) -> &'static str {
        match self {
            PowerState::Emergency => "EMERGENCY",
            PowerState::Active => "ACTIVE",
            PowerState::Balanced => "BALANCED",
            PowerState::PowerSaver => "POWER_SAVER",
            PowerState::Minimal => "MINIMAL",
            PowerState::Hibernation => "HIBERNATION",
            PowerState::Dead => "DEAD",
        }
    }
}

// ========================= MESH PACKET HEADER =========================

/// On-wire packet header (12 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct MeshPacketHeader {
    pub version: u8,
    pub packet_type: u8,
    pub ttl: u8,
    pub originator: [u8; NODE_ID_LEN],
    pub sequence: u32,
    pub hop_count: u8,
    pub next_hop: [u8; NODE_ID_LEN],
    pub payload_len: u16,
}

impl MeshPacketHeader {
    pub const SIZE: usize = 1 + 1 + 1 + 8 + 4 + 1 + 8 + 2; // 26 bytes? Let's recalc: 1+1+1=3, +8=11, +4=15, +1=16, +8=24, +2=26. Wait, need packed representation.
    // Actually: version(1) + type(1) + ttl(1) + originator(8) + seq(4) + hop(1) + next_hop(8) + payload_len(2) = 26 bytes.
    // But we also have alignment padding. Use #[repr(C, packed)] to avoid padding.
}

/// OGM Payload (variable size due to neighbors, max 50 bytes)
#[derive(Debug, Clone)]
pub struct OGMPayload {
    pub timestamp: u64,
    pub link_quality: u8,
    pub path_metric: u32,
    pub neighbor_count: u8,
    pub neighbors: [NeighborInfo; 9],
}

#[derive(Debug, Clone, Copy)]
pub struct NeighborInfo {
    pub node_id_prefix: [u8; 3],
    pub link_quality: u8,
}

impl Default for NeighborInfo {
    fn default() -> Self {
        Self {
            node_id_prefix: [0; 3],
            link_quality: 0,
        }
    }
}

// ========================= ACTION TYPES (for JNI) =========================

#[derive(Debug, Clone)]
pub enum Action {
    SendBleAdvertisement { data: [u8; BLE_ADV_SIZE] },
    SendWifiPacket { ip: u32, port: u16, data: Vec<u8> },
    SendBlePacket { mac: [u8; 6], data: Vec<u8> },
    UpdateScanInterval { interval_ms: u32, window_ms: u32 },
    NotifyUi { decrypted_message: DecryptedMessage },
}

#[derive(Debug, Clone)]
pub struct DecryptedMessage {
    pub conversation_id: [u8; 16],
    pub sender_id: [u8; NODE_ID_LEN],
    pub timestamp: u64,
    pub message_type: u8,
    pub content: Vec<u8>,
}

// ========================= HELPER FUNCTIONS =========================

/// Computes Node ID from Ed25519 public key.
/// Node ID = first 8 bytes of SHA-256(public_key)
pub fn compute_node_id(public_key: &[u8; 32]) -> [u8; NODE_ID_LEN] {
    use sha2::{Digest, Sha256};
    let mut hasher = Sha256::new();
    hasher.update(public_key);
    let hash = hasher.finalize();
    let mut node_id = [0u8; NODE_ID_LEN];
    node_id.copy_from_slice(&hash[..NODE_ID_LEN]);
    node_id
}

/// Maps RSSI (dBm) to link quality (0-255)
pub fn rssi_to_link_quality(rssi: i32) -> u8 {
    if rssi > -65 {
        255
    } else if rssi < -85 {
        0
    } else {
        (((rssi + 85) * 255) / 20) as u8
    }
          }
