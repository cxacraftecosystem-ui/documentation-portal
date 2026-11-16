package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.ClipPayload
import com.fieldrepository.app.questionnaire.data.QCompleteRecording
import com.fieldrepository.app.questionnaire.data.QCompleteSubject
import com.fieldrepository.app.questionnaire.data.QPartDone
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QSessionCreate
import com.fieldrepository.app.questionnaire.data.QShaMatch
import com.fieldrepository.app.questionnaire.data.QTarget
import com.fieldrepository.app.questionnaire.data.QTargetRef
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.SessionStep
import com.fieldrepository.app.questionnaire.data.SessionUnit
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.questionnaire.data.StepState
import com.fieldrepository.app.questionnaire.data.outboxJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class UploadFeedbackModelsTest {
    private val today = LocalDate.parse("2026-11-16")

    private fun clip(key: String, targets: List<QTargetRef>, sha: String = "sha-$key", ms: Long = 2_530_000, parts: Int = 0, path: String = "/clips/$key.m4a") = ClipPayload(
        clientClipKey = key, localPath = path, sizeBytes = 1000, contentSha256 = sha, label = "Whole session (16 Nov)",
        recording = QCompleteRecording(targets = targets, subjects = listOf(QCompleteSubject("a1")), deviceCapturedAt = "2026-11-16T05:30:00Z", durationMs = ms),
        partSize = 250, partsDone = (1..parts).map { QPartDone(it, "e$it") }
    )

    private fun step(id: String, clip: ClipPayload, state: String = StepState.PENDING, resultId: String? = null) =
        SessionStep(id, StepKind.CLIP, "2026-11-16T05:30:00Z", "i-$id", clip = clip, state = state, resultId = resultId)

    private fun unit(vararg steps: SessionStep, key: String = "c-1", sessionId: String? = "s-1", date: String = "2026-11-13") = SessionUnit(
        key, createdAt = "2026-11-16T05:00:00Z", questionnaireId = "f", sessionId = sessionId,
        steps = listOf(
            SessionStep("session-$key", StepKind.SESSION, "2026-11-16T05:00:00Z", "s-$key", state = StepState.DONE,
                bodyJson = outboxJson.encodeToString(QSessionCreate.serializer(), QSessionCreate(clientKey = key, questionnaireId = "f", sessionDate = date, startedAt = "2026-11-16T05:00:00Z")))
        ) + steps
    )

    private fun rec(id: String, targets: List<String>, at: String, ms: Long, clip: String? = null, part: String? = null) =
        QRecording(id = id, mediaFileId = "m-$id", clientClipKey = clip, capturedAt = at, durationMs = ms, targets = targets.map { QTarget(targetKey = it) }, continuationOfId = part)

    private val anyFile: (String) -> Boolean = { true }

    @QFeature("Q3")
    @Test fun `whole-session takes list under their own block, in order, with parts, uploaded and still on this phone`() {
        val local = LocalTakes.of(unit(step("st1", clip("c9", emptyList()))), online = true, isFile = anyFile)
        val takes = InPlaceTakes.whole(
            listOf(
                rec("r2", emptyList(), "2026-11-13T06:10:00Z", 291_000, part = "r1"),
                rec("r1", emptyList(), "2026-11-13T05:12:00Z", 846_000),
                rec("r3", listOf("S:secE"), "2026-11-13T05:12:00Z", 60_000)
            ),
            local
        )
        assertEquals(listOf("Take 1 · 14:06", "Part 2 · 4:51", "Take 3 · 42:10"), takes.map { it.title })
        assertEquals(listOf("13 Nov · 10:42", "13 Nov · 11:40", "Uploading 0%"), takes.map { it.subtitle })
        assertEquals(listOf(false, false, true), takes.map { it.local })
        assertEquals(InPlaceTakes.WHOLE, InPlaceTakes.scopeOf(rec("r1", emptyList(), "2026-11-13T05:12:00Z", 1)))
        assertEquals("S:secE", InPlaceTakes.scopeOf(rec("r3", listOf("S:secE"), "2026-11-13T05:12:00Z", 1)))
        assertTrue(InPlaceTakes.section("secE", emptyList(), local).isEmpty())
    }

    @QFeature("Q3")
    @Test fun `a take on this phone shows its upload state, a failed one offers Retry, a landed one waits for its take`() {
        val u = unit(
            step("st1", clip("c1", listOf(QTargetRef(sectionId = "secE")), parts = 2)),
            step("st2", clip("c2", listOf(QTargetRef(sectionId = "secE"))), state = StepState.FAILED),
            step("st3", clip("c3", listOf(QTargetRef(sectionId = "secE"))), state = StepState.DONE, resultId = "r3"),
            step("st4", clip("c4", listOf(QTargetRef(sectionId = "secE"))), state = StepState.DONE, resultId = "r4")
        )
        val offline = LocalTakes.of(u, tracked = setOf("c3"), online = false, isFile = anyFile)
        assertEquals(listOf("c1", "c2", "c3"), offline.map { it.take.clientClipKey })
        assertEquals(listOf("Uploading 50%", "Upload failed", "Uploaded"), offline.map { it.line })
        val takes = InPlaceTakes.section("secE", emptyList(), offline)
        assertEquals(listOf(null, "st2", null), takes.map { it.retryStep })
        assertEquals(listOf(true, true, false), takes.map { it.openable })
        assertEquals("Waiting for signal", LocalTakes.of(unit(step("st5", clip("c5", emptyList()))), online = false, isFile = anyFile).single().line)
        val landedOnServer = InPlaceTakes.section("secE", listOf(rec("r3", listOf("S:secE"), "2026-11-16T05:31:00Z", 60_000, clip = "c3")), offline)
        assertEquals(listOf("local:c1", "local:c2", "r3"), landedOnServer.map { it.key })
        assertTrue(LocalTakes.of(unit(step("st6", clip("c6", emptyList()))), online = true, isFile = { false }).none { it.path != null })
        assertTrue(InPlaceTakes.whole(emptyList(), LocalTakes.of(unit(step("st6", clip("c6", emptyList()))), online = true, isFile = { false })).isEmpty())
    }

    @QFeature("Q4")
    @Test fun `picked files show where they were attached at once and give way to the queued take`() {
        val picked = PickedFile("/clips/x.m4a", "shaX", 10, "audio/mp4", "m4a", Instant.parse("2026-11-13T05:12:00Z"), 120_000, name = "Sandesh.m4a")
        val arriving = LocalTakes.arriving(listOf("cX" to picked), "S:secE", online = true)
        assertEquals("Uploading 0%", arriving.single().line)
        assertEquals("S:secE", arriving.single().take.targetKey)
        val shown = InPlaceTakes.section("secE", emptyList(), LocalTakes.merge(emptyList(), arriving))
        assertEquals(listOf("local:cX"), shown.map { it.key })
        assertEquals("Section take · 2:00", shown.single().title)
        val queued = LocalTakes.of(unit(step("st1", clip("cX", listOf(QTargetRef(sectionId = "secE")), parts = 1))), online = true, isFile = anyFile)
        val merged = LocalTakes.merge(queued, arriving)
        assertEquals(1, merged.size)
        assertEquals("Uploading 25%", merged.single().line)
        assertEquals("Waiting for signal", LocalTakes.arriving(listOf("cY" to picked), InPlaceTakes.WHOLE, online = false).single().line)
    }

    @QFeature("Q4")
    @Test fun `a batch is confirmed once every file has landed, and a failure leaves the row to say so`() {
        val e = UploadBatch(listOf("c1", "c2"), "Section E")
        val whole = UploadBatch(listOf("c3"), null)
        val failing = UploadBatch(listOf("c4"), "E2")
        val waiting = UploadBatch(listOf("c5"), "Section E")
        val u = unit(
            step("st1", clip("c1", listOf(QTargetRef(sectionId = "secE"))), state = StepState.DONE),
            step("st3", clip("c3", emptyList())),
            step("st4", clip("c4", listOf(QTargetRef(questionLineageId = "lE2"))), state = StepState.FAILED),
            step("st5", clip("c5", listOf(QTargetRef(sectionId = "secE"))))
        )
        val recordings = listOf(rec("r2", listOf("S:secE"), "2026-11-16T05:30:00Z", 1, clip = "c2"))
        val (finished, still) = UploadBatches.split(listOf(e, whole, failing, waiting), u, recordings)
        assertEquals(listOf(e), finished)
        assertEquals(listOf(whole, waiting), still)
        assertEquals("2 recordings uploaded to Section E", e.notice)
        assertEquals("1 recording uploaded to the whole session", whole.notice)
        assertEquals("1 recording uploaded to E2", failing.notice)
        assertEquals(InPlaceTakes.WHOLE, UploadBatches.scopeOf(null))
        assertEquals("Q:lE2", UploadBatches.scopeOf(QTargetRef(questionLineageId = "lE2")))
        val after = unit(step("st3", clip("c3", emptyList()), state = StepState.DONE), step("st5", clip("c5", listOf(QTargetRef(sectionId = "secE")))))
        assertEquals(setOf("c3"), UploadBatches.landed(u, after))
        assertTrue(UploadBatches.landed(after, after).isEmpty())
    }

    @QFeature("Q4")
    @Test fun `a duplicate says what is true and where it is`() {
        val listed = listOf(UploadRow(PickedFile("/a.m4a", "shaA", 10, "audio/mp4", "m4a", null, 1_000)))
        val queued = unit(step("st1", clip("c1", emptyList(), sha = "shaQ")), key = "c-other", sessionId = "s-other")
        val sent = unit(step("st2", clip("c2", emptyList(), sha = "shaD"), state = StepState.DONE, resultId = "r-d"), key = "c-old", sessionId = "s-old", date = "2026-11-13")
        fun dup(sha: String, match: QShaMatch? = null, failed: Boolean = false) =
            UploadPlan.duplicate(sha, listed, listOf(queued, sent), "c-1", "s-1", match, lookupFailed = failed, today = today)
        assertEquals(UploadDuplicate.InThisList, dup("shaA"))
        assertEquals(UploadDuplicate.OnThisPhone, dup("shaQ"))
        val here = dup("shaS", QShaMatch("shaS", recordingId = "r-9", sessionId = "s-1", sameSession = true, uploadedAt = "2026-11-13T04:00:00Z")) as UploadDuplicate.OnServer
        assertEquals(LocalDate.parse("2026-11-13"), here.on)
        assertTrue(here.sameSession && here.openable)
        val there = dup("shaS", QShaMatch("shaS", recordingId = "r-8", sessionId = "s-2", uploadedAt = "2026-11-13T04:00:00Z", sessionTitle = "Sandesh P · Bagru")) as UploadDuplicate.OnServer
        assertFalse(there.sameSession)
        assertEquals("s-2", there.sessionId)
        assertNull(dup("shaS", QShaMatch("shaS", sessionId = "s-2", pending = true)))
        assertNull(dup("shaD"))
        val offline = dup("shaD", failed = true) as UploadDuplicate.OnServer
        assertEquals(LocalDate.parse("2026-11-13"), offline.on)
        assertEquals("s-old", offline.sessionId)
        assertEquals("r-d", offline.recordingId)
        assertFalse(offline.sameSession)
        assertNull(dup("shaNew", failed = true))
        assertEquals("Already in this list", UploadPlan.duplicateLine(UploadDuplicate.InThisList))
        assertEquals("Uploading from this phone", UploadPlan.duplicateLine(UploadDuplicate.OnThisPhone))
        assertEquals("Already uploaded on 13 Nov", UploadPlan.duplicateLine(here))
        assertEquals("In this session", UploadPlan.whereLine(here))
        assertEquals("In Sandesh P · Bagru", UploadPlan.whereLine(there))
        assertNull(UploadPlan.whereLine(there.copy(sessionTitle = null)))
        assertNull(UploadPlan.whereLine(UploadDuplicate.OnThisPhone))
        assertFalse(UploadDuplicate.OnServer(today, sessionId = null, sameSession = false).openable)
        assertTrue(UploadRow(listed[0].file, UploadDuplicate.InThisList).skipped)
    }

    @QFeature("B38")
    @Test fun `leaving asks while takes upload or failed, and says what happens to each`() {
        val u = unit(
            step("st1", clip("c1", emptyList())),
            step("st2", clip("c2", emptyList())),
            step("st3", clip("c3", emptyList()), state = StepState.FAILED),
            step("st4", clip("c4", emptyList()), state = StepState.DONE)
        )
        assertEquals(LeaveCounts(2, 1), LeaveCounts.of(u, recording = false))
        assertEquals(LeaveCounts(3, 1), LeaveCounts.of(u, recording = true))
        assertFalse(LeaveCounts.of(unit(), recording = false).asks)
        assertTrue(LeaveCounts.of(unit(), recording = true).asks)
        assertEquals(
            "Uploading 2 takes. They carry on in the background after you leave, and wait for signal if there is none.\n1 take didn't upload. They wait here for Retry.",
            QuestionnaireCopy.leaveBody(2, 1)
        )
        assertEquals("Uploading 1 take. They carry on in the background after you leave, and wait for signal if there is none.", QuestionnaireCopy.leaveBody(1, 0))
        assertEquals("2 takes didn't upload. They wait here for Retry.", QuestionnaireCopy.leaveBody(0, 2))
    }
}
