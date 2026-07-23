package ru.hiddi.messenger.network

import android.content.Context
import ru.hiddi.messenger.security.EncryptedGroupChatStore
import ru.hiddi.messenger.security.GroupDirectoryMember
import ru.hiddi.messenger.security.LocalGroupChat
import ru.hiddi.messenger.security.NativeMlsBridge

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

    suspend fun prepare(profile: AccountProfile): AccountProfile {
        val current = ensureDeviceId(profile)
        synchronize(current)
        publishKeyPackage(current)
        return current
    }

    suspend fun synchronize(profile: AccountProfile): List<ByteArray> {
        val current = ensureDeviceId(profile)
        val deletedGroups = processGroupDeletions(current)
        registrationOutbox.retry(current)
        memberOutbox.retry(current)
        eventOutbox.retry(current)
        return (deletedGroups + processPendingEvents(current))
            .distinctBy { it.contentHashCode() }
    }

    suspend fun createTwoPartyGroup(profile: AccountProfile, invitedNickname: String): ByteArray {
        val current = ensureDeviceId(profile)
        val deviceId = requireNotNull(current.deviceId)
        val groupId = requireNotNull(NativeMlsBridge.createLocalGroup(deviceId)) {
            "Не удалось создать локальную MLS-группу"
        }
        var memberAdded = false
        try {
            val keyPackage = api.takeMlsKeyPackage(current, invitedNickname)
            val output = requireNotNull(NativeMlsBridge.addMember(groupId, keyPackage)) {
                "OpenMLS отклонил KeyPackage приглашённого"
            }
            memberAdded = true
            eventOutbox.enqueue(
                groupId,
                KIND_WELCOME,
                listOf(invitedNickname),
                output.welcomeEnvelope,
            )
            groupStore.upsertGroup(
                groupId,
                listOf(
                    GroupDirectoryMember(current.nickname, "owner", deviceId),
                    GroupDirectoryMember(invitedNickname, "member", ""),
                ),
                ownerNickname = current.nickname,
            )
            registrationOutbox.register(
                current,
                groupId,
                listOf(GroupMember(invitedNickname)),
            )
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
        val keyPackage = requireNotNull(NativeMlsBridge.createKeyPackage(deviceId)) {
            "Не удалось создать MLS KeyPackage"
        }
        api.uploadMlsKeyPackage(profile, keyPackage)
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
                        groupStore.removeGroup(event.groupId)
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
                        is GroupApplicationPayload.Delete -> groupStore.deleteMessage(
                            event.groupId,
                            payload.messageId,
                            expectedSender = event.senderNickname,
                        )
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
        groupStore.deleteMessage(groupId, messageId)
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
        val output = requireNotNull(NativeMlsBridge.addMember(groupId, keyPackage)) {
            "OpenMLS отклонил KeyPackage приглашённого"
        }
        val existingRecipients = group.members.filterNot { it == current.nickname }
        if (existingRecipients.isNotEmpty()) {
            eventOutbox.enqueue(
                groupId,
                KIND_COMMIT,
                existingRecipients,
                output.commitEnvelope,
            )
        }
        eventOutbox.enqueue(
            groupId,
            KIND_WELCOME,
            listOf(normalized),
            output.welcomeEnvelope,
        )
        groupStore.upsertGroup(
            groupId,
            group.memberDetails + GroupDirectoryMember(normalized, "member", ""),
            ownerNickname = group.ownerNickname,
        )
        memberOutbox.register(current, groupId, GroupMember(normalized))
        eventOutbox.retry(current)
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
        val commit = requireNotNull(
            NativeMlsBridge.removeMember(groupId, target.deviceId),
        ) { "OpenMLS не создал Remove Commit" }
        val recipients = details.members.map(GroupMember::nickname)
            .filterNot { it == current.nickname }
        eventOutbox.enqueue(
            groupId,
            KIND_COMMIT,
            recipients,
            commit,
            removeMemberNickname = normalized,
        )
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
        groupStore.clearHistory(groupId)
    }

    suspend fun deleteOwnedGroup(profile: AccountProfile, groupId: ByteArray) {
        val group = groupStore.groups().firstOrNull { it.groupId.contentEquals(groupId) }
            ?: error("Неизвестная локальная MLS-группа")
        require(group.ownerNickname == profile.nickname) {
            "Удалить группу может только создатель"
        }
        api.deleteGroup(profile, groupId)
        NativeMlsBridge.deleteLocalGroup(groupId)
        groupStore.removeGroup(groupId)
    }

    private suspend fun processGroupDeletions(profile: AccountProfile): List<ByteArray> {
        val deleted = mutableListOf<ByteArray>()
        api.pendingGroupDeletions(profile).forEach { deletion ->
            NativeMlsBridge.deleteLocalGroup(deletion.groupId)
            groupStore.removeGroup(deletion.groupId)
            api.acknowledgeGroupDeletion(profile, deletion.deletionId)
            deleted += deletion.groupId
        }
        return deleted
    }

    private suspend fun ensureDeviceId(profile: AccountProfile): AccountProfile {
        profile.deviceId?.let { return profile }
        val migrated = profile.copy(deviceId = api.currentDeviceId(profile))
        accountStore.save(migrated)
        return migrated
    }

    private companion object {
        const val KIND_WELCOME = 1
        const val KIND_COMMIT = 2
        const val KIND_APPLICATION = 3
    }
}

private fun GroupMember.directory() =
    GroupDirectoryMember(nickname = nickname, role = role, deviceId = deviceId)
