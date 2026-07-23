//! Shared MLS boundary for Hiddi Android and desktop clients.
//!
//! This crate deliberately contains no transport or account token code.  It will own only
//! client-side MLS state and opaque MLS bytes; the Rust server remains unable to decrypt them.

use openmls::versions::ProtocolVersion;

pub mod storage;

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
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

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos",
    test
))]
fn encode_add_member_bundle(output: storage::AddMemberOutput) -> Result<Vec<u8>, EnvelopeError> {
    let commit = MlsEnvelope {
        kind: MlsEnvelopeKind::Commit,
        bytes: output.commit,
    }
    .encode()?;
    let welcome = MlsEnvelope {
        kind: MlsEnvelopeKind::Welcome,
        bytes: output.welcome,
    }
    .encode()?;
    let commit_len = u32::try_from(commit.len()).map_err(|_| EnvelopeError::TooLarge)?;
    let mut encoded = Vec::with_capacity(1 + 4 + commit.len() + welcome.len());
    encoded.push(ENVELOPE_VERSION);
    encoded.extend_from_slice(&commit_len.to_be_bytes());
    encoded.extend(commit);
    encoded.extend(welcome);
    Ok(encoded)
}

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos",
    test
))]
fn encode_pending_operations(
    operations: Vec<storage::PendingMembershipOperation>,
) -> Result<Vec<u8>, EnvelopeError> {
    let count = u32::try_from(operations.len()).map_err(|_| EnvelopeError::TooLarge)?;
    let mut encoded = Vec::new();
    encoded.push(ENVELOPE_VERSION);
    encoded.extend_from_slice(&count.to_be_bytes());
    for operation in operations {
        let operation_id = operation.operation_id.as_bytes();
        let id_len = u16::try_from(operation_id.len()).map_err(|_| EnvelopeError::TooLarge)?;
        let group_len =
            u16::try_from(operation.group_id.len()).map_err(|_| EnvelopeError::TooLarge)?;
        let context_len =
            u32::try_from(operation.context.len()).map_err(|_| EnvelopeError::TooLarge)?;
        let commit = MlsEnvelope {
            kind: MlsEnvelopeKind::Commit,
            bytes: operation.commit,
        }
        .encode()?;
        let commit_len = u32::try_from(commit.len()).map_err(|_| EnvelopeError::TooLarge)?;
        let welcome = operation
            .welcome
            .map(|bytes| {
                MlsEnvelope {
                    kind: MlsEnvelopeKind::Welcome,
                    bytes,
                }
                .encode()
            })
            .transpose()?;
        let welcome_len = u32::try_from(welcome.as_ref().map_or(0, Vec::len))
            .map_err(|_| EnvelopeError::TooLarge)?;

        encoded.extend_from_slice(&id_len.to_be_bytes());
        encoded.extend_from_slice(operation_id);
        encoded.push(match operation.kind {
            storage::PendingOperationKind::AddMember => 1,
            storage::PendingOperationKind::RemoveMember => 2,
        });
        encoded.extend_from_slice(&group_len.to_be_bytes());
        encoded.extend_from_slice(&operation.group_id);
        encoded.extend_from_slice(&context_len.to_be_bytes());
        encoded.extend_from_slice(&operation.context);
        encoded.extend_from_slice(&commit_len.to_be_bytes());
        encoded.extend_from_slice(&commit);
        encoded.extend_from_slice(&welcome_len.to_be_bytes());
        if let Some(welcome) = welcome {
            encoded.extend_from_slice(&welcome);
        }
    }
    Ok(encoded)
}

/// Minimal JNI boundary smoke-tested before any group key material crosses it.
/// It handles opaque bytes only and fails closed on malformed JVM input.
#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
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
#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
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
#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeInitializePersistentStorage(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    path: JString,
) -> jboolean {
    unowned_env
        .with_env(|env| {
            Ok::<jboolean, jni::errors::Error>(
                path.try_to_string(env)
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
#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
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

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeDeleteLocalGroup(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    group_id: JByteArray,
) -> jboolean {
    unowned_env
        .with_env(|env| {
            Ok::<jboolean, jni::errors::Error>(
                env.convert_byte_array(&group_id)
                    .ok()
                    .and_then(|bytes| storage::delete_local_group(&bytes).ok())
                    .is_some() as jboolean,
            )
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeCreateKeyPackage(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    device_identity: JByteArray,
) -> jbyteArray {
    unowned_env
        .with_env(|env| {
            let identity = env.convert_byte_array(&device_identity)?;
            let key_package = storage::create_key_package(&identity)
                .map_err(|_| jni::errors::Error::NullPtr("could not create MLS KeyPackage"))?;
            Ok::<jbyteArray, jni::errors::Error>(
                env.byte_array_from_slice(&key_package)?.into_raw(),
            )
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeAddMember(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    group_id: JByteArray,
    key_package: JByteArray,
    operation_id: JString,
    context: JByteArray,
) -> jbyteArray {
    unowned_env
        .with_env(|env| {
            let group_id = env.convert_byte_array(&group_id)?;
            let key_package = env.convert_byte_array(&key_package)?;
            let operation_id = operation_id.try_to_string(env)?;
            let context = env.convert_byte_array(&context)?;
            let output = storage::add_member(&group_id, &key_package, &operation_id, &context)
                .map_err(|_| jni::errors::Error::NullPtr("could not add MLS member"))?;
            let encoded = encode_add_member_bundle(output)
                .map_err(|_| jni::errors::Error::NullPtr("could not encode MLS member bundle"))?;
            Ok::<jbyteArray, jni::errors::Error>(env.byte_array_from_slice(&encoded)?.into_raw())
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeRemoveMember(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    group_id: JByteArray,
    member_identity: JByteArray,
    operation_id: JString,
    context: JByteArray,
) -> jbyteArray {
    unowned_env
        .with_env(|env| {
            let group_id = env.convert_byte_array(&group_id)?;
            let member_identity = env.convert_byte_array(&member_identity)?;
            let operation_id = operation_id.try_to_string(env)?;
            let context = env.convert_byte_array(&context)?;
            let commit =
                storage::remove_member(&group_id, &member_identity, &operation_id, &context)
                    .map_err(|_| jni::errors::Error::NullPtr("could not remove MLS member"))?;
            let envelope = MlsEnvelope {
                kind: MlsEnvelopeKind::Commit,
                bytes: commit,
            }
            .encode()
            .map_err(|_| jni::errors::Error::NullPtr("could not encode MLS remove commit"))?;
            Ok::<jbyteArray, jni::errors::Error>(env.byte_array_from_slice(&envelope)?.into_raw())
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativePendingMembershipOperations(
    mut unowned_env: EnvUnowned,
    _class: JClass,
) -> jbyteArray {
    unowned_env
        .with_env(|env| {
            let operations = storage::pending_membership_operations()
                .map_err(|_| jni::errors::Error::NullPtr("could not load MLS journal"))?;
            let encoded = encode_pending_operations(operations)
                .map_err(|_| jni::errors::Error::NullPtr("could not encode MLS journal"))?;
            Ok::<jbyteArray, jni::errors::Error>(env.byte_array_from_slice(&encoded)?.into_raw())
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeAckMembershipOperation(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    operation_id: JString,
) -> jboolean {
    unowned_env
        .with_env(|env| {
            Ok::<jboolean, jni::errors::Error>(
                operation_id
                    .try_to_string(env)
                    .ok()
                    .and_then(|id| storage::ack_membership_operation(&id).ok())
                    .is_some() as jboolean,
            )
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeProcessWelcome(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    encoded_envelope: JByteArray,
) -> jbyteArray {
    unowned_env
        .with_env(|env| {
            let encoded = env.convert_byte_array(&encoded_envelope)?;
            let envelope = MlsEnvelope::decode(&encoded)
                .map_err(|_| jni::errors::Error::NullPtr("invalid MLS envelope"))?;
            if envelope.kind != MlsEnvelopeKind::Welcome {
                return Err(jni::errors::Error::NullPtr("expected MLS Welcome"));
            }
            let group_id = storage::join_from_welcome(&envelope.bytes)
                .map_err(|_| jni::errors::Error::NullPtr("could not process MLS Welcome"))?;
            Ok::<jbyteArray, jni::errors::Error>(env.byte_array_from_slice(&group_id)?.into_raw())
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeCreateApplicationMessage(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    group_id: JByteArray,
    plaintext: JByteArray,
) -> jbyteArray {
    unowned_env
        .with_env(|env| {
            let group_id = env.convert_byte_array(&group_id)?;
            let plaintext = env.convert_byte_array(&plaintext)?;
            let message = storage::create_application_message(&group_id, &plaintext)
                .map_err(|_| jni::errors::Error::NullPtr("could not create MLS message"))?;
            let envelope = MlsEnvelope {
                kind: MlsEnvelopeKind::Application,
                bytes: message,
            }
            .encode()
            .map_err(|_| jni::errors::Error::NullPtr("could not encode MLS message"))?;
            Ok::<jbyteArray, jni::errors::Error>(env.byte_array_from_slice(&envelope)?.into_raw())
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeProcessApplicationMessage(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    group_id: JByteArray,
    encoded_envelope: JByteArray,
) -> jbyteArray {
    unowned_env
        .with_env(|env| {
            let group_id = env.convert_byte_array(&group_id)?;
            let encoded = env.convert_byte_array(&encoded_envelope)?;
            let envelope = MlsEnvelope::decode(&encoded)
                .map_err(|_| jni::errors::Error::NullPtr("invalid MLS envelope"))?;
            if envelope.kind != MlsEnvelopeKind::Application {
                return Err(jni::errors::Error::NullPtr(
                    "expected MLS application message",
                ));
            }
            let plaintext = storage::process_application_message(&group_id, &envelope.bytes)
                .map_err(|_| jni::errors::Error::NullPtr("could not process MLS message"))?;
            Ok::<jbyteArray, jni::errors::Error>(env.byte_array_from_slice(&plaintext)?.into_raw())
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

#[cfg(any(
    target_os = "android",
    target_os = "linux",
    target_os = "windows",
    target_os = "macos"
))]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_hiddi_messenger_security_NativeMlsBridge_nativeProcessCommit(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    group_id: JByteArray,
    encoded_envelope: JByteArray,
) -> jboolean {
    unowned_env
        .with_env(|env| {
            let group_id = env.convert_byte_array(&group_id)?;
            let encoded = env.convert_byte_array(&encoded_envelope)?;
            let accepted = MlsEnvelope::decode(&encoded)
                .ok()
                .filter(|envelope| envelope.kind == MlsEnvelopeKind::Commit)
                .and_then(|envelope| storage::process_commit(&group_id, &envelope.bytes).ok())
                .is_some();
            Ok::<jboolean, jni::errors::Error>(accepted as jboolean)
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
}

#[cfg(test)]
mod tests {
    use super::*;
    use openmls::prelude::{
        BasicCredential, Ciphersuite, CredentialWithKey, KeyPackage, MlsGroup,
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
    fn encodes_commit_and_welcome_as_separate_envelopes() {
        let encoded = encode_add_member_bundle(storage::AddMemberOutput {
            commit: vec![10, 11],
            welcome: vec![20, 21],
        })
        .unwrap();
        assert_eq!(encoded[0], ENVELOPE_VERSION);
        let commit_len = u32::from_be_bytes(encoded[1..5].try_into().unwrap()) as usize;
        let commit = MlsEnvelope::decode(&encoded[5..5 + commit_len]).unwrap();
        let welcome = MlsEnvelope::decode(&encoded[5 + commit_len..]).unwrap();
        assert_eq!(commit.kind, MlsEnvelopeKind::Commit);
        assert_eq!(commit.bytes, vec![10, 11]);
        assert_eq!(welcome.kind, MlsEnvelopeKind::Welcome);
        assert_eq!(welcome.bytes, vec![20, 21]);
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
        let mut provider = storage::EncryptedSqliteMlsProvider::open_in_memory().unwrap();
        let group_id = provider.create_group(b"family-device").unwrap();
        assert!(provider.group_exists(&group_id).unwrap());
    }

    #[test]
    fn encrypted_sqlite_profiles_join_through_welcome() {
        storage::tests::configure_test_storage_key();
        let mut alice_provider = storage::EncryptedSqliteMlsProvider::open_in_memory().unwrap();
        let mut bob_provider = storage::EncryptedSqliteMlsProvider::open_in_memory().unwrap();
        let group_id = alice_provider.create_group(b"alice-device").unwrap();
        let key_package = bob_provider.create_key_package(b"bob-device").unwrap();
        let output = alice_provider
            .add_member(
                &group_id,
                &key_package,
                "add-bob-operation-0001",
                b"test context",
            )
            .unwrap();
        assert_eq!(
            bob_provider.join_from_welcome(&output.welcome).unwrap(),
            group_id
        );
        let encrypted = alice_provider
            .create_application_message(&group_id, b"encrypted family message")
            .unwrap();
        assert_eq!(
            bob_provider
                .process_application_message(&group_id, &encrypted)
                .unwrap(),
            b"encrypted family message"
        );
    }

    #[test]
    fn membership_change_and_recovery_journal_are_idempotent() {
        storage::tests::configure_test_storage_key();
        let mut alice = storage::EncryptedSqliteMlsProvider::open_in_memory().unwrap();
        let mut bob = storage::EncryptedSqliteMlsProvider::open_in_memory().unwrap();
        let group_id = alice.create_group(b"alice-journal-device").unwrap();
        let key_package = bob.create_key_package(b"bob-journal-device").unwrap();
        let operation_id = "journal-operation-0001";
        let context = br#"{"version":1,"type":"test"}"#;

        let first = alice
            .add_member(&group_id, &key_package, operation_id, context)
            .unwrap();
        let second = alice
            .add_member(&group_id, &key_package, operation_id, context)
            .unwrap();
        assert_eq!(first, second);
        let pending = alice.pending_membership_operations().unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].operation_id, operation_id);
        assert_eq!(pending[0].context, context);
        assert_eq!(pending[0].commit, first.commit);
        assert_eq!(
            pending[0].welcome.as_deref(),
            Some(first.welcome.as_slice())
        );
        assert!(
            alice
                .add_member(&group_id, &key_package, operation_id, b"different context",)
                .is_err()
        );

        alice.ack_membership_operation(operation_id).unwrap();
        assert!(alice.pending_membership_operations().unwrap().is_empty());
    }

    #[test]
    fn recovery_journal_survives_provider_restart() {
        storage::tests::configure_test_storage_key();
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "hiddi-mls-journal-{}-{unique}.sqlite",
            std::process::id(),
        ));
        let mut bob = storage::EncryptedSqliteMlsProvider::open_in_memory().unwrap();
        let key_package = bob.create_key_package(b"bob-restart-device").unwrap();
        let group_id = {
            let mut alice = storage::EncryptedSqliteMlsProvider::open(&path).unwrap();
            let group_id = alice.create_group(b"alice-restart-device").unwrap();
            alice
                .add_member(
                    &group_id,
                    &key_package,
                    "restart-operation-0001",
                    b"durable context",
                )
                .unwrap();
            group_id
        };

        let mut restored = storage::EncryptedSqliteMlsProvider::open(&path).unwrap();
        assert!(restored.group_exists(&group_id).unwrap());
        assert_eq!(
            restored.pending_membership_operations().unwrap()[0].operation_id,
            "restart-operation-0001"
        );
        restored
            .ack_membership_operation("restart-operation-0001")
            .unwrap();
        drop(restored);
        std::fs::remove_file(&path).unwrap();
    }

    #[test]
    fn persistent_signer_creates_welcome_after_group_reload() {
        storage::tests::configure_test_storage_key();
        let mut alice_provider = storage::EncryptedSqliteMlsProvider::open_in_memory().unwrap();
        let mut bob_provider = storage::EncryptedSqliteMlsProvider::open_in_memory().unwrap();
        let mut charlie_provider = storage::EncryptedSqliteMlsProvider::open_in_memory().unwrap();

        let group_id = alice_provider.create_group(b"alice-device").unwrap();
        let bob_key_package = bob_provider.create_key_package(b"bob-device").unwrap();
        let output = alice_provider
            .add_member(
                &group_id,
                &bob_key_package,
                "add-bob-operation-0002",
                b"add bob context",
            )
            .unwrap();
        assert!(!output.commit.is_empty());
        assert!(!output.welcome.is_empty());
        let joined_group_id = bob_provider.join_from_welcome(&output.welcome).unwrap();
        assert_eq!(joined_group_id, group_id);
        let encrypted = alice_provider
            .create_application_message(&group_id, b"hello persistent MLS")
            .unwrap();
        assert_eq!(
            bob_provider
                .process_application_message(&group_id, &encrypted)
                .unwrap(),
            b"hello persistent MLS"
        );
        let reply = bob_provider
            .create_application_message(&group_id, b"welcome to the group")
            .unwrap();
        assert_eq!(
            alice_provider
                .process_application_message(&group_id, &reply)
                .unwrap(),
            b"welcome to the group"
        );
        let charlie_key_package = charlie_provider
            .create_key_package(b"charlie-device")
            .unwrap();
        let add_charlie = alice_provider
            .add_member(
                &group_id,
                &charlie_key_package,
                "add-charlie-operation-0001",
                b"add charlie context",
            )
            .unwrap();
        bob_provider
            .process_commit(&group_id, &add_charlie.commit)
            .unwrap();
        assert_eq!(
            charlie_provider
                .join_from_welcome(&add_charlie.welcome)
                .unwrap(),
            group_id
        );
        let three_party_message = alice_provider
            .create_application_message(&group_id, b"hello three-member MLS")
            .unwrap();
        assert_eq!(
            bob_provider
                .process_application_message(&group_id, &three_party_message)
                .unwrap(),
            b"hello three-member MLS"
        );
        assert_eq!(
            charlie_provider
                .process_application_message(&group_id, &three_party_message)
                .unwrap(),
            b"hello three-member MLS"
        );
        let remove_charlie = alice_provider
            .remove_member(
                &group_id,
                b"charlie-device",
                "remove-charlie-operation-0001",
                b"remove charlie context",
            )
            .unwrap();
        bob_provider
            .process_commit(&group_id, &remove_charlie)
            .unwrap();
        charlie_provider
            .process_commit(&group_id, &remove_charlie)
            .unwrap();
        let post_removal_message = alice_provider
            .create_application_message(&group_id, b"after Charlie removal")
            .unwrap();
        assert_eq!(
            bob_provider
                .process_application_message(&group_id, &post_removal_message)
                .unwrap(),
            b"after Charlie removal"
        );
        assert!(
            charlie_provider
                .process_application_message(&group_id, &post_removal_message)
                .is_err(),
            "removed member must not decrypt a message from the new epoch",
        );
        assert!(alice_provider.group_exists(&group_id).unwrap());
    }
}
