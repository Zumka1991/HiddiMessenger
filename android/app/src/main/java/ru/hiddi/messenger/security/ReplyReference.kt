package ru.hiddi.messenger.security

import org.json.JSONObject

/**
 * The message a reply points at. [preview] is a short snapshot taken when the
 * reply is sent, so the quote still renders when the original has fallen out of
 * the locally paged history — at the cost of outliving a later "delete for
 * everyone" of that original.
 */
data class ReplyReference(
    val messageId: String,
    val sender: String,
    val preview: String,
) {
    companion object {
        const val PREVIEW_LIMIT = 80

        /** Collapses the quoted text into the single line a quote can show. */
        fun preview(text: String): String =
            text.replace(WHITESPACE, " ").trim().take(PREVIEW_LIMIT)

        private val WHITESPACE = Regex("\\s+")
    }
}

fun ReplyReference.toJson(): JSONObject =
    JSONObject()
        .put("message_id", messageId)
        .put("sender", sender)
        .put("preview", preview)

fun JSONObject.toReplyReference(): ReplyReference? {
    val messageId = optString("message_id").takeIf(String::isNotBlank) ?: return null
    val sender = optString("sender").takeIf(String::isNotBlank) ?: return null
    return ReplyReference(
        messageId = messageId,
        sender = sender,
        preview = ReplyReference.preview(optString("preview")),
    )
}
