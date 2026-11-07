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
    val authProvider: String? = null,
    val twoFactorEnabled: Boolean = false,
    val mustChangePassword: Boolean? = null,
    val passwordSetAt: String? = null,
    val hasPassword: Boolean? = null,
    val firstLoginAt: String? = null,
    val lastLoginAt: String? = FIELD_NOT_SENT,
    val createdAt: String? = null
)

const val FIELD_NOT_SENT = ""

@Serializable
data class DashboardStats(
    val totalArtisans: Int = 0,
    val totalWorkshops: Int = 0,
    val totalProductRecords: Int = 0,
    val totalToolRecords: Int = 0,
    val totalMediaFiles: Int = 0,
    val pendingSubmissions: Int = 0,
    val mine: DashboardStatsMine? = null,
    val recentSubmissions: List<DashboardRecentSubmissionDto> = emptyList()
)

@Serializable
data class DashboardStatsMine(
    val totalArtisans: Int = 0,
    val totalWorkshops: Int = 0,
    val totalProductRecords: Int = 0,
    val totalToolRecords: Int = 0,
    val totalMediaFiles: Int = 0,
    val pendingSubmissions: Int = 0,
    val sentBack: Int = 0,
    val rejected: Int = 0
)

@Serializable
data class DashboardRecentSubmissionDto(
    val id: String = "",
    val type: String = "",
    val status: String = "",
    val createdAt: String? = null,
    val title: String? = null,
    val place: String? = null,
    val createdByName: String? = null
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
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val completeness: CompletenessDto? = null,
    val card: ArtisanCardCodesDto? = null,
    val aadhaarLast4: String? = null,
    val linkedCardCodes: List<String> = emptyList()
)

@Serializable
data class ArtisanCardCodesDto(
    val code: String,
    val shortCode: String,
    val token: String
)

@Serializable
data class ArtisanCardStatusDto(val enabled: Boolean = false, val issuer: String? = null)

@Serializable
data class ResolveArtisanCardRequest(
    val input: String,
    val source: String = "ANDROID",
    val consent: Boolean = false
)

@Serializable
data class CardArtisanDto(
    val id: String,
    val name: String,
    val craft: String? = null,
    val cluster: String? = null,
    val place: String? = null,
    val aadhaarLast4: String? = null
)

@Serializable
data class ResolveArtisanCardDto(
    val status: String,
    val via: String? = null,
    val artisan: CardArtisanDto? = null,
    val artisanId: String? = null
)

@Serializable
data class CraftDto(
    val id: String,
    val name: String,
    val localName: String? = null,
    val category: String? = null,
    val slug: String? = null,
    val categoryId: String? = null,
    val subCategoryId: String? = null,
    val sourceSerial: Int? = null,
    val stateOfOrigin: List<String> = emptyList(),
    val stateOfPractice: List<String> = emptyList(),
    val giStatus: String? = null,
    val status: String? = null,
    val place: String? = null,
    val description: String? = null,
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

@Serializable
data class FeedbackDto(
    val id: String = "",
    val userId: String = "",
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
    val message: String? = null,
    val methodMarker: JsonObject? = null
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
    val transcribeRequested: Boolean? = null,
    val transcribeRequestSource: String? = null,
    val transcriptEditedAt: String? = null,
    val transcriptEditedById: String? = null,
    val uploadedBy: UserDto? = null,
    val createdAt: String? = null,
    val linkedRecordType: String? = null,
    val linkedRecordId: String? = null,
    val questionnaireChips: com.fieldrepository.app.questionnaire.data.QChips? = null,
    val transcriptPreview: com.fieldrepository.app.transcript.data.TranscriptPreviewDto? = null,
    val sizeBytes: kotlinx.serialization.json.JsonPrimitive? = null
) {
    val byteSize: Long? get() = sizeBytes?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.toLongOrNull()?.takeIf { it > 0 }
}

@Serializable
data class UserUpdateRequest(
    val role: String? = null,
    val password: String? = null,
    val mustChangePassword: Boolean? = null,
    val canManageQuestionnaire: Boolean? = null,
    val canManageCrafts: Boolean? = null,
    val canManageWorkshops: Boolean? = null,
    val canReview: Boolean? = null,
    val canViewProvenance: Boolean? = null,
    val canDownloadDataset: Boolean? = null
)

@Serializable
data class ReviewCreatorDto(
    val id: String,
    val name: String = "",
    val role: String? = null
)

@Serializable
data class PendingReviewDto(
    val recordType: String,
    val id: String,
    val label: String,
    val place: String? = null,
    val createdAt: String? = null,
    val createdBy: ReviewCreatorDto? = null,
    val needsAdminApproval: Boolean = false,
    val submittedAt: String? = null,
    val resubmitted: Boolean = false,
    val previousReview: PreviousReviewDto? = null,
    val pendingMediaCount: Int = 0
)

@Serializable
data class PreviousReviewDto(
    val notes: String? = null,
    val reviewerName: String? = null,
    val reviewedAt: String? = null,
    val reasonLabel: String? = null
)

@Serializable
data class LatestReviewDto(
    val status: String = "",
    val notes: String? = null,
    val reason: String? = null,
    val reasonLabel: String? = null,
    val reviewerName: String? = null,
    val reviewedAt: String? = null
)

@Serializable
data class LatestReviewAnswerDto(
    val status: String = "",
    val canReview: Boolean = false,
    val needsAdminApproval: Boolean = false,
    val review: LatestReviewDto? = null
)

@Serializable
data class ReturnedRecordDto(
    val recordType: String,
    val id: String,
    val label: String = "",
    val place: String? = null,
    val status: String = "",
    val reviewNotes: String? = null,
    val reasonLabel: String? = null,
    val reviewerName: String? = null,
    val reviewedAt: String? = null
)

@Serializable
data class ReturnedCountsDto(
    @SerialName("NEEDS_REVISION") val needsRevision: Int = 0,
    @SerialName("REJECTED") val rejected: Int = 0
)

@Serializable
data class ReturnedListDto(
    val items: List<ReturnedRecordDto> = emptyList(),
    val total: Int = 0,
    val counts: ReturnedCountsDto = ReturnedCountsDto()
)

@Serializable
data class BulkApproveItem(val recordType: String, val id: String)

@Serializable
data class BulkApproveRequest(val items: List<BulkApproveItem>, val notes: String? = null)

@Serializable
data class BulkApproveResultDto(val recordType: String, val id: String, val outcome: String = "")

@Serializable
data class BulkApproveResponse(
    val results: List<BulkApproveResultDto> = emptyList(),
    val approved: Int = 0,
    val failed: Int = 0,
    val mediaApproved: Int = 0
)

@Serializable
data class RecordMediaReviewDto(
    val approvesWith: Int = 0,
    val rejectable: List<String> = emptyList()
)

@Serializable
data class QueuedReviewDecision(
    val recordType: String,
    val recordId: String,
    val notes: String? = null,
    val reason: String? = null
)

@Serializable
data class PendingReviewListDto(
    val items: List<PendingReviewDto> = emptyList(),
    val total: Int = 0,
    val page: Int? = null,
    val pageSize: Int? = null
)

@Serializable
data class ReviewActionRequest(
    val notes: String? = null,
    val reason: String? = null
)

@Serializable
data class ReviewReasonDto(
    val code: String,
    val label: String,
    val fixable: Boolean = false,
    val needsNote: Boolean = false
)

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
    val subCategoryId: String? = null,
    val stateOfOrigin: List<String>? = null,
    val stateOfPractice: List<String>? = null,
    val giStatus: String? = null,
    val description: String? = null,
    val place: String? = null,
    val workshopId: String? = null,
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata"
)

@Serializable
data class WorkshopCreateRequest(
    val title: String,
    val workshopType: String? = null,
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
    val location: LocationRequest? = null,
    val clientKey: String? = null
)

@Serializable
data class ArtisanCreateRequest(
    val name: String,
    val localName: String? = null,
    val gender: String? = null,
    val phone: String? = null,
    val phoneCountryCode: String? = null,
    val email: String? = null,
    val place: String,
    val address: String? = null,
    val notes: String? = null,
    val aadhaarNumber: String? = null,
    val dateOfBirth: String? = null,
    val craftStartDate: String? = null,
    val experienceYears: Int? = null,
    val experienceMonths: Int? = null,
    val pehchanCardAvailable: Boolean? = null,
    val pehchanCardNumber: String? = null,
    val aadhaarNotShared: Boolean? = null,
    val dos: String? = null,
    val donts: String? = null,
    val craftId: String? = null,
    val craftName: String? = null,
    val workshopId: String? = null,
    val status: String = "PENDING",
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null,
    val formContract: Int? = null,
    val duplicateAcknowledged: List<String>? = null
)

@Serializable
data class DuplicateCheckRequest(
    val name: String,
    val localName: String? = null,
    val gender: String? = null,
    val dateOfBirth: String? = null,
    val phone: String? = null,
    val place: String? = null,
    val state: String? = null,
    val district: String? = null,
    val village: String? = null,
    val craftId: String? = null,
    val craftName: String? = null,
    val aadhaar: String? = null,
    val pehchanCardNumber: String? = null,
    val excludeArtisanId: String? = null
)

@Serializable
data class DuplicateReasonDto(val code: String, val label: String)

@Serializable
data class DuplicateCandidateArtisanDto(
    val id: String,
    val name: String,
    val localName: String? = null,
    val place: String? = null,
    val state: String? = null,
    val district: String? = null,
    val craft: String? = null,
    val workshop: String? = null,
    val birthYear: Int? = null,
    val photoUrl: String? = null,
    val createdAt: String? = null
)

@Serializable
data class DuplicateCandidateDto(
    val artisan: DuplicateCandidateArtisanDto,
    val score: Double,
    val band: String,
    val reasons: List<DuplicateReasonDto> = emptyList()
)

@Serializable
data class DuplicateCheckResponse(val candidates: List<DuplicateCandidateDto> = emptyList())

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
    val measurementMethods: JsonObject? = null,
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
    val location: LocationRequest? = null,
    val clientKey: String? = null
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
    val materialFamily: String? = null,
    val yearsInUse: Int? = null,
    val height: Double? = null,
    val width: Double? = null,
    val lengthInches: Double? = null,
    val breadthInches: Double? = null,
    val heightInches: Double? = null,
    val measurementMethods: JsonObject? = null,
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
    val craftIds: List<String>? = null,
    val artisanIds: List<String>? = null,
    val workshopId: String? = null,
    val status: String = "PENDING",
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null,
    val clientKey: String? = null
)

@Serializable
data class LocationRequest(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val address: String? = null,
    @SerialName("placeName") val placeName: String? = null,
    val state: String? = null,
    val district: String? = null,
    val village: String? = null,
    val pincode: String? = null,
    val subjectLatitude: Double? = null,
    val subjectLongitude: Double? = null,
    val capturedAt: String? = null,
    val extraMetadata: JsonObject? = null
)

@Serializable
data class AddressReferenceDto(
    val version: Int = 1,
    val states: List<String> = emptyList(),
    val unionTerritories: List<String> = emptyList(),
    val statesAndUnionTerritories: List<String> = emptyList(),
    val districts: AddressDistrictsDto? = null
)

@Serializable
data class AddressDistrictsDto(
    val source: String? = null,
    val sourceUrl: String? = null,
    val asOf: String? = null,
    val listVersion: Int = 0,
    val count: Int = 0,
    val byState: Map<String, List<String>> = emptyMap()
)

@Serializable
data class QuestionnaireDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val isActive: Boolean = true,
    val isDefault: Boolean = false,
    val sortOrder: Int = 1,
    val sectionCount: Int = 0,
    val questionCount: Int = 0,
    val workshopCount: Int = 0,
    val createdAt: String? = null,
    val createdById: String? = null
)

@Serializable
data class QuestionnaireQuestionDto(
    val id: String,
    val questionnaireId: String? = null,
    val sectionId: String? = null,
    val sectionCode: String,
    val sectionTitle: String,
    val prompt: String,
    val sortOrder: Int,
    val isActive: Boolean = true,
    val artisanField: String? = null,
    val lineageId: String? = null,
    val displayCode: String? = null
)

@Serializable
data class QuestionnaireSectionDto(
    val id: String,
    val questionnaireId: String? = null,
    val code: String,
    val title: String,
    val sortOrder: Int,
    val isActive: Boolean = true,
    val questions: List<QuestionnaireQuestionDto> = emptyList()
)

@Serializable
data class QuestionnaireSectionCreateRequest(
    val questionnaireId: String? = null,
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
    val isActive: Boolean = true,
    val artisanField: String? = null
)

@Serializable
data class QuestionnaireQuestionUpdateRequest(
    val sectionId: String? = null,
    val prompt: String? = null,
    val sortOrder: Int? = null,
    val isActive: Boolean? = null,
    val artisanField: String? = null
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
    val notes: String? = null,
    val answerSource: String? = null
)

@Serializable
data class QuestionnaireInterviewCreateRequest(
    val title: String,
    val place: String? = null,
    val language: String? = null,
    val notes: String? = null,
    val status: String = "PENDING",
    val artisanIds: List<String> = emptyList(),
    val responses: List<QuestionnaireResponseRequest> = emptyList(),
    val workshopId: String? = null,
    val questionnaireId: String? = null,
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null
)

@Serializable
data class LocationDto(
    val id: String? = null,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val address: String? = null,
    @SerialName("placeName") val placeName: String? = null,
    val state: String? = null,
    val district: String? = null,
    val village: String? = null,
    val pincode: String? = null,
    val subjectLatitude: Double? = null,
    val subjectLongitude: Double? = null,
    val capturedAt: String? = null,
    val extraMetadata: JsonObject? = null
)

@Serializable
data class ArtisanDetailDto(
    val id: String,
    val name: String,
    val localName: String? = null,
    val gender: String? = null,
    val phone: String? = null,
    val phoneCountryCode: String? = null,
    val email: String? = null,
    val place: String = "",
    val address: String? = null,
    val notes: String? = null,
    val aadhaarNumber: String? = null,
    val aadhaarNotShared: Boolean = false,
    val dateOfBirth: String? = null,
    val craftStartDate: String? = null,
    val experienceYears: Int? = null,
    val experienceMonths: Int? = null,
    val pehchanCardAvailable: Boolean = true,
    val pehchanCardNumber: String? = null,
    val dos: String? = null,
    val donts: String? = null,
    val craftId: String? = null,
    val craft: CraftDto? = null,
    val workshopId: String? = null,
    val status: String = "PENDING",
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null,
    val completeness: CompletenessDto? = null
)

@Serializable
data class ArtisanIdentityMatchDto(
    val id: String,
    val name: String = "",
    val place: String? = null,
    val craft: String? = null,
    val workshop: String? = null
)

@Serializable
data class AadhaarLookupRequest(val number: String)

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
    val extraMetadata: JsonObject? = null,
    val completeness: CompletenessDto? = null
)

@Serializable
data class ToolCraftLinkDto(
    val craftId: String,
    val craft: CraftDto? = null
)

@Serializable
data class ToolArtisanLinkDto(
    val artisanId: String,
    val artisan: ArtisanDto? = null
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
    val materialFamily: String? = null,
    val yearsInUse: Int? = null,
    val height: String? = null,
    val width: String? = null,
    val lengthInches: String? = null,
    val breadthInches: String? = null,
    val heightInches: String? = null,
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
    val craftLinks: List<ToolCraftLinkDto> = emptyList(),
    val artisanLinks: List<ToolArtisanLinkDto> = emptyList(),
    val workshopId: String? = null,
    val status: String = "PENDING",
    val measurementAnalysisStatus: String? = null,
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null,
    val completeness: CompletenessDto? = null
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
    val workshopType: String = "OTHER",
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
    val canRecord: Boolean? = null,
    val questionnaireId: String? = null,
    val questionnaire: QuestionnaireDto? = null,
    val extraMetadata: JsonObject? = null,
    val completeness: CompletenessDto? = null
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
    val answeredBy: UserDto? = null,
    val answerOrigin: String? = null
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
    val workshopId: String? = null,
    val questionnaireId: String? = null,
    val questionnaire: QuestionnaireDto? = null,
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null,
    val interviewDate: String? = null,
    val completeness: CompletenessDto? = null
)

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
    val workshopId: String? = null,
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val clientKey: String? = null
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
    val totalMedia: Int = 0,
    val truncated: Boolean = false
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

@Serializable
data class CompletionMatrixDto(
    val questionnaireId: String? = null,
    val questionnaireTitle: String? = null,
    val sections: List<CompletionSectionDto> = emptyList(),
    val artisans: List<CompletionArtisanDto> = emptyList(),
    val cells: List<CompletionCellDto> = emptyList(),
    val unassignedInterviews: Int = 0,
    val overridesAreRepositoryWide: Boolean = false
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
    val status: String? = null,
    val setByName: String? = null
)

@Serializable
data class CompletionCellRequest(
    val artisanId: String,
    val sectionId: String,
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

@Serializable
data class WorkshopMappingRowDto(
    val id: String = "",
    val title: String = "",
    val workshopId: String? = null,
    val workshopTitle: String? = null,
    val rung: String? = null,
    val rungCopy: String? = null,
    val reason: String? = null,
    val reasonCopy: String? = null,
    val candidateTitles: List<String> = emptyList(),
    val clearCut: Boolean = false
)

@Serializable
data class WorkshopMappingRungCountDto(
    val rung: String = "",
    val copy: String = "",
    val count: Int = 0
)

@Serializable
data class WorkshopMappingReasonCountDto(
    val reason: String = "",
    val copy: String = "",
    val count: Int = 0
)

@Serializable
data class WorkshopMappingWorkshopCountDto(
    val workshopId: String = "",
    val title: String = "",
    val count: Int = 0
)

@Serializable
data class WorkshopMappingBucketDto(
    val bucket: String = "",
    val singular: String = "",
    val plural: String = "",
    val unassigned: Int = 0,
    val resolved: Int = 0,
    val unresolved: Int = 0,
    val clearCut: Int? = null,
    val needsReview: Int? = null,
    val byRung: List<WorkshopMappingRungCountDto> = emptyList(),
    val byReason: List<WorkshopMappingReasonCountDto> = emptyList(),
    val byWorkshop: List<WorkshopMappingWorkshopCountDto> = emptyList(),
    val rows: List<WorkshopMappingRowDto> = emptyList(),
    val rowsTruncated: Boolean = false,
    val applied: Int? = null
)

@Serializable
data class WorkshopMappingWindowDto(
    val id: String = "",
    val title: String = "",
    val start: String = "",
    val end: String = ""
)

@Serializable
data class WorkshopMappingTotalsDto(
    val unassigned: Int = 0,
    val resolved: Int = 0,
    val unresolved: Int = 0,
    val clearCut: Int? = null,
    val needsReview: Int? = null,
    val applied: Int? = null
)

@Serializable
data class WorkshopAutoMapDto(
    val enabled: Boolean = true,
    val runsAt: String = "02:00",
    val timezone: String = "Asia/Kolkata",
    val lastRunAt: String? = null,
    val lastFiled: Int = 0
)

@Serializable
data class WorkshopAutoMapRequest(val enabled: Boolean)

@Serializable
data class WorkshopMappingPlanDto(
    val workshops: List<WorkshopMappingWindowDto> = emptyList(),
    val buckets: List<WorkshopMappingBucketDto> = emptyList(),
    val totals: WorkshopMappingTotalsDto = WorkshopMappingTotalsDto(),
    val autoMap: WorkshopAutoMapDto? = null
)

object WorkshopMappingCounts {
    const val CLEAR_CUT_RUNG = "PARENT"

    fun clearCut(bucket: WorkshopMappingBucketDto): Int =
        bucket.clearCut ?: (bucket.byRung.firstOrNull { it.rung == CLEAR_CUT_RUNG }?.count ?: 0)

    fun needsReview(bucket: WorkshopMappingBucketDto): Int = bucket.needsReview ?: (bucket.unassigned - clearCut(bucket))

    fun suggested(bucket: WorkshopMappingBucketDto): Int = maxOf(0, bucket.resolved - clearCut(bucket))

    fun totalNeedsReview(plan: WorkshopMappingPlanDto): Int =
        plan.totals.needsReview ?: plan.buckets.sumOf { needsReview(it) }

    fun totalClearCut(plan: WorkshopMappingPlanDto): Int = plan.totals.clearCut ?: plan.buckets.sumOf { clearCut(it) }

    fun reviewRungs(bucket: WorkshopMappingBucketDto): List<WorkshopMappingRungCountDto> =
        bucket.byRung.filter { it.rung != CLEAR_CUT_RUNG }

    private fun records(count: Int): String = if (count == 1) "1 record" else "$count records"

    fun autoMapLine(status: WorkshopAutoMapDto?, pending: Int): String {
        if (status == null) return ""
        if (!status.enabled) return if (pending > 0) "${records(pending)} waiting to be filed." else "Off."
        return listOfNotNull(
            "Every night at ${status.runsAt}.",
            if (pending > 0) "${records(pending)} tonight." else null,
            if (status.lastRunAt != null) "Last run filed ${status.lastFiled}." else null
        ).joinToString(" ")
    }
}

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
data class EntryCommentUpdateBody(
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

@Serializable
data class WorkshopSubmissionCheckDto(
    val workshopId: String? = null,
    val title: String? = null,
    val endDate: String? = null,
    val isOver: Boolean = false,
    val outOfWindow: Boolean = false,
    val needsAdminApproval: Boolean = false,
    val assigned: Boolean = true,
    val canSubmit: Boolean = true
)

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
    val status: String = "GRANTED",
    val requestNote: String? = null,
    val decisionNote: String? = null,
    val decidedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val user: UserDto? = null,
    val workshop: WorkshopDetailDto? = null,
    val assignedBy: UserDto? = null,
    val requestedBy: UserDto? = null,
    val decidedBy: UserDto? = null
)

@Serializable
data class WorkshopAssignmentBody(
    val userIds: List<String>,
    val accessLevel: String? = null
)

@Serializable
data class WorkshopAccessRequestBody(
    val workshopIds: List<String>,
    val accessLevel: String? = null,
    val note: String? = null
)

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

@Serializable
data class WorkshopAccessDecisionBody(
    val status: String,
    val accessLevel: String? = null,
    val note: String? = null
)

@Serializable
data class WorkshopGrantBody(
    val userId: String,
    val accessLevel: String? = null,
    val note: String? = null
)

@Serializable
data class WorkshopAssignmentUpdateBody(
    val accessLevel: String? = null,
    val status: String? = null,
    val note: String? = null
)

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

@Serializable
data class TaskDto(
    val id: String,
    val title: String = "",
    val description: String? = null,
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

    val statusLabel: String = "",
    val isAwaitingReview: Boolean? = null,
    val isOutstanding: Boolean? = null,
    val effectivePercent: Int? = null,
    val progressSource: String? = null,
    val progressLabel: String? = null,
    val derivedPercent: Int? = null,
    val derivedArtisanCount: Int? = null,

    val batchId: String? = null,
    val assigneeId: String? = null,
    val assignee: UserDto? = null,
    val createdById: String? = null,
    val createdBy: UserDto? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val artisanIds: List<String> = emptyList(),
    val sectionIds: List<String> = emptyList(),
    val recordType: String? = null,
    val recordId: String? = null
)

@Serializable
data class TaskUpdateBody(
    val status: String? = null,
    val progressCount: Int? = null
)

@Serializable
data class TaskUserDto(
    val id: String,
    val name: String = "",
    val email: String? = null,
    val role: String = "",
    val roleLabel: String = ""
)

@Serializable
data class TaskRecordTypeOptionDto(
    val value: String,
    val label: String = "",
    val pluralLabel: String = ""
)

@Serializable
data class TaskWorkshopOptionDto(
    val id: String,
    val title: String = "",
    val place: String? = null,
    val date: String? = null
)

@Serializable
data class TaskOptionsDto(
    val recordTypes: List<TaskRecordTypeOptionDto> = emptyList(),
    val assignees: List<TaskUserDto> = emptyList(),
    val workshops: List<TaskWorkshopOptionDto> = emptyList(),
    val artisans: List<TaskArtisanDto> = emptyList(),
    val sections: List<TaskSectionDto> = emptyList()
)

@Serializable
data class TaskBatchCreateBody(
    val assigneeIds: List<String>,
    val workshopId: String? = null,
    val recordTypes: List<String> = emptyList(),
    val artisanIds: List<String> = emptyList(),
    val sectionIds: List<String> = emptyList(),
    val targetCount: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val dueAt: String? = null
)

@Serializable
data class TaskBatchAssigneeDto(
    val taskId: String,
    val user: TaskUserDto? = null,
    val status: String = "OPEN",
    val statusLabel: String = "",
    val isAwaitingReview: Boolean? = null,
    val progressCount: Int = 0,
    val derivedCount: Int? = null,
    val percentComplete: Int? = null,
    val effectivePercent: Int? = null,
    val completedAt: String? = null
)

@Serializable
data class TaskBatchDto(
    val key: String,
    val batchId: String? = null,
    val title: String = "",
    val description: String? = null,
    val dueAt: String? = null,
    val createdAt: String? = null,
    val createdBy: TaskUserDto? = null,
    val workshopId: String? = null,
    val workshopTitle: String? = null,
    val recordTypes: List<String> = emptyList(),
    val recordTypeLabels: List<String> = emptyList(),
    val artisans: List<TaskArtisanDto> = emptyList(),
    val sections: List<TaskSectionDto> = emptyList(),
    val targetCount: Int? = null,
    val assigneeCount: Int = 0,
    val statusCounts: Map<String, Int> = emptyMap(),
    val doneCount: Int = 0,
    val openCount: Int = 0,
    val awaitingReviewCount: Int = 0,
    val outstandingCount: Int = 0,
    val overdueCount: Int = 0,
    val reportedTotal: Int = 0,
    val derivedTotal: Int? = null,
    val percentComplete: Int = 0,
    val assignees: List<TaskBatchAssigneeDto> = emptyList()
)

@Serializable
data class TaskBatchResultDto(
    val batchId: String = "",
    val title: String = "",
    val created: Int = 0,
    val batch: TaskBatchDto? = null,
    val tasks: List<TaskDto> = emptyList()
)

@Serializable
data class TaskProgressAssigneeDto(
    val user: TaskUserDto? = null,
    val taskCount: Int = 0,
    val statusCounts: Map<String, Int> = emptyMap(),
    val openCount: Int = 0,
    val awaitingReviewCount: Int = 0,
    val outstandingCount: Int = 0,
    val overdueCount: Int = 0,
    val targetTotal: Int? = null,
    val reportedTotal: Int = 0,
    val derivedTotal: Int? = null,
    val percentComplete: Int = 0,
    val tasks: List<TaskDto> = emptyList()
)

@Serializable
data class TaskProgressReportDto(
    val workshopId: String? = null,
    val workshopTitle: String? = null,
    val assigneeCount: Int = 0,
    val taskCount: Int = 0,
    val doneCount: Int = 0,
    val openCount: Int = 0,
    val awaitingReviewCount: Int = 0,
    val outstandingCount: Int = 0,
    val overdueCount: Int = 0,
    val truncated: Boolean = false,
    val assignees: List<TaskProgressAssigneeDto> = emptyList()
)

@Serializable
data class TaskSummaryDto(
    val assignee: TaskUserDto? = null,
    val workshopId: String? = null,
    val taskCount: Int = 0,
    val statusCounts: Map<String, Int> = emptyMap(),
    val remainingCount: Int = 0,
    val awaitingReviewCount: Int = 0,
    val outstandingCount: Int = 0,
    val approvedCount: Int = 0,
    val cancelledCount: Int = 0,
    val overdueCount: Int = 0,
    val dueSoonCount: Int = 0,
    val nextDueAt: String? = null,
    val percentComplete: Int = 0,
    val measuredCount: Int = 0,
    val derivationSkipped: Boolean = false,
    val truncated: Boolean = false
)

@Serializable
data class ManagedSecretDto(
    val key: String,
    val label: String = "",
    val description: String? = null,
    val configured: Boolean = false,
    val source: String = "unset",
    val hint: String? = null,
    val lastStatus: String = "UNKNOWN",
    val lastCheckedAt: String? = null,
    val lastError: String? = null,
    val updatedBy: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class ManagedSecretRevealDto(
    val key: String,
    val value: String? = null,
    val source: String = "unset"
)

@Serializable
data class ManagedSecretSetBody(
    val value: String
)

@Serializable
data class PreferencesDto(
    val id: String? = null,
    val userId: String? = null,
    val updatedAt: String? = null,
    val theme: String = "system",
    val reducedMotion: Boolean = false,
    val largerText: Boolean = false,
    val highContrast: Boolean = false
) {
    val exists: Boolean get() = id != null
}

@Serializable
data class PreferencesUpdateBody(
    val theme: String = "system",
    val reducedMotion: Boolean = false,
    val largerText: Boolean = false,
    val highContrast: Boolean = false
)

@Serializable
data class SearchTotalsDto(
    val artisans: Int = 0,
    val workshops: Int = 0,
    val products: Int = 0,
    val tools: Int = 0,
    val media: Int = 0
)

@Serializable
data class SearchResultsDto(
    val query: String? = null,
    val page: Int = 1,
    val pageSize: Int = 10,
    val artisans: List<ArtisanDto> = emptyList(),
    val workshops: List<WorkshopDetailDto> = emptyList(),
    val products: List<ProductDetailDto> = emptyList(),
    val tools: List<ToolDetailDto> = emptyList(),
    val media: List<MediaFileDto> = emptyList(),
    val totals: SearchTotalsDto = SearchTotalsDto(),
    val total: Int = 0,
    val pageCount: Int = 1
)

@Serializable
data class DataCrumbDto(
    val name: String = "",
    val path: String = ""
)

@Serializable
data class DataInfoFieldDto(
    val label: String = "",
    val value: String = ""
)

@Serializable
data class DataFolderInfoDto(
    val title: String = "",
    val fields: List<DataInfoFieldDto> = emptyList()
)

@Serializable
data class DataTaxonomyDto(
    val id: String,
    val name: String = "",
    val path: String = "",
    val description: String = "",
    @SerialName("default") val isDefault: Boolean = false
)

@Serializable
data class DataTreeEntryDto(
    val name: String = "",
    val path: String = "",
    val kind: String = "file",
    val recordType: String? = null,
    val mediaType: String? = null,
    val mediaId: String? = null,
    val url: String? = null,
    val sizeBytes: Long? = null,
    val transcriptAvailable: Boolean = false,
    val content: String? = null,
    val transcriptFiles: List<com.fieldrepository.app.transcript.data.DataTranscriptFile> = emptyList()
) {
    val isFolder: Boolean get() = kind == "folder"
}

@Serializable
data class DataTreeDto(
    val path: String = "",
    val crumbs: List<DataCrumbDto> = emptyList(),
    val entries: List<DataTreeEntryDto> = emptyList(),
    val info: DataFolderInfoDto? = null,
    val truncated: Boolean = false,
    val taxonomies: List<DataTaxonomyDto> = emptyList(),
    val taxonomy: String? = null
)

@Serializable
data class DataManifestFileDto(
    val path: String = "",
    val url: String? = null,
    val originalPath: String? = null,
    val content: String? = null,
    val mediaId: String? = null,
    val mediaType: String? = null,
    val convertToMp4: Boolean = false
)

@Serializable
data class DataManifestDto(
    val files: List<DataManifestFileDto> = emptyList(),
    val totalFiles: Int = 0,
    val totalMedia: Int = 0,
    val truncated: Boolean = false
)

@Serializable
data class MapCountsDto(
    val artisans: Int = 0,
    val workshops: Int = 0,
    val products: Int = 0,
    val tools: Int = 0,
    val media: Int = 0
)

@Serializable
data class MapPointDto(
    val key: String = "",
    val layer: String = "",
    val label: String = "",
    val region: String = "",
    val state: String? = null,
    val district: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val precision: String = "",
    val source: String = "",
    val total: Int = 0,
    val counts: MapCountsDto = MapCountsDto(),
    val places: List<String> = emptyList(),
    val pinnedRecords: Int = 0,
    val fromPlaceText: Int = 0,
    val fixes: Int = 0,
    val spreadMetres: Int = 0,
    val medianAccuracy: Double? = null,
    val children: List<MapPointChildDto> = emptyList(),
    val childrenTruncated: Boolean = false
)

@Serializable
data class MapPointChildDto(
    val key: String = "",
    val level: String? = null,
    val layer: String = "",
    val label: String = "",
    val region: String = "",
    val state: String? = null,
    val district: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val precision: String = "",
    val source: String = "",
    val total: Int = 0,
    val counts: MapCountsDto = MapCountsDto(),
    val fixes: Int = 0,
    val spreadMetres: Int = 0
)

@Serializable
data class MapUnplacedDto(
    val label: String = "",
    val total: Int = 0,
    val counts: MapCountsDto = MapCountsDto()
)

@Serializable
data class MapFocusDto(
    val type: String = "",
    val id: String = "",
    val title: String = "",
    val place: String? = null,
    val pointKeys: List<String> = emptyList(),
    val inScope: Boolean = true
)

@Serializable
data class MapAddressCompletenessDto(
    val locations: Int = 0,
    val withState: Int = 0,
    val withDistrict: Int = 0,
    val withPincode: Int = 0,
    val withSubjectPin: Int = 0
)

@Serializable
data class MapSummaryDto(
    val records: Int = 0,
    val byType: MapCountsDto = MapCountsDto(),
    val originRecords: Int = 0,
    val captureRecords: Int = 0,
    val unplacedRecords: Int = 0,
    val originExcludes: List<String> = emptyList(),
    val captureTruncated: Boolean = false,
    val clusterKilometres: Int = 0,
    val address: MapAddressCompletenessDto = MapAddressCompletenessDto(),
    val anchoredDistricts: Int = 0,
    val anchorPins: Int = 0,
    val anchorsTruncated: Boolean = false
)

@Serializable
data class MapPointsDto(
    val scope: String = "",
    val level: String = "",
    val levels: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val points: List<MapPointDto> = emptyList(),
    val unplaced: List<MapUnplacedDto> = emptyList(),
    val focus: MapFocusDto? = null,
    val summary: MapSummaryDto = MapSummaryDto(),
    val childLevel: String? = null
)

@Serializable
data class MapPointRecordDto(
    val type: String = "",
    val id: String = "",
    val title: String = "",
    val place: String? = null,
    val craft: String? = null,
    val status: String = "",
    val createdAt: String? = null
)

@Serializable
data class MapPointRecordsDto(
    val key: String = "",
    val items: List<MapPointRecordDto> = emptyList(),
    val shown: Int = 0,
    val total: Int = 0,
    val cap: Int = 0,
    val truncated: Boolean = false
)

@Serializable
data class AccessRosterDto(
    val id: String = "",
    val accountCreated: Boolean? = null,
    val email: String = "",
    val status: String = "PENDING",
    val grantedRole: String = "CROWDSOURCE_VOLUNTEER",
    val fullName: String? = null,
    val notes: String? = null,
    val joinedAt: String? = null,
    val firstSeenAt: String? = null,
    val requestCount: Int = 0,
    val firstRequestedAt: String? = null,
    val lastRequestedAt: String? = null,
    val decidedAt: String? = null,
    val decidedById: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val userId: String? = null,
    val accountRole: String? = null,
    val accountName: String? = null,
    val accountPromotionHeld: String? = null
)

@Serializable
data class AccessRosterPendingCountDto(
    val pending: Int = 0
)

@Serializable
data class AccessRosterCreateBody(
    val email: String,
    val fullName: String? = null,
    val notes: String? = null,
    val grantedRole: String? = null,
    val isActive: Boolean = true,
    val password: String? = null,
    val mustChangePassword: Boolean? = null
)

@Serializable
data class AccessRosterUpdateBody(
    val email: String? = null,
    val status: String? = null,
    val grantedRole: String? = null,
    val fullName: String? = null,
    val notes: String? = null
)

@Serializable
data class AiModelDto(
    val id: String,
    val label: String = "",
    val note: String = "",
    val tasks: List<String> = emptyList(),
    val inputPricePerMTok: Double? = null,
    val outputPricePerMTok: Double? = null
)

@Serializable
data class AiProviderDto(
    val provider: String,
    val label: String = "",
    val keyPrefix: String? = null,
    val consoleUrl: String = "",
    val pricingUrl: String = "",
    val howTo: List<String> = emptyList(),
    val defaultModel: String = "",
    val models: List<AiModelDto> = emptyList()
)

@Serializable
data class AiCatalogueDto(
    val pricesCheckedOn: String = "",
    val tasks: List<String> = emptyList(),
    val providers: List<AiProviderDto> = emptyList()
)

@Serializable
data class UserAiKeyDto(
    val provider: String,
    val label: String = "",
    val configured: Boolean = false,
    val unreadable: Boolean = false,
    val hint: String? = null,
    val model: String = "",
    val modelKnown: Boolean = true,
    val lastStatus: String = "UNKNOWN",
    val lastCheckedAt: String? = null,
    val lastError: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class UserAiKeySetBody(
    val key: String? = null,
    val model: String? = null
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
data class ChangePasswordResponse(
    val ok: Boolean = false,
)

const val SESSION_TOKEN_HEADER = "X-Session-Token"

@Serializable
data class IssuePasswordLinkRequest(
    val userId: String
)

@Serializable
data class IssuedPasswordLinkDto(
    val id: String = "",
    val link: String = "",
    val expiresAt: String = "",
    val purpose: String = "",
    val deliveredBy: String = ""
)

@Serializable
data class SetPasswordRequest(
    val token: String,
    val password: String
)

@Serializable
data class PasswordLinkCheckRequest(
    val token: String
)

@Serializable
data class PasswordLinkCheckDto(
    val valid: Boolean,
    val reason: String? = null,
    val purpose: String? = null
)

@Serializable
data class InsightQuoteDto(
    val original: String = "",
    val english: String = "",
    val speaker: String? = null
)

@Serializable
data class InsightTranslationDto(
    val status: String = "NOT_NEEDED",
    val text: String? = null,
    val note: String? = null
)

@Serializable
data class InsightProvenanceDto(
    val provider: String = "",
    val modelId: String = "",
    val modelVersion: String? = null,
    val promptVersion: String = "",
    val generatedAt: String? = null
)

@Serializable
data class InsightEditedDto(val at: String? = null)

@Serializable
data class TranscriptInsightDto(
    val id: String = "",
    val version: Int = 0,
    val sourceLanguage: String? = null,
    val summaryLines: List<String> = emptyList(),
    val quotes: List<InsightQuoteDto> = emptyList(),
    val translation: InsightTranslationDto = InsightTranslationDto(),
    val provenance: InsightProvenanceDto = InsightProvenanceDto(),
    val edited: InsightEditedDto? = null
)

@Serializable
data class TranscriptInsightViewDto(
    val mediaId: String = "",
    val state: String = "",
    val stale: Boolean = false,
    val canEdit: Boolean = false,
    val canRegenerate: Boolean = false,
    val error: String? = null,
    val insight: TranscriptInsightDto? = null
)

@Serializable
data class TranscriptInsightBatchDto(val items: Map<String, TranscriptInsightViewDto> = emptyMap())
