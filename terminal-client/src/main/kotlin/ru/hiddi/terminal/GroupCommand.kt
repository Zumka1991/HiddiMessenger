package ru.hiddi.terminal

import org.json.JSONArray
import org.json.JSONObject
import ru.hiddi.messenger.security.NativeMlsBridge
import ru.hiddi.messenger.security.PendingMlsMembershipKind
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

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
            "invite" -> withMls(args.drop(1)) { session -> invite(session, args.drop(1)) }
            "remove" -> withMls(args.drop(1)) { session -> removeMember(session, args.drop(1)) }
            "role" -> withMls(args.drop(1)) { session -> updateRole(session, args.drop(1)) }
            "send" -> withMls(args.drop(1)) { session -> send(session, args.drop(1)) }
            "delete-message" -> withMls(args.drop(1)) { session -> deleteMessage(session, args.drop(1)) }
            "delete" -> withMls(args.drop(1)) { session -> delete(session, args.drop(1)) }
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
            val operationId = UUID.randomUUID().toString()
            val context = membershipContext(
                CONTEXT_CREATE_GROUP,
                session.nickname,
                peer,
                emptyList(),
                listOf(session.nickname, peer),
            )
            requireNotNull(
                NativeMlsBridge.addMember(
                    groupId,
                    packageResponse.getString("key_package").base64UrlDecode(),
                    operationId,
                    context,
                ),
            ) { "OpenMLS отклонил KeyPackage @$peer" }
            memberAdded = true
            handoffNativeJournal(session)
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
        val messageId = ByteArray(16).also(SecureRandom()::nextBytes).base64Url()
        val payload = encodeGroupText(messageId, text)
        val envelope = try {
            requireNotNull(NativeMlsBridge.createApplicationMessage(groupId, payload)) {
                "OpenMLS не зашифровал сообщение"
            }
        } finally {
            payload.fill(0)
        }
        val recipients = group.getJSONArray("members").strings()
            .filterNot { it == session.nickname }
        enqueueEvent(
            session,
            groupId,
            KIND_APPLICATION,
            recipients,
            envelope,
            clientEventId = messageId,
        )
        group.getJSONArray("messages").put(
            JSONObject()
                .put("message_id", messageId)
                .put("sender", session.nickname)
                .put("text", text)
                .put("outgoing", true),
        )
        session.save()
        syncPending(session)
        println("Групповое сообщение зашифровано и отправлено.")
    }

    private fun deleteMessage(session: MlsSession, args: List<String>) {
        val group = resolveGroup(session, args.option("--group"))
        val messages = group.getJSONArray("messages")
        val requested = args.option("--message")
        val message = (messages.length() - 1 downTo 0)
            .map(messages::getJSONObject)
            .firstOrNull {
                it.optBoolean("outgoing") &&
                    it.optString("message_id").isNotBlank() &&
                    (requested == null || it.getString("message_id").startsWith(requested))
            }
            ?: error("Своё удаляемое сообщение не найдено")
        val messageId = message.getString("message_id")
        val groupId = group.getString("group_id").base64UrlDecode()
        val forEveryone = "--local" !in args
        if (forEveryone) {
            val payload = encodeGroupDelete(messageId)
            val envelope = try {
                requireNotNull(NativeMlsBridge.createApplicationMessage(groupId, payload)) {
                    "OpenMLS не зашифровал удаление"
                }
            } finally {
                payload.fill(0)
            }
            enqueueEvent(
                session,
                groupId,
                KIND_APPLICATION,
                group.getJSONArray("members").strings().filterNot { it == session.nickname },
                envelope,
                deleteClientEventId = messageId,
            )
        }
        for (index in messages.length() - 1 downTo 0) {
            if (messages.getJSONObject(index).optString("message_id") == messageId) {
                messages.remove(index)
            }
        }
        session.save()
        if (forEveryone) syncPending(session)
        println(if (forEveryone) "Сообщение удалено у участников." else "Сообщение удалено локально.")
    }

    private fun invite(session: MlsSession, args: List<String>) {
        val group = resolveGroup(session, args.option("--group"))
        val currentDetails = fetchGroupDetails(
            session,
            group.getString("group_id").base64UrlDecode(),
        )
        val ownRole = currentDetails.memberDetails
            .firstOrNull { it.nickname == session.nickname }
            ?.role
        require(ownRole == "owner" || ownRole == "admin") {
            "Приглашать участников может только владелец или администратор"
        }
        val nickname = args.option("--with")?.normalizeNickname()
            ?: error("Укажите участника: group invite --with nickname")
        val currentMembers = group.getJSONArray("members").strings()
        require(nickname !in currentMembers) { "@$nickname уже состоит в группе" }
        val encodedNickname = URLEncoder.encode(nickname, Charsets.UTF_8)
        val packageResponse = request(
            session.client,
            "GET",
            "${session.server}/v1/users/$encodedNickname/mls-key-package",
            null,
            session.token,
        ) as JSONObject
        val groupId = group.getString("group_id").base64UrlDecode()
        val existingRecipients = currentMembers.filterNot { it == session.nickname }
        val operationId = UUID.randomUUID().toString()
        val context = membershipContext(
            CONTEXT_INVITE_MEMBER,
            currentDetails.ownerNickname,
            nickname,
            existingRecipients,
            currentMembers + nickname,
        )
        requireNotNull(
            NativeMlsBridge.addMember(
                groupId,
                packageResponse.getString("key_package").base64UrlDecode(),
                operationId,
                context,
            ),
        ) { "OpenMLS отклонил KeyPackage @$nickname" }
        handoffNativeJournal(session)
        syncPending(session)
        println("@$nickname приглашён; Commit и Welcome отправлены.")
    }

    private fun removeMember(session: MlsSession, args: List<String>) {
        val group = resolveGroup(session, args.option("--group"))
        val nickname = args.option("--with")?.normalizeNickname()
            ?: error("Укажите участника: group remove --with nickname")
        require(nickname != session.nickname) { "Нельзя удалить себя этой командой" }
        val groupId = group.getString("group_id").base64UrlDecode()
        val details = fetchGroupDetails(session, groupId)
        val ownRole = details.memberDetails.firstOrNull { it.nickname == session.nickname }?.role
            ?: error("Текущий профиль не состоит в группе")
        val target = details.memberDetails.firstOrNull { it.nickname == nickname }
            ?: error("@$nickname не состоит в группе")
        val allowed = ownRole == "owner" && target.role != "owner" ||
            ownRole == "admin" && target.role == "member"
        require(allowed) { "Недостаточно прав для удаления @$nickname" }
        val recipients = details.members.filterNot { it == session.nickname }
        val operationId = UUID.randomUUID().toString()
        val context = membershipContext(
            CONTEXT_REMOVE_MEMBER,
            details.ownerNickname,
            nickname,
            recipients,
            details.members,
        )
        requireNotNull(
            NativeMlsBridge.removeMember(
                groupId,
                target.deviceId,
                operationId,
                context,
            ),
        ) {
            "OpenMLS не создал Remove Commit"
        }
        handoffNativeJournal(session)
        syncPending(session)
        val refreshed = fetchGroupDetails(session, groupId)
        group.put("members", JSONArray(refreshed.members))
        session.save()
        println("@$nickname удалён; Remove Commit применён, MLS-эпоха обновлена.")
    }

    private fun updateRole(session: MlsSession, args: List<String>) {
        val group = resolveGroup(session, args.option("--group"))
        val nickname = args.option("--with")?.normalizeNickname()
            ?: error("Укажите участника: group role --with nickname --role admin|member")
        val role = args.option("--role")
            ?: error("Укажите роль: --role admin|member")
        require(role == "admin" || role == "member") { "Роль должна быть admin или member" }
        request(
            session.client,
            "PUT",
            "${session.server}/v1/groups/${group.getString("group_id")}/members/$nickname/role",
            JSONObject().put("role", role),
            session.token,
        )
        println("Роль @$nickname изменена на $role.")
    }

    private fun delete(session: MlsSession, args: List<String>) {
        val group = resolveGroup(session, args.option("--group"))
        require(group.optString("owner_nickname", session.nickname) == session.nickname) {
            "Удалить группу может только создатель"
        }
        val groupId = group.getString("group_id").base64UrlDecode()
        request(
            session.client,
            "DELETE",
            "${session.server}/v1/groups/${group.getString("group_id")}",
            null,
            session.token,
        )
        require(NativeMlsBridge.deleteLocalGroup(groupId)) {
            "Сервер удалил группу, но локальное MLS-состояние не очистилось"
        }
        for (index in session.groups.length() - 1 downTo 0) {
            if (session.groups.getJSONObject(index).getString("group_id") == group.getString("group_id")) {
                session.groups.remove(index)
            }
        }
        session.save()
        println("Группа ${shortId(groupId)} удалена.")
    }

    private fun receive(session: MlsSession, printEmpty: Boolean): Int {
        syncPending(session)
        processGroupDeletions(session)
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
                    val details = fetchGroupDetails(session, groupId)
                    upsertGroup(
                        session,
                        groupId,
                        details.members,
                        details.ownerNickname,
                    )
                    println("Принято защищённое приглашение в группу от @$sender")
                }
                KIND_COMMIT -> {
                    require(NativeMlsBridge.processCommit(groupId, envelope)) {
                        "OpenMLS отклонил Commit от @$sender"
                    }
                    if (event.optBoolean("removes_recipient")) {
                        NativeMlsBridge.deleteLocalGroup(groupId)
                        for (groupIndex in session.groups.length() - 1 downTo 0) {
                            if (session.groups.getJSONObject(groupIndex)
                                    .getString("group_id") == event.getString("group_id")
                            ) {
                                session.groups.remove(groupIndex)
                            }
                        }
                        println("Профиль исключён из группы ${shortId(groupId)}")
                    } else {
                        val details = fetchGroupDetails(session, groupId)
                        upsertGroup(
                            session,
                            groupId,
                            details.members,
                            details.ownerNickname,
                        )
                        println("Состав группы ${shortId(groupId)} обновлён")
                    }
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
                    val payload = decodeGroupPayload(text)
                    val group = requireNotNull(findGroup(session, groupId)) {
                        "Получено сообщение неизвестной локальной группы"
                    }
                    when (payload) {
                        is TerminalGroupPayload.Text -> {
                            if ((0 until group.getJSONArray("messages").length()).none {
                                    val stored = group.getJSONArray("messages").getJSONObject(it)
                                    stored.optString("event_id") == eventId ||
                                        (payload.messageId != null &&
                                            stored.optString("message_id") == payload.messageId)
                                }
                            ) {
                                group.getJSONArray("messages").put(
                                    JSONObject()
                                        .put("event_id", eventId)
                                        .put("message_id", payload.messageId)
                                        .put("sender", sender)
                                        .put("text", payload.text)
                                        .put("outgoing", false),
                                )
                            }
                            println("[${shortId(groupId)}] @$sender: ${payload.text}")
                        }
                        is TerminalGroupPayload.Attachment -> {
                            group.getJSONArray("messages").put(
                                JSONObject()
                                    .put("event_id", eventId)
                                    .put("message_id", payload.messageId)
                                    .put("sender", sender)
                                    .put("text", payload.label)
                                    .put("attachment", payload.envelope)
                                    .put("outgoing", false),
                            )
                            println("[${shortId(groupId)}] @$sender: ${payload.label}")
                        }
                        is TerminalGroupPayload.Metadata -> {
                            group.put("name", payload.name)
                            println("[${shortId(groupId)}] название группы: ${payload.name}")
                        }
                        is TerminalGroupPayload.Delete -> {
                            val messages = group.getJSONArray("messages")
                            for (messageIndex in messages.length() - 1 downTo 0) {
                                val stored = messages.getJSONObject(messageIndex)
                                if (stored.optString("message_id") == payload.messageId &&
                                    stored.getString("sender") == sender
                                ) {
                                    messages.remove(messageIndex)
                                }
                            }
                            println("[${shortId(groupId)}] @$sender удалил своё сообщение")
                        }
                    }
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
        handoffNativeJournal(session)
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
        while (session.pendingMembers.length() > 0) {
            val member = session.pendingMembers.getJSONObject(0)
            request(
                session.client,
                "POST",
                "${session.server}/v1/groups/${member.getString("group_id")}/members",
                JSONObject()
                    .put("nickname", member.getString("nickname"))
                    .put("role", member.getString("role")),
                session.token,
            )
            session.pendingMembers.remove(0)
            session.save()
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
                    .put("envelope", event.getString("envelope"))
                    .put(
                        "remove_member_nickname",
                        event.optString("remove_member_nickname")
                            .takeIf(String::isNotBlank),
                    ),
                session.token,
            )
            event.optString("delete_client_event_id").takeIf(String::isNotBlank)?.let { target ->
                request(
                    session.client,
                    "DELETE",
                    "${session.server}/v1/groups/${event.getString("group_id")}/messages/$target",
                    null,
                    session.token,
                )
            }
            pending.remove(0)
            session.save()
        }
    }

    private fun handoffNativeJournal(session: MlsSession) {
        NativeMlsBridge.pendingMembershipOperations().forEach { operation ->
            val context = JSONObject(operation.context.decodeToString())
            require(context.getInt("version") == 1)
            val type = context.getString("type")
            val owner = context.getString("owner_nickname")
            val target = context.getString("target_nickname")
            val recipients = context.getJSONArray("recipients").strings()
            val members = context.getJSONArray("members").strings()
            when (type) {
                CONTEXT_CREATE_GROUP -> {
                    if (findGroup(session, operation.groupId) == null) {
                        session.groups.put(
                            JSONObject()
                                .put("group_id", operation.groupId.base64Url())
                                .put("owner_nickname", owner)
                                .put("members", JSONArray(members))
                                .put("messages", JSONArray())
                                .put("registered", false),
                        )
                    }
                }
                CONTEXT_INVITE_MEMBER -> {
                    val group = findGroup(session, operation.groupId)
                        ?: error("Локальная группа для MLS-приглашения не найдена")
                    group.put("members", JSONArray(members))
                    enqueueMember(session, operation.groupId, target)
                }
                CONTEXT_REMOVE_MEMBER -> Unit
                else -> error("Неизвестный контекст MLS")
            }
            if (operation.kind == PendingMlsMembershipKind.ADD_MEMBER) {
                if (recipients.isNotEmpty()) {
                    enqueueEvent(
                        session,
                        operation.groupId,
                        KIND_COMMIT,
                        recipients,
                        operation.commitEnvelope,
                    )
                }
                enqueueEvent(
                    session,
                    operation.groupId,
                    KIND_WELCOME,
                    listOf(target),
                    requireNotNull(operation.welcomeEnvelope),
                )
            } else {
                enqueueEvent(
                    session,
                    operation.groupId,
                    KIND_COMMIT,
                    recipients,
                    operation.commitEnvelope,
                    removeMemberNickname = target,
                )
            }
            session.save()
            require(NativeMlsBridge.acknowledgeMembershipOperation(operation.operationId)) {
                "Не удалось подтвердить перенос операции MLS"
            }
        }
    }

    private fun membershipContext(
        type: String,
        ownerNickname: String,
        targetNickname: String,
        recipients: List<String>,
        members: List<String>,
    ): ByteArray = JSONObject()
        .put("version", 1)
        .put("type", type)
        .put("owner_nickname", ownerNickname)
        .put("target_nickname", targetNickname)
        .put("recipients", JSONArray(recipients))
        .put("members", JSONArray(members))
        .toString()
        .encodeToByteArray()

    private fun enqueueEvent(
        session: MlsSession,
        groupId: ByteArray,
        kind: Int,
        recipients: List<String>,
        envelope: ByteArray,
        clientEventId: String? = null,
        deleteClientEventId: String? = null,
        removeMemberNickname: String? = null,
    ) {
        val id = clientEventId ?: MessageDigest.getInstance("SHA-256")
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
                    .put("envelope", envelope.base64Url())
                    .put("delete_client_event_id", deleteClientEventId)
                    .put("remove_member_nickname", removeMemberNickname),
            )
        }
    }

    private fun enqueueMember(
        session: MlsSession,
        groupId: ByteArray,
        nickname: String,
    ) {
        val id = "${groupId.base64Url()}:$nickname:member"
        if ((0 until session.pendingMembers.length()).none {
                session.pendingMembers.getJSONObject(it).getString("id") == id
            }
        ) {
            session.pendingMembers.put(
                JSONObject()
                    .put("id", id)
                    .put("group_id", groupId.base64Url())
                    .put("nickname", nickname)
                    .put("role", "member"),
            )
        }
    }

    private fun upsertGroup(
        session: MlsSession,
        groupId: ByteArray,
        members: List<String>,
        ownerNickname: String,
    ): JSONObject {
        findGroup(session, groupId)?.let { existing ->
            existing.put("owner_nickname", ownerNickname)
            existing.put("members", JSONArray(members.distinct()))
            return existing
        }
        return JSONObject()
            .put("group_id", groupId.base64Url())
            .put("owner_nickname", ownerNickname)
            .put("members", JSONArray(members.distinct()))
            .put("messages", JSONArray())
            .put("registered", true)
            .also(session.groups::put)
    }

    private fun fetchGroupDetails(session: MlsSession, groupId: ByteArray): TerminalGroupDetails {
        val response = request(
            session.client,
            "GET",
            "${session.server}/v1/groups/${groupId.base64Url()}",
            null,
            session.token,
        ) as JSONObject
        return TerminalGroupDetails(
            ownerNickname = response.getString("owner_nickname"),
            memberDetails = response.getJSONArray("members").let { members ->
                (0 until members.length()).map {
                    members.getJSONObject(it).let { member ->
                        TerminalGroupMember(
                            nickname = member.getString("nickname"),
                            role = member.getString("role"),
                            deviceId = member.getString("device_id"),
                        )
                    }
                }
            },
        )
    }

    private fun processGroupDeletions(session: MlsSession) {
        val deletions = request(
            session.client,
            "GET",
            "${session.server}/v1/groups/deletions",
            null,
            session.token,
        ) as JSONArray
        for (index in 0 until deletions.length()) {
            val deletion = deletions.getJSONObject(index)
            val groupId = deletion.getString("group_id").base64UrlDecode()
            NativeMlsBridge.deleteLocalGroup(groupId)
            for (groupIndex in session.groups.length() - 1 downTo 0) {
                if (session.groups.getJSONObject(groupIndex).getString("group_id") == deletion.getString("group_id")) {
                    session.groups.remove(groupIndex)
                }
            }
            session.save()
            request(
                session.client,
                "POST",
                "${session.server}/v1/groups/deletions/${deletion.getString("deletion_id")}",
                null,
                session.token,
            )
            println("Группа ${shortId(groupId)} удалена её создателем")
        }
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
            if (!state.has("mls_pending_members")) state.put("mls_pending_members", JSONArray())
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
        val pendingMembers: JSONArray get() = state.getJSONArray("mls_pending_members")
        fun save() = vault.write(state.toString().encodeToByteArray(), passphrase)
    }

    private fun shortId(groupId: ByteArray) = groupId.base64Url().take(10)
    private fun encodeGroupText(messageId: String, text: String): ByteArray =
        (GROUP_PAYLOAD_PREFIX + JSONObject()
            .put("version", 1)
            .put("type", "text")
            .put("message_id", messageId)
            .put("text", text)
            .toString())
            .encodeToByteArray()

    private fun encodeGroupDelete(messageId: String): ByteArray =
        (GROUP_PAYLOAD_PREFIX + JSONObject()
            .put("version", 1)
            .put("type", "delete")
            .put("message_id", messageId)
            .toString())
            .encodeToByteArray()

    private fun decodeGroupPayload(plaintext: String): TerminalGroupPayload {
        if (!plaintext.startsWith(GROUP_PAYLOAD_PREFIX)) {
            return TerminalGroupPayload.Text(null, plaintext)
        }
        val payload = JSONObject(plaintext.removePrefix(GROUP_PAYLOAD_PREFIX))
        require(payload.optInt("version") == 1) { "Неподдерживаемая версия group payload" }
        val messageId = payload.getString("message_id")
        require(messageId.base64UrlDecode().size in 16..64) { "Некорректный group message id" }
        return when (payload.getString("type")) {
            "text" -> TerminalGroupPayload.Text(messageId, payload.getString("text"))
            "attachment" -> {
                val envelope = payload.getString("attachment")
                val attachment = JSONObject(envelope)
                require(attachment.getString("type") == "hiddi.attachment.v1")
                val label = when (attachment.getString("kind")) {
                    "image" -> "📷 Изображение"
                    "voice" -> "🎙 Голосовое сообщение"
                    else -> error("Неизвестный тип вложения")
                }
                TerminalGroupPayload.Attachment(messageId, label, envelope)
            }
            "group_metadata" -> TerminalGroupPayload.Metadata(
                payload.getString("name").also {
                    require(it.isNotBlank() && it.length <= 80)
                },
            )
            "delete" -> TerminalGroupPayload.Delete(messageId)
            else -> error("Неизвестный тип group payload")
        }
    }

    private fun String.normalizeNickname() = trim().removePrefix("@").lowercase()
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun List<String>.option(name: String): String? =
        indexOf(name).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    private fun usage(): Nothing = error(
        "Использование: group publish|create --with NICK|invite --with NICK|remove --with NICK|role --with NICK --role admin|member|list|send|delete-message|inbox|watch|sync|delete",
    )

    private data class TerminalGroupDetails(
        val ownerNickname: String,
        val memberDetails: List<TerminalGroupMember>,
    ) {
        val members: List<String> get() = memberDetails.map(TerminalGroupMember::nickname)
    }

    private data class TerminalGroupMember(
        val nickname: String,
        val role: String,
        val deviceId: String,
    )

    private sealed interface TerminalGroupPayload {
        data class Text(val messageId: String?, val text: String) : TerminalGroupPayload
        data class Attachment(
            val messageId: String,
            val label: String,
            val envelope: String,
        ) : TerminalGroupPayload
        data class Metadata(val name: String) : TerminalGroupPayload
        data class Delete(val messageId: String) : TerminalGroupPayload
    }

    private const val KIND_WELCOME = 1
    private const val KIND_COMMIT = 2
    private const val KIND_APPLICATION = 3
    private const val CONTEXT_CREATE_GROUP = "create_group"
    private const val CONTEXT_INVITE_MEMBER = "invite_member"
    private const val CONTEXT_REMOVE_MEMBER = "remove_member"
    private const val GROUP_PAYLOAD_PREFIX = "HIDDI_GROUP_V1:"
}
