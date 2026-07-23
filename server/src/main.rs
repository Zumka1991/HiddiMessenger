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
use rusqlite::{Connection, ErrorCode, params};
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
    registration_id: u32,
}

#[derive(Serialize)]
struct UserResponse {
    account_id: Uuid,
    nickname: String,
    identity_public_key: String,
}

#[derive(Deserialize)]
struct UserSearchQuery {
    query: String,
}

#[derive(Serialize)]
struct UserSearchItem {
    nickname: String,
}

#[derive(Deserialize)]
struct SendMessageRequest {
    recipient_nickname: String,
    ciphertext: String,
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

#[derive(Deserialize)]
struct UploadAttachmentRequest {
    recipient_nickname: String,
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
        .route("/v1/users/{nickname}", get(find_user))
        .route("/v1/messages", post(send_message).get(inbox))
        .route("/v1/messages/wait", get(wait_for_message))
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
    Ok(Json(CurrentDeviceResponse { registration_id }))
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
        "SELECT accounts.id, accounts.nickname, devices.identity_public_key
         FROM accounts JOIN devices ON devices.account_id = accounts.id
         WHERE accounts.nickname = ?1 ORDER BY devices.created_at ASC LIMIT 1",
        params![nickname],
        |row| {
            let account_id: String = row.get(0)?;
            Ok(UserResponse {
                account_id: Uuid::parse_str(&account_id).expect("database contains valid UUIDs"),
                nickname: row.get(1)?,
                identity_public_key: row.get(2)?,
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
            "SELECT nickname FROM accounts
             WHERE nickname LIKE ?1 ESCAPE '\\'
             ORDER BY nickname ASC LIMIT 20",
        )
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not search users"))?;
    let users = statement
        .query_map(params![pattern], |row| {
            Ok(UserSearchItem {
                nickname: row.get(0)?,
            })
        })
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not search users"))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not search users"))?;
    Ok(Json(users))
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
        "SELECT EXISTS(SELECT 1 FROM messages WHERE recipient_account_id = ?1 AND delivered_at IS NULL)",
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
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let removed = db
        .execute(
            "DELETE FROM messages
             WHERE id = ?1 AND (recipient_account_id = ?2 OR sender_account_id = ?2)",
            params![message_id.to_string(), account.account_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not delete message",
            )
        })?;
    if removed == 0 {
        return Err(Error(StatusCode::NOT_FOUND, "message not found"));
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
    let used_bytes: i64 = db
        .query_row(
            "SELECT COALESCE(SUM(length(ciphertext)), 0) FROM attachments WHERE sender_account_id = ?1",
            params![sender.account_id],
            |row| row.get(0),
        )
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not check attachment quota"))?;
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
         WHERE id = ?1 AND (sender_account_id = ?2 OR recipient_account_id = ?2)",
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
    let removed = db
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
    if removed == 0 {
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
         CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY NOT NULL,
            sender_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            ciphertext BLOB NOT NULL,
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
         CREATE TABLE IF NOT EXISTS conversation_deletions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            recipient_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            peer_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
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
    db.execute(
        "CREATE INDEX IF NOT EXISTS idx_messages_pending
         ON messages(recipient_account_id, delivered_at, created_at)",
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
const MAX_JSON_BODY_BYTES: usize = MAX_ATTACHMENT_BASE64_BYTES + 16 * 1024;
const ATTACHMENT_QUOTA_BYTES: usize = 1024 * 1024 * 1024;

#[cfg(test)]
mod tests {
    use super::{
        AppState, AttachmentStorageBackend, MAX_ATTACHMENT_BYTES, RateLimiter, build_app,
        decode_attachment_ciphertext, migrate, normalize_nickname,
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

    fn test_app() -> axum::Router {
        let db = Connection::open_in_memory().unwrap();
        migrate(&db).unwrap();
        build_app(AppState {
            db: Arc::new(Mutex::new(db)),
            bootstrap_secret: "test-bootstrap-secret-with-enough-length".into(),
            message_notify: Arc::new(Notify::new()),
            attachment_backend: AttachmentStorageBackend::Sqlite,
            rate_limiter: Arc::new(RateLimiter {
                entries: Mutex::new(HashMap::new()),
            }),
        })
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
}
