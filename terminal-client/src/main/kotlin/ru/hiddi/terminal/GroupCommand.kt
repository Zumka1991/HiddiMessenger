package ru.hiddi.terminal

import org.json.JSONArray
import org.json.JSONObject
import ru.hiddi.messenger.security.NativeMlsBridge
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Interactive desktop test peer for the same OpenMLS groups as Android.
 *
 * The MLS database is encrypted by a random 64-byte key kept inside the
 * Argon2id/AES-GCM terminal vault. Network requests contain opaque envelopes.
 */
object GroupCommand {
    fun run(args: List<String>) {
        when (args.firstOrNull()) {
            "publish" -> withMls(args.drop(1)) { session -> publishKeyPackage(session) }
            "create" -> withMls(args.drop(1)) { session -> create(session, args.drop(1)) }
            "send" -> withMls(args.drop(1)) { session -> send(session, args.drop(1)) }
            "inbox" -> withMls(args.drop(1)) { session -> receive(session, printEmpty = true) }
            "watch" -> withMls(args.drop(1)) { session -> watch(session) }
            "sync" -> withMls(args.drop(1)) { session ->
                syncPending(session)
                println("Очередь групп синхронизирована.")
            }
            "list" -> withMls(args.drop(1)) { session -> list(session) }
            else -> usage()
        }
    }

    private fun publishKeyPackage(session: MlsSession) {
        val keyPackage = requireNotNull(
            NativeMlsBridge.createKeyPackage(session.state.getString("device_id")),
        ) { "OpenMLS не создал KeyPackage" }
        request(
            session.client,
            "PUT",
            "${session.server}/v1/groups/key-package",
            JSONObject().put("key_package", keyPackage.base64Url()),
            session.token,
        )
        println("Одноразовый MLS KeyPackage опубликован. Теперь это устройство можно пригласить в группу.")
    }

    private fun create(session: MlsSession, args: List<String>) {
        val peer = args.option("--with")?.normalizeNickname()
            ?: error("Укажите участника: group create --with nickname")
        require(peer != session.nickname) { "Нельзя пригласить самого себя" }
        val encodedPeer = URLEncoder.encode(peer, Charsets.UTF_8)
        val packageResponse = request(
            session.client,
            "GET",
            "${session.server}/v1/users/$encodedPeer/mls-key-package",
            null,
            session.token,
        ) as JSONObject
        val groupId = requireNotNull(
            NativeMlsBridge.createLocalGroup(session.state.getString("device_id")),
        ) { "OpenMLS не создал группу" }
        var memberAdded = false
        try {
            val added = requireNotNull(
                NativeMlsBridge.addMember(
                    groupId,
                    packageResponse.getString("key_package").base64UrlDecode(),
                ),
            ) { "OpenMLS отклонил KeyPackage @$peer" }
            memberAdded = true
            val group = JSONObject()
                .put("group_id", groupId.base64Url())
                .put("members", JSONArray(listOf(session.nickname, peer)))
                .put("messages", JSONArray())
                .put("registered", false)
            session.groups.put(group)
            enqueueEvent(session, groupId, KIND_WELCOME, listOf(peer), added.welcomeEnvelope)
            session.save()
            syncPending(session)
            println("Защищённая группа создана: ${groupId.base64Url()}")
            println("Участники: @${session.nickname}, @$peer")
        } catch (error: Throwable) {
            if (!memberAdded) NativeMlsBridge.deleteLocalGroup(groupId)
            throw error
        }
    }

    private fun send(session: MlsSession, args: List<String>) {
        val group = resolveGroup(session, args.option("--group"))
        val text = args.option("--message")?.trim().orEmpty()
        require(text.isNotEmpty()) { "Укажите текст: --message 'Привет'" }
        val groupId = group.getString("group_id").base64UrlDecode()
        val envelope = requireNotNull(
            NativeMlsBridge.createApplicationMessage(groupId, text.encodeToByteArray()),
        ) { "OpenMLS не зашифровал сообщение" }
        val recipients = group.getJSONArray("members").strings()
            .filterNot { it == session.nickname }
        enqueueEvent(session, groupId, KIND_APPLICATION, recipients, envelope)
        group.getJSONArray("messages").put(
            JSONObject()
                .put("sender", session.nickname)
                .put("text", text)
                .put("outgoing", true),
        )
        session.save()
        syncPending(session)
        println("Групповое сообщение зашифровано и отправлено.")
    }

    private fun receive(session: MlsSession, printEmpty: Boolean): Int {
        syncPending(session)
        val events = request(
            session.client,
            "GET",
            "${session.server}/v1/groups/events",
            null,
            session.token,
        ) as JSONArray
        var received = 0
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            val eventId = event.getString("event_id")
            val groupId = event.getString("group_id").base64UrlDecode()
            val sender = event.getString("sender_nickname")
            val envelope = event.getString("envelope").base64UrlDecode()
            when (event.getInt("kind")) {
                KIND_WELCOME -> {
                    val joinedId = requireNotNull(NativeMlsBridge.processWelcome(envelope)) {
                        "OpenMLS отклонил Welcome от @$sender"
                    }
                    require(joinedId.contentEquals(groupId)) { "Group ID в Welcome не совпадает с маршрутом" }
                    upsertGroup(session, groupId, listOf(session.nickname, sender))
                    println("Принято защищённое приглашение в группу от @$sender")
                }
                KIND_COMMIT -> require(NativeMlsBridge.processCommit(groupId, envelope)) {
                    "OpenMLS отклонил Commit от @$sender"
                }
                KIND_APPLICATION -> {
                    val plaintext = requireNotNull(
                        NativeMlsBridge.processApplicationMessage(groupId, envelope),
                    ) { "OpenMLS отклонил сообщение от @$sender" }
                    val text = try {
                        plaintext.decodeToString()
                    } finally {
                        plaintext.fill(0)
                    }
                    val group = requireNotNull(findGroup(session, groupId)) {
                        "Получено сообщение неизвестной локальной группы"
                    }
                    if ((0 until group.getJSONArray("messages").length()).none {
                            group.getJSONArray("messages").getJSONObject(it).optString("event_id") == eventId
                        }
                    ) {
                        group.getJSONArray("messages").put(
                            JSONObject()
                                .put("event_id", eventId)
                                .put("sender", sender)
                                .put("text", text)
                                .put("outgoing", false),
                        )
                    }
                    println("[${shortId(groupId)}] @$sender: $text")
                }
                else -> error("Неизвестный тип MLS event")
            }
            session.save()
            request(
                session.client,
                "POST",
                "${session.server}/v1/groups/events/$eventId",
                null,
                session.token,
            )
            received++
        }
        if (received == 0 && printEmpty) println("Новых групповых событий нет.")
        return received
    }

    private fun watch(session: MlsSession) {
        println("Live-приём MLS-групп запущен. Нажмите Ctrl+C для выхода.")
        while (true) {
            val ready = request(
                session.client,
                "GET",
                "${session.server}/v1/groups/events/wait",
                null,
                session.token,
            ) as JSONObject
            if (ready.getBoolean("available")) receive(session, printEmpty = false)
        }
    }

    private fun list(session: MlsSession) {
        if (session.groups.length() == 0) {
            println("Локальных MLS-групп пока нет.")
            return
        }
        for (index in 0 until session.groups.length()) {
            val group = session.groups.getJSONObject(index)
            val members = group.getJSONArray("members").strings().joinToString { "@$it" }
            println("${shortId(group.getString("group_id").base64UrlDecode())}  $members")
            val messages = group.getJSONArray("messages")
            if (messages.length() > 0) {
                val last = messages.getJSONObject(messages.length() - 1)
                println("  последнее — @${last.getString("sender")}: ${last.getString("text")}")
            }
        }
    }

    private fun syncPending(session: MlsSession) {
        for (index in 0 until session.groups.length()) {
            val group = session.groups.getJSONObject(index)
            if (!group.optBoolean("registered")) {
                val members = group.getJSONArray("members").strings()
                    .filterNot { it == session.nickname }
                val memberJson = JSONArray()
                members.forEach {
                    memberJson.put(JSONObject().put("nickname", it).put("role", "member"))
                }
                request(
                    session.client,
                    "POST",
                    "${session.server}/v1/groups",
                    JSONObject()
                        .put("group_id", group.getString("group_id"))
                        .put("members", memberJson),
                    session.token,
                )
                group.put("registered", true)
                session.save()
            }
        }
        val pending = session.pendingEvents
        while (pending.length() > 0) {
            val event = pending.getJSONObject(0)
            request(
                session.client,
                "POST",
                "${session.server}/v1/groups/${event.getString("group_id")}/events",
                JSONObject()
                    .put("client_event_id", event.getString("id"))
                    .put("kind", event.getInt("kind"))
                    .put("recipient_nicknames", event.getJSONArray("recipients"))
                    .put("envelope", event.getString("envelope")),
                session.token,
            )
            pending.remove(0)
            session.save()
        }
    }

    private fun enqueueEvent(
        session: MlsSession,
        groupId: ByteArray,
        kind: Int,
        recipients: List<String>,
        envelope: ByteArray,
    ) {
        val id = MessageDigest.getInstance("SHA-256")
            .digest(groupId + byteArrayOf(kind.toByte()) + envelope)
            .base64Url()
        if ((0 until session.pendingEvents.length()).none {
                session.pendingEvents.getJSONObject(it).getString("id") == id
            }
        ) {
            session.pendingEvents.put(
                JSONObject()
                    .put("id", id)
                    .put("group_id", groupId.base64Url())
                    .put("kind", kind)
                    .put("recipients", JSONArray(recipients))
                    .put("envelope", envelope.base64Url()),
            )
        }
    }

    private fun upsertGroup(session: MlsSession, groupId: ByteArray, members: List<String>): JSONObject {
        findGroup(session, groupId)?.let { existing ->
            val combined = (existing.getJSONArray("members").strings() + members).distinct()
            existing.put("members", JSONArray(combined))
            return existing
        }
        return JSONObject()
            .put("group_id", groupId.base64Url())
            .put("members", JSONArray(members.distinct()))
            .put("messages", JSONArray())
            .put("registered", true)
            .also(session.groups::put)
    }

    private fun resolveGroup(session: MlsSession, requested: String?): JSONObject {
        require(session.groups.length() > 0) { "Нет локальных MLS-групп" }
        if (requested == null && session.groups.length() == 1) return session.groups.getJSONObject(0)
        val prefix = requested?.trim().orEmpty()
        require(prefix.isNotEmpty()) { "Укажите --group ID (достаточно начала ID из `group list`)" }
        return (0 until session.groups.length())
            .map(session.groups::getJSONObject)
            .singleOrNull { it.getString("group_id").startsWith(prefix) }
            ?: error("Группа не найдена или префикс неоднозначен")
    }

    private fun findGroup(session: MlsSession, groupId: ByteArray): JSONObject? {
        val encoded = groupId.base64Url()
        return (0 until session.groups.length())
            .map(session.groups::getJSONObject)
            .firstOrNull { it.getString("group_id") == encoded }
    }

    private fun withMls(args: List<String>, operation: (MlsSession) -> Unit) {
        val dataDir = Path.of(
            args.option("--data-dir")
                ?: Path.of(System.getProperty("user.home"), ".local", "share", "hiddi-terminal").toString(),
        )
        val console = System.console() ?: error("Команда требует интерактивный терминал для парольной фразы.")
        val passphrase = console.readPassword("Парольная фраза: ")
        try {
            val vault = EncryptedVault(dataDir.resolve("signal-device.v1"))
            val state = JSONObject(vault.read(passphrase)?.decodeToString() ?: error("Устройство не зарегистрировано"))
            if (!state.has("mls_storage_key")) {
                state.put("mls_storage_key", ByteArray(64).also(SecureRandom()::nextBytes).base64Url())
            }
            if (!state.has("mls_groups")) state.put("mls_groups", JSONArray())
            if (!state.has("mls_pending_events")) state.put("mls_pending_events", JSONArray())
            vault.write(state.toString().encodeToByteArray(), passphrase)
            val storageKey = state.getString("mls_storage_key").base64UrlDecode()
            try {
                require(NativeMlsBridge.initialize(storageKey, dataDir.resolve("group-mls.sqlite"))) {
                    "Не удалось открыть общий Rust/OpenMLS core"
                }
            } finally {
                storageKey.fill(0)
            }
            operation(MlsSession(state, vault, passphrase, HttpClient.newHttpClient()))
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private data class MlsSession(
        val state: JSONObject,
        val vault: EncryptedVault,
        val passphrase: CharArray,
        val client: HttpClient,
    ) {
        val server: String get() = state.getString("server")
        val token: String get() = state.getString("access_token")
        val nickname: String get() = state.getString("nickname")
        val groups: JSONArray get() = state.getJSONArray("mls_groups")
        val pendingEvents: JSONArray get() = state.getJSONArray("mls_pending_events")
        fun save() = vault.write(state.toString().encodeToByteArray(), passphrase)
    }

    private fun shortId(groupId: ByteArray) = groupId.base64Url().take(10)
    private fun String.normalizeNickname() = trim().removePrefix("@").lowercase()
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun List<String>.option(name: String): String? =
        indexOf(name).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    private fun usage(): Nothing = error(
        "Использование: group publish|create --with NICK|list|send [--group ID] --message TEXT|inbox|watch|sync",
    )

    private const val KIND_WELCOME = 1
    private const val KIND_COMMIT = 2
    private const val KIND_APPLICATION = 3
}
