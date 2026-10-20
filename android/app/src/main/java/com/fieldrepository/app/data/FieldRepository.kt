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
 * THE ONE ERROR-MESSAGE MAPPER. Every screen turns a failure into words through this (or
 * [apiFailure] when it also needs the code), and it never hands a person platform text: no
 * "HTTP 403 Forbidden", no "Unable to resolve host api.example.org". What it does hand them:
 *
 * - the sentence the API meant a person to read, when the server sent one;
 * - [CONNECTION_FAILED_SENTENCE] when the request never got an answer (no signal, DNS, a dropped
 *   socket, a timeout) — still the clue that the save never landed, in words a researcher can act on;
 * - a plain sentence per status for a body-less 401/403/404, and the caller's [fallback] for anything
 *   else, never Retrofit's status line. The screen pairs it with a Try again where retrying helps.
 *
 * The designer portal carries the same mapper under the same name; keep the two copyable.
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
fun Throwable.apiErrorMessage(fallback: String): String = userMessage(fallback)

/**
 * A failed request, read ONCE: the sentence for the person, plus the stable code when the server
 * sent one.
 *
 * WHY THE CODE IS SEPARATE FROM THE SENTENCE. The sign-in gate answers a refused caller with
 * `{"detail": {"code": "ACCESS_PENDING", "message": "…"}}` precisely so a client can branch on
 * something that is not English prose — the sign-in screen has to tell "you are waiting for an
 * administrator" apart from "wrong email or password" and draw them differently, and matching on
 * the sentence would break the first time somebody improves the wording. [message] is still the
 * server's own text, verbatim; nothing here composes a replacement.
 *
 * IT MUST BE ONE FUNCTION AND NOT TWO. Retrofit buffers the error body and reading it CONSUMES the
 * buffer, so a `code` reader called beside [apiErrorMessage] would get an empty body — whichever
 * ran second would silently see nothing. Hence one read, both answers.
 */
data class ApiFailure(
    /** e.g. `ACCESS_PENDING`. Null for a plain-string detail, a 422 list, or a transport failure. */
    val code: String?,
    /** What to show the person: the API's own sentence, else the platform's, else the fallback. */
    val message: String,
    /**
     * THE REST OF THE OBJECT DETAIL, kept because reading the body twice is not available.
     *
     * The same "one read, both answers" argument the two fields above are held together by, arriving
     * a third time and harder. `detail` is not always just a code and a sentence: the artisan-set 409
     * carries `holder: {id, title}` — the interview that already covers this set — and the merge
     * route's 409 carries `questions: [{questionId, sectionCode, prompt}]`, every question the two
     * sittings answer differently. A screen cannot offer to move an interview into a record it
     * cannot name, or list what has to be reconciled, without those; and it cannot go back for them,
     * because [Throwable.apiFailure] has already consumed the buffered error body by the time the
     * caller sees this object.
     *
     * NULL for a plain-string detail, a 422 validation list, or a transport failure — the shapes
     * that have nothing beyond their sentence. Readers are the typed extensions below; nothing
     * outside this file should be poking at raw JSON.
     */
    val detail: JsonObject? = null,
    /**
     * The body fields the refusal named — a 422's `loc` paths, or an object detail's `field` — so a
     * refused outbox entry can open with them highlighted (B1). Empty when nothing was named.
     */
    val fields: List<String> = emptyList()
)

/** The 409 code `POST /artisans` answers when the new artisan looks like one already recorded. */
const val POSSIBLE_DUPLICATE_CODE = "artisan_possible_duplicate"

/**
 * The likely duplicates a held create names, or null when this failure was something else. Read off
 * [ApiFailure.detail], because the error body has already been consumed by [Throwable.apiFailure].
 */
fun ApiFailure.possibleDuplicates(): List<DuplicateCandidateDto>? {
    if (code != POSSIBLE_DUPLICATE_CODE) return null
    val list = detail?.get("candidates") as? JsonArray ?: return emptyList()
    return list.mapNotNull { element ->
        runCatching { errorBodyJson.decodeFromJsonElement(DuplicateCandidateDto.serializer(), element) }.getOrNull()
    }
}

/**
 * A refusal read ONCE for the outbox (B1): the sentence [userMessage] would show, plus what the
 * tray needs to offer more than Try again — the named fields, the code, and on an artisan clash the
 * artisan already on the register.
 */
data class OutboxRefusal(
    val reason: String,
    val fields: List<String> = emptyList(),
    val code: String? = null,
    val existingId: String? = null,
    val existingLabel: String? = null
)

/** The artisan identity clash's code — `_identity_conflict` in `backend/app/api/routes/artisans.py`. */
const val ARTISAN_IDENTITY_CONFLICT_CODE = "artisan_identity_conflict"

fun Throwable.outboxRefusal(fallback: String): OutboxRefusal = outboxRefusal(apiFailure(fallback), fallback)

/** [outboxRefusal] over a [failure] already read from this throwable (an error body reads once). */
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

/**
 * The 409 code a PATCH raises when another interview already covers this exact set of artisans —
 * `_DUPLICATE_SET_CODE` in `backend/app/api/routes/questionnaire.py`.
 *
 * BRANCH ON THIS AND NEVER ON THE PROSE, which is the instruction that constant's own comment
 * gives: the sentence is written for a human, has been reworded once already, and a client matching
 * on "already exists" silently stops offering the move the next time somebody improves it.
 */
const val DUPLICATE_ARTISAN_SET_CODE = "artisan_set_taken"

/** The 409 code the merge route raises when both interviews answer a question differently. */
const val MERGE_ANSWER_CONFLICT_CODE = "merge_answer_conflict"

/**
 * The interview that already holds this artisan set, off a [DUPLICATE_ARTISAN_SET_CODE] refusal.
 *
 * NULL IS A REAL AND EXPECTED ANSWER, not only the sign of an old server: the route sends
 * `holder: null` when the holder cannot be identified (a concurrent delete, or a null set key,
 * which is not deduped). A caller that gets null falls back to printing [ApiFailure.message], which
 * is what every screen did before this existed — the offer is an addition to that sentence and
 * never a replacement for it.
 *
 * The title is left null when the holder is untitled, which the server deliberately keeps
 * distinguishable from having no holder at all. `ui/mergeOfferQuestion` is where that becomes words.
 */
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

/** One question the two interviews answer differently, as the merge route's 409 names it. */
data class MergeAnswerConflict(
    val questionId: String,
    val sectionCode: String? = null,
    val prompt: String? = null
)

/**
 * Every question a refused merge named, in the order the server sorted them (section code, then
 * prompt) — that order is the one a researcher reads a questionnaire in, and re-sorting here would
 * put the list out of step with the paper it is being checked against.
 *
 * EMPTY FOR ANY OTHER FAILURE, including a merge that failed for a reason with no question list
 * (422 for a cross-instrument merge, 403, a dropped connection). A caller that treats "empty" as
 * "not this kind of refusal" and falls back to [ApiFailure.message] is behaving correctly.
 */
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

/**
 * What a reader is told when a request never got an answer: no signal, DNS, a dropped socket, a
 * timeout. The platform's own text for these names hosts and sockets and tells the reader nothing
 * they can act on. Same constant, same words, as the designer portal's.
 */
const val CONNECTION_FAILED_SENTENCE = "Could not connect. Check your connection and try again."

/** What became of a review decision sent from this phone. */
sealed interface ReviewSend {
    /** The server decided. [mediaApproved] files went with an approval; [status] is the record's now. */
    data class Sent(val status: String? = null, val mediaApproved: Int = 0) : ReviewSend

    /** No answer: kept in the outbox, sent once the connection returns. */
    data object Queued : ReviewSend
}

/**
 * Whether a review decision got NO answer, so it may wait in the outbox: no signal, a dropped socket,
 * or the gateway giving up on the slow box. Any answer from the API itself (a 403, a 422) is final.
 */
internal fun isUnanswered(error: Throwable): Boolean =
    error is IOException || error.isTransportFailure() ||
        (error is HttpException && error.code() in setOf(502, 503, 504))

/**
 * Did this request fail on the way, with nobody answering it? The platform's own network exception
 * types, plus OkHttp's plain `IOException` that wraps a stream cut off mid-answer — and, for an
 * exception re-thrown as a plain one, the platform's own wording.
 */
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

/** "HTTP 403 Forbidden", "Not Found", a stack-trace class name — text that is not for a person. */
internal fun looksLikeProtocolText(text: String): Boolean {
    val t = text.trim()
    if (t.isBareReasonPhrase()) return true
    return Regex("""^(java|javax|kotlin|kotlinx|okhttp3|retrofit2|android)\.[\w.$]+""").containsMatchIn(t)
}

fun Throwable.apiFailure(fallback: String): ApiFailure {
    if (isTransportFailure()) return ApiFailure(null, CONNECTION_FAILED_SENTENCE)
    // A payload that would not parse: the parser's message quotes the payload.
    if (this is SerializationException) return ApiFailure(null, fallback)
    val own = message?.trim()?.takeIf { it.isNotEmpty() && !looksLikeProtocolText(it) }
    // Not an HTTP failure at all: the app's own exceptions carry a sentence written for the reader.
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
    // FastAPI's own 404/405 body is the literal reason phrase; that is protocol, not a sentence.
    val said = detailMessage(detail)?.takeUnless { looksLikeProtocolText(it) }
    return ApiFailure(code, said ?: plain, detail as? JsonObject, refusalFieldPaths(detail))
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

/** Rows per page of the review queue — the web's page size. */
const val REVIEW_PAGE_SIZE = 20

/** Pages of 100 My Activity walks per record type before it says the list was cut. */
const val MY_RECORDS_PAGE_BUDGET = 20

/** One record type's rows for My Activity, and whether the page budget cut it short. */
data class MyRecordsPage<T>(val items: List<T>, val truncated: Boolean)

/** Files in flight at once. Matches the web's UPLOAD_CONCURRENCY; see docs/MEDIA_PIPELINE.md. */
private const val UPLOAD_CONCURRENCY = 3

/**
 * "EVERY WORKSHOP, AND I MEAN IT" — the argument a caller passes to [FieldRepository.interviews] and
 * [FieldRepository.interviewsPage] when the screen it is on has no workshop control to narrow by.
 *
 * It is `null`, which is exactly what the parameter's own default used to be, and that is the whole
 * reason it exists. A default is invisible at the call site: a bare `interviews()` looked identical
 * whether somebody had decided the list should span the repository or had simply never thought about
 * it, and the second kind is what *"the questionnaire from the previous workshop are showing up even
 * in the third workshop"* was made of. Spelled out, the decision is in the diff, the compiler finds
 * every site the day the parameter changes again, and a reviewer can ask the only useful question —
 * *is this screen really repository-wide?* — of a line that is actually making the claim.
 *
 * NOT `emptyList()`. The server treats null and empty the same (`toQueryCsv` drops both), so the
 * choice is a spelling and not a behaviour; null is chosen because `workshopIds = null` is what the
 * route documents as "no scope", and a reader of the API and a reader of this file should not have
 * to reconcile two ways of saying nothing.
 */
internal val EVERY_WORKSHOP: List<String>? = null

/**
 * THE LARGEST PAGE ANY LIST ROUTE IN THIS APPLICATION WILL SERVE.
 *
 * `normalize_pagination` does `min(page_size, MAX_PAGE_SIZE)` with `MAX_PAGE_SIZE = 100`
 * (`backend/app/services/pagination.py`) and the list routes declare `Query(20, ge=1, le=100)` on top
 * of that, so 100 is refused-past and not a tunable. Named rather than repeated as a literal so that
 * the day the server raises it there is one number to change and a grep that finds every caller.
 *
 * The web's twin is `LIST_PAGE_CEILING` in `frontend/components/data/cappedList.ts`, same value, same
 * paragraph.
 */
internal const val LIST_PAGE_CEILING = 100

/**
 * HOW MANY PAGES OF A WORKSHOP'S ARTISAN ROSTER THIS CLIENT WILL WALK — and why it walks any.
 *
 * ── THE DEFECT THIS CLOSES ─────────────────────────────────────────────────────────────────────
 *
 * [FieldRepository.artisansPage] was one request for one page, so the questionnaire form on the
 * handset offered at most 100 artisans for a workshop while the browser offered 500: the web's
 * `useWorkshopArtisans` has paged to `ARTISAN_PAGE_BUDGET = 5` since the scoping landed
 * (`frontend/components/questionnaires/interviewArtisans.ts`). Both clients printed an honest cut
 * notice, so neither was lying — they were simply OFFERING DIFFERENT PEOPLE for the same workshop,
 * which is the first thing the parity requirement names. A researcher could link an interview to the
 * 118th artisan from a laptop and could not link it to them at all from the phone they were holding
 * inside the workshop.
 *
 * `/artisans` is ordered `createdAt desc`, so the hundred a single request keeps are the NEWEST
 * hundred: the cut fell in a different place on each client on each day, which is why nobody caught
 * it by looking.
 *
 * ── WHY FIVE ────────────────────────────────────────────────────────────────────────────────────
 *
 * It is the web's number, and it must stay the web's number or the two clients start disagreeing
 * again one row at a time. Five pages is 500 artisans, which covers the largest table the sibling
 * deployment has ever counted behind this picker (749 artisans across EVERY workshop; one workshop's
 * share of that is far smaller — see `frontend/components/data/cappedList.ts` for the census) while
 * bounding the worst case at five requests. Pages two onward go out together, so it is two round
 * trips and not five.
 *
 * RAISING THIS ALONE WOULD FIX NOTHING. That is `ui/RecordPickers.listCutNotice`'s standing rule and
 * the reason the notice ships beside it: moving a cut is not telling anybody where the cut is.
 */
internal const val ARTISAN_PAGE_BUDGET = 5

/**
 * PAGES A BOUNDED WALK STOPS AT — 20 pages of 100.
 *
 * The record pickers used to walk this many pages of their table up front (2,000 rows) and filter the
 * copy on the device. They no longer do: since DC-A09 a picker holds one page and sends what is typed
 * to the list route's `search`, with "Load more" for the next page (`ui/ServerSearch.kt`). What still
 * walks this budget is a question bounded by its own filter — one artisan's interviews, for example.
 * Pages are fetched one after another, not all at once, so a large answer never bursts the API.
 */
internal const val LOOKUP_PAGE_BUDGET = 20

/** Walk [budget] pages of a list, sequentially, into one envelope whose `total` is the server's. */
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
    // The sync lock is process-wide, not per instance: the background worker (data/OutboxSyncWorker.kt)
    // builds its own repository, and two passes replaying the same snapshot would create every
    // queued record twice.
    private val syncMutex: Mutex get() = outboxSyncMutex
    private val sweptStagedObjects = java.util.concurrent.atomic.AtomicBoolean(false)

    fun hasToken(): Boolean = !tokenStore.getToken().isNullOrBlank()

    /** Last known signed-in profile, used for instant, persistent login across resumes. */
    fun cachedUser(): UserDto? = tokenStore.getUser()

    /**
     * A first factor's answer, taken: a session is stored and its account returned; a two-step
     * challenge is returned with NOTHING stored — nobody is signed in until [verifyTwoFactor].
     */
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

    /** Microsoft or Yahoo, once AppAuth has brought the code back. The backend redeems it. */
    suspend fun loginWithOidc(body: OidcLoginRequest): SignInOutcome = open(api.oidcLogin(body))

    /** The second step: the challenge from a first factor and a 6-digit or recovery code. */
    suspend fun verifyTwoFactor(challengeToken: String, code: String): UserDto {
        val response = api.verifyTwoFactor(TwoFactorVerifyRequest(challengeToken = challengeToken, code = code.trim()))
        tokenStore.setToken(response.accessToken)
        tokenStore.setUser(response.user)
        return response.user
    }

    suspend fun twoFactorStatus(): TwoFactorStatusDto = api.twoFactorStatus()

    suspend fun beginTwoFactorSetup(): TwoFactorSetupDto = api.beginTwoFactorSetup()

    /** Discard an unconfirmed setup: Cancel, Back, leaving the screen, or coming back after a long while. */
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

    /* ══════════════════════════════════════════════════════════════════════════════════════════
     * PASSWORDS — ported from the Design Prototype Workshop's `WorkshopRepository`, same names.
     *
     * NONE OF THESE IS EVER QUEUED. A password write replayed hours later out of an outbox would set
     * a credential its owner had long since replaced, from a device they may no longer be holding.
     * Every one throws, and the screen says what happened.
     * ══════════════════════════════════════════════════════════════════════════════════════════ */

    /**
     * Mark the cached profile as owing a new password, for a `GET /me` that came back gated. Written
     * to the store so a relaunch with no signal shows the same gate. Null when there is no profile.
     */
    fun holdForPasswordChange(): UserDto? {
        val held = cachedUser()?.copy(mustChangePassword = true) ?: return null
        tokenStore.setUser(held)
        return held
    }

    /**
     * Replace this account's own password. The call `mustChangePassword` sends somebody to.
     *
     * THE ANSWER'S TOKEN IS ADOPTED FIRST: the write retires every session opened with the old
     * password, this one included, and the fresh token arrives in [SESSION_TOKEN_HEADER]. A blank or
     * absent header leaves the store as it was. The profile is then re-read, best-effort; if that
     * fails the flag is cleared in the store anyway, because the server cleared it in the same write.
     */
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

    /** Mint a set-password link for [userId]. Shown once; never stored, logged or cached here. */
    suspend fun issuePasswordLink(userId: String): IssuedPasswordLinkDto =
        api.issuePasswordLink(IssuePasswordLinkRequest(userId = userId))

    /** Withdraw a link that has not been used. */
    suspend fun revokePasswordLink(linkId: String) {
        api.revokePasswordLink(linkId)
    }

    /** Is a link still good? A thrown check means "unknown", never "dead" — the caller offers the form. */
    suspend fun checkPasswordLink(token: String): PasswordLinkCheckDto =
        api.checkPasswordLinkInBody(PasswordLinkCheckRequest(token = token))

    /**
     * Redeem a link. It signs THIS device out too: the redemption ends every session of the account
     * server-side, and a dead token left in the store would give the next screen an unexplained 401.
     */
    suspend fun setPasswordWithLink(token: String, password: String) {
        api.setPasswordWithLink(SetPasswordRequest(token = token, password = password))
        logout()
    }

    /* ══════════════════════════════════════════════════════════════════════════════════════════
     * GETTING BACK IN — see data/AccountRecovery.kt for the routes. Like the ones above, every one of
     * these throws and none is ever queued: a password write replayed later from an outbox would set
     * a credential its owner had long since replaced.
     * ══════════════════════════════════════════════════════════════════════════════════════════ */

    private val recoveryApi: AccountRecoveryApi by lazy {
        ApiClient.retrofit(tokenStore).create(AccountRecoveryApi::class.java)
    }

    /** "Forgot your password?" — the server's one answer, printed as it is. Works signed out. */
    suspend fun forgotPassword(email: String): String =
        recoveryApi.forgotPassword(ForgotPasswordRequest(email = email.trim().lowercase())).message

    /**
     * Change this account's password — or, for an account with none ([currentPassword] null), set its
     * first one. The change goes through [changeOwnPassword] untouched. The first password takes the
     * same answer: the fresh session token from [SESSION_TOKEN_HEADER] is adopted before the profile
     * is re-read, because the write retires the token this call was made with.
     *
     * A first password is sent with [proof], the provider sign-in run again (ProviderReauth.kt). A 400
     * carrying [CONFIRM_SIGN_IN_HEADER] is thrown as [ConfirmSignInRequired] naming the provider, so
     * the screen can drop the confirmation and offer that provider's button again.
     */
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

    /** "Email them a password link": queued to the account's own address. The answer has no link. */
    suspend fun emailPasswordLink(userId: String): IssuedPasswordLinkDto =
        recoveryApi.issuePasswordLink(PasswordLinkDeliveryRequest(userId = userId, delivery = PASSWORD_LINK_BY_EMAIL))

    /** A temporary password, which the person must replace at their next sign-in. Never trimmed. */
    suspend fun setTemporaryPassword(userId: String, password: String): UserDto =
        recoveryApi.setTemporaryPassword(userId, TemporaryPasswordBody(password = password))

    /** Every device that account is signed in on has to sign in again. */
    suspend fun signOutEverywhere(userId: String): UserDto = recoveryApi.signOutEverywhere(userId)

    /**
     * Does this deployment send e-mail? Remembered once it has been answered, because it is a fact
     * about the server and not about the person; a failure is not remembered, and reads as "no" so
     * the "Email them a link" button is simply not offered.
     */
    suspend fun mailAvailable(): Boolean {
        knownMailAvailable?.let { return it }
        val answer = runCatching { recoveryApi.mailStatus().available }.getOrNull() ?: return false
        knownMailAvailable = answer
        return answer
    }

    @Volatile
    private var knownMailAvailable: Boolean? = null

    /**
     * "Add a person" — `POST /users` with a [PasswordMode]. A 409 for an address that already has an
     * account is thrown as [AccountAlreadyExists], carrying the server's sentence and the existing
     * account's id from [EXISTING_ACCOUNT_HEADER] so the screen can open it instead.
     */
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

    /** [context] lets the activity list be forgotten too; every caller in the app passes it. */
    fun logout(context: Context? = null) {
        val signedOut = tokenStore.getUser()?.id
        tokenStore.clear()
        // The activity list holds this account's notices: the device copy, the alerts in the shade
        // and the in-memory list all go.
        if (context != null) runCatching { NotificationSync.signOut(context, signedOut) }
        // The read cache holds this account's records; the next person to sign in must not be shown
        // them offline. The outbox is NOT cleared: its entries carry their owner and wait for them.
        OfflineReadCache.clear()
        // Photo previews kept for offline viewing are this account's pictures too.
        PreviewCache.clear()
    }

    /** The signed-in account's id, from the cached profile (works offline). */
    private fun currentUserId(): String? = tokenStore.getUser()?.id

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

    // --- The access roster: who may sign in at all ----------------------------------------------
    //
    // Thin pass-throughs on purpose. Every rule that matters — only ACTIVE admits, `joinedAt` is
    // stamped once and never moved, a rejection is not undone by the rejected person retrying, the
    // master admin can never be taken off the list — lives on the server, in
    // `app/services/access_roster.py`, because both clients and both applications have to obey the
    // same one. A convenience here that "helpfully" decided any of them would be a second opinion.

    /** One page of the roster, newest request first. `status` null = every status. See the API. */
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

    /** How many people are waiting on an administrator. THE NOTIFICATION. */
    suspend fun accessRosterPendingCount(): Int = api.accessRosterPendingCount().pending

    /** The activity list's two calls, for [NotificationSync] (data/AppNotifications.kt). */
    fun notificationApi(): NotificationApi = object : NotificationApi {
        override suspend fun list(limit: Int): NotificationPageDto = api.notifications(limit)
        override suspend fun markRead(body: NotificationReadBody): NotificationReadResultDto = api.markNotificationsRead(body)
    }

    /** One pass of the activity list for the signed-in account; null when it could not be reached. */
    suspend fun syncNotifications(context: Context): Int? {
        val userId = tokenStore.getUser()?.id ?: return null
        return NotificationSync.sync(context, notificationApi(), userId)
    }

    suspend fun addToAccessRoster(
        email: String,
        fullName: String? = null,
        notes: String? = null,
        grantedRole: String? = null,
        /** Null: they sign in with Google, Microsoft or Yahoo. Set: the account is made with it. */
        password: String? = null,
        mustChangePassword: Boolean = true
    ): AccessRosterDto = api.addToAccessRoster(
        AccessRosterCreateBody(
            email = email.trim(),
            fullName = fullName?.trim()?.ifBlank { null },
            notes = notes?.trim()?.ifBlank { null },
            grantedRole = grantedRole?.ifBlank { null },
            // Never trimmed: a password is exactly what was typed.
            password = password?.takeIf { it.isNotEmpty() },
            mustChangePassword = if (password.isNullOrEmpty()) null else mustChangePassword
        )
    )

    /**
     * Approve, and set the tier the person joins at in the same call.
     *
     * ONE CALL AND NOT TWO. Approving and then promoting would leave a window in which somebody an
     * admin deliberately admitted as a Researcher can sign in as a volunteer — and if the second
     * call failed, the window would be permanent and invisible.
     */
    suspend fun approveAccessRosterEntry(id: String, grantedRole: String? = null): AccessRosterDto =
        api.updateAccessRosterEntry(
            id,
            AccessRosterUpdateBody(status = "ACTIVE", grantedRole = grantedRole?.ifBlank { null })
        )

    /**
     * Refuse. The entry STAYS, and a later attempt by the same person bumps their counter without
     * re-opening the request — see `access_roster.record_access_request` for why re-opening was
     * rejected as a design: a rejection an applicant can undo by tapping "sign in" again is not a
     * rejection, it is a timer, and the admin gets the same request back every day forever.
     */
    suspend fun rejectAccessRosterEntry(id: String): AccessRosterDto =
        api.updateAccessRosterEntry(id, AccessRosterUpdateBody(status = "REJECTED"))

    /** Suspend — never a delete. The row is the record that this address was admitted. */
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
            // Sending BOTH is a 422 server-side, on purpose — either precedence would be a guess and
            // a caller asking for `status=DONE&outstanding=true` has a bug that a quiet answer hides.
            // Suppressed rather than forwarded here so an explicit status always wins locally too,
            // and the caller sees the list they asked for instead of an error they did not.
            outstanding = if (outstanding && status.isNullOrBlank()) true else null
        ).items

    /**
     * MY workload in one object — what the full-width progress card at the top of the task screen is
     * drawn from. Always about the signed-in caller.
     *
     * NEVER COMPUTE THIS FROM [tasks]. That call is paged (100 by default, but paged all the same),
     * so a card counted from it under-reports outstanding work for anyone holding more than a page —
     * and under-reporting outstanding work is the one failure the card exists to prevent. This scans
     * the caller's whole bounded list server-side and reports `truncated` when it could not finish.
     */
    suspend fun taskSummary(workshopId: String? = null): TaskSummaryDto =
        api.taskSummary(workshopId?.blankToNull())

    /** One task, enriched exactly like a list item. Visible to the assignee, the creator and admins. */
    suspend fun task(taskId: String): TaskDto = api.task(taskId)

    /**
     * Assignee-side update: move the status and/or report how much is done.
     *
     * An assignee may write OPEN, IN_PROGRESS and SUBMITTED only. `CANCELLED` is a 403 (withdrawal
     * belongs to whoever handed the work out) and so is ANY status on a task already `DONE` — that
     * would be the person whose work was approved quietly erasing the approval, which is what would
     * make the whole review step decorative. Neither is offered by the UI, and both are worth knowing
     * about here because the 403 body is what a caller will actually see if one slips through.
     */
    suspend fun updateTaskProgress(taskId: String, status: String? = null, progressCount: Int? = null): TaskDto =
        api.updateTask(taskId, TaskUpdateBody(status = status, progressCount = progressCount))

    /**
     * AN ADMIN DECIDING ABOUT SOMEBODY ELSE'S WORK — approve a submission, send it back, or reopen
     * an approval.
     *
     * SAME ENDPOINT AND SAME BODY AS [updateTaskProgress], AND A SEPARATE FUNCTION ANYWAY. The two
     * are not the same act. An assignee moving their own task is a REPORT by the person who did the
     * work; an admin moving it is a DECLARATION ABOUT SOMEBODY ELSE, made by a person who did not do
     * it and possibly while they are still doing it. The row afterwards is byte-identical — the
     * server writes `status` and `completedAt` either way and records nothing about who pressed what
     * — so the ONLY place that distinction can be made at all is in the client, before the press,
     * and a shared function name is how it quietly stops being made. The call sites read
     * differently on purpose.
     */
    suspend fun reviewTask(taskId: String, status: String): TaskDto =
        api.updateTask(taskId, TaskUpdateBody(status = status))

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
    // ── A designer's OWN provider keys ────────────────────────────────────────────────────
    // Thin pass-throughs: there is no caching and no local store for these on purpose. A key is a
    // credential, and the only copy that should exist on the handset is the one being typed.

    suspend fun aiProviders(): AiCatalogueDto = api.aiProviders()

    suspend fun myAiKeys(): List<UserAiKeyDto> = api.myAiKeys()

    suspend fun setMyAiKey(provider: String, key: String?, model: String?): UserAiKeyDto =
        api.setMyAiKey(provider, UserAiKeySetBody(key = key?.takeIf { it.isNotBlank() }, model = model))

    suspend fun deleteMyAiKey(provider: String): UserAiKeyDto = api.deleteMyAiKey(provider)

    suspend fun testMyAiKey(provider: String): UserAiKeyDto = api.testMyAiKey(provider)

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

    /** The Monday summary's switch for this account (`eligible` false: draw nothing). */
    suspend fun weeklyDigestPreference(): WeeklyDigestPreferenceDto = api.weeklyDigestPreference()

    suspend fun saveWeeklyDigestPreference(enabled: Boolean): WeeklyDigestPreferenceDto =
        api.updateWeeklyDigestPreference(WeeklyDigestPreferenceBody(enabled = enabled))

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

    /**
     * The coverage map (`GET /map/coverage`) under [query]: every unit with its counts, the markers,
     * the gaps and the filter vocabulary. One answer draws the choropleth and, folded per state by
     * [coverageSummary], the Coverage list under it.
     */
    suspend fun coverage(query: CoverageQuery = CoverageQuery()): CoverageResponse =
        api.mapCoverage(query.params())

    /**
     * The records behind one unit, under the same filters the map was drawn with. A null
     * [districtLgd] (or a STATE-level query) reads the state as a whole. [bucket] and [cursor] page
     * one bucket further.
     */
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

    // --- Data browser ---

    /**
     * ONE level of the virtual data tree. Lazy: only this level's queries run, so navigate by calling
     * this again with an entry's `path`. `path = ""` is the taxonomy chooser, not a folder listing.
     *
     * Needs the dataset-download permission (403 otherwise) and everything listed is already filtered
     * to what the caller may see.
     */
    /**
     * One level of the tree. [status] narrows every record to one review status: the data browser
     * sends `APPROVED` unless the reader chose every status (C21, an export means approved). Null is
     * every status, which is what an older build always asked for.
     */
    suspend fun dataTree(path: String = "", status: String? = null): DataTreeDto = api.dataTree(path, status)

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
    suspend fun dataManifest(path: String = "", include: String? = null, status: String? = null): DataManifestDto =
        api.dataManifest(path, include?.blankToNull(), status)

    /** Records awaiting review (status PENDING), newest first, across record types. */
    suspend fun pendingReviews(): List<PendingReviewDto> = api.pendingReviews().items

    /** One page of the review queue, newest first, with the server's true count of the whole queue. */
    suspend fun pendingReviewsPage(
        page: Int,
        pageSize: Int = REVIEW_PAGE_SIZE,
        workshopId: String? = null
    ): PendingReviewListDto =
        api.pendingReviews(page = page, pageSize = pageSize, workshopId = workshopId?.blankToNull())

    private var reasonsCache: List<ReviewReasonDto>? = null

    /** The reasons a reviewer picks from, read once per session (the server's own wording). */
    suspend fun reviewReasons(): List<ReviewReasonDto> =
        reasonsCache ?: api.reviewReasons().also { reasonsCache = it }

    /** The last review decision on a record, for the note above its edit form (A1). */
    suspend fun latestReview(recordType: String, recordId: String): LatestReviewAnswerDto =
        api.latestReview(recordType, recordId)

    /** What reviewers sent back to this account, each with its note (A2). */
    suspend fun myReturnedRecords(): ReturnedListDto = api.myReturnedRecords()

    /** Approve many queue rows in one request (B9). */
    suspend fun bulkApprove(items: List<PendingReviewDto>): BulkApproveResponse =
        api.bulkApprove(BulkApproveRequest(items = items.map { BulkApproveItem(it.recordType, it.id) }))

    /**
     * Approve a pending record (admins, or users granted the review permission). The server approves
     * its still-pending files with it, in the same transaction, and says how many.
     *
     * WITH NO SIGNAL THE APPROVAL IS KEPT, NOT LOST: a request that got no answer goes to the outbox
     * under the Idempotency-Key it was sent with, so if it did land, the replay is answered with the
     * first answer rather than approving twice (and approving files uploaded in between).
     */
    suspend fun approveRecord(context: Context, recordType: String, recordId: String, label: String): ReviewSend =
        sendReviewDecision(context, "review-approve", QueuedReviewDecision(recordType, recordId), label)

    /**
     * Reject ONE file, pending or already approved with its record (C21). A fixable [reason] sends it
     * back instead. Queued like an approval when there is no signal.
     */
    suspend fun rejectMedia(context: Context, mediaId: String, label: String, reason: String?, notes: String?): ReviewSend =
        sendReviewDecision(
            context, "review-reject", QueuedReviewDecision("media", mediaId, notes?.blankToNull(), reason), label
        )

    /** What this reviewer may do with a record's files: the approval's count and each file's Reject. */
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

    /**
     * Reject a pending record (admins, or users granted the review permission). With a FIXABLE
     * [reason] the server sends it back for revision instead; the answer's status says which.
     */
    suspend fun rejectRecord(recordType: String, recordId: String, reason: String? = null, notes: String? = null): String? {
        val answer = api.rejectRecord(recordType, recordId, ReviewActionRequest(notes = notes?.blankToNull(), reason = reason))
        return ((answer as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull
    }

    /** Send a record back to its creator. [notes] is mandatory — the API 422s on a blank one. */
    suspend fun reviseRecord(recordType: String, recordId: String, notes: String, reason: String? = null) {
        api.reviseRecord(recordType, recordId, ReviewActionRequest(notes = notes, reason = reason))
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

    /**
     * The summary, key quotes and translation of one transcript, or null: the feature is switched
     * off (404), the media is gone, or the phone is offline. Never cached and never an error banner.
     */
    suspend fun transcriptInsight(mediaId: String): TranscriptInsightViewDto? =
        insightOrHidden { api.transcriptInsight(mediaId) }

    /** Download an update APK to the cache and return the file, for handing to the system installer. */
    suspend fun downloadApk(context: Context, url: String, versionCode: Int): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } } // drop older downloads
        val out = File(dir, "field-repository-v$versionCode.apk")
        val request = Request.Builder().url(url).get().build()
        storageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("The update didn't download. Try again.")
            // `body` is non-null since OkHttp 5 (and `execute()` never returned a null one before it):
            // a body-less reply arrives EMPTY, and the system installer refuses an empty file.
            response.body.byteStream().use { input -> FileOutputStream(out).use { output -> input.copyTo(output, 64 * 1024) } }
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

    /**
     * The same request, WITH the envelope and PAGED — because `total` is the half that says whether
     * the list is whole, and one page of it is not enough of the list.
     *
     * ── THE CEILING, AND WHY ONE REQUEST CANNOT CLEAR IT ────────────────────────────────────────
     *
     * [LIST_PAGE_CEILING] is refused-past server-side, so a single request holds 100 rows however it
     * is asked — it is not a default somebody forgot to raise. Every caller of [artisans] above keeps
     * `.items` and drops `total`, which is exactly how a cut list came to render indistinguishably
     * from a repository with nothing in it. A screen that shows this list must be able to say so —
     * see `ui/RecordPickers.listCutNotice`.
     *
     * ── AND WHY THE NOTICE WAS NOT ENOUGH ON ITS OWN ────────────────────────────────────────────
     *
     * It made the handset HONEST about offering a hundred people; it did not make the handset offer
     * the same people the browser was offering. See [ARTISAN_PAGE_BUDGET] for the whole argument. So
     * this walks pages 1..min(pages, budget) exactly as `useWorkshopArtisans` does, and reports the
     * remainder off the ACCUMULATED count so the sentence still tells the truth past 500.
     *
     * PAGES TWO ONWARD GO OUT TOGETHER. `total` and `pages` come back with page one, so the remaining
     * page numbers are known exactly; waiting for each answer to learn the next number would turn a
     * field connection's latency into a multiple of itself for no extra information. `awaitAll`
     * preserves the order it was given, so concatenating keeps the server's own `createdAt desc`
     * ordering intact end to end — which is the ordering half of the parity contract, and the reason
     * neither client sorts.
     *
     * THE ENVELOPE IS REBUILT RATHER THAN RETURNED VERBATIM: `items` is everything walked, `pageSize`
     * is how many rows that actually is, and `total` and `pages` stay the server's answer about the
     * whole filtered set. A caller reading `items.size` against `total` therefore gets the real
     * remainder rather than "100 of 640" over 500 loaded rows.
     *
     * A FAILURE ON ANY PAGE FAILS THE WHOLE CALL, deliberately. `coroutineScope` cancels its siblings
     * and rethrows, so the caller gets an exception rather than a silently short list: the one thing
     * worse than a roster that stops at 100 is a roster that stops at 237 and says 500.
     *
     * ── [pageBudget] DEFAULTS TO ONE, AND THAT IS NOT TIMIDITY ──────────────────────────────────
     *
     * Only the surface whose web twin pages asks for pages. The web has exactly one: the questionnaire
     * capture form's artisan picker (`useWorkshopArtisans` effect B, `ARTISAN_PAGE_BUDGET`). Its other
     * two artisan reads are deliberately page-one — the consolidated index
     * (`app/(protected)/questionnaire/consolidated/page.tsx`, `pageSize: 100`) and the repository-wide
     * reachability probe that backs the carry bag, whose own comment refuses to page it because
     * *"paging it would be a change to a different feature's behaviour, made silently from here"*.
     *
     * A budget that defaulted to five would therefore FIX one divergence by creating two: the handset's
     * consolidated index would offer 500 artisans against the browser's 100, and every app start would
     * walk five pages of the whole artisan table to widen `carryScope`'s reach behind the researcher's
     * back. Parity is per surface, so the budget is per call site — and each call site that raises it
     * is a place where somebody decided the browser does the same.
     */
    suspend fun artisansPage(
        workshopIds: List<String>? = null,
        /** How many pages to walk. One is the whole of today's behaviour; [ARTISAN_PAGE_BUDGET] is the picker's. */
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

    /**
     * The artisans of SEVERAL crafts, filtered by the server rather than in memory.
     *
     * The request the record forms were missing. Filtering a 100-row page of the WHOLE artisan table
     * down to one craft on the device gives the intersection of that craft with the newest hundred
     * rows overall; asking the server for the crafts gives their roster, up to the same ceiling —
     * which for a handful of crafts is, in practice, all of them. The envelope is returned for the
     * same reason as above: "in practice" is not a property of the code, so the caller still has to
     * be able to say when it was not.
     *
     * ONE REQUEST FOR THE WHOLE TICK LIST. The tool form's craft box is a multi-select, and issuing
     * one request per ticked craft is not a substitute — "Select all 178" would fire 178 of them.
     * `craftIds` is the plural the route gained on 2026-09-15, spelled exactly as `workshopIds`
     * already is on the same route.
     *
     * BOTH PARAMETERS ARE SENT FOR A SINGLE CRAFT, and that is deliberate rather than untidy. The
     * server narrows on each independently, so for one craft they narrow to the same set; what it
     * buys is that this client keeps working, unchanged, against an API that predates the plural —
     * a query parameter FastAPI does not declare is ignored in silence, and without the singular
     * beside it a handset updated ahead of the server would quietly get the whole artisan page back
     * and call it a craft's roster. The two clients deploy separately from the API; see
     * `MeasurementMarkers.body` for the same argument made about a request key.
     */
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

    /**
     * ONE PAGE OF A RECORD PICKER, searched by the SERVER (`ui/ServerSearch.kt`). [search] null is the
     * plain list; [page] is what "Load more" asks for next. Same ordering as the list route.
     */
    suspend fun artisansSearchPage(
        search: String?,
        page: Int,
        workshopIds: List<String>? = null,
        /** The record completeness filter's wire value, or null for every record. */
        completeness: String? = null
    ): PageResponse<ArtisanDto> =
        api.artisans(
            page = page,
            pageSize = 100,
            workshopIds = workshopIds.toQueryCsv(),
            search = search?.blankToNull(),
            completeness = completeness
        )

    /** [artisansSearchPage] for crafts. */
    suspend fun craftsSearchPage(search: String?, page: Int): PageResponse<CraftDto> =
        api.crafts(page = page, pageSize = 100, search = search?.blankToNull())

    /** [artisansSearchPage] for products. */
    suspend fun productsSearchPage(search: String?, page: Int, completeness: String? = null): PageResponse<ProductDetailDto> =
        api.products(page = page, pageSize = 100, search = search?.blankToNull(), completeness = completeness)

    /** [artisansSearchPage] for tools. */
    suspend fun toolsSearchPage(search: String?, page: Int, completeness: String? = null): PageResponse<ToolDetailDto> =
        api.tools(page = page, pageSize = 100, search = search?.blankToNull(), completeness = completeness)

    /** [artisansSearchPage] for processes. */
    suspend fun processesSearchPage(search: String?, page: Int): PageResponse<ProcessDetailDto> =
        api.processes(page = page, pageSize = 100, search = search?.blankToNull())

    /** [artisansSearchPage] for workshops. */
    suspend fun workshopsSearchPage(search: String?, page: Int, completeness: String? = null): PageResponse<WorkshopDetailDto> =
        api.workshops(page = page, pageSize = 100, search = search?.blankToNull(), completeness = completeness)

    /** [artisansSearchPage] for media files. */
    suspend fun mediaSearchPage(search: String?, page: Int): PageResponse<MediaFileDto> =
        api.media(page = page, pageSize = 100, search = search?.blankToNull())

    /** [artisansSearchPage] for interviews, inside the workshop scope on screen. */
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

    /**
     * ONE WORKSHOP'S RECORDS, WITH THE ENVELOPE KEPT — the five reads behind `ui/RecordSwitcher.kt`.
     *
     * Five near-identical one-liners rather than one generic helper, and that is not laziness: each
     * returns a DIFFERENT DTO, Retrofit needs the response type at the declaration, and the only
     * thing a generic wrapper could unify is the two query parameters. Kotlin cannot express "the
     * page type for this record kind" without either a sealed hierarchy over five unrelated
     * `@Serializable` classes or a reflective lookup, and both cost more than five lines.
     *
     * `pageSize = 100` is the CEILING, not a default somebody forgot to raise: `normalize_pagination`
     * clamps to `MAX_PAGE_SIZE = 100` (`backend/app/services/pagination.py`) and each route declares
     * `le=100` on top of it. Which is precisely why the envelope is returned rather than `.items` —
     * `total` is the half that says whether this list is the whole answer for the workshop, and
     * dropping it is how a cut list comes to render indistinguishably from an empty workshop. See
     * `listCutNotice` in `ui/RecordPickers.kt`.
     *
     * [search] is null for the ordinary load and non-null only when the switcher has established that
     * the list IS cut — see `shouldSearchServer` there for why searching unconditionally would be
     * worse than the on-device filter it would replace, and would fail in a courtyard with no signal.
     *
     * A blank [workshopId] is "All workshops": no filter, every record the account may see.
     */
    suspend fun artisansForWorkshopPage(workshopId: String, search: String? = null): PageResponse<ArtisanDto> =
        api.artisans(pageSize = 100, workshopId = workshopId.ifBlank { null }, search = search?.ifBlank { null })

    // --- Artisan cards (backend/app/api/routes/artisan_cards.py) ---

    /** Whether cards are on. Offline, the last answer this phone kept; no answer at all reads as off. */
    suspend fun artisanCardsEnabled(): Boolean = try {
        api.artisanCardStatus().enabled
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    /**
     * The register pages this phone already holds, read from its cache and never the network: the
     * same `GET /artisans?page=N&pageSize=100` pages the artisan pickers walk ([artisansPage]), so
     * every artisan a picker has shown can be matched by card with no signal. Stops at the first
     * page that was never fetched.
     */
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

    /** A scanned QR or a typed short code: the cached register first, then the server. */
    suspend fun resolveArtisanCard(input: String): ArtisanCardResolution =
        resolveArtisanCardWith(input, ::cachedArtisanRegister) { text ->
            api.resolveArtisanCard(ResolveArtisanCardRequest(input = text))
        }

    /**
     * A queued entry's pending cards (`card:<shortCode>`, kept by a picker when a card was scanned with
     * no signal), swapped for the artisan ids they resolve to and written back before the entry is
     * sent. A card the server refuses HOLDS the entry with its sentence; it is never dropped.
     */
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

    /** One workshop's crafts. See [artisansForWorkshopPage]. */
    suspend fun craftsForWorkshopPage(workshopId: String, search: String? = null): PageResponse<CraftDto> =
        api.crafts(pageSize = 100, workshopId = workshopId.ifBlank { null }, search = search?.ifBlank { null })

    /** One workshop's products. See [artisansForWorkshopPage]. */
    suspend fun productsForWorkshopPage(workshopId: String, search: String? = null): PageResponse<ProductDetailDto> =
        api.products(pageSize = 100, workshopId = workshopId.ifBlank { null }, search = search?.ifBlank { null })

    /** One workshop's tools. See [artisansForWorkshopPage]. */
    suspend fun toolsForWorkshopPage(workshopId: String, search: String? = null): PageResponse<ToolDetailDto> =
        api.tools(pageSize = 100, workshopId = workshopId.ifBlank { null }, search = search?.ifBlank { null })

    /** One workshop's processes. See [artisansForWorkshopPage]. */
    suspend fun processesForWorkshopPage(workshopId: String, search: String? = null): PageResponse<ProcessDetailDto> =
        api.processes(pageSize = 100, workshopId = workshopId.ifBlank { null }, search = search?.ifBlank { null })

    /**
     * The craft catalogue, revalidated with its ETag (REQUIREMENTS_2026-10-20 §2.3-§2.4). Returns the
     * newest list on hand — the server's, or the device's when offline — and throws only when there
     * is no copy at all. Every craft picker reads this.
     */
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

    /**
     * The app-wide craft lookup: every ACTIVE craft on the list, read from [craftCatalogue] (so it
     * revalidates at sign-in and on app start, and answers offline from the device copy), in the
     * lookups' page envelope. It is the whole list, so `carryScopeStateFor` can vouch for an absence.
     * Throws only when the device has no copy and the server could not be reached.
     */
    suspend fun craftLookup(context: Context): PageResponse<CraftDto> {
        val catalogue = craftCatalogue(context) ?: throw IllegalStateException(CraftCatalogueCache.OFFLINE_FIRST_RUN)
        val items = catalogue.crafts.map { it.asCraftDto(catalogue) }
        return PageResponse(items = items, total = items.size, page = 1, pageSize = items.size, pages = 1)
    }

    /**
     * EVERY ROW ONE ACCOUNT CREATED, for My Activity — filtered by the SERVER (`createdBy` on every
     * list route) and walked page by page.
     *
     * It used to read page one (100 rows) of each WHOLE list and filter it here, so once the
     * repository passed a hundred of a type a researcher's older records silently fell off the end
     * and read as "I never recorded that". [MY_RECORDS_PAGE_BUDGET] pages of 100 per type is far
     * past one person's output; [MyRecordsPage.truncated] says so if it is ever reached.
     */
    suspend fun <T> myRecordsOf(fetch: suspend (page: Int) -> PageResponse<T>): MyRecordsPage<T> {
        val rows = mutableListOf<T>()
        var page = 1
        var total = 0
        while (page <= MY_RECORDS_PAGE_BUDGET) {
            val answer = fetch(page)
            total = answer.total
            rows += answer.items
            if (answer.items.isEmpty() || rows.size >= answer.total) break
            page += 1
        }
        return MyRecordsPage(rows, truncated = rows.size < total)
    }

    suspend fun myArtisans(userId: String) = myRecordsOf { api.artisans(page = it, pageSize = 100, createdBy = userId) }
    suspend fun myProducts(userId: String) = myRecordsOf { api.products(page = it, pageSize = 100, createdBy = userId) }
    suspend fun myTools(userId: String) = myRecordsOf { api.tools(page = it, pageSize = 100, createdBy = userId) }
    suspend fun myProcesses(userId: String) = myRecordsOf { api.processes(page = it, pageSize = 100, createdBy = userId) }
    suspend fun myCrafts(userId: String) = myRecordsOf { api.crafts(page = it, pageSize = 100, createdBy = userId) }
    suspend fun myWorkshops(userId: String) = myRecordsOf { api.workshops(page = it, pageSize = 100, createdBy = userId) }
    suspend fun myInterviews(userId: String) = myRecordsOf { api.interviews(page = it, pageSize = 100, createdBy = userId) }

    // One search across both portals (docs/SEARCH.md): ranked groups from this portal's index and,
    // for the roles allowed, the designer portal's. A 404 means the server's switch is off.
    suspend fun unifiedSearch(query: String, limit: Int = 5): UnifiedSearchResponse =
        api.unifiedSearch(q = query.trim(), limit = limit)

    suspend fun remoteRecord(type: String, id: String): RemoteRecord = api.remoteRecord(type, id)

    // The app-wide search panel's three list sources (`ui/AppSearchSources.kt`). Each is the list
    // route's own `search` filter, so a match means what it means on that list's screen.
    // Crafts are searched on the device's craft list, ranked as the pickers rank them.
    suspend fun searchCrafts(query: String, pageSize: Int = 10): List<CraftDto> {
        val catalogue = CraftCatalogueCache.catalogue.value ?: return emptyList()
        return CraftCatalogueCache.search(catalogue, query.trim()).take(pageSize).map { it.asCraftDto(catalogue) }
    }

    suspend fun searchProcesses(query: String, pageSize: Int = 10): List<ProcessDetailDto> =
        api.processes(pageSize = pageSize, search = query.trim()).items

    suspend fun searchInterviews(query: String, pageSize: Int = 10): List<QuestionnaireInterviewDetailDto> =
        api.interviews(pageSize = pageSize, search = query.trim()).items

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

    /**
     * Which records name NO workshop, and which workshop each one's own evidence points at. Admin-only.
     *
     * A pure READ and the preview [mapUnmappedRecords] acts on. It THROWS on failure rather than returning
     * null, unlike [workshopSubmissionCheck] above: that one is a courtesy check on the way to saving a
     * record a researcher is standing in a field to enter, so a failure must not cost them the entry. This
     * is a whole screen whose only content is the report — a silent null would render "nothing is unmapped",
     * which is the same wrong answer this feature exists to stop the app giving.
     */
    suspend fun unmappedRecords(): WorkshopMappingPlanDto = api.unmappedRecords()

    /**
     * File every unassigned record whose evidence names exactly one workshop, and return what was written.
     *
     * Sends no body: the server re-derives the plan, so this cannot ask for a particular row to be moved to
     * a particular workshop. Idempotent — every write carries "and the column is still empty" — so pressing
     * it twice changes nothing and a row somebody assigned by hand in between keeps that answer.
     */
    suspend fun mapUnmappedRecords(): WorkshopMappingPlanDto = api.mapUnmappedRecords()

    /** Switch the nightly filing of clear-cut records. Throws on failure, like the two calls above. */
    suspend fun setUnmappedAuto(enabled: Boolean): WorkshopAutoMapDto =
        api.setUnmappedAuto(WorkshopAutoMapRequest(enabled))

    suspend fun createArtisan(body: ArtisanCreateRequest, idempotencyKey: String? = null): ArtisanDto =
        api.createArtisan(idempotencyKey, body)

    /** One artisan. A MERGED id keeps working: the server answers with the record it now lives on. */
    suspend fun artisan(id: String): ArtisanDetailDto = api.artisan(id)

    /**
     * Who the artisan being typed already looks like — the server scores, this only asks. Empty on
     * any failure (offline, rate-limited): the create's own hold still asks the question.
     */
    suspend fun checkDuplicates(body: DuplicateCheckRequest): List<DuplicateCandidateDto> =
        runCatching { api.checkDuplicates(body).candidates }.getOrDefault(emptyList())

    /**
     * Answer a queued artisan the server held as a likely duplicate, then send it. "Same person"
     * attaches its files to the existing artisan; "Different person" re-sends the create with the
     * candidates acknowledged. The entry stays queued until the pass sends it.
     */
    suspend fun answerPossibleDuplicate(context: Context, entryId: String, samePerson: Boolean) {
        val entry = OfflineOutbox.all(context).firstOrNull { it.id == entryId } ?: return
        if (!entry.isPossibleDuplicate()) return
        OfflineOutbox.update(context, if (samePerson) entry.asSamePerson() else entry.asDifferentPerson())
    }

    /** The completeness rules file as text — see [RecordCompletenessStore.refresh]. */
    suspend fun completenessRulesText(): String = api.completenessRules().use { it.string() }

    /**
     * Is this Aadhaar number already on an artisan? The form's pre-flight duplicate check, run while
     * the researcher is still typing so a duplicate surfaces before the whole form is filled in rather
     * than as a 409 on save. [number] may be typed with spacing; the API normalises it.
     */
    suspend fun lookupArtisanByAadhaar(number: String): AadhaarLookupDto =
        api.lookupArtisanByAadhaar(AadhaarLookupRequest(number.trim()))

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

    /**
     * Result of a full-dataset download: where it was saved and how many files succeeded.
     *
     * [truncated] is the SERVER's answer, not this client's: true when the repository is larger than
     * the export caps and the archive therefore cannot be the whole of it. It is carried all the way
     * out to the button's message because "Saved to Downloads — 4,312/4,312 files" over an archive
     * missing everything past the cap is a wrong answer that reads as a right one.
     */
    data class DatasetDownloadResult(
        val displayLocation: String,
        val saved: Int,
        val total: Int,
        val failed: Int,
        val truncated: Boolean = false
    )

    /**
     * Read a download manifest without ever holding it whole, and hand each entry to [onEntry].
     *
     * WHY THE MANIFEST IS SPOOLED TO DISK FIRST, WHICH IS THE PART THAT LOOKS REDUNDANT.
     *
     * The obvious shape — read a line, zip that file, read the next line — is wrong here, and would
     * fail in the field rather than on a desk. The manifest response is served by the API host while
     * every media object is fetched from S3 on [storageClient]; consuming the manifest lazily means
     * its socket stays open for the whole download and sits idle for as long as each media transfer
     * takes. `ApiClient` sets a 60-second read timeout (deliberately, for mobile data), so the first
     * media file slower than a minute would kill the manifest connection mid-download and take the
     * whole archive with it. Spooling drains the manifest at full speed, closes its connection, and
     * leaves a file we can re-read at whatever pace S3 answers.
     *
     * `copyTo` streams in 8 KB chunks, so the spool costs one buffer, not one manifest — the peak
     * heap for the whole operation becomes the longest single LINE (one entry; at worst one
     * transcript body), against the ~48 MB single contiguous allocation the typed call asks for.
     * See `data/ManifestStream.kt` for the OutOfMemoryError this replaces.
     *
     * THE FALLBACK IS NOT DEAD CODE. `?stream=1` is a query parameter, and a server that predates it
     * ignores it and answers the ordinary JSON object. That response must still produce an archive —
     * handsets update on their own schedule and a client that only works against a new server is a
     * client that breaks every phone in the field on the day of a rollback. [buffered] is that path,
     * and it is the OLD behaviour exactly: one whole-manifest allocation, which is fine on the small
     * repositories that never hit this defect and is at least an attempt on the large ones.
     */
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
            // Not the streamed format. Close what did arrive — an unclosed ResponseBody leaks the
            // connection out of OkHttp's pool — and ask again for the shape this server does speak.
            runCatching { body?.close() }
            val (files, total, truncated) = buffered()
            for (entry in files) onEntry(entry, total)
            return ManifestOutcome(total = total, truncated = truncated, unreadable = 0)
        }
        // Read BEFORE the body, which is the whole point of putting these in headers: the caller
        // needs the total to report progress from the first entry, not after the last one.
        val total = ManifestStream.count(response.headers()[ManifestStream.TOTAL_HEADER])
        val truncated = ManifestStream.flag(response.headers()[ManifestStream.TRUNCATED_HEADER])
        try {
            body!!.byteStream().use { input ->
                FileOutputStream(spool).use { out -> input.copyTo(out) }
            }
            var unreadable = 0
            spool.reader(Charsets.UTF_8).use { reader ->
                val lines = ManifestLines(reader, ApiClient.json, deserializer)
                // A plain `for`, not `forEach`: the body suspends (a folder download asks the API
                // to transcode each audio row), and only the loop keeps us in the caller's coroutine.
                for (entry in lines.entries()) onEntry(entry, total)
                unreadable = lines.unreadable
            }
            return ManifestOutcome(total = total, truncated = truncated, unreadable = unreadable)
        } finally {
            // cacheDir is not guaranteed to be swept, and a 48 MB spool left behind after every
            // download is the kind of thing that quietly fills a field handset.
            spool.delete()
        }
    }

    /**
     * Pull the full dataset manifest, then download every media object straight from S3 and zip the
     * whole directory tree to the device's Downloads folder. [onProgress] reports (done, total) as each
     * entry is written so the UI can show real progress. Individual file failures are skipped, not fatal.
     *
     * The manifest is read entry-by-entry ([readManifest]) rather than deserialised whole, so a large
     * repository no longer has to fit in one allocation. `total` therefore comes from the server's
     * `X-Dataset-Total` header instead of `files.size`; while it is unknown (-1, an old server that
     * sent no header) progress reports the running count as the total, which reads as "N of N so
     * far" rather than as a wrong fraction.
     */
    suspend fun downloadDataset(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit
    ): DatasetDownloadResult = withContext(Dispatchers.IO) {
        // NAMING 2.1: a snapshot that can repeat within a day carries HHMM (IST).
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

    /**
     * How many files the download was SUPPOSED to contain — the server's count, not ours.
     *
     * The server's is the honest denominator and the two can legitimately differ. A line that would
     * not decode was never handed to the zip loop, so it is in the server's count and not in
     * [seen]; and if a stream ends early the count is higher than anything we saw. Reporting
     * `seen` as the total in either case would let the archive present itself as complete while
     * being short, which is the failure the `truncated` flag exists to stop and this would
     * reintroduce by another door. Falls back to what arrived only when there is no header to
     * believe (-1 — a server that predates it).
     */
    private fun manifestTotal(outcome: ManifestOutcome, seen: Int): Int =
        if (outcome.total >= 0) maxOf(outcome.total, seen) else seen + outcome.unreadable

    /**
     * Download the styled .xlsx relational report straight into the public Downloads folder (same
     * MediaStore path the dataset zip uses) and return where it was saved.
     *
     * [path] scopes the report to one subtree of the data browser; the default, "", is the whole
     * dataset — which is what every caller before the data browser existed meant.
     */
    suspend fun downloadReport(context: Context, path: String = "", status: String? = null): String = withContext(Dispatchers.IO) {
        val response = api.dataReport(format = "xlsx", path = path, status = status)
        if (!response.isSuccessful) throw IllegalStateException("The report didn't download. Try again.")
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
     *
     * The manifest is read entry-by-entry ([readManifest]) for the same reason [downloadDataset]
     * does it, and this endpoint is if anything the worse of the two: an unfiltered folder manifest
     * inlines the FULL transcript of every audio row in the subtree, so its byte size is bounded by
     * nothing at all.
     */
    suspend fun downloadDataFolder(
        context: Context,
        path: String,
        include: String? = null,
        folderName: String? = null,
        /** One review status, or null for every status: the data browser's choice (C21). */
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
            if (!resp.isSuccessful) throw IllegalStateException("The download didn't finish. Try again.")
            resp.body.byteStream().copyTo(sink)
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

    /**
     * C22: the newest process documented for [craftId] that has steps, other than [excludeId] — what
     * "Start from this craft's last process" copies the step names from.
     */
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

    /** Every instrument the picker may offer. `isDefault` names the one an unscoped call lands on. */
    suspend fun questionnaires(): List<QuestionnaireDto> = api.questionnaires()

    // questionnaireId is LAST and defaulted on both, so no existing call site has to change and an
    // un-updated screen keeps getting the default instrument exactly as it did before.
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

    /**
     * THE INTERVIEWS IN SCOPE. [workshopIds] is the shared workshop scope — null or empty is EVERY
     * workshop, and the reserved id `none` asks for interviews linked to no workshop. SPELLED
     * exactly like [artisans] above, because the questionnaire form and the browse screens read the
     * two lists side by side and a scope that meant two different things across them would offer one
     * workshop's artisans against another workshop's interviews.
     *
     * AND NO LONGER *DEFAULTED* LIKE IT, WHICH IS THE HALF THIS COMMENT USED TO GET WRONG.
     *
     * There was a `= null` here, and it is what made the fix below look finished when it was not.
     * Adding the parameter changed the signature without changing a single call site: every caller
     * that had been asking the repository-wide question went on asking it, still compiled, still
     * read as deliberate, and the two that mattered were found months later by hand. A default whose
     * value is also the old behaviour cannot be audited — nothing at a bare call says whether the
     * scope is a decision or an oversight, and the compiler, the one reader that visits every call
     * site, had been told not to care.
     *
     * So the parameter is REQUIRED and [EVERY_WORKSHOP] is what a caller writes when it genuinely
     * means the whole repository. The name is the point: `interviews(EVERY_WORKSHOP)` is a sentence
     * somebody wrote; `interviews()` was a sentence nobody wrote. Four callers say it today — My
     * Activity, the misc-media link picker, the orphan re-link picker and the sharing batch — and
     * each says why on the line above it.
     *
     * [artisans] KEEPS ITS DEFAULT, and the asymmetry is a decision rather than an unfinished edit:
     * it was not the reported defect, it has an order of magnitude more call sites, and turning all
     * of them into a mechanical `EVERY_WORKSHOP` in this commit would bury the four interview sites
     * that are its subject. It is the next candidate, not a thing left behind.
     *
     * ── THIS PARAMETER IS DEFECT (2) ──────────────────────────────────────────────────────────
     *
     * "In the android, the questionnaire from the previous workshop are showing up even in the third
     * workshop". This method took NO argument. Every questionnaire surface on the handset therefore
     * listed every interview in the repository, in every workshop, for ever — while
     * `ui/ConsolidatedQuestionnaireScreen.kt` had been passing `scope.workshopIds` to [artisans]
     * since the scope control landed. One list scoped and its neighbour not is the shape of bug that
     * survives a release: each screen is internally consistent, and only somebody holding both at
     * once can see that they disagree.
     *
     * ── "EVERY WORKSHOP" IS A THING A CALLER SAYS, NOT A THING THIS SIGNATURE ASSUMES ─────────
     *
     * This section used to open "THE DEFAULT IS EVERY WORKSHOP" and argue that a defaulted null here
     * is not a policy but the absence of a question — kept so the surfaces with no workshop control
     * on screen would "keep compiling and keep meaning what they always meant". The paragraph above
     * removed the default it was describing, and the two halves of this comment then said opposite
     * things about the same parameter. It is rewritten rather than deleted because its CONCLUSION
     * was never the wrong part; only its subject was.
     *
     * What survives: [EVERY_WORKSHOP] is still not the policy for "no workshop chosen". It is the
     * absence of a question, written by the four screens that genuinely have no workshop control —
     * and the difference from the old default is that they now write it, so "keep compiling" is no
     * longer something a call site can do by saying nothing.
     *
     * The policy for a screen that DOES carry the control lives in `ui/WorkshopScope.kt`: the picker
     * settles on the most recent workshop before the first request goes out, and the only way to see
     * everything is to tap "All records", which says so. There is no third state on either client —
     * "nothing chosen yet" is not reachable, because a screen that could show an unscoped list while
     * the picker still read like a workshop was chosen is exactly the confusion being fixed.
     *
     * @see interviewsPage for the 100-row ceiling, which is the reason most callers want the envelope.
     */
    suspend fun interviews(workshopIds: List<String>?): List<QuestionnaireInterviewDetailDto> =
        interviewsPage(workshopIds).items

    /**
     * [interviews] WITH THE ENVELOPE — because `total` is the half that says whether the list is
     * whole.
     *
     * `pageSize = 100` is not a default somebody forgot to raise: `normalize_pagination` clamps to
     * `MAX_PAGE_SIZE = 100` (`backend/app/services/pagination.py`) and the route declares `le=100` on
     * top of it, so it is the ceiling and cannot be widened from this client even in principle. The
     * same argument, and the same answer, as [artisansPage]: a caller that keeps `.items` and drops
     * `total` renders a cut list indistinguishably from a repository with nothing in it. Every
     * questionnaire picker on this client now takes the envelope and says the number out loud through
     * `ui/RecordPickers.listCutNotice`.
     *
     * Scoping and the ceiling are the same fix twice over, which is why they landed together: one
     * workshop's interviews fit inside a hundred rows in practice, so the parameter above is also
     * what makes the cap stop mattering — and the notice is what keeps "in practice" from being load-
     * bearing.
     */
    suspend fun interviewsPage(
        workshopIds: List<String>?,
        /** Pages to walk. One is a single request; the record pickers pass [LOOKUP_PAGE_BUDGET]. */
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

    /**
     * EVERY INTERVIEW ONE ARTISAN SAT IN, filtered by the server — the sibling-save lookup, and
     * deliberately NOT scoped by workshop.
     *
     * An interview is identified by its exact set of artisans, and two screens need every save ever
     * made against that set: the View Data detail (which aggregates answers and media across the
     * group, so a recording attached to a sibling is never hidden) and the questionnaire form in edit
     * mode (which shows those same recordings while the form is open). Both used to find the group by
     * pulling the newest hundred interviews in the WHOLE repository and filtering them in memory —
     * so the moment the repository outgrew one page, a sibling that had sorted off page one was not
     * found, and its answers and its recordings silently disappeared from the two screens whose
     * stated purpose is that they never do. Asking for one artisan's interviews is a bounded question
     * and the right one.
     *
     * NO WORKSHOP SCOPE HERE, ON PURPOSE. The record is already chosen at both call sites; the group
     * is a property of that record, not of the list it was picked from. Narrowing it to whatever
     * workshop the picker happens to be showing would drop the sittings the aggregation exists to
     * gather — the caller would have scoped the RECORD rather than the LIST, which is the one place
     * scoping is wrong.
     */
    /**
     * One artisan's interviews INSIDE the workshop scope on screen — the View Data filter's dependent
     * list. Asked per ticked artisan rather than by walking every interview in the scope, so the
     * request is bounded by the selection and not by the size of the repository.
     */
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

    /**
     * Move [id]'s answers and recordings onto [targetId] and remove [id], answering with the
     * survivor. See [FieldRepositoryApi.mergeInterviewInto] for the contract and what it refuses.
     *
     * CALLED ONLY FROM A CONFIRMATION, never from a save path. The PATCH's 409 carries the holder
     * ([ApiFailure.artisanSetHolder]); the screen names it, asks, and calls this if the answer is
     * yes. A caller that reached here straight off the refusal would be folding two researchers'
     * sittings together without either of them agreeing to it.
     */
    suspend fun mergeQuestionnaireInterviewInto(id: String, targetId: String): QuestionnaireInterviewDetailDto =
        api.mergeInterviewInto(id, targetId)

    /**
     * Completion matrix (artisans x sections). Pass [artisanId] to scope it to one artisan, and
     * [workshopIds] to scope it to workshops — null or empty is every workshop, `none` is the records
     * linked to none. LAST and defaulted so no existing call site has to change.
     */
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
        workshopIds: List<String>? = null,
        questionnaireId: String? = null
    ): ConsolidatedQuestionnaireDto =
        api.consolidatedQuestionnaire(
            artisanId,
            workshopIds.toQueryCsv(),
            questionnaireId?.blankToNull()
        )

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

    /** How many of the signed-in account's entries are waiting in the local outbox. */
    suspend fun pendingUploads(context: Context): Int =
        OfflineOutbox.all(context).replayableBy(currentUserId()).size

    /** The signed-in account's queued entries, for the banner that lists them. */
    suspend fun pendingEntries(context: Context): List<PendingEntry> =
        OfflineOutbox.all(context).replayableBy(currentUserId())

    /**
     * Type the Aadhaar number again for an entry the sync pass is holding because this phone could
     * not keep the one typed ([AadhaarAtRest.numberWithheld]). [number] is the normalised 12 digits,
     * already checked by the form's own rule. Refuses while the Keystore still cannot seal: writing
     * the number back would only withhold it again.
     */
    suspend fun retypeOutboxAadhaar(context: Context, entryId: String, number: String) {
        check(AadhaarAtRest.canSeal()) {
            "This phone can't keep the number safely right now. Save the artisan again from its form while online."
        }
        if (OfflineOutbox.retypeAadhaar(context, entryId, number)) OutboxSyncWorker.schedule(context)
    }

    /** Let the sync pass try a refused entry again. */
    suspend fun retryRefused(context: Context, entryId: String) {
        OfflineOutbox.clearFailure(context, entryId)
        OutboxSyncWorker.schedule(context)
    }

    /** "Fix and send" (B1): the corrected body replaces the refused one and is sent on the next pass. */
    suspend fun fixRefused(context: Context, entryId: String, payloadJson: String): Boolean {
        val ok = OfflineOutbox.fixAndResend(context, entryId, payloadJson)
        if (ok) OutboxSyncWorker.schedule(context)
        return ok
    }

    /** "Use existing artisan" (B1): drop this copy; its files go to the artisan already recorded. */
    suspend fun useExistingForRefused(context: Context, entryId: String): Boolean {
        val ok = OfflineOutbox.useExisting(context, entryId)
        if (ok) OutboxSyncWorker.schedule(context)
        return ok
    }

    /** Discard (B1), after the person has confirmed it. Deletes this phone's copy and its files. */
    suspend fun discardRefused(context: Context, entryId: String): Boolean = OfflineOutbox.discard(context, entryId)

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
        caption: String?,
        location: LocationRequest? = null,
        /** The record being edited; null for a new record. */
        editOf: String? = null
    ) = queueOfflineEntry(
        context, type, payloadJson, label,
        mediaUris.mapIndexed { index, uri ->
            OfflineMediaSpec(uri = uri, caption = caption, recordName = recordName, batchIndex = index + 1, location = location)
        },
        kind = if (editOf != null) OutboxKind.UPDATE else OutboxKind.CREATE,
        targetId = editOf
    )

    /**
     * Keep files that could not reach an EXISTING record (no signal, or the connection dropped
     * part-way) on the device, and attach them to [recordId] when the connection returns.
     */
    suspend fun queueAttachments(
        context: Context,
        recordType: String,
        recordId: String,
        label: String,
        items: List<OfflineMediaSpec>
    ) = queueOfflineEntry(context, recordType, "", label, items, kind = OutboxKind.ATTACH, targetId = recordId)

    /**
     * Keep an edit that takes several requests on the device, replayed in order as one unit when the
     * connection returns (see [OutboxKind.SEQUENCE]), then attach [items] to [targetId].
     */
    suspend fun queueSequence(
        context: Context,
        type: String,
        targetId: String,
        label: String,
        steps: List<OutboxStep>,
        items: List<OfflineMediaSpec>
    ) = queueOfflineEntry(context, type, "", label, items, kind = OutboxKind.SEQUENCE, targetId = targetId, steps = steps)

    /**
     * Keep a small non-record write ([type] is "comment", "feedback" or "task-progress") on the device
     * and send it when the connection returns. [targetId] is the task, for "task-progress".
     */
    suspend fun queueAction(context: Context, type: String, payloadJson: String, label: String, targetId: String? = null) =
        queueOfflineEntry(context, type, payloadJson, label, emptyList(), kind = OutboxKind.ACTION, targetId = targetId)

    /** Queue an offline entry with a fully specified media list (e.g. attachments + stage captures). */
    suspend fun queueOfflineEntry(
        context: Context,
        type: String,
        payloadJson: String,
        label: String,
        items: List<OfflineMediaSpec>,
        kind: String = OutboxKind.CREATE,
        targetId: String? = null,
        /** The key an online attempt already sent, so its replay is recognised as the same create. */
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
                // An ATTACH entry's record already exists, so its replay starts at the media.
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
        // Sync even if the app is never opened again: WorkManager runs the pass once there is a
        // network, across process death and reboots.
        OutboxSyncWorker.schedule(context)
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
            // Only this account's entries (and legacy ones with no owner). Another account's wait,
            // untouched, for that account to sign in again on this device.
            val queue = OfflineOutbox.all(context).replayableBy(currentUserId())
            // Read first, then reported, and reported before the connection is even checked: a queue
            // file that would not parse is the one problem the researcher cannot see for themselves,
            // because its only symptom is a count that quietly drops.
            OfflineOutbox.takeAlert()?.let { notifyUser(context, it) }
            if (!ConnectivityObserver.isOnline(context)) return@withLock 0
            var synced = 0
            for (queued in queue) {
                // A TYPED AADHAAR THIS PHONE COULD NOT KEEP (2026-10-18): the Keystore could not seal
                // it, or can no longer open the seal (a restore, a new device). Sending the rest would
                // replay an edit as "keep the number on record" — answered 200, the new number lost —
                // or a create as a bare 422. So the entry is held, on this phone's own word, until the
                // number is typed again from the outbox banner (`retypeOutboxAadhaar`). Not for an
                // entry whose record is already on the server: only its files are left to send.
                if (queued.createdId == null && AadhaarAtRest.numberWithheld(queued.payloadJson)) {
                    if (queued.failure != AadhaarAtRest.RETYPE_REASON) {
                        OfflineOutbox.markFailure(context, queued.id, AadhaarAtRest.RETYPE_REASON)
                        notifyUser(context, "\"${queued.label}\" needs the Aadhaar number again. Open the outbox to type it.")
                    }
                    continue
                }
                // Already triaged as permanent: this one is waiting on a person, not on the network.
                if (queued.failure != null) continue
                // A CARD SCANNED WITH NO SIGNAL links now: the pending card is resolved and the body
                // rewritten before anything is sent. Refused: held with the server's sentence.
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
        data class Rejected(val reason: String, val refusal: OutboxRefusal? = null) : ReplayOutcome

        /**
         * The server says this entry's Idempotency-Key was used for a different request. The record
         * may already be there. Keep everything and ask the researcher; never a silent refusal.
         */
        data class Review(val reason: String) : ReplayOutcome

        /**
         * The server held this artisan as a likely duplicate. Not a clash and not a refusal: keep
         * everything and ask "Same person" / "Different person" in the tray.
         */
        data class PossibleDuplicate(val candidates: List<OutboxDuplicateCandidate>) : ReplayOutcome
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
        // An entry queued before keys existed gets one now, written down BEFORE the first send, so
        // every later replay of it carries the same key.
        if (entry.createdId == null && entry.idempotencyKey == null &&
            (entry.kind == OutboxKind.CREATE || entry.kind == OutboxKind.ACTION)
        ) {
            entry = entry.copy(idempotencyKey = java.util.UUID.randomUUID().toString())
            OfflineOutbox.update(context, entry)
        }
        if (entry.createdId == null && entry.kind == OutboxKind.SEQUENCE) {
            // One ordered unit: each step is ticked off on disk as it lands, so a pass that stops
            // resumes at the next step and never re-sends one the server already took.
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
                        // Read once: the body names the likely duplicates when the server held it.
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
                replayRefusedFilesSentence(
                    refused,
                    unlinked = entry.kind == OutboxKind.ATTACH && entry.targetId.isNullOrBlank()
                )
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
    /** A 422 about the Idempotency-Key itself (backend `app/core/idempotency.py`), not the record. */
    private fun isKeyReused(error: Throwable): Boolean =
        error is HttpException && error.code() == 422 &&
            error.response()?.headers()?.get("Idempotency-Status") == "key-reused"

    /** [isTransient], for a form deciding whether a failed online create may go to the outbox. */
    fun isTransientFailure(error: Throwable): Boolean = isTransient(error)

    private fun isTransient(error: Throwable): Boolean = when (error) {
        is HttpException -> when (val code = error.code()) {
            // The credential expired, not the record. Every entry would fail this way and re-signing
            // in fixes all of them at once, so this is the one 4xx that must not condemn an entry.
            401 -> true
            408, 429 -> true
            // The server is still running the first send of this same Idempotency-Key. Not a refusal.
            409 -> error.response()?.headers()?.get("Idempotency-Status") == "in-progress"
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

    private fun List<DuplicateCandidateDto>.toOutboxCandidates(): List<OutboxDuplicateCandidate> =
        take(5).map { OutboxDuplicateCandidate(it.artisan.id, it.artisan.name, it.artisan.place, it.artisan.craft) }

    /** What a replayed create produced: the record's server id, plus a process's step ids in order. */
    internal data class CreatedRecord(val id: String, val stepIds: List<String> = emptyList())

    /**
     * Send a queued create. Internal so `OutboxReplayBytesTest` can prove a replay puts the SAME
     * bytes and the SAME key on the wire as the online attempt it stands in for: the server
     * fingerprints the body, and a replay that differs by a byte is a 422 instead of the record.
     */
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
        // THE OUTBOX REPLAYS WHAT WAS CAPTURED, NOT WHAT IS CURRENT - and this branch reads as if
        // it needs no attention, which is exactly why both halves are written down here.
        //
        // 1. A payload queued by THIS build already carries questionnaireId: the capture form sets it
        //    on the request object before that object is serialised into entry.payloadJson, so an
        //    interview taken today and replayed next week still files on the instrument the
        //    researcher was actually looking at. Nobody may "helpfully" strip it on the way out.
        // 2. A payload queued by an OLDER build carries NOTHING, and must not be guessed at. Do not
        //    patch a default in here: the handset does not know which instrument was current when the
        //    researcher sat down, and a guess is precisely the silent mis-filing the container model
        //    exists to prevent. The server resolves an absent field (the workshop's bound instrument,
        //    else the default), which is the only honest answer available at this point.
        //
        //    That resolution happens AT REPLAY TIME, so an admin must not flip the default until
        //    every handset's outbox has drained - "the release has rolled out" is not sufficient,
        //    "the queues are empty" is. The sync screen already reports pending entries.
        "questionnaire" -> CreatedRecord(
            api.createQuestionnaireInterview(
                entry.idempotencyKey,
                offlineJson.decodeFromString<QuestionnaireInterviewCreateRequest>(entry.payloadJson)
            ).id
        )
        // Steps come back in submit order, so `stepIndex` on a queued file selects the matching one.
        "process" -> api.createProcess(entry.idempotencyKey, offlineJson.decodeFromString<ProcessCreateRequest>(entry.payloadJson))
            .let { detail -> CreatedRecord(detail.id, detail.steps.map { it.id }) }
        else -> throw IllegalStateException("Unknown offline entry type: ${entry.type}")
    }

    /**
     * Replay an edit made with no signal onto the existing record.
     *
     * CONFLICTS: last write wins, exactly as an online edit does. The form sends the record as it
     * stood when the researcher opened it plus their changes, and the server applies it. A record
     * deleted in the meantime answers 404, which the pass records as a refusal and keeps on the device.
     */
    private suspend fun updateFromEntry(entry: PendingEntry): CreatedRecord {
        // A missing id is as permanent as a bad payload: the next pass reads the same bytes.
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

    /** Send one step of a [OutboxKind.SEQUENCE] entry. */
    private suspend fun performStep(step: OutboxStep) {
        when (step.op) {
            "questionnaire.update" -> api.updateInterview(
                step.targetId ?: throw SerializationException("Queued interview edit has no interview id"),
                offlineJson.decodeFromString<QuestionnaireInterviewUpdateRequest>(step.payloadJson)
            )
            else -> throw SerializationException("Unknown queued step: ${step.op}")
        }
    }

    /** Replay a small non-record write. Its "created id" only marks it as done. */
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
                // Blank for a batch queued from Upload media with no entry picked: unlinked, as online.
                linkedRecordId = linkedRecordId.blankToNull(),
                // When it was captured, not when the signal came back.
                recordedAt = pm.capturedAt ?: Instant.now().toString(),
                location = pm.location,
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
        // THE MARKER TRAVELS WITH THE NUMBER, because the two are only ever correct together. A bare
        // `Double?` here is precisely how a model's estimate reached a record wearing a researcher's
        // name: the caller had nothing to say a machine produced it. See MeasurementMarkers.kt.
        return MeasurementReading(
            valueInches = response.analysis?.valueInches,
            marker = response.methodMarker,
        )
    }

    /**
     * Analyse a single grid-sheet photo for BOTH length and breadth at once (the object's footprint
     * on the grid). Calls the measurement endpoint with no dimension, which returns the legacy
     * length+breadth pair. Returns (lengthInches, breadthInches); either may be null if unread.
     */
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
        // One marker for the pair, because one photograph and one model call produced both numbers.
        return MeasurementReading(
            lengthInches = analysis?.lengthInches,
            breadthInches = analysis?.breadthInches,
            marker = response.methodMarker,
        )
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

/**
 * THE ONE WORKSHOP A SCOPE CAN ALSO BE SENT AS — or null when it cannot be sent as one at all.
 *
 * The singular `workshopId` parameter rides beside the plural `workshopIds` on the routes that
 * accept both, so that a handset updated ahead of the API still narrows: FastAPI ignores a query
 * parameter it does not declare, in silence, so a client that sent ONLY the plural to a server that
 * had not yet grown it would get the whole table back and render it as one workshop's records. See
 * `FieldRepositoryApi.interviews`'s `workshopId`, and `artisansForCraftsPage` for the same argument
 * about `craftId`/`craftIds`.
 *
 * IT REFUSES EVERY SCOPE IT CANNOT SAY EXACTLY, and that is the whole point of it being a function
 * rather than `workshopIds?.firstOrNull()`:
 *
 *   - TWO OR MORE WORKSHOPS collapse to null. Sending the first id would answer a NARROWER question
 *     than the one asked, with nothing on screen to say which of the ticked workshops was dropped —
 *     and against a server that DOES understand the plural, both parameters narrow, so the first id
 *     would win and the others would vanish.
 *   - THE RESERVED `none` collapses to null. `workshopId=none` tests the column against the literal
 *     string "none" and matches nothing whatsoever, rather than the records linked to no workshop
 *     that the sentinel exists to name — an empty screen where the answer was "these seven".
 *
 * In both refused cases the plural still goes out alone, so a current server answers correctly and
 * only a stale one is wide. The alternative — narrowing wrongly but confidently — is worse, because
 * a wide list is visibly wide and a silently-narrowed one is not.
 *
 * Blanks and duplicates are dropped first, exactly as [toQueryCsv] drops them, so the two parameters
 * are always derived from the same normalised selection and cannot describe different scopes.
 */
private fun List<String>?.singleWorkshopIdOrNull(): String? =
    this?.mapNotNull { it.blankToNull() }
        ?.distinct()
        ?.singleOrNull()
        ?.takeIf { it != UNASSIGNED_WORKSHOP }

// ---------------------------------------------------------------------------
// The name a captured file is uploaded under (NAMING 2.1, data/Naming.kt):
//
//     Portal_Entity_Date_Time_Place_Craft_Subject_What_Sequence.ext
//
// It used to be `K_1_RASHPALSINGHJAMMUKASHMIRBAMBOOSECTIONKL_000137_010720261728.m4a`: every word
// run together, every part a code. The name is also the S3 leaf, so it is ASCII, and it is built
// by the same helper (and the same golden table) as the server's and the browser's names.
// ---------------------------------------------------------------------------

/** What each kind of file is called in a name. Plain words — never the old IMG/VID/AUD codes. */
private val NAME_KIND_WORD = mapOf(
    "IMAGE" to "Photo",
    "VIDEO" to "Video",
    "AUDIO" to "Recording",
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

private val NON_LETTERS = Regex("[^a-z]")

/**
 * The pieces of one questionnaire clip's base name, as the interview screen minted it before
 * 18 Oct 2026: `{SECTION}_{QUESTION}_{NAME}_{DURATIONHHMMSS}_{STAMPDDMMYYYYHHMM}` with an optional
 * trailing clip number. Still parsed, for a clip queued by an older build of this screen.
 */
private data class QuestionnaireClip(
    val section: String?,
    val answer: Int?,
    val name: String,
    val stamp: String,
    val clip: Int?
)

/**
 * The NAMING 2.1 name for one file about to be uploaded (`data/Naming.kt`), which is also its S3 leaf:
 *
 *     FieldRepository_Artisan_2026-07-01_1824_Giriraj-Prasad-Chhipa_Photo_02.jpg
 *     FieldRepository_Tool_2026-06-20_1200_Ringal-Splitting-Knife_Grid-Measurement-Height_01.jpg
 *     FieldRepository_Process_2026-06-21_1430_Dabu-Printing_Step-2-Video_01.mp4
 *     FieldRepository_Interview_2026-07-01_1824_Giriraj_Recording_K-Q01.m4a
 *
 * [overrideBaseName] is the interview screen's own clip name. One already in the convention is
 * kept as it is; one in the old `K_1_…` shape is read for the section, the question and the moment
 * of capture, then spelled in the convention.
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

/** The old ddMMyyyyHHmm capture stamp (device zone) as an instant, or null when it is not one. */
private fun stampInstant(stamp: String): java.time.Instant? = runCatching {
    java.time.LocalDateTime.parse(stamp, java.time.format.DateTimeFormatter.ofPattern("ddMMyyyyHHmm"))
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
}.getOrNull()

private fun twoDigits(index: Int): String = index.coerceAtLeast(1).toString().padStart(2, '0')

/** What this file IS (the What field) and which of its kind (the Sequence field). */
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

    // `stageStep` is the tool form's numbered process stage; `customSegment` is the process form's
    // `STEP_1A` / `STEP_2_G1`, whose trailing letter distinguishes files within a step and is what
    // the file index already says.
    val step = stageStep ?: SEGMENT_STEP.find(customSegment.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    if (step != null || tag == "processstep") {
        return "${step?.let { "Step-$it" } ?: "Step"}-$kind" to twoDigits(index)
    }

    val isPreProcess = customSegment?.trim().equals("PRE", ignoreCase = true) ||
        CAPTION_PRE_PROCESS.containsMatchIn(caption.orEmpty().trim())
    if (isPreProcess) return "Pre-Process-$kind" to twoDigits(index)

    return kind to twoDigits(index)
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

/** The section and question a caption names, or as much of it as the caption can prove. */
private fun interviewSectionAnswer(caption: String?): Pair<String, Int?>? {
    val text = caption.orEmpty().trim()
    CAPTION_QUESTION.find(text)?.let { return it.groupValues[1].uppercase() to it.groupValues[2].toIntOrNull() }
    CAPTION_SECTION.find(text)?.let { return it.groupValues[1].uppercase() to null }
    return null
}

/**
 * What and Sequence for one old-shape interview clip: `Recording` + `D-All` for a whole section
 * (it answers no single question), `Recording` + `K-Q01` for one answer, `-T2` for a second take.
 */
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
    // Both slots fall back to the literal "SEC" when the screen had no code to put there.
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

private fun splitCamel(value: String?): String = CAMEL_BOUNDARY.replace(value.orEmpty(), " ")

/** Only lowercase letters, so `GRID_HEIGHT`, `grid-height-9.jpg` and "Height grid" all compare alike. */
private fun nameLetters(value: String?): String = value.orEmpty().lowercase().replace(NON_LETTERS, "")

/**
 * Drop a tail the What field is about to repeat.
 *
 * The measurement screen hands down "Ringal splitting knife measurement grid" as the record name,
 * which followed by `Grid-Measurement-Height` says "grid" twice and "measurement" twice. The record
 * is the knife; the What is what the photo is of.
 */
private fun trimRedundantTail(recordName: String, what: String): String {
    if (!what.startsWith("Grid-Measurement")) return recordName
    val trimmed = recordName.trim()
    if (!nameLetters(trimmed).endsWith("measurementgrid")) return trimmed
    val cut = trimmed.lowercase().lastIndexOf("measurement")
    // A record genuinely called nothing but "measurement grid" keeps its name.
    return if (cut > 0) trimmed.substring(0, cut).trim() else trimmed
}

/**
 * Why a replayed entry's files were kept back. A record or an attach-to-record entry names the
 * record they belong on; a batch queued from Upload media with no entry picked has none, so it only
 * says which files and why.
 */
internal fun replayRefusedFilesSentence(refused: List<String>, unlinked: Boolean): String {
    val reasons = refused.distinct().joinToString(" ")
    return if (unlinked) "${countNoun(refused.size, "file")} didn't upload: $reasons"
    else "Saved, but ${countNoun(refused.size, "file")} didn't upload: $reasons Attach them again."
}

/** A craft-list row in the shape the lookups and the search panel already read. */
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
