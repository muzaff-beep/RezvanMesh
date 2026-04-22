// src/action.rs

use std::collections::VecDeque;

/// Actions that the core can request from the radio layer.
#[derive(Debug, Clone, PartialEq)]
pub enum Action {
    /// Send a BLE advertisement (exactly 31 bytes).
    SendBleAdvertisement { data: Vec<u8> },
    /// Send a packet over WiFi Direct.
    SendWifiPacket { ip: u32, port: u16, data: Vec<u8> },
    /// Send a packet over BLE to a specific peer.
    SendBlePacket { mac: [u8; 6], data: Vec<u8> },
    /// Update BLE scanning parameters.
    UpdateScanInterval { interval_ms: u32, window_ms: u32 },
    /// Notify the UI of a new decrypted message.
    NotifyUi { decrypted_message: DecryptedMessage },
}

/// A decrypted message received from the mesh or sent by the local user.
#[derive(Debug, Clone, PartialEq)]
pub struct DecryptedMessage {
    pub conversation_id: [u8; 16],
    pub sender_id: [u8; 8],
    pub timestamp: u64,
    pub message_type: u8,
    pub content: Vec<u8>,
}

// Action type constants
const ACTION_SEND_BLE_ADVERT: u8 = 0x01;
const ACTION_SEND_WIFI_PACKET: u8 = 0x02;
const ACTION_SEND_BLE_PACKET: u8 = 0x03;
const ACTION_UPDATE_SCAN_INTERVAL: u8 = 0x04;
const ACTION_NOTIFY_UI: u8 = 0x05;

/// Serialize a slice of Actions into the binary format expected by the radio layer.
///
/// Format:
/// [1 byte: Action Count N]
/// For each action:
///     [1 byte: Action Type]
///     [2 bytes: Payload Length L] (big-endian)
///     [L bytes: Payload]
pub fn serialize_actions(actions: &[Action]) -> Vec<u8> {
    let mut output = Vec::new();
    output.push(actions.len() as u8);

    for action in actions {
        match action {
            Action::SendBleAdvertisement { data } => {
                output.push(ACTION_SEND_BLE_ADVERT);
                // Payload length (2 bytes big-endian)
                let len = data.len() as u16;
                output.extend_from_slice(&len.to_be_bytes());
                output.extend_from_slice(data);
            }
            Action::SendWifiPacket { ip, port, data } => {
                output.push(ACTION_SEND_WIFI_PACKET);
                // Payload: 4 bytes IP + 2 bytes port + data
                let payload_len = (4 + 2 + data.len()) as u16;
                output.extend_from_slice(&payload_len.to_be_bytes());
                output.extend_from_slice(&ip.to_be_bytes());
                output.extend_from_slice(&port.to_be_bytes());
                output.extend_from_slice(data);
            }
            Action::SendBlePacket { mac, data } => {
                output.push(ACTION_SEND_BLE_PACKET);
                // Payload: 6 bytes MAC + data
                let payload_len = (6 + data.len()) as u16;
                output.extend_from_slice(&payload_len.to_be_bytes());
                output.extend_from_slice(mac);
                output.extend_from_slice(data);
            }
            Action::UpdateScanInterval { interval_ms, window_ms } => {
                output.push(ACTION_UPDATE_SCAN_INTERVAL);
                output.extend_from_slice(&8u16.to_be_bytes()); // 4 + 4 = 8 bytes
                output.extend_from_slice(&interval_ms.to_be_bytes());
                output.extend_from_slice(&window_ms.to_be_bytes());
            }
            Action::NotifyUi { decrypted_message } => {
                output.push(ACTION_NOTIFY_UI);
                let msg_bytes = serialize_decrypted_message(decrypted_message);
                let payload_len = msg_bytes.len() as u16;
                output.extend_from_slice(&payload_len.to_be_bytes());
                output.extend_from_slice(&msg_bytes);
            }
        }
    }

    output
}

/// Serialize a DecryptedMessage into bytes.
/// Format:
/// [conversation_id: 16][sender_id: 8][timestamp: 8][message_type: 1][content_len: 4][content]
fn serialize_decrypted_message(msg: &DecryptedMessage) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&msg.conversation_id);
    out.extend_from_slice(&msg.sender_id);
    out.extend_from_slice(&msg.timestamp.to_be_bytes());
    out.push(msg.message_type);
    let content_len = msg.content.len() as u32;
    out.extend_from_slice(&content_len.to_be_bytes());
    out.extend_from_slice(&msg.content);
    out
}

/// Deserialize actions from bytes. Returns a VecDeque of Actions.
/// Used for testing; the radio layer handles this on the Android side.
pub fn deserialize_actions(bytes: &[u8]) -> Result<VecDeque<Action>, String> {
    let mut actions = VecDeque::new();
    if bytes.is_empty() {
        return Ok(actions);
    }
    let mut offset = 0;
    let count = bytes[offset] as usize;
    offset += 1;

    for _ in 0..count {
        if offset + 3 > bytes.len() {
            return Err("Incomplete action header".to_string());
        }
        let action_type = bytes[offset];
        offset += 1;
        let payload_len = u16::from_be_bytes([bytes[offset], bytes[offset + 1]]) as usize;
        offset += 2;

        if offset + payload_len > bytes.len() {
            return Err("Payload truncated".to_string());
        }
        let payload = &bytes[offset..offset + payload_len];
        offset += payload_len;

        let action = match action_type {
            ACTION_SEND_BLE_ADVERT => {
                if payload_len != 31 {
                    return Err("BLE advertisement must be 31 bytes".to_string());
                }
                Action::SendBleAdvertisement {
                    data: payload.to_vec(),
                }
            }
            ACTION_SEND_WIFI_PACKET => {
                if payload_len < 6 {
                    return Err("WiFi packet too short".to_string());
                }
                let ip = u32::from_be_bytes([payload[0], payload[1], payload[2], payload[3]]);
                let port = u16::from_be_bytes([payload[4], payload[5]]);
                let data = payload[6..].to_vec();
                Action::SendWifiPacket { ip, port, data }
            }
            ACTION_SEND_BLE_PACKET => {
                if payload_len < 6 {
                    return Err("BLE packet too short".to_string());
                }
                let mac: [u8; 6] = payload[0..6].try_into().unwrap();
                let data = payload[6..].to_vec();
                Action::SendBlePacket { mac, data }
            }
            ACTION_UPDATE_SCAN_INTERVAL => {
                if payload_len != 8 {
                    return Err("UpdateScanInterval payload must be 8 bytes".to_string());
                }
                let interval_ms = u32::from_be_bytes([payload[0], payload[1], payload[2], payload[3]]);
                let window_ms = u32::from_be_bytes([payload[4], payload[5], payload[6], payload[7]]);
                Action::UpdateScanInterval { interval_ms, window_ms }
            }
            ACTION_NOTIFY_UI => {
                let msg = deserialize_decrypted_message(payload)?;
                Action::NotifyUi { decrypted_message: msg }
            }
            _ => return Err(format!("Unknown action type: {}", action_type)),
        };
        actions.push_back(action);
    }

    Ok(actions)
}

/// Deserialize a DecryptedMessage from bytes.
fn deserialize_decrypted_message(bytes: &[u8]) -> Result<DecryptedMessage, String> {
    if bytes.len() < 16 + 8 + 8 + 1 + 4 {
        return Err("DecryptedMessage too short".to_string());
    }
    let mut offset = 0;
    let conversation_id: [u8; 16] = bytes[offset..offset + 16].try_into().unwrap();
    offset += 16;
    let sender_id: [u8; 8] = bytes[offset..offset + 8].try_into().unwrap();
    offset += 8;
    let timestamp = u64::from_be_bytes(bytes[offset..offset + 8].try_into().unwrap());
    offset += 8;
    let message_type = bytes[offset];
    offset += 1;
    let content_len = u32::from_be_bytes(bytes[offset..offset + 4].try_into().unwrap()) as usize;
    offset += 4;
    if offset + content_len > bytes.len() {
        return Err("Content truncated".to_string());
    }
    let content = bytes[offset..offset + content_len].to_vec();

    Ok(DecryptedMessage {
        conversation_id,
        sender_id,
        timestamp,
        message_type,
        content,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_serialize_deserialize_empty() {
        let actions = vec![];
        let serialized = serialize_actions(&actions);
        let deserialized = deserialize_actions(&serialized).unwrap();
        assert!(deserialized.is_empty());
    }

    #[test]
    fn test_serialize_deserialize_ble_advert() {
        let data = vec![0x52, 0x56]; // "RV" prefix, rest zeros for test
        let mut advert = data.clone();
        advert.resize(31, 0);
        let actions = vec![Action::SendBleAdvertisement { data: advert.clone() }];
        let serialized = serialize_actions(&actions);
        let deserialized = deserialize_actions(&serialized).unwrap();
        assert_eq!(deserialized.len(), 1);
        match &deserialized[0] {
            Action::SendBleAdvertisement { data: d } => assert_eq!(d, &advert),
            _ => panic!("Wrong action type"),
        }
    }

    #[test]
    fn test_serialize_deserialize_wifi_packet() {
        let actions = vec![Action::SendWifiPacket {
            ip: 0xC0A80101, // 192.168.1.1
            port: 4237,
            data: b"hello".to_vec(),
        }];
        let serialized = serialize_actions(&actions);
        let deserialized = deserialize_actions(&serialized).unwrap();
        assert_eq!(deserialized, actions.into_iter().collect::<VecDeque<_>>());
    }

    #[test]
    fn test_serialize_deserialize_ble_packet() {
        let actions = vec![Action::SendBlePacket {
            mac: [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF],
            data: b"test".to_vec(),
        }];
        let serialized = serialize_actions(&actions);
        let deserialized = deserialize_actions(&serialized).unwrap();
        assert_eq!(deserialized, actions.into_iter().collect::<VecDeque<_>>());
    }

    #[test]
    fn test_serialize_deserialize_update_scan() {
        let actions = vec![Action::UpdateScanInterval {
            interval_ms: 5000,
            window_ms: 250,
        }];
        let serialized = serialize_actions(&actions);
        let deserialized = deserialize_actions(&serialized).unwrap();
        assert_eq!(deserialized, actions.into_iter().collect::<VecDeque<_>>());
    }

    #[test]
    fn test_serialize_deserialize_notify_ui() {
        let msg = DecryptedMessage {
            conversation_id: [1u8; 16],
            sender_id: [2u8; 8],
            timestamp: 1234567890,
            message_type: 0,
            content: b"Hello, world!".to_vec(),
        };
        let actions = vec![Action::NotifyUi {
            decrypted_message: msg.clone(),
        }];
        let serialized = serialize_actions(&actions);
        let deserialized = deserialize_actions(&serialized).unwrap();
        match &deserialized[0] {
            Action::NotifyUi { decrypted_message } => {
                assert_eq!(decrypted_message.conversation_id, msg.conversation_id);
                assert_eq!(decrypted_message.sender_id, msg.sender_id);
                assert_eq!(decrypted_message.timestamp, msg.timestamp);
                assert_eq!(decrypted_message.message_type, msg.message_type);
                assert_eq!(decrypted_message.content, msg.content);
            }
            _ => panic!("Wrong action type"),
        }
    }

    #[test]
    fn test_multiple_actions() {
        let actions = vec![
            Action::UpdateScanInterval { interval_ms: 1000, window_ms: 500 },
            Action::SendBlePacket { mac: [0x11; 6], data: vec![1, 2, 3] },
        ];
        let serialized = serialize_actions(&actions);
        let deserialized = deserialize_actions(&serialized).unwrap();
        assert_eq!(deserialized, actions.into_iter().collect::<VecDeque<_>>());
    }
  }
