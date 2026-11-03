package com.fieldrepository.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException

object OfflineReadCache {
    private const val MAX_BYTES = 40L * 1024 * 1024
    private const val HEADER_CACHE_CONTROL = "Cache-Control"

    private val caches = HashMap<String, Cache>()

    @Synchronized
    private fun cacheFor(directory: File, maxBytes: Long): Cache =
        caches.getOrPut(directory.absoluteFile.normalize().path) { Cache(directory, maxBytes) }

    private val NEVER_CACHED = listOf(
        "auth/", "secrets", "me/ai-keys", "preferences/", "app/release", "export/", "data/manifest",
        "data/report", "data/media/", "artisans/lookup", "submission-check"
    )

    internal fun isCacheablePath(encodedPath: String): Boolean {
        val path = encodedPath.trimStart('/')
        return NEVER_CACHED.none { blocked -> path.contains(blocked) }
    }

    fun install(builder: OkHttpClient.Builder, directory: File, maxBytes: Long = MAX_BYTES): OkHttpClient.Builder {
        return builder
            .cache(cacheFor(directory, maxBytes))
            .addInterceptor(::fallBackToCache)
            .addNetworkInterceptor(::markCacheability)
    }

    fun clear(): Job {
        val all = synchronized(this) { caches.values.toList() }
        return AppScope.io.launch(Dispatchers.IO) { all.forEach { runCatching { it.evictAll() } } }
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
