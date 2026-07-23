package ru.hiddi.desktop

import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.security.SecureRandom
import java.util.Base64

class SignalDevice private constructor(
    val registrationId: Int,
    private val identity: IdentityKeyPair,
    private val signed: SignedPreKeyRecord,
    private val kyberSigned: KyberPreKeyRecord,
    private val classical: List<PreKeyRecord>,
    private val kyber: List<KyberPreKeyRecord>,
) {
    fun linkJson(linkCode: String, deviceName: String): JSONObject =
        JSONObject()
            .put("link_code", linkCode)
            .put("device_name", deviceName)
            .put("registration_id", registrationId)
            .put("identity_public_key", identity.publicKey.serialize().base64Url())

    fun publicPrekeysJson(): JSONObject =
        JSONObject()
            .put("signed_prekey", signed.publicJson())
            .put("kyber_signed_prekey", kyberSigned.publicJson())
            .put("one_time_prekeys", JSONArray(classical.map(PreKeyRecord::publicJson)))
            .put(
                "kyber_one_time_prekeys",
                JSONArray(kyber.map(KyberPreKeyRecord::publicJson)),
            )

    fun privateState(registration: JSONObject, server: String): JSONObject =
        JSONObject()
            .put("format", 1)
            .put("server", server.trimEnd('/'))
            .put("nickname", registration.getString("nickname"))
            .put("account_id", registration.getString("account_id"))
            .put("device_id", registration.getString("device_id"))
            .put("device_number", registration.getInt("device_number"))
            .put("access_token", registration.getString("access_token"))
            .put("registration_id", registrationId)
            .put("identity", identity.serialize().base64Url())
            .put("signed_prekey", signed.serialize().base64Url())
            .put("kyber_signed_prekey", kyberSigned.serialize().base64Url())
            .put(
                "one_time_prekeys",
                JSONArray(classical.map { it.serialize().base64Url() }),
            )
            .put(
                "kyber_one_time_prekeys",
                JSONArray(kyber.map { it.serialize().base64Url() }),
            )
            .put("pending_public_prekeys", publicPrekeysJson())

    companion object {
        private const val ONE_TIME_COUNT = 100

        fun create(): SignalDevice {
            val identity = IdentityKeyPair.generate()
            val signedPair = ECKeyPair.generate()
            val signed =
                SignedPreKeyRecord(
                    1,
                    System.currentTimeMillis(),
                    signedPair,
                    identity.privateKey.calculateSignature(signedPair.publicKey.serialize()),
                )
            val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val kyberSigned =
                KyberPreKeyRecord(
                    2,
                    System.currentTimeMillis(),
                    kyberPair,
                    identity.privateKey.calculateSignature(kyberPair.publicKey.serialize()),
                )
            val classical =
                (0 until ONE_TIME_COUNT).map {
                    PreKeyRecord(10_000 + it, ECKeyPair.generate())
                }
            val kyber =
                (0 until ONE_TIME_COUNT).map { offset ->
                    val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
                    KyberPreKeyRecord(
                        20_000 + offset,
                        System.currentTimeMillis(),
                        pair,
                        identity.privateKey.calculateSignature(pair.publicKey.serialize()),
                    )
                }
            return SignalDevice(
                SecureRandom().nextInt(16_380) + 1,
                identity,
                signed,
                kyberSigned,
                classical,
                kyber,
            )
        }
    }
}

private fun PreKeyRecord.publicJson(): JSONObject =
    JSONObject().put("id", id).put("public_key", keyPair.publicKey.serialize().base64Url())

private fun SignedPreKeyRecord.publicJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("public_key", keyPair.publicKey.serialize().base64Url())
        .put("signature", signature.base64Url())

private fun KyberPreKeyRecord.publicJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("public_key", keyPair.publicKey.serialize().base64Url())
        .put("signature", signature.base64Url())

fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)
