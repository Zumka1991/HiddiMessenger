package ru.hiddi.desktop

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
)

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
