// src/routing.rs

use crate::action::Action;
use crate::crypto::{CryptoProvider, IdentityKeypair};
use crate::power::PowerState;
use std::collections::HashMap;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// Node identifier (first 8 bytes of SHA-256 of Ed25519 public key).
pub type NodeId = [u8; 8];

/// Compute NodeId from an Ed25519 public key.
pub fn compute_node_id(public_ed25519: &[u8; 32]) -> NodeId {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    // In production, use SHA-256 and take first 8 bytes.
    // For mock purposes, we use a simple hash.
    let mut hasher = DefaultHasher::new();
    public_ed25519.hash(&mut hasher);
    let hash = hasher.finish();
    hash.to_be_bytes()
}

/// A routing table entry for a destination node.
#[derive(Debug, Clone)]
pub struct RouteEntry {
    /// Destination node ID.
    pub destination: NodeId,
    /// Next hop toward the destination.
    pub next_hop: NodeId,
    /// Path metric (lower is better).
    pub metric: u32,
    /// Link quality to the next hop (0-255).
    pub link_quality: u8,
    /// Sequence number from the last OGM.
    pub sequence: u32,
    /// Timestamp when this entry was last updated.
    pub last_updated: u64,
    /// Time-to-live for this route (decremented each tick).
    pub ttl: u8,
}

/// Routing table for the mesh engine.
pub struct RoutingTable {
    /// Our own NodeId.
    our_node_id: NodeId,
    /// Map of destination -> route entry.
    routes: HashMap<NodeId, RouteEntry>,
    /// Neighbors discovered via OGMs: neighbor_id -> (link_quality, battery_level, last_seen).
    neighbors: HashMap<NodeId, (u8, u8, u64)>,
    /// Current OGM sequence number.
    ogm_sequence: u32,
    /// Timestamp of the last OGM broadcast (Unix microseconds).
    last_ogm_broadcast: u64,
    /// Interval between OGM broadcasts in seconds.
    ogm_interval_secs: u64,
}

impl RoutingTable {
    /// Create a new routing table.
    pub fn new(our_node_id: NodeId) -> Self {
        RoutingTable {
            our_node_id,
            routes: HashMap::new(),
            neighbors: HashMap::new(),
            ogm_sequence: 0,
            last_ogm_broadcast: 0,
            ogm_interval_secs: 5,
        }
    }

    /// Update the OGM broadcast interval based on power state.
    pub fn set_ogm_interval(&mut self, interval_secs: u64) {
        self.ogm_interval_secs = interval_secs;
    }

    /// Process an incoming OGM packet.
    /// Returns true if the OGM should be rebroadcast.
    pub fn process_ogm(
        &mut self,
        originator: NodeId,
        sequence: u32,
        rssi: i32,
        battery_level: u8,
        path_metric: u32,
        hop_count: u8,
        timestamp: u64,
    ) -> bool {
        // Convert RSSI to link quality (0-255)
        let link_quality = rssi_to_lq(rssi);

        // Update neighbor information (the direct sender is the neighbor)
        let sender_id = originator; // In a real implementation, sender is the previous hop
        self.neighbors.insert(
            sender_id,
            (link_quality, battery_level, timestamp),
        );

        // Calculate the metric via this neighbor
        let metric_via_neighbor = calculate_metric(path_metric, link_quality, battery_level, hop_count);

        // Check if we already have a route to this originator
        let should_update = if let Some(existing) = self.routes.get(&originator) {
            // Update if:
            // 1. New sequence number is higher, or
            // 2. Same sequence but better metric
            sequence > existing.sequence
                || (sequence == existing.sequence && metric_via_neighbor < existing.metric)
        } else {
            true
        };

        if should_update {
            let entry = RouteEntry {
                destination: originator,
                next_hop: sender_id,
                metric: metric_via_neighbor,
                link_quality,
                sequence,
                last_updated: timestamp,
                ttl: 10, // Initial TTL
            };
            self.routes.insert(originator, entry);
        }

        // Rebroadcast if TTL allows and it's a new/better route
        hop_count < 10 && should_update
    }

    /// Get the next hop for a destination.
    pub fn get_next_hop(&self, destination: &NodeId) -> Option<NodeId> {
        self.routes.get(destination).map(|e| e.next_hop)
    }

    /// Get all known destinations (for OGM neighbor list).
    pub fn get_neighbors(&self) -> Vec<(NodeId, u8)> {
        self.neighbors
            .iter()
            .map(|(id, (lq, _, _))| (*id, *lq))
            .collect()
    }

    /// Perform periodic routing table maintenance.
    /// Returns an OGM broadcast action if it's time to send one.
    pub fn tick(&mut self, current_time: u64, power_state: PowerState) -> Option<Action> {
        // Update OGM interval based on power state
        self.ogm_interval_secs = crate::power::get_ogm_interval_secs(power_state);

        // Decrement TTL on all routes, remove expired ones
        self.routes.retain(|_, entry| {
            if entry.ttl > 0 {
                entry.ttl -= 1;
                true
            } else {
                false
            }
        });

        // Remove stale neighbors (not seen in 60 seconds)
        self.neighbors.retain(|_, (_, _, last_seen)| {
            (current_time - *last_seen) < 60_000_000 // 60 seconds in microseconds
        });

        // Check if it's time to broadcast an OGM
        let interval_micros = self.ogm_interval_secs * 1_000_000;
        if current_time - self.last_ogm_broadcast >= interval_micros {
            self.last_ogm_broadcast = current_time;
            self.ogm_sequence = self.ogm_sequence.wrapping_add(1);
            Some(self.build_ogm_action())
        } else {
            None
        }
    }

    /// Build an OGM broadcast action.
    fn build_ogm_action(&self) -> Action {
        use crate::engine::MeshPacketHeader;

        // Build OGMPayload
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or(Duration::from_secs(0))
            .as_micros() as u64;

        let payload = crate::engine::OGMPayload {
            timestamp,
            link_quality: 255, // Maximum for our own OGM
            path_metric: 0,
            neighbor_count: self.neighbors.len() as u8,
            neighbors: self.build_neighbor_array(),
        };

        // Serialize payload (50 bytes)
        let mut payload_bytes = Vec::with_capacity(50);
        payload_bytes.extend_from_slice(&payload.timestamp.to_be_bytes());
        payload_bytes.push(payload.link_quality);
        payload_bytes.extend_from_slice(&payload.path_metric.to_be_bytes());
        payload_bytes.push(payload.neighbor_count);
        for neighbor in &payload.neighbors {
            payload_bytes.extend_from_slice(&neighbor.node_id_prefix);
            payload_bytes.push(neighbor.link_quality);
        }
        // Pad remaining neighbor slots with zeros
        while payload_bytes.len() < 2 + 8 + 1 + 4 + 1 + (9 * 4) {
            payload_bytes.push(0);
        }

        // Build header
        let header = MeshPacketHeader {
            version: 0x01,
            packet_type: 0x01, // OGM
            ttl: 10,
            originator: self.our_node_id,
            sequence: self.ogm_sequence,
            hop_count: 0,
            next_hop: [0u8; 8],
            payload_len: payload_bytes.len() as u16,
        };

        let mut header_bytes = Vec::with_capacity(12);
        header_bytes.push(header.version);
        header_bytes.push(header.packet_type);
        header_bytes.push(header.ttl);
        header_bytes.extend_from_slice(&header.originator);
        header_bytes.extend_from_slice(&header.sequence.to_be_bytes());
        header_bytes.push(header.hop_count);
        header_bytes.extend_from_slice(&header.next_hop);
        header_bytes.extend_from_slice(&header.payload_len.to_be_bytes());

        // Combine header and payload
        let mut packet = Vec::new();
        packet.extend_from_slice(&header_bytes);
        packet.extend_from_slice(&payload_bytes);

        // Append mock signature (64 bytes of zeros)
        packet.extend_from_slice(&[0u8; 64]);

        // Return as BLE broadcast action
        Action::SendBlePacket {
            mac: [0xFF; 6], // Broadcast MAC
            data: packet,
        }
    }

    /// Build the neighbor array for OGM payload (up to 9 neighbors).
    fn build_neighbor_array(&self) -> [NeighborInfo; 9] {
        let mut neighbors = [NeighborInfo {
            node_id_prefix: [0u8; 3],
            link_quality: 0,
        }; 9];

        for (i, (node_id, (lq, _, _))) in self.neighbors.iter().take(9).enumerate() {
            neighbors[i].node_id_prefix.copy_from_slice(&node_id[0..3]);
            neighbors[i].link_quality = *lq;
        }

        neighbors
    }

    /// Get the number of active routes.
    pub fn route_count(&self) -> usize {
        self.routes.len()
    }

    /// Get the number of active neighbors.
    pub fn neighbor_count(&self) -> usize {
        self.neighbors.len()
    }
}

/// Neighbor information included in OGM payload.
#[derive(Debug, Clone, Copy)]
pub struct NeighborInfo {
    pub node_id_prefix: [u8; 3],
    pub link_quality: u8,
}

/// Convert RSSI value to link quality (0-255).
/// Based on typical BLE RSSI range (-30 to -100 dBm).
fn rssi_to_lq(rssi: i32) -> u8 {
    if rssi > -65 {
        255
    } else if rssi < -85 {
        0
    } else {
        ((rssi + 85) * 255 / 20) as u8
    }
}

/// Calculate path metric with hop penalty and battery weighting.
fn calculate_metric(
    incoming_metric: u32,
    link_quality: u8,
    battery_level: u8,
    hop_count: u8,
) -> u32 {
    let battery_weight = if battery_level > 50 {
        1.0
    } else if battery_level > 20 {
        1.5
    } else {
        2.5
    };

    let lq_factor = if link_quality > 0 {
        (256.0 / link_quality as f32).powi(2)
    } else {
        256.0 * 256.0
    };

    let hop_penalty = (1000.0 * lq_factor * battery_weight) as u32;
    incoming_metric + hop_penalty
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_rssi_to_lq() {
        assert_eq!(rssi_to_lq(-30), 255);
        assert_eq!(rssi_to_lq(-65), 255);
        assert_eq!(rssi_to_lq(-75), 127); // (10 * 255 / 20) = 127
        assert_eq!(rssi_to_lq(-85), 0);
        assert_eq!(rssi_to_lq(-100), 0);
    }

    #[test]
    fn test_calculate_metric() {
        // High battery, good link quality
        let metric1 = calculate_metric(1000, 255, 80, 1);
        let metric2 = calculate_metric(1000, 128, 80, 1);
        assert!(metric2 > metric1); // Worse link quality = higher metric

        // Low battery penalty
        let metric3 = calculate_metric(1000, 255, 30, 1);
        let metric4 = calculate_metric(1000, 255, 80, 1);
        assert!(metric3 > metric4); // Lower battery = higher metric
    }

    #[test]
    fn test_routing_table_creation() {
        let our_id = [1u8; 8];
        let mut rt = RoutingTable::new(our_id);
        assert_eq!(rt.route_count(), 0);
        assert_eq!(rt.neighbor_count(), 0);
    }

    #[test]
    fn test_process_ogm_new_route() {
        let our_id = [1u8; 8];
        let mut rt = RoutingTable::new(our_id);
        let originator = [2u8; 8];

        let should_rebroadcast = rt.process_ogm(
            originator,
            1,        // sequence
            -70,      // RSSI
            80,       // battery
            0,        // path_metric
            0,        // hop_count
            1000000,  // timestamp
        );

        assert!(should_rebroadcast);
        assert_eq!(rt.route_count(), 1);
        assert_eq!(rt.neighbor_count(), 1);
        assert_eq!(rt.get_next_hop(&originator), Some(originator));
    }

    #[test]
    fn test_process_ogm_better_metric() {
        let our_id = [1u8; 8];
        let mut rt = RoutingTable::new(our_id);
        let originator = [2u8; 8];

        // First OGM with poor RSSI
        rt.process_ogm(originator, 1, -85, 80, 0, 0, 1000000);
        let first_metric = rt.routes.get(&originator).unwrap().metric;

        // Second OGM with same sequence but better RSSI
        rt.process_ogm(originator, 1, -60, 80, 0, 0, 2000000);
        let second_metric = rt.routes.get(&originator).unwrap().metric;

        assert!(second_metric < first_metric);
    }

    #[test]
    fn test_routing_table_ttl_expiry() {
        let our_id = [1u8; 8];
        let mut rt = RoutingTable::new(our_id);
        let originator = [2u8; 8];

        rt.process_ogm(originator, 1, -70, 80, 0, 0, 1000000);
        assert_eq!(rt.route_count(), 1);

        // Tick 11 times to expire TTL (initial TTL = 10)
        for _ in 0..11 {
            rt.tick(2000000, PowerState::Active);
        }
        assert_eq!(rt.route_count(), 0);
    }

    #[test]
    fn test_ogm_broadcast_interval() {
        let our_id = [1u8; 8];
        let mut rt = RoutingTable::new(our_id);

        // First tick - should broadcast
        let action = rt.tick(0, PowerState::Active);
        assert!(action.is_some());

        // Immediate next tick - should not broadcast (interval not elapsed)
        let action = rt.tick(1000000, PowerState::Active); // 1 second later
        assert!(action.is_none());

        // After interval - should broadcast
        let action = rt.tick(6000000, PowerState::Active); // 6 seconds later (interval is 5)
        assert!(action.is_some());
    }
      }
