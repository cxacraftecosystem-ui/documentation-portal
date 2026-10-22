package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QAnswer
import com.fieldrepository.app.questionnaire.data.QConsentVerdict
import com.fieldrepository.app.questionnaire.data.QParticipant
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QRecordingEvent
import com.fieldrepository.app.questionnaire.data.QRespondent
import com.fieldrepository.app.questionnaire.data.QRevision
import com.fieldrepository.app.questionnaire.data.QSessionDetail
import com.fieldrepository.app.questionnaire.data.QSubject
import com.fieldrepository.app.questionnaire.data.QTranscript
import com.fieldrepository.app.questionnaire.data.QUserRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The read view and review (B27), History (B21), a take's history (B40), the transcript line while
 * transcription is paused (B34) and the consent line (B33, C4).
 */
class ReadViewRulesTest {
    private val detail = QSessionDetail(
        id = "s1", sessionDate = "2026-07-01",
        participants = listOf(QParticipant("a1", "Giriraj")),
        recordings = listOf(
            QRecording("r1", subjects = listOf(QSubject("a1", position = 2)), estimated = false),
            QRecording("r2", subjects = listOf(QSubject("a1")), filing = "NEEDS_DECISION")
        )
    )

    @QFeature("B27")
    @Test fun `Approve is held back with its reason until the session can be approved`() {
        assertEquals("Decide on 1 take first.", approveBlocker(detail))
        assertEquals("Add who was there first.", approveBlocker(detail.copy(participants = emptyList())))
        assertNull("a guest is somebody who was there", approveBlocker(detail.copy(participants = emptyList(), respondents = listOf(QRespondent("g1", name = "Ravi")), recordings = detail.recordings.take(1))))
        assertEquals("Nothing recorded or answered yet.", approveBlocker(detail.copy(recordings = emptyList())))
        assertNull(approveBlocker(detail.copy(recordings = emptyList(), answers = listOf(QAnswer(text = "Teak", artisanId = "a1", questionLineageId = "l1")))))
        assertEquals("1 repeat · 1 needs a decision", reviewFlags(detail))
    }

    @QFeature("B21")
    @Test fun `History names who wrote each version and when, and marks the current one`() {
        val line = revisionLine(QRevision("v2", text = "Teak", origin = "TYPED", actor = QUserRef("u3", "Nandini Rao"), createdAt = "2026-07-07T05:12:00Z", current = true))
        assertEquals("Nandini · 7 Jul 10:42 · Typed · Current", line)
        assertEquals("From recording", originLabel("TRANSCRIPT"))
    }

    @QFeature("B40")
    @Test fun `a take's history reads as plain events`() {
        assertEquals("Removed · Asha · 8 Jul 09:30", eventLine(QRecordingEvent("e1", kind = "REMOVED", actor = QUserRef("u1", "Asha Devi"), at = "2026-07-08T04:00:00Z")))
        assertEquals("Moved", eventLine(QRecordingEvent("e2", kind = "MOVED")))
    }

    @QFeature("B34")
    @Test fun `while transcription is paused a take says so, neutrally, and offers no retry`() {
        val line = TranscriptStatus.line(QRecording("r1", transcript = QTranscript(status = "QUEUED", paused = true)), paused = true)!!
        assertEquals("Transcription paused", line.text)
        assertEquals(TranscriptTone.NEUTRAL, line.tone)
        assertFalse(line.retryEnabled)
    }

    @QFeature("C4")
    @Test fun `a refused take is held back from outside services and says so, never as an error`() {
        val line = TranscriptStatus.line(QRecording("r1", consent = QConsentVerdict("REFUSED")), paused = false)!!
        assertEquals("Not sent: consent refused", line.text)
        assertEquals(TranscriptTone.NEUTRAL, line.tone)
        assertEquals("Recordings of Giriraj may be sent to outside services to be written down or translated.",
            com.fieldrepository.app.questionnaire.data.QuestionnaireCopy.consentNotice("Giriraj"))
    }
}
