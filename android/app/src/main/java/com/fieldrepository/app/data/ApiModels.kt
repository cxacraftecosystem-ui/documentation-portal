package com.fieldrepository.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    val canReview: Boolean? = null
)

/** One record awaiting review, as surfaced by GET /review/pending. */
@Serializable
data class PendingReviewDto(
    val recordType: String,
    val id: String,
    val label: String,
    val place: String? = null,
    val createdAt: String? = null
)

@Serializable
data class PendingReviewListDto(
    val items: List<PendingReviewDto> = emptyList(),
    val total: Int = 0
)

/** Optional reviewer notes sent with approve/reject. */
@Serializable
data class ReviewActionRequest(
    val notes: String? = null
)

@Serializable
data class CraftCreateRequest(
    val name: String,
    val localName: String? = null,
    val category: String? = null,
    val description: String? = null,
    val place: String? = null,
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
    val craftId: String? = null,
    val craftName: String? = null,
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

@Serializable
data class QuestionnaireInterviewCreateRequest(
    val title: String,
    val interviewDate: String? = null,
    val place: String? = null,
    val language: String? = null,
    val notes: String? = null,
    val status: String = "PENDING",
    val artisanIds: List<String> = emptyList(),
    val responses: List<QuestionnaireResponseRequest> = emptyList(),
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
    val craftId: String? = null,
    val craft: CraftDto? = null,
    val status: String = "PENDING",
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
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
    val total: Int = 0
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
    val recordedTimezone: String? = null,
    val location: LocationRequest? = null
)
