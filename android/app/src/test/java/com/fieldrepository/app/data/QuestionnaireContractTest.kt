package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.data.*
import com.fieldrepository.app.questionnaire.QFeature
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every BUILD_PLAN §1 example decodes into the app's models. The examples are the frozen contract's,
 * copied verbatim; once `shared/questionnaire-contract.json` arrives from `feature/q-backend`
 * ([sharedContract]), its examples are decoded too.
 */
class QuestionnaireContractTest {
    private val json = ApiClient.json

    private fun <T> decodes(serializer: KSerializer<T>, text: String): T = json.decodeFromString(serializer, text)

    @QFeature("B36")
    @Test fun config() {
        val c = decodes(QConfig.serializer(), """{"contractVersion": 1, "v2Enabled": true, "transcriptionPaused": true, "noteMaxChars": 80,
            "multipartThresholdBytes": 5242880, "partSizeBytes": 8388608, "snapshotPollSeconds": 60, "joinCodeLength": 6, "minAppVersionForCreate": null}""")
        assertTrue(c.v2Enabled); assertTrue(c.transcriptionPaused); assertEquals(5242880L, c.multipartThresholdBytes)
    }

    @Test fun session() {
        val s = decodes(QSession.serializer(), """{
            "id": "s1", "clientKey": "c-2f6e9a", "version": 3,
            "title": "Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa",
            "note": "Under the neem tree", "legacyTitle": null,
            "questionnaire": {"id": "q_ctw3", "title": "3rd Craft Toolkit"},
            "workshop": {"id": "w_bankura", "name": "Bankura"}, "designWorkshopId": null,
            "sessionDate": "2026-07-01", "sessionDateEstimated": false, "startedAt": "…", "endedAt": null, "closedAt": null,
            "state": "OPEN", "purpose": "FIRST_SITTING", "origin": "NATIVE", "mode": "INDIVIDUAL", "participantCount": 1,
            "place": "Bagru, Jaipur", "languageCode": "hi", "languageOther": null,
            "joinCode": "K7Q2MX", "joinQr": "{\"v\":1,\"clientKey\":\"c-2f6e9a\",\"questionnaireId\":\"q_ctw3\",\"workshopId\":\"w_bankura\",\"sessionDate\":\"2026-07-01\",\"participants\":[\"a1\"]}",
            "participants": [{"artisanId": "a1", "name": "Giriraj Prasad Chhipa", "joinedAt": null, "leftAt": null, "consent": {"state": "GRANTED", "source": "researcher"}}],
            "researchers": [{"userId": "u3", "name": "Nandini", "role": "LEAD"}],
            "status": "PENDING", "submittedAt": "…", "reviewReasons": [],
            "counts": {"sections": 6, "takes": 9, "needsDecision": 0}}""")
        assertEquals("K7Q2MX", s.joinCode)
        assertEquals("c-2f6e9a", JoinQr.decode(s.joinQr!!)!!.clientKey)
        assertEquals("GRANTED", s.participants.single().consent?.state)
    }

    @Test fun requestsEncodeWithoutTitleOrFileName() {
        val create = QSessionCreate(clientKey = "c-2f6e9a", questionnaireId = "q_ctw3", workshopId = "w_bankura", sessionDate = "2026-07-01", startedAt = "2026-07-01T12:54:00Z",
            place = "Bagru, Jaipur", languageCode = "hi", note = "Under the neem tree", participants = listOf(QArtisanRef("a1")), researchers = listOf(QResearcherRef("u3")))
        val text = outboxJson.encodeToString(QSessionCreate.serializer(), create)
        val keys = json.parseToJsonElement(text).jsonObject.keys
        assertTrue(keys.containsAll(listOf("clientKey", "questionnaireId", "sessionDate", "startedAt", "purpose", "participants")))
        assertTrue("title" !in keys)
        val presign = outboxJson.encodeToString(QPresignRequest.serializer(), QPresignRequest(sessionId = "s1", clientClipKey = "k-7d1", sizeBytes = 3400000, deviceCapturedAt = "x"))
        assertTrue(json.parseToJsonElement(presign).jsonObject.keys.none { it.contains("filename", ignoreCase = true) })
    }

    @Test fun recording() {
        val r = decodes(QRecording.serializer(), """{
            "id": "r9", "mediaFileId": "m9", "clientClipKey": "k-7d1", "sessionId": "s1", "version": 1,
            "capturedAt": "2026-07-01T12:54:00Z", "capturedAtSource": "DEVICE", "estimated": false,
            "durationMs": 852000, "rosterCount": 1, "recordedBy": {"id": "u3", "name": "Nandini"},
            "targets": [{"targetKey": "Q:lin_d3", "sectionId": null, "questionLineageId": "lin_d3", "askedQuestionId": "qq_d3_v2", "displayCode": "D3", "label": "D3 Tools you use most"}],
            "subjects": [{"artisanId": "a1", "role": "ANSWERED", "named": false, "repeatReason": null, "flag": null, "position": 1}],
            "continuationOfId": null, "filing": "FILED", "decisionKind": null, "provenance": "NATIVE",
            "languageCode": "hi", "removedAt": null,
            "downloadName": "FieldRepository_Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Recording_D-Q03.m4a",
            "transcript": {"status": "QUEUED", "paused": true, "text": null, "language": null},
            "consent": {"verdict": "ALLOW", "source": "owner attestation"}}""")
        assertTrue(r.transcript!!.paused)
        assertEquals("owner attestation", r.consent!!.source)
        // The name the server minted is exactly the one Naming.kt makes for the same take.
        val local = Naming.recordingFileName(
            Naming.RecordingFields(
                Naming.SessionFields(java.time.LocalDate.of(2026, 7, 1), place = "Bagru, Jaipur", workshopCraft = "Hand Block Printing", participantNames = listOf("Giriraj Prasad Chhipa")),
                java.time.Instant.parse(r.capturedAt), r.capturedAtSource, listOf(Naming.NameTarget(sectionCode = "D", displayCode = "D3"))
            ), transliterate = { it }
        )
        assertEquals(r.downloadName, local)
    }

    @Test fun mediaAndCoverage() {
        decodes(QSnapshot.serializer(), """{"asOfSeq": "1042", "takes": [{"recordingId": "r9", "sessionId": "s1", "artisanId": "a1", "targetKey": "Q:lin_d3",
               "role": "ANSWERED", "capturedAt": "…", "estimated": false, "filing": "FILED", "position": 1}], "removedRecordingIds": []}""")
        decodes(QPresignResponse.serializer(), """{"objectKey": "media/u3/9f1c0e7a0b2c4d5e8f90a1b2c3d4e5f6/FieldRepository_Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Recording_D-Q03.m4a",
                      "url": "https://…", "headers": {"x-amz-checksum-sha256": "…"}, "expiresAt": "…"}""")
        decodes(QMultipartCreateResponse.serializer(), """{"objectKey": "…", "uploadId": "…", "partSize": 8388608, "partsDone": []}""")
        val state = decodes(QUploadState.serializer(), """{"objectKey": "…", "multipartUploadId": null, "partSize": 8388608, "partsDone": [{"partNumber": 1, "etag": "\"…\""}], "closed": false}""")
        assertEquals(1, state.partsDone.single().partNumber)
    }

    @Test fun answersConsentInboxRecord() {
        decodes(QAnswersPutResponse.serializer(), """{"results": [{"artisanId": "a1", "questionLineageId": "lin_d3", "status": "SAVED", "answer": {"id": "ans1", "version": 3, "text": "…", "origin": "TYPED",
              "attribution": "INDIVIDUAL", "pinned": false, "sourceRecordingId": null}, "current": null}]}""")
        decodes(QConsentGet.serializer(), """{"artisanId": "a1", "purpose": "EXTERNAL_PROCESSING", "state": "GRANTED", "basis": "VERBAL_RECORDED",
                 "source": "researcher", "recordedAt": "…", "recordedBy": {"id": "u3", "name": "Nandini"}}""")
        decodes(QInbox.serializer(), """{"toFile": [], "needsDecision": [], "legacy": [], "setDates": [], "noParticipants": [], "emptySessions": [], "primaryChanged": [], "counts": {"total": 0, "olderThan24h": 0}}""")
        val rec = decodes(QArtisanRecord.serializer(), """{"artisanId": "a1", "instruments": [{"questionnaireId": "q_ctw3", "title": "3rd Craft Toolkit",
                    "summary": {"sectionsCovered": 14, "sectionsTotal": 24, "sessions": 4, "repeats": 2, "open": ["B", "P"]},
                    "sessions": [], "document": [{"sectionCode": "D", "questions": [{"displayCode": "D3", "answer": null,
                                  "primaryRecordingId": "r9", "otherRecordingIds": [], "accountsDiffer": false, "recordedUnderEarlierWording": false}]}], "retired": []}]}""")
        assertEquals(14, rec.instruments.single().summary.sectionsCovered)
    }

    @Test fun errorCodes() {
        val body = decodes(QErrorBody.serializer(), """{"detail": {"code": "STALE_VERSION", "message": "Changed on another device.", "current": {}, "diff": {}, "count": 0}}""")
        assertEquals(QErrorCodes.STALE_VERSION, body.detail!!.code)
        assertEquals(18, QErrorCodes.ALL.size)
    }

    /** The shared contract's examples, once the BACKEND stream's first commit is merged in. */
    @Test fun sharedContract() {
        val file = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "shared/questionnaire-contract.json") }.firstOrNull { it.isFile } ?: return
        val root = json.parseToJsonElement(file.readText()).jsonObject
        (root["errorCodes"] ?: root["error"]?.jsonObject?.get("errorCodes"))?.let { codes ->
            val listed = json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(String.serializer()), codes)
            assertEquals(listed.toSet(), QErrorCodes.ALL.toSet())
        }
        (root["Session"] as? JsonObject)?.let { json.decodeFromJsonElement(QSession.serializer(), it) }
        (root["Recording"] as? JsonObject)?.let { json.decodeFromJsonElement(QRecording.serializer(), it) }
    }
}
