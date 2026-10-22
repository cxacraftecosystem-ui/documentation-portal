@file:OptIn(ExperimentalSerializationApi::class)

package com.fieldrepository.app.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Questionnaire v2 wire models: the frozen contract of BUILD_PLAN_2026-10-18.md §1
 * (`shared/questionnaire-contract.json`). Every response field is defaulted so a server one contract
 * step ahead still decodes; requests carry exactly the contract's fields and NEVER a title or a file
 * name (DESIGN §9 test 61).
 *
 * REQUEST DEFAULTS ARE ALWAYS ENCODED (audit C1, 21 Oct 2026). Retrofit encodes with [ApiClient.json],
 * which leaves `encodeDefaults` off for every legacy model, so a defaulted request field was silently
 * dropped: `linkedRecordType = "questionnaire_session"` never reached the server, which then routed
 * every presign and complete to the legacy path and refused it with a 422. Each defaulted field of a
 * request below carries `@EncodeDefault`, so it is on the wire whatever encoder sends it;
 * `ApiClientEncodingTest` encodes every request through [ApiClient.json] and pins the keys.
 */

object QErrorCodes {
    const val VALIDATION_FAILED = "VALIDATION_FAILED"
    const val NOT_FOUND = "NOT_FOUND"
    const val FORBIDDEN = "FORBIDDEN"
    const val STALE_VERSION = "STALE_VERSION"
    const val INSTRUMENT_LOCKED = "INSTRUMENT_LOCKED"
    const val PARTICIPANT_HAS_TAKES = "PARTICIPANT_HAS_TAKES"
    const val HAS_RECORDINGS = "HAS_RECORDINGS"
    const val TITLE_IS_GENERATED = "TITLE_IS_GENERATED"
    const val TARGET_OUTSIDE_INSTRUMENT = "TARGET_OUTSIDE_INSTRUMENT"
    const val JOIN_CODE_UNKNOWN = "JOIN_CODE_UNKNOWN"
    const val MERGE_INSTRUMENT_MISMATCH = "MERGE_INSTRUMENT_MISMATCH"
    const val SESSION_UNKNOWN = "SESSION_UNKNOWN"
    const val TRANSCRIPTION_PAUSED = "TRANSCRIPTION_PAUSED"
    const val CONSENT_REFUSED = "CONSENT_REFUSED"
    const val CONSENT_NOT_RECORDED = "CONSENT_NOT_RECORDED"
    const val APPROVE_BLOCKED = "APPROVE_BLOCKED"
    const val FORM_DELETED = "FORM_DELETED"
    const val BUNDLE_VERSION = "BUNDLE_VERSION"

    val ALL = listOf(
        VALIDATION_FAILED, NOT_FOUND, FORBIDDEN, STALE_VERSION, INSTRUMENT_LOCKED, PARTICIPANT_HAS_TAKES,
        HAS_RECORDINGS, TITLE_IS_GENERATED, TARGET_OUTSIDE_INSTRUMENT, JOIN_CODE_UNKNOWN,
        MERGE_INSTRUMENT_MISMATCH, SESSION_UNKNOWN, TRANSCRIPTION_PAUSED, CONSENT_REFUSED,
        CONSENT_NOT_RECORDED, APPROVE_BLOCKED, FORM_DELETED, BUNDLE_VERSION
    )
}

// RepeatReason, SessionPurpose, SubjectRole and CoverageState are the generated enums in SharedEnums.kt
// (from shared/enums.json); the wire models carry their `.wire` strings.

@Serializable
data class QConfig(
    val contractVersion: Int = 0,
    val v2Enabled: Boolean = false,
    val transcriptionPaused: Boolean = true,
    val noteMaxChars: Int = 80,
    val multipartThresholdBytes: Long = 5L * 1024 * 1024,
    val partSizeBytes: Long = 8L * 1024 * 1024,
    val snapshotPollSeconds: Int = 60,
    val joinCodeLength: Int = 6,
    /** Legacy: a version NAME. Read only when it is a bare number, which is then a versionCode (C7). */
    val minAppVersionForCreate: String? = null,
    /** The lowest Android versionCode that may create (audit C7); null when there is no cutoff. */
    val minAndroidVersionCodeForCreate: Int? = null
) {
    /**
     * P14: whether this build is below the server's cutoff. Codes only: names were reset to 0.0.1, so a
     * name compare would show the banner for ever on the newest build (audit C7).
     */
    fun updateRequired(versionCode: Int): Boolean {
        val minimum = minAndroidVersionCodeForCreate ?: minAppVersionForCreate?.trim()?.toIntOrNull() ?: return false
        return versionCode < minimum
    }
}

@Serializable
data class QErrorDetail(
    val code: String = "",
    val message: String = "",
    val current: JsonElement? = null,
    val diff: JsonElement? = null,
    val count: Int? = null,
    /** Why, in a word the client acts on (`UPLOAD_MISSING`: the bytes never reached storage). */
    val reason: String? = null,
    /** The server says the same request can succeed later (after a re-upload, say). */
    val retry: Boolean = false
)

@Serializable
data class QErrorBody(val detail: QErrorDetail? = null)

// ── Sessions (§1.2) ──────────────────────────────────────────────────────────────────────────

@Serializable
data class QArtisanRef(val artisanId: String)

@Serializable
data class QResearcherRef(val userId: String, @EncodeDefault val role: String = "CONTRIBUTOR")

@Serializable
data class QSessionCreate(
    val clientKey: String,
    val questionnaireId: String,
    val workshopId: String? = null,
    val designWorkshopId: String? = null,
    val sessionDate: String,
    val startedAt: String,
    @EncodeDefault val purpose: String = SessionPurpose.FIRST_SITTING.wire,
    val place: String? = null,
    val languageCode: String? = null,
    val languageOther: String? = null,
    val note: String? = null,
    @EncodeDefault val participants: List<QArtisanRef> = emptyList(),
    @EncodeDefault val researchers: List<QResearcherRef> = emptyList()
)

@Serializable
data class QSessionPatch(
    val version: Int,
    val place: String? = null,
    val languageCode: String? = null,
    val languageOther: String? = null,
    val note: String? = null,
    val sessionDate: String? = null,
    val workshopId: String? = null,
    val designWorkshopId: String? = null,
    val purpose: String? = null
)

@Serializable
data class QNamedRef(val id: String = "", val title: String? = null, val name: String? = null)

@Serializable
data class QConsentBrief(val state: String = "NOT_RECORDED", val source: String? = null)

@Serializable
data class QParticipant(
    val artisanId: String,
    val name: String = "",
    val joinedAt: String? = null,
    val leftAt: String? = null,
    val consent: QConsentBrief? = null
)

@Serializable
data class QResearcher(val userId: String, val name: String = "", val role: String = "CONTRIBUTOR")

@Serializable
data class QSessionCounts(val sections: Int = 0, val takes: Int = 0, val needsDecision: Int = 0)

@Serializable
data class QSession(
    val id: String,
    val clientKey: String? = null,
    val version: Int = 1,
    val title: String = "",
    val note: String? = null,
    val legacyTitle: String? = null,
    val questionnaire: QNamedRef? = null,
    val workshop: QNamedRef? = null,
    val designWorkshopId: String? = null,
    val sessionDate: String? = null,
    val sessionDateEstimated: Boolean = false,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val closedAt: String? = null,
    val state: String = "OPEN",
    val purpose: String = SessionPurpose.FIRST_SITTING.wire,
    val origin: String = "NATIVE",
    val mode: String = "INDIVIDUAL",
    val participantCount: Int = 0,
    val place: String? = null,
    val languageCode: String? = null,
    val languageOther: String? = null,
    val joinCode: String? = null,
    val joinQr: String? = null,
    val participants: List<QParticipant> = emptyList(),
    val researchers: List<QResearcher> = emptyList(),
    val status: String = "PENDING",
    val submittedAt: String? = null,
    val reviewReasons: List<String> = emptyList(),
    val counts: QSessionCounts = QSessionCounts()
)

@Serializable
data class QSessionDetail(
    val id: String,
    val clientKey: String? = null,
    val version: Int = 1,
    val title: String = "",
    val note: String? = null,
    val legacyTitle: String? = null,
    val questionnaire: QNamedRef? = null,
    val workshop: QNamedRef? = null,
    val sessionDate: String? = null,
    val state: String = "OPEN",
    val purpose: String = SessionPurpose.FIRST_SITTING.wire,
    val participantCount: Int = 0,
    val place: String? = null,
    val languageCode: String? = null,
    val joinCode: String? = null,
    val joinQr: String? = null,
    val participants: List<QParticipant> = emptyList(),
    val researchers: List<QResearcher> = emptyList(),
    val status: String = "PENDING",
    val counts: QSessionCounts = QSessionCounts(),
    val recordings: List<QRecording> = emptyList(),
    val answers: List<QAnswer> = emptyList(),
    val grid: JsonElement? = null
)

@Serializable
data class QSessionList(val items: List<QSession> = emptyList(), val nextCursor: String? = null, val capped: Boolean = false)

@Serializable
data class QOpenMatch(val items: List<QSession> = emptyList())

@Serializable
data class QJoinRequest(val joinCode: String)

@Serializable
data class QParticipantPatch(val joinedAt: String? = null, val leftAt: String? = null)

// ── Recording, coverage and media (§1.3) ─────────────────────────────────────────────────────

@Serializable
data class QTarget(
    val targetKey: String = "",
    val sectionId: String? = null,
    val questionLineageId: String? = null,
    val askedQuestionId: String? = null,
    val displayCode: String? = null,
    val label: String? = null
)

/** A target as a request names it: exactly one of the two ids. */
@Serializable
data class QTargetRef(val sectionId: String? = null, val questionLineageId: String? = null) {
    val targetKey: String get() = if (questionLineageId != null) "Q:$questionLineageId" else "S:$sectionId"
}

@Serializable
data class QSubject(
    val artisanId: String,
    val role: String = SubjectRole.ANSWERED.wire,
    val named: Boolean = false,
    val repeatReason: String? = null,
    val flag: String? = null,
    val position: Int? = null
)

@Serializable
data class QUserRef(val id: String = "", val name: String = "")

@Serializable
data class QTranscript(val status: String = "QUEUED", val paused: Boolean = true, val text: String? = null, val language: String? = null)

@Serializable
data class QConsentVerdict(val verdict: String = "AWAITING", val source: String? = null)

@Serializable
data class QRecording(
    val id: String,
    val mediaFileId: String? = null,
    val clientClipKey: String? = null,
    val sessionId: String? = null,
    val version: Int = 1,
    val capturedAt: String? = null,
    val capturedAtSource: String = "DEVICE",
    val estimated: Boolean = false,
    val durationMs: Long = 0,
    val rosterCount: Int = 1,
    val recordedBy: QUserRef? = null,
    val targets: List<QTarget> = emptyList(),
    val subjects: List<QSubject> = emptyList(),
    val continuationOfId: String? = null,
    val filing: String = "FILED",
    val decisionKind: String? = null,
    val provenance: String = "NATIVE",
    val languageCode: String? = null,
    val removedAt: String? = null,
    val downloadName: String? = null,
    val transcript: QTranscript? = null,
    val consent: QConsentVerdict? = null
)

@Serializable
data class QCoverageCheckRequest(
    val questionnaireId: String,
    val artisanIds: List<String>,
    val targets: List<QTargetRef>,
    val sessionId: String? = null,
    val contentSha256: String? = null
)

@Serializable
data class QCheckSession(val id: String = "", val date: String? = null, val rosterCount: Int = 1)

@Serializable
data class QCheckTake(
    val recordingId: String,
    val position: Int = 1,
    val capturedAt: String? = null,
    val estimated: Boolean = false,
    val durationMs: Long = 0,
    val recordedBy: QUserRef? = null,
    val session: QCheckSession? = null,
    val sameSession: Boolean = false,
    val mine: Boolean = false,
    val repeatReason: String? = null
)

@Serializable
data class QCheckArtisan(
    val artisanId: String,
    val state: String = CoverageState.NOT_RECORDED.wire,
    val viaSection: Boolean = false,
    val takes: List<QCheckTake> = emptyList()
)

@Serializable
data class QCheckTarget(val targetKey: String, val label: String? = null, val perArtisan: List<QCheckArtisan> = emptyList())

@Serializable
data class QIdentical(val recordingId: String, val sessionId: String? = null, val sameSession: Boolean = false)

@Serializable
data class QCoverageCheckResponse(
    val asOf: String? = null,
    val identical: QIdentical? = null,
    val targets: List<QCheckTarget> = emptyList(),
    val suggestedReason: String? = null
)

@Serializable
data class QSnapshotTake(
    val recordingId: String,
    val sessionId: String? = null,
    val artisanId: String,
    val targetKey: String,
    val role: String = SubjectRole.ANSWERED.wire,
    val capturedAt: String? = null,
    val estimated: Boolean = false,
    val filing: String = "FILED",
    val position: Int = 1,
    /** Not in contract v1's example; read when a later server sends it, for P2's copy. */
    val durationMs: Long = 0,
    val recordedBy: QUserRef? = null,
    val rosterCount: Int = 1,
    val sessionDate: String? = null,
    val repeatReason: String? = null
)

@Serializable
data class QSnapshot(
    val asOfSeq: String = "0",
    val takes: List<QSnapshotTake> = emptyList(),
    val removedRecordingIds: List<String> = emptyList(),
    /** Takes that changed since `sinceSeq`: their old rows go before [takes] are added (a take now PRESENT-only appears in neither list). */
    val changedRecordingIds: List<String> = emptyList()
)

@Serializable
data class QPresignRequest(
    @EncodeDefault val linkedRecordType: String = "questionnaire_session",
    val sessionId: String,
    val clientClipKey: String,
    val sizeBytes: Long,
    @EncodeDefault val contentType: String = "audio/mp4",
    @EncodeDefault val extension: String = "m4a",
    val contentSha256: String? = null,
    @EncodeDefault val targets: List<QTargetRef> = emptyList(),
    @EncodeDefault val subjectArtisanIds: List<String> = emptyList(),
    val deviceCapturedAt: String,
    @EncodeDefault val clockOffsetMs: Long = 0,
    /** When the offset was measured; a stale one marks the capture time estimated (server-side, §3.9). */
    val clockOffsetMeasuredAt: String? = null,
    /** The take this one continues, so the name the server mints ends `-P2`, not `-T2`. */
    val continuationOfId: String? = null
)

@Serializable
data class QPresignResponse(
    val objectKey: String,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: String? = null,
    /** The upload already landed (its intent is closed): nothing is signed, nothing is PUT again. */
    val closed: Boolean = false
)

@Serializable
data class QPartDone(val partNumber: Int, val etag: String)

@Serializable
data class QMultipartCreateResponse(
    val objectKey: String,
    /** Empty when [closed]: the upload already landed and nothing is opened again. */
    val uploadId: String = "",
    val partSize: Long = 8L * 1024 * 1024,
    val partsDone: List<QPartDone> = emptyList(),
    val closed: Boolean = false
)

@Serializable
data class QMultipartPresignParts(val objectKey: String, val uploadId: String, val partNumbers: List<Int>)

@Serializable
data class QMultipartPresignPartsResponse(val urls: Map<String, String> = emptyMap())

@Serializable
data class QMultipartComplete(val objectKey: String, val uploadId: String, val parts: List<QPartDone>)

@Serializable
data class QUploadState(
    val objectKey: String,
    val multipartUploadId: String? = null,
    val partSize: Long = 8L * 1024 * 1024,
    val partsDone: List<QPartDone> = emptyList(),
    val closed: Boolean = false
)

@Serializable
data class QCompleteSubject(
    val artisanId: String,
    @EncodeDefault val role: String = SubjectRole.ANSWERED.wire,
    @EncodeDefault val named: Boolean = false,
    val repeatReason: String? = null
)

@Serializable
data class QCompleteRecording(
    @EncodeDefault val targets: List<QTargetRef> = emptyList(),
    @EncodeDefault val subjects: List<QCompleteSubject> = emptyList(),
    val deviceCapturedAt: String,
    @EncodeDefault val clockOffsetMs: Long = 0,
    val clockOffsetMeasuredAt: String? = null,
    @EncodeDefault val capturedAtSource: String = "DEVICE",
    @EncodeDefault val durationMs: Long = 0,
    @EncodeDefault val rosterCount: Int = 1,
    val contentSha256: String? = null,
    val continuationOfId: String? = null,
    val replacesRecordingId: String? = null,
    @EncodeDefault val ackedRecordingIds: List<String> = emptyList()
)

@Serializable
data class QCompleteRequest(
    val objectKey: String,
    val clientClipKey: String,
    @EncodeDefault val linkedRecordType: String = "questionnaire_session",
    val linkedRecordId: String,
    val recording: QCompleteRecording
)

// ── Answers, consent, inbox, record (§1.4) ───────────────────────────────────────────────────

@Serializable
data class QAnswer(
    val id: String = "",
    val version: Int = 1,
    val text: String = "",
    val origin: String = "TYPED",
    val attribution: String = "INDIVIDUAL",
    val pinned: Boolean = false,
    val sourceRecordingId: String? = null,
    val artisanId: String? = null,
    val questionLineageId: String? = null
)

@Serializable
data class QAnswerItem(
    val artisanId: String,
    val questionLineageId: String,
    val text: String,
    @EncodeDefault val origin: String = "TYPED",
    @EncodeDefault val attribution: String = "INDIVIDUAL",
    val sessionId: String,
    val sourceRecordingId: String? = null,
    val baseVersion: Int? = null
)

@Serializable
data class QAnswersPut(val items: List<QAnswerItem>)

@Serializable
data class QAnswerResult(
    val artisanId: String,
    val questionLineageId: String,
    val status: String,
    val answer: QAnswer? = null,
    val current: QAnswer? = null
)

@Serializable
data class QAnswersPutResponse(val results: List<QAnswerResult> = emptyList())

@Serializable
data class QConsentGet(
    val artisanId: String = "",
    val purpose: String = "EXTERNAL_PROCESSING",
    val state: String = "NOT_RECORDED",
    val basis: String? = null,
    val source: String? = null,
    val recordedAt: String? = null,
    val recordedBy: QUserRef? = null
)

@Serializable
data class QConsentPost(
    @EncodeDefault val purpose: String = "EXTERNAL_PROCESSING",
    val state: String,
    @EncodeDefault val basis: String = "VERBAL_RECORDED",
    val note: String? = null,
    val evidenceMediaId: String? = null,
    val clientKey: String
)

@Serializable
data class QSubjectPatch(val artisanId: String, val role: String)

/** `PATCH /recordings/{id}`: only the fields being changed; nulls are left out on the wire. */
@Serializable
data class QRecordingPatch(
    val version: Int,
    val subjects: List<QSubjectPatch>? = null,
    val targets: List<QTargetRef>? = null,
    val sessionId: String? = null
)

@Serializable
data class QDecideRequest(val action: String, val otherRecordingId: String? = null)

@Serializable
data class QInboxCounts(val total: Int = 0, val olderThan24h: Int = 0)

@Serializable
data class QInbox(
    val toFile: List<JsonObject> = emptyList(),
    val needsDecision: List<JsonObject> = emptyList(),
    val legacy: List<JsonObject> = emptyList(),
    val setDates: List<JsonObject> = emptyList(),
    val noParticipants: List<JsonObject> = emptyList(),
    val emptySessions: List<JsonObject> = emptyList(),
    val primaryChanged: List<JsonObject> = emptyList(),
    val counts: QInboxCounts = QInboxCounts()
)

@Serializable
data class QRecordSummary(
    val sectionsCovered: Int = 0,
    val sectionsTotal: Int = 0,
    val sessions: Int = 0,
    val repeats: Int = 0,
    val open: List<String> = emptyList()
)

@Serializable
data class QRecordSession(
    val session: QSession,
    val recordings: List<QRecording> = emptyList(),
    val uncertain: List<QRecording> = emptyList()
)

@Serializable
data class QRecordQuestion(
    val displayCode: String = "",
    val answer: QAnswer? = null,
    val primaryRecordingId: String? = null,
    val otherRecordingIds: List<String> = emptyList(),
    val accountsDiffer: Boolean = false,
    val recordedUnderEarlierWording: Boolean = false
)

@Serializable
data class QRecordSection(val sectionCode: String = "", val questions: List<QRecordQuestion> = emptyList())

@Serializable
data class QRecordInstrument(
    val questionnaireId: String,
    val title: String = "",
    val summary: QRecordSummary = QRecordSummary(),
    val sessions: List<QRecordSession> = emptyList(),
    val document: List<QRecordSection> = emptyList(),
    val retired: List<JsonElement> = emptyList()
) {
    /** The roster size of the take a group answer came from, when the record holds that take. */
    fun rosterOf(recordingId: String?): Int? = recordingId?.let { id ->
        sessions.asSequence().flatMap { it.recordings.asSequence() + it.uncertain.asSequence() }.firstOrNull { it.id == id }?.rosterCount
    }
}

@Serializable
data class QArtisanRecord(val artisanId: String, val instruments: List<QRecordInstrument> = emptyList())

// ── Continue, merge, primary, proposals ──────────────────────────────────────────────────────

/** `POST /sessions/continue`: carry on another day with these artisans, as one session or one each. */
@Serializable
data class QContinueRequest(
    val fromSessionId: String,
    val artisanIds: List<String>,
    @EncodeDefault val split: String = "ONE",
    val sessionDate: String,
    /** Keys the sessions the server makes, so a replay of the same request returns them again. */
    val clientKey: String? = null
) {
    companion object {
        /** A single artisan is always one session, whatever the picker said (the web's `continueBody`). */
        fun of(fromSessionId: String, artisanIds: List<String>, split: String, sessionDate: String, clientKey: String? = null) =
            QContinueRequest(fromSessionId, artisanIds, if (artisanIds.size > 1) split else "ONE", sessionDate, clientKey)
    }
}

/** "File it" (audit H14): a "To file" clip joins [sessionId] as a take of [targets]; [subjects] when named. */
@Serializable
data class QFileMediaRequest(
    val sessionId: String,
    @EncodeDefault val targets: List<QTargetRef> = emptyList(),
    val subjects: List<QSubjectPatch>? = null
)

/** "File it" for a take the server could not place (a legacy question, a session with nobody in it). */
@Serializable
data class QFileRecordingRequest(
    @EncodeDefault val targets: List<QTargetRef> = emptyList(),
    val subjects: List<QSubjectPatch>? = null,
    val sessionId: String? = null
)

@Serializable
data class QContinueResponse(val sessions: List<QSession> = emptyList(), val firstGap: Map<String, String?> = emptyMap())

@Serializable
data class QMakePrimaryRequest(val artisanId: String, val targetKey: String)

@Serializable
data class QProposal(
    val questionLineageId: String,
    val artisanIds: List<String>? = null,
    val text: String = "",
    val origin: String = "TRANSCRIPT",
    val existing: List<QAnswer> = emptyList()
)

@Serializable
data class QProposals(val proposals: List<QProposal> = emptyList())
