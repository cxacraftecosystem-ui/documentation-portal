package com.fieldrepository.app.recording

import com.fieldrepository.app.questionnaire.QFeature
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P13 on the JVM: which drafts come back after a kill. The playable-m4a half of DESIGN §9 test 50
 * needs MediaMuxer and a handset; see the hand-off notes.
 */
class RecoveryScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun draft(key: String, bytes: Int, queued: Boolean = false): RecordingDraft {
        val adts = File(tmp.root, "$key.aac").apply { if (bytes > 0) writeBytes(ByteArray(bytes)) }
        return RecordingDraft(key, "c-1", "q", adts.absolutePath, "2026-07-01T10:0${key.last()}:00Z", label = "Section D", queued = queued)
    }

    @QFeature("B15")
    @Test
    fun `a cut-short take is offered, the running and queued ones are not`() {
        val drafts = listOf(draft("k1", 64_000), draft("k2", 64_000), draft("k3", 64_000, queued = true), draft("k4", 0))
        assertEquals(listOf("k1"), RecoveryScanner.recoverable(drafts, activeClipKey = "k2").map { it.clientClipKey })
        assertEquals(listOf("k4"), RecoveryScanner.empty(drafts, activeClipKey = "k2").map { it.clientClipKey })
    }

    @Test
    fun `minutes come from the duration, else the file at 64 kbps`() {
        assertEquals(12L, RecoveryScanner.minutes(draft("k5", 0).copy(durationMs = 12 * 60_000L + 5_000)))
        assertEquals(2L, RecoveryScanner.minutes(draft("k6", 8_000 * 125)))
    }
}
