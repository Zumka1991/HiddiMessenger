package ru.hiddi.desktop

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom

class RecoveryKey private constructor(private val seed: ByteArray) : AutoCloseable {
    val encoded: String get() = "hiddi1.${seed.base64Url()}"

    fun publicKey(): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    override fun close() {
        seed.fill(0)
    }

    companion object {
        fun generate(): RecoveryKey = RecoveryKey(ByteArray(32).also(SecureRandom()::nextBytes))
    }
}
