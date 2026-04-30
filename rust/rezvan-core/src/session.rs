use std::collections::HashMap;
use rezvan_common::NodeId;
use rezvan_crypto::{CryptoError, CryptoProvider, IdentityKeypair, SessionState};

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

    pub fn identity(&self) -> IdentityKeypair {
        self.identity.clone()
    }

    pub fn create_outbound_session(
        &mut self,
        their_id: &NodeId,
        their_identity_25519: &[u8; 32],
    ) -> Result<(), CryptoError> {
        if self.sessions.contains_key(their_id) {
            return Ok(());
        }

        let session = self.crypto.initiate_x3dh(
            &self.identity,
            their_identity_25519,
            their_identity_25519, // signed prekey == identity key for now
            None,
        )?;

        self.sessions.insert(*their_id, session);
        Ok(())
    }

    pub fn process_inbound_handshake(
        &mut self,
        their_id: &NodeId,
        initiation_bytes: &[u8],
    ) -> Result<(), CryptoError> {
        // Inbound handshake carries the sender's ephemeral key; we still need their
        // long‑term X25519 identity.  For now use the same placeholder as above.
        let their_identity_25519 = [0u8; 32]; // TODO: extract from initiation_bytes

        let session = self.crypto.receive_x3dh(
            &self.identity,
            &their_identity_25519,
            initiation_bytes,
        )?;

        self.sessions.insert(*their_id, session);
        Ok(())
    }

    pub fn encrypt(&mut self, their_id: &NodeId, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let session = self.sessions.get_mut(their_id).ok_or(CryptoError::NoSession)?;
        self.crypto.ratchet_encrypt(session, plaintext)
    }

    pub fn decrypt(&mut self, their_id: &NodeId, ciphertext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let session = self.sessions.get_mut(their_id).ok_or(CryptoError::NoSession)?;
        self.crypto.ratchet_decrypt(session, ciphertext)
    }

    pub fn has_session(&self, their_id: &NodeId) -> bool {
        self.sessions.contains_key(their_id)
    }

    pub fn remove_session(&mut self, their_id: &NodeId) {
        self.sessions.remove(their_id);
    }
}
