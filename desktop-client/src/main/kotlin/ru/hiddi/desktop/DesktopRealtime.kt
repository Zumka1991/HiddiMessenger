package ru.hiddi.desktop

import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** WebSocket wake-up channel. Message bodies remain in the authenticated durable inbox. */
class DesktopRealtime(
    server: String,
    private val token: String,
) : AutoCloseable {
    private val endpoint = server.toDesktopWebSocketUrl() + "/v1/realtime"
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    val events = Channel<Event>(Channel.UNLIMITED)
    private var socket: WebSocket? = null

    sealed interface Event {
        data object SyncRequired : Event
        data object Disconnected : Event
    }

    fun connect() {
        if (socket != null) return
        client.newWebSocketBuilder()
            .header("Authorization", "Bearer $token")
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI(endpoint), Listener(events) { socket = null })
            .whenComplete { connected, error ->
                if (error == null) socket = connected else events.trySend(Event.Disconnected)
            }
    }

    override fun close() {
        socket?.abort()
        socket = null
        events.close()
    }

    private class Listener(
        private val events: Channel<Event>,
        private val disconnected: () -> Unit,
    ) : WebSocket.Listener {
        private val text = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
            text.append(data)
            if (last) {
                runCatching { JSONObject(text.toString()).getString("kind") }
                    .onSuccess { events.trySend(Event.SyncRequired) }
                    .onFailure { webSocket.sendClose(1003, "invalid realtime event") }
                text.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletableFuture<*> {
            webSocket.request(1)
            return webSocket.sendPong(message)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*>? {
            disconnected()
            events.trySend(Event.Disconnected)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            disconnected()
            events.trySend(Event.Disconnected)
        }
    }
}

private fun String.toDesktopWebSocketUrl(): String = when {
    startsWith("https://") -> "wss://" + removePrefix("https://").trimEnd('/')
    startsWith("http://") -> "ws://" + removePrefix("http://").trimEnd('/')
    else -> error("Некорректный URL сервера")
}
