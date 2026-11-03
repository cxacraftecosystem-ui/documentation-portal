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
import retrofit2.http.QueryMap
import retrofit2.http.Streaming
import okhttp3.ResponseBody

interface QuestionnaireApi {

    @GET("questionnaire/config")
    suspend fun config(): QConfig

    @GET("questionnaire/sessions/open-match")
    suspend fun openMatch(
        @Query("questionnaireId") questionnaireId: String,
        @Query("workshopId") workshopId: String?,
        @Query("artisanIds") artisanIds: String,
        @Query("date") date: String
    ): QOpenMatch

    @POST("questionnaire/sessions")
    suspend fun createSession(@Header("Idempotency-Key") idempotencyKey: String, @Body body: QSessionCreate): QSession

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

    @GET("questionnaire/sessions")
    suspend fun sessionsFiltered(
        @QueryMap(encoded = true) filters: Map<String, String>,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null
    ): QSessionList

    @GET("questionnaire/recordings")
    suspend fun recordings(
        @QueryMap(encoded = true) filters: Map<String, String>,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("sessionId") sessionId: String? = null
    ): QRecordingPage

    @GET("questionnaire/filters")
    suspend fun filterOptions(
        @Query("field") field: String? = null,
        @Query("q") q: String? = null,
        @Query("questionnaireId") questionnaireId: String? = null,
        @Query("limit") limit: Int? = null
    ): QFilterOptions

    @GET("questionnaire/sessions/{id}")
    suspend fun session(@Path("id") id: String): QSessionDetail

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

    @POST("questionnaire/sessions/{id}/researchers")
    suspend fun addResearcher(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("id") id: String,
        @Body body: QResearcherRef
    ): QSession

    @GET("users/directory")
    suspend fun userDirectory(): List<QDirectoryUser>

    @POST("questionnaire/sessions/{id}/restore")
    suspend fun restoreSession(@Path("id") id: String): QSession

    @POST("questionnaire/sessions/{id}/respondents")
    suspend fun addRespondents(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("id") id: String,
        @Body body: QRespondentsAdd
    ): QRespondentsResponse

    @PATCH("questionnaire/sessions/{id}/respondents/{respondentId}")
    suspend fun patchRespondent(@Path("id") id: String, @Path("respondentId") respondentId: String, @Body body: QRespondentPatch): QRespondent

    @DELETE("questionnaire/sessions/{id}/respondents/{respondentId}")
    suspend fun removeRespondent(@Path("id") id: String, @Path("respondentId") respondentId: String): Response<Unit>

    @POST("questionnaire/respondents/{respondentId}/register")
    suspend fun registerRespondent(@Path("respondentId") respondentId: String, @Body body: QRespondentRegister): JsonObject

    @GET("questionnaire/form-for")
    suspend fun formFor(
        @Query("workshopId") workshopId: String? = null,
        @Query("designWorkshopId") designWorkshopId: String? = null,
        @Query("questionnaireId") questionnaireId: String? = null
    ): QFormFor

    @GET("questionnaire/forms/{id}/bindings")
    suspend fun formBindings(@Path("id") questionnaireId: String): QFormBindings

    @GET("questionnaires/{id}")
    suspend fun form(@Path("id") questionnaireId: String): QFormDetail

    @GET("questionnaires/options")
    suspend fun formOptions(@Query("designWorkshopId") designWorkshopId: String? = null): List<QFormOption>

    @DELETE("questionnaire/sessions/{id}")
    suspend fun deleteSession(@Path("id") id: String): Response<Unit>

    @POST("questionnaire/sessions/{id}/merge-into/{targetId}")
    suspend fun mergeInto(@Path("id") id: String, @Path("targetId") targetId: String): QSession

    @POST("questionnaire/sessions/continue")
    suspend fun continueSessions(@Header("Idempotency-Key") idempotencyKey: String, @Body body: QContinueRequest): QContinueResponse

    @POST("questionnaire/coverage/check")
    suspend fun coverageCheck(@Body body: QCoverageCheckRequest): QCoverageCheckResponse

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

    @DELETE("questionnaire/recordings/{id}")
    suspend fun removeRecording(@Path("id") id: String): QRecording

    @POST("questionnaire/recordings/{id}/restore")
    suspend fun restoreRecording(@Path("id") id: String): QRecording

    @POST("questionnaire/recordings/{id}/make-primary")
    suspend fun makePrimary(@Path("id") id: String, @Body body: QMakePrimaryRequest): QRecording

    @POST("questionnaire/recordings/{id}/answer-proposals")
    suspend fun answerProposals(@Path("id") id: String): QProposals

    @PATCH("questionnaire/recordings/{id}")
    suspend fun patchRecording(@Path("id") id: String, @Body body: QRecordingPatch): QRecording

    @POST("questionnaire/recordings/{id}/file")
    suspend fun fileRecording(@Path("id") id: String, @Body body: QFileRecordingRequest): QRecording

    @POST("questionnaire/media/{id}/file")
    suspend fun fileMedia(@Path("id") mediaId: String, @Body body: QFileMediaRequest): QRecording

    @POST("questionnaire/media/{id}/not-needed")
    suspend fun mediaNotNeeded(@Path("id") mediaId: String): JsonObject

    @POST("questionnaire/recordings/{id}/decide")
    suspend fun decide(@Path("id") id: String, @Body body: QDecideRequest): QRecording

    @GET("questionnaire/recordings/removed")
    suspend fun removedRecordings(@Query("sessionId") sessionId: String? = null, @Query("artisanId") artisanId: String? = null): QRecordingList

    @GET("questionnaire/recordings/{id}/events")
    suspend fun recordingEvents(@Path("id") id: String): QRecordingEvents

    @POST("questionnaire/recordings/{id}/transcribe")
    suspend fun transcribe(@Path("id") id: String): QTranscript

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
    suspend fun complete(@Header("Idempotency-Key") idempotencyKey: String, @Body body: QCompleteRequestWire): QRecording

    @PUT("questionnaire/answers")
    suspend fun putAnswers(@Body body: QAnswersPutWire): QAnswersPutResponse

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

    @POST("review/questionnaire/{id}/approve")
    suspend fun approveSession(@Path("id") sessionId: String, @Body body: QReviewAction): JsonObject

    @POST("review/questionnaire/{id}/revise")
    suspend fun returnSession(@Path("id") sessionId: String, @Body body: QReviewAction): JsonObject

    @GET("artisans/{id}/record")
    suspend fun artisanRecord(@Path("id") artisanId: String, @Query("questionnaireId") questionnaireId: String? = null): QArtisanRecord

    @Streaming
    @GET("artisans/{id}/record.csv")
    suspend fun artisanRecordCsv(@Path("id") artisanId: String, @Query("questionnaireId") questionnaireId: String? = null): Response<ResponseBody>
}
