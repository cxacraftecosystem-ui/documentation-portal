package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QReviewDecision
import com.fieldrepository.app.questionnaire.data.QReviewSection
import com.fieldrepository.app.questionnaire.data.QSessionReview
import com.fieldrepository.app.questionnaire.data.QSessionTotals
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReleaseGapsTest {
    private val instrument = Instrument(
        "f1", "Toolkit",
        listOf(
            InstrumentSection("secA", "A", "About you", listOf(InstrumentQuestion("qa1", "A1", "Name", "secA"))),
            InstrumentSection("secE", "E", "Tools", listOf(InstrumentQuestion("qe1", "E1", "Tools", "secE"), InstrumentQuestion("qe3", "E3", "Care", "secE")))
        )
    )

    @QFeature("Q7")
    @Test fun `names read as one name, two names, or the first name and others`() {
        assertEquals("", QuestionnaireCopy.namesLine(emptyList()))
        assertEquals("Meera Devi", QuestionnaireCopy.namesLine(listOf("Meera Devi")))
        assertEquals("Meera Devi and Bikash Behera", QuestionnaireCopy.namesLine(listOf("Meera Devi", "Bikash Behera")))
        assertEquals("Ramesh Maharana and 2 others", QuestionnaireCopy.namesLine(listOf("Ramesh Maharana", "Bikash Behera", "Sarojini Das")))
    }

    @QFeature("Q5")
    @Test fun `one question is singular and today reads Today`() {
        assertEquals("1 question", QuestionnaireCopy.questionCount(1))
        assertEquals("5 questions", QuestionnaireCopy.questionCount(5))
        val today = LocalDate.of(2026, 11, 15)
        assertEquals("Today", QuestionnaireCopy.dayOrToday(today, today))
        assertEquals("14 Nov", QuestionnaireCopy.dayOrToday(today.minusDays(1), today))
    }

    @QFeature("Q7")
    @Test fun `status pills count the server totals plus sessions still on this phone`() {
        val counts = totalsCounts(QSessionTotals(all = 41, open = 6, finished = 35), localOpen = 2)
        assertEquals(43, counts.getValue(SessionStatus.ALL))
        assertEquals(8, counts.getValue(SessionStatus.OPEN))
        assertEquals(35, counts.getValue(SessionStatus.FINISHED))
        assertEquals("finished", SessionStatus.FINISHED.wire())
        assertEquals("all", SessionStatus.ALL.wire())
    }

    @QFeature("Q6")
    @Test fun `the review uses the server's counts and what it says is still to ask`() {
        val evidence = SessionEvidence(subjects = setOf("a1"), takes = emptyList(), overlay = emptyList(), typed = emptySet())
        val served = QSessionReview(
            sessionId = "s1", answered = 1, total = 3, sectionsDone = 1, pendingUploads = 2,
            needsDecision = listOf(QReviewDecision("r1", null, listOf("E1"))),
            stillToAsk = listOf(QReviewSection("secE", "E", "Tools", answered = 0, total = 2, lineages = listOf("qe1", "qe3")))
        )
        val model = ReviewModel.of(instrument, evidence, listOf("Meera"), pending = 0, tray = emptyList(), served = served)
        assertEquals(1, model.tally.answered)
        assertEquals(3, model.tally.total)
        assertEquals(listOf("E"), model.stillToAsk.map { it.section.code })
        assertEquals("Not asked · 2", model.stillToAsk.single().trailing)
        assertEquals(listOf("decide:E1"), model.needsYou.map { it.key })
        assertEquals("1 section done for Meera. 2 takes still uploading.", model.summary)
    }
}
