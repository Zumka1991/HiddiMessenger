package ru.hiddi.messenger.security

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

/** Official libsignal storage bridge; every mutating operation is snapshotted by [snapshot]. */
class AndroidSignalProtocolStore(private val original: SignalState) : SignalProtocolStore {
    private val identity = IdentityKeyPair(original.keys.identity)
    private val preKeys = original.keys.oneTimePreKeys.map { PreKeyRecord(it) }.associateByTo(mutableMapOf()) { it.id }
    private val signed = SignedPreKeyRecord(original.keys.signedPreKey)
    private val kyberSigned = KyberPreKeyRecord(original.keys.kyberSignedPreKey)
    private val kyber = original.keys.kyberOneTimePreKeys.map { KyberPreKeyRecord(it) }.associateByTo(mutableMapOf()) { it.id }
    private val sessions = original.ratchet.optJSONObject("sessions") ?: JSONObject()
    private val identities = original.ratchet.optJSONObject("identities") ?: JSONObject()

    override fun getIdentityKeyPair() = identity
    override fun getLocalRegistrationId() = original.keys.registrationId
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val key = address.key(); val old = identities.optString(key).takeIf(String::isNotBlank)
        identities.put(key, identityKey.serialize().b64())
        return if (old == null || old.decode().contentEquals(identityKey.serialize())) IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED else IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }
    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean =
        identities.optString(address.key()).takeIf(String::isNotBlank)?.decode()?.contentEquals(identityKey.serialize()) ?: true
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = identities.optString(address.key()).takeIf(String::isNotBlank)?.let { IdentityKey(it.decode()) }

    override fun loadPreKey(id: Int) = preKeys[id] ?: throw InvalidKeyIdException("unknown prekey")
    override fun storePreKey(id: Int, record: PreKeyRecord) { preKeys[id] = record }
    override fun containsPreKey(id: Int) = preKeys.containsKey(id)
    override fun removePreKey(id: Int) { preKeys.remove(id) }
    override fun loadSession(address: SignalProtocolAddress): SessionRecord? = sessions.optString(address.key()).takeIf(String::isNotBlank)?.let { SessionRecord(it.decode()) }
    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> = addresses.map { loadSession(it) ?: throw NoSessionException(it, "no session") }.toMutableList()
    override fun getSubDeviceSessions(name: String): MutableList<Int> = mutableListOf()
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) { sessions.put(address.key(), record.serialize().b64()) }
    override fun containsSession(address: SignalProtocolAddress) = sessions.has(address.key())
    override fun deleteSession(address: SignalProtocolAddress) { sessions.remove(address.key()) }
    override fun deleteAllSessions(name: String) {
        sessions.keys().asSequence().toList().filter { it.substringBeforeLast('|') == name }.forEach(sessions::remove)
    }

    override fun loadSignedPreKey(id: Int) = if (id == signed.id) signed else throw InvalidKeyIdException("unknown signed prekey")
    override fun loadSignedPreKeys() = mutableListOf(signed)
    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) = Unit
    override fun containsSignedPreKey(id: Int) = id == signed.id
    override fun removeSignedPreKey(id: Int) = Unit
    override fun loadKyberPreKey(id: Int) = if (id == kyberSigned.id) kyberSigned else kyber[id] ?: throw InvalidKeyIdException("unknown Kyber prekey")
    override fun loadKyberPreKeys() = (kyber.values + kyberSigned).toMutableList()
    override fun storeKyberPreKey(id: Int, record: KyberPreKeyRecord) { kyber[id] = record }
    override fun containsKyberPreKey(id: Int) = id == kyberSigned.id || kyber.containsKey(id)
    override fun markKyberPreKeyUsed(id: Int, signedPreKeyId: Int, baseKey: ECPublicKey) { if (id != kyberSigned.id && kyber.remove(id) == null) throw ReusedBaseKeyException("Kyber key reused") }
    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) = Unit
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? = null

    fun snapshot() = SignalState(
        original.keys.copy(oneTimePreKeys = preKeys.values.map(PreKeyRecord::serialize), kyberOneTimePreKeys = kyber.values.map(KyberPreKeyRecord::serialize)),
        original.ratchet.put("sessions", sessions).put("identities", identities),
    )
}

private fun SignalProtocolAddress.key() = "$name|$deviceId"
private fun ByteArray.b64(): String = android.util.Base64.encodeToString(this, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
private fun String.decode(): ByteArray = android.util.Base64.decode(this, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
