pub mod identity;
pub mod sign;
pub mod x3dh;
pub mod ratchet;
pub mod sender_key;
pub mod hkdf;

pub use identity::IdentityKeypair;
pub use x3dh::{CryptoError, SessionState};

pub trait CryptoProvider: Send + Sync {
    fn generate_identity(&self, seed: &[u8; 32]) -> IdentityKeypair;
    fn sign(&self, identity: &IdentityKeypair, message: &[u8]) -> [u8; 64];
    fn verify(&self, public_key: &[u8; 32], message: &[u8], signature: &[u8; 64]) -> bool;

    fn initiate_x3dh(
        &self,
        our_identity: &IdentityKeypair,
        their_identity: &[u8; 32],
        their_signed_prekey: &[u8; 32],
        their_one_time_prekey: Option<&[u8; 32]>,
    ) -> Result<SessionState, CryptoError>;

    fn receive_x3dh(
        &self,
        our_identity: &IdentityKeypair,
        their_identity: &[u8; 32],
        initiation_bytes: &[u8],
    ) -> Result<SessionState, CryptoError>;

    fn ratchet_encrypt(&self, session: &mut SessionState, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError>;
    fn ratchet_decrypt(&self, session: &mut SessionState, ciphertext: &[u8]) -> Result<Vec<u8>, CryptoError>;

    fn generate_sender_key(&self) -> [u8; 32];
    fn sender_key_encrypt(&self, key: &[u8; 32], plaintext: &[u8]) -> Vec<u8>;
    fn sender_key_decrypt(&self, key: &[u8; 32], ciphertext: &[u8]) -> Option<Vec<u8>>;

    fn hkdf(&self, ikm: &[u8], salt: &[u8], info: &[u8], length: usize) -> Vec<u8>;
    fn random_bytes(&self, len: usize) -> Vec<u8>;
}

pub struct SodiumCryptoProvider;

impl CryptoProvider for SodiumCryptoProvider {
    fn generate_identity(&self, seed: &[u8; 32]) -> IdentityKeypair {
        identity::generate_identity(seed)
    }
    fn sign(&self, identity: &IdentityKeypair, message: &[u8]) -> [u8; 64] {
        sign::sign(identity, message)
    }
    fn verify(&self, public_key: &[u8; 32], message: &[u8], signature: &[u8; 64]) -> bool {
        sign::verify(public_key, message, signature)
    }
    fn initiate_x3dh(
        &self,
        our_identity: &IdentityKeypair,
        their_identity: &[u8; 32],
        their_signed_prekey: &[u8; 32],
        their_one_time_prekey: Option<&[u8; 32]>,
    ) -> Result<SessionState, CryptoError> {
        x3dh::initiate_x3dh(our_identity, their_identity, their_signed_prekey, their_one_time_prekey)
    }
    fn receive_x3dh(
        &self,
        our_identity: &IdentityKeypair,
        their_identity: &[u8; 32],
        initiation_bytes: &[u8],
    ) -> Result<SessionState, CryptoError> {
        x3dh::receive_x3dh(our_identity, their_identity, initiation_bytes)
    }
    fn ratchet_encrypt(&self, session: &mut SessionState, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        ratchet::ratchet_encrypt(session, plaintext)
    }
    fn ratchet_decrypt(&self, session: &mut SessionState, ciphertext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        ratchet::ratchet_decrypt(session, ciphertext)
    }
    fn generate_sender_key(&self) -> [u8; 32] {
        sender_key::generate()
    }
    fn sender_key_encrypt(&self, key: &[u8; 32], plaintext: &[u8]) -> Vec<u8> {
        sender_key::encrypt(key, plaintext)
    }
    fn sender_key_decrypt(&self, key: &[u8; 32], ciphertext: &[u8]) -> Option<Vec<u8>> {
        sender_key::decrypt(key, ciphertext)
    }
    fn hkdf(&self, ikm: &[u8], salt: &[u8], info: &[u8], length: usize) -> Vec<u8> {
        hkdf::hkdf_sha256(ikm, salt, info, length)
    }
    fn random_bytes(&self, len: usize) -> Vec<u8> {
        sodiumoxide::randombytes::randombytes(len)
    }
}
