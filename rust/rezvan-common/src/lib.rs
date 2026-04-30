use sha2::{Sha256, Digest};

// ---------------------------------------------------------------------------
// Node Identity
// ---------------------------------------------------------------------------

/// A node's unique identifier – the first 8 bytes of SHA-256(public_ed25519)
pub type NodeId = [u8; 8];

/// Compute the NodeId from an Ed25519 public key.
pub fn compute_node_id(public_key: &[u8; 32]) -> NodeId {
    let hash = Sha256::digest(public_key);
    let mut node_id: NodeId = [0u8; 8];
    node_id.copy_from_slice(&hash[0..8]);
    node_id
}

// ---------------------------------------------------------------------------
// Mesh Packet Header (12 bytes, followed by payload and Ed25519 signature)
// ---------------------------------------------------------------------------

#[repr(C)]
pub struct MeshPacketHeader {
    pub version: u8,           // 0x01
    pub packet_type: u8,       // 0x01=OGM, 0x02=Data, 0x03=Broadcast, 0x04=X3DH
    pub ttl: u8,
    pub originator: NodeId,
    pub sequence: u32,         // big-endian
    pub hop_count: u8,
    pub next_hop: NodeId,      // [0u8; 8] for broadcast
    pub payload_len: u16,      // big-endian
}

impl MeshPacketHeader {
    pub const SIZE: usize = 12;

    pub fn serialize(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(Self::SIZE);
        buf.push(self.version);
        buf.push(self.packet_type);
        buf.push(self.ttl);
        buf.extend_from_slice(&self.originator);
        buf.extend_from_slice(&self.sequence.to_be_bytes());
        buf.push(self.hop_count);
        buf.extend_from_slice(&self.next_hop);
        buf.extend_from_slice(&self.payload_len.to_be_bytes());
        buf
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < Self::SIZE {
            return None;
        }
        let version = data[0];
        let packet_type = data[1];
        let ttl = data[2];
        let mut originator: NodeId = [0u8; 8];
        originator.copy_from_slice(&data[3..11]);
        let mut seq_bytes = [0u8; 4];
        seq_bytes.copy_from_slice(&data[11..15]);
        let sequence = u32::from_be_bytes(seq_bytes);
        let hop_count = data[15];
        let mut next_hop: NodeId = [0u8; 8];
        next_hop.copy_from_slice(&data[16..24]);
        let mut len_bytes = [0u8; 2];
        len_bytes.copy_from_slice(&data[24..26]);
        let payload_len = u16::from_be_bytes(len_bytes);

        Some(Self {
            version,
            packet_type,
            ttl,
            originator,
            sequence,
            hop_count,
            next_hop,
            payload_len,
        })
    }
}

// ---------------------------------------------------------------------------
// OGM Payload (50 bytes)
// ---------------------------------------------------------------------------

pub struct OGMPayload {
    pub timestamp: u64,        // Unix microseconds, big-endian
    pub link_quality: u8,      // 0–255 from RSSI
    pub path_metric: u32,      // cumulative metric, big-endian
    pub neighbor_count: u8,
    pub neighbors: [NeighborInfo; 9],
}

impl OGMPayload {
    pub const SIZE: usize = 50;

    pub fn serialize(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(Self::SIZE);
        buf.extend_from_slice(&self.timestamp.to_be_bytes());
        buf.push(self.link_quality);
        buf.extend_from_slice(&self.path_metric.to_be_bytes());
        buf.push(self.neighbor_count);
        for neighbor in &self.neighbors {
            buf.extend_from_slice(&neighbor.serialize());
        }
        buf
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < Self::SIZE {
            return None;
        }
        let mut ts_bytes = [0u8; 8];
        ts_bytes.copy_from_slice(&data[0..8]);
        let timestamp = u64::from_be_bytes(ts_bytes);
        let link_quality = data[8];
        let mut metric_bytes = [0u8; 4];
        metric_bytes.copy_from_slice(&data[9..13]);
        let path_metric = u32::from_be_bytes(metric_bytes);
        let neighbor_count = data[13];
        let mut neighbors = [NeighborInfo::default(); 9];
        for i in 0..9 {
            let offset = 14 + i * 4;
            neighbors[i] = NeighborInfo::deserialize(&data[offset..offset + 4])?;
        }
        Some(Self {
            timestamp,
            link_quality,
            path_metric,
            neighbor_count,
            neighbors,
        })
    }
}

// ---------------------------------------------------------------------------
// Neighbour Info (4 bytes)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, Default)]
pub struct NeighborInfo {
    pub node_id_prefix: [u8; 3],  // first 3 bytes of neighbour's NodeId
    pub link_quality: u8,
}

impl NeighborInfo {
    pub fn serialize(&self) -> [u8; 4] {
        let mut buf = [0u8; 4];
        buf[0..3].copy_from_slice(&self.node_id_prefix);
        buf[3] = self.link_quality;
        buf
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < 4 {
            return None;
        }
        let mut node_id_prefix = [0u8; 3];
        node_id_prefix.copy_from_slice(&data[0..3]);
        let link_quality = data[3];
        Some(Self {
            node_id_prefix,
            link_quality,
        })
    }
}

// ---------------------------------------------------------------------------
// Data Message (unencrypted fields only – ciphertext carried separately)
// ---------------------------------------------------------------------------

pub struct DataMessage {
    pub conversation_id: [u8; 16],  // UUID v4
    pub message_number: u32,
    pub ratchet_key: [u8; 32],      // ephemeral public key for this ratchet step
    pub previous_chain_length: u32,
    pub ciphertext: Vec<u8>,
}

// ---------------------------------------------------------------------------
// Decrypted Message (passed from Rust → Kotlin UI)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct DecryptedMessage {
    pub conversation_id: [u8; 16],
    pub sender_id: NodeId,
    pub timestamp: u64,
    pub message_type: u8,
    pub content: Vec<u8>,
}

impl DecryptedMessage {
    /// Serialize for the `NotifyUi` action payload.
    /// Layout:
    ///   [conversation_id:16][sender_id:8][timestamp:8][message_type:1][content_len:4][content]
    pub fn serialize(&self) -> Vec<u8> {
        let mut buf = Vec::new();
        buf.extend_from_slice(&self.conversation_id);
        buf.extend_from_slice(&self.sender_id);
        buf.extend_from_slice(&self.timestamp.to_be_bytes());
        buf.push(self.message_type);
        buf.extend_from_slice(&(self.content.len() as u32).to_be_bytes());
        buf.extend_from_slice(&self.content);
        buf
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < 37 {
            return None;
        }
        let mut conversation_id = [0u8; 16];
        conversation_id.copy_from_slice(&data[0..16]);
        let mut sender_id: NodeId = [0u8; 8];
        sender_id.copy_from_slice(&data[16..24]);
        let mut ts_bytes = [0u8; 8];
        ts_bytes.copy_from_slice(&data[24..32]);
        let timestamp = u64::from_be_bytes(ts_bytes);
        let message_type = data[32];
        let mut len_bytes = [0u8; 4];
        len_bytes.copy_from_slice(&data[33..37]);
        let content_len = u32::from_be_bytes(len_bytes) as usize;
        if data.len() < 37 + content_len {
            return None;
        }
        let content = data[37..37 + content_len].to_vec();
        Some(Self {
            conversation_id,
            sender_id,
            timestamp,
            message_type,
            content,
        })
    }
}

// ---------------------------------------------------------------------------
// Message Type Enum
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum MessageType {
    Text = 0,
    Voice = 1,
    FileMetadata = 2,
    FileChunk = 3,
}

// ---------------------------------------------------------------------------
// Packet Type Constants
// ---------------------------------------------------------------------------

#[repr(u8)]
pub enum PacketType {
    OGM = 0x01,
    Data = 0x02,
    Broadcast = 0x03,
    X3DH = 0x04,
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_compute_node_id() {
        let pk = [0xABu8; 32];
        let id = compute_node_id(&pk);
        assert_eq!(id.len(), 8);
    }

    #[test]
    fn test_header_roundtrip() {
        let hdr = MeshPacketHeader {
            version: 0x01,
            packet_type: 0x02,
            ttl: 10,
            originator: [1, 2, 3, 4, 5, 6, 7, 8],
            sequence: 42,
            hop_count: 0,
            next_hop: [0u8; 8],
            payload_len: 100,
        };
        let bytes = hdr.serialize();
        let hdr2 = MeshPacketHeader::deserialize(&bytes).unwrap();
        assert_eq!(hdr.sequence, hdr2.sequence);
    }

    #[test]
    fn test_ogm_roundtrip() {
        let ogm = OGMPayload {
            timestamp: 1234567890,
            link_quality: 200,
            path_metric: 500,
            neighbor_count: 2,
            neighbors: [NeighborInfo::default(); 9],
        };
        let bytes = ogm.serialize();
        let ogm2 = OGMPayload::deserialize(&bytes).unwrap();
        assert_eq!(ogm.timestamp, ogm2.timestamp);
    }

    #[test]
    fn test_decrypted_message_roundtrip() {
        let msg = DecryptedMessage {
            conversation_id: [0xAAu8; 16],
            sender_id: [0xBBu8; 8],
            timestamp: 9876543210,
            message_type: 0,
            content: b"Hello mesh!".to_vec(),
        };
        let bytes = msg.serialize();
        let msg2 = DecryptedMessage::deserialize(&bytes).unwrap();
        assert_eq!(msg.content, msg2.content);
    }
}