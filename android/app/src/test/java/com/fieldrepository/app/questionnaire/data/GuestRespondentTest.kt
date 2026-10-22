package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.questionnaire.QFeature
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

/**
 * UNIFY U6 (owner decision 1): a person who is not a registered artisan answers a form. On the phone
 * a guest is a subject like an artisan (`R:local-…` until their row lands), offline included; the
 * server's ids replace the local ones as the session and its respondent steps land, and only the
 * wire splits a subject into `artisanId` or `respondentId`.
 */
class GuestRespondentTest {
    @get:Rule val tmp = TemporaryFolder()
    private val t0 = Instant.parse("2026-07-01T10:00:00Z")

    private fun body(guests: List<QRespondentInput>, artisans: List<String> = emptyList()) = QSessionCreate(
        clientKey = "c-g", questionnaireId = "form-1", designWorkshopId = "dw-1", sessionDate = "2026-07-01",
        startedAt = t0.toString(), participants = artisans.map { QArtisanRef(it) }, respondents = guests
    )

    @QFeature("A18")
    @Test fun `a session of guests only is recorded and answered offline, then lands with the server's ids`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        val ravi = QSubjects.newLocalGuest()
        val shopper = QSubjects.newLocalGuest()
        outbox.startSession(
            body(listOf(QRespondentInput("  Ravi   Kumar "), QRespondentInput("Shop owner", notes = "Bagru market"))), "u1",
            guestIds = listOf(ravi, shopper)
        )
        outbox.addClip("c-g", testClip(tmp.newFolder(), "k1", 200, "2026-07-01T10:02:00Z", listOf(ravi, shopper)))
        outbox.addAnswers("c-g", QAnswersPut(listOf(QAnswerItem(shopper, "lin-price", "Rs 400 a metre", sessionId = "c-g", note = "Wholesale"))))
        // Nothing has reached the server yet: the screens read the guests from the queue.
        assertEquals(listOf(ravi to "Ravi Kumar", shopper to "Shop owner"), outbox.guestsOf(outbox.unit("c-g")!!))

        val server = FakeSessionTransport()
        outbox.sync(server, "u1")

        val unit = outbox.unit("c-g")!!
        val serverRavi = server.sessionsByClientKey.getValue("c-g").respondents.first { it.name == "Ravi Kumar" }
        // The server listed its rows in another order; each local guest still got their own row.
        assertEquals(QSubjects.guest(serverRavi.id), unit.resolve(ravi))
        assertTrue(unit.resolve(shopper).startsWith("R:g-"))
        // The take names both as guests on the wire, never as artisans.
        val complete = portalJson().parseToJsonElement(server.bodies.first { it.contains("\"clientClipKey\":\"k1\"") }).jsonObject
        val subjects = complete["recording"]!!.jsonObject["subjects"]!!.jsonArray
        assertTrue(subjects.all { it.jsonObject["artisanId"]!!.jsonPrimitive.content.startsWith("R:g-") })
        val wire = QCompleteRequest("o", "k1", linkedRecordId = "s", recording = outboxJson.decodeFromString(QCompleteRequest.serializer(), server.bodies.first { it.contains("\"clientClipKey\":\"k1\"") }).recording).wire()
        assertTrue(wire.recording.subjects.all { it.artisanId == null && it.respondentId!!.startsWith("g-") })
        // The answer went as the guest's, with its note (A20).
        val answers = outboxJson.decodeFromString(QAnswersPut.serializer(), server.bodies.last())
        val item = answers.wire().items.single()
        assertNull(item.artisanId)
        assertEquals(unit.resolve(shopper).removePrefix("R:"), item.respondentId)
        assertEquals("Wholesale", item.note)
    }

    @QFeature("A17")
    @Test fun `a guest added after the session landed goes as its own respondent step, before the takes`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 })
        outbox.startSession(body(emptyList(), artisans = listOf("a1")), "u1")
        val server = FakeSessionTransport()
        outbox.sync(server, "u1")
        val guest = QSubjects.newLocalGuest()
        outbox.addRespondent("c-g", guest, QRespondentInput("Meena's mother"))
        outbox.addClip("c-g", testClip(tmp.newFolder(), "k2", 100, "2026-07-01T10:30:00Z", listOf("a1", guest)))
        outbox.sync(server, "u1")
        val order = server.calls.filter { it == "respondent" || it.startsWith("complete:") }
        assertEquals(listOf("respondent", "complete:k2"), order)
        assertTrue(outbox.unit("c-g")!!.resolve(guest).startsWith("R:g-"))
        // A17 is online: the edit names the server's row; an empty name retracts it.
        val patch = portalJson().encodeToString(QRespondentPatch.serializer(), QRespondentPatch(name = "", notes = ""))
        assertEquals("""{"name":"","notes":""}""", patch)
    }

    @QFeature("A15")
    @Test fun `a session on a form made on this phone waits until the form has landed`() = runBlocking {
        var formLanded: String? = null
        val transport = QuestionnaireTransport(
            api = UnusedApi.api, createArtisanCall = { _, _ -> "a" },
            formIdOf = { id -> if (id.startsWith("tmp-")) formLanded else id }
        )
        val failure = runCatching { transport.createSession("k", body(listOf(QRespondentInput("x"))).copy(questionnaireId = "tmp-123")) }.exceptionOrNull()
        assertTrue("waiting is a transient failure: the step stays queued", failure is IOException)
        formLanded = "form-9"
        // Now the session names the server's form; the call reaches the API (which this test does not serve).
        val next = runCatching { transport.createSession("k", body(listOf(QRespondentInput("x"))).copy(questionnaireId = "tmp-123")) }.exceptionOrNull()
        assertTrue(next !is IOException || next.message != "The form has not reached the server yet")
    }

    @Test fun `subject ids split into artisan or guest, and the routes that know artisans only never see a guest`() {
        assertEquals("R:r1", QSubjects.of(null, "r1"))
        assertEquals("a1", QSubjects.of("a1", null))
        assertEquals(listOf("a1"), QSubjects.artisans(listOf("a1", "R:r1", "R:local-x")))
        assertEquals("r1", QSubjects.respondentOf("R:r1"))
        assertNull(QSubjects.artisanOf("R:r1"))
        val result = QAnswerResult(questionLineageId = "lin", status = "SAVED", respondentId = "r1").normalised()
        assertEquals("R:r1", result.artisanId)
    }
}

/** The portal's decoder, which every request goes through. */
private fun portalJson() = com.fieldrepository.app.questionnaire.portal.QuestionnairePortal.json

/** An API that is never reached in these tests: every call fails as no network would. */
private object UnusedApi {
    val api: QuestionnaireApi = retrofit2.Retrofit.Builder()
        .baseUrl("http://127.0.0.1:9/api/")
        .client(okhttp3.OkHttpClient.Builder().addInterceptor { throw IOException("no network in this test") }.build())
        .addConverterFactory(portalJson().asConverterFactory("application/json".toMediaType()))
        .build().create(QuestionnaireApi::class.java)
}
