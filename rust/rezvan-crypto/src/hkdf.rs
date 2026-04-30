use sodiumoxide::crypto::auth::hmacsha256;
use sodiumoxide::crypto::hash::sha256;

/// HKDF-SHA256 (RFC 5869)
///
/// * `ikm`   – input keying material
/// * `salt`  – optional salt (pass empty slice for a zero‑filled 32‑byte salt)
/// * `info`  – context / application‑specific information
/// * `length`– desired output length in bytes
pub fn hkdf_sha256(ikm: &[u8], salt: &[u8], info: &[u8], length: usize) -> Vec<u8> {
    // ---- extract ----
    let salt_key = if salt.is_empty() {
        hmacsha256::Key([0u8; 32])
    } else {
        let hash = sha256::hash(salt);
        let mut key = [0u8; 32];
        key.copy_from_slice(&hash.0);
        hmacsha256::Key(key)
    };

    let prk = hmacsha256::authenticate(ikm, &salt_key);
    let mut prk_key = [0u8; 32];
    prk_key.copy_from_slice(&prk.0);

    // ---- expand ----
    let mut output = Vec::with_capacity(length);
    let mut t = Vec::new();       // T(0) = empty
    let n = (length + 31) / 32;

    for i in 1..=n {
        let mut input = Vec::new();
        input.extend_from_slice(&t);
        input.extend_from_slice(info);
        input.push(i as u8);

        let key = hmacsha256::Key(prk_key);
        let tag = hmacsha256::authenticate(&input, &key);
        t = tag.0.to_vec();
        output.extend_from_slice(&tag.0);
    }

    output.truncate(length);
    output
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_rfc5869_vector_1() {
        // Test Vector 1 from RFC 5869, Section A.1
        let ikm = [0x0bu8; 22];
        let salt: [u8; 13] = [
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c,
        ];
        let info = [0xf0u8, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9];
        let okm = hkdf_sha256(&ikm, &salt, &info, 42);
        let expected: [u8; 42] = [
            0x3c, 0xb2, 0x5f, 0x25, 0xfa, 0xac, 0xd5, 0x7a,
            0x90, 0x43, 0x4f, 0x64, 0xd0, 0x36, 0x2f, 0x2a,
            0x2d, 0x2d, 0x0a, 0x90, 0xcf, 0x1a, 0x5a, 0x4c,
            0x5d, 0xb0, 0x2d, 0x56, 0xec, 0xc4, 0xc5, 0xbf,
            0x34, 0x00, 0x72, 0x08, 0xd5, 0xb8, 0x87, 0x18,
            0x58, 0x65,
        ];
        assert_eq!(okm, expected.to_vec());
    }

    #[test]
    fn test_empty_salt() {
        let ikm = b"hello";
        let okm = hkdf_sha256(ikm, &[], b"test", 32);
        assert_eq!(okm.len(), 32);
    }

    #[test]
    fn test_output_length() {
        let ikm = b"some key material";
        let okm = hkdf_sha256(ikm, &[], b"app info", 16);
        assert_eq!(okm.len(), 16);
    }
}