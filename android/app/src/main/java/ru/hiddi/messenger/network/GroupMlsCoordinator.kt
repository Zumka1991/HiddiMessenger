package ru.hiddi.messenger.network

import android.content.Context
import ru.hiddi.messenger.security.EncryptedGroupChatStore
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
                listOf(current.nickname, invitedNickname),
                ownerNickname = current.nickname,
            )
            registrationOutbox.register(
                current,
                groupId,
                listOf(GroupMember(invitedNickname)),
            )
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
                        details.members.map(GroupMember::nickname),
                        ownerNickname = details.ownerNickname,
                    )
                    changedGroups += groupId
                }
                KIND_COMMIT -> {
                    require(NativeMlsBridge.processCommit(event.groupId, event.envelope)) {
                        "OpenMLS отклонил Commit"
                    }
                    val details = api.groupDetails(profile, event.groupId)
                    groupStore.replaceMembers(
                        event.groupId,
                        details.members.map(GroupMember::nickname),
                        details.ownerNickname,
                    )
                    changedGroups += event.groupId
                }
                KIND_APPLICATION -> {
                    val plaintext = requireNotNull(
                        NativeMlsBridge.processApplicationMessage(event.groupId, event.envelope),
                    ) { "OpenMLS отклонил group message" }
                    groupStore.appendIncoming(
                        event.groupId,
                        event.eventId,
                        event.senderNickname,
                        plaintext.decodeToString(),
                        event.createdAt,
                    )
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
        val envelope = requireNotNull(
            NativeMlsBridge.createApplicationMessage(groupId, trimmed.encodeToByteArray()),
        ) { "Не удалось зашифровать group message" }
        eventOutbox.enqueue(groupId, KIND_APPLICATION, recipients, envelope)
        groupStore.appendOutgoing(groupId, profile.nickname, trimmed)
        eventOutbox.retry(profile)
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
        require(group.ownerNickname == current.nickname) {
            "Приглашать участников пока может только создатель"
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
            group.members + normalized,
            ownerNickname = group.ownerNickname,
        )
        memberOutbox.register(current, groupId, GroupMember(normalized))
        eventOutbox.retry(current)
        val details = api.groupDetails(current, groupId)
        groupStore.replaceMembers(
            groupId,
            details.members.map(GroupMember::nickname),
            details.ownerNickname,
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
