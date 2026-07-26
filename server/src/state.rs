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
    pub(crate) version: u8,
    pub(crate) kind: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) nickname: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) typing: Option<bool>,
}

#[derive(Clone, Debug)]
pub(crate) struct PresenceEvent {
    pub(crate) account_id: String,
    pub(crate) nickname: String,
    pub(crate) online: bool,
}

#[derive(Clone)]
pub(crate) struct RealtimeHub {
    accounts: Arc<Mutex<HashMap<String, broadcast::Sender<RealtimeEvent>>>>,
    visible_connections: Arc<Mutex<HashMap<String, usize>>>,
    presence: broadcast::Sender<PresenceEvent>,
}

impl Default for RealtimeHub {
    fn default() -> Self {
        Self {
            accounts: Arc::new(Mutex::new(HashMap::new())),
            visible_connections: Arc::new(Mutex::new(HashMap::new())),
            presence: broadcast::channel(256).0,
        }
    }
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
        self.publish_event(
            account_id,
            RealtimeEvent {
                version: 1,
                kind,
                nickname: None,
                typing: None,
            },
        );
    }

    pub(crate) fn publish_typing(&self, account_id: &str, sender_nickname: &str, typing: bool) {
        self.publish_event(
            account_id,
            RealtimeEvent {
                version: 1,
                kind: "typing",
                nickname: Some(sender_nickname.to_owned()),
                typing: Some(typing),
            },
        );
    }

    fn publish_event(&self, account_id: &str, event: RealtimeEvent) {
        let sender = {
            let mut accounts = self.accounts.lock().expect("realtime hub mutex poisoned");
            accounts
                .entry(account_id.to_owned())
                .or_insert_with(|| broadcast::channel(128).0)
                .clone()
        };
        let _ = sender.send(event);
    }

    pub(crate) fn subscribe_presence(&self) -> broadcast::Receiver<PresenceEvent> {
        self.presence.subscribe()
    }

    pub(crate) fn is_online(&self, account_id: &str) -> bool {
        self.visible_connections
            .lock()
            .expect("realtime presence mutex poisoned")
            .get(account_id)
            .copied()
            .unwrap_or_default()
            > 0
    }

    pub(crate) fn connection_opened(&self, account_id: &str, nickname: &str) {
        let became_online = {
            let mut connections = self
                .visible_connections
                .lock()
                .expect("realtime presence mutex poisoned");
            let count = connections.entry(account_id.to_owned()).or_default();
            *count += 1;
            *count == 1
        };
        if became_online {
            let _ = self.presence.send(PresenceEvent {
                account_id: account_id.to_owned(),
                nickname: nickname.to_owned(),
                online: true,
            });
        }
    }

    pub(crate) fn connection_closed(&self, account_id: &str, nickname: &str) {
        let became_offline = {
            let mut connections = self
                .visible_connections
                .lock()
                .expect("realtime presence mutex poisoned");
            let Some(count) = connections.get_mut(account_id) else {
                return;
            };
            *count = count.saturating_sub(1);
            if *count == 0 {
                connections.remove(account_id);
                true
            } else {
                false
            }
        };
        if became_offline {
            let _ = self.presence.send(PresenceEvent {
                account_id: account_id.to_owned(),
                nickname: nickname.to_owned(),
                online: false,
            });
        }
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
