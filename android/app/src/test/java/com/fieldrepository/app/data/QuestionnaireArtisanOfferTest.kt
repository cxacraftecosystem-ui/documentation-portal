package com.fieldrepository.app.data

import com.fieldrepository.app.ui.artisanScopeNoun
import com.fieldrepository.app.ui.artisansNotAtWorkshop
import com.fieldrepository.app.ui.listCutNotice
import com.fieldrepository.app.ui.outOfWorkshopNotice
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

class QuestionnaireArtisanOfferTest {

    private val sent = mutableListOf<HttpUrl>()

    private fun pageBody(page: Int): String {
        val first = (page - 1) * 2 + 1
        val ids = listOf(first, first + 1).filter { it <= 7 }
        val items = ids.joinToString(",") { """{"id":"a$it","name":"Artisan $it","place":"Bhuj","status":"APPROVED"}""" }
        return """{"items":[$items],"total":7,"page":$page,"pageSize":2,"pages":4}"""
    }

    private fun api(): FieldRepositoryApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url
                synchronized(sent) { sent += url }
                val page = url.queryParameter("page")?.toIntOrNull() ?: 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(pageBody(page).toResponseBody("application/json".toMediaType()))
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

    private fun repository(api: FieldRepositoryApi): FieldRepository =
        FieldRepository(api, unopenedTokenStore())

    @Test
    fun `the picker's budget walks the pages and concatenates them in server order`() {
        val page = runBlocking { repository(api()).artisansPage(listOf("w1"), pageBudget = ARTISAN_PAGE_BUDGET) }

        assertEquals(listOf("a1", "a2", "a3", "a4", "a5", "a6", "a7"), page.items.map { it.id })
        assertEquals("a1", page.items.first().id)
        assertEquals("a7", page.items.last().id)
    }

    @Test
    fun `every page after the first asks for its own page number, and page one is not asked twice`() {
        runBlocking { repository(api()).artisansPage(listOf("w1"), pageBudget = ARTISAN_PAGE_BUDGET) }

        val pages = sent.mapNotNull { it.queryParameter("page")?.toIntOrNull() }.sorted()
        assertEquals(listOf(1, 2, 3, 4), pages)
        assertTrue("every request carries the scope", sent.all { it.queryParameter("workshopIds") == "w1" })
    }

    @Test
    fun `the budget bounds the walk, and the envelope still reports what was left behind`() {
        val page = runBlocking { repository(api()).artisansPage(listOf("w1"), pageBudget = 2) }

        assertEquals(listOf("a1", "a2", "a3", "a4"), page.items.map { it.id })
        assertEquals("the server's count, not the accumulated one", 7, page.total)
        assertEquals(2, sent.size)
        assertNotNull(
            "4 of 7 artisans loaded must produce a notice",
            listCutNotice(page.items.size, page.total, artisanScopeNoun("w1"))
        )
    }

    @Test
    fun `the default budget is one request, exactly as before this change`() {
        val page = runBlocking { repository(api()).artisansPage(listOf("w1")) }

        assertEquals(1, sent.size)
        assertEquals(listOf("a1", "a2"), page.items.map { it.id })
        assertEquals(7, page.total)
    }

    @Test
    fun `an empty scope sends no workshop parameter even while paging`() {
        runBlocking { repository(api()).artisansPage(emptyList(), pageBudget = ARTISAN_PAGE_BUDGET) }

        assertTrue("no request may name a workshop", sent.all { it.queryParameter("workshopIds") == null })
        assertEquals(4, sent.size)
    }

    @Test
    fun `the budget is the same five the browser pages to`() {
        assertEquals(5, ARTISAN_PAGE_BUDGET)
        assertEquals(100, LIST_PAGE_CEILING)
    }

    private val offered = listOf("a-ramesh", "a-sita")

    @Test
    fun `an artisan the workshop's complete roster does not hold is named`() {
        assertEquals(
            listOf("a-outsider"),
            artisansNotAtWorkshop(
                selectedIds = listOf("a-ramesh", "a-outsider"),
                offeredIds = offered,
                loadedForWorkshop = "w-2",
                workshopId = "w-2",
                cut = null
            )
        )
    }

    @Test
    fun `nobody is named before this workshop's roster has landed, or after it failed`() {
        assertEquals(
            emptyList<String>(),
            artisansNotAtWorkshop(
                selectedIds = listOf("a-ramesh", "a-outsider"),
                offeredIds = offered,
                loadedForWorkshop = "w-1",
                workshopId = "w-2",
                cut = null
            )
        )
        assertEquals(
            emptyList<String>(),
            artisansNotAtWorkshop(
                selectedIds = listOf("a-ramesh", "a-outsider"),
                offeredIds = emptyList(),
                loadedForWorkshop = null,
                workshopId = "w-2",
                cut = null
            )
        )
    }

    @Test
    fun `nobody is named while the roster is cut`() {
        assertEquals(
            emptyList<String>(),
            artisansNotAtWorkshop(
                selectedIds = listOf("a-ramesh", "a-outsider"),
                offeredIds = offered,
                loadedForWorkshop = "w-2",
                workshopId = "w-2",
                cut = "Showing 500 of 640 artisans at this workshop. To find another, use Browse records from the menu."
            )
        )
    }

    @Test
    fun `the named ids keep the researcher's own order`() {
        assertEquals(
            listOf("a-outsider", "a-stranger"),
            artisansNotAtWorkshop(
                selectedIds = listOf("a-sita", "a-outsider", "a-stranger", "a-ramesh"),
                offeredIds = offered,
                loadedForWorkshop = "w-2",
                workshopId = "w-2",
                cut = null
            )
        )
    }

    @Test
    fun `says nothing when there is nothing to say`() {
        assertNull(outOfWorkshopNotice(emptyList()))
        assertNull(outOfWorkshopNotice(listOf("", "   ")))
    }

    @Test
    fun `names one artisan, and does not read as an accusation`() {
        val line = outOfWorkshopNotice(listOf("Ramesh Kumar"))
        assertNotNull(line)
        assertTrue(line!!.contains("Ramesh Kumar is not yet linked to this workshop"))
        assertTrue(line.contains("untick anyone who shouldn't be here"))
        assertTrue(line.contains("Saving links them"))
    }

    @Test
    fun `names several, in the order given`() {
        assertTrue(outOfWorkshopNotice(listOf("Sita Devi", "Ramesh Kumar"))!!.contains("Sita Devi, Ramesh Kumar are"))
    }

    @Test
    fun `caps the list and counts the remainder`() {
        val line = outOfWorkshopNotice(listOf("A", "B", "C", "D", "E", "F"))!!
        assertTrue(line.contains("A, B, C, D and 2 more are"))
    }

    @Test
    fun `the cut sentence's noun follows the scope`() {
        assertEquals("artisans at this workshop", artisanScopeNoun("w-2"))
        assertEquals("artisans", artisanScopeNoun(""))
    }
}
