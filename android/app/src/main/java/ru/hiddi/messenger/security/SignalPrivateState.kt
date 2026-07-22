package ru.hiddi.messenger.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Private Signal records decoded only after Android Keystore decryption. */
data class SignalPrivateState(
    val registrationId: Int,
    val identity: ByteArray,
    val signedPreKey: ByteArray,
    val kyberSignedPreKey: ByteArray,
    val oneTimePreKeys: List<ByteArray>,
    val kyberOneTimePreKeys: List<ByteArray>,
) {
    fun encode(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(FORMAT_VERSION)
            output.writeInt(registrationId)
            output.writeBlock(identity)
            output.writeBlock(signedPreKey)
            output.writeBlock(kyberSignedPreKey)
            output.writeBlocks(oneTimePreKeys)
            output.writeBlocks(kyberOneTimePreKeys)
        }
        bytes.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): SignalPrivateState = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version in 1..FORMAT_VERSION) { "Неподдерживаемая версия Signal-ключей" }
            val state = SignalPrivateState(
                registrationId = if (version == 1) 0 else input.readInt(),
                identity = input.readBlock(),
                signedPreKey = input.readBlock(),
                kyberSignedPreKey = input.readBlock(),
                oneTimePreKeys = input.readBlocks(),
                kyberOneTimePreKeys = input.readBlocks(),
            )
            require(input.available() == 0) { "Signal-ключи содержат лишние данные" }
            state
        }

        private const val FORMAT_VERSION = 2
    }
}

private fun DataOutputStream.writeBlocks(blocks: List<ByteArray>) {
    writeInt(blocks.size)
    blocks.forEach(::writeBlock)
}

private fun DataOutputStream.writeBlock(block: ByteArray) {
    writeInt(block.size)
    write(block)
}

private fun DataInputStream.readBlocks(): List<ByteArray> {
    val count = readInt()
    require(count in 0..1_000) { "Некорректное число Signal-ключей" }
    return List(count) { readBlock() }
}

private fun DataInputStream.readBlock(): ByteArray {
    val size = readInt()
    require(size in 1..1_048_576) { "Некорректный размер Signal-ключа" }
    return ByteArray(size).also(::readFully)
}
