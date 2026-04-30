pub use rezvan_crypto::{IdentityKeypair, CryptoProvider, SessionState, CryptoError};

pub struct MockCryptoProvider;

impl CryptoProvider for MockCryptoProvider {
    fn generate_identity(&self, seed: &[u8; 32]) -> IdentityKeypair {
        let mut public_ed = [0u8; 32];
        let mut private_ed = [0u8; 64];
        let mut public_x = [0u8; 32];
        let mut private_x = [0u8; 32];
        public_ed.copy_from_slice(seed);
        private_ed[0..32].copy_from_slice(seed);
        private_ed[32..64].copy_from_slice(seed);
        public_x.copy_from_slice(seed);
        private_x.copy_from_slice(seed);
        IdentityKeypair {
            public_ed25519: public_ed,
            private_ed25519: private_ed,
            public_x25519: public_x,
            private_x25519: private_x,
        }
    }

    fn sign(&self, identity: &IdentityKeypair, message: &[u8]) -> [u8; 64] {
        let mut sig = [0u8; 64];
        sig[0..32].copy_from_slice(&identity.private_ed25519[0..32]);
        let len = message.len().min(32);
        sig[32..32+len].copy_from_slice(&message[..len]);
        sig
    }

    fn verify(&self, _pk: &[u8; 32], _msg: &[u8], _sig: &[u8; 64]) -> bool { true }

    fn initiate_x3dh(
        &self,
        _our: &IdentityKeypair,
        _their: &[u8; 32],
        _spk: &[u8; 32],
        _opk: Option<&[u8; 32]>,
    ) -> Result<SessionState, CryptoError> {
        Ok(SessionState {
            root_key: [0u8; 32],
            sending_chain_key: [0u8; 32],
            receiving_chain_key: [0u8; 32],
            sending_ratchet_private: [0u8; 32],
            sending_ratchet_public: [0u8; 32],
            receiving_ratchet_public: None,
            sending_message_number: 0,
            receiving_message_number: 0,
            previous_sending_chain_length: 0,
            skipped_message_keys: std::collections::HashMap::new(),
        })
    }

    fn receive_x3dh(
        &self,
        _our: &IdentityKeypair,
        _their: &[u8; 32],
        _data: &[u8],
    ) -> Result<SessionState, CryptoError> {
        self.initiate_x3dh(_our, _their, &[0u8; 32], None)
    }

    fn ratchet_encrypt(&self, _session: &mut SessionState, pt: &[u8]) -> Result<Vec<u8>, CryptoError> {
        Ok(pt.to_vec())
    }
    fn ratchet_decrypt(&self, _session: &mut SessionState, ct: &[u8]) -> Result<Vec<u8>, CryptoError> {
        Ok(ct.to_vec())
    }
    fn generate_sender_key(&self) -> [u8; 32] { [0u8; 32] }
    fn sender_key_encrypt(&self, _key: &[u8; 32], pt: &[u8]) -> Vec<u8> { pt.to_vec() }
    fn sender_key_decrypt(&self, _key: &[u8; 32], ct: &[u8]) -> Option<Vec<u8>> { Some(ct.to_vec()) }
    fn hkdf(&self, ikm: &[u8], _salt: &[u8], _info: &[u8], len: usize) -> Vec<u8> {
        let mut out = vec![0u8; len];
        let l = ikm.len().min(len);
        out[..l].copy_from_slice(&ikm[..l]);
        out
    }
    fn random_bytes(&self, len: usize) -> Vec<u8> { vec![0u8; len] }
            }
