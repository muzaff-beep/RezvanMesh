use rezvan_common::DecryptedMessage;

/// Actions that the Rust mesh engine requests from the Kotlin radio layer.
///
/// The serialization format is a compact binary sequence understood by
/// `ActionDispatcher.kt` on the Android side:
///
/// ```text
/// [1 byte: Action Count N]
/// For each action:
///     [1 byte: Action Type]
///     [2 bytes: Payload Length L]   (big-endian)
///     [L bytes: Payload]
/// ```
#[derive(Debug, Clone)]
pub enum Action {
    /// Send a 31‑byte BLE advertisement.  `data` must be exactly 31 bytes.
    SendBleAdvertisement { data: Vec<u8> },

    /// Send a raw packet over Wi‑Fi Direct to the given IP and port.
    SendWifiPacket { ip: u32, port: u16, data: Vec<u8> },

    /// Send a raw BLE packet to a specific peer identified by its MAC.
    SendBlePacket { mac: [u8; 6], data: Vec<u8> },

    /// Change BLE scan duty‑cycle parameters.
    UpdateScanInterval { interval_ms: u32, window_ms: u32 },

    /// Notify the Kotlin UI of a newly decrypted message.
    NotifyUi { decrypted_message: DecryptedMessage },
}

/// Serialize a list of actions into the binary wire format.
pub fn serialize_actions(actions: &[Action]) -> Vec<u8> {
    if actions.is_empty() {
        return vec![0u8]; // zero actions, only the count byte
    }

    let mut buf = Vec::new();
    buf.push(actions.len() as u8);

    for action in actions {
        serialize_one(&mut buf, action);
    }
    buf
}

fn serialize_one(buf: &mut Vec<u8>, action: &Action) {
    match action {
        Action::SendBleAdvertisement { data } => {
            buf.push(0x01);
            let payload = prepare_ble_adv_payload(data);
            write_payload(buf, &payload);
        }
        Action::SendWifiPacket { ip, port, data } => {
            buf.push(0x02);
            let mut payload = Vec::with_capacity(6 + data.len());
            payload.extend_from_slice(&ip.to_be_bytes());
            payload.extend_from_slice(&port.to_be_bytes());
            payload.extend_from_slice(data);
            write_payload(buf, &payload);
        }
        Action::SendBlePacket { mac, data } => {
            buf.push(0x03);
            let mut payload = Vec::with_capacity(6 + data.len());
            payload.extend_from_slice(mac);
            payload.extend_from_slice(data);
            write_payload(buf, &payload);
        }
        Action::UpdateScanInterval { interval_ms, window_ms } => {
            buf.push(0x04);
            let mut payload = Vec::with_capacity(8);
            payload.extend_from_slice(&interval_ms.to_be_bytes());
            payload.extend_from_slice(&window_ms.to_be_bytes());
            write_payload(buf, &payload);
        }
        Action::NotifyUi { decrypted_message } => {
            buf.push(0x05);
            let payload = decrypted_message.serialize(); // uses DecryptedMessage's serialize
            write_payload(buf, &payload);
        }
    }
}

/// Ensure the BLE advertisement data is exactly 31 bytes — pad with zeros if needed.
fn prepare_ble_adv_payload(data: &[u8]) -> Vec<u8> {
    let mut fixed = vec![0u8; 31];
    let len = data.len().min(31);
    fixed[..len].copy_from_slice(&data[..len]);
    fixed
}

fn write_payload(buf: &mut Vec<u8>, payload: &[u8]) {
    let len = (payload.len() as u16).to_be_bytes();
    buf.extend_from_slice(&len);
    buf.extend_from_slice(payload);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_serialize_empty() {
        let actions: Vec<Action> = vec![];
        let result = serialize_actions(&actions);
        assert_eq!(result, vec![0u8]);
    }

    #[test]
    fn test_serialize_ble_advertisement() {
        let actions = vec![Action::SendBleAdvertisement {
            data: vec![0xAB; 31],
        }];
        let serialized = serialize_actions(&actions);
        // 1 count, then type 0x01, length 31, 31 bytes
        assert_eq!(serialized[0], 1);
        assert_eq!(serialized[1], 0x01);
        assert_eq!(u16::from_be_bytes([serialized[2], serialized[3]]), 31);
        assert_eq!(serialized.len(), 1 + 1 + 2 + 31);
    }

    #[test]
    fn test_serialize_wifi_packet() {
        let actions = vec![Action::SendWifiPacket {
            ip: 0xC0A80001,
            port: 4237,
            data: b"hello".to_vec(),
        }];
        let serialized = serialize_actions(&actions);
        assert_eq!(serialized[0], 1);
        assert_eq!(serialized[1], 0x02);
        let payload_len = u16::from_be_bytes([serialized[2], serialized[3]]) as usize;
        assert_eq!(payload_len, 6 + 5); // ip(4) + port(2) + data(5)
    }

    #[test]
    fn test_serialize_update_scan() {
        let actions = vec![Action::UpdateScanInterval {
            interval_ms: 5000,
            window_ms: 250,
        }];
        let serialized = serialize_actions(&actions);
        assert_eq!(serialized[1], 0x04);
        let payload_len = u16::from_be_bytes([serialized[2], serialized[3]]);
        assert_eq!(payload_len, 8);
    }

    #[test]
    fn test_serialize_notify_ui() {
        let msg = DecryptedMessage {
            conversation_id: [0x01; 16],
            sender_id: [0x02; 8],
            timestamp: 12345,
            message_type: 0,
            content: vec![0xAA; 10],
        };
        let actions = vec![Action::NotifyUi {
            decrypted_message: msg.clone(),
        }];
        let serialized = serialize_actions(&actions);
        assert_eq!(serialized[1], 0x05);
        // Verify the payload within can be deserialized
        let payload = &serialized[4..];
        let decoded = DecryptedMessage::deserialize(payload).unwrap();
        assert_eq!(decoded.timestamp, msg.timestamp);
    }
}