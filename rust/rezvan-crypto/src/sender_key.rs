use sodiumoxide::crypto::aead::xchacha20poly1305_ietf;
use sodiumoxide::crypto::secretbox;

pub fn generate() -> [u8; 32] {
    let key = secretbox::gen_key();
    let mut result = [0u8; 32];
    result.copy_from_slice(&key.0);
    result
}

pub fn encrypt(key: &[u8; 32], plaintext: &[u8]) -> Vec<u8> {
    let nonce = xchacha20poly1305_ietf::gen_nonce();
    let key = xchacha20poly1305_ietf::Key(*key);
    let ciphertext = xchacha20poly1305_ietf::seal(plaintext, None, &nonce, &key);
    let mut result = Vec::new();
    result.extend_from_slice(&nonce.0);
    result.extend_from_slice(&ciphertext);
    result
}

pub fn decrypt(key: &[u8; 32], ciphertext: &[u8]) -> Option<Vec<u8>> {
    if ciphertext.len() < 24 {
        return None;
    }
    let nonce_bytes: [u8; 24] = ciphertext[0..24].try_into().unwrap();
    let nonce = xchacha20poly1305_ietf::Nonce(nonce_bytes);
    let encrypted = &ciphertext[24..];
    let key = xchacha20poly1305_ietf::Key(*key);
    xchacha20poly1305_ietf::open(encrypted, None, &nonce, &key).ok()
}
