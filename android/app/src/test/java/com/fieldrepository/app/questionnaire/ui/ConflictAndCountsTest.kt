package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QAnswerResult
import com.fieldrepository.app.questionnaire.data.QSessionTotals
import com.fieldrepository.app.questionnaire.data.QueuedSessionPatch
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.SessionOutbox
import com.fieldrepository.app.questionnaire.data.SessionStep
import com.fieldrepository.app.questionnaire.data.SessionUnit
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.questionnaire.data.StepState
import com.fieldrepository.app.questionnaire.data.outboxJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictAndCountsTest {
    private val instrument = Instrument(
        "f", "Craft survey", listOf(
            InstrumentSection("secA", "A", "Origin", listOf(InstrumentQuestion("l1", "A1", "History?", "secA"))),
            InstrumentSection("secB", "B", "Family", listOf(InstrumentQuestion("l2", "B1", "Who helps?", "secB")))
        )
    )

    private fun unit(vararg steps: SessionStep) = SessionUnit("c-1", createdAt = "2026-11-14T10:00:00Z", questionnaireId = "f", sessionId = "s-1", steps = steps.toList())

    private val recordByConflict = SessionStep(
        "p1", StepKind.SESSION_PATCH, "t", "i", state = StepState.FAILED, failureCode = SessionOutbox.RECORD_BY_CONFLICT,
        failure = QuestionnaireCopy.s("p9.title"),
        bodyJson = outboxJson.encodeToString(QueuedSessionPatch.serializer(), QueuedSessionPatch("SECTION", before = "QUESTION"))
    )

    private val answerConflict = SessionStep(
        "a1", StepKind.ANSWERS, "t", "j", state = StepState.FAILED, failure = "CONFLICT", failureCode = "CONFLICT",
        conflicts = listOf(QAnswerResult("art1", "l1", "CONFLICT"))
    )

    @QFeature("Q2")
    @Test fun `a conflict is not an upload failure and has no Retry on the save pill`() {
        val pill = SavePillModel.of(unit(recordByConflict, answerConflict))
        assertEquals(SaveTone.CONFLICT, pill.tone)
        assertEquals("Changed on another device", pill.text)
        assertTrue(pill.failedSteps.isEmpty())
        val failedFinish = SessionStep("c1", StepKind.FINISH, "t", "k", state = StepState.FAILED, failure = "Refused")
        val mixed = SavePillModel.of(unit(recordByConflict, failedFinish))
        assertEquals(SaveTone.FAILED, mixed.tone)
        assertEquals(listOf("c1"), mixed.failedSteps)
    }

    @QFeature("Q2")
    @Test fun `a Record by conflict asks keep mine or keep theirs, and Review sends it to the tray instead of retrying`() {
        val rows = OutboxTrayModel.rows(unit(recordByConflict), { "" }, { it })
        val row = rows.single()
        assertTrue(row.choice)
        assertFalse(row.sendAgain)
        assertEquals("Question", row.reason)
        assertEquals("Section", row.mine)
        val model = ReviewModel.of(instrument, SessionEvidence(setOf("art1"), emptyList(), emptyList(), emptySet()), listOf("Meera"), 0, rows)
        val needs = model.needsYou.single()
        assertTrue(needs.conflict)
        assertEquals("Changed on another device", needs.detail)
        assertEquals("Choose", needs.action)
        val plain = OutboxTrayModel.rows(unit(recordByConflict.copy(failureCode = null)), { "" }, { it }).single()
        assertTrue(plain.sendAgain)
        assertFalse(plain.choice)
    }

    @QFeature("Q6")
    @Test fun `review warns about required questions left unanswered before Finish`() {
        val required = Instrument(
            "f", "Craft survey", listOf(
                InstrumentSection("secA", "A", "Origin", listOf(
                    InstrumentQuestion("l1", "A1", "History?", "secA", required = true),
                    InstrumentQuestion("l2", "A2", "Teacher?", "secA", required = true)
                ))
            )
        )
        val evidence = SessionEvidence(setOf("art1"), emptyList(), emptyList(), setOf("art1" to "l1"))
        val count = required.requiredUnanswered(listOf("art1")) { s, l -> (s to l) in evidence.typed }
        assertEquals(1, count)
        val model = ReviewModel.of(required, evidence, listOf("Meera"), 0, emptyList(), required = count)
        assertTrue(model.summary.endsWith(" 1 required question unanswered."))
        val none = ReviewModel.of(required, evidence, listOf("Meera"), 0, emptyList(), required = 0)
        assertFalse(none.summary.contains("required"))
    }

    @QFeature("Q7")
    @Test fun `sessions still on this phone count the same whichever status pill is selected`() {
        val local = listOf(SessionListRow(key = "c-local", title = "Meera", note = null, meta = "", local = true, session = null))
        val totals = QSessionTotals(all = 9, open = 5, finished = 4)
        val counts = SessionStatus.entries.map { totalsCounts(totals, local.size) }.distinct().single()
        assertEquals(10, counts.getValue(SessionStatus.ALL))
        assertEquals(6, counts.getValue(SessionStatus.OPEN))
        assertEquals(emptyList<SessionListRow>(), listedLocal(local, SessionStatus.FINISHED))
        assertEquals(local, listedLocal(local, SessionStatus.OPEN))
    }

    @QFeature("Q2")
    @Test fun `the menu's second item reads Edit details`() {
        assertEquals("Edit details", QuestionnaireCopy.t("editSession"))
    }
}
