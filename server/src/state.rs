use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use axum::http::StatusCode;
use rusqlite::Connection;
use tokio::sync::Notify;

use crate::{attachment_storage::AttachmentStorageBackend, error::Error};

#[derive(Clone)]
pub(crate) struct AppState {
    pub(crate) db: Arc<Mutex<Connection>>,
    pub(crate) bootstrap_secret: Arc<str>,
    pub(crate) message_notify: Arc<Notify>,
    pub(crate) attachment_backend: AttachmentStorageBackend,
    pub(crate) rate_limiter: Arc<RateLimiter>,
}

pub(crate) struct RateLimiter {
    entries: Mutex<HashMap<String, RateLimitWindow>>,
}

struct RateLimitWindow {
    started: Instant,
    count: u32,
}

impl RateLimiter {
    pub(crate) fn new() -> Self {
        Self {
            entries: Mutex::new(HashMap::new()),
        }
    }

    pub(crate) fn check(
        &self,
        key: impl Into<String>,
        limit: u32,
        window: Duration,
    ) -> Result<(), Error> {
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
