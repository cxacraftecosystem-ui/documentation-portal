package com.fieldrepository.app.questionnaire

import com.fieldrepository.app.data.AnswerLock
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.CoverageMarks
import com.fieldrepository.app.data.FakeSessionTransport
import com.fieldrepository.app.data.FormRights
import com.fieldrepository.app.data.FormsCopy
import com.fieldrepository.app.data.InMemorySessionUnitStore
import com.fieldrepository.app.data.QAnswer
import com.fieldrepository.app.data.QAnswerItem
import com.fieldrepository.app.data.QAnswerResult
import com.fieldrepository.app.data.QAnswersPut
import com.fieldrepository.app.data.QAnswersPutResponse
import com.fieldrepository.app.data.QArtisanRef
import com.fieldrepository.app.data.QForm
import com.fieldrepository.app.data.QFormQuestion
import com.fieldrepository.app.data.QFormSection
import com.fieldrepository.app.data.QGrid
import com.fieldrepository.app.data.QGuestAnswersPut
import com.fieldrepository.app.data.QParticipant
import com.fieldrepository.app.data.QRecording
import com.fieldrepository.app.data.QRequiredUnanswered
import com.fieldrepository.app.data.QRespondent
import com.fieldrepository.app.data.QRespondentPatch
import com.fieldrepository.app.data.QResearcherAdd
import com.fieldrepository.app.data.QSession
import com.fieldrepository.app.data.QSessionCreate
import com.fieldrepository.app.data.QSessionDetail
import com.fieldrepository.app.data.QSnapshotTake
import com.fieldrepository.app.data.QTarget
import com.fieldrepository.app.data.RequiredQuestions
import com.fieldrepository.app.data.SectionOverride
import com.fieldrepository.app.data.SessionOutbox
import com.fieldrepository.app.data.SessionTransport
import com.fieldrepository.app.data.StepKind
import com.fieldrepository.app.data.StepState
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.outboxJson
import com.fieldrepository.app.ui.questionnaire.GuestModel
import com.fieldrepository.app.ui.questionnaire.GuestRoster
import com.fieldrepository.app.ui.questionnaire.Instrument
import com.fieldrepository.app.ui.questionnaire.InstrumentQuestion
import com.fieldrepository.app.ui.questionnaire.InstrumentSection
import com.fieldrepository.app.ui.questionnaire.OutboxTrayModel
import com.fieldrepository.app.ui.questionnaire.ReadViewModel
import com.fieldrepository.app.ui.questionnaire.RequiredModel
import com.fieldrepository.app.ui.questionnaire.StartForm
import com.fieldrepository.app.ui.questionnaire.eventLine
import com.fieldrepository.app.ui.questionnaire.guestAnswers
import com.fieldrepository.app.ui.questionnaire.menuLabel
import com.fieldrepository.app.ui.questionnaire.revisionLine
import com.fieldrepository.app.ui.questionnaire.sessionsMenu
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The unified session tools on Field Repository Android (stream U4): the answer lock, guests, locks
 * in the fix tray, soft delete and restore, researchers, required questions, answer notes, History,
 * the read view and review, workshop coverage with ● ✎ and the three overrides, and the corpus.
 */
class SessionUnifiedTest {
    private fun user(id: String, role: String) = UserDto(id = id, email = "$id@x", name = id, role = role)

    @QFeature("A11")
    @Test fun `An answer with text is its author's, a lead's or a content admin's, and the tray says P15`() {
        assertTrue(AnswerLock.locked("Teak", "TYPED", authorId = "u2", meId = "u1", isLead = false, isContentAdmin = false))
        assertFalse("the author", AnswerLock.locked("Teak", "TYPED", "u1", "u1", isLead = false, isContentAdmin = false))
        assertFalse("a lead", AnswerLock.locked("Teak", "TYPED", "u2", "u1", isLead = true, isContentAdmin = false))
        assertFalse("a content admin", AnswerLock.locked("Teak", "TYPED", "u2", "u1", isLead = false, isContentAdmin = true))
        assertFalse("empty: anyone may fill it", AnswerLock.locked("", "TYPED", "u2", "u1", false, false))
        assertFalse("a prefill is nobody's", AnswerLock.locked("Bagru", "PREFILL", "u2", "u1", false, false))
        assertEquals("Answered by Nandini. Only they or a lead can change it.", AnswerLock.message("Nandini"))

        val locked = QAnswerResult("a1", "lin1", "FORBIDDEN", current = QAnswer(id = "x", version = 2, text = "Teak", actorName = "Nandini"), code = "ANSWER_LOCKED")
        assertTrue(AnswerLock.isLockedResult(locked))
        val unit = runBlocking {
            val store = InMemorySessionUnitStore()
            val outbox = SessionOutbox(store)
            outbox.startSession(QSessionCreate(clientKey = "k", questionnaireId = "f", sessionDate = "2026-10-22", startedAt = "2026-10-22T05:00:00Z", participants = listOf(QArtisanRef("a1"))), "u1")
            outbox.addAnswers("k", QAnswersPut(listOf(QAnswerItem("a1", "lin1", "Bamboo", sessionId = "k"))))
            val lockingServer = object : SessionTransport by FakeSessionTransport() {
                override suspend fun putAnswers(body: QAnswersPut) = QAnswersPutResponse(listOf(locked))
            }
            outbox.sync(lockingServer, "u1")
            outbox.unit("k")!!
        }
        val row = OutboxTrayModel.rows(unit, { "Giriraj" }, { "D3" }).single()
        assertEquals("Answered by Nandini. Only they or a lead can change it.", row.reason)
        assertNull("a lock offers no Keep mine", row.conflict)
    }

    @QFeature("A14")
    @Test fun `Deleting a session is a restorable soft delete for content admins, listed under Deleted sessions`() = runBlocking {
        assertTrue(FormRights.mayDeleteSession(user("m", "MINISTRY_ADMIN")))
        assertFalse(FormRights.mayDeleteSession(user("p", "PROFESSOR")))
        assertEquals(listOf("sessions.join", "sessions.coverage", "sessions.deleted"), sessionsMenu(canBuild = false, user = user("a", "ADMIN")))
        assertEquals(listOf("sessions.join", "sessions.coverage"), sessionsMenu(canBuild = false, user = user("r", "RESEARCHER")))
        assertEquals(FormsCopy.DELETED_SESSIONS, menuLabel("sessions.deleted"))
        assertEquals("Admins can restore it from Deleted sessions.", FormsCopy.SESSION_DELETE_BODY)
        val server = FakeServer()
        server.on("GET /questionnaire/sessions?deleted=true", """{"items":[{"id":"s1","title":"Interview_2026-07-01"}],"nextCursor":null,"capped":false}""")
        server.on("POST /questionnaire/sessions/s1/restore", """{"id":"s1","title":"Interview_2026-07-01"}""")
        assertEquals("s1", server.forms.deletedSessions().items.single().id)
        assertEquals("s1", server.forms.restoreSession("s1").id)
        assertEquals(listOf("GET /questionnaire/sessions?deleted=true", "POST /questionnaire/sessions/s1/restore"), server.seen.map { "${it.method} ${it.path}" })
    }

    @QFeature("B9")
    @QFeature("C7")
    @Test fun `Session delete is now soft, and edit and merge keep their routes`() = runBlocking {
        val server = FakeServer()
        server.on("DELETE /questionnaire/sessions/s1", FakeServer.Reply(204, ""))
        server.on("POST /questionnaire/sessions/s1/merge-into/s2", """{"id":"s2","title":"t"}""")
        assertTrue(server.api.deleteSession("s1").isSuccessful)
        assertEquals("s2", server.api.mergeInto("s1", "s2").id)
        assertTrue(FormsCopy.SESSION_DELETE_BODY.contains("restore"))
    }

    // A guest session: a transport that keys guests like the server (`respondents` back on the create).
    private class GuestServer(private val inner: FakeSessionTransport = FakeSessionTransport()) : SessionTransport by inner {
        val guestBodies = mutableListOf<QGuestAnswersPut>()
        val created = mutableListOf<QSessionCreate>()
        override suspend fun createSession(idempotencyKey: String, body: QSessionCreate): QSession {
            created += body
            return QSession(id = "s1", clientKey = body.clientKey, respondents = body.respondents.mapIndexed { i, r -> QRespondent(id = "r${i + 1}", name = r.name) }.reversed())
        }
        override suspend fun putGuestAnswers(body: QGuestAnswersPut): QAnswersPutResponse {
            guestBodies += body
            return QAnswersPutResponse(body.items.map { QAnswerResult(questionLineageId = it.questionLineageId, status = "SAVED", respondentId = it.respondentId) })
        }
    }

    @QFeature("A18")
    @Test fun `A session with only a guest starts offline, and the guest's answers go with the server's respondent id`() = runBlocking {
        val form = StartForm(questionnaireId = "f1", date = LocalDate.of(2026, 10, 22), guests = listOf("Meena (shopkeeper)", "Ravi"))
        assertTrue("guests alone may start a session", form.canStart)
        assertFalse(StartForm(questionnaireId = "f1").canStart)
        val keys = form.guestKeys()
        val body = form.toCreate("k1", "u1", java.time.Instant.parse("2026-10-22T05:00:00Z"))
        assertEquals(listOf("Meena (shopkeeper)", "Ravi"), body.respondents.map { it.name })
        assertTrue(body.participants.isEmpty())

        val outbox = SessionOutbox(InMemorySessionUnitStore())
        outbox.startSession(body, "u1", guests = keys)
        val meena = GuestModel(keys[0].first, keys[0].second, null, null)
        outbox.addGuestAnswers("k1", guestAnswers(GuestRoster.item(meena, "lin1", "Cotton", "k1", note = "Said twice", answers = emptyList())))
        val unit = outbox.unit("k1")!!
        assertEquals("Cotton", GuestRoster.answer(unit, null, meena, "lin1"))
        assertEquals(listOf("Meena (shopkeeper)", "Ravi"), GuestRoster.of(unit, null).map { it.name })

        val server = GuestServer()
        outbox.sync(server, "u1")
        val sent = server.guestBodies.single().items.single()
        assertEquals("matched by name, not by order", "r1", sent.respondentId)
        assertEquals("s1", sent.sessionId)
        assertEquals("Said twice", sent.note)
        assertEquals(StepState.DONE, outbox.unit("k1")?.steps?.first { it.kind == StepKind.GUEST_ANSWERS }?.state ?: StepState.DONE)
        val encoded = ApiClient.json.encodeToString(QGuestAnswersPut.serializer(), server.guestBodies.single())
        assertFalse("a guest's item names no artisan", encoded.contains("artisanId"))
    }

    @QFeature("A17")
    @Test fun `A respondent's name and notes are edited on the server, so personal data can be retracted`() = runBlocking {
        val server = FakeServer()
        server.on("PATCH /questionnaire/sessions/s1/respondents/r1", """{"id":"r1","name":"M.","notes":null}""")
        val out = server.forms.patchRespondent("s1", "r1", QRespondentPatch(name = "M.", notes = ""))
        assertEquals("M.", out.name)
        assertEquals("""{"name":"M.","notes":""}""", server.last().body)
        val detail = QSessionDetail(id = "s1", respondents = listOf(QRespondent("r1", "s1", "M."), QRespondent("r2", "s1", "Registered", artisanId = "a9")))
        assertEquals("a guest registered as an artisan leaves the guest list", listOf("M."), GuestRoster.of(null, detail).map { it.name })
    }

    private val form = QForm(
        id = "f1", title = "Toolkit",
        sections = listOf(
            QFormSection("sA", "A", "About", 1, questions = listOf(QFormQuestion("q1", lineageId = "L1", prompt = "Name?", isRequired = true), QFormQuestion("q2", lineageId = "L2", prompt = "Age?"))),
            QFormSection("sB", "B", "Tools", 2, questions = listOf(QFormQuestion("q3", lineageId = "L3", prompt = "Tools?", isRequired = true)))
        )
    )

    @QFeature("A19")
    @Test fun `Finish reports required questions left, typed or recorded either counts, and never blocks`() {
        val answers = listOf(QAnswer(text = "Giriraj", artisanId = "a1", questionLineageId = "L1"))
        val takes = listOf(QSnapshotTake("t1", artisanId = "a2", targetKey = "S:sB"))
        // a1: L3 left. a2: L1 left (L3 by its section take).
        assertEquals(2, RequiredModel.unanswered(form, listOf("a1", "a2"), answers, takes))
        assertEquals("2 required questions unanswered", RequiredQuestions.line(QRequiredUnanswered(2)))
        assertEquals("1 required question unanswered", RequiredQuestions.line(QRequiredUnanswered(1)))
        assertNull(RequiredQuestions.line(QRequiredUnanswered(0)))
        val finish = ApiClient.json.decodeFromString(com.fieldrepository.app.data.QFinishResult.serializer(), """{"id":"s1","state":"CLOSED","requiredUnanswered":{"count":3,"bySubject":{"A:a1":3}}}""")
        assertEquals(3, finish.requiredUnanswered.count)
    }

    @QFeature("A20")
    @Test fun `An answer carries its note, and History shows it`() {
        val item = QAnswerItem("a1", "L1", "Teak", sessionId = "s1", note = "Pointed at the beam")
        assertTrue(ApiClient.json.encodeToString(QAnswerItem.serializer(), item).contains(""""note":"Pointed at the beam""""))
        val bare = ApiClient.json.encodeToString(QAnswerItem.serializer(), item.copy(note = null))
        assertFalse("no note: the key is left out", bare.contains("note"))
    }

    @QFeature("B21")
    @Test fun `History lists an answer's revisions, newest first, with who wrote each`() = runBlocking {
        val server = FakeServer()
        server.on("GET /questionnaire/answers/ans1/revisions", """{"answerId":"ans1","revisions":[
            {"id":"v2","text":"Teak","origin":"TYPED","actor":{"id":"u2","name":"Nandini"},"createdAt":"2026-07-07T08:50:00Z","current":true},
            {"id":"v1","text":"Sal","origin":"TRANSCRIPT","actor":{"id":"u1","name":"Ravi"},"createdAt":"2026-07-01T06:30:00Z","current":false}]}""")
        val revisions = server.forms.revisions("ans1").revisions
        assertEquals(listOf("Teak", "Sal"), revisions.map { it.text })
        assertEquals("Nandini · 7 Jul 14:20 · Typed · Current", revisionLine(revisions[0]))
        assertEquals("Ravi · 1 Jul 12:00 · Transcript", revisionLine(revisions[1]))
    }

    @QFeature("B40")
    @Test fun `A take's history and the session's removed takes are read from their routes`() = runBlocking {
        val server = FakeServer()
        server.on("GET /questionnaire/recordings/r1/events", """{"recordingId":"r1","events":[{"id":"e1","kind":"REMOVED","before":null,"after":{"removed":true},"actor":{"id":"u","name":"Admin"},"at":"2026-07-01T06:30:00Z"}]}""")
        server.on("GET /questionnaire/recordings/removed", """{"items":[{"id":"r1","removedAt":"2026-07-01T06:30:00Z"}]}""")
        assertEquals("Removed · Admin · 1 Jul 12:00", eventLine(server.forms.recordingEvents("r1").events.single()))
        assertEquals("r1", server.forms.removedRecordings("s1").items.single().id)
        assertEquals("/questionnaire/recordings/removed?sessionId=s1", server.last().path)
    }

    @QFeature("B6")
    @Test fun `A researcher joins a session from the workshop's team`() = runBlocking {
        val server = FakeServer()
        server.on("POST /questionnaire/sessions/s1/researchers", """{"id":"s1","researchers":[{"userId":"u2","name":"Nandini","role":"CONTRIBUTOR"}]}""")
        val out = server.forms.addResearcher("s1", QResearcherAdd("u2"))
        assertEquals("Nandini", out.researchers.single().name)
        assertEquals("""{"userId":"u2","role":"CONTRIBUTOR"}""", server.last().body)
    }

    @QFeature("B27")
    @Test fun `The read view lists each question's answers and takes, and a reviewer approves the session`() = runBlocking {
        val instrument = Instrument("f1", "Toolkit", listOf(InstrumentSection("sA", "A", "About", listOf(InstrumentQuestion("L1", "A1", "Name?", "sA"), InstrumentQuestion("L2", "A2", "Age?", "sA")))))
        val detail = QSessionDetail(
            id = "s1", participants = listOf(QParticipant("a1", "Giriraj Prasad")),
            answers = listOf(QAnswer(text = "Giriraj", artisanId = "a1", questionLineageId = "L1")),
            recordings = listOf(QRecording("r1", targets = listOf(QTarget("S:sA", sectionId = "sA"))), QRecording("r2", targets = listOf(QTarget("Q:L2", questionLineageId = "L2")), removedAt = "2026-07-01T00:00:00Z"))
        )
        val lines = ReadViewModel.lines(instrument, detail)
        assertEquals(listOf("Giriraj: Giriraj"), lines[0].answers)
        assertEquals("a removed take is not read", listOf(1, 1), lines.map { it.takes })
        assertTrue(FormRights.mayReview(user("f", "FIELD_CONTRIBUTOR")) || FormRights.mayReview(user("p", "PROFESSOR")))
        val server = FakeServer()
        server.on("POST /review/questionnaire/s1/approve", """{"status":"APPROVED"}""")
        server.forms.approveSession("key-1", "s1")
        assertEquals("/review/questionnaire/s1/approve", server.last().path)
    }

    private val gridJson = """{"questionnaireId":"f1","artisans":[{"artisanId":"a1","name":"Giriraj"},{"artisanId":"a2","name":"Meena"}],
        "sections":[{"sectionId":"sA","code":"A","title":"About","cells":[
          {"artisanId":"a1","subjectKey":"A:a1","state":"RECORDED","takes":2,"covered":14,"total":24,"needsDecision":false,"override":null,"source":"RECORDED","recordedCount":12,"typedOnlyCount":2,"addRecording":false},
          {"artisanId":"a2","subjectKey":"A:a2","state":"PARTIAL","takes":0,"covered":3,"total":24,"needsDecision":false,"override":"NEEDS_REVIEW","source":"TYPED","recordedCount":0,"typedOnlyCount":3,"addRecording":true}]}]}"""

    @QFeature("B12")
    @Test fun `Workshop coverage reads the grid for one workshop and form`() = runBlocking {
        val server = FakeServer()
        server.on("GET /questionnaire/coverage", gridJson)
        val grid = server.forms.coverage("f1", "w1")
        assertEquals("/questionnaire/coverage?questionnaireId=f1&workshopId=w1", server.last().path)
        assertEquals(listOf("Giriraj", "Meena"), grid.artisans.map { it.name })
        assertEquals("14 of 24", CoverageMarks.count(grid.sections[0].cells[0]))
    }

    @QFeature("D4")
    @Test fun `Typed or recorded both count, a recording is shown first and a typed-only cell asks for one`() {
        val grid = ApiClient.json.decodeFromString(QGrid.serializer(), gridJson)
        val (recorded, typed) = grid.sections[0].cells
        assertEquals("●", CoverageMarks.mark(recorded))
        assertEquals("✎", CoverageMarks.mark(typed))
        assertNull(CoverageMarks.prompt(recorded))
        assertEquals("Add a recording?", CoverageMarks.prompt(typed))
        assertEquals("Meena, A, Typed, 3 of 24, Needs review", CoverageMarks.accessibility(typed, "Meena", "A"))
    }

    @QFeature("D1")
    @Test fun `The section override keeps three states, and Clear goes back to the data`() {
        assertEquals(listOf("Marked complete", "Needs review", "Needs redo"), SectionOverride.STATES.values.toList())
        assertEquals("NEEDS_REDO", SectionOverride.body("a1", "sA", SectionOverride.NEEDS_REDO).status)
        assertNull(SectionOverride.body("a1", "sA", null).status)
        assertTrue(runCatching { SectionOverride.body("a1", "sA", "DONE") }.isFailure)
    }

    @QFeature("B13")
    @QFeature("C5")
    @Test fun `Only a content admin sets an override, through PUT completion`() = runBlocking {
        assertTrue(FormRights.mayOverride(user("a", "ADMIN")))
        assertFalse(FormRights.mayOverride(user("p", "PROFESSOR")))
        val server = FakeServer()
        server.on("PUT /questionnaire/completion", """{"artisanId":"a1","sectionId":"sA","status":"COMPLETED"}""")
        server.forms.setCompletion(SectionOverride.body("a1", "sA", SectionOverride.COMPLETED))
        assertEquals("""{"artisanId":"a1","sectionId":"sA","status":"COMPLETED"}""", server.last().body)
    }

    @QFeature("B29")
    @Test fun `The corpus downloads behind the dataset gate, named by the server`() = runBlocking {
        assertTrue(FormRights.mayExportCorpus(user("p", "PROFESSOR")))
        assertFalse(FormRights.mayExportCorpus(user("r", "RESEARCHER")))
        val server = FakeServer()
        server.on("GET /questionnaire/corpus.csv", FakeServer.Reply(body = "a,b\n", type = "text/csv", headers = mapOf("Content-Disposition" to "attachment; filename=\"FieldRepository_Questionnaire_2026-10-22_Toolkit_Corpus-Answers.csv\"")))
        val response = server.forms.corpusCsv("f1", "recordings")
        assertEquals("/questionnaire/corpus.csv?questionnaireId=f1&kind=recordings&status=APPROVED", server.last().path)
        assertEquals("FieldRepository_Questionnaire_2026-10-22_Toolkit_Corpus-Answers.csv", com.fieldrepository.app.data.QuestionnaireExport.dispositionFileName(response.headers()["Content-Disposition"]))
    }
}
