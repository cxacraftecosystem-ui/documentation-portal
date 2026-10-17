package com.fieldrepository.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * WHAT A PICKER COSTS BEFORE ANYBODY TYPES — before and after DC-A09, measured on the real Retrofit
 * client against a 2,000-artisan, 2,000-craft deployment that never leaves the process.
 *
 * BEFORE, the app-wide lookups walked [LOOKUP_PAGE_BUDGET] pages of each table at start-up and kept
 * every row; the record pickers then filtered that copy. AFTER, each lookup is one page and the
 * pickers ask the list route's own `search` and `page` for the rest. The "before" calls still exist
 * on the repository (`artisansPage(pageBudget = …)`, `walkPages`), so both shapes are timed here, side
 * by side, in one run.
 *
 * The figures are printed (see the test report's stdout) and the assertions pin the shape: one
 * request per table, a hundred rows, and a search that carries its term and page to the server.
 */
class PickerLookupBenchmarkTest {

    private val sent = mutableListOf<HttpUrl>()

    private fun body(kind: String, page: Int, search: String?): String {
        val all = (1..2000).map { "$kind $it" }
        val matching = if (search == null) all else all.filter { it.contains(search, ignoreCase = true) }
        val rows = matching.drop((page - 1) * 100).take(100)
        val items = rows.joinToString(",") { name ->
            val id = name.replace(' ', '-')
            if (kind == "Craft") """{"id":"$id","name":"$name"}"""
            else """{"id":"$id","name":"$name","place":"Bhuj","status":"APPROVED"}"""
        }
        val pages = (matching.size + 99) / 100
        return """{"items":[$items],"total":${matching.size},"page":$page,"pageSize":100,"pages":$pages}"""
    }

    private fun api(): FieldRepositoryApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url
                synchronized(sent) { sent += url }
                val page = url.queryParameter("page")?.toIntOrNull() ?: 1
                val kind = if (url.encodedPath.endsWith("/crafts")) "Craft" else "Artisan"
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body(kind, page, url.queryParameter("search")).toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        return Retrofit.Builder()
            .baseUrl("https://field-repository.invalid/api/")
            .client(client)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FieldRepositoryApi::class.java)
    }

    private data class Cost(val requests: Int, val rows: Int, val millis: Double)

    /** Median of [runs] timings of [block], with the request count and rows of the last run. */
    private fun measure(runs: Int = 7, block: suspend (FieldRepository) -> Int): Cost {
        val repository = FieldRepository(api(), unopenedTokenStore())
        runBlocking { block(repository) } // warm-up: class loading, the JSON serializers
        val timings = mutableListOf<Double>()
        var rows = 0
        var requests = 0
        repeat(runs) {
            synchronized(sent) { sent.clear() }
            val started = System.nanoTime()
            rows = runBlocking { block(repository) }
            timings += (System.nanoTime() - started) / 1_000_000.0
            requests = synchronized(sent) { sent.size }
        }
        return Cost(requests, rows, timings.sorted()[timings.size / 2])
    }

    @Test
    fun `start-up lookups - twenty pages each before, one page each after`() {
        val before = measure { repo ->
            val crafts = walkPages(LOOKUP_PAGE_BUDGET) { p -> repo.craftsSearchPage(null, p) }
            val artisans = repo.artisansPage(pageBudget = LOOKUP_PAGE_BUDGET)
            crafts.items.size + artisans.items.size
        }
        val after = measure { repo ->
            repo.craftsPage().items.size + repo.artisansPage().items.size
        }
        println("DC-A09 start-up lookups, 2,000 crafts + 2,000 artisans:")
        println("  before: ${before.requests} requests, ${before.rows} rows held, median ${"%.1f".format(before.millis)} ms")
        println("  after:  ${after.requests} requests, ${after.rows} rows held, median ${"%.1f".format(after.millis)} ms")

        assertEquals(40, before.requests)
        assertEquals(4000, before.rows)
        assertEquals(2, after.requests)
        assertEquals(200, after.rows)
        assertTrue("one page must cost less than twenty", after.millis < before.millis)
    }

    @Test
    fun `a typed term and load more reach the server with the term and the next page`() {
        val repository = FieldRepository(api(), unopenedTokenStore())
        synchronized(sent) { sent.clear() }
        val first = runBlocking { repository.artisansSearchPage("Artisan 19", 1) }
        val second = runBlocking { repository.artisansForCraftsPage(listOf("c1", "c2"), "Artisan 1", 2) }
        val urls = synchronized(sent) { sent.toList() }
        assertEquals("Artisan 19", urls[0].queryParameter("search"))
        assertEquals("1", urls[0].queryParameter("page"))
        assertEquals("100", urls[0].queryParameter("pageSize"))
        assertEquals("2", urls[1].queryParameter("page"))
        assertEquals("c1,c2", urls[1].queryParameter("craftIds"))
        // "Artisan 19", "Artisan 190".."199", "Artisan 1900".."1999" — every one reachable by typing.
        assertEquals(111, first.total)
        assertEquals(100, second.items.size)
        // A blank term is the plain list: no `search` parameter at all.
        synchronized(sent) { sent.clear() }
        runBlocking { repository.craftsSearchPage("  ", 1) }
        assertEquals(null, synchronized(sent) { sent.single() }.queryParameter("search"))
    }
}
