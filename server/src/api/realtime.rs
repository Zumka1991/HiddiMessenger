use axum::{
    Router,
    extract::{
        State,
        ws::{Message, WebSocket, WebSocketUpgrade},
    },
    http::HeaderMap,
    response::Response,
    routing::get,
};
use futures_util::StreamExt;
use rusqlite::params;
use serde::Serialize;

use crate::{
    auth::{AuthenticatedAccount, authenticate},
    error::Error,
    state::{AppState, PresenceEvent, RealtimeEvent},
    validation::normalize_nickname,
};

pub(crate) fn routes(router: Router<AppState>) -> Router<AppState> {
    router.route("/v1/realtime", get(upgrade))
}

async fn upgrade(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, Error> {
    let account = authenticate(&state, &headers)?;
    let invisible = headers
        .get("x-hiddi-invisible")
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.eq_ignore_ascii_case("true"));
    Ok(ws.on_upgrade(move |socket| serve(socket, state, account, invisible)))
}

async fn serve(
    mut socket: WebSocket,
    state: AppState,
    account: AuthenticatedAccount,
    invisible: bool,
) {
    let mut events = state.realtime.subscribe(&account.account_id);
    let mut presence_events = state.realtime.subscribe_presence();
    let mut watched_account: Option<String> = None;
    let mut visible = !invisible;
    if visible {
        state
            .realtime
            .connection_opened(&account.account_id, &account.nickname);
    }
    if send_event(&mut socket, "sync_required").await.is_err() {
        if visible {
            state
                .realtime
                .connection_closed(&account.account_id, &account.nickname);
        }
        return;
    }

    loop {
        tokio::select! {
            incoming = socket.next() => {
                match incoming {
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    Some(Ok(Message::Text(text))) => {
                        if text.as_str() == "sync" {
                            if send_event(&mut socket, "sync_required").await.is_err() {
                                break;
                            }
                            continue;
                        }
                        let Ok(payload) = serde_json::from_str::<serde_json::Value>(text.as_str()) else {
                            continue;
                        };
                        match payload.get("kind").and_then(|kind| kind.as_str()) {
                            Some("presence_subscribe") => {
                                let Some(nickname) = payload
                                    .get("nickname")
                                    .and_then(|value| value.as_str())
                                    .and_then(normalize_nickname)
                                else {
                                    continue;
                                };
                                let Some(target_id) = account_id_for_nickname(&state, &nickname) else {
                                    continue;
                                };
                                watched_account = Some(target_id.clone());
                                let event = PresenceEvent {
                                    online: state.realtime.is_online(&target_id),
                                    account_id: target_id,
                                    nickname,
                                };
                                if send_presence(&mut socket, &event).await.is_err() {
                                    break;
                                }
                            }
                            Some("typing") => {
                                let Some(nickname) = payload
                                    .get("nickname")
                                    .and_then(|value| value.as_str())
                                    .and_then(normalize_nickname)
                                else {
                                    continue;
                                };
                                let typing = payload
                                    .get("typing")
                                    .and_then(|value| value.as_bool())
                                    .unwrap_or(false);
                                if typing && !visible {
                                    continue;
                                }
                                if let Some(target_id) = account_id_for_nickname(&state, &nickname) {
                                    state.realtime.publish_typing(
                                        &target_id,
                                        &account.nickname,
                                        typing,
                                    );
                                }
                            }
                            Some("visibility") => {
                                let requested = payload
                                    .get("visible")
                                    .and_then(|value| value.as_bool())
                                    .unwrap_or(true);
                                if requested != visible {
                                    if requested {
                                        state.realtime.connection_opened(
                                            &account.account_id,
                                            &account.nickname,
                                        );
                                    } else {
                                        state.realtime.connection_closed(
                                            &account.account_id,
                                            &account.nickname,
                                        );
                                    }
                                    visible = requested;
                                }
                            }
                            _ => {}
                        }
                    }
                    _ => {}
                }
            }
            event = events.recv() => {
                let event = match event {
                    Ok(event) => event,
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => RealtimeEvent {
                        version: 1,
                        kind: "sync_required",
                        nickname: None,
                        typing: None,
                    },
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                };
                if send_payload(&mut socket, &event).await.is_err() {
                    break;
                }
            }
            presence = presence_events.recv() => {
                let Ok(presence) = presence else {
                    continue;
                };
                if watched_account.as_deref() == Some(presence.account_id.as_str())
                    && send_presence(&mut socket, &presence).await.is_err()
                {
                    break;
                }
            }
        }
    }
    if visible {
        state
            .realtime
            .connection_closed(&account.account_id, &account.nickname);
    }
}

async fn send_event(socket: &mut WebSocket, kind: &'static str) -> Result<(), axum::Error> {
    send_payload(
        socket,
        &RealtimeEvent {
            version: 1,
            kind,
            nickname: None,
            typing: None,
        },
    )
    .await
}

async fn send_presence(socket: &mut WebSocket, event: &PresenceEvent) -> Result<(), axum::Error> {
    send_payload(
        socket,
        &serde_json::json!({
            "version": 1,
            "kind": "presence",
            "nickname": event.nickname,
            "online": event.online,
        }),
    )
    .await
}

async fn send_payload(socket: &mut WebSocket, payload: &impl Serialize) -> Result<(), axum::Error> {
    socket
        .send(Message::Text(
            serde_json::to_string(payload)
                .expect("realtime event must serialize")
                .into(),
        ))
        .await
}

fn account_id_for_nickname(state: &AppState, nickname: &str) -> Option<String> {
    state
        .db
        .lock()
        .ok()?
        .query_row(
            "SELECT id FROM accounts WHERE nickname = ?1",
            params![nickname],
            |row| row.get(0),
        )
        .ok()
}
