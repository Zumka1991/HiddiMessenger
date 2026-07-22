package ru.hiddi.terminal

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class EncryptedVaultTest {
    @Test
    fun roundTripRequiresTheSamePassphrase() {
        val directory = Files.createTempDirectory("hiddi-vault-test")
        val vault = EncryptedVault(directory.resolve("keys.bin"))
        vault.write("private-signal-state".encodeToByteArray(), "correct horse battery staple".toCharArray())

        assertContentEquals(
            "private-signal-state".encodeToByteArray(),
            vault.read("correct horse battery staple".toCharArray()),
        )
        assertFailsWith<IllegalArgumentException> {
            vault.read("wrong passphrase".toCharArray())
        }
    }
}
