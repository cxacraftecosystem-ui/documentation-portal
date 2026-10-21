package com.fieldrepository.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Audit C2 (21 Oct 2026): the outbox deleted a session's unit after its first take uploaded, so the
 * next take, Present/Away, an answer, an added artisan and Finish all hit a missing unit (and the app
 * crashed). The unit now stays while the session is open, goes once Finish lands (or long after its
 * last change), a missing one is put back from the workspace's copy, and a landed take's audio leaves
 * the phone (audit M23).
 */
class OutboxKeepsOpenSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = Instant.parse("2026-07-01T10:00:00Z")
    private fun body(key: String) = QSessionCreate(
        clientKey = key, questionnaireId = "q", workshopId = "w", sessionDate = "2026-07-01",
        startedAt = now.toString(), participants = listOf(QArtisanRef("a1"), QArtisanRef("a2"))
    )

    @Test fun `every change after the first take still queues, and Finish lets the unit go`() = runBlocking {
        val landed = mutableListOf<String>()
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { now }, onClipLanded = { landed += it })
        val server = FakeSessionTransport()
        val dir = tmp.newFolder()
        outbox.startSession(body("c-1"), "u1")
        val first = testClip(dir, "k1", 200, "2026-07-01T10:01:00Z", listOf("a1"))
        outbox.addClip("c-1", first)
        outbox.sync(server, "u1")

        assertNotNull("the unit stays while the session is open", outbox.unit("c-1"))
        assertFalse("the landed take's audio left the phone", File(first.localPath).exists())
        assertEquals(listOf("k1"), landed)

        // The calls that used to crash on a missing unit.
        outbox.addClip("c-1", testClip(dir, "k2", 200, "2026-07-01T10:05:00Z", listOf("a2")))
        outbox.addParticipantChange("c-1", ParticipantChange("a2", ParticipantChange.LEAVE, "2026-07-01T10:06:00Z"))
        outbox.addAnswers("c-1", QAnswersPut(listOf(QAnswerItem("a1", "lin1", "Bamboo", sessionId = "c-1"))))
        outbox.addParticipantChange("c-1", ParticipantChange("a3", ParticipantChange.ADD, "2026-07-01T10:07:00Z"))
        outbox.finishSession("c-1")
        outbox.sync(server, "u1")

        assertTrue(server.calls.containsAll(listOf("complete:k2", "participant:patch:a2", "answers", "participant:add:a3", "finish")))
        assertNull("Finish landed: the unit goes", outbox.unit("c-1"))
    }

    @Test fun `a settled unit nobody touched for two weeks is forgotten, an open one is not`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { now })
        val server = FakeSessionTransport()
        outbox.startSession(body("c-2"), "u1")
        outbox.sync(server, "u1")
        assertNotNull(outbox.unit("c-2"))
        now = now.plus(SessionOutbox.STALE_AFTER).plus(Duration.ofHours(1))
        outbox.sync(server, "u1")
        assertNull(outbox.unit("c-2"))
    }

    @Test fun `a missing unit is put back from the workspace's copy, every step ticked`() = runBlocking {
        val store = InMemorySessionUnitStore()
        val outbox = SessionOutbox(store, clock = { now })
        val known = outbox.startSession(body("c-3"), "u1")
        store.delete("c-3")

        val failure = runCatching { outbox.addAnswers("c-3", QAnswersPut(listOf(QAnswerItem("a1", "lin1", "x", sessionId = "c-3")))) }.exceptionOrNull()
        assertTrue("a missing unit is its own, catchable failure", failure is MissingSessionUnit)

        val back = outbox.reopen(known)
        assertTrue(back.steps.all { it.state == StepState.DONE })
        outbox.addAnswers("c-3", QAnswersPut(listOf(QAnswerItem("a1", "lin1", "x", sessionId = "c-3"))))
        assertEquals(1, outbox.unit("c-3")!!.steps.count { it.kind == StepKind.ANSWERS && it.state == StepState.PENDING })
    }

    @Test fun `one unit that throws never ends the pass for the others`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { now })
        outbox.startSession(body("c-bad"), "u1")
        now = now.plusSeconds(1)
        outbox.startSession(body("c-good"), "u1")
        val fake = FakeSessionTransport()
        val server = object : SessionTransport by fake {
            override suspend fun createSession(idempotencyKey: String, body: QSessionCreate): QSession {
                if (body.clientKey == "c-bad") throw IllegalArgumentException("a bug, not a refusal")
                return fake.createSession(idempotencyKey, body)
            }
        }
        val result = outbox.sync(server, "u1")
        assertEquals("s-1", outbox.unit("c-good")!!.sessionId)
        assertTrue("the broken unit is still waiting", result.remaining >= 1)
    }

    @Test fun `a take still uploading reads its real progress, not a fixed half`() {
        val clip = testClip(tmp.newFolder(), "k9", 300, "2026-07-01T10:00:00Z", listOf("a1"))
        assertEquals(0, clip.uploadedPercent)
        assertEquals(33, clip.copy(partSize = 100, partsDone = listOf(QPartDone(1, "e"))).uploadedPercent)
        assertEquals(100, clip.copy(uploaded = true).uploadedPercent)
    }
}
