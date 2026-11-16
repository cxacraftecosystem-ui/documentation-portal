package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.questionnaire.QFeature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class OutboxRevisionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = Instant.parse("2026-11-16T05:00:00Z")
    private fun body(key: String) = QSessionCreate(
        clientKey = key, questionnaireId = "q", sessionDate = "2026-11-16", startedAt = now.toString(), participants = listOf(QArtisanRef("a1"))
    )

    @QFeature("B38")
    @Test fun `every write to the outbox moves its revision, so the session screen sees a take land while it is open`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { now })
        val server = FakeSessionTransport()
        val dir = tmp.newFolder()
        val start = outbox.revision.value
        outbox.startSession(body("c-1"), "u1")
        val afterStart = outbox.revision.value
        assertTrue(afterStart > start)
        outbox.addClip("c-1", testClip(dir, "k1", 200, "2026-11-16T05:01:00Z", listOf("a1")))
        val afterClip = outbox.revision.value
        assertTrue(afterClip > afterStart)
        outbox.sync(server, "u1")
        assertTrue(outbox.revision.value > afterClip)
        assertEquals(StepState.DONE, outbox.unit("c-1")!!.steps.first { it.kind == StepKind.CLIP }.state)
    }

    @QFeature("B38")
    @Test fun `the background pass counts the takes still waiting for this account only`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { now })
        val dir = tmp.newFolder()
        outbox.startSession(body("c-1"), "u1")
        outbox.addClip("c-1", testClip(dir, "k1", 200, "2026-11-16T05:01:00Z", listOf("a1")))
        outbox.addClip("c-1", testClip(dir, "k2", 200, "2026-11-16T05:02:00Z", listOf("a1")))
        outbox.startSession(body("c-2"), "u2")
        assertEquals(3, outbox.waiting("u1"))
        assertEquals(1, outbox.waiting("u2"))
        assertEquals(4, outbox.waiting(null))
        outbox.sync(FakeSessionTransport(), "u1")
        assertEquals(0, outbox.waiting("u1"))
    }
}
