use sodiumoxide::crypto::aead::xchacha20poly1305_ietf;
use sodiumoxide::crypto::scalarmult;
use crate::hkdf;
use crate::x3dh::{SessionState, CryptoError};

const MAX_SKIPPED: u32 = 100;

// ---- helper: Diffie-Hellman (already returns Result<GroupElement, ()> in 0.2.7) ----

fn dh_ratchet(private: &[u8; 32], public: &[u8; 32]) -> Result<[u8; 32], CryptoError> {
    let scalar = scalarmult::Scalar(*private);
    let point = scalarmult::GroupElement(*public);
    Ok(scalarmult::scalarmult(&scalar, &point).unwrap().0)
}

// ---- KDF_CK: derive Message Key and next Chain Key ----

fn kdf_ck(ck: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let mut mk = [0u8; 32];
    let mut next_ck = [0u8; 32];
    let output = hkdf::hkdf_sha256(ck, &[], b"RezvanRatchet", 64);
    mk.copy_from_slice(&output[0..32]);
    next_ck.copy_from_slice(&output[32..64]);
    (mk, next_ck)
}

// ---- generate a new ephemeral ratchet key pair ----

fn generate_ephemeral_ratchet_keypair() -> ([u8; 32], [u8; 32]) {
    let sk = sodiumoxide::randombytes::randombytes(32);
    let mut private = [0u8; 32];
    private.copy_from_slice(&sk);
    let scalar = scalarmult::Scalar(private);
    let public = scalarmult::scalarmult_base(&scalar);
    (private, public.0)
}

// ---- symmetric‑ratchet encrypt ----

pub fn ratchet_encrypt(
    session: &mut SessionState,
    plaintext: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    // Advance sending chain
    let (mk, next_ck) = kdf_ck(&session.sending_chain_key);
    session.sending_chain_key = next_ck;
    session.sending_message_number += 1;

    // Encrypt with XChaCha20‑Poly1305
    let nonce = xchacha20poly1305_ietf::gen_nonce();
    let key = xchacha20poly1305_ietf::Key(mk);
    let ciphertext = xchacha20poly1305_ietf::seal(plaintext, None, &nonce, &key);

    // Build the message: [ratchet_public (32)][message_number (4)][nonce (24)][ciphertext]
    let mut message = Vec::new();
    message.extend_from_slice(&session.sending_ratchet_public);
    message.extend_from_slice(&session.sending_message_number.to_be_bytes());
    message.extend_from_slice(&nonce.0);
    message.extend_from_slice(&ciphertext);

    // Perform a DH ratchet step (generate new sending key pair)
    let (new_private, new_public) = generate_ephemeral_ratchet_keypair();
    if let Some(recv_public) = session.receiving_ratchet_public.take() {
        let dh_output = dh_ratchet(&session.sending_ratchet_private, &recv_public)?;
        let mut combined = Vec::new();
        combined.extend_from_slice(&dh_output);
        combined.extend_from_slice(&session.root_key);
        let new_root = hkdf::hkdf_sha256(&combined, &[], b"RezvanRootUpdate", 32);
        session.root_key.copy_from_slice(&new_root);

        let chain_input = hkdf::hkdf_sha256(&[], &session.root_key, b"RezvanChain", 32);
        session.sending_chain_key.copy_from_slice(&chain_input);
    }

    session.sending_ratchet_private = new_private;
    session.sending_ratchet_public = new_public;

    Ok(message)
}

// ---- symmetric‑ratchet decrypt ----

pub fn ratchet_decrypt(
    session: &mut SessionState,
    ciphertext: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    // Message must contain at least: ratchet_public(32) + msg_num(4) + nonce(24) + tag(16)
    if ciphertext.len() < 88 {
        return Err(CryptoError::DecryptionFailed);
    }

    let their_ratchet_public: [u8; 32] = ciphertext[0..32].try_into().unwrap();
    let mut msg_num_bytes = [0u8; 4];
    msg_num_bytes.copy_from_slice(&ciphertext[32..36]);
    let _msg_number = u32::from_be_bytes(msg_num_bytes);
    let nonce_bytes: [u8; 24] = ciphertext[36..60].try_into().unwrap();
    let nonce = xchacha20poly1305_ietf::Nonce(nonce_bytes);
    let encrypted = &ciphertext[60..];

    // Determine if the sender has ratcheted (their public key changed)
    let need_ratchet = session.receiving_ratchet_public.map_or(true, |pk| pk != their_ratchet_public);

    if need_ratchet {
        // Use the old receiving ratchet key (if any) to update the root key
        if let Some(recv_public) = session.receiving_ratchet_public {
            let dh_output = dh_ratchet(&session.sending_ratchet_private, &recv_public)?;
            let mut combined = Vec::new();
            combined.extend_from_slice(&dh_output);
            combined.extend_from_slice(&session.root_key);
            let new_root = hkdf::hkdf_sha256(&combined, &[], b"RezvanRootUpdate", 32);
            session.root_key.copy_from_slice(&new_root);

            let chain_input = hkdf::hkdf_sha256(&[], &session.root_key, b"RezvanChain", 32);
            session.receiving_chain_key.copy_from_slice(&chain_input);
        }

        // Update root key using the new ratchet public key
        let dh_output = dh_ratchet(&session.sending_ratchet_private, &their_ratchet_public)?;
        let mut combined = Vec::new();
        combined.extend_from_slice(&dh_output);
        combined.extend_from_slice(&session.root_key);
        let new_root = hkdf::hkdf_sha256(&combined, &[], b"RezvanRootUpdate", 32);
        session.root_key.copy_from_slice(&new_root);

        // Generate a new sending ratchet key pair
        let (new_private, new_public) = generate_ephemeral_ratchet_keypair();
        session.sending_ratchet_private = new_private;
        session.sending_ratchet_public = new_public;

        // Re-derive the receiving chain key for the new DH epoch
        let chain_input = hkdf::hkdf_sha256(&[], &session.root_key, b"RezvanChain", 32);
        session.receiving_chain_key.copy_from_slice(&chain_input);
        session.receiving_ratchet_public = Some(their_ratchet_public);
    }

    // Advance the receiving chain
    let (mk, next_ck) = kdf_ck(&session.receiving_chain_key);
    session.receiving_chain_key = next_ck;
    session.receiving_message_number += 1;

    // Decrypt
    let key = xchacha20poly1305_ietf::Key(mk);
    match xchacha20poly1305_ietf::open(encrypted, None, &nonce, &key) {
        Ok(plaintext) => Ok(plaintext),
        Err(_) => Err(CryptoError::DecryptionFailed),
    }
}