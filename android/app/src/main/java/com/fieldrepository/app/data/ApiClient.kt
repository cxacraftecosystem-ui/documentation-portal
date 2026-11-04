package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.data.ClientClock
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
    private val RETRIABLE_GATEWAY_CODES = setOf(502, 503, 504)
    private const val MAX_GATEWAY_ATTEMPTS = 4

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

    private fun isCredentialWrite(method: String, path: String): Boolean =
        method.equals("POST", ignoreCase = true) && (
            path.endsWith("/auth/change-password") ||
                path.endsWith("/auth/set-password") ||
                path.endsWith("/auth/password-links")
            )

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

    private fun HttpUrl.isUnder(base: HttpUrl): Boolean =
        scheme == base.scheme && host == base.host && port == base.port &&
            encodedPath.startsWith(base.encodedPath)

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
            if (apiBase != null && request.url.isUnder(apiBase) &&
                isSessionEnded(response.code, gate, token, tokenStore.getToken())
            ) {
                SessionEndedSignal.raise()
            }
            response
        }

    private fun backoffMillis(attempt: Int): Long = minOf(4_000L, 600L * attempt)

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    fun create(tokenStore: TokenStore, readCacheDir: File? = null): FieldRepositoryApi =
        retrofit(tokenStore, readCacheDir).create(FieldRepositoryApi::class.java)

    fun retrofit(tokenStore: TokenStore, readCacheDir: File? = null): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .apply { if (readCacheDir != null) OfflineReadCache.install(this, readCacheDir) }
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val retriable = !original.cacheControl.onlyIfCached &&
                    isSafelyRetriable(original.method, original.url.encodedPath)
                val maxAttempts = if (retriable) MAX_GATEWAY_ATTEMPTS else 1
                var attempt = 0
                var lastError: IOException? = null
                while (attempt < maxAttempts) {
                    attempt++
                    try {
                        val response = chain.proceed(original)
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
