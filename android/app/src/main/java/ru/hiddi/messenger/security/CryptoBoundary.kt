package ru.hiddi.messenger.security

/**
 * Единственная разрешённая граница между приложением и Signal Protocol.
 * Реализация появится вместе с официальной libsignal; UI и HTTP-код не знают
 * приватных ключей и никогда не сериализуют их для отправки на сервер.
 */
interface CryptoBoundary {
    suspend fun createRegistrationBundle(): RegistrationBundle
}

data class RegistrationBundle(
    val registrationId: Int,
    val identityPublicKey: ByteArray,
    val signedPreKey: PublicPreKey,
    val kyberSignedPreKey: PublicPreKey,
    val oneTimePreKeys: List<PublicPreKey>,
    val kyberOneTimePreKeys: List<PublicPreKey>,
)

data class PublicPreKey(
    val id: Int,
    val publicKey: ByteArray,
    val signature: ByteArray?,
)
