package com.fieldrepository.app.data

import com.fieldrepository.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
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

    /**
     * The credential writes: a password change, a link redemption, a link minted. Each is sent ONCE —
     * see [sentOnce] — because the first copy may already have landed (the password changed, every
     * older session retired, the link spent) and a silent second copy would be refused with words
     * that contradict what actually happened.
     */
    private fun isCredentialWrite(method: String, path: String): Boolean =
        method.equals("POST", ignoreCase = true) && (
            path.endsWith("/auth/change-password") ||
                path.endsWith("/auth/set-password") ||
                path.endsWith("/auth/password-links")
            )

    /**
     * This request with its body marked ONE-SHOT, OkHttp's word for "never transmit this twice".
     * `retryOnConnectionFailure(true)` would otherwise let OkHttp resend a POST by itself after a
     * connection failed mid-exchange. Ported from the Design Prototype Workshop's `ApiClient`.
     */
    private fun Request.sentOnce(): Request {
        val original = body ?: return this
        if (original.isOneShot()) return this
        return newBuilder().method(method, object : RequestBody() {
            override fun contentType(): MediaType? = original.contentType()
            override fun contentLength(): Long = original.contentLength()
            override fun writeTo(sink: BufferedSink) = original.writeTo(sink)
            override fun isOneShot(): Boolean = true
        }).build()
    }

    /** Is this a request to the API itself: its scheme, host and port, and a path under the base? */
    private fun HttpUrl.isUnder(base: HttpUrl): Boolean =
        scheme == base.scheme && host == base.host && port == base.port &&
            encodedPath.startsWith(base.encodedPath)

    /**
     * Puts the session on every request, and tells the session root what the answers say about it.
     *
     * HEADERS ONLY, BOTH WAYS: the body belongs to the caller, and reading it here would consume it.
     * Each caller still gets its own 401 and triages it as it always has — the outbox keeps its work
     * on one — so the two signals change nothing a caller sees. They only make sure the screen learns
     * what the server has said about the SESSION: that the account owes a new password
     * ([PasswordChangeSignal]), or that the session has ended because the password was changed or an
     * administrator signed the account out everywhere ([SessionEndedSignal]).
     */
    internal fun sessionInterceptor(tokenStore: TokenStore, apiBase: HttpUrl?): Interceptor =
        Interceptor { chain ->
            val token = tokenStore.getToken()
            val outgoing = chain.request().let {
                if (isCredentialWrite(it.method, it.url.encodedPath)) it.sentOnce() else it
            }
            val request = if (token.isNullOrBlank()) {
                outgoing
            } else {
                outgoing.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            }
            val response = chain.proceed(request)
            val gate = response.header(PASSWORD_CHANGE_REQUIRED_HEADER)
            if (!token.isNullOrBlank() && isPasswordChangeRequired(response.code, gate)) {
                PasswordChangeSignal.raise()
            }
            // The store is read AGAIN after the answer: a token replaced while this request was in
            // flight (a password change adopting a fresh one, a sign-out) means the 401 is about a
            // session this handset has already left. See [isSessionEnded].
            if (apiBase != null && request.url.isUnder(apiBase) &&
                isSessionEnded(response.code, gate, token, tokenStore.getToken())
            ) {
                SessionEndedSignal.raise()
            }
            response
        }

    /** Linear-ish backoff with a hard cap, so a struggling origin gets breathing room without long stalls. */
    private fun backoffMillis(attempt: Int): Long = minOf(4_000L, 600L * attempt)

    /**
     * THE decoder for anything this API says — the converter's, and the one every hand-rolled parse
     * must borrow rather than re-declare.
     *
     * It used to be a local inside [retrofit], which was fine while Retrofit was the only thing
     * decoding. It is not any more: `FieldRepository.readManifest` decodes the streamed download
     * manifest a line at a time (see `data/ManifestStream.kt`), and a second `Json { }` built
     * beside this one would be a second set of leniency rules over the same wire format. That is
     * not hypothetical — every setting below exists because a real response needed it, and a
     * streamed manifest entry is the same JSON object the typed DTO carries. One spelling.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // The API serializes Prisma Decimal columns (measurements, costs) as JSON *strings*
        // (e.g. "12.5"). isLenient lets numeric DTO fields decode from quoted values, and
        // coerceInputValues falls back to defaults if a non-null field arrives null — together
        // these stop a single measured record from failing an entire list deserialization.
        isLenient = true
        coerceInputValues = true
    }

    /**
     * [readCacheDir], when given, turns on [OfflineReadCache]: every screen's last answer is kept on the
     * device and served when there is no connection. The app passes it; JVM tests do not.
     */
    fun create(tokenStore: TokenStore, readCacheDir: File? = null): FieldRepositoryApi =
        retrofit(tokenStore, readCacheDir).create(FieldRepositoryApi::class.java)

    /**
     * The configured Retrofit — the gateway retry, the auth header, the timeouts and the lenient JSON
     * above — so a feature can declare its OWN typed service without standing up a second HTTP stack
     * beside this one. A second stack is not a style question here: it would silently opt that
     * feature out of the 504 retry that exists because CloudFront times out this origin, and out of
     * the Decimal-as-string leniency that keeps one measured record from failing a whole list.
     */
    fun retrofit(tokenStore: TokenStore, readCacheDir: File? = null): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            // FIRST, so its offline fallback wraps the retry below rather than the other way round.
            .apply { if (readCacheDir != null) OfflineReadCache.install(this, readCacheDir) }
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
                // A cache-only read (OfflineReadCache's fallback) never touches the network, and its
                // miss is a synthetic 504 that retrying cannot change.
                val retriable = !original.cacheControl.onlyIfCached &&
                    isSafelyRetriable(original.method, original.url.encodedPath)
                val maxAttempts = if (retriable) MAX_GATEWAY_ATTEMPTS else 1
                var attempt = 0
                var lastError: IOException? = null
                while (attempt < maxAttempts) {
                    attempt++
                    try {
                        val response = chain.proceed(original)
                        // A cache-only read answered from disk says nothing about the server.
                        if (!original.cacheControl.onlyIfCached) ServerReachability.recordAnswer()
                        if (retriable && response.code in RETRIABLE_GATEWAY_CODES && attempt < maxAttempts) {
                            response.close()
                            runCatching { Thread.sleep(backoffMillis(attempt)) }
                            continue
                        }
                        return@addInterceptor response
                    } catch (e: IOException) {
                        lastError = e
                        if (!original.cacheControl.onlyIfCached) ServerReachability.recordFailure(e)
                        if (!retriable || attempt >= maxAttempts) throw e
                        runCatching { Thread.sleep(backoffMillis(attempt)) }
                    }
                }
                throw lastError ?: IOException("Request failed after $maxAttempts attempts")
            }
            .addInterceptor(sessionInterceptor(tokenStore, BuildConfig.DEFAULT_API_BASE_URL.toHttpUrlOrNull()))
            // X-Client-Time and X-App-Version on every request, and the clock offset off every
            // answer (questionnaire v2, DESIGN §3.9). Headers only; the server logs the version.
            .addInterceptor(ClientClock.interceptor())
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.DEFAULT_API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
