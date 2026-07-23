package ru.hiddi.messenger.network

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import ru.hiddi.messenger.security.AndroidKeystoreSecretStore

/** Durable routing update that must complete before Commit/Welcome delivery. */
class GroupMemberOutbox(context: Context, private val api: SignalMessagingApi) {
    private val store = AndroidKeystoreSecretStore(
        context,
        fileName = "pending-group-members.v1",
        keyAlias = "ru.hiddi.messenger.pending-group-members.v1",
    )

    suspend fun register(
        profile: AccountProfile,
        groupId: ByteArray,
        member: GroupMember,
    ) {
        enqueue(groupId, member)
        retry(profile)
    }

    suspend fun retry(profile: AccountProfile) {
        pending().forEach { entry ->
            api.addGroupMember(profile, entry.groupId.decode(), entry.member)
            remove(entry.id)
        }
    }

    private fun enqueue(groupId: ByteArray, member: GroupMember) = synchronized(lock) {
        val entry = PendingGroupMember(
            id = "${groupId.b64()}:${member.nickname}:${member.role}",
            groupId = groupId.b64(),
            member = member.copy(nickname = member.nickname.trim().removePrefix("@").lowercase()),
        )
        val entries = read()
        if (entries.none { it.id == entry.id }) write(entries + entry)
    }

    private fun pending(): List<PendingGroupMember> = synchronized(lock) { read() }

    private fun remove(id: String) = synchronized(lock) {
        write(read().filterNot { it.id == id })
    }

    private fun read(): List<PendingGroupMember> = store.read()?.decodeToString()?.let { source ->
        val entries = JSONArray(source)
        (0 until entries.length()).map { index ->
            entries.getJSONObject(index).let { item ->
                PendingGroupMember(
                    id = item.getString("id"),
                    groupId = item.getString("group_id"),
                    member = GroupMember(
                        nickname = item.getString("nickname"),
                        role = item.getString("role"),
                    ),
                )
            }
        }
    } ?: emptyList()

    private fun write(entries: List<PendingGroupMember>) {
        val output = JSONArray()
        entries.forEach { entry ->
            output.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("group_id", entry.groupId)
                    .put("nickname", entry.member.nickname)
                    .put("role", entry.member.role),
            )
        }
        store.write(output.toString().encodeToByteArray())
    }

    private data class PendingGroupMember(
        val id: String,
        val groupId: String,
        val member: GroupMember,
    )

    private companion object {
        val lock = Any()
        fun ByteArray.b64() =
            Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        fun String.decode() =
            Base64.decode(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
