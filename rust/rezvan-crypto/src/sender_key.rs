use sodiumoxide::crypto::aead::xchacha20poly1305_ietf;

/// Generate a random 32‑byte sender key for group messaging.
pub fn generate() -> [u8; 32] {
    let mut key = [0u8; 32];
    let random_bytes = sodiumoxide::randombytes::randombytes(32);
    key.copy_from_slice(&random_bytes);
    key
}

/// Encrypt plaintext under the given sender key using XChaCha20‑Poly1305.
/// The nonce is randomly generated and prepended to the ciphertext.
pub fn encrypt(key: &[u8; 32], plaintext: &[u8]) -> Vec<u8> {
    let nonce = xchacha20poly1305_ietf::gen_nonce();
    let key = xchacha20poly1305_ietf::Key(*key);
    let ciphertext = xchacha20poly1305_ietf::seal(plaintext, None, &nonce, &key);

    let mut result = Vec::with_capacity(24 + ciphertext.len());
    result.extend_from_slice(&nonce.0);
    result.extend_from_slice(&ciphertext);
    result
}

/// Decrypt ciphertext that was encrypted with `encrypt`.
/// Returns `None` if the ciphertext is too short or authentication fails.
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