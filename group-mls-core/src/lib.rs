//! Shared MLS boundary for Hiddi Android and desktop clients.
//!
//! This crate deliberately contains no transport or account token code.  It will own only
//! client-side MLS state and opaque MLS bytes; the Rust server remains unable to decrypt them.

use openmls::versions::ProtocolVersion;

pub const ENVELOPE_VERSION: u8 = 1;
pub const MAX_MLS_ENVELOPE_BYTES: usize = 2_800_000;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MlsEnvelopeKind {
    Welcome = 1,
    Commit = 2,
    Application = 3,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MlsEnvelope {
    pub kind: MlsEnvelopeKind,
    pub bytes: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum EnvelopeError {
    Empty,
    TooLarge,
    UnknownVersion,
    UnknownKind,
}

impl MlsEnvelope {
    /// A versioned opaque wrapper. MLS parses and authenticates `bytes` before any UI uses it.
    pub fn encode(&self) -> Result<Vec<u8>, EnvelopeError> {
        validate_payload(&self.bytes)?;
        let mut output = Vec::with_capacity(self.bytes.len() + 2);
        output.push(ENVELOPE_VERSION);
        output.push(self.kind as u8);
        output.extend_from_slice(&self.bytes);
        Ok(output)
    }

    pub fn decode(encoded: &[u8]) -> Result<Self, EnvelopeError> {
        if encoded.first().copied() != Some(ENVELOPE_VERSION) {
            return Err(EnvelopeError::UnknownVersion);
        }
        let kind = match encoded.get(1).copied() {
            Some(1) => MlsEnvelopeKind::Welcome,
            Some(2) => MlsEnvelopeKind::Commit,
            Some(3) => MlsEnvelopeKind::Application,
            _ => return Err(EnvelopeError::UnknownKind),
        };
        let bytes = encoded[2..].to_vec();
        validate_payload(&bytes)?;
        Ok(Self { kind, bytes })
    }

    pub const fn protocol_version() -> ProtocolVersion {
        ProtocolVersion::Mls10
    }
}

fn validate_payload(bytes: &[u8]) -> Result<(), EnvelopeError> {
    if bytes.is_empty() {
        return Err(EnvelopeError::Empty);
    }
    if bytes.len() > MAX_MLS_ENVELOPE_BYTES {
        return Err(EnvelopeError::TooLarge);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_an_opaque_application_envelope() {
        let envelope = MlsEnvelope { kind: MlsEnvelopeKind::Application, bytes: vec![7, 8, 9] };
        assert_eq!(MlsEnvelope::decode(&envelope.encode().unwrap()).unwrap(), envelope);
        assert_eq!(MlsEnvelope::protocol_version(), ProtocolVersion::Mls10);
    }

    #[test]
    fn rejects_unknown_or_empty_data() {
        assert_eq!(MlsEnvelope::decode(&[]), Err(EnvelopeError::UnknownVersion));
        assert_eq!(MlsEnvelope::decode(&[1, 3]), Err(EnvelopeError::Empty));
    }
}
