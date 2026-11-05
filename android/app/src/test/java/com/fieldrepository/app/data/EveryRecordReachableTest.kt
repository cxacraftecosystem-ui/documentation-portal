package com.fieldrepository.app.data

import com.fieldrepository.app.ui.sharingOptions
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class EveryRecordReachableTest {

    private val sent = mutableListOf<HttpUrl>()

    private fun row(kind: String, n: Int): String = when (kind) {
        "artisans" -> """{"id":"artisans-$n","name":"Artisan $n","place":"Kutch","status":"APPROVED"}"""
        "users" -> """{"id":"users-$n","email":"u$n@example.org","name":"User $n","role":"RESEARCHER"}"""
        "interviews" -> """{"id":"interviews-$n","title":"Interview $n"}"""
        else -> """{"id":"$kind-$n"}"""
    }

    private fun repository(totals: Map<String, Int>): FieldRepository {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url
                sent += url
                val kind = url.pathSegments.last()
                val total = totals[kind] ?: 0
                val page = url.queryParameter("page")?.toInt() ?: 1
                val size = url.queryParameter("pageSize")?.toInt() ?: 100
                val from = (page - 1) * size + 1
                val to = minOf(total, page * size)
                val items = if (from > to) "" else (from..to).joinToString(",") { row(kind, it) }
                val pages = (total + size - 1) / size
                val body = """{"items":[$items],"total":$total,"page":$page,"pageSize":$size,"pages":$pages}"""
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        val api = Retrofit.Builder()
            .baseUrl("https://field-repository.invalid/api/")
            .client(client)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FieldRepositoryApi::class.java)
        return FieldRepository(api, unopenedTokenStore())
    }

    @Test
    fun everyPickerListWalksToTheLastRecord() = runBlocking {
        val repo = repository(mapOf("products" to 231, "tools" to 101, "workshops" to 305, "processes" to 150, "users" to 412, "artisans" to 250))
        assertEquals("products-231", repo.products().last().id)
        assertEquals(231, repo.products().size)
        assertEquals("tools-101", repo.tools().last().id)
        assertEquals("workshops-305", repo.workshops().last().id)
        assertEquals("processes-150", repo.processes().last().id)
        assertEquals("users-412", repo.users().last().id)
        assertEquals(412, repo.users().size)
        assertEquals("artisans-250", repo.artisans().last().id)
    }

    @Test
    fun sharingListsEveryOwnRecordAndAsksTheServerForOnlyMine() = runBlocking {
        val repo = repository(mapOf("artisans" to 130, "products" to 101, "tools" to 2, "workshops" to 1, "interviews" to 205))
        val options = sharingOptions(repo.allMine("me"))
        assertEquals(130 + 101 + 2 + 1 + 205, options.size)
        assertTrue(options.any { it.value == "questionnaire::interviews-205" })
        assertTrue(options.any { it.value == "product::products-101" })
        assertTrue(sent.all { it.queryParameter("createdBy") == "me" })
    }

    @Test
    fun myRecordsCarryOnFromWhereTheFirstBudgetStopped() = runBlocking {
        val total = MY_RECORDS_PAGE_BUDGET * 100 + 150
        val repo = repository(mapOf("artisans" to total))
        val first = repo.myArtisans("me")
        assertEquals(MY_RECORDS_PAGE_BUDGET * 100, first.items.size)
        assertTrue(first.truncated)
        assertEquals(MY_RECORDS_PAGE_BUDGET + 1, first.nextPage)
        val rest = repo.myArtisans("me", first.nextPage!!)
        assertEquals(150, rest.items.size)
        assertFalse(rest.truncated)
        assertNull(rest.nextPage)
        assertEquals("artisans-$total", rest.items.last().id)
        assertEquals(total, (first.items + rest.items).map { it.id }.distinct().size)
    }

    @Test
    fun aShortListIsNeverCalledTruncated() = runBlocking {
        val page = myRecordsWalk(1) { p -> PageResponse(if (p == 1) listOf("a", "b") else emptyList(), total = 2, page = p, pageSize = 100, pages = 1) }
        assertEquals(listOf("a", "b"), page.items)
        assertFalse(page.truncated)
        assertNull(page.nextPage)
    }
}
