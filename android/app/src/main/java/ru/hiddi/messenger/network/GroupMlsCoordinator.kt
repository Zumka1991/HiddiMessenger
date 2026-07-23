package ru.hiddi.messenger.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.hiddi.messenger.security.EncryptedGroupChatStore
import ru.hiddi.messenger.security.EncryptedAttachmentStore
import ru.hiddi.messenger.security.AttachmentDescriptor
import ru.hiddi.messenger.security.GroupDirectoryMember
import ru.hiddi.messenger.security.LocalGroupChat
import ru.hiddi.messenger.security.NativeMlsBridge
import ru.hiddi.messenger.security.PendingMlsMembershipKind
import java.util.UUID

/**
 * Coordinates local OpenMLS state with the opaque server transport.
 *
 * No message plaintext or MLS private state crosses this class. Network
 * failures leave encrypted outbox entries for a later retry.
 */
class GroupMlsCoordinator(
    context: Context,
    private val api: SignalMessagingApi,
) {
    private val appContext = context.applicationContext
    private val accountStore = AccountStore(appContext)
    private val registrationOutbox = GroupRegistrationOutbox(appContext, api)
    private val memberOutbox = GroupMemberOutbox(appContext, api)
    private val eventOutbox = GroupEventOutbox(appContext, api)
    private val groupStore = EncryptedGroupChatStore(appContext)
    private val attachmentStore = EncryptedAttachmentStore(appContext)

    suspend fun prepare(profile: AccountProfile): AccountProfile {
        val current = ensureDeviceId(profile)
        synchronize(current)
        publishKeyPackage(current)
        return current
    }

    suspend fun synchronize(profile: AccountProfile): List<ByteArray> {
        val current = ensureDeviceId(profile)
        val deletedGroups = processGroupDeletions(current)
        handoffNativeJournal()
        registrationOutbox.retry(current)
        memberOutbox.retry(current)
        eventOutbox.retry(current)
        downloadPendingAttachments(current)
        return (deletedGroups + processPendingEvents(current))
            .distinctBy { it.contentHashCode() }
    }

    suspend fun createTwoPartyGroup(
        profile: AccountProfile,
        invitedNickname: String,
        name: String,
    ): ByteArray {
        val current = ensureDeviceId(profile)
        val deviceId = requireNotNull(current.deviceId)
        val groupId = requireNotNull(NativeMlsBridge.createLocalGroup(deviceId)) {
            "Не удалось создать локальную MLS-группу"
        }
        var memberAdded = false
        try {
            val keyPackage = api.takeMlsKeyPackage(current, invitedNickname)
            val operationId = UUID.randomUUID().toString()
            val context = membershipContext(
                type = CONTEXT_CREATE_GROUP,
                ownerNickname = current.nickname,
                ownerDeviceId = deviceId,
                targetNickname = invitedNickname,
                recipients = emptyList(),
                members = listOf(
                    GroupDirectoryMember(current.nickname, "owner", deviceId),
                    GroupDirectoryMember(invitedNickname, "member", ""),
                ),
                groupName = name,
            )
            requireNotNull(NativeMlsBridge.addMember(groupId, keyPackage, operationId, context)) {
                "OpenMLS отклонил KeyPackage приглашённого"
            }
            memberAdded = true
            handoffNativeJournal()
            registrationOutbox.retry(current)
            eventOutbox.retry(current)
            groupStore.setGroupName(groupId, name)
            sendGroupMetadata(current, groupId, name, listOf(invitedNickname))
            api.groupDetails(current, groupId).also { details ->
                groupStore.replaceMembers(
                    groupId,
                    details.members.map(GroupMember::directory),
                    details.ownerNickname,
                )
            }
            eventOutbox.retry(current)
            return groupId
        } catch (error: Throwable) {
            if (!memberAdded) NativeMlsBridge.deleteLocalGroup(groupId)
            throw error
        }
    }

    suspend fun publishKeyPackage(profile: AccountProfile) {
        val deviceId = requireNotNull(profile.deviceId) { "Не найден device_id для MLS" }
        var available = 0
        while (available < KEY_PACKAGE_RESERVE) {
            val keyPackage = requireNotNull(NativeMlsBridge.createKeyPackage(deviceId)) {
                "Не удалось создать MLS KeyPackage"
            }
            available = api.uploadMlsKeyPackage(profile, keyPackage)
        }
    }

    suspend fun processWelcomes(profile: AccountProfile): List<ByteArray> {
        return processPendingEvents(profile)
    }

    suspend fun processPendingEvents(profile: AccountProfile): List<ByteArray> {
        val changedGroups = mutableListOf<ByteArray>()
        api.groupEventInbox(profile).forEach { event ->
            when (event.kind) {
                KIND_WELCOME -> {
                    val groupId = requireNotNull(NativeMlsBridge.processWelcome(event.envelope)) {
                        "OpenMLS отклонил Welcome"
                    }
                    require(groupId.contentEquals(event.groupId)) {
                        "Server group id не совпадает с MLS Welcome"
                    }
                    val details = api.groupDetails(profile, groupId)
                    groupStore.upsertGroup(
                        groupId,
                        details.members.map(GroupMember::directory),
                        ownerNickname = details.ownerNickname,
                    )
                    changedGroups += groupId
                }
                KIND_COMMIT -> {
                    require(NativeMlsBridge.processCommit(event.groupId, event.envelope)) {
                        "OpenMLS отклонил Commit"
                    }
                    if (event.removesRecipient) {
                        NativeMlsBridge.deleteLocalGroup(event.groupId)
                        groupStore.removeGroup(event.groupId).forEach {
                            attachmentStore.delete(it.attachmentId)
                        }
                    } else {
                        val details = api.groupDetails(profile, event.groupId)
                        groupStore.replaceMembers(
                            event.groupId,
                            details.members.map(GroupMember::directory),
                            details.ownerNickname,
                        )
                    }
                    changedGroups += event.groupId
                }
                KIND_APPLICATION -> {
                    val plaintext = requireNotNull(
                        NativeMlsBridge.processApplicationMessage(event.groupId, event.envelope),
                    ) { "OpenMLS отклонил group message" }
                    val payload = try {
                        GroupApplicationPayloadCodec.decode(plaintext.decodeToString())
                    } finally {
                        plaintext.fill(0)
                    }
                    when (payload) {
                        is GroupApplicationPayload.Text -> groupStore.appendIncoming(
                            event.groupId,
                            event.eventId,
                            payload.messageId,
                            event.senderNickname,
                            payload.text,
                            event.createdAt,
                        )
                        is GroupApplicationPayload.Attachment -> {
                            val descriptor = payload.descriptor
                            groupStore.appendIncoming(
                                event.groupId,
                                event.eventId,
                                payload.messageId,
                                event.senderNickname,
                                when (descriptor.kind) {
                                    EncryptedAttachmentStore.IMAGE_KIND -> "📷 Изображение"
                                    EncryptedAttachmentStore.VOICE_KIND -> "🎙 Голосовое сообщение"
                                    else -> "Вложение"
                                },
                                event.createdAt,
                                descriptor,
                            )
                            listOfNotNull(descriptor.preview, descriptor).forEach { part ->
                                if (!attachmentStore.exists(part.attachmentId)) {
                                    attachmentStore.saveCiphertext(
                                        part.attachmentId,
                                        api.downloadAttachment(profile, part.attachmentId),
                                    )
                                }
                            }
                        }
                        is GroupApplicationPayload.Metadata ->
                            groupStore.setGroupName(event.groupId, payload.name)
                        is GroupApplicationPayload.Delete ->
                            groupStore.deleteMessage(
                                event.groupId,
                                payload.messageId,
                                expectedSender = event.senderNickname,
                            ).forEach {
                                attachmentStore.delete(it.attachmentId)
                            }
                    }
                    changedGroups += event.groupId
                }
                else -> error("Неизвестный MLS event kind")
            }
            api.acknowledgeGroupEvent(profile, event.eventId)
        }
        return changedGroups.distinctBy { it.contentHashCode() }
    }

    suspend fun sendText(profile: AccountProfile, groupId: ByteArray, text: String) {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Пустое сообщение" }
        val group = groupStore.groups().firstOrNull { it.groupId.contentEquals(groupId) }
            ?: error("Неизвестная локальная MLS-группа")
        val recipients = group.members.filterNot { it == profile.nickname }
        val messageId = GroupApplicationPayloadCodec.newMessageId()
        val payload = GroupApplicationPayloadCodec.encodeText(messageId, trimmed)
        val envelope = try {
            requireNotNull(NativeMlsBridge.createApplicationMessage(groupId, payload)) {
                "Не удалось зашифровать group message"
            }
        } finally {
            payload.fill(0)
        }
        eventOutbox.enqueue(
            groupId,
            KIND_APPLICATION,
            recipients,
            envelope,
            clientEventId = messageId,
        )
        groupStore.appendOutgoing(groupId, messageId, profile.nickname, trimmed)
        eventOutbox.retry(profile)
    }

    suspend fun sendAttachment(
        profile: AccountProfile,
        groupId: ByteArray,
        descriptor: AttachmentDescriptor,
    ) {
        val group = groupStore.groups().firstOrNull { it.groupId.contentEquals(groupId) }
            ?: error("Неизвестная локальная MLS-группа")
        val recipients = group.members.filterNot { it == profile.nickname }
        val messageId = GroupApplicationPayloadCodec.newMessageId()
        val payload = GroupApplicationPayloadCodec.encodeAttachment(messageId, descriptor)
        val envelope = try {
            requireNotNull(NativeMlsBridge.createApplicationMessage(groupId, payload)) {
                "Не удалось зашифровать group attachment"
            }
        } finally {
            payload.fill(0)
        }
        eventOutbox.enqueue(
            groupId,
            KIND_APPLICATION,
            recipients,
            envelope,
            clientEventId = messageId,
        )
        groupStore.appendOutgoing(
            groupId,
            messageId,
            profile.nickname,
            when (descriptor.kind) {
                EncryptedAttachmentStore.IMAGE_KIND -> "📷 Изображение"
                EncryptedAttachmentStore.VOICE_KIND -> "🎙 Голосовое сообщение"
                else -> "Вложение"
            },
            descriptor,
        )
        eventOutbox.retry(profile)
    }

    suspend fun deleteMessage(
        profile: AccountProfile,
        groupId: ByteArray,
        messageId: String,
        forEveryone: Boolean,
    ) {
        val group = groupStore.groups().firstOrNull { it.groupId.contentEquals(groupId) }
            ?: error("Неизвестная локальная MLS-группа")
        val message = group.messages.firstOrNull { it.messageId == messageId }
            ?: return
        require(message.outgoing && message.sender == profile.nickname) {
            "Удалять у всех можно только свои сообщения"
        }
        if (forEveryone) {
            val recipients = group.members.filterNot { it == profile.nickname }
            val payload = GroupApplicationPayloadCodec.encodeDelete(messageId)
            val envelope = try {
                requireNotNull(NativeMlsBridge.createApplicationMessage(groupId, payload)) {
                    "Не удалось зашифровать удаление group message"
                }
            } finally {
                payload.fill(0)
            }
            eventOutbox.enqueue(
                groupId,
                KIND_APPLICATION,
                recipients,
                envelope,
                deleteClientEventId = messageId,
            )
        }
        val attachments = groupStore.deleteMessage(groupId, messageId)
        attachments.forEach { descriptor ->
            attachmentStore.delete(descriptor.attachmentId)
            if (forEveryone) runCatching { api.deleteAttachment(profile, descriptor.attachmentId) }
        }
        if (forEveryone) eventOutbox.retry(profile)
    }

    fun groups(): List<LocalGroupChat> = groupStore.groups()

    suspend fun inviteMember(
        profile: AccountProfile,
        groupId: ByteArray,
        nickname: String,
    ) {
        val current = ensureDeviceId(profile)
        val normalized = nickname.trim().removePrefix("@").lowercase()
        val group = groupStore.groups().firstOrNull { it.groupId.contentEquals(groupId) }
            ?: error("Неизвестная локальная MLS-группа")
        val ownRole = group.memberDetails
            .firstOrNull { it.nickname == current.nickname }
            ?.role
        require(ownRole == "owner" || ownRole == "admin") {
            "Приглашать участников может только владелец или администратор"
        }
        require(normalized !in group.members) { "@$normalized уже состоит в группе" }
        val keyPackage = api.takeMlsKeyPackage(current, normalized)
        val existingRecipients = group.members.filterNot { it == current.nickname }
        val operationId = UUID.randomUUID().toString()
        val context = membershipContext(
            type = CONTEXT_INVITE_MEMBER,
            ownerNickname = group.ownerNickname,
            ownerDeviceId = requireNotNull(current.deviceId),
            targetNickname = normalized,
            recipients = existingRecipients,
            members = group.memberDetails + GroupDirectoryMember(normalized, "member", ""),
            groupName = group.name,
        )
        requireNotNull(NativeMlsBridge.addMember(groupId, keyPackage, operationId, context)) {
            "OpenMLS отклонил KeyPackage приглашённого"
        }
        handoffNativeJournal()
        memberOutbox.retry(current)
        eventOutbox.retry(current)
        sendGroupMetadata(current, groupId, group.name, listOf(normalized))
        val details = api.groupDetails(current, groupId)
        groupStore.replaceMembers(
            groupId,
            details.members.map(GroupMember::directory),
            details.ownerNickname,
        )
    }

    suspend fun removeMember(
        profile: AccountProfile,
        groupId: ByteArray,
        nickname: String,
    ) {
        val current = ensureDeviceId(profile)
        val normalized = nickname.trim().removePrefix("@").lowercase()
        require(normalized != current.nickname) { "Нельзя удалить самого себя этим действием" }
        val details = api.groupDetails(current, groupId)
        val ownRole = details.members.firstOrNull { it.nickname == current.nickname }?.role
            ?: error("Текущий пользователь не состоит в группе")
        val target = details.members.firstOrNull { it.nickname == normalized }
            ?: error("@$normalized не состоит в группе")
        val allowed = ownRole == "owner" && target.role != "owner" ||
            ownRole == "admin" && target.role == "member"
        require(allowed) { "Недостаточно прав для удаления @$normalized" }
        require(target.deviceId.isNotBlank()) { "Не найден MLS device id участника" }
        val recipients = details.members.map(GroupMember::nickname)
            .filterNot { it == current.nickname }
        val operationId = UUID.randomUUID().toString()
        val context = membershipContext(
            type = CONTEXT_REMOVE_MEMBER,
            ownerNickname = details.ownerNickname,
            ownerDeviceId = requireNotNull(current.deviceId),
            targetNickname = normalized,
            recipients = recipients,
            members = details.members.map(GroupMember::directory),
            groupName = groupStore.groups()
                .firstOrNull { it.groupId.contentEquals(groupId) }
                ?.name,
        )
        requireNotNull(
            NativeMlsBridge.removeMember(
                groupId,
                target.deviceId,
                operationId,
                context,
            ),
        ) { "OpenMLS не создал Remove Commit" }
        handoffNativeJournal()
        eventOutbox.retry(current)
        val refreshed = api.groupDetails(current, groupId)
        groupStore.replaceMembers(
            groupId,
            refreshed.members.map(GroupMember::directory),
            refreshed.ownerNickname,
        )
    }

    suspend fun updateMemberRole(
        profile: AccountProfile,
        groupId: ByteArray,
        nickname: String,
        role: String,
    ) {
        require(role == "admin" || role == "member") { "Некорректная роль" }
        api.updateGroupMemberRole(profile, groupId, nickname, role)
        val refreshed = api.groupDetails(profile, groupId)
        groupStore.replaceMembers(
            groupId,
            refreshed.members.map(GroupMember::directory),
            refreshed.ownerNickname,
        )
    }

    fun clearLocalHistory(profile: AccountProfile, groupId: ByteArray) {
        val group = groupStore.groups().firstOrNull { it.groupId.contentEquals(groupId) }
            ?: error("Неизвестная локальная MLS-группа")
        require(group.ownerNickname == profile.nickname) {
            "Очищать историю группы может только создатель"
        }
        groupStore.clearHistory(groupId).forEach {
            attachmentStore.delete(it.attachmentId)
        }
    }

    suspend fun deleteOwnedGroup(profile: AccountProfile, groupId: ByteArray) {
        val group = groupStore.groups().firstOrNull { it.groupId.contentEquals(groupId) }
            ?: error("Неизвестная локальная MLS-группа")
        require(group.ownerNickname == profile.nickname) {
            "Удалить группу может только создатель"
        }
        api.deleteGroup(profile, groupId)
        NativeMlsBridge.deleteLocalGroup(groupId)
        groupStore.removeGroup(groupId).forEach {
            attachmentStore.delete(it.attachmentId)
        }
    }

    private suspend fun processGroupDeletions(profile: AccountProfile): List<ByteArray> {
        val deleted = mutableListOf<ByteArray>()
        api.pendingGroupDeletions(profile).forEach { deletion ->
            NativeMlsBridge.deleteLocalGroup(deletion.groupId)
            groupStore.removeGroup(deletion.groupId).forEach {
                attachmentStore.delete(it.attachmentId)
            }
            api.acknowledgeGroupDeletion(profile, deletion.deletionId)
            deleted += deletion.groupId
        }
        return deleted
    }

    private suspend fun downloadPendingAttachments(profile: AccountProfile) {
        groupStore.pendingIncomingAttachments().forEach { descriptor ->
            if (!attachmentStore.exists(descriptor.attachmentId)) {
                attachmentStore.saveCiphertext(
                    descriptor.attachmentId,
                    api.downloadAttachment(profile, descriptor.attachmentId),
                )
            }
        }
    }

    private suspend fun ensureDeviceId(profile: AccountProfile): AccountProfile {
        profile.deviceId?.let { return profile }
        val migrated = profile.copy(deviceId = api.currentDeviceId(profile))
        accountStore.save(migrated)
        return migrated
    }

    private fun handoffNativeJournal() {
        NativeMlsBridge.pendingMembershipOperations().forEach { operation ->
            val context = JSONObject(operation.context.decodeToString())
            require(context.getInt("version") == 1) { "Неподдерживаемый контекст MLS" }
            val type = context.getString("type")
            val target = context.getString("target_nickname")
            val recipients = context.getJSONArray("recipients").strings()
            val members = context.getJSONArray("members").directoryMembers()
            val owner = context.getString("owner_nickname")
            val groupName = context.optString("group_name").takeIf(String::isNotBlank)

            groupStore.upsertGroup(operation.groupId, members, owner, groupName)
            when (type) {
                CONTEXT_CREATE_GROUP -> registrationOutbox.enqueue(
                    operation.groupId,
                    listOf(GroupMember(target)),
                )
                CONTEXT_INVITE_MEMBER ->
                    memberOutbox.enqueue(operation.groupId, GroupMember(target))
                CONTEXT_REMOVE_MEMBER -> Unit
                else -> error("Неизвестный контекст MLS")
            }
            if (operation.kind == PendingMlsMembershipKind.ADD_MEMBER) {
                if (recipients.isNotEmpty()) {
                    eventOutbox.enqueue(
                        operation.groupId,
                        KIND_COMMIT,
                        recipients,
                        operation.commitEnvelope,
                    )
                }
                eventOutbox.enqueue(
                    operation.groupId,
                    KIND_WELCOME,
                    listOf(target),
                    requireNotNull(operation.welcomeEnvelope),
                )
            } else {
                eventOutbox.enqueue(
                    operation.groupId,
                    KIND_COMMIT,
                    recipients,
                    operation.commitEnvelope,
                    removeMemberNickname = target,
                )
            }
            require(NativeMlsBridge.acknowledgeMembershipOperation(operation.operationId)) {
                "Не удалось подтвердить перенос операции MLS"
            }
        }
    }

    private fun membershipContext(
        type: String,
        ownerNickname: String,
        ownerDeviceId: String,
        targetNickname: String,
        recipients: List<String>,
        members: List<GroupDirectoryMember>,
        groupName: String? = null,
    ): ByteArray = JSONObject()
        .put("version", 1)
        .put("type", type)
        .put("owner_nickname", ownerNickname)
        .put("owner_device_id", ownerDeviceId)
        .put("target_nickname", targetNickname)
        .put("group_name", groupName)
        .put("recipients", JSONArray(recipients))
        .put(
            "members",
            JSONArray().also { output ->
                members.forEach {
                    output.put(
                        JSONObject()
                            .put("nickname", it.nickname)
                            .put("role", it.role)
                            .put("device_id", it.deviceId),
                    )
                }
            },
        )
        .toString()
        .encodeToByteArray()

    private suspend fun sendGroupMetadata(
        profile: AccountProfile,
        groupId: ByteArray,
        name: String,
        recipients: List<String>,
    ) {
        val messageId = GroupApplicationPayloadCodec.newMessageId()
        val payload = GroupApplicationPayloadCodec.encodeMetadata(messageId, name)
        val envelope = try {
            requireNotNull(NativeMlsBridge.createApplicationMessage(groupId, payload)) {
                "Не удалось зашифровать название группы"
            }
        } finally {
            payload.fill(0)
        }
        eventOutbox.enqueue(
            groupId,
            KIND_APPLICATION,
            recipients,
            envelope,
            clientEventId = messageId,
        )
        eventOutbox.retry(profile)
    }

    private fun JSONArray.strings(): List<String> =
        (0 until length()).map(::getString)

    private fun JSONArray.directoryMembers(): List<GroupDirectoryMember> =
        (0 until length()).map { index ->
            getJSONObject(index).let {
                GroupDirectoryMember(
                    nickname = it.getString("nickname"),
                    role = it.getString("role"),
                    deviceId = it.optString("device_id"),
                )
            }
        }

    private companion object {
        const val KIND_WELCOME = 1
        const val KIND_COMMIT = 2
        const val KIND_APPLICATION = 3
        const val CONTEXT_CREATE_GROUP = "create_group"
        const val KEY_PACKAGE_RESERVE = 10
        const val CONTEXT_INVITE_MEMBER = "invite_member"
        const val CONTEXT_REMOVE_MEMBER = "remove_member"
    }
}

private fun GroupMember.directory() =
    GroupDirectoryMember(nickname = nickname, role = role, deviceId = deviceId)
