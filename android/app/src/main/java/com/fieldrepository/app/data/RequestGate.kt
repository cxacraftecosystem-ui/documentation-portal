package com.fieldrepository.app.data

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class RequestBackoffException : IOException(ServerReachability.SERVER_UNREACHABLE_SENTENCE)

class RequestGate(
    private val polledTtlMillis: Long = POLLED_TTL_MILLIS,
    private val burstTtlMillis: Long = BURST_TTL_MILLIS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private class Captured(
        val code: Int,
        val message: String,
        val protocol: Protocol,
        val headers: Headers,
        val contentType: MediaType?,
        val body: ByteArray,
        val at: Long,
        val ttl: Long,
    )

    private val cache = ConcurrentHashMap<String, Captured>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Captured>>()
    private class Backoff(var failures: Int = 0, var blockedUntil: Long = 0L)

    private val backoffs = ConcurrentHashMap<String, Backoff>()

    fun networkReturned() {
        backoffs.clear()
    }

    fun clear() {
        cache.clear()
    }

    fun backingOff(origin: String): Boolean {
        val state = backoffs[origin] ?: return false
        return synchronized(state) { now() < state.blockedUntil }
    }

    fun interceptor(identity: () -> String?): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.method != "GET") {
            if (invalidatesPolled(request.url.encodedPath)) cache.clear() else cache.values.removeIf { it.ttl == burstTtlMillis }
            return@Interceptor chain.proceed(request)
        }
        if (request.cacheControl.onlyIfCached) return@Interceptor chain.proceed(request)
        val origin = "${request.url.host}:${request.url.port}"
        if (backingOff(origin)) throw RequestBackoffException()
        val ttl = when {
            isPolledPath(request.url.encodedPath) -> polledTtlMillis
            isBurstPath(request.url.encodedPath) -> burstTtlMillis
            else -> return@Interceptor guarded(origin) { chain.proceed(request) }
        }
        val key = "${identity()?.hashCode() ?: 0}|${request.url}"
        cache[key]?.let { hit ->
            if (now() - hit.at < hit.ttl) return@Interceptor hit.toResponse(request)
            cache.remove(key, hit)
        }
        val mine = CompletableFuture<Captured>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) {
            val shared = try {
                running.get()
            } catch (e: ExecutionException) {
                val cause = e.cause
                if (cause is IOException && cause.message.equals("Canceled", ignoreCase = true) && !chain.call().isCanceled()) {
                    return@Interceptor guarded(origin) { chain.proceed(request) }
                }
                throw (cause as? IOException) ?: IOException(cause)
            }
            return@Interceptor shared.toResponse(request)
        }
        try {
            val captured = guarded(origin) { chain.proceed(request) }.use { response ->
                Captured(
                    code = response.code,
                    message = response.message,
                    protocol = response.protocol,
                    headers = response.headers,
                    contentType = response.body.contentType(),
                    body = response.body.bytes(),
                    at = now(),
                    ttl = ttl,
                )
            }
            if (captured.code in 200..299 && captured.contentType?.subtype == "json") cache[key] = captured
            mine.complete(captured)
            captured.toResponse(request)
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private inline fun guarded(origin: String, call: () -> Response): Response {
        val response = try {
            call()
        } catch (e: IOException) {
            if (isConnectivityFailure(e)) recordConnectivityFailure(origin)
            throw e
        }
        backoffs.remove(origin)
        return response
    }

    private fun recordConnectivityFailure(origin: String) {
        val state = backoffs.getOrPut(origin) { Backoff() }
        synchronized(state) {
            state.failures++
            if (state.failures >= FAILURES_BEFORE_BACKOFF) {
                val step = state.failures - FAILURES_BEFORE_BACKOFF
                val wait = (FIRST_BACKOFF_MILLIS shl minOf(step, 5)).coerceAtMost(MAX_BACKOFF_MILLIS)
                state.blockedUntil = now() + wait
            }
        }
    }

    private fun Captured.toResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(protocol)
            .code(code)
            .message(message)
            .headers(headers)
            .body(body.toResponseBody(contentType))
            .build()

    companion object {
        const val POLLED_TTL_MILLIS = 10_000L
        const val FAILURES_BEFORE_BACKOFF = 2
        const val FIRST_BACKOFF_MILLIS = 2_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val BURST_TTL_MILLIS = 3_000L

        val shared = RequestGate()

        private val POLLED_SUFFIXES = listOf(
            "/notifications",
            "/access/roster/pending-count",
            "/access-roster/pending-count",
            "/design-workshops/ai-verb-allowance",
            "/app/release/latest",
        )

        private val INVALIDATING_PARTS = listOf("/notifications", "/access", "/app/release", "/design-workshops", "/auth")

        private val BURST_PATHS = listOf(
            Regex(".*/workshops$"),
            Regex(".*/artisans$"),
            Regex(".*/artisans/[^/]+$"),
            Regex(".*/workshops/[^/]+/submission-check$"),
            Regex(".*/media$"),
        )

        fun isBurstPath(path: String): Boolean = BURST_PATHS.any { it.matches(path) }

        fun isPolledPath(path: String): Boolean = POLLED_SUFFIXES.any { path.endsWith(it) }

        fun invalidatesPolled(path: String): Boolean = INVALIDATING_PARTS.any { path.contains(it) }

        fun isConnectivityFailure(error: IOException): Boolean {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is UnknownHostException || cause is ConnectException || cause is NoRouteToHostException) return true
                cause = cause.cause
            }
            return false
        }
    }
}
