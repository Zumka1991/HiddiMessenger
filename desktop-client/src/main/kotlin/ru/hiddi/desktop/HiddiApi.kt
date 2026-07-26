package ru.hiddi.desktop

import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class HiddiApi(
    private val vault: Vault,
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    fun register(
        server: String,
        nickname: String,
        inviteCode: String,
        deviceName: String,
        passphrase: CharArray,
    ): RegistrationResult {
        val endpoint = validateServer(server)
        require(nickname.trim().removePrefix("@").lowercase().matches(Regex("[a-z0-9_]{3,32}"))) {
            "Никнейм: 3–32 символа a-z, 0-9 или _"
        }
        require(inviteCode.isNotBlank()) { "Введите инвайт" }
        require(deviceName.trim().length in 1..64) { "Название устройства: от 1 до 64 символов" }
        require(!vault.exists()) { "Это приложение уже настроено" }
        val device = SignalDevice.create()
        RecoveryKey.generate().use { recoveryKey ->
            val registration = request("POST", "$endpoint/v1/auth/register", device.registrationJson(nickname, inviteCode, deviceName, recoveryKey.publicKey()), null) as JSONObject
            check(registration.getInt("registration_id") == device.registrationId) { "Сервер вернул другой registration_id" }
            val state = device.privateState(registration, endpoint)
            persist(state, passphrase)
            publishPendingPrekeys(state, passphrase)
            return RegistrationResult(registration.getString("nickname"), recoveryKey.encoded)
        }
    }

    fun pair(
        server: String,
        linkCode: String,
        deviceName: String,
        passphrase: CharArray,
    ): DeviceLinkResult {
        val endpoint = validateServer(server)
        require(linkCode.trim().length >= 32) { "Проверьте код привязки" }
        require(deviceName.trim().length in 1..64) { "Название устройства: от 1 до 64 символов" }
        require(!vault.exists()) { "Это приложение уже привязано" }

        val device = SignalDevice.create()
        val registration =
            request(
                "POST",
                "$endpoint/v1/devices/link",
                device.linkJson(linkCode.trim(), deviceName.trim()),
                null,
            ) as JSONObject
        check(registration.getInt("registration_id") == device.registrationId) {
            "Сервер вернул другой registration_id"
        }
        val state = device.privateState(registration, endpoint)

        // Save private material before network publication. If publication is
        // interrupted, unlock() safely retries it from the encrypted vault.
        persist(state, passphrase)
        publishPendingPrekeys(state, passphrase)
        return DeviceLinkResult(
            nickname = registration.getString("nickname"),
            deviceNumber = registration.getInt("device_number"),
        )
    }

    fun unlock(passphrase: CharArray): HiddiSession {
        val state =
            JSONObject(
                vault.read(passphrase)?.decodeToString()
                    ?: error("Сначала привяжите этот компьютер"),
            )
        publishPendingPrekeys(state, passphrase)
        return HiddiSession(vault, passphrase, state, client)
    }

    private fun publishPendingPrekeys(state: JSONObject, passphrase: CharArray) {
        val pending = state.optJSONObject("pending_public_prekeys") ?: return
        request(
            "PUT",
            "${state.getString("server")}/v1/devices/prekeys",
            pending,
            state.getString("access_token"),
        )
        state.remove("pending_public_prekeys")
        persist(state, passphrase)
    }

    private fun persist(state: JSONObject, passphrase: CharArray) {
        val plaintext = state.toString().encodeToByteArray()
        try {
            vault.write(plaintext, passphrase)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun validateServer(value: String): String {
        val server = value.trim().trimEnd('/')
        require(
            server.startsWith("https://") ||
                server.startsWith("http://127.0.0.1") ||
                server.startsWith("http://localhost"),
        ) {
            "Удалённый сервер должен использовать HTTPS"
        }
        return server
    }

    private fun request(
        method: String,
        url: String,
        body: JSONObject?,
        token: String?,
    ): Any = httpRequest(client, method, url, body, token)
}

class HiddiSession internal constructor(
    private val vault: Vault,
    private val passphrase: CharArray,
    private val state: JSONObject,
    private val client: HttpClient,
) : AutoCloseable {
    private val store = DesktopSignalStore(state)

    val nickname: String get() = state.getString("nickname")
    val deviceNumber: Int get() = state.getInt("device_number")
    val server: String get() = state.getString("server")
    internal val accessToken: String get() = state.getString("access_token")

    fun isOnline(): Boolean {
        val response =
            client.send(
                HttpRequest.newBuilder(URI("$server/health")).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        return response.statusCode() in 200..299
    }

    fun profile(): HiddiProfile {
        val json = authenticatedRequest("GET", "$server/v1/profile", null) as JSONObject
        return HiddiProfile(
            nickname = json.getString("nickname"),
            displayName = json.optString("display_name"),
            bio = json.optString("bio"),
        )
    }

    fun search(query: String): List<HiddiProfile> {
        val normalized = query.trim().removePrefix("@").lowercase()
        if (normalized.length < 2) return emptyList()
        val encoded = URLEncoder.encode(normalized, Charsets.UTF_8)
        val result =
            authenticatedRequest("GET", "$server/v1/users?query=$encoded", null) as JSONArray
        return (0 until result.length()).map { index ->
            result.getJSONObject(index).let {
                HiddiProfile(
                    nickname = it.getString("nickname"),
                    displayName = it.optString("display_name"),
                    bio = it.optString("bio"),
                )
            }
        }
    }

    @Synchronized
    fun history(): List<ChatEntry> =
        state.optJSONArray("chat_history")?.let { entries ->
            (0 until entries.length()).mapNotNull { index ->
                runCatching {
                    entries.getJSONObject(index).let { item ->
                        ChatEntry(
                            peer = item.getString("peer"),
                            text = item.getString("text"),
                            outgoing = item.getBoolean("outgoing"),
                            createdAt = item.optLong("created_at", System.currentTimeMillis()),
                            messageId = item.optString("message_id").takeIf(String::isNotBlank),
                            deliveryStatus = item.optString("delivery_status", "sent"),
                        )
                    }
                }.getOrNull()
            }
        } ?: emptyList()

    @Synchronized
    fun syncInbox(): List<ChatEntry> {
        val inbox = authenticatedRequest("GET", "$server/v1/messages", null) as JSONArray
        val received = mutableListOf<ChatEntry>()
        for (index in 0 until inbox.length()) {
            val item = inbox.getJSONObject(index)
            val messageId = item.getString("message_id")
            if (history().any { it.messageId == messageId }) {
                authenticatedRequest("POST", "$server/v1/messages/$messageId", null)
                continue
            }
            val raw = item.getString("ciphertext").base64UrlDecode()
            require(raw.isNotEmpty()) { "Получен пустой encrypted envelope" }
            val sender = item.getString("sender_nickname")
            val remote = SignalProtocolAddress(sender, item.optInt("sender_device_number", 1))
            val local = SignalProtocolAddress(nickname, deviceNumber)
            val plain = when (raw.first().toInt()) {
                CiphertextMessage.PREKEY_TYPE -> SessionCipher(store, local, remote).decrypt(PreKeySignalMessage(raw.drop(1).toByteArray()))
                CiphertextMessage.WHISPER_TYPE -> SessionCipher(store, local, remote).decrypt(SignalMessage(raw.drop(1).toByteArray()))
                else -> error("Неизвестный тип сообщения")
            }
            try {
                val entry = ChatEntry(sender, plain.decodeToString(), outgoing = false, messageId = messageId, deliveryStatus = "delivered")
                appendHistory(entry)
                persist()
                authenticatedRequest("POST", "$server/v1/messages/$messageId", null)
                received += entry
            } finally {
                plain.fill(0)
                raw.fill(0)
            }
        }
        return received
    }

    @Synchronized
    fun send(recipient: String, plaintext: String): ChatEntry {
        val peer = recipient.trim().removePrefix("@").lowercase()
        require(peer.matches(Regex("[a-z0-9_]{3,32}"))) { "Некорректный никнейм" }
        require(plaintext.isNotBlank()) { "Сообщение пустое" }
        require(plaintext.encodeToByteArray().size <= 32_000) { "Сообщение слишком длинное" }

        val local = SignalProtocolAddress(nickname, deviceNumber)
        val bundleJson =
            authenticatedRequest(
                "GET",
                "$server/v1/users/$peer/prekey-bundle",
                null,
            ) as JSONObject
        val remote = SignalProtocolAddress(peer, bundleJson.optInt("device_number", 1))
        if (!store.containsSession(remote)) {
            SessionBuilder(store, remote, local).process(bundle(bundleJson))
        }
        val encrypted =
            SessionCipher(store, local, remote).encrypt(plaintext.encodeToByteArray())
        persist()
        val envelope = byteArrayOf(encrypted.type.toByte()) + encrypted.serialize()
        val result =
            authenticatedRequest(
                "POST",
                "$server/v1/messages",
                JSONObject()
                    .put("recipient_nickname", peer)
                    .put("ciphertext", envelope.base64Url()),
            ) as JSONObject
        envelope.fill(0)
        val entry = ChatEntry(peer, plaintext, outgoing = true, messageId = result.getString("message_id"), deliveryStatus = "sent")
        appendHistory(entry)
        persist()
        return entry
    }

    @Synchronized
    fun updateDeliveryStatus(messageId: String): String? {
        val status = authenticatedRequest("GET", "$server/v1/messages/$messageId", null) as JSONObject
        val next = when {
            status.optBoolean("read") -> "read"
            status.optBoolean("delivered") -> "delivered"
            else -> "sent"
        }
        val history = historyJson()
        for (index in 0 until history.length()) {
            if (history.getJSONObject(index).optString("message_id") == messageId) {
                history.getJSONObject(index).put("delivery_status", next)
                persist()
                return next
            }
        }
        return null
    }

    private fun bundle(json: JSONObject): PreKeyBundle {
        val oneTime = json.optJSONObject("one_time_prekey")
        val kyberOneTime = json.optJSONObject("kyber_one_time_prekey")
        val signed = json.getJSONObject("signed_prekey")
        val kyberSigned = json.getJSONObject("kyber_signed_prekey")
        return PreKeyBundle(
            json.getInt("registration_id"),
            json.optInt("device_number", 1),
            oneTime?.getInt("id") ?: PreKeyBundle.NULL_PRE_KEY_ID,
            oneTime?.let { ECPublicKey(it.getString("public_key").base64UrlDecode()) },
            signed.getInt("id"),
            ECPublicKey(signed.getString("public_key").base64UrlDecode()),
            signed.getString("signature").base64UrlDecode(),
            IdentityKey(json.getString("identity_public_key").base64UrlDecode()),
            kyberOneTime?.getInt("id") ?: PreKeyBundle.NULL_PRE_KEY_ID,
            kyberOneTime?.let {
                KEMPublicKey(it.getString("public_key").base64UrlDecode())
            } ?: KEMPublicKey(kyberSigned.getString("public_key").base64UrlDecode()),
            (kyberOneTime ?: kyberSigned).getString("signature").base64UrlDecode(),
        )
    }

    private fun authenticatedRequest(method: String, url: String, body: JSONObject?): Any =
        httpRequest(client, method, url, body, state.getString("access_token"))

    private fun appendHistory(entry: ChatEntry) {
        historyJson().put(
            JSONObject()
                .put("peer", entry.peer)
                .put("text", entry.text)
                .put("outgoing", entry.outgoing)
                .put("created_at", entry.createdAt)
                .put("message_id", entry.messageId)
                .put("delivery_status", entry.deliveryStatus),
        )
    }

    private fun historyJson(): JSONArray = state.optJSONArray("chat_history") ?: JSONArray().also { state.put("chat_history", it) }

    private fun persist() {
        val plaintext = store.snapshot().toString().encodeToByteArray()
        try {
            vault.write(plaintext, passphrase)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun close() {
        passphrase.fill('\u0000')
    }
}

private fun httpRequest(
    client: HttpClient,
    method: String,
    url: String,
    body: JSONObject?,
    token: String?,
): Any {
    val builder = HttpRequest.newBuilder(URI(url))
    token?.let { builder.header("Authorization", "Bearer $it") }
    if (body != null) builder.header("Content-Type", "application/json")
    val publisher =
        if (body == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(body.toString())
        }
    val response =
        client.send(
            builder.method(method, publisher).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    check(response.statusCode() in 200..299) {
        val message =
            runCatching { JSONObject(response.body()).optString("error") }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
        message ?: "Ошибка сервера: HTTP ${response.statusCode()}"
    }
    return when {
        response.body().isBlank() -> JSONObject()
        response.body().trimStart().startsWith("[") -> JSONArray(response.body())
        else -> JSONObject(response.body())
    }
}
