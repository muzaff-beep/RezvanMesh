// src/hkdf.rs

use sodiumoxide::crypto::auth::hmacsha256;

/// HKDF-SHA256 (RFC 5869)
/// # Arguments
/// * `ikm` - Input keying material
/// * `salt` - Optional salt value (non-secret random value)
/// * `info` - Optional context and application specific information
/// * `length` - Length of output keying material in bytes (must be <= 255 * 32 = 8160)
pub fn hkdf(ikm: &[u8], salt: &[u8], info: &[u8], length: usize) -> Vec<u8> {
    // 1. Extract: PRK = HMAC-SHA256(salt, IKM)
    let prk = if salt.is_empty() {
        let zero_salt = [0u8; 32];
        hmacsha256::authenticate(ikm, &hmacsha256::Key(zero_salt)).0
    } else {
        // HMAC-SHA256 uses a 32-byte key; if salt is longer, it's hashed first.
        // sodiumoxide's Key expects exactly 32 bytes.
        let salt_key = if salt.len() == 32 {
            let mut key = [0u8; 32];
            key.copy_from_slice(salt);
            key
        } else {
            // Hash the salt to get a 32-byte key
            use sodiumoxide::crypto::hash::sha256;
            sha256::hash(salt).0
        };
        hmacsha256::authenticate(ikm, &hmacsha256::Key(salt_key)).0
    };

    // 2. Expand: OKM = T(1) || T(2) || ... || T(N)
    let hash_len = 32; // SHA256 output length
    let n = (length + hash_len - 1) / hash_len;
    let mut okm = Vec::with_capacity(n * hash_len);
    let mut t_prev = Vec::new();

    for i in 1..=n {
        let mut input = t_prev.clone();
        input.extend_from_slice(info);
        input.push(i as u8);
        let t_i = hmacsha256::authenticate(&input, &hmacsha256::Key(prk)).0;
        okm.extend_from_slice(&t_i);
        t_prev = t_i.to_vec();
    }

    okm.truncate(length);
    okm
}

#[cfg(test)]
mod tests {
    use super::*;

    // Test vectors from RFC 5869
    #[test]
    fn test_hkdf_rfc5869_case1() {
        sodiumoxide::init().unwrap();
        let ikm = hex::decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b").unwrap();
        let salt = hex::decode("000102030405060708090a0b0c").unwrap();
        let info = hex::decode("f0f1f2f3f4f5f6f7f8f9").unwrap();
        let expected = hex::decode("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865").unwrap();
        let okm = hkdf(&ikm, &salt, &info, 42);
        assert_eq!(okm, expected);
    }

    #[test]
    fn test_hkdf_rfc5869_case2() {
        sodiumoxide::init().unwrap();
        let ikm = hex::decode("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f").unwrap();
        let salt = hex::decode("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeaf").unwrap();
        let info = hex::decode("b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff").unwrap();
        let expected = hex::decode("b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87").unwrap();
        let okm = hkdf(&ikm, &salt, &info, 82);
        assert_eq!(okm, expected);
    }

    #[test]
    fn test_hkdf_rfc5869_case3() {
        sodiumoxide::init().unwrap();
        let ikm = hex::decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b").unwrap();
        let salt = [];
        let info = [];
        let expected = hex::decode("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8").unwrap();
        let okm = hkdf(&ikm, &salt, &info, 42);
        assert_eq!(okm, expected);
    }

    #[test]
    fn test_hkdf_zero_length_output() {
        sodiumoxide::init().unwrap();
        let ikm = b"test";
        let okm = hkdf(ikm, b"salt", b"info", 0);
        assert_eq!(okm.len(), 0);
    }
}
