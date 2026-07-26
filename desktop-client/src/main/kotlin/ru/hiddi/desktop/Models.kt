package ru.hiddi.desktop

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
