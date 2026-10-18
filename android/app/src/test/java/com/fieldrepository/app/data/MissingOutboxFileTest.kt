package com.fieldrepository.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** DESIGN §9 test 56: a missing outbox file is Failed with the exact copy, and never ticked. */
class MissingOutboxFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a clip whose file is gone fails with the exact sentence`() = runBlocking {
        val outbox = SessionOutbox(InMemorySessionUnitStore())
        outbox.startSession(QSessionCreate(clientKey = "c-1", questionnaireId = "q", sessionDate = "2026-07-07", startedAt = "x", participants = listOf(QArtisanRef("a1"))), "u3")
        val clip = testClip(tmp.newFolder(), "k1", 100, "2026-07-07T05:00:00Z", listOf("a1"))
        outbox.addClip("c-1", clip)
        File(clip.localPath).delete()
        val server = FakeSessionTransport()

        outbox.sync(server, "u3")
        outbox.sync(server, "u3")

        val step = outbox.unit("c-1")!!.steps.single { it.kind == StepKind.CLIP }
        assertEquals(StepState.FAILED, step.state)
        assertEquals("The file for Section D (7 Jul) is missing on this device.", step.failure)
        assertFalse(server.calls.any { it.startsWith("presign") || it.startsWith("complete") })
    }
}
