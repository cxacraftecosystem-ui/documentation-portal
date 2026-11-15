package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QParticipant
import com.fieldrepository.app.questionnaire.data.QSectionProgress
import com.fieldrepository.app.questionnaire.data.QSession
import com.fieldrepository.app.questionnaire.data.QSessionCounts
import com.fieldrepository.app.questionnaire.data.QSessionProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@QFeature("Q7")
class SessionsListRulesTest {
    private val instrument = Instrument(
        "f1", "Toolkit",
        listOf(
            InstrumentSection("secA", "A", "About you", listOf(InstrumentQuestion("qa1", "A1", "Name", "secA"))),
            InstrumentSection("secE", "E", "Tools", listOf(InstrumentQuestion("qe1", "E1", "Tools", "secE"), InstrumentQuestion("qe3", "E3", "Care", "secE")))
        )
    )

    private fun session(id: String, state: String = "OPEN", date: String = "2026-11-15", answered: Int = 18, total: Int = 108) = QSession(
        id = id, clientKey = "c-$id", state = state, sessionDate = date, crafts = listOf("Stone carving"),
        participants = listOf(QParticipant("a1", "Meera Devi")),
        counts = QSessionCounts(sections = 3, takes = 4, answeredQuestions = answered, totalQuestions = total)
    )

    @QFeature("Q5")
    @Test fun `a row names the people and reads question progress with its status`() {
        val open = serverRow(session("s1"))
        assertEquals("Meera Devi", open.people)
        assertEquals("Stone carving · 15 Nov · 18 of 108", open.meta)
        assertFalse(open.finished)
        val done = serverRow(session("s2", state = "CLOSED", answered = 108).copy(progress = QSessionProgress(108, 108)))
        assertTrue(done.finished)
        assertEquals(108, done.answered)
        assertEquals("Stone carving · 15 Nov", serverRow(session("s3", total = 0)).meta)
    }

    @Test fun `the status pills count and filter the list`() {
        val rows = listOf(serverRow(session("s1")), serverRow(session("s2", state = "CLOSED")), serverRow(session("s3", state = "CLOSED")))
        val counts = statusCounts(rows)
        assertEquals(3, counts[SessionStatus.ALL]); assertEquals(1, counts[SessionStatus.OPEN]); assertEquals(2, counts[SessionStatus.FINISHED])
        assertEquals(listOf("c-s2", "c-s3"), byStatus(rows, SessionStatus.FINISHED).map { it.key })
        assertEquals(rows, byStatus(rows, SessionStatus.ALL))
        assertEquals("All 3", statusLabel(SessionStatus.ALL, 3))
        assertEquals("Finished", statusLabel(SessionStatus.FINISHED, null))
    }

    @Test fun `open today is the first open session dated today`() {
        val today = LocalDate.parse("2026-11-15")
        val rows = listOf(serverRow(session("s0", state = "CLOSED")), serverRow(session("s1", date = "2026-11-14")), serverRow(session("s2")))
        assertEquals("c-s2", openToday(rows, today)?.key)
        assertNull(openToday(rows.take(2), today))
    }

    @Test fun `continue opens the last target anyone worked in, else the first unfinished section`() {
        val worked = listOf("2026-11-15T05:00:00Z" to "S:secA", "2026-11-15T06:10:00Z" to "Q:qe3", null to "S:secE", "2026-11-15T06:00:00Z" to "S:secA")
        assertEquals("Q:qe3", lastTarget(worked))
        assertEquals(ContinueAt("secE", "E3"), continueAt("Q:qe3", instrument, null))
        assertEquals(ContinueAt("secA", "A"), continueAt("S:secA", instrument, null))
        val progress = QSessionProgress(1, 3, listOf(QSectionProgress("secA", 1, 1, inSectionTake = true), QSectionProgress("secE", 0, 2)))
        assertEquals(ContinueAt("secE", "E"), continueAt(null, instrument, progress))
        assertEquals(ContinueAt("secA", "A"), continueAt("Q:gone", instrument, null))
        assertNull(continueAt("Q:qe3", null, progress))
        assertEquals(1, sectionsDone(progress))
    }
}
