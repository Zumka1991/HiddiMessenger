package ru.hiddi.messenger.network

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import ru.hiddi.messenger.security.AndroidKeystoreSecretStore
import ru.hiddi.messenger.security.NativeMlsBridge

/**
 * Durable client-side handoff between local MLS creation and server routing
 * registration. A failed HTTP request never discards MLS state; it remains
 * here, encrypted with Android Keystore, until a later retry succeeds.
 */
class GroupRegistrationOutbox(context: Context, private val api: SignalMessagingApi) {
    private val store = AndroidKeystoreSecretStore(
        context,
        fileName = "pending-group-registrations.v1",
        keyAlias = "ru.hiddi.messenger.pending-group-registrations.v1",
    )

    suspend fun create(profile: AccountProfile, members: List<GroupMember>): String {
        val deviceId = requireNotNull(profile.deviceId) {
            "Для групп нужен device_id. Перерегистрируйте этот устаревший профиль."
        }
        val groupId = requireNotNull(NativeMlsBridge.createLocalGroup(deviceId)) {
            "Не удалось создать локальное MLS-состояние"
        }
        register(profile, groupId, members)
        return groupId.b64()
    }

    suspend fun register(profile: AccountProfile, groupId: ByteArray, members: List<GroupMember>) {
        enqueue(groupId, members)
        retry(profile)
    }

    suspend fun retry(profile: AccountProfile) {
        pending().forEach { entry ->
            api.createGroup(profile, entry.groupId.decode(), entry.members)
            remove(entry.groupId)
        }
    }

    fun enqueue(groupId: ByteArray, members: List<GroupMember>) = synchronized(lock) {
        val id = groupId.b64()
        val entries = read()
        val existing = entries.firstOrNull { it.groupId == id }
        if (existing == null) {
            write(entries + PendingGroupRegistration(id, members))
        } else {
            require(existing.members == members) { "MLS-группа ожидает регистрацию с другим составом" }
        }
    }

    private fun remove(groupId: String) = synchronized(lock) {
        write(read().filterNot { it.groupId == groupId })
    }

    private fun pending(): List<PendingGroupRegistration> = synchronized(lock) { read() }

    private fun read(): List<PendingGroupRegistration> = store.read()?.decodeToString()?.let { source ->
        val values = JSONArray(source)
        (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            PendingGroupRegistration(
                item.getString("group_id"),
                item.getJSONArray("members").let { members ->
                    (0 until members.length()).map { memberIndex ->
                        members.getJSONObject(memberIndex).let { GroupMember(it.getString("nickname"), it.getString("role")) }
                    }
                },
            )
        }
    } ?: emptyList()

    private fun write(entries: List<PendingGroupRegistration>) {
        val output = JSONArray()
        entries.forEach { entry ->
            output.put(
                JSONObject()
                    .put("group_id", entry.groupId)
                    .put("members", JSONArray().also { members ->
                        entry.members.forEach { member ->
                            members.put(JSONObject().put("nickname", member.nickname).put("role", member.role))
                        }
                    }),
            )
        }
        store.write(output.toString().encodeToByteArray())
    }

    private data class PendingGroupRegistration(val groupId: String, val members: List<GroupMember>)

    private companion object {
        val lock = Any()
        fun ByteArray.b64() = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        fun String.decode() = Base64.decode(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
