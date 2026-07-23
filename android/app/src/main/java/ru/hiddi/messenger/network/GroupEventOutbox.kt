package ru.hiddi.messenger.network

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import ru.hiddi.messenger.security.AndroidKeystoreSecretStore
import java.security.MessageDigest

/** Keystore-encrypted retry queue for opaque MLS Commit/Welcome/application events. */
class GroupEventOutbox(context: Context, private val api: SignalMessagingApi) {
    private val store = AndroidKeystoreSecretStore(
        context,
        fileName = "pending-group-events.v1",
        keyAlias = "ru.hiddi.messenger.pending-group-events.v1",
    )

    fun enqueue(groupId: ByteArray, kind: Int, recipients: List<String>, envelope: ByteArray) =
        synchronized(lock) {
            val entry = PendingGroupEvent(
                id = MessageDigest.getInstance("SHA-256")
                    .digest(groupId + byteArrayOf(kind.toByte()) + envelope)
                    .b64(),
                groupId = groupId.b64(),
                kind = kind,
                recipients = recipients.map { it.trim().removePrefix("@").lowercase() },
                envelope = envelope.b64(),
            )
            val entries = read()
            if (entries.none { it.id == entry.id }) write(entries + entry)
        }

    suspend fun retry(profile: AccountProfile) {
        read().forEach { entry ->
            api.sendGroupEvent(
                profile,
                entry.groupId.decode(),
                entry.id,
                entry.kind,
                entry.recipients,
                entry.envelope.decode(),
            )
            remove(entry.id)
        }
    }

    private fun remove(id: String) = synchronized(lock) {
        write(read().filterNot { it.id == id })
    }

    private fun read(): List<PendingGroupEvent> = store.read()?.decodeToString()?.let { source ->
        val items = JSONArray(source)
        (0 until items.length()).map { index ->
            items.getJSONObject(index).let { item ->
                PendingGroupEvent(
                    id = item.getString("id"),
                    groupId = item.getString("group_id"),
                    kind = item.getInt("kind"),
                    recipients = item.getJSONArray("recipients").let { recipients ->
                        (0 until recipients.length()).map(recipients::getString)
                    },
                    envelope = item.getString("envelope"),
                )
            }
        }
    } ?: emptyList()

    private fun write(entries: List<PendingGroupEvent>) {
        val items = JSONArray()
        entries.forEach { entry ->
            items.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("group_id", entry.groupId)
                    .put("kind", entry.kind)
                    .put("recipients", JSONArray(entry.recipients))
                    .put("envelope", entry.envelope),
            )
        }
        store.write(items.toString().encodeToByteArray())
    }

    private data class PendingGroupEvent(
        val id: String,
        val groupId: String,
        val kind: Int,
        val recipients: List<String>,
        val envelope: String,
    )

    private companion object {
        val lock = Any()
        fun ByteArray.b64() =
            Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        fun String.decode() =
            Base64.decode(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
