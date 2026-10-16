package com.fieldrepository.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The handset's half of `Idempotency-Key`: the header goes out on a create when there is a key and
 * not at all when there is none, and the server's "still running" answer is something to retry,
 * not a refusal.
 */
class IdempotencyKeyClientTest {
    private val sent = mutableListOf<Request>()

    private fun api(): FieldRepositoryApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                sent += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body("""{"id":"craft_1"}""".toResponseBody("application/json".toMediaType()))
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

    @Test
    fun `a create carries its key, and a create without one sends no header`() = runBlocking {
        val repository = FieldRepository(api(), unopenedTokenStore())
        repository.createCraft(CraftCreateRequest(name = "Ajrakh"), idempotencyKey = "4f1d2c7e-0000-4000-8000-000000000001")
        repository.createCraft(CraftCreateRequest(name = "Ajrakh"))
        assertEquals("4f1d2c7e-0000-4000-8000-000000000001", sent[0].header("Idempotency-Key"))
        assertNull(sent[1].header("Idempotency-Key"))
    }

    private fun httpError(code: Int, vararg extra: Pair<String, String>): HttpException {
        val raw = Response.Builder()
            .request(Request.Builder().url("https://field-repository.invalid/api/crafts").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .apply { extra.forEach { (name, value) -> header(name, value) } }
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
        return HttpException(retrofit2.Response.error<Any>("{}".toResponseBody("application/json".toMediaType()), raw))
    }

    @Test
    fun `the server still running the same key is retried, a real conflict is not`() {
        val repository = FieldRepository(api(), unopenedTokenStore())
        assertTrue(repository.isTransientFailure(httpError(409, "Idempotency-Status" to "in-progress")))
        assertFalse(repository.isTransientFailure(httpError(409)))
        assertFalse(repository.isTransientFailure(httpError(422)))
        assertTrue(repository.isTransientFailure(httpError(503)))
        assertTrue(repository.isTransientFailure(java.io.IOException("no signal")))
    }
}
