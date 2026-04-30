use crate::action::Action;
use crate::power::{PowerState, compute_state};
use crate::routing::RoutingTable;
use crate::session::SessionManager;
use rezvan_common::{DecryptedMessage, NodeId};
use rezvan_crypto::{CryptoProvider, IdentityKeypair, SessionState};

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

impl MeshEngine {
    pub fn new(seed: &[u8; 32], crypto: Box<dyn CryptoProvider>) -> Self {
        let identity = crypto.generate_identity(seed);
        let node_id = crypto.compute_node_id(&identity.public_ed25519);

        Self {
            routing: RoutingTable::new(node_id),
            sessions: SessionManager::new(crypto.clone_box(), identity),
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
        Vec::new()
    }

    pub fn process_incoming(&mut self, _packet: &[u8], _rssi: i32, _timestamp: u64) -> (Option<DecryptedMessage>, Vec<Action>) {
        (None, Vec::new())
    }

    pub fn send_message(&mut self, _recipient: &NodeId, _plaintext: &[u8], _msg_type: u8) -> Vec<Action> {
        Vec::new()
    }

    pub fn update_battery(&mut self, level: u8, charging: bool) {
        self.battery_level = level;
        self.is_charging = charging;
        self.power_state = compute_state(level, charging, self.node_density, self.user_override);
    }

    pub fn get_power_state(&self) -> PowerState {
        self.power_state
    }
}

// Trait for cloning the crypto provider (object‑safe)
trait CloneableCrypto: CryptoProvider {
    fn clone_box(&self) -> Box<dyn CryptoProvider>;
}

impl<T: CryptoProvider + Clone + 'static> CloneableCrypto for T {
    fn clone_box(&self) -> Box<dyn CryptoProvider> {
        Box::new(self.clone())
    }
}

impl Clone for Box<dyn CryptoProvider> {
    fn clone(&self) -> Self {
        (**self).clone_box()
    }
}
