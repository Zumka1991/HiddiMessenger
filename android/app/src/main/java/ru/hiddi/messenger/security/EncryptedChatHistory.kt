package ru.hiddi.messenger.security

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local plaintext history. It never leaves the device and is Keystore-encrypted at rest. */
class EncryptedChatHistory(context: Context) {
    private val store = AndroidKeystoreSecretStore(
        context,
        fileName = "chat-history.v1",
        keyAlias = "ru.hiddi.messenger.chat-history.v1",
    )

    fun messagesWith(nickname: String): List<ChatHistoryItem> {
        return synchronized(historyLock) {
            val entries = read()
            (0 until entries.length()).map { entries.getJSONObject(it) }
                .filter { it.getString("peer") == nickname }
                .map {
                    ChatHistoryItem(
                        it.getString("peer"),
                        it.getString("text"),
                        it.getBoolean("outgoing"),
                        it.getString("time"),
                        it.optBoolean("unread", false),
                        it.optJSONObject("attachment")?.toAttachmentDescriptor(),
                        it.optString("message_id").takeIf(String::isNotBlank),
                        it.optString("delivery_status", "sent"),
                    )
                }
        }
    }

    fun append(item: ChatHistoryItem) = synchronized(historyLock) {
        val entries = read()
        entries.put(
            JSONObject()
                .put("peer", item.peer)
                .put("text", item.text)
                .put("outgoing", item.outgoing)
                .put("time", item.time)
                .put("unread", item.unread)
                .apply { item.messageId?.let { put("message_id", it) } }
                .put("delivery_status", item.deliveryStatus)
                .apply { item.attachment?.let { put("attachment", it.toJson()) } },
        )
        store.write(entries.toString().encodeToByteArray())
    }

    fun unreadCount(nickname: String): Int = synchronized(historyLock) {
        val entries = read()
        (0 until entries.length()).count { index ->
            val item = entries.getJSONObject(index)
            item.getString("peer") == nickname && item.optBoolean("unread", false)
        }
    }

    fun markRead(nickname: String) = synchronized(historyLock) {
        val entries = read()
        var changed = false
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            if (item.getString("peer") == nickname && item.optBoolean("unread", false)) {
                item.put("unread", false)
                changed = true
            }
        }
        if (changed) store.write(entries.toString().encodeToByteArray())
    }

    fun updateDeliveryStatus(messageId: String, status: String) = synchronized(historyLock) {
        val entries = read()
        var changed = false
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            if (item.optString("message_id") == messageId && item.optString("delivery_status", "sent") != status) {
                item.put("delivery_status", status)
                changed = true
            }
        }
        if (changed) store.write(entries.toString().encodeToByteArray())
    }

    /** Removes this dialogue only from this device and returns ciphertext blobs safe to discard locally. */
    fun clearConversation(nickname: String): List<AttachmentDescriptor> = synchronized(historyLock) {
        val entries = read()
        val retained = JSONArray()
        val attachments = mutableListOf<AttachmentDescriptor>()
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            if (item.getString("peer") == nickname) {
                item.optJSONObject("attachment")?.toAttachmentDescriptor()?.let { descriptor ->
                    attachments += descriptor
                    descriptor.preview?.let(attachments::add)
                }
            } else {
                retained.put(item)
            }
        }
        if (retained.length() != entries.length()) store.write(retained.toString().encodeToByteArray())
        attachments.distinctBy { it.attachmentId }
    }

    fun peers(): List<String> = synchronized(historyLock) {
        read().let { entries ->
            (0 until entries.length()).map { entries.getJSONObject(it).getString("peer") }.asReversed().distinct()
        }
    }

    fun pendingIncomingAttachments(): List<AttachmentDescriptor> = synchronized(historyLock) {
        val entries = read()
        (0 until entries.length()).mapNotNull { index ->
            entries.getJSONObject(index).takeUnless { it.getBoolean("outgoing") }
                ?.optJSONObject("attachment")?.toAttachmentDescriptor()
        }.flatMap { listOfNotNull(it.preview, it) }
            .distinctBy { it.attachmentId }
    }

    private fun read(): JSONArray = store.read()?.decodeToString()?.let(::JSONArray) ?: JSONArray()

    private companion object {
        val historyLock = Any()
    }
}

data class ChatHistoryItem(
    val peer: String,
    val text: String,
    val outgoing: Boolean,
    val time: String,
    val unread: Boolean = false,
    val attachment: AttachmentDescriptor? = null,
    /** Server id is ciphertext metadata, never message plaintext. */
    val messageId: String? = null,
    /** sent, delivered, or read. Only meaningful for outgoing messages. */
    val deliveryStatus: String = "sent",
)

private fun AttachmentDescriptor.toJson(): JSONObject = JSONObject()
    .put("attachment_id", attachmentId)
    .put("binding_id", bindingId)
    .put("kind", kind)
    .put("key", key)
    .put("iv", iv)
    .put("mime", mimeType)
    .put("plain_size", plainSize)
    .apply {
        durationMs?.let { put("duration_ms", it) }
        preview?.let { put("preview", it.toJson()) }
    }

private fun JSONObject.toAttachmentDescriptor(): AttachmentDescriptor = AttachmentDescriptor(
    attachmentId = getString("attachment_id"),
    bindingId = getString("binding_id"),
    kind = getString("kind"),
    key = getString("key"),
    iv = getString("iv"),
    mimeType = getString("mime"),
    plainSize = getInt("plain_size"),
    durationMs = optLong("duration_ms").takeIf { has("duration_ms") },
    preview = optJSONObject("preview")?.toAttachmentDescriptor(),
)
