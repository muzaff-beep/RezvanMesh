use sodiumoxide::crypto::sign;
use sodiumoxide::crypto::scalarmult;

pub struct IdentityKeypair {
    pub public_ed25519: [u8; 32],
    pub private_ed25519: [u8; 64],
    pub public_x25519: [u8; 32],
    pub private_x25519: [u8; 32],
}

impl Clone for IdentityKeypair {
    fn clone(&self) -> Self {
        Self {
            public_ed25519: self.public_ed25519,
            private_ed25519: self.private_ed25519,
            public_x25519: self.public_x25519,
            private_x25519: self.private_x25519,
        }
    }
}

pub fn generate_identity(seed: &[u8; 32]) -> IdentityKeypair {
    let (pk, sk) = sign::keypair_from_seed(&sign::Seed(*seed));
    let mut xs = *seed;
    xs[0] &= 248;
    xs[31] &= 127;
    xs[31] |= 64;
    let s = scalarmult::Scalar(xs);
    let pub_x = scalarmult::scalarmult_base(&s);
    IdentityKeypair {
        public_ed25519: pk.0,
        private_ed25519: sk.0,
        public_x25519: pub_x.0,
        private_x25519: xs,
    }
}