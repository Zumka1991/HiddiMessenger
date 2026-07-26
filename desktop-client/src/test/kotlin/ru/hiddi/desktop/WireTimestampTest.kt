package ru.hiddi.desktop

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WireTimestampTest {
    @Test
    fun `SQLite timestamp is interpreted as UTC`() {
        assertEquals(
            Instant.parse("2026-07-27T12:34:56Z").toEpochMilli(),
            wireTimestampMillis("2026-07-27 12:34:56"),
        )
    }

    @Test
    fun `RFC 3339 timestamp remains compatible`() {
        assertEquals(
            Instant.parse("2026-07-27T12:34:56.123Z").toEpochMilli(),
            wireTimestampMillis("2026-07-27T12:34:56.123Z"),
        )
    }
}
