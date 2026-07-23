package ru.hiddi.desktop

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
 * Private Signal state encrypted with Argon2id + AES-256-GCM.
 * The passphrase and derived key are never written to disk.
 */
class Vault(private val path: Path) {
    fun exists(): Boolean = Files.exists(path)

    fun read(passphrase: CharArray): ByteArray? {
        if (!exists()) return null
        val stored = Files.readAllBytes(path)
        require(stored.size >= HEADER_SIZE + GCM_TAG_BYTES) { "Хранилище ключей повреждено" }
        val input = ByteBuffer.wrap(stored)
        require(input.get() == FORMAT_VERSION) { "Неподдерживаемая версия хранилища ключей" }
        val salt = ByteArray(SALT_BYTES).also(input::get)
        val iv = ByteArray(IV_BYTES).also(input::get)
        val ciphertext = ByteArray(input.remaining()).also(input::get)
        val key = deriveKey(passphrase, salt)
        return try {
            cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(ciphertext)
        } catch (error: Exception) {
            throw IllegalArgumentException("Неверная парольная фраза или хранилище повреждено", error)
        } finally {
            key.fill(0)
            salt.fill(0)
        }
    }

    fun write(plaintext: ByteArray, passphrase: CharArray) {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveKey(passphrase, salt)
        val ciphertext = try {
            cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plaintext)
        } finally {
            key.fill(0)
        }
        val output = ByteBuffer.allocate(HEADER_SIZE + ciphertext.size)
            .put(FORMAT_VERSION)
            .put(salt)
            .put(iv)
            .put(ciphertext)
            .array()
        path.parent?.let(Files::createDirectories)
        val temporary = Files.createTempFile(path.parent, ".hiddi-vault-", ".tmp")
        try {
            Files.write(temporary, output)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
            salt.fill(0)
            iv.fill(0)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray =
        Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)
            .rawHash(ARGON_ITERATIONS, ARGON_MEMORY_KIB, ARGON_PARALLELISM, passphrase, salt)

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        }

    companion object {
        const val FILE_NAME = "signal-device.v1"
        private const val FORMAT_VERSION: Byte = 1
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val HEADER_SIZE = 1 + SALT_BYTES + IV_BYTES
        private const val GCM_TAG_BITS = 128
        private const val ARGON_ITERATIONS = 3
        private const val ARGON_MEMORY_KIB = 65_536
        private const val ARGON_PARALLELISM = 1
    }
}
