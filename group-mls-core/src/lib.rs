//! Shared MLS boundary for Hiddi Android and desktop clients.
//!
//! This crate deliberately contains no transport or account token code.  It will own only
//! client-side MLS state and opaque MLS bytes; the Rust server remains unable to decrypt them.

use openmls::versions::ProtocolVersion;

pub mod storage;

#[cfg(target_os = "android")]
use jni::{
    EnvUnowned,
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jbyteArray},
};

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

/// Minimal JNI boundary smoke-tested before any group key material crosses it.
/// It handles opaque bytes only and fails closed on malformed JVM input.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeIsValidEnvelope(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    encoded: JByteArray,
) -> jboolean {
    unowned_env
        .with_env(|env| {
            Ok::<jboolean, jni::errors::Error>(
                env.convert_byte_array(&encoded)
                    .ok()
                    .and_then(|bytes| MlsEnvelope::decode(&bytes).ok())
                    .is_some() as jboolean,
            )
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Receives a 64-byte profile key only after Kotlin has unwrapped it using the
/// Android Keystore. The Rust core keeps it in process memory and never writes
/// it itself; a different configured key fails closed.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeConfigureStorageKey(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    key: JByteArray,
) -> jboolean {
    unowned_env
        .with_env(|env| {
            Ok::<jboolean, jni::errors::Error>(
                env.convert_byte_array(&key)
                    .ok()
                    .and_then(|bytes| storage::configure_storage_key(&bytes).ok())
                    .is_some() as jboolean,
            )
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Opens the process-local encrypted MLS SQLite provider at an app-private
/// Android path. The path carries no user-visible data; all MLS records remain
/// encrypted by the already configured Keystore-backed profile key.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeInitializePersistentStorage(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    path: JString,
) -> jboolean {
    unowned_env
        .with_env(|env| {
            Ok::<jboolean, jni::errors::Error>(
                path.try_to_string(&env)
                    .ok()
                    .filter(|value| !value.is_empty())
                    .and_then(|path| storage::initialize_persistent_provider(path).ok())
                    .is_some() as jboolean,
            )
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Creates a one-member MLS group in the initialized encrypted provider and
/// returns its random MLS group id. No network operation happens here.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeCreateLocalGroup(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    device_identity: JByteArray,
) -> jbyteArray {
    unowned_env
        .with_env(|env| {
            let identity = env.convert_byte_array(&device_identity)?;
            let group_id = storage::create_local_group(&identity)
                .map_err(|_| jni::errors::Error::NullPtr("could not create MLS group"))?;
            Ok::<jbyteArray, jni::errors::Error>(env.byte_array_from_slice(&group_id)?.into_raw())
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

#[cfg(test)]
mod tests {
    use super::*;
    use openmls::prelude::{
        BasicCredential, Ciphersuite, CredentialWithKey, GroupId, KeyPackage, MlsGroup,
        MlsGroupCreateConfig, MlsGroupJoinConfig, MlsMessageBodyIn, MlsMessageIn,
        ProcessedMessageContent, StagedWelcome, tls_codec::Deserialize,
    };
    use openmls_basic_credential::SignatureKeyPair;
    use openmls_rust_crypto::OpenMlsRustCrypto;
    use openmls_traits::OpenMlsProvider;

    fn credential<Provider: OpenMlsProvider>(
        identity: &[u8],
        provider: &Provider,
    ) -> (CredentialWithKey, SignatureKeyPair) {
        let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
        let signer = SignatureKeyPair::new(ciphersuite.signature_algorithm()).unwrap();
        signer.store(provider.storage()).unwrap();
        (
            CredentialWithKey {
                credential: BasicCredential::new(identity.to_vec()).into(),
                signature_key: signer.to_public_vec().into(),
            },
            signer,
        )
    }

    #[test]
    fn round_trips_an_opaque_application_envelope() {
        let envelope = MlsEnvelope {
            kind: MlsEnvelopeKind::Application,
            bytes: vec![7, 8, 9],
        };
        assert_eq!(
            MlsEnvelope::decode(&envelope.encode().unwrap()).unwrap(),
            envelope
        );
        assert_eq!(MlsEnvelope::protocol_version(), ProtocolVersion::Mls10);
    }

    #[test]
    fn rejects_unknown_or_empty_data() {
        assert_eq!(MlsEnvelope::decode(&[]), Err(EnvelopeError::UnknownVersion));
        assert_eq!(MlsEnvelope::decode(&[1, 3]), Err(EnvelopeError::Empty));
    }

    #[test]
    fn welcome_join_persists_and_restores_group_state() {
        let alice_provider = OpenMlsRustCrypto::default();
        let bob_provider = OpenMlsRustCrypto::default();
        let (alice_credential, alice_signer) = credential(b"alice", &alice_provider);
        let (bob_credential, bob_signer) = credential(b"bob", &bob_provider);
        let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
        let bob_key_package = KeyPackage::builder()
            .build(ciphersuite, &bob_provider, &bob_signer, bob_credential)
            .unwrap();
        let create_config = MlsGroupCreateConfig::builder()
            .ciphersuite(ciphersuite)
            .use_ratchet_tree_extension(true)
            .build();
        let mut alice_group = MlsGroup::new(
            &alice_provider,
            &alice_signer,
            &create_config,
            alice_credential,
        )
        .unwrap();
        let (_commit, welcome, _) = alice_group
            .add_members(
                &alice_provider,
                &alice_signer,
                core::slice::from_ref(bob_key_package.key_package()),
            )
            .unwrap();
        alice_group.merge_pending_commit(&alice_provider).unwrap();
        let welcome = MlsMessageIn::tls_deserialize_exact(welcome.to_bytes().unwrap()).unwrap();
        let welcome = match welcome.extract() {
            MlsMessageBodyIn::Welcome(welcome) => welcome,
            _ => panic!("expected MLS Welcome"),
        };
        let staged = StagedWelcome::new_from_welcome(
            &bob_provider,
            &MlsGroupJoinConfig::builder()
                .use_ratchet_tree_extension(true)
                .build(),
            welcome,
            None,
        )
        .unwrap();
        let bob_group = staged.into_group(&bob_provider).unwrap();
        let bob_group_id = bob_group.group_id().clone();
        drop(bob_group);

        // `MlsGroup` itself is disposable: all ratchet and epoch state must come from the
        // provider storage after an app restart.  The production Android provider will keep
        // this storage encrypted with the Android Keystore; this test exercises OpenMLS'
        // restore path rather than serialising individual MLS secrets ourselves.
        let mut bob_group = MlsGroup::load(bob_provider.storage(), &bob_group_id)
            .unwrap()
            .expect("joined group must be persisted by the OpenMLS storage provider");
        let application = alice_group
            .create_message(&alice_provider, &alice_signer, b"family secret")
            .unwrap();
        let incoming = MlsMessageIn::tls_deserialize_exact(application.to_bytes().unwrap())
            .unwrap()
            .try_into_protocol_message()
            .unwrap();
        let processed = bob_group.process_message(&bob_provider, incoming).unwrap();
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(message) => {
                assert_eq!(message.into_bytes(), b"family secret");
            }
            _ => panic!("expected MLS application message"),
        }
    }

    #[test]
    fn encrypted_sqlite_provider_restores_a_group() {
        storage::tests::configure_test_storage_key();
        let provider = storage::EncryptedSqliteMlsProvider::open_in_memory().unwrap();
        let group_id = GroupId::from_slice(&provider.create_group(b"family-device").unwrap());

        assert!(
            MlsGroup::load(provider.storage(), &group_id)
                .unwrap()
                .is_some()
        );
    }

    #[test]
    fn encrypted_sqlite_profiles_join_through_welcome() {
        storage::tests::configure_test_storage_key();
        let alice_provider = storage::EncryptedSqliteMlsProvider::open_in_memory().unwrap();
        let bob_provider = storage::EncryptedSqliteMlsProvider::open_in_memory().unwrap();
        let (alice_credential, alice_signer) = credential(b"alice-device", &alice_provider);
        let (bob_credential, bob_signer) = credential(b"bob-device", &bob_provider);
        let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
        let bob_key_package = KeyPackage::builder()
            .build(ciphersuite, &bob_provider, &bob_signer, bob_credential)
            .unwrap();
        let config = MlsGroupCreateConfig::builder()
            .ciphersuite(ciphersuite)
            .use_ratchet_tree_extension(true)
            .build();
        let mut alice_group =
            MlsGroup::new(&alice_provider, &alice_signer, &config, alice_credential).unwrap();
        let (_commit, welcome, _) = alice_group
            .add_members(
                &alice_provider,
                &alice_signer,
                core::slice::from_ref(bob_key_package.key_package()),
            )
            .unwrap();
        alice_group.merge_pending_commit(&alice_provider).unwrap();

        let welcome = MlsMessageIn::tls_deserialize_exact(welcome.to_bytes().unwrap()).unwrap();
        let welcome = match welcome.extract() {
            MlsMessageBodyIn::Welcome(welcome) => welcome,
            _ => panic!("expected MLS Welcome"),
        };
        let staged = StagedWelcome::new_from_welcome(
            &bob_provider,
            &MlsGroupJoinConfig::builder()
                .use_ratchet_tree_extension(true)
                .build(),
            welcome,
            None,
        )
        .unwrap();
        let bob_group = staged.into_group(&bob_provider).unwrap();
        let bob_group_id = bob_group.group_id().clone();
        drop(bob_group);
        let mut bob_group = MlsGroup::load(bob_provider.storage(), &bob_group_id)
            .unwrap()
            .expect("Welcome join must persist encrypted SQLite state");

        let application = alice_group
            .create_message(&alice_provider, &alice_signer, b"encrypted family message")
            .unwrap();
        let incoming = MlsMessageIn::tls_deserialize_exact(application.to_bytes().unwrap())
            .unwrap()
            .try_into_protocol_message()
            .unwrap();
        let processed = bob_group.process_message(&bob_provider, incoming).unwrap();
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(message) => {
                assert_eq!(message.into_bytes(), b"encrypted family message");
            }
            _ => panic!("expected MLS application message"),
        }
    }
}
