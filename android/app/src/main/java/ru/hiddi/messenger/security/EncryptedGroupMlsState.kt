package ru.hiddi.messenger.security

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * Android-side persistence boundary for opaque MLS group state.
 *
 * OpenMLS will own the bytes' contents in the Rust bridge.  Kotlin may only address, atomically
 * save, load, or remove those bytes; the file is encrypted with a non-exportable Keystore key.
 */
class EncryptedGroupMlsState(context: Context) {
    private val store = AndroidKeystoreSecretStore(
        context,
        fileName = "group-mls-state.v1",
        keyAlias = "ru.hiddi.messenger.group-mls-state.v1",
    )

    fun load(groupId: String): ByteArray? = synchronized(lock) {
        read().optString(groupId).takeIf(String::isNotBlank)?.let(::decode)
    }

    fun save(groupId: String, state: ByteArray) = synchronized(lock) {
        require(groupId.matches(GROUP_ID)) { "Некорректный идентификатор группы" }
        require(state.isNotEmpty() && state.size <= MAX_STATE_BYTES) { "Некорректное MLS-состояние" }
        read().put(groupId, encode(state)).also { records ->
            store.write(records.toString().encodeToByteArray())
        }
    }

    fun remove(groupId: String) = synchronized(lock) {
        read().also { records ->
            if (records.remove(groupId) != null) store.write(records.toString().encodeToByteArray())
        }
    }

    private fun read(): JSONObject = store.read()?.decodeToString()?.let(::JSONObject) ?: JSONObject()

    private companion object {
        val lock = Any()
        val GROUP_ID = Regex("[0-9a-f-]{36}")
        const val MAX_STATE_BYTES = 8 * 1024 * 1024
        fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
