package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit C1 (21 Oct 2026): every v2 request, encoded the way Retrofit sends it — through
 * [QuestionnairePortal.json], whose `encodeDefaults` is off for the legacy models — still carries the fields the
 * server needs. Before the fix `linkedRecordType` was a dropped default, so every presign and complete
 * went to the legacy path and came back 422; the contract test encoded with [outboxJson] and passed.
 */
@QFeature("B10")
class ApiClientEncodingTest {
    private fun <T> keys(serializer: KSerializer<T>, value: T): JsonObject =
        QuestionnairePortal.json.parseToJsonElement(QuestionnairePortal.json.encodeToString(serializer, value)).jsonObject

    @Test fun `presign carries the v2 route and the file description`() {
        val body = keys(QPresignRequest.serializer(), QPresignRequest(sessionId = "s1", clientClipKey = "k1", sizeBytes = 10, deviceCapturedAt = "2026-07-01T10:00:00Z"))
        assertEquals("questionnaire_session", body["linkedRecordType"]!!.jsonPrimitive.content)
        assertEquals("audio/mp4", body["contentType"]!!.jsonPrimitive.content)
        assertEquals("m4a", body["extension"]!!.jsonPrimitive.content)
        assertTrue(body.keys.containsAll(listOf("targets", "subjectArtisanIds", "clockOffsetMs")))
    }

    @Test fun `complete carries the v2 route and a full recording`() {
        val body = keys(
            QCompleteRequest.serializer(),
            QCompleteRequest(objectKey = "o", clientClipKey = "k1", linkedRecordId = "s1", recording = QCompleteRecording(subjects = listOf(QCompleteSubject("a1")), deviceCapturedAt = "x"))
        )
        assertEquals("questionnaire_session", body["linkedRecordType"]!!.jsonPrimitive.content)
        val recording = body["recording"]!!.jsonObject
        assertTrue(recording.keys.containsAll(listOf("targets", "subjects", "clockOffsetMs", "capturedAtSource", "durationMs", "rosterCount", "ackedRecordingIds")))
        val subject = (recording["subjects"] as kotlinx.serialization.json.JsonArray).first().jsonObject
        assertEquals("ANSWERED", subject["role"]!!.jsonPrimitive.content)
        assertEquals("false", subject["named"]!!.jsonPrimitive.content)
    }

    @Test fun `session create carries purpose, participants and each researcher's role`() {
        val body = keys(
            QSessionCreate.serializer(),
            QSessionCreate(clientKey = "c", questionnaireId = "q", sessionDate = "2026-07-01", startedAt = "x", participants = listOf(QArtisanRef("a1")), researchers = listOf(QResearcherRef("u1")))
        )
        assertEquals("FIRST_SITTING", body["purpose"]!!.jsonPrimitive.content)
        // ResearcherInput.role has no server default: a dropped role is a 422.
        assertEquals("CONTRIBUTOR", (body["researchers"] as kotlinx.serialization.json.JsonArray).first().jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue("title" !in body.keys)
    }

    @Test fun `answers, consent and continue carry their defaults`() {
        val answer = keys(QAnswerItem.serializer(), QAnswerItem("a1", "lin", "text", sessionId = "s1"))
        assertEquals("TYPED", answer["origin"]!!.jsonPrimitive.content)
        assertEquals("INDIVIDUAL", answer["attribution"]!!.jsonPrimitive.content)
        // An unset baseVersion stays off the wire (a null would still be valid, but the key is not needed).
        assertTrue("baseVersion" !in answer.keys)
        val consent = keys(QConsentPost.serializer(), QConsentPost(state = "GRANTED", clientKey = "k"))
        assertEquals("EXTERNAL_PROCESSING", consent["purpose"]!!.jsonPrimitive.content)
        assertEquals("VERBAL_RECORDED", consent["basis"]!!.jsonPrimitive.content)
        val cont = keys(QContinueRequest.serializer(), QContinueRequest.of("s1", listOf("a1"), "EACH", "2026-07-02"))
        assertEquals("ONE", cont["split"]!!.jsonPrimitive.content)
    }

    @Test fun `a recording patch sends only what changes`() {
        val body = keys(QRecordingPatch.serializer(), QRecordingPatch(version = 2, targets = listOf(QTargetRef(questionLineageId = "lin"))))
        assertEquals(setOf("version", "targets"), body.keys)
    }
}
