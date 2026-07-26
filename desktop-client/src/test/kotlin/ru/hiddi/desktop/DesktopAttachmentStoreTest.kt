package ru.hiddi.desktop

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopAttachmentStoreTest {
    @Test
    fun `attachment envelope matches Android format and ciphertext round trips`() {
        val directory = Files.createTempDirectory("hiddi-desktop-attachments")
        val store = DesktopAttachmentStore(directory)
        val plaintext = "private photo bytes".encodeToByteArray()
        try {
            val prepared =
                store.encrypt(
                    plaintext,
                    DesktopAttachmentStore.IMAGE_KIND,
                    DesktopAttachmentStore.JPEG_MIME,
                )
            val descriptor = prepared.descriptor(UUID.randomUUID().toString())
            store.saveCiphertext(descriptor.attachmentId, prepared.ciphertext)

            val envelope = DesktopAttachmentStore.envelope(descriptor)
            val parsed = checkNotNull(DesktopAttachmentStore.parseEnvelope(envelope))
            assertEquals(descriptor, parsed)
            assertContentEquals(plaintext, store.decrypt(parsed))
            assertFalse(
                Files.readAllBytes(directory.resolve("${descriptor.attachmentId}.bin"))
                    .contentEquals(plaintext),
            )
        } finally {
            plaintext.fill(0)
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `image preview survives the versioned envelope`() {
        val directory = Files.createTempDirectory("hiddi-desktop-preview")
        val store = DesktopAttachmentStore(directory)
        try {
            val full =
                store.encrypt(
                    ByteArray(32) { 1 },
                    DesktopAttachmentStore.IMAGE_KIND,
                    DesktopAttachmentStore.JPEG_MIME,
                ).descriptor(UUID.randomUUID().toString())
            val preview =
                store.encrypt(
                    ByteArray(16) { 2 },
                    DesktopAttachmentStore.IMAGE_KIND,
                    DesktopAttachmentStore.JPEG_MIME,
                ).descriptor(UUID.randomUUID().toString())
            val descriptor = full.copy(preview = preview)

            assertEquals(
                descriptor,
                DesktopAttachmentStore.parseEnvelope(
                    DesktopAttachmentStore.envelope(descriptor),
                ),
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
