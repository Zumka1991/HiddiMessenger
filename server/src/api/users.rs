use axum::{
    Json, Router,
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    routing::{get, put},
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use rusqlite::params;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    auth::authenticate,
    error::Error,
    state::AppState,
    validation::{decode_avatar, normalize_nickname, validate_bio, validate_display_name},
};

pub(crate) fn routes(router: Router<AppState>) -> Router<AppState> {
    router
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
    let profile = db
        .query_row(
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
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load profile"))?;
    drop(db);
    notify_profile_peers(&state, &account.account_id)?;
    Ok(Json(profile))
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
    drop(db);
    notify_profile_peers(&state, &account.account_id)?;
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
    drop(db);
    notify_profile_peers(&state, &account.account_id)?;
    Ok(StatusCode::NO_CONTENT)
}

/// Profile events are sent only to accounts that have already exchanged a
/// personal message with the changed account. This avoids broadcasting profile
/// activity to unrelated users.
fn notify_profile_peers(state: &AppState, account_id: &str) -> Result<(), Error> {
    let peers = {
        let db = state
            .db
            .lock()
            .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
        let mut statement = db
            .prepare(
                "SELECT DISTINCT CASE
                    WHEN messages.sender_account_id = ?1 THEN messages.recipient_account_id
                    ELSE messages.sender_account_id
                 END AS peer_id
                 FROM messages
                 WHERE messages.sender_account_id = ?1 OR messages.recipient_account_id = ?1",
            )
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not load profile peers",
                )
            })?;
        statement
            .query_map(params![account_id], |row| row.get::<_, String>(0))
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not load profile peers",
                )
            })?
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not load profile peers",
                )
            })?
    };
    for peer in peers {
        state.realtime.publish(&peer, "profile");
    }
    Ok(())
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
