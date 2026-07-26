package ru.hiddi.desktop

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceLinkQrTest {
    @Test
    fun `parses phone device link payload`() {
        val code = "a".repeat(43)
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "http://127.0.0.1:3000\n$code".toByteArray(StandardCharsets.UTF_8),
        )

        assertEquals(DeviceLinkQr("http://127.0.0.1:3000", code), parseDeviceLinkQr("hiddi-device-link-v1:$payload"))
    }

    @Test
    fun `rejects unrelated qr payload`() {
        assertFailsWith<IllegalArgumentException> { parseDeviceLinkQr("https://example.test") }
    }
}
