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

    private fun measure(runs: Int = 7, block: suspend (FieldRepository) -> Int): Cost {
        val repository = FieldRepository(api(), unopenedTokenStore())
        runBlocking { block(repository) }
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
            repo.artisansPage().items.size
        }
        println("DC-A09 start-up lookups, 2,000 crafts + 2,000 artisans:")
        println("  before: ${before.requests} requests, ${before.rows} rows held, median ${"%.1f".format(before.millis)} ms")
        println("  after:  ${after.requests} requests, ${after.rows} rows held, median ${"%.1f".format(after.millis)} ms")

        assertEquals(40, before.requests)
        assertEquals(4000, before.rows)
        assertEquals(1, after.requests)
        assertEquals(100, after.rows)
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
        assertEquals(111, first.total)
        assertEquals(100, second.items.size)
        synchronized(sent) { sent.clear() }
        runBlocking { repository.craftsSearchPage("  ", 1) }
        assertEquals(null, synchronized(sent) { sent.single() }.queryParameter("search"))
    }
}
