use sodiumoxide::crypto::scalarmult;
use sodiumoxide::crypto::sign;
use crate::identity::IdentityKeypair;
use crate::hkdf;

#[derive(Debug, Clone)]
pub struct SessionState {
    pub(crate) root_key: [u8; 32],
    pub(crate) sending_chain_key: [u8; 32],
    pub(crate) receiving_chain_key: [u8; 32],
    pub(crate) sending_ratchet_private: [u8; 32],
    pub(crate) sending_ratchet_public: [u8; 32],
    pub(crate) receiving_ratchet_public: Option<[u8; 32]>,
    pub(crate) sending_message_number: u32,
    pub(crate) receiving_message_number: u32,
    pub(crate) previous_sending_chain_length: u32,
    pub(crate) skipped_message_keys: std::collections::HashMap<(u32, [u8; 32]), [u8; 32]>,
}

#[derive(Debug)]
pub enum CryptoError {
    HandshakeFailed,
    DecryptionFailed,
    InvalidKey,
    NoSession,
    MessageOutOfOrder,
}

fn convert_ed_pub_to_x25519(ed_pk: &[u8; 32]) -> [u8; 32] {
    // FIXME: implement proper Ed25519→X25519 conversion
    let mut x25519 = [0u8; 32];
    x25519.copy_from_slice(ed_pk);
    x25519
}

fn dh(private: &[u8; 32], public: &[u8; 32]) -> Result<[u8; 32], CryptoError> {
    let scalar = scalarmult::Scalar(*private);
    let point = scalarmult::GroupElement(*public);
    Ok(scalarmult::scalarmult(&scalar, &point).unwrap().0)
}

fn generate_ephemeral_keypair() -> ([u8; 32], [u8; 32]) {
    let sk = sodiumoxide::randombytes::randombytes(32);
    let mut private = [0u8; 32];
    private.copy_from_slice(&sk);
    let scalar = scalarmult::Scalar(private);
    let public = scalarmult::scalarmult_base(&scalar).unwrap();
    (private, public.0)
}

fn get_signed_prekey_for_handshake() -> Result<([u8; 32], [u8; 32]), CryptoError> {
    let sk = sodiumoxide::randombytes::randombytes(32);
    let mut private = [0u8; 32];
    private.copy_from_slice(&sk);
    let scalar = scalarmult::Scalar(private);
    let public = scalarmult::scalarmult_base(&scalar).unwrap();
    Ok((private, public.0))
}

pub fn initiate_x3dh(
    our_identity: &IdentityKeypair,
    their_identity: &[u8; 32],
    their_signed_prekey: &[u8; 32],
    their_one_time_prekey: Option<&[u8; 32]>,
) -> Result<SessionState, CryptoError> {
    let (ephemeral_private, ephemeral_public) = generate_ephemeral_keypair();

    let their_id_x25519 = convert_ed_pub_to_x25519(their_identity);

    let dh1 = dh(&our_identity.private_x25519, their_signed_prekey)?;
    let dh2 = dh(&ephemeral_private, &their_id_x25519)?;
    let dh3 = dh(&ephemeral_private, their_signed_prekey)?;

    let mut shared_secret = Vec::new();
    shared_secret.extend_from_slice(&dh1);
    shared_secret.extend_from_slice(&dh2);
    shared_secret.extend_from_slice(&dh3);

    if let Some(opk) = their_one_time_prekey {
        let dh4 = dh(&ephemeral_private, opk)?;
        shared_secret.extend_from_slice(&dh4);
    }

    let sk = hkdf::hkdf_sha256(&shared_secret, &[], b"RezvanX3DH", 32);
    let mut root_key = [0u8; 32];
    root_key.copy_from_slice(&sk);

    let chain_key = hkdf::hkdf_sha256(&[], &root_key, b"RezvanChain", 32);
    let mut sending_chain_key = [0u8; 32];
    sending_chain_key.copy_from_slice(&chain_key);

    Ok(SessionState {
        root_key,
        sending_chain_key,
        receiving_chain_key: [0u8; 32],
        sending_ratchet_private: ephemeral_private,
        sending_ratchet_public: ephemeral_public,
        receiving_ratchet_public: Some(*their_signed_prekey),
        sending_message_number: 0,
        receiving_message_number: 0,
        previous_sending_chain_length: 0,
        skipped_message_keys: std::collections::HashMap::new(),
    })
}

pub fn receive_x3dh(
    our_identity: &IdentityKeypair,
    their_identity: &[u8; 32],
    initiation_bytes: &[u8],
) -> Result<SessionState, CryptoError> {
    if initiation_bytes.len() < 96 {
        return Err(CryptoError::HandshakeFailed);
    }

    let their_ephemeral_public: [u8; 32] = initiation_bytes[0..32].try_into().unwrap();
    let their_id_x25519 = convert_ed_pub_to_x25519(their_identity);

    let (_signed_prekey_private, signed_prekey_public) = get_signed_prekey_for_handshake()?;

    let dh1 = dh(&our_identity.private_x25519, &their_ephemeral_public)?;
    let dh2 = dh(&our_identity.private_x25519, &their_id_x25519)?;
    let dh3 = dh(&our_identity.private_x25519, &their_ephemeral_public)?;

    let mut shared_secret = Vec::new();
    shared_secret.extend_from_slice(&dh1);
    shared_secret.extend_from_slice(&dh2);
    shared_secret.extend_from_slice(&dh3);

    let sk = hkdf::hkdf_sha256(&shared_secret, &[], b"RezvanX3DH", 32);
    let mut root_key = [0u8; 32];
    root_key.copy_from_slice(&sk);

    let chain_key = hkdf::hkdf_sha256(&[], &root_key, b"RezvanChain", 32);
    let mut receiving_chain_key = [0u8; 32];
    receiving_chain_key.copy_from_slice(&chain_key);

    Ok(SessionState {
        root_key,
        sending_chain_key: [0u8; 32],
        receiving_chain_key,
        sending_ratchet_private: *our_identity.private_x25519.as_ref(),
        sending_ratchet_public: our_identity.public_x25519,
        receiving_ratchet_public: Some(their_ephemeral_public),
        sending_message_number: 0,
        receiving_message_number: 0,
        previous_sending_chain_length: 0,
        skipped_message_keys: std::collections::HashMap::new(),
    })
}
