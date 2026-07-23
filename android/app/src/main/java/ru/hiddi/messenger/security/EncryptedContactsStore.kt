package ru.hiddi.messenger.security

import android.content.Context
import org.json.JSONArray

/**
 * Private local address book. Nicknames are encrypted as one Keystore-backed
 * document, so the server does not learn who the user chose to save.
 */
class EncryptedContactsStore(context: Context) {
    private val store = AndroidKeystoreSecretStore(
        context,
        fileName = "contacts.v1",
        keyAlias = "ru.hiddi.messenger.contacts.v1",
    )

    fun contacts(): List<String> = synchronized(lock) {
        read().sorted()
    }

    fun contains(nickname: String): Boolean = synchronized(lock) {
        normalize(nickname) in read()
    }

    fun add(nickname: String) = synchronized(lock) {
        val normalized = normalize(nickname)
        require(normalized.isNotBlank()) { "Пустой никнейм" }
        write(read() + normalized)
    }

    fun remove(nickname: String) = synchronized(lock) {
        write(read() - normalize(nickname))
    }

    private fun read(): Set<String> =
        store.read()?.decodeToString()?.let(::JSONArray)?.let { values ->
            (0 until values.length()).map(values::getString).map(::normalize).toSet()
        } ?: emptySet()

    private fun write(values: Set<String>) {
        store.write(JSONArray(values.sorted()).toString().encodeToByteArray())
    }

    private fun normalize(value: String) = value.trim().removePrefix("@").lowercase()

    private companion object {
        val lock = Any()
    }
}
