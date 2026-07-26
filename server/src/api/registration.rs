use std::time::Duration;

use axum::{
    Json, Router,
    extract::State,
    http::{HeaderMap, StatusCode},
    routing::{get, post},
};
use rusqlite::{ErrorCode, params};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    auth::authorized,
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
}

#[derive(Serialize)]
struct RegisterResponse {
    account_id: Uuid,
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
            device_id,
            device_number: 1,
            registration_id: request.registration_id,
            access_token,
        }),
    ))
}
