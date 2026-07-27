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
    /**
     * RETIRED GRANTS. The server still serves both columns and `PATCH /users` still accepts them,
     * so they stay here to decode — but `can_manage_crafts` / `can_manage_workshops` stopped reading
     * them (deps.py): both powers are Professor-by-rank alone now, because a grant that lifted
     * someone below the taxonomy over it was invisible in the role column. Nothing in this app may
     * consult them for a permission decision; the predicates in AppNavigation.kt and MainActivity.kt
     * are rank-only, and the toggles that set them are gone from the user admin card.
     */
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
    /**
     * The captured coordinate. `GET /artisans` has always sent this — the field was simply not
     * declared here, so it was discarded at parse time and the artisan list was the one record type
     * with no position. The map screen needs it; [ArtisanDetailDto] already carried it.
     *
     * Deliberately the only thing added: this DTO carries no `aadhaarNumber` or `pehchanCardNumber`
     * and must not start to. Any screen that plots artisans reads THIS type precisely so that the
     * identity fields are not in scope to leak.
     */
    val location: LocationDto? = null,
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
    @SerialName("placeName") val placeName: String? = null,
    /*
     * THE STATED ADDRESS — where the SUBJECT is, as a statement by the researcher. Six real columns
     * on Location (see the backend's LocationInput and migration
     * 20260727120000_location_stated_address), all six null unless the researcher supplied them: the
     * API forbids unknown keys but is happy with these absent, and `explicitNulls = false` drops
     * them from the body.
     *
     * [state] and [district] must be canonical names from GET /reference/address — the server
     * validates against the very lists it serves, and resolves the district WITHIN its state because
     * several district names belong to two states at once. [pincode] is the bare six digits, no
     * spaces. [village] is free text, because no closed list of Indian villages exists.
     */
    val state: String? = null,
    val district: String? = null,
    val village: String? = null,
    val pincode: String? = null,
    /**
     * The optional pin the researcher dropped on the SUBJECT'S place.
     *
     * Deliberately not [latitude]/[longitude], which mean the DEVICE. Send the pair or neither — the
     * server refuses half a pin (`_pin_is_a_pair`), because a latitude on its own is 111 km of
     * meridian stored in the one column that exists to be precise.
     */
    val subjectLatitude: Double? = null,
    val subjectLongitude: Double? = null,
    /**
     * When the device took this reading, ISO 8601 with an offset — the "at" in "captured at".
     *
     * A column that has existed on Location since the beginning and that nothing has ever written,
     * which is why every stored coordinate is undated. A reading with no time on it cannot be told
     * apart from a reading taken a month later at a desk in another state, and that is exactly the
     * question the fifteen pilot records leave a reader unable to answer.
     */
    val capturedAt: String? = null,
    /**
     * The stated address AGAIN, in the shape this app used before the four columns above existed.
     *
     * Both shapes are sent on every save and neither may be dropped — see the long note at the top
     * of ui/LocationFields.kt for why, and the server's `lift_stated_address` for the half that
     * normalises them into one. Kept as a raw [JsonObject] rather than a `Map<String, String>` for
     * two reasons: a value this client does not understand round-trips through an edit instead of
     * being dropped on the floor, and a nested blob written by some future writer cannot fail the
     * decode of a whole artisan list.
     */
    val extraMetadata: JsonObject? = null
)

/**
 * `GET /reference/address` — the canonical Indian state / union-territory list and the pincode rule.
 *
 * Fetched rather than hard-coded, deliberately. A list copied into this file would be a second copy
 * of the server's, and the day the two disagree is the day a researcher picks a state the API
 * refuses. The payload is a pure constant server-side, so it costs one request.
 */
@Serializable
data class AddressReferenceDto(
    val version: Int = 1,
    val states: List<String> = emptyList(),
    val unionTerritories: List<String> = emptyList(),
    /** The flat list a single-group dropdown binds to, in the server's own order. */
    val statesAndUnionTerritories: List<String> = emptyList(),
    /**
     * The districts of each state, absent on a server older than reference version 2.
     *
     * Nullable on purpose rather than defaulted to an empty table: the district dropdown has to be
     * able to tell "this deployment does not serve districts yet" from "this state genuinely has
     * none", and only the first of those is worth explaining to the researcher.
     */
    val districts: AddressDistrictsDto? = null
)

/**
 * The district list, shipped inside the address reference so a state dropdown and its district
 * dropdown can never hold two different vintages of the same table.
 *
 * [asOf] and [listVersion] travel with the names so an exported dataset can record which vintage it
 * was coded against — districts are created, renamed and merged several times a year.
 */
@Serializable
data class AddressDistrictsDto(
    val source: String? = null,
    val sourceUrl: String? = null,
    val asOf: String? = null,
    val listVersion: Int = 0,
    val count: Int = 0,
    /** State name to its districts, in the register's own order. */
    val byState: Map<String, List<String>> = emptyMap()
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
    @SerialName("placeName") val placeName: String? = null,
    /**
     * The stated address, column by column; see [LocationRequest] for every rule that governs them.
     *
     * ALL FOUR OF district/village/subjectLatitude/subjectLongitude WERE MISSING HERE, and their
     * absence was silent data loss rather than a gap in a read model. `LocationDto.toRequest()`
     * builds the body an edit re-sends, `attach_location` writes a BRAND NEW Location row from that
     * body, and `forbid_clearing_location` deliberately does not demand a stated address on update —
     * so a colleague opening a web-entered record on the phone to fix a phone number PATCHed the
     * district, the village and the pin away, successfully, with nothing on screen to say so.
     */
    val state: String? = null,
    val district: String? = null,
    val village: String? = null,
    val pincode: String? = null,
    val subjectLatitude: Double? = null,
    val subjectLongitude: Double? = null,
    /** When the device took this reading; see [LocationRequest.capturedAt]. */
    val capturedAt: String? = null,
    /** The pre-column shape of the stated address; see [LocationRequest.extraMetadata]. */
    val extraMetadata: JsonObject? = null
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
    val createdAt: String? = null,
    val updatedAt: String? = null,
    // The raw scope-id columns, next to the resolved [artisans]/[sections] above. An admin screen
    // needs the ids to pre-tick a picker; the resolved rows are only what the server could still find.
    val artisanIds: List<String> = emptyList(),
    val sectionIds: List<String> = emptyList(),
    // Legacy single-record link ("finish THIS product"), orthogonal to the scope block.
    val recordType: String? = null,
    val recordId: String? = null
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

// ---------------------------------------------------------------------------
// Task ADMINISTRATION (admin / master admin): hand work out, then hold it to
// account. Every route behind these DTOs is `require_admin` server-side.
// ---------------------------------------------------------------------------

/**
 * `user_brief()` — just enough to name a person on a task board, never any privilege material.
 *
 * Distinct from [UserDto] on purpose: the brief carries [roleLabel] ("Master Admin") and carries
 * none of the permission booleans, so it can never be mistaken for an authorisation source.
 */
@Serializable
data class TaskUserDto(
    val id: String,
    val name: String = "",
    val email: String? = null,
    val role: String = "",
    /** Human label for [role] as the server words it — use this, don't re-derive it on the client. */
    val roleLabel: String = ""
)

/** One entry of the record-type catalogue: `{value:"tool", label:"tool", pluralLabel:"tools"}`. */
@Serializable
data class TaskRecordTypeOptionDto(
    val value: String,
    val label: String = "",
    val pluralLabel: String = ""
)

/** A workshop as the assignment picker shows it (not the full [WorkshopDetailDto]). */
@Serializable
data class TaskWorkshopOptionDto(
    val id: String,
    val title: String = "",
    val place: String? = null,
    val date: String? = null
)

/**
 * `GET /tasks/options` — every picker the assignment builder needs, in one call.
 *
 * [assignees] is already filtered to the people THIS admin may assign to (strictly below their own
 * tier; the master admin sees everyone but themselves), so the client must never widen it.
 * [artisans] narrows to the workshop when `workshopId` was passed.
 */
@Serializable
data class TaskOptionsDto(
    val recordTypes: List<TaskRecordTypeOptionDto> = emptyList(),
    val assignees: List<TaskUserDto> = emptyList(),
    val workshops: List<TaskWorkshopOptionDto> = emptyList(),
    val artisans: List<TaskArtisanDto> = emptyList(),
    val sections: List<TaskSectionDto> = emptyList()
)

/**
 * `POST /tasks/batch` — assign ONE scope to several people at once. Writes one row per assignee, all
 * sharing a generated `batchId`.
 *
 * The scope must contain work: `recordTypes` and/or `sectionIds` non-empty, or the API 422s. Empty
 * [artisanIds]/[sectionIds] mean "not narrowed", not "nothing" — and because the JSON encoder skips
 * values equal to their default, an empty list is simply not sent, which is exactly that meaning.
 * [title] is optional: omit it and the server derives a readable one from the scope. [dueAt] is
 * ISO-8601 (e.g. `2026-08-01T00:00:00Z`).
 */
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

/** One member of a batch: who it went to and where they have got to. */
@Serializable
data class TaskBatchAssigneeDto(
    val taskId: String,
    val user: TaskUserDto? = null,
    val status: String = "OPEN",
    val progressCount: Int = 0,
    val derivedCount: Int? = null,
    /** Null for an open-ended task (no target) that is neither DONE nor CANCELLED. */
    val percentComplete: Int? = null,
    val completedAt: String? = null
)

/**
 * `GET /tasks/batches` item — one assignment ACTION rolled back up.
 *
 * [batchId] is null for rows written before batching existed and for single-assignee creates; use
 * [key] (the batchId, or `task:{id}`) as the stable list key. The counts always describe the WHOLE
 * batch even when the request filtered by assignee or status — "3 of 5 done" stops meaning anything
 * if the filter silently dropped two of the five.
 *
 * [reportedTotal] is what the assignees CLAIM; [derivedTotal] is what the repository can actually
 * find them having produced (null when the counts could not be run). The gap is the point.
 */
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
    /** OPEN / IN_PROGRESS / DONE / CANCELLED -> how many of the batch's rows are in that state. */
    val statusCounts: Map<String, Int> = emptyMap(),
    val doneCount: Int = 0,
    val openCount: Int = 0,
    val overdueCount: Int = 0,
    val reportedTotal: Int = 0,
    val derivedTotal: Int? = null,
    val percentComplete: Int = 0,
    val assignees: List<TaskBatchAssigneeDto> = emptyList()
)

/** `POST /tasks/batch` response: the new batch plus every row it wrote. */
@Serializable
data class TaskBatchResultDto(
    val batchId: String = "",
    val title: String = "",
    val created: Int = 0,
    val batch: TaskBatchDto? = null,
    val tasks: List<TaskDto> = emptyList()
)

/** One person's line on the accountability rollup, with their tasks attached. */
@Serializable
data class TaskProgressAssigneeDto(
    val user: TaskUserDto? = null,
    val taskCount: Int = 0,
    val statusCounts: Map<String, Int> = emptyMap(),
    val openCount: Int = 0,
    val overdueCount: Int = 0,
    /** Sum of the quotas they were given; null when none of their tasks carries one. */
    val targetTotal: Int? = null,
    val reportedTotal: Int = 0,
    val derivedTotal: Int? = null,
    val percentComplete: Int = 0,
    val tasks: List<TaskDto> = emptyList()
)

/**
 * `GET /tasks/progress` — the accountability rollup: who has what, and how far along they really are.
 *
 * [truncated] is true when the 2000-row scan window was hit, which makes this a PARTIAL picture; say
 * so rather than presenting it as the whole truth. Assignees arrive busiest-outstanding first.
 */
@Serializable
data class TaskProgressReportDto(
    val workshopId: String? = null,
    val workshopTitle: String? = null,
    val assigneeCount: Int = 0,
    val taskCount: Int = 0,
    val doneCount: Int = 0,
    val openCount: Int = 0,
    val overdueCount: Int = 0,
    val truncated: Boolean = false,
    val assignees: List<TaskProgressAssigneeDto> = emptyList()
)

// ---------------------------------------------------------------------------
// Managed provider keys (MASTER ADMIN ONLY). Every /secrets route is behind
// `require_master_admin`, not merely `require_admin`.
// ---------------------------------------------------------------------------

/**
 * One manageable API key. This shape NEVER carries the value — only a four-character [hint] — so a
 * list screen can be rendered without a credential ever reaching it. Only [ManagedSecretRevealDto]
 * carries plaintext, and fetching it is audit-logged server-side.
 *
 * [source] is `database` (an override is stored here), `environment` (only the deployed env var) or
 * `unset`. [lastStatus] is `UNKNOWN` / `OK` / `FAILED` and only changes when a test is run.
 */
@Serializable
data class ManagedSecretDto(
    val key: String,
    val label: String = "",
    val description: String? = null,
    /** True when the key resolves to something at all — stored override OR environment value. */
    val configured: Boolean = false,
    val source: String = "unset",
    /** Last four characters of the effective value, so two keys can be told apart safely. */
    val hint: String? = null,
    val lastStatus: String = "UNKNOWN",
    val lastCheckedAt: String? = null,
    val lastError: String? = null,
    /** Display name (or email) of whoever last saved the override; null for environment values. */
    val updatedBy: String? = null,
    val updatedAt: String? = null
)

/**
 * `GET /secrets/{key}/reveal` — the eye button, one key at a time. [value] is the value actually in
 * force (the stored override, else the environment value), and null when the key is unset or a
 * stored value can no longer be decrypted.
 */
@Serializable
data class ManagedSecretRevealDto(
    val key: String,
    val value: String? = null,
    val source: String = "unset"
)

/** `PUT /secrets/{key}` — set or rotate one key. Blank is a 422; use DELETE to fall back to the env. */
@Serializable
data class ManagedSecretSetBody(
    val value: String
)

// ---------------------------------------------------------------------------
// Per-user appearance + accessibility preferences.
// ---------------------------------------------------------------------------

/**
 * `GET /preferences/me`, `PUT /preferences/me`.
 *
 * The GET returns an EMPTY OBJECT when the account has never saved any — which decodes here to a
 * row with a null [id]. Read that as "this account has no opinion yet" (see [exists]) and keep
 * whatever the device already applied, seeding the server with it, rather than snapping the user
 * back to the defaults below.
 *
 * [theme] is `system` | `light` | `dark`; anything else is a 422 on save.
 */
@Serializable
data class PreferencesDto(
    val id: String? = null,
    val userId: String? = null,
    val updatedAt: String? = null,
    val theme: String = "system",
    /** Force reduced motion. ORs with the OS setting; it can never switch the OS preference off. */
    val reducedMotion: Boolean = false,
    val largerText: Boolean = false,
    val highContrast: Boolean = false
) {
    /** False when the server returned `{}` — the account has no saved row yet. */
    val exists: Boolean get() = id != null
}

/** `PUT /preferences/me`. Sent WHOLE on every save: an omitted field falls back to off/system. */
@Serializable
data class PreferencesUpdateBody(
    val theme: String = "system",
    val reducedMotion: Boolean = false,
    val largerText: Boolean = false,
    val highContrast: Boolean = false
)

// ---------------------------------------------------------------------------
// Global search.
// ---------------------------------------------------------------------------

/** Per-bucket match counts for the CURRENT filters — the whole result set, not just this page. */
@Serializable
data class SearchTotalsDto(
    val artisans: Int = 0,
    val workshops: Int = 0,
    val products: Int = 0,
    val tools: Int = 0,
    val media: Int = 0
)

/**
 * `GET /search` — five buckets sharing one page/pageSize.
 *
 * Each bucket is its own slice of its own result set, so a page can be full in one bucket and empty
 * in another; [totals] is how many matches each bucket has in total and [pageCount] is the last page
 * of the LONGEST bucket (at least 1, so an empty result still reads as "page 1 of 1"). Every row is
 * already filtered by what the caller is allowed to see.
 */
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
    /** Every bucket's matches added together. */
    val total: Int = 0,
    val pageCount: Int = 1
)

// ---------------------------------------------------------------------------
// Data browser: a lazily-explorable file-system view over the repository.
// Gated by the dataset-download permission AND by row visibility.
// ---------------------------------------------------------------------------

/** One breadcrumb. The server resolves clean names, so never derive these from the path segments. */
@Serializable
data class DataCrumbDto(
    val name: String = "",
    val path: String = ""
)

/** One labelled field of a record folder's info card. Both sides are already display-ready text. */
@Serializable
data class DataInfoFieldDto(
    val label: String = "",
    val value: String = ""
)

/** The info card shown on a record folder (workshop / artisan / product / tool / process / interview). */
@Serializable
data class DataFolderInfoDto(
    val title: String = "",
    val fields: List<DataInfoFieldDto> = emptyList()
)

/**
 * One of the three ways the same repository can be browsed, served with EVERY tree level so the
 * client can offer the other two without a second call. [isDefault] marks the one to open on.
 */
@Serializable
data class DataTaxonomyDto(
    val id: String,
    val name: String = "",
    val path: String = "",
    val description: String = "",
    @SerialName("default") val isDefault: Boolean = false
)

/**
 * One row of a tree level. [kind] is `folder` or `file`.
 *
 * A folder carries [recordType] (`workshop`, `artisan`, `product`, `tool`, `process`, `interview`,
 * `craft`, `category`, `taxonomy`, ...) and is opened by re-requesting the tree at its [path].
 * A file is either GENERATED TEXT — [content] holds the whole body inline (details.txt, answers.txt,
 * notes.txt, *.transcript.md), nothing to download — or a real media object, in which case
 * [mediaId] / [mediaType] / [url] / [sizeBytes] are set. [transcriptAvailable] means that media row
 * carries transcript text.
 */
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
    val content: String? = null
) {
    val isFolder: Boolean get() = kind == "folder"
}

/**
 * `GET /data/tree?path=` — ONE level of the virtual tree (lazy: only this level's queries run).
 *
 * The root (`path=""`) is not a folder listing but the taxonomy chooser. [info] is populated on
 * record folders and null everywhere else. [truncated] is true when the 500-row per-level cap was
 * hit. [taxonomy] is which taxonomy the current path sits in, and null at the root.
 */
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

/**
 * One file of a flattened subtree. [path] is relative to the requested folder and is what a zip
 * entry should be named.
 *
 * Exactly one of [content] (generated text, inline) and [url] (an object to fetch) is meaningful.
 * When [convertToMp4] is true the entry is audio that the SERVER will re-encode: fetch
 * `data/media/{mediaId}/download?format=mp4`, and only fall back to [url] (the original, named
 * [originalPath]) if that fails.
 */
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

/**
 * `GET /data/manifest?path=&include=` — the flattened subtree below a path, for client-side zipping.
 * [truncated] is true when the walk hit its depth/file ceiling.
 */
@Serializable
data class DataManifestDto(
    val files: List<DataManifestFileDto> = emptyList(),
    val totalFiles: Int = 0,
    val totalMedia: Int = 0,
    val truncated: Boolean = false
)
