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
    BasicCredential, Ciphersuite, CredentialWithKey, GroupId, MlsGroup, MlsGroupCreateConfig,
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
