package com.fieldrepository.app.data

import com.fieldrepository.app.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {
    // Gateway/proxy timeouts that mean "the origin was too slow", not "the request was bad". CloudFront
    // returns 504 when the EC2 origin doesn't respond within its origin-response timeout, and 502/503
    // under transient origin trouble — all worth a quick retry rather than surfacing as a hard failure.
    private val RETRIABLE_GATEWAY_CODES = setOf(502, 503, 504)
    private const val MAX_GATEWAY_ATTEMPTS = 4

    /**
     * Only requests that are safe to repeat are auto-retried, so a 504 (where the origin may or may not
     * have already processed the call) can never create a duplicate record. GETs are always safe; among
     * POSTs only the side-effect-free upload-setup calls qualify — presigning a URL or starting/aborting
     * a multipart upload can be re-issued harmlessly. Record-creating calls (complete, create*, update*)
     * are deliberately excluded; their resilience comes from the save/back-guard flow instead.
     */
    private fun isSafelyRetriable(method: String, path: String): Boolean {
        if (method.equals("GET", ignoreCase = true)) return true
        if (method.equals("POST", ignoreCase = true)) {
            return path.endsWith("/media/presign") ||
                path.endsWith("/media/multipart/create") ||
                path.endsWith("/media/multipart/presign-parts") ||
                path.endsWith("/media/multipart/abort")
        }
        return false
    }

    /** Linear-ish backoff with a hard cap, so a struggling origin gets breathing room without long stalls. */
    private fun backoffMillis(attempt: Int): Long = minOf(4_000L, 600L * attempt)

    fun create(tokenStore: TokenStore): FieldRepositoryApi {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            // The API serializes Prisma Decimal columns (measurements, costs) as JSON *strings*
            // (e.g. "12.5"). isLenient lets numeric DTO fields decode from quoted values, and
            // coerceInputValues falls back to defaults if a non-null field arrives null — together
            // these stop a single measured record from failing an entire list deserialization.
            isLenient = true
            coerceInputValues = true
        }

        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            // Mobile data is slower and drops connections more than Wi-Fi, so allow generous timeouts
            // and let OkHttp retry a connection that fails mid-handshake (e.g. a NAT64 path settling).
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Outermost interceptor: transparently retry safe requests on gateway timeouts (HTTP 504 and
            // friends) and on transport errors, with backoff. This re-runs the whole chain each attempt
            // (so the auth header below is freshly applied), turning a flaky/overloaded origin into a
            // brief delay instead of an "Upload failed" the user sees. Unsafe requests pass through once.
            .addInterceptor { chain ->
                val original = chain.request()
                val retriable = isSafelyRetriable(original.method, original.url.encodedPath)
                val maxAttempts = if (retriable) MAX_GATEWAY_ATTEMPTS else 1
                var attempt = 0
                var lastError: IOException? = null
                while (attempt < maxAttempts) {
                    attempt++
                    try {
                        val response = chain.proceed(original)
                        if (retriable && response.code in RETRIABLE_GATEWAY_CODES && attempt < maxAttempts) {
                            response.close()
                            runCatching { Thread.sleep(backoffMillis(attempt)) }
                            continue
                        }
                        return@addInterceptor response
                    } catch (e: IOException) {
                        lastError = e
                        if (!retriable || attempt >= maxAttempts) throw e
                        runCatching { Thread.sleep(backoffMillis(attempt)) }
                    }
                }
                throw lastError ?: IOException("Request failed after $maxAttempts attempts")
            }
            .addInterceptor { chain ->
                val token = tokenStore.getToken()
                val request = if (token.isNullOrBlank()) {
                    chain.request()
                } else {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                }
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.DEFAULT_API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FieldRepositoryApi::class.java)
    }
}
