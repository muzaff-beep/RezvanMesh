use rezvan_common::{NeighborInfo, NodeId, OGMPayload, MeshPacketHeader};
use std::collections::HashMap;

// ---------------------------------------------------------------------------
// Routing Table
// ---------------------------------------------------------------------------

pub struct RoutingTable {
    /// Our own node id (first 8 bytes of SHA‑256(pubkey))
    pub node_id: NodeId,
    /// Map from destination NodeId → up to 3 candidate routes
    routes: HashMap<NodeId, Vec<RouteEntry>>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RouteEntry {
    /// Next hop to reach the destination
    pub next_hop: NodeId,
    /// Cumulative BATMAN‑adv metric (lower is better)
    pub metric: u32,
    /// Link quality (0‑255) of the link through which this OGM was received
    pub link_quality: u8,
}

impl RoutingTable {
    pub fn new(node_id: NodeId) -> Self {
        Self {
            node_id,
            routes: HashMap::new(),
        }
    }

    // -----------------------------------------------------------------------
    // OGM Processing
    // -----------------------------------------------------------------------

    /// Process an incoming OGM packet.
    ///
    /// Returns `true` if the OGM should be rebroadcast (i.e. the metric was
    /// updated), `false` otherwise.
    pub fn process_ogm(&mut self, packet: &[u8], rssi: i32) -> bool {
        let header = match MeshPacketHeader::deserialize(packet) {
            Some(h) => h,
            None => return false,
        };

        // Reject our own OGM
        if header.originator == self.node_id {
            return false;
        }

        let payload_data = match packet.get(12..) {
            Some(p) => p,
            None => return false,
        };

        let ogm = match OGMPayload::deserialize(payload_data) {
            Some(o) => o,
            None => return false,
        };

        let lq = rssi_to_lq(rssi);
        if lq == 0 {
            return false; // link too weak to consider
        }

        // Battery weight placeholder – in production this is extracted from the
        // BLE advertisement of the neighbour (not available in the OGM itself).
        let battery_weight = 1.0;

        // Compute the cumulative metric to the originator
        let hop_penalty = compute_hop_penalty(lq, battery_weight);
        let new_metric = ogm.path_metric + hop_penalty;

        let entries = self.routes.entry(header.originator).or_default();

        // Check if we already have a route through this same next‑hop
        if let Some(existing) = entries.iter_mut().find(|e| e.next_hop == header.next_hop) {
            if new_metric < existing.metric {
                existing.metric = new_metric;
                existing.link_quality = lq;
                return true; // metric improved → rebroadcast
            }
            return false;
        }

        // New route entry
        entries.push(RouteEntry {
            next_hop: header.originator,
            metric: new_metric,
            link_quality: lq,
        });

        // Keep only the best 3 routes, sorted by metric
        entries.sort_by(|a, b| a.metric.cmp(&b.metric));
        entries.truncate(3);

        true // new route added → rebroadcast
    }

    // -----------------------------------------------------------------------
    // Route Lookup
    // -----------------------------------------------------------------------

    /// Return the best route (lowest metric) for a destination.
    pub fn get_best_route(&self, dest: &NodeId) -> Option<&RouteEntry> {
        self.routes.get(dest)?.first()
    }

    /// Return all known routes for a destination (up to 3).
    pub fn get_routes(&self, dest: &NodeId) -> &[RouteEntry] {
        self.routes.get(dest).map(|v| v.as_slice()).unwrap_or(&[])
    }

    /// Return a list of all known destinations.
    pub fn destinations(&self) -> Vec<NodeId> {
        self.routes.keys().cloned().collect()
    }

    // -----------------------------------------------------------------------
    // OGM Construction (for periodic rebroadcast)
    // -----------------------------------------------------------------------

    /// Build an OGM packet that reflects our current view of the network.
    ///
    /// The OGM is signed later by the engine using the Ed25519 identity key.
    /// Returns the raw bytes `[header][payload]` (signature is appended
    /// externally).
    pub fn build_ogm(&self, sequence: u32, timestamp: u64) -> Vec<u8> {
        let mut neighbors = [NeighborInfo::default(); 9];
        let mut count = 0u8;

        for (dest, entries) in &self.routes {
            if count >= 9 {
                break;
            }
            if let Some(best) = entries.first() {
                let mut prefix = [0u8; 3];
                prefix.copy_from_slice(&dest[..3]);
                neighbors[count as usize] = NeighborInfo {
                    node_id_prefix: prefix,
                    link_quality: best.link_quality,
                };
                count += 1;
            }
        }

        let ogm = OGMPayload {
            timestamp,
            link_quality: 0, // set by the originator only
            path_metric: 0,   // originator sets this to 0
            neighbor_count: count,
            neighbors,
        };

        let payload = ogm.serialize();
        let header = MeshPacketHeader {
            version: 0x01,
            packet_type: 0x01,
            ttl: 10,
            originator: self.node_id,
            sequence,
            hop_count: 0,
            next_hop: [0u8; 8],
            payload_len: payload.len() as u16,
        };

        let mut packet = header.serialize();
        packet.extend_from_slice(&payload);
        packet
    }

    /// Remove routes that have not been updated for `max_age_secs`.
    /// In a full implementation this would be driven by a timer; here it is
    /// provided as a utility for the engine.
    pub fn purge_stale(&mut self, _max_age_secs: u64) {
        // Placeholder – full implementation requires a last‑seen timestamp in RouteEntry
    }
}

// ---------------------------------------------------------------------------
// Metric Helpers
// ---------------------------------------------------------------------------

/// Map RSSI to a 0‑255 link quality value.
pub fn rssi_to_lq(rssi: i32) -> u8 {
    if rssi > -65 {
        255
    } else if rssi < -85 {
        0
    } else {
        ((rssi + 85) * 255 / 20) as u8
    }
}

/// Compute the BATMAN‑adv hop penalty for a single link.
///
/// `battery_weight` is derived from the neighbour's battery level:
///   - > 50 % → 1.0
///   - > 20 % → 1.5
///   - else   → 2.5
pub fn compute_hop_penalty(lq: u8, battery_weight: f32) -> u32 {
    let lq_f = lq.max(1) as f32;
    (1000.0 * (256.0 / lq_f).powi(2) * battery_weight) as u32
}

/// Compute the route length penalty (discourages excessively long paths).
pub fn route_length_penalty(hop_count: u8) -> u32 {
    100 * (hop_count.saturating_sub(1) as f32).powf(1.5) as u32
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn dummy_node_id(byte: u8) -> NodeId {
        [byte; 8]
    }

    fn dummy_ogm_packet(originator: NodeId, path_metric: u32, hop_count: u8) -> Vec<u8> {
        let ogm = OGMPayload {
            timestamp: 0,
            link_quality: 200,
            path_metric,
            neighbor_count: 0,
            neighbors: [NeighborInfo::default(); 9],
        };
        let payload = ogm.serialize();
        let header = MeshPacketHeader {
            version: 0x01,
            packet_type: 0x01,
            ttl: 10,
            originator,
            sequence: 1,
            hop_count,
            next_hop: originator,
            payload_len: payload.len() as u16,
        };
        let mut pkt = header.serialize();
        pkt.extend_from_slice(&payload);
        pkt
    }

    #[test]
    fn test_rssi_to_lq_boundaries() {
        assert_eq!(rssi_to_lq(-50), 255);
        assert_eq!(rssi_to_lq(-65), 255);
        assert_eq!(rssi_to_lq(-85), 0);
        assert_eq!(rssi_to_lq(-90), 0);
    }

    #[test]
    fn test_hop_penalty() {
        let penalty = compute_hop_penalty(255, 1.0);
        assert!(penalty > 900 && penalty < 1100);
    }

    #[test]
    fn test_process_ogm_new_route() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let pkt = dummy_ogm_packet(dummy_node_id(0xBB), 500, 1);
        let rebroadcast = table.process_ogm(&pkt, -60);
        assert!(rebroadcast);
        assert!(table.get_best_route(&dummy_node_id(0xBB)).is_some());
    }

    #[test]
    fn test_process_ogm_own_packet_ignored() {
        let our_id = dummy_node_id(0xAA);
        let mut table = RoutingTable::new(our_id);
        let pkt = dummy_ogm_packet(our_id, 0, 0);
        let rebroadcast = table.process_ogm(&pkt, -60);
        assert!(!rebroadcast);
    }

    #[test]
    fn test_build_ogm() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let pkt = dummy_ogm_packet(dummy_node_id(0xBB), 200, 1);
        table.process_ogm(&pkt, -70);
        let ogm = table.build_ogm(1, 12345678);
        assert!(ogm.len() > 12);
    }
}