package com.fieldrepository.app.ui

import com.fieldrepository.app.data.QCompleteSubject
import com.fieldrepository.app.data.QInbox
import com.fieldrepository.app.data.SessionOutbox
import com.fieldrepository.app.ui.questionnaire.HeaderParticipant
import com.fieldrepository.app.ui.questionnaire.answerForIds
import com.fieldrepository.app.ui.questionnaire.answerForOptions
import com.fieldrepository.app.ui.questionnaire.inboxSections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The inbox rows and their in-place decisions, "Answer for", and the "Answered by" subject rule. */
class QuestionnaireInboxAndAnswerTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun `inbox rows read from the server's fields, in DESIGN order, with decisions only where the API answers them`() {
        val inbox = QInbox(
            toFile = listOf(obj("""{"mediaId":"m1","name":"f-sec-1718.webm","date":"2026-06-23T05:00:00Z","sessionId":null}""")),
            needsDecision = listOf(obj("""{"recordingId":"r8","sessionId":"s1","decisionKind":"CONCURRENT","targets":["D3"],"artisanNames":"Giriraj","capturedAt":"2026-07-01T12:54:00Z"}""")),
            setDates = listOf(obj("""{"workshopId":"w1","workshop":"Bankura","sessionIds":["s4","s5"],"from":"2026-06-20","to":"2026-06-24","count":2}""")),
            noParticipants = listOf(obj("""{"sessionId":"s6","title":null,"date":"2026-07-07"}""")),
            emptySessions = listOf(obj("""{"sessionId":"s7","title":"Interview_2026-07-08_Bankura","approvedAt":"2026-07-09T04:00:00Z"}"""))
        )
        val sections = inboxSections(inbox)
        assertEquals(listOf("To file", "Needs a decision", "Set dates", "Who was there", "Empty session"), sections.map { it.first })
        val (toFile, decide, dates, nobody, empty) = sections.map { it.second.single() }
        assertEquals("f-sec-1718.webm · 23 Jun", toFile.text)
        assertNull(toFile.sessionId)
        assertEquals("D3 · Giriraj · 1 Jul 18:24", decide.text)
        assertEquals("needsDecision", decide.section)
        assertEquals("r8", decide.recordingId)
        assertEquals("m1", toFile.mediaId)
        assertEquals("Bankura · 2 legacy sessions, 20 Jun–24 Jun", dates.text)
        assertEquals("s4", dates.sessionId)
        assertEquals("Session of 7 Jul has no artisans", nobody.text)
        assertEquals("Interview_2026-07-08_Bankura · Empty session, approved 9 Jul", empty.text)
    }

    @Test fun `answer for offers All N then each artisan, and resolves to the ids saved`() {
        val roster = listOf(HeaderParticipant("a1", "Giriraj Prasad", true), HeaderParticipant("a2", "Meena Pal", true))
        assertEquals(listOf("All 2", "Giriraj Prasad", "Meena Pal"), answerForOptions(roster).map { it.label })
        assertEquals(setOf("a1", "a2"), answerForIds(roster, answerForOptions(roster).first().value))
        assertEquals(setOf("a2"), answerForIds(roster, "a2"))
        assertEquals("a removed artisan falls back to everyone", setOf("a1", "a2"), answerForIds(roster, "gone"))
    }

    @Test fun `answered by marks the unticked PRESENT, never drops them, and names a subset`() {
        val subjects = listOf(QCompleteSubject("a1"), QCompleteSubject("a2"), QCompleteSubject("a3"))
        val out = SessionOutbox.withAnswered(subjects, setOf("a1"))
        assertEquals(listOf("ANSWERED", "PRESENT", "PRESENT"), out.map { it.role })
        assertEquals(listOf(true, false, false), out.map { it.named })
        assertEquals(listOf(false, false, false), SessionOutbox.withAnswered(subjects, setOf("a1", "a2", "a3")).map { it.named })
    }

    @Test fun `the missing-file sentence is the shared one`() {
        assertEquals("The file for Section D (7 Jul) is missing on this device.", SessionOutbox.missingFileMessage("Section D (7 Jul)"))
        assertEquals("The file for D3 is missing on this device.", SessionOutbox.missingFileMessage("D3"))
    }
}
