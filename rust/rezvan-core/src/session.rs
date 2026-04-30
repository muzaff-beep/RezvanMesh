use std::collections::HashMap;
use rezvan_common::NodeId;
use rezvan_crypto::{CryptoError, CryptoProvider, IdentityKeypair, SessionState};

/// Manages cryptographic sessions (Double Ratchet) with peers.
///
/// Each session is identified by the peer's NodeId.  Outbound sessions are
/// created to the peer with an ephemeral key handshake (X3DH).  Inbound
/// handshakes are processed when an X3DH initiation packet arrives.
pub struct SessionManager {
    /// The crypto backend (mock or real).
    crypto: Box<dyn CryptoProvider>,
    /// Our own identity keypair.
    identity: IdentityKeypair,
    /// Active Double Ratchet sessions keyed by peer NodeId.
    sessions: HashMap<NodeId, SessionState>,
}

impl SessionManager {
    /// Create a new SessionManager.
    pub fn new(crypto: Box<dyn CryptoProvider>, identity: IdentityKeypair) -> Self {
        Self {
            crypto,
            identity,
            sessions: HashMap::new(),
        }
    }

    /// Initiate an outbound X3DH session to the given peer.
    ///
    /// In a real implementation this would fetch the peer's pre‑key bundle.
    /// For the mock environment the public identity key of the peer is used
    /// directly as a placeholder for the signed pre‑key.
    pub fn create_outbound_session(
        &mut self,
        their_id: &NodeId,
        their_signed_prekey: &[u8; 32],
    ) -> Result<(), CryptoError> {
        if self.sessions.contains_key(their_id) {
            return Ok(()); // session already exists
        }

        let session = self.crypto.initiate_x3dh(
            &self.identity,
            their_id,                  // use NodeId as public Ed25519 identity placeholder
            their_signed_prekey,
            None,                      // no one‑time pre‑key for now
        )?;

        self.sessions.insert(*their_id, session);
        Ok(())
    }

    /// Process an inbound X3DH initiation message from a peer.
    pub fn process_inbound_handshake(
        &mut self,
        their_id: &NodeId,
        initiation_bytes: &[u8],
    ) -> Result<(), CryptoError> {
        let session = self.crypto.receive_x3dh(
            &self.identity,
            their_id,               // peer's Ed25519 identity
            initiation_bytes,
        )?;

        self.sessions.insert(*their_id, session);
        Ok(())
    }

    /// Encrypt a plaintext message for the given peer using the Double Ratchet.
    pub fn encrypt(&mut self, their_id: &NodeId, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let session = self.sessions.get_mut(their_id).ok_or(CryptoError::NoSession)?;
        self.crypto.ratchet_encrypt(session, plaintext)
    }

    /// Decrypt a ciphertext message from the given peer using the Double Ratchet.
    pub fn decrypt(&mut self, their_id: &NodeId, ciphertext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let session = self.sessions.get_mut(their_id).ok_or(CryptoError::NoSession)?;
        self.crypto.ratchet_decrypt(session, ciphertext)
    }

    /// Return true if a session with the given peer exists.
    pub fn has_session(&self, their_id: &NodeId) -> bool {
        self.sessions.contains_key(their_id)
    }

    /// Remove a session (e.g., when a peer leaves).
    pub fn remove_session(&mut self, their_id: &NodeId) {
        self.sessions.remove(their_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rezvan_crypto::MockCryptoProvider; // Note: Mock is in rezvan-core's crypto module
    use crate::crypto::MockCryptoProvider; // For tests we import from our own crate

    fn mock_provider() -> Box<dyn CryptoProvider> {
        Box::new(MockCryptoProvider)
    }

    fn dummy_identity(seed: u8) -> IdentityKeypair {
        let mut s = [0u8; 32];
        s[0] = seed;
        mock_provider().generate_identity(&s)
    }

    #[test]
    fn test_create_outbound_session() {
        let crypto = mock_provider();
        let identity = crypto.generate_identity(&[1u8; 32]);
        let mut mgr = SessionManager::new(crypto, identity);
        let peer_id = [2u8; 8];
        let prekey = [3u8; 32];

        assert!(!mgr.has_session(&peer_id));
        assert!(mgr.create_outbound_session(&peer_id, &prekey).is_ok());
        assert!(mgr.has_session(&peer_id));
    }

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let crypto = mock_provider();
        let identity = crypto.generate_identity(&[1u8; 32]);
        let mut mgr = SessionManager::new(crypto, identity);
        let peer_id = [2u8; 8];
        let prekey = [3u8; 32];

        mgr.create_outbound_session(&peer_id, &prekey).unwrap();

        let plain = b"Hello mesh!";
        let encrypted = mgr.encrypt(&peer_id, plain).unwrap();
        // In mock provider, encrypt returns plaintext, so decrypt should return it back
        let decrypted = mgr.decrypt(&peer_id, &encrypted).unwrap();
        assert_eq!(decrypted, plain);
    }

    #[test]
    fn test_encrypt_without_session() {
        let crypto = mock_provider();
        let identity = crypto.generate_identity(&[1u8; 32]);
        let mut mgr = SessionManager::new(crypto, identity);
        let peer_id = [9u8; 8];
        assert!(mgr.encrypt(&peer_id, b"test").is_err());
    }

    #[test]
    fn test_decrypt_without_session() {
        let crypto = mock_provider();
        let identity = crypto.generate_identity(&[1u8; 32]);
        let mut mgr = SessionManager::new(crypto, identity);
        let peer_id = [9u8; 8];
        assert!(mgr.decrypt(&peer_id, b"test").is_err());
    }

    #[test]
    fn test_remove_session() {
        let crypto = mock_provider();
        let identity = crypto.generate_identity(&[1u8; 32]);
        let mut mgr = SessionManager::new(crypto, identity);
        let peer_id = [7u8; 8];
        let prekey = [8u8; 32];
        mgr.create_outbound_session(&peer_id, &prekey).unwrap();
        assert!(mgr.has_session(&peer_id));
        mgr.remove_session(&peer_id);
        assert!(!mgr.has_session(&peer_id));
    }
}