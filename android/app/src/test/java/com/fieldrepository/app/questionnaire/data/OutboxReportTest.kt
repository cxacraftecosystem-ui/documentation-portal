package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.questionnaire.QFeature
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.UUID

class OutboxReportTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun reset() = OutboxReport.detach()

    private val now = Instant.parse("2026-11-16T05:00:00Z")
    private fun body(key: String) = QSessionCreate(clientKey = key, questionnaireId = "q", sessionDate = "2026-11-16", startedAt = now.toString(), participants = listOf(QArtisanRef("a1")))

    private fun clipStep(id: String, captured: String, state: String, queued: String = "2026-11-16T06:00:00Z") = SessionStep(
        id, StepKind.CLIP, queued, "i-$id", state = state,
        clip = ClipPayload(clientClipKey = "c-$id", localPath = "/x/$id.m4a", sizeBytes = 10, label = "Whole session",
            recording = QCompleteRecording(deviceCapturedAt = captured))
    )

    @QFeature("B38")
    @Test fun `the phone counts every take it still holds, the failed ones and the oldest capture`() {
        val units = listOf(
            SessionUnit("c-1", createdAt = now.toString(), questionnaireId = "q", steps = listOf(
                clipStep("1", "2026-11-13T05:12:00Z", StepState.PENDING),
                clipStep("2", "2026-11-12T04:00:00Z", StepState.FAILED),
                clipStep("3", "2026-11-01T04:00:00Z", StepState.DONE),
                SessionStep("s", StepKind.SESSION, now.toString(), "i-s")
            )),
            SessionUnit("c-2", createdAt = now.toString(), questionnaireId = "q", steps = listOf(clipStep("4", "not a time", StepState.PENDING, queued = "2026-11-14T09:00:00Z")))
        )
        assertEquals(OutboxSnapshot(3, 1, Instant.parse("2026-11-12T04:00:00Z")), OutboxSnapshot.of(units))
        assertEquals(OutboxSnapshot(1, 0, Instant.parse("2026-11-14T09:00:00Z")), OutboxSnapshot.of(listOf(units[1])))
        assertEquals(OutboxSnapshot.EMPTY, OutboxSnapshot.of(emptyList()))
    }

    @QFeature("B38")
    @Test fun `the headers carry the install, the counts and the oldest time in UTC, and leave the oldest out when nothing is held`() {
        val id = "4f1c2d3e-5a6b-4c7d-8e9f-0a1b2c3d4e5f"
        assertEquals(
            mapOf(
                "X-Install-Id" to id, "X-Outbox-Pending" to "3", "X-Outbox-Failed" to "1", "X-Outbox-Oldest" to "2026-11-12T04:00:00Z"
            ),
            OutboxReport.headersOf(id, OutboxSnapshot(3, 1, Instant.parse("2026-11-12T04:00:00Z")))
        )
        val empty = OutboxReport.headersOf(id, OutboxSnapshot.EMPTY)
        assertEquals(mapOf("X-Install-Id" to id, "X-Outbox-Pending" to "0", "X-Outbox-Failed" to "0"), empty)
        assertFalse(OutboxReport.OLDEST_HEADER in empty)
    }

    @QFeature("B38")
    @Test fun `the install id is a random UUID kept for the install`() {
        val stored = mutableListOf<String>()
        val made = OutboxReport.installIdFrom(null) { stored += it }
        assertEquals(listOf(made), stored)
        assertEquals(made, UUID.fromString(made).toString())
        assertEquals(made, OutboxReport.installIdFrom(made) { stored += it })
        assertEquals(1, stored.size)
        val replaced = OutboxReport.installIdFrom("not-a-uuid") { stored += it }
        assertTrue(replaced != "not-a-uuid")
        assertEquals(2, stored.size)
    }

    @QFeature("B38")
    @Test fun `every request through the app-version interceptor carries the outbox headers`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { now })
        outbox.startSession(body("c-1"), "u1")
        outbox.addClip("c-1", testClip(tmp.newFolder(), "k1", 200, "2026-11-13T05:12:00Z", listOf("a1")))
        OutboxReport.attach("install-1") { outbox.snapshot() }
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor(ClientClock.interceptor(appVersion = "0.0.7", versionCode = 45))
            .addInterceptor { chain ->
                seen += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{}".toResponseBody()).build()
            }
            .build()
        client.newCall(Request.Builder().url("http://localhost/api/questionnaire/config").build()).execute().close()
        val first = seen.last()
        assertEquals("install-1", first.header("X-Install-Id"))
        assertEquals("1", first.header("X-Outbox-Pending"))
        assertEquals("0", first.header("X-Outbox-Failed"))
        assertEquals("2026-11-13T05:12:00Z", first.header("X-Outbox-Oldest"))
        assertEquals("0.0.7", first.header("X-App-Version"))
        outbox.sync(FakeSessionTransport(), "u1")
        client.newCall(Request.Builder().url("http://localhost/api/questionnaire/config").build()).execute().close()
        assertEquals("0", seen.last().header("X-Outbox-Pending"))
        assertNull(seen.last().header("X-Outbox-Oldest"))
        OutboxReport.detach()
        client.newCall(Request.Builder().url("http://localhost/api/questionnaire/config").build()).execute().close()
        assertNull(seen.last().header("X-Install-Id"))
        assertEquals("0.0.7", seen.last().header("X-App-Version"))
    }
}
