package ru.hiddi.messenger.security

import android.content.Context
import org.json.JSONObject

/** Locally remembers only verified public-key fingerprints, encrypted by Android Keystore. */
class TrustedSafetyNumberStore(context: Context) {
    private val store = AndroidKeystoreSecretStore(
        context,
        fileName = "trusted-safety-numbers.v1",
        keyAlias = "ru.hiddi.messenger.trusted-safety-numbers.v1",
    )

    fun isTrusted(peer: String, safetyNumber: String): Boolean = synchronized(lock) {
        read().optString(peer) == safetyNumber
    }

    fun trust(peer: String, safetyNumber: String) = synchronized(lock) {
        read().put(peer, safetyNumber).also { records ->
            store.write(records.toString().encodeToByteArray())
        }
    }

    private fun read(): JSONObject = store.read()?.decodeToString()?.let(::JSONObject) ?: JSONObject()

    private companion object { val lock = Any() }
}
