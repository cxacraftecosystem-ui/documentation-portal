@file:OptIn(ExperimentalSerializationApi::class)

package com.fieldrepository.app.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * The forms half of the unified questionnaire on Field Repository Android (UNIFY 4.1, stream U4):
 * the forms library, the builder, workbooks, bindings and defaults, plus the session routes the
 * workspace did not call yet (restore, researchers, guests, history, coverage, corpus).
 *
 * Every response field is defaulted, so a server one step ahead (or one step behind) still decodes.
 */

// ── Forms ────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class QForm(
    val id: String,
    val title: String = "",
    val description: String? = null,
    val isActive: Boolean = true,
    val isDefault: Boolean = false,
    val isShared: Boolean = false,
    val kind: String? = null,
    val sortOrder: Int = 1,
    val sectionCount: Int = 0,
    val questionCount: Int = 0,
    val workshopCount: Int = 0,
    val createdAt: String? = null,
    /** FR's author column; [ownerId] is the unified one. Either names the author. */
    val createdById: String? = null,
    val ownerId: String? = null,
    val deletedAt: String? = null,
    val version: Int = 0,
    val sections: List<QFormSection> = emptyList()
) {
    val authorId: String? get() = ownerId?.takeIf { it.isNotBlank() } ?: createdById?.takeIf { it.isNotBlank() }
    /** Deleted: soft-deleted (`deletedAt`) or, on FR's older routes, retired (`isActive = false`). */
    val deleted: Boolean get() = deletedAt != null || !isActive
}

@Serializable
data class QFormSection(
    val id: String,
    val code: String = "",
    val title: String = "",
    val sortOrder: Int = 1,
    val isActive: Boolean = true,
    val questions: List<QFormQuestion> = emptyList()
)

@Serializable
data class QFormQuestion(
    val id: String,
    val sectionId: String? = null,
    val sectionCode: String = "",
    val prompt: String = "",
    val helpText: String? = null,
    val isRequired: Boolean = false,
    val sortOrder: Int = 1,
    val isActive: Boolean = true,
    val artisanField: String? = null,
    val questionCode: String? = null,
    val lineageId: String? = null,
    val displayCode: String? = null,
    val supersededById: String? = null,
    val retiredAt: String? = null
)

@Serializable
data class QFormCreate(val title: String, val description: String? = null, val kind: String? = null)

@Serializable
data class QSectionCreate(val questionnaireId: String, val code: String, val title: String, val sortOrder: Int? = null)

@Serializable
data class QSectionPatch(val code: String? = null, val title: String? = null, val sortOrder: Int? = null)

@Serializable
data class QQuestionCreate(
    val sectionId: String,
    val prompt: String,
    val helpText: String? = null,
    @EncodeDefault val isRequired: Boolean = false,
    val sortOrder: Int? = null,
    val artisanField: String? = null
)

@Serializable
data class QQuestionPatch(val prompt: String? = null, val helpText: String? = null, val isRequired: Boolean? = null, val sortOrder: Int? = null)

@Serializable
data class QDefaultPut(@EncodeDefault val isDefault: Boolean = true)

@Serializable
data class QBinding(
    val workshopId: String? = null,
    val designWorkshopId: String? = null,
    @EncodeDefault val isPrimary: Boolean = false,
    val title: String? = null
)

@Serializable
data class QBindingsPut(val bindings: List<QBinding>)

@Serializable
data class QBindings(val questionnaireId: String = "", val bindings: List<QBinding> = emptyList())

@Serializable
data class QTypeDefaultPut(val questionnaireId: String?, val workshopType: String? = null)

@Serializable
data class QBoundForm(val questionnaireId: String = "", val isPrimary: Boolean = false, val kind: String? = null)

@Serializable
data class QFormFor(
    val questionnaireId: String? = null,
    /** Which step chose it: EXPLICIT, PRIMARY_BINDING, ONLY_BINDING, TYPE_DEFAULT, GLOBAL_DEFAULT or PICKER. */
    val reason: String? = null,
    val bound: List<QBoundForm> = emptyList()
)

@Serializable
data class QUploadReport(val questionnaire: QForm? = null, val report: JsonObject? = null)

// ── Sessions: restore, researchers, guests ───────────────────────────────────────────────────

@Serializable
data class QResearcherAdd(val userId: String, @EncodeDefault val role: String = "CONTRIBUTOR")

@Serializable
data class QRespondentInput(val name: String, @EncodeDefault val kind: String = "GUEST", val notes: String? = null)

@Serializable
data class QRespondentsAdd(val respondents: List<QRespondentInput>)

@Serializable
data class QRespondentPatch(val name: String? = null, val notes: String? = null)

@Serializable
data class QRespondent(
    val id: String = "",
    val sessionId: String? = null,
    val name: String = "",
    val kind: String = "GUEST",
    val notes: String? = null,
    val artisanId: String? = null,
    val subjectKey: String? = null
)

@Serializable
data class QRespondents(val respondents: List<QRespondent> = emptyList())

@Serializable
data class QRequiredUnanswered(val count: Int = 0, val bySubject: Map<String, Int> = emptyMap())

@Serializable
data class QFinishResult(val id: String = "", val requiredUnanswered: QRequiredUnanswered = QRequiredUnanswered())

// ── Answer history, recording history ────────────────────────────────────────────────────────

@Serializable
data class QRevision(
    val id: String = "",
    val text: String = "",
    val origin: String? = null,
    val attribution: String? = null,
    val sessionId: String? = null,
    val sourceRecordingId: String? = null,
    val status: String? = null,
    val actor: QUserRef = QUserRef(),
    val createdAt: String? = null,
    val current: Boolean = false,
    val note: String? = null
)

@Serializable
data class QRevisions(val answerId: String = "", val revisions: List<QRevision> = emptyList())

@Serializable
data class QRecordingEvent(
    val id: String = "",
    val kind: String = "",
    val at: String? = null,
    val actor: QUserRef? = null,
    val before: JsonElement? = null,
    val after: JsonElement? = null
)

@Serializable
data class QRecordingEvents(val recordingId: String = "", val events: List<QRecordingEvent> = emptyList())

@Serializable
data class QRemovedRecordings(val items: List<QRecording> = emptyList())

// ── Workshop coverage (B12, B13, D1, D4) ─────────────────────────────────────────────────────

@Serializable
data class QGridCell(
    val artisanId: String = "",
    val subjectKey: String? = null,
    val state: String = "NOT_RECORDED",
    val takes: Int = 0,
    val covered: Int = 0,
    val total: Int = 0,
    val needsDecision: Boolean = false,
    val override: String? = null,
    /** RECORDED, TYPED or null (amendment 2 point 4). */
    val source: String? = null,
    val recordedCount: Int = 0,
    val typedOnlyCount: Int = 0,
    val addRecording: Boolean = false
)

@Serializable
data class QGridSection(val sectionId: String = "", val code: String = "", val title: String = "", val cells: List<QGridCell> = emptyList())

@Serializable
data class QGridArtisan(val artisanId: String = "", val name: String = "")

@Serializable
data class QGrid(val questionnaireId: String = "", val artisans: List<QGridArtisan> = emptyList(), val sections: List<QGridSection> = emptyList())

@Serializable
data class QCompletionPut(val artisanId: String, val sectionId: String, val status: String?)

// ── Review ───────────────────────────────────────────────────────────────────────────────────

@Serializable
data class QReviewAction(val notes: String? = null, val reason: String? = null)

interface QuestionnaireFormsApi {
    @GET("questionnaires")
    suspend fun forms(
        @Query("activeOnly") activeOnly: Boolean = true,
        @Query("scope") scope: String? = null,
        @Query("kind") kind: String? = null
    ): List<QForm>

    @GET("questionnaires/{id}")
    suspend fun form(@Path("id") id: String): QForm

    @POST("questionnaires")
    suspend fun createForm(@Header("Idempotency-Key") idempotencyKey: String, @Body body: QFormCreate): QForm

    /** Title, description, kind (author or content admin); `isShared` (admin); `isActive: true` restores on FR's routes. */
    @PATCH("questionnaires/{id}")
    suspend fun patchForm(@Path("id") id: String, @Body body: JsonObject): QForm

    @DELETE("questionnaires/{id}")
    suspend fun deleteForm(@Path("id") id: String): Response<ResponseBody>

    @POST("questionnaires/{id}/restore")
    suspend fun restoreForm(@Path("id") id: String): QForm

    @PUT("questionnaires/{id}/default")
    suspend fun setDefault(@Path("id") id: String, @Body body: QDefaultPut): QForm

    @Streaming
    @GET("questionnaires/pro-forma")
    suspend fun proForma(): Response<ResponseBody>

    @Streaming
    @GET("questionnaires/{id}/xlsx")
    suspend fun workbook(@Path("id") id: String): Response<ResponseBody>

    @Streaming
    @GET("questionnaires/{id}/question-set.xlsx")
    suspend fun questionSet(@Path("id") id: String): Response<ResponseBody>

    @Multipart
    @POST("questionnaires/upload")
    suspend fun uploadWorkbook(@Part file: MultipartBody.Part): QUploadReport

    @Multipart
    @POST("questionnaires/{id}/upload")
    suspend fun reuploadWorkbook(@Path("id") id: String, @Part file: MultipartBody.Part): QUploadReport

    @POST("questionnaire/sections")
    suspend fun createSection(@Header("Idempotency-Key") idempotencyKey: String, @Body body: QSectionCreate): QFormSection

    @PATCH("questionnaire/sections/{id}")
    suspend fun patchSection(@Path("id") id: String, @Body body: QSectionPatch): QFormSection

    @DELETE("questionnaire/sections/{id}")
    suspend fun deleteSection(@Path("id") id: String): Response<ResponseBody>

    @POST("questionnaire/questions")
    suspend fun createQuestion(@Header("Idempotency-Key") idempotencyKey: String, @Body body: QQuestionCreate): QFormQuestion

    /** A reword after the first answer supersedes on the server; the client only sends the new words. */
    @PATCH("questionnaire/questions/{id}")
    suspend fun patchQuestion(@Path("id") id: String, @Body body: QQuestionPatch): QFormQuestion

    @DELETE("questionnaire/questions/{id}")
    suspend fun deleteQuestion(@Path("id") id: String): Response<ResponseBody>

    @GET("questionnaire/forms/{id}/bindings")
    suspend fun bindings(@Path("id") id: String): QBindings

    @PUT("questionnaire/forms/{id}/bindings")
    suspend fun putBindings(@Path("id") id: String, @Body body: QBindingsPut): QBindings

    @PUT("questionnaire/type-defaults")
    suspend fun putTypeDefault(@Body body: QTypeDefaultPut): JsonObject

    @GET("questionnaire/form-for")
    suspend fun formFor(@Query("workshopId") workshopId: String?, @Query("questionnaireId") questionnaireId: String? = null): QFormFor

    // Sessions

    @GET("questionnaire/sessions")
    suspend fun deletedSessions(@Query("deleted") deleted: Boolean = true, @Query("cursor") cursor: String? = null): QSessionList

    @POST("questionnaire/sessions/{id}/restore")
    suspend fun restoreSession(@Path("id") id: String): QSession

    @POST("questionnaire/sessions/{id}/finish")
    suspend fun finish(@Path("id") id: String): QFinishResult

    @POST("questionnaire/sessions/{id}/researchers")
    suspend fun addResearcher(@Path("id") id: String, @Body body: QResearcherAdd): QSession

    @POST("questionnaire/sessions/{id}/respondents")
    suspend fun addRespondents(@Path("id") id: String, @Body body: QRespondentsAdd): QRespondents

    @PATCH("questionnaire/sessions/{id}/respondents/{rid}")
    suspend fun patchRespondent(@Path("id") id: String, @Path("rid") respondentId: String, @Body body: QRespondentPatch): QRespondent

    @POST("questionnaire/respondents/{rid}/register")
    suspend fun registerRespondent(@Path("rid") respondentId: String, @Body body: QArtisanRef): JsonObject

    @GET("questionnaire/sessions/{id}")
    suspend fun sessionRespondents(@Path("id") id: String): QRespondents

    // History

    @GET("questionnaire/answers/{id}/revisions")
    suspend fun revisions(@Path("id") answerId: String): QRevisions

    @GET("questionnaire/recordings/{id}/events")
    suspend fun recordingEvents(@Path("id") recordingId: String): QRecordingEvents

    @GET("questionnaire/recordings/removed")
    suspend fun removedRecordings(@Query("sessionId") sessionId: String): QRemovedRecordings

    // Coverage

    @GET("questionnaire/coverage")
    suspend fun coverage(@Query("questionnaireId") questionnaireId: String, @Query("workshopId") workshopId: String): QGrid

    /** Content admins: Marked complete (COMPLETED), Needs review, Needs redo, or null to clear. */
    @PUT("questionnaire/completion")
    suspend fun setCompletion(@Body body: QCompletionPut): JsonObject

    // Review and corpus

    @POST("review/questionnaire/{id}/approve")
    suspend fun approveSession(@Header("Idempotency-Key") idempotencyKey: String, @Path("id") sessionId: String, @Body body: QReviewAction = QReviewAction()): JsonObject

    @Streaming
    @GET("questionnaire/corpus.csv")
    suspend fun corpusCsv(
        @Query("questionnaireId") questionnaireId: String,
        @Query("kind") kind: String = "answers",
        @Query("status") status: String = "APPROVED"
    ): Response<ResponseBody>

    @GET("questionnaire/annexure")
    suspend fun annexure(@Query("workshopId") workshopId: String): JsonObject
}
