package com.fieldrepository.app.data

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

    /** One page; `nextCursor` asks for the next (audit M23: the server's parameter is `search`, never `q`). */
    @GET("questionnaire/sessions")
    suspend fun sessions(
        @Query("search") search: String? = null,
        @Query("workshopId") workshopId: String? = null,
        @Query("questionnaireId") questionnaireId: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null
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

    /** An inbox "Needs a decision" row: keep both takes, or keep this one. */
    @POST("questionnaire/recordings/{id}/decide")
    suspend fun decide(@Path("id") id: String, @Body body: QDecideRequest): QRecording

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

    @POST("media/complete")
    suspend fun complete(@Header("Idempotency-Key") idempotencyKey: String, @Body body: QCompleteRequest): QRecording

    // ── Answers, consent, inbox, record ──────────────────────────────────────────────────────

    @PUT("questionnaire/answers")
    suspend fun putAnswers(@Body body: QAnswersPut): QAnswersPutResponse

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

    @GET("artisans/{id}/record")
    suspend fun artisanRecord(@Path("id") artisanId: String, @Query("questionnaireId") questionnaireId: String? = null): QArtisanRecord

    /** The record as CSV; the server names the file (Content-Disposition), the app never does. */
    @Streaming
    @GET("artisans/{id}/record.csv")
    suspend fun artisanRecordCsv(@Path("id") artisanId: String, @Query("questionnaireId") questionnaireId: String? = null): Response<ResponseBody>
}
