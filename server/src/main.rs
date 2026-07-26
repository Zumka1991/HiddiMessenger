mod api;
mod attachment_storage;
mod auth;
mod config;
mod crypto;
mod db;
mod error;
mod state;
mod validation;

use std::{
    env,
    net::SocketAddr,
    sync::{Arc, Mutex},
};

use anyhow::Context;
use attachment_storage::AttachmentStorageBackend;
use axum::{Router, extract::DefaultBodyLimit};
use config::ServerConfig;
use db::migrate;
use rusqlite::Connection;
use state::{AppState, RateLimiter, RealtimeHub};
use tokio::sync::Notify;
use tower_http::trace::TraceLayer;
use tracing::info;
use validation::MAX_JSON_BODY_BYTES;
#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(ServerConfig::log_filter())
        .init();

    let config = ServerConfig::from_environment()?;
    if let Some(parent) = config.database_path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let db = Connection::open(&config.database_path)?;
    migrate(&db)?;
    let attachment_backend = AttachmentStorageBackend::from_environment()?;
    attachment_backend.ensure_ready()?;

    let state = AppState {
        db: Arc::new(Mutex::new(db)),
        bootstrap_secret: config.bootstrap_secret.into(),
        message_notify: Arc::new(Notify::new()),
        realtime: RealtimeHub::default(),
        attachment_backend,
        rate_limiter: Arc::new(RateLimiter::new()),
    };
    let app = build_app(state);

    let address: SocketAddr = env::var("HIDDI_BIND_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:3000".into())
        .parse()
        .context("HIDDI_BIND_ADDR must be a socket address")?;
    let listener = tokio::net::TcpListener::bind(address).await?;
    info!(%address, attachment_backend = attachment_backend.name(), "Hiddi server is listening");
    axum::serve(listener, app).await?;
    Ok(())
}

fn build_app(state: AppState) -> Router {
    api::registration::routes(api::users::routes(api::devices::routes(
        api::messages::routes(api::groups::routes(api::attachments::routes(
            api::realtime::routes(Router::new()),
        ))),
    )))
    .layer(DefaultBodyLimit::max(MAX_JSON_BODY_BYTES))
    .layer(TraceLayer::new_for_http())
    .with_state(state)
}

#[cfg(test)]
mod tests {
    use super::{AppState, RateLimiter, RealtimeHub, build_app};
    use crate::{
        attachment_storage::AttachmentStorageBackend,
        crypto::hash,
        db::migrate,
        validation::{MAX_ATTACHMENT_BYTES, decode_attachment_ciphertext, normalize_nickname},
    };
    use axum::{
        body::{Body, to_bytes},
        http::{Request, StatusCode},
    };
    use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
    use ed25519_dalek::{Signer, SigningKey};
    use rusqlite::Connection;
    use std::{
        sync::{Arc, Mutex},
        time::Duration,
    };
    use tokio::sync::Notify;
    use tower::ServiceExt;

    #[test]
    fn normalizes_valid_nickname() {
        assert_eq!(normalize_nickname(" @Alice_42 "), Some("alice_42".into()));
    }

    #[test]
    fn rejects_invalid_nickname() {
        assert_eq!(normalize_nickname("a-b"), None);
    }

    #[test]
    fn validates_encrypted_attachment_limits() {
        assert!(decode_attachment_ciphertext(&URL_SAFE_NO_PAD.encode([7_u8; 32])).is_ok());
        assert!(decode_attachment_ciphertext("not base64!").is_err());
        let oversized = vec![0_u8; MAX_ATTACHMENT_BYTES + 1];
        assert!(decode_attachment_ciphertext(&URL_SAFE_NO_PAD.encode(oversized)).is_err());
    }

    #[test]
    fn rate_limiter_rejects_excess_and_keeps_keys_independent() {
        let limiter = RateLimiter::new();
        assert!(limiter.check("alice", 2, Duration::from_secs(60)).is_ok());
        assert!(limiter.check("alice", 2, Duration::from_secs(60)).is_ok());
        assert!(limiter.check("alice", 2, Duration::from_secs(60)).is_err());
        assert!(limiter.check("bob", 2, Duration::from_secs(60)).is_ok());
    }

    fn test_state() -> AppState {
        let db = Connection::open_in_memory().unwrap();
        migrate(&db).unwrap();
        AppState {
            db: Arc::new(Mutex::new(db)),
            bootstrap_secret: "test-bootstrap-secret-with-enough-length".into(),
            message_notify: Arc::new(Notify::new()),
            realtime: RealtimeHub::default(),
            attachment_backend: AttachmentStorageBackend::Sqlite,
            rate_limiter: Arc::new(RateLimiter::new()),
        }
    }

    fn test_app() -> axum::Router {
        build_app(test_state())
    }

    async fn request(
        app: &axum::Router,
        method: &str,
        path: &str,
        token: Option<&str>,
        body: String,
    ) -> (StatusCode, String) {
        let mut builder = Request::builder().method(method).uri(path);
        if let Some(token) = token {
            builder = builder.header("authorization", format!("Bearer {token}"));
        }
        let response = app
            .clone()
            .oneshot(
                builder
                    .header("content-type", "application/json")
                    .body(Body::from(body))
                    .unwrap(),
            )
            .await
            .unwrap();
        let status = response.status();
        let bytes = to_bytes(response.into_body(), 1_000_000).await.unwrap();
        (status, String::from_utf8(bytes.to_vec()).unwrap())
    }

    async fn register_account(app: &axum::Router, nickname: &str) -> String {
        let admin = "test-bootstrap-secret-with-enough-length";
        let (_, invite) =
            request(app, "POST", "/v1/admin/invites", Some(admin), String::new()).await;
        let invite = serde_json::from_str::<serde_json::Value>(&invite).unwrap()["invite_code"]
            .as_str()
            .unwrap()
            .to_owned();
        let (status, registered) = request(
            app,
            "POST",
            "/v1/auth/register",
            None,
            serde_json::json!({"nickname": nickname, "invite_code": invite, "identity_public_key": URL_SAFE_NO_PAD.encode([5_u8; 33]), "registration_id": 42}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        serde_json::from_str::<serde_json::Value>(&registered).unwrap()["access_token"]
            .as_str()
            .unwrap()
            .to_owned()
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn two_hundred_authenticated_waiters_can_wake_concurrently() {
        let state = test_state();
        let tokens = {
            let mut db = state.db.lock().unwrap();
            let transaction = db.transaction().unwrap();
            let mut tokens = Vec::with_capacity(200);
            for index in 0..200 {
                let account_id = uuid::Uuid::new_v4().to_string();
                let device_id = uuid::Uuid::new_v4().to_string();
                let token = format!("capacity-token-{index}");
                transaction
                    .execute(
                        "INSERT INTO accounts (id, nickname) VALUES (?1, ?2)",
                        rusqlite::params![account_id, format!("capacity_{index:03}")],
                    )
                    .unwrap();
                transaction
                    .execute(
                        "INSERT INTO devices
                         (id, account_id, identity_public_key, access_token_hash, registration_id)
                         VALUES (?1, ?2, ?3, ?4, ?5)",
                        rusqlite::params![
                            device_id,
                            account_id,
                            URL_SAFE_NO_PAD.encode([5_u8; 33]),
                            hash(&token),
                            index + 1,
                        ],
                    )
                    .unwrap();
                tokens.push(token);
            }
            transaction.commit().unwrap();
            tokens
        };
        let app = build_app(state.clone());
        let mut waiters = tokio::task::JoinSet::new();
        for token in tokens {
            let app = app.clone();
            waiters.spawn(async move {
                request(
                    &app,
                    "GET",
                    "/v1/messages/wait",
                    Some(&token),
                    String::new(),
                )
                .await
            });
        }

        for _ in 0..20 {
            tokio::time::sleep(Duration::from_millis(25)).await;
            state.message_notify.notify_waiters();
        }
        let completed = tokio::time::timeout(Duration::from_secs(3), async {
            let mut count = 0;
            while let Some(result) = waiters.join_next().await {
                let (status, response) = result.unwrap();
                assert_eq!(status, StatusCode::OK);
                assert_eq!(
                    serde_json::from_str::<serde_json::Value>(&response).unwrap()["available"],
                    false
                );
                count += 1;
            }
            count
        })
        .await
        .expect("200 concurrent waiters should finish promptly");
        assert_eq!(completed, 200);
    }

    #[tokio::test]
    async fn registration_and_message_delivery_work_over_http() {
        let app = test_app();
        let admin = "test-bootstrap-secret-with-enough-length";
        let (_, invite_alice) = request(
            &app,
            "POST",
            "/v1/admin/invites",
            Some(admin),
            String::new(),
        )
        .await;
        let invite_alice =
            serde_json::from_str::<serde_json::Value>(&invite_alice).unwrap()["invite_code"]
                .as_str()
                .unwrap()
                .to_owned();
        let (_, invite_bob) = request(
            &app,
            "POST",
            "/v1/admin/invites",
            Some(admin),
            String::new(),
        )
        .await;
        let invite_bob =
            serde_json::from_str::<serde_json::Value>(&invite_bob).unwrap()["invite_code"]
                .as_str()
                .unwrap()
                .to_owned();
        let registration = |nickname: &str, invite: String| {
            serde_json::json!({"nickname": nickname, "invite_code": invite, "identity_public_key": URL_SAFE_NO_PAD.encode([5_u8; 33]), "registration_id": 42}).to_string()
        };
        let (alice_status, alice) = request(
            &app,
            "POST",
            "/v1/auth/register",
            None,
            registration("alice", invite_alice.clone()),
        )
        .await;
        assert_eq!(alice_status, StatusCode::CREATED);
        let alice_token =
            serde_json::from_str::<serde_json::Value>(&alice).unwrap()["access_token"]
                .as_str()
                .unwrap()
                .to_owned();
        let (bob_status, bob) = request(
            &app,
            "POST",
            "/v1/auth/register",
            None,
            registration("bob", invite_bob),
        )
        .await;
        assert_eq!(bob_status, StatusCode::CREATED);
        let bob_token = serde_json::from_str::<serde_json::Value>(&bob).unwrap()["access_token"]
            .as_str()
            .unwrap()
            .to_owned();
        let (reused_status, _) = request(
            &app,
            "POST",
            "/v1/auth/register",
            None,
            registration("eve", invite_alice),
        )
        .await;
        assert_eq!(reused_status, StatusCode::UNAUTHORIZED);
        let (send_status, _) = request(
            &app,
            "POST",
            "/v1/messages",
            Some(&alice_token),
            serde_json::json!({"recipient_nickname":"bob","ciphertext":"aGVsbG8"}).to_string(),
        )
        .await;
        assert_eq!(send_status, StatusCode::CREATED);
        let (inbox_status, inbox) =
            request(&app, "GET", "/v1/messages", Some(&bob_token), String::new()).await;
        assert_eq!(inbox_status, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox).unwrap()[0]["sender_nickname"],
            "alice"
        );
    }

    #[tokio::test]
    async fn deleting_current_device_revokes_its_session() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;

        let (status, _) = request(
            &app,
            "DELETE",
            "/v1/devices/current",
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);

        let (status, _) = request(
            &app,
            "GET",
            "/v1/devices/current",
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn authenticated_device_can_link_one_new_device_with_single_use_code() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;

        let (status, link) = request(
            &app,
            "POST",
            "/v1/devices/link-code",
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let link_code = serde_json::from_str::<serde_json::Value>(&link).unwrap()["link_code"]
            .as_str()
            .unwrap()
            .to_owned();

        let body = serde_json::json!({
            "link_code": link_code,
            "identity_public_key": URL_SAFE_NO_PAD.encode([7_u8; 33]),
            "registration_id": 77,
            "device_name": "Linux desktop",
        })
        .to_string();
        let (status, linked) = request(&app, "POST", "/v1/devices/link", None, body.clone()).await;
        assert_eq!(status, StatusCode::CREATED);
        let linked = serde_json::from_str::<serde_json::Value>(&linked).unwrap();
        assert_eq!(linked["nickname"], "alice");
        assert_eq!(linked["device_number"], 2);
        let desktop_token = linked["access_token"].as_str().unwrap();

        let (status, devices) = request(
            &app,
            "GET",
            "/v1/devices",
            Some(desktop_token),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let devices = serde_json::from_str::<serde_json::Value>(&devices).unwrap();
        assert_eq!(devices.as_array().unwrap().len(), 2);
        assert_eq!(devices[1]["device_name"], "Linux desktop");
        assert_eq!(devices[1]["current"], true);

        let (status, _) = request(&app, "POST", "/v1/devices/link", None, body).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn blocked_sender_is_silently_dropped_until_unblocked() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let (status, _) = request(&app, "PUT", "/v1/blocks/alice", Some(&bob), String::new()).await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, blocks) = request(&app, "GET", "/v1/blocks", Some(&bob), String::new()).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&blocks).unwrap()[0]["nickname"],
            "alice"
        );
        let (status, _) = request(
            &app,
            "POST",
            "/v1/messages",
            Some(&alice),
            serde_json::json!({
                "recipient_nickname": "bob",
                "ciphertext": "aGVsbG8",
            })
            .to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let (_, inbox) = request(&app, "GET", "/v1/messages", Some(&bob), String::new()).await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            0
        );
        let (status, _) = request(
            &app,
            "DELETE",
            "/v1/blocks/alice",
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, _) = request(
            &app,
            "POST",
            "/v1/messages",
            Some(&alice),
            serde_json::json!({
                "recipient_nickname": "bob",
                "ciphertext": "aGVsbG8",
            })
            .to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let (_, inbox) = request(&app, "GET", "/v1/messages", Some(&bob), String::new()).await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            1
        );
    }

    #[tokio::test]
    async fn profile_metadata_and_avatar_are_available_to_authenticated_users() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let (status, profile) = request(
            &app,
            "PUT",
            "/v1/profile",
            Some(&alice),
            serde_json::json!({
                "display_name": "Alice Example",
                "bio": "Private messages, public profile."
            })
            .to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let profile: serde_json::Value = serde_json::from_str(&profile).unwrap();
        assert_eq!(profile["nickname"], "alice");
        assert_eq!(profile["display_name"], "Alice Example");

        let avatar = URL_SAFE_NO_PAD.encode([0xff, 0xd8, 0xff, 0x00, 0xff, 0xd9]);
        let (status, uploaded) = request(
            &app,
            "PUT",
            "/v1/profile/avatar",
            Some(&alice),
            serde_json::json!({"image": avatar}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let version = serde_json::from_str::<serde_json::Value>(&uploaded).unwrap()["version"]
            .as_str()
            .unwrap()
            .to_owned();

        let (status, search) = request(
            &app,
            "GET",
            "/v1/users?query=ali",
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let search: serde_json::Value = serde_json::from_str(&search).unwrap();
        assert_eq!(search[0]["display_name"], "Alice Example");
        assert_eq!(search[0]["avatar_version"], version);

        let (status, downloaded) = request(
            &app,
            "GET",
            "/v1/users/alice/avatar",
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&downloaded).unwrap()["image"],
            avatar
        );
    }

    #[tokio::test]
    async fn mls_key_packages_are_buffered_and_consumed_once() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let packages = (1_u8..=3)
            .map(|value| URL_SAFE_NO_PAD.encode([value; 32]))
            .collect::<Vec<_>>();

        for (index, key_package) in packages.iter().enumerate() {
            let (status, response) = request(
                &app,
                "PUT",
                "/v1/groups/key-package",
                Some(&bob),
                serde_json::json!({"key_package": key_package}).to_string(),
            )
            .await;
            assert_eq!(status, StatusCode::CREATED);
            assert_eq!(
                serde_json::from_str::<serde_json::Value>(&response).unwrap()["available"],
                index + 1
            );
        }

        let mut consumed = Vec::new();
        for _ in 0..3 {
            let (status, response) = request(
                &app,
                "GET",
                "/v1/users/bob/mls-key-package",
                Some(&alice),
                String::new(),
            )
            .await;
            assert_eq!(status, StatusCode::OK);
            consumed.push(
                serde_json::from_str::<serde_json::Value>(&response).unwrap()["key_package"]
                    .as_str()
                    .unwrap()
                    .to_owned(),
            );
        }
        consumed.sort();
        let mut expected = packages;
        expected.sort();
        assert_eq!(consumed, expected);

        let (status, _) = request(
            &app,
            "GET",
            "/v1/users/bob/mls-key-package",
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn group_transport_accepts_only_member_routed_opaque_mls_events() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let charlie = register_account(&app, "charlie").await;
        let group_id = URL_SAFE_NO_PAD.encode([9_u8; 16]);
        let create_request =
            serde_json::json!({"group_id": group_id, "members": [{"nickname":"bob", "role":"member"}]}).to_string();
        let (status, first_group) = request(
            &app,
            "POST",
            "/v1/groups",
            Some(&alice),
            create_request.clone(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let (status, repeated_group) =
            request(&app, "POST", "/v1/groups", Some(&alice), create_request).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(first_group, repeated_group);
        let envelope = URL_SAFE_NO_PAD.encode([1_u8, 1, 42]);
        let client_event_id = URL_SAFE_NO_PAD.encode([7_u8; 32]);
        let event_request = serde_json::json!({
            "client_event_id": client_event_id,
            "kind": 1,
            "recipient_nicknames": ["bob"],
            "envelope": envelope,
        })
        .to_string();
        let (status, first_response) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/events").as_str(),
            Some(&alice),
            event_request.clone(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let (status, repeated_response) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/events").as_str(),
            Some(&alice),
            event_request,
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        assert_eq!(first_response, repeated_response);
        let (status, inbox) =
            request(&app, "GET", "/v1/groups/events", Some(&bob), String::new()).await;
        assert_eq!(status, StatusCode::OK);
        let inbox = serde_json::from_str::<serde_json::Value>(&inbox).unwrap();
        assert_eq!(inbox.as_array().unwrap().len(), 1);
        assert_eq!(inbox[0]["group_id"], group_id);
        assert_eq!(inbox[0]["kind"], 1);
        let event_id = inbox[0]["event_id"].as_str().unwrap();
        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/events/{event_id}").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (_, inbox) = request(&app, "GET", "/v1/groups/events", Some(&bob), String::new()).await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            0
        );
        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/events").as_str(),
            Some(&bob),
            serde_json::json!({
                "client_event_id": URL_SAFE_NO_PAD.encode([8_u8; 32]),
                "kind": 2,
                "recipient_nicknames": ["alice"],
                "envelope": URL_SAFE_NO_PAD.encode([1_u8, 2, 42]),
            })
            .to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN);

        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/members").as_str(),
            Some(&bob),
            serde_json::json!({"nickname":"charlie","role":"member"}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN);
        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/members").as_str(),
            Some(&alice),
            serde_json::json!({"nickname":"charlie","role":"member"}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, details) = request(
            &app,
            "GET",
            format!("/v1/groups/{group_id}").as_str(),
            Some(&charlie),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let details = serde_json::from_str::<serde_json::Value>(&details).unwrap();
        assert_eq!(details["owner_nickname"], "alice");
        assert_eq!(details["members"].as_array().unwrap().len(), 3);
        let group_message_id = URL_SAFE_NO_PAD.encode([11_u8; 16]);
        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/events").as_str(),
            Some(&alice),
            serde_json::json!({
                "client_event_id": group_message_id,
                "kind": 3,
                "recipient_nicknames": ["bob", "charlie"],
                "envelope": URL_SAFE_NO_PAD.encode([1_u8, 3, 42]),
            })
            .to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let (status, _) = request(
            &app,
            "DELETE",
            format!("/v1/groups/{group_id}/messages/{group_message_id}").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (_, inbox) = request(&app, "GET", "/v1/groups/events", Some(&bob), String::new()).await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            1
        );
        let (status, _) = request(
            &app,
            "DELETE",
            format!("/v1/groups/{group_id}/messages/{group_message_id}").as_str(),
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (_, inbox) = request(&app, "GET", "/v1/groups/events", Some(&bob), String::new()).await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            0
        );
        let (status, _) = request(
            &app,
            "PUT",
            format!("/v1/groups/{group_id}/members/charlie/role").as_str(),
            Some(&bob),
            serde_json::json!({"role":"admin"}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN);
        let (status, _) = request(
            &app,
            "PUT",
            format!("/v1/groups/{group_id}/members/charlie/role").as_str(),
            Some(&alice),
            serde_json::json!({"role":"admin"}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let remove_bob_id = URL_SAFE_NO_PAD.encode([12_u8; 32]);
        let remove_bob = serde_json::json!({
            "client_event_id": remove_bob_id,
            "kind": 2,
            "recipient_nicknames": ["bob", "charlie"],
            "envelope": URL_SAFE_NO_PAD.encode([1_u8, 2, 99]),
            "remove_member_nickname": "bob",
        })
        .to_string();
        let (status, first_removal) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/events").as_str(),
            Some(&alice),
            remove_bob.clone(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let (status, repeated_removal) = request(
            &app,
            "POST",
            format!("/v1/groups/{group_id}/events").as_str(),
            Some(&alice),
            remove_bob,
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        assert_eq!(first_removal, repeated_removal);
        let (status, inbox) =
            request(&app, "GET", "/v1/groups/events", Some(&bob), String::new()).await;
        assert_eq!(status, StatusCode::OK);
        let inbox = serde_json::from_str::<serde_json::Value>(&inbox).unwrap();
        assert_eq!(inbox.as_array().unwrap().len(), 1);
        assert_eq!(inbox[0]["removes_recipient"], true);
        let removal_event_id = inbox[0]["event_id"].as_str().unwrap();
        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/events/{removal_event_id}").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, _) = request(
            &app,
            "GET",
            format!("/v1/groups/{group_id}").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        let (status, _) = request(
            &app,
            "DELETE",
            format!("/v1/groups/{group_id}").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN);
        let (status, _) = request(
            &app,
            "DELETE",
            format!("/v1/groups/{group_id}").as_str(),
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (_, deletions) = request(
            &app,
            "GET",
            "/v1/groups/deletions",
            Some(&charlie),
            String::new(),
        )
        .await;
        let deletions = serde_json::from_str::<serde_json::Value>(&deletions).unwrap();
        assert_eq!(deletions[0]["group_id"], group_id);
        let deletion_id = deletions[0]["deletion_id"].as_str().unwrap();
        let (status, _) = request(
            &app,
            "POST",
            format!("/v1/groups/deletions/{deletion_id}").as_str(),
            Some(&charlie),
            String::new(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
    }

    #[tokio::test]
    async fn conversation_deletion_erases_server_data_and_notifies_peer() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let (sent, _) = request(
            &app,
            "POST",
            "/v1/messages",
            Some(&alice),
            serde_json::json!({"recipient_nickname":"bob","ciphertext":"aGVsbG8"}).to_string(),
        )
        .await;
        assert_eq!(sent, StatusCode::CREATED);
        let (deleted, _) = request(
            &app,
            "DELETE",
            "/v1/conversations/bob",
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(deleted, StatusCode::NO_CONTENT);
        let (notice_status, notices) = request(
            &app,
            "GET",
            "/v1/conversations/deletions",
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(notice_status, StatusCode::OK);
        let notices = serde_json::from_str::<serde_json::Value>(&notices).unwrap();
        assert_eq!(notices[0]["peer_nickname"], "alice");
        let deletion_id = notices[0]["deletion_id"].as_i64().unwrap();
        let (_, repeated) = request(
            &app,
            "GET",
            "/v1/conversations/deletions",
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&repeated)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            1
        );
        let (acknowledged, _) = request(
            &app,
            "POST",
            &format!("/v1/conversations/deletions/{deletion_id}"),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(acknowledged, StatusCode::NO_CONTENT);
        let (inbox_status, inbox) =
            request(&app, "GET", "/v1/messages", Some(&bob), String::new()).await;
        assert_eq!(inbox_status, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            0
        );
    }

    #[tokio::test]
    async fn sender_can_reliably_delete_one_message_for_everyone() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let (sent, body) = request(
            &app,
            "POST",
            "/v1/messages",
            Some(&alice),
            serde_json::json!({"recipient_nickname":"bob","ciphertext":"aGVsbG8"}).to_string(),
        )
        .await;
        assert_eq!(sent, StatusCode::CREATED);
        let message_id = serde_json::from_str::<serde_json::Value>(&body).unwrap()["message_id"]
            .as_str()
            .unwrap()
            .to_owned();
        let (deleted, _) = request(
            &app,
            "DELETE",
            format!("/v1/messages/{message_id}?for_everyone=true").as_str(),
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(deleted, StatusCode::NO_CONTENT);
        let (_, inbox) = request(&app, "GET", "/v1/messages", Some(&bob), String::new()).await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&inbox)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            0
        );
        let (_, deletions) = request(
            &app,
            "GET",
            "/v1/messages/deletions",
            Some(&bob),
            String::new(),
        )
        .await;
        let deletions = serde_json::from_str::<serde_json::Value>(&deletions).unwrap();
        assert_eq!(deletions[0]["message_id"], message_id);
        let deletion_id = deletions[0]["deletion_id"].as_str().unwrap();
        let (acknowledged, _) = request(
            &app,
            "POST",
            format!("/v1/messages/deletions/{deletion_id}").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(acknowledged, StatusCode::NO_CONTENT);
        let (_, empty) = request(
            &app,
            "GET",
            "/v1/messages/deletions",
            Some(&bob),
            String::new(),
        )
        .await;
        assert!(
            serde_json::from_str::<serde_json::Value>(&empty)
                .unwrap()
                .as_array()
                .unwrap()
                .is_empty()
        );

        let (_, second) = request(
            &app,
            "POST",
            "/v1/messages",
            Some(&alice),
            serde_json::json!({"recipient_nickname":"bob","ciphertext":"d29ybGQ"}).to_string(),
        )
        .await;
        let second_id = serde_json::from_str::<serde_json::Value>(&second).unwrap()["message_id"]
            .as_str()
            .unwrap()
            .to_owned();
        let (forbidden, _) = request(
            &app,
            "DELETE",
            format!("/v1/messages/{second_id}?for_everyone=true").as_str(),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(forbidden, StatusCode::FORBIDDEN);
    }

    #[tokio::test]
    async fn attachment_is_available_only_to_participants_and_can_be_deleted() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let charlie = register_account(&app, "charlie").await;
        let ciphertext = URL_SAFE_NO_PAD.encode([9_u8; 64]);
        let (uploaded, body) = request(
            &app,
            "POST",
            "/v1/attachments",
            Some(&alice),
            serde_json::json!({"recipient_nickname":"bob","ciphertext":ciphertext}).to_string(),
        )
        .await;
        assert_eq!(uploaded, StatusCode::CREATED);
        let id = serde_json::from_str::<serde_json::Value>(&body).unwrap()["attachment_id"]
            .as_str()
            .unwrap()
            .to_owned();
        let (downloaded, downloaded_body) = request(
            &app,
            "GET",
            &format!("/v1/attachments/{id}"),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(downloaded, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&downloaded_body).unwrap()["ciphertext"],
            ciphertext
        );
        let (forbidden, _) = request(
            &app,
            "GET",
            &format!("/v1/attachments/{id}"),
            Some(&charlie),
            String::new(),
        )
        .await;
        assert_eq!(forbidden, StatusCode::NOT_FOUND);
        let (removed, _) = request(
            &app,
            "DELETE",
            &format!("/v1/attachments/{id}"),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(removed, StatusCode::NO_CONTENT);
        let (missing, _) = request(
            &app,
            "GET",
            &format!("/v1/attachments/{id}"),
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(missing, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn group_attachment_is_shared_once_and_only_with_current_members() {
        let app = test_app();
        let alice = register_account(&app, "alice").await;
        let bob = register_account(&app, "bob").await;
        let charlie = register_account(&app, "charlie").await;
        let group_id = URL_SAFE_NO_PAD.encode([31_u8; 16]);
        let (created, _) = request(
            &app,
            "POST",
            "/v1/groups",
            Some(&alice),
            serde_json::json!({
                "group_id": group_id,
                "members": [{"nickname": "bob", "role": "member"}],
            })
            .to_string(),
        )
        .await;
        assert_eq!(created, StatusCode::CREATED);
        let ciphertext = URL_SAFE_NO_PAD.encode([17_u8; 96]);
        let (uploaded, body) = request(
            &app,
            "POST",
            &format!("/v1/groups/{group_id}/attachments"),
            Some(&alice),
            serde_json::json!({"ciphertext": ciphertext}).to_string(),
        )
        .await;
        assert_eq!(uploaded, StatusCode::CREATED);
        let id = serde_json::from_str::<serde_json::Value>(&body).unwrap()["attachment_id"]
            .as_str()
            .unwrap()
            .to_owned();
        let (downloaded, body) = request(
            &app,
            "GET",
            &format!("/v1/attachments/{id}"),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(downloaded, StatusCode::OK);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&body).unwrap()["ciphertext"],
            ciphertext
        );
        let (outsider, _) = request(
            &app,
            "GET",
            &format!("/v1/attachments/{id}"),
            Some(&charlie),
            String::new(),
        )
        .await;
        assert_eq!(outsider, StatusCode::NOT_FOUND);
        let (forbidden_delete, _) = request(
            &app,
            "DELETE",
            &format!("/v1/attachments/{id}"),
            Some(&bob),
            String::new(),
        )
        .await;
        assert_eq!(forbidden_delete, StatusCode::NOT_FOUND);
        let (deleted, _) = request(
            &app,
            "DELETE",
            &format!("/v1/attachments/{id}"),
            Some(&alice),
            String::new(),
        )
        .await;
        assert_eq!(deleted, StatusCode::NO_CONTENT);
    }

    #[tokio::test]
    async fn recovery_key_adds_a_new_device_and_challenge_is_single_use() {
        let app = test_app();
        let (_, invite) = request(
            &app,
            "POST",
            "/v1/admin/invites",
            Some("test-bootstrap-secret-with-enough-length"),
            String::new(),
        )
        .await;
        let invite = serde_json::from_str::<serde_json::Value>(&invite).unwrap()["invite_code"]
            .as_str()
            .unwrap()
            .to_owned();
        let signing_key = SigningKey::from_bytes(&[73_u8; 32]);
        let (status, registered) = request(
            &app,
            "POST",
            "/v1/auth/register",
            None,
            serde_json::json!({
                "nickname": "recoverable",
                "invite_code": invite,
                "identity_public_key": URL_SAFE_NO_PAD.encode([5_u8; 33]),
                "registration_id": 42,
                "recovery_public_key": URL_SAFE_NO_PAD.encode(signing_key.verifying_key().to_bytes()),
            })
            .to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let registered = serde_json::from_str::<serde_json::Value>(&registered).unwrap();

        let (status, challenge) = request(
            &app,
            "POST",
            "/v1/auth/recovery/challenge",
            None,
            serde_json::json!({"nickname": "recoverable"}).to_string(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let challenge = serde_json::from_str::<serde_json::Value>(&challenge).unwrap();
        let challenge_id = challenge["challenge_id"].as_str().unwrap();
        let challenge_value = challenge["challenge"].as_str().unwrap();
        let proof = format!("hiddi-recovery-v1\0{challenge_id}\0{challenge_value}");
        let recovery_body = serde_json::json!({
            "nickname": "recoverable",
            "challenge_id": challenge_id,
            "signature": URL_SAFE_NO_PAD.encode(signing_key.sign(proof.as_bytes()).to_bytes()),
            "identity_public_key": URL_SAFE_NO_PAD.encode([7_u8; 33]),
            "registration_id": 77,
            "device_name": "Recovered test device",
        })
        .to_string();
        let (status, recovered) = request(
            &app,
            "POST",
            "/v1/auth/recover",
            None,
            recovery_body.clone(),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let recovered = serde_json::from_str::<serde_json::Value>(&recovered).unwrap();
        assert_eq!(recovered["account_id"], registered["account_id"]);
        assert_eq!(recovered["device_number"], 2);

        let (status, _) = request(&app, "POST", "/v1/auth/recover", None, recovery_body).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn realtime_hub_delivers_account_scoped_wakeups() {
        let hub = RealtimeHub::default();
        let mut alice = hub.subscribe("alice");
        let mut bob = hub.subscribe("bob");
        hub.publish("alice", "message");

        assert_eq!(alice.recv().await.unwrap().kind, "message");
        assert!(
            tokio::time::timeout(Duration::from_millis(20), bob.recv())
                .await
                .is_err()
        );
    }
}
