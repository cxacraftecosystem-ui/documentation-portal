package com.fieldrepository.app.data

import com.fieldrepository.app.transcript.data.TranscribeChoices
import com.fieldrepository.app.transcript.data.UploadTranscribe
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import com.fieldrepository.app.ui.ConsolidatedQuestionnaireDto
import com.fieldrepository.app.ui.UNASSIGNED_WORKSHOP
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

private const val MULTIPART_THRESHOLD = 64L * 1024 * 1024

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

fun WorkshopDetailDto.occurrenceDate(): String = startDate ?: date ?: createdAt ?: ""

private val errorBodyJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun Throwable.apiErrorMessage(fallback: String): String = userMessage(fallback)

data class ApiFailure(
    val code: String?,
    val message: String,
    val detail: JsonObject? = null,
    val fields: List<String> = emptyList()
)

const val POSSIBLE_DUPLICATE_CODE = "artisan_possible_duplicate"

fun ApiFailure.possibleDuplicates(): List<DuplicateCandidateDto>? {
    if (code != POSSIBLE_DUPLICATE_CODE) return null
    val list = detail?.get("candidates") as? JsonArray ?: return emptyList()
    return list.mapNotNull { element ->
        runCatching { errorBodyJson.decodeFromJsonElement(DuplicateCandidateDto.serializer(), element) }.getOrNull()
    }
}

data class OutboxRefusal(
    val reason: String,
    val fields: List<String> = emptyList(),
    val code: String? = null,
    val existingId: String? = null,
    val existingLabel: String? = null
)

const val ARTISAN_IDENTITY_CONFLICT_CODE = "artisan_identity_conflict"

fun Throwable.outboxRefusal(fallback: String): OutboxRefusal = outboxRefusal(apiFailure(fallback), fallback)

fun Throwable.outboxRefusal(failure: ApiFailure, fallback: String): OutboxRefusal {
    val said = failure.message
    val reason = if (this is HttpException && (said == fallback || said.isBareReasonPhrase())) {
        statusSentence(code()) ?: fallback
    } else {
        said
    }
    val existing = if (failure.code == ARTISAN_IDENTITY_CONFLICT_CODE) failure.detail?.get("existingArtisan") as? JsonObject else null
    fun text(key: String) = (existing?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    return OutboxRefusal(
        reason = reason,
        fields = failure.fields,
        code = failure.code,
        existingId = text("id"),
        existingLabel = listOfNotNull(text("name"), text("place")).joinToString(", ").ifBlank { null }
    )
}

const val DUPLICATE_ARTISAN_SET_CODE = "artisan_set_taken"

const val MERGE_ANSWER_CONFLICT_CODE = "merge_answer_conflict"

data class InterviewSetHolder(val id: String, val title: String?)

fun ApiFailure.artisanSetHolder(): InterviewSetHolder? {
    if (code != DUPLICATE_ARTISAN_SET_CODE) return null
    val holder = detail?.get("holder") as? JsonObject ?: return null
    val id = (holder["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    return InterviewSetHolder(
        id = id,
        title = (holder["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    )
}

data class MergeAnswerConflict(
    val questionId: String,
    val sectionCode: String? = null,
    val prompt: String? = null
)

fun ApiFailure.mergeAnswerConflicts(): List<MergeAnswerConflict> {
    if (code != MERGE_ANSWER_CONFLICT_CODE) return emptyList()
    val rows = detail?.get("questions") as? JsonArray ?: return emptyList()
    return rows.mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        fun text(key: String) =
            (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val id = text("questionId") ?: return@mapNotNull null
        MergeAnswerConflict(questionId = id, sectionCode = text("sectionCode"), prompt = text("prompt"))
    }
}

const val CONNECTION_FAILED_SENTENCE = "Could not connect. Check your connection and try again."

sealed interface ReviewSend {
    data class Sent(val status: String? = null, val mediaApproved: Int = 0) : ReviewSend

    data object Queued : ReviewSend
}

internal fun isUnanswered(error: Throwable): Boolean =
    error is IOException || error.isTransportFailure() ||
        (error is HttpException && error.code() in setOf(502, 503, 504))

internal fun Throwable.isTransportFailure(): Boolean =
    this is java.net.UnknownHostException ||
        this is java.net.SocketException ||
        this is java.net.UnknownServiceException ||
        this is java.io.InterruptedIOException ||
        this is javax.net.ssl.SSLException ||
        (this is IOException && (cause is java.io.EOFException || cause is java.net.SocketException)) ||
        looksLikePlatformTransportText(message)

private val TRANSPORT_TEXT = listOf(
    "unable to resolve host", "failed to connect", "connection refused", "connection reset",
    "timeout", "timed out", "no address associated", "network is unreachable", "software caused connection abort"
)

private fun looksLikePlatformTransportText(text: String?): Boolean {
    val lower = text?.lowercase() ?: return false
    return TRANSPORT_TEXT.any { it in lower }
}

internal fun looksLikeProtocolText(text: String): Boolean {
    val t = text.trim()
    if (t.isBareReasonPhrase()) return true
    return Regex("""^(java|javax|kotlin|kotlinx|okhttp3|retrofit2|android)\.[\w.$]+""").containsMatchIn(t)
}

fun Throwable.apiFailure(fallback: String): ApiFailure {
    if (isTransportFailure()) return ApiFailure(null, CONNECTION_FAILED_SENTENCE)
    if (this is SerializationException) return ApiFailure(null, fallback)
    val own = message?.trim()?.takeIf { it.isNotEmpty() && !looksLikeProtocolText(it) }
    val http = this as? HttpException ?: return ApiFailure(null, own ?: fallback)
    val plain = statusSentence(http.code()) ?: fallback
    val raw = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
    if (raw.isNullOrBlank()) return ApiFailure(null, plain)
    val detail = (runCatching { errorBodyJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject)
        ?.get("detail")
        ?: return ApiFailure(null, plain)
    val code = ((detail as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val said = detailMessage(detail)?.takeUnless { looksLikeProtocolText(it) }
    return ApiFailure(code, said ?: plain, detail as? JsonObject, refusalFieldPaths(detail))
}

private fun errorBodyDetail(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val body = runCatching { errorBodyJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    val detail = body?.get("detail") ?: return null
    return detailMessage(detail)?.takeUnless { looksLikeProtocolText(it) }
}

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

const val REVIEW_PAGE_SIZE = 20

const val MY_RECORDS_PAGE_BUDGET = 20

data class MyRecordsPage<T>(val items: List<T>, val truncated: Boolean, val nextPage: Int? = null)

private const val UPLOAD_CONCURRENCY = 3

internal val EVERY_WORKSHOP: List<String>? = null

internal const val LIST_PAGE_CEILING = 100

internal const val ARTISAN_PAGE_BUDGET = 5

internal const val LOOKUP_PAGE_BUDGET = 20

internal const val EVERY_PAGE_BUDGET = 1000

data class MyRecordsAll(
    val artisans: List<ArtisanDto>,
    val products: List<ProductDetailDto>,
    val tools: List<ToolDetailDto>,
    val workshops: List<WorkshopDetailDto>,
    val interviews: List<QuestionnaireInterviewDetailDto>,
)

internal suspend fun <T> myRecordsWalk(from: Int, fetch: suspend (page: Int) -> PageResponse<T>): MyRecordsPage<T> {
    val rows = mutableListOf<T>()
    var page = from.coerceAtLeast(1)
    val last = page + MY_RECORDS_PAGE_BUDGET - 1
    var reached = (page - 1) * LIST_PAGE_CEILING
    var total = 0
    var exhausted = false
    while (page <= last) {
        val answer = fetch(page)
        total = answer.total
        rows += answer.items
        reached += answer.items.size
        if (answer.items.isEmpty() || reached >= answer.total) {
            exhausted = true
            break
        }
        page += 1
    }
    val more = !exhausted && reached < total
    return MyRecordsPage(rows, truncated = more, nextPage = if (more) page else null)
}

internal suspend fun <T> walkPages(budget: Int, fetch: suspend (page: Int) -> PageResponse<T>): PageResponse<T> {
    val first = fetch(1)
    val last = minOf(first.pages, budget)
    if (last <= 1) return first
    val rows = first.items.toMutableList()
    for (page in 2..last) {
        val next = fetch(page)
        rows += next.items
        if (next.items.isEmpty()) break
    }
    return first.copy(items = rows, page = 1, pageSize = rows.size)
}

class FieldRepository(
    private val api: FieldRepositoryApi,
    private val tokenStore: TokenStore
) {
    private val storageClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.MINUTES)
        .build()

    private val offlineJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val completeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val syncMutex: Mutex get() = outboxSyncMutex
    private val sweptStagedObjects = java.util.concurrent.atomic.AtomicBoolean(false)

    fun hasToken(): Boolean = !tokenStore.getToken().isNullOrBlank()

    fun cachedUser(): UserDto? = tokenStore.getUser()

    private fun open(response: SignInResponse): SignInOutcome {
        val token = response.accessToken
        val user = response.user
        if (token != null && user != null) {
            tokenStore.setToken(token)
            tokenStore.setUser(user)
            return SignInOutcome.SignedIn(user)
        }
        val challenge = response.challengeToken
        if (response.twoFactorRequired && !challenge.isNullOrBlank()) return SignInOutcome.TwoStepRequired(challenge)
        throw IllegalStateException("Sign-in did not complete. Try again.")
    }

    suspend fun login(email: String, password: String): SignInOutcome =
        open(api.login(LoginRequest(email = email.trim(), password = password)))

    suspend fun loginWithGoogle(idToken: String): SignInOutcome =
        open(api.googleLogin(GoogleLoginRequest(googleIdToken = idToken)))

    suspend fun loginWithOidc(body: OidcLoginRequest): SignInOutcome = open(api.oidcLogin(body))

    suspend fun verifyTwoFactor(challengeToken: String, code: String): UserDto {
        val response = api.verifyTwoFactor(TwoFactorVerifyRequest(challengeToken = challengeToken, code = code.trim()))
        tokenStore.setToken(response.accessToken)
        tokenStore.setUser(response.user)
        return response.user
    }

    suspend fun twoFactorStatus(): TwoFactorStatusDto = api.twoFactorStatus()

    suspend fun beginTwoFactorSetup(): TwoFactorSetupDto = api.beginTwoFactorSetup()

    suspend fun cancelTwoFactorSetup() {
        val response = api.cancelTwoFactorSetup()
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun enableTwoFactor(code: String): List<String> =
        api.enableTwoFactor(TwoFactorCodeRequest(code.trim())).recoveryCodes

    suspend fun disableTwoFactor(code: String) {
        val response = api.disableTwoFactor(TwoFactorCodeRequest(code.trim()))
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun makeNewRecoveryCodes(code: String): List<String> =
        api.makeNewRecoveryCodes(TwoFactorCodeRequest(code.trim())).recoveryCodes

    suspend fun resetTwoFactorForUser(id: String): UserDto = api.resetTwoFactorForUser(id)

    fun holdForPasswordChange(): UserDto? {
        val held = cachedUser()?.copy(mustChangePassword = true) ?: return null
        tokenStore.setUser(held)
        return held
    }

    suspend fun changeOwnPassword(currentPassword: String, newPassword: String) {
        val answer = api.changeOwnPassword(
            ChangePasswordRequest(currentPassword = currentPassword, newPassword = newPassword)
        )
        if (!answer.isSuccessful) throw HttpException(answer)
        answer.headers()[SESSION_TOKEN_HEADER]?.takeIf { it.isNotBlank() }?.let { tokenStore.setToken(it) }
        val profile = runCatching { refreshUser() }.getOrNull() ?: cachedUser()
        if (profile?.mustChangePassword == true) {
            tokenStore.setUser(profile.copy(mustChangePassword = false))
        }
    }

    suspend fun issuePasswordLink(userId: String): IssuedPasswordLinkDto =
        api.issuePasswordLink(IssuePasswordLinkRequest(userId = userId))

    suspend fun revokePasswordLink(linkId: String) {
        api.revokePasswordLink(linkId)
    }

    suspend fun checkPasswordLink(token: String): PasswordLinkCheckDto =
        api.checkPasswordLinkInBody(PasswordLinkCheckRequest(token = token))

    suspend fun setPasswordWithLink(token: String, password: String) {
        api.setPasswordWithLink(SetPasswordRequest(token = token, password = password))
        logout()
    }

    private val recoveryApi: AccountRecoveryApi by lazy {
        ApiClient.retrofit(tokenStore).create(AccountRecoveryApi::class.java)
    }

    suspend fun forgotPassword(email: String): String =
        recoveryApi.forgotPassword(ForgotPasswordRequest(email = email.trim().lowercase())).message

    suspend fun saveOwnPassword(
        currentPassword: String?,
        newPassword: String,
        twoFactorCode: String? = null,
        proof: ProviderProof? = null,
    ) {
        if (currentPassword != null) {
            changeOwnPassword(currentPassword, newPassword)
            return
        }
        val answer = recoveryApi.setFirstPassword(
            proof?.request(newPassword, twoFactorCode)
                ?: FirstPasswordRequest(newPassword = newPassword, twoFactorCode = twoFactorCode?.trim()?.ifBlank { null })
        )
        if (!answer.isSuccessful) {
            val refused = HttpException(answer)
            answer.headers()[CONFIRM_SIGN_IN_HEADER]?.takeIf { it.isNotBlank() }?.let { provider ->
                throw ConfirmSignInRequired(refused.apiErrorMessage(confirmPrompt(provider)), provider.trim().uppercase())
            }
            if (answer.headers()[TWO_FACTOR_REQUIRED_HEADER] != null) {
                throw TwoFactorCodeRequired(
                    refused.apiErrorMessage(TWO_FACTOR_CODE_FOR_PASSWORD),
                    answer.headers()[SIGN_IN_PROOF_HEADER]?.trim()?.ifBlank { null },
                )
            }
            throw refused
        }
        answer.headers()[SESSION_TOKEN_HEADER]?.takeIf { it.isNotBlank() }?.let { tokenStore.setToken(it) }
        val profile = runCatching { refreshUser() }.getOrNull() ?: cachedUser()
        if (profile != null && profile.hasPassword != true) {
            tokenStore.setUser(profile.copy(hasPassword = true, mustChangePassword = false))
        }
    }

    suspend fun emailPasswordLink(userId: String): IssuedPasswordLinkDto =
        recoveryApi.issuePasswordLink(PasswordLinkDeliveryRequest(userId = userId, delivery = PASSWORD_LINK_BY_EMAIL))

    suspend fun setTemporaryPassword(userId: String, password: String): UserDto =
        recoveryApi.setTemporaryPassword(userId, TemporaryPasswordBody(password = password))

    suspend fun signOutEverywhere(userId: String): UserDto = recoveryApi.signOutEverywhere(userId)

    suspend fun mailAvailable(): Boolean {
        knownMailAvailable?.let { return it }
        val answer = runCatching { recoveryApi.mailStatus().available }.getOrNull() ?: return false
        knownMailAvailable = answer
        return answer
    }

    @Volatile
    private var knownMailAvailable: Boolean? = null

    suspend fun createAccount(body: CreateAccountBody): CreatedAccountDto {
        val answer = recoveryApi.createAccount(body.copy(email = body.email.trim().lowercase(), name = body.name.trim()))
        if (answer.isSuccessful) {
            return answer.body() ?: throw IllegalStateException("Something went wrong. Try again.")
        }
        val refused = HttpException(answer)
        if (answer.code() == 409) {
            throw AccountAlreadyExists(
                refused.apiErrorMessage("That address already has an account."),
                answer.headers()[EXISTING_ACCOUNT_HEADER]?.trim()?.ifBlank { null }
            )
        }
        throw refused
    }

    fun logout(context: Context? = null) {
        val signedOut = tokenStore.getUser()?.id
        tokenStore.clear()
        if (context != null) runCatching { NotificationSync.signOut(context, signedOut) }
        if (context != null) runCatching { com.fieldrepository.app.explorer.data.ExplorerDownloads.forgetAll(context) }
        OfflineReadCache.clear()
        PreviewCache.clear()
    }

    private fun currentUserId(): String? = tokenStore.getUser()?.id

    suspend fun currentUser(): UserDto = api.me()

    suspend fun refreshUser(): UserDto {
        val user = api.me()
        tokenStore.setUser(user)
        return user
    }

    suspend fun stats(): DashboardStats = api.dashboardStats()

    suspend fun addressReference(): AddressReferenceDto =
        cachedAddressReference ?: api.addressReference().also { cachedAddressReference = it }

    @Volatile
    private var cachedAddressReference: AddressReferenceDto? = null

    suspend fun users(): List<UserDto> = walkPages(EVERY_PAGE_BUDGET) { api.users(page = it, pageSize = 100) }.items

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

    suspend fun updateUserRole(id: String, role: String): UserDto =
        api.updateUser(id, UserUpdateRequest(role = role))

    suspend fun userDirectory(): List<UserDto> = api.userDirectory()

    suspend fun accessRoster(
        page: Int = 1,
        pageSize: Int = 50,
        search: String? = null,
        status: String? = null
    ): PageResponse<AccessRosterDto> = api.accessRoster(
        page = page,
        pageSize = pageSize,
        search = search?.trim()?.ifBlank { null },
        status = status?.ifBlank { null }
    )

    suspend fun accessRosterPendingCount(): Int = api.accessRosterPendingCount().pending

    fun notificationApi(): NotificationApi = object : NotificationApi {
        override suspend fun list(limit: Int): NotificationPageDto = api.notifications(limit)
        override suspend fun markRead(body: NotificationReadBody): NotificationReadResultDto = api.markNotificationsRead(body)
    }

    suspend fun syncNotifications(context: Context): Int? {
        val userId = tokenStore.getUser()?.id ?: return null
        return NotificationSync.sync(context, notificationApi(), userId)
    }

    suspend fun addToAccessRoster(
        email: String,
        fullName: String? = null,
        notes: String? = null,
        grantedRole: String? = null,
        password: String? = null,
        mustChangePassword: Boolean = true
    ): AccessRosterDto = api.addToAccessRoster(
        AccessRosterCreateBody(
            email = email.trim(),
            fullName = fullName?.trim()?.ifBlank { null },
            notes = notes?.trim()?.ifBlank { null },
            grantedRole = grantedRole?.ifBlank { null },
            password = password?.takeIf { it.isNotEmpty() },
            mustChangePassword = if (password.isNullOrEmpty()) null else mustChangePassword
        )
    )

    suspend fun approveAccessRosterEntry(id: String, grantedRole: String? = null): AccessRosterDto =
        api.updateAccessRosterEntry(
            id,
            AccessRosterUpdateBody(status = "ACTIVE", grantedRole = grantedRole?.ifBlank { null })
        )

    suspend fun rejectAccessRosterEntry(id: String): AccessRosterDto =
        api.updateAccessRosterEntry(id, AccessRosterUpdateBody(status = "REJECTED"))

    suspend fun suspendAccessRosterEntry(id: String): AccessRosterDto = api.suspendAccessRosterEntry(id)
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
        api.addEntryComment(null, EntryCommentBody(recordType = recordType, recordId = recordId, body = body))
    suspend fun deleteEntryComment(commentId: String) = api.deleteEntryComment(commentId)
    suspend fun updateEntryComment(commentId: String, body: String): EntryCommentDto =
        api.updateEntryComment(commentId, EntryCommentUpdateBody(body = body))
    suspend fun recordRevisions(recordType: String, recordId: String): List<RecordRevisionDto> =
        api.recordRevisions(recordType, recordId)

    suspend fun workshopAssignments(workshopId: String): List<WorkshopAssignmentDto> =
        api.workshopAssignments(workshopId)

    suspend fun setWorkshopAssignments(workshopId: String, userIds: List<String>, accessLevel: String? = null): List<WorkshopAssignmentDto> =
        api.setWorkshopAssignments(workshopId, WorkshopAssignmentBody(userIds, accessLevel))

    suspend fun grantWorkshopAccess(workshopId: String, userId: String, accessLevel: String, note: String? = null): WorkshopAssignmentDto =
        api.grantWorkshopAssignment(workshopId, WorkshopGrantBody(userId = userId, accessLevel = accessLevel, note = note?.blankToNull()))

    suspend fun updateWorkshopAccess(workshopId: String, userId: String, accessLevel: String? = null, status: String? = null, note: String? = null): WorkshopAssignmentDto =
        api.updateWorkshopAssignment(
            workshopId,
            userId,
            WorkshopAssignmentUpdateBody(accessLevel = accessLevel, status = status, note = note?.blankToNull())
        )

    suspend fun revokeWorkshopAccess(workshopId: String, userId: String): WorkshopAssignmentDto =
        api.revokeWorkshopAssignment(workshopId, userId)

    suspend fun workshopAccessLevels(): List<WorkshopAccessLevelDto> = api.workshopAccessLevels()

    suspend fun requestWorkshopAccess(workshopIds: List<String>, accessLevel: String?, note: String?): WorkshopAccessRequestResultDto =
        api.requestWorkshopAccess(
            WorkshopAccessRequestBody(
                workshopIds = workshopIds,
                accessLevel = accessLevel?.blankToNull(),
                note = note?.blankToNull()
            )
        )

    suspend fun myWorkshopAccess(): List<WorkshopAssignmentDto> = api.myWorkshopAccess()

    suspend fun workshopAccessQueue(statusFilter: String = "PENDING"): List<WorkshopAssignmentDto> =
        api.workshopAccessRequests(statusFilter)

    suspend fun decideWorkshopAccess(requestId: String, status: String, accessLevel: String? = null, note: String? = null): WorkshopAssignmentDto =
        api.decideWorkshopAccess(
            requestId,
            WorkshopAccessDecisionBody(status = status, accessLevel = accessLevel, note = note?.blankToNull())
        )

    suspend fun tasks(
        view: String = "assigned",
        status: String? = null,
        workshopId: String? = null,
        assigneeId: String? = null,
        batchId: String? = null,
        pageSize: Int = 100,
        outstanding: Boolean = false
    ): List<TaskDto> =
        api.tasks(
            view = view,
            status = status?.blankToNull(),
            workshopId = workshopId?.blankToNull(),
            pageSize = pageSize,
            assigneeId = assigneeId?.blankToNull(),
            batchId = batchId?.blankToNull(),
            outstanding = if (outstanding && status.isNullOrBlank()) true else null
        ).items

    suspend fun taskSummary(workshopId: String? = null): TaskSummaryDto =
        api.taskSummary(workshopId?.blankToNull())

    suspend fun task(taskId: String): TaskDto = api.task(taskId)

    suspend fun updateTaskProgress(taskId: String, status: String? = null, progressCount: Int? = null): TaskDto =
        api.updateTask(taskId, TaskUpdateBody(status = status, progressCount = progressCount))

    suspend fun reviewTask(taskId: String, status: String): TaskDto =
        api.updateTask(taskId, TaskUpdateBody(status = status))

    suspend fun taskOptions(workshopId: String? = null): TaskOptionsDto =
        api.taskOptions(workshopId?.blankToNull())

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

    suspend fun deleteTaskBatch(batchId: String) = api.deleteTaskBatch(batchId)

    suspend fun deleteTask(taskId: String) = api.deleteTask(taskId)

    suspend fun managedSecrets(): List<ManagedSecretDto> = api.managedSecrets()

    suspend fun revealSecret(key: String): ManagedSecretRevealDto = api.revealSecret(key)

    suspend fun aiProviders(): AiCatalogueDto = api.aiProviders()

    suspend fun myAiKeys(): List<UserAiKeyDto> = api.myAiKeys()

    suspend fun setMyAiKey(provider: String, key: String?, model: String?): UserAiKeyDto =
        api.setMyAiKey(provider, UserAiKeySetBody(key = key?.takeIf { it.isNotBlank() }, model = model))

    suspend fun deleteMyAiKey(provider: String): UserAiKeyDto = api.deleteMyAiKey(provider)

    suspend fun testMyAiKey(provider: String): UserAiKeyDto = api.testMyAiKey(provider)

    suspend fun setSecret(key: String, value: String): ManagedSecretDto =
        api.setSecret(key, ManagedSecretSetBody(value = value.trim()))

    suspend fun clearSecret(key: String): ManagedSecretDto = api.clearSecret(key)

    suspend fun testSecret(key: String): ManagedSecretDto = api.testSecret(key)

    suspend fun myPreferences(): PreferencesDto? = api.myPreferences().takeIf { it.exists }

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

    suspend fun savePreferences(preferences: PreferencesDto): PreferencesDto =
        savePreferences(
            theme = preferences.theme,
            reducedMotion = preferences.reducedMotion,
            largerText = preferences.largerText,
            highContrast = preferences.highContrast
        )

    suspend fun weeklyDigestPreference(): WeeklyDigestPreferenceDto = api.weeklyDigestPreference()

    suspend fun saveWeeklyDigestPreference(enabled: Boolean): WeeklyDigestPreferenceDto =
        api.updateWeeklyDigestPreference(WeeklyDigestPreferenceBody(enabled = enabled))

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

    suspend fun coverage(query: CoverageQuery = CoverageQuery()): CoverageResponse =
        api.mapCoverage(query.params())

    suspend fun coverageUnitRecords(
        stateLgd: Int,
        districtLgd: Int?,
        query: CoverageQuery,
        bucket: String? = null,
        cursor: String? = null
    ): CoverageUnitRecords = api.coverageUnitRecords(
        stateLgd = stateLgd,
        districtLgd = if (query.level == CoverageLevel.STATE) 0 else districtLgd ?: 0,
        params = query.params(forUnit = true),
        bucket = bucket,
        cursor = cursor
    )

    suspend fun dataTree(path: String = "", status: String? = null): DataTreeDto = api.dataTree(path, status)

    suspend fun locateRecord(recordType: String, recordId: String): String? {
        val body = api.dataLocate(recordType, recordId) as? JsonObject ?: return null
        return (body["path"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    suspend fun dataManifest(path: String = "", include: String? = null, status: String? = null): DataManifestDto =
        api.dataManifest(path, include?.blankToNull(), status)

    suspend fun pendingReviews(): List<PendingReviewDto> = api.pendingReviews().items

    suspend fun pendingReviewsPage(
        page: Int,
        pageSize: Int = REVIEW_PAGE_SIZE,
        workshopId: String? = null
    ): PendingReviewListDto =
        api.pendingReviews(page = page, pageSize = pageSize, workshopId = workshopId?.blankToNull())

    @Volatile private var reasonsCache: List<ReviewReasonDto>? = null
    private val reasonsLock = Mutex()

    suspend fun reviewReasons(): List<ReviewReasonDto> =
        reasonsCache ?: reasonsLock.withLock { reasonsCache ?: api.reviewReasons().also { reasonsCache = it } }

    suspend fun latestReview(recordType: String, recordId: String): LatestReviewAnswerDto =
        api.latestReview(recordType, recordId)

    suspend fun myReturnedRecords(): ReturnedListDto = api.myReturnedRecords()

    suspend fun bulkApprove(items: List<PendingReviewDto>): BulkApproveResponse =
        api.bulkApprove(BulkApproveRequest(items = items.map { BulkApproveItem(it.recordType, it.id) }))

    suspend fun approveRecord(context: Context, recordType: String, recordId: String, label: String): ReviewSend =
        sendReviewDecision(context, "review-approve", QueuedReviewDecision(recordType, recordId), label)

    suspend fun rejectMedia(context: Context, mediaId: String, label: String, reason: String?, notes: String?): ReviewSend =
        sendReviewDecision(
            context, "review-reject", QueuedReviewDecision("media", mediaId, notes?.blankToNull(), reason), label
        )

    suspend fun recordMediaReview(recordType: String, recordId: String): RecordMediaReviewDto =
        api.recordMediaReview(recordType, recordId)

    private suspend fun postReviewDecision(type: String, decision: QueuedReviewDecision, key: String?): JsonElement {
        val body = ReviewActionRequest(notes = decision.notes, reason = decision.reason)
        return when (type) {
            "review-approve" -> api.approveRecord(decision.recordType, decision.recordId, body, key)
            "review-reject" -> api.rejectRecord(decision.recordType, decision.recordId, body, key)
            else -> throw SerializationException("Unknown review decision: $type")
        }
    }

    private suspend fun sendReviewDecision(
        context: Context,
        type: String,
        decision: QueuedReviewDecision,
        label: String
    ): ReviewSend {
        val key = java.util.UUID.randomUUID().toString()
        return try {
            val answer = postReviewDecision(type, decision, key) as? JsonObject
            ReviewSend.Sent(
                status = (answer?.get("status") as? JsonPrimitive)?.contentOrNull,
                mediaApproved = (answer?.get("mediaApproved") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!isUnanswered(e)) throw e
            queueOfflineEntry(
                context, type, offlineJson.encodeToString(QueuedReviewDecision.serializer(), decision), label,
                emptyList(), kind = OutboxKind.ACTION, targetId = decision.recordId, idempotencyKey = key
            )
            ReviewSend.Queued
        }
    }

    suspend fun rejectRecord(recordType: String, recordId: String, reason: String? = null, notes: String? = null): String? {
        val answer = api.rejectRecord(recordType, recordId, ReviewActionRequest(notes = notes?.blankToNull(), reason = reason))
        return ((answer as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull
    }

    suspend fun reviseRecord(recordType: String, recordId: String, notes: String, reason: String? = null) {
        api.reviseRecord(recordType, recordId, ReviewActionRequest(notes = notes, reason = reason))
    }

    suspend fun editReviewedRecord(recordType: String, recordId: String, fields: Map<String, String>, note: String?) {
        api.editReviewedRecord(
            recordType,
            recordId,
            ReviewEditRequest(fields = fields, note = note?.blankToNull())
        )
    }

    fun installedVersionCode(context: Context): Int {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pkg.versionCode
    }

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

    suspend fun latestAppRelease(): AppReleaseDto = api.latestAppRelease()

    suspend fun myFeedback(): FeedbackDto = api.myFeedback()

    suspend fun upsertMyFeedback(request: FeedbackUpsertRequest): FeedbackDto =
        api.upsertMyFeedback(request)

    suspend fun allFeedback(): List<FeedbackDto> = api.allFeedback()

    suspend fun appSettings(): AppSettingDto = api.appSettings()

    suspend fun updateAppSettings(request: AppSettingUpdateRequest): AppSettingDto =
        api.updateAppSettings(request)

    suspend fun orphanedMedia(): List<MediaFileDto> = api.orphanMedia()

    suspend fun relinkMedia(mediaId: String, linkedRecordType: String, linkedRecordId: String): MediaFileDto =
        api.relinkMedia(mediaId, MediaRelinkRequest(linkedRecordType = linkedRecordType, linkedRecordId = linkedRecordId))

    suspend fun refineTranscript(mediaId: String, translate: Boolean): TranscriptRefineResponse =
        api.refineTranscript(mediaId, TranscriptRefineRequest(translate = translate))

    suspend fun applyTranscript(mediaId: String, text: String): MediaFileDto =
        api.setTranscript(mediaId, TranscriptUpdateRequest(text = text))

    suspend fun transcribeNow(mediaId: String): MediaFileDto = api.transcribeNow(mediaId)

    suspend fun transcriptInsight(mediaId: String): TranscriptInsightViewDto? =
        insightOrHidden { api.transcriptInsight(mediaId) }

    private val insightBatcher by lazy {
        TranscriptInsightBatcher({ ids -> insightOrHidden { api.transcriptInsights(ids.joinToString(",")).items } })
    }

    suspend fun transcriptInsightBatched(mediaId: String): TranscriptInsightViewDto? = insightBatcher.load(mediaId)

    suspend fun saveTranscriptInsight(mediaId: String, body: TranscriptInsightPatchRequest): TranscriptInsightViewDto =
        api.patchTranscriptInsight(mediaId, body)

    suspend fun regenerateTranscriptInsight(mediaId: String, discardEdits: Boolean): TranscriptInsightViewDto =
        api.regenerateTranscriptInsight(mediaId, TranscriptInsightRegenerateRequest(discardEdits))

    val transcriptInsightActions: TranscriptInsightActions
        get() = TranscriptInsightActions(save = ::saveTranscriptInsight, regenerate = ::regenerateTranscriptInsight)

    suspend fun downloadApk(context: Context, url: String, versionCode: Int): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val out = File(dir, "field-repository-v$versionCode.apk")
        val request = Request.Builder().url(url).get().build()
        storageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("The update didn't download. Try again.")
            response.body.byteStream().use { input -> FileOutputStream(out).use { output -> input.copyTo(output, 64 * 1024) } }
        }
        out
    }

    suspend fun artisans(workshopIds: List<String>? = null): List<ArtisanDto> =
        walkPages(EVERY_PAGE_BUDGET) { api.artisans(page = it, pageSize = 100, workshopIds = workshopIds.toQueryCsv()) }.items

    suspend fun artisansPage(
        workshopIds: List<String>? = null,
        pageBudget: Int = 1
    ): PageResponse<ArtisanDto> = coroutineScope {
        val scope = workshopIds.toQueryCsv()
        val first = api.artisans(page = 1, pageSize = LIST_PAGE_CEILING, workshopIds = scope)
        val lastPage = minOf(first.pages, pageBudget)
        if (lastPage <= 1) return@coroutineScope first
        val rest = (2..lastPage)
            .map { page -> async { api.artisans(page = page, pageSize = LIST_PAGE_CEILING, workshopIds = scope) } }
            .awaitAll()
        val rows = first.items + rest.flatMap { it.items }
        first.copy(items = rows, page = 1, pageSize = rows.size)
    }

    suspend fun artisansForCraftsPage(
        craftIds: List<String>,
        search: String? = null,
        page: Int = 1
    ): PageResponse<ArtisanDto> =
        api.artisans(
            page = page,
            pageSize = 100,
            craftId = craftIds.singleOrNull()?.blankToNull(),
            craftIds = craftIds.toQueryCsv(),
            search = search?.blankToNull()
        )

    suspend fun artisansSearchPage(
        search: String?,
        page: Int,
        workshopIds: List<String>? = null,
        completeness: String? = null
    ): PageResponse<ArtisanDto> =
        api.artisans(
            page = page,
            pageSize = 100,
            workshopIds = workshopIds.toQueryCsv(),
            search = search?.blankToNull(),
            completeness = completeness
        )

    suspend fun craftsSearchPage(search: String?, page: Int): PageResponse<CraftDto> =
        api.crafts(page = page, pageSize = 100, search = search?.blankToNull())

    suspend fun productsSearchPage(search: String?, page: Int, completeness: String? = null): PageResponse<ProductDetailDto> =
        api.products(page = page, pageSize = 100, search = search?.blankToNull(), completeness = completeness)

    suspend fun toolsSearchPage(search: String?, page: Int, completeness: String? = null): PageResponse<ToolDetailDto> =
        api.tools(page = page, pageSize = 100, search = search?.blankToNull(), completeness = completeness)

    suspend fun processesSearchPage(search: String?, page: Int): PageResponse<ProcessDetailDto> =
        api.processes(page = page, pageSize = 100, search = search?.blankToNull())

    suspend fun workshopsSearchPage(search: String?, page: Int, completeness: String? = null): PageResponse<WorkshopDetailDto> =
        api.workshops(page = page, pageSize = 100, search = search?.blankToNull(), completeness = completeness)

    suspend fun mediaSearchPage(search: String?, page: Int, filters: Map<String, String> = emptyMap()): PageResponse<MediaFileDto> =
        api.media(page = page, pageSize = 100, search = search?.blankToNull(), filters = filters)

    suspend fun interviewsSearchPage(
        workshopIds: List<String>?,
        search: String?,
        page: Int,
        completeness: String? = null
    ): PageResponse<QuestionnaireInterviewDetailDto> =
        api.interviews(
            page = page,
            pageSize = 100,
            workshopIds = workshopIds.toQueryCsv(),
            workshopId = workshopIds.singleWorkshopIdOrNull(),
            search = search?.blankToNull(),
            completeness = completeness
        )

    suspend fun artisansForWorkshopPage(workshopId: String, search: String? = null): PageResponse<ArtisanDto> =
        api.artisans(pageSize = 100, workshopId = workshopId.ifBlank { null }, search = search?.ifBlank { null })

    suspend fun artisanCardsEnabled(): Boolean = try {
        api.artisanCardStatus().enabled
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    suspend fun cachedArtisanRegister(): List<ArtisanDto> {
        val rows = mutableListOf<ArtisanDto>()
        for (page in 1..ARTISAN_PAGE_BUDGET) {
            val answer = try {
                api.artisansFromCache(page = page, pageSize = LIST_PAGE_CEILING)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                break
            }
            rows += answer.items
            if (page >= answer.pages) break
        }
        return rows
    }

    suspend fun resolveArtisanCard(input: String): ArtisanCardResolution =
        resolveArtisanCardWith(input, ::cachedArtisanRegister) { text ->
            api.resolveArtisanCard(ResolveArtisanCardRequest(input = text))
        }

    private suspend fun resolvePendingCards(context: Context, queued: PendingEntry): PendingCardOutcome {
        val bodies = listOf(queued.payloadJson) + queued.steps.map { it.payloadJson }
        if (bodies.none { ArtisanCardCode.pendingIn(it).isNotEmpty() }) return PendingCardOutcome.Resolved(queued.payloadJson)
        val resolve: suspend (String) -> ArtisanCardResolution = { short -> resolveArtisanCard(short) }
        val payload = when (val outcome = substitutePendingCards(queued.payloadJson, resolve)) {
            is PendingCardOutcome.Resolved -> outcome.payloadJson
            else -> return outcome
        }
        val steps = queued.steps.map { step ->
            when (val outcome = substitutePendingCards(step.payloadJson, resolve)) {
                is PendingCardOutcome.Resolved -> step.copy(payloadJson = outcome.payloadJson)
                else -> return outcome
            }
        }
        OfflineOutbox.update(context, queued.copy(payloadJson = payload, steps = steps))
        return PendingCardOutcome.Resolved(payload)
    }

    suspend fun craftsForWorkshopPage(workshopId: String, search: String? = null): PageResponse<CraftDto> =
        api.crafts(pageSize = 100, workshopId = workshopId.ifBlank { null }, search = search?.ifBlank { null })

    suspend fun productsForWorkshopPage(workshopId: String, search: String? = null): PageResponse<ProductDetailDto> =
        api.products(pageSize = 100, workshopId = workshopId.ifBlank { null }, search = search?.ifBlank { null })

    suspend fun toolsForWorkshopPage(workshopId: String, search: String? = null): PageResponse<ToolDetailDto> =
        api.tools(pageSize = 100, workshopId = workshopId.ifBlank { null }, search = search?.ifBlank { null })

    suspend fun processesForWorkshopPage(workshopId: String, search: String? = null): PageResponse<ProcessDetailDto> =
        api.processes(pageSize = 100, workshopId = workshopId.ifBlank { null }, search = search?.ifBlank { null })

    suspend fun craftCatalogue(context: Context): CraftCatalogue? =
        CraftCatalogueCache.refresh(context) { etag ->
            val response = api.craftCatalogue(ifNoneMatch = etag)
            when {
                response.code() == 304 -> CraftCatalogueCache.Fetch.NotModified
                response.isSuccessful -> CraftCatalogueCache.Fetch.Fresh(
                    body = response.body()?.string().orEmpty(),
                    etag = response.headers()["ETag"],
                )
                else -> throw HttpException(response)
            }
        }

    suspend fun craftLookup(context: Context): PageResponse<CraftDto> {
        val catalogue = craftCatalogue(context) ?: throw IllegalStateException(CraftCatalogueCache.OFFLINE_FIRST_RUN)
        val items = catalogue.crafts.map { it.asCraftDto(catalogue) }
        return PageResponse(items = items, total = items.size, page = 1, pageSize = items.size, pages = 1)
    }

    suspend fun <T> myRecordsOf(from: Int = 1, fetch: suspend (page: Int) -> PageResponse<T>): MyRecordsPage<T> =
        myRecordsWalk(from, fetch)

    suspend fun myArtisans(userId: String, from: Int = 1) = myRecordsOf(from) { api.artisans(page = it, pageSize = 100, createdBy = userId) }
    suspend fun myProducts(userId: String, from: Int = 1) = myRecordsOf(from) { api.products(page = it, pageSize = 100, createdBy = userId) }
    suspend fun myTools(userId: String, from: Int = 1) = myRecordsOf(from) { api.tools(page = it, pageSize = 100, createdBy = userId) }
    suspend fun myProcesses(userId: String, from: Int = 1) = myRecordsOf(from) { api.processes(page = it, pageSize = 100, createdBy = userId) }
    suspend fun myCrafts(userId: String, from: Int = 1) = myRecordsOf(from) { api.crafts(page = it, pageSize = 100, createdBy = userId) }
    suspend fun myWorkshops(userId: String, from: Int = 1) = myRecordsOf(from) { api.workshops(page = it, pageSize = 100, createdBy = userId) }
    suspend fun myInterviews(userId: String, from: Int = 1) = myRecordsOf(from) { api.interviews(page = it, pageSize = 100, createdBy = userId) }

    suspend fun allMine(userId: String): MyRecordsAll = coroutineScope {
        fun <T> every(fetch: suspend (Int) -> PageResponse<T>) = async { walkPages(EVERY_PAGE_BUDGET, fetch).items }
        val artisans = every { api.artisans(page = it, pageSize = 100, createdBy = userId) }
        val products = every { api.products(page = it, pageSize = 100, createdBy = userId) }
        val tools = every { api.tools(page = it, pageSize = 100, createdBy = userId) }
        val workshops = every { api.workshops(page = it, pageSize = 100, createdBy = userId) }
        val interviews = every { api.interviews(page = it, pageSize = 100, createdBy = userId) }
        MyRecordsAll(artisans.await(), products.await(), tools.await(), workshops.await(), interviews.await())
    }

    suspend fun unifiedSearch(query: String, limit: Int = 5): UnifiedSearchResponse =
        api.unifiedSearch(q = query.trim(), limit = limit)

    suspend fun unifiedSearchIn(query: String, limit: Int, scope: String, type: String? = null, page: Int? = null, sort: String? = null): UnifiedSearchResponse =
        api.unifiedSearch(q = query.trim(), limit = limit, scope = scope, type = type, page = page, sort = sort)

    suspend fun remoteRecord(type: String, id: String): RemoteRecord = api.remoteRecord(type, id)

    suspend fun searchCrafts(query: String, pageSize: Int = 10): List<CraftDto> {
        val catalogue = CraftCatalogueCache.catalogue.value ?: return emptyList()
        return CraftCatalogueCache.search(catalogue, query.trim()).take(pageSize).map { it.asCraftDto(catalogue) }
    }

    suspend fun searchProcesses(query: String, pageSize: Int = 10): List<ProcessDetailDto> =
        api.processes(pageSize = pageSize, search = query.trim()).items

    suspend fun searchInterviews(query: String, pageSize: Int = 10): List<QuestionnaireInterviewDetailDto> =
        api.interviews(pageSize = pageSize, search = query.trim()).items

    suspend fun products(): List<ProductDetailDto> = walkPages(EVERY_PAGE_BUDGET) { api.products(page = it, pageSize = 100) }.items

    suspend fun productsForArtisan(artisanId: String, artisanName: String? = null): List<ProductDetailDto> =
        api.products(pageSize = 100, artisanId = artisanId, artisanName = artisanName?.trim()?.ifBlank { null }).items

    suspend fun tools(): List<ToolDetailDto> = walkPages(EVERY_PAGE_BUDGET) { api.tools(page = it, pageSize = 100) }.items

    suspend fun toolArtisans(toolId: String): List<ArtisanDto> = api.toolArtisans(toolId)

    suspend fun assignToolArtisans(toolId: String, artisanIds: List<String>): List<ArtisanDto> =
        api.assignToolArtisans(toolId, ToolArtisanAssignRequest(artisanIds))

    suspend fun unassignToolArtisan(toolId: String, artisanId: String) = api.unassignToolArtisan(toolId, artisanId)

    suspend fun workshops(): List<WorkshopDetailDto> = walkPages(EVERY_PAGE_BUDGET) { api.workshops(page = it, pageSize = 100) }.items

    suspend fun workshopsByOccurrence(): List<WorkshopDetailDto> =
        workshops().sortedByDescending { it.occurrenceDate() }

    suspend fun workshopSubmissionCheck(workshopId: String): WorkshopSubmissionCheckDto? =
        runCatching { api.workshopSubmissionCheck(workshopId) }.getOrNull()

    suspend fun unmappedRecords(): WorkshopMappingPlanDto = api.unmappedRecords()

    suspend fun mapUnmappedRecords(): WorkshopMappingPlanDto = api.mapUnmappedRecords()

    suspend fun setUnmappedAuto(enabled: Boolean): WorkshopAutoMapDto =
        api.setUnmappedAuto(WorkshopAutoMapRequest(enabled))

    suspend fun createArtisan(body: ArtisanCreateRequest, idempotencyKey: String? = null): ArtisanDto =
        api.createArtisan(idempotencyKey, body)

    suspend fun artisan(id: String): ArtisanDetailDto = api.artisan(id)

    suspend fun checkDuplicates(body: DuplicateCheckRequest): List<DuplicateCandidateDto> =
        runCatching { api.checkDuplicates(body).candidates }.getOrDefault(emptyList())

    suspend fun answerPossibleDuplicate(context: Context, entryId: String, samePerson: Boolean) {
        val entry = OfflineOutbox.all(context).firstOrNull { it.id == entryId } ?: return
        if (!entry.isPossibleDuplicate()) return
        OfflineOutbox.update(context, if (samePerson) entry.asSamePerson() else entry.asDifferentPerson())
    }

    suspend fun completenessRulesText(): String = api.completenessRules().use { it.string() }

    suspend fun lookupArtisanByAadhaar(number: String): AadhaarLookupDto =
        api.lookupArtisanByAadhaar(AadhaarLookupRequest(number.trim()))

    suspend fun updateArtisan(id: String, body: ArtisanCreateRequest): ArtisanDetailDto = api.updateArtisan(id, body)

    suspend fun artisanQuestionnaire(id: String): ArtisanQuestionnaireDto = api.artisanQuestionnaire(id)

    suspend fun media(): List<MediaFileDto> = api.media(pageSize = 20).items

    suspend fun mediaList(): List<MediaFileDto> = walkPages(EVERY_PAGE_BUDGET) { api.media(page = it, pageSize = 100) }.items

    suspend fun mediaItem(id: String): MediaFileDto = api.getMedia(id)

    suspend fun deleteMedia(id: String) = api.deleteMedia(id)

    suspend fun deleteArtisan(id: String) = api.deleteArtisan(id)
    suspend fun deleteCraft(id: String) = api.deleteCraft(id)
    suspend fun deleteProduct(id: String) = api.deleteProduct(id)
    suspend fun deleteTool(id: String) = api.deleteTool(id)
    suspend fun deleteWorkshop(id: String) = api.deleteWorkshop(id)
    suspend fun deleteProcess(id: String) = api.deleteProcess(id)
    suspend fun deleteInterview(id: String) = api.deleteInterview(id)

    data class DatasetDownloadResult(
        val displayLocation: String,
        val saved: Int,
        val total: Int,
        val failed: Int,
        val truncated: Boolean = false
    )

    private suspend fun <T> readManifest(
        streamed: suspend () -> retrofit2.Response<okhttp3.ResponseBody>,
        buffered: suspend () -> Triple<List<T>, Int, Boolean>,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        spool: File,
        onEntry: suspend (entry: T, announcedTotal: Int) -> Unit
    ): ManifestOutcome {
        val response = streamed()
        val body = response.body()
        val ndjson = response.isSuccessful && body != null &&
            ManifestStream.isNdjson(response.headers()["Content-Type"])
        if (!ndjson) {
            runCatching { body?.close() }
            val (files, total, truncated) = buffered()
            for (entry in files) onEntry(entry, total)
            return ManifestOutcome(total = total, truncated = truncated, unreadable = 0)
        }
        val total = ManifestStream.count(response.headers()[ManifestStream.TOTAL_HEADER])
        val truncated = ManifestStream.flag(response.headers()[ManifestStream.TRUNCATED_HEADER])
        try {
            body!!.byteStream().use { input ->
                FileOutputStream(spool).use { out -> input.copyTo(out) }
            }
            var unreadable = 0
            spool.reader(Charsets.UTF_8).use { reader ->
                val lines = ManifestLines(reader, ApiClient.json, deserializer)
                for (entry in lines.entries()) onEntry(entry, total)
                unreadable = lines.unreadable
            }
            return ManifestOutcome(total = total, truncated = truncated, unreadable = unreadable)
        } finally {
            spool.delete()
        }
    }

    suspend fun downloadDataset(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit
    ): DatasetDownloadResult = withContext(Dispatchers.IO) {
        val zipName = Naming.exportFilename("Dataset", "Export", "zip", withTime = true, subject = "All Records")
        val tmp = File(context.cacheDir, zipName)
        var failed = 0
        var written = 0
        val outcome = ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
            readManifest(
                streamed = { api.datasetManifestStream() },
                buffered = {
                    val manifest = api.datasetManifest()
                    Triple(manifest.files, manifest.files.size, manifest.truncated)
                },
                deserializer = DatasetFileDto.serializer(),
                spool = File(context.cacheDir, "$zipName.manifest")
            ) { f, announced ->
                runCatching {
                    zip.putNextEntry(ZipEntry(f.path))
                    when {
                        f.content != null -> zip.write(f.content.toByteArray(Charsets.UTF_8))
                        f.url != null -> writeObject(f.url, zip)
                    }
                    zip.closeEntry()
                }.onFailure {
                    failed++
                    runCatching { zip.closeEntry() }
                }
                written++
                onProgress(written, if (announced >= 0) announced else written)
            }
        }
        val location = persistFileToDownloads(context, tmp, zipName, "application/zip")
        tmp.delete()
        DatasetDownloadResult(
            displayLocation = location,
            saved = written - failed,
            total = manifestTotal(outcome, written),
            failed = manifestTotal(outcome, written) - (written - failed),
            truncated = outcome.truncated
        )
    }

    private fun manifestTotal(outcome: ManifestOutcome, seen: Int): Int =
        if (outcome.total >= 0) maxOf(outcome.total, seen) else seen + outcome.unreadable

    suspend fun downloadReport(context: Context, path: String = "", status: String? = null): String = withContext(Dispatchers.IO) {
        val response = api.dataReport(format = "xlsx", path = path, status = status)
        if (!response.isSuccessful) throw IllegalStateException(
            errorBodyDetail(response.errorBody()?.string()) ?: "The report didn't download. Try again."
        )
        val body = response.body() ?: throw IllegalStateException("The report didn't download. Try again.")
        val name = Naming.exportFilename(
            "Dataset", "Report", "xlsx", withTime = true,
            subject = path.substringAfterLast('/').blankToNull() ?: "All Records",
        )
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val location = persistFileToDownloads(context, tmp, name, XLSX_MIME)
        tmp.delete()
        location
    }

    suspend fun downloadDataFolder(
        context: Context,
        path: String,
        include: String? = null,
        folderName: String? = null,
        status: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): DatasetDownloadResult = withContext(Dispatchers.IO) {
        val zipName = Naming.exportFilename(
            "Dataset", "Export", "zip", withTime = true,
            subject = (folderName ?: path.substringAfterLast('/')).blankToNull() ?: "All Records",
        )
        val tmp = File(context.cacheDir, zipName)
        var failed = 0
        var written = 0
        val outcome = ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
            readManifest(
                streamed = { api.dataManifestStream(path, include?.blankToNull(), status = status) },
                buffered = {
                    val manifest = api.dataManifest(path, include?.blankToNull(), status)
                    Triple(manifest.files, manifest.files.size, manifest.truncated)
                },
                deserializer = DataManifestFileDto.serializer(),
                spool = File(context.cacheDir, "$zipName.manifest")
            ) { f, announced ->
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
                written++
                onProgress(written, if (announced >= 0) announced else written)
            }
        }
        val location = persistFileToDownloads(context, tmp, zipName, "application/zip")
        tmp.delete()
        DatasetDownloadResult(
            displayLocation = location,
            saved = written - failed,
            total = manifestTotal(outcome, written),
            failed = manifestTotal(outcome, written) - (written - failed),
            truncated = outcome.truncated
        )
    }

    private suspend fun writeConvertedMedia(mediaId: String, sink: java.io.OutputStream): Boolean =
        runCatching {
            val response = api.downloadDataMedia(mediaId, "mp4")
            val body = response.body()
            if (!response.isSuccessful || body == null) return@runCatching false
            body.byteStream().use { it.copyTo(sink) }
            true
        }.getOrDefault(false)

    private suspend fun writeObject(url: String?, sink: java.io.OutputStream) {
        if (url.isNullOrBlank()) return
        if (url.startsWith("/api/")) {
            val response = api.apiPath(url)
            val body = response.body()
            if (!response.isSuccessful || body == null) throw IllegalStateException("The download didn't finish. Try again.")
            body.byteStream().use { it.copyTo(sink) }
            return
        }
        storageClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("The download didn't finish. Try again.")
            resp.body.byteStream().copyTo(sink)
        }
    }

    suspend fun downloadDataMedia(
        context: Context,
        mediaId: String,
        filename: String,
        format: String? = null
    ): String = withContext(Dispatchers.IO) {
        val response = api.downloadDataMedia(mediaId, format?.blankToNull())
        if (!response.isSuccessful) throw IllegalStateException("The download didn't finish. Try again.")
        val body = response.body() ?: throw IllegalStateException("The download didn't finish. Try again.")
        val name = filename.blankToNull()?.replace(Regex("[^A-Za-z0-9._-]+"), "_") ?: mediaId
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val mime = response.headers()["Content-Type"]?.substringBefore(';')?.trim().blankToNull()
            ?: "application/octet-stream"
        val location = persistFileToDownloads(context, tmp, name, mime)
        tmp.delete()
        location
    }

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

    suspend fun mediaForRecord(linkedRecordType: String, linkedRecordId: String): List<MediaFileDto> =
        walkPages(EVERY_PAGE_BUDGET) { api.media(page = it, pageSize = 100, linkedRecordType = linkedRecordType, linkedRecordId = linkedRecordId) }.items

    suspend fun processes(): List<ProcessDetailDto> = walkPages(EVERY_PAGE_BUDGET) { api.processes(page = it, pageSize = 100) }.items

    suspend fun process(id: String): ProcessDetailDto = api.process(id)

    suspend fun latestProcessForCraft(craftId: String, excludeId: String? = null): ProcessDetailDto? =
        api.processes(pageSize = 10, craftId = craftId).items.firstOrNull { it.id != excludeId && it.steps.isNotEmpty() }

    suspend fun createProcess(body: ProcessCreateRequest, idempotencyKey: String? = null): ProcessDetailDto =
        api.createProcess(idempotencyKey, body)

    suspend fun updateProcess(id: String, body: ProcessCreateRequest): ProcessDetailDto = api.updateProcess(id, body)

    suspend fun createCraft(body: CraftCreateRequest, idempotencyKey: String? = null): CreatedRecordDto =
        api.createCraft(idempotencyKey, body)

    suspend fun craft(id: String): CraftDto = api.craft(id)

    suspend fun updateCraft(id: String, body: CraftCreateRequest): CraftDto = api.updateCraft(id, body)

    suspend fun createWorkshop(body: WorkshopCreateRequest, idempotencyKey: String? = null): CreatedRecordDto =
        api.createWorkshop(idempotencyKey, body)

    suspend fun workshop(id: String): WorkshopDetailDto = api.workshop(id)

    suspend fun updateWorkshop(id: String, body: WorkshopCreateRequest): WorkshopDetailDto = api.updateWorkshop(id, body)

    suspend fun createProduct(body: ProductCreateRequest, idempotencyKey: String? = null): CreatedRecordDto =
        api.createProduct(idempotencyKey, body)

    suspend fun product(id: String): ProductDetailDto = api.product(id)

    suspend fun updateProduct(id: String, body: ProductCreateRequest): ProductDetailDto = api.updateProduct(id, body)

    suspend fun createTool(body: ToolCreateRequest, idempotencyKey: String? = null): CreatedRecordDto =
        api.createTool(idempotencyKey, body)

    suspend fun tool(id: String): ToolDetailDto = api.tool(id)

    suspend fun updateTool(id: String, body: ToolCreateRequest): ToolDetailDto = api.updateTool(id, body)

    suspend fun questionnaires(): List<QuestionnaireDto> = api.questionnaires()

    suspend fun questionnaireQuestions(questionnaireId: String? = null): List<QuestionnaireQuestionDto> =
        api.questionnaireQuestions(questionnaireId?.blankToNull())

    suspend fun questionnaireSections(questionnaireId: String? = null): List<QuestionnaireSectionDto> =
        api.questionnaireSections(questionnaireId?.blankToNull())

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

    suspend fun createQuestionnaireInterview(
        body: QuestionnaireInterviewCreateRequest,
        idempotencyKey: String? = null
    ): CreatedRecordDto = api.createQuestionnaireInterview(idempotencyKey, body)

    suspend fun interviews(workshopIds: List<String>?): List<QuestionnaireInterviewDetailDto> =
        interviewsPage(workshopIds).items

    suspend fun interviewsPage(
        workshopIds: List<String>?,
        pageBudget: Int = 1
    ): PageResponse<QuestionnaireInterviewDetailDto> =
        walkPages(pageBudget) { page ->
            api.interviews(
                page = page,
                pageSize = 100,
                workshopIds = workshopIds.toQueryCsv(),
                workshopId = workshopIds.singleWorkshopIdOrNull()
            )
        }

    suspend fun interviewsForArtisanInScope(artisanId: String, workshopIds: List<String>?): List<QuestionnaireInterviewDetailDto> =
        walkPages(LOOKUP_PAGE_BUDGET) { page ->
            api.interviews(
                page = page,
                pageSize = 100,
                artisanId = artisanId,
                workshopIds = workshopIds.toQueryCsv(),
                workshopId = workshopIds.singleWorkshopIdOrNull()
            )
        }.items

    suspend fun interviewsForArtisan(artisanId: String): List<QuestionnaireInterviewDetailDto> =
        api.interviews(pageSize = 100, artisanId = artisanId).items

    suspend fun interview(id: String): QuestionnaireInterviewDetailDto = api.interview(id)

    suspend fun updateQuestionnaireInterview(id: String, body: QuestionnaireInterviewUpdateRequest): QuestionnaireInterviewDetailDto =
        api.updateInterview(id, body)

    suspend fun mergeQuestionnaireInterviewInto(id: String, targetId: String): QuestionnaireInterviewDetailDto =
        api.mergeInterviewInto(id, targetId)

    suspend fun completionMatrix(
        artisanId: String? = null,
        workshopIds: List<String>? = null,
        questionnaireId: String? = null
    ): CompletionMatrixDto =
        api.completionMatrix(
            artisanId?.blankToNull(),
            workshopIds.toQueryCsv(),
            questionnaireId?.blankToNull()
        )

    suspend fun setCompletionCell(artisanId: String, sectionId: String, status: String?) =
        api.setCompletionCell(CompletionCellRequest(artisanId, sectionId, status))

    suspend fun consolidatedQuestionnaire(
        artisanId: String,
        workshopIds: List<String>? = null,
        questionnaireId: String? = null
    ): ConsolidatedQuestionnaireDto =
        api.consolidatedQuestionnaire(
            artisanId,
            workshopIds.toQueryCsv(),
            questionnaireId?.blankToNull()
        )

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
            ?: TranscribeChoices.processingFor(uri.toString(), mediaType)
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

    private suspend fun completeUpload(body: MediaCompleteRequest, checksum: String?): MediaFileDto {
        if (checksum == null) return api.completeMedia(body)
        val encoded = completeJson.encodeToJsonElement(MediaCompleteRequest.serializer(), body).jsonObject
        return api.completeMediaChecksummed(JsonObject(encoded + ("checksum" to JsonPrimitive(checksum))))
    }

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

    private data class UploadTarget(
        val objectKey: String,
        val bucket: String,
        val publicUrl: String?,
        val checksum: String?
    )

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
            StagedJournal.record(context, presign.objectKey)
            val digest = ContentDigest()
            withContext(Dispatchers.IO) {
                putToStorage(presign.uploadUrl, presign.headers, source.size, mimeType, source.open, onProgress, digest)
            }
            return UploadTarget(presign.objectKey, presign.bucket, presign.publicUrl, digest.hex())
        }
        return uploadMultipart(context, filename, mimeType, mediaType, source, linkedRecordType, linkedRecordId, onProgress)
    }

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
                        digest.update(bytes, bytes.size)
                        val url = partUrls[partNumber.toString()]
                            ?: throw IllegalStateException("The upload didn't finish. Try again.")
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
            withContext(NonCancellable) {
                runCatching {
                    api.abortMultipart(MultipartAbortRequest(create.objectKey, create.uploadId))
                    StagedJournal.drop(create.objectKey)
                }
            }
            throw t
        }
    }

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
            currentCoroutineContext().ensureActive()
            var expired = false
            try {
                val body = StreamingRequestBody(
                    bytes.size.toLong(),
                    null,
                    { java.io.ByteArrayInputStream(bytes) },
                    onProgress?.let { cb -> { sent, _ -> cb(sent) } }
                )
                executeCancellable(storageClient.newCall(Request.Builder().url(target).put(body).build())).use { response ->
                    if (response.isSuccessful) {
                        return response.header("ETag")
                            ?: throw IllegalStateException("The upload didn't finish. Try again.")
                    }
                    if (response.code == 403 && !refreshed) expired = true
                    else if (response.code < 500) throw IllegalStateException("The upload didn't finish. Try again.")
                    lastError = IllegalStateException("The upload didn't finish. Try again.")
                }
            } catch (e: IOException) {
                lastError = e
            }
            if (expired) {
                refreshed = true
                target = repesign() ?: throw (lastError ?: IllegalStateException("The upload didn't finish. Try again."))
                continue
            }
            failures++
            if (failures >= maxAttempts) break
            delay(800L * failures)
        }
        throw lastError ?: IllegalStateException("The upload didn't finish. Try again.")
    }

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
        val resolvedProcessing = processingRequests ?: emptyList()
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

    suspend fun deleteStaged(objectKey: String) {
        api.deleteMediaObject(objectKey)
        StagedJournal.drop(objectKey)
    }

    suspend fun sweepStagedObjects(context: Context): Int {
        if (!hasToken() || !ConnectivityObserver.isOnline(context)) return 0
        return StagedJournal.sweep(context) { objectKey, uploadId ->
            val abortSettled = uploadId == null || try {
                api.abortMultipart(MultipartAbortRequest(objectKey, uploadId))
                true
            } catch (e: HttpException) {
                true
            } catch (e: IOException) {
                false
            }
            val deleteSettled = try {
                api.deleteMediaObject(objectKey)
                true
            } catch (e: HttpException) {
                e.code() == 409 || e.code() == 403 || e.code() == 404
            } catch (e: IOException) {
                false
            }
            abortSettled && deleteSettled
        }
    }

    fun isOnline(context: Context): Boolean = ConnectivityObserver.isOnline(context)

    suspend fun pendingUploads(context: Context): Int =
        OfflineOutbox.all(context).replayableBy(currentUserId()).size

    suspend fun pendingEntries(context: Context): List<PendingEntry> =
        OfflineOutbox.all(context).replayableBy(currentUserId())

    suspend fun retypeOutboxAadhaar(context: Context, entryId: String, number: String) {
        check(AadhaarAtRest.canSeal()) {
            "This phone can't keep the number safely right now. Save the artisan again from its form while online."
        }
        if (OfflineOutbox.retypeAadhaar(context, entryId, number)) OutboxSyncWorker.schedule(context)
    }

    suspend fun retryRefused(context: Context, entryId: String) {
        OfflineOutbox.clearFailure(context, entryId)
        OutboxSyncWorker.schedule(context)
    }

    suspend fun fixRefused(context: Context, entryId: String, payloadJson: String): Boolean {
        val ok = OfflineOutbox.fixAndResend(context, entryId, payloadJson)
        if (ok) OutboxSyncWorker.schedule(context)
        return ok
    }

    suspend fun useExistingForRefused(context: Context, entryId: String): Boolean {
        val ok = OfflineOutbox.useExisting(context, entryId)
        if (ok) OutboxSyncWorker.schedule(context)
        return ok
    }

    suspend fun discardRefused(context: Context, entryId: String): Boolean = OfflineOutbox.discard(context, entryId)

    suspend fun queueOffline(
        context: Context,
        type: String,
        payloadJson: String,
        label: String,
        mediaUris: List<Uri>,
        recordName: String?,
        caption: String?,
        location: LocationRequest? = null,
        editOf: String? = null
    ) = queueOfflineEntry(
        context, type, payloadJson, label,
        mediaUris.mapIndexed { index, uri ->
            OfflineMediaSpec(uri = uri, caption = caption, recordName = recordName, batchIndex = index + 1, location = location)
        },
        kind = if (editOf != null) OutboxKind.UPDATE else OutboxKind.CREATE,
        targetId = editOf
    )

    suspend fun queueAttachments(
        context: Context,
        recordType: String,
        recordId: String,
        label: String,
        items: List<OfflineMediaSpec>
    ) = queueOfflineEntry(context, recordType, "", label, items, kind = OutboxKind.ATTACH, targetId = recordId)

    suspend fun queueSequence(
        context: Context,
        type: String,
        targetId: String,
        label: String,
        steps: List<OutboxStep>,
        items: List<OfflineMediaSpec>
    ) = queueOfflineEntry(context, type, "", label, items, kind = OutboxKind.SEQUENCE, targetId = targetId, steps = steps)

    suspend fun queueAction(context: Context, type: String, payloadJson: String, label: String, targetId: String? = null) =
        queueOfflineEntry(context, type, payloadJson, label, emptyList(), kind = OutboxKind.ACTION, targetId = targetId)

    suspend fun queueOfflineEntry(
        context: Context,
        type: String,
        payloadJson: String,
        label: String,
        items: List<OfflineMediaSpec>,
        kind: String = OutboxKind.CREATE,
        targetId: String? = null,
        idempotencyKey: String? = null,
        steps: List<OutboxStep> = emptyList()
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
                stepIndex = spec.stepIndex,
                location = spec.location
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
                createdAt = Instant.now().toString(),
                kind = kind,
                targetId = targetId,
                createdId = if (kind == OutboxKind.ATTACH) targetId else null,
                ownerUserId = currentUserId(),
                idempotencyKey = idempotencyKey ?: if (kind == OutboxKind.CREATE || kind == OutboxKind.ACTION) {
                    java.util.UUID.randomUUID().toString()
                } else {
                    null
                },
                steps = steps
            )
        )
        OutboxSyncWorker.schedule(context)
    }

    suspend fun syncOutbox(context: Context): Int {
        if (sweptStagedObjects.compareAndSet(false, true)) {
            AppScope.io.launch { runCatching { sweepStagedObjects(context) } }
        }
        return syncMutex.withLock {
            val queue = OfflineOutbox.all(context).replayableBy(currentUserId())
            OfflineOutbox.takeAlert()?.let { notifyUser(context, it) }
            if (!ConnectivityObserver.isOnline(context)) return@withLock 0
            var synced = 0
            for (queued in queue) {
                if (queued.createdId == null && AadhaarAtRest.numberWithheld(queued.payloadJson)) {
                    if (queued.failure != AadhaarAtRest.RETYPE_REASON) {
                        OfflineOutbox.markFailure(context, queued.id, AadhaarAtRest.RETYPE_REASON)
                        notifyUser(context, "\"${queued.label}\" needs the Aadhaar number again. Open the outbox to type it.")
                    }
                    continue
                }
                if (queued.failure != null) continue
                if (queued.createdId == null) {
                    when (val cards = resolvePendingCards(context, queued)) {
                        is PendingCardOutcome.Held -> {
                            OfflineOutbox.markFailure(context, queued.id, cards.reason)
                            notifyUser(context, "\"${queued.label}\" is held. ${cards.reason}")
                            continue
                        }
                        PendingCardOutcome.Retry -> return@withLock synced
                        is PendingCardOutcome.Resolved -> Unit
                    }
                }
                val current = OfflineOutbox.all(context).firstOrNull { it.id == queued.id } ?: queued
                when (val outcome = replayEntry(context, current)) {
                    ReplayOutcome.Synced -> {
                        OfflineOutbox.remove(context, queued)
                        synced++
                    }
                    is ReplayOutcome.Rejected -> {
                        OfflineOutbox.markFailure(
                            context,
                            queued.id,
                            outcome.reason,
                            fields = outcome.refusal?.fields.orEmpty(),
                            code = outcome.refusal?.code,
                            existingId = outcome.refusal?.existingId,
                            existingLabel = outcome.refusal?.existingLabel
                        )
                        notifyUser(context, "\"${queued.label}\" could not be uploaded. ${outcome.reason}")
                    }
                    is ReplayOutcome.Review -> {
                        OfflineOutbox.markReview(context, queued.id, outcome.reason)
                        notifyUser(context, "\"${queued.label}\" needs a look before it is sent. ${outcome.reason}")
                    }
                    is ReplayOutcome.PossibleDuplicate -> {
                        OfflineOutbox.markPossibleDuplicate(context, queued.id, outcome.candidates)
                        notifyUser(context, "\"${queued.label}\" may already be recorded. Open the outbox to check.")
                    }
                    ReplayOutcome.Retry -> return@withLock synced
                }
            }
            synced
        }
    }

    private sealed interface ReplayOutcome {
        data object Synced : ReplayOutcome

        data object Retry : ReplayOutcome

        data class Rejected(val reason: String, val refusal: OutboxRefusal? = null) : ReplayOutcome

        data class Review(val reason: String) : ReplayOutcome

        data class PossibleDuplicate(val candidates: List<OutboxDuplicateCandidate>) : ReplayOutcome
    }

    private suspend fun replayEntry(context: Context, queued: PendingEntry): ReplayOutcome {
        var entry = queued
        if (entry.createdId == null && entry.idempotencyKey == null &&
            (entry.kind == OutboxKind.CREATE || entry.kind == OutboxKind.ACTION)
        ) {
            entry = entry.copy(idempotencyKey = java.util.UUID.randomUUID().toString())
            OfflineOutbox.update(context, entry)
        }
        if (entry.createdId == null && entry.kind == OutboxKind.SEQUENCE) {
            for (index in entry.completedSteps until entry.steps.size) {
                try {
                    performStep(entry.steps[index])
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    return if (isTransient(e)) ReplayOutcome.Retry
                    else e.outboxRefusal("This change couldn't be saved.").let { refusal ->
                        ReplayOutcome.Rejected(
                            "Step ${index + 1} of ${entry.steps.size}: " + refusal.reason,
                            refusal
                        )
                    }
                }
                entry = entry.copy(completedSteps = index + 1)
                OfflineOutbox.update(context, entry)
            }
        }
        if (entry.createdId == null) {
            val created = try {
                when (entry.kind) {
                    OutboxKind.UPDATE -> updateFromEntry(entry)
                    OutboxKind.ACTION -> actionFromEntry(entry)
                    OutboxKind.ATTACH, OutboxKind.SEQUENCE -> CreatedRecord(
                        entry.targetId ?: throw SerializationException("Queued entry has no record id")
                    )
                    else -> createFromEntry(entry)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return when {
                    isTransient(e) -> ReplayOutcome.Retry
                    isKeyReused(e) -> ReplayOutcome.Review(
                        "This may already be saved. Check the list; if it isn't there, tap Send again. Photos stay on this phone."
                    )
                    else -> {
                        val fallback = "This record couldn't be saved."
                        val failure = e.apiFailure(fallback)
                        val held = failure.possibleDuplicates()
                        if (held != null && entry.kind == OutboxKind.CREATE && entry.type == "artisan") {
                            ReplayOutcome.PossibleDuplicate(held.toOutboxCandidates())
                        } else {
                            e.outboxRefusal(failure, fallback).let { ReplayOutcome.Rejected(it.reason, it) }
                        }
                    }
                }
            }
            entry = entry.copy(createdId = created.id, createdStepIds = created.stepIds)
            OfflineOutbox.update(context, entry)
        }

        val alreadyUp = entry.uploadedMedia.toMutableSet()
        val refused = mutableListOf<String>()
        val remaining = entry.media.withIndex().filterNot { (index, _) -> index in alreadyUp }
        val landed = ConcurrentLinkedQueue<FileOutcome>()

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
                    stopped = true
                    break
                }
                persistProgress()
            }
        } finally {
            withContext(NonCancellable) { persistProgress() }
        }
        if (stopped) return ReplayOutcome.Retry

        if (refused.isNotEmpty()) {
            return ReplayOutcome.Rejected(
                replayRefusedFilesSentence(
                    refused,
                    unlinked = entry.kind == OutboxKind.ATTACH && entry.targetId.isNullOrBlank()
                )
            )
        }
        return ReplayOutcome.Synced
    }

    private sealed interface FileOutcome {
        data class Uploaded(val index: Int) : FileOutcome
        data class Refused(val index: Int, val reason: String) : FileOutcome
    }

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
                            "\"${pm.originalFilename}\" wasn't attached — its process step no longer exists."
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
                        FileOutcome.Refused(index, "\"${pm.originalFilename}\": ${e.apiErrorMessage("couldn't be uploaded.")}")
                    )
                }
            }
        }
    }

    private fun linkTargetFor(entry: PendingEntry, pm: PendingMedia): Pair<String, String>? {
        val recordId = entry.createdId ?: return null
        val stepIndex = pm.stepIndex
        if (entry.type == "process" && stepIndex != null) {
            val stepId = entry.createdStepIds.getOrNull(stepIndex) ?: return null
            return "processstep" to stepId
        }
        return (pm.linkedType ?: entry.type) to recordId
    }

    private fun isKeyReused(error: Throwable): Boolean =
        error is HttpException && error.code() == 422 &&
            error.response()?.headers()?.get("Idempotency-Status") == "key-reused"

    fun isTransientFailure(error: Throwable): Boolean = isTransient(error)

    private fun isTransient(error: Throwable): Boolean = when (error) {
        is HttpException -> when (val code = error.code()) {
            401 -> true
            408, 429 -> true
            409 -> error.response()?.headers()?.get("Idempotency-Status") == "in-progress"
            else -> code >= 500
        }
        is IOException -> true
        is SerializationException -> false
        else -> true
    }

    private suspend fun notifyUser(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun outboxFailures(context: Context): List<PendingEntry> = OfflineOutbox.failed(context)

    private fun withIdentityAnswer(body: ArtisanCreateRequest): ArtisanCreateRequest =
        if (body.pehchanCardAvailable != null) body
        else body.copy(pehchanCardAvailable = body.pehchanCardNumber != null)

    private fun List<DuplicateCandidateDto>.toOutboxCandidates(): List<OutboxDuplicateCandidate> =
        take(5).map { OutboxDuplicateCandidate(it.artisan.id, it.artisan.name, it.artisan.place, it.artisan.craft) }

    internal data class CreatedRecord(val id: String, val stepIds: List<String> = emptyList())

    internal suspend fun createFromEntry(entry: PendingEntry): CreatedRecord = when (entry.type) {
        "artisan" -> CreatedRecord(
            api.createArtisan(
                entry.idempotencyKey,
                withIdentityAnswer(offlineJson.decodeFromString<ArtisanCreateRequest>(entry.payloadJson))
            ).id
        )
        "product" -> CreatedRecord(api.createProduct(entry.idempotencyKey, offlineJson.decodeFromString<ProductCreateRequest>(entry.payloadJson)).id)
        "tool" -> CreatedRecord(api.createTool(entry.idempotencyKey, offlineJson.decodeFromString<ToolCreateRequest>(entry.payloadJson)).id)
        "workshop" -> CreatedRecord(api.createWorkshop(entry.idempotencyKey, offlineJson.decodeFromString<WorkshopCreateRequest>(entry.payloadJson)).id)
        "craft" -> CreatedRecord(api.createCraft(entry.idempotencyKey, offlineJson.decodeFromString<CraftCreateRequest>(entry.payloadJson)).id)
        "questionnaire" -> CreatedRecord(
            api.createQuestionnaireInterview(
                entry.idempotencyKey,
                offlineJson.decodeFromString<QuestionnaireInterviewCreateRequest>(entry.payloadJson)
            ).id
        )
        "process" -> api.createProcess(entry.idempotencyKey, offlineJson.decodeFromString<ProcessCreateRequest>(entry.payloadJson))
            .let { detail -> CreatedRecord(detail.id, detail.steps.map { it.id }) }
        else -> throw IllegalStateException("Unknown offline entry type: ${entry.type}")
    }

    private suspend fun updateFromEntry(entry: PendingEntry): CreatedRecord {
        val id = entry.targetId ?: throw SerializationException("Queued edit has no record id")
        return when (entry.type) {
            "artisan" -> CreatedRecord(api.updateArtisan(id, offlineJson.decodeFromString<ArtisanCreateRequest>(entry.payloadJson)).id)
            "product" -> CreatedRecord(api.updateProduct(id, offlineJson.decodeFromString<ProductCreateRequest>(entry.payloadJson)).id)
            "tool" -> CreatedRecord(api.updateTool(id, offlineJson.decodeFromString<ToolCreateRequest>(entry.payloadJson)).id)
            "workshop" -> CreatedRecord(api.updateWorkshop(id, offlineJson.decodeFromString<WorkshopCreateRequest>(entry.payloadJson)).id)
            "craft" -> CreatedRecord(api.updateCraft(id, offlineJson.decodeFromString<CraftCreateRequest>(entry.payloadJson)).id)
            "process" -> api.updateProcess(id, offlineJson.decodeFromString<ProcessCreateRequest>(entry.payloadJson))
                .let { detail -> CreatedRecord(detail.id, detail.steps.map { it.id }) }
            else -> throw SerializationException("Unknown offline edit type: ${entry.type}")
        }
    }

    private suspend fun performStep(step: OutboxStep) {
        when (step.op) {
            "questionnaire.update" -> api.updateInterview(
                step.targetId ?: throw SerializationException("Queued interview edit has no interview id"),
                offlineJson.decodeFromString<QuestionnaireInterviewUpdateRequest>(step.payloadJson)
            )
            else -> throw SerializationException("Unknown queued step: ${step.op}")
        }
    }

    private suspend fun actionFromEntry(entry: PendingEntry): CreatedRecord = when (entry.type) {
        "comment" -> CreatedRecord(
            api.addEntryComment(entry.idempotencyKey, offlineJson.decodeFromString<EntryCommentBody>(entry.payloadJson)).id
        )
        "feedback" -> CreatedRecord(
            api.upsertMyFeedback(offlineJson.decodeFromString<FeedbackUpsertRequest>(entry.payloadJson)).id.ifBlank { entry.id }
        )
        "task-progress" -> CreatedRecord(
            api.updateTask(
                entry.targetId ?: throw SerializationException("Queued task update has no task id"),
                offlineJson.decodeFromString<TaskUpdateBody>(entry.payloadJson)
            ).id
        )
        "review-approve", "review-reject" -> {
            val decision = offlineJson.decodeFromString<QueuedReviewDecision>(entry.payloadJson)
            postReviewDecision(entry.type, decision, entry.idempotencyKey)
            CreatedRecord(decision.recordId)
        }
        else -> throw SerializationException("Unknown offline action: ${entry.type}")
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
        val resolvedProcessing = pm.processing ?: UploadTranscribe.legacy(pm.mediaType)
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
                linkedRecordId = linkedRecordId.blankToNull(),
                recordedAt = pm.capturedAt ?: Instant.now().toString(),
                location = pm.location,
                processingRequests = resolvedProcessing
            ),
            target.checksum
        )
        StagedJournal.drop(target.objectKey)
    }

    suspend fun analyzeMeasurement(context: Context, uri: Uri, dimension: String): MeasurementReading {
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
        return MeasurementReading(
            valueInches = response.analysis?.valueInches,
            marker = response.methodMarker,
        )
    }

    suspend fun analyzeMeasurementLengthBreadth(context: Context, uri: Uri): MeasurementReading {
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
        return MeasurementReading(
            lengthInches = analysis?.lengthInches,
            breadthInches = analysis?.breadthInches,
            marker = response.methodMarker,
        )
    }

    private suspend fun executeCancellable(call: Call): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

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
            currentCoroutineContext().ensureActive()
            try {
                val body = StreamingRequestBody(contentLength, mimeType.toMediaType(), openStream, onProgress, digest)
                val builder = Request.Builder().url(uploadUrl).put(body)
                headers.forEach { (name, value) -> builder.header(name, value) }
                executeCancellable(storageClient.newCall(builder.build())).use { response ->
                    if (response.isSuccessful) return
                    if (response.code < 500) {
                        throw IllegalStateException("The upload didn't finish. Try again.")
                    }
                    lastError = IllegalStateException("The upload didn't finish. Try again.")
                }
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < maxAttempts) delay(800L * attempt)
        }
        throw lastError ?: IllegalStateException("The upload didn't finish. Try again.")
    }

    private class UploadSource(val size: Long, val open: () -> InputStream, val cleanup: () -> Unit)

    private fun queryContentSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)

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

    private class ContentDigest {
        private val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun reset() = digest.reset()
        fun update(bytes: ByteArray, length: Int) = digest.update(bytes, 0, length)
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

private fun List<String>?.toQueryCsv(): String? =
    this?.mapNotNull { it.blankToNull() }?.distinct()?.takeIf { it.isNotEmpty() }?.joinToString(",")

private fun List<String>?.singleWorkshopIdOrNull(): String? =
    this?.mapNotNull { it.blankToNull() }
        ?.distinct()
        ?.singleOrNull()
        ?.takeIf { it != UNASSIGNED_WORKSHOP }

private val NAME_KIND_WORD = mapOf(
    "IMAGE" to "Photo",
    "VIDEO" to "Video",
    "AUDIO" to "Recording",
    "PDF" to "Document",
    "DOCUMENT" to "Document"
)

private val NAME_RECORD_TYPE_WORD = mapOf(
    "artisan" to "Artisan",
    "product" to "Product",
    "tool" to "Tool",
    "process" to "Process",
    "processstep" to "Process",
    "workshop" to "Workshop",
    "craft" to "Craft"
)

private val CAPTION_QUESTION = Regex("""^question\s+audio:\s*([A-Za-z]{1,3})\s*(\d+)\b""", RegexOption.IGNORE_CASE)

private val CAPTION_SECTION = Regex("""^section\s+audio:\s*([A-Za-z]{1,3})\b""", RegexOption.IGNORE_CASE)

private val CAPTION_PRE_PROCESS = Regex("""^pre-process\s+media\b""", RegexOption.IGNORE_CASE)

private val CAPTION_RECORD_NAME = Regex(
    """^(?:field media|pre-process media|process stage step \d+|measurement grid image)\s+for\s+(.+)$""",
    RegexOption.IGNORE_CASE
)

private val SEGMENT_STEP = Regex("""^STEP[_-](\d+)""", RegexOption.IGNORE_CASE)

private val CAMEL_BOUNDARY = Regex("""(?<=[a-z0-9])(?=[A-Z])""")

private val NON_LETTERS = Regex("[^a-z]")

private data class QuestionnaireClip(
    val section: String?,
    val answer: Int?,
    val name: String,
    val stamp: String,
    val clip: Int?
)

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
    val extension = Naming.splitExtension(originalName.substringAfterLast('/')).second

    val base = overrideBaseName.blankToNull()
    if (base != null && base.startsWith("${NAMING_PORTAL}_")) return Naming.asciiLeaf(base + extension)
    val clip = base?.let(::parseQuestionnaireClip)

    val tag = recordType?.trim()?.lowercase().orEmpty()
    val (what, sequence) = if (clip != null) {
        interviewClipWhat(clip, caption, mediaType, index)
    } else {
        mediaWhat(recordType, mediaType, index, stageStep, customSegment, caption, originalName)
    }
    val supplied = recordName.blankToNull()
        ?: captionRecordName(caption)
        ?: clip?.name
        ?: base
        ?: splitCamel(originalName.substringBeforeLast('.'))
    val subject = trimRedundantTail(supplied, what)
    val isInterview = clip != null || tag == "questionnaire" || tag == "questionnaireinterview"
    return Naming.recordingFilename(
        entity = if (isInterview) "Interview" else NAME_RECORD_TYPE_WORD[tag],
        ext = extension,
        capturedAt = clip?.let { stampInstant(it.stamp) } ?: java.time.Instant.now(),
        craft = if (tag == "craft") subject else null,
        subject = if (tag == "craft") null else subject,
        what = what,
        sequence = sequence,
    )
}

private fun stampInstant(stamp: String): java.time.Instant? = runCatching {
    java.time.LocalDateTime.parse(stamp, java.time.format.DateTimeFormatter.ofPattern("ddMMyyyyHHmm"))
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
}.getOrNull()

private fun twoDigits(index: Int): String = index.coerceAtLeast(1).toString().padStart(2, '0')

private fun mediaWhat(
    recordType: String?,
    mediaType: String,
    index: Int,
    stageStep: Int?,
    customSegment: String?,
    caption: String?,
    originalName: String
): Pair<String, String> {
    val kind = nameKindWord(mediaType)
    val tag = recordType?.trim()?.lowercase().orEmpty()

    if (tag == "questionnaire" || tag == "questionnaireinterview") {
        val (section, answer) = interviewSectionAnswer(caption) ?: return kind to twoDigits(index)
        return kind to (if (answer != null) Naming.questionSequence(section, answer) else Naming.sectionSequence(section))
    }

    gridDescriptor(customSegment, caption, originalName)?.let { return it to twoDigits(index) }

    val step = stageStep ?: SEGMENT_STEP.find(customSegment.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    if (step != null || tag == "processstep") {
        return "${step?.let { "Step-$it" } ?: "Step"}-$kind" to twoDigits(index)
    }

    val isPreProcess = customSegment?.trim().equals("PRE", ignoreCase = true) ||
        CAPTION_PRE_PROCESS.containsMatchIn(caption.orEmpty().trim())
    if (isPreProcess) return "Pre-Process-$kind" to twoDigits(index)

    return kind to twoDigits(index)
}

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

private fun interviewSectionAnswer(caption: String?): Pair<String, Int?>? {
    val text = caption.orEmpty().trim()
    CAPTION_QUESTION.find(text)?.let { return it.groupValues[1].uppercase() to it.groupValues[2].toIntOrNull() }
    CAPTION_SECTION.find(text)?.let { return it.groupValues[1].uppercase() to null }
    return null
}

private fun interviewClipWhat(
    clip: QuestionnaireClip,
    caption: String?,
    mediaType: String,
    index: Int
): Pair<String, String> {
    val (section, answer) = interviewSectionAnswer(caption) ?: (clip.section to clip.answer)
    val kind = nameKindWord(mediaType)
    if (section == null) return kind to twoDigits(index)
    val sequence = if (answer != null) Naming.questionSequence(section, answer, take = clip.clip) else Naming.sectionSequence(section)
    return kind to sequence
}

private fun nameKindWord(mediaType: String): String = NAME_KIND_WORD[mediaType.uppercase()] ?: "File"

private fun parseQuestionnaireClip(base: String): QuestionnaireClip? {
    val tokens = base.trim().split('_')
    if (tokens.size < 5) return null
    if (tokens[3].length != 6 || !tokens[3].all(Char::isDigit)) return null
    val stamp = tokens[4].takeIf { it.length == 12 && it.all(Char::isDigit) } ?: return null
    val section = tokens[0].takeIf { it.isNotBlank() && !it.equals("SEC", ignoreCase = true) }
    return QuestionnaireClip(
        section = section,
        answer = tokens[1].toIntOrNull(),
        name = tokens[2],
        stamp = stamp,
        clip = tokens.getOrNull(5)?.toIntOrNull()?.takeIf { it > 1 }
    )
}

private fun captionRecordName(caption: String?): String? =
    CAPTION_RECORD_NAME.find(caption.orEmpty().trim())?.groupValues?.get(1)?.blankToNull()

private fun splitCamel(value: String?): String = CAMEL_BOUNDARY.replace(value.orEmpty(), " ")

private fun nameLetters(value: String?): String = value.orEmpty().lowercase().replace(NON_LETTERS, "")

private fun trimRedundantTail(recordName: String, what: String): String {
    if (!what.startsWith("Grid-Measurement")) return recordName
    val trimmed = recordName.trim()
    if (!nameLetters(trimmed).endsWith("measurementgrid")) return trimmed
    val cut = trimmed.lowercase().lastIndexOf("measurement")
    return if (cut > 0) trimmed.substring(0, cut).trim() else trimmed
}

internal fun replayRefusedFilesSentence(refused: List<String>, unlinked: Boolean): String {
    val reasons = refused.distinct().joinToString(" ")
    return if (unlinked) "${countNoun(refused.size, "file")} didn't upload: $reasons"
    else "Saved, but ${countNoun(refused.size, "file")} didn't upload: $reasons Attach them again."
}

internal fun CatalogueCraft.asCraftDto(catalogue: CraftCatalogue): CraftDto = CraftDto(
    id = id,
    name = name,
    localName = localName,
    category = CraftCatalogueCache.categoryName(catalogue, this).ifBlank { null },
    slug = slug.ifBlank { null },
    categoryId = categoryId ?: catalogue.subCategoryParent(subCategoryId),
    subCategoryId = subCategoryId,
    sourceSerial = sourceSerial,
    stateOfOrigin = stateOfOrigin,
    stateOfPractice = stateOfPractice,
    giStatus = giStatus,
    status = status ?: "ACTIVE",
)
