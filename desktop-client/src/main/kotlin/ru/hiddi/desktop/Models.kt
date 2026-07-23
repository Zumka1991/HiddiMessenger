package ru.hiddi.desktop

data class HiddiProfile(
    val nickname: String,
    val displayName: String,
    val bio: String,
)

data class ChatEntry(
    val peer: String,
    val text: String,
    val outgoing: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)

data class DeviceLinkResult(
    val nickname: String,
    val deviceNumber: Int,
)

sealed interface AppScreen {
    data object Pairing : AppScreen
    data object Unlock : AppScreen
    data object Messenger : AppScreen
}
