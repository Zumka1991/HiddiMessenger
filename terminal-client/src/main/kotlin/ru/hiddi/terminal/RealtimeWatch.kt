package ru.hiddi.terminal

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

object RealtimeWatch {
    private sealed interface Signal {
        data class Event(val kind: String, val receivedAtNanos: Long) : Signal
        data class Closed(val cause: Throwable?) : Signal
    }

    fun forever(server: String, token: String, synchronize: (String) -> Unit): Nothing {
        var retryMillis = 250L
        while (true) {
            val queue = LinkedBlockingQueue<Signal>()
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
            val socketResult = runCatching {
                client.newWebSocketBuilder()
                    .header("Authorization", "Bearer $token")
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(server.toWebSocketUrl() + "/v1/realtime"), Listener(queue))
                    .join()
            }
            if (socketResult.isFailure) {
                Thread.sleep(retryMillis)
                retryMillis = (retryMillis * 2).coerceAtMost(10_000L)
                continue
            }
            val socket = socketResult.getOrThrow()
            println("Realtime подключён.")
            retryMillis = 250L
            while (true) {
                when (val signal = queue.take()) {
                    is Signal.Event -> {
                        synchronize(signal.kind)
                        val elapsedMs = (System.nanoTime() - signal.receivedAtNanos) / 1_000_000.0
                        println("Realtime ${signal.kind}: синхронизация ${"%.1f".format(elapsedMs)} мс")
                    }
                    is Signal.Closed -> {
                        signal.cause?.let {
                            System.err.println("Realtime отключён: ${it.javaClass.simpleName}")
                        }
                        break
                    }
                }
            }
            socket.abort()
            Thread.sleep(retryMillis)
            retryMillis = (retryMillis * 2).coerceAtMost(10_000L)
        }
    }

    private class Listener(
        private val queue: LinkedBlockingQueue<Signal>,
    ) : WebSocket.Listener {
        private val text = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletableFuture<*>? {
            text.append(data)
            if (last) {
                runCatching { JSONObject(text.toString()).getString("kind") }
                    .onSuccess { queue.offer(Signal.Event(it, System.nanoTime())) }
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

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletableFuture<*>? {
            queue.offer(Signal.Closed(null))
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            queue.offer(Signal.Closed(error))
        }
    }
}

private fun String.toWebSocketUrl(): String = when {
    startsWith("https://") -> "wss://" + removePrefix("https://").trimEnd('/')
    startsWith("http://") -> "ws://" + removePrefix("http://").trimEnd('/')
    else -> error("Некорректный URL сервера")
}
