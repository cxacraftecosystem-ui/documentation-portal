package com.fieldrepository.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

/**
 * Every create carries the SAME Idempotency-Key, `clientKey` and `clientClipKey` on every retry, and
 * the body sent is the one frozen when the step was queued. No request carries a title or a file name
 * (DESIGN §9 test 61).
 */
class OutboxIdempotencyTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Fails the first [failures] creates with no signal, then behaves. */
    private class Flaky(var failures: Int) : SessionTransport by FakeSessionTransport() {
        val inner = FakeSessionTransport()
        override suspend fun createSession(idempotencyKey: String, body: QSessionCreate): QSession {
            if (failures-- > 0) { inner.idempotencyKeys.getOrPut("session") { mutableListOf() } += idempotencyKey; throw IOException("no signal") }
            return inner.createSession(idempotencyKey, body)
        }
        override suspend fun complete(idempotencyKey: String, body: QCompleteRequest): QRecording {
            if (failures-- > 0) { inner.idempotencyKeys.getOrPut("complete") { mutableListOf() } += idempotencyKey; throw IOException("no signal") }
            return inner.complete(idempotencyKey, body)
        }
        override suspend fun presign(body: QPresignRequest) = inner.presign(body)
        override suspend fun put(url: String, headers: Map<String, String>, file: java.io.File, offset: Long, length: Long, contentType: String) = inner.put(url, headers, file, offset, length, contentType)
    }

    @Test
    fun `retries reuse the key and the frozen body`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { Instant.parse("2026-07-01T10:00:00Z") })
        val body = QSessionCreate(clientKey = "c-2f6e9a", questionnaireId = "q_ctw3", sessionDate = "2026-07-01", startedAt = "2026-07-01T10:00:00Z", note = "Under the neem tree", participants = listOf(QArtisanRef("a1")))
        outbox.startSession(body, "u3")
        outbox.addClip("c-2f6e9a", testClip(tmp.newFolder(), "k-7d1", 100, "2026-07-01T10:01:00Z", listOf("a1")))
        val server = Flaky(failures = 2)

        outbox.sync(server, "u3")
        outbox.sync(server, "u3")
        outbox.sync(server, "u3")

        val sessionKeys = server.inner.idempotencyKeys.getValue("session")
        assertTrue(sessionKeys.size >= 2)
        assertEquals("one key for every try of the session create", 1, sessionKeys.distinct().size)
        val completeKeys = server.inner.idempotencyKeys.getValue("complete")
        assertEquals(1, completeKeys.distinct().size)

        val sent = server.inner.bodies.first { it.contains("\"clientKey\"") }
        assertTrue(sent.contains("\"clientKey\":\"c-2f6e9a\""))
        assertTrue(sent.contains("\"note\":\"Under the neem tree\""))
        assertFalse("no title is ever sent", sent.contains("\"title\""))
        val complete = server.inner.bodies.first { it.contains("\"clientClipKey\"") }
        assertTrue(complete.contains("\"clientClipKey\":\"k-7d1\""))
        assertFalse("the client never names an uploaded file", complete.contains("filename", ignoreCase = true))
    }

    @Test
    fun `keys are minted once at enqueue and survive a store round trip`() = runBlocking {
        val dir = tmp.newFolder()
        val store = FileSessionUnitStore(dir)
        val outbox = SessionOutbox(store)
        outbox.startSession(QSessionCreate(clientKey = "c-9", questionnaireId = "q", sessionDate = "2026-07-01", startedAt = "x"), "u3")
        val before = store.read("c-9")!!.steps.single().idempotencyKey
        val reopened = SessionOutbox(FileSessionUnitStore(dir))
        assertEquals(before, reopened.unit("c-9")!!.steps.single().idempotencyKey)
    }
}
