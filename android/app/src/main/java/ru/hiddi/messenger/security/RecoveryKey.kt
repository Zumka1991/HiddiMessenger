package ru.hiddi.messenger.security

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

class RecoveryKey private constructor(private val seed: ByteArray) {
    val encoded: String
        get() = PREFIX + Base64.encodeToString(
            seed,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

    fun publicKey(): ByteArray =
        Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun destroy() {
        seed.fill(0)
    }

    companion object {
        private const val PREFIX = "hiddi1."

        fun generate(): RecoveryKey =
            RecoveryKey(ByteArray(32).also(SecureRandom()::nextBytes))

        fun parse(value: String): RecoveryKey {
            val normalized = value.trim().replace(" ", "")
            require(normalized.startsWith(PREFIX)) { "Ключ восстановления имеет неверный формат" }
            val seed = Base64.decode(
                normalized.removePrefix(PREFIX),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            require(seed.size == 32) { "Ключ восстановления имеет неверную длину" }
            return RecoveryKey(seed)
        }
    }
}
