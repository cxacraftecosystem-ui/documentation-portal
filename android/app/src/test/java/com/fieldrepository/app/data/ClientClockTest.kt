package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.QFeature
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** DESIGN §3.9: clockOffsetMs = server Date − X-Client-Time; an offset older than 7 days is estimated. */
class ClientClockTest {
    @QFeature("B17")
    @Test
    fun `the offset is read off the Date header`() {
        val sent = Instant.parse("2026-07-01T12:54:00Z")
        ClientClock.observe(sent, "Wed, 01 Jul 2026 12:56:00 GMT", now = sent)
        assertEquals(120_000L, ClientClock.offsetMs())
        assertEquals("DEVICE", ClientClock.captureSource(sent.plusSeconds(3600)))
        assertEquals("UPLOAD_TIME", ClientClock.captureSource(sent.plusSeconds(8L * 86_400)))
    }

    @Test
    fun `under a second is no offset and a bad header changes nothing`() {
        val sent = Instant.parse("2026-07-01T12:54:00.400Z")
        ClientClock.observe(sent, "Wed, 01 Jul 2026 12:54:00 GMT", now = sent)
        assertEquals(0L, ClientClock.offsetMs())
        ClientClock.observe(sent, "not a date", now = sent)
        assertEquals(0L, ClientClock.offsetMs())
    }
}
