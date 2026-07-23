package ru.hiddi.messenger.security

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Keystore-encrypted local group directory and plaintext history. */
class EncryptedGroupChatStore(context: Context) {
    private val store = AndroidKeystoreSecretStore(
        context,
        fileName = "group-chats.v1",
        keyAlias = "ru.hiddi.messenger.group-chats.v1",
    )

    fun upsertGroup(
        groupId: ByteArray,
        members: List<GroupDirectoryMember>,
        ownerNickname: String,
    ) = synchronized(lock) {
        val root = read()
        val id = groupId.b64()
        val groups = root.getJSONArray("groups")
        val existing = (0 until groups.length())
            .map(groups::getJSONObject)
            .firstOrNull { it.getString("group_id") == id }
        if (existing == null) {
            groups.put(
                JSONObject()
                    .put("group_id", id)
                    .put("owner_nickname", ownerNickname)
                    .put("members", JSONArray(members.map(GroupDirectoryMember::nickname).distinct()))
                    .put("member_details", members.memberJson())
                    .put("messages", JSONArray()),
            )
        } else {
            if (existing.optString("owner_nickname").isBlank()) {
                existing.put("owner_nickname", ownerNickname)
            }
            val merged = (existing.memberDetails() + members)
                .associateBy(GroupDirectoryMember::nickname)
                .values
                .toList()
            existing.put("members", JSONArray(merged.map(GroupDirectoryMember::nickname)))
            existing.put("member_details", merged.memberJson())
        }
        write(root)
    }

    fun groups(): List<LocalGroupChat> = synchronized(lock) {
        val groups = read().getJSONArray("groups")
        (0 until groups.length()).map { index ->
            groups.getJSONObject(index).let { group ->
                LocalGroupChat(
                    groupId = group.getString("group_id").decode(),
                    ownerNickname = group.optString("owner_nickname")
                        .takeIf(String::isNotBlank)
                        ?: group.members().first(),
                    memberDetails = group.memberDetails(),
                    messages = group.getJSONArray("messages").messages(),
                )
            }
        }
    }

    fun appendIncoming(
        groupId: ByteArray,
        eventId: String,
        messageId: String?,
        sender: String,
        plaintext: String,
        createdAt: String,
    ) = synchronized(lock) {
        val root = read()
        val group = root.group(groupId) ?: error("Неизвестная локальная MLS-группа")
        val messages = group.getJSONArray("messages")
        if ((0 until messages.length()).none { messages.getJSONObject(it).optString("event_id") == eventId }) {
            messages.put(
                JSONObject()
                    .put("event_id", eventId)
                    .put("message_id", messageId)
                    .put("sender", sender)
                    .put("text", plaintext)
                    .put("outgoing", false)
                    .put("time", createdAt),
            )
            write(root)
        }
    }

    fun appendOutgoing(
        groupId: ByteArray,
        messageId: String,
        sender: String,
        plaintext: String,
    ) = synchronized(lock) {
        val root = read()
        val group = root.group(groupId) ?: error("Неизвестная локальная MLS-группа")
        group.getJSONArray("messages").put(
            JSONObject()
                .put("message_id", messageId)
                .put("sender", sender)
                .put("text", plaintext)
                .put("outgoing", true)
                .put("time", Instant.now().toString()),
        )
        write(root)
    }

    fun deleteMessage(
        groupId: ByteArray,
        messageId: String,
        expectedSender: String? = null,
    ): Boolean = synchronized(lock) {
        val root = read()
        val group = root.group(groupId) ?: error("Неизвестная локальная MLS-группа")
        val messages = group.getJSONArray("messages")
        val retained = JSONArray()
        var deleted = false
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            val matches = message.optString("message_id") == messageId &&
                (expectedSender == null || message.getString("sender") == expectedSender)
            if (matches) {
                deleted = true
            } else {
                retained.put(message)
            }
        }
        if (deleted) {
            group.put("messages", retained)
            write(root)
        }
        deleted
    }

    fun clearHistory(groupId: ByteArray) = synchronized(lock) {
        val root = read()
        val group = root.group(groupId) ?: error("Неизвестная локальная MLS-группа")
        group.put("messages", JSONArray())
        write(root)
    }

    fun replaceMembers(
        groupId: ByteArray,
        members: List<GroupDirectoryMember>,
        ownerNickname: String,
    ) = synchronized(lock) {
        val root = read()
        val group = root.group(groupId) ?: error("Неизвестная локальная MLS-группа")
        group.put("owner_nickname", ownerNickname)
        group.put("members", JSONArray(members.map(GroupDirectoryMember::nickname).distinct()))
        group.put("member_details", members.memberJson())
        write(root)
    }

    fun removeGroup(groupId: ByteArray) = synchronized(lock) {
        val root = read()
        val groups = root.getJSONArray("groups")
        val retained = JSONArray()
        val id = groupId.b64()
        for (index in 0 until groups.length()) {
            val group = groups.getJSONObject(index)
            if (group.getString("group_id") != id) retained.put(group)
        }
        root.put("groups", retained)
        write(root)
    }

    private fun read(): JSONObject = store.read()?.decodeToString()?.let(::JSONObject)
        ?: JSONObject().put("groups", JSONArray())

    private fun write(root: JSONObject) = store.write(root.toString().encodeToByteArray())

    private fun JSONObject.group(groupId: ByteArray): JSONObject? {
        val id = groupId.b64()
        val groups = getJSONArray("groups")
        return (0 until groups.length()).map(groups::getJSONObject)
            .firstOrNull { it.getString("group_id") == id }
    }

    private fun JSONObject.members(): List<String> = getJSONArray("members").let { members ->
        (0 until members.length()).map(members::getString)
    }

    private fun JSONObject.memberDetails(): List<GroupDirectoryMember> {
        val owner = optString("owner_nickname")
        val details = optJSONArray("member_details") ?: return members().map { nickname ->
            GroupDirectoryMember(
                nickname = nickname,
                role = if (nickname == owner) "owner" else "member",
                deviceId = "",
            )
        }
        return (0 until details.length()).map { index ->
            details.getJSONObject(index).let {
                GroupDirectoryMember(
                    nickname = it.getString("nickname"),
                    role = it.getString("role"),
                    deviceId = it.optString("device_id"),
                )
            }
        }
    }

    private fun List<GroupDirectoryMember>.memberJson() = JSONArray().also { output ->
        forEach {
            output.put(
                JSONObject()
                    .put("nickname", it.nickname)
                    .put("role", it.role)
                    .put("device_id", it.deviceId),
            )
        }
    }

    private fun JSONArray.messages(): List<GroupChatMessage> = (0 until length()).map { index ->
        getJSONObject(index).let { message ->
            GroupChatMessage(
                messageId = message.optString("message_id").takeIf(String::isNotBlank),
                sender = message.getString("sender"),
                text = message.getString("text"),
                outgoing = message.getBoolean("outgoing"),
                time = message.getString("time"),
            )
        }
    }

    private companion object {
        val lock = Any()
        fun ByteArray.b64() =
            Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        fun String.decode() =
            Base64.decode(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

data class LocalGroupChat(
    val groupId: ByteArray,
    val ownerNickname: String,
    val memberDetails: List<GroupDirectoryMember>,
    val messages: List<GroupChatMessage>,
) {
    val members: List<String> get() = memberDetails.map(GroupDirectoryMember::nickname)
}

data class GroupDirectoryMember(
    val nickname: String,
    val role: String,
    val deviceId: String,
)

data class GroupChatMessage(
    val messageId: String?,
    val sender: String,
    val text: String,
    val outgoing: Boolean,
    val time: String,
)
