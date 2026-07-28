package com.fieldrepository.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
// Borrowed from the ui package, and the direction is backwards on purpose: the consolidated
// questionnaire's DTOs live beside their screen because this file and ApiModels.kt were being edited
// concurrently when that feature landed. Re-declaring them here would give the app two spellings of
// one wire format; if they ever move into ApiModels.kt this import is the only line to delete.
import com.fieldrepository.app.ui.ConsolidatedQuestionnaireDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okio.BufferedSink
import retrofit2.HttpException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Files at/under this size upload as one streamed S3 PUT; larger files switch to a chunked S3
 * multipart upload (resilient/resumable, no 5 GB ceiling) that S3 stitches back into one object.
 */
private const val MULTIPART_THRESHOLD = 64L * 1024 * 1024

/** MIME type for the .xlsx report workbook (OOXML spreadsheet). */
private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/**
 * When a workshop actually took place, as a sortable ISO-8601 timestamp.
 *
 * `GET /workshops` orders rows by `createdAt` like every other record list, but for a researcher in
 * the field "the most recent workshop" means the most recent date of OCCURRENCE — so we prefer the
 * workshop's own `startDate`, fall back to the single-day `date`, and only use `createdAt` when the
 * row carries neither. Every value is ISO-8601, so lexicographic ordering is chronological.
 */
fun WorkshopDetailDto.occurrenceDate(): String = startDate ?: date ?: createdAt ?: ""

/** Reader for API error bodies only — lenient, because a failing server can return anything. */
private val errorBodyJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The message the API meant the user to read; failing that, the exception's own text, and only then
 * [fallback]. Never swallows a gateway/transport failure ("HTTP 504 Gateway Time-out", "Unable to
 * resolve host") behind a generic sentence — that text is the one clue that the save never landed.
 *
 * Retrofit collapses every non-2xx response into an `HttpException` whose `message` is just
 * "HTTP 409 Conflict" — nothing a researcher can act on when what they need to know is WHICH artisan
 * already holds the Aadhaar number they typed. FastAPI puts the usable text in `detail`, in one of
 * three shapes, all unwrapped here:
 *
 * - a plain string, from `raise HTTPException(detail="…")`;
 * - an object carrying a `message`, e.g. the artisan identity 409, whose message names the existing
 *   artisan and their place;
 * - a list of Pydantic validation errors (422), where each `msg` holds the field validator's own
 *   wording ("That Aadhaar number fails its checksum…") behind a "Value error, " prefix worth
 *   stripping. Those messages are written for the person filling the form, so they are surfaced
 *   verbatim rather than replaced with something generic.
 *
 * Retrofit buffers the error body, but reading it CONSUMES the buffer — call this once per failure.
 */
fun Throwable.apiErrorMessage(fallback: String): String {
    val plain = message?.takeIf { it.isNotBlank() } ?: fallback
    // Not an HTTP failure at all (no connection, timeout, serialization): the platform message is all
    // there is, and it is more informative than anything this function could invent.
    val http = this as? HttpException ?: return plain
    val raw = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
    if (raw.isNullOrBlank()) return plain
    val detail = (runCatching { errorBodyJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject)
        ?.get("detail")
        ?: return plain
    return detailMessage(detail) ?: plain
}

/** Pull the human-readable text out of whichever `detail` shape FastAPI returned. */
private fun detailMessage(detail: JsonElement): String? = when (detail) {
    is JsonPrimitive -> detail.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    is JsonObject -> (detail["message"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    is JsonArray -> detail
        .mapNotNull { entry ->
            ((entry as? JsonObject)?.get("msg") as? JsonPrimitive)?.contentOrNull
                ?.removePrefix("Value error, ")?.trim()?.takeIf { it.isNotEmpty() }
        }
        .distinct()
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }
    else -> null
}

/** Files in flight at once. Matches the web's UPLOAD_CONCURRENCY; see docs/MEDIA_PIPELINE.md. */
private const val UPLOAD_CONCURRENCY = 3

class FieldRepository(
    private val api: FieldRepositoryApi,
    private val tokenStore: TokenStore
) {
    // Generous timeouts (large videos over slow field connections) + automatic connection retry.
    private val storageClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.MINUTES)
        .build()

    private val offlineJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // Mirrors the Retrofit converter's config (ApiClient.kt:42) so a body re-encoded here to carry the
    // checksum is byte-identical to the one the plain call would have sent — same omitted nulls, same
    // omitted defaults. A `processingRequests: []` that should have been absent changes what the
    // server does with the file.
    private val completeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val syncMutex = Mutex()
    private val sweptStagedObjects = java.util.concurrent.atomic.AtomicBoolean(false)

    fun hasToken(): Boolean = !tokenStore.getToken().isNullOrBlank()

    /** Last known signed-in profile, used for instant, persistent login across resumes. */
    fun cachedUser(): UserDto? = tokenStore.getUser()

    suspend fun login(email: String, password: String): UserDto {
        val response = api.login(LoginRequest(email = email.trim(), password = password))
        tokenStore.setToken(response.accessToken)
        tokenStore.setUser(response.user)
        return response.user
    }

    suspend fun loginWithGoogle(idToken: String): UserDto {
        val response = api.googleLogin(GoogleLoginRequest(googleIdToken = idToken))
        tokenStore.setToken(response.accessToken)
        tokenStore.setUser(response.user)
        return response.user
    }

    fun logout() {
        tokenStore.clear()
    }

    suspend fun currentUser(): UserDto = api.me()

    /** Refresh the profile from the server and update the local cache. */
    suspend fun refreshUser(): UserDto {
        val user = api.me()
        tokenStore.setUser(user)
        return user
    }

    suspend fun stats(): DashboardStats = api.dashboardStats()

    /**
     * The state / union-territory list an address form renders its dropdown from.
     *
     * Cached for the life of the process. The payload is a server-side constant, so re-asking on
     * every form would buy nothing; a FAILURE is deliberately not cached, so the next form that opens
     * after the phone finds signal asks again rather than being stuck with an empty dropdown for the
     * rest of the session.
     */
    suspend fun addressReference(): AddressReferenceDto =
        cachedAddressReference ?: api.addressReference().also { cachedAddressReference = it }

    @Volatile
    private var cachedAddressReference: AddressReferenceDto? = null

    suspend fun users(): List<UserDto> = api.users(pageSize = 100).items

    suspend fun updateUserQuestionnaireAccess(id: String, canManageQuestionnaire: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageQuestionnaire = canManageQuestionnaire))

    suspend fun updateUserCraftAccess(id: String, canManageCrafts: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageCrafts = canManageCrafts))

    suspend fun updateUserWorkshopAccess(id: String, canManageWorkshops: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageWorkshops = canManageWorkshops))

    suspend fun updateUserReviewAccess(id: String, canReview: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canReview = canReview))

    suspend fun updateUserProvenanceAccess(id: String, canViewProvenance: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canViewProvenance = canViewProvenance))

    suspend fun updateUserDatasetAccess(id: String, canDownloadDataset: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canDownloadDataset = canDownloadDataset))

    /** Change a user's role (e.g. elevate RESEARCHER -> ADMIN). Master-admin gated server-side for ADMIN+. */
    suspend fun updateUserRole(id: String, role: String): UserDto =
        api.updateUser(id, UserUpdateRequest(role = role))

    // --- Cross-researcher data access (Sharing) ---
    suspend fun userDirectory(): List<UserDto> = api.userDirectory()
    suspend fun dataAccessTiers(): List<DataAccessTierInfo> = api.dataAccessTiers()
    suspend fun dataAccessGrants(): MyGrantsDto = api.dataAccessGrants()
    suspend fun requestDataAccess(ownerId: String, tier: String, note: String?): DataAccessGrantDto =
        api.requestDataAccess(DataAccessRequestBody(ownerId = ownerId, tier = tier, allData = true, requestNote = note?.ifBlank { null }))
    suspend fun grantDataAccess(granteeId: String, tier: String, allData: Boolean, scopeItems: List<DataAccessScopeItemDto>): DataAccessGrantDto =
        api.grantDataAccess(DataAccessGrantBody(granteeId = granteeId, tier = tier, allData = allData, scopeItems = scopeItems))
    suspend fun decideDataAccess(id: String, status: String, tier: String?): DataAccessGrantDto =
        api.decideDataAccess(id, DataAccessDecisionBody(status = status, tier = tier))
    suspend fun revokeDataAccess(id: String): DataAccessGrantDto = api.revokeDataAccess(id)
    suspend fun deleteDataAccess(id: String) = api.deleteDataAccess(id)
    suspend fun entryComments(recordType: String, recordId: String): List<EntryCommentDto> =
        api.entryComments(recordType, recordId)
    suspend fun addEntryComment(recordType: String, recordId: String, body: String): EntryCommentDto =
        api.addEntryComment(EntryCommentBody(recordType = recordType, recordId = recordId, body = body))
    suspend fun recordRevisions(recordType: String, recordId: String): List<RecordRevisionDto> =
        api.recordRevisions(recordType, recordId)

    // --- Workshop assignment (admin roster for ONE workshop) ---
    suspend fun workshopAssignments(workshopId: String): List<WorkshopAssignmentDto> =
        api.workshopAssignments(workshopId)

    /**
     * Replace the whole roster. Everyone in [userIds] becomes GRANTED; everyone dropped is REVOKED
     * (not deleted, so "X had access until Y removed them" survives). An EMPTY set therefore revokes
     * everybody, which — with no granted admin row left — reopens the workshop to all.
     */
    suspend fun setWorkshopAssignments(workshopId: String, userIds: List<String>, accessLevel: String? = null): List<WorkshopAssignmentDto> =
        api.setWorkshopAssignments(workshopId, WorkshopAssignmentBody(userIds, accessLevel))

    /** Grant one user access at a level without disturbing the rest of the roster (upsert). */
    suspend fun grantWorkshopAccess(workshopId: String, userId: String, accessLevel: String, note: String? = null): WorkshopAssignmentDto =
        api.grantWorkshopAssignment(workshopId, WorkshopGrantBody(userId = userId, accessLevel = accessLevel, note = note?.blankToNull()))

    /** Raise/lower one roster row's level, and/or set it GRANTED | DENIED | REVOKED. */
    suspend fun updateWorkshopAccess(workshopId: String, userId: String, accessLevel: String? = null, status: String? = null, note: String? = null): WorkshopAssignmentDto =
        api.updateWorkshopAssignment(
            workshopId,
            userId,
            WorkshopAssignmentUpdateBody(accessLevel = accessLevel, status = status, note = note?.blankToNull())
        )

    suspend fun revokeWorkshopAccess(workshopId: String, userId: String): WorkshopAssignmentDto =
        api.revokeWorkshopAssignment(workshopId, userId)

    // --- Workshop access requests (user side + admin queue) ---
    suspend fun workshopAccessLevels(): List<WorkshopAccessLevelDto> = api.workshopAccessLevels()

    /** Ask for access to several workshops at once. Idempotent per workshop; see the outcomes list. */
    suspend fun requestWorkshopAccess(workshopIds: List<String>, accessLevel: String?, note: String?): WorkshopAccessRequestResultDto =
        api.requestWorkshopAccess(
            WorkshopAccessRequestBody(
                workshopIds = workshopIds,
                accessLevel = accessLevel?.blankToNull(),
                note = note?.blankToNull()
            )
        )

    /** Every workshop-access row belonging to me: held, waiting, and refused — not just the pending ones. */
    suspend fun myWorkshopAccess(): List<WorkshopAssignmentDto> = api.myWorkshopAccess()

    /** Admin: the PENDING approval queue across ALL workshops (oldest first). */
    suspend fun workshopAccessQueue(statusFilter: String = "PENDING"): List<WorkshopAssignmentDto> =
        api.workshopAccessRequests(statusFilter)

    /** Admin: answer a PENDING request. [status] is GRANTED or DENIED; anything else is a 422. */
    suspend fun decideWorkshopAccess(requestId: String, status: String, accessLevel: String? = null, note: String? = null): WorkshopAssignmentDto =
        api.decideWorkshopAccess(
            requestId,
            WorkshopAccessDecisionBody(status = status, accessLevel = accessLevel, note = note?.blankToNull())
        )

    // --- Assigned tasks ---

    /**
     * My to-do list. [view] "created"/"all" are admin-only planning views and 403 for everyone else.
     *
     * [assigneeId] and [batchId] are admin-only narrowings — on the default "assigned" view the API
     * hard-pins the list to the caller, so they cannot be used to read somebody else's tasks.
     */
    suspend fun tasks(
        view: String = "assigned",
        status: String? = null,
        workshopId: String? = null,
        assigneeId: String? = null,
        batchId: String? = null,
        pageSize: Int = 100
    ): List<TaskDto> =
        api.tasks(
            view = view,
            status = status?.blankToNull(),
            workshopId = workshopId?.blankToNull(),
            pageSize = pageSize,
            assigneeId = assigneeId?.blankToNull(),
            batchId = batchId?.blankToNull()
        ).items

    /** One task, enriched exactly like a list item. Visible to the assignee, the creator and admins. */
    suspend fun task(taskId: String): TaskDto = api.task(taskId)

    /** Assignee-side update: move the status and/or report how much is done. */
    suspend fun updateTaskProgress(taskId: String, status: String? = null, progressCount: Int? = null): TaskDto =
        api.updateTask(taskId, TaskUpdateBody(status = status, progressCount = progressCount))

    // --- Task administration (admin) ---

    /**
     * Every picker the assignment builder needs, in one call. Pass [workshopId] to narrow the artisan
     * list to that workshop; the assignee list is already filtered to who this admin may assign to.
     */
    suspend fun taskOptions(workshopId: String? = null): TaskOptionsDto =
        api.taskOptions(workshopId?.blankToNull())

    /**
     * Hand ONE scope to several people at once — the assignment action. All-or-nothing: a bad
     * assignee or a typo'd artisan id fails the whole call rather than leaving half a batch behind.
     *
     * The scope must contain work ([recordTypes] and/or [sectionIds] non-empty) or the API 422s.
     * Empty [artisanIds]/[sectionIds] mean "not narrowed". Omit [title] to let the server derive a
     * readable one from the scope. [dueAt] is ISO-8601.
     */
    suspend fun createTaskBatch(
        assigneeIds: List<String>,
        workshopId: String? = null,
        recordTypes: List<String> = emptyList(),
        artisanIds: List<String> = emptyList(),
        sectionIds: List<String> = emptyList(),
        targetCount: Int? = null,
        title: String? = null,
        description: String? = null,
        dueAt: String? = null
    ): TaskBatchResultDto =
        api.createTaskBatch(
            TaskBatchCreateBody(
                assigneeIds = assigneeIds,
                workshopId = workshopId?.blankToNull(),
                recordTypes = recordTypes,
                artisanIds = artisanIds,
                sectionIds = sectionIds,
                targetCount = targetCount,
                title = title?.blankToNull(),
                description = description?.blankToNull(),
                dueAt = dueAt?.blankToNull()
            )
        )

    /**
     * Assignments grouped back into the action that created them, newest first. The filters choose
     * which batches are SHOWN; every count reported is for the whole batch regardless.
     */
    suspend fun taskBatches(
        workshopId: String? = null,
        view: String = "all",
        batchId: String? = null,
        assigneeId: String? = null,
        status: String? = null,
        page: Int = 1,
        pageSize: Int = 20
    ): PageResponse<TaskBatchDto> =
        api.taskBatches(
            view = view,
            workshopId = workshopId?.blankToNull(),
            batchId = batchId?.blankToNull(),
            assigneeId = assigneeId?.blankToNull(),
            status = status?.blankToNull(),
            page = page,
            pageSize = pageSize
        )

    /**
     * The accountability rollup: what each person was given, what they claim, and what the repository
     * can actually find them having produced. Leave [workshopId] off for the organisation-wide view.
     */
    suspend fun taskProgress(
        workshopId: String? = null,
        assigneeId: String? = null,
        includeFinished: Boolean = true
    ): TaskProgressReportDto =
        api.taskProgress(
            workshopId = workshopId?.blankToNull(),
            assigneeId = assigneeId?.blankToNull(),
            includeFinished = includeFinished
        )

    /** Withdraw a whole assignment. Only the admin who sent it, or the master admin, may unsend it. */
    suspend fun deleteTaskBatch(batchId: String) = api.deleteTaskBatch(batchId)

    /** Withdraw ONE row — the way to remove a pre-batch/single-assignee assignment (batchId null). */
    suspend fun deleteTask(taskId: String) = api.deleteTask(taskId)

    // --- Managed provider keys (MASTER ADMIN ONLY; everyone else gets a 403) ---

    /**
     * Every manageable key with where its value comes from and how its last test went. No provider is
     * contacted, and no row here ever carries a value — only a four-character hint.
     */
    suspend fun managedSecrets(): List<ManagedSecretDto> = api.managedSecrets()

    /** The plaintext of ONE key, for the eye button. The read is audit-logged server-side. */
    suspend fun revealSecret(key: String): ManagedSecretRevealDto = api.revealSecret(key)

    /**
     * Set or rotate a key. Takes effect on the next provider call — no restart, no redeploy. Blank is
     * a 422 by design: use [clearSecret] to fall back to the deployed environment value.
     */
    suspend fun setSecret(key: String, value: String): ManagedSecretDto =
        api.setSecret(key, ManagedSecretSetBody(value = value.trim()))

    /** Drop the stored override so the environment value applies again. Returns the key's new state. */
    suspend fun clearSecret(key: String): ManagedSecretDto = api.clearSecret(key)

    /** Call the provider once with the key in force; the verdict is persisted onto the row. */
    suspend fun testSecret(key: String): ManagedSecretDto = api.testSecret(key)

    // --- Appearance + accessibility preferences ---

    /**
     * This account's saved preferences, or NULL when it has never saved any.
     *
     * Null means "no opinion yet", not "the defaults": keep whatever the device already applied and
     * seed the server with it via [savePreferences], rather than snapping the user back to system.
     */
    suspend fun myPreferences(): PreferencesDto? = api.myPreferences().takeIf { it.exists }

    /**
     * Create or update this account's preferences. Sent whole on every save. [theme] is
     * `system` | `light` | `dark`; anything else is a 422.
     */
    suspend fun savePreferences(
        theme: String = "system",
        reducedMotion: Boolean = false,
        largerText: Boolean = false,
        highContrast: Boolean = false
    ): PreferencesDto =
        api.updateMyPreferences(
            PreferencesUpdateBody(
                theme = theme,
                reducedMotion = reducedMotion,
                largerText = largerText,
                highContrast = highContrast
            )
        )

    /** Save a whole [PreferencesDto] back (the round-trip form of [savePreferences]). */
    suspend fun savePreferences(preferences: PreferencesDto): PreferencesDto =
        savePreferences(
            theme = preferences.theme,
            reducedMotion = preferences.reducedMotion,
            largerText = preferences.largerText,
            highContrast = preferences.highContrast
        )

    // --- Global search ---

    /**
     * Search artisans, workshops, products, tools and media at once. Every argument is optional; the
     * five buckets share one [page]/[pageSize] but each has its own length and its own total, so page
     * against `totals`/`pageCount`, never against how full one bucket happens to be.
     *
     * Every filter ANDs: a query plus a place plus a date range narrows to the rows satisfying all
     * three, never their union.
     *
     * [types] names the buckets to search in the API's own PLURAL vocabulary — `artisans`,
     * `workshops`, `products`, `tools`, `media` — not the singular record type a search hit reports.
     * Null or empty searches all five. An unrecognised name is a 422 rather than a silent omission,
     * so nothing here invents one: the caller passes the canonical list and this only tidies it.
     *
     * [dateFrom]/[dateTo] are ISO-8601 instants. The API takes DATES, never preset names — "Last 30
     * days" is a phrase in a UI and only the client knows the clock it is counted against — so the
     * caller resolves its presets before it gets here. [pageSize] is capped at 50 server-side.
     */
    suspend fun search(
        q: String? = null,
        craftId: String? = null,
        place: String? = null,
        artisanId: String? = null,
        mediaType: String? = null,
        types: List<String>? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        workshopIds: List<String>? = null,
        page: Int = 1,
        pageSize: Int = 10
    ): SearchResultsDto =
        api.search(
            q = q?.blankToNull(),
            craftId = craftId?.blankToNull(),
            place = place?.blankToNull(),
            artisanId = artisanId?.blankToNull(),
            mediaType = mediaType?.blankToNull(),
            types = types
                ?.mapNotNull { it.trim().lowercase().blankToNull() }
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(","),
            dateFrom = dateFrom?.blankToNull(),
            dateTo = dateTo?.blankToNull(),
            workshopIds = workshopIds.toQueryCsv(),
            page = page,
            pageSize = pageSize.coerceIn(1, 50)
        )

    // --- Map: where the records are ---

    /**
     * Every pin for the current filters, in BOTH layers — where the craft comes from (ORIGIN) and
     * where it was recorded (CAPTURE). The filter vocabulary is [search]'s, argument for argument, so
     * one set of UI filters drives both and the two can never disagree about what a phrase contains.
     *
     * [types] names the buckets in the API's PLURAL vocabulary (`artisans`, `workshops`, `products`,
     * `tools`, `media`); null or empty counts all five.
     *
     * [workshopIds] is the shared workshop SCOPE. Null or empty means EVERY workshop — it is not a
     * narrowing at all — and the reserved id `none` means "records linked to no workshop", so
     * `listOf("none")` is a real and different question from `null`.
     *
     * [level] is `NATION` | `STATE` | `DISTRICT`: the administrative unit both layers are grouped at.
     * Null lets the server apply its own default rather than hard-coding a second copy of it here;
     * read the level actually used back off `MapPointsDto.level`, and build the toggle from
     * `MapPointsDto.levels`.
     *
     * [focusType] + [focusId] ask for one record in context. Pass BOTH or neither — one alone is
     * ignored — and note the map still draws the whole filtered corpus; the focus only names which
     * pins hold that record, in `MapFocusDto.pointKeys`.
     */
    suspend fun mapPoints(
        q: String? = null,
        craftId: String? = null,
        place: String? = null,
        artisanId: String? = null,
        mediaType: String? = null,
        types: List<String>? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        workshopIds: List<String>? = null,
        level: String? = null,
        focusType: String? = null,
        focusId: String? = null
    ): MapPointsDto =
        api.mapPoints(
            q = q?.blankToNull(),
            craftId = craftId?.blankToNull(),
            place = place?.blankToNull(),
            artisanId = artisanId?.blankToNull(),
            mediaType = mediaType?.blankToNull(),
            types = types?.map { it.lowercase() }.toQueryCsv(),
            dateFrom = dateFrom?.blankToNull(),
            dateTo = dateTo?.blankToNull(),
            workshopIds = workshopIds.toQueryCsv(),
            level = level?.blankToNull(),
            focusType = focusType?.blankToNull(),
            focusId = focusId?.blankToNull()
        )

    /**
     * The records behind ONE pin, fetched when a reader opens it rather than carried by [mapPoints] —
     * the aggregate is a couple of dozen pins, but the records behind every pin would be the whole
     * corpus in a payload that exists to draw thirteen dots.
     *
     * [key] is `MapPointDto.key`, passed through UNTOUCHED: it holds ':' and '|' and the encoding is
     * Retrofit's job (see `FieldRepositoryApi.mapPointRecords`). Do not trim, split or re-case it.
     *
     * PASS THE SAME FILTERS THE MAP WAS DRAWN WITH, [level] and [workshopIds] included. The key names
     * an administrative unit; which records sit in it is exactly what the filters decide, so a panel
     * fetched with different filters would list records the pin was not counting.
     */
    suspend fun mapPointRecords(
        key: String,
        q: String? = null,
        craftId: String? = null,
        place: String? = null,
        artisanId: String? = null,
        mediaType: String? = null,
        types: List<String>? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        workshopIds: List<String>? = null,
        level: String? = null
    ): MapPointRecordsDto =
        api.mapPointRecords(
            key = key,
            q = q?.blankToNull(),
            craftId = craftId?.blankToNull(),
            place = place?.blankToNull(),
            artisanId = artisanId?.blankToNull(),
            mediaType = mediaType?.blankToNull(),
            types = types?.map { it.lowercase() }.toQueryCsv(),
            dateFrom = dateFrom?.blankToNull(),
            dateTo = dateTo?.blankToNull(),
            workshopIds = workshopIds.toQueryCsv(),
            level = level?.blankToNull()
        )

    // --- Data browser ---

    /**
     * ONE level of the virtual data tree. Lazy: only this level's queries run, so navigate by calling
     * this again with an entry's `path`. `path = ""` is the taxonomy chooser, not a folder listing.
     *
     * Needs the dataset-download permission (403 otherwise) and everything listed is already filtered
     * to what the caller may see.
     */
    suspend fun dataTree(path: String = ""): DataTreeDto = api.dataTree(path)

    /**
     * The tree folder that holds [recordId], or null when nothing files it yet — an artisan who has
     * never been attached to a workshop genuinely has no folder, so the caller must say so rather
     * than open the nearest one, which would belong to somebody else.
     *
     * [recordType] is one of `workshop`, `craft`, `artisan`, `product`, `tool`, `process`,
     * `interview`, `media` — the same vocabulary the search buckets hand back.
     */
    suspend fun locateRecord(recordType: String, recordId: String): String? {
        val body = api.dataLocate(recordType, recordId) as? JsonObject ?: return null
        return (body["path"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /**
     * The flattened subtree below [path]. [include] is a CSV of
     * `text,images,videos,audios,transcripts,documents,other`; null means everything.
     */
    suspend fun dataManifest(path: String = "", include: String? = null): DataManifestDto =
        api.dataManifest(path, include?.blankToNull())

    /** Records awaiting review (status PENDING), newest first, across record types. */
    suspend fun pendingReviews(): List<PendingReviewDto> = api.pendingReviews().items

    /** Approve a pending record (admins, or users granted the review permission). */
    suspend fun approveRecord(recordType: String, recordId: String) {
        api.approveRecord(recordType, recordId, ReviewActionRequest())
    }

    /** Reject a pending record (admins, or users granted the review permission). */
    suspend fun rejectRecord(recordType: String, recordId: String) {
        api.rejectRecord(recordType, recordId, ReviewActionRequest())
    }

    /** Send a record back to its creator. [notes] is mandatory — the API 422s on a blank one. */
    suspend fun reviseRecord(recordType: String, recordId: String, notes: String) {
        api.reviseRecord(recordType, recordId, ReviewActionRequest(notes = notes))
    }

    /**
     * Reviewer edit: fix the record's values in place rather than bouncing it back. Only the keys in
     * [fields] are written and the status is left alone, so this is never a back-door approval.
     */
    suspend fun editReviewedRecord(recordType: String, recordId: String, fields: Map<String, String>, note: String?) {
        api.editReviewedRecord(
            recordType,
            recordId,
            ReviewEditRequest(fields = fields, note = note?.blankToNull())
        )
    }

    // --- Over-the-air app update ---

    /** versionCode baked into the currently-installed app, for comparing against a published release. */
    fun installedVersionCode(context: Context): Int {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pkg.versionCode
    }

    /**
     * Master admin: publish the currently-installed APK as the over-the-air update for everyone. The
     * app reads its own installed APK, uploads it to object storage, and records the version so other
     * devices can discover and self-install it on next launch.
     */
    suspend fun publishAppUpdate(context: Context): AppReleaseDto {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = installedVersionCode(context)
        val versionName = pkg.versionName ?: versionCode.toString()
        val apk = File(context.applicationInfo.sourceDir)
        val size = apk.length()
        val mime = "application/vnd.android.package-archive"
        val presign = api.presignMedia(
            MediaPresignRequest(
                filename = "field-repository-v$versionCode.apk",
                mimeType = mime,
                mediaType = "DOCUMENT",
                sizeBytes = size
            )
        )
        withContext(Dispatchers.IO) {
            putToStorage(presign.uploadUrl, presign.headers, size, mime, { FileInputStream(apk) }, null)
        }
        return api.publishAppRelease(
            AppReleasePublishRequest(
                versionCode = versionCode,
                versionName = versionName,
                objectKey = presign.objectKey,
                url = presign.publicUrl
            )
        )
    }

    /** The currently-published release (highest versionCode), or versionCode 0 when none exists. */
    suspend fun latestAppRelease(): AppReleaseDto = api.latestAppRelease()

    /** The current user's own app feedback (empty/blank id when they haven't given any yet). */
    suspend fun myFeedback(): FeedbackDto = api.myFeedback()

    /** Create or update the current user's detailed feedback (they can revisit and change it anytime). */
    suspend fun upsertMyFeedback(request: FeedbackUpsertRequest): FeedbackDto =
        api.upsertMyFeedback(request)

    /** Master-admin only: all users' feedback, newest first, each with its author. */
    suspend fun allFeedback(): List<FeedbackDto> = api.allFeedback()

    /** Master-admin only: the global app settings (transcription mode + off-peak processing window). */
    suspend fun appSettings(): AppSettingDto = api.appSettings()

    /** Master-admin only: update the global app settings. */
    suspend fun updateAppSettings(request: AppSettingUpdateRequest): AppSettingDto =
        api.updateAppSettings(request)

    /** Admin-only: media files whose parent record was deleted (recoverable, not lost). */
    suspend fun orphanedMedia(): List<MediaFileDto> = api.orphanMedia()

    /** Admin-only: re-attach an orphaned/mis-linked media file to an existing record. */
    suspend fun relinkMedia(mediaId: String, linkedRecordType: String, linkedRecordId: String): MediaFileDto =
        api.relinkMedia(mediaId, MediaRelinkRequest(linkedRecordType = linkedRecordType, linkedRecordId = linkedRecordId))

    /**
     * AI-refine a media file's transcript into a clean interviewer/interviewee conversation (Markdown),
     * optionally translated to English. Billable (gpt-4o-mini) — the caller confirms cost first.
     */
    suspend fun refineTranscript(mediaId: String, translate: Boolean): TranscriptRefineResponse =
        api.refineTranscript(mediaId, TranscriptRefineRequest(translate = translate))

    /** Save an approved (AI-refined) transcript in place of the stored one. Uploader or admin only. */
    suspend fun applyTranscript(mediaId: String, text: String): MediaFileDto =
        api.setTranscript(mediaId, TranscriptUpdateRequest(text = text))

    /**
     * Admin/master-admin: transcribe an audio media file right now, applying the transcription mode
     * configured on the settings page (raw / refined / refined+translated), bypassing the off-peak
     * window. Returns the updated media row (its transcriptStatus/Text reflect the outcome).
     */
    suspend fun transcribeNow(mediaId: String): MediaFileDto = api.transcribeNow(mediaId)

    /** Download an update APK to the cache and return the file, for handing to the system installer. */
    suspend fun downloadApk(context: Context, url: String, versionCode: Int): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } } // drop older downloads
        val out = File(dir, "field-repository-v$versionCode.apk")
        val request = Request.Builder().url(url).get().build()
        storageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Update download failed: HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Update download returned no body")
            body.byteStream().use { input -> FileOutputStream(out).use { output -> input.copyTo(output, 64 * 1024) } }
        }
        out
    }

    /**
     * [workshopIds] is the shared workshop scope: null or empty is EVERY workshop, and the reserved
     * id `none` asks for artisans linked to no workshop. Broader than the singular `workshopId` the
     * form pickers use — it also counts an artisan who merely sat in an interview taken at the
     * workshop — so this list and the completion matrix agree about who was there.
     */
    suspend fun artisans(workshopIds: List<String>? = null): List<ArtisanDto> =
        api.artisans(pageSize = 100, workshopIds = workshopIds.toQueryCsv()).items

    suspend fun crafts(): List<CraftDto> = api.crafts(pageSize = 100).items

    suspend fun products(): List<ProductDetailDto> = api.products(pageSize = 100).items

    /**
     * Products the server links to a given artisan. Covers datasets with >100 total products, and —
     * when the artisan's name is supplied — also returns legacy products that carry only the typed
     * artisan name with no FK link (the server OR-matches by name for FK-null rows). This is what
     * makes the process form's product dropdown reliable instead of intermittently empty.
     */
    suspend fun productsForArtisan(artisanId: String, artisanName: String? = null): List<ProductDetailDto> =
        api.products(pageSize = 100, artisanId = artisanId, artisanName = artisanName?.trim()?.ifBlank { null }).items

    suspend fun tools(): List<ToolDetailDto> = api.tools(pageSize = 100).items

    /** Artisans a tool is assigned to (many-to-many). */
    suspend fun toolArtisans(toolId: String): List<ArtisanDto> = api.toolArtisans(toolId)

    /** Assign a tool to the given artisans (idempotent). Returns the full updated assignment list. */
    suspend fun assignToolArtisans(toolId: String, artisanIds: List<String>): List<ArtisanDto> =
        api.assignToolArtisans(toolId, ToolArtisanAssignRequest(artisanIds))

    suspend fun unassignToolArtisan(toolId: String, artisanId: String) = api.unassignToolArtisan(toolId, artisanId)

    suspend fun workshops(): List<WorkshopDetailDto> = api.workshops(pageSize = 100).items

    /**
     * The workshops this user can SEE — `GET /workshops` is scoped by row visibility — ordered by
     * date of occurrence, most recent first. This is the single source of truth for every record
     * form's workshop dropdown: the list order is what the picker shows, and its first entry is the
     * one pre-selected when creating a new record.
     *
     * Visible is NOT the same as submittable. The API separately 403s a submission into a workshop
     * that has assignments the user is not part of, and flags a submission made outside the
     * workshop's [startDate, endDate] window as needing admin approval — neither of which this list
     * filters out. `GET /workshops/{id}/submission-check` is the pre-flight for both.
     */
    suspend fun workshopsByOccurrence(): List<WorkshopDetailDto> =
        workshops().sortedByDescending { it.occurrenceDate() }

    /**
     * The pre-flight above: what submitting a record into [workshopId] would mean for this user.
     *
     * Returns null instead of throwing when the answer cannot be had — the endpoint is missing, the
     * phone is offline, or the server hiccupped. A record form MUST read null as "no answer" and let
     * the save proceed: a researcher standing in a field must never lose an entry to a failed
     * courtesy request. The endpoint itself never 403s, so a real refusal always arrives as
     * `canSubmit = false` inside a successful response.
     */
    suspend fun workshopSubmissionCheck(workshopId: String): WorkshopSubmissionCheckDto? =
        runCatching { api.workshopSubmissionCheck(workshopId) }.getOrNull()

    suspend fun createArtisan(body: ArtisanCreateRequest): ArtisanDto = api.createArtisan(body)

    suspend fun artisan(id: String): ArtisanDetailDto = api.artisan(id)

    /**
     * Is this Aadhaar number already on an artisan? The form's pre-flight duplicate check, run while
     * the researcher is still typing so a duplicate surfaces before the whole form is filled in rather
     * than as a 409 on save. [number] may be typed with spacing; the API normalises it.
     */
    suspend fun lookupArtisanByAadhaar(number: String): AadhaarLookupDto =
        api.lookupArtisanByAadhaar(number.trim())

    suspend fun updateArtisan(id: String, body: ArtisanCreateRequest): ArtisanDetailDto = api.updateArtisan(id, body)

    suspend fun artisanQuestionnaire(id: String): ArtisanQuestionnaireDto = api.artisanQuestionnaire(id)

    suspend fun media(): List<MediaFileDto> = api.media(pageSize = 20).items

    /** A broader media list for the View Data "Miscellaneous Media" browser (most recent first). */
    suspend fun mediaList(): List<MediaFileDto> = api.media(pageSize = 100).items

    /** One media file by id, for the View Data media detail. */
    suspend fun mediaItem(id: String): MediaFileDto = api.getMedia(id)

    /** Delete one saved media file (its DB row + S3 object). Backend allows the uploader or an admin. */
    suspend fun deleteMedia(id: String) = api.deleteMedia(id)

    // Admin-only deletes (backend enforces is_admin; 403 otherwise).
    suspend fun deleteArtisan(id: String) = api.deleteArtisan(id)
    suspend fun deleteCraft(id: String) = api.deleteCraft(id)
    suspend fun deleteProduct(id: String) = api.deleteProduct(id)
    suspend fun deleteTool(id: String) = api.deleteTool(id)
    suspend fun deleteWorkshop(id: String) = api.deleteWorkshop(id)
    suspend fun deleteProcess(id: String) = api.deleteProcess(id)
    suspend fun deleteInterview(id: String) = api.deleteInterview(id)

    /** Result of a full-dataset download: where it was saved and how many files succeeded. */
    data class DatasetDownloadResult(val displayLocation: String, val saved: Int, val total: Int, val failed: Int)

    /**
     * Pull the full dataset manifest, then download every media object straight from S3 and zip the
     * whole directory tree to the device's Downloads folder. [onProgress] reports (done, total) as each
     * entry is written so the UI can show real progress. Individual file failures are skipped, not fatal.
     */
    suspend fun downloadDataset(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit
    ): DatasetDownloadResult = withContext(Dispatchers.IO) {
        val manifest = api.datasetManifest()
        val total = manifest.files.size
        val stamp = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val zipName = "FieldRepository_dataset_$stamp.zip"
        val tmp = File(context.cacheDir, zipName)
        var failed = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
            manifest.files.forEachIndexed { index, f ->
                runCatching {
                    zip.putNextEntry(ZipEntry(f.path))
                    when {
                        f.content != null -> zip.write(f.content.toByteArray(Charsets.UTF_8))
                        f.url != null -> {
                            val request = Request.Builder().url(f.url).build()
                            storageClient.newCall(request).execute().use { resp ->
                                if (resp.isSuccessful) resp.body?.byteStream()?.copyTo(zip) else throw IllegalStateException("HTTP ${resp.code}")
                            }
                        }
                    }
                    zip.closeEntry()
                }.onFailure {
                    failed++
                    runCatching { zip.closeEntry() }
                }
                onProgress(index + 1, total)
            }
        }
        val location = persistFileToDownloads(context, tmp, zipName, "application/zip")
        tmp.delete()
        DatasetDownloadResult(displayLocation = location, saved = total - failed, total = total, failed = failed)
    }

    /**
     * Download the styled .xlsx relational report straight into the public Downloads folder (same
     * MediaStore path the dataset zip uses) and return where it was saved.
     *
     * [path] scopes the report to one subtree of the data browser; the default, "", is the whole
     * dataset — which is what every caller before the data browser existed meant.
     */
    suspend fun downloadReport(context: Context, path: String = ""): String = withContext(Dispatchers.IO) {
        val response = api.dataReport(format = "xlsx", path = path)
        if (!response.isSuccessful) throw IllegalStateException("Report request failed (HTTP ${response.code()})")
        val body = response.body() ?: throw IllegalStateException("The report response was empty")
        val stamp = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val name = "FieldRepository_report_$stamp.xlsx"
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val location = persistFileToDownloads(context, tmp, name, XLSX_MIME)
        tmp.delete()
        location
    }

    /**
     * Zip ONE folder of the data browser into the device's Downloads folder.
     *
     * The same shape as [downloadDataset], but scoped to [path] and filterable with [include] (a CSV
     * of `text,images,videos,audios,transcripts,documents,other`; null means everything). Generated
     * text entries are written from their inline `content` — no request at all. Audio marked
     * `convertToMp4` is fetched from the API as an .mp4 and falls back to the original object when
     * the server cannot convert it, exactly as the web does. A file that fails is counted and
     * skipped, never fatal.
     *
     * [folderName] names the .zip; the requested folder's own name is the natural choice.
     */
    suspend fun downloadDataFolder(
        context: Context,
        path: String,
        include: String? = null,
        folderName: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): DatasetDownloadResult = withContext(Dispatchers.IO) {
        val manifest = api.dataManifest(path, include?.blankToNull())
        val total = manifest.files.size
        val stamp = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val stem = (folderName ?: path.substringAfterLast('/')).blankToNull()
            ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")?.take(60)
            ?: "dataset"
        val zipName = "FieldRepository_${stem}_$stamp.zip"
        val tmp = File(context.cacheDir, zipName)
        var failed = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
            manifest.files.forEachIndexed { index, f ->
                runCatching {
                    zip.putNextEntry(ZipEntry(f.path))
                    when {
                        f.content != null -> zip.write(f.content.toByteArray(Charsets.UTF_8))
                        f.convertToMp4 && f.mediaId != null ->
                            if (!writeConvertedMedia(f.mediaId, zip)) writeObject(f.url, zip)
                        else -> writeObject(f.url, zip)
                    }
                    zip.closeEntry()
                }.onFailure {
                    failed++
                    runCatching { zip.closeEntry() }
                }
                onProgress(index + 1, total)
            }
        }
        val location = persistFileToDownloads(context, tmp, zipName, "application/zip")
        tmp.delete()
        DatasetDownloadResult(displayLocation = location, saved = total - failed, total = total, failed = failed)
    }

    /** Stream the API's .mp4 conversion of one audio row into [sink]. False = let the caller fall back. */
    private suspend fun writeConvertedMedia(mediaId: String, sink: java.io.OutputStream): Boolean =
        runCatching {
            val response = api.downloadDataMedia(mediaId, "mp4")
            val body = response.body()
            if (!response.isSuccessful || body == null) return@runCatching false
            body.byteStream().use { it.copyTo(sink) }
            true
        }.getOrDefault(false)

    /** Stream a stored object straight from its (presigned) URL into [sink]. */
    private fun writeObject(url: String?, sink: java.io.OutputStream) {
        if (url.isNullOrBlank()) return
        storageClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.byteStream()?.copyTo(sink)
        }
    }

    /**
     * Save ONE media file from the data browser into Downloads and return where it landed. Audio
     * arrives as an .mp4 (AAC) the server transcodes on the fly, which is what makes a field
     * recording playable on any device; pass [format] = "original" to bypass that.
     */
    suspend fun downloadDataMedia(
        context: Context,
        mediaId: String,
        filename: String,
        format: String? = null
    ): String = withContext(Dispatchers.IO) {
        val response = api.downloadDataMedia(mediaId, format?.blankToNull())
        if (!response.isSuccessful) throw IllegalStateException("Download failed (HTTP ${response.code()})")
        val body = response.body() ?: throw IllegalStateException("The download response was empty")
        val name = filename.blankToNull()?.replace(Regex("[^A-Za-z0-9._-]+"), "_") ?: mediaId
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val mime = response.headers()["Content-Type"]?.substringBefore(';')?.trim().blankToNull()
            ?: "application/octet-stream"
        val location = persistFileToDownloads(context, tmp, name, mime)
        tmp.delete()
        location
    }

    /** Copy a built file into the public Downloads collection (MediaStore on Q+, file path below). */
    private fun persistFileToDownloads(context: Context, source: File, name: String, mimeType: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the download entry")
            resolver.openOutputStream(uri).use { out -> source.inputStream().use { it.copyTo(out!!) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Downloads/$name"
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()
        val dest = File(downloads, name)
        source.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    /** All media attached to a specific record, used by the View Data screen (with transcripts). */
    suspend fun mediaForRecord(linkedRecordType: String, linkedRecordId: String): List<MediaFileDto> =
        api.media(pageSize = 100, linkedRecordType = linkedRecordType, linkedRecordId = linkedRecordId).items

    suspend fun processes(): List<ProcessDetailDto> = api.processes(pageSize = 100).items

    suspend fun process(id: String): ProcessDetailDto = api.process(id)

    suspend fun createProcess(body: ProcessCreateRequest): ProcessDetailDto = api.createProcess(body)

    suspend fun updateProcess(id: String, body: ProcessCreateRequest): ProcessDetailDto = api.updateProcess(id, body)

    suspend fun createCraft(body: CraftCreateRequest): CreatedRecordDto = api.createCraft(body)

    suspend fun craft(id: String): CraftDto = api.craft(id)

    suspend fun updateCraft(id: String, body: CraftCreateRequest): CraftDto = api.updateCraft(id, body)

    suspend fun createWorkshop(body: WorkshopCreateRequest): CreatedRecordDto = api.createWorkshop(body)

    suspend fun workshop(id: String): WorkshopDetailDto = api.workshop(id)

    suspend fun updateWorkshop(id: String, body: WorkshopCreateRequest): WorkshopDetailDto = api.updateWorkshop(id, body)

    suspend fun createProduct(body: ProductCreateRequest): CreatedRecordDto = api.createProduct(body)

    suspend fun product(id: String): ProductDetailDto = api.product(id)

    suspend fun updateProduct(id: String, body: ProductCreateRequest): ProductDetailDto = api.updateProduct(id, body)

    suspend fun createTool(body: ToolCreateRequest): CreatedRecordDto = api.createTool(body)

    suspend fun tool(id: String): ToolDetailDto = api.tool(id)

    suspend fun updateTool(id: String, body: ToolCreateRequest): ToolDetailDto = api.updateTool(id, body)

    suspend fun questionnaireQuestions(): List<QuestionnaireQuestionDto> = api.questionnaireQuestions()

    suspend fun questionnaireSections(): List<QuestionnaireSectionDto> = api.questionnaireSections()

    suspend fun createQuestionnaireSection(body: QuestionnaireSectionCreateRequest): QuestionnaireSectionDto =
        api.createQuestionnaireSection(body)

    suspend fun updateQuestionnaireSection(id: String, body: QuestionnaireSectionUpdateRequest): QuestionnaireSectionDto =
        api.updateQuestionnaireSection(id, body)

    suspend fun deleteQuestionnaireSection(id: String) {
        api.deleteQuestionnaireSection(id)
    }

    suspend fun reorderQuestionnaireSections(sectionIds: List<String>): List<QuestionnaireSectionDto> =
        api.reorderQuestionnaireSections(QuestionnaireSectionReorderRequest(sectionIds))

    suspend fun createQuestionnaireQuestion(body: QuestionnaireQuestionCreateRequest): QuestionnaireQuestionDto =
        api.createQuestionnaireQuestion(body)

    suspend fun updateQuestionnaireQuestion(id: String, body: QuestionnaireQuestionUpdateRequest): QuestionnaireQuestionDto =
        api.updateQuestionnaireQuestion(id, body)

    suspend fun deleteQuestionnaireQuestion(id: String) {
        api.deleteQuestionnaireQuestion(id)
    }

    suspend fun reorderQuestionnaireQuestions(sectionId: String, questionIds: List<String>): List<QuestionnaireSectionDto> =
        api.reorderQuestionnaireQuestions(QuestionnaireQuestionReorderRequest(sectionId, questionIds))

    suspend fun createQuestionnaireInterview(body: QuestionnaireInterviewCreateRequest): CreatedRecordDto =
        api.createQuestionnaireInterview(body)

    suspend fun interviews(): List<QuestionnaireInterviewDetailDto> = api.interviews(pageSize = 100).items

    suspend fun interview(id: String): QuestionnaireInterviewDetailDto = api.interview(id)

    suspend fun updateQuestionnaireInterview(id: String, body: QuestionnaireInterviewUpdateRequest): QuestionnaireInterviewDetailDto =
        api.updateInterview(id, body)

    /**
     * Completion matrix (artisans x sections). Pass [artisanId] to scope it to one artisan, and
     * [workshopIds] to scope it to workshops — null or empty is every workshop, `none` is the records
     * linked to none. LAST and defaulted so no existing call site has to change.
     */
    suspend fun completionMatrix(
        artisanId: String? = null,
        workshopIds: List<String>? = null
    ): CompletionMatrixDto =
        api.completionMatrix(artisanId?.blankToNull(), workshopIds.toQueryCsv())

    /** Admin-only: set ([status] = COMPLETED/NEEDS_REVIEW/NEEDS_REDO) or clear ([status] = null) one cell. */
    suspend fun setCompletionCell(artisanId: String, sectionId: String, status: String?) =
        api.setCompletionCell(CompletionCellRequest(artisanId, sectionId, status))

    /**
     * One artisan's questionnaire gathered from EVERY interview they sat in.
     *
     * [workshopIds] reads the document as it stands FOR THOSE WORKSHOPS: the whole document comes back
     * either way, the scope only decides which sittings feed it. Null or empty is every workshop.
     *
     * The DTO is declared in `ui/ConsolidatedQuestionnaireScreen.kt` beside the screen that renders
     * it — see the import note at the top of [FieldRepositoryApi] for why it is not in ApiModels.kt.
     */
    suspend fun consolidatedQuestionnaire(
        artisanId: String,
        workshopIds: List<String>? = null
    ): ConsolidatedQuestionnaireDto =
        api.consolidatedQuestionnaire(artisanId, workshopIds.toQueryCsv())

    /**
     * Upload a captured/selected file as a single streamed object. The bytes are streamed straight
     * from the content Uri to object storage (S3 PUT handles up to 5 GB), so even large videos upload
     * whole — no client-side chunking and no re-muxing, which is both faster and keeps each capture a
     * single file. Long audio is chunked only on the server for transcription, where the per-chunk
     * transcripts are stitched back together, so the stored audio object stays whole too.
     */
    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        linkedRecordType: String?,
        linkedRecordId: String?,
        caption: String?,
        location: LocationRequest?,
        titleHint: String? = null,
        batchIndex: Int = 1,
        processingRequests: List<String>? = null,
        stageStep: Int? = null,
        customSegment: String? = null,
        overrideBaseName: String? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): MediaFileDto {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "field-media-${System.currentTimeMillis()}"
        val mediaType = inferMediaType(mimeType)
        return uploadResolved(
            context = context,
            uri = uri,
            mimeType = mimeType,
            mediaType = mediaType,
            originalName = originalName,
            linkedRecordType = linkedRecordType,
            linkedRecordId = linkedRecordId,
            caption = caption,
            location = location,
            titleHint = titleHint,
            batchIndex = batchIndex,
            processingRequests = processingRequests,
            stageStep = stageStep,
            customSegment = customSegment,
            overrideBaseName = overrideBaseName,
            onProgress = onProgress
        )
    }

    /** Single-object upload (no splitting). Streams straight from the Uri so the heap never holds the file. */
    private suspend fun uploadResolved(
        context: Context,
        uri: Uri,
        mimeType: String,
        mediaType: String,
        originalName: String,
        linkedRecordType: String?,
        linkedRecordId: String?,
        caption: String?,
        location: LocationRequest?,
        titleHint: String?,
        batchIndex: Int,
        processingRequests: List<String>?,
        stageStep: Int?,
        customSegment: String?,
        overrideBaseName: String? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): MediaFileDto {
        val resolvedProcessing = processingRequests
            ?: if (mediaType == "AUDIO") listOf("TRANSCRIPTION") else emptyList()
        val filename = mediaFilename(
            recordType = linkedRecordType,
            recordName = titleHint,
            mediaType = mediaType,
            index = batchIndex,
            stageStep = stageStep,
            customSegment = customSegment,
            caption = caption,
            overrideBaseName = overrideBaseName,
            originalName = originalName
        )
        // Stream the file straight from the content Uri to object storage — never load it fully into
        // memory — so even multi-hundred-MB videos upload without OOM. The size comes from metadata;
        // if that is unavailable we spool to a temp cache file on disk to obtain an exact length.
        val source = withContext(Dispatchers.IO) { resolveUploadSource(context, uri) }
        try {
            val target = uploadBytesToS3(
                context = context,
                filename = filename,
                mimeType = mimeType,
                mediaType = mediaType,
                source = source,
                linkedRecordType = linkedRecordType,
                linkedRecordId = linkedRecordId,
                onProgress = onProgress
            )
            val media = completeUpload(
                MediaCompleteRequest(
                    originalFilename = filename,
                    mediaType = mediaType,
                    mimeType = mimeType,
                    sizeBytes = source.size,
                    objectKey = target.objectKey,
                    bucket = target.bucket,
                    url = target.publicUrl,
                    caption = caption.blankToNull(),
                    linkedRecordType = linkedRecordType.blankToNull(),
                    linkedRecordId = linkedRecordId.blankToNull(),
                    recordedAt = Instant.now().toString(),
                    location = location,
                    processingRequests = resolvedProcessing
                ),
                target.checksum
            )
            StagedJournal.drop(target.objectKey)
            return media
        } finally {
            source.cleanup()
        }
    }

    /**
     * `/media/complete`, carrying the SHA-256 of the bytes that actually went up so a silently
     * corrupted transfer is detectable later. [MediaCompleteRequest] has no `checksum` field, so the
     * key is added to the encoded body — derived from the canonical request rather than through a
     * parallel data class, so a field added to it is still sent here.
     */
    private suspend fun completeUpload(body: MediaCompleteRequest, checksum: String?): MediaFileDto {
        if (checksum == null) return api.completeMedia(body)
        val encoded = completeJson.encodeToJsonElement(MediaCompleteRequest.serializer(), body).jsonObject
        return api.completeMediaChecksummed(JsonObject(encoded + ("checksum" to JsonPrimitive(checksum))))
    }

    /**
     * Eager pre-upload: push the bytes to object storage immediately on capture using a provisional
     * key, so the slow network transfer overlaps the time the user spends filling the form. The
     * human-readable, nomenclature-correct filename is applied later in [completeStaged].
     */
    suspend fun preuploadObject(
        context: Context,
        uri: Uri,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): StagedMedia {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "field-media-${System.currentTimeMillis()}"
        val extension = originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val mediaType = inferMediaType(mimeType)
        val source = withContext(Dispatchers.IO) { resolveUploadSource(context, uri) }
        try {
            val provisional = "staged-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}" +
                (extension?.let { ".$it" } ?: "")
            val target = uploadBytesToS3(
                context = context,
                filename = provisional,
                mimeType = mimeType,
                mediaType = mediaType,
                source = source,
                linkedRecordType = null,
                linkedRecordId = null,
                onProgress = onProgress
            )
            // The hash is computed by the transfer and consumed by a save that may be many minutes
            // away, so it rides in the journal — the one record of this object that outlives both.
            StagedJournal.record(context, target.objectKey, target.checksum)
            return StagedMedia(
                objectKey = target.objectKey,
                bucket = target.bucket,
                publicUrl = target.publicUrl,
                mimeType = mimeType,
                mediaType = mediaType,
                sizeBytes = source.size,
                extension = extension
            )
        } finally {
            source.cleanup()
        }
    }

    /** Where an uploaded object ended up: its key, bucket, public URL, and the hash of what went up. */
    private data class UploadTarget(
        val objectKey: String,
        val bucket: String,
        val publicUrl: String?,
        val checksum: String?
    )

    /**
     * Push a resolved source to object storage and return its location. Files at/under
     * [MULTIPART_THRESHOLD] go up as one streamed PUT (fast, simple). Larger files use an S3 multipart
     * upload: the bytes are chunked for the transfer (resilient, resumable per part, and past the 5 GB
     * single-PUT ceiling), then S3 stitches the parts into a single object on complete — so the stored
     * file is still whole. Best of both worlds.
     */
    private suspend fun uploadBytesToS3(
        context: Context,
        filename: String,
        mimeType: String,
        mediaType: String,
        source: UploadSource,
        linkedRecordType: String?,
        linkedRecordId: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): UploadTarget {
        if (source.size <= MULTIPART_THRESHOLD) {
            val presign = api.presignMedia(
                MediaPresignRequest(
                    filename = filename,
                    mimeType = mimeType,
                    mediaType = mediaType,
                    sizeBytes = source.size,
                    linkedRecordType = linkedRecordType.blankToNull(),
                    linkedRecordId = linkedRecordId.blankToNull()
                )
            )
            // Journalled before the first byte moves: from here until /media/complete claims the key,
            // this line on disk is the only thing that would know the bucket holds an unreferenced
            // object if the process were killed right now.
            StagedJournal.record(context, presign.objectKey)
            val digest = ContentDigest()
            withContext(Dispatchers.IO) {
                putToStorage(presign.uploadUrl, presign.headers, source.size, mimeType, source.open, onProgress, digest)
            }
            return UploadTarget(presign.objectKey, presign.bucket, presign.publicUrl, digest.hex())
        }
        return uploadMultipart(context, filename, mimeType, mediaType, source, linkedRecordType, linkedRecordId, onProgress)
    }

    /** S3 multipart upload for a large file: chunk → upload parts → S3 stitches into one object. */
    private suspend fun uploadMultipart(
        context: Context,
        filename: String,
        mimeType: String,
        mediaType: String,
        source: UploadSource,
        linkedRecordType: String?,
        linkedRecordId: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): UploadTarget {
        val create = api.createMultipart(
            MultipartCreateRequest(
                filename = filename,
                mimeType = mimeType,
                mediaType = mediaType,
                sizeBytes = source.size,
                linkedRecordType = linkedRecordType.blankToNull(),
                linkedRecordId = linkedRecordId.blankToNull()
            )
        )
        // The uploadId goes to disk WITH the key, because for a multipart the key alone is useless:
        // until the parts are stitched there is no object at it, only uploaded parts, and the one
        // call that reclaims those needs the uploadId to name them. Recording the key alone left a
        // large video killed mid-transfer — the most likely transfer to be killed — costing storage
        // that no sweep could ever find its way back to.
        StagedJournal.record(context, create.objectKey, uploadId = create.uploadId)
        try {
            val partUrls = api.presignMultipartParts(
                MultipartPresignPartsRequest(
                    objectKey = create.objectKey,
                    uploadId = create.uploadId,
                    partNumbers = (1..create.partCount).toList()
                )
            ).urls
            val completed = ArrayList<CompletedPart>(create.partCount)
            val digest = ContentDigest()
            withContext(Dispatchers.IO) {
                source.open().use { input ->
                    var sentTotal = 0L
                    for (partNumber in 1..create.partCount) {
                        val thisSize = minOf(create.partSize, source.size - (partNumber - 1).toLong() * create.partSize)
                        val bytes = readExactly(input, thisSize.toInt())
                        // Hashed here rather than in the request body: parts are read in order (so the
                        // hash is of the whole file, as S3 will stitch it) and a part retry re-sends
                        // bytes already counted.
                        digest.update(bytes, bytes.size)
                        val url = partUrls[partNumber.toString()]
                            ?: throw IllegalStateException("Missing presigned URL for part $partNumber")
                        val base = sentTotal
                        val etag = putPart(
                            url = url,
                            bytes = bytes,
                            onProgress = { sent -> onProgress?.invoke(base + sent, source.size) },
                            repesign = {
                                api.presignMultipartParts(
                                    MultipartPresignPartsRequest(
                                        objectKey = create.objectKey,
                                        uploadId = create.uploadId,
                                        partNumbers = listOf(partNumber)
                                    )
                                ).urls[partNumber.toString()]
                            }
                        )
                        completed.add(CompletedPart(partNumber = partNumber, etag = etag))
                        sentTotal += bytes.size.toLong()
                    }
                }
            }
            val done = api.completeMultipart(
                MultipartCompleteRequest(
                    objectKey = create.objectKey,
                    uploadId = create.uploadId,
                    parts = completed
                )
            )
            return UploadTarget(done.objectKey, done.bucket, done.publicUrl, digest.hex())
        } catch (t: Throwable) {
            // Clean up the half-done multipart upload so its parts don't linger in storage.
            //
            // NonCancellable because the commonest reason to be here is now cancellation — a sibling
            // upload in the same batch failed and took this one down with it — and a suspend call
            // made from a cancelled coroutine gives up at its first suspension point, which would
            // mean the cancelled transfer's parts were never reclaimed. The journal is still the
            // backstop if even this cannot reach the server.
            withContext(NonCancellable) {
                runCatching {
                    api.abortMultipart(MultipartAbortRequest(create.objectKey, create.uploadId))
                    // The abort discarded the parts, so there is nothing left for a sweep to reclaim.
                    StagedJournal.drop(create.objectKey)
                }
            }
            throw t
        }
    }

    /** Read exactly [size] bytes from [input] (handles short reads); returns fewer only at EOF. */
    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == size) buffer else buffer.copyOf(offset)
    }

    /**
     * Upload one multipart part (with retry) and return its S3 ETag for the complete call.
     *
     * A part URL is signed for an hour (`s3.py:191`) and every part of a 400 MB video is signed at
     * once, up front — on a field connection the last parts can easily still be waiting when their
     * signature runs out, and S3 rejects an expired one with 403. [repesign] gets that single part a
     * fresh URL so the transfer continues, instead of the whole upload dying on the one thing that is
     * certain to fix itself. The refresh is allowed once and does not spend a retry attempt: it is not
     * a failure, and the bytes still have to go somewhere.
     */
    private suspend fun putPart(
        url: String,
        bytes: ByteArray,
        onProgress: ((sent: Long) -> Unit)?,
        repesign: suspend () -> String?
    ): String {
        val maxAttempts = 3
        var target = url
        var refreshed = false
        var failures = 0
        var lastError: Exception? = null
        while (true) {
            // Same reason as [putToStorage]: a cancelled call arrives as an IOException, and this
            // loop would otherwise re-send the part instead of standing down.
            currentCoroutineContext().ensureActive()
            var expired = false
            try {
                // Content-Type is intentionally unset: the part presign does not sign it, so sending one
                // would not match. A fresh ByteArrayInputStream per attempt lets a retry re-send cleanly.
                val body = StreamingRequestBody(
                    bytes.size.toLong(),
                    null,
                    { java.io.ByteArrayInputStream(bytes) },
                    onProgress?.let { cb -> { sent, _ -> cb(sent) } }
                )
                executeCancellable(storageClient.newCall(Request.Builder().url(target).put(body).build())).use { response ->
                    if (response.isSuccessful) {
                        return response.header("ETag")
                            ?: throw IllegalStateException("S3 returned no ETag for the uploaded part")
                    }
                    if (response.code == 403 && !refreshed) expired = true
                    else if (response.code < 500) throw IllegalStateException("Part upload failed: HTTP ${response.code}")
                    lastError = IllegalStateException("Part upload failed: HTTP ${response.code}")
                }
            } catch (e: IOException) {
                lastError = e
            }
            if (expired) {
                refreshed = true
                target = repesign() ?: throw (lastError ?: IllegalStateException("Part upload failed: HTTP 403"))
                continue
            }
            failures++
            if (failures >= maxAttempts) break
            delay(800L * failures)
        }
        throw lastError ?: IllegalStateException("Part upload failed")
    }

    /** Attach an already-uploaded staged object to a saved record, applying the final filename. */
    suspend fun completeStaged(
        staged: StagedMedia,
        linkedRecordType: String?,
        linkedRecordId: String?,
        recordName: String?,
        caption: String?,
        location: LocationRequest?,
        batchIndex: Int = 1,
        stageStep: Int? = null,
        customSegment: String? = null,
        processingRequests: List<String>? = null,
        overrideBaseName: String? = null
    ): MediaFileDto {
        val filename = mediaFilename(
            recordType = linkedRecordType,
            recordName = recordName,
            mediaType = staged.mediaType,
            index = batchIndex,
            stageStep = stageStep,
            customSegment = customSegment,
            caption = caption,
            overrideBaseName = overrideBaseName,
            originalName = "media" + (staged.extension?.let { ".$it" } ?: "")
        )
        val resolvedProcessing = processingRequests
            ?: if (staged.mediaType == "AUDIO") listOf("TRANSCRIPTION") else emptyList()
        val media = completeUpload(
            MediaCompleteRequest(
                originalFilename = filename,
                mediaType = staged.mediaType,
                mimeType = staged.mimeType,
                sizeBytes = staged.sizeBytes,
                objectKey = staged.objectKey,
                bucket = staged.bucket,
                url = staged.publicUrl,
                caption = caption.blankToNull(),
                linkedRecordType = linkedRecordType.blankToNull(),
                linkedRecordId = linkedRecordId.blankToNull(),
                recordedAt = Instant.now().toString(),
                location = location,
                processingRequests = resolvedProcessing
            ),
            StagedJournal.checksumFor(staged.objectKey)
        )
        StagedJournal.drop(staged.objectKey)
        return media
    }

    /** Delete a staged object that was cancelled before save. */
    suspend fun deleteStaged(objectKey: String) {
        // Journalled until the server confirms: a delete that never landed still leaves bytes behind,
        // and the next launch's sweep is what finishes the job.
        api.deleteMediaObject(objectKey)
        StagedJournal.drop(objectKey)
    }

    /**
     * Delete every object a previous run of the app left staged and never attached to a record — the
     * bytes of a capture that was mid-upload when the process died. Run once on app start (see
     * [syncOutbox]), never per upload: an object staged by THIS process belongs to a form that may
     * still be open.
     *
     * Nothing is destructive by accident. `/media/object` and `/media/multipart/abort` are both
     * scoped to the caller's own `media/<user_id>/` prefix, and the delete 409s on anything a record
     * already points at, so the worst a bad entry can do is come back refused — which counts as
     * settled, since the object clearly found an owner. Only an unsettled key (no signal, gateway
     * failure) stays for the next launch.
     */
    suspend fun sweepStagedObjects(context: Context): Int {
        if (!hasToken() || !ConnectivityObserver.isOnline(context)) return 0
        return StagedJournal.sweep(context) { objectKey, uploadId ->
            // Abort BEFORE deleting, and only for a key that was journalled as a multipart. An
            // interrupted multipart has no object to delete — deleting the key removes nothing and
            // the uploaded parts stay billed for ever — so this is the only call that reclaims them.
            val abortSettled = uploadId == null || try {
                api.abortMultipart(MultipartAbortRequest(objectKey, uploadId))
                true
            } catch (e: HttpException) {
                // The server answered, so there is nothing more to do about this uploadId here: a 500
                // is what S3's NoSuchUpload becomes once the upload DID complete (leaving the finished
                // object, which the delete below handles), 403/404 mean it was never ours to abort.
                true
            } catch (e: IOException) {
                false
            }
            val deleteSettled = try {
                api.deleteMediaObject(objectKey)
                true
            } catch (e: HttpException) {
                // 409 attached, 403 another account's key (this device changed hands), 404 already gone.
                e.code() == 409 || e.code() == 403 || e.code() == 404
            } catch (e: IOException) {
                false
            }
            // Journalled until BOTH halves are settled: forgetting the key after a successful delete
            // whose abort never landed would strand the parts exactly as before.
            abortSettled && deleteSettled
        }
    }

    // --- Offline outbox: make entries with no connection, sync them when it returns ---

    /** True when the device currently has validated internet. */
    fun isOnline(context: Context): Boolean = ConnectivityObserver.isOnline(context)

    /** How many records are waiting in the local outbox to be uploaded. */
    suspend fun pendingUploads(context: Context): Int = OfflineOutbox.count(context)

    /**
     * Save a new record to the local outbox (no network). Copies the attached media into app storage so
     * nothing is lost, then enqueues the serialized create request. [payloadJson] is the record's
     * create request serialized with [offlineJson]. Synced later by [syncOutbox].
     */
    suspend fun queueOffline(
        context: Context,
        type: String,
        payloadJson: String,
        label: String,
        mediaUris: List<Uri>,
        recordName: String?,
        caption: String?
    ) = queueOfflineEntry(
        context, type, payloadJson, label,
        mediaUris.mapIndexed { index, uri ->
            OfflineMediaSpec(uri = uri, caption = caption, recordName = recordName, batchIndex = index + 1)
        }
    )

    /** Queue an offline entry with a fully specified media list (e.g. attachments + stage captures). */
    suspend fun queueOfflineEntry(
        context: Context,
        type: String,
        payloadJson: String,
        label: String,
        items: List<OfflineMediaSpec>
    ) = withContext(Dispatchers.IO) {
        val media = items.map { spec ->
            OfflineOutbox.stageMedia(
                context = context,
                uri = spec.uri,
                caption = spec.caption,
                recordName = spec.recordName,
                customSegment = spec.customSegment,
                overrideBaseName = spec.overrideBaseName,
                batchIndex = spec.batchIndex,
                processing = spec.processing,
                stageStep = spec.stageStep,
                linkedType = spec.linkedType,
                stepIndex = spec.stepIndex
            )
        }
        OfflineOutbox.enqueue(
            context,
            PendingEntry(
                id = java.util.UUID.randomUUID().toString(),
                type = type,
                payloadJson = payloadJson,
                label = label,
                media = media,
                createdAt = Instant.now().toString()
            )
        )
    }

    /**
     * Replay every queued offline entry: create the record, then upload its copied media, then drop
     * the local copy. Returns the number of entries fully synced. Safe to call often.
     *
     * TWO THINGS THIS NO LONGER DOES, both of which cost field data.
     *
     * IT NEVER RE-CREATES A RECORD THE SERVER ALREADY HAS. "Create, then upload the media" is two
     * steps and only the first is cheap to repeat — repeating it makes a SECOND record. The server id
     * is written back to the entry the moment the create lands, and every uploaded file is ticked off
     * as it goes, so a pass interrupted during the media resumes at the media. Before this, an entry
     * whose upload failed re-created its record on the next pass, and the pass after that, once per
     * sync for as long as the signal stayed bad — and a bad signal is the entire reason the entry is
     * in the outbox.
     *
     * AND IT NEVER STOPS THE WHOLE QUEUE AT A RECORD THE SERVER WILL NOT TAKE. A 4xx is the server's
     * final answer: the payload is wrong, or this user may not do that, and the next pass sends the
     * identical bytes to the identical rejection. It is recorded on the entry, said out loud, and
     * stepped over. A 5xx, a timeout or a dead connection is the opposite — everything behind it will
     * fail the same way — so the pass stops there and the queue keeps its order. This is the triage
     * `frontend/lib/offline.ts` already makes; without it, one unacceptable record silently blocked
     * every record queued behind it, for ever.
     */
    suspend fun syncOutbox(context: Context): Int {
        // App-start housekeeping, not per-upload work. This is the app's existing "signed in, or the
        // network just came back" hook and the only one that carries a Context, so the first pass of
        // the process also reclaims objects an earlier run left staged. Detached, so a slow sweep
        // never delays the queued records — those are the data the researcher is waiting on.
        if (sweptStagedObjects.compareAndSet(false, true)) {
            AppScope.io.launch { runCatching { sweepStagedObjects(context) } }
        }
        return syncMutex.withLock {
            val queue = OfflineOutbox.all(context)
            // Read first, then reported, and reported before the connection is even checked: a queue
            // file that would not parse is the one problem the researcher cannot see for themselves,
            // because its only symptom is a count that quietly drops.
            OfflineOutbox.takeAlert()?.let { notifyUser(context, it) }
            if (!ConnectivityObserver.isOnline(context)) return@withLock 0
            var synced = 0
            for (queued in queue) {
                // Already triaged as permanent: this one is waiting on a person, not on the network.
                if (queued.failure != null) continue
                when (val outcome = replayEntry(context, queued)) {
                    ReplayOutcome.Synced -> {
                        OfflineOutbox.remove(context, queued)
                        synced++
                    }
                    is ReplayOutcome.Rejected -> {
                        OfflineOutbox.markFailure(context, queued.id, outcome.reason)
                        notifyUser(context, "\"${queued.label}\" could not be uploaded. ${outcome.reason}")
                    }
                    // Transient: stop here so the queue keeps its order and nothing is marked failed
                    // for a reason that is really "the signal went away again".
                    ReplayOutcome.Retry -> return@withLock synced
                }
            }
            synced
        }
    }

    /** What became of one replayed entry. */
    private sealed interface ReplayOutcome {
        /** Record and every attachment are on the server; the local copy can go. */
        data object Synced : ReplayOutcome

        /** Nothing more will happen until the network comes back. Stop the pass; keep the order. */
        data object Retry : ReplayOutcome

        /** The server's final answer. Keep the entry AND its files; tell the researcher. */
        data class Rejected(val reason: String) : ReplayOutcome
    }

    /**
     * Replay one entry, writing every step back to disk as it lands.
     *
     * Where those writes happen is the whole point. The created id goes down BEFORE the first byte of
     * media moves, and each finished file is ticked off as soon as it is up — so whatever kills this
     * pass, the next one starts from what has actually happened rather than from the top.
     */
    private suspend fun replayEntry(context: Context, queued: PendingEntry): ReplayOutcome {
        var entry = queued
        if (entry.createdId == null) {
            val created = try {
                createFromEntry(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return if (isTransient(e)) ReplayOutcome.Retry
                else ReplayOutcome.Rejected(e.apiErrorMessage("The server rejected this record."))
            }
            entry = entry.copy(createdId = created.id, createdStepIds = created.stepIds)
            OfflineOutbox.update(context, entry)
        }

        val alreadyUp = entry.uploadedMedia.toMutableSet()
        val refused = mutableListOf<String>()
        val remaining = entry.media.withIndex().filterNot { (index, _) -> index in alreadyUp }
        // Each worker posts its own result here the instant its file lands, rather than the batch
        // reporting as a unit — a batch that is torn down because one of its three files failed has
        // usually finished one of the other two, and a finished file that is not ticked off gets
        // uploaded again next pass, leaving the record holding the same photograph twice.
        val landed = ConcurrentLinkedQueue<FileOutcome>()

        /** Fold everything the workers have finished into the entry and write it to disk. */
        suspend fun persistProgress() {
            var changed = false
            while (true) {
                when (val outcome = landed.poll() ?: break) {
                    is FileOutcome.Uploaded -> alreadyUp.add(outcome.index)
                    is FileOutcome.Refused -> refused.add(outcome.reason)
                }
                changed = true
            }
            if (!changed) return
            entry = entry.copy(uploadedMedia = alreadyUp.sorted())
            OfflineOutbox.update(context, entry)
        }

        var stopped = false
        try {
            for (batch in remaining.chunked(UPLOAD_CONCURRENCY)) {
                try {
                    uploadBatch(context, entry, batch, landed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Only a transient failure escapes [uploadBatch]. The record and every file that
                    // did land are on disk, so the next pass resumes at what is left.
                    stopped = true
                    break
                }
                persistProgress()
            }
        } finally {
            // NonCancellable, because a file that IS on the server has to be ticked off even while
            // this pass is being torn down. The alternative is the next pass sending it again.
            withContext(NonCancellable) { persistProgress() }
        }
        if (stopped) return ReplayOutcome.Retry

        if (refused.isNotEmpty()) {
            // The record IS saved, so the entry must never be replayed — but its files are still only
            // here, so it must not be deleted either. Kept, with the reason, exactly as the web does.
            return ReplayOutcome.Rejected(
                "It was saved, but ${refused.size} file(s) were refused: ${refused.distinct().joinToString(" ")} " +
                    "Re-attach them on the record."
            )
        }
        return ReplayOutcome.Synced
    }

    /** One file's fate within a replayed batch. */
    private sealed interface FileOutcome {
        data class Uploaded(val index: Int) : FileOutcome
        data class Refused(val index: Int, val reason: String) : FileOutcome
    }

    /**
     * Upload up to [UPLOAD_CONCURRENCY] of a record's attachments at once.
     *
     * The web has done this since the eager-upload work and Android did not: an interview with twelve
     * clips uploaded them strictly in series, so the transfer cost the sum of twelve round trips even
     * though a field connection is usually latency-bound rather than bandwidth-bound. Three at a time
     * is the cap the web uses, and for the same reason: a 2G-ish uplink shared by ten parallel PUTs
     * makes every one of them time out instead of making any of them finish.
     *
     * WHY THE TWO KINDS OF FAILURE LEAVE BY DIFFERENT DOORS. A transient one is THROWN, so
     * `coroutineScope` cancels the siblings on the spot: they share the connection that has just been
     * shown to be gone, and now that the transfers are genuinely cancellable that cancellation stops
     * real sockets instead of being a note in a log. A refusal is POSTED to [landed] instead, because
     * a file the server will not take says nothing about the other two — cancelling them would strand
     * attachments that were seconds from succeeding.
     *
     * Every result goes to [landed] as it happens rather than being returned when the batch is over,
     * so that a torn-down batch still tells the caller which of its files did land.
     */
    private suspend fun uploadBatch(
        context: Context,
        entry: PendingEntry,
        batch: List<IndexedValue<PendingMedia>>,
        landed: ConcurrentLinkedQueue<FileOutcome>
    ): Unit = coroutineScope {
        batch.forEach { (index, pm) ->
            launch(Dispatchers.IO) {
                val target = linkTargetFor(entry, pm)
                if (target == null) {
                    landed.add(
                        FileOutcome.Refused(
                            index,
                            "\"${pm.originalFilename}\" had nowhere to attach — the saved record has fewer " +
                                "process steps than were captured."
                        )
                    )
                    return@launch
                }
                try {
                    uploadLocalFile(context, pm, target.first, target.second)
                    landed.add(FileOutcome.Uploaded(index))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (isTransient(e)) throw e
                    landed.add(
                        FileOutcome.Refused(index, "\"${pm.originalFilename}\": ${e.apiErrorMessage("refused by the server.")}")
                    )
                }
            }
        }
    }

    /**
     * Where one queued file attaches once its record exists: the link type and the server id.
     *
     * Null when a process came back with fewer steps than were captured — the caller reports that
     * rather than attaching the capture to the wrong step or dropping it without a word.
     */
    private fun linkTargetFor(entry: PendingEntry, pm: PendingMedia): Pair<String, String>? {
        val recordId = entry.createdId ?: return null
        val stepIndex = pm.stepIndex
        if (entry.type == "process" && stepIndex != null) {
            val stepId = entry.createdStepIds.getOrNull(stepIndex) ?: return null
            return "processstep" to stepId
        }
        // Media attaches to the created record, or to an overridden link type (e.g. a clip).
        return (pm.linkedType ?: entry.type) to recordId
    }

    /**
     * Will trying this again help?
     *
     * The web outbox's `isTransient` (`frontend/lib/offline.ts`), in Kotlin, plus the two failures a
     * phone has that a browser does not. Being wrong in either direction is expensive: call a
     * permanent failure transient and one bad record blocks the queue for ever; call a transient one
     * permanent and a day's work is parked for a human because a tunnel took the signal away.
     */
    private fun isTransient(error: Throwable): Boolean = when (error) {
        is HttpException -> when (val code = error.code()) {
            // The credential expired, not the record. Every entry would fail this way and re-signing
            // in fixes all of them at once, so this is the one 4xx that must not condemn an entry.
            401 -> true
            408, 429 -> true
            else -> code >= 500
        }
        // No answer at all: no signal, DNS, a socket dropped mid-transfer, a gateway timeout.
        is IOException -> true
        // The queued payload itself will not parse. The next pass reads the same bytes off the same
        // disk and fails identically, so this is as permanent as a 422.
        is SerializationException -> false
        // Anything else (a presign that came back malformed, an unexpected state) is treated as worth
        // another try: the cost of retrying is a delay, and the cost of not retrying is a lost record.
        else -> true
    }

    /**
     * Say something to the researcher from a sync that has no screen.
     *
     * A repository raising UI is not where this belongs, and it is here because the alternative is
     * silence: `syncOutbox` runs from a timer and a network callback, and the only thing the shell
     * shows is a count of queued entries under the words "uploading when you're online" — which is a
     * lie for an entry the server has refused for good, and which stays a lie for ever. An entry that
     * will never send has to say so at the moment it stops trying. The durable half is
     * [PendingEntry.failure], readable through [outboxFailures] by whatever screen shows it next.
     */
    private suspend fun notifyUser(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Queued entries the server refused, each carrying the reason it will never be sent. */
    suspend fun outboxFailures(context: Context): List<PendingEntry> = OfflineOutbox.failed(context)

    /**
     * An artisan queued by a build that predates the identity fields carries no Pehchan answer at all,
     * and the API refuses a create that claims a card without giving its number. Replaying it as "no
     * card recorded" gets the field capture safely onto the server, where the researcher can correct
     * the answer on the record itself — rather than the entry being parked as permanently rejected
     * for a question the build that captured it never asked.
     */
    private fun withIdentityAnswer(body: ArtisanCreateRequest): ArtisanCreateRequest =
        if (body.pehchanCardAvailable != null) body
        else body.copy(pehchanCardAvailable = body.pehchanCardNumber != null)

    /** What a replayed create produced: the record's server id, plus a process's step ids in order. */
    private data class CreatedRecord(val id: String, val stepIds: List<String> = emptyList())

    private suspend fun createFromEntry(entry: PendingEntry): CreatedRecord = when (entry.type) {
        "artisan" -> CreatedRecord(
            api.createArtisan(
                withIdentityAnswer(offlineJson.decodeFromString<ArtisanCreateRequest>(entry.payloadJson))
            ).id
        )
        "product" -> CreatedRecord(api.createProduct(offlineJson.decodeFromString<ProductCreateRequest>(entry.payloadJson)).id)
        "tool" -> CreatedRecord(api.createTool(offlineJson.decodeFromString<ToolCreateRequest>(entry.payloadJson)).id)
        "workshop" -> CreatedRecord(api.createWorkshop(offlineJson.decodeFromString<WorkshopCreateRequest>(entry.payloadJson)).id)
        "craft" -> CreatedRecord(api.createCraft(offlineJson.decodeFromString<CraftCreateRequest>(entry.payloadJson)).id)
        "questionnaire" -> CreatedRecord(
            api.createQuestionnaireInterview(
                offlineJson.decodeFromString<QuestionnaireInterviewCreateRequest>(entry.payloadJson)
            ).id
        )
        // Steps come back in submit order, so `stepIndex` on a queued file selects the matching one.
        "process" -> api.createProcess(offlineJson.decodeFromString<ProcessCreateRequest>(entry.payloadJson))
            .let { detail -> CreatedRecord(detail.id, detail.steps.map { it.id }) }
        else -> throw IllegalStateException("Unknown offline entry type: ${entry.type}")
    }

    private suspend fun uploadLocalFile(
        context: Context,
        pm: PendingMedia,
        linkedRecordType: String,
        linkedRecordId: String
    ) {
        val file = File(pm.localPath)
        if (!file.exists()) return
        val filename = mediaFilename(
            recordType = linkedRecordType,
            recordName = pm.recordName,
            mediaType = pm.mediaType,
            index = pm.batchIndex,
            stageStep = pm.stageStep,
            customSegment = pm.customSegment,
            caption = pm.caption,
            overrideBaseName = pm.overrideBaseName,
            originalName = pm.originalFilename
        )
        val source = UploadSource(size = file.length(), open = { FileInputStream(file) }, cleanup = {})
        val target = uploadBytesToS3(
            context = context,
            filename = filename,
            mimeType = pm.mimeType,
            mediaType = pm.mediaType,
            source = source,
            linkedRecordType = linkedRecordType,
            linkedRecordId = linkedRecordId,
            onProgress = null
        )
        val resolvedProcessing = pm.processing ?: if (pm.mediaType == "AUDIO") listOf("TRANSCRIPTION") else emptyList()
        completeUpload(
            MediaCompleteRequest(
                originalFilename = filename,
                mediaType = pm.mediaType,
                mimeType = pm.mimeType,
                sizeBytes = file.length(),
                objectKey = target.objectKey,
                bucket = target.bucket,
                url = target.publicUrl,
                caption = pm.caption.blankToNull(),
                linkedRecordType = linkedRecordType,
                linkedRecordId = linkedRecordId,
                recordedAt = Instant.now().toString(),
                processingRequests = resolvedProcessing
            ),
            target.checksum
        )
        StagedJournal.drop(target.objectKey)
    }

    /**
     * Analyse a grid-sheet photo for one dimension (length/breadth/height) and return the estimated
     * inches, or null if the model couldn't read it. A grid photo is small, so reading it into memory
     * is fine. Used by the "Document using grid" capture to auto-fill the measurement field.
     */
    suspend fun analyzeMeasurement(context: Context, uri: Uri, dimension: String): Double? {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to open the captured image")
        }
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file",
            "grid-${dimension}.jpg",
            bytes.toRequestBody(mimeType.toMediaType())
        )
        val response = api.analyzeMeasurement(part, dimension)
        return response.analysis?.valueInches
    }

    /**
     * Analyse a single grid-sheet photo for BOTH length and breadth at once (the object's footprint
     * on the grid). Calls the measurement endpoint with no dimension, which returns the legacy
     * length+breadth pair. Returns (lengthInches, breadthInches); either may be null if unread.
     */
    suspend fun analyzeMeasurementLengthBreadth(context: Context, uri: Uri): Pair<Double?, Double?> {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to open the captured image")
        }
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file",
            "grid-length-breadth.jpg",
            bytes.toRequestBody(mimeType.toMediaType())
        )
        val response = api.analyzeMeasurement(part, null)
        val analysis = response.analysis
        return (analysis?.lengthInches) to (analysis?.breadthInches)
    }

    /**
     * Run an OkHttp call so that cancelling the coroutine actually stops the transfer.
     *
     * `execute()` blocks a thread nothing can interrupt. Cancel the coroutine around it and the
     * socket keeps pushing bytes until OkHttp's own call timeout — twelve minutes, on the field
     * connection that is already the scarce resource. `enqueue` gives the cancellation somewhere to
     * land, and `call.cancel()` closes the socket at once.
     */
    private suspend fun executeCancellable(call: Call): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // Cancelled while the answer was in flight: nobody downstream will reach the
                    // `use` that closes this, so close it here rather than leak the connection.
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

    /**
     * PUT bytes to object storage with bounded retries and byte-level progress. Transient failures
     * (network drop, or a 5xx from S3 under concurrent load) are retried with linear backoff so a
     * single hiccup never loses an upload; a 4xx (bad signature etc.) fails fast. This is what makes
     * many files — and many researchers uploading at once — resilient.
     *
     * SUSPENDING AND CANCELLABLE, deliberately. This was a blocking function that slept between
     * attempts with `Thread.sleep`, so a transfer could not be stopped at all: neither the socket nor
     * the backoff could hear a cancellation. That is what made a parallel upload batch unable to give
     * up — when one file failed, its two siblings were cancelled on paper and went on transferring
     * for real, over the connection that had just been shown to be broken.
     */
    private suspend fun putToStorage(
        uploadUrl: String,
        headers: Map<String, String>,
        contentLength: Long,
        mimeType: String,
        openStream: () -> InputStream,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
        digest: ContentDigest? = null
    ) {
        val maxAttempts = 3
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            // A cancelled call surfaces as a plain IOException, which the catch below would retry.
            // Checked here so a cancellation ends the loop as a cancellation rather than as a
            // transport failure the caller would then queue for another try.
            currentCoroutineContext().ensureActive()
            try {
                // A fresh stream per attempt so a retry re-reads from the start.
                val body = StreamingRequestBody(contentLength, mimeType.toMediaType(), openStream, onProgress, digest)
                val builder = Request.Builder().url(uploadUrl).put(body)
                headers.forEach { (name, value) -> builder.header(name, value) }
                executeCancellable(storageClient.newCall(builder.build())).use { response ->
                    if (response.isSuccessful) return
                    // Client errors (4xx) won't fix themselves — fail immediately.
                    if (response.code < 500) {
                        throw IllegalStateException("Object storage upload failed: HTTP ${response.code}")
                    }
                    lastError = IllegalStateException("Object storage upload failed: HTTP ${response.code}")
                }
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < maxAttempts) delay(800L * attempt)
        }
        throw lastError ?: IllegalStateException("Object storage upload failed")
    }

    /** A re-openable upload source: exact byte size, a fresh stream per attempt, and cleanup. */
    private class UploadSource(val size: Long, val open: () -> InputStream, val cleanup: () -> Unit)

    /** Content-provider SIZE column, or 0 if unknown. */
    private fun queryContentSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /**
     * Build an [UploadSource] that streams from disk, not memory. When the provider exposes a SIZE we
     * stream straight from the content Uri (re-opened per retry). When it doesn't, we spool the bytes
     * to a temp cache file (streamed copy, never a giant in-memory array) to learn the exact length,
     * then stream from that file. Either way the heap never holds the whole video.
     */
    private fun resolveUploadSource(context: Context, uri: Uri): UploadSource {
        val size = queryContentSize(context, uri)
        if (size > 0L) {
            return UploadSource(
                size = size,
                open = {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Unable to open selected media")
                },
                cleanup = {}
            )
        }
        val temp = File.createTempFile("upload-", ".bin", context.cacheDir)
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { out -> input.copyTo(out, 64 * 1024) }
            } ?: throw IllegalStateException("Unable to open selected media")
        }.onFailure { temp.delete(); throw it }
        return UploadSource(
            size = temp.length(),
            open = { FileInputStream(temp) },
            cleanup = { runCatching { temp.delete() } }
        )
    }

    /** OkHttp body that streams an InputStream in 64 KB chunks, reporting cumulative bytes written. */
    private class StreamingRequestBody(
        private val length: Long,
        private val contentType: MediaType?,
        private val openStream: () -> InputStream,
        private val onProgress: ((sent: Long, total: Long) -> Unit)?,
        private val digest: ContentDigest? = null
    ) : RequestBody() {
        override fun contentType(): MediaType? = contentType
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            // Every write of this body re-sends the whole file from the start (a retry, or OkHttp
            // re-issuing the request), so the hash has to start over with it.
            digest?.reset()
            openStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    digest?.update(buffer, read)
                    sent += read
                    onProgress?.invoke(sent, length)
                }
            }
        }
    }

    /**
     * SHA-256 of a file's content, fed from the bytes on their way to the socket so hashing a 400 MB
     * video costs no second read of it. Sent on `/media/complete` as `sha256:<hex>` (the same shape
     * the web sends) so a transfer that silently corrupted the file is detectable afterwards, and so
     * identical bytes are recognisable. Nothing verifies it at upload time.
     */
    private class ContentDigest {
        private val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun reset() = digest.reset()
        fun update(bytes: ByteArray, length: Int) = digest.update(bytes, 0, length)
        /** Terminal — reading the hash resets the digest, so call this once, after the last byte. */
        fun hex(): String = "sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
        }
        return uri.lastPathSegment
    }

    private fun inferMediaType(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "IMAGE"
        mimeType.startsWith("video/") -> "VIDEO"
        mimeType.startsWith("audio/") -> "AUDIO"
        mimeType == "application/pdf" -> "PDF"
        else -> "DOCUMENT"
    }

}

private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Comma-join a list-shaped query parameter, the way every route in the shared filter vocabulary reads
 * one — or null when there is nothing to send.
 *
 * AN EMPTY LIST MUST BECOME NULL, not "". Absent means "every workshop / every bucket"; a blank
 * string is one blank id, which matches nothing, so the two differ by the entire result set. Blanks
 * and duplicates are dropped here rather than at the interface, which stays a plain description of
 * the wire (house rule), and order is preserved so the query string a screen sends is stable across
 * recompositions instead of depending on tick order.
 *
 * Case is left ALONE: workshop ids are case-sensitive cuids and the reserved sentinel `none` is
 * already lowercase. Callers that need a lowercased vocabulary (the `types` buckets) lowercase
 * before calling.
 */
private fun List<String>?.toQueryCsv(): String? =
    this?.mapNotNull { it.blankToNull() }?.distinct()?.takeIf { it.isNotEmpty() }?.joinToString(",")

// ---------------------------------------------------------------------------
// The name a captured file is uploaded under.
//
//     {RecordType}-{RecordName}-{Descriptor}-{ddMMyyyyHHmm}.{ext}
//
// It used to be `K_1_RASHPALSINGHJAMMUKASHMIRBAMBOOSECTIONKL_000137_010720261728.m4a`: every word
// run together, every part a code, and the whole thing legible only to the screen that wrote it. A
// researcher works from a zip extracted onto a laptop, where the folder that carried the meaning is
// gone, so the name has to answer on its own what kind of record this hangs off, which one, what
// the file is, and when it was taken.
//
// This is the capture-time half of backend/app/services/media_naming.py, which re-derives the same
// name from the row whenever the repository is browsed, exported or downloaded. The two have to
// agree: where they disagree a researcher sees one file under two names with nothing to tell them
// it is one file. Every rule below — the character deny list, the two length limits, the descriptor
// vocabulary, which part gets truncated — is that module's, in Kotlin.
// ---------------------------------------------------------------------------

/**
 * The two path separators plus the punctuation Windows reserves: the only characters a filesystem or
 * a zip genuinely cannot carry.
 *
 * The rule is a DENY list rather than an allow list, and that inversion is the point of this whole
 * change. The old `[^A-Za-z0-9]` allow list emptied every Devanagari name it touched, so a row of
 * artisans collapsed onto one indistinguishable filename — and in a repository whose subject is
 * Indian craft, the names are the data.
 */
private val NAME_UNSAFE_CHARS: Set<Int> = "<>:\"/\\|?*".map { it.code }.toSet()

/**
 * Cc control characters; Cf invisible format characters, which include the bidi overrides that
 * render a filename back to front; Cs lone surrogates, which no encoder will take.
 */
private val NAME_UNSAFE_CATEGORIES = setOf(
    Character.CONTROL.toInt(),
    Character.FORMAT.toInt(),
    Character.SURROGATE.toInt()
)

/**
 * …except these two, which are Cf but load-bearing: in Devanagari and the other Indic scripts they
 * select conjunct and half forms, so dropping them misspells the very names this scheme exists to
 * keep.
 */
private const val ZERO_WIDTH_NON_JOINER = '\u200C'.code
private const val ZERO_WIDTH_JOINER = '\u200D'.code

/**
 * Combining marks are not letters or digits to the JDK, but they are half of the syllable they sit
 * on, so a word splitter that treats "not alphanumeric" as a boundary shatters every Indic syllable.
 */
private val NAME_MARK_CATEGORIES = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt()
)

/** Numerals the JDK does not count as digits (Nl/No) but Python's `isalnum` does — kept so the two
 *  halves of the scheme break words at the same places. */
private val NAME_NUMBER_CATEGORIES = setOf(
    Character.LETTER_NUMBER.toInt(),
    Character.OTHER_NUMBER.toInt()
)

/** Windows refuses these device names in any case, with or without an extension. */
private val NAME_RESERVED: Set<String> = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    (1..9).forEach { add("COM$it"); add("LPT$it") }
}

/**
 * The whole leaf, in characters AND in bytes. A filesystem caps a name at ~255 BYTES while a slice
 * counts characters, and one Devanagari character costs three of them, so a name that passes the
 * character check can still be rejected on write. 200 bytes matches the backend's budget and leaves
 * room under the ceiling for the `-2` a duplicate name picks up on the way out of an export.
 */
private const val MAX_NAME_CHARS = 150
private const val MAX_NAME_BYTES = 200

/** Enough of a record name to identify it; the rest of the budget belongs to the descriptor. */
private const val MAX_RECORD_NAME_CHARS = 60

/** A step name is free text a researcher typed and runs to a sentence more often than not. */
private const val MAX_STEP_NAME_CHARS = 32

/** What each kind of file is called in a name. Plain words — never the old IMG/VID/AUD codes. */
private val NAME_KIND_WORD = mapOf(
    "IMAGE" to "Photo",
    "VIDEO" to "Video",
    "AUDIO" to "Audio-Note",
    "PDF" to "Document",
    "DOCUMENT" to "Document"
)

/**
 * `linkedRecordType` -> the word that opens the name.
 *
 * A questionnaire clip is deliberately absent. The record such a clip is really about is the artisan
 * being interviewed, and at capture time this layer holds only the interview's TITLE — free text
 * from a field labelled "Interview title", which is routinely "Rashpal Singh Jammu Kashmir Bamboo
 * Section KL" rather than anyone's name. Opening that with "Artisan-" would state something false,
 * so the head word is left out and the descriptor's own "Interview-…" says what kind of record it
 * is. The backend, which can see the interview's artisans, fills the head in when it re-derives.
 */
private val NAME_RECORD_TYPE_WORD = mapOf(
    "artisan" to "Artisan",
    "product" to "Product",
    "tool" to "Tool",
    "process" to "Process",
    "processstep" to "Process",
    "workshop" to "Workshop",
    "craft" to "Craft"
)

/** "Question audio: K1 What types of waste …" — section letter then question number. */
private val CAPTION_QUESTION = Regex("""^question\s+audio:\s*([A-Za-z]{1,3})\s*(\d+)\b""", RegexOption.IGNORE_CASE)

/** "Section audio: D RAW MATERIALS …" — a recording covering a whole section, answering no one question. */
private val CAPTION_SECTION = Regex("""^section\s+audio:\s*([A-Za-z]{1,3})\b""", RegexOption.IGNORE_CASE)

/** "Process step Dyeing" — the only place the step's NAME reaches this layer. */
private val CAPTION_STEP = Regex("""^process\s+step\s+(.+)$""", RegexOption.IGNORE_CASE)

private val CAPTION_PRE_PROCESS = Regex("""^pre-process\s+media\b""", RegexOption.IGNORE_CASE)

/** Every capture screen ends its caption with the record's name; this is the fallback for a call
 *  site that passed no title of its own. */
private val CAPTION_RECORD_NAME = Regex(
    """^(?:field media|pre-process media|process stage step \d+|measurement grid image)\s+for\s+(.+)$""",
    RegexOption.IGNORE_CASE
)

/** The process-step segment the form mints: `STEP_1A` (sequential) or `STEP_2_G1` (group). */
private val SEGMENT_STEP = Regex("""^STEP[_-](\d+)""", RegexOption.IGNORE_CASE)

/** `DabuHandBlockPrinting` -> `Dabu Hand Block Printing`: the one place this scheme has to invent
 *  word boundaries, and only for a name no caller supplied. */
private val CAMEL_BOUNDARY = Regex("""(?<=[a-z0-9])(?=[A-Z])""")

private val REPEATED_HYPHEN = Regex("-{2,}")
private val NON_LETTERS = Regex("[^a-z]")

/**
 * The pieces of one questionnaire clip's base name, as the interview screen minted it:
 * `{SECTION}_{QUESTION}_{NAME}_{DURATIONHHMMSS}_{STAMPDDMMYYYYHHMM}` with an optional trailing clip
 * number. Only the stamp and the clip number cannot be recovered from anywhere else, which is why
 * that base name is still parsed rather than ignored.
 */
private data class QuestionnaireClip(
    val section: String?,
    val answer: Int?,
    val name: String,
    val stamp: String,
    val clip: Int?
)

/**
 * `{RecordType}-{RecordName}-{Descriptor}-{ddMMyyyyHHmm}.{ext}` for one file about to be uploaded.
 *
 *     Artisan-Giriraj-Prasad-Chhipa-Photo-2-010720261824.jpg
 *     Product-Bagru-Block-Print-Video-1-200620261153.mp4
 *     Tool-Ringal-Splitting-Knife-Grid-Measurement-Height-200620261200.jpg
 *     Process-Dabu-Printing-Step-2-Dyeing-Video-1-210620261430.mp4
 *
 * [overrideBaseName] is the interview screen's own clip name; it is read for the section, the
 * question and the moment of capture, then re-spelled in the same words as everything else instead
 * of being passed through as `K_1_…`.
 */
private fun mediaFilename(
    recordType: String?,
    recordName: String?,
    mediaType: String,
    index: Int,
    stageStep: Int? = null,
    customSegment: String? = null,
    caption: String? = null,
    overrideBaseName: String? = null,
    originalName: String
): String {
    val extension = nameSafeChars(originalName.substringAfterLast('.', ""))
        .takeIf { it.isNotBlank() }
        ?.let { ".$it" }
        .orEmpty()

    val base = overrideBaseName.blankToNull()
    val clip = base?.let(::parseQuestionnaireClip)
    // A caller-supplied base that is not the interview shape is that caller's business, not this
    // function's to re-interpret; it is only made safe and hyphenated.
    if (base != null && clip == null) return literalBaseName(base, extension)

    val descriptor = if (clip != null) {
        interviewClipDescriptor(clip, caption, mediaType, index)
    } else {
        mediaDescriptor(recordType, mediaType, index, stageStep, customSegment, caption, originalName)
    }

    val supplied = recordName.blankToNull()
        ?: captionRecordName(caption)
        ?: clip?.name
        ?: splitCamel(originalName.substringBeforeLast('.'))
    val stem = assembleName(
        recordType = hyphenate(NAME_RECORD_TYPE_WORD[recordType?.trim()?.lowercase()]),
        recordName = hyphenate(trimRedundantTail(supplied, descriptor)),
        descriptor = descriptor,
        stamp = clip?.stamp ?: captureStamp(),
        extension = extension
    )
    return stem.ifBlank { nameSafeChars(originalName).trim(' ', '.').ifBlank { "file" } }
}

/**
 * ddMMyyyyHHmm, in the device's own zone. Twelve digits, never fourteen.
 *
 * The moment the phone captured the file, which is what the researcher saw on screen — not the
 * moment the row reached the server, which lands a beat later and shifts a name by a minute. The
 * backend reads this stamp straight back off the uploaded name and cuts the seconds off the older
 * uploads that carry them, so every file in the repository is stamped to the same precision. Where
 * that puts two files of one minute on one name, the export numbers the later one `-2`; a second
 * this app never recorded is never invented to keep them apart.
 */
private fun captureStamp(): String =
    java.text.SimpleDateFormat("ddMMyyyyHHmm", java.util.Locale.US).format(java.util.Date())

/** Drop only the characters a filesystem or a zip genuinely cannot carry, in any script. */
private fun nameSafeChars(value: String?): String {
    val text = value ?: return ""
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val code = text.codePointAt(i)
        i += Character.charCount(code)
        val category = Character.getType(code)
        val keep = code !in NAME_UNSAFE_CHARS &&
            (category !in NAME_UNSAFE_CATEGORIES || code == ZERO_WIDTH_JOINER || code == ZERO_WIDTH_NON_JOINER)
        if (keep) out.appendCodePoint(code)
    }
    return out.toString()
}

/**
 * Words joined by hyphens, with every script intact.
 *
 * Anything that is not a letter, a digit or a combining mark is a boundary, so "Cane, Bamboo and
 * Block Printing" reads "Cane-Bamboo-and-Block-Printing" and "गिरीराज प्रसाद छीपा" keeps its
 * characters and simply gains hyphens between its words.
 */
private fun hyphenate(value: String?): String {
    val text = nameSafeChars(value)
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val code = text.codePointAt(i)
        i += Character.charCount(code)
        val category = Character.getType(code)
        val isWordChar = Character.isLetterOrDigit(code) ||
            category in NAME_NUMBER_CATEGORIES ||
            category in NAME_MARK_CATEGORIES ||
            code == ZERO_WIDTH_JOINER ||
            code == ZERO_WIDTH_NON_JOINER
        if (isWordChar) out.appendCodePoint(code) else out.append('-')
    }
    return out.toString().replace(REPEATED_HYPHEN, "-").trim('-')
}

/** Trim to both limits at once, never mid-character and never mid-surrogate-pair. */
private fun clipName(value: String, maxChars: Int, maxBytes: Int): String {
    var out = if (value.codePointCount(0, value.length) > maxChars) {
        value.substring(0, value.offsetByCodePoints(0, maxChars))
    } else {
        value
    }
    while (out.isNotEmpty() && out.toByteArray(Charsets.UTF_8).size > maxBytes) {
        out = out.substring(0, out.offsetByCodePoints(out.length, -1))
    }
    return out
}

/**
 * [clipName], but cutting back to a whole word when it has to cut at all.
 *
 * The text is already hyphenated, so a blind slice leaves "Cane-Bamboo-an" — a fragment that reads
 * as a word the record does not contain. Dropping the partial word says less and says nothing
 * false. A single very long word has no boundary to retreat to, and there the blind cut stands.
 */
private fun clipWords(value: String, maxChars: Int, maxBytes: Int): String {
    val clipped = clipName(value, maxChars, maxBytes)
    if (clipped == value) return value
    val head = clipped.substringBeforeLast('-', "")
    return (if (head.isNotEmpty()) head else clipped).trim('-')
}

private fun splitCamel(value: String?): String = CAMEL_BOUNDARY.replace(value.orEmpty(), " ")

/** Only lowercase letters, so `GRID_HEIGHT`, `grid-height-9.jpg` and "Height grid" all compare alike. */
private fun nameLetters(value: String?): String = value.orEmpty().lowercase().replace(NON_LETTERS, "")

/**
 * Join the pieces, spending the byte budget on the record name and nothing else.
 *
 * The descriptor and the timestamp are what tell one artisan's forty clips apart, so they are never
 * trimmed. The record name absorbs the whole shortfall, and if the tail alone has eaten the budget
 * the head goes entirely — leaving a name that says less about which record this belongs to but
 * still says exactly which file it is.
 */
private fun assembleName(
    recordType: String,
    recordName: String,
    descriptor: String,
    stamp: String,
    extension: String
): String {
    val tail = listOf(descriptor, stamp).filter { it.isNotEmpty() }.joinToString("-")
    val budget = MAX_NAME_BYTES - "-$tail$extension".toByteArray(Charsets.UTF_8).size
    val head = if (budget <= 0) {
        ""
    } else {
        // Re-clip the pair: a long type word plus a short name can still overrun.
        clipWords(
            listOf(recordType, clipWords(recordName, MAX_RECORD_NAME_CHARS, budget))
                .filter { it.isNotEmpty() }
                .joinToString("-"),
            MAX_NAME_CHARS,
            budget
        )
    }

    var stem = listOf(head, tail).filter { it.isNotEmpty() }.joinToString("-")
        .replace(REPEATED_HYPHEN, "-")
        .trim('-', ' ', '.')
    if (stem.isEmpty()) return ""
    if (stem.substringBefore('.').uppercase() in NAME_RESERVED) stem = "${stem}_"
    val room = MAX_NAME_BYTES - extension.toByteArray(Charsets.UTF_8).size
    return clipName(stem, MAX_NAME_CHARS, room).trim('-', ' ', '.') + extension
}

/** What this file IS, in words: the half of the name that disambiguates one photo from the next. */
private fun mediaDescriptor(
    recordType: String?,
    mediaType: String,
    index: Int,
    stageStep: Int?,
    customSegment: String?,
    caption: String?,
    originalName: String
): String {
    val kind = "${nameKindWord(mediaType)}-$index"
    val tag = recordType?.trim()?.lowercase().orEmpty()

    if (tag == "questionnaire" || tag == "questionnaireinterview") {
        val (section, answer) = interviewSectionAnswer(caption) ?: return "Interview-$kind"
        return "Interview-Section-$section" + (answer?.let { "-Answer-$it" }).orEmpty()
    }

    gridDescriptor(customSegment, caption, originalName)?.let { return it }

    // `stageStep` is the tool form's numbered process stage; `customSegment` is the process form's
    // `STEP_1A` / `STEP_2_G1`, whose trailing letter distinguishes files within a step and is what
    // the file index already says.
    val step = stageStep ?: SEGMENT_STEP.find(customSegment.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    if (step != null || tag == "processstep") {
        val stepName = clipWords(
            hyphenate(CAPTION_STEP.find(caption.orEmpty().trim())?.groupValues?.get(1)),
            MAX_STEP_NAME_CHARS,
            MAX_STEP_NAME_CHARS * 3
        )
        return listOf(step?.let { "Step-$it" } ?: "Step", stepName, kind)
            .filter { it.isNotEmpty() }
            .joinToString("-")
    }

    val isPreProcess = customSegment?.trim().equals("PRE", ignoreCase = true) ||
        CAPTION_PRE_PROCESS.containsMatchIn(caption.orEmpty().trim())
    if (isPreProcess) return "Pre-Process-$kind"

    return kind
}

/**
 * `Grid-Measurement-Height` and friends, when this image is a measurement grid.
 *
 * The axis is only stated when something actually says which one it is: the segment the older grid
 * flow tagged, or the name the capture screen gave the file on disk, or the caption. A grid photo
 * that names no axis gets the bare `Grid-Measurement` rather than a guess that would be wrong half
 * the time.
 */
private fun gridDescriptor(customSegment: String?, caption: String?, originalName: String): String? {
    val segment = nameLetters(customSegment)
    val file = originalName.substringBeforeLast('.').lowercase()
    val head = nameLetters(caption)
    if (segment.contains("gridlengthbreadth") ||
        file.startsWith("grid-lengthbreadth") ||
        head.startsWith("lengthbreadthgrid")
    ) {
        return "Grid-Measurement-Length-Breadth"
    }
    if (segment.contains("gridheight") || file.startsWith("grid-height") || head.startsWith("heightgrid")) {
        return "Grid-Measurement-Height"
    }
    if (segment.contains("measurementgrid") ||
        file.startsWith("measure-grid") ||
        head.startsWith("measurementgrid")
    ) {
        return "Grid-Measurement"
    }
    return null
}

/** `Interview-Section-K-Answer-1`, or as much of it as the caption can prove. */
private fun interviewSectionAnswer(caption: String?): Pair<String, Int?>? {
    val text = caption.orEmpty().trim()
    CAPTION_QUESTION.find(text)?.let { return it.groupValues[1].uppercase() to it.groupValues[2].toIntOrNull() }
    CAPTION_SECTION.find(text)?.let { return it.groupValues[1].uppercase() to null }
    return null
}

/**
 * The descriptor for one interview clip.
 *
 * A recording that covers a whole section is not an answer to any one question, so it stops at
 * `Interview-Section-D` instead of claiming an answer number it does not have. `-Clip-2` is
 * appended only when the same target really was recorded more than once in the same save; without
 * it two takes of one answer, a few seconds apart, would land on the same name.
 */
private fun interviewClipDescriptor(
    clip: QuestionnaireClip,
    caption: String?,
    mediaType: String,
    index: Int
): String {
    val (section, answer) = interviewSectionAnswer(caption) ?: (clip.section to clip.answer)
    if (section == null) return "Interview-${nameKindWord(mediaType)}-$index"
    return buildString {
        append("Interview-Section-").append(section)
        if (answer != null) append("-Answer-").append(answer)
        if (clip.clip != null) append("-Clip-").append(clip.clip)
    }
}

private fun nameKindWord(mediaType: String): String = NAME_KIND_WORD[mediaType.uppercase()] ?: "File"

private fun parseQuestionnaireClip(base: String): QuestionnaireClip? {
    val tokens = base.trim().split('_')
    if (tokens.size < 5) return null
    if (tokens[3].length != 6 || !tokens[3].all(Char::isDigit)) return null
    val stamp = tokens[4].takeIf { it.length == 12 && it.all(Char::isDigit) } ?: return null
    // Both slots fall back to the literal "SEC" when the screen had no code to put there, and
    // "Section-SEC" would be exactly the sort of code this scheme exists to stop emitting.
    val section = tokens[0].takeIf { it.isNotBlank() && !it.equals("SEC", ignoreCase = true) }
    return QuestionnaireClip(
        section = section,
        answer = tokens[1].toIntOrNull(),
        name = tokens[2],
        stamp = stamp,
        clip = tokens.getOrNull(5)?.toIntOrNull()?.takeIf { it > 1 }
    )
}

/** The record's name out of a capture screen's caption, for a call site that passed no title. */
private fun captionRecordName(caption: String?): String? =
    CAPTION_RECORD_NAME.find(caption.orEmpty().trim())?.groupValues?.get(1)?.blankToNull()

/**
 * Drop a tail the descriptor is about to repeat.
 *
 * The measurement screen hands down "Ringal splitting knife measurement grid" as the record name,
 * which followed by `Grid-Measurement-Height` says "grid" twice and "measurement" twice. The record
 * is the knife; the descriptor is what the photo is of.
 */
private fun trimRedundantTail(recordName: String, descriptor: String): String {
    if (!descriptor.startsWith("Grid-Measurement")) return recordName
    val trimmed = recordName.trim()
    if (!nameLetters(trimmed).endsWith("measurementgrid")) return trimmed
    val cut = trimmed.lowercase().lastIndexOf("measurement")
    // A record genuinely called nothing but "measurement grid" keeps its name; there is nothing else
    // left to call it.
    return if (cut > 0) trimmed.substring(0, cut).trim() else trimmed
}

/** A caller-supplied base name this scheme cannot read: made safe, hyphenated, left alone otherwise. */
private fun literalBaseName(base: String, extension: String): String {
    val room = MAX_NAME_BYTES - extension.toByteArray(Charsets.UTF_8).size
    return clipWords(hyphenate(base), MAX_NAME_CHARS, room).ifBlank { "Recording" } + extension
}
