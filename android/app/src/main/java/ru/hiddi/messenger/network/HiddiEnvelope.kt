package ru.hiddi.messenger.network

import org.json.JSONObject
import ru.hiddi.messenger.security.EncryptedAttachmentStore
import ru.hiddi.messenger.security.ReplyReference
import ru.hiddi.messenger.security.toJson
import ru.hiddi.messenger.security.toReplyReference

/**
 * Envelope types carried inside a personal-chat plaintext. A plaintext without
 * one of these is the message itself, which is how every older client speaks.
 */
object HiddiEnvelope {
    /** Own-device journal entry for a single message this account sent. */
    const val SELF_SYNC = "hiddi.sync.v1"

    /** Own-device history snapshot delivered to a newly linked device. */
    const val HISTORY_SYNC = "hiddi.sync.batch.v1"

    /**
     * Wrapper that carries reply metadata around an ordinary message body. It is
     * only used when there is something to carry, so plain conversations keep
     * travelling as bare text and stay readable to older clients.
     */
    const val MESSAGE = "hiddi.msg.v2"

    /** Shown instead of raw JSON when a peer uses a newer envelope. */
    const val UNSUPPORTED_TEXT = "Сообщение не поддерживается этой версией"

    private const val PREFIX = "hiddi."

    private val KNOWN =
        setOf(SELF_SYNC, HISTORY_SYNC, MESSAGE, EncryptedAttachmentStore.ATTACHMENT_TYPE)

    /** A message body together with the reply metadata that wrapped it. */
    data class Body(val text: String, val replyTo: ReplyReference? = null)

    /**
     * Wraps [body] only when there is metadata to attach. [body] stays exactly
     * what an older client would have sent, so an attachment envelope nests
     * inside the wrapper untouched.
     */
    fun wrap(body: String, replyTo: ReplyReference?): String =
        if (replyTo == null) {
            body
        } else {
            JSONObject()
                .put("type", MESSAGE)
                .put("body", body)
                .put("reply_to", replyTo.toJson())
                .toString()
        }

    /** Inverse of [wrap]. A plaintext that is not a wrapper is its own body. */
    fun unwrap(plaintext: String): Body {
        val payload = runCatching { JSONObject(plaintext) }.getOrNull()
        if (payload?.optString("type") != MESSAGE) return Body(plaintext)
        return Body(
            text = payload.optString("body"),
            replyTo = payload.optJSONObject("reply_to")?.toReplyReference(),
        )
    }

    /**
     * True when the payload announces a Hiddi envelope this build cannot
     * interpret — a newer peer. Such a message is shown as a placeholder rather
     * than as the raw JSON the sender never typed.
     */
    fun isUnsupported(payload: JSONObject?): Boolean {
        val type = payload?.optString("type")?.takeIf(String::isNotBlank) ?: return false
        return type.startsWith(PREFIX) && type !in KNOWN
    }
}
