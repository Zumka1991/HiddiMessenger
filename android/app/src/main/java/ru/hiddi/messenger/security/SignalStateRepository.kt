package ru.hiddi.messenger.security

import android.content.Context
import org.json.JSONObject

/**
 * Persists all client-side Signal state. The server never receives this data.
 * Key material and mutable ratchet metadata use independent Android Keystore
 * encryption keys so a partial write cannot expose either plaintext.
 */
class SignalStateRepository(context: Context) {
    private val keyRecords = AndroidKeystoreSecretStore(context)
    private val ratchetRecords = AndroidKeystoreSecretStore(
        context,
        fileName = "signal-ratchet-state.v1",
        keyAlias = "ru.hiddi.messenger.signal-ratchet-state.v1",
    )

    fun load(): SignalState {
        val keys = keyRecords.read()?.let(SignalPrivateState::decode)
            ?: error("На этом устройстве ещё нет Signal-ключей")
        val ratchet = ratchetRecords.read()?.decodeToString()?.let(::JSONObject) ?: JSONObject()
        return SignalState(keys, ratchet)
    }

    fun save(state: SignalState) {
        keyRecords.write(state.keys.encode())
        ratchetRecords.write(state.ratchet.toString().encodeToByteArray())
    }
}

data class SignalState(
    val keys: SignalPrivateState,
    /** Serialized session and trusted-identity metadata; always encrypted at rest. */
    val ratchet: JSONObject,
)
