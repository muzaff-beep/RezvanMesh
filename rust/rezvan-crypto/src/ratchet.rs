// src/ratchet.rs

use crate::hkdf;
use crate::x3dh::{CryptoError, SessionState};
use sodiumoxide::crypto::aead::xchacha20poly1305_ietf as aead;
use sodiumoxide::crypto::scalarmult;
use std::collections::HashMap;

const MAX_SKIP: u32 = 1000;

/// Encrypt a plaintext message using the Double Ratchet session state.
/// Returns the ciphertext including header for decryption.
pub fn encrypt(session: &mut SessionState, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError> {
    // Advance sending chain to get message key
    let (message_key, new_chain_key) = kdf_ck(&session.sending_chain_key);
    session.sending_chain_key = new_chain_key;
    let message_number = session.sending_message_number;
    session.sending_message_number += 1;

    let nonce = aead::gen_nonce();
    let ciphertext_body = aead::seal(
        plaintext,
        None,
        &nonce,
        &aead::Key(message_key),
    );

    // Header: flag (always include ratchet public for first message? We'll simplify and always include)
    // In a real implementation, you'd only include when ratchet changes.
    let mut header = Vec::new();
    header.push(0x01); // flag indicating ratchet public present
    header.extend_from_slice(&session.sending_ratchet_public);
    header.extend_from_slice(&message_number.to_be_bytes());
    header.extend_from_slice(nonce.as_ref());

    let mut output = header;
    output.extend_from_slice(&ciphertext_body);
    Ok(output)
}

/// Decrypt a ciphertext produced by `encrypt`.
pub fn decrypt(session: &mut SessionState, ciphertext: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if ciphertext.len() < 1 + 32 + 4 + 24 + 16 {
        return Err(CryptoError::DecryptionFailed);
    }

    let mut offset = 0;
    let flag = ciphertext[offset];
    offset += 1;

    let their_ratchet_public: [u8; 32] = ciphertext[offset..offset+32].try_into().unwrap();
    offset += 32;

    let message_number = u32::from_be_bytes(ciphertext[offset..offset+4].try_into().unwrap());
    offset += 4;

    let nonce = aead::Nonce::from_slice(&ciphertext[offset..offset+24])
        .ok_or(CryptoError::DecryptionFailed)?;
    offset += 24;
    let ciphertext_body = &ciphertext[offset..];

    // If they sent a new ratchet public key, perform DH ratchet
    if session.receiving_ratchet_public != Some(their_ratchet_public) {
        ratchet_root(session, &their_ratchet_public)?;
    }

    // Try to decrypt with current receiving chain or skipped keys
    if message_number < session.receiving_message_number {
        // Look up in skipped keys
        let key = session.skipped_message_keys.remove(&(
            message_number,
            session.receiving_ratchet_public.unwrap_or([0u8; 32]),
        ));
        if let Some(mk) = key {
            return aead::open(ciphertext_body, None, &nonce, &aead::Key(mk))
                .map_err(|_| CryptoError::DecryptionFailed);
        } else {
            return Err(CryptoError::MessageOutOfOrder);
        }
    } else {
        // Advance receiving chain to message_number
        let mut chain_key = session.receiving_chain_key;
        let mut current = session.receiving_message_number;
        while current < message_number {
            let (mk, next_ck) = kdf_ck(&chain_key);
            session.skipped_message_keys.insert(
                (current, session.receiving_ratchet_public.unwrap_or([0u8; 32])),
                mk,
            );
            chain_key = next_ck;
            current += 1;
            if session.skipped_message_keys.len() > MAX_SKIP as usize {
                return Err(CryptoError::MessageOutOfOrder);
            }
        }
        let (message_key, next_chain_key) = kdf_ck(&chain_key);
        session.receiving_chain_key = next_chain_key;
        session.receiving_message_number = message_number + 1;

        aead::open(ciphertext_body, None, &nonce, &aead::Key(message_key))
            .map_err(|_| CryptoError::DecryptionFailed)
    }
}

fn ratchet_root(session: &mut SessionState, their_new_ratchet_public: &[u8; 32]) -> Result<(), CryptoError> {
    let dh_output = scalarmult::scalarmult(
        &scalarmult::Scalar(session.sending_ratchet_private),
        &scalarmult::GroupElement(*their_new_ratchet_public),
    ).0;

    let (new_root_key, new_chain_key) = kdf_rk(&session.root_key, &dh_output);
    session.root_key = new_root_key;
    session.receiving_chain_key = new_chain_key;
    session.receiving_ratchet_public = Some(*their_new_ratchet_public);
    session.receiving_message_number = 0;

    // Generate new sending ratchet key pair
    let (new_sending_public, new_sending_private) = {
        let (pk, sk) = sodiumoxide::crypto::box_::gen_keypair();
        (pk.0, sk.0)
    };
    let dh_output_send = scalarmult::scalarmult(
        &scalarmult::Scalar(new_sending_private),
        &scalarmult::GroupElement(*their_new_ratchet_public),
    ).0;
    let (updated_root, new_sending_chain_key) = kdf_rk(&session.root_key, &dh_output_send);

    session.root_key = updated_root;
    session.sending_ratchet_private = new_sending_private;
    session.sending_ratchet_public = new_sending_public;
    session.sending_chain_key = new_sending_chain_key;
    session.previous_sending_chain_length = session.sending_message_number;
    session.sending_message_number = 0;

    Ok(())
}

fn kdf_rk(root_key: &[u8; 32], dh_output: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let prk = hkdf::hkdf(dh_output, root_key, b"RezvanRatchetRoot", 64);
    let mut rk = [0u8; 32];
    let mut ck = [0u8; 32];
    rk.copy_from_slice(&prk[0..32]);
    ck.copy_from_slice(&prk[32..64]);
    (rk, ck)
}

fn kdf_ck(chain_key: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    use sodiumoxide::crypto::auth::hmacsha256;
    let mk = hmacsha256::authenticate(&[0x01], &hmacsha256::Key(*chain_key)).0;
    let nck = hmacsha256::authenticate(&[0x02], &hmacsha256::Key(*chain_key)).0;
    (mk, nck)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::generate_identity;
    use crate::x3dh::{initiate_x3dh, receive_x3dh};

    #[test]
    fn test_ratchet_roundtrip() {
        sodiumoxide::init().unwrap();
        let alice_id = generate_identity(&[1u8; 32]);
        let bob_id = generate_identity(&[2u8; 32]);
        let (bob_sk, bob_pk) = {
            let (pk, sk) = sodiumoxide::crypto::box_::gen_keypair();
            (sk.0, pk.0)
        };

        let mut alice_session = initiate_x3dh(&alice_id, &bob_id.public_ed25519, &bob_pk, None).unwrap();
        // Bob's session (we'll create a matching one via receive for testing)
        let initiation_bytes = {
            let mut v = Vec::new();
            v.extend_from_slice(&alice_session.sending_ratchet_public);
            v.push(0);
            v
        };
        let mut bob_session = receive_x3dh(&bob_id, &alice_id.public_ed25519, &initiation_bytes).unwrap();

        let plain = b"hello";
        let cipher = encrypt(&mut alice_session, plain).unwrap();
        let decrypted = decrypt(&mut bob_session, &cipher).unwrap();
        assert_eq!(decrypted, plain);
    }
}
