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

use crate::{auth::authenticate, error::Error, state::AppState};

pub(crate) fn routes(router: Router<AppState>) -> Router<AppState> {
    router.route("/v1/realtime", get(upgrade))
}

async fn upgrade(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, Error> {
    let account = authenticate(&state, &headers)?;
    Ok(ws.on_upgrade(move |socket| serve(socket, state, account.account_id)))
}

async fn serve(mut socket: WebSocket, state: AppState, account_id: String) {
    let mut events = state.realtime.subscribe(&account_id);
    if send_event(&mut socket, "sync_required").await.is_err() {
        return;
    }

    loop {
        tokio::select! {
            incoming = socket.next() => {
                match incoming {
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    Some(Ok(Message::Text(text))) => {
                        if text.as_str() != "sync" {
                            continue;
                        }
                        if send_event(&mut socket, "sync_required").await.is_err() {
                            break;
                        }
                    }
                    _ => {}
                }
            }
            event = events.recv() => {
                let kind = match event {
                    Ok(event) => event.kind,
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => "sync_required",
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                };
                if send_event(&mut socket, kind).await.is_err() {
                    break;
                }
            }
        }
    }
}

async fn send_event(socket: &mut WebSocket, kind: &'static str) -> Result<(), axum::Error> {
    socket
        .send(Message::Text(
            serde_json::json!({"version": 1, "kind": kind})
                .to_string()
                .into(),
        ))
        .await
}
