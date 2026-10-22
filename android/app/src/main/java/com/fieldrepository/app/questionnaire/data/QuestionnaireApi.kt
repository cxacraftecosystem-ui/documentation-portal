package com.fieldrepository.app.questionnaire.data

import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import okhttp3.ResponseBody

/**
 * The typed questionnaire v2 client (BUILD_PLAN §1). Built on [ApiClient.retrofit], so it shares the
 * gateway retry, the session header and the clock headers ([ClientClock]).
 *
 * Every POST that creates takes an `Idempotency-Key`; the outbox mints it once when the step is queued
 * and sends the same key on every retry. Presign is deliberately not keyed (its URL expires); it is
 * idempotent through `clientClipKey`.
 */
interface QuestionnaireApi {

    @GET("questionnaire/config")
    suspend fun config(): QConfig

    // ── Sessions ─────────────────────────────────────────────────────────────────────────────

    @GET("questionnaire/sessions/open-match")
    suspend fun openMatch(
        @Query("questionnaireId") questionnaireId: String,
        @Query("workshopId") workshopId: String?,
        @Query("artisanIds") artisanIds: String,
        @Query("date") date: String
    ): QOpenMatch

    @POST("questionnaire/sessions")
    suspend fun createSession(@Header("Idempotency-Key") idempotencyKey: String, @Body body: QSessionCreate): QSession

    /**
     * One page; `nextCursor` asks for the next (audit M23: the server's parameter is `search`, never
     * `q`). [deleted] lists the soft-deleted sessions instead (content admins; 403 otherwise).
     */
    @GET("questionnaire/sessions")
    suspend fun sessions(
        @Query("search") search: String? = null,
        @Query("workshopId") workshopId: String? = null,
        @Query("questionnaireId") questionnaireId: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("researcherId") researcherId: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("deleted") deleted: Boolean? = null
    ): QSessionList

    @GET("questionnaire/sessions/{id}")
    suspend fun session(@Path("id") id: String): QSessionDetail

    /** The same response read as the list's session shape, to open it from the inbox. */
    @GET("questionnaire/sessions/{id}")
    suspend fun sessionSummary(@Path("id") id: String): QSession

    @PATCH("questionnaire/sessions/{id}")
    suspend fun patchSession(@Path("id") id: String, @Body body: QSessionPatch): QSession

    @POST("questionnaire/sessions/{id}/participants")
    suspend fun addParticipant(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("id") id: String,
        @Body body: QArtisanRef
    ): QSession

    @PATCH("questionnaire/sessions/{id}/participants/{artisanId}")
    suspend fun patchParticipant(@Path("id") id: String, @Path("artisanId") artisanId: String, @Body body: QParticipantPatch): QSession

    @DELETE("questionnaire/sessions/{id}/participants/{artisanId}")
    suspend fun removeParticipant(@Path("id") id: String, @Path("artisanId") artisanId: String, @Query("detach") detach: Boolean? = null): QSession

    @POST("questionnaire/sessions/join")
    suspend fun join(@Body body: QJoinRequest): QSession

    @POST("questionnaire/sessions/{id}/finish")
    suspend fun finish(@Path("id") id: String): QSession

    /** "Add researcher" (B6): a colleague, or a volunteer who may record but not create. */
    @POST("questionnaire/sessions/{id}/researchers")
    suspend fun addResearcher(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("id") id: String,
        @Body body: QResearcherRef
    ): QSession

    /** The people "Add researcher" can pick from. */
    @GET("users/directory")
    suspend fun userDirectory(): List<QDirectoryUser>

    /** Content admins: a soft-deleted session comes back whole (owner decision 3). */
    @POST("questionnaire/sessions/{id}/restore")
    suspend fun restoreSession(@Path("id") id: String): QSession

    // ── Guests (UNIFY U6, A17, A18) ──────────────────────────────────────────────────────────

    @POST("questionnaire/sessions/{id}/respondents")
    suspend fun addRespondents(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("id") id: String,
        @Body body: QRespondentsAdd
    ): QRespondentsResponse

    /** A17: edit a guest's name and notes; an empty name retracts it. */
    @PATCH("questionnaire/sessions/{id}/respondents/{respondentId}")
    suspend fun patchRespondent(@Path("id") id: String, @Path("respondentId") respondentId: String, @Body body: QRespondentPatch): QRespondent

    @DELETE("questionnaire/sessions/{id}/respondents/{respondentId}")
    suspend fun removeRespondent(@Path("id") id: String, @Path("respondentId") respondentId: String): Response<Unit>

    /** Register a guest as an artisan: their answers and takes move to that artisan (one transaction). */
    @POST("questionnaire/respondents/{respondentId}/register")
    suspend fun registerRespondent(@Path("respondentId") respondentId: String, @Body body: QRespondentRegister): JsonObject

    // ── The start sheet's form (binding resolution, UNIFY U5 and owner amendment 2) ──────────

    @GET("questionnaire/form-for")
    suspend fun formFor(
        @Query("workshopId") workshopId: String? = null,
        @Query("designWorkshopId") designWorkshopId: String? = null,
        @Query("questionnaireId") questionnaireId: String? = null
    ): QFormFor

    @GET("questionnaire/forms/{id}/bindings")
    suspend fun formBindings(@Path("id") questionnaireId: String): QFormBindings

    /** A form with its sections and questions, for the session workspace (the forms library's route). */
    @GET("questionnaires/{id}")
    suspend fun form(@Path("id") questionnaireId: String): QFormDetail

    /** Every form this account may start a session on (DR's `/questionnaires/options`). */
    @GET("questionnaires/options")
    suspend fun formOptions(@Query("designWorkshopId") designWorkshopId: String? = null): List<QFormOption>

    @DELETE("questionnaire/sessions/{id}")
    suspend fun deleteSession(@Path("id") id: String): Response<Unit>

    /** Content admins only (owner, 2026-10-21). The source session is hidden and points at [targetId]. */
    @POST("questionnaire/sessions/{id}/merge-into/{targetId}")
    suspend fun mergeInto(@Path("id") id: String, @Path("targetId") targetId: String): QSession

    /** "Continue with…" another day: the server opens the session(s) and says each one's first gap. */
    @POST("questionnaire/sessions/continue")
    suspend fun continueSessions(@Header("Idempotency-Key") idempotencyKey: String, @Body body: QContinueRequest): QContinueResponse

    // ── Coverage ─────────────────────────────────────────────────────────────────────────────

    @POST("questionnaire/coverage/check")
    suspend fun coverageCheck(@Body body: QCoverageCheckRequest): QCoverageCheckResponse

    /** The workshop coverage page (B12): artisan x section, with the overrides and the source of each cell. */
    @GET("questionnaire/coverage")
    suspend fun coverageGrid(
        @Query("questionnaireId") questionnaireId: String,
        @Query("workshopId") workshopId: String? = null,
        @Query("designWorkshopId") designWorkshopId: String? = null,
        @Query("artisanIds") artisanIds: String? = null
    ): QGrid

    @GET("questionnaire/coverage/snapshot")
    suspend fun snapshot(
        @Query("questionnaireId") questionnaireId: String,
        @Query("workshopId") workshopId: String?,
        @Query("sinceSeq") sinceSeq: String?
    ): QSnapshot

    // ── Recordings ───────────────────────────────────────────────────────────────────────────

    @DELETE("questionnaire/recordings/{id}")
    suspend fun removeRecording(@Path("id") id: String): QRecording

    @POST("questionnaire/recordings/{id}/restore")
    suspend fun restoreRecording(@Path("id") id: String): QRecording

    /** Professor and above: this take becomes [QMakePrimaryRequest.artisanId]'s main take of the target. */
    @POST("questionnaire/recordings/{id}/make-primary")
    suspend fun makePrimary(@Path("id") id: String, @Body body: QMakePrimaryRequest): QRecording

    /** Drafts from the take's finished transcript; never an answer, never a new transcription. */
    @POST("questionnaire/recordings/{id}/answer-proposals")
    suspend fun answerProposals(@Path("id") id: String): QProposals

    /** Refile a landed take: who answered, which target. Optimistic on `version` (409 STALE_VERSION). */
    @PATCH("questionnaire/recordings/{id}")
    suspend fun patchRecording(@Path("id") id: String, @Body body: QRecordingPatch): QRecording

    /** "File it" for a take the server could not place: its targets, who answered, its session. */
    @POST("questionnaire/recordings/{id}/file")
    suspend fun fileRecording(@Path("id") id: String, @Body body: QFileRecordingRequest): QRecording

    /** "File it" for an inbox "To file" clip, which has no recording yet: it becomes one in the session. */
    @POST("questionnaire/media/{id}/file")
    suspend fun fileMedia(@Path("id") mediaId: String, @Body body: QFileMediaRequest): QRecording

    /** "Not needed" on an inbox "To file" clip, kept on the server so every device agrees; deletes nothing. */
    @POST("questionnaire/media/{id}/not-needed")
    suspend fun mediaNotNeeded(@Path("id") mediaId: String): JsonObject

    /** An inbox "Needs a decision" row: keep both takes, or keep this one. */
    @POST("questionnaire/recordings/{id}/decide")
    suspend fun decide(@Path("id") id: String, @Body body: QDecideRequest): QRecording

    /** "Removed takes" of one session or one artisan, newest first (B40). */
    @GET("questionnaire/recordings/removed")
    suspend fun removedRecordings(@Query("sessionId") sessionId: String? = null, @Query("artisanId") artisanId: String? = null): QRecordingList

    /** A take's history, oldest first: moves, refilings, decisions, removals, restores (B40). */
    @GET("questionnaire/recordings/{id}/events")
    suspend fun recordingEvents(@Path("id") id: String): QRecordingEvents

    /** While transcription is paused this answers 202 `{status: QUEUED, paused: true}`; the UI never offers it then. */
    @POST("questionnaire/recordings/{id}/transcribe")
    suspend fun transcribe(@Path("id") id: String): QTranscript

    // ── Media ────────────────────────────────────────────────────────────────────────────────

    @POST("media/presign")
    suspend fun presign(@Body body: QPresignRequest): QPresignResponse

    @POST("media/multipart/create")
    suspend fun multipartCreate(@Body body: QPresignRequest): QMultipartCreateResponse

    @POST("media/multipart/presign-parts")
    suspend fun multipartPresignParts(@Body body: QMultipartPresignParts): QMultipartPresignPartsResponse

    @POST("media/multipart/complete")
    suspend fun multipartComplete(@Body body: QMultipartComplete): JsonObject

    @GET("media/uploads/{clientClipKey}")
    suspend fun uploadState(@Path("clientClipKey") clientClipKey: String): Response<QUploadState>

    /** [body] carries subjects split into artisans and guests ([QCompleteRequest.wire]). */
    @POST("media/complete")
    suspend fun complete(@Header("Idempotency-Key") idempotencyKey: String, @Body body: QCompleteRequestWire): QRecording

    // ── Answers, consent, inbox, record ──────────────────────────────────────────────────────

    /** Per item, never as a whole; a locked item comes back FORBIDDEN with `ANSWER_LOCKED` (U9). */
    @PUT("questionnaire/answers")
    suspend fun putAnswers(@Body body: QAnswersPutWire): QAnswersPutResponse

    /** History (B21): every revision of one answer, newest first. */
    @GET("questionnaire/answers/{id}/revisions")
    suspend fun answerRevisions(@Path("id") answerId: String): QRevisions

    @GET("artisans/{id}/consent")
    suspend fun consent(@Path("id") artisanId: String): QConsentGet

    @POST("artisans/{id}/consent")
    suspend fun recordConsent(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("id") artisanId: String,
        @Body body: QConsentPost
    ): QConsentGet

    @GET("questionnaire/inbox")
    suspend fun inbox(): QInbox

    /** Read view and review (B27): approving cascades to the session's live takes and answers. */
    @POST("review/questionnaire/{id}/approve")
    suspend fun approveSession(@Path("id") sessionId: String, @Body body: QReviewAction): JsonObject

    @POST("review/questionnaire/{id}/revise")
    suspend fun returnSession(@Path("id") sessionId: String, @Body body: QReviewAction): JsonObject

    @GET("artisans/{id}/record")
    suspend fun artisanRecord(@Path("id") artisanId: String, @Query("questionnaireId") questionnaireId: String? = null): QArtisanRecord

    /** The record as CSV; the server names the file (Content-Disposition), the app never does. */
    @Streaming
    @GET("artisans/{id}/record.csv")
    suspend fun artisanRecordCsv(@Path("id") artisanId: String, @Query("questionnaireId") questionnaireId: String? = null): Response<ResponseBody>
}
