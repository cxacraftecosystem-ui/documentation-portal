package com.fieldrepository.app.questionnaire

import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.QAnswer
import com.fieldrepository.app.data.QCoverageCheckRequest
import com.fieldrepository.app.data.QForm
import com.fieldrepository.app.data.QFormQuestion
import com.fieldrepository.app.data.QFormSection
import com.fieldrepository.app.data.QTargetRef
import com.fieldrepository.app.ui.questionnaire.AnswerModel
import com.fieldrepository.app.ui.questionnaire.PrefillModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session features that were on the server and in the workspace but had no test pinning the client
 * half: the open-match check (P1), the duplicate-file check (P5), "Use as answer" and AI drafts as
 * proposals, and prefill from the artisan's record.
 */
class SessionClientTest {
    @QFeature("B2")
    @Test fun `The start sheet asks whether a matching session is open before it creates one`() = runBlocking {
        val server = FakeServer()
        server.on("GET /questionnaire/sessions/open-match", """{"items":[{"id":"s9","title":"Interview_2026-10-22","startedAt":"2026-10-22T04:30:00Z","researchers":[{"userId":"u2","name":"Nandini","role":"LEAD"}]}]}""")
        val match = server.api.openMatch("f1", "w1", listOf("a1", "a2").joinToString(","), "2026-10-22").items.single()
        assertEquals("s9", match.id)
        assertEquals("/questionnaire/sessions/open-match?questionnaireId=f1&workshopId=w1&artisanIds=a1%2Ca2&date=2026-10-22", server.last().path)
    }

    @QFeature("B14")
    @Test fun `The file's hash goes with the coverage check, and an identical file elsewhere comes back as P5`() = runBlocking {
        val server = FakeServer()
        server.on("POST /questionnaire/coverage/check", """{"asOf":"2026-10-22T05:00:00Z","identical":{"recordingId":"r7","sessionId":"s3","sameSession":false},"targets":[]}""")
        val sha = "a".repeat(64)
        val out = server.api.coverageCheck(QCoverageCheckRequest("f1", listOf("a1"), listOf(QTargetRef(sectionId = "sD")), sessionId = "s1", contentSha256 = sha))
        assertEquals("r7", out.identical!!.recordingId)
        assertTrue(server.last().body.contains(""""contentSha256":"$sha""""))
    }

    @QFeature("B22")
    @QFeature("C2")
    @Test fun `Use as answer offers a take's transcript as a proposal, saved with its origin and source take`() = runBlocking {
        val server = FakeServer()
        server.on("POST /questionnaire/recordings/r1/answer-proposals", """{"proposals":[{"questionLineageId":"L1","artisanIds":["a1"],"text":"Teak, from Sagwara","origin":"TRANSCRIPT","existing":[]}]}""")
        val p = server.api.answerProposals("r1").proposals.single()
        val items = AnswerModel.items(p.text, p.artisanIds!!.toSet(), p.questionLineageId, "s1", p.existing, origin = p.origin, sourceRecordingId = "r1")
        assertEquals("TRANSCRIPT", items.single().origin)
        assertEquals("r1", items.single().sourceRecordingId)
        assertEquals("Teak, from Sagwara", items.single().text)
    }

    @QFeature("B23")
    @QFeature("C3")
    @Test fun `AI drafts arrive as section-take proposals and keep the machine origin when accepted`() = runBlocking {
        val server = FakeServer()
        server.on("POST /questionnaire/recordings/r2/answer-proposals", """{"proposals":[
            {"questionLineageId":"L1","artisanIds":null,"text":"Teak","origin":"MACHINE_SUGGESTED","existing":[{"id":"x","version":3,"text":"Sal"}]},
            {"questionLineageId":"L2","artisanIds":["a1"],"text":"12 years","origin":"MACHINE_SUGGESTED","existing":[]}]}""")
        val proposals = server.api.answerProposals("r2").proposals
        assertEquals(2, proposals.size)
        val group = proposals[0]
        // No artisans named: the researcher picks whose (All 3 is a group answer); the existing answer's version is the base.
        val items = AnswerModel.items(group.text, setOf("a1", "a2", "a3"), group.questionLineageId, "s1",
            group.existing.map { it.copy(artisanId = "a1", questionLineageId = "L1") }, origin = group.origin, sourceRecordingId = "r2")
        assertEquals(listOf("GROUP_SHARED"), items.map { it.attribution }.distinct())
        assertEquals("MACHINE_SUGGESTED", items.first().origin)
        assertEquals(3, items.first { it.artisanId == "a1" }.baseVersion)
    }

    @QFeature("B24")
    @Test fun `Mapped questions prefill from the artisan's record, never over a typed answer`() {
        val form = QForm(id = "f1", sections = listOf(QFormSection("sA", "A", "About", 1, questions = listOf(
            QFormQuestion("q1", lineageId = "L1", prompt = "Name?", artisanField = "name"),
            QFormQuestion("q2", lineageId = "L2", prompt = "Village?", artisanField = "place"),
            QFormQuestion("q3", lineageId = "L3", prompt = "Story?")
        ))))
        val answers = listOf(QAnswer(text = "Typed village", artisanId = "a1", questionLineageId = "L2"))
        val artisan = ArtisanDto(id = "a1", name = "Giriraj", place = "Bagru", status = "APPROVED")
        val items = PrefillModel.items(form, listOf("a1"), "s1", answers) { _, field -> PrefillModel.localValue(artisan, field) }
        assertEquals(listOf("L1"), items.map { it.questionLineageId })
        assertEquals("PREFILL", items.single().origin)
        assertEquals("Giriraj", items.single().text)
        assertTrue(ApiClient.json.encodeToString(com.fieldrepository.app.data.QAnswersPut.serializer(), com.fieldrepository.app.data.QAnswersPut(items)).contains(""""origin":"PREFILL""""))
    }
}
