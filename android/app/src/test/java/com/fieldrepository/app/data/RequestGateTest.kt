package com.fieldrepository.app.data

import java.net.ConnectException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RequestGateTest {
    private val clock = AtomicLong(1_000_000L)
    private val wire = mutableListOf<String>()
    private var offline = false
    private var serverDelayMillis = 0L

    private fun client(gate: RequestGate): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(gate.interceptor { "token-a" })
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            synchronized(wire) { wire += "${request.method} ${request.url.encodedPath}" }
            if (offline) throw ConnectException("Failed to connect")
            if (serverDelayMillis > 0) Thread.sleep(serverDelayMillis)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{\"ok\":true}".toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()

    private fun OkHttpClient.get(path: String): Result<String> = runCatching {
        newCall(Request.Builder().url("https://api.test/api$path").build()).execute().use { it.body.string() }
    }

    private fun OkHttpClient.post(path: String) {
        newCall(
            Request.Builder().url("https://api.test/api$path")
                .post("{}".toRequestBody("application/json".toMediaType())).build()
        ).execute().close()
    }

    private fun wireCount(path: String): Int = synchronized(wire) { wire.count { it == "GET /api$path" } }

    private fun screenOpen(client: OkHttpClient, paths: List<String>) {
        val pool = Executors.newFixedThreadPool(paths.size)
        val start = CountDownLatch(1)
        val futures = paths.map { path -> pool.submit<Result<String>> { start.await(); client.get(path) } }
        start.countDown()
        futures.forEach { assertTrue(it.get(10, TimeUnit.SECONDS).isSuccess) }
        pool.shutdown()
    }

    @Test
    fun aScreenChangeFetchesEachPolledEndpointOnceWithinBudget() {
        val gate = RequestGate(now = { clock.get() })
        val client = client(gate)
        serverDelayMillis = 150
        val polled = listOf("/notifications?limit=20", "/access/roster/pending-count", "/access-roster/pending-count", "/design-workshops/ai-verb-allowance", "/app/release/latest")
        screenOpen(client, polled + polled + polled)
        clock.addAndGet(2_000)
        screenOpen(client, polled + polled)
        val budget = polled.size
        assertTrue("wire ${wire.size} over budget $budget: $wire", wire.size <= budget)
        polled.forEach { assertEquals(1, wireCount(it.substringBefore('?'))) }
        clock.addAndGet(RequestGate.POLLED_TTL_MILLIS)
        client.get("/notifications?limit=20")
        assertEquals(2, wireCount("/notifications"))
    }

    @Test
    fun theArtisanEditFormLoadsEachRecordOnce() {
        val gate = RequestGate(now = { clock.get() })
        val client = client(gate)
        serverDelayMillis = 100
        val formOpen = List(8) { "/workshops" } + List(6) { "/artisans/a1" } + List(4) { "/workshops/w1/submission-check" } + List(4) { "/media" }
        screenOpen(client, formOpen)
        val budget = 4
        assertTrue("artisan edit form made ${wire.size} requests, budget $budget: $wire", wire.size <= budget)
        assertEquals(1, wireCount("/workshops"))
        assertEquals(1, wireCount("/artisans/a1"))
        client.post("/artisans/a1/edit")
        client.get("/artisans/a1")
        assertEquals(2, wireCount("/artisans/a1"))
    }

    @Test
    fun offlinePollingBacksOffAndResumesWhenTheConnectionReturns() {
        val gate = RequestGate(now = { clock.get() })
        val client = client(gate)
        offline = true
        val polled = listOf("/notifications?limit=20", "/access/roster/pending-count", "/design-workshops/ai-verb-allowance")
        repeat(15) {
            polled.forEach { path -> assertTrue(client.get(path).isFailure) }
            clock.addAndGet(2_000)
        }
        val budget = 8
        assertTrue("offline storm: ${wire.size} requests in 30s, budget $budget", wire.size <= budget)
        offline = false
        gate.networkReturned()
        val before = wire.size
        assertTrue(client.get("/access/roster/pending-count").isSuccess)
        assertEquals(before + 1, wire.size)
    }

    @Test
    fun aBackedOffRequestIsNotRetriedByTheGatewayRetry() {
        val gate = RequestGate(now = { clock.get() })
        val client = client(gate)
        offline = true
        repeat(RequestGate.FAILURES_BEFORE_BACKOFF) { client.get("/tasks") }
        val before = wire.size
        try {
            client.newCall(Request.Builder().url("https://api.test/api/tasks").build()).execute().close()
            fail("expected a backoff")
        } catch (e: RequestBackoffException) {
            assertEquals(before, wire.size)
        }
    }
}
