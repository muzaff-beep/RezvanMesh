use sodiumoxide::crypto::aead::xchacha20poly1305_ietf;
use sodiumoxide::crypto::scalarmult;
use crate::hkdf;
use crate::x3dh::{SessionState, CryptoError};

const MAX_SKIPPED: u32 = 100;

fn dh_ratchet(private: &[u8; 32], public: &[u8; 32]) -> Result<[u8; 32], CryptoError> {
    let scalar = scalarmult::Scalar(*private);
    let point = scalarmult::GroupElement(*public);
    Ok(scalarmult::scalarmult(&scalar, &point).unwrap().0)
}

fn kdf_ck(ck: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let mut mk = [0u8; 32];
    let mut next_ck = [0u8; 32];
    let output = hkdf::hkdf_sha256(ck, &[], b"RezvanRatchet", 64);
    mk.copy_from_slice(&output[0..32]);
    next_ck.copy_from_slice(&output[32..64]);
    (mk, next_ck)
}

fn generate_ephemeral_ratchet_keypair() -> ([u8; 32], [u8; 32]) {
    let sk = sodiumoxide::randombytes::randombytes(32);
    let mut private = [0u8; 32];
    private.copy_from_slice(&sk);
    let scalar = scalarmult::Scalar(private);
    let public = scalarmult::scalarmult_base(&scalar);
    (private, public.0)
}

pub fn ratchet_encrypt(
    session: &mut SessionState,
    plaintext: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    let (mk, next_ck) = kdf_ck(&session.sending_chain_key);
    session.sending_chain_key = next_ck;
    session.sending_message_number += 1;

    let nonce = xchacha20poly1305_ietf::gen_nonce();
    let key = xchacha20poly1305_ietf::Key(mk);
    let ciphertext = xchacha20poly1305_ietf::seal(plaintext, None, &nonce, &key);

    let mut message = Vec::new();
    message.extend_from_slice(&session.sending_ratchet_public);
    message.extend_from_slice(&session.sending_message_number.to_be_bytes());
    message.extend_from_slice(&nonce.0);
    message.extend_from_slice(&ciphertext);

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

pub fn ratchet_decrypt(
    session: &mut SessionState,
    ciphertext: &[u8],
) -> Result<Vec<u8>, CryptoError> {
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

    let need_ratchet = session.receiving_ratchet_public.map_or(true, |pk| pk != their_ratchet_public);

    if need_ratchet {
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

        let dh_output = dh_ratchet(&session.sending_ratchet_private, &their_ratchet_public)?;
        let mut combined = Vec::new();
        combined.extend_from_slice(&dh_output);
        combined.extend_from_slice(&session.root_key);
        let new_root = hkdf::hkdf_sha256(&combined, &[], b"RezvanRootUpdate", 32);
        session.root_key.copy_from_slice(&new_root);

        let (new_private, new_public) = generate_ephemeral_ratchet_keypair();
        session.sending_ratchet_private = new_private;
        session.sending_ratchet_public = new_public;

        let chain_input = hkdf::hkdf_sha256(&[], &session.root_key, b"RezvanChain", 32);
        session.receiving_chain_key.copy_from_slice(&chain_input);
        session.receiving_ratchet_public = Some(their_ratchet_public);
    }

    let (mk, next_ck) = kdf_ck(&session.receiving_chain_key);
    session.receiving_chain_key = next_ck;
    session.receiving_message_number += 1;

    let key = xchacha20poly1305_ietf::Key(mk);

    match xchacha20poly1305_ietf::open(encrypted, None, &nonce, &key) {
        Ok(plaintext) => Ok(plaintext),
        Err(_) => Err(CryptoError::DecryptionFailed),
    }
            }
