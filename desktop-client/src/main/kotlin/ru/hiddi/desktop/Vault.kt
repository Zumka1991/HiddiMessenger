package ru.hiddi.desktop

import de.mkammerer.argon2.Argon2Factory
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Private Signal state encrypted with Argon2id + AES-256-GCM.
 * The passphrase and derived key are never written to disk.
 */
class Vault(private val path: Path) {
    internal val storageDirectory: Path
        get() = checkNotNull(path.parent) { "Хранилище должно иметь родительский каталог" }

    fun exists(): Boolean =
        Files.exists(path).also { present ->
            if (present) hardenStoragePermissions()
        }

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
        prepareStorageDirectory()
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
        val temporary = Files.createTempFile(path.parent, ".hiddi-vault-", ".tmp")
        try {
            setOwnerOnlyPermissions(temporary, FILE_PERMISSIONS)
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
            setOwnerOnlyPermissions(path, FILE_PERMISSIONS)
        } finally {
            Files.deleteIfExists(temporary)
            salt.fill(0)
            iv.fill(0)
        }
    }

    /** Deletes only Hiddi Desktop local material. Server revocation must happen first. */
    fun deleteLocalData() {
        val directory = storageDirectory
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray =
        Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)
            .rawHash(ARGON_ITERATIONS, ARGON_MEMORY_KIB, ARGON_PARALLELISM, passphrase, salt)

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        }

    private fun prepareStorageDirectory() {
        path.parent?.let { directory ->
            Files.createDirectories(directory)
            setOwnerOnlyPermissions(directory, DIRECTORY_PERMISSIONS)
        }
    }

    private fun hardenStoragePermissions() {
        prepareStorageDirectory()
        setOwnerOnlyPermissions(path, FILE_PERMISSIONS)
    }

    private fun setOwnerOnlyPermissions(target: Path, permissions: Set<PosixFilePermission>) {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(target, permissions)
        }
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
        private val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        private val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}
