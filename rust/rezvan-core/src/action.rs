use rezvan_common::DecryptedMessage;

#[derive(Debug, Clone)]
pub enum Action {
    /// Send a 31‑byte BLE advertisement.
    SendBleAdvertisement { data: Vec<u8> },

    /// Send a raw packet over Wi‑Fi Direct.
    SendWifiPacket { ip: u32, port: u16, data: Vec<u8> },

    /// Send a raw BLE packet to a specific peer.
    SendBlePacket { mac: [u8; 6], data: Vec<u8> },

    /// Change BLE scan duty‑cycle parameters.
    UpdateScanInterval { interval_ms: u32, window_ms: u32 },

    /// Notify the Kotlin UI of a newly decrypted message.
    NotifyUi { decrypted_message: DecryptedMessage },

    /// Diagnostic log entry – surfaced to Kotlin for in‑app display.
    DiagLog { tag: String, level: u8, message: String },
}

pub fn serialize_actions(actions: &[Action]) -> Vec<u8> {
    if actions.is_empty() {
        return vec![0u8];
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
            let payload = decrypted_message.serialize();
            write_payload(buf, &payload);
        }
        Action::DiagLog { tag, level, message } => {
            buf.push(0x06);
            let tag_bytes = tag.as_bytes();
            let msg_bytes = message.as_bytes();
            let mut payload = Vec::with_capacity(1 + 2 + tag_bytes.len() + 2 + msg_bytes.len());
            payload.push(*level);
            payload.extend_from_slice(&(tag_bytes.len() as u16).to_be_bytes());
            payload.extend_from_slice(tag_bytes);
            payload.extend_from_slice(&(msg_bytes.len() as u16).to_be_bytes());
            payload.extend_from_slice(msg_bytes);
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