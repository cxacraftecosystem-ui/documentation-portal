package com.fieldrepository.app.ui

import android.content.Context
import com.fieldrepository.app.BuildConfig
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
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

const val STT_KEY_NO_KEY = "NO_KEY"
const val STT_KEY_UNTESTED = "UNTESTED"
const val STT_KEY_FAILING = "FAILING"
const val STT_KEY_PASSING = "PASSING"

@Serializable
data class SttProviderDto(
    val id: String,
    val name: String = "",
    val keyName: String = "",
    val keyLabel: String = "",
    val configured: Boolean = false,
    val keyState: String = STT_KEY_UNTESTED,
    val rankable: Boolean = false,
    val frozenReason: String? = null,
    val testedAt: String? = null,
    val testError: String? = null
)

@Serializable
data class SttProviderOrderDto(
    val providers: List<SttProviderDto> = emptyList(),
    val effectiveChain: List<String> = emptyList(),
    val verifiedChain: List<String> = emptyList(),
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

object SttProviderClient {
    @Volatile
    private var cached: Retrofit? = null

    @Volatile
    private var ladder: SttProviderApi? = null

    @Volatile
    private var settings: TranscriptionSettingsApi? = null

    private fun retrofit(context: Context): Retrofit =
        cached ?: synchronized(this) { cached ?: build(context.applicationContext).also { cached = it } }

    fun get(context: Context): SttProviderApi =
        ladder ?: retrofit(context).create(SttProviderApi::class.java).also { ladder = it }

    fun transcription(context: Context): TranscriptionSettingsApi =
        settings ?: retrofit(context).create(TranscriptionSettingsApi::class.java).also { settings = it }

    private fun build(context: Context): Retrofit {
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
    }

    private val GATEWAY_CODES = setOf(502, 503, 504)
}

val STT_PROVIDER_BLURBS: Map<String, String> = mapOf(
    "elevenlabs" to "Best for Indian languages and accents. Slowest, priciest.",
    "deepgram" to "Fastest and cheapest. Weaker with background noise.",
    "whisper" to "Reliable fallback. No speaker separation."
)

val BUILT_IN_STT_PROVIDERS: List<SttProviderDto> = listOf(
    SttProviderDto(id = "elevenlabs", name = "ElevenLabs", keyLabel = "ElevenLabs Scribe", keyName = "ELEVENLABS_API_KEY"),
    SttProviderDto(id = "deepgram", name = "Deepgram", keyLabel = "Deepgram", keyName = "DEEPGRAM_API_KEY"),
    SttProviderDto(id = "whisper", name = "Whisper (OpenAI)", keyLabel = "OpenAI", keyName = "OPENAI_API_KEY"),
    SttProviderDto(id = "muse", name = "Meta Muse", keyLabel = "Meta Muse", keyName = "META_MODEL_API_KEY")
)

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

data class SttTrouble(
    val headline: String,
    val advice: String,
    val retryable: Boolean
)

fun describeSttTrouble(error: Throwable, action: String): SttTrouble {
    val status = (error as? HttpException)?.code() ?: 0
    val sentence = error.apiErrorMessage("").takeIf { it.isNotBlank() }

    return when {
        status == 404 -> SttTrouble(
            headline = "The transcription order couldn't be loaded.",
            advice = "Nothing is lost. Try again shortly.",
            retryable = true
        )
        status == 403 -> SttTrouble(
            headline = "You don't have access to this.",
            advice = "Needs Admin or above. Ask a master admin.",
            retryable = false
        )
        status == 401 -> SttTrouble(
            headline = "Your session has ended.",
            advice = "Sign in again and come back.",
            retryable = false
        )
        status >= 500 -> SttTrouble(
            headline = "Something went wrong.",
            advice = "Try again in a minute.",
            retryable = true
        )
        status == 0 -> SttTrouble(
            headline = "Couldn't connect.",
            advice = "Check your connection and try again.",
            retryable = true
        )
        else -> SttTrouble(
            headline = "Couldn't $action the transcription order.",
            advice = sentence ?: "Try again.",
            retryable = true
        )
    }
}

fun sttTestedAgo(iso: String?, now: Instant = Instant.now()): String {
    if (iso.isNullOrBlank()) return ""
    val then = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(iso) }.getOrNull()
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

fun joinSttNames(names: List<String>): String = when {
    names.isEmpty() -> ""
    names.size == 1 -> names[0]
    else -> "${names.dropLast(1).joinToString(", ")} and ${names.last()}"
}
