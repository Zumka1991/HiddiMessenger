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
        val signedSignature = identity.privateKey.calculateSignature(signedKeyPair.publicKey.serialize())
        val signedRecord = SignedPreKeyRecord(SIGNED_PREKEY_ID, System.currentTimeMillis(), signedKeyPair, signedSignature)

        val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSignature = identity.privateKey.calculateSignature(kyberKeyPair.publicKey.serialize())
        val kyberRecord = KyberPreKeyRecord(KYBER_SIGNED_PREKEY_ID, System.currentTimeMillis(), kyberKeyPair, kyberSignature)

        val classical = (0 until ONE_TIME_PREKEY_COUNT).map { offset ->
            PreKeyRecord(FIRST_CLASSICAL_ONE_TIME_PREKEY_ID + offset, ECKeyPair.generate())
        }
        val kyber = (0 until ONE_TIME_PREKEY_COUNT).map { offset ->
            val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val signature = identity.privateKey.calculateSignature(keyPair.publicKey.serialize())
            KyberPreKeyRecord(FIRST_KYBER_ONE_TIME_PREKEY_ID + offset, System.currentTimeMillis(), keyPair, signature)
        }

        secretStore.write(
            SignalPrivateState(
                registrationId,
                identity.serialize(),
                signedRecord.serialize(),
                kyberRecord.serialize(),
                classical.map(PreKeyRecord::serialize),
                kyber.map(KyberPreKeyRecord::serialize),
            ).encode(),
        )

        return RegistrationBundle(
            registrationId = registrationId,
            identityPublicKey = identity.publicKey.serialize(),
            signedPreKey = PublicPreKey(SIGNED_PREKEY_ID, signedKeyPair.publicKey.serialize(), signedSignature),
            kyberSignedPreKey = PublicPreKey(KYBER_SIGNED_PREKEY_ID, kyberKeyPair.publicKey.serialize(), kyberSignature),
            oneTimePreKeys = classical.map { record ->
                PublicPreKey(record.id, record.keyPair.publicKey.serialize(), null)
            },
            kyberOneTimePreKeys = kyber.map { record ->
                PublicPreKey(record.id, record.keyPair.publicKey.serialize(), record.signature)
            },
        )
    }

    private companion object {
        const val SIGNED_PREKEY_ID = 1
        const val KYBER_SIGNED_PREKEY_ID = 2
        const val FIRST_CLASSICAL_ONE_TIME_PREKEY_ID = 10_000
        const val FIRST_KYBER_ONE_TIME_PREKEY_ID = 20_000
        const val ONE_TIME_PREKEY_COUNT = 100
        const val MAX_REGISTRATION_ID = 16_380
    }
}
