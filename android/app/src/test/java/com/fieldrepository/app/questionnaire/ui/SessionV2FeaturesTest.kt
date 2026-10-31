package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.FakeSessionTransport
import com.fieldrepository.app.questionnaire.data.InMemorySessionUnitStore
import com.fieldrepository.app.questionnaire.data.ParticipantChange
import com.fieldrepository.app.questionnaire.data.QArtisanRef
import com.fieldrepository.app.questionnaire.data.QConsentBrief
import com.fieldrepository.app.questionnaire.data.QContinueRequest
import com.fieldrepository.app.questionnaire.data.QParticipant
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QSession
import com.fieldrepository.app.questionnaire.data.QSessionCreate
import com.fieldrepository.app.questionnaire.data.QSessionDetail
import com.fieldrepository.app.questionnaire.data.QSubject
import com.fieldrepository.app.questionnaire.data.QuestionnaireExport
import com.fieldrepository.app.questionnaire.data.SessionOutbox
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.questionnaire.data.outboxJson
import com.fieldrepository.app.questionnaire.ui.ParticipantModel
import com.fieldrepository.app.questionnaire.ui.SessionEdit
import com.fieldrepository.app.questionnaire.ui.appendPage
import com.fieldrepository.app.questionnaire.ui.serverRow
import com.fieldrepository.app.questionnaire.ui.sessionsMenu
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The v2 features the backend and the web had and Android lacked (audit §1, 21 Oct 2026): edit,
 * merge and continue a session, remove a participant (P10), the artisan record CSV, sessions-list
 * paging, offline artisan registration; and H15, the builder for questionnaire managers only.
 */
class SessionV2FeaturesTest {
    private val t0 = Instant.parse("2026-07-01T10:00:00Z")

    @QFeature("B12")
    @Test fun `the sessions menu offers Recordings, Join and the workshop coverage page to everyone`() {
        assertEquals(listOf("recordings", "sessions.join", "coverage.title"), sessionsMenu())
    }

    @QFeature("B9")
    @Test fun `Edit session sends only what changed, on top of the version it read`() {
        val before = SessionEdit.of("2026-07-01", "FIRST_SITTING", "Bagru", "hi", null, "Under the neem")
        val patch = before.copy(place = "Bagru, Jaipur", note = "Under the neem").patch(version = 3, before = before)
        assertEquals(3, patch.version)
        assertEquals("Bagru, Jaipur", patch.place)
        assertNull(patch.note); assertNull(patch.sessionDate); assertNull(patch.purpose); assertNull(patch.languageCode)
        val body = QuestionnairePortal.json.parseToJsonElement(QuestionnairePortal.json.encodeToString(com.fieldrepository.app.questionnaire.data.QSessionPatch.serializer(), patch)).toString()
        assertFalse("never a title", body.contains("title"))
    }

    @Test fun `Edit session before it reaches the server rewrites the queued create`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        outbox.startSession(QSessionCreate(clientKey = "c", questionnaireId = "q", sessionDate = "2026-07-01", startedAt = t0.toString(), participants = listOf(QArtisanRef("a1"), QArtisanRef("a2"))), "u1")
        val edit = SessionEdit.of("2026-07-02", "FOLLOW_UP", "Kolkata", "bn", null, "Second visit")
        assertTrue(outbox.editPendingSession("c", edit::applyTo))
        val frozen = outboxJson.decodeFromString(QSessionCreate.serializer(), outbox.unit("c")!!.steps.single { it.kind == StepKind.SESSION }.bodyJson!!)
        assertEquals("2026-07-02", frozen.sessionDate)
        assertEquals("FOLLOW_UP", frozen.purpose)
        assertEquals("Second visit", frozen.note)

        // P10 before sync: the artisan leaves the queued create, and their queued join goes too.
        outbox.addParticipantChange("c", ParticipantChange("a2", ParticipantChange.JOIN, t0.toString()))
        assertTrue(outbox.removePendingParticipant("c", "a2"))
        val after = outbox.unit("c")!!
        assertEquals(listOf("a1"), outboxJson.decodeFromString(QSessionCreate.serializer(), after.steps.single { it.kind == StepKind.SESSION }.bodyJson!!).participants.map { it.artisanId })
        assertTrue(after.steps.none { it.kind == StepKind.PARTICIPANT })

        // Once the session has landed, only the server can change it.
        outbox.sync(FakeSessionTransport(), "u1")
        assertFalse(outbox.removePendingParticipant("c", "a1"))
        assertFalse(outbox.editPendingSession("c", edit::applyTo))
    }

    @QFeature("B5")
    @Test fun `P10 counts the artisan's live takes, and Change consent asks the one who refused`() {
        val detail = QSessionDetail(
            id = "s1",
            participants = listOf(QParticipant("a1", consent = QConsentBrief("GRANTED")), QParticipant("a2", consent = QConsentBrief("REFUSED"))),
            recordings = listOf(
                QRecording("r1", subjects = listOf(QSubject("a1"), QSubject("a2"))),
                QRecording("r2", subjects = listOf(QSubject("a2"))),
                QRecording("r3", subjects = listOf(QSubject("a2")), removedAt = "2026-07-02T00:00:00Z")
            )
        )
        assertEquals(1, ParticipantModel.takesOf(detail, "a1"))
        assertEquals(2, ParticipantModel.takesOf(detail, "a2"))
        assertEquals("a2", ParticipantModel.refusingSubject(detail.recordings[0], detail))
    }

    @QFeature("B8")
    @Test fun `Continue another day with one artisan is always one session`() {
        assertEquals("ONE", QContinueRequest.of("s1", listOf("a1"), "EACH", "2026-07-02").split)
        assertEquals("EACH", QContinueRequest.of("s1", listOf("a1", "a2"), "EACH", "2026-07-02").split)
    }

    @QFeature("B28")
    @Test fun `the next page joins the list without repeating a session`() {
        val first = listOf(serverRow(QSession(id = "s1", clientKey = "c1")))
        val rows = appendPage(first, listOf(QSession(id = "s1", clientKey = "c1"), QSession(id = "s2")))
        assertEquals(listOf("c1", "s2"), rows.map { it.key })
    }

    @QFeature("B26")
    @Test fun `the CSV keeps the name the server gave it`() {
        assertEquals("FieldRepository_Artisan_Record.csv", QuestionnaireExport.dispositionFileName("""attachment; filename="FieldRepository_Artisan_Record.csv""""))
        assertEquals("Giriraj_Record.csv", QuestionnaireExport.dispositionFileName("attachment; filename=\"x.csv\"; filename*=UTF-8''Giriraj_Record.csv"))
        assertEquals("evil.csv", QuestionnaireExport.dispositionFileName("""attachment; filename="../../evil.csv""""))
        assertNull(QuestionnaireExport.dispositionFileName(null))
    }

    @Test fun `the CSV route is the server's`() {
        val method = com.fieldrepository.app.questionnaire.data.QuestionnaireApi::class.java.methods.single { it.name == "artisanRecordCsv" }
        assertEquals("artisans/{id}/record.csv", method.getAnnotation(retrofit2.http.GET::class.java)!!.value)
    }
}
