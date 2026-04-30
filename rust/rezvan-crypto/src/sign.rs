// src/sign.rs

use crate::identity::IdentityKeypair;
use sodiumoxide::crypto::sign;

/// Sign a message using the Ed25519 private key from the identity keypair.
/// Returns a 64-byte detached signature.
pub fn sign(identity: &IdentityKeypair, message: &[u8]) -> [u8; 64] {
    let sk = sign::SecretKey(identity.private_ed25519);
    let sig = sign::sign_detached(message, &sk);
    sig.0
}

/// Verify a detached Ed25519 signature against a public key and message.
/// Returns true if the signature is valid.
pub fn verify(public_key: &[u8; 32], message: &[u8], signature: &[u8; 64]) -> bool {
    let pk = sign::PublicKey(*public_key);
    let sig = sign::Signature(*signature);
    sign::verify_detached(&sig, message, &pk)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::generate_identity;

    #[test]
    fn test_sign_and_verify() {
        sodiumoxide::init().unwrap();
        let seed = [0x12; 32];
        let id = generate_identity(&seed);
        let message = b"The quick brown fox jumps over the lazy dog";

        let signature = sign(&id, message);
        assert!(verify(&id.public_ed25519, message, &signature));
    }

    #[test]
    fn test_verify_tampered_message_fails() {
        sodiumoxide::init().unwrap();
        let seed = [0x34; 32];
        let id = generate_identity(&seed);
        let message = b"original message";
        let signature = sign(&id, message);

        let tampered = b"tampered message";
        assert!(!verify(&id.public_ed25519, tampered, &signature));
    }

    #[test]
    fn test_verify_wrong_public_key_fails() {
        sodiumoxide::init().unwrap();
        let seed1 = [1u8; 32];
        let seed2 = [2u8; 32];
        let id1 = generate_identity(&seed1);
        let id2 = generate_identity(&seed2);
        let message = b"test";

        let signature = sign(&id1, message);
        assert!(!verify(&id2.public_ed25519, message, &signature));
    }

    #[test]
    fn test_signature_length() {
        sodiumoxide::init().unwrap();
        let seed = [0x56; 32];
        let id = generate_identity(&seed);
        let message = b"hello";
        let sig = sign(&id, message);
        assert_eq!(sig.len(), 64);
    }
}
