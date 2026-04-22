// src/crypto.rs

/// Cryptographic provider trait. Team B will provide the real implementation.
/// For now, we define the trait and a mock that panics or returns dummy data.
pub trait CryptoProvider: Send + Sync {
    fn generate_identity(seed: &[u8; 32]) -> IdentityKeypair;
    fn sign(identity: &IdentityKeypair, message: &[u8]) -> [u8; 64];
    fn verify(public_key: &[u8; 32], message: &[u8], signature: &[u8; 64]) -> bool;

    fn initiate_x3dh(
        our_identity: &IdentityKeypair,
        their_identity: &[u8; 32],
        their_signed_prekey: &[u8; 32],
        their_one_time_prekey: Option<&[u8; 32]>,
    ) -> Result<SessionState, CryptoError>;

    fn receive_x3dh(
        our_identity: &IdentityKeypair,
        their_identity: &[u8; 32],
        initiation_bytes: &[u8],
    ) -> Result<SessionState, CryptoError>;

    fn ratchet_encrypt(session: &mut SessionState, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError>;
    fn ratchet_decrypt(session: &mut SessionState, ciphertext: &[u8]) -> Result<Vec<u8>, CryptoError>;

    fn generate_sender_key() -> [u8; 32];
    fn sender_key_encrypt(key: &[u8; 32], plaintext: &[u8]) -> Vec<u8>;
    fn sender_key_decrypt(key: &[u8; 32], ciphertext: &[u8]) -> Option<Vec<u8>>;

    fn hkdf(ikm: &[u8], salt: &[u8], info: &[u8], length: usize) -> Vec<u8>;
    fn random_bytes(len: usize) -> Vec<u8>;
}

/// Identity keypair as defined by Team B.
#[derive(Debug, Clone)]
pub struct IdentityKeypair {
    pub public_ed25519: [u8; 32],
    pub private_ed25519: [u8; 64], // libsodium expanded form
    pub public_x25519: [u8; 32],
    pub private_x25519: [u8; 32],
}

/// Session state for Double Ratchet. Opaque to us; we use a placeholder.
#[derive(Debug, Clone)]
pub struct SessionState {
    pub(crate) _placeholder: (),
}

/// Cryptographic errors.
#[derive(Debug)]
pub enum CryptoError {
    HandshakeFailed,
    DecryptionFailed,
    InvalidKey,
    NoSession,
}

/// Pre-key bundle for X3DH initiation.
pub struct PreKeyBundle {
    pub identity_key: [u8; 32],
    pub signed_prekey: [u8; 32],
    pub signed_prekey_signature: [u8; 64],
    pub one_time_prekey: Option<[u8; 32]>,
}

// ---------- Mock Implementation ----------
/// A mock crypto provider that panics on most operations.
/// This allows the engine to compile and be tested with mocked crypto.
pub struct MockCryptoProvider;

impl CryptoProvider for MockCryptoProvider {
    fn generate_identity(seed: &[u8; 32]) -> IdentityKeypair {
        // Dummy keypair derived from seed (not cryptographically secure)
        let mut ed_pub = [0u8; 32];
        let mut ed_priv = [0u8; 64];
        let mut x_pub = [0u8; 32];
        let mut x_priv = [0u8; 32];
        
        // Simple deterministic derivation for mock
        for i in 0..32 {
            ed_pub[i] = seed[i] ^ 0xAA;
            x_pub[i] = seed[i] ^ 0x55;
            x_priv[i] = seed[i];
        }
        for i in 0..64 {
            ed_priv[i] = if i < 32 { seed[i] } else { seed[i - 32] ^ 0xFF };
        }
        
        IdentityKeypair {
            public_ed25519: ed_pub,
            private_ed25519: ed_priv,
            public_x25519: x_pub,
            private_x25519: x_priv,
        }
    }

    fn sign(_identity: &IdentityKeypair, _message: &[u8]) -> [u8; 64] {
        [0u8; 64] // mock signature
    }

    fn verify(_public_key: &[u8; 32], _message: &[u8], _signature: &[u8; 64]) -> bool {
        true // mock always accepts
    }

    fn initiate_x3dh(
        _our_identity: &IdentityKeypair,
        _their_identity: &[u8; 32],
        _their_signed_prekey: &[u8; 32],
        _their_one_time_prekey: Option<&[u8; 32]>,
    ) -> Result<SessionState, CryptoError> {
        Ok(SessionState { _placeholder: () })
    }

    fn receive_x3dh(
        _our_identity: &IdentityKeypair,
        _their_identity: &[u8; 32],
        _initiation_bytes: &[u8],
    ) -> Result<SessionState, CryptoError> {
        Ok(SessionState { _placeholder: () })
    }

    fn ratchet_encrypt(_session: &mut SessionState, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        // Mock encryption: just return plaintext prepended with [0xDE, 0xAD]
        let mut result = vec![0xDE, 0xAD];
        result.extend_from_slice(plaintext);
        Ok(result)
    }

    fn ratchet_decrypt(_session: &mut SessionState, ciphertext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        if ciphertext.len() < 2 {
            return Err(CryptoError::DecryptionFailed);
        }
        Ok(ciphertext[2..].to_vec())
    }

    fn generate_sender_key() -> [u8; 32] {
        [0u8; 32]
    }

    fn sender_key_encrypt(_key: &[u8; 32], plaintext: &[u8]) -> Vec<u8> {
        plaintext.to_vec()
    }

    fn sender_key_decrypt(_key: &[u8; 32], ciphertext: &[u8]) -> Option<Vec<u8>> {
        Some(ciphertext.to_vec())
    }

    fn hkdf(ikm: &[u8], _salt: &[u8], _info: &[u8], length: usize) -> Vec<u8> {
        let mut result = vec![0u8; length];
        let copy_len = std::cmp::min(ikm.len(), length);
        result[..copy_len].copy_from_slice(&ikm[..copy_len]);
        result
    }

    fn random_bytes(len: usize) -> Vec<u8> {
        vec![0x42; len]
    }
          }
