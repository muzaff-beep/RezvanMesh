// src/identity.rs

use sodiumoxide::crypto::{scalarmult, sign};

/// A combined identity keypair containing both Ed25519 (for signing)
/// and X25519 (for key exchange) keys derived from the same seed.
#[derive(Debug, Clone)]
pub struct IdentityKeypair {
    /// Ed25519 public key (32 bytes)
    pub public_ed25519: [u8; 32],
    /// Ed25519 private key in libsodium's expanded form (64 bytes)
    pub private_ed25519: [u8; 64],
    /// X25519 public key (32 bytes)
    pub public_x25519: [u8; 32],
    /// X25519 private key (32 bytes)
    pub private_x25519: [u8; 32],
}

/// Generate an identity keypair from a 32-byte seed.
/// The seed is used directly as the Ed25519 private key seed,
/// and the X25519 private key is derived by clamping the same seed.
pub fn generate_identity(seed: &[u8; 32]) -> IdentityKeypair {
    // Generate Ed25519 keypair from seed.
    let (public_ed25519, private_ed25519) = sign::keypair_from_seed(seed);

    // Derive X25519 private key from the same seed.
    // sodiumoxide's Scalar performs clamping automatically.
    let mut x25519_seed = [0u8; 32];
    x25519_seed.copy_from_slice(&seed[0..32]);
    let private_x25519 = scalarmult::Scalar(x25519_seed);
    let public_x25519 = scalarmult::scalarmult_base(&private_x25519);

    IdentityKeypair {
        public_ed25519: public_ed25519.0,
        private_ed25519: private_ed25519.0,
        public_x25519: public_x25519.0,
        private_x25519: private_x25519.0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sodiumoxide::crypto::sign;

    #[test]
    fn test_generate_identity_deterministic() {
        sodiumoxide::init().unwrap();
        let seed = [42u8; 32];
        let id1 = generate_identity(&seed);
        let id2 = generate_identity(&seed);
        assert_eq!(id1.public_ed25519, id2.public_ed25519);
        assert_eq!(id1.private_ed25519, id2.private_ed25519);
        assert_eq!(id1.public_x25519, id2.public_x25519);
        assert_eq!(id1.private_x25519, id2.private_x25519);
    }

    #[test]
    fn test_signature_with_identity() {
        sodiumoxide::init().unwrap();
        let seed = [1u8; 32];
        let id = generate_identity(&seed);
        let message = b"test message";
        let sig = sign::sign_detached(message, &sign::SecretKey(id.private_ed25519));
        let verified = sign::verify_detached(&sig, message, &sign::PublicKey(id.public_ed25519));
        assert!(verified);
    }

    #[test]
    fn test_x25519_derivation() {
        sodiumoxide::init().unwrap();
        let seed = [2u8; 32];
        let id = generate_identity(&seed);
        assert_ne!(id.public_x25519, [0u8; 32]);
        assert_ne!(id.private_x25519, [0u8; 32]);
    }
}
