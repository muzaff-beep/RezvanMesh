// rezvan-common/src/lib.rs

use sha2::{Sha256, Digest};

pub type NodeId = [u8; 8];

pub fn compute_node_id(public_key: &[u8; 32]) -> NodeId {
    let hash = Sha256::digest(public_key);
    let mut node_id = [0u8; 8];
    node_id.copy_from_slice(&hash[0..8]);
    node_id
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MeshPacketHeader {
    pub version: u8,
    pub packet_type: u8,
    pub ttl: u8,
    pub originator: NodeId,
    pub sequence: u32,
    pub hop_count: u8,
    pub next_hop: NodeId,
    pub payload_len: u16,
}

impl MeshPacketHeader {
    pub const SIZE: usize = 26;

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
        if data.len() < Self::SIZE { return None; }
        let version = data[0];
        let packet_type = data[1];
        let ttl = data[2];
        let mut originator = [0u8; 8];
        originator.copy_from_slice(&data[3..11]);
        let sequence = u32::from_be_bytes([data[11], data[12], data[13], data[14]]);
        let hop_count = data[15];
        let mut next_hop = [0u8; 8];
        next_hop.copy_from_slice(&data[16..24]);
        let payload_len = u16::from_be_bytes([data[24], data[25]]);
        Some(Self { version, packet_type, ttl, originator, sequence, hop_count, next_hop, payload_len })
    }
}

const _: () = assert!(MeshPacketHeader::SIZE == 26);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OGMPayload {
    pub timestamp: u64,
    pub link_quality: u8,
    pub path_metric: u32,
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
        for n in &self.neighbors {
            buf.extend_from_slice(&n.serialize());
        }
        buf
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < Self::SIZE { return None; }
        let timestamp = u64::from_be_bytes([data[0], data[1], data[2], data[3], data[4], data[5], data[6], data[7]]);
        let link_quality = data[8];
        let path_metric = u32::from_be_bytes([data[9], data[10], data[11], data[12]]);
        let neighbor_count = data[13];
        let mut neighbors = [NeighborInfo::default(); 9];
        for i in 0..9 {
            let off = 14 + i * 4;
            neighbors[i] = NeighborInfo::deserialize(&data[off..off+4])?;
        }
        Some(Self { timestamp, link_quality, path_metric, neighbor_count, neighbors })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct NeighborInfo {
    pub node_id_prefix: [u8; 3],
    pub link_quality: u8,
}

impl NeighborInfo {
    pub fn serialize(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(4);
        buf.extend_from_slice(&self.node_id_prefix);
        buf.push(self.link_quality);
        buf
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < 4 { return None; }
        let mut node_id_prefix = [0u8; 3];
        node_id_prefix.copy_from_slice(&data[0..3]);
        let link_quality = data[3];
        Some(Self { node_id_prefix, link_quality })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecryptedMessage {
    pub conversation_id: [u8; 16],
    pub sender_id: NodeId,
    pub timestamp: u64,
    pub message_type: u8,
    pub content: Vec<u8>,
}

impl DecryptedMessage {
    pub fn serialize(&self) -> Vec<u8> {
        let content_len = (self.content.len() as u32).to_be_bytes();
        let mut buf = Vec::with_capacity(16 + 8 + 8 + 1 + 4 + self.content.len());
        buf.extend_from_slice(&self.conversation_id);
        buf.extend_from_slice(&self.sender_id);
        buf.extend_from_slice(&self.timestamp.to_be_bytes());
        buf.push(self.message_type);
        buf.extend_from_slice(&content_len);
        buf.extend_from_slice(&self.content);
        buf
    }

    pub fn deserialize(data: &[u8]) -> Option<Self> {
        if data.len() < 16 + 8 + 8 + 1 + 4 { return None; }
        let mut conversation_id = [0u8; 16];
        conversation_id.copy_from_slice(&data[0..16]);
        let mut sender_id = [0u8; 8];
        sender_id.copy_from_slice(&data[16..24]);
        let timestamp = u64::from_be_bytes([data[24], data[25], data[26], data[27], data[28], data[29], data[30], data[31]]);
        let message_type = data[32];
        let content_len = u32::from_be_bytes([data[33], data[34], data[35], data[36]]) as usize;
        if data.len() < 37 + content_len { return None; }
        let content = data[37..37+content_len].to_vec();
        Some(Self { conversation_id, sender_id, timestamp, message_type, content })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_header_size_constant_matches_serialization() {
        let hdr = MeshPacketHeader {
            version: 1, packet_type: 1, ttl: 1,
            originator: [0; 8], sequence: 0, hop_count: 0,
            next_hop: [0; 8], payload_len: 0,
        };
        assert_eq!(hdr.serialize().len(), MeshPacketHeader::SIZE);
    }

    #[test]
    fn test_truncated_header_rejected() {
        let bytes = vec![0u8; MeshPacketHeader::SIZE - 1];
        assert!(MeshPacketHeader::deserialize(&bytes).is_none());
    }

    #[test]
    fn test_header_fuzz_no_panic() {
        for len in 0..30 {
            let bytes = vec![0xAAu8; len];
            let _ = MeshPacketHeader::deserialize(&bytes);
        }
    }

    #[test]
    fn test_ogm_roundtrip() {
        let ogm = OGMPayload {
            timestamp: 123456789,
            link_quality: 200,
            path_metric: 500,
            neighbor_count: 1,
            neighbors: [NeighborInfo::default(); 9],
        };
        let ser = ogm.serialize();
        assert_eq!(ser.len(), OGMPayload::SIZE);
        let deser = OGMPayload::deserialize(&ser).unwrap();
        assert_eq!(ogm, deser);
    }

    #[test]
    fn test_ogm_fuzz_no_panic() {
        for len in 0..60 {
            let bytes = vec![0xAAu8; len];
            let _ = OGMPayload::deserialize(&bytes);
        }
    }

    #[test]
    fn test_message_roundtrip() {
        let msg = DecryptedMessage {
            conversation_id: [1; 16],
            sender_id: [2; 8],
            timestamp: 99999,
            message_type: 0,
            content: b"hello mesh".to_vec(),
        };
        let ser = msg.serialize();
        let deser = DecryptedMessage::deserialize(&ser).unwrap();
        assert_eq!(msg, deser);
    }
    }
