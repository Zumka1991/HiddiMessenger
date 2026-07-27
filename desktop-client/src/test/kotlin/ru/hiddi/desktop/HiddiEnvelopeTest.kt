package ru.hiddi.desktop

import org.json.JSONObject
import kotlin.test.Test
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
    fun `plain text message is never an envelope`() {
        assertFalse(HiddiEnvelope.isUnsupported(null))
        assertFalse(HiddiEnvelope.isUnsupported(JSONObject()))
        // A message whose text merely happens to be JSON stays a plain message.
        assertFalse(HiddiEnvelope.isUnsupported(JSONObject().put("type", "заметка")))
    }
}
