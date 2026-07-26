package ru.hiddi.desktop

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class VaultTest {
    @Test
    fun `round trip encrypts state and rejects wrong password`() {
        val directory = Files.createTempDirectory("hiddi-desktop-test")
        val vault = Vault(directory.resolve(Vault.FILE_NAME))
        val plaintext = """{"private":"signal-state"}""".encodeToByteArray()
        val password = "correct horse battery staple".toCharArray()
        try {
            vault.write(plaintext, password)
            assertContentEquals(plaintext, vault.read(password))
            assertFailsWith<IllegalArgumentException> {
                vault.read("wrong password".toCharArray())
            }
            val raw = Files.readAllBytes(directory.resolve(Vault.FILE_NAME))
            assertFalse(raw.toString(Charsets.UTF_8).contains("signal-state"))
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertEquals(
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                    Files.getPosixFilePermissions(directory),
                )
                assertEquals(
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    ),
                    Files.getPosixFilePermissions(directory.resolve(Vault.FILE_NAME)),
                )
            }
        } finally {
            password.fill('\u0000')
            plaintext.fill(0)
            directory.toFile().deleteRecursively()
        }
    }
}
