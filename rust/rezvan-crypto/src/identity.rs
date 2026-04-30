use sodiumoxide::crypto::sign;
use sodiumoxide::crypto::scalarmult;

pub struct IdentityKeypair {
    pub public_ed25519: [u8; 32],
    pub private_ed25519: [u8; 64],
    pub public_x25519: [u8; 32],
    pub private_x25519: [u8; 32],
}

pub fn generate_identity(seed: &[u8; 32]) -> IdentityKeypair {
    // Ed25519 keypair from seed (libsodium standard)
    let (public_ed25519, private_ed25519) = sign::keypair_from_seed(&sign::Seed(*seed));

    // Derive X25519 keypair from the same seed using proper clamping.
    // This matches how libsodium internally derives Curve25519 keys from Ed25519 seeds.
    let mut x25519_seed = *seed;
    x25519_seed[0] &= 248;
    x25519_seed[31] &= 127;
    x25519_seed[31] |= 64;

    let scalar = scalarmult::Scalar(x25519_seed);
    let public_x25519 = scalarmult::scalarmult_base(&scalar);

    IdentityKeypair {
        public_ed25519: public_ed25519.0,
        private_ed25519: private_ed25519.0,
        public_x25519: public_x25519.0,
        private_x25519: x25519_seed,
    }
}
