use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Serialize;

#[derive(Serialize)]
struct ApiError {
    error: &'static str,
}

/// An intentionally small, stable error response used by every HTTP handler.
pub(crate) struct Error(pub(crate) StatusCode, pub(crate) &'static str);

impl IntoResponse for Error {
    fn into_response(self) -> Response {
        (self.0, Json(ApiError { error: self.1 })).into_response()
    }
}
