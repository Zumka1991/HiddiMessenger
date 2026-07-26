use axum::{
    Json, Router,
    extract::State,
    http::{HeaderMap, StatusCode},
    routing::{get, post},
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use rusqlite::params;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    api::groups::validate_group_id,
    auth::authenticate,
    error::Error,
    state::AppState,
    validation::{decode_attachment_ciphertext, normalize_nickname},
};

const ATTACHMENT_QUOTA_BYTES: usize = 1024 * 1024 * 1024;

pub(crate) fn routes(router: Router<AppState>) -> Router<AppState> {
    router
        .route("/v1/attachments", post(upload_attachment))
        .route(
            "/v1/attachments/{attachment_id}",
            get(download_attachment).delete(delete_attachment),
        )
        .route(
            "/v1/groups/{group_id}/attachments",
            post(upload_group_attachment),
        )
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
