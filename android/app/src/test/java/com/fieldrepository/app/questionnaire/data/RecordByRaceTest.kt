package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.questionnaire.QFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RecordByRaceTest {
    private val t0 = Instant.parse("2026-07-01T10:00:00Z")
    private var tick = 0L
    private val clock = { t0.plusSeconds(tick++) }

    private fun body(key: String) = QSessionCreate(
        clientKey = key, questionnaireId = "q_ctw3", workshopId = "w_bankura", sessionDate = "2026-07-01",
        startedAt = t0.toString(), participants = listOf(QArtisanRef("a1"))
    )

    private class GatedCreate(val inner: FakeSessionTransport) : SessionTransport by inner {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        override suspend fun createSession(idempotencyKey: String, body: QSessionCreate): QSession {
            entered.complete(Unit)
            gate.await()
            return inner.createSession(idempotencyKey, body)
        }
    }

    @QFeature("Q1")
    @Test
    fun `switching back before the next poll is not a conflict with my own earlier change`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = clock)
        outbox.startSession(body("c-own"), "u3")
        val server = FakeSessionTransport()
        outbox.sync(server, "u3")
        outbox.setRecordBy("c-own", "QUESTION", before = "SECTION")
        outbox.sync(server, "u3")
        assertEquals("QUESTION", server.serverRecordBy)
        outbox.setRecordBy("c-own", "SECTION", before = "SECTION")
        outbox.sync(server, "u3")
        assertEquals("SECTION", server.serverRecordBy)
        assertTrue(outbox.unit("c-own")!!.steps.all { it.state == StepState.DONE })
        assertEquals("SECTION", outbox.offlineRecordBy(outbox.unit("c-own")))
    }

    @QFeature("Q1")
    @Test
    fun `a change made on another device after my own change still waits in the tray`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = clock)
        outbox.startSession(body("c-other"), "u3")
        val server = FakeSessionTransport()
        outbox.sync(server, "u3")
        outbox.setRecordBy("c-other", "QUESTION", before = "SECTION")
        outbox.sync(server, "u3")
        server.serverRecordBy = "WHOLE_ELSEWHERE"
        outbox.setRecordBy("c-other", "SECTION", before = "QUESTION")
        outbox.sync(server, "u3")
        val step = outbox.unit("c-other")!!.steps.last { it.kind == StepKind.SESSION_PATCH }
        assertEquals(StepState.FAILED, step.state)
        assertEquals(SessionOutbox.RECORD_BY_CONFLICT, step.failureCode)
        assertEquals("WHOLE_ELSEWHERE", server.serverRecordBy)
    }

    @QFeature("Q1")
    @Test
    fun `an opened server session keeps its Record by for use without signal`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = clock)
        val session = QSession(id = "s-9", clientKey = "c-9", recordBy = RecordBy.QUESTION.wire)
        val unit = outbox.adoptSession(session, "q_ctw3", "u3")
        assertEquals(RecordBy.QUESTION.wire, unit.recordBy)
        assertEquals(RecordBy.QUESTION.wire, outbox.offlineRecordBy(outbox.unit("c-9")))
        outbox.adoptSession(session.copy(recordBy = RecordBy.SECTION.wire), "q_ctw3", "u3")
        assertEquals(RecordBy.SECTION.wire, outbox.offlineRecordBy(outbox.unit("c-9")))
    }

    @QFeature("Q1")
    @Test
    fun `Record by changed while the create is in flight reaches the server`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = clock)
        outbox.startSession(body("c-fly"), "u3")
        val fake = FakeSessionTransport()
        val server = GatedCreate(fake)
        val pass = launch { outbox.sync(server, "u3") }
        server.entered.await()
        val edit = launch { outbox.setRecordBy("c-fly", "QUESTION", before = "SECTION") }
        yield()
        server.gate.complete(Unit)
        pass.join()
        edit.join()
        assertTrue(fake.bodies.first().contains("\"recordBy\":\"SECTION\""))
        outbox.sync(server, "u3")
        assertEquals("QUESTION", fake.serverRecordBy)
        assertTrue(outbox.unit("c-fly")!!.steps.all { it.state == StepState.DONE })
    }

    @QFeature("Q1")
    @Test
    fun `a guest added while the create is in flight is sent, not lost`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = clock)
        outbox.startSession(body("c-guest"), "u3")
        val fake = FakeSessionTransport()
        val server = GatedCreate(fake)
        val pass = launch { outbox.sync(server, "u3") }
        server.entered.await()
        val add = launch { outbox.addRespondent("c-guest", "local-g1", QRespondentInput(name = "Sita")) }
        yield()
        server.gate.complete(Unit)
        pass.join()
        add.join()
        outbox.sync(server, "u3")
        assertEquals(1, fake.calls.count { it == "respondent" })
        assertTrue(fake.bodies.any { it.contains("Sita") })
        assertTrue(outbox.unit("c-guest")!!.respondentIds.containsKey("local-g1"))
    }
}
