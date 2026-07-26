use std::time::Duration;

use axum::{
    Json, Router,
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    routing::{get, post},
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{auth::authenticate, error::Error, state::AppState, validation::normalize_nickname};

pub(crate) fn routes(router: Router<AppState>) -> Router<AppState> {
    router
        .route("/v1/messages", post(send_message).get(inbox))
        .route("/v1/messages/history", get(message_history))
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
            "/v1/conversations/deletions/{deletion_id}",
            post(ack_conversation_deletion),
        )
        .route(
            "/v1/conversations/{nickname}",
            axum::routing::delete(delete_conversation),
        )
}

#[derive(Deserialize)]
struct SendMessageRequest {
    recipient_nickname: String,
    #[serde(default)]
    ciphertext: String,
    #[serde(default)]
    device_ciphertexts: Vec<DeviceCiphertext>,
}

#[derive(Deserialize)]
struct DeviceCiphertext {
    device_number: u32,
    ciphertext: String,
}

#[derive(Serialize)]
struct MessageResponse {
    message_id: Uuid,
    sender_nickname: String,
    sender_device_number: u32,
    ciphertext: String,
    created_at: String,
}

#[derive(Deserialize, Default)]
struct HistoryQuery {
    limit: Option<usize>,
    before: Option<String>,
}

#[derive(Serialize)]
struct HistoryResponse {
    messages: Vec<MessageResponse>,
    next_cursor: Option<String>,
    has_more: bool,
}

#[derive(Serialize)]
struct MessageStatusResponse {
    delivered: bool,
    read: bool,
}

#[derive(Serialize)]
struct ConversationDeletionResponse {
    deletion_id: i64,
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
    let legacy_valid = request.ciphertext.len() >= 4
        && request.ciphertext.len() <= 2_800_000
        && URL_SAFE_NO_PAD.decode(&request.ciphertext).is_ok();
    let deliveries_valid = !request.device_ciphertexts.is_empty()
        && request.device_ciphertexts.iter().all(|entry| {
            entry.device_number > 0
                && entry.ciphertext.len() >= 4
                && entry.ciphertext.len() <= 2_800_000
                && URL_SAFE_NO_PAD.decode(&entry.ciphertext).is_ok()
        });
    if !legacy_valid && !deliveries_valid {
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
        "INSERT INTO messages
         (id, sender_account_id, sender_device_id, recipient_account_id, ciphertext)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![
            message_id.to_string(),
            sender.account_id,
            sender.device_id,
            recipient_id,
            if deliveries_valid {
                "multi-device-v1".to_string()
            } else {
                request.ciphertext.clone()
            }
        ],
    )
    .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not store message"))?;
    if deliveries_valid {
        for delivery in &request.device_ciphertexts {
            let device_id: String = db
                .query_row(
                    "SELECT id FROM devices WHERE account_id = ?1 AND device_number = ?2",
                    params![recipient_id, delivery.device_number],
                    |row| row.get(0),
                )
                .map_err(|_| Error(StatusCode::BAD_REQUEST, "recipient device not found"))?;
            db.execute(
                "INSERT INTO message_deliveries (message_id, recipient_device_id, ciphertext)
                 VALUES (?1, ?2, ?3)",
                params![message_id.to_string(), device_id, delivery.ciphertext],
            )
            .map_err(|_| {
                Error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not store device delivery",
                )
            })?;
        }
    }
    drop(db);
    state.message_notify.notify_waiters();
    state.realtime.publish(&recipient_id, "message");
    Ok((
        StatusCode::CREATED,
        Json(serde_json::json!({"message_id": message_id})),
    ))
}

/// Registers only routing metadata for a new MLS group. No title, MLS state,
/// epoch, public key, or message content is supplied to the server.
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
            "SELECT messages.id, accounts.nickname,
                    COALESCE((
                      SELECT device_number FROM devices
                      WHERE devices.id = messages.sender_device_id
                    ), 1),
                    COALESCE(message_deliveries.ciphertext, messages.ciphertext), messages.created_at
         FROM messages JOIN accounts ON accounts.id = messages.sender_account_id
         LEFT JOIN message_deliveries ON message_deliveries.message_id = messages.id
             AND message_deliveries.recipient_device_id = ?2
         WHERE (messages.recipient_account_id = ?1 AND messages.delivered_at IS NULL
                AND messages.ciphertext != 'multi-device-v1')
            OR (message_deliveries.recipient_device_id = ?2 AND message_deliveries.delivered_at IS NULL)
         ORDER BY messages.created_at ASC LIMIT 100",
        )
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load inbox"))?;
    let messages = statement
        .query_map(params![recipient.account_id, recipient.device_id], |row| {
            let id: String = row.get(0)?;
            Ok(MessageResponse {
                message_id: Uuid::parse_str(&id).expect("database contains valid UUIDs"),
                sender_nickname: row.get(1)?,
                sender_device_number: row.get(2)?,
                ciphertext: row.get(3)?,
                created_at: row.get(4)?,
            })
        })
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load inbox"))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "could not load inbox"))?;
    Ok(Json(messages))
}

/// Returns an opaque, device-scoped ciphertext history page. The server cannot
/// inspect message contents; clients decrypt only previously unseen envelopes.
async fn message_history(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HistoryQuery>,
) -> Result<Json<HistoryResponse>, Error> {
    let recipient = authenticate(&state, &headers)?;
    let limit = query.limit.unwrap_or(50).clamp(1, 100);
    let before = query
        .before
        .as_deref()
        .map(decode_history_cursor)
        .transpose()?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let sql = format!(
        "SELECT messages.id, accounts.nickname,
                COALESCE(sender_devices.device_number, 1),
                COALESCE(message_deliveries.ciphertext, messages.ciphertext),
                messages.created_at
         FROM messages
         JOIN accounts ON accounts.id = messages.sender_account_id
         LEFT JOIN devices AS sender_devices ON sender_devices.id = messages.sender_device_id
         LEFT JOIN message_deliveries ON message_deliveries.message_id = messages.id
             AND message_deliveries.recipient_device_id = ?2
         WHERE (message_deliveries.recipient_device_id = ?2
                OR (messages.recipient_account_id = ?1
                    AND messages.ciphertext != 'multi-device-v1'))
           {}
         ORDER BY messages.created_at DESC, messages.id DESC
         LIMIT ?{}",
        if before.is_some() {
            "AND (messages.created_at < ?3 OR (messages.created_at = ?3 AND messages.id < ?4))"
        } else {
            ""
        },
        if before.is_some() { 5 } else { 3 },
    );
    let mut statement = db.prepare(&sql).map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not load message history",
        )
    })?;
    let requested = (limit + 1) as i64;
    let map_row = |row: &rusqlite::Row<'_>| {
        let id: String = row.get(0)?;
        Ok(MessageResponse {
            message_id: Uuid::parse_str(&id).expect("database contains valid UUIDs"),
            sender_nickname: row.get(1)?,
            sender_device_number: row.get(2)?,
            ciphertext: row.get(3)?,
            created_at: row.get(4)?,
        })
    };
    let mut messages = if let Some((created_at, id)) = before {
        statement.query_map(
            params![
                recipient.account_id,
                recipient.device_id,
                created_at,
                id,
                requested
            ],
            map_row,
        )
    } else {
        statement.query_map(
            params![recipient.account_id, recipient.device_id, requested],
            map_row,
        )
    }
    .map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not load message history",
        )
    })?
    .collect::<rusqlite::Result<Vec<_>>>()
    .map_err(|_| {
        Error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not load message history",
        )
    })?;
    let has_more = messages.len() > limit;
    messages.truncate(limit);
    let next_cursor = messages
        .last()
        .map(|message| encode_history_cursor(&message.created_at, message.message_id));
    messages.reverse();
    Ok(Json(HistoryResponse {
        messages,
        next_cursor,
        has_more,
    }))
}

fn encode_history_cursor(created_at: &str, id: Uuid) -> String {
    URL_SAFE_NO_PAD.encode(format!("{created_at}\0{id}"))
}

fn decode_history_cursor(value: &str) -> Result<(String, String), Error> {
    let decoded = URL_SAFE_NO_PAD
        .decode(value)
        .ok()
        .and_then(|bytes| String::from_utf8(bytes).ok())
        .ok_or(Error(StatusCode::BAD_REQUEST, "invalid history cursor"))?;
    let (created_at, id) = decoded
        .split_once('\0')
        .ok_or(Error(StatusCode::BAD_REQUEST, "invalid history cursor"))?;
    let id = Uuid::parse_str(id)
        .map_err(|_| Error(StatusCode::BAD_REQUEST, "invalid history cursor"))?;
    Ok((created_at.to_owned(), id.to_string()))
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
            "UPDATE message_deliveries SET delivered_at = COALESCE(delivered_at, CURRENT_TIMESTAMP)
             WHERE message_id = ?1 AND recipient_device_id = ?2",
            params![message_id.to_string(), account.device_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not acknowledge message",
            )
        })?;
    if acknowledged == 0 {
        let legacy = db
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
        if legacy == 0 {
            return Err(Error(StatusCode::NOT_FOUND, "message not found"));
        }
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
    if query.for_everyone {
        state.realtime.publish(&recipient_id, "message_deletion");
    }
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
    drop(db);
    state.realtime.publish(&peer_id, "conversation_deletion");
    state.message_notify.notify_waiters();
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
                    deletion_id: row.get(0)?,
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
    Ok(Json(rows.into_iter().map(|(_, item)| item).collect()))
}

async fn ack_conversation_deletion(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Path(deletion_id): axum::extract::Path<i64>,
) -> Result<StatusCode, Error> {
    let account = authenticate(&state, &headers)?;
    let db = state
        .db
        .lock()
        .map_err(|_| Error(StatusCode::INTERNAL_SERVER_ERROR, "database unavailable"))?;
    let removed = db
        .execute(
            "DELETE FROM conversation_deletions
             WHERE id = ?1 AND recipient_account_id = ?2",
            params![deletion_id, account.account_id],
        )
        .map_err(|_| {
            Error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not acknowledge conversation deletion",
            )
        })?;
    if removed == 0 {
        return Err(Error(
            StatusCode::NOT_FOUND,
            "conversation deletion not found",
        ));
    }
    Ok(StatusCode::NO_CONTENT)
}
