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
    private val publicPrekeys: JSONObject,
) {
    fun linkJson(linkCode: String, deviceName: String): JSONObject =
        JSONObject()
            .put("link_code", linkCode)
            .put("device_name", deviceName)
            .put("registration_id", registrationId)
            .put("identity_public_key", identity.publicKey.serialize().base64Url())

    fun registrationJson(nickname: String, inviteCode: String, deviceName: String, recoveryPublicKey: ByteArray): JSONObject =
        JSONObject()
            .put("nickname", nickname.trim().removePrefix("@").lowercase())
            .put("invite_code", inviteCode.trim())
            .put("device_name", deviceName.trim())
            .put("registration_id", registrationId)
            .put("identity_public_key", identity.publicKey.serialize().base64Url())
            .put("recovery_public_key", recoveryPublicKey.base64Url())

    fun publicPrekeysJson(): JSONObject = JSONObject(publicPrekeys.toString())

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
            val signedPublicKey = signedPair.publicKey.serialize()
            val signedSignature = identity.privateKey.calculateSignature(signedPublicKey)
            val signed =
                SignedPreKeyRecord(
                    1,
                    System.currentTimeMillis(),
                    signedPair,
                    signedSignature,
                )
            val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            // KyberPreKeyRecord owns the native key-pair handle: retain public
            // material before creating the record, as on Android.
            val kyberPublicKey = kyberPair.publicKey.serialize()
            val kyberSignature = identity.privateKey.calculateSignature(kyberPublicKey)
            val kyberSigned =
                KyberPreKeyRecord(
                    2,
                    System.currentTimeMillis(),
                    kyberPair,
                    kyberSignature,
                )
            val classicalMaterial =
                (0 until ONE_TIME_COUNT).map {
                    val pair = ECKeyPair.generate()
                    PreKeyRecord(10_000 + it, pair) to pair.publicKey.serialize()
                }
            val kyberMaterial =
                (0 until ONE_TIME_COUNT).map { offset ->
                    val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
                    val publicKey = pair.publicKey.serialize()
                    val signature = identity.privateKey.calculateSignature(publicKey)
                    KyberPreKeyRecord(
                        20_000 + offset,
                        System.currentTimeMillis(),
                        pair,
                        signature,
                    ) to (publicKey to signature)
                }
            val publicPrekeys = JSONObject()
                .put("signed_prekey", JSONObject().put("id", 1).put("public_key", signedPublicKey.base64Url()).put("signature", signedSignature.base64Url()))
                .put("kyber_signed_prekey", JSONObject().put("id", 2).put("public_key", kyberPublicKey.base64Url()).put("signature", kyberSignature.base64Url()))
                .put("one_time_prekeys", JSONArray(classicalMaterial.map { (record, publicKey) -> JSONObject().put("id", record.id).put("public_key", publicKey.base64Url()) }))
                .put("kyber_one_time_prekeys", JSONArray(kyberMaterial.map { (record, material) -> JSONObject().put("id", record.id).put("public_key", material.first.base64Url()).put("signature", material.second.base64Url()) }))
            return SignalDevice(
                SecureRandom().nextInt(16_380) + 1,
                identity,
                signed,
                kyberSigned,
                classicalMaterial.map { it.first },
                kyberMaterial.map { it.first },
                publicPrekeys,
            )
        }
    }
}

fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)
