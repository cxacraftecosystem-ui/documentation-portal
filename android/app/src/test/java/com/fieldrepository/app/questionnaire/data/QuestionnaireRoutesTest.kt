package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The designer portal's additions to the session routes, through the app's own client: each call
 * reaches the route the server mounts (`backend/app/api/routes/questionnaire_v2.py`), with the body it
 * reads, and the answer decodes into what the screens show.
 */
class QuestionnaireRoutesTest {
    private data class Seen(val method: String, val path: String, val query: String?, val body: String)

    private val seen = mutableListOf<Seen>()
    private var reply: (String) -> String = { "{}" }

    private val api: QuestionnaireApi = Retrofit.Builder()
        .baseUrl("http://localhost/api/")
        .client(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            val path = request.url.encodedPath.removePrefix("/api/")
            seen += Seen(request.method, path, request.url.encodedQuery, body)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply(path).toResponseBody("application/json".toMediaType())).build()
        }.build())
        .addConverterFactory(QuestionnairePortal.json.asConverterFactory("application/json".toMediaType()))
        .build().create(QuestionnaireApi::class.java)

    @QFeature("A14")
    @Test fun `content admins list the deleted sessions and restore one whole`() = runBlocking {
        reply = { path -> if (path == "questionnaire/sessions") """{"items":[{"id":"s9","title":"Interview_2026-07-01","deletedAt":"2026-07-02T00:00:00Z"}]}""" else """{"id":"s9","title":"Interview_2026-07-01"}""" }
        val deleted = api.sessions(deleted = true, limit = 100)
        assertEquals("limit=100&deleted=true", seen.last().query)
        assertEquals("2026-07-02T00:00:00Z", deleted.items.single().deletedAt)
        val back = api.restoreSession("s9")
        assertEquals(Seen("POST", "questionnaire/sessions/s9/restore", null, ""), seen.last())
        assertEquals(null, back.deletedAt)
    }

    @QFeature("B6")
    @Test fun `Add researcher names the colleague and their role, keyed once`() = runBlocking {
        reply = { """{"id":"s1","researchers":[{"userId":"u2","name":"Ankur","role":"VOLUNTEER"}]}""" }
        val session = api.addResearcher("key-1", "s1", QResearcherRef("u2", "VOLUNTEER"))
        assertEquals("questionnaire/sessions/s1/researchers", seen.last().path)
        assertEquals("""{"userId":"u2","role":"VOLUNTEER"}""", seen.last().body)
        assertEquals("VOLUNTEER", session.researchers.single().role)
    }

    @QFeature("B21")
    @Test fun `History lists an answer's revisions with who wrote each`() = runBlocking {
        reply = { """{"answerId":"ans1","revisions":[{"id":"v2","text":"Teak","origin":"TYPED","actor":{"id":"u3","name":"Nandini Rao"},"createdAt":"2026-07-07T05:12:00Z","current":true},{"id":"v1","text":"Sal","origin":"TRANSCRIPT","createdAt":"2026-07-01T05:00:00Z"}]}""" }
        val history = api.answerRevisions("ans1")
        assertEquals("questionnaire/answers/ans1/revisions", seen.last().path)
        assertEquals(listOf("Teak", "Sal"), history.revisions.map { it.text })
        assertTrue(history.revisions.first().current)
    }

    @QFeature("B40")
    @Test fun `Removed takes and a take's history come from their own routes`() = runBlocking {
        reply = { path ->
            if (path.endsWith("/events")) """{"recordingId":"r1","events":[{"id":"e1","kind":"REMOVED","actor":{"id":"u1","name":"Admin"},"at":"2026-07-08T04:00:00Z"}]}"""
            else """{"items":[{"id":"r1","removedAt":"2026-07-08T04:00:00Z"}]}"""
        }
        val removed = api.removedRecordings(sessionId = "s1")
        assertEquals("questionnaire/recordings/removed", seen.last().path)
        assertEquals("sessionId=s1", seen.last().query)
        assertEquals("r1", removed.items.single().id)
        val events = api.recordingEvents("r1")
        assertEquals("REMOVED", events.events.single().kind)
    }

    @QFeature("B27")
    @Test fun `review approves or returns the session through the review queue`() = runBlocking {
        reply = { """{"status":"APPROVED"}""" }
        api.approveSession("s1", QReviewAction())
        assertEquals(Seen("POST", "review/questionnaire/s1/approve", null, "{}"), seen.last())
        api.returnSession("s1", QReviewAction("Add the market section"))
        assertEquals("""{"notes":"Add the market section"}""", seen.last().body)
    }

    @QFeature("C7")
    @Test fun `merge moves this session into the one picked`() = runBlocking {
        reply = { """{"id":"s2","title":"Interview_2026-07-01"}""" }
        val target = api.mergeInto("s1", "s2")
        assertEquals(Seen("POST", "questionnaire/sessions/s1/merge-into/s2", null, ""), seen.last())
        assertEquals("s2", target.id)
    }

    @QFeature("B31")
    @Test fun `the start sheet asks the server which form a workshop opens with`() = runBlocking {
        reply = { """{"questionnaireId":"f2","reason":"PRIMARY_BINDING","bound":[{"questionnaireId":"f2","isPrimary":true,"kind":"WORKSHOP_INTERVIEW"},{"questionnaireId":"f3","kind":"MARKET_SURVEY"}]}""" }
        val resolved = api.formFor(designWorkshopId = "dw1")
        assertEquals("questionnaire/form-for", seen.last().path)
        assertEquals("designWorkshopId=dw1", seen.last().query)
        assertEquals("f2", resolved.questionnaireId)
        assertEquals(FormResolution.PRIMARY_BINDING.wire, resolved.reason)
    }

    @QFeature("B34")
    @Test fun `a take's retry is the transcription route, and while paused it only queues`() = runBlocking {
        reply = { """{"status":"QUEUED","paused":true}""" }
        val transcript = api.transcribe("r1")
        assertEquals("questionnaire/recordings/r1/transcribe", seen.last().path)
        assertTrue(transcript.paused)
    }
}
