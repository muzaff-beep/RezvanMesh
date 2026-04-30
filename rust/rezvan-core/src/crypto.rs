pub use rezvan_crypto::{IdentityKeypair, CryptoProvider, SessionState, CryptoError};

/// A mock implementation of `CryptoProvider` for Team A's initial development.
///
/// This provider performs **no real cryptography**.  It is used to validate
/// routing, power management, JNI integration, and the action pipeline before
/// the production `SodiumCryptoProvider` is integrated.
///
/// # Important
/// Replace this mock with `rezvan_crypto::SodiumCryptoProvider` before any
/// field deployment.  All methods are deterministic and **never fail** (e.g.
/// `ratchet_decrypt` simply returns the ciphertext unchanged).
pub struct MockCryptoProvider;

impl CryptoProvider for MockCryptoProvider {
    fn generate_identity(&self, seed: &[u8; 32]) -> IdentityKeypair {
        let mut public_ed = [0u8; 32];
        let mut private_ed = [0u8; 64];
        let mut public_x = [0u8; 32];
        let mut private_x = [0u8; 32];

        // Simple deterministic mapping from seed (NOT secure!)
        public_ed.copy_from_slice(seed);
        private_ed[0..32].copy_from_slice(seed);
        private_ed[32..64].copy_from_slice(seed);
        public_x.copy_from_slice(seed);
        private_x.copy_from_slice(seed);

        IdentityKeypair {
            public_ed25519: public_ed,
            private_ed25519: private_ed,
            public_x25519: public_x,
            private_x25519: private_x,
        }
    }

    fn sign(&self, identity: &IdentityKeypair, message: &[u8]) -> [u8; 64] {
        let mut sig = [0u8; 64];
        // First 32 bytes: copy of the private Ed25519 key (mock)
        sig[0..32].copy_from_slice(&identity.private_ed25519[0..32]);
        // Next 32 bytes: copy of the first 32 bytes of the message
        let len = message.len().min(32);
        sig[32..32 + len].copy_from_slice(&message[..len]);
        sig
    }

    fn verify(&self, _public_key: &[u8; 32], _message: &[u8], _signature: &[u8; 64]) -> bool {
        // Insecure mock: always accepts
        true
    }

    fn initiate_x3dh(
        &self,
        _our_identity: &IdentityKeypair,
        _their_identity: &[u8; 32],
        _their_signed_prekey: &[u8; 32],
        _their_one_time_prekey: Option<&[u8; 32]>,
    ) -> Result<SessionState, CryptoError> {
        Ok(SessionState {
            root_key: [0u8; 32],
            sending_chain_key: [0u8; 32],
            receiving_chain_key: [0u8; 32],
            sending_ratchet_private: [0u8; 32],
            sending_ratchet_public: [0u8; 32],
            receiving_ratchet_public: None,
            sending_message_number: 0,
            receiving_message_number: 0,
            previous_sending_chain_length: 0,
            skipped_message_keys: std::collections::HashMap::new(),
        })
    }

    fn receive_x3dh(
        &self,
        our_identity: &IdentityKeypair,
        their_identity: &[u8; 32],
        initiation_bytes: &[u8],
    ) -> Result<SessionState, CryptoError> {
        // Mock: simply delegate to initiate_x3dh with a dummy signed prekey
        self.initiate_x3dh(our_identity, their_identity, &[0u8; 32], None)
    }

    fn ratchet_encrypt(
        &self,
        _session: &mut SessionState,
        plaintext: &[u8],
    ) -> Result<Vec<u8>, CryptoError> {
        // Mock: no encryption
        Ok(plaintext.to_vec())
    }

    fn ratchet_decrypt(
        &self,
        _session: &mut SessionState,
        ciphertext: &[u8],
    ) -> Result<Vec<u8>, CryptoError> {
        // Mock: no encryption
        Ok(ciphertext.to_vec())
    }

    fn generate_sender_key(&self) -> [u8; 32] {
        [0u8; 32]
    }

    fn sender_key_encrypt(&self, _key: &[u8; 32], plaintext: &[u8]) -> Vec<u8> {
        plaintext.to_vec()
    }

    fn sender_key_decrypt(&self, _key: &[u8; 32], ciphertext: &[u8]) -> Option<Vec<u8>> {
        Some(ciphertext.to_vec())
    }

    fn hkdf(&self, ikm: &[u8], _salt: &[u8], _info: &[u8], length: usize) -> Vec<u8> {
        let mut result = vec![0u8; length];
        let copy_len = ikm.len().min(length);
        result[..copy_len].copy_from_slice(&ikm[..copy_len]);
        result
    }

    fn random_bytes(&self, len: usize) -> Vec<u8> {
        vec![0u8; len]
    }
}