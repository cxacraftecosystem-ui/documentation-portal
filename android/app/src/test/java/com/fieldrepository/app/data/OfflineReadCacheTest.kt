package com.fieldrepository.app.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The read cache against a real local HTTP server (the JDK's own), switched off half-way through
 * to stand in for losing the signal.
 */
class OfflineReadCacheTest {
    private val dir = Files.createTempDirectory("read-cache-test").toFile()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var hits = 0

    init {
        fun route(path: String, contentType: String, body: String) {
            server.createContext(path) { exchange ->
                hits++
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.responseHeaders.add("Cache-Control", "no-store")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        route("/api/crafts", "application/json; charset=utf-8", """{"items":["Ajrakh"]}""")
        route("/api/auth/two-factor", "application/json", """{"enabled":true}""")
        route("/api/export/dataset", "application/zip", "PK")
        server.start()
    }

    private val client: OkHttpClient = OfflineReadCache.install(
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false),
        dir
    ).build()

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}/api/$path"

    private fun get(path: String): String =
        client.newCall(Request.Builder().url(url(path)).build()).execute().use { it.body.string() }

    private fun assertFailsOffline(path: String) {
        try {
            get(path)
            fail("$path should fail with no connection and nothing cached")
        } catch (_: IOException) {
        }
    }

    @After
    fun cleanUp() {
        runCatching { server.stop(0) }
        runBlocking { OfflineReadCache.clear().join() }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        dir.deleteRecursively()
    }

    @Test
    fun `a screen opened offline shows what it last loaded`() {
        assertEquals("""{"items":["Ajrakh"]}""", get("crafts"))
        // Online, the server is always asked again, even though the answer is stored.
        assertEquals("""{"items":["Ajrakh"]}""", get("crafts"))
        assertEquals(2, hits)
        server.stop(0)
        client.connectionPool.evictAll()
        assertEquals("""{"items":["Ajrakh"]}""", get("crafts"))
    }

    @Test
    fun `a read never made online still fails offline, as before`() {
        server.stop(0)
        assertFailsOffline("crafts")
    }

    @Test
    fun `sign-in state and downloads are never kept`() {
        get("auth/two-factor")
        get("export/dataset")
        server.stop(0)
        client.connectionPool.evictAll()
        assertFailsOffline("auth/two-factor")
        assertFailsOffline("export/dataset")
    }

    @Test
    fun `signing out forgets every stored answer`() {
        get("crafts")
        runBlocking { OfflineReadCache.clear().join() }
        server.stop(0)
        client.connectionPool.evictAll()
        assertFailsOffline("crafts")
    }

    @Test
    fun `one cache per directory for the whole process`() {
        val again = OfflineReadCache.install(OkHttpClient.Builder(), dir).build()
        assertTrue("a second client on the same directory must share the first one's Cache", again.cache === client.cache)
    }

    @Test
    fun `the never-cached list`() {
        assertTrue(OfflineReadCache.isCacheablePath("/api/artisans/abc"))
        assertTrue(OfflineReadCache.isCacheablePath("/api/dashboard/stats"))
        assertFalse(OfflineReadCache.isCacheablePath("/api/secrets/OPENAI/reveal"))
        assertFalse(OfflineReadCache.isCacheablePath("/api/me/ai-keys"))
        assertFalse(OfflineReadCache.isCacheablePath("/api/preferences/me"))
        assertFalse(OfflineReadCache.isCacheablePath("/api/artisans/lookup/aadhaar"))
        assertFalse(OfflineReadCache.isCacheablePath("/api/workshops/w1/submission-check"))
        assertFalse(OfflineReadCache.isCacheablePath("/api/data/media/m1/download"))
    }
}
