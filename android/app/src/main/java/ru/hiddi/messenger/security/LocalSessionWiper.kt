package ru.hiddi.messenger.security

import android.content.Context
import java.security.KeyStore
import java.util.Collections

/**
 * Deletes all device-local Hiddi session material after an explicit user
 * confirmation. The server-side device token must be revoked before this runs.
 */
class LocalSessionWiper(context: Context) {
    private val appContext = context.applicationContext

    fun wipe() {
        val failures = appContext.noBackupFilesDir
            .listFiles()
            .orEmpty()
            .filterNot { it.deleteRecursively() }
        check(failures.isEmpty()) {
            "Не удалось удалить локальные данные: ${failures.joinToString { it.name }}"
        }

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = Collections.list(keyStore.aliases())
        aliases
            .filter { it.startsWith(KEY_ALIAS_PREFIX) }
            .forEach(keyStore::deleteEntry)
    }

    private companion object {
        const val KEY_ALIAS_PREFIX = "ru.hiddi.messenger."
    }
}
