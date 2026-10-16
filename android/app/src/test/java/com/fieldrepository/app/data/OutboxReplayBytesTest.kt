package com.fieldrepository.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * A create that went to the outbox because its online answer was lost must be REPLAYED BYTE FOR BYTE
 * under the same Idempotency-Key. The server fingerprints method, path and body; a replay that
 * differs by one character (a fresh `recordedAt`, a new client key, a re-encoded default) is a 422
 * "different request" instead of the record the first attempt already made, and the photos queued
 * with it would have nowhere to attach.
 *
 * Each case sends a body online through the repository, queues the same object the way the forms
 * do (`offlineFormJson`, MainActivity.kt), replays it through `createFromEntry`, and compares the two
 * requests as the server would see them.
 */
class OutboxReplayBytesTest {
    private val sent = mutableListOf<Request>()

    /** The forms' outbox encoder: `offlineFormJson` in MainActivity.kt. */
    private val offlineFormJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun api(): FieldRepositoryApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                sent += chain.request()
                // Answer 500: what matters here is what went out, and a 500 needs no DTO to decode.
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("lost")
                    .body("{}".toResponseBody("application/json".toMediaType()))
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

    private val repository = FieldRepository(api(), unopenedTokenStore())

    private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

    private fun <T> assertReplaysIdentically(type: String, serializer: KSerializer<T>, body: T, online: suspend (String) -> Unit) =
        runBlocking {
            val key = "4f1d2c7e-0000-4000-8000-0000000000aa"
            sent.clear()
            runCatching { online(key) }
            val entry = PendingEntry(
                id = "e1",
                type = type,
                payloadJson = offlineFormJson.encodeToString(serializer, body),
                label = type,
                createdAt = "2026-10-17T09:00:00Z",
                idempotencyKey = key
            )
            runCatching { repository.createFromEntry(entry) }
            assertEquals("$type: two requests expected", 2, sent.size)
            val (first, replay) = sent
            assertEquals("$type: same path", first.url, replay.url)
            assertEquals("$type: same key", key, replay.header("Idempotency-Key"))
            assertEquals("$type: same key on the online attempt", key, first.header("Idempotency-Key"))
            assertEquals("$type: the replay must send the online attempt's exact bytes", first.bodyText(), replay.bodyText())
        }

    private val recordedAt = "2026-10-17T08:59:12.345Z"
    private val location = LocationRequest(latitude = 23.25, longitude = 69.67, address = "Bhuj")

    @Test
    fun `a craft replays byte for byte`() {
        val body = CraftCreateRequest(name = "Ajrakh", localName = "અજરખ", place = "Bhuj", recordedAt = recordedAt)
        assertReplaysIdentically("craft", CraftCreateRequest.serializer(), body) { repository.createCraft(body, it) }
    }

    @Test
    fun `an artisan replays byte for byte`() {
        val body = ArtisanCreateRequest(
            name = "Ram Kumar", place = "Bagru", pehchanCardAvailable = false, recordedAt = recordedAt, location = location
        )
        assertReplaysIdentically("artisan", ArtisanCreateRequest.serializer(), body) { repository.createArtisan(body, it) }
    }

    @Test
    fun `a workshop replays byte for byte`() {
        val body = WorkshopCreateRequest(
            title = "Block printing", date = recordedAt, startDate = recordedAt, endDate = recordedAt, place = "Bagru",
            artisanIds = listOf("a1", "a2"), recordedAt = recordedAt, location = location
        )
        assertReplaysIdentically("workshop", WorkshopCreateRequest.serializer(), body) { repository.createWorkshop(body, it) }
    }

    @Test
    fun `a product replays byte for byte, decimals included`() {
        val body = ProductCreateRequest(
            craftName = "Pottery", place = "Khavda", artisanName = "Sita", productName = "Pot",
            lengthInches = 12.5, sellingPrice = 450.0, recordedAt = recordedAt
        )
        assertReplaysIdentically("product", ProductCreateRequest.serializer(), body) { repository.createProduct(body, it) }
    }

    @Test
    fun `a tool replays byte for byte`() {
        val body = ToolCreateRequest(
            craftName = "Pottery", place = "Khavda", artisanName = "Sita", toolkitName = "Wheel",
            height = 3.25, lengthInches = 10.0, recordedAt = recordedAt
        )
        assertReplaysIdentically("tool", ToolCreateRequest.serializer(), body) { repository.createTool(body, it) }
    }

    @Test
    fun `a process replays byte for byte`() {
        val body = ProcessCreateRequest(
            name = "Firing", productId = "p1", steps = listOf(ProcessStepRequest(name = "Stack"), ProcessStepRequest(name = "Fire")),
            recordedAt = recordedAt
        )
        assertReplaysIdentically("process", ProcessCreateRequest.serializer(), body) { repository.createProcess(body, it) }
    }

    @Test
    fun `a questionnaire interview replays byte for byte`() {
        val body = QuestionnaireInterviewCreateRequest(
            title = "Interview with Ram", place = "Bagru", artisanIds = listOf("a1"),
            responses = listOf(QuestionnaireResponseRequest(questionId = "q1", answerText = "Since 1990")),
            location = location, recordedAt = recordedAt
        )
        assertReplaysIdentically("questionnaire", QuestionnaireInterviewCreateRequest.serializer(), body) {
            repository.createQuestionnaireInterview(body, it)
        }
    }
}
