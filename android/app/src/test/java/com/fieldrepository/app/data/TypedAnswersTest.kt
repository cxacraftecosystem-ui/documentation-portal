package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.ui.questionnaire.AnswerModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Audit H19 (21 Oct 2026): a typed answer vanished after Save, no `baseVersion` was sent, and the
 * second save therefore came back CONFLICT and stuck. The question now shows each artisan's answer
 * (this phone's queued one first), every save names the version it replaces, and a second save typed
 * before the first synced is sent on top of the first instead of clashing with it.
 */
class TypedAnswersTest {
    private val t0 = Instant.parse("2026-07-01T10:00:00Z")

    /** A server that keeps one version per answer and refuses a save on a stale base, as `answers.py` does. */
    private class VersionedServer(private val fake: FakeSessionTransport = FakeSessionTransport()) : SessionTransport by fake {
        val versions = mutableMapOf<String, Int>()
        val conflicts = mutableListOf<String>()
        override suspend fun putAnswers(body: QAnswersPut): QAnswersPutResponse = QAnswersPutResponse(body.items.map { item ->
            val key = "${item.artisanId}|${item.questionLineageId}"
            val current = versions[key]
            if (current != null && item.baseVersion != current) {
                conflicts += key
                QAnswerResult(item.artisanId, item.questionLineageId, "CONFLICT", current = QAnswer(version = current, text = "theirs"))
            } else {
                versions[key] = (current ?: 0) + 1
                QAnswerResult(item.artisanId, item.questionLineageId, "SAVED", answer = QAnswer(version = versions.getValue(key), text = item.text))
            }
        })
    }

    @Test fun `two saves typed offline land one after the other, never as a clash with each other`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        outbox.startSession(QSessionCreate(clientKey = "c", questionnaireId = "q", sessionDate = "2026-07-01", startedAt = t0.toString(), participants = listOf(QArtisanRef("a1"))), "u1")
        // Both typed with no answer on the server yet: neither carries a base.
        outbox.addAnswers("c", QAnswersPut(AnswerModel.items("Bamboo", setOf("a1"), "lin1", "c", emptyList())))
        outbox.addAnswers("c", QAnswersPut(AnswerModel.items("Bamboo and cane", setOf("a1"), "lin1", "c", emptyList())))
        val server = VersionedServer()
        outbox.sync(server, "u1")
        assertTrue("no conflict with itself", server.conflicts.isEmpty())
        assertEquals(2, server.versions["a1|lin1"])
        assertTrue(outbox.unit("c")!!.failedSteps.isEmpty())
    }

    @QFeature("B19")
    @QFeature("B20")
    @Test fun `a save carries the version of the answer it replaces`() {
        val current = listOf(QAnswer(id = "x", version = 3, text = "Teak", artisanId = "a1", questionLineageId = "lin1"))
        val one = AnswerModel.items("Sal", setOf("a1"), "lin1", "s1", current).single()
        assertEquals(3, one.baseVersion)
        assertEquals("INDIVIDUAL", one.attribution)
        val group = AnswerModel.items("Sal", setOf("a1", "a2"), "lin1", "s1", current)
        assertEquals(listOf("GROUP_SHARED", "GROUP_SHARED"), group.map { it.attribution })
        assertEquals(listOf(3, null), group.map { it.baseVersion })
    }

    @Test fun `the question shows the answer - this phone's queued one first, then the server's with its badge`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        outbox.startSession(QSessionCreate(clientKey = "c", questionnaireId = "q", sessionDate = "2026-07-01", startedAt = t0.toString(), participants = listOf(QArtisanRef("a1"))), "u1")
        outbox.addAnswers("c", QAnswersPut(listOf(QAnswerItem("a1", "lin1", "Queued text", sessionId = "c"))))
        val server = listOf(
            QAnswer(text = "Old", artisanId = "a1", questionLineageId = "lin1"),
            QAnswer(text = "From the take", sourceRecordingId = "r1", artisanId = "a2", questionLineageId = "lin1")
        )
        val lines = AnswerModel.lines(listOf("a1", "a2", "a3"), "lin1", server, outbox.unit("c")) { null }
        assertEquals(listOf("Queued text", "From the take"), lines.map { it.text })
        assertEquals(listOf(true, false), lines.map { it.onPhone })
        assertEquals(QuestionnaireCopy.s("record.fromRecording"), lines[1].badge)
    }

    @Test fun `P8 asks before an answer that came from a recording is replaced`() {
        val answers = listOf(
            QAnswer(text = "Typed", artisanId = "a1", questionLineageId = "lin1"),
            QAnswer(text = "Heard", sourceRecordingId = "r9", artisanId = "a2", questionLineageId = "lin1")
        )
        assertNull(AnswerModel.replacesRecordedAnswer(answers, setOf("a1"), "lin1"))
        assertEquals("r9", AnswerModel.replacesRecordedAnswer(answers, setOf("a1", "a2"), "lin1")?.sourceRecordingId)
    }
}
