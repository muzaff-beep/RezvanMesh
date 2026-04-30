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
    fn initiate_x3dh(&self, our: &IdentityKeypair, their: &[u8; 32], spk: &[u8; 32], opk: Option<&[u8; 32]>) -> Result<SessionState, CryptoError>;
    fn receive_x3dh(&self, our: &IdentityKeypair, their: &[u8; 32], data: &[u8]) -> Result<SessionState, CryptoError>;
    fn ratchet_encrypt(&self, s: &mut SessionState, pt: &[u8]) -> Result<Vec<u8>, CryptoError>;
    fn ratchet_decrypt(&self, s: &mut SessionState, ct: &[u8]) -> Result<Vec<u8>, CryptoError>;
    fn generate_sender_key(&self) -> [u8; 32];
    fn sender_key_encrypt(&self, key: &[u8; 32], pt: &[u8]) -> Vec<u8>;
    fn sender_key_decrypt(&self, key: &[u8; 32], ct: &[u8]) -> Option<Vec<u8>>;
    fn hkdf(&self, ikm: &[u8], salt: &[u8], info: &[u8], len: usize) -> Vec<u8>;
    fn random_bytes(&self, len: usize) -> Vec<u8>;
}

pub struct SodiumCryptoProvider;

impl CryptoProvider for SodiumCryptoProvider {
    fn generate_identity(&self, seed: &[u8; 32]) -> IdentityKeypair { identity::generate_identity(seed) }
    fn sign(&self, i: &IdentityKeypair, m: &[u8]) -> [u8; 64] { sign::sign(i, m) }
    fn verify(&self, pk: &[u8; 32], m: &[u8], s: &[u8; 64]) -> bool { sign::verify(pk, m, s) }
    fn initiate_x3dh(&self, our: &IdentityKeypair, their: &[u8; 32], spk: &[u8; 32], opk: Option<&[u8; 32]>) -> Result<SessionState, CryptoError> { x3dh::initiate_x3dh(our, their, spk, opk) }
    fn receive_x3dh(&self, our: &IdentityKeypair, their: &[u8; 32], data: &[u8]) -> Result<SessionState, CryptoError> { x3dh::receive_x3dh(our, their, data) }
    fn ratchet_encrypt(&self, s: &mut SessionState, pt: &[u8]) -> Result<Vec<u8>, CryptoError> { ratchet::ratchet_encrypt(s, pt) }
    fn ratchet_decrypt(&self, s: &mut SessionState, ct: &[u8]) -> Result<Vec<u8>, CryptoError> { ratchet::ratchet_decrypt(s, ct) }
    fn generate_sender_key(&self) -> [u8; 32] { sender_key::generate() }
    fn sender_key_encrypt(&self, key: &[u8; 32], pt: &[u8]) -> Vec<u8> { sender_key::encrypt(key, pt) }
    fn sender_key_decrypt(&self, key: &[u8; 32], ct: &[u8]) -> Option<Vec<u8>> { sender_key::decrypt(key, ct) }
    fn hkdf(&self, ikm: &[u8], salt: &[u8], info: &[u8], len: usize) -> Vec<u8> { hkdf::hkdf_sha256(ikm, salt, info, len) }
    fn random_bytes(&self, len: usize) -> Vec<u8> { sodiumoxide::randombytes::randombytes(len) }
}