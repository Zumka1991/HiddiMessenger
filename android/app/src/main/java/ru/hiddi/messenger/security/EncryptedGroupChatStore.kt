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

    fun upsertGroup(groupId: ByteArray, members: List<String>) = synchronized(lock) {
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
                    .put("members", JSONArray(members.distinct()))
                    .put("messages", JSONArray()),
            )
        } else {
            existing.put("members", JSONArray((existing.members() + members).distinct()))
        }
        write(root)
    }

    fun groups(): List<LocalGroupChat> = synchronized(lock) {
        val groups = read().getJSONArray("groups")
        (0 until groups.length()).map { index ->
            groups.getJSONObject(index).let { group ->
                LocalGroupChat(
                    groupId = group.getString("group_id").decode(),
                    members = group.members(),
                    messages = group.getJSONArray("messages").messages(),
                )
            }
        }
    }

    fun appendIncoming(
        groupId: ByteArray,
        eventId: String,
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
                    .put("sender", sender)
                    .put("text", plaintext)
                    .put("outgoing", false)
                    .put("time", createdAt),
            )
            write(root)
        }
    }

    fun appendOutgoing(groupId: ByteArray, sender: String, plaintext: String) = synchronized(lock) {
        val root = read()
        val group = root.group(groupId) ?: error("Неизвестная локальная MLS-группа")
        group.getJSONArray("messages").put(
            JSONObject()
                .put("sender", sender)
                .put("text", plaintext)
                .put("outgoing", true)
                .put("time", Instant.now().toString()),
        )
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

    private fun JSONArray.messages(): List<GroupChatMessage> = (0 until length()).map { index ->
        getJSONObject(index).let { message ->
            GroupChatMessage(
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
    val members: List<String>,
    val messages: List<GroupChatMessage>,
)

data class GroupChatMessage(
    val sender: String,
    val text: String,
    val outgoing: Boolean,
    val time: String,
)
