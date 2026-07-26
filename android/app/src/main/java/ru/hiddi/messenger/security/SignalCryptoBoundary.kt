package ru.hiddi.messenger.security

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.security.SecureRandom

/**
 * Produces a PQXDH registration bundle using Signal's native implementation.
 * Private record serializations are encrypted by [AndroidKeystoreSecretStore] and
 * are never included in [RegistrationBundle].
 */
class SignalCryptoBoundary(
    private val secretStore: AndroidKeystoreSecretStore,
) : CryptoBoundary {
    override suspend fun createRegistrationBundle(): RegistrationBundle {
        check(secretStore.read() == null) { "This device already has Signal identity material" }

        val identity = IdentityKeyPair.generate()
        val registrationId = SecureRandom().nextInt(MAX_REGISTRATION_ID) + 1
        val signedKeyPair = ECKeyPair.generate()
        val signedPublicKey = signedKeyPair.publicKey.serialize()
        val signedSignature = identity.privateKey.calculateSignature(signedPublicKey)
        val signedRecord = SignedPreKeyRecord(SIGNED_PREKEY_ID, System.currentTimeMillis(), signedKeyPair, signedSignature)

        val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        // KyberPreKeyRecord takes ownership of the native key-pair handle. Read the
        // public bytes before constructing the record; accessing it afterwards can
        // yield a null native handle on Android.
        val kyberPublicKey = kyberKeyPair.publicKey.serialize()
        val kyberSignature = identity.privateKey.calculateSignature(kyberPublicKey)
        val kyberRecord = KyberPreKeyRecord(KYBER_SIGNED_PREKEY_ID, System.currentTimeMillis(), kyberKeyPair, kyberSignature)

        val classical = (0 until ONE_TIME_PREKEY_COUNT).map { offset ->
            val keyPair = ECKeyPair.generate()
            val publicKey = keyPair.publicKey.serialize()
            ClassicalPreKeyMaterial(
                PreKeyRecord(FIRST_CLASSICAL_ONE_TIME_PREKEY_ID + offset, keyPair),
                publicKey,
                null,
            )
        }
        val kyber = (0 until ONE_TIME_PREKEY_COUNT).map { offset ->
            val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val publicKey = keyPair.publicKey.serialize()
            val signature = identity.privateKey.calculateSignature(publicKey)
            KyberPreKeyMaterial(
                KyberPreKeyRecord(FIRST_KYBER_ONE_TIME_PREKEY_ID + offset, System.currentTimeMillis(), keyPair, signature),
                publicKey,
                signature,
            )
        }

        secretStore.write(
            SignalPrivateState(
                registrationId,
                identity.serialize(),
                signedRecord.serialize(),
                kyberRecord.serialize(),
                classical.map { it.record.serialize() },
                kyber.map { it.record.serialize() },
            ).encode(),
        )

        return RegistrationBundle(
            registrationId = registrationId,
            identityPublicKey = identity.publicKey.serialize(),
            signedPreKey = PublicPreKey(SIGNED_PREKEY_ID, signedPublicKey, signedSignature),
            kyberSignedPreKey = PublicPreKey(KYBER_SIGNED_PREKEY_ID, kyberPublicKey, kyberSignature),
            oneTimePreKeys = classical.map { material ->
                PublicPreKey(material.record.id, material.publicKey, material.signature)
            },
            kyberOneTimePreKeys = kyber.map { material ->
                PublicPreKey(material.record.id, material.publicKey, material.signature)
            },
        )
    }

    private data class ClassicalPreKeyMaterial(
        val record: PreKeyRecord,
        val publicKey: ByteArray,
        val signature: ByteArray?,
    )

    private data class KyberPreKeyMaterial(
        val record: KyberPreKeyRecord,
        val publicKey: ByteArray,
        val signature: ByteArray?,
    )

    private companion object {
        const val SIGNED_PREKEY_ID = 1
        const val KYBER_SIGNED_PREKEY_ID = 2
        const val FIRST_CLASSICAL_ONE_TIME_PREKEY_ID = 10_000
        const val FIRST_KYBER_ONE_TIME_PREKEY_ID = 20_000
        const val ONE_TIME_PREKEY_COUNT = 100
        const val MAX_REGISTRATION_ID = 16_380
    }
}
