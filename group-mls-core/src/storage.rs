//! Persistent storage for OpenMLS state.
//!
//! OpenMLS owns the shape of its state through `StorageProvider`; Hiddi only
//! encrypts opaque serialized records before they reach SQLite.  This avoids a
//! fragile, home-grown serialization of ratchet and epoch secrets.

use std::sync::Mutex;
use std::{path::Path, sync::OnceLock};

use aes_siv::{
    Aes256SivAead, Nonce,
    aead::{Aead, KeyInit, Payload},
};
use openmls::prelude::{
    BasicCredential, Ciphersuite, CredentialWithKey, GroupId, KeyPackage, KeyPackageIn, MlsGroup,
    MlsGroupCreateConfig, MlsGroupJoinConfig, MlsMessageBodyIn, MlsMessageIn,
    ProcessedMessageContent, ProtocolVersion, StagedWelcome, tls_codec::Deserialize,
    tls_codec::Serialize as TlsSerialize,
};
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::RustCrypto;
use openmls_sqlite_storage::{Codec, Connection, SqliteStorageProvider};
use openmls_traits::OpenMlsProvider;
use serde::{Serialize, de::DeserializeOwned};
use thiserror::Error;

const STORAGE_RECORD_VERSION: u8 = 1;
const STORAGE_AAD: &[u8] = b"hiddi-openmls-sqlite-v1";
const MIN_ENCRYPTED_RECORD_BYTES: usize = 1 + 16;

static STORAGE_KEY: OnceLock<[u8; 64]> = OnceLock::new();
static PERSISTENT_PROVIDER: OnceLock<Mutex<EncryptedSqliteMlsProvider>> = OnceLock::new();

#[derive(Debug, Error)]
pub enum StorageError {
    #[error("MLS storage key must be exactly 64 bytes")]
    InvalidKeyLength,
    #[error("MLS storage key is already configured for this process")]
    KeyAlreadyConfigured,
    #[error("MLS storage key is not configured")]
    KeyNotConfigured,
    #[error("MLS storage record is malformed")]
    MalformedRecord,
    #[error("MLS storage record has an unsupported version")]
    UnsupportedRecordVersion,
    #[error("MLS storage encryption failed")]
    Encryption,
    #[error("MLS storage decryption or authentication failed")]
    Decryption,
    #[error("MLS storage serialization failed: {0}")]
    Serialization(#[from] serde_json::Error),
    #[error("MLS SQLite operation failed: {0}")]
    Sqlite(String),
    #[error("MLS SQLite migration failed: {0}")]
    Migration(String),
    #[error("OpenMLS operation failed: {0}")]
    OpenMls(String),
}

#[derive(Debug, Eq, PartialEq)]
pub struct AddMemberOutput {
    pub commit: Vec<u8>,
    pub welcome: Vec<u8>,
}

/// Installs the per-profile key unwrapped by Android Keystore (or desktop's
/// OS keychain). The key is never persisted by this crate.
pub fn configure_storage_key(key: &[u8]) -> Result<(), StorageError> {
    let key: [u8; 64] = key.try_into().map_err(|_| StorageError::InvalidKeyLength)?;
    if STORAGE_KEY
        .get()
        .is_some_and(|configured| configured == &key)
    {
        return Ok(());
    }
    STORAGE_KEY
        .set(key)
        .map_err(|_| StorageError::KeyAlreadyConfigured)
}

/// Opens the per-profile database after its key has been supplied. The provider
/// stays process-local, which prevents a second path or a second profile from
/// silently replacing active MLS state.
pub fn initialize_persistent_provider(path: impl AsRef<Path>) -> Result<(), StorageError> {
    storage_key()?;
    if PERSISTENT_PROVIDER.get().is_some() {
        return Ok(());
    }
    let provider = Mutex::new(EncryptedSqliteMlsProvider::open(path)?);
    let _ = PERSISTENT_PROVIDER.set(provider);
    Ok(())
}

/// Creates a local group in the already initialized process-local provider.
/// The caller must transmit resulting MLS commits/welcomes separately; no
/// unencrypted group data is accepted or sent here.
pub fn create_local_group(device_identity: &[u8]) -> Result<Vec<u8>, StorageError> {
    let provider = PERSISTENT_PROVIDER
        .get()
        .ok_or_else(|| StorageError::OpenMls("MLS provider is not initialized".to_owned()))?;
    let provider = provider
        .lock()
        .map_err(|_| StorageError::OpenMls("MLS provider lock is poisoned".to_owned()))?;
    provider.create_group(device_identity)
}

/// Produces a public MLS KeyPackage for an invitation. Its matching private
/// material and signer are persisted only in the encrypted local provider.
pub fn create_key_package(device_identity: &[u8]) -> Result<Vec<u8>, StorageError> {
    if device_identity.is_empty() || device_identity.len() > 256 {
        return Err(StorageError::OpenMls(
            "invalid MLS device identity".to_owned(),
        ));
    }
    let provider = PERSISTENT_PROVIDER
        .get()
        .ok_or_else(|| StorageError::OpenMls("MLS provider is not initialized".to_owned()))?;
    let provider = provider
        .lock()
        .map_err(|_| StorageError::OpenMls("MLS provider lock is poisoned".to_owned()))?;
    provider.create_key_package(device_identity)
}

/// Validates a remote public KeyPackage, creates the membership Commit and
/// Welcome, advances the local epoch, and returns only MLS wire bytes.
pub fn add_member(group_id: &[u8], key_package: &[u8]) -> Result<AddMemberOutput, StorageError> {
    let provider = PERSISTENT_PROVIDER
        .get()
        .ok_or_else(|| StorageError::OpenMls("MLS provider is not initialized".to_owned()))?;
    let provider = provider
        .lock()
        .map_err(|_| StorageError::OpenMls("MLS provider lock is poisoned".to_owned()))?;
    provider.add_member(group_id, key_package)
}

/// Creates an authenticated Remove Commit for the leaf whose BasicCredential
/// identity matches `member_identity`, then advances the local epoch.
pub fn remove_member(group_id: &[u8], member_identity: &[u8]) -> Result<Vec<u8>, StorageError> {
    with_persistent_provider(|provider| provider.remove_member(group_id, member_identity))
}

/// Validates an MLS Welcome and persists the joined group state.
pub fn join_from_welcome(welcome: &[u8]) -> Result<Vec<u8>, StorageError> {
    let provider = PERSISTENT_PROVIDER
        .get()
        .ok_or_else(|| StorageError::OpenMls("MLS provider is not initialized".to_owned()))?;
    let provider = provider
        .lock()
        .map_err(|_| StorageError::OpenMls("MLS provider lock is poisoned".to_owned()))?;
    provider.join_from_welcome(welcome)
}

pub fn create_application_message(
    group_id: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, StorageError> {
    with_persistent_provider(|provider| provider.create_application_message(group_id, plaintext))
}

pub fn process_application_message(
    group_id: &[u8],
    message: &[u8],
) -> Result<Vec<u8>, StorageError> {
    with_persistent_provider(|provider| provider.process_application_message(group_id, message))
}

pub fn process_commit(group_id: &[u8], message: &[u8]) -> Result<(), StorageError> {
    with_persistent_provider(|provider| provider.process_commit(group_id, message))
}

fn with_persistent_provider<T>(
    operation: impl FnOnce(&EncryptedSqliteMlsProvider) -> Result<T, StorageError>,
) -> Result<T, StorageError> {
    let provider = PERSISTENT_PROVIDER
        .get()
        .ok_or_else(|| StorageError::OpenMls("MLS provider is not initialized".to_owned()))?;
    let provider = provider
        .lock()
        .map_err(|_| StorageError::OpenMls("MLS provider lock is poisoned".to_owned()))?;
    operation(&provider)
}

/// Permanently removes a locally stored MLS group. This is for an explicit
/// user cancellation/deletion only; transient network failures must retain the
/// state for a durable registration retry.
pub fn delete_local_group(group_id: &[u8]) -> Result<(), StorageError> {
    if !(8..=64).contains(&group_id.len()) {
        return Err(StorageError::OpenMls("invalid MLS group id".to_owned()));
    }
    let provider = PERSISTENT_PROVIDER
        .get()
        .ok_or_else(|| StorageError::OpenMls("MLS provider is not initialized".to_owned()))?;
    let provider = provider
        .lock()
        .map_err(|_| StorageError::OpenMls("MLS provider lock is poisoned".to_owned()))?;
    let mut group = MlsGroup::load(provider.storage(), &GroupId::from_slice(group_id))
        .map_err(|error| StorageError::OpenMls(error.to_string()))?
        .ok_or_else(|| StorageError::OpenMls("MLS group does not exist".to_owned()))?;
    group
        .delete(provider.storage())
        .map_err(|error| StorageError::OpenMls(error.to_string()))
}

/// Codec used by OpenMLS' upstream SQLite provider. Query keys must remain
/// stable, hence deterministic AES-SIV encryption. AES-SIV is specifically
/// nonce-misuse resistant; it intentionally leaks equality of equal SQLite
/// values, but no plaintext.
#[derive(Default)]
pub struct EncryptedJsonCodec;

impl Codec for EncryptedJsonCodec {
    type Error = StorageError;

    fn to_vec<T: Serialize>(value: &T) -> Result<Vec<u8>, Self::Error> {
        let plain = serde_json::to_vec(value)?;
        let cipher =
            Aes256SivAead::new_from_slice(storage_key()?).map_err(|_| StorageError::Encryption)?;
        let encrypted = cipher
            .encrypt(
                &fixed_nonce(),
                Payload {
                    msg: &plain,
                    aad: STORAGE_AAD,
                },
            )
            .map_err(|_| StorageError::Encryption)?;
        let mut encoded = Vec::with_capacity(1 + encrypted.len());
        encoded.push(STORAGE_RECORD_VERSION);
        encoded.extend(encrypted);
        Ok(encoded)
    }

    fn from_slice<T: DeserializeOwned>(slice: &[u8]) -> Result<T, Self::Error> {
        let (version, encrypted) = slice.split_first().ok_or(StorageError::MalformedRecord)?;
        if *version != STORAGE_RECORD_VERSION {
            return Err(StorageError::UnsupportedRecordVersion);
        }
        if encrypted.len() < MIN_ENCRYPTED_RECORD_BYTES - 1 {
            return Err(StorageError::MalformedRecord);
        }
        let cipher =
            Aes256SivAead::new_from_slice(storage_key()?).map_err(|_| StorageError::Decryption)?;
        let plain = cipher
            .decrypt(
                &fixed_nonce(),
                Payload {
                    msg: encrypted,
                    aad: STORAGE_AAD,
                },
            )
            .map_err(|_| StorageError::Decryption)?;
        Ok(serde_json::from_slice(&plain)?)
    }
}

fn storage_key() -> Result<&'static [u8; 64], StorageError> {
    STORAGE_KEY.get().ok_or(StorageError::KeyNotConfigured)
}

fn fixed_nonce() -> Nonce {
    Nonce::default()
}

pub struct EncryptedSqliteMlsProvider {
    crypto: RustCrypto,
    storage: SqliteStorageProvider<EncryptedJsonCodec, Connection>,
}

impl EncryptedSqliteMlsProvider {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, StorageError> {
        let connection =
            Connection::open(path).map_err(|error| StorageError::Sqlite(error.to_string()))?;
        let mut storage = SqliteStorageProvider::new(connection);
        storage
            .run_migrations()
            .map_err(|error| StorageError::Migration(error.to_string()))?;
        Ok(Self {
            crypto: RustCrypto::default(),
            storage,
        })
    }

    /// Creates a locally persisted one-member MLS group for the registered
    /// device. This method does not send members or messages to the server.
    pub fn create_group(&self, device_identity: &[u8]) -> Result<Vec<u8>, StorageError> {
        if device_identity.is_empty() || device_identity.len() > 256 {
            return Err(StorageError::OpenMls(
                "invalid MLS device identity".to_owned(),
            ));
        }
        let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
        let signer = SignatureKeyPair::new(ciphersuite.signature_algorithm())
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        signer
            .store(self.storage())
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        let credential = CredentialWithKey {
            credential: BasicCredential::new(device_identity.to_vec()).into(),
            signature_key: signer.to_public_vec().into(),
        };
        let config = MlsGroupCreateConfig::builder()
            .ciphersuite(ciphersuite)
            .use_ratchet_tree_extension(true)
            .build();
        let group = MlsGroup::new(self, &signer, &config, credential)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        Ok(group.group_id().as_slice().to_vec())
    }

    pub fn create_key_package(&self, device_identity: &[u8]) -> Result<Vec<u8>, StorageError> {
        if device_identity.is_empty() || device_identity.len() > 256 {
            return Err(StorageError::OpenMls(
                "invalid MLS device identity".to_owned(),
            ));
        }
        let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
        let signer = SignatureKeyPair::new(ciphersuite.signature_algorithm())
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        signer
            .store(self.storage())
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        let credential = CredentialWithKey {
            credential: BasicCredential::new(device_identity.to_vec()).into(),
            signature_key: signer.to_public_vec().into(),
        };
        KeyPackage::builder()
            .build(ciphersuite, self, &signer, credential)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?
            .key_package()
            .tls_serialize_detached()
            .map_err(|error| StorageError::OpenMls(error.to_string()))
    }

    pub fn add_member(
        &self,
        group_id: &[u8],
        key_package: &[u8],
    ) -> Result<AddMemberOutput, StorageError> {
        if !(8..=64).contains(&group_id.len())
            || key_package.is_empty()
            || key_package.len() > 65_536
        {
            return Err(StorageError::OpenMls(
                "invalid MLS group id or KeyPackage".to_owned(),
            ));
        }
        let mut group = MlsGroup::load(self.storage(), &GroupId::from_slice(group_id))
            .map_err(|error| StorageError::OpenMls(error.to_string()))?
            .ok_or_else(|| StorageError::OpenMls("MLS group does not exist".to_owned()))?;
        let signature_key = group
            .own_leaf_node()
            .ok_or_else(|| StorageError::OpenMls("own MLS leaf is missing".to_owned()))?
            .signature_key()
            .as_slice()
            .to_vec();
        let signer = SignatureKeyPair::read(
            self.storage(),
            &signature_key,
            group.ciphersuite().signature_algorithm(),
        )
        .ok_or_else(|| StorageError::OpenMls("MLS signer is missing".to_owned()))?;
        let key_package = KeyPackageIn::tls_deserialize_exact(key_package)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?
            .validate(self.crypto(), ProtocolVersion::Mls10)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        let (commit, welcome, _) = group
            .add_members(self, &signer, &[key_package])
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        let output = AddMemberOutput {
            commit: commit
                .to_bytes()
                .map_err(|error| StorageError::OpenMls(error.to_string()))?,
            welcome: welcome
                .to_bytes()
                .map_err(|error| StorageError::OpenMls(error.to_string()))?,
        };
        group
            .merge_pending_commit(self)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        Ok(output)
    }

    pub fn remove_member(
        &self,
        group_id: &[u8],
        member_identity: &[u8],
    ) -> Result<Vec<u8>, StorageError> {
        if !(8..=64).contains(&group_id.len())
            || member_identity.is_empty()
            || member_identity.len() > 256
        {
            return Err(StorageError::OpenMls(
                "invalid MLS group id or member identity".to_owned(),
            ));
        }
        let mut group = MlsGroup::load(self.storage(), &GroupId::from_slice(group_id))
            .map_err(|error| StorageError::OpenMls(error.to_string()))?
            .ok_or_else(|| StorageError::OpenMls("MLS group does not exist".to_owned()))?;
        let own_index = group.own_leaf_index();
        let removed_index = group
            .members()
            .find(|member| member.credential.serialized_content() == member_identity)
            .map(|member| member.index)
            .ok_or_else(|| StorageError::OpenMls("MLS member does not exist".to_owned()))?;
        if removed_index == own_index {
            return Err(StorageError::OpenMls(
                "cannot remove own MLS leaf through this operation".to_owned(),
            ));
        }
        let signature_key = group
            .own_leaf_node()
            .ok_or_else(|| StorageError::OpenMls("own MLS leaf is missing".to_owned()))?
            .signature_key()
            .as_slice()
            .to_vec();
        let signer = SignatureKeyPair::read(
            self.storage(),
            &signature_key,
            group.ciphersuite().signature_algorithm(),
        )
        .ok_or_else(|| StorageError::OpenMls("MLS signer is missing".to_owned()))?;
        let (commit, welcome, _) = group
            .remove_members(self, &signer, &[removed_index])
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        if welcome.is_some() {
            return Err(StorageError::OpenMls(
                "remove commit unexpectedly produced Welcome".to_owned(),
            ));
        }
        let commit = commit
            .to_bytes()
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        group
            .merge_pending_commit(self)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        Ok(commit)
    }

    pub fn join_from_welcome(&self, welcome: &[u8]) -> Result<Vec<u8>, StorageError> {
        if welcome.is_empty() || welcome.len() > 2_800_000 {
            return Err(StorageError::OpenMls("invalid MLS Welcome".to_owned()));
        }
        let message = MlsMessageIn::tls_deserialize_exact(welcome)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        let welcome = match message.extract() {
            MlsMessageBodyIn::Welcome(welcome) => welcome,
            _ => return Err(StorageError::OpenMls("expected MLS Welcome".to_owned())),
        };
        let staged = StagedWelcome::new_from_welcome(
            self,
            &MlsGroupJoinConfig::builder()
                .use_ratchet_tree_extension(true)
                .build(),
            welcome,
            None,
        )
        .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        let group = staged
            .into_group(self)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        Ok(group.group_id().as_slice().to_vec())
    }

    pub fn create_application_message(
        &self,
        group_id: &[u8],
        plaintext: &[u8],
    ) -> Result<Vec<u8>, StorageError> {
        if !(8..=64).contains(&group_id.len())
            || plaintext.is_empty()
            || plaintext.len() > 1_048_576
        {
            return Err(StorageError::OpenMls(
                "invalid MLS group id or application data".to_owned(),
            ));
        }
        let mut group = MlsGroup::load(self.storage(), &GroupId::from_slice(group_id))
            .map_err(|error| StorageError::OpenMls(error.to_string()))?
            .ok_or_else(|| StorageError::OpenMls("MLS group does not exist".to_owned()))?;
        let signature_key = group
            .own_leaf_node()
            .ok_or_else(|| StorageError::OpenMls("own MLS leaf is missing".to_owned()))?
            .signature_key()
            .as_slice();
        let signer = SignatureKeyPair::read(
            self.storage(),
            signature_key,
            group.ciphersuite().signature_algorithm(),
        )
        .ok_or_else(|| StorageError::OpenMls("MLS signer is missing".to_owned()))?;
        group
            .create_message(self, &signer, plaintext)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?
            .to_bytes()
            .map_err(|error| StorageError::OpenMls(error.to_string()))
    }

    pub fn process_application_message(
        &self,
        group_id: &[u8],
        message: &[u8],
    ) -> Result<Vec<u8>, StorageError> {
        let mut group = self.load_group_for_message(group_id, message)?;
        let message = MlsMessageIn::tls_deserialize_exact(message)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?
            .try_into_protocol_message()
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        let processed = group
            .process_message(self, message)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(application) => {
                Ok(application.into_bytes())
            }
            _ => Err(StorageError::OpenMls(
                "expected MLS application message".to_owned(),
            )),
        }
    }

    pub fn process_commit(&self, group_id: &[u8], message: &[u8]) -> Result<(), StorageError> {
        let mut group = self.load_group_for_message(group_id, message)?;
        let message = MlsMessageIn::tls_deserialize_exact(message)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?
            .try_into_protocol_message()
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        let processed = group
            .process_message(self, message)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?;
        match processed.into_content() {
            ProcessedMessageContent::StagedCommitMessage(commit) => group
                .merge_staged_commit(self, *commit)
                .map_err(|error| StorageError::OpenMls(error.to_string())),
            _ => Err(StorageError::OpenMls("expected MLS Commit".to_owned())),
        }
    }

    fn load_group_for_message(
        &self,
        group_id: &[u8],
        message: &[u8],
    ) -> Result<MlsGroup, StorageError> {
        if !(8..=64).contains(&group_id.len()) || message.is_empty() || message.len() > 2_800_000 {
            return Err(StorageError::OpenMls(
                "invalid MLS group id or message".to_owned(),
            ));
        }
        MlsGroup::load(self.storage(), &GroupId::from_slice(group_id))
            .map_err(|error| StorageError::OpenMls(error.to_string()))?
            .ok_or_else(|| StorageError::OpenMls("MLS group does not exist".to_owned()))
    }

    #[cfg(test)]
    pub(crate) fn open_in_memory() -> Result<Self, StorageError> {
        let connection = Connection::open_in_memory()
            .map_err(|error| StorageError::Sqlite(error.to_string()))?;
        let mut storage = SqliteStorageProvider::new(connection);
        storage
            .run_migrations()
            .map_err(|error| StorageError::Migration(error.to_string()))?;
        Ok(Self {
            crypto: RustCrypto::default(),
            storage,
        })
    }
}

impl OpenMlsProvider for EncryptedSqliteMlsProvider {
    type CryptoProvider = RustCrypto;
    type RandProvider = RustCrypto;
    type StorageProvider = SqliteStorageProvider<EncryptedJsonCodec, Connection>;

    fn storage(&self) -> &Self::StorageProvider {
        &self.storage
    }

    fn crypto(&self) -> &Self::CryptoProvider {
        &self.crypto
    }

    fn rand(&self) -> &Self::RandProvider {
        &self.crypto
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub(crate) fn configure_test_storage_key() {
        match configure_storage_key(&[0xA5; 64]) {
            Ok(()) | Err(StorageError::KeyAlreadyConfigured) => {}
            Err(error) => panic!("could not configure test MLS storage key: {error}"),
        }
    }

    #[test]
    fn encrypted_codec_round_trips_and_rejects_tampering() {
        configure_test_storage_key();
        assert!(configure_storage_key(&[0xA5; 64]).is_ok());
        assert!(matches!(
            configure_storage_key(&[0x5A; 64]),
            Err(StorageError::KeyAlreadyConfigured)
        ));

        let encoded = EncryptedJsonCodec::to_vec(&"family MLS state").unwrap();
        assert!(!encoded.windows(6).any(|window| window == b"family"));
        assert_eq!(
            EncryptedJsonCodec::from_slice::<String>(&encoded).unwrap(),
            "family MLS state"
        );

        let mut tampered = encoded;
        *tampered.last_mut().unwrap() ^= 1;
        assert!(matches!(
            EncryptedJsonCodec::from_slice::<String>(&tampered),
            Err(StorageError::Decryption)
        ));
    }
}
