@file:OptIn(ExperimentalSerializationApi::class)

package com.fieldrepository.app.forms

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
 * the forms library, the builder, workbooks, bindings and defaults, the corpus and the workshop
 * annexure. The session screens are the shared tree's (`questionnaire/`), synced from designer-portal.
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


// ── The workshop's annexure (A12) ────────────────────────────────────────────────────────────

@Serializable
data class QAnnexureAnswer(val subject: String = "", val text: String = "")

@Serializable
data class QAnnexureQuestion(val displayCode: String = "", val prompt: String = "", val answers: List<QAnnexureAnswer> = emptyList())

@Serializable
data class QAnnexureSection(val code: String = "", val title: String = "", val questions: List<QAnnexureQuestion> = emptyList())

@Serializable
data class QAnnexureForm(val id: String = "", val title: String = "", val kind: String? = null, val sessionCount: Int = 0, val sections: List<QAnnexureSection> = emptyList())

@Serializable
data class QAnnexureGroup(val kind: String? = null, val label: String = "", val stage: String? = null, val forms: List<QAnnexureForm> = emptyList())

@Serializable
data class QAnnexure(val workshopId: String = "", val workshopTitle: String? = null, val groups: List<QAnnexureGroup> = emptyList())

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

    @Streaming
    @GET("questionnaire/corpus.csv")
    suspend fun corpusCsv(
        @Query("questionnaireId") questionnaireId: String,
        @Query("kind") kind: String = "answers",
        @Query("status") status: String = "APPROVED"
    ): Response<ResponseBody>

    /** A12: the workshop's forms grouped by kind, with the answers of its live sessions. */
    @GET("questionnaire/annexure")
    suspend fun annexure(@Query("workshopId") workshopId: String): QAnnexure

    /** The same annexure as the text the workshop export prints (C10: the export reads takes and answers). */
    @Streaming
    @GET("questionnaire/annexure")
    suspend fun annexureText(@Query("workshopId") workshopId: String, @Query("format") format: String = "txt"): Response<ResponseBody>
}
