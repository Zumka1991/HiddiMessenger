package ru.hiddi.desktop

import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** WebSocket wake-up channel. Message bodies remain in the authenticated durable inbox. */
class DesktopRealtime(
    server: String,
    private val token: String,
) : AutoCloseable {
    private val endpoint = server.toDesktopWebSocketUrl() + "/v1/realtime"
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    val events = Channel<Event>(Channel.UNLIMITED)

    // Handshake futures and listener callbacks run on HttpClient threads, so
    // every field below is published through this monitor rather than by luck.
    private val lock = Any()
    private var socket: WebSocket? = null
    private var connecting = false
    private var generation = 0
    private var closed = false
    private val awaitingPong = AtomicBoolean(false)

    sealed interface Event {
        data object Connected : Event
        data object SyncRequired : Event
        data class Presence(val nickname: String, val online: Boolean) : Event
        data class Typing(val nickname: String, val typing: Boolean) : Event
        data object Disconnected : Event
    }

    fun connect(invisible: Boolean = false) {
        val attempt = synchronized(lock) {
            if (closed || connecting || socket != null) return
            connecting = true
            ++generation
        }
        awaitingPong.set(false)
        client.newWebSocketBuilder()
            .header("Authorization", "Bearer $token")
            .header("X-Hiddi-Invisible", invisible.toString())
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI(endpoint), Listener(attempt))
            .whenComplete { connected, error ->
                val installed = synchronized(lock) {
                    connecting = false
                    // A listener callback can retire this attempt before the
                    // handshake future completes. Publishing the socket anyway
                    // would leave connect() permanently short-circuited on a
                    // dead connection.
                    if (error == null && !closed && generation == attempt) {
                        socket = connected
                        true
                    } else {
                        false
                    }
                }
                if (installed) {
                    events.trySend(Event.Connected)
                } else {
                    connected?.abort()
                    retire(attempt)
                }
            }
    }

    /**
     * Pings the server and drops the connection when the previous ping was never
     * answered. A socket left half-open by a VPN or network change never reports
     * onClose, so it has to be detected explicitly for [connect] to retry.
     */
    fun heartbeat() {
        val (current, attempt) = synchronized(lock) { socket to generation }
        if (current == null) return
        if (awaitingPong.getAndSet(true)) {
            retire(attempt)
            current.abort()
            return
        }
        runCatching { current.sendPing(ByteBuffer.allocate(0)) }
            .onFailure {
                retire(attempt)
                current.abort()
            }
    }

    fun subscribePresence(nickname: String) {
        send("presence_subscribe", nickname, null)
    }

    fun sendTyping(nickname: String, typing: Boolean) {
        send("typing", nickname, typing)
    }

    fun setVisible(visible: Boolean) {
        current()?.sendText(
            JSONObject()
                .put("version", 1)
                .put("kind", "visibility")
                .put("visible", visible)
                .toString(),
            true,
        )
    }

    private fun send(kind: String, nickname: String, typing: Boolean?) {
        val payload =
            JSONObject()
                .put("version", 1)
                .put("kind", kind)
                .put("nickname", nickname)
        typing?.let { payload.put("typing", it) }
        current()?.sendText(payload.toString(), true)
    }

    private fun current(): WebSocket? = synchronized(lock) { socket }

    /** Retires [attempt] exactly once so that a reconnect can be scheduled. */
    private fun retire(attempt: Int) {
        synchronized(lock) {
            if (generation != attempt) return
            generation++
            socket = null
            connecting = false
        }
        events.trySend(Event.Disconnected)
    }

    override fun close() {
        val current = synchronized(lock) {
            closed = true
            generation++
            socket.also { socket = null }
        }
        current?.abort()
        events.close()
    }

    private inner class Listener(private val attempt: Int) : WebSocket.Listener {
        private val text = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
            text.append(data)
            if (last) {
                runCatching {
                    val payload = JSONObject(text.toString())
                    when (payload.getString("kind")) {
                        "presence" ->
                            Event.Presence(
                                nickname = payload.getString("nickname"),
                                online = payload.getBoolean("online"),
                            )
                        "typing" ->
                            Event.Typing(
                                nickname = payload.getString("nickname"),
                                typing = payload.optBoolean("typing"),
                            )
                        else -> Event.SyncRequired
                    }
                }
                    .onSuccess { events.trySend(it) }
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

        override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletableFuture<*>? {
            awaitingPong.set(false)
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*>? {
            retire(attempt)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            retire(attempt)
        }
    }
}

private fun String.toDesktopWebSocketUrl(): String = when {
    startsWith("https://") -> "wss://" + removePrefix("https://").trimEnd('/')
    startsWith("http://") -> "ws://" + removePrefix("http://").trimEnd('/')
    else -> error("Некорректный URL сервера")
}
