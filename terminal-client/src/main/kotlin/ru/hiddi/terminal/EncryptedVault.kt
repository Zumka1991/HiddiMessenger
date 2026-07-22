package ru.hiddi.terminal

import de.mkammerer.argon2.Argon2Factory
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal encrypted-at-rest container for private Signal state on desktop.
 * The passphrase is never written to disk. Its Argon2id-derived key only lives
 * in memory for the duration of the command.
 */
class EncryptedVault(private val path: Path) {
    fun exists(): Boolean = Files.exists(path)

    fun read(passphrase: CharArray): ByteArray? {
        if (!exists()) return null
        val stored = Files.readAllBytes(path)
        require(stored.size >= HEADER_SIZE + GCM_TAG_BYTES) { "Хранилище ключей повреждено" }
        val input = ByteBuffer.wrap(stored)
        require(input.get() == FORMAT_VERSION) { "Неподдерживаемый формат хранилища ключей" }
        val salt = ByteArray(SALT_BYTES).also(input::get)
        val iv = ByteArray(IV_BYTES).also(input::get)
        val ciphertext = ByteArray(input.remaining()).also(input::get)
        return try {
            decrypt(ciphertext, deriveKey(passphrase, salt), iv)
        } catch (error: Exception) {
            throw IllegalArgumentException("Неверная парольная фраза или хранилище повреждено", error)
        } finally {
            salt.fill(0)
        }
    }

    fun write(plaintext: ByteArray, passphrase: CharArray) {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveKey(passphrase, salt)
        val ciphertext = try {
            encrypt(plaintext, key, iv)
        } finally {
            key.fill(0)
        }
        val output = ByteBuffer.allocate(HEADER_SIZE + ciphertext.size)
            .put(FORMAT_VERSION)
            .put(salt)
            .put(iv)
            .put(ciphertext)
            .array()
        writeAtomically(output)
        salt.fill(0)
        iv.fill(0)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray =
        Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)
            .rawHash(ARGON_ITERATIONS, ARGON_MEMORY_KIB, ARGON_PARALLELISM, passphrase, salt)

    private fun encrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plaintext)

    private fun decrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(ciphertext)

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        }

    private fun writeAtomically(content: ByteArray) {
        path.parent?.let(Files::createDirectories)
        val temporary = Files.createTempFile(path.parent, ".hiddi-vault-", ".tmp")
        try {
            Files.write(temporary, content)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val FORMAT_VERSION: Byte = 1
        const val SALT_BYTES = 16
        const val IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val HEADER_SIZE = 1 + SALT_BYTES + IV_BYTES
        const val GCM_TAG_BITS = 128
        const val ARGON_ITERATIONS = 3
        const val ARGON_MEMORY_KIB = 65_536
        const val ARGON_PARALLELISM = 1
    }
}
