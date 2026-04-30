// src/x3dh.rs

use crate::hkdf;
use crate::identity::IdentityKeypair;
use sodiumoxide::crypto::{scalarmult, sign};
use std::collections::HashMap;
use thiserror::Error;

/// Errors that can occur during cryptographic operations.
#[derive(Debug, Error)]
pub enum CryptoError {
    #[error("Handshake failed: invalid data")]
    HandshakeFailed,
    #[error("Decryption failed: authentication tag mismatch")]
    DecryptionFailed,
    #[error("Invalid key material")]
    InvalidKey,
    #[error("No session established with this peer")]
    NoSession,
    #[error("Message out of order and cannot be decrypted")]
    MessageOutOfOrder,
}

/// A pre-key bundle published by a user for X3DH handshake initiation.
#[derive(Debug, Clone)]
pub struct PreKeyBundle {
    /// Identity public key (Ed25519, will be converted to X25519 for DH)
    pub identity_key: [u8; 32],
    /// Signed pre-key (X25519 public key)
    pub signed_prekey: [u8; 32],
    /// Signature of the signed pre-key using the identity Ed25519 private key
    pub signed_prekey_signature: [u8; 64],
    /// Optional one-time pre-key (X25519 public key)
    pub one_time_prekey: Option<[u8; 32]>,
}

/// Internal state for a Double Ratchet session.
/// This is treated as opaque by Team A.
#[derive(Debug, Clone)]
pub struct SessionState {
    /// Root key for the ratchet (32 bytes)
    pub(crate) root_key: [u8; 32],
    /// Sending chain key (32 bytes)
    pub(crate) sending_chain_key: [u8; 32],
    /// Receiving chain key (32 bytes)
    pub(crate) receiving_chain_key: [u8; 32],
    /// Our current sending ratchet private key (X25519)
    pub(crate) sending_ratchet_private: [u8; 32],
    /// Our current sending ratchet public key (X25519)
    pub(crate) sending_ratchet_public: [u8; 32],
    /// The other party's current ratchet public key (if we have received one)
    pub(crate) receiving_ratchet_public: Option<[u8; 32]>,
    /// Message number for sending chain
    pub(crate) sending_message_number: u32,
    /// Message number for receiving chain
    pub(crate) receiving_message_number: u32,
    /// Number of messages sent in the previous sending chain
    pub(crate) previous_sending_chain_length: u32,
    /// Skipped message keys for out-of-order decryption
    pub(crate) skipped_message_keys: HashMap<(u32, [u8; 32]), [u8; 32]>,
}

impl SessionState {
    /// Create a new session state from the computed shared secret after X3DH.
    pub(crate) fn new(
        shared_secret: [u8; 32],
        our_ratchet_private: [u8; 32],
        our_ratchet_public: [u8; 32],
    ) -> Self {
        SessionState {
            root_key: shared_secret,
            sending_chain_key: shared_secret, // Placeholder; will be ratcheted before use
            receiving_chain_key: [0u8; 32],
            sending_ratchet_private: our_ratchet_private,
            sending_ratchet_public: our_ratchet_public,
            receiving_ratchet_public: None,
            sending_message_number: 0,
            receiving_message_number: 0,
            previous_sending_chain_length: 0,
            skipped_message_keys: HashMap::new(),
        }
    }
}

/// X3DH initiation as the initiator (Alice).
/// Returns a SessionState (initiation bytes are currently not returned but could be added).
pub fn initiate_x3dh(
    our_identity: &IdentityKeypair,
    their_identity: &[u8; 32],
    their_signed_prekey: &[u8; 32],
    their_one_time_prekey: Option<&[u8; 32]>,
) -> Result<SessionState, CryptoError> {
    // 1. Generate ephemeral key pair (X25519)
    let (ephemeral_public, ephemeral_private) = {
        let (pk, sk) = sodiumoxide::crypto::box_::gen_keypair();
        (pk.0, sk.0)
    };

    // 2. Convert Ed25519 identity keys to X25519 for DH
    let their_identity_x25519 = convert_ed25519_pub_to_x25519(their_identity)?;

    // 3. Perform DH calculations
    let dh1 = scalarmult(&our_identity.private_x25519, their_signed_prekey)?;
    let dh2 = scalarmult(&ephemeral_private, &their_identity_x25519)?;
    let dh3 = scalarmult(&ephemeral_private, their_signed_prekey)?;
    let dh4 = if let Some(otpk) = their_one_time_prekey {
        scalarmult(&ephemeral_private, otpk)?
    } else {
        [0u8; 32]
    };

    // 4. Concatenate DH results: DH1 || DH2 || DH3 || DH4
    let mut dh_concat = Vec::with_capacity(128);
    dh_concat.extend_from_slice(&dh1);
    dh_concat.extend_from_slice(&dh2);
    dh_concat.extend_from_slice(&dh3);
    dh_concat.extend_from_slice(&dh4);

    // 5. Derive shared secret SK via HKDF
    let sk = hkdf::hkdf(&dh_concat, &[0u8; 32], b"RezvanX3DH", 32);
    let sk_array: [u8; 32] = sk.try_into().map_err(|_| CryptoError::HandshakeFailed)?;

    Ok(SessionState::new(
        sk_array,
        ephemeral_private,
        ephemeral_public,
    ))
}

/// Process an X3DH initiation message as the responder (Bob).
/// `initiation_bytes` format: 32 bytes ephemeral public key, followed by 1 byte indicating if one-time prekey was used.
/// This implementation assumes the responder has access to their signed prekey private key
/// and one-time prekey private key (passed via a store; here we accept them as parameters via a global).
pub fn receive_x3dh(
    our_identity: &IdentityKeypair,
    their_identity: &[u8; 32],
    initiation_bytes: &[u8],
) -> Result<SessionState, CryptoError> {
    if initiation_bytes.len() != 33 {
        return Err(CryptoError::HandshakeFailed);
    }
    let ephemeral_public: [u8; 32] = initiation_bytes[0..32].try_into().unwrap();
    let otpk_used = initiation_bytes[32];

    let their_identity_x25519 = convert_ed25519_pub_to_x25519(their_identity)?;

    // In a real implementation, the responder would fetch their prekey private keys from a store.
    // For this library, we assume they are provided by the caller via some means.
    // Since we don't have that context, we will use placeholder retrieval functions that panic.
    // Team A will replace this with actual key store integration.
    let (signed_prekey_private, signed_prekey_public) = get_signed_prekey_for_handshake()?;
    let one_time_prekey_private = if otpk_used != 0 {
        Some(get_one_time_prekey_for_handshake()?)
    } else {
        None
    };

    // DH calculations (mirroring initiator)
    let dh1 = scalarmult(&signed_prekey_private, &their_identity_x25519)?;
    let dh2 = scalarmult(&our_identity.private_x25519, &ephemeral_public)?;
    let dh3 = scalarmult(&signed_prekey_private, &ephemeral_public)?;
    let dh4 = if let Some(otpk_priv) = one_time_prekey_private {
        scalarmult(&otpk_priv, &ephemeral_public)?
    } else {
        [0u8; 32]
    };

    let mut dh_concat = Vec::with_capacity(128);
    dh_concat.extend_from_slice(&dh1);
    dh_concat.extend_from_slice(&dh2);
    dh_concat.extend_from_slice(&dh3);
    dh_concat.extend_from_slice(&dh4);

    let sk = hkdf::hkdf(&dh_concat, &[0u8; 32], b"RezvanX3DH", 32);
    let sk_array: [u8; 32] = sk.try_into().map_err(|_| CryptoError::HandshakeFailed)?;

    // Bob's initial sending ratchet is not yet set; it will be generated on first message.
    // Receiving ratchet public is set to the ephemeral key from Alice.
    let mut session = SessionState::new(sk_array, [0u8; 32], [0u8; 32]);
    session.receiving_ratchet_public = Some(ephemeral_public);
    session.receiving_chain_key = sk_array;

    Ok(session)
}

// Helper: Convert Ed25519 public key to X25519 public key
fn convert_ed25519_pub_to_x25519(ed_pub: &[u8; 32]) -> Result<[u8; 32], CryptoError> {
    let ed_pk = sign::PublicKey(*ed_pub);
    scalarmult::ed25519_pk_to_curve25519(&ed_pk)
        .map(|xpk| xpk.0)
        .ok_or(CryptoError::InvalidKey)
}

// Helper: Perform X25519 scalar multiplication
fn scalarmult(our_private: &[u8; 32], their_public: &[u8; 32]) -> Result<[u8; 32], CryptoError> {
    let sk = scalarmult::Scalar(*our_private);
    let pk = scalarmult::GroupElement(*their_public);
    let shared = scalarmult::scalarmult(&sk, &pk);
    Ok(shared.0)
}

// Placeholder functions for prekey retrieval; Team A will replace with actual store.
fn get_signed_prekey_for_handshake() -> Result<([u8; 32], [u8; 32]), CryptoError> {
    // Dummy implementation for testing only.
    let (pk, sk) = sodiumoxide::crypto::box_::gen_keypair();
    Ok((sk.0, pk.0))
}

fn get_one_time_prekey_for_handshake() -> Result<[u8; 32], CryptoError> {
    let (_, sk) = sodiumoxide::crypto::box_::gen_keypair();
    Ok(sk.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::generate_identity;

    #[test]
    fn test_x3dh_handshake_both_sides() {
        sodiumoxide::init().unwrap();

        let alice_id = generate_identity(&[1u8; 32]);
        let bob_id = generate_identity(&[2u8; 32]);

        // Bob's signed prekey
        let (bob_signed_priv, bob_signed_pub) = {
            let (pk, sk) = sodiumoxide::crypto::box_::gen_keypair();
            (sk.0, pk.0)
        };

        // Alice initiates
        let alice_session = initiate_x3dh(
            &alice_id,
            &bob_id.public_ed25519,
            &bob_signed_pub,
            None,
        )
        .expect("Alice initiation failed");

        // Bob receives (mock initiation bytes)
        let initiation_bytes = {
            let mut v = Vec::new();
            v.extend_from_slice(&alice_session.sending_ratchet_public);
            v.push(0);
            v
        };
        let bob_session = receive_x3dh(&bob_id, &alice_id.public_ed25519, &initiation_bytes)
            .expect("Bob receive failed");

        assert_eq!(alice_session.root_key, bob_session.root_key);
    }
  }
