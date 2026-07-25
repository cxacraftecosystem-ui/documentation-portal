package com.fieldrepository.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class GoogleLoginRequest(
    val googleIdToken: String
)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val tokenType: String = "bearer",
    val user: UserDto
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String,
    val role: String,
    val canManageQuestionnaire: Boolean = false,
    val canManageCrafts: Boolean = false,
    val canManageWorkshops: Boolean = false,
    val canReview: Boolean = false,
    val canViewProvenance: Boolean = false,
    val canDownloadDataset: Boolean = false,
    val authProvider: String? = null
)

@Serializable
data class DashboardStats(
    val totalArtisans: Int = 0,
    val totalWorkshops: Int = 0,
    val totalProductRecords: Int = 0,
    val totalToolRecords: Int = 0,
    val totalMediaFiles: Int = 0,
    val pendingSubmissions: Int = 0
)

@Serializable
data class PageResponse<T>(
    val items: List<T>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val pages: Int
)

@Serializable
data class ArtisanDto(
    val id: String,
    val name: String,
    val place: String,
    val status: String,
    val craftId: String? = null,
    val craft: CraftDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null
)

@Serializable
data class CraftDto(
    val id: String,
    val name: String,
    val localName: String? = null,
    val category: String? = null,
    val place: String? = null,
    val description: String? = null,
    // The workshop this craft was documented at. Persisted since the workshop-linkage migration
    // (Craft.workshopId), so it round-trips; still nullable, because a craft may carry no workshop
    // and every row created before that migration has none.
    val workshopId: String? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

@Serializable
data class CreatedRecordDto(
    val id: String
)

@Serializable
data class ToolArtisanAssignRequest(
    val artisanIds: List<String>
)

@Serializable
data class MediaPresignRequest(
    val filename: String,
    val mimeType: String,
    val mediaType: String,
    val sizeBytes: Long,
    val linkedRecordType: String? = null,
    val linkedRecordId: String? = null
)

@Serializable
data class MediaPresignResponse(
    val uploadUrl: String,
    val method: String = "PUT",
    val objectKey: String,
    val bucket: String,
    val headers: Map<String, String> = emptyMap(),
    val publicUrl: String? = null
)

@Serializable
data class MediaCompleteRequest(
    val originalFilename: String,
    val mediaType: String,
    val mimeType: String,
    val sizeBytes: Long,
    val objectKey: String,
    val bucket: String? = null,
    val url: String? = null,
    val caption: String? = null,
    val linkedRecordType: String? = null,
    val linkedRecordId: String? = null,
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null,
    val processingRequests: List<String> = emptyList()
)

// --- S3 multipart upload (large files: chunk for transfer, S3 stitches into one object) ---

@Serializable
data class MultipartCreateRequest(
    val filename: String,
    val mimeType: String,
    val mediaType: String,
    val sizeBytes: Long,
    val linkedRecordType: String? = null,
    val linkedRecordId: String? = null
)

@Serializable
data class MultipartCreateResponse(
    val objectKey: String,
    val uploadId: String,
    val bucket: String,
    val partSize: Long,
    val partCount: Int,
    val publicUrl: String? = null
)

@Serializable
data class MultipartPresignPartsRequest(
    val objectKey: String,
    val uploadId: String,
    val partNumbers: List<Int>
)

@Serializable
data class MultipartPresignPartsResponse(
    val urls: Map<String, String> = emptyMap()
)

@Serializable
data class CompletedPart(
    val partNumber: Int,
    val etag: String
)

@Serializable
data class MultipartCompleteRequest(
    val objectKey: String,
    val uploadId: String,
    val parts: List<CompletedPart>
)

@Serializable
data class MultipartCompleteResponse(
    val objectKey: String,
    val bucket: String,
    val publicUrl: String? = null
)

@Serializable
data class MultipartAbortRequest(
    val objectKey: String,
    val uploadId: String
)

// --- Over-the-air app update ---

@Serializable
data class AppReleaseDto(
    val versionCode: Int = 0,
    val versionName: String = "",
    val url: String? = null,
    val notes: String? = null,
    val objectKey: String? = null
)

@Serializable
data class AppReleasePublishRequest(
    val versionCode: Int,
    val versionName: String,
    val objectKey: String,
    val url: String? = null,
    val notes: String? = null
)

// --- In-app feedback (quantitative rating + qualitative comment) ---

@Serializable
data class FeedbackDto(
    val id: String = "",
    val userId: String = "",
    // Quantitative (each 1–5): overall rating + per-aspect sub-ratings.
    val rating: Int? = null,
    val easeOfUse: Int? = null,
    val reliability: Int? = null,
    val performance: Int? = null,
    val design: Int? = null,
    val features: Int? = null,
    val recommend: Int? = null,
    // Qualitative free text.
    val comment: String? = null,
    val likeMost: String? = null,
    val improve: String? = null,
    val bugs: String? = null,
    val featureRequests: String? = null,
    val role: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val user: UserDto? = null
)

@Serializable
data class FeedbackUpsertRequest(
    val rating: Int? = null,
    val easeOfUse: Int? = null,
    val reliability: Int? = null,
    val performance: Int? = null,
    val design: Int? = null,
    val features: Int? = null,
    val recommend: Int? = null,
    val comment: String? = null,
    val likeMost: String? = null,
    val improve: String? = null,
    val bugs: String? = null,
    val featureRequests: String? = null,
    val role: String? = null
)

@Serializable
data class MediaRelinkRequest(
    val linkedRecordType: String,
    val linkedRecordId: String
)

@Serializable
data class TranscriptRefineRequest(
    val translate: Boolean = false
)

@Serializable
data class TranscriptUpdateRequest(
    val text: String
)

@Serializable
data class TranscriptRefineResponse(
    val available: Boolean = true,
    val status: String? = null,
    val refined: String? = null,
    val model: String? = null,
    val translated: Boolean = false,
    val message: String? = null
)

@Serializable
data class MeasurementAnalysisDto(
    val valueInches: Double? = null,
    val lengthInches: Double? = null,
    val breadthInches: Double? = null,
    val confidence: Double? = null,
    val notes: String? = null
)

@Serializable
data class AnalyzeMeasurementResponse(
    val available: Boolean = false,
    val status: String? = null,
    val analysis: MeasurementAnalysisDto? = null,
    val message: String? = null
)

@Serializable
data class MediaFileDto(
    val id: String,
    val originalFilename: String,
    val mediaType: String,
    val mimeType: String? = null,
    val url: String? = null,
    val caption: String? = null,
    val transcriptStatus: String? = null,
    val transcriptText: String? = null,
    val transcriptError: String? = null,
    val uploadedBy: UserDto? = null,
    val createdAt: String? = null,
    val linkedRecordType: String? = null,
    val linkedRecordId: String? = null
)

@Serializable
data class UserUpdateRequest(
    val role: String? = null,
    val canManageQuestionnaire: Boolean? = null,
    val canManageCrafts: Boolean? = null,
    val canManageWorkshops: Boolean? = null,
    val canReview: Boolean? = null,
    val canViewProvenance: Boolean? = null,
    val canDownloadDataset: Boolean? = null
)

/**
 * Who created a record awaiting review. A trimmed shape on purpose: `/review/pending` embeds only
 * id/name/role, so this cannot reuse [UserDto] (whose `email` is required and absent here).
 */
@Serializable
data class ReviewCreatorDto(
    val id: String,
    val name: String = "",
    val role: String? = null
)

/** One record awaiting review, as surfaced by GET /review/pending. */
@Serializable
data class PendingReviewDto(
    val recordType: String,
    val id: String,
    val label: String,
    val place: String? = null,
    val createdAt: String? = null,
    val createdBy: ReviewCreatorDto? = null,
    // Submitted after its workshop ended: only an admin/master admin may edit or approve it, so the
    // queue warns before the reviewer discovers it as a 403.
    val needsAdminApproval: Boolean = false
)

@Serializable
data class PendingReviewListDto(
    val items: List<PendingReviewDto> = emptyList(),
    val total: Int = 0
)

/** Optional reviewer notes sent with approve/reject; MANDATORY on "send for revision" (422 without). */
@Serializable
data class ReviewActionRequest(
    val notes: String? = null
)

/**
 * A reviewer correcting a record in place instead of bouncing it back to its creator.
 *
 * [fields] is validated server-side against the record type's own PATCH schema, so only real columns
 * are accepted and every rule the normal edit path enforces still applies. Send ONLY the keys that
 * actually changed: the API refuses `status`, `extraMetadata`, `workshopId`, `location`, `artisanIds`,
 * `craftIds`, `responses`, `steps`, `recordedAt` and `recordedTimezone` with a 422.
 *
 * The record's status is left ALONE unless [approve] is set — an edit is not an approval.
 */
@Serializable
data class ReviewEditRequest(
    val fields: Map<String, String>,
    val note: String? = null,
    val approve: Boolean = false
)

@Serializable
data class CraftCreateRequest(
    val name: String,
    val localName: String? = null,
    val category: String? = null,
    val description: String? = null,
    val place: String? = null,
    // The workshop this craft was documented at. Every record form now opens with the workshop
    // picker, so the field is sent for craft too. NOTE: the API's Pydantic base sets
    // `extra="forbid"`, so this key is NOT optional at the wire level — a backend that predates
    // `CraftCreate.workshopId` rejects the whole request with 422. Ship the two together. When no
    // workshop is selected the value is null and `explicitNulls = false` omits the key entirely,
    // which is the backwards-compatible "no workshop named" path.
    val workshopId: String? = null,
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata"
)

@Serializable
data class WorkshopCreateRequest(
    val title: String,
    val date: String,
    val startDate: String? = null,
    val endDate: String? = null,
    val place: String,
    val description: String? = null,
    val notes: String? = null,
    val artisanIds: List<String>? = null,
    val craftIds: List<String>? = null,
    val status: String = "PENDING",
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null
)

@Serializable
data class ArtisanCreateRequest(
    val name: String,
    val localName: String? = null,
    val gender: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val place: String,
    val address: String? = null,
    val notes: String? = null,
    // Identity. `aadhaarNumber` is the repository's deduplication key (UNIQUE in the DB) and travels as
    // BARE 12 digits — the form's grouped "1234 5678 9012" display is presentation only. The API also
    // normalises spacing, but sending the clean value keeps the wire form and the stored form identical.
    val aadhaarNumber: String? = null,
    // Nullable on purpose even though the API's create model defaults it to true: the request Json is
    // built with `encodeDefaults = false`, so a non-null default would be DROPPED from the payload
    // whenever it matched — and a PATCH that omits the flag cannot flip a "No" record back to "Yes".
    // With null as the default, whatever the form sets is always on the wire.
    val pehchanCardAvailable: Boolean? = null,
    val pehchanCardNumber: String? = null,
    // Newline-separated, numbered Do's (positive prompt) and Don'ts (negative prompt). Required on the
    // form; the backend requires them on create (nullable here so an update PATCH stays flexible).
    val dos: String? = null,
    val donts: String? = null,
    val craftId: String? = null,
    val craftName: String? = null,
    // The workshop this artisan was documented at (see [CraftCreateRequest.workshopId]).
    val workshopId: String? = null,
    val status: String = "PENDING",
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null
)

@Serializable
data class ProductCreateRequest(
    val craftName: String,
    val place: String,
    val artisanName: String,
    val productName: String,
    val localName: String? = null,
    val productType: String = "OTHER",
    val timeTakenToCompleteProduct: String? = null,
    val size: String? = null,
    val lengthInches: Double? = null,
    val breadthInches: Double? = null,
    val heightInches: Double? = null,
    val costOfMaking: Double? = null,
    val sellingPrice: Double? = null,
    val marketDemand: String = "UNKNOWN",
    val rawMaterialsUsed: String? = null,
    val mainToolsUsed: String? = null,
    val productFunctionUse: String? = null,
    val remarks: String? = null,
    val artisanId: String? = null,
    val craftId: String? = null,
    val workshopId: String? = null,
    val status: String = "PENDING",
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null
)

@Serializable
data class ToolCreateRequest(
    val craftName: String,
    val place: String,
    val artisanName: String,
    val toolkitName: String,
    val localName: String? = null,
    val englishName: String? = null,
    val processUsedIn: String? = null,
    val material: String? = null,
    val yearsInUse: Int? = null,
    val height: Double? = null,
    val width: Double? = null,
    val lengthInches: Double? = null,
    val breadthInches: Double? = null,
    val thickness: Double? = null,
    val weight: Double? = null,
    val radius: Double? = null,
    val maker: String = "UNKNOWN",
    val traditionType: String = "UNKNOWN",
    val replacementCost: Double? = null,
    val suggestionsForToolImprovement: String? = null,
    val remarks: String? = null,
    val artisanId: String? = null,
    val craftId: String? = null,
    val workshopId: String? = null,
    val status: String = "PENDING",
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null
)

@Serializable
data class LocationRequest(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val address: String? = null,
    @SerialName("placeName") val placeName: String? = null
)

@Serializable
data class QuestionnaireQuestionDto(
    val id: String,
    val sectionId: String? = null,
    val sectionCode: String,
    val sectionTitle: String,
    val prompt: String,
    val sortOrder: Int,
    val isActive: Boolean = true
)

@Serializable
data class QuestionnaireSectionDto(
    val id: String,
    val code: String,
    val title: String,
    val sortOrder: Int,
    val isActive: Boolean = true,
    val questions: List<QuestionnaireQuestionDto> = emptyList()
)

@Serializable
data class QuestionnaireSectionCreateRequest(
    val code: String,
    val title: String,
    val sortOrder: Int? = null,
    val isActive: Boolean = true
)

@Serializable
data class QuestionnaireSectionUpdateRequest(
    val code: String? = null,
    val title: String? = null,
    val sortOrder: Int? = null,
    val isActive: Boolean? = null
)

@Serializable
data class QuestionnaireSectionReorderRequest(
    val sectionIds: List<String>
)

@Serializable
data class QuestionnaireQuestionCreateRequest(
    val sectionId: String,
    val prompt: String,
    val sortOrder: Int? = null,
    val isActive: Boolean = true
)

@Serializable
data class QuestionnaireQuestionUpdateRequest(
    val sectionId: String? = null,
    val prompt: String? = null,
    val sortOrder: Int? = null,
    val isActive: Boolean? = null
)

@Serializable
data class QuestionnaireQuestionReorderRequest(
    val sectionId: String,
    val questionIds: List<String>
)

@Serializable
data class QuestionnaireResponseRequest(
    val questionId: String,
    val answerText: String? = null,
    val notes: String? = null
)

/**
 * NOTE: there is deliberately no `interviewDate` here. The date of an interview is no longer a form
 * field on either client — the server derives it from `recordedAt` (see the questionnaire route's
 * `derive_interview_date`), which is the moment the interview was actually captured rather than a
 * date a researcher retypes (and mistypes) at the end of a long session. Do not re-add it.
 */
@Serializable
data class QuestionnaireInterviewCreateRequest(
    val title: String,
    val place: String? = null,
    val language: String? = null,
    val notes: String? = null,
    val status: String = "PENDING",
    val artisanIds: List<String> = emptyList(),
    val responses: List<QuestionnaireResponseRequest> = emptyList(),
    // The workshop this interview was conducted at (see [CraftCreateRequest.workshopId]).
    val workshopId: String? = null,
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null
)

// ---------------------------------------------------------------------------
// Read models used by the browse / edit-existing screens. All fields are made
// optional so partial server payloads never break deserialization. Decimal
// columns arrive as JSON numbers (FastAPI encodes Decimal as float).
// ---------------------------------------------------------------------------

@Serializable
data class LocationDto(
    val id: String? = null,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val address: String? = null,
    @SerialName("placeName") val placeName: String? = null
)

@Serializable
data class ArtisanDetailDto(
    val id: String,
    val name: String,
    val localName: String? = null,
    val gender: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val place: String = "",
    val address: String? = null,
    val notes: String? = null,
    // The artisan record returns the FULL Aadhaar number (every other surface — the data browser, the
    // .xlsx report, exports — gets the "XXXX XXXX 9012" mask), because the edit form has to show the
    // researcher what is stored before they change it.
    val aadhaarNumber: String? = null,
    val pehchanCardAvailable: Boolean = true,
    val pehchanCardNumber: String? = null,
    val dos: String? = null,
    val donts: String? = null,
    val craftId: String? = null,
    val craft: CraftDto? = null,
    // The workshop this artisan was documented at (see [CraftDto.workshopId]).
    val workshopId: String? = null,
    val status: String = "PENDING",
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

/**
 * The artisan already holding a searched-for Aadhaar number. Deliberately only the fields that let a
 * researcher recognise the person and go to them — the number itself is never echoed back.
 */
@Serializable
data class ArtisanIdentityMatchDto(
    val id: String,
    val name: String = "",
    val place: String? = null,
    val craft: String? = null,
    val workshop: String? = null
)

/**
 * Answer from `GET /artisans/lookup/aadhaar`. "Not found" is the expected, successful answer (the
 * endpoint never 404s), so [found] false with a null [artisan] is the normal case, not an error.
 */
@Serializable
data class AadhaarLookupDto(
    val found: Boolean = false,
    val artisan: ArtisanIdentityMatchDto? = null
)

@Serializable
data class ProductDetailDto(
    val id: String,
    val productName: String = "",
    val localName: String? = null,
    val craftName: String = "",
    val artisanName: String = "",
    val place: String = "",
    val productType: String = "OTHER",
    val timeTakenToCompleteProduct: String? = null,
    val size: String? = null,
    // Decimal columns arrive from the API as JSON strings (e.g. "12.5"); typing them Double? broke
    // list parsing. The forms read them via numToText(). Request DTOs keep Double? (they send numbers).
    val lengthInches: String? = null,
    val breadthInches: String? = null,
    val heightInches: String? = null,
    val costOfMaking: String? = null,
    val sellingPrice: String? = null,
    val marketDemand: String = "UNKNOWN",
    val rawMaterialsUsed: String? = null,
    val mainToolsUsed: String? = null,
    val productFunctionUse: String? = null,
    val remarks: String? = null,
    val artisanId: String? = null,
    val craftId: String? = null,
    val workshopId: String? = null,
    val status: String = "PENDING",
    val measurementAnalysisStatus: String? = null,
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

@Serializable
data class ToolDetailDto(
    val id: String,
    val toolkitName: String = "",
    val localName: String? = null,
    val englishName: String? = null,
    val craftName: String = "",
    val artisanName: String = "",
    val place: String = "",
    val processUsedIn: String? = null,
    val material: String? = null,
    val yearsInUse: Int? = null,
    // Decimal columns arrive as JSON strings; typed String? to keep list parsing from failing.
    val height: String? = null,
    val width: String? = null,
    val lengthInches: String? = null,
    val breadthInches: String? = null,
    val thickness: String? = null,
    val weight: String? = null,
    val radius: String? = null,
    val maker: String = "UNKNOWN",
    val traditionType: String = "UNKNOWN",
    val replacementCost: Double? = null,
    val suggestionsForToolImprovement: String? = null,
    val remarks: String? = null,
    val artisanId: String? = null,
    val craftId: String? = null,
    val workshopId: String? = null,
    val status: String = "PENDING",
    val measurementAnalysisStatus: String? = null,
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

@Serializable
data class WorkshopArtisanLinkDto(
    val artisanId: String,
    val artisan: ArtisanDto? = null
)

@Serializable
data class WorkshopCraftLinkDto(
    val craftId: String,
    val craft: CraftDto? = null
)

@Serializable
data class WorkshopDetailDto(
    val id: String,
    val title: String = "",
    val place: String = "",
    val description: String? = null,
    val notes: String? = null,
    val date: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val status: String = "PENDING",
    val artisans: List<WorkshopArtisanLinkDto> = emptyList(),
    val crafts: List<WorkshopCraftLinkDto> = emptyList(),
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

@Serializable
data class ArtisanAnswerDto(
    val responseId: String,
    val questionId: String,
    val prompt: String? = null,
    val sectionCode: String? = null,
    val sectionTitle: String? = null,
    val sortOrder: Int = 0,
    val answerText: String? = null,
    val notes: String? = null,
    val interviewId: String? = null,
    val interviewTitle: String? = null,
    val answeredByName: String? = null
)

@Serializable
data class ArtisanQuestionnaireDto(
    val artisanId: String,
    val answered: List<ArtisanAnswerDto> = emptyList(),
    val total: Int = 0,
    // Every interview this artisan belongs to (alone, in a subset, or in a larger set), with its
    // recordings and the co-artisans — so a group recording surfaces for each member individually.
    val interviews: List<ArtisanInterviewDto> = emptyList()
)

@Serializable
data class ArtisanInterviewDto(
    val interviewId: String,
    val title: String = "",
    val notes: String? = null,
    val interviewDate: String? = null,
    val place: String? = null,
    val language: String? = null,
    val status: String? = null,
    val artisanCount: Int = 0,
    val coArtisans: List<String> = emptyList(),
    val media: List<MediaFileDto> = emptyList()
)

@Serializable
data class InterviewResponseDto(
    val questionId: String,
    val answerText: String? = null,
    val notes: String? = null,
    val answeredBy: UserDto? = null
)

@Serializable
data class QuestionnaireInterviewDetailDto(
    val id: String,
    val title: String = "",
    val place: String? = null,
    val language: String? = null,
    val notes: String? = null,
    val status: String = "PENDING",
    val artisans: List<WorkshopArtisanLinkDto> = emptyList(),
    val responses: List<InterviewResponseDto> = emptyList(),
    // The workshop this interview was conducted at (see [CraftDto.workshopId]).
    val workshopId: String? = null,
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

// ---------------------------------------------------------------------------
// Process documentation: a making/using process tied to a product, with ordered
// steps that each carry their own media. Steps are sequential (files named 1A,
// 1B…) or a group of activities (files named 1-G1, 1-G2…).
// ---------------------------------------------------------------------------

@Serializable
data class ProcessStepRequest(
    val id: String? = null,
    val name: String,
    val stepType: String = "SEQUENTIAL",
    val sortOrder: Int = 0,
    val notes: String? = null
)

@Serializable
data class ProcessCreateRequest(
    val name: String,
    val productId: String,
    val preProcessAvailable: Boolean = false,
    val notes: String? = null,
    val status: String = "PENDING",
    val steps: List<ProcessStepRequest> = emptyList(),
    // The workshop this process was documented at (see [CraftCreateRequest.workshopId]).
    val workshopId: String? = null,
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata"
)

@Serializable
data class ProcessStepDto(
    val id: String,
    val name: String = "",
    val stepType: String = "SEQUENTIAL",
    val sortOrder: Int = 0,
    val notes: String? = null,
    val media: List<MediaFileDto> = emptyList()
)

@Serializable
data class ProcessDetailDto(
    val id: String,
    val name: String = "",
    val productId: String = "",
    val preProcessAvailable: Boolean = false,
    val notes: String? = null,
    val status: String = "PENDING",
    val product: ProductDetailDto? = null,
    val steps: List<ProcessStepDto> = emptyList(),
    val media: List<MediaFileDto> = emptyList(),
    // The workshop this process was documented at (see [CraftDto.workshopId]).
    val workshopId: String? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

@Serializable
data class DatasetFileDto(
    val path: String,
    val url: String? = null,
    val content: String? = null
)

@Serializable
data class DatasetManifestDto(
    val files: List<DatasetFileDto> = emptyList(),
    val totalFiles: Int = 0,
    val totalMedia: Int = 0
)

@Serializable
data class QuestionnaireInterviewUpdateRequest(
    val title: String? = null,
    val place: String? = null,
    val language: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val artisanIds: List<String>? = null,
    val responses: List<QuestionnaireResponseRequest>? = null,
    val workshopId: String? = null,
    val recordedTimezone: String? = null,
    val location: LocationRequest? = null
)

// --- Questionnaire completion matrix (artisans x sections) ---

@Serializable
data class CompletionMatrixDto(
    val sections: List<CompletionSectionDto> = emptyList(),
    val artisans: List<CompletionArtisanDto> = emptyList(),
    val cells: List<CompletionCellDto> = emptyList()
)

@Serializable
data class CompletionSectionDto(
    val id: String,
    val code: String,
    val title: String,
    val sortOrder: Int = 0
)

@Serializable
data class CompletionArtisanDto(
    val id: String,
    val name: String
)

@Serializable
data class CompletionCellDto(
    val artisanId: String,
    val sectionId: String,
    val derived: Boolean = false,
    // null = no admin override (fall back to `derived`); else COMPLETED | NEEDS_REVIEW | NEEDS_REDO.
    val status: String? = null,
    val setByName: String? = null
)

@Serializable
data class CompletionCellRequest(
    val artisanId: String,
    val sectionId: String,
    // null clears the override.
    val status: String? = null
)

@Serializable
data class AppSettingDto(
    val transcriptionMode: String = "REFINED_TRANSLATED",
    val batchWindowEnabled: Boolean = false,
    val batchWindowStart: String = "02:00",
    val batchWindowEnd: String = "05:00",
    val batchTimezone: String = "Asia/Kolkata"
)

@Serializable
data class AppSettingUpdateRequest(
    val transcriptionMode: String? = null,
    val batchWindowEnabled: Boolean? = null,
    val batchWindowStart: String? = null,
    val batchWindowEnd: String? = null,
    val batchTimezone: String? = null
)

// --- Cross-researcher data access (Sharing) ---

@Serializable
data class DataAccessTierInfo(val tier: String, val description: String)

@Serializable
data class DataAccessScopeItemDto(
    val recordType: String,
    val recordId: String
)

@Serializable
data class DataAccessGrantDto(
    val id: String,
    val ownerId: String,
    val granteeId: String,
    val tier: String,
    val status: String,
    val allData: Boolean = true,
    val requestNote: String? = null,
    val decisionNote: String? = null,
    val owner: UserDto? = null,
    val grantee: UserDto? = null,
    val scopeItems: List<DataAccessScopeItemDto> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class MyGrantsDto(
    val incoming: List<DataAccessGrantDto> = emptyList(),
    val outgoing: List<DataAccessGrantDto> = emptyList()
)

@Serializable
data class DataAccessRequestBody(
    val ownerId: String,
    val tier: String = "DOWNLOAD",
    val allData: Boolean = true,
    val requestNote: String? = null
)

@Serializable
data class DataAccessGrantBody(
    val granteeId: String,
    val tier: String = "DOWNLOAD",
    val allData: Boolean = true,
    val scopeItems: List<DataAccessScopeItemDto> = emptyList(),
    val decisionNote: String? = null
)

@Serializable
data class DataAccessDecisionBody(
    val status: String,
    val tier: String? = null,
    val decisionNote: String? = null
)

@Serializable
data class EntryCommentDto(
    val id: String,
    val recordType: String,
    val recordId: String,
    val authorId: String,
    val body: String,
    val author: UserDto? = null,
    val createdAt: String
)

@Serializable
data class EntryCommentBody(
    val recordType: String,
    val recordId: String,
    val body: String
)

@Serializable
data class RevisionChange(
    val old: JsonElement? = null,
    val new: JsonElement? = null
)

@Serializable
data class RecordRevisionDto(
    val id: String,
    val recordType: String,
    val recordId: String,
    val editedBy: UserDto? = null,
    val changes: Map<String, RevisionChange> = emptyMap(),
    val createdAt: String
)

// ---------------------------------------------------------------------------
// Workshop access. ONE row per (workshop, user) carries the whole two-sided
// conversation: an admin grants/revokes, a user requests and is approved or
// denied. Only status == "GRANTED" confers access — a PENDING row confers
// nothing, and DENIED/REVOKED rows are kept as history, never deleted.
// ---------------------------------------------------------------------------

/**
 * Answer from `GET /workshops/{id}/submission-check` — what submitting a record into ONE workshop
 * would mean for the signed-in user, asked BEFORE the record is sent. The endpoint never 403s; it
 * only reports, so a failure to reach it must never block a save.
 *
 * - [canSubmit] false: the workshop is curated and this user is not on its roster, so a create would
 *   come back 403. Say so at pick time instead of after the form is filled in.
 * - [needsAdminApproval] true: the submission IS accepted, but it is pinned to PENDING and only an
 *   admin or master admin can approve it — a professor cannot. Admins are the approval authority, so
 *   an admin submitting late sees [outOfWindow] true with [needsAdminApproval] false.
 *
 * Only the eight documented keys are modelled; the endpoint also returns accessLevel/requestStatus/
 * restricted/canEdit, which the record forms have no use for (`ignoreUnknownKeys` drops them).
 */
@Serializable
data class WorkshopSubmissionCheckDto(
    val workshopId: String? = null,
    val title: String? = null,
    val endDate: String? = null,
    /** The whole of the end day has passed. */
    val isOver: Boolean = false,
    /** Submitting now falls outside [startDate, endDate] — before it opened, or after it closed. */
    val outOfWindow: Boolean = false,
    val needsAdminApproval: Boolean = false,
    val assigned: Boolean = true,
    val canSubmit: Boolean = true
)

/** VIEW < CONTRIBUTE < EDIT. The ladder and its human definitions come from the API. */
@Serializable
data class WorkshopAccessLevelDto(
    val level: String,
    val description: String = ""
)

@Serializable
data class WorkshopAssignmentDto(
    val id: String,
    val workshopId: String,
    val userId: String,
    val accessLevel: String = "CONTRIBUTE",
    // PENDING | GRANTED | DENIED | REVOKED. Anything but GRANTED means no access.
    val status: String = "GRANTED",
    val requestNote: String? = null,
    val decisionNote: String? = null,
    val decidedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val user: UserDto? = null,
    // Present on the cross-workshop views (/access-requests and /access-requests/mine), where a row
    // has to name the workshop it belongs to; null on a single workshop's roster.
    val workshop: WorkshopDetailDto? = null,
    val assignedBy: UserDto? = null,
    val requestedBy: UserDto? = null,
    val decidedBy: UserDto? = null
)

/** Legacy whole-set roster replacement (PUT). Still supported; POST/PATCH/DELETE are per-user. */
@Serializable
data class WorkshopAssignmentBody(
    val userIds: List<String>,
    val accessLevel: String? = null
)

/**
 * Ask for access to SEVERAL workshops at once — that is how the need arrives (a researcher joining a
 * project wants the same access to a whole season), and filing them one at a time produces a queue
 * nobody works through. Idempotent per workshop server-side.
 */
@Serializable
data class WorkshopAccessRequestBody(
    val workshopIds: List<String>,
    val accessLevel: String? = null,
    val note: String? = null
)

/** Per-workshop result of a multi-select request: CREATED | ALREADY_PENDING | ALREADY_GRANTED | RE_REQUESTED. */
@Serializable
data class WorkshopAccessOutcomeDto(
    val workshopId: String,
    val outcome: String
)

@Serializable
data class WorkshopAccessRequestResultDto(
    val outcomes: List<WorkshopAccessOutcomeDto> = emptyList(),
    val requests: List<WorkshopAssignmentDto> = emptyList()
)

/** Admin answer to a PENDING request: status is GRANTED or DENIED. */
@Serializable
data class WorkshopAccessDecisionBody(
    val status: String,
    val accessLevel: String? = null,
    val note: String? = null
)

/** Admin grants ONE user access at a level without disturbing the rest of the roster (upsert). */
@Serializable
data class WorkshopGrantBody(
    val userId: String,
    val accessLevel: String? = null,
    val note: String? = null
)

/** Admin changes one roster row: its level, its status (GRANTED | DENIED | REVOKED), or both. */
@Serializable
data class WorkshopAssignmentUpdateBody(
    val accessLevel: String? = null,
    val status: String? = null,
    val note: String? = null
)

// ---------------------------------------------------------------------------
// Assigned tasks. One row is always exactly ONE assignee; handing the same
// scope to N people writes N rows sharing a batchId. Scope is five orthogonal
// dimensions: workshop x recordTypes x artisans x sections x targetCount.
// ---------------------------------------------------------------------------

@Serializable
data class TaskArtisanDto(
    val id: String,
    val name: String = "",
    val place: String? = null
)

@Serializable
data class TaskSectionDto(
    val id: String,
    val code: String = "",
    val title: String = "",
    val sortOrder: Int = 0
)

/**
 * A task with its scope already resolved by the server — workshop title, artisan names, section
 * codes and both progress numbers — so a task board renders from this one call.
 *
 * [progressCount] is what the assignee CLAIMS; [derivedCount] is what the database can see them
 * having actually produced. The two answer different questions and the gap between them is the whole
 * point of the accountability view, so neither ever overwrites the other. [derivedCount] is null when
 * the count could not be run, and [percentComplete] is null for an open-ended task (no target).
 */
@Serializable
data class TaskDto(
    val id: String,
    val title: String = "",
    val description: String? = null,
    // OPEN | IN_PROGRESS | DONE | CANCELLED
    val status: String = "OPEN",
    val dueAt: String? = null,
    val completedAt: String? = null,
    val workshopId: String? = null,
    val workshopTitle: String? = null,
    val recordTypes: List<String> = emptyList(),
    val recordTypeLabels: List<String> = emptyList(),
    val artisans: List<TaskArtisanDto> = emptyList(),
    val sections: List<TaskSectionDto> = emptyList(),
    val targetCount: Int? = null,
    val progressCount: Int = 0,
    val percentComplete: Int? = null,
    val isOverdue: Boolean = false,
    val derivedCount: Int? = null,
    val derivedTarget: Int? = null,
    val derivedBreakdown: Map<String, Int> = emptyMap(),
    val batchId: String? = null,
    val assigneeId: String? = null,
    val assignee: UserDto? = null,
    val createdById: String? = null,
    val createdBy: UserDto? = null,
    val createdAt: String? = null
)

/**
 * What an ASSIGNEE may change: where the task stands, and how much of it is done. Scope, due date and
 * reassignment stay with whoever handed the work out — sending anything else is a 403.
 */
@Serializable
data class TaskUpdateBody(
    val status: String? = null,
    val progressCount: Int? = null
)
