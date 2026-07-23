package ru.hiddi.messenger.security

import android.content.Context
import java.security.SecureRandom

/**
 * Keeps the MLS database key wrapped by a non-exportable Android Keystore key.
 *
 * Rust receives the key only for the current process, after this store decrypts
 * it. The raw key is never placed in preferences, a database, a backup, or logs.
 */
class MlsStorageKeyStore(context: Context) {
    private val store = AndroidKeystoreSecretStore(
        context,
        fileName = "group-mls-storage-key.v1",
        keyAlias = "ru.hiddi.messenger.group-mls-storage-key.v1",
    )

    fun loadOrCreate(): ByteArray = synchronized(lock) {
        store.read()?.also { key ->
            require(key.size == KEY_BYTES) { "Некорректный ключ MLS-хранилища" }
        } ?: ByteArray(KEY_BYTES).also { key ->
            SecureRandom().nextBytes(key)
            store.write(key)
        }
    }

    private companion object {
        val lock = Any()
        const val KEY_BYTES = 64
    }
}
