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
        assertEquals("CONTRIBUTOR", (body["researchers"] as kotlinx.serialization.json.JsonArray).first().jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue("title" !in body.keys)
    }

    @QFeature("Q1")
    @Test fun `session create sends record by section unless told otherwise, and a patch sends it only when it changes`() {
        val create = keys(QSessionCreate.serializer(), QSessionCreate(clientKey = "c", questionnaireId = "q", sessionDate = "2026-07-01", startedAt = "x"))
        assertEquals(RecordBy.SECTION.wire, create["recordBy"]!!.jsonPrimitive.content)
        val byQuestion = keys(QSessionCreate.serializer(), QSessionCreate(clientKey = "c", questionnaireId = "q", sessionDate = "2026-07-01", startedAt = "x", recordBy = RecordBy.QUESTION.wire))
        assertEquals("QUESTION", byQuestion["recordBy"]!!.jsonPrimitive.content)
        assertTrue("recordBy" !in keys(QSessionPatch.serializer(), QSessionPatch(version = 2, note = "n")).keys)
        assertEquals("QUESTION", keys(QSessionPatch.serializer(), QSessionPatch(version = 2, recordBy = "QUESTION"))["recordBy"]!!.jsonPrimitive.content)
    }

    @QFeature("Q5")
    @Test fun `a session reads its record by and its question progress`() {
        val session = QuestionnairePortal.json.decodeFromString(
            QSession.serializer(),
            """{"id": "s1", "recordBy": "QUESTION", "counts": {"sections": 3, "takes": 4, "needsDecision": 0, "answeredQuestions": 18, "totalQuestions": 108},
               "progress": {"answeredQuestions": 18, "totalQuestions": 108, "sections": [{"sectionId": "secA", "answeredQuestions": 5, "totalQuestions": 5, "inSectionTake": true}]}}"""
        )
        assertEquals("QUESTION", session.recordBy)
        assertEquals(108, session.counts.totalQuestions)
        assertTrue(session.progress!!.section("secA")!!.inSectionTake)
        assertEquals(RecordBy.SECTION.wire, QuestionnairePortal.json.decodeFromString(QSession.serializer(), """{"id": "s2"}""").recordBy)
    }

    @Test fun `answers, consent and continue carry their defaults`() {
        val answer = keys(QAnswerItem.serializer(), QAnswerItem("a1", "lin", "text", sessionId = "s1"))
        assertEquals("TYPED", answer["origin"]!!.jsonPrimitive.content)
        assertEquals("INDIVIDUAL", answer["attribution"]!!.jsonPrimitive.content)
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
