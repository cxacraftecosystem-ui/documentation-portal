package com.fieldrepository.app.data

import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException

/**
 * The last answer to every read, kept on the device so a screen opened with no signal shows what it
 * showed last time instead of an error.
 *
 * HOW. OkHttp's own disk cache, plus two interceptors:
 *  - a NETWORK interceptor marks each cacheable JSON answer `max-age=0` (store it, but revalidate it
 *    whenever the server can be asked), and everything else `no-store`, so a 48 MB dataset zip or a
 *    presigned download is never written here. NOT `no-cache`: OkHttp refuses to serve a `no-cache`
 *    entry to an `only-if-cached` request, which would make the fallback below always miss. A 304
 *    keeps `max-age=0` too, because its headers are merged into the stored entry;
 *  - an APPLICATION interceptor, outermost, catches the `IOException` a read throws when there is no
 *    connection and asks the cache alone (`only-if-cached`). A hit is returned as if it came from the
 *    server; a miss rethrows the original error, so a screen that has never been opened fails exactly
 *    as it did before.
 * Only a transport failure falls back. A 401, a 403 or a 500 is the server's answer and is passed on.
 *
 * WHAT IS NEVER CACHED ([isCacheablePath]): sign-in and two-step state, stored secrets and AI keys,
 * Aadhaar lookups, the app-update check, downloads and exports, the late-submission check (a stale
 * "this workshop is still open" would be wrong), and `preferences/me` (its caller treats "no answer"
 * as "keep this device's choice", and a stale row would undo a change made offline).
 *
 * WHERE. `noBackupFilesDir`: survives reboots and app updates, is not reclaimed under storage
 * pressure the way `cacheDir` is, and is excluded from cloud backup, because it holds the account's
 * records. [clear] runs on sign-out so the next account never sees them.
 */
object OfflineReadCache {
    private const val MAX_BYTES = 40L * 1024 * 1024
    private const val HEADER_CACHE_CONTROL = "Cache-Control"

    @Volatile
    private var cache: Cache? = null

    /** Paths (relative to the API base) whose answers must never be kept on the device. */
    private val NEVER_CACHED = listOf(
        "auth/", "secrets", "me/ai-keys", "preferences/", "app/release", "export/", "data/manifest",
        "data/report", "data/media/", "artisans/lookup", "submission-check"
    )

    internal fun isCacheablePath(encodedPath: String): Boolean {
        val path = encodedPath.trimStart('/')
        return NEVER_CACHED.none { blocked -> path.contains(blocked) }
    }

    /** Attach the cache and both interceptors. Call before any other `addInterceptor`. */
    fun install(builder: OkHttpClient.Builder, directory: File, maxBytes: Long = MAX_BYTES): OkHttpClient.Builder {
        val created = Cache(directory, maxBytes)
        cache = created
        return builder
            .cache(created)
            .addInterceptor(::fallBackToCache)
            .addNetworkInterceptor(::markCacheability)
    }

    /** Forget every stored answer. Safe to call when nothing was installed. */
    fun clear() {
        runCatching { cache?.evictAll() }
    }

    private fun fallBackToCache(chain: Interceptor.Chain): Response {
        val request = chain.request()
        try {
            return chain.proceed(request)
        } catch (e: IOException) {
            if (request.method != "GET" || !isCacheablePath(request.url.encodedPath)) throw e
            val cached = try {
                chain.proceed(request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build())
            } catch (_: IOException) {
                throw e
            }
            // OkHttp answers an only-if-cached miss with a synthetic 504.
            if (cached.code == 504 && cached.cacheResponse == null) {
                cached.close()
                throw e
            }
            return cached
        }
    }

    private fun markCacheability(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val json = response.header("Content-Type")?.contains("json", ignoreCase = true) == true
        val stored = (response.isSuccessful && json) || response.code == 304
        val keep = request.method == "GET" && stored && isCacheablePath(request.url.encodedPath)
        return response.newBuilder()
            .removeHeader("Pragma")
            .header(HEADER_CACHE_CONTROL, if (keep) "max-age=0" else "no-store")
            .build()
    }
}
