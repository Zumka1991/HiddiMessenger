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
import java.security.MessageDigest
import java.time.Instant

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
    private val attachmentStore =
        DesktopAttachmentStore(vault.storageDirectory.resolve("attachments-v1"))

    val nickname: String get() = state.getString("nickname")
    val deviceNumber: Int get() = state.getInt("device_number")
    internal val deviceId: String get() = state.getString("device_id")
    val server: String get() = state.getString("server")
    internal val accessToken: String get() = state.getString("access_token")
    internal val storageDirectory get() = vault.storageDirectory
    private val groupManager by lazy { DesktopGroupManager(this) }

    @Synchronized
    fun prepareGroups() = groupManager.initialize()

    @Synchronized
    fun groups(): List<DesktopGroup> = groupManager.groups()

    @Synchronized
    fun createGroup(name: String, invitedNickname: String): DesktopGroup =
        groupManager.create(name, invitedNickname)

    @Synchronized
    fun sendGroupText(groupId: String, text: String): DesktopGroupMessage =
        groupManager.sendText(groupId, text)

    @Synchronized
    fun syncGroups(): List<DesktopGroup> {
        groupManager.synchronize()
        return groupManager.groups()
    }

    internal fun ensureGroupState() {
        var changed = false
        if (!state.has("mls_storage_key")) {
            state.put(
                "mls_storage_key",
                ByteArray(64).also(java.security.SecureRandom()::nextBytes).base64Url(),
            )
            changed = true
        }
        if (!state.has("mls_groups")) {
            state.put("mls_groups", JSONArray())
            changed = true
        }
        if (!state.has("mls_pending_events")) {
            state.put("mls_pending_events", JSONArray())
            changed = true
        }
        if (changed) persist()
    }

    internal fun groupStorageKey(): ByteArray = state.getString("mls_storage_key").base64UrlDecode()

    internal fun groupStateArray(): JSONArray = state.getJSONArray("mls_groups")

    internal fun groupPendingEvents(): JSONArray = state.getJSONArray("mls_pending_events")

    internal fun persistGroupState() = persist()

    internal fun groupRequest(method: String, url: String, body: JSONObject?): Any =
        authenticatedRequest(method, url, body)

    @Synchronized
    fun invisibleMode(): Boolean = state.optBoolean("invisible_mode", false)

    @Synchronized
    fun setInvisibleMode(enabled: Boolean) {
        state.put("invisible_mode", enabled)
        persist()
    }

    @Synchronized
    fun contacts(): Set<String> {
        val values = state.optJSONArray("contacts") ?: return emptySet()
        return (0 until values.length())
            .mapNotNull { index -> values.optString(index).takeIf(String::isNotBlank) }
            .map(::normalizePeer)
            .toSet()
    }

    @Synchronized
    fun setContact(peer: String, added: Boolean) {
        val normalized = normalizePeer(peer)
        val updated = contacts().toMutableSet().apply {
            if (added) add(normalized) else remove(normalized)
        }
        state.put("contacts", JSONArray(updated.sorted()))
        persist()
    }

    @Synchronized
    fun createDeviceLinkCode(): Pair<String, Long> {
        val json = authenticatedRequest("POST", "$server/v1/devices/link-code", JSONObject()) as JSONObject
        return json.getString("link_code") to json.getLong("expires_at")
    }

    @Synchronized
    fun logoutCurrentDevice() {
        authenticatedRequest("DELETE", "$server/v1/devices/current", null)
        passphrase.fill('\u0000')
        vault.deleteLocalData()
    }

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
            avatarVersion = json.optString("avatar_version").takeIf(String::isNotBlank),
        )
    }

    @Synchronized
    fun updateProfile(displayName: String, bio: String): HiddiProfile {
        require(displayName.length <= 64 && displayName.none(Char::isISOControl)) {
            "Имя должно быть не длиннее 64 символов"
        }
        require(bio.length <= 250) { "Описание должно быть не длиннее 250 символов" }
        val json =
            authenticatedRequest(
                "PUT",
                "$server/v1/profile",
                JSONObject().put("display_name", displayName).put("bio", bio),
            ) as JSONObject
        return HiddiProfile(
            nickname = json.getString("nickname"),
            displayName = json.optString("display_name"),
            bio = json.optString("bio"),
            avatarVersion = json.optString("avatar_version").takeIf(String::isNotBlank),
        )
    }

    @Synchronized
    fun avatar(peer: String): ByteArray {
        val encoded = URLEncoder.encode(normalizePeer(peer), Charsets.UTF_8)
        val json =
            authenticatedRequest("GET", "$server/v1/users/$encoded/avatar", null) as JSONObject
        return json.getString("image").base64UrlDecode()
    }

    @Synchronized
    fun uploadAvatar(jpeg: ByteArray): String {
        require(jpeg.size in 4..512 * 1024) { "Аватар должен быть не больше 512 КиБ" }
        require(
            jpeg.take(3) == listOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) &&
                jpeg.takeLast(2) == listOf(0xff.toByte(), 0xd9.toByte()),
        ) { "Аватар должен быть JPEG-изображением" }
        val response =
            authenticatedRequest(
                "PUT",
                "$server/v1/profile/avatar",
                JSONObject().put("image", jpeg.base64Url()),
            ) as JSONObject
        return response.getString("version")
    }

    @Synchronized
    fun deleteAvatar() {
        authenticatedRequest("DELETE", "$server/v1/profile/avatar", null)
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
                    avatarVersion = it.optString("avatar_version").takeIf(String::isNotBlank),
                )
            }
        }
    }

    fun userProfile(peer: String): HiddiProfile {
        val encoded = URLEncoder.encode(normalizePeer(peer), Charsets.UTF_8)
        val json = authenticatedRequest("GET", "$server/v1/users/$encoded", null) as JSONObject
        return HiddiProfile(
            nickname = json.getString("nickname"),
            displayName = json.optString("display_name"),
            bio = json.optString("bio"),
            avatarVersion = json.optString("avatar_version").takeIf(String::isNotBlank),
        )
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
                            attachment =
                                item.optJSONObject("attachment")
                                    ?.let {
                                        runCatching {
                                            it.toAttachmentDescriptor().also(
                                                DesktopAttachmentStore::validateDescriptor,
                                            )
                                        }.getOrNull()
                                    },
                        )
                    }
                }.getOrNull()
            }
        } ?: emptyList()

    /**
     * Sends an encrypted initial history snapshot only to own devices which
     * have not received one from this desktop yet. The server receives regular
     * opaque Signal envelopes and cannot inspect the snapshot.
     */
    @Synchronized
    fun synchronizeHistoryToOwnDevices(): Int {
        val local = SignalProtocolAddress(nickname, deviceNumber)
        val ownBundles =
            authenticatedRequest(
                "GET",
                "$server/v1/users/$nickname/prekey-bundles",
                null,
            ) as JSONArray
        val synchronizedDevices =
            state.optJSONArray("history_sync_devices_v1")
                ?.let { values ->
                    (0 until values.length()).map(values::getInt).toMutableSet()
                }
                ?: mutableSetOf()
        var synchronizedCount = 0
        for (index in 0 until ownBundles.length()) {
            val value = ownBundles.getJSONObject(index)
            val targetDeviceNumber = value.optInt("device_number", 1)
            if (targetDeviceNumber == deviceNumber || targetDeviceNumber in synchronizedDevices) {
                continue
            }
            val remote = SignalProtocolAddress(nickname, targetDeviceNumber)
            if (!store.containsSession(remote)) {
                SessionBuilder(store, remote, local).process(bundle(value))
            }
            history()
                .map(::historySyncJson)
                .chunked(HISTORY_SYNC_BATCH_SIZE)
                .forEach { entries ->
                    val payload =
                        JSONObject()
                            .put("type", HiddiEnvelope.HISTORY_SYNC)
                            .put("entries", JSONArray(entries))
                            .toString()
                    val encrypted =
                        SessionCipher(store, local, remote).encrypt(payload.encodeToByteArray())
                    val envelope = byteArrayOf(encrypted.type.toByte()) + encrypted.serialize()
                    try {
                        authenticatedRequest(
                            "POST",
                            "$server/v1/messages",
                            JSONObject()
                                .put("recipient_nickname", nickname)
                                .put(
                                    "device_ciphertexts",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("device_number", targetDeviceNumber)
                                            .put("ciphertext", envelope.base64Url()),
                                    ),
                                ),
                        )
                    } finally {
                        envelope.fill(0)
                    }
                }
            synchronizedDevices += targetDeviceNumber
            synchronizedCount++
        }
        state.put(
            "history_sync_devices_v1",
            JSONArray(synchronizedDevices.sorted()),
        )
        persist()
        return synchronizedCount
    }

    @Synchronized
    fun syncInbox(): List<ChatEntry> {
        synchronizeMessageDeletions()
        synchronizeConversationDeletions()
        val received = mutableListOf<ChatEntry>()
        val knownMessageIds = history().mapNotNull(ChatEntry::messageId).toMutableSet()
        var batchCount = 0
        do {
            val inbox = authenticatedRequest("GET", "$server/v1/messages", null) as JSONArray
            for (index in 0 until inbox.length()) {
                val item = inbox.getJSONObject(index)
                val messageId = item.getString("message_id")
                if (!knownMessageIds.add(messageId)) {
                    runCatching { acknowledgeMessage(messageId) }
                    continue
                }
                // The inbox is ordered oldest-first and every row without an ACK
                // is returned again on the next poll. One invalid envelope must
                // not permanently block every newer message, so a failure is
                // acknowledged and skipped instead of aborting the whole batch.
                runCatching { received += readInboxItem(item, messageId, knownMessageIds) }
                    .onFailure { knownMessageIds.remove(messageId) }
                runCatching { acknowledgeMessage(messageId) }
            }
            batchCount++
            if (inbox.length() < SERVER_INBOX_PAGE_SIZE) break
        } while (batchCount < MAX_INBOX_BATCHES)
        return received
    }

    private fun acknowledgeMessage(messageId: String) {
        authenticatedRequest("POST", "$server/v1/messages/$messageId", null)
    }

    private fun readInboxItem(
        item: JSONObject,
        messageId: String,
        knownMessageIds: MutableSet<String>,
    ): List<ChatEntry> {
        val raw = item.getString("ciphertext").base64UrlDecode()
        val sender = item.getString("sender_nickname")
        val remote = SignalProtocolAddress(sender, item.optInt("sender_device_number", 1))
        val local = SignalProtocolAddress(nickname, deviceNumber)
        val plain =
            try {
                require(raw.isNotEmpty()) { "Получен пустой encrypted envelope" }
                when (raw.first().toInt()) {
                    CiphertextMessage.PREKEY_TYPE -> SessionCipher(store, local, remote).decrypt(PreKeySignalMessage(raw.drop(1).toByteArray()))
                    CiphertextMessage.WHISPER_TYPE -> SessionCipher(store, local, remote).decrypt(SignalMessage(raw.drop(1).toByteArray()))
                    else -> error("Неизвестный тип сообщения")
                }
            } finally {
                raw.fill(0)
            }
        // The ratchet advanced even when the decrypted payload turns out to be
        // unusable: persist before parsing so a retry cannot desynchronize it.
        persist()
        try {
            val plaintext = plain.decodeToString()
            val envelope = runCatching { JSONObject(plaintext) }.getOrNull()
            val entries = when (envelope?.optString("type")) {
                HiddiEnvelope.HISTORY_SYNC -> historyBatchEntries(envelope, messageId, knownMessageIds)
                HiddiEnvelope.SELF_SYNC -> listOf(
                    inboxEntry(
                        peer = envelope.optString("peer").ifBlank { sender },
                        text = envelope.optString("text"),
                        outgoing = true,
                        createdAt = wireTimestampMillis(item.getString("created_at")),
                        messageId = messageId,
                    ),
                )
                else -> listOf(
                    inboxEntry(
                        peer = sender,
                        // A newer peer may use an envelope this build cannot read.
                        // Showing a placeholder beats rendering raw JSON as if it
                        // were the message the sender typed.
                        text = if (HiddiEnvelope.isUnsupported(envelope)) {
                            HiddiEnvelope.UNSUPPORTED_TEXT
                        } else {
                            plaintext
                        },
                        outgoing = false,
                        createdAt = wireTimestampMillis(item.getString("created_at")),
                        messageId = messageId,
                    ),
                )
            }
            entries.forEach(::appendHistory)
            persist()
            return entries
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Expands an encrypted history snapshot from another own device. Entries are
     * deduplicated by their original message id, so a snapshot overlapping the
     * local history does not create doubles.
     */
    private fun historyBatchEntries(
        envelope: JSONObject,
        messageId: String,
        knownMessageIds: MutableSet<String>,
    ): List<ChatEntry> {
        val entries = envelope.optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { index ->
            runCatching {
                val entry = entries.getJSONObject(index)
                val sourceId =
                    entry.optString("source_message_id").takeIf(String::isNotBlank)
                        ?: "$messageId:sync:$index"
                if (!knownMessageIds.add(sourceId)) return@runCatching null
                inboxEntry(
                    peer = entry.getString("peer"),
                    text = entry.getString("text"),
                    outgoing = entry.getBoolean("outgoing"),
                    createdAt = wireTimestampMillis(entry.getString("created_at")),
                    messageId = sourceId,
                    deliveryStatus = entry.optString("delivery_status", "delivered"),
                )
            }.getOrNull()
        }
    }

    private fun inboxEntry(
        peer: String,
        text: String,
        outgoing: Boolean,
        createdAt: Long,
        messageId: String,
        deliveryStatus: String = "delivered",
    ): ChatEntry {
        val attachment = DesktopAttachmentStore.parseEnvelope(text)
        attachment?.let { runCatching { cacheAttachment(it) } }
        return ChatEntry(
            peer = peer,
            text = attachment?.displayText() ?: text,
            outgoing = outgoing,
            createdAt = createdAt,
            messageId = messageId,
            deliveryStatus = deliveryStatus,
            attachment = attachment,
        )
    }

    @Synchronized
    fun deleteMessage(messageId: String, forEveryone: Boolean) {
        if (forEveryone) {
            authenticatedRequest(
                "DELETE",
                "$server/v1/messages/${URLEncoder.encode(messageId, Charsets.UTF_8)}?for_everyone=true",
                null,
            )
        }
        if (removeMessageHistory(messageId)) persist()
    }

    @Synchronized
    fun safetyNumber(peer: String): SafetyNumberInfo {
        val normalized = normalizePeer(peer)
        val remote =
            authenticatedRequest(
                "GET",
                "$server/v1/users/${URLEncoder.encode(normalized, Charsets.UTF_8)}",
                null,
            ) as JSONObject
        val participants =
            listOf(
                "$nickname\u0000${store.identityKeyPair.publicKey.serialize().base64Url()}",
                "${remote.getString("nickname")}\u0000${remote.getString("identity_public_key")}",
            ).sorted()
        val digest =
            MessageDigest.getInstance("SHA-256").digest(
                ("hiddi-safety-number-v1\u0000" + participants.joinToString("\u0000"))
                    .encodeToByteArray(),
            )
        val value = digest.take(30).joinToString("") { "%02x".format(it) }
            .chunked(5)
            .joinToString(" ")
        val trusted =
            state.optJSONObject("trusted_safety_numbers")
                ?.optString(normalized)
                ?.takeIf(String::isNotBlank) == value
        return SafetyNumberInfo(value, trusted)
    }

    @Synchronized
    fun trustSafetyNumber(peer: String, value: String) {
        val normalized = normalizePeer(peer)
        val trusted = state.optJSONObject("trusted_safety_numbers")
            ?: JSONObject().also { state.put("trusted_safety_numbers", it) }
        trusted.put(normalized, value)
        persist()
    }

    @Synchronized
    fun clearConversation(peer: String, forBoth: Boolean) {
        val normalized = normalizePeer(peer)
        val removed = history().filter { it.peer == normalized }
        if (forBoth) {
            val encoded = URLEncoder.encode(normalized, Charsets.UTF_8)
            authenticatedRequest("DELETE", "$server/v1/conversations/$encoded", null)
            removed.filter(ChatEntry::outgoing)
                .flatMap { it.attachment.descriptors() }
                .forEach { descriptor ->
                    runCatching { deleteRemoteAttachment(descriptor.attachmentId) }
                }
        }
        removed.flatMap { it.attachment.descriptors() }
            .forEach { descriptor -> runCatching { attachmentStore.delete(descriptor.attachmentId) } }
        removeConversationHistory(normalized)
        persist()
    }

    @Synchronized
    fun blockedUsers(): Set<String> {
        val entries = authenticatedRequest("GET", "$server/v1/blocks", null) as JSONArray
        return (0 until entries.length())
            .map { entries.getJSONObject(it).getString("nickname") }
            .toSet()
    }

    @Synchronized
    fun setBlocked(peer: String, blocked: Boolean) {
        val encoded = URLEncoder.encode(normalizePeer(peer), Charsets.UTF_8)
        authenticatedRequest(if (blocked) "PUT" else "DELETE", "$server/v1/blocks/$encoded", null)
    }

    @Synchronized
    fun send(recipient: String, plaintext: String): ChatEntry {
        require(plaintext.isNotBlank()) { "Сообщение пустое" }
        return sendPayload(recipient, plaintext, plaintext, null)
    }

    @Synchronized
    fun sendImage(recipient: String, file: java.io.File): ChatEntry {
        val peer = normalizePeer(recipient)
        val sanitized = sanitizeDesktopImage(file)
        val uploadedIds = mutableListOf<String>()
        var full: PreparedAttachment? = null
        var preview: PreparedAttachment? = null
        var messageSent = false
        try {
            full =
                try {
                    attachmentStore.encrypt(
                        sanitized.full,
                        DesktopAttachmentStore.IMAGE_KIND,
                        DesktopAttachmentStore.JPEG_MIME,
                    )
                } finally {
                    sanitized.full.fill(0)
                }
            preview =
                try {
                    attachmentStore.encrypt(
                        sanitized.preview,
                        DesktopAttachmentStore.IMAGE_KIND,
                        DesktopAttachmentStore.JPEG_MIME,
                    )
                } finally {
                    sanitized.preview.fill(0)
                }
            val preparedFull = checkNotNull(full)
            val preparedPreview = checkNotNull(preview)
            val previewId = uploadAttachment(peer, preparedPreview.ciphertext)
            uploadedIds += previewId
            val fullId = uploadAttachment(peer, preparedFull.ciphertext)
            uploadedIds += fullId
            val descriptor =
                preparedFull.descriptor(fullId).copy(
                    preview = preparedPreview.descriptor(previewId),
                )
            attachmentStore.saveCiphertext(previewId, preparedPreview.ciphertext)
            attachmentStore.saveCiphertext(fullId, preparedFull.ciphertext)
            return sendPayload(
                recipient = peer,
                plaintext = DesktopAttachmentStore.envelope(descriptor),
                displayText = descriptor.displayText(),
                attachment = descriptor,
            ).also { messageSent = true }
        } finally {
            sanitized.full.fill(0)
            sanitized.preview.fill(0)
            full?.ciphertext?.fill(0)
            preview?.ciphertext?.fill(0)
            if (!messageSent) {
                uploadedIds.forEach { id ->
                    runCatching { deleteRemoteAttachment(id) }
                    runCatching { attachmentStore.delete(id) }
                }
            }
        }
    }

    @Synchronized
    fun sendVoice(
        recipient: String,
        pcm: ByteArray,
        durationMs: Long,
    ): ChatEntry {
        val peer = normalizePeer(recipient)
        var attachmentId: String? = null
        var messageSent = false
        val prepared =
            try {
                attachmentStore.encrypt(
                    pcm,
                    DesktopAttachmentStore.VOICE_KIND,
                    DesktopAttachmentStore.AUDIO_MIME,
                    durationMs,
                )
            } finally {
                pcm.fill(0)
            }
        try {
            val uploadedId = uploadAttachment(peer, prepared.ciphertext)
            attachmentId = uploadedId
            val descriptor = prepared.descriptor(uploadedId)
            attachmentStore.saveCiphertext(uploadedId, prepared.ciphertext)
            return sendPayload(
                recipient = peer,
                plaintext = DesktopAttachmentStore.envelope(descriptor),
                displayText = descriptor.displayText(),
                attachment = descriptor,
            ).also { messageSent = true }
        } finally {
            prepared.ciphertext.fill(0)
            if (!messageSent) {
                attachmentId?.let { id ->
                    runCatching { deleteRemoteAttachment(id) }
                    runCatching { attachmentStore.delete(id) }
                }
            }
        }
    }

    @Synchronized
    fun attachmentBytes(descriptor: AttachmentDescriptor): ByteArray {
        DesktopAttachmentStore.validateDescriptor(descriptor)
        if (!attachmentStore.exists(descriptor.attachmentId)) {
            val ciphertext = downloadAttachment(descriptor.attachmentId)
            try {
                attachmentStore.saveCiphertext(descriptor.attachmentId, ciphertext)
            } finally {
                ciphertext.fill(0)
            }
        }
        return attachmentStore.decrypt(descriptor)
    }

    private fun sendPayload(
        recipient: String,
        plaintext: String,
        displayText: String,
        attachment: AttachmentDescriptor?,
    ): ChatEntry {
        val peer = recipient.trim().removePrefix("@").lowercase()
        require(peer.matches(Regex("[a-z0-9_]{3,32}"))) { "Некорректный никнейм" }
        require(plaintext.encodeToByteArray().size <= 32_000) { "Сообщение слишком длинное" }

        val local = SignalProtocolAddress(nickname, deviceNumber)
        val bundleJson =
            authenticatedRequest(
                "GET",
                "$server/v1/users/$peer/prekey-bundles",
                null,
            ) as JSONArray
        val deliveries = JSONArray()
        for (index in 0 until bundleJson.length()) {
            val value = bundleJson.getJSONObject(index)
            val remote = SignalProtocolAddress(peer, value.optInt("device_number", 1))
            if (!store.containsSession(remote)) {
                SessionBuilder(store, remote, local).process(bundle(value))
            }
            val encrypted = SessionCipher(store, local, remote).encrypt(plaintext.encodeToByteArray())
            val envelope = byteArrayOf(encrypted.type.toByte()) + encrypted.serialize()
            deliveries.put(JSONObject().put("device_number", remote.deviceId).put("ciphertext", envelope.base64Url()))
            envelope.fill(0)
        }
        persist()
        val result =
            authenticatedRequest(
                "POST",
                "$server/v1/messages",
                JSONObject()
                    .put("recipient_nickname", peer)
                    .put("device_ciphertexts", deliveries),
            ) as JSONObject
        if (peer != nickname) {
            val ownBundles = authenticatedRequest("GET", "$server/v1/users/$nickname/prekey-bundles", null) as JSONArray
            val ownDeliveries = JSONArray()
            val syncPayload = JSONObject().put("type", HiddiEnvelope.SELF_SYNC).put("peer", peer).put("text", plaintext).toString()
            for (index in 0 until ownBundles.length()) {
                val value = ownBundles.getJSONObject(index)
                val remote = SignalProtocolAddress(nickname, value.optInt("device_number", 1))
                if (remote.deviceId == deviceNumber) continue
                if (!store.containsSession(remote)) SessionBuilder(store, remote, local).process(bundle(value))
                val encrypted = SessionCipher(store, local, remote).encrypt(syncPayload.encodeToByteArray())
                val envelope = byteArrayOf(encrypted.type.toByte()) + encrypted.serialize()
                ownDeliveries.put(JSONObject().put("device_number", remote.deviceId).put("ciphertext", envelope.base64Url()))
                envelope.fill(0)
            }
            if (ownDeliveries.length() > 0) {
                authenticatedRequest("POST", "$server/v1/messages", JSONObject().put("recipient_nickname", nickname).put("device_ciphertexts", ownDeliveries))
                persist()
            }
        }
        val entry =
            ChatEntry(
                peer = peer,
                text = displayText,
                outgoing = true,
                messageId = result.getString("message_id"),
                deliveryStatus = "sent",
                attachment = attachment,
            )
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

    @Synchronized
    fun updateDeliveryStatuses(peer: String): Map<String, String> {
        val normalized = normalizePeer(peer)
        val statuses =
            authenticatedRequest(
                "GET",
                "$server/v1/messages/statuses/${URLEncoder.encode(normalized, Charsets.UTF_8)}",
                null,
            ) as JSONArray
        val result = buildMap {
            for (index in 0 until statuses.length()) {
                val item = statuses.getJSONObject(index)
                put(
                    item.getString("message_id"),
                    when {
                        item.getBoolean("read") -> "read"
                        item.getBoolean("delivered") -> "delivered"
                        else -> "sent"
                    },
                )
            }
        }
        val history = historyJson()
        var changed = false
        for (index in 0 until history.length()) {
            val item = history.getJSONObject(index)
            val next = result[item.optString("message_id")] ?: continue
            if (item.optString("delivery_status", "sent") != next) {
                item.put("delivery_status", next)
                changed = true
            }
        }
        if (changed) persist()
        return result
    }

    /**
     * Marks delivered messages from an open conversation as read. The sender
     * only learns the read state, never which device displayed the message.
     */
    @Synchronized
    fun markConversationRead(peer: String) {
        val encoded = URLEncoder.encode(normalizePeer(peer), Charsets.UTF_8)
        authenticatedRequest("POST", "$server/v1/messages/read/$encoded", null)
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
                .put("delivery_status", entry.deliveryStatus)
                .apply {
                    entry.attachment?.let { put("attachment", it.toJson()) }
                },
        )
    }

    private fun cacheAttachment(descriptor: AttachmentDescriptor) {
        descriptor.descriptors().forEach {
            if (!attachmentStore.exists(it.attachmentId)) {
                val ciphertext = downloadAttachment(it.attachmentId)
                try {
                    attachmentStore.saveCiphertext(it.attachmentId, ciphertext)
                } finally {
                    ciphertext.fill(0)
                }
            }
        }
    }

    private fun uploadAttachment(recipient: String, ciphertext: ByteArray): String =
        (
            authenticatedRequest(
                "POST",
                "$server/v1/attachments",
                JSONObject()
                    .put("recipient_nickname", normalizePeer(recipient))
                    .put("ciphertext", ciphertext.base64Url()),
            ) as JSONObject
        ).getString("attachment_id")

    private fun downloadAttachment(attachmentId: String): ByteArray =
        (
            authenticatedRequest(
                "GET",
                "$server/v1/attachments/$attachmentId",
                null,
            ) as JSONObject
        ).getString("ciphertext").base64UrlDecode()

    private fun deleteRemoteAttachment(attachmentId: String) {
        authenticatedRequest("DELETE", "$server/v1/attachments/$attachmentId", null)
    }

    private fun historyJson(): JSONArray = state.optJSONArray("chat_history") ?: JSONArray().also { state.put("chat_history", it) }

    private fun historySyncJson(entry: ChatEntry): JSONObject =
        JSONObject()
            .put("peer", entry.peer)
            .put("text", entry.attachment?.let(DesktopAttachmentStore::envelope) ?: entry.text)
            .put("outgoing", entry.outgoing)
            .put("created_at", Instant.ofEpochMilli(entry.createdAt).toString())
            .put("delivery_status", entry.deliveryStatus)
            .apply {
                entry.messageId?.let { put("source_message_id", it) }
            }

    private companion object {
        const val SERVER_INBOX_PAGE_SIZE = 100
        const val MAX_INBOX_BATCHES = 20
        const val HISTORY_SYNC_BATCH_SIZE = 10
    }

    private fun synchronizeConversationDeletions() {
        val deletions =
            authenticatedRequest("GET", "$server/v1/conversations/deletions", null) as JSONArray
        var changed = false
        for (index in 0 until deletions.length()) {
            val deletion = deletions.getJSONObject(index)
            changed = removeConversationHistory(deletion.getString("peer_nickname")) || changed
            authenticatedRequest(
                "POST",
                "$server/v1/conversations/deletions/${deletion.getLong("deletion_id")}",
                null,
            )
        }
        if (changed) persist()
    }

    private fun synchronizeMessageDeletions() {
        val deletions =
            authenticatedRequest("GET", "$server/v1/messages/deletions", null) as JSONArray
        var changed = false
        for (index in 0 until deletions.length()) {
            val deletion = deletions.getJSONObject(index)
            changed = removeMessageHistory(deletion.getString("message_id")) || changed
            authenticatedRequest(
                "POST",
                "$server/v1/messages/deletions/${deletion.getString("deletion_id")}",
                null,
            )
        }
        if (changed) persist()
    }

    private fun removeMessageHistory(messageId: String): Boolean {
        val history = historyJson()
        for (index in history.length() - 1 downTo 0) {
            val item = history.getJSONObject(index)
            if (item.optString("message_id") == messageId) {
                item.optJSONObject("attachment")
                    ?.let { runCatching { it.toAttachmentDescriptor() }.getOrNull() }
                    ?.descriptors()
                    ?.forEach { attachmentStore.delete(it.attachmentId) }
                history.remove(index)
                return true
            }
        }
        return false
    }

    private fun removeConversationHistory(peer: String): Boolean {
        val normalized = normalizePeer(peer)
        val history = historyJson()
        var changed = false
        for (index in history.length() - 1 downTo 0) {
            if (history.getJSONObject(index).optString("peer") == normalized) {
                history.remove(index)
                changed = true
            }
        }
        return changed
    }

    private fun normalizePeer(peer: String): String =
        peer.trim().removePrefix("@").lowercase().also {
            require(it.matches(Regex("[a-z0-9_]{3,32}"))) { "Некорректный никнейм" }
        }

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

/**
 * Envelope types carried inside a personal-chat plaintext. A plaintext without
 * one of these is the message itself, which is how every older client speaks.
 */
internal object HiddiEnvelope {
    /** Own-device journal entry for a single message this account sent. */
    const val SELF_SYNC = "hiddi.sync.v1"

    /** Own-device history snapshot delivered to a newly linked device. */
    const val HISTORY_SYNC = "hiddi.sync.batch.v1"

    /** Shown instead of raw JSON when a peer uses a newer envelope. */
    const val UNSUPPORTED_TEXT = "Сообщение не поддерживается этой версией"

    private const val PREFIX = "hiddi."

    private val KNOWN =
        setOf(SELF_SYNC, HISTORY_SYNC, DesktopAttachmentStore.ATTACHMENT_TYPE)

    /**
     * True when the payload announces a Hiddi envelope this build cannot
     * interpret — a newer peer. Such a message is shown as a placeholder rather
     * than as the raw JSON the sender never typed.
     */
    fun isUnsupported(payload: JSONObject?): Boolean {
        val type = payload?.optString("type")?.takeIf(String::isNotBlank) ?: return false
        return type.startsWith(PREFIX) && type !in KNOWN
    }
}

private fun AttachmentDescriptor?.descriptors(): List<AttachmentDescriptor> =
    this?.let { listOfNotNull(it, it.preview) }.orEmpty()

private fun AttachmentDescriptor.displayText(): String =
    when (kind) {
        DesktopAttachmentStore.IMAGE_KIND -> "📷 Изображение"
        DesktopAttachmentStore.VOICE_KIND -> "🎙 Голосовое сообщение"
        else -> "Вложение"
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
