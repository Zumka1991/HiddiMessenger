use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use axum::http::StatusCode;
use rusqlite::Connection;
use serde::Serialize;
use tokio::sync::{Notify, broadcast};

use crate::{attachment_storage::AttachmentStorageBackend, error::Error};

#[derive(Clone)]
pub(crate) struct AppState {
    pub(crate) db: Arc<Mutex<Connection>>,
    pub(crate) bootstrap_secret: Arc<str>,
    pub(crate) message_notify: Arc<Notify>,
    pub(crate) realtime: RealtimeHub,
    pub(crate) attachment_backend: AttachmentStorageBackend,
    pub(crate) rate_limiter: Arc<RateLimiter>,
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct RealtimeEvent {
    pub(crate) kind: &'static str,
}

#[derive(Clone, Default)]
pub(crate) struct RealtimeHub {
    accounts: Arc<Mutex<HashMap<String, broadcast::Sender<RealtimeEvent>>>>,
}

impl RealtimeHub {
    pub(crate) fn subscribe(&self, account_id: &str) -> broadcast::Receiver<RealtimeEvent> {
        let mut accounts = self.accounts.lock().expect("realtime hub mutex poisoned");
        accounts
            .entry(account_id.to_owned())
            .or_insert_with(|| broadcast::channel(128).0)
            .subscribe()
    }

    pub(crate) fn publish(&self, account_id: &str, kind: &'static str) {
        let sender = {
            let mut accounts = self.accounts.lock().expect("realtime hub mutex poisoned");
            accounts
                .entry(account_id.to_owned())
                .or_insert_with(|| broadcast::channel(128).0)
                .clone()
        };
        let _ = sender.send(RealtimeEvent { kind });
    }
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
