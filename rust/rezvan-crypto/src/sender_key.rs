// src/sender_key.rs

use sodiumoxide::crypto::aead::xchacha20poly1305_ietf as aead;

/// Generate a random 32-byte sender key for group messaging.
pub fn generate_key() -> [u8; 32] {
    aead::gen_key().0
}

/// Encrypt a plaintext using the sender key.
/// A random nonce is generated and prepended to the ciphertext.
/// Format: [24-byte nonce][ciphertext + tag]
pub fn encrypt(key: &[u8; 32], plaintext: &[u8]) -> Vec<u8> {
    let key = aead::Key(*key);
    let nonce = aead::gen_nonce();
    let ciphertext = aead::seal(plaintext, None, &nonce, &key);
    [nonce.as_ref(), &ciphertext].concat()
}

/// Decrypt a ciphertext produced by `encrypt`.
/// Returns `Some(plaintext)` on success, or `None` if decryption fails.
pub fn decrypt(key: &[u8; 32], ciphertext: &[u8]) -> Option<Vec<u8>> {
    if ciphertext.len() < aead::NONCEBYTES + aead::TAGBYTES {
        return None;
    }
    let (nonce_bytes, encrypted) = ciphertext.split_at(aead::NONCEBYTES);
    let nonce = aead::Nonce::from_slice(nonce_bytes)?;
    let key = aead::Key(*key);
    aead::open(encrypted, None, &nonce, &key).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sender_key_roundtrip() {
        sodiumoxide::init().unwrap();
        let key = generate_key();
        let plaintext = b"Secret group message";
        let ciphertext = encrypt(&key, plaintext);
        let decrypted = decrypt(&key, &ciphertext).expect("decryption failed");
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_wrong_key_fails() {
        sodiumoxide::init().unwrap();
        let key1 = generate_key();
        let key2 = generate_key();
        let plaintext = b"test";
        let ciphertext = encrypt(&key1, plaintext);
        assert!(decrypt(&key2, &ciphertext).is_none());
    }

    #[test]
    fn test_tampered_ciphertext_fails() {
        sodiumoxide::init().unwrap();
        let key = generate_key();
        let plaintext = b"test";
        let mut ciphertext = encrypt(&key, plaintext);
        // Tamper with the last byte (part of tag)
        if let Some(last) = ciphertext.last_mut() {
            *last ^= 0x01;
        }
        assert!(decrypt(&key, &ciphertext).is_none());
    }
}
