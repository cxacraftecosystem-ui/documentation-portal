package com.fieldrepository.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * DESIGN §9 test 55: the join QR round-trips through a real symbol (ZXing runs on the JVM), and two
 * phones that joined offline under one `clientKey` end up in ONE session once both outboxes sync.
 */
class QrJoinTest {
    private val payload = JoinQrPayload(
        clientKey = "c-2f6e9a", questionnaireId = "q_ctw3", workshopId = "w_bankura",
        sessionDate = "2026-07-01", participants = listOf("a1", "local-7")
    )

    @Test
    fun `the payload is the contract's shape`() {
        assertEquals(
            """{"v":1,"clientKey":"c-2f6e9a","questionnaireId":"q_ctw3","workshopId":"w_bankura","sessionDate":"2026-07-01","participants":["a1","local-7"]}""",
            JoinQr.encode(payload)
        )
        assertEquals(payload, JoinQr.decode(JoinQr.encode(payload)))
    }

    @Test
    fun `a drawn QR reads back`() {
        val matrix = JoinQr.matrix(JoinQr.encode(payload), 400)
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val text = JoinQr.read(pixels, matrix.width, matrix.height)
        assertEquals(payload, text?.let(JoinQr::decode))
    }

    @Test
    fun `anything else is not a join code`() {
        assertNull(JoinQr.decode("https://example.org"))
        assertNull(JoinQr.decode("""{"v":2,"clientKey":"c","questionnaireId":"q","sessionDate":"2026-07-01"}"""))
        assertEquals("K7Q2MX", JoinQr.normaliseCode(" k7q-2mx "))
        assertNull(JoinQr.normaliseCode("K7Q2"))
    }

    @Test
    fun `two phones with one clientKey resolve to one session`() = runBlocking {
        val server = FakeSessionTransport()
        val first = SessionOutbox(InMemorySessionUnitStore())
        val second = SessionOutbox(InMemorySessionUnitStore())
        val now = Instant.parse("2026-07-01T10:00:00Z")
        first.startSession(JoinQr.sessionCreate(payload, "u3", now), "u3")
        second.startSession(JoinQr.sessionCreate(JoinQr.decode(JoinQr.encode(payload))!!, "u4", now), "u4")

        first.sync(server, "u3")
        second.sync(server, "u4")

        assertEquals(1, server.sessionsByClientKey.size)
        assertEquals(2, server.calls.count { it == "session" })
    }
}
