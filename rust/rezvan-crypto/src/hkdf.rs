use sodiumoxide::crypto::auth::hmacsha256;
use sodiumoxide::crypto::hash::sha256;

pub fn hkdf_sha256(ikm: &[u8], salt: &[u8], info: &[u8], length: usize) -> Vec<u8> {
    // Extract
    let salt_key = if salt.is_empty() {
        hmacsha256::Key([0u8; 32])
    } else {
        let mut key = [0u8; 32];
        let hash = sha256::hash(salt);
        key.copy_from_slice(&hash.0);
        hmacsha256::Key(key)
    };

    let prk = hmacsha256::authenticate(ikm, &salt_key);
    let mut prk_key = [0u8; 32];
    prk_key.copy_from_slice(&prk.0);

    // Expand
    let mut output = Vec::new();
    let mut t = Vec::new();
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
