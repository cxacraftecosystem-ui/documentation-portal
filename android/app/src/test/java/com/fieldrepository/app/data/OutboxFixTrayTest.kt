package com.fieldrepository.app.data

import com.fieldrepository.app.ui.questionnaire.OutboxTrayModel
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * Audit H20 (21 Oct 2026): a step the server refused was never shown, retried or discarded, and the
 * workspace read "N takes uploading" for ever. The fix tray lists each refused step with Send again
 * and Discard, and each conflicted answer with P9 (Keep theirs · Keep mine). A 404 from a server with
 * v2 switched off is a switch, not a refusal: the step waits instead of failing.
 */
class OutboxFixTrayTest {
    @get:Rule val tmp = TemporaryFolder()
    private val t0 = Instant.parse("2026-07-01T10:00:00Z")

    private suspend fun session(outbox: SessionOutbox, key: String) = outbox.startSession(
        QSessionCreate(clientKey = key, questionnaireId = "q", sessionDate = "2026-07-01", startedAt = t0.toString(), participants = listOf(QArtisanRef("a1"))), "u1"
    )

    @Test fun `a refused take is a tray row - Send again resends it, Discard deletes it and its file`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        session(outbox, "c-1")
        val clip = testClip(tmp.newFolder(), "k1", 100, "2026-07-01T10:01:00Z", listOf("a1"))
        outbox.addClip("c-1", clip)
        val server = FakeSessionTransport().apply { refuseComplete += "k1" }
        outbox.sync(server, "u1")

        val unit = outbox.unit("c-1")!!
        assertTrue("a refused take is not 'uploading'", unit.uploadingClips.isEmpty())
        val row = OutboxTrayModel.rows(unit, { "Giriraj" }, { it }).single()
        assertEquals("Section D (7 Jul)", row.title)
        assertEquals("Target outside the instrument", row.reason)
        assertTrue(row.sendAgain)
        assertEquals(QErrorCodes.TARGET_OUTSIDE_INSTRUMENT, unit.failedSteps.single().failureCode)

        server.refuseComplete.clear()
        outbox.retryStep("c-1", row.stepId)
        outbox.sync(server, "u1")
        assertTrue(outbox.unit("c-1")!!.failedSteps.isEmpty())

        outbox.addClip("c-1", testClip(tmp.newFolder(), "k2", 100, "2026-07-01T10:02:00Z", listOf("a1")))
        server.refuseComplete += "k2"
        outbox.sync(server, "u1")
        val stuck = outbox.unit("c-1")!!.failedSteps.single()
        val file = File(stuck.clip!!.localPath)
        outbox.discardStep("c-1", stuck.id)
        assertTrue(outbox.unit("c-1")!!.failedSteps.isEmpty())
        assertFalse(file.exists())
    }

    @Test fun `a conflicted answer offers P9, and Keep mine resends on top of the server's version`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        session(outbox, "c-2")
        val fake = FakeSessionTransport()
        var sent: QAnswersPut? = null
        var conflict = true
        val server = object : SessionTransport by fake {
            override suspend fun putAnswers(body: QAnswersPut): QAnswersPutResponse {
                sent = body
                return QAnswersPutResponse(body.items.map {
                    if (conflict) QAnswerResult(it.artisanId, it.questionLineageId, "CONFLICT", current = QAnswer(id = "ans", version = 4, text = "Teak"))
                    else QAnswerResult(it.artisanId, it.questionLineageId, "SAVED", answer = QAnswer(id = "ans", version = 5, text = it.text))
                })
            }
        }
        outbox.addAnswers("c-2", QAnswersPut(listOf(QAnswerItem("a1", "lin1", "Bamboo", sessionId = "c-2", baseVersion = 3))))
        outbox.sync(server, "u1")

        val row = OutboxTrayModel.rows(outbox.unit("c-2")!!, { "Giriraj Prasad" }, { "D3" }).single()
        assertEquals("D3 · Giriraj", row.title)
        assertEquals("Teak", row.reason)
        assertEquals("Bamboo", row.mine)
        assertFalse("P9, not Send again", row.sendAgain)

        outbox.resolveConflict("c-2", row.stepId, "a1", "lin1", keepMine = true)
        conflict = false
        outbox.sync(server, "u1")
        assertEquals(4, sent!!.items.single().baseVersion)
        assertTrue(outbox.unit("c-2")!!.failedSteps.isEmpty())
    }

    @Test fun `Keep theirs drops the item, and the step goes when nothing is left`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        session(outbox, "c-3")
        val fake = FakeSessionTransport()
        val server = object : SessionTransport by fake {
            override suspend fun putAnswers(body: QAnswersPut) = QAnswersPutResponse(body.items.map {
                QAnswerResult(it.artisanId, it.questionLineageId, "CONFLICT", current = QAnswer(version = 2, text = "Teak"))
            })
        }
        outbox.addAnswers("c-3", QAnswersPut(listOf(QAnswerItem("a1", "lin1", "Bamboo", sessionId = "c-3"))))
        outbox.sync(server, "u1")
        val step = outbox.unit("c-3")!!.failedSteps.single()
        outbox.resolveConflict("c-3", step.id, "a1", "lin1", keepMine = false)
        assertTrue(outbox.unit("c-3")!!.steps.none { it.kind == StepKind.ANSWERS })
    }

    /** A QuestionnaireApi nobody calls: the 404 comes from the artisan create below. */
    private val unusedApi = Proxy.newProxyInstance(QuestionnaireApi::class.java.classLoader, arrayOf(QuestionnaireApi::class.java)) { _, _, _ -> error("not called") } as QuestionnaireApi

    private fun notFound() = HttpException(Response.error<Any>(404, """{"detail":{"code":"NOT_FOUND","message":"Not found."}}""".toResponseBody()))

    @Test fun `a 404 from a switched-off v2 waits, a real 404 fails into the tray`() = runBlocking {
        val off = QuestionnaireTransport(unusedApi, { _, _ -> throw notFound() }, v2SwitchedOff = { true })
        val waited = runCatching { off.createArtisan("k", "{}") }.exceptionOrNull()
        assertTrue("v2 off: transient, the step waits", waited is IOException)

        val on = QuestionnaireTransport(unusedApi, { _, _ -> throw notFound() }, v2SwitchedOff = { false })
        val refused = runCatching { on.createArtisan("k", "{}") }.exceptionOrNull()
        assertTrue("v2 on: a refusal", refused is PermanentStepFailure)
        assertEquals(QErrorCodes.NOT_FOUND, (refused as PermanentStepFailure).code)
    }

    @Test fun `after Send again the step starts over with no failure`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        session(outbox, "c-4")
        val unit = outbox.unit("c-4")!!
        val step = unit.steps.single()
        val store = InMemorySessionUnitStore()
        val failed = unit.copy(steps = listOf(step.copy(state = StepState.FAILED, failure = "x", failureCode = "NOT_FOUND", attempts = 7)))
        store.write(failed)
        val again = SessionOutbox(store, clock = { t0 })
        again.retryStep("c-4", step.id)
        val after = again.unit("c-4")!!.steps.single()
        assertEquals(StepState.PENDING, after.state)
        assertNull(after.failure); assertNull(after.failureCode); assertEquals(0, after.attempts)
    }
}
