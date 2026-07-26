use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use rand::Rng;
use sha2::{Digest, Sha256};

pub(crate) fn random_token(bytes: usize) -> String {
    let mut raw = vec![0_u8; bytes];
    rand::rng().fill(&mut raw[..]);
    URL_SAFE_NO_PAD.encode(raw)
}

pub(crate) fn hash(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}
