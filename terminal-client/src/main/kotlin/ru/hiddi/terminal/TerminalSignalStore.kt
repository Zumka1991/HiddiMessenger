package ru.hiddi.terminal

import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/** In-memory Signal store which snapshots all durable state back into the encrypted vault. */
class TerminalSignalStore(private val state: JSONObject) : SignalProtocolStore {
    private val identity = IdentityKeyPair(state.getString("identity").base64UrlDecode())
    private val registrationId = state.getInt("registration_id")
    private val preKeys: MutableMap<Int, PreKeyRecord> = state.getJSONArray("one_time_prekeys")
        .records { encoded -> PreKeyRecord(encoded.base64UrlDecode()) }
        .associateByTo(mutableMapOf()) { record -> record.id }
    private val signedPreKeys = mutableMapOf(1 to SignedPreKeyRecord(state.getString("signed_prekey").base64UrlDecode()))
    private val kyberPreKeys: MutableMap<Int, KyberPreKeyRecord> = state.getJSONArray("kyber_one_time_prekeys")
        .records { encoded -> KyberPreKeyRecord(encoded.base64UrlDecode()) }
        .associateByTo(mutableMapOf()) { record -> record.id }
    private val kyberSigned = KyberPreKeyRecord(state.getString("kyber_signed_prekey").base64UrlDecode())
    private val sessions = state.optJSONObject("sessions") ?: JSONObject()
    private val remoteIdentities = state.optJSONObject("remote_identities") ?: JSONObject()

    override fun getIdentityKeyPair(): IdentityKeyPair = identity
    override fun getLocalRegistrationId(): Int = registrationId
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val key = addressKey(address)
        val previous = remoteIdentities.optString(key).takeIf(String::isNotBlank)
        remoteIdentities.put(key, identityKey.serialize().base64Url())
        return if (previous == null || previous.base64UrlDecode().contentEquals(identityKey.serialize())) {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }
    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val previous = remoteIdentities.optString(addressKey(address)).takeIf(String::isNotBlank) ?: return true
        return previous.base64UrlDecode().contentEquals(identityKey.serialize())
    }
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = remoteIdentities.optString(addressKey(address))
        .takeIf(String::isNotBlank)?.let { IdentityKey(it.base64UrlDecode()) }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord = preKeys[preKeyId] ?: throw InvalidKeyIdException("no prekey $preKeyId")
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) { preKeys[preKeyId] = record }
    override fun containsPreKey(preKeyId: Int): Boolean = preKeys.containsKey(preKeyId)
    override fun removePreKey(preKeyId: Int) { preKeys.remove(preKeyId) }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? = sessions.optString(addressKey(address)).takeIf(String::isNotBlank)?.let { SessionRecord(it.base64UrlDecode()) }
    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> = addresses.map {
        loadSession(it) ?: throw NoSessionException(it, "no session for $it")
    }.toMutableList()
    override fun getSubDeviceSessions(name: String): MutableList<Int> = sessions.keySet().asSequence()
        .mapNotNull { key -> key.substringBeforeLast('|').takeIf { it == name }?.let { key.substringAfterLast('|').toInt() } }
        .filter { it != 1 }.toMutableList()
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) { sessions.put(addressKey(address), record.serialize().base64Url()) }
    override fun containsSession(address: SignalProtocolAddress): Boolean = sessions.has(addressKey(address))
    override fun deleteSession(address: SignalProtocolAddress) { sessions.remove(addressKey(address)) }
    override fun deleteAllSessions(name: String) { sessions.keySet().filter { it.substringBeforeLast('|') == name }.forEach(sessions::remove) }

    override fun loadSignedPreKey(id: Int): SignedPreKeyRecord = signedPreKeys[id] ?: throw InvalidKeyIdException("no signed prekey $id")
    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> = signedPreKeys.values.toMutableList()
    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) { signedPreKeys[id] = record }
    override fun containsSignedPreKey(id: Int): Boolean = signedPreKeys.containsKey(id)
    override fun removeSignedPreKey(id: Int) { signedPreKeys.remove(id) }

    override fun loadKyberPreKey(id: Int): KyberPreKeyRecord = when (id) {
        kyberSigned.id -> kyberSigned
        else -> kyberPreKeys[id] ?: throw InvalidKeyIdException("no Kyber prekey $id")
    }
    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> = (kyberPreKeys.values + kyberSigned).toMutableList()
    override fun storeKyberPreKey(id: Int, record: KyberPreKeyRecord) { kyberPreKeys[id] = record }
    override fun containsKyberPreKey(id: Int): Boolean = id == kyberSigned.id || kyberPreKeys.containsKey(id)
    override fun markKyberPreKeyUsed(id: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        if (id != kyberSigned.id && kyberPreKeys.remove(id) == null) throw ReusedBaseKeyException("Kyber prekey already used")
    }

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) = Unit
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? = null

    fun snapshot(): JSONObject {
        state.put("one_time_prekeys", JSONArray(preKeys.values.map { it.serialize().base64Url() }))
        state.put("kyber_one_time_prekeys", JSONArray(kyberPreKeys.values.map { it.serialize().base64Url() }))
        state.put("sessions", sessions)
        state.put("remote_identities", remoteIdentities)
        return state
    }

    private fun addressKey(address: SignalProtocolAddress) = "${address.name}|${address.deviceId}"
}

private fun <T> JSONArray.records(factory: (String) -> T): List<T> =
    (0 until length()).map { index -> factory(getString(index)) }

fun String.base64UrlDecode(): ByteArray = java.util.Base64.getUrlDecoder().decode(this)
