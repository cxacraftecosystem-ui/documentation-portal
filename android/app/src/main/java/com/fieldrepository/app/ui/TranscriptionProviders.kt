package com.fieldrepository.app.ui

import android.content.Context
import com.fieldrepository.app.BuildConfig
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.data.apiErrorMessage
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/*
 * The speech-to-text provider ladder — transport, the four key states, and the freeze arithmetic.
 *
 * The Android half of `frontend/components/settings/ProviderOrderPanel.tsx` and of
 * `backend/app/services/app_settings.py`; the screen itself is [ProviderOrderPanel] in
 * ProviderOrderPanel.kt. Everything in this file is deliberately UI-free so the two rules that must
 * not be re-invented — which engine may be ranked, and what a failed request MEANS — can be read in
 * one place and compared against the web line by line.
 *
 * WHY THIS FILE OWNS ITS OWN RETROFIT SERVICE
 * -------------------------------------------
 * The three endpoints belong in `data/FieldRepositoryApi.kt`, reached through `FieldRepository`.
 * They are here instead because `FieldRepository.kt` is being edited by another stream in this same
 * working tree right now, and adding a method to a file somebody else is rewriting is how one of the
 * two changes silently disappears. `api` is private to `FieldRepository`, so there is no way to
 * borrow the instance it holds either. The transport below is therefore a deliberately small,
 * movable copy: three methods, the same `Json` configuration as `data/ApiClient.kt`, the same bearer
 * header from the same [TokenStore] (so it is the same session, not a second login), and the same
 * gateway retry on safe reads. Folding it into the repository later is a delete-and-re-point, and
 * that is the intended end state — see the report accompanying this change.
 */

// The four states a provider key can be in, mirroring `app_settings.STT_KEY_*`. Only the last one
// is rankable: having a key and having a key that WORKS are different claims, and a ranking that
// confuses them promises the next recording to an engine that will refuse it.
const val STT_KEY_NO_KEY = "NO_KEY"
const val STT_KEY_UNTESTED = "UNTESTED"
const val STT_KEY_FAILING = "FAILING"
const val STT_KEY_PASSING = "PASSING"

/** One engine as the ranking screen sees it. Carries no credential — see `TranscriptionProviderDto`. */
@Serializable
data class SttProviderDto(
    val id: String,
    val name: String = "",
    val keyName: String = "",
    val keyLabel: String = "",
    /** A key resolves, so the pipeline WILL call this engine — whether or not the key is any good. */
    val configured: Boolean = false,
    val keyState: String = STT_KEY_UNTESTED,
    /** Only a passing test earns this. */
    val rankable: Boolean = false,
    val frozenReason: String? = null,
    val testedAt: String? = null,
    val testError: String? = null
)

@Serializable
data class SttProviderOrderDto(
    val providers: List<SttProviderDto> = emptyList(),
    /** What the pipeline would really do right now — engines with no key at all already dropped. */
    val effectiveChain: List<String> = emptyList(),
    /** The subset of that which has actually answered a test. */
    val verifiedChain: List<String> = emptyList(),
    /** True when the server had to sink an unproven engine to keep the freeze true. */
    val normalised: Boolean = false,
    val normalisedNote: String? = null
)

@Serializable
data class SttProviderOrderBody(val order: List<String>)

interface SttProviderApi {
    @GET("settings/transcription-providers")
    suspend fun providerOrder(): SttProviderOrderDto

    @PUT("settings/transcription-providers")
    suspend fun setProviderOrder(@Body body: SttProviderOrderBody): SttProviderOrderDto

    @POST("settings/transcription-providers/{provider}/test")
    suspend fun testProvider(@Path("provider") provider: String): SttProviderOrderDto
}

/**
 * The one [SttProviderApi] this process uses, built on first need and kept for the app's lifetime.
 *
 * The token is read per request rather than captured, so signing out and back in on the same launch
 * is picked up without rebuilding anything — exactly as `data/ApiClient.kt` does it.
 */
object SttProviderClient {
    @Volatile
    private var cached: SttProviderApi? = null

    fun get(context: Context): SttProviderApi =
        cached ?: synchronized(this) { cached ?: build(context.applicationContext).also { cached = it } }

    private fun build(context: Context): SttProviderApi {
        val tokenStore = TokenStore(context)
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
        }
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // CloudFront answers 502/503/504 when the EC2 origin is slow or briefly unhealthy, which
            // is a wait rather than a failure. Only the GET is repeated: re-issuing the PUT or the
            // test POST could apply a ranking twice or bill a second probe.
            .addInterceptor { chain ->
                val request = chain.request()
                val retriable = request.method.equals("GET", ignoreCase = true)
                var attempt = 0
                var lastError: IOException? = null
                while (attempt < if (retriable) 3 else 1) {
                    attempt++
                    try {
                        val response = chain.proceed(request)
                        if (retriable && response.code in GATEWAY_CODES && attempt < 3) {
                            response.close()
                            runCatching { Thread.sleep(600L * attempt) }
                            continue
                        }
                        return@addInterceptor response
                    } catch (e: IOException) {
                        lastError = e
                        if (!retriable || attempt >= 3) throw e
                        runCatching { Thread.sleep(600L * attempt) }
                    }
                }
                throw lastError ?: IOException("Request failed after $attempt attempts")
            }
            .addInterceptor { chain ->
                val token = tokenStore.getToken()
                val request = if (token.isNullOrBlank()) {
                    chain.request()
                } else {
                    chain.request().newBuilder().header("Authorization", "Bearer $token").build()
                }
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.DEFAULT_API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SttProviderApi::class.java)
    }

    private val GATEWAY_CODES = setOf(502, 503, 504)
}

// ---------------------------------------------------------------------------------------------
// Editorial copy and the offline fallback
// ---------------------------------------------------------------------------------------------

/** Verbatim from the web panel's `BLURBS`. Names and key state come from the server; this does not. */
val STT_PROVIDER_BLURBS: Map<String, String> = mapOf(
    "elevenlabs" to "Strongest on Indian-language and accented speech. Slower, and the most expensive per minute.",
    "deepgram" to "Fastest and cheapest. Very good on clear audio, weaker under heavy background noise.",
    "whisper" to "The dependable fallback. Handles almost anything, with no diarisation of its own."
)

/**
 * What to draw when the server cannot be asked at all — the engines compiled into the transcription
 * chain, in the order that applies before anyone ranks anything.
 *
 * Marked unverified throughout, and never `configured`, because a screen that could not reach the
 * server knows nothing about anybody's keys and must not imply otherwise.
 */
val BUILT_IN_STT_PROVIDERS: List<SttProviderDto> = listOf(
    SttProviderDto(id = "elevenlabs", name = "ElevenLabs", keyLabel = "ElevenLabs Scribe", keyName = "ELEVENLABS_API_KEY"),
    SttProviderDto(id = "deepgram", name = "Deepgram", keyLabel = "Deepgram", keyName = "DEEPGRAM_API_KEY"),
    SttProviderDto(id = "whisper", name = "Whisper (OpenAI)", keyLabel = "OpenAI", keyName = "OPENAI_API_KEY")
)

// ---------------------------------------------------------------------------------------------
// The freeze arithmetic — mirrors app_settings.order_violations / freeze_unrankable
// ---------------------------------------------------------------------------------------------

/**
 * How many (frozen above proven) pairs this order contains. Zero means the freeze holds.
 *
 * COUNTED rather than asserted, because a stored order can go illegal without anybody touching it:
 * a key expires overnight and yesterday's legal ranking is illegal by morning. Judging a move by
 * whether it makes this number WORSE is what lets an admin repair such an order by hand, instead of
 * every arrow on the screen being dead because the list arrived already in breach.
 */
fun sttOrderViolations(list: List<SttProviderDto>): Int {
    var frozenSoFar = 0
    var total = 0
    for (provider in list) {
        if (provider.rankable) total += frozenSoFar else frozenSoFar += 1
    }
    return total
}

fun sttReorder(list: List<SttProviderDto>, from: Int, to: Int): List<SttProviderDto> {
    val next = list.toMutableList()
    next.add(to, next.removeAt(from))
    return next
}

fun sttMovePermitted(list: List<SttProviderDto>, from: Int, to: Int): Boolean {
    if (to < 0 || to >= list.size || from == to) return false
    return sttOrderViolations(sttReorder(list, from, to)) <= sttOrderViolations(list)
}

fun sameSttOrder(a: List<SttProviderDto>, b: List<SttProviderDto>): Boolean =
    a.size == b.size && a.indices.all { a[it].id == b[it].id }

// ---------------------------------------------------------------------------------------------
// Degrading honestly
// ---------------------------------------------------------------------------------------------

/** A failed request, rewritten as something a non-engineer can act on. */
data class SttTrouble(
    /** One line naming what happened, in the user's terms. */
    val headline: String,
    /** What it means and who can fix it. */
    val advice: String,
    /** The bit an engineer needs, kept out of the sentence above. */
    val technical: String,
    /** Whether pressing the same button again could plausibly work. */
    val retryable: Boolean
)

/**
 * Turn a failed request into an explanation.
 *
 * The default — showing whatever sentence the server sent — is how the word "Not Found" ended up as
 * the entire account of an endpoint that had not been deployed yet. FastAPI's 404 body carries the
 * literal string "Not Found" and nothing else, so there is no sentence to surface: it has to be
 * written here, against the status, or it does not exist. This is not a translation of the error,
 * it is a replacement for it.
 */
fun describeSttTrouble(error: Throwable, action: String): SttTrouble {
    val status = (error as? HttpException)?.code() ?: 0
    val sentence = error.apiErrorMessage("").takeIf { it.isNotBlank() }
    val technical = buildString {
        append("HTTP ")
        append(if (status == 0) "—" else status.toString())
        append(" from /settings/transcription-providers")
        if (sentence != null) append(" — “$sentence”")
    }

    return when {
        status == 404 -> SttTrouble(
            headline = "This server does not have the provider ranking yet.",
            advice = "The app is newer than the API it is talking to — the address it asked for simply is not " +
                "there. Nothing is wrong with your account or your recordings, and no setting has been lost. " +
                "Whoever deploys the backend needs to release the current version; until they do, the order " +
                "below is the app's built-in default rather than the live one, and cannot be changed from here.",
            technical = technical,
            retryable = true
        )
        status == 403 -> SttTrouble(
            headline = "Your account is not allowed to see or change this ranking.",
            advice = "Choosing which engine transcribes recordings needs the Admin role or above. Ask a master " +
                "admin either to raise your role or to make the change for you — this is a permission, not a " +
                "fault, so retrying will give the same answer.",
            technical = technical,
            retryable = false
        )
        status == 401 -> SttTrouble(
            headline = "Your session has ended.",
            advice = "Sign in again and come back to this screen; the ranking itself is untouched.",
            technical = technical,
            retryable = false
        )
        status >= 500 -> SttTrouble(
            headline = "The server ran into a problem of its own.",
            advice = "This one is on the API side, not on anything you did, and it is not fixable from this " +
                "screen. Give it a minute and tap Try again. If it keeps happening, send whoever looks after " +
                "the backend the line below and roughly what time it was — that is enough to find it in the logs.",
            technical = technical,
            retryable = true
        )
        // No HTTP status at all: a socket that never opened, a DNS failure, a timeout. On a phone in
        // the field this is the likeliest of all of them, so it is named as such rather than lumped
        // in with "something went wrong".
        status == 0 -> SttTrouble(
            headline = "The phone could not reach the server.",
            advice = "No answer came back, which is usually this handset's connection rather than the " +
                "repository being down. Check you are online — mobile data or Wi-Fi — and tap Try again.",
            technical = technical,
            retryable = true
        )
        else -> SttTrouble(
            headline = "The server refused to $action the provider order.",
            advice = sentence ?: "It gave no reason. Tap Try again, and tell an administrator if it persists.",
            technical = technical,
            retryable = true
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------------------------

/** "just now" / "12 minutes ago" / "3 days ago". Empty when the stamp is missing or unparseable. */
fun sttTestedAgo(iso: String?, now: Instant = Instant.now()): String {
    if (iso.isNullOrBlank()) return ""
    val then = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(iso) }.getOrNull()
        // The server serialises a naive datetime for verdicts recorded without a zone; read those as
        // UTC, which is what the database stores, rather than dropping the timestamp entirely.
        ?: runCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: return ""
    val seconds = maxOf(0L, (now.toEpochMilli() - then.toEpochMilli()) / 1000L)
    if (seconds < 60) return "just now"
    val minutes = (seconds / 60.0).roundToLong()
    if (minutes < 60) return if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
    val hours = (minutes / 60.0).roundToLong()
    if (hours < 48) return if (hours == 1L) "1 hour ago" else "$hours hours ago"
    return "${(hours / 24.0).roundToLong()} days ago"
}

/** "A", "A and B", "A, B and C" — the web panel's `join`. */
fun joinSttNames(names: List<String>): String = when {
    names.isEmpty() -> ""
    names.size == 1 -> names[0]
    else -> "${names.dropLast(1).joinToString(", ")} and ${names.last()}"
}
