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

/**
 * `GET /dashboard/stats`.
 *
 * THE TOP-LEVEL TOTALS ARE THE REPOSITORY'S, not the caller's uploads. They used to be filtered by
 * row visibility, so below Professor "Artisans" meant "artisans you entered" while the label said
 * otherwise — an account that had uploaded nothing read a screen of zeroes indistinguishable from an
 * empty repository. Reading is open now, so these are the true totals and the caller's own
 * contribution is answered separately in [mine]. Never render one under the other's label.
 */
@Serializable
data class DashboardStats(
    val totalArtisans: Int = 0,
    val totalWorkshops: Int = 0,
    val totalProductRecords: Int = 0,
    val totalToolRecords: Int = 0,
    val totalMediaFiles: Int = 0,
    val pendingSubmissions: Int = 0,
    /**
     * This account's own contribution, same keys as above so both rows render from one template.
     *
     * NULLABLE ON PURPOSE: a device can be talking to an API deployed before this block existed, and
     * a missing `mine` must read as "this server does not answer that question" — hide the row —
     * rather than as six honest zeroes, which would tell the user their work is gone.
     */
    val mine: DashboardStatsMine? = null,
    /** The repository's newest entries across the four record types, newest first, capped at ten. */
    val recentSubmissions: List<DashboardRecentSubmissionDto> = emptyList()
)

/** The caller's own uploads. Deliberately the SAME field names as [DashboardStats]'s totals. */
@Serializable
data class DashboardStatsMine(
    val totalArtisans: Int = 0,
    val totalWorkshops: Int = 0,
    val totalProductRecords: Int = 0,
    val totalToolRecords: Int = 0,
    val totalMediaFiles: Int = 0,
    val pendingSubmissions: Int = 0
)

/**
 * One row of the dashboard's recent-activity list. [type] is the SINGULAR record type — `artisan`,
 * `workshop`, `product`, `tool` — because it names one row, not a search bucket.
 *
 * [title] is whichever name column the record type carries, so it is null for a row that has none.
 * [createdByName] is whose work it is: the list could once only hold the reader's own rows, so it
 * went unshown; now that the list is the repository's, an unattributed title is one nobody can
 * follow up on. Still nullable — the account may have been deleted.
 */
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
    val message: String? = null,
    /**
     * THE SERVER'S OWN METHOD MARKER, RELAYED VERBATIM AND NEVER REBUILT.
     *
     * A `JsonObject` rather than a typed class ON PURPOSE, and this is the one field in this file
     * where that is the right answer. The marker's key set is deliberately OPEN server-side — the
     * validator accepts keys it does not know — precisely so a handset can relay a NEWER server's
     * extra key through a save without being refused mid-deploy. A data class here would close the
     * set again at exactly the layer that must not close it: `ignoreUnknownKeys = true` would drop
     * the new key in silence, the handset would save a poorer marker than the browser did for the
     * same act, and nothing anywhere would report it.
     *
     * WHAT ITS ABSENCE COST UNTIL 2026-09-14. This field did not exist, so the handset had nothing
     * to echo and `visionMarker(null)` fell back to a bare `{"method": "VISION_MODEL"}` on every
     * grid reading. The browser, reading the same endpoint, stored `provider`, `modelId` AND
     * `selfReportedConfidence`. Same researcher, same photograph, same button — a weaker record from
     * the handset, and the missing key was the confidence, which is the only number on the stamp.
     *
     * Null from a server that predates the marker and on every failure path, which is why
     * `visionMarker` still keeps its fallback rather than assuming this is populated.
     */
    val methodMarker: JsonObject? = null
)

@Serializable
data class MediaFileDto(
    val id: String,
    val originalFilename: String,
    val mediaType: String,
    val mimeType: String? = null,
    /**
     * The fetchable object URL — ABSENT WHENEVER THIS ACCOUNT MAY NOT TAKE THE FILE, which is now a
     * routine state rather than only the sign of an incomplete upload.
     *
     * Reading the repository is open to every signed-in account, so every media ROW travels: name,
     * type, caption, transcript, parent record, uploader. But a URL is not a description of a file, it
     * IS the file, so the server withholds it (and `objectKey`, from which it can be rebuilt) unless
     * the caller may download that uploader's data — themselves, a professor or above, a holder of the
     * dataset-download permission, or someone the uploader granted access to. See
     * `records._MEDIA_URL_KEYS`.
     *
     * So treat null as "not for you" OR "not uploaded yet" and offer no player either way. It is not an
     * error and must never be reported as one.
     */
    val url: String? = null,
    val caption: String? = null,
    val transcriptStatus: String? = null,
    val transcriptText: String? = null,
    val transcriptError: String? = null,
    /**
     * WHEN A PERSON LAST REPLACED THE TRANSCRIPT, and NULL MEANS "NOT STATED" — never "never
     * edited". `POST /media/{id}/transcript` has been able to replace a transcript since long before
     * this column existed, so rows stored before it genuinely do not say.
     *
     * RENDER THREE STATES AND NOT TWO: edited, not edited, and silent. Collapsing the third into
     * "not edited" prints "the machine said this" over text a researcher may well have typed, which
     * is the single assertion this column was added to stop being made silently.
     */
    val transcriptEditedAt: String? = null,
    /** Who made that edit. A bare id with no relation — an audit stamp, not a navigable edge. */
    val transcriptEditedById: String? = null,
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
    /**
     * DESIGN_PROTOTYPE or OTHER — which kind of workshop this is.
     *
     * `String? = null` AND NOT `String = "OTHER"`, which is the sharp part. This same class is the
     * PATCH body (`FieldRepositoryApi.updateWorkshop` takes it), `ApiClient.json` has
     * `explicitNulls = false`, so a null is DROPPED from the payload — and an omitted key on a PATCH
     * means "leave the stored kind alone". A non-null default would make every correction re-assert
     * OTHER and quietly demote a workshop somebody had marked DESIGN_PROTOTYPE on the web.
     *
     * It is also the rule every field added to a `*CreateRequest` follows, for a second reason:
     * `offlineJson` (FieldRepository.kt) decodes a queued body with neither `coerceInputValues` nor
     * `explicitNulls = false`, so a non-null-with-default field turns an explicit `null` in a
     * fortnight-old `queue.json` into a `SerializationException` that parks the entry as a permanent
     * failure the researcher reads as "the server refused this" about a record the server never saw.
     */
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
    /**
     * ── THE CREATE-IDEMPOTENCY KEY. THIS IS THE CANONICAL COPY OF THE KDOC ────────────────────
     *
     * A queued create is POSTed, the server writes the row, and the answer is lost on the way back —
     * a tunnel, a captive portal, the OS killing the process mid-request. This handset learned
     * nothing, so the entry is still in `queue.json` and the next sync sends it again. With a key on
     * the body, the second landing is answered from the row the first one wrote; without one it
     * becomes a second government record of the same fieldwork, under one researcher's name, in a
     * register nobody reconciles.
     *
     * [PendingEntry.createdId] CANNOT CLOSE THIS AND SAYS SO. It is a record of a REPLY — "non-null
     * means the record IS on the server" — so it is empty in exactly the case this key exists for.
     * It is also a fact one queue file holds about its own send: a queue restored onto a second
     * handset, or drained after a sign-out and back in, has no such record and this key still works.
     *
     * IT IS MINTED AT QUEUE TIME AND MERGED ONTO THE BODY AT REPLAY, never stored inside
     * `payloadJson` — that string is the form's own serialisation of what the user saved, and a key
     * is bookkeeping about the SEND rather than something the user saved.
     *
     * THREE CONSEQUENCES OF THE `String? = null`, AND THE SECOND IS THE SHARP ONE.
     *  1. AN OLDER BUILD'S ENTRY REPLAYS UNCHANGED. Null copies as null and `explicitNulls = false`
     *     drops the key, so the body is byte-identical to what this app has always sent.
     *  2. A CORRECTION SENDS NOTHING, WHICH IS THE SHARP ONE. This same class is the PATCH body —
     *     `FieldRepositoryApi.updateWorkshop` takes it — and the server's UPDATE schemas do not
     *     declare `clientKey`. Every request model is `extra="forbid"`, so a correction carrying a
     *     key would be `extra_forbidden`: a 422 the queue reads as a disagreement between builds and
     *     re-attempts once per app run, for ever, on a prepaid connection. It cannot happen, because
     *     the key is never written into a correction's payload and a null is dropped — but it is the
     *     reason this field must never gain a non-null default.
     *  3. A FORTNIGHT-OLD QUEUE STILL DECODES. `offlineJson` (FieldRepository.kt) has neither
     *     `coerceInputValues` nor `explicitNulls = false`, so a non-null-with-default field would
     *     turn an explicit `null` in an old `queue.json` into a `SerializationException` — recorded
     *     as a permanent failure on an entry the server never saw.
     *
     * THE DEPLOY ORDER THIS DEPENDS ON. The backend ships first and independently
     * (`.github/workflows/deploy-backend.yml`, on push to main); no APK carrying this field exists
     * until the next tagged release. A key sent to a server whose schema does not declare it is a 422
     * on the WHOLE save, and a 4xx is not re-queued — the refused save loses the record. Never cut an
     * APK carrying `clientKey` or `workshopType` before the backend that declares them is live.
     */
    val clientKey: String? = null
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
    /**
     * ── THE TWO FACTS THE DESIGN WORKSHOP ASKS OF EVERY ARTISAN ──────────────────────────────
     *
     * Both were read only from legacy `extraMetadata` spellings this app stopped writing years ago,
     * so the artisan record sheet printed two permanently empty cells and nothing in the product
     * could record either fact.
     *
     * A DATE, NOT AN AGE: the record sheet shows an age and the server derives it from this every time
     * it is read, because an age written down is wrong within a year and nothing would notice.
     * Sent as `yyyy-MM-dd`; the API accepts a bare date.
     */
    val dateOfBirth: String? = null,
    /**
     * THE DAY THIS ARTISAN BEGAN PRACTISING, and the feeder the record sheet's experience figure is
     * derived from. Sent as `yyyy-MM-dd`; the API accepts a bare date.
     *
     * Same argument [dateOfBirth] makes, one column later: a stated NUMBER of years is right on the
     * day it is typed and silently wrong from then on, and the sheet that prints it is read years
     * after the visit. [experienceYears] below is still collected and still read — an artisan who
     * says "about thirty years" and cannot name a year must stay recordable — but where a date
     * exists the server derives from it in preference.
     */
    val craftStartDate: String? = null,
    /** Years practising the craft. 0..90, the same bound the sibling repository uses. */
    val experienceYears: Int? = null,
    /**
     * The odd months on top of those years, 0..11 — a REMAINDER and never a total; twelve months is
     * a year the field above already holds. Bounded by the server AND by
     * `CHECK ("experienceMonths" BETWEEN 0 AND 11)` on the column.
     *
     * `Int? = null` and not `Int = 0`: absent and zero are different answers, and an artisan asked
     * only about years said nothing whatever about months. It also has to be nullable for the reason
     * every field on this class does — see [clientKey] on the create requests below.
     */
    val experienceMonths: Int? = null,
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
    /**
     * HOW THOSE THREE WERE MEASURED, for the ones somebody accepted a machine's proposal into.
     *
     * `{lengthInches: {method: "PHOTO_GEOMETRY", technique: "SCALE"}, …}` — built by
     * `MeasurementMarkers.body`, which is the only thing allowed to build it. A raw [JsonObject] and
     * not a typed class on purpose: the server documents a marker's key set as OPEN so that a handset
     * relaying a NEWER server's extra key is not refused mid-deploy, and a typed model here would
     * either drop that key silently or fail to decode it. The one shape this client composes itself is
     * `PHOTO_GEOMETRY`; a `VISION_MODEL` marker is the server's own, echoed back unchanged.
     *
     * ── THE KEY IS OMITTED WHEN THERE IS NOTHING TO SAY, AND THAT IS LOAD-BEARING ─────────────
     *
     * `MeasurementMarkers.body` answers null, and `ApiClient.json` is `explicitNulls = false`, so the
     * key is DROPPED from the request entirely — every record whose dimensions were typed off a tape
     * goes out on exactly the bytes it went out on before this field existed. That matters because the
     * web and the API deploy separately, so a newer client meets an older server: its request models
     * forbid unknown keys, so `"measurementMethods": null` on the body would be a 422 on the WHOLE
     * save, and the outbox will not queue a 4xx. The researcher's form would be neither saved nor
     * retried. An ABSENT key is refused by nothing, ever, and an absent marker means UNRECORDED —
     * never TYPED.
     *
     * AND THIS IS ALSO THE OFFLINE PATH. This body is what `offlineFormJson.encodeToString` puts in the
     * outbox and what `syncOutbox` decodes back out before posting, so a record saved in a courtyard
     * keeps its provenance instead of arriving a fortnight later indistinguishable from a hand-typed
     * one. `offlineFormJson` does NOT set `explicitNulls = false`, so a stored payload carries
     * `"measurementMethods": null` — which is harmless precisely because it is decoded back into this
     * field and re-encoded by the Retrofit converter on the way out. Nothing ever posts the stored
     * string as a body.
     *
     * ⚠ A field missing from this class is dropped SILENTLY: `ApiClient.json` is
     * `ignoreUnknownKeys = true`, so there is no error anywhere to tell you the marker never left.
     */
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
    /** The create-idempotency key. See [WorkshopCreateRequest.clientKey] for the whole argument. */
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
    val yearsInUse: Int? = null,
    val height: Double? = null,
    val width: Double? = null,
    val lengthInches: Double? = null,
    val breadthInches: Double? = null,
    /**
     * The measured height IN INCHES — the third of the triple, and the only height on this model
     * that records its unit. [height] above is the old unit-less column, kept for what is already
     * stored; nothing in the database can say what unit those values are in, which is exactly why
     * they were never copied across. The grid-measurement panel returns an inches reading and must
     * fill THIS field: until the column existed the only box it could reach was the unit-less one.
     */
    val heightInches: Double? = null,
    /**
     * HOW `lengthInches` / `breadthInches` / `heightInches` WERE MEASURED — see the identical field on
     * [ProductCreateRequest] for the whole argument, including why the key is omitted rather than sent
     * as null and why that is a data-loss question rather than a style one.
     *
     * ONE THING IS SPECIFIC TO THIS MODEL: [height] above — the unit-less legacy column — is NOT a
     * dimension this may describe. `MEASUREMENT_DIMENSIONS` holds exactly the three inch columns, and
     * a marker naming `height` is refused BY NAME with a 422 rather than dropped. That is also why
     * `TOOL_MEASURE_DIMENSIONS` never offers it as a measurement destination: nothing in the database
     * can say what unit the values already in it are in.
     */
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
    /**
     * THE CRAFTS AND ARTISANS THIS TOOL IS LINKED TO, PLURAL — the tool form's two multi-selects.
     *
     * NOT COLUMNS. The route pops both and writes `ToolCraft` / `ToolArtisan` rows; [craftId] and
     * [artisanId] above stay exactly where they are and are DERIVED from element 0 when these are
     * present and non-empty, so every existing filter, index, report and carry-forward that reads
     * the singular column keeps reading the same value. `craftName` is likewise re-derived by the
     * server as the selected names joined ", " IN THIS ORDER, which is why the list is ordered and
     * why nothing here sorts it.
     *
     * `artisanIds` is spelled exactly as [ToolArtisanAssignRequest.artisanIds] already spells it,
     * because it is the same list of the same ids meaning the same thing.
     *
     * ── NULL IS NOT THE SAME AS EMPTY, AND THE SERVER REFUSES ONE OF THEM ──────────────────────
     *
     * Absent means "leave the links alone"; `[]` means "no links". The server answers a LITERAL
     * `"craftIds": null` with a 422, because it cannot tell that apart from absent otherwise, and
     * the whole contract rests on the distinction. Two things make the null unreachable from here
     * and both must stay true:
     *
     *  • `ApiClient.json` is `explicitNulls = false`, so a null default is dropped from the body
     *    rather than written as `null`. This is the same property `measurementMethods` above relies
     *    on, and for the same 422.
     *  • The OFFLINE QUEUE stores the body with `encodeDefaults = true` and `explicitNulls` at its
     *    default, so a queued blob genuinely does hold `"craftIds": null` — and that is harmless
     *    precisely because the replay DECODES it back into this class and re-encodes it through the
     *    Retrofit converter (`FieldRepository.kt`, `offlineJson.decodeFromString<ToolCreateRequest>`),
     *    where `explicitNulls = false` drops it again. Do NOT add `@EncodeDefault` to either field:
     *    the argument that earned `maker` one — "the one edit that cannot be saved is the edit BACK
     *    to the default" — does not apply, because `[]` and null are different values here and `[]`
     *    is what a clearing client sends.
     *
     * WHO SENDS THEM, AND WHEN. The tool form sends a list when it CHANGED that list — an emptied
     * picker included, because `[]` is a real answer — and omits the key entirely when the picker was
     * never touched. It used to send both on every save, which made an edit about Remarks a request
     * to rewrite two relations, and both of this route's gates then fired on the key's PRESENCE:
     * `_may_manage_tool_links` left the professor clause out of its copy of the edit rule, and
     * `assert_can_contribute_relation` asked only whether the stored relation was populated without
     * comparing, so a byte-identical list was refused as a replacement. The backend has since fixed
     * both (it delegates to `record_edit_privilege`, and the guard now asks whether the relation is
     * populated AND changing) — this client still sends only what it changed because a fielded APK
     * meets whatever server is deployed, and because "absent" is the contract's own word for a
     * relation nobody touched. A queued body still means exactly the form it was written from.
     */
    val craftIds: List<String>? = null,
    val artisanIds: List<String>? = null,
    val workshopId: String? = null,
    val status: String = "PENDING",
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null,
    /** The create-idempotency key. See [WorkshopCreateRequest.clientKey] for the whole argument. */
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

/**
 * ONE NAMED INSTRUMENT. Two exist — the 2nd Craft Toolkit Workshop's (24 sections, RESP/A..W) and
 * the 3rd's (22 sections, A..V) — and their section CODES collide completely, so a section is only
 * ever identified by its id or by (questionnaireId, code), never by its code alone.
 *
 * `isDefault` is where every request that names no instrument lands, which INCLUDES every build of
 * this app that shipped before 2026-09-13 and every payload sitting in an outbox written by one.
 */
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
    // Present for parity with frontend/lib/types.ts `Questionnaire`. ignoreUnknownKeys means a field
    // missing HERE is silently never seen, so the two files are diffed field by field and kept equal
    // even where this client has no screen for the value yet.
    val workshopCount: Int = 0,
    val createdAt: String? = null
)

@Serializable
data class QuestionnaireQuestionDto(
    val id: String,
    /**
     * NULLABLE WITH A DEFAULT even though the server always sends it, so a handset running this
     * build against an older backend still decodes the list instead of failing all of it.
     */
    val questionnaireId: String? = null,
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
    /** See [QuestionnaireQuestionDto.questionnaireId] for why this is nullable. */
    val questionnaireId: String? = null,
    val code: String,
    val title: String,
    val sortOrder: Int,
    val isActive: Boolean = true,
    val questions: List<QuestionnaireQuestionDto> = emptyList()
)

@Serializable
data class QuestionnaireSectionCreateRequest(
    /**
     * WHICH INSTRUMENT the new section joins. Optional on the wire: the server resolves the default
     * when it is absent, which is what keeps an un-updated builder working the day the backend
     * deploys. This build always sends it, because the builder screen knows which instrument it is
     * showing and adding a section to a different one is the exact mistake the field prevents.
     */
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
 *
 * `questionnaireId` IS DIFFERENT AND IS SENT. It says which instrument the researcher was looking
 * at, and it is written when the interview is QUEUED rather than resolved when it is sent — an
 * outbox entry may replay days later, and by then the server-side default may have moved. When it
 * is absent (an older build, or an older queued payload) the server resolves it: the workshop's
 * bound instrument, else the default. NEVER default it to a hard-coded id on this side; the handset
 * does not know which instrument was current when the researcher sat down, and a guess is exactly
 * the silent mis-filing the container model exists to prevent.
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
    // Which instrument this sitting is on. See the note above this class.
    val questionnaireId: String? = null,
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
    val dateOfBirth: String? = null,
    /** The day this artisan began practising. See [ArtisanCreateRequest.craftStartDate]. */
    val craftStartDate: String? = null,
    val experienceYears: Int? = null,
    /** The odd months on top of the years, 0..11. See [ArtisanCreateRequest.experienceMonths]. */
    val experienceMonths: Int? = null,
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

/**
 * One row of the `ToolCraft` join table, as `GET /tools` hydrates it.
 *
 * Shaped like [WorkshopCraftLinkDto] on purpose — the id and the craft, and not the link row's own
 * `id`/`createdAt`, which no screen reads. The SERVER decides the order (it returns these in the
 * order the names appear in `craftName`) and this client preserves it: `craftIds` is an ordered wire
 * contract, so re-sorting the list here would change what the next save stores.
 */
@Serializable
data class ToolCraftLinkDto(
    val craftId: String,
    val craft: CraftDto? = null
)

/**
 * One row of the `ToolArtisan` join table, as `GET /tools` hydrates it. Shaped like [ToolCraftLinkDto].
 *
 * ── THE HEAD IS THE LINK `artisanId` NAMES. THE TAIL IS "OLDEST FIRST". ──────────────────────
 *
 * This line used to end *"ordered `createdAt` ascending"*, which was true of a DIFFERENT ENDPOINT:
 * `GET /tools/{id}/artisans` sorts `createdAt asc, id asc` and promises "oldest first". The
 * `artisanLinks` on a tool payload come from `hydrate_relations`, which passes no `order` at all —
 * so for a while they arrived in whatever order Postgres's chosen plan produced, and since the server
 * derives `tool.artisanId` from element 0 of what comes back, merely reopening a tool and saving it
 * could re-point that column at a different person while `artisanName`/`place` kept naming the first.
 *
 * `_order_artisan_links` now sorts every encoded tool: the link whose `artisanId` matches the tool's
 * own column FIRST, then the rest by `createdAt asc, id asc`. Note what that second key can and
 * cannot say — a whole selection is written by one `create_many` and therefore shares one
 * `createdAt`, so TICK ORDER DOES NOT SURVIVE THIS TABLE and only the head is meaningful. Do not
 * write anything here that reads the tail as the order somebody ticked in. [ToolCraftLinkDto] is
 * different: `craftName` is its ordinal and `_order_craft_links` restores the full order from it.
 *
 * THE HEAD IS STILL PINNED CLIENT-SIDE, and that is not redundant. `_order_artisan_links` can only
 * pin a link that EXISTS, and `DELETE /tools/{id}/artisans/{artisan_id}` removes a link row while
 * touching no scalar — so a tool may hold an `artisanId` with no row of its own at any time, and a
 * fielded APK may be talking to an API older than that ordering. The tool form seeds its selection as
 * the union with `artisanId` at the head; see `MainActivity.ToolForm`.
 */
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
    val yearsInUse: Int? = null,
    // Decimal columns arrive as JSON strings; typed String? to keep list parsing from failing.
    val height: String? = null,
    val width: String? = null,
    val lengthInches: String? = null,
    val breadthInches: String? = null,
    /**
     * The measured height in inches — see [ToolCreateRequest.heightInches] for why it is a separate
     * column from [height], which is the old unit-less one. `String?` like its siblings above: these
     * are Decimal columns and they arrive as JSON strings.
     */
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
    /**
     * EVERY craft and artisan this tool is linked to — what the form's two multi-selects re-open on.
     *
     * [craftId] and [artisanId] above still hold the FIRST of each, for every reader that has always
     * read them; these hold all of them. Always present on the wire and never null — an empty array
     * for a tool with no links — so `emptyList()` here is the "older server" default and not a
     * "no links" answer.
     *
     * DO NOT ASSUME THESE CONTAIN THE TWO SCALARS. Both join tables have now been backfilled from
     * their column — `ToolCraft` by `20260915100000`, `ToolArtisan` by `20260916090000`, which was
     * missed at the time and cost every tool recorded between June and the multi-select its primary
     * artisan on the next save — so in the ordinary case they do. But `ToolArtisan` is also written
     * by "Assign tools to artisans", whose DELETE removes a link row and touches no scalar, so a tool
     * can hold an [artisanId] that appears in no link row again at any time; and a fielded APK meets
     * whatever API the courtyard has, including one that predates either backfill. A form that seeds
     * its selection from these alone then drops the record's own artisan and, because the server
     * derives `artisanId` from element 0 while leaving `artisanName`/`place` untouched, reassigns the
     * tool to somebody else under a 200. The tool form seeds the UNION with the scalar at the head;
     * see `MainActivity.ToolForm`.
     */
    val craftLinks: List<ToolCraftLinkDto> = emptyList(),
    val artisanLinks: List<ToolArtisanLinkDto> = emptyList(),
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
    /**
     * DESIGN_PROTOTYPE or OTHER. A RESPONSE DTO MAY TAKE A NON-NULL DEFAULT where a create request
     * may not: this class is decoded by `ApiClient.json`, which sets `coerceInputValues = true`, so
     * a server null lands on the default instead of throwing — and it is never decoded by
     * `offlineJson`, which is the decoder that lacks that flag. Every row recorded before the column
     * reads OTHER, which is what it implicitly was.
     */
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
    /**
     * WHICH QUESTIONNAIRE IS IN USE AT THIS WORKSHOP, chosen once by an admin. `null` means "not
     * chosen", which resolves to the DEFAULT instrument at read time — it does not mean the workshop
     * has no questionnaire. The capture form reads this to open on the right instrument without
     * asking the researcher to choose again.
     */
    val questionnaireId: String? = null,
    /** The instrument row itself, hydrated by the server alongside the id above. */
    val questionnaire: QuestionnaireDto? = null,
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
    // Which instrument this sitting was taken on, and the instrument row itself when the server
    // hydrated it. Never changes after the create: the API has no field to change it with.
    val questionnaireId: String? = null,
    val questionnaire: QuestionnaireDto? = null,
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
    val recordedTimezone: String = "Asia/Kolkata",
    /**
     * The create-idempotency key. See [WorkshopCreateRequest.clientKey] for the whole argument.
     *
     * PROCESS IS THE ONE OF THE FOUR WHOSE REPLAY HAS TO THINK ABOUT CHILDREN. Steps are written
     * after the row, and a create body carries no step ids — so a replay that re-ran the step sync
     * would mint fresh ids for every step and orphan every media file linked to the old ones. The
     * server answers a replay from the stored row and writes steps only where it has none; nothing
     * on this side changes, but that is why sending the same key twice is safe.
     */
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

/**
 * `GET /export/dataset` — the whole-repository download manifest.
 *
 * [truncated] is true when any table hit its export cap (`EXPORT_TAKE = 5000` per record table,
 * `MEDIA_TAKE = 20000` for media). The server has always sent it and this DTO used to drop it on
 * the floor, so an archive missing everything past the cap presented itself as complete. Defaulted
 * to false so a server that predates the field still parses.
 */
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

// --- Questionnaire completion matrix (artisans x sections) ---

@Serializable
data class CompletionMatrixDto(
    /**
     * WHICH INSTRUMENT THIS MATRIX IS ABOUT. Two instruments run overlapping section codes, so a
     * grid headed "A", "B", "C" is an unlabelled claim without this. Absent on an older backend.
     */
    val questionnaireId: String? = null,
    val questionnaireTitle: String? = null,
    val sections: List<CompletionSectionDto> = emptyList(),
    val artisans: List<CompletionArtisanDto> = emptyList(),
    val cells: List<CompletionCellDto> = emptyList(),
    /**
     * How many questionnaire interviews the CHOSEN WORKSHOP SCOPE cannot see, because they name no
     * workshop at all.
     *
     * The number exists so the failure mode can never be silent again. An interview with no `workshopId`
     * counts towards no workshop scope — which is correct, it genuinely does not say where it was taken —
     * but it is also exactly the shape of the bug that had this matrix reporting "nothing was covered at
     * this workshop" while twenty-five interviews sat in the repository unlinked, leaving only the admin
     * overrides visible. Zero whenever the scope cannot hide anything: no workshop chosen, or the
     * unassigned records explicitly included.
     */
    val unassignedInterviews: Int = 0,
    /**
     * True while an admin's mark is keyed on (artisan, section) ALONE, i.e. is not per workshop.
     *
     * The server states it rather than leaving each client to work out whether it matters: the workshop
     * scope narrows the green DERIVED from recordings, but a marked cell keeps its colour under every
     * scope, and a reader watching a cell stay green as the scope moves deserves to be told why.
     */
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

// ---------------------------------------------------------------------------
// Records that name no workshop — GET /workshops/unmapped and POST /workshops/unmapped/map.
//
// WHY THIS EXISTS. Every control that narrows by workshop reads one column, `workshopId`. A record with
// that column empty counts towards NO workshop scope — while remaining perfectly visible under "All
// records". Since both clients OPEN scoped to the most recent workshop, the result reads as "nothing was
// documented here" rather than as a filter excluding data sitting right there. That is exactly what
// happened: 25 questionnaire interviews and 924 media files, every one recorded at the single workshop in
// the repository, none carrying its id, and a completion matrix showing nothing but the cells an admin had
// ticked by hand.
//
// The server decides everything. It reads the evidence, it names the rung that decided each row, and it
// refuses to guess when the evidence is ambiguous — see `backend/app/services/workshop_inference.py`. This
// client renders the report and presses the button; it never proposes a mapping of its own.
//
// Every field defaults, like the map payloads above and for the same reason: an admin screen that cannot
// decode a new field is a screen that shows nothing at all.
// ---------------------------------------------------------------------------

/** One row the ladder looked at: where it would be filed, or why it was left alone. */
@Serializable
data class WorkshopMappingRowDto(
    val id: String = "",
    val title: String = "",
    val workshopId: String? = null,
    val workshopTitle: String? = null,
    /** `PARENT` | `ARTISANS` | `WINDOW`, or null when the row could not be settled. */
    val rung: String? = null,
    /** The server's own sentence for that rung, so both clients word a decision identically. */
    val rungCopy: String? = null,
    /** `NO_EVIDENCE` | `AMBIGUOUS`, or null when the row WAS settled. */
    val reason: String? = null,
    val reasonCopy: String? = null,
    /** Every workshop the deciding rung pointed at. More than one is what AMBIGUOUS means. */
    val candidateTitles: List<String> = emptyList()
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

/**
 * One record type's share of the gap. [singular]/[plural] are the server's own nouns, so the two clients
 * cannot call the same bucket two different things.
 *
 * [rows] is CAPPED by the server — the counts are the answer and the rows are the evidence for
 * spot-checking it — with unresolved rows listed FIRST, because those are what an admin has to act on.
 * [rowsTruncated] says the cap was hit. [applied] is null on the preview and the number actually written
 * on the response to the button.
 */
@Serializable
data class WorkshopMappingBucketDto(
    val bucket: String = "",
    val singular: String = "",
    val plural: String = "",
    val unassigned: Int = 0,
    val resolved: Int = 0,
    val unresolved: Int = 0,
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
    /** Null on the preview; the number of rows actually written on the response to the button. */
    val applied: Int? = null
)

@Serializable
data class WorkshopMappingPlanDto(
    /** The workshops as time spans, so the screen can say what the dates were read as. */
    val workshops: List<WorkshopMappingWindowDto> = emptyList(),
    val buckets: List<WorkshopMappingBucketDto> = emptyList(),
    val totals: WorkshopMappingTotalsDto = WorkshopMappingTotalsDto()
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
 *
 * THE REVIEW STATE. An assignee's "mark done" now lands on `SUBMITTED`, and only an admin's approval
 * moves it to `DONE` (backend/app/api/routes/tasks.py:1689 rewrites the assignee's `DONE` rather than
 * refusing it, precisely so a field build older than this one is not bricked out where nobody can fix
 * it). Every field below that describes that state is READ FROM THE SERVER rather than re-derived:
 * see `taskStatusLabel` in ui/TaskAdminScreen.kt for what happens when one of them is missing.
 */
@Serializable
data class TaskDto(
    val id: String,
    val title: String = "",
    val description: String? = null,
    // OPEN | IN_PROGRESS | SUBMITTED | DONE | CANCELLED.
    //
    // SUBMITTED IS NEW AND IT IS NOT A VARIANT OF DONE. It means "the researcher says this is
    // finished and nobody with authority has agreed yet". Anything here that tests for finished work
    // by asking `status == "DONE"` keeps the right answer; anything that tests for UNFINISHED work by
    // asking `status != "DONE"` also keeps the right answer. What silently broke on the day SUBMITTED
    // landed was every `when (status)` whose `else ->` branch existed to mean OPEN — a submitted task
    // rendered as "Open" and a researcher was told to redo work they had already handed in. Grep for
    // the branch before adding a sixth state; `ignoreUnknownKeys` will not warn you.
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
    /**
     * How [derivedCount] was arrived at, keyed by the server. Carries `unlinkedSections` when
     * sections were answered on an interview with no artisan attached — those answers cannot raise
     * the (artisan, section) pair count, so without this the difference between "nobody has started"
     * and "the interviews were never linked to anybody" is an unexplained, permanently stuck 0%.
     */
    val derivedBreakdown: Map<String, Int> = emptyMap(),

    // ── The review state, said in the server's words ──────────────────────────────────────────
    //
    // ALL SEVEN COME FROM THE SERVER AND NONE IS RE-DERIVED HERE. There is no codegen between the
    // backend and this file, so the only thing keeping an Android pill and a web pill saying the
    // same sentence about the same row is that both print a string the server sent. The moment this
    // client builds its own "Under review" the two can drift, and nobody finds out, because a
    // researcher only ever looks at one of the two screens.

    /** "To do" / "In progress" / "Under review" / "Approved" / "Cancelled" — render THIS. Blank on a
     *  server older than the review state, which is the signal `taskStatusLabel` falls back on. */
    val statusLabel: String = "",
    /**
     * `status == "SUBMITTED"`. NULLABLE ON PURPOSE, and the nullability is the whole safety net: a
     * non-null `false` default would make an older server's silence indistinguishable from "this is
     * not awaiting review", and a submitted task would offer the assignee a "Mark done" button that
     * re-submits work already in the queue. Null means UNKNOWN, and `taskAwaitingReview()` answers it
     * from [status], which every server sends.
     */
    val isAwaitingReview: Boolean? = null,
    /**
     * OPEN, IN_PROGRESS or SUBMITTED — "still on my screen, by anybody's doing". FILTER ON THIS, not
     * on `status != "DONE"`, which also keeps CANCELLED. Nullable for the same reason as
     * [isAwaitingReview] and for a worse consequence: a `false` default would empty the assignee's
     * task list against an older server, and an empty to-do list looks exactly like a finished one.
     */
    val isOutstanding: Boolean? = null,
    /**
     * THE NUMBER A PROGRESS BAR IS DRAWN FROM. Null is MEANINGFUL — it means "this task has nothing
     * measurable in it, render the state pill and NO BAR". A bar at 0% on a task with no countable
     * scope reads as "this person has produced nothing", which is a different and defamatory claim.
     */
    val effectivePercent: Int? = null,
    /** "status" | "derived" | "reported" | null — what [effectivePercent] was computed from, so the
     *  bar can be captioned honestly instead of implying every figure was measured. */
    val progressSource: String? = null,
    /** The caption for that bar, already worded: "6 of 24 artisan sections recorded". */
    val progressLabel: String? = null,
    /** The honest counter-number to [percentComplete]: the same fraction, measured rather than typed. */
    val derivedPercent: Int? = null,
    /** The roster size the derived denominator was built from — how many artisans are actually in scope. */
    val derivedArtisanCount: Int? = null,

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
 *
 * `DONE` IS NO LONGER THE ASSIGNEE'S TO WRITE, AND SENDING IT IS STILL CORRECT. An assignee's `DONE`
 * is REWRITTEN to `SUBMITTED` server-side (tasks.py:1689) and the response carries
 * `status: "SUBMITTED"`, so the row never lands where an old build thought it did but the old build
 * is never lied to about it either. This client sends `SUBMITTED` explicitly anyway — the round trip
 * then says the same word in both directions, and a reader of a network log is not left deducing a
 * rewrite. Two statuses here are 403s for an assignee and must never be offered: `CANCELLED`
 * (withdrawal belongs to whoever handed the work out) and any status at all on a task already
 * `DONE` (undoing somebody else's approval).
 *
 * The same body is what an ADMIN's approve / send-back writes — see `FieldRepository.reviewTask`.
 * One endpoint, one body, two very different acts; the difference is made visible in the UI before
 * the press, because the row it writes is identical afterwards.
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
    /** The server's wording for [status]; blank on a server older than the review state. */
    val statusLabel: String = "",
    /** Null = unknown, not "no". See [TaskDto.isAwaitingReview] for why this is not a plain Boolean. */
    val isAwaitingReview: Boolean? = null,
    val progressCount: Int = 0,
    val derivedCount: Int? = null,
    /** Null for an open-ended task (no target) that is neither DONE nor CANCELLED. */
    val percentComplete: Int? = null,
    /** The bar number; null means "nothing measurable here", NOT zero. */
    val effectivePercent: Int? = null,
    /**
     * ON A SUBMITTED ROW THIS IS WHEN THE WORK WAS HANDED IN, not when it was approved — the server
     * stamps it at the first declaration that the work is finished and deliberately does NOT
     * re-stamp it on approval, so `completedAt > dueAt` stays a fact about the person who did the
     * work rather than about how fast their reviewer got round to it. Nothing on the wire records
     * the moment of approval; do not caption this "approved on".
     */
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
    /**
     * OPEN / IN_PROGRESS / SUBMITTED / DONE / CANCELLED -> how many of the batch's rows are in that
     * state. All five keys are ALWAYS present (the server builds the map from a fixed key list), so
     * `statusCounts["SUBMITTED"]` is safe on a batch nobody has submitted in — but keep the `?: 0`,
     * because an older server's map has only four.
     */
    val statusCounts: Map<String, Int> = emptyMap(),
    /** APPROVED rows only. Submissions are counted by [awaitingReviewCount] and are NOT folded in. */
    val doneCount: Int = 0,
    /** OPEN + IN_PROGRESS — waiting on the ASSIGNEE. Excludes submissions, which wait on the reviewer. */
    val openCount: Int = 0,
    /** Handed in, nobody has agreed yet — the reviewer's queue for this batch. */
    val awaitingReviewCount: Int = 0,
    /** OPEN + IN_PROGRESS + SUBMITTED: how many of these rows are still on somebody's screen. */
    val outstandingCount: Int = 0,
    val overdueCount: Int = 0,
    val reportedTotal: Int = 0,
    val derivedTotal: Int? = null,
    /**
     * APPROVED-ONLY when the batch has no target count. Draw the awaiting slice from
     * [awaitingReviewCount] beside this rather than adding it in: a batch reading "5 of 5 done"
     * while nobody has looked at any of it is the exact illusion the review state exists to remove.
     */
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
    /** All five statuses, always present. See [TaskBatchDto.statusCounts]. */
    val statusCounts: Map<String, Int> = emptyMap(),
    /** OPEN + IN_PROGRESS. What THIS PERSON still owes — the number the board sorts on. */
    val openCount: Int = 0,
    /**
     * What THE READER owes this person: rows they have handed in and nobody has decided on. Kept out
     * of [openCount] deliberately, so "who is behind" does not chase somebody whose only outstanding
     * work is sitting in the admin's own queue.
     */
    val awaitingReviewCount: Int = 0,
    /** [openCount] + [awaitingReviewCount] — everything not yet approved or withdrawn. */
    val outstandingCount: Int = 0,
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
    /** APPROVED. Not "the researcher says so" — a second person agreed. */
    val doneCount: Int = 0,
    /** OPEN + IN_PROGRESS across everybody. */
    val openCount: Int = 0,
    /**
     * THE READER'S OWN QUEUE. Every one of these is a researcher waiting on a decision from whoever
     * is looking at this board — the one number on an accountability screen that is about the person
     * reading it rather than about the people on it, which is why it gets its own tile.
     */
    val awaitingReviewCount: Int = 0,
    val outstandingCount: Int = 0,
    val overdueCount: Int = 0,
    val truncated: Boolean = false,
    val assignees: List<TaskProgressAssigneeDto> = emptyList()
)

/**
 * `GET /tasks/summary` — MY workload in one object, for the full-width card at the top of the
 * assignee's screen. Always about the caller: there is no `assigneeId` to point it at anybody else.
 *
 * WHY THIS IS NOT COMPUTED FROM THE TASK LIST THIS SCREEN ALREADY HAS. That list is PAGED. A card
 * reading "4 remaining" counted from a page of twenty is wrong for anybody holding twenty-one tasks,
 * and wrong in the one direction that matters — it UNDER-reports outstanding work, which is the
 * single number the card exists to make impossible to miss. The server scans the caller's whole
 * (bounded) list and says [truncated] when it could not reach the end.
 *
 * WHY IT IS NOT `GET /tasks/progress`. That route is admin-only and rolls up everybody. A researcher
 * cannot call it and must not be able to: handing them the board so they can read their own line off
 * it would hand them everyone else's line too.
 *
 * Pass `workshopId` unless a lifetime bar is genuinely what is wanted — a researcher on their fourth
 * trip does not want this trip's progress diluted by three finished ones.
 */
@Serializable
data class TaskSummaryDto(
    val assignee: TaskUserDto? = null,
    val workshopId: String? = null,
    val taskCount: Int = 0,
    /** All five statuses, always present. */
    val statusCounts: Map<String, Int> = emptyMap(),
    /**
     * OPEN + IN_PROGRESS — WHAT IS STILL ON YOU, and the number the card leads with. SUBMITTED is
     * deliberately not in it: telling somebody who has handed everything in that they still have
     * four tasks remaining is telling them to do the work twice.
     */
    val remainingCount: Int = 0,
    /** Handed in, not yet agreed. Still on the screen, but visibly not "to do". */
    val awaitingReviewCount: Int = 0,
    /** [remainingCount] + [awaitingReviewCount] — how many cards the assignee will actually see. */
    val outstandingCount: Int = 0,
    val approvedCount: Int = 0,
    val cancelledCount: Int = 0,
    val overdueCount: Int = 0,
    /** Due inside the server's "soon" window and NOT already overdue. */
    val dueSoonCount: Int = 0,
    /**
     * The next deadline still ahead. EXCLUDES anything already overdue — a card whose "next due" is
     * a date in the past reports the same emergency twice under two headings while hiding the real
     * next deadline behind it. Null when nothing is scheduled.
     */
    val nextDueAt: String? = null,
    val percentComplete: Int = 0,
    /**
     * How many of the figures behind [percentComplete] were MEASURED from the repository rather than
     * inferred from a status or read off a typed-in number. A card claiming progress happens
     * automatically has to be able to say how much of it actually did.
     */
    val measuredCount: Int = 0,
    /** True when the caller holds too many tasks for the derived counts to be run at all. */
    val derivationSkipped: Boolean = false,
    /** True when the scan window was hit, so these numbers are a floor rather than a total. */
    val truncated: Boolean = false
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

// ---------------------------------------------------------------------------
// Map: WHERE the repository's records are.
// GET /map/points and GET /map/points/{key}/records.
//
// TWO LAYERS, NEVER ADDED TOGETHER. A record has two different true locations: where its craft
// COMES FROM (the ORIGIN layer, built from the subject's stated address, else the legacy free-text
// place through the server's town atlas) and where it was RECORDED (the CAPTURE layer, built from
// the device's GPS fix). One record can appear in both — its craft is from Bagru and it was
// documented at a Kharagpur workshop — so `summary.originRecords` and `summary.captureRecords` are
// two denominators over the same corpus. Adding them either double-counts that record or hides one
// of its two truths; the layer a pin belongs to is [MapPointDto.layer].
//
// Every field here has a default, including the ones the server always sends. These payloads are the
// only thing standing between a phone and a blank map, and a server that gains a summary field must
// not be able to turn the whole screen into a parse error.
// ---------------------------------------------------------------------------

/**
 * The five record buckets a pin folds, in the API's PLURAL vocabulary. A fixed five-key object, not
 * a map: the keys are the wire's own closed list, so naming them is what lets a caller read
 * `counts.artisans` instead of guessing at a string key that a typo would silently turn into null.
 *
 * Each defaults to 0 because a bucket the caller did not ask for is simply absent from the payload,
 * and "not counted" draws the same as "none here" on a pin that was never counting it.
 */
@Serializable
data class MapCountsDto(
    val artisans: Int = 0,
    val workshops: Int = 0,
    val products: Int = 0,
    val tools: Int = 0,
    val media: Int = 0
)

/**
 * One pin. [key] is what identifies it to `GET /map/points/{key}/records`, and is opaque: it is one
 * of `nation:india`, `state:<State>`, `district:<State>|<District>` or
 * `capture:<cell size>:<lat cell>_<lon cell>`. It contains ':' and '|', so it is URL-encoded on the
 * way back (Retrofit does that — see `FieldRepositoryApi.mapPointRecords`). NEVER parse it to derive
 * a label: [label], [region], [state] and [district] are the server's own resolved names.
 *
 * [layer] is `ORIGIN` or `CAPTURE`. [precision] is `SUBJECT_PIN` | `MEASURED` | `TOWN` | `DISTRICT`
 * | `STATE` | `NATION` and [source] is `SUBJECT_PIN` | `STATED_ADDRESS` | `PLACE_TEXT` |
 * `DEVICE_FIX` — together they say how much of this position is a measurement and how much is a
 * lookup, which is the difference between "we stood here" and "we know the district".
 *
 * [latitude]/[longitude] are always sent for a point (the server cannot build one without a
 * position) and are the WEIGHTED MEAN of everything folded in, not the corner of a grid cell.
 *
 * ORIGIN-only: [places] — every finer place name and free-text spelling that folded in, longest
 * first, so grouping to a district moves that detail into the panel instead of losing it;
 * [pinnedRecords] — how many of [total] are positioned by a real coordinate; [fromPlaceText] — how
 * many reached this pin through the legacy prose column, i.e. which records to go and fill in.
 *
 * CAPTURE-only: [fixes] — how many separate GPS fixes this pin stands in for, so "317 records" is
 * not mistaken for one measurement; [spreadMetres] — how much ground they cover; [medianAccuracy] —
 * metres, null when no fix reported one. All three default so an ORIGIN pin, which omits them, is
 * not a decoding failure; branch on [layer], never on `fixes > 0`.
 */
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
    /**
     * The finer breakdown of this point, for the list's expandable row. See [MapPointChildDto].
     *
     * Empty at DISTRICT level and empty whenever the point holds only ONE child, both decided by the
     * server: a disclosure whose content restates the row it hangs under is a control that does nothing,
     * and a reader who opens one learns to stop opening them.
     */
    val children: List<MapPointChildDto> = emptyList(),
    /** True when a point holds more children than the server will send. Stated, never silent. */
    val childrenTruncated: Boolean = false
)

/**
 * One entry inside a point's expandable row — the same place, one administrative level down.
 *
 * WHY POINTS HAVE CHILDREN AT ALL. At NATION level the whole country is a single dot and therefore a
 * single row, and at STATE level a state is one dot and one row. "Tap a pin, the list scrolls to its row"
 * has nowhere to go at those levels: the row a reader lands on is the row they already had. So each point
 * carries the level below it — the states inside the nation, the districts inside the state — and the list
 * renders that as a disclosure. DISTRICT points have none, because a district is the finest unit an Indian
 * address names.
 *
 * [key] is a REAL point key at [level], which is what makes the disclosure navigable rather than
 * decorative: tapping one switches the map to [level] and selects that key, landing on exactly the pin the
 * Detail control would have drawn. [level] is `NATION` | `STATE` | `DISTRICT`, null on an older server.
 *
 * A CAPTURE point's children are tighter GPS clusters rather than administrative units, so their [label]
 * is the layer's own "Recorded here" and [region] is the coordinate — a measured fix has no administrative
 * name to borrow, and lending it one would erase the distinction between the two layers.
 */
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

/**
 * Records the map could NOT place, grouped by what their place column said. Blank, unreadable and
 * "prose the atlas cannot resolve, with no state picked" all land here as one honest bucket rather
 * than as a pin somewhere plausible. [label] is `No place recorded` when there was no text at all.
 *
 * This list must be shown. It is the count of records the map is silently not drawing, and hiding it
 * makes a map of 60 records look like a map of the whole repository.
 */
@Serializable
data class MapUnplacedDto(
    val label: String = "",
    val total: Int = 0,
    val counts: MapCountsDto = MapCountsDto()
)

/**
 * The one record the caller asked to see in context (`focusType` + `focusId`). The map still draws
 * the whole filtered corpus — "in context" is meant literally — and this only names which pins hold
 * the record, in [pointKeys], computed by the same ladder at the same level so a focus key always
 * matches a drawn pin. Usually two keys: its origin pin and its capture pin.
 *
 * [type] is the PLURAL bucket name here (`artisans`, ...), matching what the request asked for.
 */
@Serializable
data class MapFocusDto(
    val type: String = "",
    val id: String = "",
    val title: String = "",
    val place: String? = null,
    val pointKeys: List<String> = emptyList(),
    /**
     * False when the current filters — the workshop scope above all — exclude this record, so its
     * [pointKeys] ring no drawn pin.
     *
     * REPORTED RATHER THAN ENFORCED, and that is the whole reason it exists. The server used to resolve
     * a focused record through the same narrowed clause the pins were built from and 404 when it missed,
     * which took the ENTIRE map response down: "show this record on the map" from anything older than
     * the most recent workshop produced an error screen instead of a map with one unringed pin.
     *
     * Defaults TRUE so an API that predates the field reads as it always behaved, where being out of
     * scope was not representable at all.
     */
    val inScope: Boolean = true
)

/**
 * How much of the structured address the corpus in play actually holds — the map's own quality,
 * reported so "why is this record at the state capital" has an answer a researcher can act on (go
 * and fill in the district, or drop a pin) instead of looking like a bug.
 *
 * [locations] is the denominator for all four: how many `Location` rows the filtered corpus touches.
 */
@Serializable
data class MapAddressCompletenessDto(
    val locations: Int = 0,
    val withState: Int = 0,
    val withDistrict: Int = 0,
    val withPincode: Int = 0,
    val withSubjectPin: Int = 0
)

/**
 * What the map is standing on. [records] is how many records the filters matched at all; [byType]
 * breaks that down over the same five buckets as a pin's counts.
 *
 * [originExcludes] names the buckets that CANNOT reach the origin layer — media has no place column
 * of its own, a photograph inherits the record it belongs to — so the asymmetry is stated rather
 * than left for a reader to notice. [captureTruncated] is true when more locations were in play than
 * the server would read fixes for, i.e. the capture layer is incomplete.
 *
 * [clusterKilometres] is the radius fixes were merged at for the current level, which is what a
 * "Recorded here" pin is really claiming. [anchoredDistricts] / [anchorPins] say how well the server
 * knows where districts ARE and how much of that is measured rather than seeded from the atlas;
 * [anchorsTruncated] means the anchor read hit its ceiling, so some districts fall back to coarser
 * positions.
 */
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

/**
 * `GET /map/points` — every pin for the current filters, both layers, plus what would not place.
 *
 * [scope] is `all` (no filter narrowed anything), `filtered`, or `record` (a focus was requested).
 * [level] is the administrative unit the pins are GROUPED at — `NATION` | `STATE` | `DISTRICT` — and
 * [levels] is the vocabulary to build the level toggle from, served so the client's list and the
 * server's cannot drift apart. [types] is which buckets were counted, in the server's own order.
 *
 * [points] is already sorted: biggest first, ties by label.
 */
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
    /**
     * The level every point's [MapPointDto.children] are keyed at — the level to switch the map to when a
     * reader drills into one. Null at DISTRICT, where there are no children, and null on an older server.
     *
     * Read from the server rather than re-derived here, so this client cannot hold a different idea of the
     * ladder than the server does.
     */
    val childLevel: String? = null
)

/**
 * One record behind a pin. [type] is the PLURAL bucket name (`artisans`, `workshops`, `products`,
 * `tools`, `media`) — the same vocabulary the request's `types` filter uses.
 *
 * Hand-picked columns, and that is the security property: no identity field is in this shape at any
 * rank, so a map cannot leak one however it renders a row.
 */
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

/**
 * `GET /map/points/{key}/records` — the records behind ONE pin, fetched on demand so a pin can be
 * navigated FROM rather than only looked at.
 *
 * MUST BE REQUESTED WITH THE SAME FILTERS THE MAP WAS DRAWN WITH, including `level` and the workshop
 * scope. The key alone does not determine the answer: it names an administrative unit, and which
 * records sit in that unit is exactly what the filters decide. [truncated] is true when the
 * per-bucket cap was hit, so [total] is a floor, not the count.
 */
@Serializable
data class MapPointRecordsDto(
    val key: String = "",
    val items: List<MapPointRecordDto> = emptyList(),
    /**
     * How many rows are IN [items] — not how many records the pin holds.
     *
     * [total] is the same number under an older name, kept because clients read it. Do not compare
     * either against `MapPointDto.total`: that one is the pin's corpus count, this one is the length of
     * a list capped at [cap] PER RECORD TYPE, so a pin holding 317 files answers with 40 here. A client
     * that reads the two identically-named fields as the same quantity reports 277 missing records.
     * [truncated] is the field that actually says which situation this is.
     */
    val shown: Int = 0,
    val total: Int = 0,
    val cap: Int = 0,
    val truncated: Boolean = false
)

// ---------------------------------------------------------------------------------------------
// The access roster — the admin side of the sign-in gate
// ---------------------------------------------------------------------------------------------
//
// WHAT THIS IS. Until the gate shipped, this application refused nobody: any verified Google
// address on earth got a `User` row and a bearer token, automatically, at the lowest tier. The
// roster is the institution's answer to "who may sign in at all", and it is NOT the user table —
// a row usually exists BEFORE the account does (an admin admits an address; the account provisions
// itself on first sign-in) and it outlives the access (suspending keeps the row). That is why
// every one of these endpoints is keyed by EMAIL and not by user id.
//
// ONLY `status == "ACTIVE"` ADMITS. A PENDING row is created BY THE REFUSED CALLER, so "a row
// exists for this address" and "this person may sign in" are one clause apart. Any screen that
// conflates them is showing an admin an authentication bypass and calling it a roster.

/** One roster row, exactly as `access_roster.roster_payload` serialises it. */
@Serializable
data class AccessRosterDto(
    val id: String = "",
    val email: String = "",
    /** PENDING | ACTIVE | REJECTED | SUSPENDED — `AccessStatus` in prisma/schema.prisma. */
    val status: String = "PENDING",
    /** The tier this address gets on admission. NOT the live account role — see [accountRole]. */
    val grantedRole: String = "CROWDSOURCE_VOLUNTEER",
    /**
     * ADMIN-WRITTEN ONLY, and that is a security property rather than an omission: the sign-in path
     * never stores a display name from an unverified source, because the pending queue is the one
     * screen in this product where a stranger can cause content to appear.
     */
    val fullName: String? = null,
    val notes: String? = null,
    /** The date of joining the platform. Stamped the FIRST time an address is admitted, never moved. */
    val joinedAt: String? = null,
    /** First successful sign-in. Null on an ACTIVE row = admitted but never taken up. */
    val firstSeenAt: String? = null,
    /** Refused attempts. 0 = an administrator added this row and nobody asked to be here. */
    val requestCount: Int = 0,
    val firstRequestedAt: String? = null,
    val lastRequestedAt: String? = null,
    val decidedAt: String? = null,
    val decidedById: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** The account behind the address, when one exists. Resolved server-side for the whole page. */
    val userId: String? = null,
    /**
     * The account's LIVE role, which disagrees with [grantedRole] the moment somebody is promoted
     * through the users screen. Both are shown, because an admin reading only the roster would
     * otherwise be looking at a stale tier and believing it current.
     */
    val accountRole: String? = null,
    val accountName: String? = null
)

/** `GET /access-roster/pending-count` — the notification, in its entirety. */
@Serializable
data class AccessRosterPendingCountDto(
    val pending: Int = 0
)

/**
 * `POST /access-roster` — put an address on the allow list. No account is required and none is
 * created; the account provisions itself, at [grantedRole], the first time that address signs in.
 *
 * [grantedRole] absent means the bottom of the ladder: "all the users by default join as the lowest
 * rung unless promoted there itself". The server bounds it by the calling admin's own tier.
 */
@Serializable
data class AccessRosterCreateBody(
    val email: String,
    val fullName: String? = null,
    val notes: String? = null,
    val grantedRole: String? = null,
    val isActive: Boolean = true
)

/**
 * `PATCH /access-roster/{id}` — approve, refuse, restore or correct one entry.
 *
 * EVERY FIELD IS NULLABLE AND NULL MEANS "LEAVE IT ALONE": the Retrofit converter is configured
 * with `explicitNulls = false` (ApiClient.kt), so an unset field is omitted from the JSON entirely
 * and the server's `exclude_unset` leaves the column untouched. That is what stops an admin fixing
 * a typo in a note from silently re-approving somebody another admin had rejected.
 *
 * `status = "PENDING"` is refused by the server on purpose: putting a request back in the queue is
 * indistinguishable, on the queue, from a fresh request by the applicant, so one admin could
 * quietly undo another's rejection with nothing on screen saying so.
 */
@Serializable
data class AccessRosterUpdateBody(
    val email: String? = null,
    val status: String? = null,
    val grantedRole: String? = null,
    val fullName: String? = null,
    val notes: String? = null
)

// ---------------------------------------------------------------------------------------------
// A designer's OWN provider keys — `GET /ai/providers`, `GET|PUT|DELETE /me/ai-keys[/…]`
//
// SEPARATE FROM [ManagedSecretDto] AND ITS OPPOSITE IN EVERY RESPECT. That one describes the
// DEPLOYMENT's keys, is master-admin only, and has a reveal endpoint because a master admin
// sometimes has to compare a stored key against a provider dashboard. These describe ONE PERSON's
// own key, billed to their own card at their own provider, and there is deliberately no reveal
// route at all — nobody, administrator included, has any business reading somebody else's personal
// credential. The last four characters are the most this app can ever show.
// ---------------------------------------------------------------------------------------------

/**
 * One model a designer can choose, and the honest list of what it can be used for.
 *
 * [tasks] IS NOT DECORATION AND MUST BE RENDERED. It carries the enum names the server uses —
 * PROOFREAD, EXPAND, SUMMARISE, TRANSLATE, TRANSCRIBE, CAPTION — and it is how a designer learns
 * BEFORE choosing that, for instance, no Claude model can transcribe audio. Dropping it from the
 * screen would let somebody paste a Claude key believing their recordings were now on their own
 * account, and find out otherwise from a bill that never arrives.
 */
@Serializable
data class AiModelDto(
    val id: String,
    val label: String = "",
    val note: String = "",
    val tasks: List<String> = emptyList(),
    /** Indicative USD per million tokens. Null for models the provider does not price per token. */
    val inputPricePerMTok: Double? = null,
    val outputPricePerMTok: Double? = null
)

/** One provider: how to get a key, what it costs, and which models it offers. */
@Serializable
data class AiProviderDto(
    val provider: String,
    val label: String = "",
    /** What a key from this provider starts with, checked before an obviously-wrong paste is sent. */
    val keyPrefix: String? = null,
    val consoleUrl: String = "",
    val pricingUrl: String = "",
    /** The accordion: how to get a key, one action per step, in the order they will be done. */
    val howTo: List<String> = emptyList(),
    val defaultModel: String = "",
    val models: List<AiModelDto> = emptyList()
)

/**
 * `GET /ai/providers`.
 *
 * [pricesCheckedOn] TRAVELS WITH EVERY PRICE THIS APP PRINTS. The figures go stale — providers
 * re-price, and some of the current rates are introductory — and a stale price shown as current is
 * a small lie told to somebody deciding how to spend their own money.
 */
@Serializable
data class AiCatalogueDto(
    val pricesCheckedOn: String = "",
    val tasks: List<String> = emptyList(),
    val providers: List<AiProviderDto> = emptyList()
)

/**
 * One provider row for the signed-in person. Carries no plaintext, ever — see the block comment
 * above on why there is no reveal.
 */
@Serializable
data class UserAiKeyDto(
    val provider: String,
    val label: String = "",
    /** True when a key is stored AND can still be decrypted. */
    val configured: Boolean = false,
    /** A stored key the server can no longer decrypt: the owner must paste it again. */
    val unreadable: Boolean = false,
    val hint: String? = null,
    val model: String = "",
    /** False when the saved model is no longer one this app offers — the row says so rather than
     *  silently correcting it, because the designer's own choice is what is being overridden. */
    val modelKnown: Boolean = true,
    val lastStatus: String = "UNKNOWN",
    val lastCheckedAt: String? = null,
    val lastError: String? = null,
    val updatedAt: String? = null
)

/** `PUT /me/ai-keys/{provider}`. Both fields are optional: sending only [model] changes the model
 *  without making somebody find their key again, which is what stops a UI teaching people to keep
 *  a credential somewhere convenient and less safe. */
@Serializable
data class UserAiKeySetBody(
    val key: String? = null,
    val model: String? = null
)
