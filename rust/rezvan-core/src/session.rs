// src/session.rs

use crate::crypto::{CryptoError, CryptoProvider, IdentityKeypair, SessionState};
use crate::routing::NodeId;
use std::collections::HashMap;

/// Manages cryptographic sessions with peers.
pub struct SessionManager {
    /// Crypto provider for all operations.
    crypto: Box<dyn CryptoProvider>,
    /// Our own identity keypair.
    identity: IdentityKeypair,
    /// Active sessions: peer NodeId -> SessionState.
    sessions: HashMap<NodeId, SessionState>,
}

impl SessionManager {
    /// Create a new session manager.
    pub fn new(crypto: Box<dyn CryptoProvider>, identity: IdentityKeypair) -> Self {
        SessionManager {
            crypto,
            identity,
            sessions: HashMap::new(),
        }
    }

    /// Create an outbound session by initiating X3DH.
    pub fn create_outbound_session(
        &mut self,
        their_id: &NodeId,
        bundle: &PreKeyBundle,
    ) -> Result<(), CryptoError> {
        let session = self.crypto.initiate_x3dh(
            &self.identity,
            &bundle.identity_key,
            &bundle.signed_prekey,
            bundle.one_time_prekey.as_ref(),
        )?;
        self.sessions.insert(*their_id, session);
        Ok(())
    }

    /// Process an inbound X3DH handshake to establish a session.
    pub fn process_inbound_handshake(
        &mut self,
        their_id: &NodeId,
        handshake_data: &[u8],
    ) -> Result<(), CryptoError> {
        let session = self
            .crypto
            .receive_x3dh(&self.identity, their_id, handshake_data)?;
        self.sessions.insert(*their_id, session);
        Ok(())
    }

    /// Encrypt a plaintext message for a peer.
    /// Returns the ciphertext or an error if no session exists.
    pub fn encrypt(&mut self, their_id: &NodeId, plain: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let session = self
            .sessions
            .get_mut(their_id)
            .ok_or(CryptoError::NoSession)?;
        self.crypto.ratchet_encrypt(session, plain)
    }

    /// Decrypt a ciphertext from a peer.
    /// Returns the plaintext or an error.
    pub fn decrypt(&mut self, their_id: &NodeId, cipher: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let session = self
            .sessions
            .get_mut(their_id)
            .ok_or(CryptoError::NoSession)?;
        self.crypto.ratchet_decrypt(session, cipher)
    }

    /// Check if a session exists for a peer.
    pub fn has_session(&self, their_id: &NodeId) -> bool {
        self.sessions.contains_key(their_id)
    }

    /// Remove a session for a peer.
    pub fn remove_session(&mut self, their_id: &NodeId) {
        self.sessions.remove(their_id);
    }

    /// Get the number of active sessions.
    pub fn session_count(&self) -> usize {
        self.sessions.len()
    }

    /// Generate a new sender key for group messaging.
    pub fn generate_sender_key(&self) -> [u8; 32] {
        self.crypto.generate_sender_key()
    }

    /// Encrypt a message with a sender key (for group messaging).
    pub fn sender_key_encrypt(&self, key: &[u8; 32], plaintext: &[u8]) -> Vec<u8> {
        self.crypto.sender_key_encrypt(key, plaintext)
    }

    /// Decrypt a message with a sender key (for group messaging).
    pub fn sender_key_decrypt(&self, key: &[u8; 32], ciphertext: &[u8]) -> Option<Vec<u8>> {
        self.crypto.sender_key_decrypt(key, ciphertext)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::MockCryptoProvider;

    fn create_test_identity() -> IdentityKeypair {
        let crypto = MockCryptoProvider;
        crypto.generate_identity(&[1u8; 32])
    }

    fn create_test_bundle() -> PreKeyBundle {
        PreKeyBundle {
            identity_key: [2u8; 32],
            signed_prekey: [3u8; 32],
            signed_prekey_signature: [0u8; 64],
            one_time_prekey: Some([4u8; 32]),
        }
    }

    #[test]
    fn test_session_manager_creation() {
        let crypto = Box::new(MockCryptoProvider);
        let identity = create_test_identity();
        let manager = SessionManager::new(crypto, identity);
        assert_eq!(manager.session_count(), 0);
    }

    #[test]
    fn test_create_outbound_session() {
        let crypto = Box::new(MockCryptoProvider);
        let identity = create_test_identity();
        let mut manager = SessionManager::new(crypto, identity);
        let peer_id = [5u8; 8];
        let bundle = create_test_bundle();

        let result = manager.create_outbound_session(&peer_id, &bundle);
        assert!(result.is_ok());
        assert!(manager.has_session(&peer_id));
        assert_eq!(manager.session_count(), 1);
    }

    #[test]
    fn test_encrypt_without_session_fails() {
        let crypto = Box::new(MockCryptoProvider);
        let identity = create_test_identity();
        let mut manager = SessionManager::new(crypto, identity);
        let peer_id = [5u8; 8];

        let result = manager.encrypt(&peer_id, b"hello");
        assert!(matches!(result, Err(CryptoError::NoSession)));
    }

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let crypto = Box::new(MockCryptoProvider);
        let identity = create_test_identity();
        let mut manager = SessionManager::new(crypto, identity);
        let peer_id = [5u8; 8];
        let bundle = create_test_bundle();

        // Create session
        manager.create_outbound_session(&peer_id, &bundle).unwrap();

        // Encrypt
        let plaintext = b"Secret message";
        let ciphertext = manager.encrypt(&peer_id, plaintext).unwrap();

        // Decrypt (mock just strips the 0xDEAD prefix)
        let decrypted = manager.decrypt(&peer_id, &ciphertext).unwrap();
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_remove_session() {
        let crypto = Box::new(MockCryptoProvider);
        let identity = create_test_identity();
        let mut manager = SessionManager::new(crypto, identity);
        let peer_id = [5u8; 8];
        let bundle = create_test_bundle();

        manager.create_outbound_session(&peer_id, &bundle).unwrap();
        assert!(manager.has_session(&peer_id));

        manager.remove_session(&peer_id);
        assert!(!manager.has_session(&peer_id));
    }

    #[test]
    fn test_sender_key_encrypt_decrypt() {
        let crypto = Box::new(MockCryptoProvider);
        let identity = create_test_identity();
        let manager = SessionManager::new(crypto, identity);

        let key = manager.generate_sender_key();
        let plaintext = b"Group message";

        let ciphertext = manager.sender_key_encrypt(&key, plaintext);
        let decrypted = manager.sender_key_decrypt(&key, &ciphertext).unwrap();

        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_process_inbound_handshake() {
        let crypto = Box::new(MockCryptoProvider);
        let identity = create_test_identity();
        let mut manager = SessionManager::new(crypto, identity);
        let peer_id = [5u8; 8];

        // Mock handshake data
        let handshake_data = vec![0u8; 64];

        let result = manager.process_inbound_handshake(&peer_id, &handshake_data);
        assert!(result.is_ok());
        assert!(manager.has_session(&peer_id));
    }
      }
