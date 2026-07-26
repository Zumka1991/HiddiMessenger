use std::time::Duration;

use axum::{
    Json, Router,
    extract::State,
    http::{HeaderMap, StatusCode},
    routing::{get, post, put},
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use rusqlite::{ErrorCode, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{auth::authenticate, error::Error, state::AppState, validation::normalize_nickname};

pub(crate) fn routes(router: Router<AppState>) -> Router<AppState> {
    router
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
            "/v1/groups/{group_id}/messages/{client_event_id}",
            axum::routing::delete(delete_group_message),
        )
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
struct UploadMlsKeyPackageResponse {
    available: i64,
}

#[derive(Serialize)]
struct MlsKeyPackageResponse {
    nickname: String,
    key_package: String,
}

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
) -> Result<(StatusCode, Json<UploadMlsKeyPackageResponse>), Error> {
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
    let transaction = db
        .unchecked_transaction()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    transaction
        .execute(
            "INSERT OR IGNORE INTO mls_key_packages (id, device_id, key_package)
             VALUES (?1, ?2, ?3)",
            params![
                Uuid::new_v4().to_string(),
                account.device_id,
                request.key_package
            ],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not store MLS key package",
            )
        })?;
    transaction
        .execute(
            "DELETE FROM mls_key_packages
             WHERE id IN (
                SELECT id FROM mls_key_packages
                WHERE device_id = ?1
                ORDER BY created_at ASC, id ASC
                LIMIT -1 OFFSET 32
             )",
            params![account.device_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not prune MLS key packages",
            )
        })?;
    let available: i64 = transaction
        .query_row(
            "SELECT COUNT(*) FROM mls_key_packages WHERE device_id = ?1",
            params![account.device_id],
            |row| row.get(0),
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not count MLS key packages",
            )
        })?;
    transaction.commit().map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not store MLS key package",
        )
    })?;
    Ok((
        StatusCode::CREATED,
        Json(UploadMlsKeyPackageResponse { available }),
    ))
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
    let (key_package_id, key_package): (String, String) = transaction.query_row(
        "SELECT mls_key_packages.id, mls_key_packages.key_package FROM accounts JOIN devices ON devices.account_id = accounts.id JOIN mls_key_packages ON mls_key_packages.device_id = devices.id WHERE accounts.nickname = ?1 ORDER BY mls_key_packages.created_at ASC, mls_key_packages.id ASC LIMIT 1", params![nickname], |row| Ok((row.get(0)?, row.get(1)?)))
        .map_err(|error| match error { rusqlite::Error::QueryReturnedNoRows => Error(StatusCode::NOT_FOUND, "MLS key package not found"), _ => Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load MLS key package") })?;
    transaction
        .execute(
            "DELETE FROM mls_key_packages WHERE id = ?1",
            params![key_package_id],
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

pub(crate) fn validate_group_id(value: &str) -> Result<(), Error> {
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
