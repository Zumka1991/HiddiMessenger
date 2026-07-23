mod attachment_storage;

use std::{
    collections::HashMap,
    env,
    net::SocketAddr,
    path::Path,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use anyhow::Context;
use attachment_storage::AttachmentStorageBackend;
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Query, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{get, post, put},
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use rand::Rng;
use rusqlite::{Connection, ErrorCode, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::Notify;
use tower_http::trace::TraceLayer;
use tracing::info;
use uuid::Uuid;

#[derive(Clone)]
struct AppState {
    db: Arc<Mutex<Connection>>,
    bootstrap_secret: Arc<str>,
    message_notify: Arc<Notify>,
    attachment_backend: AttachmentStorageBackend,
    rate_limiter: Arc<RateLimiter>,
}

struct RateLimiter {
    entries: Mutex<HashMap<String, RateLimitWindow>>,
}

struct RateLimitWindow {
    started: Instant,
    count: u32,
}

impl RateLimiter {
    fn check(&self, key: impl Into<String>, limit: u32, window: Duration) -> Result<(), Error> {
        let now = Instant::now();
        let mut entries = self.entries.lock().map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "rate limiter unavailable",
            )
        })?;
        entries.retain(|_, entry| now.duration_since(entry.started) < window);
        let entry = entries.entry(key.into()).or_insert(RateLimitWindow {
            started: now,
            count: 0,
        });
        if entry.count >= limit {
            return Err(Error(StatusCode::TOO_MANY_REQUESTS, "rate limit exceeded"));
        }
        entry.count += 1;
        Ok(())
    }
}

#[derive(Serialize)]
struct Health {
    status: &'static str,
    attachment_backend: &'static str,
}

#[derive(Serialize)]
struct InviteResponse {
    invite_code: String,
}

#[derive(Deserialize)]
struct RegisterRequest {
    nickname: String,
    invite_code: String,
    identity_public_key: String,
    registration_id: u32,
}

#[derive(Serialize)]
struct RegisterResponse {
    account_id: Uuid,
    device_id: Uuid,
    registration_id: u32,
    access_token: String,
}

#[derive(Serialize)]
struct CurrentDeviceResponse {
    device_id: Uuid,
    registration_id: u32,
}

#[derive(Serialize)]
struct UserResponse {
    account_id: Uuid,
    nickname: String,
    display_name: String,
    bio: String,
    avatar_version: Option<String>,
    identity_public_key: String,
}

#[derive(Deserialize)]
struct UserSearchQuery {
    query: String,
}

#[derive(Serialize)]
struct UserSearchItem {
    nickname: String,
    display_name: String,
    bio: String,
    avatar_version: Option<String>,
}

#[derive(Serialize)]
struct UserProfileResponse {
    nickname: String,
    display_name: String,
    bio: String,
    avatar_version: Option<String>,
}

#[derive(Deserialize)]
struct UpdateProfileRequest {
    display_name: String,
    bio: String,
}

#[derive(Deserialize)]
struct UploadAvatarRequest {
    image: String,
}

#[derive(Serialize)]
struct AvatarResponse {
    image: String,
    version: String,
}

#[derive(Serialize)]
struct AvatarVersionResponse {
    version: String,
}

#[derive(Serialize)]
struct BlockedUserResponse {
    nickname: String,
}

#[derive(Deserialize)]
struct SendMessageRequest {
    recipient_nickname: String,
    ciphertext: String,
}

#[derive(Deserialize)]
struct CreateGroupRequest {
    group_id: String,
    #[serde(default)]
    members: Vec<GroupMemberRequest>,
}

#[derive(Deserialize)]
struct GroupMemberRequest {
    nickname: String,
    role: String,
}

#[derive(Deserialize)]
struct AddGroupMemberRequest {
    nickname: String,
    #[serde(default = "default_group_member_role")]
    role: String,
}

#[derive(Deserialize)]
struct UpdateGroupRoleRequest {
    role: String,
}

fn default_group_member_role() -> String {
    "member".to_owned()
}

#[derive(Serialize)]
struct CreateGroupResponse {
    group_id: String,
}

#[derive(Serialize)]
struct GroupDetailsResponse {
    group_id: String,
    owner_nickname: String,
    members: Vec<GroupMemberResponse>,
}

#[derive(Serialize)]
struct GroupMemberResponse {
    nickname: String,
    role: String,
    device_id: String,
}

#[derive(Serialize)]
struct GroupDeletionResponse {
    deletion_id: Uuid,
    group_id: String,
}

#[derive(Deserialize)]
struct SendGroupEventRequest {
    client_event_id: String,
    kind: u8,
    recipient_nicknames: Vec<String>,
    envelope: String,
    #[serde(default)]
    remove_member_nickname: Option<String>,
}

#[derive(Serialize)]
struct SendGroupEventResponse {
    event_ids: Vec<Uuid>,
}

#[derive(Serialize)]
struct GroupEventResponse {
    event_id: Uuid,
    group_id: String,
    sender_nickname: String,
    kind: u8,
    envelope: String,
    created_at: String,
    removes_recipient: bool,
}

#[derive(Deserialize)]
struct UploadMlsKeyPackageRequest {
    key_package: String,
}
#[derive(Serialize)]
struct MlsKeyPackageResponse {
    nickname: String,
    key_package: String,
}

#[derive(Serialize)]
struct MessageResponse {
    message_id: Uuid,
    sender_nickname: String,
    ciphertext: String,
    created_at: String,
}

#[derive(Serialize)]
struct MessageStatusResponse {
    delivered: bool,
    read: bool,
}

#[derive(Serialize)]
struct ConversationDeletionResponse {
    peer_nickname: String,
}

#[derive(Deserialize, Default)]
struct DeleteMessageQuery {
    #[serde(default)]
    for_everyone: bool,
}

#[derive(Serialize)]
struct MessageDeletionResponse {
    deletion_id: Uuid,
    message_id: Uuid,
}

#[derive(Deserialize)]
struct UploadAttachmentRequest {
    recipient_nickname: String,
    ciphertext: String,
}

#[derive(Deserialize)]
struct UploadGroupAttachmentRequest {
    ciphertext: String,
}

#[derive(Serialize)]
struct UploadAttachmentResponse {
    attachment_id: Uuid,
}

#[derive(Serialize)]
struct AttachmentResponse {
    attachment_id: Uuid,
    ciphertext: String,
}

#[derive(Deserialize)]
struct PublicPreKey {
    id: u32,
    public_key: String,
    #[serde(default)]
    signature: Option<String>,
}

#[derive(Deserialize)]
struct UploadPrekeysRequest {
    signed_prekey: PublicPreKey,
    kyber_signed_prekey: PublicPreKey,
    #[serde(default)]
    one_time_prekeys: Vec<PublicPreKey>,
    #[serde(default)]
    kyber_one_time_prekeys: Vec<PublicPreKey>,
}

#[derive(Serialize)]
struct PrekeyBundleResponse {
    account_id: Uuid,
    nickname: String,
    device_id: Uuid,
    registration_id: u32,
    identity_public_key: String,
    signed_prekey: StoredPreKey,
    kyber_signed_prekey: StoredPreKey,
    one_time_prekey: Option<StoredPreKey>,
    kyber_one_time_prekey: Option<StoredPreKey>,
}

#[derive(Serialize)]
struct StoredPreKey {
    id: u32,
    public_key: String,
    signature: Option<String>,
}

struct AuthenticatedAccount {
    account_id: String,
    device_id: String,
}

#[derive(Serialize)]
struct ApiError {
    error: &'static str,
}

struct Error(StatusCode, &'static str);

impl IntoResponse for Error {
    fn into_response(self) -> Response {
        (self.0, Json(ApiError { error: self.1 })).into_response()
    }
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            env::var("RUST_LOG").unwrap_or_else(|_| "hiddi_server=info,tower_http=info".into()),
        )
        .init();

    let bootstrap_secret =
        env::var("HIDDI_BOOTSTRAP_SECRET").context("HIDDI_BOOTSTRAP_SECRET must be set")?;
    if bootstrap_secret.len() < 32 {
        anyhow::bail!("HIDDI_BOOTSTRAP_SECRET must be at least 32 characters");
    }

    let database_path =
        env::var("HIDDI_DATABASE_PATH").unwrap_or_else(|_| "../data/hiddi.db".into());
    if let Some(parent) = Path::new(&database_path).parent() {
        std::fs::create_dir_all(parent)?;
    }
    let db = Connection::open(&database_path)?;
    migrate(&db)?;
    let attachment_backend = AttachmentStorageBackend::from_environment()?;
    attachment_backend.ensure_ready()?;

    let state = AppState {
        db: Arc::new(Mutex::new(db)),
        bootstrap_secret: bootstrap_secret.into(),
        message_notify: Arc::new(Notify::new()),
        attachment_backend,
        rate_limiter: Arc::new(RateLimiter {
            entries: Mutex::new(HashMap::new()),
        }),
    };
    let app = build_app(state);

    let address: SocketAddr = env::var("HIDDI_BIND_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:3000".into())
        .parse()
        .context("HIDDI_BIND_ADDR must be a socket address")?;
    let listener = tokio::net::TcpListener::bind(address).await?;
    info!(%address, attachment_backend = attachment_backend.name(), "Hiddi server is listening");
    axum::serve(listener, app).await?;
    Ok(())
}

fn build_app(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/v1/admin/invites", post(create_invite))
        .route("/v1/auth/register", post(register))
        .route("/v1/devices/prekeys", put(upload_prekeys))
        .route("/v1/devices/current", get(current_device))
        .route(
            "/v1/users/{nickname}/prekey-bundle",
            get(take_prekey_bundle),
        )
        .route("/v1/users", get(search_users))
        .route("/v1/users/{nickname}/avatar", get(user_avatar))
        .route("/v1/users/{nickname}", get(find_user))
        .route("/v1/profile", get(current_profile).put(update_profile))
        .route(
            "/v1/profile/avatar",
            put(upload_avatar).delete(delete_avatar),
        )
        .route("/v1/blocks", get(blocked_users))
        .route(
            "/v1/blocks/{nickname}",
            put(block_user).delete(unblock_user),
        )
        .route("/v1/groups", post(create_group))
        .route("/v1/groups/key-package", put(upload_mls_key_package))
        .route("/v1/groups/deletions", get(pending_group_deletions))
        .route(
            "/v1/groups/deletions/{deletion_id}",
            post(ack_group_deletion),
        )
        .route(
            "/v1/groups/{group_id}",
            get(group_details).delete(delete_group),
        )
        .route("/v1/groups/{group_id}/members", post(add_group_member))
        .route(
            "/v1/groups/{group_id}/members/{nickname}/role",
            put(update_group_member_role),
        )
        .route(
            "/v1/users/{nickname}/mls-key-package",
            get(take_mls_key_package),
        )
        .route("/v1/groups/events", get(group_event_inbox))
        .route("/v1/groups/events/wait", get(wait_for_group_event))
        .route("/v1/groups/events/{event_id}", post(ack_group_event))
        .route("/v1/groups/{group_id}/events", post(send_group_event))
        .route(
            "/v1/groups/{group_id}/attachments",
            post(upload_group_attachment),
        )
        .route(
            "/v1/groups/{group_id}/messages/{client_event_id}",
            axum::routing::delete(delete_group_message),
        )
        .route("/v1/messages", post(send_message).get(inbox))
        .route("/v1/messages/wait", get(wait_for_message))
        .route("/v1/messages/deletions", get(pending_message_deletions))
        .route(
            "/v1/messages/deletions/{deletion_id}",
            post(ack_message_deletion),
        )
        .route(
            "/v1/messages/{message_id}",
            post(ack_message).get(message_status).delete(delete_message),
        )
        .route(
            "/v1/messages/read/{nickname}",
            post(mark_peer_messages_read),
        )
        .route(
            "/v1/conversations/deletions",
            get(pending_conversation_deletions),
        )
        .route(
            "/v1/conversations/{nickname}",
            axum::routing::delete(delete_conversation),
        )
        .route("/v1/attachments", post(upload_attachment))
        .route(
            "/v1/attachments/{attachment_id}",
            get(download_attachment).delete(delete_attachment),
        )
        .layer(DefaultBodyLimit::max(MAX_JSON_BODY_BYTES))
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

async fn upload_prekeys(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<UploadPrekeysRequest>,
) -> Result<StatusCode, Error> {
    let device = authenticate(&state, &headers)?;
    validate_signed_prekey(&request.signed_prekey)?;
    validate_signed_prekey(&request.kyber_signed_prekey)?;
    validate_one_time_prekeys(&request.one_time_prekeys, false)?;
    validate_one_time_prekeys(&request.kyber_one_time_prekeys, true)?;

    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    transaction.execute(
        "INSERT INTO prekey_bundles (device_id, signed_prekey_id, signed_prekey, signed_prekey_signature,
          kyber_signed_prekey_id, kyber_signed_prekey, kyber_signed_prekey_signature)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
         ON CONFLICT(device_id) DO UPDATE SET
          signed_prekey_id = excluded.signed_prekey_id, signed_prekey = excluded.signed_prekey,
          signed_prekey_signature = excluded.signed_prekey_signature,
          kyber_signed_prekey_id = excluded.kyber_signed_prekey_id,
          kyber_signed_prekey = excluded.kyber_signed_prekey,
          kyber_signed_prekey_signature = excluded.kyber_signed_prekey_signature",
        params![
            device.device_id,
            request.signed_prekey.id,
            request.signed_prekey.public_key,
            request.signed_prekey.signature,
            request.kyber_signed_prekey.id,
            request.kyber_signed_prekey.public_key,
            request.kyber_signed_prekey.signature,
        ],
    ).map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not store prekey bundle"))?;
    transaction
        .execute(
            "DELETE FROM one_time_prekeys WHERE device_id = ?1",
            params![device.device_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not replace one-time prekeys",
            )
        })?;
    for key in request.one_time_prekeys {
        transaction
            .execute(
                "INSERT INTO one_time_prekeys (device_id, key_kind, key_id, public_key, signature)
             VALUES (?1, 'classical', ?2, ?3, NULL)",
                params![device.device_id, key.id, key.public_key],
            )
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not store one-time prekey",
                )
            })?;
    }
    for key in request.kyber_one_time_prekeys {
        transaction
            .execute(
                "INSERT INTO one_time_prekeys (device_id, key_kind, key_id, public_key, signature)
             VALUES (?1, 'kyber', ?2, ?3, ?4)",
                params![device.device_id, key.id, key.public_key, key.signature],
            )
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not store Kyber prekey",
                )
            })?;
    }
    transaction.commit().map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not finalize prekey upload",
        )
    })?;
    Ok(StatusCode::NO_CONTENT)
}

async fn current_device(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<CurrentDeviceResponse>, Error> {
    let device = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let registration_id = db
        .query_row(
            "SELECT registration_id FROM devices WHERE id = ?1",
            params![device.device_id],
            |row| row.get(0),
        )
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load device"))?;
    Ok(Json(CurrentDeviceResponse {
        device_id: Uuid::parse_str(&device.device_id)
            .expect("authenticated device id is a valid UUID"),
        registration_id,
    }))
}

async fn take_prekey_bundle(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(nickname): axum::extract::Path<String>,
) -> Result<Json<PrekeyBundleResponse>, Error> {
    authenticate(&state, &headers)?;
    let nickname =
        normalize_nickname(&nickname).ok_or(Error(StatusCode::BAD_REQUEST, "invalid nickname"))?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let bundle = transaction.query_row(
        "SELECT accounts.id, accounts.nickname, devices.id, devices.registration_id, devices.identity_public_key,
                prekey_bundles.signed_prekey_id, prekey_bundles.signed_prekey,
                prekey_bundles.signed_prekey_signature, prekey_bundles.kyber_signed_prekey_id,
                prekey_bundles.kyber_signed_prekey, prekey_bundles.kyber_signed_prekey_signature
         FROM accounts JOIN devices ON devices.account_id = accounts.id
         JOIN prekey_bundles ON prekey_bundles.device_id = devices.id
         WHERE accounts.nickname = ?1 ORDER BY devices.created_at ASC LIMIT 1",
        params![nickname],
        |row| {
            let account_id: String = row.get(0)?;
            let device_id: String = row.get(2)?;
            Ok(PrekeyBundleResponse {
                account_id: Uuid::parse_str(&account_id).expect("database contains valid UUIDs"),
                nickname: row.get(1)?,
                device_id: Uuid::parse_str(&device_id).expect("database contains valid UUIDs"),
                registration_id: row.get(3)?,
                identity_public_key: row.get(4)?,
                signed_prekey: StoredPreKey {
                    id: row.get(5)?,
                    public_key: row.get(6)?,
                    signature: row.get(7)?,
                },
                kyber_signed_prekey: StoredPreKey {
                    id: row.get(8)?,
                    public_key: row.get(9)?,
                    signature: row.get(10)?,
                },
                one_time_prekey: None,
                kyber_one_time_prekey: None,
            })
        },
    );
    let mut bundle = match bundle {
        Ok(bundle) => bundle,
        Err(rusqlite::Error::QueryReturnedNoRows) => {
            return Err(Error(StatusCode::NOT_FOUND, "user has no prekey bundle"));
        }
        Err(_) => {
            return Err(Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not retrieve prekey bundle",
            ));
        }
    };
    bundle.one_time_prekey =
        take_one_time_prekey(&transaction, &bundle.device_id.to_string(), "classical")?;
    bundle.kyber_one_time_prekey =
        take_one_time_prekey(&transaction, &bundle.device_id.to_string(), "kyber")?;
    transaction.commit().map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not finalize prekey bundle",
        )
    })?;
    Ok(Json(bundle))
}

fn take_one_time_prekey(
    transaction: &rusqlite::Transaction<'_>,
    device_id: &str,
    kind: &str,
) -> Result<Option<StoredPreKey>, Error> {
    let key = transaction.query_row(
        "SELECT key_id, public_key, signature FROM one_time_prekeys
         WHERE device_id = ?1 AND key_kind = ?2 ORDER BY id ASC LIMIT 1",
        params![device_id, kind],
        |row| {
            Ok(StoredPreKey {
                id: row.get(0)?,
                public_key: row.get(1)?,
                signature: row.get(2)?,
            })
        },
    );
    let key = match key {
        Ok(key) => key,
        Err(rusqlite::Error::QueryReturnedNoRows) => return Ok(None),
        Err(_) => {
            return Err(Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not retrieve one-time prekey",
            ));
        }
    };
    transaction
        .execute(
            "DELETE FROM one_time_prekeys WHERE device_id = ?1 AND key_kind = ?2 AND key_id = ?3",
            params![device_id, kind, key.id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not consume one-time prekey",
            )
        })?;
    Ok(Some(key))
}

async fn health(State(state): State<AppState>) -> Json<Health> {
    Json(Health {
        status: "ok",
        attachment_backend: state.attachment_backend.name(),
    })
}

async fn create_invite(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<InviteResponse>, Error> {
    if !authorized(&headers, &state.bootstrap_secret) {
        return Err(Error(StatusCode::UNAUTHORIZED, "unauthorized"));
    }
    state
        .rate_limiter
        .check("invite", 10, Duration::from_secs(60))?;
    let code = random_token(32);
    let hash = hash(&code);
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.execute("INSERT INTO invites (code_hash) VALUES (?1)", params![hash])
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not create invite"))?;
    Ok(Json(InviteResponse { invite_code: code }))
}

async fn register(
    State(state): State<AppState>,
    Json(request): Json<RegisterRequest>,
) -> Result<(StatusCode, Json<RegisterResponse>), Error> {
    state
        .rate_limiter
        .check("register", 12, Duration::from_secs(60))?;
    let nickname = normalize_nickname(&request.nickname)
        .ok_or(Error(StatusCode::BAD_REQUEST, "invalid nickname"))?;
    if request.identity_public_key.len() < 20 || request.identity_public_key.len() > 4096 {
        return Err(Error(
            StatusCode::BAD_REQUEST,
            "invalid identity public key",
        ));
    }
    if !(1..=16_380).contains(&request.registration_id) {
        return Err(Error(StatusCode::BAD_REQUEST, "invalid registration id"));
    }
    let account_id = Uuid::new_v4();
    let device_id = Uuid::new_v4();
    let access_token = random_token(48);
    let invite_hash = hash(&request.invite_code);
    let token_hash = hash(&access_token);
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let used = transaction.execute(
        "UPDATE invites SET used_at = CURRENT_TIMESTAMP WHERE code_hash = ?1 AND used_at IS NULL",
        params![invite_hash],
    ).map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not validate invite"))?;
    if used == 0 {
        return Err(Error(
            StatusCode::UNAUTHORIZED,
            "invalid or already used invite",
        ));
    }
    let inserted = transaction.execute(
        "INSERT INTO accounts (id, nickname) VALUES (?1, ?2)",
        params![account_id.to_string(), nickname],
    );
    if let Err(error) = inserted {
        return match error.sqlite_error_code() {
            Some(ErrorCode::ConstraintViolation) => {
                Err(Error(StatusCode::CONFLICT, "nickname is already taken"))
            }
            _ => Err(Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not create account",
            )),
        };
    }
    transaction.execute(
        "INSERT INTO devices (id, account_id, identity_public_key, access_token_hash, registration_id) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![device_id.to_string(), account_id.to_string(), request.identity_public_key, token_hash, request.registration_id],
    ).map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not create device"))?;
    transaction.commit().map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not finalize registration",
        )
    })?;
    Ok((
        StatusCode::CREATED,
        Json(RegisterResponse {
            account_id,
            device_id,
            registration_id: request.registration_id,
            access_token,
        }),
    ))
}

async fn find_user(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(nickname): axum::extract::Path<String>,
) -> Result<Json<UserResponse>, Error> {
    authenticate(&state, &headers)?;
    let nickname =
        normalize_nickname(&nickname).ok_or(Error(StatusCode::BAD_REQUEST, "invalid nickname"))?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let user = db.query_row(
        "SELECT accounts.id, accounts.nickname, accounts.display_name, accounts.bio,
                accounts.avatar_version, devices.identity_public_key
         FROM accounts JOIN devices ON devices.account_id = accounts.id
         WHERE accounts.nickname = ?1 ORDER BY devices.created_at ASC LIMIT 1",
        params![nickname],
        |row| {
            let account_id: String = row.get(0)?;
            Ok(UserResponse {
                account_id: Uuid::parse_str(&account_id).expect("database contains valid UUIDs"),
                nickname: row.get(1)?,
                display_name: row.get(2)?,
                bio: row.get(3)?,
                avatar_version: row.get(4)?,
                identity_public_key: row.get(5)?,
            })
        },
    );
    match user {
        Ok(user) => Ok(Json(user)),
        Err(rusqlite::Error::QueryReturnedNoRows) => {
            Err(Error(StatusCode::NOT_FOUND, "user not found"))
        }
        Err(_) => Err(Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not find user",
        )),
    }
}

async fn search_users(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<UserSearchQuery>,
) -> Result<Json<Vec<UserSearchItem>>, Error> {
    authenticate(&state, &headers)?;
    let prefix = normalize_nickname(&query.query)
        .ok_or(Error(StatusCode::BAD_REQUEST, "invalid nickname query"))?;
    let escaped_prefix = prefix.replace('_', "\\_");
    let pattern = format!("{escaped_prefix}%");
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let mut statement = db
        .prepare(
            "SELECT nickname, display_name, bio, avatar_version FROM accounts
             WHERE nickname LIKE ?1 ESCAPE '\\'
             ORDER BY nickname ASC LIMIT 20",
        )
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not search users"))?;
    let users = statement
        .query_map(params![pattern], |row| {
            Ok(UserSearchItem {
                nickname: row.get(0)?,
                display_name: row.get(1)?,
                bio: row.get(2)?,
                avatar_version: row.get(3)?,
            })
        })
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not search users"))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not search users"))?;
    Ok(Json(users))
}

async fn current_profile(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<UserProfileResponse>, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.query_row(
        "SELECT nickname, display_name, bio, avatar_version
         FROM accounts WHERE id = ?1",
        params![account.account_id],
        |row| {
            Ok(UserProfileResponse {
                nickname: row.get(0)?,
                display_name: row.get(1)?,
                bio: row.get(2)?,
                avatar_version: row.get(3)?,
            })
        },
    )
    .map(Json)
    .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load profile"))
}

async fn update_profile(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<UpdateProfileRequest>,
) -> Result<Json<UserProfileResponse>, Error> {
    let account = authenticate(&state, &headers)?;
    let display_name = validate_display_name(&request.display_name)?;
    let bio = validate_bio(&request.bio)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.execute(
        "UPDATE accounts SET display_name = ?1, bio = ?2 WHERE id = ?3",
        params![display_name, bio, account.account_id],
    )
    .map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not update profile",
        )
    })?;
    db.query_row(
        "SELECT nickname, display_name, bio, avatar_version
         FROM accounts WHERE id = ?1",
        params![account.account_id],
        |row| {
            Ok(UserProfileResponse {
                nickname: row.get(0)?,
                display_name: row.get(1)?,
                bio: row.get(2)?,
                avatar_version: row.get(3)?,
            })
        },
    )
    .map(Json)
    .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load profile"))
}

async fn upload_avatar(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<UploadAvatarRequest>,
) -> Result<(StatusCode, Json<AvatarVersionResponse>), Error> {
    let account = authenticate(&state, &headers)?;
    let image = decode_avatar(&request.image)?;
    let version = Uuid::new_v4().to_string();
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.execute(
        "UPDATE accounts SET avatar = ?1, avatar_version = ?2 WHERE id = ?3",
        params![image, version, account.account_id],
    )
    .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not update avatar"))?;
    Ok((StatusCode::CREATED, Json(AvatarVersionResponse { version })))
}

async fn delete_avatar(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.execute(
        "UPDATE accounts SET avatar = NULL, avatar_version = NULL WHERE id = ?1",
        params![account.account_id],
    )
    .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not delete avatar"))?;
    Ok(StatusCode::NO_CONTENT)
}

async fn user_avatar(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(nickname): axum::extract::Path<String>,
) -> Result<Json<AvatarResponse>, Error> {
    authenticate(&state, &headers)?;
    let nickname =
        normalize_nickname(&nickname).ok_or(Error(StatusCode::BAD_REQUEST, "invalid nickname"))?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.query_row(
        "SELECT avatar, avatar_version FROM accounts
         WHERE nickname = ?1 AND avatar IS NOT NULL AND avatar_version IS NOT NULL",
        params![nickname],
        |row| {
            let image: Vec<u8> = row.get(0)?;
            Ok(AvatarResponse {
                image: URL_SAFE_NO_PAD.encode(image),
                version: row.get(1)?,
            })
        },
    )
    .map(Json)
    .map_err(|error| match error {
        rusqlite::Error::QueryReturnedNoRows => Error(StatusCode::NOT_FOUND, "avatar not found"),
        _ => Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load avatar"),
    })
}

async fn blocked_users(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<BlockedUserResponse>>, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let mut statement = db
        .prepare(
            "SELECT accounts.nickname
             FROM blocks JOIN accounts ON accounts.id = blocks.blocked_account_id
             WHERE blocks.blocker_account_id = ?1
             ORDER BY accounts.nickname",
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load block list",
            )
        })?;
    let users = statement
        .query_map(params![account.account_id], |row| {
            Ok(BlockedUserResponse {
                nickname: row.get(0)?,
            })
        })
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load block list",
            )
        })?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load block list",
            )
        })?;
    Ok(Json(users))
}

async fn block_user(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(raw_nickname): axum::extract::Path<String>,
) -> Result<StatusCode, Error> {
    let blocker = authenticate(&state, &headers)?;
    let nickname = normalize_nickname(&raw_nickname)
        .ok_or(Error(StatusCode::BAD_REQUEST, "invalid blocked nickname"))?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let blocked_id: String = db
        .query_row(
            "SELECT id FROM accounts WHERE nickname = ?1",
            params![nickname],
            |row| row.get(0),
        )
        .map_err(|error| match error {
            rusqlite::Error::QueryReturnedNoRows => {
                Error(StatusCode::NOT_FOUND, "blocked user not found")
            }
            _ => Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not find blocked user",
            ),
        })?;
    if blocked_id == blocker.account_id {
        return Err(Error(StatusCode::BAD_REQUEST, "cannot block yourself"));
    }
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    transaction
        .execute(
            "INSERT INTO blocks (blocker_account_id, blocked_account_id)
             VALUES (?1, ?2)
             ON CONFLICT(blocker_account_id, blocked_account_id) DO NOTHING",
            params![blocker.account_id, blocked_id],
        )
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not block user"))?;
    transaction
        .execute(
            "DELETE FROM messages
             WHERE sender_account_id = ?1 AND recipient_account_id = ?2",
            params![blocked_id, blocker.account_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not clear blocked messages",
            )
        })?;
    transaction
        .execute(
            "DELETE FROM attachments
             WHERE sender_account_id = ?1 AND recipient_account_id = ?2",
            params![blocked_id, blocker.account_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not clear blocked attachments",
            )
        })?;
    transaction
        .commit()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not block user"))?;
    Ok(StatusCode::NO_CONTENT)
}

async fn unblock_user(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(raw_nickname): axum::extract::Path<String>,
) -> Result<StatusCode, Error> {
    let blocker = authenticate(&state, &headers)?;
    let nickname = normalize_nickname(&raw_nickname)
        .ok_or(Error(StatusCode::BAD_REQUEST, "invalid blocked nickname"))?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.execute(
        "DELETE FROM blocks
         WHERE blocker_account_id = ?1
           AND blocked_account_id = (SELECT id FROM accounts WHERE nickname = ?2)",
        params![blocker.account_id, nickname],
    )
    .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not unblock user"))?;
    Ok(StatusCode::NO_CONTENT)
}

async fn send_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<SendMessageRequest>,
) -> Result<(StatusCode, Json<serde_json::Value>), Error> {
    let sender = authenticate(&state, &headers)?;
    state.rate_limiter.check(
        format!("send:{}", sender.account_id),
        120,
        Duration::from_secs(60),
    )?;
    let recipient_nickname = normalize_nickname(&request.recipient_nickname)
        .ok_or(Error(StatusCode::BAD_REQUEST, "invalid recipient nickname"))?;
    if request.ciphertext.len() < 4
        || request.ciphertext.len() > 2_800_000
        || URL_SAFE_NO_PAD.decode(&request.ciphertext).is_err()
    {
        return Err(Error(
            StatusCode::BAD_REQUEST,
            "ciphertext must be URL-safe base64 and at most 2 MiB",
        ));
    }
    let message_id = Uuid::new_v4();
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let recipient_id: String = db
        .query_row(
            "SELECT id FROM accounts WHERE nickname = ?1",
            params![recipient_nickname],
            |row| row.get(0),
        )
        .map_err(|error| match error {
            rusqlite::Error::QueryReturnedNoRows => {
                Error(StatusCode::NOT_FOUND, "recipient not found")
            }
            _ => Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not find recipient",
            ),
        })?;
    let blocked: bool = db
        .query_row(
            "SELECT EXISTS(
                SELECT 1 FROM blocks
                WHERE blocker_account_id = ?1 AND blocked_account_id = ?2
             )",
            params![recipient_id, sender.account_id],
            |row| row.get(0),
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not check recipient block list",
            )
        })?;
    if blocked {
        return Ok((
            StatusCode::CREATED,
            Json(serde_json::json!({"message_id": message_id})),
        ));
    }
    db.execute(
        "INSERT INTO messages (id, sender_account_id, recipient_account_id, ciphertext)
         VALUES (?1, ?2, ?3, ?4)",
        params![
            message_id.to_string(),
            sender.account_id,
            recipient_id,
            request.ciphertext
        ],
    )
    .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not store message"))?;
    drop(db);
    state.message_notify.notify_waiters();
    Ok((
        StatusCode::CREATED,
        Json(serde_json::json!({"message_id": message_id})),
    ))
}

/// Registers only routing metadata for a new MLS group. No title, MLS state,
/// epoch, public key, or message content is supplied to the server.
async fn create_group(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<CreateGroupRequest>,
) -> Result<(StatusCode, Json<CreateGroupResponse>), Error> {
    let creator = authenticate(&state, &headers)?;
    state.rate_limiter.check(
        format!("group-create:{}", creator.account_id),
        10,
        Duration::from_secs(60),
    )?;
    validate_group_id(&request.group_id)?;
    if request.members.len() > 31 {
        return Err(Error(
            StatusCode::BAD_REQUEST,
            "too many initial group members",
        ));
    }
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let mut resolved_members = Vec::with_capacity(request.members.len());
    for member in &request.members {
        let nickname = normalize_nickname(&member.nickname).ok_or(Error(
            StatusCode::BAD_REQUEST,
            "invalid group member nickname",
        ))?;
        let role = match member.role.as_str() {
            "admin" | "member" => member.role.clone(),
            _ => return Err(Error(StatusCode::BAD_REQUEST, "invalid group member role")),
        };
        let account_id: String = db
            .query_row(
                "SELECT id FROM accounts WHERE nickname = ?1",
                params![nickname],
                |row| row.get(0),
            )
            .map_err(|error| match error {
                rusqlite::Error::QueryReturnedNoRows => {
                    Error(StatusCode::NOT_FOUND, "group member not found")
                }
                _ => Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not find group member",
                ),
            })?;
        if account_id == creator.account_id {
            return Err(Error(
                StatusCode::BAD_REQUEST,
                "creator role is always owner",
            ));
        }
        if resolved_members
            .iter()
            .any(|(existing_id, _)| existing_id == &account_id)
        {
            return Err(Error(StatusCode::BAD_REQUEST, "duplicate group member"));
        }
        resolved_members.push((account_id, role));
    }
    let existing_owner: Option<String> = db
        .query_row(
            "SELECT owner_account_id FROM groups WHERE id = ?1",
            params![request.group_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not check existing group",
            )
        })?;
    if let Some(existing_owner) = existing_owner {
        if existing_owner != creator.account_id {
            return Err(Error(StatusCode::CONFLICT, "group already exists"));
        }
        let mut statement = db
            .prepare(
                "SELECT account_id, role FROM group_members
                 WHERE group_id = ?1 AND role != 'owner'
                 ORDER BY account_id, role",
            )
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not check existing group",
                )
            })?;
        let mut existing_members = statement
            .query_map(params![request.group_id], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not check existing group",
                )
            })?
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not check existing group",
                )
            })?;
        existing_members.sort();
        resolved_members.sort();
        if existing_members != resolved_members {
            return Err(Error(
                StatusCode::CONFLICT,
                "group exists with different members",
            ));
        }
        return Ok((
            StatusCode::OK,
            Json(CreateGroupResponse {
                group_id: request.group_id,
            }),
        ));
    }
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    transaction
        .execute(
            "INSERT INTO groups (id, owner_account_id) VALUES (?1, ?2)",
            params![request.group_id, creator.account_id],
        )
        .map_err(|error| match error {
            rusqlite::Error::SqliteFailure(ref failure, _)
                if failure.code == ErrorCode::ConstraintViolation =>
            {
                Error(StatusCode::CONFLICT, "group already exists")
            }
            _ => Error(StatusCode::INTERNAL_SERVER_ERROR, "could not create group"),
        })?;
    transaction
        .execute(
            "INSERT INTO group_members (group_id, account_id, role) VALUES (?1, ?2, 'owner')",
            params![request.group_id, creator.account_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not add group owner",
            )
        })?;
    for (account_id, role) in resolved_members {
        transaction
            .execute(
                "INSERT INTO group_members (group_id, account_id, role) VALUES (?1, ?2, ?3)",
                params![request.group_id, account_id, role],
            )
            .map_err(|error| match error {
                rusqlite::Error::SqliteFailure(ref failure, _)
                    if failure.code == ErrorCode::ConstraintViolation =>
                {
                    Error(StatusCode::BAD_REQUEST, "duplicate group member")
                }
                _ => Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not add group member",
                ),
            })?;
    }
    transaction
        .commit()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not create group"))?;
    Ok((
        StatusCode::CREATED,
        Json(CreateGroupResponse {
            group_id: request.group_id,
        }),
    ))
}

async fn group_details(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(group_id): axum::extract::Path<String>,
) -> Result<Json<GroupDetailsResponse>, Error> {
    let account = authenticate(&state, &headers)?;
    validate_group_id(&group_id)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let authorized: bool = db
        .query_row(
            "SELECT EXISTS(
                SELECT 1 FROM group_members
                WHERE group_id = ?1 AND account_id = ?2
             )",
            params![group_id, account.account_id],
            |row| row.get(0),
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not authorize group",
            )
        })?;
    if !authorized {
        return Err(Error(StatusCode::NOT_FOUND, "group not found"));
    }
    let owner_nickname: String = db
        .query_row(
            "SELECT accounts.nickname
             FROM groups JOIN accounts ON accounts.id = groups.owner_account_id
             WHERE groups.id = ?1",
            params![group_id],
            |row| row.get(0),
        )
        .map_err(|_| Error(StatusCode::NOT_FOUND, "group not found"))?;
    let mut statement = db
        .prepare(
            "SELECT accounts.nickname, group_members.role,
                    (SELECT devices.id FROM devices
                     WHERE devices.account_id = accounts.id
                     ORDER BY devices.created_at ASC LIMIT 1)
             FROM group_members
             JOIN accounts ON accounts.id = group_members.account_id
             WHERE group_members.group_id = ?1
             ORDER BY CASE group_members.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END,
                      accounts.nickname",
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load group members",
            )
        })?;
    let members = statement
        .query_map(params![group_id], |row| {
            Ok(GroupMemberResponse {
                nickname: row.get(0)?,
                role: row.get(1)?,
                device_id: row.get(2)?,
            })
        })
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load group members",
            )
        })?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load group members",
            )
        })?;
    Ok(Json(GroupDetailsResponse {
        group_id,
        owner_nickname,
        members,
    }))
}

async fn add_group_member(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(group_id): axum::extract::Path<String>,
    Json(request): Json<AddGroupMemberRequest>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    validate_group_id(&group_id)?;
    let nickname = normalize_nickname(&request.nickname).ok_or(Error(
        StatusCode::BAD_REQUEST,
        "invalid group member nickname",
    ))?;
    if !matches!(request.role.as_str(), "member" | "admin") {
        return Err(Error(StatusCode::BAD_REQUEST, "invalid group member role"));
    }
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let requester_role: String = db
        .query_row(
            "SELECT role FROM group_members WHERE group_id = ?1 AND account_id = ?2",
            params![group_id, account.account_id],
            |row| row.get(0),
        )
        .map_err(|error| match error {
            rusqlite::Error::QueryReturnedNoRows => {
                Error(StatusCode::FORBIDDEN, "not a group administrator")
            }
            _ => Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not authorize group administrator",
            ),
        })?;
    if !matches!(requester_role.as_str(), "owner" | "admin")
        || (request.role == "admin" && requester_role != "owner")
    {
        return Err(Error(
            StatusCode::FORBIDDEN,
            "not allowed to add this group member",
        ));
    }
    let new_account_id: String = db
        .query_row(
            "SELECT id FROM accounts WHERE nickname = ?1",
            params![nickname],
            |row| row.get(0),
        )
        .map_err(|error| match error {
            rusqlite::Error::QueryReturnedNoRows => {
                Error(StatusCode::NOT_FOUND, "group member not found")
            }
            _ => Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not find group member",
            ),
        })?;
    let existing_role: Option<String> = db
        .query_row(
            "SELECT role FROM group_members WHERE group_id = ?1 AND account_id = ?2",
            params![group_id, new_account_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not check group member",
            )
        })?;
    if let Some(existing_role) = existing_role {
        return if existing_role == request.role {
            Ok(StatusCode::NO_CONTENT)
        } else {
            Err(Error(
                StatusCode::CONFLICT,
                "group member already has another role",
            ))
        };
    }
    db.execute(
        "INSERT INTO group_members (group_id, account_id, role) VALUES (?1, ?2, ?3)",
        params![group_id, new_account_id, request.role],
    )
    .map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not add group member",
        )
    })?;
    Ok(StatusCode::NO_CONTENT)
}

async fn update_group_member_role(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path((group_id, raw_nickname)): axum::extract::Path<(String, String)>,
    Json(request): Json<UpdateGroupRoleRequest>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    validate_group_id(&group_id)?;
    let nickname = normalize_nickname(&raw_nickname).ok_or(Error(
        StatusCode::BAD_REQUEST,
        "invalid group member nickname",
    ))?;
    if !matches!(request.role.as_str(), "admin" | "member") {
        return Err(Error(StatusCode::BAD_REQUEST, "invalid group member role"));
    }
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let requester_role: String = db
        .query_row(
            "SELECT role FROM group_members WHERE group_id = ?1 AND account_id = ?2",
            params![group_id, account.account_id],
            |row| row.get(0),
        )
        .map_err(|_| {
            Error(
                StatusCode::FORBIDDEN,
                "only the group owner may change roles",
            )
        })?;
    if requester_role != "owner" {
        return Err(Error(
            StatusCode::FORBIDDEN,
            "only the group owner may change roles",
        ));
    }
    let updated = db
        .execute(
            "UPDATE group_members
             SET role = ?1
             WHERE group_id = ?2
               AND account_id = (SELECT id FROM accounts WHERE nickname = ?3)
               AND role != 'owner'",
            params![request.role, group_id, nickname],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not update group member role",
            )
        })?;
    if updated == 0 {
        let current_role: Option<String> = db
            .query_row(
                "SELECT group_members.role
                 FROM group_members JOIN accounts ON accounts.id = group_members.account_id
                 WHERE group_members.group_id = ?1 AND accounts.nickname = ?2",
                params![group_id, nickname],
                |row| row.get(0),
            )
            .optional()
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not inspect group member role",
                )
            })?;
        return match current_role.as_deref() {
            Some("owner") => Err(Error(
                StatusCode::BAD_REQUEST,
                "owner role cannot be changed",
            )),
            Some(role) if role == request.role => Ok(StatusCode::NO_CONTENT),
            Some(_) => Err(Error(StatusCode::CONFLICT, "group role update conflicted")),
            None => Err(Error(StatusCode::NOT_FOUND, "group member not found")),
        };
    }
    Ok(StatusCode::NO_CONTENT)
}

async fn delete_group(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(group_id): axum::extract::Path<String>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    validate_group_id(&group_id)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let owner_id: String = db
        .query_row(
            "SELECT owner_account_id FROM groups WHERE id = ?1",
            params![group_id],
            |row| row.get(0),
        )
        .map_err(|_| Error(StatusCode::NOT_FOUND, "group not found"))?;
    if owner_id != account.account_id {
        return Err(Error(
            StatusCode::FORBIDDEN,
            "only the group owner may delete it",
        ));
    }
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let recipient_ids = {
        let mut statement = transaction
            .prepare(
                "SELECT account_id FROM group_members
                 WHERE group_id = ?1 AND account_id != ?2",
            )
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not load group members",
                )
            })?;
        statement
            .query_map(params![group_id, account.account_id], |row| {
                row.get::<_, String>(0)
            })
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not load group members",
                )
            })?
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not load group members",
                )
            })?
    };
    for recipient_id in recipient_ids {
        transaction
            .execute(
                "INSERT INTO group_deletions (id, recipient_account_id, group_id)
                 VALUES (?1, ?2, ?3)
                 ON CONFLICT(recipient_account_id, group_id) DO NOTHING",
                params![Uuid::new_v4().to_string(), recipient_id, group_id],
            )
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not mark group deletion",
                )
            })?;
    }
    transaction
        .execute("DELETE FROM groups WHERE id = ?1", params![group_id])
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not delete group"))?;
    transaction
        .commit()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not delete group"))?;
    drop(db);
    state.message_notify.notify_waiters();
    Ok(StatusCode::NO_CONTENT)
}

async fn delete_group_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path((group_id, client_event_id)): axum::extract::Path<(String, String)>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    validate_group_id(&group_id)?;
    let decoded_id = URL_SAFE_NO_PAD
        .decode(&client_event_id)
        .map_err(|_| Error(StatusCode::BAD_REQUEST, "invalid group message id"))?;
    if !(16..=64).contains(&decoded_id.len()) {
        return Err(Error(StatusCode::BAD_REQUEST, "invalid group message id"));
    }
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let is_member: bool = db
        .query_row(
            "SELECT EXISTS(
                SELECT 1 FROM group_members
                WHERE group_id = ?1 AND account_id = ?2
             )",
            params![group_id, account.account_id],
            |row| row.get(0),
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not authorize group message deletion",
            )
        })?;
    if !is_member {
        return Err(Error(StatusCode::NOT_FOUND, "group not found"));
    }
    db.execute(
        "DELETE FROM group_events
         WHERE group_id = ?1
           AND sender_account_id = ?2
           AND client_event_id = ?3
           AND kind = 3",
        params![group_id, account.account_id, client_event_id],
    )
    .map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not delete group message ciphertext",
        )
    })?;
    Ok(StatusCode::NO_CONTENT)
}

async fn pending_group_deletions(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<GroupDeletionResponse>>, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let mut statement = db
        .prepare(
            "SELECT id, group_id FROM group_deletions
             WHERE recipient_account_id = ?1
             ORDER BY created_at ASC LIMIT 100",
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load group deletions",
            )
        })?;
    let deletions = statement
        .query_map(params![account.account_id], |row| {
            let deletion_id: String = row.get(0)?;
            Ok(GroupDeletionResponse {
                deletion_id: Uuid::parse_str(&deletion_id)
                    .expect("database contains valid group deletion UUIDs"),
                group_id: row.get(1)?,
            })
        })
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load group deletions",
            )
        })?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load group deletions",
            )
        })?;
    Ok(Json(deletions))
}

async fn ack_group_deletion(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(deletion_id): axum::extract::Path<Uuid>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let deleted = db
        .execute(
            "DELETE FROM group_deletions
             WHERE id = ?1 AND recipient_account_id = ?2",
            params![deletion_id.to_string(), account.account_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not acknowledge group deletion",
            )
        })?;
    if deleted == 0 {
        return Err(Error(StatusCode::NOT_FOUND, "group deletion not found"));
    }
    Ok(StatusCode::NO_CONTENT)
}

async fn upload_mls_key_package(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<UploadMlsKeyPackageRequest>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    if request.key_package.len() < 4
        || request.key_package.len() > 65_536
        || URL_SAFE_NO_PAD.decode(&request.key_package).is_err()
    {
        return Err(Error(StatusCode::BAD_REQUEST, "invalid MLS key package"));
    }
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.execute("INSERT INTO mls_key_packages (device_id, key_package) VALUES (?1, ?2) ON CONFLICT(device_id) DO UPDATE SET key_package = excluded.key_package, created_at = CURRENT_TIMESTAMP", params![account.device_id, request.key_package])
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not store MLS key package"))?;
    Ok(StatusCode::NO_CONTENT)
}

async fn take_mls_key_package(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(nickname): axum::extract::Path<String>,
) -> Result<Json<MlsKeyPackageResponse>, Error> {
    let _requester = authenticate(&state, &headers)?;
    let nickname =
        normalize_nickname(&nickname).ok_or(Error(StatusCode::BAD_REQUEST, "invalid nickname"))?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let (device_id, key_package): (String, String) = transaction.query_row(
        "SELECT devices.id, mls_key_packages.key_package FROM accounts JOIN devices ON devices.account_id = accounts.id JOIN mls_key_packages ON mls_key_packages.device_id = devices.id WHERE accounts.nickname = ?1 ORDER BY mls_key_packages.created_at ASC LIMIT 1", params![nickname], |row| Ok((row.get(0)?, row.get(1)?)))
        .map_err(|error| match error { rusqlite::Error::QueryReturnedNoRows => Error(StatusCode::NOT_FOUND, "MLS key package not found"), _ => Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load MLS key package") })?;
    transaction
        .execute(
            "DELETE FROM mls_key_packages WHERE device_id = ?1",
            params![device_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not consume MLS key package",
            )
        })?;
    transaction.commit().map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not consume MLS key package",
        )
    })?;
    Ok(Json(MlsKeyPackageResponse {
        nickname,
        key_package,
    }))
}

/// Stores versioned opaque MLS bytes for selected group members. The `kind`
/// byte is a server-side authorization hint only; OpenMLS validates all bytes.
async fn send_group_event(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(group_id): axum::extract::Path<String>,
    Json(request): Json<SendGroupEventRequest>,
) -> Result<(StatusCode, Json<SendGroupEventResponse>), Error> {
    let sender = authenticate(&state, &headers)?;
    state.rate_limiter.check(
        format!("group-event:{}", sender.account_id),
        120,
        Duration::from_secs(60),
    )?;
    validate_group_id(&group_id)?;
    validate_group_envelope(request.kind, &request.envelope)?;
    let decoded_client_event_id = URL_SAFE_NO_PAD
        .decode(&request.client_event_id)
        .map_err(|_| Error(StatusCode::BAD_REQUEST, "invalid group client event id"))?;
    if !(16..=64).contains(&decoded_client_event_id.len()) {
        return Err(Error(
            StatusCode::BAD_REQUEST,
            "invalid group client event id",
        ));
    }
    if request.recipient_nicknames.is_empty() || request.recipient_nicknames.len() > 32 {
        return Err(Error(
            StatusCode::BAD_REQUEST,
            "invalid group event recipients",
        ));
    }
    let recipients = request
        .recipient_nicknames
        .iter()
        .map(|nickname| {
            normalize_nickname(nickname).ok_or(Error(
                StatusCode::BAD_REQUEST,
                "invalid group member nickname",
            ))
        })
        .collect::<Result<Vec<_>, _>>()?;
    let recipient_set = recipients
        .iter()
        .cloned()
        .collect::<std::collections::BTreeSet<_>>();
    if recipient_set.len() != recipients.len() {
        return Err(Error(
            StatusCode::BAD_REQUEST,
            "duplicate group event recipient",
        ));
    }
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let sender_role: String = db
        .query_row(
            "SELECT role FROM group_members WHERE group_id = ?1 AND account_id = ?2",
            params![group_id, sender.account_id],
            |row| row.get(0),
        )
        .map_err(|error| match error {
            rusqlite::Error::QueryReturnedNoRows => {
                Error(StatusCode::FORBIDDEN, "not a group member")
            }
            _ => Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not authorize group event",
            ),
        })?;
    if matches!(request.kind, 1 | 2) && !matches!(sender_role.as_str(), "owner" | "admin") {
        return Err(Error(
            StatusCode::FORBIDDEN,
            "only an owner or admin may send this MLS event",
        ));
    }
    let existing_events = {
        let mut statement = db
            .prepare(
                "SELECT group_events.id, accounts.nickname
                 FROM group_events JOIN accounts
                   ON accounts.id = group_events.recipient_account_id
                 WHERE group_events.sender_account_id = ?1
                   AND group_events.client_event_id = ?2",
            )
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not check group event idempotency",
                )
            })?;
        statement
            .query_map(params![sender.account_id, request.client_event_id], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not check group event idempotency",
                )
            })?
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not check group event idempotency",
                )
            })?
    };
    if !existing_events.is_empty() {
        let existing_recipients = existing_events
            .iter()
            .map(|(_, nickname)| nickname.clone())
            .collect::<std::collections::BTreeSet<_>>();
        if existing_recipients != recipient_set {
            return Err(Error(
                StatusCode::CONFLICT,
                "group client event id was reused with other recipients",
            ));
        }
        return Ok((
            StatusCode::CREATED,
            Json(SendGroupEventResponse {
                event_ids: recipients
                    .iter()
                    .map(|nickname| {
                        let id = existing_events
                            .iter()
                            .find_map(|(id, existing_nickname)| {
                                (existing_nickname == nickname).then_some(id)
                            })
                            .expect("recipient set was checked above");
                        Uuid::parse_str(id).expect("database contains valid group event UUIDs")
                    })
                    .collect(),
            }),
        ));
    }
    let removal = if let Some(raw_target) = request.remove_member_nickname.as_deref() {
        if request.kind != 2 {
            return Err(Error(
                StatusCode::BAD_REQUEST,
                "member removal requires an MLS Commit event",
            ));
        }
        let target_nickname = normalize_nickname(raw_target).ok_or(Error(
            StatusCode::BAD_REQUEST,
            "invalid removed member nickname",
        ))?;
        let (target_id, target_role): (String, String) = db
            .query_row(
                "SELECT accounts.id, group_members.role
                 FROM accounts JOIN group_members ON group_members.account_id = accounts.id
                 WHERE group_members.group_id = ?1 AND accounts.nickname = ?2",
                params![group_id, target_nickname],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .map_err(|error| match error {
                rusqlite::Error::QueryReturnedNoRows => {
                    Error(StatusCode::NOT_FOUND, "removed group member not found")
                }
                _ => Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not inspect removed group member",
                ),
            })?;
        let allowed = match sender_role.as_str() {
            "owner" => target_role != "owner",
            "admin" => target_role == "member",
            _ => false,
        };
        if !allowed || target_id == sender.account_id {
            return Err(Error(
                StatusCode::FORBIDDEN,
                "not allowed to remove this group member",
            ));
        }
        let expected_recipients = {
            let mut statement = db
                .prepare(
                    "SELECT accounts.nickname
                     FROM group_members JOIN accounts ON accounts.id = group_members.account_id
                     WHERE group_members.group_id = ?1 AND group_members.account_id != ?2",
                )
                .map_err(|_| {
                    Error(
                        StatusCode::INTERNAL_SERVER_ERROR,
                        "could not load group members",
                    )
                })?;
            statement
                .query_map(params![group_id, sender.account_id], |row| {
                    row.get::<_, String>(0)
                })
                .map_err(|_| {
                    Error(
                        StatusCode::INTERNAL_SERVER_ERROR,
                        "could not load group members",
                    )
                })?
                .collect::<rusqlite::Result<std::collections::BTreeSet<_>>>()
                .map_err(|_| {
                    Error(
                        StatusCode::INTERNAL_SERVER_ERROR,
                        "could not load group members",
                    )
                })?
        };
        if recipient_set != expected_recipients {
            return Err(Error(
                StatusCode::BAD_REQUEST,
                "remove commit must be routed to every other current member",
            ));
        }
        Some((target_id, target_nickname))
    } else {
        None
    };
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let mut event_ids = Vec::with_capacity(recipients.len());
    for nickname in recipients {
        let recipient_id: String = transaction.query_row(
            "SELECT accounts.id FROM accounts JOIN group_members ON group_members.account_id = accounts.id WHERE accounts.nickname = ?1 AND group_members.group_id = ?2",
            params![nickname, group_id], |row| row.get(0),
        ).map_err(|error| match error {
            rusqlite::Error::QueryReturnedNoRows => Error(StatusCode::FORBIDDEN, "recipient is not a group member"),
            _ => Error(StatusCode::INTERNAL_SERVER_ERROR, "could not authorize group recipient"),
        })?;
        let event_id = Uuid::new_v4();
        let removes_recipient = removal
            .as_ref()
            .is_some_and(|(target_id, _)| target_id == &recipient_id);
        transaction
            .execute(
                "INSERT INTO group_events
                (id, group_id, sender_account_id, recipient_account_id, client_event_id,
                 kind, envelope, removes_recipient)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
                params![
                    event_id.to_string(),
                    group_id,
                    sender.account_id,
                    recipient_id,
                    request.client_event_id,
                    request.kind,
                    request.envelope,
                    removes_recipient,
                ],
            )
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not store group event",
                )
            })?;
        event_ids.push(event_id);
    }
    if let Some((target_id, _)) = removal {
        let removed = transaction
            .execute(
                "DELETE FROM group_members WHERE group_id = ?1 AND account_id = ?2",
                params![group_id, target_id],
            )
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not remove group member",
                )
            })?;
        if removed != 1 {
            return Err(Error(
                StatusCode::CONFLICT,
                "group membership changed during removal",
            ));
        }
    }
    transaction.commit().map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not store group event",
        )
    })?;
    drop(db);
    state.message_notify.notify_waiters();
    Ok((
        StatusCode::CREATED,
        Json(SendGroupEventResponse { event_ids }),
    ))
}

async fn group_event_inbox(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<GroupEventResponse>>, Error> {
    let recipient = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let mut statement = db
        .prepare(
            "SELECT group_events.id, group_events.group_id, accounts.nickname,
                    group_events.kind, group_events.envelope, group_events.created_at,
                    group_events.removes_recipient
             FROM group_events
             JOIN accounts ON accounts.id = group_events.sender_account_id
             WHERE group_events.recipient_account_id = ?1
               AND group_events.delivered_at IS NULL
             ORDER BY group_events.created_at ASC
             LIMIT 100",
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load group events",
            )
        })?;
    let events = statement
        .query_map(params![recipient.account_id], |row| {
            let event_id: String = row.get(0)?;
            Ok(GroupEventResponse {
                event_id: Uuid::parse_str(&event_id).expect("database contains valid UUIDs"),
                group_id: row.get(1)?,
                sender_nickname: row.get(2)?,
                kind: row.get(3)?,
                envelope: row.get(4)?,
                created_at: row.get(5)?,
                removes_recipient: row.get(6)?,
            })
        })
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load group events",
            )
        })?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load group events",
            )
        })?;
    Ok(Json(events))
}

async fn wait_for_group_event(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<serde_json::Value>, Error> {
    let recipient = authenticate(&state, &headers)?;
    if has_pending_group_event(&state, &recipient.account_id)? {
        return Ok(Json(serde_json::json!({"available": true})));
    }
    let _ = tokio::time::timeout(Duration::from_secs(25), state.message_notify.notified()).await;
    Ok(Json(serde_json::json!({
        "available": has_pending_group_event(&state, &recipient.account_id)?
    })))
}

fn has_pending_group_event(state: &AppState, account_id: &str) -> Result<bool, Error> {
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.query_row(
        "SELECT EXISTS(
            SELECT 1 FROM group_events
            WHERE recipient_account_id = ?1 AND delivered_at IS NULL
            UNION ALL
            SELECT 1 FROM group_deletions
            WHERE recipient_account_id = ?1
         )",
        params![account_id],
        |row| row.get(0),
    )
    .map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not check group events",
        )
    })
}

async fn ack_group_event(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(event_id): axum::extract::Path<Uuid>,
) -> Result<StatusCode, Error> {
    let recipient = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let updated = db
        .execute(
            "UPDATE group_events
             SET delivered_at = COALESCE(delivered_at, CURRENT_TIMESTAMP)
             WHERE id = ?1 AND recipient_account_id = ?2",
            params![event_id.to_string(), recipient.account_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not acknowledge group event",
            )
        })?;
    if updated == 0 {
        return Err(Error(StatusCode::NOT_FOUND, "group event not found"));
    }
    Ok(StatusCode::NO_CONTENT)
}

fn validate_group_id(value: &str) -> Result<(), Error> {
    let decoded = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| Error(StatusCode::BAD_REQUEST, "group id must be URL-safe base64"))?;
    if !(8..=64).contains(&decoded.len()) {
        return Err(Error(StatusCode::BAD_REQUEST, "invalid group id length"));
    }
    Ok(())
}

fn validate_group_envelope(kind: u8, envelope: &str) -> Result<(), Error> {
    if !matches!(kind, 1..=3) || envelope.len() < 4 || envelope.len() > 2_800_000 {
        return Err(Error(StatusCode::BAD_REQUEST, "invalid MLS envelope"));
    }
    let decoded = URL_SAFE_NO_PAD.decode(envelope).map_err(|_| {
        Error(
            StatusCode::BAD_REQUEST,
            "MLS envelope must be URL-safe base64",
        )
    })?;
    if decoded.len() < 3 || decoded.len() > 2_800_000 || decoded[0] != 1 || decoded[1] != kind {
        return Err(Error(StatusCode::BAD_REQUEST, "invalid MLS envelope"));
    }
    Ok(())
}

async fn wait_for_message(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<serde_json::Value>, Error> {
    let recipient = authenticate(&state, &headers)?;
    if has_pending_message(&state, &recipient.account_id)? {
        return Ok(Json(serde_json::json!({"available": true})));
    }
    let _ = tokio::time::timeout(
        std::time::Duration::from_secs(25),
        state.message_notify.notified(),
    )
    .await;
    Ok(Json(
        serde_json::json!({"available": has_pending_message(&state, &recipient.account_id)?}),
    ))
}

fn has_pending_message(state: &AppState, account_id: &str) -> Result<bool, Error> {
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.query_row(
        "SELECT EXISTS(
            SELECT 1 FROM messages
            WHERE recipient_account_id = ?1 AND delivered_at IS NULL
            UNION ALL
            SELECT 1 FROM message_deletions
            WHERE recipient_account_id = ?1
         )",
        params![account_id],
        |row| row.get(0),
    )
    .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not check inbox"))
}

async fn inbox(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<MessageResponse>>, Error> {
    let recipient = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let mut statement = db
        .prepare(
            "SELECT messages.id, accounts.nickname, messages.ciphertext, messages.created_at
         FROM messages JOIN accounts ON accounts.id = messages.sender_account_id
         WHERE messages.recipient_account_id = ?1 AND messages.delivered_at IS NULL
         ORDER BY messages.created_at ASC LIMIT 100",
        )
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load inbox"))?;
    let messages = statement
        .query_map(params![recipient.account_id], |row| {
            let id: String = row.get(0)?;
            Ok(MessageResponse {
                message_id: Uuid::parse_str(&id).expect("database contains valid UUIDs"),
                sender_nickname: row.get(1)?,
                ciphertext: row.get(2)?,
                created_at: row.get(3)?,
            })
        })
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load inbox"))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load inbox"))?;
    Ok(Json(messages))
}

async fn ack_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(message_id): axum::extract::Path<Uuid>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let acknowledged = db
        .execute(
            "UPDATE messages SET delivered_at = COALESCE(delivered_at, CURRENT_TIMESTAMP)
             WHERE id = ?1 AND recipient_account_id = ?2",
            params![message_id.to_string(), account.account_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not acknowledge message",
            )
        })?;
    if acknowledged == 0 {
        return Err(Error(StatusCode::NOT_FOUND, "message not found"));
    }
    Ok(StatusCode::NO_CONTENT)
}

/// Confirms read state only for the sender.  No plaintext or recipient presence is exposed.
async fn message_status(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(message_id): axum::extract::Path<Uuid>,
) -> Result<Json<MessageStatusResponse>, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.query_row(
        "SELECT delivered_at IS NOT NULL, read_at IS NOT NULL FROM messages WHERE id = ?1 AND sender_account_id = ?2",
        params![message_id.to_string(), account.account_id],
        |row| Ok(MessageStatusResponse { delivered: row.get(0)?, read: row.get(1)? }),
    )
    .map(Json)
    .map_err(|error| match error {
        rusqlite::Error::QueryReturnedNoRows => Error(StatusCode::NOT_FOUND, "message not found"),
        _ => Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load message status"),
    })
}

/// Called only when the recipient has opened a dialogue.  It deliberately records no message text.
async fn mark_peer_messages_read(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(nickname): axum::extract::Path<String>,
) -> Result<StatusCode, Error> {
    let recipient = authenticate(&state, &headers)?;
    let sender_nickname = normalize_nickname(&nickname)
        .ok_or(Error(StatusCode::BAD_REQUEST, "invalid sender nickname"))?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.execute(
        "UPDATE messages SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP)
         WHERE recipient_account_id = ?1 AND delivered_at IS NOT NULL
           AND sender_account_id = (SELECT id FROM accounts WHERE nickname = ?2)",
        params![recipient.account_id, sender_nickname],
    )
    .map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not acknowledge read state",
        )
    })?;
    Ok(StatusCode::NO_CONTENT)
}

async fn delete_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(message_id): axum::extract::Path<Uuid>,
    Query(query): Query<DeleteMessageQuery>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let participants: Option<(String, String)> = db
        .query_row(
            "SELECT sender_account_id, recipient_account_id FROM messages WHERE id = ?1",
            params![message_id.to_string()],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load message"))?;
    let Some((sender_id, recipient_id)) = participants else {
        return Err(Error(StatusCode::NOT_FOUND, "message not found"));
    };
    if account.account_id != sender_id && account.account_id != recipient_id {
        return Err(Error(StatusCode::NOT_FOUND, "message not found"));
    }
    if query.for_everyone && account.account_id != sender_id {
        return Err(Error(
            StatusCode::FORBIDDEN,
            "only the sender may delete for everyone",
        ));
    }
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    if query.for_everyone {
        transaction
            .execute(
                "INSERT INTO message_deletions
                    (id, recipient_account_id, message_id)
                 VALUES (?1, ?2, ?3)
                 ON CONFLICT(recipient_account_id, message_id) DO NOTHING",
                params![
                    Uuid::new_v4().to_string(),
                    recipient_id,
                    message_id.to_string()
                ],
            )
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not mark remote message deletion",
                )
            })?;
    }
    transaction
        .execute(
            "DELETE FROM messages WHERE id = ?1",
            params![message_id.to_string()],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not delete message",
            )
        })?;
    transaction.commit().map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not delete message",
        )
    })?;
    drop(db);
    state.message_notify.notify_waiters();
    Ok(StatusCode::NO_CONTENT)
}

async fn pending_message_deletions(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<MessageDeletionResponse>>, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let mut statement = db
        .prepare(
            "SELECT id, message_id FROM message_deletions
             WHERE recipient_account_id = ?1
             ORDER BY created_at ASC LIMIT 100",
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load message deletions",
            )
        })?;
    let deletions = statement
        .query_map(params![account.account_id], |row| {
            let deletion_id: String = row.get(0)?;
            let message_id: String = row.get(1)?;
            Ok(MessageDeletionResponse {
                deletion_id: Uuid::parse_str(&deletion_id)
                    .expect("database contains valid deletion UUIDs"),
                message_id: Uuid::parse_str(&message_id)
                    .expect("database contains valid message UUIDs"),
            })
        })
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load message deletions",
            )
        })?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load message deletions",
            )
        })?;
    Ok(Json(deletions))
}

async fn ack_message_deletion(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(deletion_id): axum::extract::Path<Uuid>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let deleted = db
        .execute(
            "DELETE FROM message_deletions
             WHERE id = ?1 AND recipient_account_id = ?2",
            params![deletion_id.to_string(), account.account_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not acknowledge message deletion",
            )
        })?;
    if deleted == 0 {
        return Err(Error(StatusCode::NOT_FOUND, "message deletion not found"));
    }
    Ok(StatusCode::NO_CONTENT)
}

/// Removes opaque server copies for both accounts and leaves a one-shot marker for the peer app.
async fn delete_conversation(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(nickname): axum::extract::Path<String>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    let peer_nickname =
        normalize_nickname(&nickname).ok_or(Error(StatusCode::BAD_REQUEST, "invalid nickname"))?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let peer_id: String = db
        .query_row(
            "SELECT id FROM accounts WHERE nickname = ?1",
            params![peer_nickname],
            |row| row.get(0),
        )
        .map_err(|error| match error {
            rusqlite::Error::QueryReturnedNoRows => Error(StatusCode::NOT_FOUND, "user not found"),
            _ => Error(StatusCode::INTERNAL_SERVER_ERROR, "could not find user"),
        })?;
    db.execute("DELETE FROM messages WHERE (sender_account_id = ?1 AND recipient_account_id = ?2) OR (sender_account_id = ?2 AND recipient_account_id = ?1)", params![account.account_id, peer_id])
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not delete messages"))?;
    db.execute("DELETE FROM attachments WHERE (sender_account_id = ?1 AND recipient_account_id = ?2) OR (sender_account_id = ?2 AND recipient_account_id = ?1)", params![account.account_id, peer_id])
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not delete attachments"))?;
    db.execute("INSERT INTO conversation_deletions (recipient_account_id, peer_account_id) VALUES (?1, ?2)", params![peer_id, account.account_id])
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not mark remote deletion"))?;
    Ok(StatusCode::NO_CONTENT)
}

async fn pending_conversation_deletions(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<ConversationDeletionResponse>>, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let mut statement = db.prepare("SELECT conversation_deletions.id, accounts.nickname FROM conversation_deletions JOIN accounts ON accounts.id = conversation_deletions.peer_account_id WHERE recipient_account_id = ?1")
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load deletions"))?;
    let rows = statement
        .query_map(params![account.account_id], |row| {
            Ok((
                row.get::<_, i64>(0)?,
                ConversationDeletionResponse {
                    peer_nickname: row.get(1)?,
                },
            ))
        })
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load deletions",
            )
        })?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not load deletions",
            )
        })?;
    drop(statement);
    for (id, _) in &rows {
        db.execute(
            "DELETE FROM conversation_deletions WHERE id = ?1",
            params![id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not acknowledge deletion",
            )
        })?;
    }
    Ok(Json(rows.into_iter().map(|(_, item)| item).collect()))
}

async fn upload_attachment(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<UploadAttachmentRequest>,
) -> Result<(StatusCode, Json<UploadAttachmentResponse>), Error> {
    let sender = authenticate(&state, &headers)?;
    let recipient_nickname = normalize_nickname(&request.recipient_nickname)
        .ok_or(Error(StatusCode::BAD_REQUEST, "invalid recipient nickname"))?;
    let ciphertext = decode_attachment_ciphertext(&request.ciphertext)?;

    let attachment_id = Uuid::new_v4();
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let recipient_id: String = db
        .query_row(
            "SELECT id FROM accounts WHERE nickname = ?1",
            params![recipient_nickname],
            |row| row.get(0),
        )
        .map_err(|error| match error {
            rusqlite::Error::QueryReturnedNoRows => {
                Error(StatusCode::NOT_FOUND, "recipient not found")
            }
            _ => Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not find recipient",
            ),
        })?;
    let blocked: bool = db
        .query_row(
            "SELECT EXISTS(
                SELECT 1 FROM blocks
                WHERE blocker_account_id = ?1 AND blocked_account_id = ?2
             )",
            params![recipient_id, sender.account_id],
            |row| row.get(0),
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not check recipient block list",
            )
        })?;
    if blocked {
        return Ok((
            StatusCode::CREATED,
            Json(UploadAttachmentResponse { attachment_id }),
        ));
    }
    let used_bytes: i64 = db
        .query_row(
            "SELECT
                COALESCE((SELECT SUM(length(ciphertext)) FROM attachments
                          WHERE sender_account_id = ?1), 0) +
                COALESCE((SELECT SUM(length(ciphertext)) FROM group_attachments
                          WHERE sender_account_id = ?1), 0)",
            params![sender.account_id],
            |row| row.get(0),
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not check attachment quota",
            )
        })?;
    if used_bytes.saturating_add(ciphertext.len() as i64) > ATTACHMENT_QUOTA_BYTES as i64 {
        return Err(Error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "encrypted attachment quota exceeded",
        ));
    }
    db.execute(
        "INSERT INTO attachments (id, sender_account_id, recipient_account_id, ciphertext)
         VALUES (?1, ?2, ?3, ?4)",
        params![
            attachment_id.to_string(),
            sender.account_id,
            recipient_id,
            ciphertext,
        ],
    )
    .map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not store attachment",
        )
    })?;
    Ok((
        StatusCode::CREATED,
        Json(UploadAttachmentResponse { attachment_id }),
    ))
}

async fn upload_group_attachment(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(group_id): axum::extract::Path<String>,
    Json(request): Json<UploadGroupAttachmentRequest>,
) -> Result<(StatusCode, Json<UploadAttachmentResponse>), Error> {
    let sender = authenticate(&state, &headers)?;
    validate_group_id(&group_id)?;
    let ciphertext = decode_attachment_ciphertext(&request.ciphertext)?;
    let attachment_id = Uuid::new_v4();
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let is_member: bool = db
        .query_row(
            "SELECT EXISTS(
                SELECT 1 FROM group_members
                WHERE group_id = ?1 AND account_id = ?2
             )",
            params![group_id, sender.account_id],
            |row| row.get(0),
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not authorize group attachment",
            )
        })?;
    if !is_member {
        return Err(Error(StatusCode::NOT_FOUND, "group not found"));
    }
    let used_bytes: i64 = db
        .query_row(
            "SELECT
                COALESCE((SELECT SUM(length(ciphertext)) FROM attachments
                          WHERE sender_account_id = ?1), 0) +
                COALESCE((SELECT SUM(length(ciphertext)) FROM group_attachments
                          WHERE sender_account_id = ?1), 0)",
            params![sender.account_id],
            |row| row.get(0),
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not check attachment quota",
            )
        })?;
    if used_bytes.saturating_add(ciphertext.len() as i64) > ATTACHMENT_QUOTA_BYTES as i64 {
        return Err(Error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "encrypted attachment quota exceeded",
        ));
    }
    db.execute(
        "INSERT INTO group_attachments (id, group_id, sender_account_id, ciphertext)
         VALUES (?1, ?2, ?3, ?4)",
        params![
            attachment_id.to_string(),
            group_id,
            sender.account_id,
            ciphertext,
        ],
    )
    .map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not store group attachment",
        )
    })?;
    Ok((
        StatusCode::CREATED,
        Json(UploadAttachmentResponse { attachment_id }),
    ))
}

async fn download_attachment(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(attachment_id): axum::extract::Path<Uuid>,
) -> Result<Json<AttachmentResponse>, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let ciphertext: Result<Vec<u8>, _> = db.query_row(
        "SELECT ciphertext FROM attachments
         WHERE id = ?1 AND (sender_account_id = ?2 OR recipient_account_id = ?2)
         UNION ALL
         SELECT group_attachments.ciphertext
         FROM group_attachments
         JOIN group_members ON group_members.group_id = group_attachments.group_id
         WHERE group_attachments.id = ?1 AND group_members.account_id = ?2
         LIMIT 1",
        params![attachment_id.to_string(), account.account_id],
        |row| row.get(0),
    );
    match ciphertext {
        Ok(ciphertext) => Ok(Json(AttachmentResponse {
            attachment_id,
            ciphertext: URL_SAFE_NO_PAD.encode(ciphertext),
        })),
        Err(rusqlite::Error::QueryReturnedNoRows) => {
            Err(Error(StatusCode::NOT_FOUND, "attachment not found"))
        }
        Err(_) => Err(Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not load attachment",
        )),
    }
}

async fn delete_attachment(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(attachment_id): axum::extract::Path<Uuid>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let removed_personal = db
        .execute(
            "DELETE FROM attachments
             WHERE id = ?1 AND (sender_account_id = ?2 OR recipient_account_id = ?2)",
            params![attachment_id.to_string(), account.account_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not delete attachment",
            )
        })?;
    let removed_group = db
        .execute(
            "DELETE FROM group_attachments
             WHERE id = ?1 AND sender_account_id = ?2",
            params![attachment_id.to_string(), account.account_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not delete group attachment",
            )
        })?;
    if removed_personal + removed_group == 0 {
        return Err(Error(StatusCode::NOT_FOUND, "attachment not found"));
    }
    Ok(StatusCode::NO_CONTENT)
}

fn decode_attachment_ciphertext(value: &str) -> Result<Vec<u8>, Error> {
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

fn migrate(db: &Connection) -> rusqlite::Result<()> {
    db.execute_batch(
        "PRAGMA foreign_keys = ON;
         CREATE TABLE IF NOT EXISTS invites (
            code_hash TEXT PRIMARY KEY NOT NULL,
            used_at TEXT
         );
         CREATE TABLE IF NOT EXISTS accounts (
            id TEXT PRIMARY KEY NOT NULL,
            nickname TEXT UNIQUE NOT NULL COLLATE NOCASE,
            display_name TEXT NOT NULL DEFAULT '',
            bio TEXT NOT NULL DEFAULT '',
            avatar BLOB,
            avatar_version TEXT,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY NOT NULL,
            account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            identity_public_key TEXT NOT NULL,
            access_token_hash TEXT NOT NULL UNIQUE,
            registration_id INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS blocks (
            blocker_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            blocked_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY(blocker_account_id, blocked_account_id),
            CHECK(blocker_account_id != blocked_account_id)
         );
         CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY NOT NULL,
            sender_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            ciphertext BLOB NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS groups (
            id TEXT PRIMARY KEY NOT NULL,
            owner_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS group_members (
            group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            role TEXT NOT NULL CHECK(role IN ('owner', 'admin', 'member')),
            PRIMARY KEY(group_id, account_id)
         );
         CREATE TABLE IF NOT EXISTS group_events (
            id TEXT PRIMARY KEY NOT NULL,
            group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            sender_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            client_event_id TEXT,
            kind INTEGER NOT NULL CHECK(kind IN (1, 2, 3)),
            envelope TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            delivered_at TEXT,
            removes_recipient INTEGER NOT NULL DEFAULT 0
         );
         CREATE TABLE IF NOT EXISTS mls_key_packages (
            device_id TEXT PRIMARY KEY NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            key_package TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS attachments (
            id TEXT PRIMARY KEY NOT NULL,
            sender_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            ciphertext TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            delivered_at TEXT
         );
         CREATE TABLE IF NOT EXISTS group_attachments (
            id TEXT PRIMARY KEY NOT NULL,
            group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            sender_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            ciphertext TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS conversation_deletions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            peer_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
         );
         CREATE TABLE IF NOT EXISTS message_deletions (
            id TEXT PRIMARY KEY NOT NULL,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            message_id TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(recipient_account_id, message_id)
         );
         CREATE TABLE IF NOT EXISTS group_deletions (
            id TEXT PRIMARY KEY NOT NULL,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            group_id TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(recipient_account_id, group_id)
         );
         CREATE TABLE IF NOT EXISTS prekey_bundles (
            device_id TEXT PRIMARY KEY NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            signed_prekey_id INTEGER NOT NULL,
            signed_prekey TEXT NOT NULL,
            signed_prekey_signature TEXT NOT NULL,
            kyber_signed_prekey_id INTEGER NOT NULL,
            kyber_signed_prekey TEXT NOT NULL,
            kyber_signed_prekey_signature TEXT NOT NULL
         );
         CREATE TABLE IF NOT EXISTS one_time_prekeys (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            key_kind TEXT NOT NULL CHECK(key_kind IN ('classical', 'kyber')),
            key_id INTEGER NOT NULL,
            public_key TEXT NOT NULL,
            signature TEXT,
            UNIQUE(device_id, key_kind, key_id)
         );",
    )?;
    let has_registration_id = db
        .prepare("PRAGMA table_info(devices)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|column| column == "registration_id");
    if !has_registration_id {
        db.execute(
            "ALTER TABLE devices ADD COLUMN registration_id INTEGER NOT NULL DEFAULT 0",
            [],
        )?;
    }
    let has_delivered_at = db
        .prepare("PRAGMA table_info(messages)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|column| column == "delivered_at");
    if !has_delivered_at {
        db.execute("ALTER TABLE messages ADD COLUMN delivered_at TEXT", [])?;
    }
    let has_read_at = db
        .prepare("PRAGMA table_info(messages)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|column| column == "read_at");
    if !has_read_at {
        db.execute("ALTER TABLE messages ADD COLUMN read_at TEXT", [])?;
    }
    let has_group_client_event_id = db
        .prepare("PRAGMA table_info(group_events)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|column| column == "client_event_id");
    if !has_group_client_event_id {
        db.execute(
            "ALTER TABLE group_events ADD COLUMN client_event_id TEXT",
            [],
        )?;
    }
    let has_group_removes_recipient = db
        .prepare("PRAGMA table_info(group_events)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|column| column == "removes_recipient");
    if !has_group_removes_recipient {
        db.execute(
            "ALTER TABLE group_events
             ADD COLUMN removes_recipient INTEGER NOT NULL DEFAULT 0",
            [],
        )?;
    }
    let account_columns = db
        .prepare("PRAGMA table_info(accounts)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    if !account_columns
        .iter()
        .any(|column| column == "display_name")
    {
        db.execute(
            "ALTER TABLE accounts ADD COLUMN display_name TEXT NOT NULL DEFAULT ''",
            [],
        )?;
    }
    if !account_columns.iter().any(|column| column == "bio") {
        db.execute(
            "ALTER TABLE accounts ADD COLUMN bio TEXT NOT NULL DEFAULT ''",
            [],
        )?;
    }
    if !account_columns.iter().any(|column| column == "avatar") {
        db.execute("ALTER TABLE accounts ADD COLUMN avatar BLOB", [])?;
    }
    if !account_columns
        .iter()
        .any(|column| column == "avatar_version")
    {
        db.execute("ALTER TABLE accounts ADD COLUMN avatar_version TEXT", [])?;
    }
    db.execute(
        "CREATE INDEX IF NOT EXISTS idx_messages_pending
         ON messages(recipient_account_id, delivered_at, created_at)",
        [],
    )?;
    db.execute(
        "CREATE INDEX IF NOT EXISTS idx_group_events_pending
         ON group_events(recipient_account_id, delivered_at, created_at)",
        [],
    )?;
    db.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_group_events_idempotency
         ON group_events(sender_account_id, recipient_account_id, client_event_id)
         WHERE client_event_id IS NOT NULL",
        [],
    )?;
    db.execute(
        "UPDATE devices SET registration_id = (ABS(RANDOM()) % 16380) + 1 WHERE registration_id = 0",
        [],
    )?;
    Ok(())
}

fn authorized(headers: &HeaderMap, secret: &str) -> bool {
    let expected = format!("Bearer {secret}");
    headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        == Some(expected.as_str())
}

fn authenticate(state: &AppState, headers: &HeaderMap) -> Result<AuthenticatedAccount, Error> {
    let token = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .ok_or(Error(StatusCode::UNAUTHORIZED, "missing access token"))?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.query_row(
        "SELECT account_id, id FROM devices WHERE access_token_hash = ?1",
        params![hash(token)],
        |row| {
            Ok(AuthenticatedAccount {
                account_id: row.get(0)?,
                device_id: row.get(1)?,
            })
        },
    )
    .map_err(|error| match error {
        rusqlite::Error::QueryReturnedNoRows => {
            Error(StatusCode::UNAUTHORIZED, "invalid access token")
        }
        _ => Error(StatusCode::INTERNAL_SERVER_ERROR, "could not authenticate"),
    })
}

fn normalize_nickname(value: &str) -> Option<String> {
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

fn validate_display_name(value: &str) -> Result<String, Error> {
    let normalized = value.trim();
    if normalized.chars().count() > 64 || normalized.chars().any(char::is_control) {
        return Err(Error(StatusCode::BAD_REQUEST, "invalid display name"));
    }
    Ok(normalized.to_owned())
}

fn validate_bio(value: &str) -> Result<String, Error> {
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

fn decode_avatar(value: &str) -> Result<Vec<u8>, Error> {
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

fn validate_signed_prekey(key: &PublicPreKey) -> Result<(), Error> {
    validate_key(&key.public_key)?;
    let signature = key.signature.as_deref().ok_or(Error(
        StatusCode::BAD_REQUEST,
        "signed prekey signature is required",
    ))?;
    validate_key(signature)
}

fn validate_one_time_prekeys(
    keys: &[PublicPreKey],
    signatures_required: bool,
) -> Result<(), Error> {
    if keys.len() > 1_000 {
        return Err(Error(StatusCode::BAD_REQUEST, "too many one-time prekeys"));
    }
    let mut ids = std::collections::HashSet::with_capacity(keys.len());
    for key in keys {
        if !ids.insert(key.id) {
            return Err(Error(
                StatusCode::BAD_REQUEST,
                "duplicate one-time prekey id",
            ));
        }
        validate_key(&key.public_key)?;
        if signatures_required {
            validate_key(key.signature.as_deref().ok_or(Error(
                StatusCode::BAD_REQUEST,
                "Kyber prekey signature is required",
            ))?)?;
        }
    }
    Ok(())
}

fn validate_key(value: &str) -> Result<(), Error> {
    if value.len() < 20 || value.len() > 16_000 || URL_SAFE_NO_PAD.decode(value).is_err() {
        return Err(Error(
            StatusCode::BAD_REQUEST,
            "key material must be URL-safe base64",
        ));
    }
    Ok(())
}

fn random_token(bytes: usize) -> String {
    let mut raw = vec![0_u8; bytes];
    rand::rng().fill(&mut raw[..]);
    URL_SAFE_NO_PAD.encode(raw)
}

fn hash(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}

const MAX_ATTACHMENT_BYTES: usize = 8 * 1024 * 1024;
const MAX_ATTACHMENT_BASE64_BYTES: usize = MAX_ATTACHMENT_BYTES.div_ceil(3) * 4;
const MAX_AVATAR_BYTES: usize = 512 * 1024;
const MAX_AVATAR_BASE64_BYTES: usize = MAX_AVATAR_BYTES.div_ceil(3) * 4;
const MAX_JSON_BODY_BYTES: usize = MAX_ATTACHMENT_BASE64_BYTES + 16 * 1024;
const ATTACHMENT_QUOTA_BYTES: usize = 1024 * 1024 * 1024;

#[cfg(test)]
mod tests {
    use super::{
        AppState, AttachmentStorageBackend, MAX_ATTACHMENT_BYTES, RateLimiter, build_app,
        decode_attachment_ciphertext, hash, migrate, normalize_nickname,
    };
    use axum::{
        body::{Body, to_bytes},
        http::{Request, StatusCode},
    };
    use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
    use rusqlite::Connection;
    use std::{
        collections::HashMap,
        sync::{Arc, Mutex},
        time::Duration,
    };
    use tokio::sync::Notify;
    use tower::ServiceExt;

    #[test]
    fn normalizes_valid_nickname() {
        assert_eq!(normalize_nickname(" @Alice_42 "), Some("alice_42".into()));
    }

    #[test]
    fn rejects_invalid_nickname() {
        assert_eq!(normalize_nickname("a-b"), None);
    }

    #[test]
    fn validates_encrypted_attachment_limits() {
        assert!(decode_attachment_ciphertext(&URL_SAFE_NO_PAD.encode([7_u8; 32])).is_ok());
        assert!(decode_attachment_ciphertext("not base64!").is_err());
        let oversized = vec![0_u8; MAX_ATTACHMENT_BYTES + 1];
        assert!(decode_attachment_ciphertext(&URL_SAFE_NO_PAD.encode(oversized)).is_err());
    }

    #[test]
    fn rate_limiter_rejects_excess_and_keeps_keys_independent() {
        let limiter = RateLimiter {
            entries: std::sync::Mutex::new(std::collections::HashMap::new()),
        };
        assert!(limiter.check("alice", 2, Duration::from_secs(60)).is_ok());
        assert!(limiter.check("alice", 2, Duration::from_secs(60)).is_ok());
        assert!(limiter.check("alice", 2, Duration::from_secs(60)).is_err());
        assert!(limiter.check("bob", 2, Duration::from_secs(60)).is_ok());
    }

    fn test_state() -> AppState {
        let db = Connection::open_in_memory().unwrap();
        migrate(&db).unwrap();
        AppState {
            db: Arc::new(Mutex::new(db)),
            bootstrap_secret: "test-bootstrap-secret-with-enough-length".into(),
            message_notify: Arc::new(Notify::new()),
            attachment_backend: AttachmentStorageBackend::Sqlite,
            rate_limiter: Arc::new(RateLimiter {
                entries: Mutex::new(HashMap::new()),
            }),
        }
    }

    fn test_app() -> axum::Router {
        build_app(test_state())
    }

    async fn request(
        app: &axum::Router,
        method: &str,
        path: &str,
        token: Option<&str>,
        body: String,
    ) -> (StatusCode, String) {
        let mut builder = Request::builder().method(method).uri(path);
        if let Some(token) = token {
            builder = builder.header("authorization", format!("Bearer {token}"));
        }
        let response = app
            .clone()
            .oneshot(
                builder
                    .header("content-type", "application/json")
                    .body(Body::from(body))
                    .unwrap(),
            )
            .await
            .unwrap();
        let status = response.status();
        let bytes = to_bytes(response.into_body(), 1_000_000).await.unwrap();
        (status, String::from_utf8(bytes.to_vec()).unwrap())
    }

    async fn register_account(app: &axum::Router, nickname: &str) -> String {
        let admin = "test-bootstrap-secret-with-enough-length";
        let (_, invite) =
            request(app, "POST", "/v1/admin/invites", Some(admin), String::new()).await;
        let invite = serde_json::from_str::<serde_json::Value>(&invite).unwrap()["invite_code"]
            .as_str()
            .unwrap()
            .to_owned();
        let (status, registered) = request(
            app,
            "POST",
            "/v1/auth/register",
            None,
            serde_json::json!({"nickname": nickname, "invite_code": invite, "identity_public_key": URL_SAFE_NO_PAD.encode([5_u8; 33]), "registration_id": 42}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        serde_json::from_str::<serde_json::Value>(&registered).unwrap()["access_token"]
            .as_str()
            .unwrap()
            .to_owned()
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn two_hundred_authenticated_waiters_can_wake_concurrently() {
        let state = test_state();
        let tokens = {
            let mut db = state.db.lock().unwrap();
            let transaction = db.transaction().unwrap();
            let mut tokens = Vec::with_capacity(200);
            for index in 0..200 {
                let account_id = uuid::Uuid::new_v4().to_string();
                let device_id = uuid::Uuid::new_v4().to_string();
                let token = format!("capacity-token-{index}");
                transaction
                    .execute(
                        "INSERT INTO accounts (id, nickname) VALUES (?1, ?2)",
                        rusqlite::params![account_id, format!("capacity_{index:03}")],
                    )
                    .unwrap();
                transaction
                    .execute(
                        "INSERT INTO devices
                         (id, account_id, identity_public_key, access_token_hash, registration_id)
                         VALUES (?1, ?2, ?3, ?4, ?5)",
                        rusqlite::params![
                            device_id,
                            account_id,
                            URL_SAFE_NO_PAD.encode([5_u8; 33]),
                            hash(&token),
                            index + 1,
                        ],
                    )
                    .unwrap();
                tokens.push(token);
            }
            transaction.commit().unwrap();
            tokens
        };
        let app = build_app(state.clone());
        let mut waiters = tokio::task::JoinSet::new();
        for token in tokens {
            let app = app.clone();
            waiters.spawn(async move {
                request(
                    &app,
                    "GET",
                    "/v1/messages/wait",
                    Some(&token),
                    String::new(),
                )
                .await
            });
        }

        for _ in 0..20 {
            tokio::time::sleep(Duration::from_millis(25)).await;
            state.message_notify.notify_waiters();
        }
        let completed = tokio::time::timeout(Duration::from_secs(3), async {
            let mut count = 0;
            while let Some(result) = waiters.join_next().await {
                let (status, response) = result.unwrap();
                assert_eq!(status, StatusCode::OK);
                assert_eq!(
                    serde_json::from_str::<serde_json::Value>(&response).unwrap()["available"],
                    false
                );
                count += 1;
            }
            count
        })
        .await
        .expect("200 concurrent waiters should finish promptly");
        assert_eq!(completed, 200);
    }

    #[tokio::test]
    async fn registration_and_message_delivery_work_over_http() {
        let app = test_app();
        let admin = "test-bootstrap-secret-with-enough-length";
        let (_, invite_alice) = request(
            &app,
            "POST",
            "/v1/admin/invites",
            Some(admin),
            String::new(),
        )
        .await;
        let invite_alice =
            serde_json::from_str::<serde_json::Value>(&invite_alice).unwrap()["invite_code"]
                .as_str()
                .unwrap()
                .to_owned();
        let (_, invite_bob) = request(
            &app,
            "POST",
            "/v1/admin/invites",
            Some(admin),
            String::new(),
        )
        .await;
        let invite_bob =
            serde_json::from_str::<serde_json::Value>(&invite_bob).unwrap()["invite_code"]
                .as_str()
                .unwrap()
                .to_owned();
        let registration = |nickname: &str, invite: String| {
            serde_json::json!({"nickname": nickname, "invite_code": invite, "identity_public_key": URL_SAFE_NO_PAD.encode([5_u8; 33]), "registration_id": 42}).to_string()
        };
        let (alice_status, alice) = request(
            &app,
            "POST",
            "/v1/auth/register",
            None,
            registration("alice", invite_alice.clone()),
        )
        .await;
        assert_eq!(alice_status, StatusCode::CREATED);
        let alice_token =
            serde_json::from_str::<serde_json::Value>(&alice).unwrap()["access_token"]
                .as_str()
                .unwrap()
                .to_owned();
        let (bob_status, bob) = request(
            &app,
            "POST",
            "/v1/auth/register",
            None,
            registration("bob", invite_bob),
        )
        .await;
        assert_eq!(bob_status, StatusCode::CREATED);
        let bob_token = serde_json::from_str::<serde_json::Value>(&bob).unwrap()["access_token"]
            .as_str()
            .unwrap()
            .to_owned();
        let (reused_status, _) = request(
            &app,
            "POST",
            "/v1/auth/register",
            None,
            registration("eve", invite_alice),
        )
        .await;
        assert_eq!(reused_status, StatusCode::UNAUTHORIZED);
        let (send_status, _) = request(
            &app,
            "POST",
            "/v1/messages",
            Some(&alice_token),
            serde_json::json!({"recipient_nickname":"bob","ciphertext":"aGVsbG8"}).to_string(),
        )
        .await;
        assert_eq!(send_status, StatusCode::CREATED);
        let (inbox_status, inbox) =
            request(&app, "GET", "/v1/messages", Some(&bob_token), String::new()).await;
        assert_eq!(inbox_status, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox).unwrap()[0]["sender_nickname"],
            "alice"
        );
    }

    #[tokio::test]
    async fn blocked_sender_is_silently_dropped_until_unblocked() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let (status, _) = request(&app, "PUT", "/v1/blocks/alice", Some(&bob), String::new()).await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, blocks) = request(&app, "GET", "/v1/blocks", Some(&bob), String::new()).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&blocks).unwrap()[0]["nickname"],
            "alice"
        );
        let (status, _) = request(
            &app,
            "POST",
            "/v1/messages",
            Some(&alice),
            serde_json::json!({
                "recipient_nickname": "bob",
                "ciphertext": "aGVsbG8",
            })
            .to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let (_, inbox) = request(&app, "GET", "/v1/messages", Some(&bob), String::new()).await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            0
        );
        let (status, _) = request(
            &app,
            "DELETE",
            "/v1/blocks/alice",
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, _) = request(
            &app,
            "POST",
            "/v1/messages",
            Some(&alice),
            serde_json::json!({
                "recipient_nickname": "bob",
                "ciphertext": "aGVsbG8",
            })
            .to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let (_, inbox) = request(&app, "GET", "/v1/messages", Some(&bob), String::new()).await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            1
        );
    }

    #[tokio::test]
    async fn profile_metadata_and_avatar_are_available_to_authenticated_users() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let (status, profile) = request(
            &app,
            "PUT",
            "/v1/profile",
            Some(&alice),
            serde_json::json!({
                "display_name": "Alice Example",
                "bio": "Private messages, public profile."
            })
            .to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let profile: serde_json::Value = serde_json::from_str(&profile).unwrap();
        assert_eq!(profile["nickname"], "alice");
        assert_eq!(profile["display_name"], "Alice Example");

        let avatar = URL_SAFE_NO_PAD.encode([0xff, 0xd8, 0xff, 0x00, 0xff, 0xd9]);
        let (status, uploaded) = request(
            &app,
            "PUT",
            "/v1/profile/avatar",
            Some(&alice),
            serde_json::json!({"image": avatar}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let version = serde_json::from_str::<serde_json::Value>(&uploaded).unwrap()["version"]
            .as_str()
            .unwrap()
            .to_owned();

        let (status, search) = request(
            &app,
            "GET",
            "/v1/users?query=ali",
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let search: serde_json::Value = serde_json::from_str(&search).unwrap();
        assert_eq!(search[0]["display_name"], "Alice Example");
        assert_eq!(search[0]["avatar_version"], version);

        let (status, downloaded) = request(
            &app,
            "GET",
            "/v1/users/alice/avatar",
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&downloaded).unwrap()["image"],
            avatar
        );
    }

    #[tokio::test]
    async fn group_transport_accepts_only_member_routed_opaque_mls_events() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let charlie = register_account(&app, "charlie").await;
        let group_id = URL_SAFE_NO_PAD.encode([9_u8; 16]);
        let create_request =
            serde_json::json!({"group_id": group_id, "members": [{"nickname":"bob", "role":"member"}]}).to_string();
        let (status, first_group) = request(
            &app,
            "POST",
            "/v1/groups",
            Some(&alice),
            create_request.clone(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let (status, repeated_group) =
            request(&app, "POST", "/v1/groups", Some(&alice), create_request).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(first_group, repeated_group);
        let envelope = URL_SAFE_NO_PAD.encode([1_u8, 1, 42]);
        let client_event_id = URL_SAFE_NO_PAD.encode([7_u8; 32]);
        let event_request = serde_json::json!({
            "client_event_id": client_event_id,
            "kind": 1,
            "recipient_nicknames": ["bob"],
            "envelope": envelope,
        })
        .to_string();
        let (status, first_response) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/events").as_str(),
            Some(&alice),
            event_request.clone(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let (status, repeated_response) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/events").as_str(),
            Some(&alice),
            event_request,
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        assert_eq!(first_response, repeated_response);
        let (status, inbox) =
            request(&app, "GET", "/v1/groups/events", Some(&bob), String::new()).await;
        assert_eq!(status, StatusCode::OK);
        let inbox = serde_json::from_str::<serde_json::Value>(&inbox).unwrap();
        assert_eq!(inbox.as_array().unwrap().len(), 1);
        assert_eq!(inbox[0]["group_id"], group_id);
        assert_eq!(inbox[0]["kind"], 1);
        let event_id = inbox[0]["event_id"].as_str().unwrap();
        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/events/{event_id}").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (_, inbox) = request(&app, "GET", "/v1/groups/events", Some(&bob), String::new()).await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            0
        );
        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/events").as_str(),
            Some(&bob),
            serde_json::json!({
                "client_event_id": URL_SAFE_NO_PAD.encode([8_u8; 32]),
                "kind": 2,
                "recipient_nicknames": ["alice"],
                "envelope": URL_SAFE_NO_PAD.encode([1_u8, 2, 42]),
            })
            .to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN);

        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/members").as_str(),
            Some(&bob),
            serde_json::json!({"nickname":"charlie","role":"member"}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN);
        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/members").as_str(),
            Some(&alice),
            serde_json::json!({"nickname":"charlie","role":"member"}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, details) = request(
            &app,
            "GET",
            format!("/v1/groups/{group_id}").as_str(),
            Some(&charlie),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let details = serde_json::from_str::<serde_json::Value>(&details).unwrap();
        assert_eq!(details["owner_nickname"], "alice");
        assert_eq!(details["members"].as_array().unwrap().len(), 3);
        let group_message_id = URL_SAFE_NO_PAD.encode([11_u8; 16]);
        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/events").as_str(),
            Some(&alice),
            serde_json::json!({
                "client_event_id": group_message_id,
                "kind": 3,
                "recipient_nicknames": ["bob", "charlie"],
                "envelope": URL_SAFE_NO_PAD.encode([1_u8, 3, 42]),
            })
            .to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let (status, _) = request(
            &app,
            "DELETE",
            format!("/v1/groups/{group_id}/messages/{group_message_id}").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (_, inbox) = request(&app, "GET", "/v1/groups/events", Some(&bob), String::new()).await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            1
        );
        let (status, _) = request(
            &app,
            "DELETE",
            format!("/v1/groups/{group_id}/messages/{group_message_id}").as_str(),
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (_, inbox) = request(&app, "GET", "/v1/groups/events", Some(&bob), String::new()).await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            0
        );
        let (status, _) = request(
            &app,
            "PUT",
            format!("/v1/groups/{group_id}/members/charlie/role").as_str(),
            Some(&bob),
            serde_json::json!({"role":"admin"}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN);
        let (status, _) = request(
            &app,
            "PUT",
            format!("/v1/groups/{group_id}/members/charlie/role").as_str(),
            Some(&alice),
            serde_json::json!({"role":"admin"}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let remove_bob_id = URL_SAFE_NO_PAD.encode([12_u8; 32]);
        let remove_bob = serde_json::json!({
            "client_event_id": remove_bob_id,
            "kind": 2,
            "recipient_nicknames": ["bob", "charlie"],
            "envelope": URL_SAFE_NO_PAD.encode([1_u8, 2, 99]),
            "remove_member_nickname": "bob",
        })
        .to_string();
        let (status, first_removal) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/events").as_str(),
            Some(&alice),
            remove_bob.clone(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let (status, repeated_removal) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/events").as_str(),
            Some(&alice),
            remove_bob,
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        assert_eq!(first_removal, repeated_removal);
        let (status, inbox) =
            request(&app, "GET", "/v1/groups/events", Some(&bob), String::new()).await;
        assert_eq!(status, StatusCode::OK);
        let inbox = serde_json::from_str::<serde_json::Value>(&inbox).unwrap();
        assert_eq!(inbox.as_array().unwrap().len(), 1);
        assert_eq!(inbox[0]["removes_recipient"], true);
        let removal_event_id = inbox[0]["event_id"].as_str().unwrap();
        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/events/{removal_event_id}").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, _) = request(
            &app,
            "GET",
            format!("/v1/groups/{group_id}").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        let (status, _) = request(
            &app,
            "DELETE",
            format!("/v1/groups/{group_id}").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN);
        let (status, _) = request(
            &app,
            "DELETE",
            format!("/v1/groups/{group_id}").as_str(),
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (_, deletions) = request(
            &app,
            "GET",
            "/v1/groups/deletions",
            Some(&charlie),
            String::new(),
        )
        .await;
        let deletions = serde_json::from_str::<serde_json::Value>(&deletions).unwrap();
        assert_eq!(deletions[0]["group_id"], group_id);
        let deletion_id = deletions[0]["deletion_id"].as_str().unwrap();
        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/deletions/{deletion_id}").as_str(),
            Some(&charlie),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
    }

    #[tokio::test]
    async fn conversation_deletion_erases_server_data_and_notifies_peer() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let (sent, _) = request(
            &app,
            "POST",
            "/v1/messages",
            Some(&alice),
            serde_json::json!({"recipient_nickname":"bob","ciphertext":"aGVsbG8"}).to_string(),
        )
        .await;
        assert_eq!(sent, StatusCode::CREATED);
        let (deleted, _) = request(
            &app,
            "DELETE",
            "/v1/conversations/bob",
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(deleted, StatusCode::NO_CONTENT);
        let (notice_status, notices) = request(
            &app,
            "GET",
            "/v1/conversations/deletions",
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(notice_status, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&notices).unwrap()[0]["peer_nickname"],
            "alice"
        );
        let (inbox_status, inbox) =
            request(&app, "GET", "/v1/messages", Some(&bob), String::new()).await;
        assert_eq!(inbox_status, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            0
        );
    }

    #[tokio::test]
    async fn sender_can_reliably_delete_one_message_for_everyone() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let (sent, body) = request(
            &app,
            "POST",
            "/v1/messages",
            Some(&alice),
            serde_json::json!({"recipient_nickname":"bob","ciphertext":"aGVsbG8"}).to_string(),
        )
        .await;
        assert_eq!(sent, StatusCode::CREATED);
        let message_id = serde_json::from_str::<serde_json::Value>(&body).unwrap()["message_id"]
            .as_str()
            .unwrap()
            .to_owned();
        let (deleted, _) = request(
            &app,
            "DELETE",
            format!("/v1/messages/{message_id}?for_everyone=true").as_str(),
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(deleted, StatusCode::NO_CONTENT);
        let (_, inbox) = request(&app, "GET", "/v1/messages", Some(&bob), String::new()).await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            0
        );
        let (_, deletions) = request(
            &app,
            "GET",
            "/v1/messages/deletions",
            Some(&bob),
            String::new(),
        )
        .await;
        let deletions = serde_json::from_str::<serde_json::Value>(&deletions).unwrap();
        assert_eq!(deletions[0]["message_id"], message_id);
        let deletion_id = deletions[0]["deletion_id"].as_str().unwrap();
        let (acknowledged, _) = request(
            &app,
            "POST",
            format!("/v1/messages/deletions/{deletion_id}").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(acknowledged, StatusCode::NO_CONTENT);
        let (_, empty) = request(
            &app,
            "GET",
            "/v1/messages/deletions",
            Some(&bob),
            String::new(),
        )
        .await;
        assert!(
            serde_json::from_str::<serde_json::Value>(&empty)
                .unwrap()
                .as_array()
                .unwrap()
                .is_empty()
        );

        let (_, second) = request(
            &app,
            "POST",
            "/v1/messages",
            Some(&alice),
            serde_json::json!({"recipient_nickname":"bob","ciphertext":"d29ybGQ"}).to_string(),
        )
        .await;
        let second_id = serde_json::from_str::<serde_json::Value>(&second).unwrap()["message_id"]
            .as_str()
            .unwrap()
            .to_owned();
        let (forbidden, _) = request(
            &app,
            "DELETE",
            format!("/v1/messages/{second_id}?for_everyone=true").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(forbidden, StatusCode::FORBIDDEN);
    }

    #[tokio::test]
    async fn attachment_is_available_only_to_participants_and_can_be_deleted() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let charlie = register_account(&app, "charlie").await;
        let ciphertext = URL_SAFE_NO_PAD.encode([9_u8; 64]);
        let (uploaded, body) = request(
            &app,
            "POST",
            "/v1/attachments",
            Some(&alice),
            serde_json::json!({"recipient_nickname":"bob","ciphertext":ciphertext}).to_string(),
        )
        .await;
        assert_eq!(uploaded, StatusCode::CREATED);
        let id = serde_json::from_str::<serde_json::Value>(&body).unwrap()["attachment_id"]
            .as_str()
            .unwrap()
            .to_owned();
        let (downloaded, downloaded_body) = request(
            &app,
            "GET",
            &format!("/v1/attachments/{id}"),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(downloaded, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&downloaded_body).unwrap()["ciphertext"],
            ciphertext
        );
        let (forbidden, _) = request(
            &app,
            "GET",
            &format!("/v1/attachments/{id}"),
            Some(&charlie),
            String::new(),
        )
        .await;
        assert_eq!(forbidden, StatusCode::NOT_FOUND);
        let (removed, _) = request(
            &app,
            "DELETE",
            &format!("/v1/attachments/{id}"),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(removed, StatusCode::NO_CONTENT);
        let (missing, _) = request(
            &app,
            "GET",
            &format!("/v1/attachments/{id}"),
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(missing, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn group_attachment_is_shared_once_and_only_with_current_members() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let charlie = register_account(&app, "charlie").await;
        let group_id = URL_SAFE_NO_PAD.encode([31_u8; 16]);
        let (created, _) = request(
            &app,
            "POST",
            "/v1/groups",
            Some(&alice),
            serde_json::json!({
                "group_id": group_id,
                "members": [{"nickname": "bob", "role": "member"}],
            })
            .to_string(),
        )
        .await;
        assert_eq!(created, StatusCode::CREATED);
        let ciphertext = URL_SAFE_NO_PAD.encode([17_u8; 96]);
        let (uploaded, body) = request(
            &app,
            "POST",
            &format!("/v1/groups/{group_id}/attachments"),
            Some(&alice),
            serde_json::json!({"ciphertext": ciphertext}).to_string(),
        )
        .await;
        assert_eq!(uploaded, StatusCode::CREATED);
        let id = serde_json::from_str::<serde_json::Value>(&body).unwrap()["attachment_id"]
            .as_str()
            .unwrap()
            .to_owned();
        let (downloaded, body) = request(
            &app,
            "GET",
            &format!("/v1/attachments/{id}"),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(downloaded, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&body).unwrap()["ciphertext"],
            ciphertext
        );
        let (outsider, _) = request(
            &app,
            "GET",
            &format!("/v1/attachments/{id}"),
            Some(&charlie),
            String::new(),
        )
        .await;
        assert_eq!(outsider, StatusCode::NOT_FOUND);
        let (forbidden_delete, _) = request(
            &app,
            "DELETE",
            &format!("/v1/attachments/{id}"),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(forbidden_delete, StatusCode::NOT_FOUND);
        let (deleted, _) = request(
            &app,
            "DELETE",
            &format!("/v1/attachments/{id}"),
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(deleted, StatusCode::NO_CONTENT);
    }
}
