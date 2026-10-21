package com.fieldrepository.app.data

import com.fieldrepository.app.ui.questionnaire.decisionRow
import com.fieldrepository.app.ui.questionnaire.joinFailureKey
import com.fieldrepository.app.ui.questionnaire.replaceRemovesEarlier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * The contract of fix/q-v2-backend (21-22 Oct 2026) as Android meets it: the build headers, closed
 * intents, `/complete`'s UPLOAD_MISSING, the join rate limit, the optional request fields, the
 * snapshot's changed takes, the inbox's named takes, and a researcher's replace keeping both takes.
 */
class BackendContractTest {
    @get:Rule val tmp = TemporaryFolder()
    private val t0 = Instant.parse("2026-07-01T10:00:00Z")

    @Test fun `every request names the platform beside the build number and the version name`() {
        var seen: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(ClientClock.interceptor(appVersion = "0.0.1", versionCode = 31))
            .addInterceptor { chain ->
                seen = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{}".toResponseBody()).build()
            }
            .build()
        client.newCall(Request.Builder().url("http://localhost/api/questionnaire/config").build()).execute().close()
        assertEquals("android", seen!!.header("X-App-Platform"))
        assertEquals("31", seen!!.header("X-App-Version-Code"))
        assertEquals("0.0.1", seen!!.header("X-App-Version"))
    }

    @Test fun `the banner reads the Android cutoff, now that the name minimum is always null`() {
        assertTrue(QConfig(minAppVersionForCreate = null, minAndroidVersionCodeForCreate = 32).updateRequired(31))
        assertFalse(QConfig(minAppVersionForCreate = null, minAndroidVersionCodeForCreate = 31).updateRequired(31))
    }

    private suspend fun outbox(size: Int, threshold: Long = 1000): SessionOutbox {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 }, multipartThresholdBytes = threshold, defaultPartSize = FakeSessionTransport.PART)
        outbox.startSession(QSessionCreate(clientKey = "c", questionnaireId = "q", sessionDate = "2026-07-01", startedAt = t0.toString(), participants = listOf(QArtisanRef("a1"))), "u1")
        outbox.addClip("c", testClip(tmp.newFolder(), "k1", size, "2026-07-01T10:01:00Z", listOf("a1")))
        return outbox
    }

    @Test fun `a closed multipart intent is completed, not uploaded again`() = runBlocking {
        val box = outbox(size = 3 * FakeSessionTransport.PART.toInt())
        val fake = FakeSessionTransport()
        val server = object : SessionTransport by fake {
            override suspend fun multipartCreate(body: QPresignRequest) =
                ApiClient.json.decodeFromString(QMultipartCreateResponse.serializer(), """{"objectKey":"media/u/k1/x.m4a","closed":true}""")
        }
        box.sync(server, "u1")
        assertEquals(0L, fake.bytesPut)
        assertTrue(fake.calls.contains("complete:k1"))
    }

    @Test fun `UPLOAD_MISSING from complete uploads the take again and never drops it`() = runBlocking {
        val box = outbox(size = 100)
        val fake = FakeSessionTransport()
        var missing = true
        val server = object : SessionTransport by fake {
            override suspend fun complete(idempotencyKey: String, body: QCompleteRequest): QRecording {
                if (missing) { missing = false; throw UploadMissing("No bytes in storage.") }
                return fake.complete(idempotencyKey, body)
            }
        }
        box.sync(server, "u1")
        val waiting = box.unit("c")!!.steps.single { it.kind == StepKind.CLIP }
        assertEquals("not dropped, not refused", StepState.PENDING, waiting.state)
        assertFalse("uploads again", waiting.clip!!.uploaded)
        box.sync(server, "u1")
        assertEquals(200L, fake.bytesPut)
        assertTrue(fake.calls.contains("complete:k1"))
    }

    @Test fun `the transport reads UPLOAD_MISSING off a 404`() = runBlocking {
        val body = """{"detail":{"code":"NOT_FOUND","message":"The upload is not in storage.","reason":"UPLOAD_MISSING","retry":true}}"""
        val api = Proxy.newProxyInstance(QuestionnaireApi::class.java.classLoader, arrayOf(QuestionnaireApi::class.java)) { _, _, _ ->
            throw HttpException(retrofit2.Response.error<Any>(404, body.toResponseBody()))
        } as QuestionnaireApi
        val transport = QuestionnaireTransport(api, { _, _ -> "" }, v2SwitchedOff = { false })
        val failure = runCatching {
            transport.complete("k", QCompleteRequest(objectKey = "o", clientClipKey = "k1", linkedRecordId = "s1", recording = QCompleteRecording(deviceCapturedAt = "x")))
        }.exceptionOrNull()
        assertTrue(failure is UploadMissing)
    }

    @Test fun `ten wrong join codes ask the researcher to wait`() {
        val limited = HttpException(retrofit2.Response.error<Any>(429, """{"detail":{"code":"RATE_LIMITED"}}""".toResponseBody()))
        assertEquals("join.rateLimited", joinFailureKey(limited))
        assertEquals("join.notFound", joinFailureKey(HttpException(retrofit2.Response.error<Any>(404, "{}".toResponseBody()))))
        assertTrue(QuestionnaireCopy.s("join.rateLimited").isNotBlank())
    }

    @Test fun `presign and complete carry the offset's age, and a Part 2 presign names its parent`() {
        val presign = ApiClient.json.parseToJsonElement(ApiClient.json.encodeToString(QPresignRequest.serializer(), QPresignRequest(
            sessionId = "s1", clientClipKey = "k2", sizeBytes = 10, deviceCapturedAt = "x",
            clockOffsetMeasuredAt = "2026-07-01T09:00:00Z", continuationOfId = "r1"
        ))).jsonObject
        assertEquals("r1", presign["continuationOfId"]!!.jsonPrimitive.content)
        assertEquals("2026-07-01T09:00:00Z", presign["clockOffsetMeasuredAt"]!!.jsonPrimitive.content)
        val bare = ApiClient.json.parseToJsonElement(ApiClient.json.encodeToString(QPresignRequest.serializer(), QPresignRequest(sessionId = "s1", clientClipKey = "k2", sizeBytes = 10, deviceCapturedAt = "x"))).jsonObject
        assertTrue("optional fields stay off the wire when unknown", "continuationOfId" !in bare.keys && "clockOffsetMeasuredAt" !in bare.keys)
    }

    @Test fun `a changed take's old rows leave the cached snapshot`() {
        val cache = CoverageCache(null, clock = { t0 })
        cache.apply("q", null, QSnapshot(asOfSeq = "1", takes = listOf(QSnapshotTake("r1", artisanId = "a1", targetKey = "S:d"))), delta = false)
        cache.apply("q", null, QSnapshot(asOfSeq = "2", changedRecordingIds = listOf("r1")), delta = true)
        assertTrue(cache.get("q", null)!!.snapshot.takes.isEmpty())
    }

    @Test fun `the inbox names the other takes and the Part 2 parent`() {
        val row = decisionRow(Json.parseToJsonElement("""{"recordingId":"r2","sessionId":"s1","otherRecordingIds":["r1","r0"],"part2OfRecordingId":"r1"}""") as JsonObject)
        assertEquals("r1", row.otherRecordingId)
        assertEquals("r1", row.part2OfRecordingId)
        val named = decisionRow(Json.parseToJsonElement("""{"recordingId":"r2","otherRecordingId":"r9"}""") as JsonObject)
        assertEquals("r9", named.otherRecordingId)
        assertNull(named.part2OfRecordingId)
    }

    @Test fun `a researcher's replace keeps both takes, so only a content admin is asked P7`() {
        assertFalse(replaceRemovesEarlier(isContentAdmin = false))
        assertTrue(replaceRemovesEarlier(isContentAdmin = true))
        assertTrue(File("src/main/java/com/fieldrepository/app/ui/questionnaire/SessionWorkspace.kt").readText().contains("replaceRemovesEarlier(mayDelete)"))
    }
}
