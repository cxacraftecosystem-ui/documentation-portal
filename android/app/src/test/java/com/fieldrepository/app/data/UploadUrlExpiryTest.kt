package com.fieldrepository.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Audit M23 and Low (21 Oct 2026): every part URL of a long take was signed up front and lives an
 * hour, so on a slow link the last parts met an expired URL and the take failed for good; a dead
 * multipart upload made the complete retry for ever. Each part is now signed just before it goes, an
 * expired signature (403) is signed again once, a closed intent skips the PUT, and a complete that
 * keeps failing restarts the upload.
 */
class UploadUrlExpiryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val t0 = Instant.parse("2026-07-01T10:00:00Z")

    private suspend fun outboxWithClip(size: Int, threshold: Long = 1000): Pair<SessionOutbox, ClipPayload> {
        val outbox = SessionOutbox(InMemorySessionUnitStore(), clock = { t0 }, multipartThresholdBytes = threshold, defaultPartSize = FakeSessionTransport.PART)
        outbox.startSession(QSessionCreate(clientKey = "c", questionnaireId = "q", sessionDate = "2026-07-01", startedAt = t0.toString(), participants = listOf(QArtisanRef("a1"))), "u1")
        val clip = testClip(tmp.newFolder(), "k1", size, "2026-07-01T10:01:00Z", listOf("a1"))
        outbox.addClip("c", clip)
        return outbox to clip
    }

    @Test fun `each part is signed just before it is sent`() = runBlocking {
        val (outbox, _) = outboxWithClip(size = 3 * FakeSessionTransport.PART.toInt())
        val fake = FakeSessionTransport()
        val order = mutableListOf<String>()
        val server = object : SessionTransport by fake {
            override suspend fun presignParts(objectKey: String, uploadId: String, partNumbers: List<Int>): Map<Int, String> {
                order += "sign:${partNumbers.joinToString(",")}"
                return fake.presignParts(objectKey, uploadId, partNumbers)
            }
            override suspend fun put(url: String, headers: Map<String, String>, file: File, offset: Long, length: Long, contentType: String): String {
                order += "put:${url.substringAfterLast('/')}"
                return fake.put(url, headers, file, offset, length, contentType)
            }
        }
        outbox.sync(server, "u1")
        assertEquals(listOf("sign:1", "put:1", "sign:2", "put:2", "sign:3", "put:3"), order)
    }

    @Test fun `an expired signature is signed again once, a second refusal fails the take into the tray`() = runBlocking {
        val (outbox, _) = outboxWithClip(size = 100)
        val fake = FakeSessionTransport()
        var refusals = 1
        var presigns = 0
        val server = object : SessionTransport by fake {
            override suspend fun presign(body: QPresignRequest): QPresignResponse { presigns++; return fake.presign(body) }
            override suspend fun put(url: String, headers: Map<String, String>, file: File, offset: Long, length: Long, contentType: String): String {
                if (refusals-- > 0) throw SignedUrlExpired("403")
                return fake.put(url, headers, file, offset, length, contentType)
            }
        }
        outbox.sync(server, "u1")
        assertEquals(2, presigns)
        assertTrue(fake.calls.contains("complete:k1"))

        val (second, _) = outboxWithClip(size = 100)
        refusals = 2
        second.sync(server, "u1")
        assertEquals(StepState.FAILED, second.unit("c")!!.steps.single { it.kind == StepKind.CLIP }.state)
    }

    @Test fun `a closed intent is not uploaded again`() = runBlocking {
        val (outbox, _) = outboxWithClip(size = 100)
        val fake = FakeSessionTransport()
        val server = object : SessionTransport by fake {
            override suspend fun presign(body: QPresignRequest) = QPresignResponse(objectKey = "media/u/k1/x.m4a", closed = true)
        }
        outbox.sync(server, "u1")
        assertEquals(0L, fake.bytesPut)
        assertTrue(fake.calls.contains("complete:k1"))
    }

    @Test fun `a multipart complete that keeps failing restarts the upload instead of looping`() = runBlocking {
        val (outbox, _) = outboxWithClip(size = 2 * FakeSessionTransport.PART.toInt())
        val fake = FakeSessionTransport()
        var failures = SessionOutbox.COMPLETE_RESTART_AFTER
        var creates = 0
        val server = object : SessionTransport by fake {
            override suspend fun multipartCreate(body: QPresignRequest): QMultipartCreateResponse { creates++; return fake.multipartCreate(body) }
            override suspend fun uploadState(clientClipKey: String): QUploadState? = null
            override suspend fun multipartComplete(objectKey: String, uploadId: String, parts: List<QPartDone>) {
                if (failures-- > 0) throw IOException("NoSuchUpload")
                fake.multipartComplete(objectKey, uploadId, parts)
            }
        }
        repeat(SessionOutbox.COMPLETE_RESTART_AFTER) { outbox.sync(server, "u1") }
        val clip = outbox.unit("c")!!.steps.single { it.kind == StepKind.CLIP }.clip!!
        assertNull("the dead upload id is dropped", clip.uploadId)
        assertTrue(clip.partsDone.isEmpty())
        outbox.sync(server, "u1")
        assertEquals("a fresh upload after the restart", 2, creates)
        assertTrue(fake.calls.contains("complete:k1"))
    }
}
