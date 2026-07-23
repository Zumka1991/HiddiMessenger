package ru.hiddi.messenger

enum class ServerConnection {
    CHECKING,
    ONLINE,
    OFFLINE;

    companion object {
        fun fromWire(value: String?): ServerConnection = when (value) {
            MessagingService.STATE_ONLINE -> ONLINE
            MessagingService.STATE_OFFLINE -> OFFLINE
            else -> CHECKING
        }
    }
}
