package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.questionnaire.QFeature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/**
 * DESIGN §9 tests 52 and 53: one ordered unit per session. Offline: register an artisan, start a
 * session, add a participant, record 3 takes; on reconnect every step goes once, in order, and the
 * bytes sent equal the file sizes. A failed clip blocks nothing; at most 3 clips upload at once and 1
 * on a slow link; a multipart upload resumes from its stored ETags after a drop.
 */
@QFeature("B38")
class OutboxOrderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = Instant.parse("2026-07-01T10:00:00Z")

    private fun body(key: String, participants: List<String>) = QSessionCreate(
        clientKey = key, questionnaireId = "q_ctw3", workshopId = "w_bankura", sessionDate = "2026-07-01",
        startedAt = t0.toString(), participants = participants.map { QArtisanRef(it) }
    )

    @Test
    fun `offline session replays in order and every step once`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        outbox.startSession(body("c-1", listOf("local-meena", "a1")), "u3", localArtisans = listOf("local-meena" to """{"name":"Meena"}"""))
        outbox.addParticipantChange("c-1", ParticipantChange("a2", ParticipantChange.ADD, "2026-07-01T10:05:00Z"))
        val dir = tmp.newFolder()
        outbox.addClip("c-1", testClip(dir, "k1", 300, "2026-07-01T10:01:00Z", listOf("local-meena", "a1")))
        outbox.addClip("c-1", testClip(dir, "k2", 500, "2026-07-01T10:10:00Z", listOf("a1", "a2")))
        outbox.addClip("c-1", testClip(dir, "k3", 700, "2026-07-01T10:12:00Z", listOf("a2")))
        outbox.addAnswers("c-1", QAnswersPut(listOf(QAnswerItem("a1", "lin_d3", "Bamboo", sessionId = "c-1"))))

        val server = FakeSessionTransport()
        val first = outbox.sync(server, "u3")

        val order = server.calls.filter { !it.startsWith("presign") }
        assertEquals(listOf("artisan", "session", "complete:k1", "participant:add:a2", "complete:k2", "complete:k3", "answers"), order)
        assertEquals(1500L, server.bytesPut)
        assertEquals(0, first.remaining)
        // The offline artisan's local id became the server's id everywhere it was named.
        assertTrue(server.bodies.none { it.contains("local-meena") && !it.contains("Meena\"}") })
        // The answers typed before the session synced now name the server's session.
        assertTrue(server.bodies.last().contains("\"sessionId\":\"s-2\""))

        // A second pass sends nothing: everything was ticked off. The unit stays while the session is
        // open (audit C2), and goes once its Finish has landed.
        val callsBefore = server.calls.size
        outbox.sync(server, "u3")
        assertEquals(callsBefore, server.calls.size)
        assertEquals(1, outbox.units().size)
        outbox.finishSession("c-1")
        outbox.sync(server, "u3")
        assertTrue(outbox.units().isEmpty())
    }

    @Test
    fun `a failed clip blocks neither the other clips nor the answers`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        outbox.startSession(body("c-2", listOf("a1")), "u3")
        val dir = tmp.newFolder()
        listOf("k1", "k2", "k3").forEachIndexed { i, k -> outbox.addClip("c-2", testClip(dir, k, 100, "2026-07-01T10:0${i}:00Z", listOf("a1"))) }
        outbox.addAnswers("c-2", QAnswersPut(listOf(QAnswerItem("a1", "lin_d3", "x", sessionId = "c-2"))))
        val server = FakeSessionTransport().apply { refuseComplete += "k2" }

        val result = outbox.sync(server, "u3")

        assertTrue(server.calls.contains("complete:k1"))
        assertTrue(server.calls.contains("complete:k3"))
        assertTrue(server.calls.contains("answers"))
        assertEquals(1, result.failed)
        val failed = outbox.units().single().steps.single { it.state == StepState.FAILED }
        assertEquals("k2", failed.clip?.clientClipKey)
        assertEquals("Target outside the instrument", failed.failure)
    }

    @Test
    fun `three clips upload at once, and one at a time on a slow link`() = runBlocking {
        val dir = tmp.newFolder()
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        outbox.startSession(body("c-3", listOf("a1")), "u3")
        (1..6).forEach { outbox.addClip("c-3", testClip(dir, "k$it", 100, "2026-07-01T10:0$it:00Z", listOf("a1"))) }
        val server = FakeSessionTransport().apply { putDelayMs = 80 }
        outbox.sync(server, "u3")
        assertEquals(3, server.maxInFlight)
        // 100 bytes in about 80 ms is ~1.2 kB/s: far under 50 kbps, so the next pass goes one by one.
        assertEquals(1, outbox.concurrency())

        outbox.startSession(body("c-4", listOf("a1")), "u3")
        (1..4).forEach { outbox.addClip("c-4", testClip(dir, "m$it", 100, "2026-07-01T11:0$it:00Z", listOf("a1"))) }
        val slow = FakeSessionTransport().apply { putDelayMs = 40 }
        outbox.sync(slow, "u3")
        assertEquals(1, slow.maxInFlight)
    }

    @Test
    fun `a multipart upload resumes from the stored ETags after a drop`() = runBlocking {
        val dir = tmp.newFolder()
        val store = InMemorySessionUnitStore()
        val outbox = SessionOutbox(store, clock = { t0 }, multipartThresholdBytes = 2048, defaultPartSize = FakeSessionTransport.PART)
        outbox.startSession(body("c-5", listOf("a1")), "u3")
        outbox.addClip("c-5", testClip(dir, "big", 5000, "2026-07-01T10:01:00Z", listOf("a1")))
        val server = FakeSessionTransport().apply { dropOnPart = 3 }

        outbox.sync(server, "u3")
        assertEquals(listOf(1, 2), server.partsPut)
        val saved = store.read("c-5")!!.steps.single { it.kind == StepKind.CLIP }.clip!!
        assertEquals(listOf(1, 2), saved.partsDone.map { it.partNumber })
        assertEquals(StepState.PENDING, store.read("c-5")!!.steps.single { it.kind == StepKind.CLIP }.state)

        outbox.sync(server, "u3")
        assertEquals(listOf(1, 2, 3, 4, 5), server.partsPut)
        assertEquals(1, server.calls.count { it.startsWith("multipart:") })
        assertTrue(server.calls.contains("complete:big"))
        assertEquals(5000L, server.bytesPut)
    }

    @Test
    fun `finish waits for every take and answer`() = runBlocking {
        val dir = tmp.newFolder()
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        outbox.startSession(body("c-6", listOf("a1")), "u3")
        outbox.addClip("c-6", testClip(dir, "k1", 100, "2026-07-01T10:01:00Z", listOf("a1")))
        outbox.finishSession("c-6")
        val server = FakeSessionTransport()
        outbox.sync(server, "u3")
        assertEquals("finish", server.calls.last())
    }

    @Test
    fun `another account's units are not replayed`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        outbox.startSession(body("c-7", listOf("a1")), "someone-else")
        val server = FakeSessionTransport()
        outbox.sync(server, "u3")
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `the answered-by chips make an unticked artisan PRESENT, never deleted`() = runBlocking {
        val dir = tmp.newFolder()
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        outbox.startSession(body("c-8", listOf("a1", "a2")), "u3")
        outbox.addClip("c-8", testClip(dir, "k1", 100, "2026-07-01T10:01:00Z", listOf("a1", "a2")))
        assertTrue(outbox.updateClipSubjects("c-8", "k1", setOf("a1")))
        val subjects = outbox.unit("c-8")!!.steps.single { it.kind == StepKind.CLIP }.clip!!.recording.subjects
        assertEquals(listOf("a1" to SubjectRole.ANSWERED.wire, "a2" to SubjectRole.PRESENT.wire), subjects.map { it.artisanId to it.role })
    }
}
