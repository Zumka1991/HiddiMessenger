use std::time::{Duration, SystemTime, UNIX_EPOCH};

use axum::{
    Json, Router,
    extract::State,
    http::{HeaderMap, StatusCode},
    routing::{get, post, put},
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use rusqlite::params;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    auth::authenticate,
    crypto::{hash, random_token},
    error::Error,
    state::AppState,
    validation::{normalize_device_name, normalize_nickname, validate_device_material},
};

pub(crate) fn routes(router: Router<AppState>) -> Router<AppState> {
    router
        .route("/v1/devices", get(list_devices))
        .route("/v1/devices/link-code", post(create_device_link_code))
        .route("/v1/devices/link", post(link_device))
        .route("/v1/devices/prekeys", put(upload_prekeys))
        .route(
            "/v1/devices/current",
            get(current_device).delete(delete_current_device),
        )
        .route(
            "/v1/users/{nickname}/prekey-bundle",
            get(take_prekey_bundle),
        )
        .route(
            "/v1/users/{nickname}/prekey-bundles",
            get(take_prekey_bundles),
        )
}

#[derive(Serialize)]
struct CurrentDeviceResponse {
    device_id: Uuid,
    device_number: u32,
    registration_id: u32,
}

#[derive(Serialize)]
struct DeviceResponse {
    device_id: Uuid,
    device_number: u32,
    device_name: String,
    current: bool,
    created_at: String,
}

#[derive(Serialize)]
struct DeviceLinkCodeResponse {
    link_code: String,
    expires_at: i64,
}

#[derive(Deserialize)]
struct LinkDeviceRequest {
    link_code: String,
    identity_public_key: String,
    registration_id: u32,
    device_name: String,
}

#[derive(Serialize)]
struct LinkDeviceResponse {
    account_id: Uuid,
    nickname: String,
    device_id: Uuid,
    device_number: u32,
    registration_id: u32,
    access_token: String,
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
    device_number: u32,
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
    let (registration_id, device_number) = db
        .query_row(
            "SELECT registration_id, device_number FROM devices WHERE id = ?1",
            params![device.device_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load device"))?;
    Ok(Json(CurrentDeviceResponse {
        device_id: Uuid::parse_str(&device.device_id)
            .expect("authenticated device id is a valid UUID"),
        device_number,
        registration_id,
    }))
}

async fn delete_current_device(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let deleted = db
        .execute(
            "DELETE FROM devices WHERE id = ?1 AND account_id = ?2",
            params![account.device_id, account.account_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not delete device session",
            )
        })?;
    if deleted != 1 {
        return Err(Error(StatusCode::NOT_FOUND, "device session not found"));
    }
    Ok(StatusCode::NO_CONTENT)
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
        "SELECT accounts.id, accounts.nickname, devices.id, devices.device_number,
                devices.registration_id, devices.identity_public_key,
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
                device_number: row.get(3)?,
                registration_id: row.get(4)?,
                identity_public_key: row.get(5)?,
                signed_prekey: StoredPreKey {
                    id: row.get(6)?,
                    public_key: row.get(7)?,
                    signature: row.get(8)?,
                },
                kyber_signed_prekey: StoredPreKey {
                    id: row.get(9)?,
                    public_key: row.get(10)?,
                    signature: row.get(11)?,
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

/// Returns and reserves one prekey bundle for every active device of an account.
async fn take_prekey_bundles(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(nickname): axum::extract::Path<String>,
) -> Result<Json<Vec<PrekeyBundleResponse>>, Error> {
    authenticate(&state, &headers)?;
    let nickname = normalize_nickname(&nickname).ok_or(Error(StatusCode::BAD_REQUEST, "invalid nickname"))?;
    let db = state.db.lock().map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let transaction = db.unchecked_transaction().map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let device_ids = transaction.prepare(
        "SELECT devices.id FROM accounts JOIN devices ON devices.account_id = accounts.id
         JOIN prekey_bundles ON prekey_bundles.device_id = devices.id
         WHERE accounts.nickname = ?1 ORDER BY devices.device_number",
    ).map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not retrieve prekey bundles"))?
        .query_map(params![nickname], |row| row.get::<_, String>(0))
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not retrieve prekey bundles"))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not retrieve prekey bundles"))?;
    if device_ids.is_empty() { return Err(Error(StatusCode::NOT_FOUND, "user has no prekey bundle")); }
    let mut result = Vec::with_capacity(device_ids.len());
    for device_id in device_ids {
        let mut bundle = transaction.query_row(
            "SELECT accounts.id, accounts.nickname, devices.id, devices.device_number, devices.registration_id,
                    devices.identity_public_key, prekey_bundles.signed_prekey_id, prekey_bundles.signed_prekey,
                    prekey_bundles.signed_prekey_signature, prekey_bundles.kyber_signed_prekey_id,
                    prekey_bundles.kyber_signed_prekey, prekey_bundles.kyber_signed_prekey_signature
             FROM devices JOIN accounts ON accounts.id = devices.account_id
             JOIN prekey_bundles ON prekey_bundles.device_id = devices.id WHERE devices.id = ?1",
            params![device_id],
            |row| Ok(PrekeyBundleResponse { account_id: Uuid::parse_str(&row.get::<_, String>(0)?).expect("valid UUID"), nickname: row.get(1)?, device_id: Uuid::parse_str(&row.get::<_, String>(2)?).expect("valid UUID"), device_number: row.get(3)?, registration_id: row.get(4)?, identity_public_key: row.get(5)?, signed_prekey: StoredPreKey { id: row.get(6)?, public_key: row.get(7)?, signature: row.get(8)? }, kyber_signed_prekey: StoredPreKey { id: row.get(9)?, public_key: row.get(10)?, signature: row.get(11)? }, one_time_prekey: None, kyber_one_time_prekey: None }),
        ).map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not retrieve prekey bundle"))?;
        bundle.one_time_prekey = take_one_time_prekey(&transaction, &bundle.device_id.to_string(), "classical")?;
        bundle.kyber_one_time_prekey = take_one_time_prekey(&transaction, &bundle.device_id.to_string(), "kyber")?;
        result.push(bundle);
    }
    transaction.commit().map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not finalize prekey bundles"))?;
    Ok(Json(result))
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

async fn create_device_link_code(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<(StatusCode, Json<DeviceLinkCodeResponse>), Error> {
    let account = authenticate(&state, &headers)?;
    state.rate_limiter.check(
        format!("device-link:{}", account.account_id),
        6,
        Duration::from_secs(60),
    )?;
    let link_code = random_token(32);
    let expires_at = unix_time() + 10 * 60;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    transaction
        .execute(
            "DELETE FROM device_link_sessions
             WHERE account_id = ?1 AND (consumed_at IS NOT NULL OR expires_at < ?2)",
            params![account.account_id, unix_time()],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not clean device link sessions",
            )
        })?;
    transaction
        .execute(
            "INSERT INTO device_link_sessions
             (id, account_id, authorized_by_device_id, code_hash, expires_at)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                Uuid::new_v4().to_string(),
                account.account_id,
                account.device_id,
                hash(&link_code),
                expires_at,
            ],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not create device link code",
            )
        })?;
    transaction.commit().map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not finalize device link code",
        )
    })?;
    Ok((
        StatusCode::CREATED,
        Json(DeviceLinkCodeResponse {
            link_code,
            expires_at,
        }),
    ))
}

async fn link_device(
    State(state): State<AppState>,
    Json(request): Json<LinkDeviceRequest>,
) -> Result<(StatusCode, Json<LinkDeviceResponse>), Error> {
    state
        .rate_limiter
        .check("device-link-consume", 20, Duration::from_secs(60))?;
    validate_device_material(&request.identity_public_key, request.registration_id)?;
    if request.link_code.len() < 32 || request.link_code.len() > 128 {
        return Err(Error(StatusCode::UNAUTHORIZED, "invalid device link code"));
    }
    let device_name = normalize_device_name(&request.device_name, "Desktop");
    let access_token = random_token(48);
    let device_id = Uuid::new_v4();
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let (session_id, account_id, nickname): (String, String, String) = transaction
        .query_row(
            "SELECT device_link_sessions.id, accounts.id, accounts.nickname
             FROM device_link_sessions
             JOIN accounts ON accounts.id = device_link_sessions.account_id
             WHERE device_link_sessions.code_hash = ?1
               AND device_link_sessions.consumed_at IS NULL
               AND device_link_sessions.expires_at >= ?2",
            params![hash(&request.link_code), unix_time()],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .map_err(|error| match error {
            rusqlite::Error::QueryReturnedNoRows => Error(
                StatusCode::UNAUTHORIZED,
                "invalid or expired device link code",
            ),
            _ => Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not validate device link code",
            ),
        })?;
    let device_number: u32 = transaction
        .query_row(
            "SELECT COALESCE(MAX(device_number), 0) + 1 FROM devices WHERE account_id = ?1",
            params![account_id],
            |row| row.get(0),
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not allocate device number",
            )
        })?;
    if device_number > 127 {
        return Err(Error(StatusCode::CONFLICT, "device limit reached"));
    }
    transaction
        .execute(
            "INSERT INTO devices
             (id, account_id, identity_public_key, access_token_hash, registration_id,
              device_number, device_name)
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
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not link device"))?;
    let consumed = transaction
        .execute(
            "UPDATE device_link_sessions SET consumed_at = CURRENT_TIMESTAMP
             WHERE id = ?1 AND consumed_at IS NULL",
            params![session_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not consume device link code",
            )
        })?;
    if consumed != 1 {
        return Err(Error(StatusCode::CONFLICT, "device link code already used"));
    }
    transaction.commit().map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not finalize linked device",
        )
    })?;
    Ok((
        StatusCode::CREATED,
        Json(LinkDeviceResponse {
            account_id: Uuid::parse_str(&account_id).expect("database contains valid UUID"),
            nickname,
            device_id,
            device_number,
            registration_id: request.registration_id,
            access_token,
        }),
    ))
}

async fn list_devices(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<DeviceResponse>>, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let mut statement = db
        .prepare(
            "SELECT id, device_number, device_name, created_at
             FROM devices WHERE account_id = ?1 ORDER BY device_number",
        )
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not list devices"))?;
    let devices = statement
        .query_map(params![account.account_id], |row| {
            let id: String = row.get(0)?;
            Ok(DeviceResponse {
                current: id == account.device_id,
                device_id: Uuid::parse_str(&id).expect("database contains valid UUID"),
                device_number: row.get(1)?,
                device_name: row.get(2)?,
                created_at: row.get(3)?,
            })
        })
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not list devices"))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not list devices"))?;
    Ok(Json(devices))
}

fn unix_time() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock is after Unix epoch")
        .as_secs() as i64
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
