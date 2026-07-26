use std::time::{Duration, SystemTime, UNIX_EPOCH};

use axum::{
    Json, Router,
    extract::State,
    http::{HeaderMap, StatusCode},
    routing::{get, post, put},
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use rusqlite::{ErrorCode, params};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    auth::{authenticate, authorized},
    crypto::{hash, random_token},
    error::Error,
    state::AppState,
    validation::{normalize_device_name, normalize_nickname, validate_device_material},
};

pub(crate) fn routes(router: Router<AppState>) -> Router<AppState> {
    router
        .route("/health", get(health))
        .route("/v1/admin/invites", post(create_invite))
        .route("/v1/auth/register", post(register))
        .route("/v1/auth/recovery-key", put(set_recovery_key))
        .route(
            "/v1/auth/recovery/challenge",
            post(create_recovery_challenge),
        )
        .route("/v1/auth/recover", post(recover))
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
    #[serde(default)]
    device_name: String,
    #[serde(default)]
    recovery_public_key: Option<String>,
}

#[derive(Deserialize)]
struct SetRecoveryKeyRequest {
    recovery_public_key: String,
}

#[derive(Deserialize)]
struct RecoveryChallengeRequest {
    nickname: String,
}

#[derive(Serialize)]
struct RecoveryChallengeResponse {
    challenge_id: Uuid,
    challenge: String,
}

#[derive(Deserialize)]
struct RecoverRequest {
    nickname: String,
    challenge_id: Uuid,
    signature: String,
    identity_public_key: String,
    registration_id: u32,
    #[serde(default)]
    device_name: String,
}

#[derive(Serialize)]
struct RecoverResponse {
    account_id: Uuid,
    nickname: String,
    device_id: Uuid,
    device_number: u32,
    registration_id: u32,
    access_token: String,
}

#[derive(Serialize)]
struct RegisterResponse {
    account_id: Uuid,
    nickname: String,
    device_id: Uuid,
    device_number: u32,
    registration_id: u32,
    access_token: String,
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
    validate_device_material(&request.identity_public_key, request.registration_id)?;
    if let Some(public_key) = request.recovery_public_key.as_deref() {
        validate_recovery_public_key(public_key)?;
    }
    let device_name = normalize_device_name(&request.device_name, "Android");
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
        "INSERT INTO accounts (id, nickname, recovery_public_key) VALUES (?1, ?2, ?3)",
        params![
            account_id.to_string(),
            nickname,
            request.recovery_public_key
        ],
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
        "INSERT INTO devices
         (id, account_id, identity_public_key, access_token_hash, registration_id, device_number, device_name)
         VALUES (?1, ?2, ?3, ?4, ?5, 1, ?6)",
        params![
            device_id.to_string(),
            account_id.to_string(),
            request.identity_public_key,
            token_hash,
            request.registration_id,
            device_name,
        ],
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
            nickname,
            device_id,
            device_number: 1,
            registration_id: request.registration_id,
            access_token,
        }),
    ))
}

async fn set_recovery_key(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<SetRecoveryKeyRequest>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    validate_recovery_public_key(&request.recovery_public_key)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    db.execute(
        "UPDATE accounts SET recovery_public_key = ?1 WHERE id = ?2",
        params![request.recovery_public_key, account.account_id],
    )
    .map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not update recovery key",
        )
    })?;
    Ok(StatusCode::NO_CONTENT)
}

async fn create_recovery_challenge(
    State(state): State<AppState>,
    Json(request): Json<RecoveryChallengeRequest>,
) -> Result<Json<RecoveryChallengeResponse>, Error> {
    state
        .rate_limiter
        .check("recovery-challenge", 20, Duration::from_secs(60))?;
    let nickname = normalize_nickname(&request.nickname)
        .ok_or(Error(StatusCode::BAD_REQUEST, "invalid nickname"))?;
    let challenge_id = Uuid::new_v4();
    let challenge = random_token(32);
    let expires_at = unix_time() + 5 * 60;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let account_id: String = db
        .query_row(
            "SELECT id FROM accounts
             WHERE nickname = ?1 AND recovery_public_key IS NOT NULL",
            params![nickname],
            |row| row.get(0),
        )
        .map_err(|error| match error {
            rusqlite::Error::QueryReturnedNoRows => {
                Error(StatusCode::UNAUTHORIZED, "account recovery is unavailable")
            }
            _ => Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not create recovery challenge",
            ),
        })?;
    db.execute(
        "DELETE FROM recovery_challenges
         WHERE account_id = ?1 AND (consumed_at IS NOT NULL OR expires_at < ?2)",
        params![account_id, unix_time()],
    )
    .map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not clean recovery challenges",
        )
    })?;
    db.execute(
        "INSERT INTO recovery_challenges (id, account_id, challenge, expires_at)
         VALUES (?1, ?2, ?3, ?4)",
        params![challenge_id.to_string(), account_id, challenge, expires_at],
    )
    .map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not create recovery challenge",
        )
    })?;
    Ok(Json(RecoveryChallengeResponse {
        challenge_id,
        challenge,
    }))
}

async fn recover(
    State(state): State<AppState>,
    Json(request): Json<RecoverRequest>,
) -> Result<(StatusCode, Json<RecoverResponse>), Error> {
    state
        .rate_limiter
        .check("recover", 12, Duration::from_secs(60))?;
    let nickname = normalize_nickname(&request.nickname)
        .ok_or(Error(StatusCode::BAD_REQUEST, "invalid nickname"))?;
    validate_device_material(&request.identity_public_key, request.registration_id)?;
    let signature = URL_SAFE_NO_PAD
        .decode(&request.signature)
        .ok()
        .and_then(|raw| Signature::from_slice(&raw).ok())
        .ok_or(Error(StatusCode::UNAUTHORIZED, "invalid recovery proof"))?;
    let device_name = normalize_device_name(&request.device_name, "Recovered Android");
    let device_id = Uuid::new_v4();
    let access_token = random_token(48);
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let (account_id, public_key, challenge, expires_at): (String, String, String, i64) =
        transaction
            .query_row(
                "SELECT accounts.id, accounts.recovery_public_key,
                        recovery_challenges.challenge, recovery_challenges.expires_at
                 FROM recovery_challenges
                 JOIN accounts ON accounts.id = recovery_challenges.account_id
                 WHERE recovery_challenges.id = ?1
                   AND accounts.nickname = ?2
                   AND recovery_challenges.consumed_at IS NULL",
                params![request.challenge_id.to_string(), nickname],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )
            .map_err(|_| Error(StatusCode::UNAUTHORIZED, "invalid recovery proof"))?;
    if expires_at < unix_time() {
        return Err(Error(
            StatusCode::UNAUTHORIZED,
            "recovery challenge expired",
        ));
    }
    let public_key = decode_recovery_public_key(&public_key)?;
    let proof = recovery_proof(request.challenge_id, &challenge);
    public_key
        .verify(proof.as_bytes(), &signature)
        .map_err(|_| Error(StatusCode::UNAUTHORIZED, "invalid recovery proof"))?;
    let consumed = transaction
        .execute(
            "UPDATE recovery_challenges SET consumed_at = CURRENT_TIMESTAMP
             WHERE id = ?1 AND consumed_at IS NULL",
            params![request.challenge_id.to_string()],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not recover account",
            )
        })?;
    if consumed != 1 {
        return Err(Error(StatusCode::UNAUTHORIZED, "invalid recovery proof"));
    }
    let device_number: u32 = transaction
        .query_row(
            "SELECT COALESCE(MAX(device_number), 0) + 1 FROM devices WHERE account_id = ?1",
            params![account_id],
            |row| row.get(0),
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not recover account",
            )
        })?;
    transaction
        .execute(
            "INSERT INTO devices
             (id, account_id, identity_public_key, access_token_hash,
              registration_id, device_number, device_name)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                device_id.to_string(),
                account_id,
                request.identity_public_key,
                hash(&access_token),
                request.registration_id,
                device_number,
                device_name,
            ],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not recover account",
            )
        })?;
    transaction.commit().map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not recover account",
        )
    })?;
    Ok((
        StatusCode::CREATED,
        Json(RecoverResponse {
            account_id: Uuid::parse_str(&account_id).expect("database contains valid UUIDs"),
            nickname,
            device_id,
            device_number,
            registration_id: request.registration_id,
            access_token,
        }),
    ))
}

fn validate_recovery_public_key(value: &str) -> Result<(), Error> {
    decode_recovery_public_key(value).map(|_| ())
}

fn decode_recovery_public_key(value: &str) -> Result<VerifyingKey, Error> {
    let raw = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| Error(StatusCode::BAD_REQUEST, "invalid recovery public key"))?;
    let raw: [u8; 32] = raw
        .try_into()
        .map_err(|_| Error(StatusCode::BAD_REQUEST, "invalid recovery public key"))?;
    VerifyingKey::from_bytes(&raw)
        .map_err(|_| Error(StatusCode::BAD_REQUEST, "invalid recovery public key"))
}

fn recovery_proof(challenge_id: Uuid, challenge: &str) -> String {
    format!("hiddi-recovery-v1\0{challenge_id}\0{challenge}")
}

fn unix_time() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock is after Unix epoch")
        .as_secs() as i64
}
