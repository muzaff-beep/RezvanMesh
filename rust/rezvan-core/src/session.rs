use rezvan_crypto::{CryptoError, CryptoProvider, IdentityKeypair, SessionState};
use rezvan_common::NodeId;
use std::collections::HashMap;

pub struct SessionManager {
    crypto: Box<dyn CryptoProvider>,
    identity: IdentityKeypair,
    sessions: HashMap<NodeId, SessionState>,
}

impl SessionManager {
    pub fn new(crypto: Box<dyn CryptoProvider>, identity: IdentityKeypair) -> Self {
        Self {
            crypto,
            identity,
            sessions: HashMap::new(),
        }
    }

    pub fn create_outbound_session(
        &mut self,
        _their_id: &NodeId,
        _their_bundle: &[u8],
    ) -> Result<(), CryptoError> {
        Ok(())
    }

    pub fn process_inbound_handshake(
        &mut self,
        _their_id: &NodeId,
        _handshake_data: &[u8],
    ) -> Result<(), CryptoError> {
        Ok(())
    }

    pub fn encrypt(&mut self, _their_id: &NodeId, plain: &[u8]) -> Result<Vec<u8>, CryptoError> {
        Ok(plain.to_vec())
    }

    pub fn decrypt(&mut self, _their_id: &NodeId, cipher: &[u8]) -> Result<Vec<u8>, CryptoError> {
        Ok(cipher.to_vec())
    }
}
