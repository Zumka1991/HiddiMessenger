package ru.hiddi.desktop

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

data class HiddiProfile(
    val nickname: String,
    val displayName: String,
    val bio: String,
    val avatarVersion: String? = null,
)

data class ChatEntry(
    val peer: String,
    val text: String,
    val outgoing: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    val messageId: String? = null,
    val deliveryStatus: String = "sent",
    val attachment: AttachmentDescriptor? = null,
    val replyTo: ReplyReference? = null,
)

/**
 * The message a reply points at. [preview] is a short snapshot taken when the
 * reply is sent, so the quote still renders when the original has fallen out of
 * the local history window — at the cost of outliving a later "delete for
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

internal fun ReplyReference.toJson(): JSONObject =
    JSONObject()
        .put("message_id", messageId)
        .put("sender", sender)
        .put("preview", preview)

internal fun JSONObject.toReplyReference(): ReplyReference? {
    val messageId = optString("message_id").takeIf(String::isNotBlank) ?: return null
    val sender = optString("sender").takeIf(String::isNotBlank) ?: return null
    return ReplyReference(
        messageId = messageId,
        sender = sender,
        preview = ReplyReference.preview(optString("preview")),
    )
}

data class DeviceLinkResult(
    val nickname: String,
    val deviceNumber: Int,
)

data class RegistrationResult(
    val nickname: String,
    val recoveryKey: String,
)

data class SafetyNumberInfo(
    val value: String,
    val trusted: Boolean,
)

sealed interface AppScreen {
    data object Pairing : AppScreen
    data object Registration : AppScreen
    data object Unlock : AppScreen
    data object Messenger : AppScreen
}

enum class DesktopSection {
    Chats,
    Groups,
    Contacts,
    Settings,
}

private val sqliteUtcTimestamp =
    DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd()
        .toFormatter()

/** Accepts both RFC 3339 and SQLite UTC timestamps from older server rows. */
internal fun wireTimestampMillis(value: String): Long =
    runCatching { Instant.parse(value).toEpochMilli() }
        .getOrElse {
            LocalDateTime.parse(value, sqliteUtcTimestamp)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }
