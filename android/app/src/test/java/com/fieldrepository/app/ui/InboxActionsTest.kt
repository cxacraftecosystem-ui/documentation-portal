package com.fieldrepository.app.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.QDecideRequest
import com.fieldrepository.app.data.QFileMediaRequest
import com.fieldrepository.app.data.QInbox
import com.fieldrepository.app.data.QRecording
import com.fieldrepository.app.data.QSubject
import com.fieldrepository.app.data.QSubjectPatch
import com.fieldrepository.app.data.QTarget
import com.fieldrepository.app.data.QTargetRef
import com.fieldrepository.app.ui.questionnaire.InboxSection
import com.fieldrepository.app.ui.questionnaire.inboxSections
import com.fieldrepository.app.ui.questionnaire.legacyRow
import com.fieldrepository.app.ui.questionnaire.otherTakeOf
import com.fieldrepository.app.ui.questionnaire.partTwoOrder
import com.fieldrepository.app.ui.questionnaire.toFileRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit H13 and H14 (21 Oct 2026): "Keep one…" sent KEEP_THIS with no other take (always a 422), and
 * "To file" rows could not be filed at all. Keep one now names the other take, Part 2 makes the later
 * take the continuation, and File it sends the session, target and who answered. "Not needed", "Keep"
 * and "OK" are remembered on the phone.
 */
class InboxActionsTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun take(id: String, at: String, target: String = "S:d", who: String = "a1", removed: String? = null) = QRecording(
        id = id, capturedAt = at, removedAt = removed,
        targets = listOf(QTarget(targetKey = target)), subjects = listOf(QSubject(who))
    )

    @Test fun `Keep one names the other take - the server's, else the nearest live take on the same target and artisan`() {
        val takes = listOf(
            take("r1", "2026-07-07T10:42:00Z"),
            take("r2", "2026-07-07T10:44:00Z"),
            take("r3", "2026-07-07T10:43:00Z", removed = "2026-07-08T00:00:00Z"),
            take("r4", "2026-07-07T10:43:30Z", target = "S:e"),
            take("r5", "2026-07-07T09:00:00Z", who = "a2")
        )
        assertEquals("r2", otherTakeOf("r1", null, takes)?.id)
        assertEquals("r5", otherTakeOf("r1", "r5", takes)?.id)
        assertNull(otherTakeOf("r4", null, takes))
    }

    @QFeature("B18")
    @Test fun `Part 2 makes the later take the continuation of the earlier one`() {
        val early = take("r1", "2026-07-07T10:42:00Z")
        val late = take("r2", "2026-07-07T10:44:00Z")
        assertEquals("r2" to "r1", partTwoOrder(early, late))
        assertEquals("r2" to "r1", partTwoOrder(late, early))
    }

    @Test fun `the decide body carries the other take`() {
        val body = ApiClient.json.parseToJsonElement(ApiClient.json.encodeToString(QDecideRequest.serializer(), QDecideRequest("KEEP_THIS", "r2"))).jsonObject
        assertEquals("r2", body["otherRecordingId"]!!.jsonPrimitive.content)
    }

    @QFeature("B25")
    @Test fun `File it preselects the candidate and sends session, target and who answered`() {
        val row = toFileRow(obj("""{"mediaId":"m1","name":"f.webm","date":"2026-06-23T05:00:00Z","sessionId":null,"candidate":{"sessionId":"s9","targetKey":"Q:lin3"}}"""))
        assertEquals("m1", row.mediaId)
        assertEquals("s9", row.candidateSessionId)
        assertEquals("Q:lin3", row.candidateTargetKey)
        val body = ApiClient.json.parseToJsonElement(
            ApiClient.json.encodeToString(QFileMediaRequest.serializer(), QFileMediaRequest("s9", listOf(QTargetRef(questionLineageId = "lin3")), listOf(QSubjectPatch("a1", "ANSWERED"))))
        ).jsonObject
        assertEquals(setOf("sessionId", "targets", "subjects"), body.keys)
        val everyone = ApiClient.json.parseToJsonElement(ApiClient.json.encodeToString(QFileMediaRequest.serializer(), QFileMediaRequest("s9"))).jsonObject
        assertEquals("subjects left to the server when everyone there answered", setOf("sessionId", "targets"), everyone.keys)
    }

    @Test fun `Not needed is kept on the server, so every device agrees`() {
        val method = com.fieldrepository.app.data.QuestionnaireApi::class.java.methods.single { it.name == "mediaNotNeeded" }
        assertEquals("questionnaire/media/{id}/not-needed", method.getAnnotation(retrofit2.http.POST::class.java)!!.value)
        val file = com.fieldrepository.app.data.QuestionnaireApi::class.java.methods.single { it.name == "fileMedia" }
        assertEquals("questionnaire/media/{id}/file", file.getAnnotation(retrofit2.http.POST::class.java)!!.value)
    }

    @Test fun `a legacy row carries its candidate questions as pills`() {
        val row = legacyRow(obj("""{"recordingId":"r1","sessionId":"s1","candidates":[{"questionLineageId":"lin7","prompt":"Tools you use"},{"questionLineageId":"lin8"}],"capturedAt":"2026-06-20T05:00:00Z"}"""))
        assertEquals(listOf("lin7" to "Tools you use", "lin8" to "lin8"), row.candidates.map { it.lineageId to it.prompt })
    }

    @Test fun `rows dismissed on this phone stay hidden, the rest keep their sections`() {
        val inbox = QInbox(
            toFile = listOf(obj("""{"mediaId":"m1","name":"a.webm"}"""), obj("""{"mediaId":"m2","name":"b.webm"}""")),
            primaryChanged = listOf(obj("""{"eventId":"e1","recordingId":"r1","at":"2026-07-01T00:00:00Z"}"""))
        )
        val sections = inboxSections(inbox, dismissed = setOf("toFile:m1", "primaryChanged:e1"))
        assertEquals(1, sections.size)
        assertEquals(listOf("m2"), sections.single().second.map { it.mediaId })
        assertTrue(sections.single().second.all { it.section == InboxSection.TO_FILE })
    }
}
