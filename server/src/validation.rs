use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};

use crate::error::Error;
use axum::http::StatusCode;

pub(crate) const MAX_ATTACHMENT_BYTES: usize = 8 * 1024 * 1024;
pub(crate) const MAX_ATTACHMENT_BASE64_BYTES: usize = MAX_ATTACHMENT_BYTES.div_ceil(3) * 4;
pub(crate) const MAX_AVATAR_BYTES: usize = 512 * 1024;
pub(crate) const MAX_AVATAR_BASE64_BYTES: usize = MAX_AVATAR_BYTES.div_ceil(3) * 4;
pub(crate) const MAX_JSON_BODY_BYTES: usize = MAX_ATTACHMENT_BASE64_BYTES + 16 * 1024;

pub(crate) fn decode_attachment_ciphertext(value: &str) -> Result<Vec<u8>, Error> {
    if value.len() < 24 || value.len() > MAX_ATTACHMENT_BASE64_BYTES {
        return Err(Error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "encrypted attachment must be at most 8 MiB",
        ));
    }
    let decoded = URL_SAFE_NO_PAD.decode(value).map_err(|_| {
        Error(
            StatusCode::BAD_REQUEST,
            "attachment ciphertext must be URL-safe base64",
        )
    })?;
    if decoded.len() > MAX_ATTACHMENT_BYTES {
        return Err(Error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "encrypted attachment must be at most 8 MiB",
        ));
    }
    Ok(decoded)
}

pub(crate) fn validate_device_material(
    identity_public_key: &str,
    registration_id: u32,
) -> Result<(), Error> {
    if identity_public_key.len() < 20 || identity_public_key.len() > 4096 {
        return Err(Error(
            StatusCode::BAD_REQUEST,
            "invalid identity public key",
        ));
    }
    if !(1..=16_380).contains(&registration_id) {
        return Err(Error(StatusCode::BAD_REQUEST, "invalid registration id"));
    }
    Ok(())
}

pub(crate) fn normalize_device_name(value: &str, fallback: &str) -> String {
    let normalized = value.trim();
    if normalized.is_empty() {
        fallback.to_owned()
    } else {
        normalized.chars().take(64).collect()
    }
}

pub(crate) fn normalize_nickname(value: &str) -> Option<String> {
    let value = value.trim();
    let nickname = value
        .strip_prefix('@')
        .unwrap_or(value)
        .to_ascii_lowercase();
    let valid = nickname.len() >= 3
        && nickname.len() <= 32
        && nickname
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'_');
    valid.then_some(nickname)
}

pub(crate) fn validate_display_name(value: &str) -> Result<String, Error> {
    let normalized = value.trim();
    if normalized.chars().count() > 64 || normalized.chars().any(char::is_control) {
        return Err(Error(StatusCode::BAD_REQUEST, "invalid display name"));
    }
    Ok(normalized.to_owned())
}

pub(crate) fn validate_bio(value: &str) -> Result<String, Error> {
    let normalized = value.replace("\r\n", "\n").replace('\r', "\n");
    let normalized = normalized.trim();
    if normalized.chars().count() > 250
        || normalized
            .chars()
            .any(|character| character.is_control() && character != '\n' && character != '\t')
    {
        return Err(Error(StatusCode::BAD_REQUEST, "invalid bio"));
    }
    Ok(normalized.to_owned())
}

pub(crate) fn decode_avatar(value: &str) -> Result<Vec<u8>, Error> {
    if value.is_empty() || value.len() > MAX_AVATAR_BASE64_BYTES {
        return Err(Error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "avatar must be at most 512 KiB",
        ));
    }
    let decoded = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| Error(StatusCode::BAD_REQUEST, "avatar must be URL-safe base64"))?;
    if decoded.len() > MAX_AVATAR_BYTES
        || !decoded.starts_with(&[0xff, 0xd8, 0xff])
        || !decoded.ends_with(&[0xff, 0xd9])
    {
        return Err(Error(
            StatusCode::BAD_REQUEST,
            "avatar must be a JPEG image",
        ));
    }
    Ok(decoded)
}
