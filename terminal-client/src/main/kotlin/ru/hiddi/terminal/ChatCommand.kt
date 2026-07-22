package ru.hiddi.terminal

import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

object ChatCommand {
    fun send(args: List<String>) = withVault(args) { state, store, vault, passphrase, _ ->
        val recipient = args.option("--to")?.trim()?.removePrefix("@")?.lowercase() ?: usage()
        val message = args.option("--message") ?: usage()
        sendMessage(state, store, vault, passphrase, recipient, message)
    }

    fun shell(args: List<String>) = withVault(args) { state, store, vault, passphrase, dataDir ->
        val console = System.console() ?: error("Интерактивная оболочка требует терминал.")
        var recipient: String? = null
        println("Hiddi Terminal: /use nickname, /send текст, /inbox, /watch, /quit")
        while (true) {
            val line = console.readLine("hiddi${recipient?.let { " @$it" } ?: ""}> ")?.trim() ?: break
            when {
                line == "/quit" || line == "/exit" -> break
                line.startsWith("/use ") -> {
                    recipient = line.removePrefix("/use ").trim().removePrefix("@").lowercase()
                    println("Диалог: @$recipient")
                }
                line.startsWith("/send ") -> {
                    val peer = requireNotNull(recipient) { "Сначала выберите диалог: /use nickname" }
                    sendMessage(state, store, vault, passphrase, peer, line.removePrefix("/send "))
                }
                line == "/inbox" -> receive(state, store, vault, passphrase, dataDir, printEmpty = true)
                line == "/watch" -> println("Используйте отдельную команду `hiddi-terminal watch` для непрерывного приёма.")
                line == "/help" -> println("/use nickname, /send текст, /inbox, /quit")
                line.isNotBlank() -> println("Неизвестная команда. /help")
            }
        }
    }

    private fun sendMessage(state: JSONObject, store: TerminalSignalStore, vault: EncryptedVault, passphrase: CharArray, recipient: String, message: String) {
        val client = HttpClient.newHttpClient()
        val local = SignalProtocolAddress(state.getString("nickname"), DEVICE_ID)
        val remote = SignalProtocolAddress(recipient, DEVICE_ID)
        if (!store.containsSession(remote)) {
            SessionBuilder(store, remote, local).process(fetchBundle(client, state, recipient))
        }
        val encrypted = SessionCipher(store, local, remote).encrypt(message.encodeToByteArray())
        vault.write(store.snapshot().toString().encodeToByteArray(), passphrase)
        val payload = byteArrayOf(encrypted.type.toByte()) + encrypted.serialize()
        val result = request(client, "POST", "${state.getString("server")}/v1/messages", JSONObject()
            .put("recipient_nickname", recipient).put("ciphertext", payload.base64Url()), state.getString("access_token")) as JSONObject
        println("Отправлено: ${result.getString("message_id")}")
    }

    fun inbox(args: List<String>) = withVault(args) { state, store, vault, passphrase, dataDir ->
        receive(state, store, vault, passphrase, dataDir, printEmpty = true)
    }

    /** Keeps the encrypted vault unlocked only in this foreground terminal process. */
    fun watch(args: List<String>) = withVault(args) { state, store, vault, passphrase, dataDir ->
        println("Live-приём запущен. Нажмите Ctrl+C для выхода.")
        while (true) {
            val client = HttpClient.newHttpClient()
            val ready = request(client, "GET", "${state.getString("server")}/v1/messages/wait", null, state.getString("access_token")) as JSONObject
            if (ready.getBoolean("available")) receive(state, store, vault, passphrase, dataDir, printEmpty = false)
        }
    }

    fun attachments(args: List<String>) = withVault(args) { state, _, _, _, _ -> AttachmentSupport.list(state) }

    fun exportAttachment(args: List<String>) = withVault(args) { state, _, _, _, dataDir ->
        val id = args.option("--id") ?: error("Укажите --id")
        val output = args.option("--output")?.let(Path::of) ?: error("Укажите --output")
        AttachmentSupport.export(state, dataDir, id, output)
    }

    private fun receive(state: JSONObject, store: TerminalSignalStore, vault: EncryptedVault, passphrase: CharArray, dataDir: Path, printEmpty: Boolean) {
        val client = HttpClient.newHttpClient()
        val messages = request(client, "GET", "${state.getString("server")}/v1/messages", null, state.getString("access_token")) as org.json.JSONArray
        var received = 0
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            val sender = message.getString("sender_nickname")
            val raw = message.getString("ciphertext").base64UrlDecode()
            require(raw.isNotEmpty()) { "Получен пустой encrypted envelope" }
            val local = SignalProtocolAddress(state.getString("nickname"), DEVICE_ID)
            val remote = SignalProtocolAddress(sender, DEVICE_ID)
            val cipher = SessionCipher(store, local, remote)
            val plaintext = when (raw.first().toInt()) {
                CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(raw.drop(1).toByteArray()))
                CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(raw.drop(1).toByteArray()))
                else -> error("Неизвестный тип Signal-сообщения от @$sender")
            }
            val decoded = plaintext.decodeToString()
            val attachmentSummary = AttachmentSupport.receiveIfAttachment(client, state, decoded, dataDir)
            vault.write(store.snapshot().toString().encodeToByteArray(), passphrase)
            request(client, "POST", "${state.getString("server")}/v1/messages/${message.getString("message_id")}", null, state.getString("access_token"))
            println("@$sender: ${attachmentSummary ?: decoded}")
            plaintext.fill(0)
            received++
        }
        if (received == 0 && printEmpty) println("Входящих сообщений нет.")
    }

    private fun fetchBundle(client: HttpClient, state: JSONObject, nickname: String): PreKeyBundle {
        val bundle = request(client, "GET", "${state.getString("server")}/v1/users/$nickname/prekey-bundle", null, state.getString("access_token")) as JSONObject
        val oneTime = bundle.optJSONObject("one_time_prekey")
        val kyberOneTime = bundle.optJSONObject("kyber_one_time_prekey")
        val signed = bundle.getJSONObject("signed_prekey")
        val kyberSigned = bundle.getJSONObject("kyber_signed_prekey")
        return PreKeyBundle(
            bundle.getInt("registration_id"), DEVICE_ID,
            oneTime?.getInt("id") ?: PreKeyBundle.NULL_PRE_KEY_ID,
            oneTime?.let { ECPublicKey(it.getString("public_key").base64UrlDecode()) },
            signed.getInt("id"), ECPublicKey(signed.getString("public_key").base64UrlDecode()),
            signed.getString("signature").base64UrlDecode(), IdentityKey(bundle.getString("identity_public_key").base64UrlDecode()),
            kyberOneTime?.getInt("id") ?: PreKeyBundle.NULL_PRE_KEY_ID,
            kyberOneTime?.let { KEMPublicKey(it.getString("public_key").base64UrlDecode()) }
                ?: KEMPublicKey(kyberSigned.getString("public_key").base64UrlDecode()),
            (kyberOneTime ?: kyberSigned).getString("signature").base64UrlDecode(),
        )
    }

    private fun withVault(args: List<String>, operation: (JSONObject, TerminalSignalStore, EncryptedVault, CharArray, Path) -> Unit) {
        val dataDir = Path.of(args.option("--data-dir") ?: Path.of(System.getProperty("user.home"), ".local", "share", "hiddi-terminal").toString())
        val console = System.console() ?: error("Команда требует интерактивный терминал для парольной фразы.")
        val passphrase = console.readPassword("Парольная фраза: ")
        try {
            val vault = EncryptedVault(dataDir.resolve("signal-device.v1"))
            val state = JSONObject(vault.read(passphrase)?.decodeToString() ?: error("Устройство не зарегистрировано"))
            operation(state, TerminalSignalStore(state), vault, passphrase, dataDir)
        } finally { passphrase.fill('\u0000') }
    }

    private fun List<String>.option(name: String): String? = indexOf(name).takeIf { it >= 0 }?.let { getOrNull(it + 1) }
    private fun usage(): Nothing = error("Использование: chat --to nickname --message text [--data-dir PATH]")
    private const val DEVICE_ID = 1
}

internal fun request(client: HttpClient, method: String, url: String, body: JSONObject?, token: String): Any {
    val builder = HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer $token")
    if (body != null) builder.header("Content-Type", "application/json")
    val response = client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body?.toString().orEmpty())).build(), HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in 200..299) { response.body().takeIf(String::isNotBlank)?.let { JSONObject(it).optString("error", "HTTP ${response.statusCode()}") } ?: "HTTP ${response.statusCode()}" }
    return when {
        response.body().isBlank() -> JSONObject()
        response.body().trimStart().startsWith("[") -> org.json.JSONArray(response.body())
        else -> JSONObject(response.body())
    }
}
