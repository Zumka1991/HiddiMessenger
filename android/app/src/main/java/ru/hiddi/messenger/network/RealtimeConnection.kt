package ru.hiddi.messenger.network

import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authenticated wake-up channel. Durable messages and deletion markers remain
 * in the HTTP/SQLite inboxes and are synchronized after every reconnect.
 */
class RealtimeConnection {
    sealed interface Event {
        data object Connected : Event
        data class SyncRequired(val kind: String) : Event
        data class Disconnected(val cause: Throwable?) : Event
    }

    val events = Channel<Event>(Channel.UNLIMITED)

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var socket: WebSocket? = null
    private var terminalEventSent = AtomicBoolean(false)

    fun connect(profile: AccountProfile) {
        terminalEventSent = AtomicBoolean(false)
        socket?.cancel()
        val request = Request.Builder()
            .url(profile.serverUrl.toWebSocketUrl() + "/v1/realtime")
            .header("Authorization", "Bearer ${profile.accessToken}")
            .build()
        socket = client.newWebSocket(request, listener())
    }

    fun close() {
        socket?.close(1000, "service stopped")
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            events.trySend(Event.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val payload = JSONObject(text)
                check(payload.optInt("version") == 1) { "Unsupported realtime protocol" }
                payload.getString("kind")
            }.onSuccess { kind ->
                events.trySend(Event.SyncRequired(kind))
            }.onFailure {
                webSocket.close(1003, "invalid realtime event")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            disconnected(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            disconnected(t)
        }

        private fun disconnected(cause: Throwable?) {
            if (terminalEventSent.compareAndSet(false, true)) {
                events.trySend(Event.Disconnected(cause))
            }
        }
    }
}

private fun String.toWebSocketUrl(): String = when {
    startsWith("https://") -> "wss://" + removePrefix("https://").trimEnd('/')
    startsWith("http://") -> "ws://" + removePrefix("http://").trimEnd('/')
    else -> error("Unsupported server URL")
}
