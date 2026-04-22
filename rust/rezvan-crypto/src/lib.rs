// src/lib.rs

pub mod hkdf;
pub mod identity;
pub mod ratchet;
pub mod sender_key;
pub mod sign;
pub mod x3dh;

use identity::IdentityKeypair;
use x3dh::{CryptoError, PreKeyBundle, SessionState};

/// Core cryptographic provider trait for Rezvan Mesh.
/// All crypto operations required by the mesh engine are defined here.
pub trait CryptoProvider: Send + Sync {
    /// Generate a new identity keypair from a 32-byte seed.
    fn generate_identity(seed: &[u8; 32]) -> IdentityKeypair;

    /// Sign a message with the Ed25519 private key.
    fn sign(identity: &IdentityKeypair, message: &[u8]) -> [u8; 64];

    /// Verify an Ed25519 signature.
    fn verify(public_key: &[u8; 32], message: &[u8], signature: &[u8; 64]) -> bool;

    /// Perform X3DH key exchange to establish a Double Ratchet session.
    fn initiate_x3dh(
        our_identity: &IdentityKeypair,
        their_identity: &[u8; 32],
        their_signed_prekey: &[u8; 32],
        their_one_time_prekey: Option<&[u8; 32]>,
    ) -> Result<SessionState, CryptoError>;

    /// Process a received X3DH initiation message.
    fn receive_x3dh(
        our_identity: &IdentityKeypair,
        their_identity: &[u8; 32],
        initiation_bytes: &[u8],
    ) -> Result<SessionState, CryptoError>;

    /// Double Ratchet: encrypt a message.
    fn ratchet_encrypt(session: &mut SessionState, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError>;

    /// Double Ratchet: decrypt a message.
    fn ratchet_decrypt(session: &mut SessionState, ciphertext: &[u8]) -> Result<Vec<u8>, CryptoError>;

    /// Generate a random 32-byte sender key for group messaging.
    fn generate_sender_key() -> [u8; 32];

    /// Encrypt with sender key (symmetric, with random nonce).
    fn sender_key_encrypt(key: &[u8; 32], plaintext: &[u8]) -> Vec<u8>;

    /// Decrypt with sender key.
    fn sender_key_decrypt(key: &[u8; 32], ciphertext: &[u8]) -> Option<Vec<u8>>;

    /// HKDF-SHA256 (RFC 5869).
    fn hkdf(ikm: &[u8], salt: &[u8], info: &[u8], length: usize) -> Vec<u8>;

    /// Generate cryptographically random bytes.
    fn random_bytes(len: usize) -> Vec<u8>;
}

/// Public concrete implementation using libsodium.
pub struct SodiumCryptoProvider;

impl CryptoProvider for SodiumCryptoProvider {
    fn generate_identity(seed: &[u8; 32]) -> IdentityKeypair {
        identity::generate_identity(seed)
    }

    fn sign(identity: &IdentityKeypair, message: &[u8]) -> [u8; 64] {
        sign::sign(identity, message)
    }

    fn verify(public_key: &[u8; 32], message: &[u8], signature: &[u8; 64]) -> bool {
        sign::verify(public_key, message, signature)
    }

    fn initiate_x3dh(
        our_identity: &IdentityKeypair,
        their_identity: &[u8; 32],
        their_signed_prekey: &[u8; 32],
        their_one_time_prekey: Option<&[u8; 32]>,
    ) -> Result<SessionState, CryptoError> {
        x3dh::initiate_x3dh(
            our_identity,
            their_identity,
            their_signed_prekey,
            their_one_time_prekey,
        )
    }

    fn receive_x3dh(
        our_identity: &IdentityKeypair,
        their_identity: &[u8; 32],
        initiation_bytes: &[u8],
    ) -> Result<SessionState, CryptoError> {
        x3dh::receive_x3dh(our_identity, their_identity, initiation_bytes)
    }

    fn ratchet_encrypt(
        session: &mut SessionState,
        plaintext: &[u8],
    ) -> Result<Vec<u8>, CryptoError> {
        ratchet::encrypt(session, plaintext)
    }

    fn ratchet_decrypt(
        session: &mut SessionState,
        ciphertext: &[u8],
    ) -> Result<Vec<u8>, CryptoError> {
        ratchet::decrypt(session, ciphertext)
    }

    fn generate_sender_key() -> [u8; 32] {
        sender_key::generate_key()
    }

    fn sender_key_encrypt(key: &[u8; 32], plaintext: &[u8]) -> Vec<u8> {
        sender_key::encrypt(key, plaintext)
    }

    fn sender_key_decrypt(key: &[u8; 32], ciphertext: &[u8]) -> Option<Vec<u8>> {
        sender_key::decrypt(key, ciphertext)
    }

    fn hkdf(ikm: &[u8], salt: &[u8], info: &[u8], length: usize) -> Vec<u8> {
        hkdf::hkdf(ikm, salt, info, length)
    }

    fn random_bytes(len: usize) -> Vec<u8> {
        sodiumoxide::crypto::generichash::state::randombytes::randombytes(len)
    }
      }
