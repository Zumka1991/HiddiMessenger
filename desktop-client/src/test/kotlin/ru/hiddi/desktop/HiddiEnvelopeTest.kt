package ru.hiddi.desktop

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiddiEnvelopeTest {
    @Test
    fun `envelope from a newer peer is reported as unsupported`() {
        assertTrue(
            HiddiEnvelope.isUnsupported(
                JSONObject().put("type", "hiddi.msg.v9").put("body", "привет"),
            ),
        )
    }

    @Test
    fun `known envelope types are not unsupported`() {
        listOf(
            HiddiEnvelope.SELF_SYNC,
            HiddiEnvelope.HISTORY_SYNC,
            DesktopAttachmentStore.ATTACHMENT_TYPE,
        ).forEach { type ->
            assertFalse(
                HiddiEnvelope.isUnsupported(JSONObject().put("type", type)),
                "$type должен остаться поддерживаемым",
            )
        }
    }

    @Test
    fun `plain text travels unwrapped so older clients still read it`() {
        assertEquals("привет", HiddiEnvelope.wrap("привет", null))
    }

    @Test
    fun `reply metadata survives a wrap and unwrap round trip`() {
        val reply = ReplyReference("msg-1", "alice", "исходный текст")
        val body = HiddiEnvelope.unwrap(HiddiEnvelope.wrap("ответ", reply))
        assertEquals("ответ", body.text)
        assertEquals(reply, body.replyTo)
    }

    @Test
    fun `an attachment envelope nests inside the reply wrapper untouched`() {
        val descriptor = AttachmentDescriptor(
            attachmentId = "11111111-1111-1111-1111-111111111111",
            bindingId = "22222222-2222-2222-2222-222222222222",
            kind = DesktopAttachmentStore.IMAGE_KIND,
            mimeType = DesktopAttachmentStore.JPEG_MIME,
            key = ByteArray(32).base64Url(),
            iv = ByteArray(12).base64Url(),
            plainSize = 1024,
        )
        val inner = DesktopAttachmentStore.envelope(descriptor)
        val reply = ReplyReference("msg-2", "bob", "фото")
        val body = HiddiEnvelope.unwrap(HiddiEnvelope.wrap(inner, reply))
        assertEquals(reply, body.replyTo)
        assertEquals(descriptor, DesktopAttachmentStore.parseEnvelope(body.text))
    }

    @Test
    fun `quote preview is collapsed to one bounded line`() {
        val preview = ReplyReference.preview("  первая\nстрока   и   ещё " + "я".repeat(200))
        assertEquals(ReplyReference.PREVIEW_LIMIT, preview.length)
        assertFalse(preview.contains("\n"))
        assertTrue(preview.startsWith("первая строка и ещё"))
    }

    @Test
    fun `plain text message is never an envelope`() {
        assertFalse(HiddiEnvelope.isUnsupported(null))
        assertFalse(HiddiEnvelope.isUnsupported(JSONObject()))
        // A message whose text merely happens to be JSON stays a plain message.
        assertFalse(HiddiEnvelope.isUnsupported(JSONObject().put("type", "заметка")))
    }
}
