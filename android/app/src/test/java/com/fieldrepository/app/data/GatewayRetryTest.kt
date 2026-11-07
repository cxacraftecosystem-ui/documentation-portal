package com.fieldrepository.app.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class GatewayRetryTest {
    @Before fun reset() = ServerReachability.recordAnswer()

    @After fun clear() = ServerReachability.recordAnswer()

    private fun client(terminal: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(ApiClient.gatewayRetry(pause = {})).addInterceptor(terminal).build()

    private fun folder() = Request.Builder().url("https://portal.example.org/api/data/tree?path=by-workshop").build()

    @Test fun `a call the app cancelled is neither retried nor counted as a lost connection`() {
        val attempts = AtomicInteger()
        val client = client { chain ->
            attempts.incrementAndGet()
            chain.call().cancel()
            throw IOException("stream was reset: CANCEL")
        }
        repeat(3) { assertThrows(IOException::class.java) { client.newCall(folder()).execute() } }
        assertEquals(3, attempts.get())
        assertFalse(ServerReachability.unreachable.value)
    }

    @Test fun `a lost connection is retried and counted`() {
        val attempts = AtomicInteger()
        val client = client { _ ->
            attempts.incrementAndGet()
            throw IOException("Connection reset")
        }
        assertThrows(IOException::class.java) { client.newCall(folder()).execute() }
        assertEquals(4, attempts.get())
        assertTrue(ServerReachability.unreachable.value)
    }
}
