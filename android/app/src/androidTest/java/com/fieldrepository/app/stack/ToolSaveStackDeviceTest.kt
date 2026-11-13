package com.fieldrepository.app.stack

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.FieldRepositoryApi
import com.fieldrepository.app.data.LocationRequest
import com.fieldrepository.app.data.ToolCreateRequest
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ToolSaveStackDeviceTest {
    private val args = InstrumentationRegistry.getArguments()
    private fun arg(key: String): String? = args.getString(key)?.takeIf { it.isNotBlank() }
    private val token get() = arg("token") ?: error("pass -e token")
    private val craftId get() = arg("craftId") ?: error("pass -e craftId")
    private val artisanIds get() = (arg("artisanIds") ?: error("pass -e artisanIds")).split(",")
    private val stackApi get() = arg("stackApi") ?: error("pass -e stackApi")
    private val baselineApi get() = arg("baselineApi")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
        })
        .build()

    private fun agra() = LocationRequest(
        latitude = 27.1751,
        longitude = 78.0421,
        placeName = "Agra",
        state = "Uttar Pradesh",
        district = "Agra",
        capturedAt = Instant.now().toString()
    )

    private fun shippedColdCatalogueBody(toolkit: String, craftName: String, artisanName: String) = ToolCreateRequest(
        toolkitName = toolkit,
        craftName = craftName,
        artisanName = artisanName,
        place = "Agra",
        maker = "UNKNOWN",
        traditionType = "UNKNOWN",
        artisanId = artisanIds.first(),
        craftId = craftId,
        craftIds = listOf(craftId),
        artisanIds = artisanIds,
        status = "PENDING",
        recordedAt = Instant.now().toString(),
        location = agra()
    )

    private data class Answer(val code: Int, val body: String)

    private fun post(base: String, body: ToolCreateRequest): Answer {
        val json = ApiClient.json.encodeToString(ToolCreateRequest.serializer(), body)
        Log.i(TAG, "POST ${base}tools body=$json")
        val request = Request.Builder()
            .url(base + "tools")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            Log.i(TAG, "HTTP ${response.code} from ${base}tools: $text")
            return Answer(response.code, text)
        }
    }

    private fun shownToUser(base: String, body: ToolCreateRequest): String? {
        val api = Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FieldRepositoryApi::class.java)
        return runBlocking {
            try {
                api.createTool(UUID.randomUUID().toString(), body)
                null
            } catch (refused: HttpException) {
                refused.apiErrorMessage("Unable to save tool").also { Log.i(TAG, "The app shows: $it") }
            }
        }
    }

    @Test
    fun theShippedColdCatalogueBodyIsRefusedByTheShippedServer() {
        val baseline = baselineApi
        assumeTrue("no baseline server given", baseline != null)
        val answer = post(baseline!!, shippedColdCatalogueBody("Baseline Chisel ${stamp()}", craftName = "", artisanName = "Fahim"))
        assertEquals(answer.body, 422, answer.code)
        assertTrue(answer.body, answer.body.contains("craftName"))
    }

    @Test
    fun theShippedColdCatalogueBodySavesOnThisServer() {
        val answer = post(stackApi, shippedColdCatalogueBody("Inlay Chisel ${stamp()}", craftName = "", artisanName = "Fahim"))
        assertEquals(answer.body, 201, answer.code)
        assertTrue(answer.body, answer.body.contains("Agra Marble Inlay"))
    }

    @Test
    fun theShippedBodyWithABlankArtisanNameSavesOnThisServer() {
        val answer = post(stackApi, shippedColdCatalogueBody("Inlay File ${stamp()}", craftName = "", artisanName = ""))
        assertEquals(answer.body, 201, answer.code)
        assertTrue(answer.body, answer.body.contains("Fahim"))
    }

    @Test
    fun aRealRefusalReachesThePersonAsTheServersSentence() {
        val shown = shownToUser(stackApi, shippedColdCatalogueBody("", craftName = "", artisanName = "Fahim"))
        assertTrue("the save should have been refused", shown != null)
        assertFalse(shown!!, shown.startsWith("HTTP"))
        assertFalse(shown, shown == "Unable to save tool")
        assertTrue(shown, shown.contains("Toolkit name", ignoreCase = true))
    }

    private fun stamp() = UUID.randomUUID().toString().take(6)

    private companion object {
        const val TAG = "ToolStackProof"
    }
}
