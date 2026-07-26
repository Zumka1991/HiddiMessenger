use axum::http::{HeaderMap, StatusCode, header};
use rusqlite::params;

use crate::{error::Error, state::AppState};

pub(crate) struct AuthenticatedAccount {
    pub(crate) account_id: String,
    pub(crate) device_id: String,
}

pub(crate) fn authorized(headers: &HeaderMap, secret: &str) -> bool {
    let expected = format!("Bearer {secret}");
    headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        == Some(expected.as_str())
}

pub(crate) fn authenticate(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<AuthenticatedAccount, Error> {
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
        params![crate::crypto::hash(token)],
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
