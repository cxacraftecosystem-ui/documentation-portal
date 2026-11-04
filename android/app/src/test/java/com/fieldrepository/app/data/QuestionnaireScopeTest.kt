package com.fieldrepository.app.data

import com.fieldrepository.app.ui.listCutNotice
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
import org.junit.Test
import retrofit2.Retrofit

class QuestionnaireScopeTest {

    private val sent = mutableListOf<HttpUrl>()

    private val onePageOfMany = """
        {"items":[{"id":"iv1","title":"Sitting one","createdAt":"2026-09-01T00:00:00Z"}],
         "total":137,"page":1,"pageSize":100,"pages":2}
    """.trimIndent()

    private fun api(): FieldRepositoryApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                sent += chain.request().url
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(onePageOfMany.toResponseBody("application/json".toMediaType()))
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

    private fun onlyRequest(): HttpUrl {
        assertEquals("expected exactly one request, got $sent", 1, sent.size)
        return sent.first()
    }

    @Test
    fun `the chosen workshops ride onto the interviews request`() {
        runBlocking { repository(api()).interviews(listOf("w2", "w1")) }

        val url = onlyRequest()
        assertEquals("/api/questionnaire/interviews", url.encodedPath)
        assertEquals("w2,w1", url.queryParameter("workshopIds"))
        assertEquals("100", url.queryParameter("pageSize"))
    }

    @Test
    fun `the order the caller chose is the order that is sent`() {
        runBlocking { repository(api()).interviews(listOf("alpha", "beta", "gamma")) }
        assertEquals("alpha,beta,gamma", onlyRequest().queryParameter("workshopIds"))
    }

    @Test
    fun `blank and repeated ids never reach the query string`() {
        runBlocking { repository(api()).interviews(listOf("w1", "  ", "w1", "")) }

        val url = onlyRequest()
        assertEquals("w1", url.queryParameter("workshopIds"))
        assertEquals("w1", url.queryParameter("workshopId"))
    }

    @Test
    fun `no scope sends no workshop parameter at all`() {
        val api = api()
        runBlocking {
            repository(api).interviews(EVERY_WORKSHOP)
            repository(api).interviews(emptyList())
            repository(api).interviews(listOf("", "   "))
        }

        assertEquals(3, sent.size)
        sent.forEach { url ->
            assertNull("workshopIds must be absent, not empty: $url", url.queryParameter("workshopIds"))
            assertNull("workshopId must be absent, not empty: $url", url.queryParameter("workshopId"))
        }
    }

    @Test
    fun `a single workshop is also sent singularly for a server that predates the plural`() {
        runBlocking { repository(api()).interviews(listOf("w1")) }

        val url = onlyRequest()
        assertEquals("w1", url.queryParameter("workshopIds"))
        assertEquals("w1", url.queryParameter("workshopId"))
    }

    @Test
    fun `two workshops are never sent as the singular filter`() {
        runBlocking { repository(api()).interviews(listOf("w1", "w2")) }

        val url = onlyRequest()
        assertEquals("w1,w2", url.queryParameter("workshopIds"))
        assertNull(url.queryParameter("workshopId"))
    }

    @Test
    fun `the unassigned sentinel goes out only in the plural`() {
        runBlocking { repository(api()).interviews(listOf("none")) }

        val url = onlyRequest()
        assertEquals("none", url.queryParameter("workshopIds"))
        assertNull(url.queryParameter("workshopId"))
    }

    @Test
    fun `a workshop plus the unassigned sentinel is sent whole in the plural`() {
        runBlocking { repository(api()).interviews(listOf("w1", "none")) }

        val url = onlyRequest()
        assertEquals("w1,none", url.queryParameter("workshopIds"))
        assertNull(url.queryParameter("workshopId"))
    }

    @Test
    fun `the sibling lookup asks by artisan and never by workshop`() {
        runBlocking { repository(api()).interviewsForArtisan("a1") }

        val url = onlyRequest()
        assertEquals("a1", url.queryParameter("artisanId"))
        assertNull(url.queryParameter("workshopIds"))
        assertNull(url.queryParameter("workshopId"))
    }

    @Test
    fun `the envelope survives so a truncated list can say so`() {
        val page = runBlocking { repository(api()).interviewsPage(listOf("w1")) }

        assertEquals(1, page.items.size)
        assertEquals(137, page.total)
        assertNotNull(
            "a page holding 1 of 137 interviews must produce a notice",
            listCutNotice(page.items.size, page.total, "interviews")
        )
        assertNull(listCutNotice(loaded = 12, total = 12, noun = "interviews"))
    }
}
