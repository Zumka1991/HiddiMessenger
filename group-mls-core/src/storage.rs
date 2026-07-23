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
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize as SerdeDeserialize, Serialize, de::DeserializeOwned};
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

#[derive(Clone, Debug, SerdeDeserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PendingOperationKind {
    AddMember,
    RemoveMember,
}

#[derive(Clone, Debug, SerdeDeserialize, Eq, PartialEq, Serialize)]
pub struct PendingMembershipOperation {
    pub operation_id: String,
    pub kind: PendingOperationKind,
    pub group_id: Vec<u8>,
    pub context: Vec<u8>,
    pub commit: Vec<u8>,
    pub welcome: Option<Vec<u8>>,
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
    let mut provider = provider
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
    let mut provider = provider
        .lock()
        .map_err(|_| StorageError::OpenMls("MLS provider lock is poisoned".to_owned()))?;
    provider.create_key_package(device_identity)
}

/// Validates a remote public KeyPackage, creates the membership Commit and
/// Welcome, advances the local epoch, and returns only MLS wire bytes.
pub fn add_member(
    group_id: &[u8],
    key_package: &[u8],
    operation_id: &str,
    context: &[u8],
) -> Result<AddMemberOutput, StorageError> {
    let provider = PERSISTENT_PROVIDER
        .get()
        .ok_or_else(|| StorageError::OpenMls("MLS provider is not initialized".to_owned()))?;
    let mut provider = provider
        .lock()
        .map_err(|_| StorageError::OpenMls("MLS provider lock is poisoned".to_owned()))?;
    provider.add_member(group_id, key_package, operation_id, context)
}

/// Creates an authenticated Remove Commit for the leaf whose BasicCredential
/// identity matches `member_identity`, then advances the local epoch.
pub fn remove_member(
    group_id: &[u8],
    member_identity: &[u8],
    operation_id: &str,
    context: &[u8],
) -> Result<Vec<u8>, StorageError> {
    with_persistent_provider(|provider| {
        provider.remove_member(group_id, member_identity, operation_id, context)
    })
}

pub fn pending_membership_operations() -> Result<Vec<PendingMembershipOperation>, StorageError> {
    with_persistent_provider(|provider| provider.pending_membership_operations())
}

pub fn ack_membership_operation(operation_id: &str) -> Result<(), StorageError> {
    with_persistent_provider(|provider| provider.ack_membership_operation(operation_id))
}

/// Validates an MLS Welcome and persists the joined group state.
pub fn join_from_welcome(welcome: &[u8]) -> Result<Vec<u8>, StorageError> {
    let provider = PERSISTENT_PROVIDER
        .get()
        .ok_or_else(|| StorageError::OpenMls("MLS provider is not initialized".to_owned()))?;
    let mut provider = provider
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
    operation: impl FnOnce(&mut EncryptedSqliteMlsProvider) -> Result<T, StorageError>,
) -> Result<T, StorageError> {
    let provider = PERSISTENT_PROVIDER
        .get()
        .ok_or_else(|| StorageError::OpenMls("MLS provider is not initialized".to_owned()))?;
    let mut provider = provider
        .lock()
        .map_err(|_| StorageError::OpenMls("MLS provider lock is poisoned".to_owned()))?;
    operation(&mut provider)
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
    let mut provider = provider
        .lock()
        .map_err(|_| StorageError::OpenMls("MLS provider lock is poisoned".to_owned()))?;
    provider.delete_group(group_id)
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
    connection: Connection,
}

impl EncryptedSqliteMlsProvider {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, StorageError> {
        let mut connection =
            Connection::open(path).map_err(|error| StorageError::Sqlite(error.to_string()))?;
        initialize_connection(&mut connection)?;
        Ok(Self {
            crypto: RustCrypto::default(),
            connection,
        })
    }

    pub fn create_group(&mut self, device_identity: &[u8]) -> Result<Vec<u8>, StorageError> {
        self.transaction(|provider| provider.create_group(device_identity))
    }

    pub fn create_key_package(&mut self, device_identity: &[u8]) -> Result<Vec<u8>, StorageError> {
        self.transaction(|provider| provider.create_key_package(device_identity))
    }

    pub fn add_member(
        &mut self,
        group_id: &[u8],
        key_package: &[u8],
        operation_id: &str,
        context: &[u8],
    ) -> Result<AddMemberOutput, StorageError> {
        self.transaction(|provider| {
            provider.add_member(group_id, key_package, operation_id, context)
        })
    }

    pub fn remove_member(
        &mut self,
        group_id: &[u8],
        member_identity: &[u8],
        operation_id: &str,
        context: &[u8],
    ) -> Result<Vec<u8>, StorageError> {
        self.transaction(|provider| {
            provider.remove_member(group_id, member_identity, operation_id, context)
        })
    }

    pub fn join_from_welcome(&mut self, welcome: &[u8]) -> Result<Vec<u8>, StorageError> {
        self.transaction(|provider| provider.join_from_welcome(welcome))
    }

    pub fn create_application_message(
        &mut self,
        group_id: &[u8],
        plaintext: &[u8],
    ) -> Result<Vec<u8>, StorageError> {
        self.transaction(|provider| provider.create_application_message(group_id, plaintext))
    }

    pub fn process_application_message(
        &mut self,
        group_id: &[u8],
        message: &[u8],
    ) -> Result<Vec<u8>, StorageError> {
        self.transaction(|provider| provider.process_application_message(group_id, message))
    }

    pub fn process_commit(&mut self, group_id: &[u8], message: &[u8]) -> Result<(), StorageError> {
        self.transaction(|provider| provider.process_commit(group_id, message))
    }

    pub fn delete_group(&mut self, group_id: &[u8]) -> Result<(), StorageError> {
        self.transaction(|provider| provider.delete_group(group_id))
    }

    pub fn pending_membership_operations(
        &mut self,
    ) -> Result<Vec<PendingMembershipOperation>, StorageError> {
        let mut statement = self
            .connection
            .prepare("SELECT record FROM hiddi_mls_operation_journal ORDER BY rowid")
            .map_err(sqlite_error)?;
        statement
            .query_map([], |row| row.get::<_, Vec<u8>>(0))
            .map_err(sqlite_error)?
            .map(|record| {
                let record = record.map_err(sqlite_error)?;
                EncryptedJsonCodec::from_slice(&record)
            })
            .collect()
    }

    pub fn ack_membership_operation(&mut self, operation_id: &str) -> Result<(), StorageError> {
        validate_operation_id(operation_id)?;
        self.connection
            .execute(
                "DELETE FROM hiddi_mls_operation_journal WHERE operation_id = ?1",
                params![operation_id],
            )
            .map_err(sqlite_error)?;
        Ok(())
    }

    fn transaction<T>(
        &mut self,
        operation: impl FnOnce(&TransactionalMlsProvider<'_>) -> Result<T, StorageError>,
    ) -> Result<T, StorageError> {
        let transaction = self.connection.transaction().map_err(sqlite_error)?;
        let output = {
            let provider = TransactionalMlsProvider {
                crypto: &self.crypto,
                storage: SqliteStorageProvider::new(&*transaction),
                connection: &transaction,
            };
            operation(&provider)?
        };
        transaction.commit().map_err(sqlite_error)?;
        Ok(output)
    }

    #[cfg(test)]
    pub(crate) fn open_in_memory() -> Result<Self, StorageError> {
        let mut connection = Connection::open_in_memory()
            .map_err(|error| StorageError::Sqlite(error.to_string()))?;
        initialize_connection(&mut connection)?;
        Ok(Self {
            crypto: RustCrypto::default(),
            connection,
        })
    }

    #[cfg(test)]
    pub(crate) fn group_exists(&self, group_id: &[u8]) -> Result<bool, StorageError> {
        let provider = TransactionalMlsProvider {
            crypto: &self.crypto,
            storage: SqliteStorageProvider::new(&self.connection),
            connection: &self.connection,
        };
        Ok(
            MlsGroup::load(provider.storage(), &GroupId::from_slice(group_id))
                .map_err(|error| StorageError::OpenMls(error.to_string()))?
                .is_some(),
        )
    }
}

struct TransactionalMlsProvider<'a> {
    crypto: &'a RustCrypto,
    storage: SqliteStorageProvider<EncryptedJsonCodec, &'a Connection>,
    connection: &'a Connection,
}

impl TransactionalMlsProvider<'_> {
    fn create_group(&self, device_identity: &[u8]) -> Result<Vec<u8>, StorageError> {
        validate_device_identity(device_identity)?;
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

    fn create_key_package(&self, device_identity: &[u8]) -> Result<Vec<u8>, StorageError> {
        validate_device_identity(device_identity)?;
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

    fn add_member(
        &self,
        group_id: &[u8],
        key_package: &[u8],
        operation_id: &str,
        context: &[u8],
    ) -> Result<AddMemberOutput, StorageError> {
        validate_membership_operation(group_id, operation_id, context)?;
        if key_package.is_empty() || key_package.len() > 65_536 {
            return Err(StorageError::OpenMls("invalid MLS KeyPackage".to_owned()));
        }
        if let Some(existing) = self.load_journal(operation_id)? {
            validate_existing_operation(
                &existing,
                PendingOperationKind::AddMember,
                group_id,
                context,
            )?;
            return Ok(AddMemberOutput {
                commit: existing.commit,
                welcome: existing.welcome.ok_or_else(|| {
                    StorageError::OpenMls("add-member journal has no Welcome".to_owned())
                })?,
            });
        }
        let mut group = self.load_group(group_id)?;
        let signer = self.signer(&group)?;
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
        self.store_journal(&PendingMembershipOperation {
            operation_id: operation_id.to_owned(),
            kind: PendingOperationKind::AddMember,
            group_id: group_id.to_vec(),
            context: context.to_vec(),
            commit: output.commit.clone(),
            welcome: Some(output.welcome.clone()),
        })?;
        Ok(output)
    }

    fn remove_member(
        &self,
        group_id: &[u8],
        member_identity: &[u8],
        operation_id: &str,
        context: &[u8],
    ) -> Result<Vec<u8>, StorageError> {
        validate_membership_operation(group_id, operation_id, context)?;
        validate_device_identity(member_identity)?;
        if let Some(existing) = self.load_journal(operation_id)? {
            validate_existing_operation(
                &existing,
                PendingOperationKind::RemoveMember,
                group_id,
                context,
            )?;
            return Ok(existing.commit);
        }
        let mut group = self.load_group(group_id)?;
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
        let signer = self.signer(&group)?;
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
        self.store_journal(&PendingMembershipOperation {
            operation_id: operation_id.to_owned(),
            kind: PendingOperationKind::RemoveMember,
            group_id: group_id.to_vec(),
            context: context.to_vec(),
            commit: commit.clone(),
            welcome: None,
        })?;
        Ok(commit)
    }

    fn join_from_welcome(&self, welcome: &[u8]) -> Result<Vec<u8>, StorageError> {
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

    fn create_application_message(
        &self,
        group_id: &[u8],
        plaintext: &[u8],
    ) -> Result<Vec<u8>, StorageError> {
        if plaintext.is_empty() || plaintext.len() > 1_048_576 {
            return Err(StorageError::OpenMls(
                "invalid MLS application data".to_owned(),
            ));
        }
        let mut group = self.load_group(group_id)?;
        let signer = self.signer(&group)?;
        group
            .create_message(self, &signer, plaintext)
            .map_err(|error| StorageError::OpenMls(error.to_string()))?
            .to_bytes()
            .map_err(|error| StorageError::OpenMls(error.to_string()))
    }

    fn process_application_message(
        &self,
        group_id: &[u8],
        message: &[u8],
    ) -> Result<Vec<u8>, StorageError> {
        validate_message(group_id, message)?;
        let mut group = self.load_group(group_id)?;
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

    fn process_commit(&self, group_id: &[u8], message: &[u8]) -> Result<(), StorageError> {
        validate_message(group_id, message)?;
        let mut group = self.load_group(group_id)?;
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

    fn delete_group(&self, group_id: &[u8]) -> Result<(), StorageError> {
        let mut group = self.load_group(group_id)?;
        group
            .delete(self.storage())
            .map_err(|error| StorageError::OpenMls(error.to_string()))
    }

    fn load_group(&self, group_id: &[u8]) -> Result<MlsGroup, StorageError> {
        if !(8..=64).contains(&group_id.len()) {
            return Err(StorageError::OpenMls("invalid MLS group id".to_owned()));
        }
        MlsGroup::load(self.storage(), &GroupId::from_slice(group_id))
            .map_err(|error| StorageError::OpenMls(error.to_string()))?
            .ok_or_else(|| StorageError::OpenMls("MLS group does not exist".to_owned()))
    }

    fn signer(&self, group: &MlsGroup) -> Result<SignatureKeyPair, StorageError> {
        let signature_key = group
            .own_leaf_node()
            .ok_or_else(|| StorageError::OpenMls("own MLS leaf is missing".to_owned()))?
            .signature_key()
            .as_slice();
        SignatureKeyPair::read(
            self.storage(),
            signature_key,
            group.ciphersuite().signature_algorithm(),
        )
        .ok_or_else(|| StorageError::OpenMls("MLS signer is missing".to_owned()))
    }

    fn load_journal(
        &self,
        operation_id: &str,
    ) -> Result<Option<PendingMembershipOperation>, StorageError> {
        let record = self
            .connection
            .query_row(
                "SELECT record FROM hiddi_mls_operation_journal WHERE operation_id = ?1",
                params![operation_id],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .optional()
            .map_err(sqlite_error)?;
        record
            .map(|value| EncryptedJsonCodec::from_slice(&value))
            .transpose()
    }

    fn store_journal(&self, operation: &PendingMembershipOperation) -> Result<(), StorageError> {
        let record = EncryptedJsonCodec::to_vec(operation)?;
        self.connection
            .execute(
                "INSERT INTO hiddi_mls_operation_journal (operation_id, record)
                 VALUES (?1, ?2)",
                params![operation.operation_id, record],
            )
            .map_err(sqlite_error)?;
        Ok(())
    }
}

impl<'a> OpenMlsProvider for TransactionalMlsProvider<'a> {
    type CryptoProvider = RustCrypto;
    type RandProvider = RustCrypto;
    type StorageProvider = SqliteStorageProvider<EncryptedJsonCodec, &'a Connection>;

    fn storage(&self) -> &Self::StorageProvider {
        &self.storage
    }

    fn crypto(&self) -> &Self::CryptoProvider {
        self.crypto
    }

    fn rand(&self) -> &Self::RandProvider {
        self.crypto
    }
}

fn initialize_connection(connection: &mut Connection) -> Result<(), StorageError> {
    {
        let mut storage = SqliteStorageProvider::<EncryptedJsonCodec, _>::new(&mut *connection);
        storage
            .run_migrations()
            .map_err(|error| StorageError::Migration(error.to_string()))?;
    }
    connection
        .execute_batch(
            "CREATE TABLE IF NOT EXISTS hiddi_mls_operation_journal (
                operation_id TEXT PRIMARY KEY NOT NULL,
                record BLOB NOT NULL,
                created_at INTEGER NOT NULL DEFAULT (unixepoch())
             );",
        )
        .map_err(sqlite_error)
}

fn validate_device_identity(identity: &[u8]) -> Result<(), StorageError> {
    if identity.is_empty() || identity.len() > 256 {
        return Err(StorageError::OpenMls(
            "invalid MLS device identity".to_owned(),
        ));
    }
    Ok(())
}

fn validate_membership_operation(
    group_id: &[u8],
    operation_id: &str,
    context: &[u8],
) -> Result<(), StorageError> {
    if !(8..=64).contains(&group_id.len()) || context.is_empty() || context.len() > 65_536 {
        return Err(StorageError::OpenMls(
            "invalid MLS membership operation".to_owned(),
        ));
    }
    validate_operation_id(operation_id)
}

fn validate_operation_id(operation_id: &str) -> Result<(), StorageError> {
    if !(16..=128).contains(&operation_id.len())
        || !operation_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    {
        return Err(StorageError::OpenMls("invalid MLS operation id".to_owned()));
    }
    Ok(())
}

fn validate_message(group_id: &[u8], message: &[u8]) -> Result<(), StorageError> {
    if !(8..=64).contains(&group_id.len()) || message.is_empty() || message.len() > 2_800_000 {
        return Err(StorageError::OpenMls(
            "invalid MLS group id or message".to_owned(),
        ));
    }
    Ok(())
}

fn validate_existing_operation(
    existing: &PendingMembershipOperation,
    kind: PendingOperationKind,
    group_id: &[u8],
    context: &[u8],
) -> Result<(), StorageError> {
    if existing.kind != kind || existing.group_id != group_id || existing.context != context {
        return Err(StorageError::OpenMls(
            "MLS operation id was reused with different data".to_owned(),
        ));
    }
    Ok(())
}

fn sqlite_error(error: rusqlite::Error) -> StorageError {
    StorageError::Sqlite(error.to_string())
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
