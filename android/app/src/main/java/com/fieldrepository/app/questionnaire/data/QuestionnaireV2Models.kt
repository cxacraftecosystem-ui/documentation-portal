@file:OptIn(ExperimentalSerializationApi::class)

package com.fieldrepository.app.questionnaire.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object QSubjects {
    const val GUEST_PREFIX = "R:"
    const val LOCAL_GUEST_PREFIX = "R:local-"

    fun isGuest(subjectId: String): Boolean = subjectId.startsWith(GUEST_PREFIX)
    fun respondentOf(subjectId: String): String? = if (isGuest(subjectId)) subjectId.removePrefix(GUEST_PREFIX) else null
    fun artisanOf(subjectId: String): String? = if (isGuest(subjectId)) null else subjectId
    fun guest(respondentId: String): String = GUEST_PREFIX + respondentId
    fun newLocalGuest(): String = LOCAL_GUEST_PREFIX + java.util.UUID.randomUUID().toString()

    fun of(artisanId: String?, respondentId: String?): String =
        artisanId?.ifBlank { null } ?: respondentId?.ifBlank { null }?.let(::guest).orEmpty()

    fun artisans(subjectIds: Collection<String>): List<String> = subjectIds.filterNot(::isGuest)
}

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
    const val ANSWER_LOCKED = "ANSWER_LOCKED"
    const val RESPONDENT_HAS_ROWS = "RESPONDENT_HAS_ROWS"
    const val FORM_DELETED = "FORM_DELETED"
    const val BUNDLE_VERSION = "BUNDLE_VERSION"

    val ALL = listOf(
        VALIDATION_FAILED, NOT_FOUND, FORBIDDEN, STALE_VERSION, INSTRUMENT_LOCKED, PARTICIPANT_HAS_TAKES,
        HAS_RECORDINGS, TITLE_IS_GENERATED, TARGET_OUTSIDE_INSTRUMENT, JOIN_CODE_UNKNOWN,
        MERGE_INSTRUMENT_MISMATCH, SESSION_UNKNOWN, TRANSCRIPTION_PAUSED, CONSENT_REFUSED,
        CONSENT_NOT_RECORDED, APPROVE_BLOCKED, ANSWER_LOCKED, RESPONDENT_HAS_ROWS, FORM_DELETED, BUNDLE_VERSION
    )
}

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
    val minAppVersionForCreate: String? = null,
    val minAndroidVersionCodeForCreate: Int? = null,
    val minWebVersionCodeForCreate: Int? = null,
    val portal: String? = null
) {
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
    val reason: String? = null,
    val retry: Boolean = false
)

@Serializable
data class QErrorBody(val detail: QErrorDetail? = null)

@Serializable
data class QArtisanRef(val artisanId: String)

@Serializable
data class QResearcherRef(val userId: String, @EncodeDefault val role: String = "CONTRIBUTOR")

@Serializable
data class QSessionLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double? = null,
    val altitude: Double? = null,
    val placeName: String? = null
)

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
    @EncodeDefault val researchers: List<QResearcherRef> = emptyList(),
    val respondents: List<QRespondentInput> = emptyList(),
    @EncodeDefault val source: String = SessionSource.APP.wire,
    val location: QSessionLocation? = null
)

@Serializable
data class QRespondentInput(
    val name: String? = null,
    @EncodeDefault val kind: String = RespondentKind.GUEST.wire,
    val notes: String? = null
)

@Serializable
data class QRespondentsAdd(val respondents: List<QRespondentInput>)

@Serializable
data class QRespondentPatch(val name: String? = null, val notes: String? = null)

@Serializable
data class QRespondentRegister(val artisanId: String)

@Serializable
data class QRespondent(
    val id: String,
    val sessionId: String? = null,
    val name: String = "",
    val kind: String = RespondentKind.GUEST.wire,
    val notes: String? = null,
    val userId: String? = null,
    val artisanId: String? = null,
    val subjectKey: String? = null,
    val registeredAt: String? = null
) {
    val subjectId: String get() = QSubjects.guest(id)
}

@Serializable
data class QRespondentsResponse(val respondents: List<QRespondent> = emptyList())

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
    val purpose: String? = null,
    val location: QSessionLocation? = null,
    val clearLocation: Boolean? = null
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
    val location: QSessionLocation? = null,
    val languageCode: String? = null,
    val languageOther: String? = null,
    val joinCode: String? = null,
    val joinQr: String? = null,
    val participants: List<QParticipant> = emptyList(),
    val researchers: List<QResearcher> = emptyList(),
    val status: String = "PENDING",
    val submittedAt: String? = null,
    val reviewReasons: List<String> = emptyList(),
    val counts: QSessionCounts = QSessionCounts(),
    val source: String = SessionSource.APP.wire,
    val deletedAt: String? = null,
    val respondents: List<QRespondent> = emptyList(),
    val chips: QChips? = null
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
    val location: QSessionLocation? = null,
    val languageCode: String? = null,
    val joinCode: String? = null,
    val joinQr: String? = null,
    val participants: List<QParticipant> = emptyList(),
    val researchers: List<QResearcher> = emptyList(),
    val status: String = "PENDING",
    val counts: QSessionCounts = QSessionCounts(),
    val recordings: List<QRecording> = emptyList(),
    val answers: List<QAnswer> = emptyList(),
    val grid: JsonElement? = null,
    val designWorkshopId: String? = null,
    val languageOther: String? = null,
    val startedAt: String? = null,
    val closedAt: String? = null,
    val submittedAt: String? = null,
    val reviewReasons: List<String> = emptyList(),
    val source: String = SessionSource.APP.wire,
    val deletedAt: String? = null,
    val respondents: List<QRespondent> = emptyList()
) {
    val subjectIds: List<String> get() =
        participants.map { it.artisanId } + respondents.filter { it.artisanId == null }.map { it.subjectId }

    fun nameOf(subjectId: String): String? =
        participants.firstOrNull { it.artisanId == subjectId }?.name?.ifBlank { null }
            ?: respondents.firstOrNull { it.subjectId == subjectId }?.name?.ifBlank { null }
}

@Serializable
data class QSessionList(val items: List<QSession> = emptyList(), val nextCursor: String? = null, val capped: Boolean = false)

@Serializable
data class QOpenMatch(val items: List<QSession> = emptyList())

@Serializable
data class QJoinRequest(val joinCode: String)

@Serializable
data class QParticipantPatch(val joinedAt: String? = null, val leftAt: String? = null)

@Serializable
data class QTarget(
    val targetKey: String = "",
    val sectionId: String? = null,
    val questionLineageId: String? = null,
    val askedQuestionId: String? = null,
    val displayCode: String? = null,
    val label: String? = null
)

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
data class QTranscript(
    val status: String = "QUEUED",
    val paused: Boolean = true,
    val text: String? = null,
    val language: String? = null,
    val preview: com.fieldrepository.app.transcript.data.TranscriptPreviewDto? = null,
) {
    fun shownPreview(): com.fieldrepository.app.transcript.data.TranscriptPreviewDto? =
        preview?.takeIf { it.lines.isNotEmpty() } ?: text?.let(com.fieldrepository.app.transcript.data.TranscriptPreview::fromMarkdown)
}

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
    val consent: QConsentVerdict? = null,
    val respondents: List<QRecordingRespondent> = emptyList(),
    val chips: QChips? = null
) {
    val allSubjects: List<QSubject> get() =
        subjects + respondents.map { QSubject(QSubjects.guest(it.respondentId), it.role, it.named) }
}

@Serializable
data class QRecordingRespondent(
    val respondentId: String,
    val name: String = "",
    val role: String = SubjectRole.ANSWERED.wire,
    val named: Boolean = false,
    val subjectKey: String? = null
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
    val clockOffsetMeasuredAt: String? = null,
    val continuationOfId: String? = null
)

@Serializable
data class QPresignResponse(
    val objectKey: String,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: String? = null,
    val closed: Boolean = false
)

@Serializable
data class QPartDone(val partNumber: Int, val etag: String)

@Serializable
data class QMultipartCreateResponse(
    val objectKey: String,
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
    val questionLineageId: String? = null,
    val respondentId: String? = null,
    val note: String? = null,
    val richText: String? = null,
    val actorId: String? = null,
    val actorName: String? = null,
    val actor: QUserRef? = null
) {
    val subjectId: String get() = QSubjects.of(artisanId, respondentId)
    val authorId: String? get() = actorId ?: actor?.id?.ifBlank { null }
    val authorName: String? get() = actorName?.ifBlank { null } ?: actor?.name?.ifBlank { null }
}

@Serializable
data class QAnswerItem(
    val artisanId: String,
    val questionLineageId: String,
    val text: String,
    @EncodeDefault val origin: String = "TYPED",
    @EncodeDefault val attribution: String = "INDIVIDUAL",
    val sessionId: String,
    val sourceRecordingId: String? = null,
    val baseVersion: Int? = null,
    val note: String? = null,
    val richText: String? = null
)

@Serializable
data class QAnswersPut(val items: List<QAnswerItem>) {
    fun wire(): QAnswersPutWire = QAnswersPutWire(items.map { it.wire() })
}

fun QAnswerItem.wire(): QAnswerItemWire = QAnswerItemWire(
    artisanId = QSubjects.artisanOf(artisanId), respondentId = QSubjects.respondentOf(artisanId),
    questionLineageId = questionLineageId, text = text, origin = origin, attribution = attribution, sessionId = sessionId,
    sourceRecordingId = sourceRecordingId, baseVersion = baseVersion, note = note, richText = richText
)

@Serializable
data class QAnswerItemWire(
    val artisanId: String? = null,
    val respondentId: String? = null,
    val questionLineageId: String,
    val text: String,
    @EncodeDefault val origin: String = "TYPED",
    @EncodeDefault val attribution: String = "INDIVIDUAL",
    val sessionId: String,
    val sourceRecordingId: String? = null,
    val baseVersion: Int? = null,
    val note: String? = null,
    val richText: String? = null
)

@Serializable
data class QAnswersPutWire(val items: List<QAnswerItemWire>)

@Serializable
data class QAnswerResult(
    val artisanId: String = "",
    val questionLineageId: String,
    val status: String,
    val answer: QAnswer? = null,
    val current: QAnswer? = null,
    val respondentId: String? = null,
    val code: String? = null
) {
    fun normalised(): QAnswerResult = if (artisanId.isBlank() && respondentId != null) copy(artisanId = QSubjects.guest(respondentId)) else this
    val locked: Boolean get() = status == "FORBIDDEN" && code == QErrorCodes.ANSWER_LOCKED
}

@Serializable
data class QAnswersPutResponse(val results: List<QAnswerResult> = emptyList()) {
    fun normalised(): QAnswersPutResponse = copy(results = results.map { it.normalised() })
}

@Serializable
data class QRevision(
    val id: String,
    val text: String = "",
    val origin: String? = null,
    val attribution: String? = null,
    val sessionId: String? = null,
    val sourceRecordingId: String? = null,
    val status: String? = null,
    val actor: QUserRef? = null,
    val createdAt: String? = null,
    val current: Boolean = false,
    val note: String? = null,
    val richText: String? = null
)

@Serializable
data class QRevisions(val answerId: String = "", val revisions: List<QRevision> = emptyList())

@Serializable
data class QRecordingEvent(
    val id: String,
    val kind: String = "",
    val before: JsonElement? = null,
    val after: JsonElement? = null,
    val actor: QUserRef? = null,
    val at: String? = null
)

@Serializable
data class QRecordingEvents(val recordingId: String = "", val events: List<QRecordingEvent> = emptyList())

@Serializable
data class QRecordingList(val items: List<QRecording> = emptyList())

@Serializable
data class QRecordingPage(val items: List<QRecording> = emptyList(), val nextCursor: String? = null)

@Serializable
data class QFilterOption(
    val id: String = "",
    val label: String = "",
    val count: Int = 0,
    val kind: String? = null,
    val type: String? = null,
    val code: String? = null,
    val title: String? = null,
    val displayCode: String? = null,
    val sectionId: String? = null,
    val questionnaireId: String? = null
)

@Serializable
data class QFilterOptions(
    val questionnaires: List<QFilterOption> = emptyList(),
    val sections: List<QFilterOption> = emptyList(),
    val questions: List<QFilterOption> = emptyList(),
    val artisans: List<QFilterOption> = emptyList(),
    val researchers: List<QFilterOption> = emptyList(),
    val workshops: List<QFilterOption> = emptyList(),
    val languages: List<QFilterOption> = emptyList(),
    val kinds: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val transcript: List<String> = emptyList()
)

@Serializable
data class QBoundForm(val questionnaireId: String, val isPrimary: Boolean = false, val kind: String? = null)

@Serializable
data class QBoundByKind(val kind: String? = null, val questionnaireIds: List<String> = emptyList())

@Serializable
data class QFormFor(
    val questionnaireId: String? = null,
    val reason: String? = null,
    val bound: List<QBoundForm> = emptyList(),
    val boundByKind: List<QBoundByKind> = emptyList()
)

@Serializable
data class QFormBinding(val workshopId: String? = null, val designWorkshopId: String? = null, val isPrimary: Boolean = false)

@Serializable
data class QFormBindings(val questionnaireId: String = "", val bindings: List<QFormBinding> = emptyList())

@Serializable
data class QGridCell(
    val artisanId: String? = null,
    val respondentId: String? = null,
    val subjectKey: String? = null,
    val state: String = CoverageState.NOT_RECORDED.wire,
    val takes: Int = 0,
    val covered: Int = 0,
    val total: Int = 0,
    val needsDecision: Boolean = false,
    val override: String? = null,
    val source: String? = null,
    val recordedCount: Int = 0,
    val typedOnlyCount: Int = 0,
    val addRecording: Boolean = false,
    val recordings: List<QCoverageRecording> = emptyList()
) {
    val subjectId: String get() = QSubjects.of(artisanId, respondentId)
}

@Serializable
data class QCoverageRecording(
    val recordingId: String,
    val mediaFileId: String? = null,
    val sessionId: String? = null,
    val capturedAt: String? = null,
    val durationMs: Long? = null,
    val recordedBy: QUserRef? = null,
    val needsDecision: Boolean = false
)

@Serializable
data class QGridSection(val sectionId: String, val code: String = "", val title: String = "", val cells: List<QGridCell> = emptyList())

@Serializable
data class QGridPerson(val artisanId: String? = null, val respondentId: String? = null, val name: String = "") {
    val subjectId: String get() = QSubjects.of(artisanId, respondentId)
}

@Serializable
data class QGrid(
    val questionnaireId: String = "",
    val sections: List<QGridSection> = emptyList(),
    val artisans: List<QGridPerson> = emptyList(),
    val respondents: List<QGridPerson> = emptyList()
) {
    fun cell(sectionId: String, subjectId: String): QGridCell? =
        sections.firstOrNull { it.sectionId == sectionId }?.cells?.firstOrNull { it.subjectId == subjectId }

    val names: Map<String, String> get() = (artisans + respondents).associate { it.subjectId to it.name }
}

@Serializable
data class QCompleteSubjectWire(
    val artisanId: String? = null,
    val respondentId: String? = null,
    @EncodeDefault val role: String = SubjectRole.ANSWERED.wire,
    @EncodeDefault val named: Boolean = false,
    val repeatReason: String? = null
)

@Serializable
data class QCompleteRecordingWire(
    @EncodeDefault val targets: List<QTargetRef> = emptyList(),
    @EncodeDefault val subjects: List<QCompleteSubjectWire> = emptyList(),
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
data class QCompleteRequestWire(
    val objectKey: String,
    val clientClipKey: String,
    @EncodeDefault val linkedRecordType: String = "questionnaire_session",
    val linkedRecordId: String,
    val recording: QCompleteRecordingWire
)

fun QCompleteRequest.wire(): QCompleteRequestWire = QCompleteRequestWire(
    objectKey = objectKey, clientClipKey = clientClipKey, linkedRecordType = linkedRecordType, linkedRecordId = linkedRecordId,
    recording = QCompleteRecordingWire(
        targets = recording.targets,
        subjects = recording.subjects.map {
            QCompleteSubjectWire(QSubjects.artisanOf(it.artisanId), QSubjects.respondentOf(it.artisanId), it.role, it.named, it.repeatReason)
        },
        deviceCapturedAt = recording.deviceCapturedAt, clockOffsetMs = recording.clockOffsetMs,
        clockOffsetMeasuredAt = recording.clockOffsetMeasuredAt, capturedAtSource = recording.capturedAtSource,
        durationMs = recording.durationMs, rosterCount = recording.rosterCount, contentSha256 = recording.contentSha256,
        continuationOfId = recording.continuationOfId, replacesRecordingId = recording.replacesRecordingId,
        ackedRecordingIds = recording.ackedRecordingIds
    )
)

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
    fun rosterOf(recordingId: String?): Int? = recordingId?.let { id ->
        sessions.asSequence().flatMap { it.recordings.asSequence() + it.uncertain.asSequence() }.firstOrNull { it.id == id }?.rosterCount
    }
}

@Serializable
data class QArtisanRecord(val artisanId: String, val instruments: List<QRecordInstrument> = emptyList())

@Serializable
data class QContinueRequest(
    val fromSessionId: String,
    val artisanIds: List<String>,
    @EncodeDefault val split: String = "ONE",
    val sessionDate: String,
    val clientKey: String? = null
) {
    companion object {
        fun of(fromSessionId: String, artisanIds: List<String>, split: String, sessionDate: String, clientKey: String? = null) =
            QContinueRequest(fromSessionId, artisanIds, if (artisanIds.size > 1) split else "ONE", sessionDate, clientKey)
    }
}

@Serializable
data class QFileMediaRequest(
    val sessionId: String,
    @EncodeDefault val targets: List<QTargetRef> = emptyList(),
    val subjects: List<QSubjectPatch>? = null
)

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

@Serializable
data class QFormQuestion(
    val id: String,
    val prompt: String = "",
    val helpText: String? = null,
    val isRequired: Boolean = false,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val retiredAt: String? = null,
    val lineageId: String? = null,
    val displayCode: String? = null,
    val artisanField: String? = null
)

@Serializable
data class QFormSection(
    val id: String,
    val code: String = "",
    val title: String = "",
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val questions: List<QFormQuestion> = emptyList()
)

@Serializable
data class QFormDetail(
    val id: String,
    val title: String = "",
    val kind: String? = null,
    val ownerId: String? = null,
    val isShared: Boolean = false,
    val isActive: Boolean = true,
    val sections: List<QFormSection> = emptyList()
)

@Serializable
data class QFormOption(
    val id: String,
    val title: String = "",
    val version: Int = 1,
    val designWorkshopId: String? = null,
    val isShared: Boolean = false,
    val kind: String? = null,
    val kindLabel: String = "",
    val isDefault: Boolean = false,
    val sectionCount: Int? = null
)

@Serializable
data class QWorkshopOption(
    val id: String,
    val title: String,
    val code: String? = null,
    val place: String? = null,
    val district: String? = null,
    val state: String? = null,
    val craft: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class QArtisanOption(
    val id: String,
    val name: String,
    val place: String = "",
    val craft: String? = null
)

@Serializable
data class QDirectoryUser(val id: String, val name: String = "", val role: String? = null, val email: String? = null)

@Serializable
data class QReviewAction(val notes: String? = null)
