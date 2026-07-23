package ru.hiddi.messenger.network

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom

/** Versioned application payload encrypted inside an OpenMLS application message. */
sealed interface GroupApplicationPayload {
    data class Text(val messageId: String?, val text: String) : GroupApplicationPayload
    data class Delete(val messageId: String) : GroupApplicationPayload
}

object GroupApplicationPayloadCodec {
    private const val PREFIX = "HIDDI_GROUP_V1:"

    fun newMessageId(): String =
        Base64.encodeToString(
            ByteArray(16).also(SecureRandom()::nextBytes),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

    fun encodeText(messageId: String, text: String): ByteArray =
        encode(JSONObject().put("type", "text").put("message_id", messageId).put("text", text))

    fun encodeDelete(messageId: String): ByteArray =
        encode(JSONObject().put("type", "delete").put("message_id", messageId))

    fun decode(plaintext: String): GroupApplicationPayload {
        if (!plaintext.startsWith(PREFIX)) return GroupApplicationPayload.Text(null, plaintext)
        val payload = JSONObject(plaintext.removePrefix(PREFIX))
        require(payload.optInt("version") == 1) { "Неподдерживаемая версия group payload" }
        val messageId = payload.getString("message_id")
        require(runCatching {
            Base64.decode(
                messageId,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            ).size in 16..64
        }.getOrDefault(false)) { "Некорректный group message id" }
        return when (payload.getString("type")) {
            "text" -> GroupApplicationPayload.Text(messageId, payload.getString("text"))
            "delete" -> GroupApplicationPayload.Delete(messageId)
            else -> error("Неизвестный тип group payload")
        }
    }

    private fun encode(payload: JSONObject): ByteArray =
        (PREFIX + payload.put("version", 1).toString()).encodeToByteArray()
}
