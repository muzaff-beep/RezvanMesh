use sodiumoxide::crypto::sign;
use sodiumoxide::crypto::scalarmult;

pub struct IdentityKeypair {
    pub public_ed25519: [u8; 32],
    pub private_ed25519: [u8; 64],
    pub public_x25519: [u8; 32],
    pub private_x25519: [u8; 32],
}

pub fn generate_identity(seed: &[u8; 32]) -> IdentityKeypair {
    let (public_ed25519, private_ed25519) = sign::keypair_from_seed(&sign::Seed(*seed));

    let mut x25519_seed = [0u8; 32];
    x25519_seed.copy_from_slice(&seed[0..32]);
    let private_x25519 = scalarmult::Scalar(x25519_seed);
    let public_x25519 = scalarmult::scalarmult_base(&private_x25519);

    IdentityKeypair {
        public_ed25519: public_ed25519.0,
        private_ed25519: private_ed25519.0,
        public_x25519: public_x25519.0,
        private_x25519: private_x25519.0,
    }
}
