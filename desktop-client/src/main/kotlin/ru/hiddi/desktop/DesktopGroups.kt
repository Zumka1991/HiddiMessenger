package ru.hiddi.desktop

import org.json.JSONArray
import org.json.JSONObject
import ru.hiddi.messenger.security.NativeMlsBridge
import ru.hiddi.messenger.security.PendingMlsMembershipKind
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

data class DesktopGroupMessage(
    val messageId: String?,
    val sender: String,
    val text: String,
    val outgoing: Boolean,
    val createdAt: Long,
)

data class DesktopGroup(
    val id: String,
    val name: String,
    val owner: String,
    val members: List<String>,
    val messages: List<DesktopGroupMessage>,
)

/**
 * Persistent MLS group directory and reliable opaque outbox.
 *
 * All JSON below is encrypted by [Vault]; OpenMLS private state stays inside
 * the encrypted SQLite provider owned by the Rust core.
 */
internal class DesktopGroupManager(
    private val session: HiddiSession,
) {
    fun initialize() {
        require(NativeMlsBridge.isAvailable) {
            "OpenMLS-модуль не найден. Соберите group-mls-core в release-режиме."
        }
        session.ensureGroupState()
        val key = session.groupStorageKey()
        try {
            require(
                NativeMlsBridge.initialize(
                    key,
                    session.storageDirectory.resolve("group-mls.sqlite"),
                ),
            ) { "Не удалось открыть зашифрованное хранилище OpenMLS" }
        } finally {
            key.fill(0)
        }
        handoffNativeJournal()
        flushOutbox()
        publishKeyPackages()
        synchronize()
    }

    fun groups(): List<DesktopGroup> =
        session.groupStateArray().objects().map(::decodeGroup)

    fun create(name: String, invitedNickname: String): DesktopGroup {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty() && normalizedName.length <= 80) {
            "Название группы: от 1 до 80 символов"
        }
        val invited = normalizeNickname(invitedNickname)
        require(invited != session.nickname) { "Нельзя пригласить самого себя" }
        val encoded = URLEncoder.encode(invited, Charsets.UTF_8)
        val packageResponse =
            session.groupRequest(
                "GET",
                "${session.server}/v1/users/$encoded/mls-key-package",
                null,
            ) as JSONObject
        val groupId = requireNotNull(
            NativeMlsBridge.createLocalGroup(session.deviceId),
        ) { "OpenMLS не создал локальную группу" }
        var memberAdded = false
        try {
            val context =
                JSONObject()
                    .put("version", 1)
                    .put("type", CONTEXT_CREATE_GROUP)
                    .put("owner_nickname", session.nickname)
                    .put("target_nickname", invited)
                    .put("recipients", JSONArray())
                    .put("members", JSONArray(listOf(session.nickname, invited)))
                    .put("group_name", normalizedName)
                    .toString()
                    .encodeToByteArray()
            requireNotNull(
                NativeMlsBridge.addMember(
                    groupId,
                    packageResponse.getString("key_package").base64UrlDecode(),
                    UUID.randomUUID().toString(),
                    context,
                ),
            ) { "OpenMLS отклонил KeyPackage @$invited" }
            memberAdded = true
            handoffNativeJournal()
            findGroup(groupId)?.put("name", normalizedName)
            session.persistGroupState()
            flushOutbox()
            sendMetadata(groupId, normalizedName, listOf(invited))
            flushOutbox()
            return requireNotNull(groups().firstOrNull { it.id == groupId.base64Url() })
        } catch (error: Throwable) {
            if (!memberAdded) NativeMlsBridge.deleteLocalGroup(groupId)
            throw error
        }
    }

    fun sendText(groupId: String, text: String): DesktopGroupMessage {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Введите сообщение" }
        val group = findGroup(groupId.base64UrlDecode()) ?: error("MLS-группа не найдена")
        val id = randomMessageId()
        val plaintext =
            encodePayload(
                JSONObject()
                    .put("type", "text")
                    .put("message_id", id)
                    .put("text", trimmed),
            )
        val envelope = try {
            requireNotNull(
                NativeMlsBridge.createApplicationMessage(groupId.base64UrlDecode(), plaintext),
            ) { "OpenMLS не зашифровал сообщение" }
        } finally {
            plaintext.fill(0)
        }
        enqueueEvent(
            groupId.base64UrlDecode(),
            KIND_APPLICATION,
            group.getJSONArray("members").strings().filterNot { it == session.nickname },
            envelope,
            clientEventId = id,
        )
        val message =
            JSONObject()
                .put("message_id", id)
                .put("sender", session.nickname)
                .put("text", trimmed)
                .put("outgoing", true)
                .put("created_at", System.currentTimeMillis())
        group.getJSONArray("messages").put(message)
        session.persistGroupState()
        flushOutbox()
        return decodeMessage(message)
    }

    fun synchronize() {
        processDeletions()
        handoffNativeJournal()
        flushOutbox()
        val events =
            session.groupRequest("GET", "${session.server}/v1/groups/events", null) as JSONArray
        events.objects().forEach { event ->
            val eventId = event.getString("event_id")
            val groupId = event.getString("group_id").base64UrlDecode()
            val sender = event.getString("sender_nickname")
            val envelope = event.getString("envelope").base64UrlDecode()
            when (event.getInt("kind")) {
                KIND_WELCOME -> {
                    val joined = requireNotNull(NativeMlsBridge.processWelcome(envelope)) {
                        "OpenMLS отклонил приглашение от @$sender"
                    }
                    require(joined.contentEquals(groupId)) { "MLS Welcome адресован другой группе" }
                    val details = groupDetails(groupId)
                    upsertGroup(groupId, details.members, details.owner)
                }
                KIND_COMMIT -> {
                    require(NativeMlsBridge.processCommit(groupId, envelope)) {
                        "OpenMLS отклонил изменение состава группы"
                    }
                    if (event.optBoolean("removes_recipient")) {
                        NativeMlsBridge.deleteLocalGroup(groupId)
                        removeGroup(groupId)
                    } else {
                        val details = groupDetails(groupId)
                        upsertGroup(groupId, details.members, details.owner)
                    }
                }
                KIND_APPLICATION -> processApplication(
                    groupId,
                    eventId,
                    sender,
                    event.optString("created_at"),
                    envelope,
                )
                else -> error("Неизвестный MLS event kind")
            }
            session.persistGroupState()
            session.groupRequest(
                "POST",
                "${session.server}/v1/groups/events/$eventId",
                null,
            )
        }
    }

    private fun processApplication(
        groupId: ByteArray,
        eventId: String,
        sender: String,
        createdAt: String,
        envelope: ByteArray,
    ) {
        val plaintext = requireNotNull(
            NativeMlsBridge.processApplicationMessage(groupId, envelope),
        ) { "OpenMLS отклонил групповое сообщение" }
        val decoded = try {
            plaintext.decodeToString()
        } finally {
            plaintext.fill(0)
        }
        val group = findGroup(groupId) ?: error("Получено сообщение неизвестной MLS-группы")
        if (!decoded.startsWith(PAYLOAD_PREFIX)) {
            appendIncoming(group, eventId, null, sender, decoded, createdAt)
            return
        }
        val payload = JSONObject(decoded.removePrefix(PAYLOAD_PREFIX))
        require(payload.optInt("version") == 1)
        val messageId = payload.getString("message_id")
        when (payload.getString("type")) {
            "text" ->
                appendIncoming(
                    group,
                    eventId,
                    messageId,
                    sender,
                    payload.getString("text"),
                    createdAt,
                )
            "group_metadata" ->
                group.put(
                    "name",
                    payload.getString("name").also {
                        require(it.isNotBlank() && it.length <= 80)
                    },
                )
            "attachment" -> {
                val attachment = JSONObject(payload.getString("attachment"))
                val label = when (attachment.getString("kind")) {
                    DesktopAttachmentStore.IMAGE_KIND -> "📷 Изображение"
                    DesktopAttachmentStore.VOICE_KIND -> "🎙 Голосовое сообщение"
                    else -> "Вложение"
                }
                appendIncoming(group, eventId, messageId, sender, label, createdAt)
            }
            "delete" -> {
                val messages = group.getJSONArray("messages")
                for (index in messages.length() - 1 downTo 0) {
                    val stored = messages.getJSONObject(index)
                    if (
                        stored.optString("message_id") == messageId &&
                        stored.optString("sender") == sender
                    ) {
                        messages.remove(index)
                    }
                }
            }
            else -> error("Неизвестный тип group payload")
        }
    }

    private fun appendIncoming(
        group: JSONObject,
        eventId: String,
        messageId: String?,
        sender: String,
        text: String,
        createdAt: String,
    ) {
        val messages = group.getJSONArray("messages")
        if (messages.objects().any {
                it.optString("event_id") == eventId ||
                    (messageId != null && it.optString("message_id") == messageId)
            }
        ) {
            return
        }
        messages.put(
            JSONObject()
                .put("event_id", eventId)
                .put("message_id", messageId)
                .put("sender", sender)
                .put("text", text)
                .put("outgoing", false)
                .put(
                    "created_at",
                    runCatching { Instant.parse(createdAt).toEpochMilli() }
                        .getOrDefault(System.currentTimeMillis()),
                ),
        )
    }

    private fun sendMetadata(groupId: ByteArray, name: String, recipients: List<String>) {
        val messageId = randomMessageId()
        val plaintext =
            encodePayload(
                JSONObject()
                    .put("type", "group_metadata")
                    .put("message_id", messageId)
                    .put("name", name),
            )
        val envelope = try {
            requireNotNull(NativeMlsBridge.createApplicationMessage(groupId, plaintext))
        } finally {
            plaintext.fill(0)
        }
        enqueueEvent(groupId, KIND_APPLICATION, recipients, envelope, messageId)
    }

    private fun publishKeyPackages() {
        var available = 0
        while (available < KEY_PACKAGE_RESERVE) {
            val keyPackage = requireNotNull(
                NativeMlsBridge.createKeyPackage(session.deviceId),
            ) { "OpenMLS не создал KeyPackage" }
            available =
                (
                    session.groupRequest(
                        "PUT",
                        "${session.server}/v1/groups/key-package",
                        JSONObject().put("key_package", keyPackage.base64Url()),
                    ) as JSONObject
                ).getInt("available")
        }
    }

    private fun handoffNativeJournal() {
        NativeMlsBridge.pendingMembershipOperations().forEach { operation ->
            val context = JSONObject(operation.context.decodeToString())
            require(context.getInt("version") == 1)
            val owner = context.getString("owner_nickname")
            val target = context.getString("target_nickname")
            val recipients = context.getJSONArray("recipients").strings()
            val members = context.getJSONArray("members").strings()
            if (context.getString("type") == CONTEXT_CREATE_GROUP && findGroup(operation.groupId) == null) {
                session.groupStateArray().put(
                    JSONObject()
                        .put("group_id", operation.groupId.base64Url())
                        .put("name", context.optString("group_name", "Защищённая группа"))
                        .put("owner_nickname", owner)
                        .put("members", JSONArray(members))
                        .put("messages", JSONArray())
                        .put("registered", false),
                )
            }
            require(operation.kind == PendingMlsMembershipKind.ADD_MEMBER) {
                "Desktop пока не создаёт Remove Commit"
            }
            if (recipients.isNotEmpty()) {
                enqueueEvent(
                    operation.groupId,
                    KIND_COMMIT,
                    recipients,
                    operation.commitEnvelope,
                )
            }
            enqueueEvent(
                operation.groupId,
                KIND_WELCOME,
                listOf(target),
                requireNotNull(operation.welcomeEnvelope),
            )
            session.persistGroupState()
            require(NativeMlsBridge.acknowledgeMembershipOperation(operation.operationId)) {
                "Не удалось подтвердить перенос MLS-операции"
            }
        }
    }

    private fun flushOutbox() {
        session.groupStateArray().objects().forEach { group ->
            if (!group.optBoolean("registered")) {
                val members = JSONArray()
                group.getJSONArray("members").strings()
                    .filterNot { it == session.nickname }
                    .forEach {
                        members.put(JSONObject().put("nickname", it).put("role", "member"))
                    }
                session.groupRequest(
                    "POST",
                    "${session.server}/v1/groups",
                    JSONObject()
                        .put("group_id", group.getString("group_id"))
                        .put("members", members),
                )
                group.put("registered", true)
                session.persistGroupState()
            }
        }
        val pending = session.groupPendingEvents()
        while (pending.length() > 0) {
            val event = pending.getJSONObject(0)
            session.groupRequest(
                "POST",
                "${session.server}/v1/groups/${event.getString("group_id")}/events",
                JSONObject()
                    .put("client_event_id", event.getString("id"))
                    .put("kind", event.getInt("kind"))
                    .put("recipient_nicknames", event.getJSONArray("recipients"))
                    .put("envelope", event.getString("envelope")),
            )
            pending.remove(0)
            session.persistGroupState()
        }
    }

    private fun enqueueEvent(
        groupId: ByteArray,
        kind: Int,
        recipients: List<String>,
        envelope: ByteArray,
        clientEventId: String? = null,
    ) {
        val id = clientEventId ?: MessageDigest.getInstance("SHA-256")
            .digest(groupId + byteArrayOf(kind.toByte()) + envelope)
            .base64Url()
        val pending = session.groupPendingEvents()
        if (pending.objects().none { it.getString("id") == id }) {
            pending.put(
                JSONObject()
                    .put("id", id)
                    .put("group_id", groupId.base64Url())
                    .put("kind", kind)
                    .put("recipients", JSONArray(recipients))
                    .put("envelope", envelope.base64Url()),
            )
        }
    }

    private fun processDeletions() {
        val deletions =
            session.groupRequest(
                "GET",
                "${session.server}/v1/groups/deletions",
                null,
            ) as JSONArray
        deletions.objects().forEach { deletion ->
            val groupId = deletion.getString("group_id").base64UrlDecode()
            NativeMlsBridge.deleteLocalGroup(groupId)
            removeGroup(groupId)
            session.persistGroupState()
            session.groupRequest(
                "POST",
                "${session.server}/v1/groups/deletions/${deletion.getString("deletion_id")}",
                null,
            )
        }
    }

    private fun groupDetails(groupId: ByteArray): GroupDetails {
        val response =
            session.groupRequest(
                "GET",
                "${session.server}/v1/groups/${groupId.base64Url()}",
                null,
            ) as JSONObject
        return GroupDetails(
            owner = response.getString("owner_nickname"),
            members = response.getJSONArray("members").objects().map {
                it.getString("nickname")
            },
        )
    }

    private fun upsertGroup(groupId: ByteArray, members: List<String>, owner: String): JSONObject {
        findGroup(groupId)?.let {
            it.put("owner_nickname", owner)
            it.put("members", JSONArray(members.distinct()))
            return it
        }
        return JSONObject()
            .put("group_id", groupId.base64Url())
            .put("name", "Защищённая группа")
            .put("owner_nickname", owner)
            .put("members", JSONArray(members.distinct()))
            .put("messages", JSONArray())
            .put("registered", true)
            .also(session.groupStateArray()::put)
    }

    private fun removeGroup(groupId: ByteArray) {
        val encoded = groupId.base64Url()
        val groups = session.groupStateArray()
        for (index in groups.length() - 1 downTo 0) {
            if (groups.getJSONObject(index).getString("group_id") == encoded) groups.remove(index)
        }
    }

    private fun findGroup(groupId: ByteArray): JSONObject? {
        val encoded = groupId.base64Url()
        return session.groupStateArray().objects()
            .firstOrNull { it.getString("group_id") == encoded }
    }

    private fun decodeGroup(json: JSONObject): DesktopGroup =
        DesktopGroup(
            id = json.getString("group_id"),
            name = json.optString("name", "Защищённая группа"),
            owner = json.getString("owner_nickname"),
            members = json.getJSONArray("members").strings(),
            messages = json.getJSONArray("messages").objects().map(::decodeMessage),
        )

    private fun decodeMessage(json: JSONObject): DesktopGroupMessage =
        DesktopGroupMessage(
            messageId = json.optString("message_id").takeIf(String::isNotBlank),
            sender = json.getString("sender"),
            text = json.getString("text"),
            outgoing = json.optBoolean("outgoing"),
            createdAt = json.optLong("created_at", System.currentTimeMillis()),
        )

    private fun encodePayload(payload: JSONObject): ByteArray =
        (PAYLOAD_PREFIX + payload.put("version", 1).toString()).encodeToByteArray()

    private fun randomMessageId(): String =
        ByteArray(16).also(SecureRandom()::nextBytes).base64Url()

    private fun normalizeNickname(value: String): String =
        value.trim().removePrefix("@").lowercase().also {
            require(it.matches(Regex("[a-z0-9_]{3,32}"))) { "Некорректный никнейм" }
        }

    private data class GroupDetails(val owner: String, val members: List<String>)

    private companion object {
        const val KIND_WELCOME = 1
        const val KIND_COMMIT = 2
        const val KIND_APPLICATION = 3
        const val CONTEXT_CREATE_GROUP = "create_group"
        const val KEY_PACKAGE_RESERVE = 10
        const val PAYLOAD_PREFIX = "HIDDI_GROUP_V1:"
    }
}

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).map(::getJSONObject)

private fun JSONArray.strings(): List<String> =
    (0 until length()).map(::getString)
