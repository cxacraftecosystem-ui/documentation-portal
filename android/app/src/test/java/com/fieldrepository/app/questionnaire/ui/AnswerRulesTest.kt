package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.InMemorySessionUnitStore
import com.fieldrepository.app.questionnaire.data.QAnswer
import com.fieldrepository.app.questionnaire.data.QAnswerResult
import com.fieldrepository.app.questionnaire.data.QAnswersPut
import com.fieldrepository.app.questionnaire.data.QArtisanRef
import com.fieldrepository.app.questionnaire.data.QErrorCodes
import com.fieldrepository.app.questionnaire.data.QFormDetail
import com.fieldrepository.app.questionnaire.data.QFormQuestion
import com.fieldrepository.app.questionnaire.data.QFormSection
import com.fieldrepository.app.questionnaire.data.QSessionCreate
import com.fieldrepository.app.questionnaire.data.QUserRef
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.SessionOutbox
import com.fieldrepository.app.questionnaire.data.SessionTransport
import com.fieldrepository.app.questionnaire.data.FakeSessionTransport
import com.fieldrepository.app.questionnaire.data.QAnswersPutResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The answer rules the designer portal brings to every client: the one answer lock (U9, P15), notes
 * (A20), required questions on Finish (A19), group answers (B20), and answers taken from a take's
 * transcript or the drafter (B22, B23), which are proposals a person accepts, never imposed.
 */
class AnswerRulesTest {
    private val t0 = Instant.parse("2026-07-01T10:00:00Z")
    private val nandini = QAnswer(id = "ans1", version = 2, text = "Teak", artisanId = "a1", questionLineageId = "lin1", actor = QUserRef("u3", "Nandini Rao"))

    @QFeature("A11")
    @Test fun `an answer someone else wrote reads locked, with P15, to anyone but its author, a lead or an admin`() {
        assertTrue(AnswerModel.locked(nandini, meId = "u9", leadOrAdmin = false))
        assertFalse("its author", AnswerModel.locked(nandini, meId = "u3", leadOrAdmin = false))
        assertFalse("a lead or an admin", AnswerModel.locked(nandini, meId = "u9", leadOrAdmin = true))
        assertFalse("an empty answer is anyone's to fill", AnswerModel.locked(nandini.copy(text = " "), "u9", false))
        assertFalse("a prefill is nobody's account", AnswerModel.locked(nandini.copy(origin = "PREFILL"), "u9", false))
        val line = AnswerModel.lines(listOf("a1"), "lin1", listOf(nandini), null, meId = "u9") { null }.single()
        assertEquals("Answered by Nandini Rao. Only they or a lead can change it.", line.lockedLine)
        assertEquals("ans1", line.answerId)
    }

    @QFeature("A11")
    @Test fun `a locked item the server refuses waits in the tray with P15, the others save`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        outbox.startSession(QSessionCreate(clientKey = "c", questionnaireId = "q", sessionDate = "2026-07-01", startedAt = t0.toString(), participants = listOf(QArtisanRef("a1"), QArtisanRef("a2"))), "u9")
        outbox.addAnswers("c", QAnswersPut(AnswerModel.items("Sal", setOf("a1"), "lin1", "c", emptyList()) + AnswerModel.items("Bamboo", setOf("a2"), "lin1", "c", emptyList())))
        val fake = FakeSessionTransport()
        val server = object : SessionTransport by fake {
            override suspend fun putAnswers(body: QAnswersPut) = QAnswersPutResponse(body.items.map {
                if (it.artisanId == "a1") QAnswerResult(it.artisanId, it.questionLineageId, "FORBIDDEN", current = nandini, code = QErrorCodes.ANSWER_LOCKED)
                else QAnswerResult(it.artisanId, it.questionLineageId, "SAVED", answer = QAnswer(id = "x", version = 1, text = it.text))
            })
        }
        outbox.sync(server, "u9")
        val row = OutboxTrayModel.rows(outbox.unit("c")!!, { "Giriraj" }, { "D3" }).single()
        assertEquals("Answered by Nandini Rao. Only they or a lead can change it.", row.reason)
        assertNull("not a P9 choice: the answer is not this researcher's", row.conflict)
        assertFalse(row.sendAgain)
    }

    @QFeature("B39")
    @Test fun `Send without the conflicting answers lets the refused ones go and keeps what landed`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        outbox.startSession(QSessionCreate(clientKey = "c2", questionnaireId = "q", sessionDate = "2026-07-01", startedAt = t0.toString(), participants = listOf(QArtisanRef("a1"), QArtisanRef("a2"))), "u9")
        outbox.addAnswers("c2", QAnswersPut(AnswerModel.items("Sal", setOf("a1"), "lin1", "c2", emptyList()) + AnswerModel.items("Bamboo", setOf("a2"), "lin1", "c2", emptyList())))
        val fake = FakeSessionTransport()
        val server = object : SessionTransport by fake {
            override suspend fun putAnswers(body: QAnswersPut) = QAnswersPutResponse(body.items.map {
                if (it.artisanId == "a1") QAnswerResult(it.artisanId, it.questionLineageId, "CONFLICT", current = QAnswer(id = "x", version = 3, text = "Teak"))
                else QAnswerResult(it.artisanId, it.questionLineageId, "SAVED", answer = QAnswer(id = "y", version = 1, text = it.text))
            })
        }
        outbox.sync(server, "u9")
        val rows = OutboxTrayModel.rows(outbox.unit("c2")!!, { "Giriraj" }, { "D3" })
        val step = OutboxTrayModel.conflictSteps(rows).single()
        outbox.dropConflicts("c2", step)
        assertTrue(outbox.unit("c2")!!.failedSteps.isEmpty())
        assertTrue("nothing is left to send again", outbox.unit("c2")!!.steps.none { it.kind == com.fieldrepository.app.questionnaire.data.StepKind.ANSWERS })
    }

    @QFeature("A20")
    @Test fun `a note goes with the answer it was written on, and shows under it`() {
        val item = AnswerModel.items("Teak", setOf("a1"), "lin1", "s1", emptyList(), note = "  Said twice  ").single()
        assertEquals("Said twice", item.note)
        assertEquals("Said twice", QAnswersPut(listOf(item)).wire().items.single().note)
        assertNull(AnswerModel.items("Teak", setOf("a1"), "lin1", "s1", emptyList(), note = " ").single().note)
        val shown = AnswerModel.lines(listOf("a1"), "lin1", listOf(nandini.copy(note = "Wholesale price")), null) { null }.single()
        assertEquals("Wholesale price", shown.note)
    }

    @QFeature("C8")
    @Test fun `a formatted answer keeps its plain words and its formatting, a plain one only its words`() {
        val rich = """{"blocks":[{"kind":"BULLET","spans":[{"text":"Teak"}]}]}"""
        val item = AnswerModel.items("Teak", setOf("a1"), "lin1", "s1", emptyList(), richText = rich).single()
        val wire = QAnswersPut(listOf(item)).wire().items.single()
        assertEquals("Teak", wire.text)
        assertEquals(rich, wire.richText)
        val plain = com.fieldrepository.app.questionnaire.portal.QuestionnairePortal.json.encodeToString(
            com.fieldrepository.app.questionnaire.data.QAnswersPutWire.serializer(),
            QAnswersPut(AnswerModel.items("Teak", setOf("a1"), "lin1", "s1", emptyList(), richText = " ")).wire()
        )
        assertFalse("no richText key for plain prose", plain.contains("richText"))
    }

    @QFeature("A19")
    @Test fun `Finish says how many required questions are left and never blocks`() {
        val form = QFormDetail(
            id = "f", title = "Market survey", sections = listOf(QFormSection(id = "s1", code = "A", questions = listOf(
                QFormQuestion(id = "q1", prompt = "Price?", isRequired = true, lineageId = "l1", displayCode = "A1"),
                QFormQuestion(id = "q2", prompt = "Buyers?", isRequired = true, lineageId = "l2", displayCode = "A2"),
                QFormQuestion(id = "q3", prompt = "Notes", lineageId = "l3", displayCode = "A3"),
                QFormQuestion(id = "q4", prompt = "Old", isRequired = true, retiredAt = "2026-01-01T00:00:00Z")
            )))
        )
        val instrument = Instrument.from(form)
        assertEquals("a retired question is not asked", 3, instrument.sections.single().questions.size)
        val answered = setOf("a1" to "l1")
        assertEquals(1, instrument.requiredUnanswered(listOf("a1")) { s, l -> (s to l) in answered })
        assertEquals("1 required question unanswered.", QuestionnaireCopy.requiredUnanswered(1))
        assertEquals("2 required questions unanswered.", QuestionnaireCopy.requiredUnanswered(2))
        assertNull(QuestionnaireCopy.requiredUnanswered(0))
    }

    @QFeature("B20")
    @Test fun `an answer for all of a group is one group answer per person`() {
        val group = AnswerModel.items("We buy from Sanganer", setOf("a1", "a2", "R:g1"), "lin1", "s1", emptyList())
        assertTrue(group.all { it.attribution == "GROUP_SHARED" })
        assertEquals(setOf("a1", "a2", "R:g1"), group.map { it.artisanId }.toSet())
        val roster = listOf(HeaderParticipant("a1", "Giriraj", true), HeaderParticipant("a2", "Meena", true), HeaderParticipant("R:g1", "Ravi", true, guest = true))
        assertEquals("All 3", answerForOptions(roster).first().label)
        assertEquals(setOf("a2"), answerForIds(roster, "a2"))
    }

    @QFeature("B22")
    @Test fun `Use as answer proposes the take's transcript and saves it as from that take`() {
        val item = AnswerModel.items("Teak from the forest office", setOf("a1"), "lin1", "s1", emptyList(), origin = "TRANSCRIPT", sourceRecordingId = "r1").single()
        assertEquals("TRANSCRIPT", item.origin)
        assertEquals("r1", item.sourceRecordingId)
        // P8: replacing an answer that came from a recording asks first.
        val fromTake = QAnswer(id = "x", text = "Sal", sourceRecordingId = "r0", artisanId = "a1", questionLineageId = "lin1")
        assertEquals(fromTake, AnswerModel.replacesRecordedAnswer(listOf(fromTake), setOf("a1"), "lin1"))
    }

    @QFeature("C2")
    @Test fun `an edited transcript is kept as the person's answer, not the machine's`() {
        val accepted = AnswerModel.items("Teak", setOf("a1"), "lin1", "s1", emptyList(), origin = "MACHINE_SUGGESTED_EDITED", sourceRecordingId = "r1").single()
        assertEquals("MACHINE_SUGGESTED_EDITED", accepted.origin)
    }

    @QFeature("B23")
    @Test fun `a drafted answer from a section take is accepted per question, never written by itself`() {
        val proposal = com.fieldrepository.app.questionnaire.data.QProposal("lin2", artisanIds = null, text = "Cotton and silk", origin = "MACHINE_SUGGESTED")
        val roster = listOf(HeaderParticipant("a1", "Giriraj", true), HeaderParticipant("a2", "Meena", true))
        // A take that does not say who answered asks "Whose answer is this?" first; the choice decides.
        val ids = proposal.artisanIds?.toSet() ?: answerForIds(roster, "a1")
        val items = AnswerModel.items(proposal.text, ids, proposal.questionLineageId, "s1", emptyList(), origin = proposal.origin, sourceRecordingId = "r5")
        assertEquals(listOf("a1"), items.map { it.artisanId })
        assertEquals("MACHINE_SUGGESTED", items.single().origin)
    }

    @QFeature("C3")
    @Test fun `drafts are proposals from one take's transcript, kept apart from typed answers`() {
        val typed = AnswerModel.typedPairs(listOf(QAnswer(text = "Teak", artisanId = "a1", questionLineageId = "lin1")), null)
        assertEquals(setOf("a1" to "lin1"), typed)
        assertTrue(AnswerModel.typedPairs(listOf(QAnswer(text = "", artisanId = "a1", questionLineageId = "lin1")), null).isEmpty())
    }

    @QFeature("B24")
    @Test fun `Fill from profile asks the server per artisan, never over a typed answer, never for a guest`() {
        val typed = QAnswer(id = "t", text = "Teak", origin = "TYPED", artisanId = "a2", questionLineageId = "l-phone")
        val items = AnswerModel.prefillItems(listOf("a1", "a2", "R:g1"), "l-phone", "s1", listOf(typed))
        assertEquals(listOf("a1"), items.map { it.artisanId })
        assertEquals("PREFILL", items.single().origin)
        assertEquals("INDIVIDUAL", items.single().attribution)
        val again = AnswerModel.prefillItems(listOf("a1"), "l-phone", "s1", listOf(QAnswer(id = "p", text = "+91 98765 43210", origin = "PREFILL", artisanId = "a1", questionLineageId = "l-phone")))
        assertEquals("a prefill may refresh an earlier prefill", 1, again.size)
        assertEquals("phone", Instrument.from(QFormDetail("f", sections = listOf(QFormSection("s", code = "A", questions = listOf(QFormQuestion("q", artisanField = "phone")))))).sections.single().questions.single().artisanField)
    }
}
