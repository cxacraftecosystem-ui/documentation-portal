package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerTranscriptItem
import com.fieldrepository.app.explorer.ui.ExplorerTranscriptStatus
import com.fieldrepository.app.explorer.ui.TranscriptStatus
import com.fieldrepository.app.transcript.data.TranscriptSummary
import com.fieldrepository.app.transcript.data.TranscriptTranslationProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExplorerTranscriptRowsTest {
    private val item = ExplorerTranscriptItem(mediaId = "m1", name = "Kiln firing", mediaType = "VIDEO", hasText = true)

    @QFeature("X9")
    @Test fun `a translating transcript carries the server's percent`() {
        val row = ExplorerTranscriptStatus.row(item, TranscriptSummary(mediaId = "m1", status = "TRANSLATING", translation = TranscriptTranslationProgress(7, 10, 70)))
        assertEquals(TranscriptStatus.TRANSLATING, row.status)
        assertEquals(70, row.percent)
    }

    @QFeature("X9")
    @Test fun `statuses map to ready, translating, transcribing and failed`() {
        assertEquals(TranscriptStatus.READY, ExplorerTranscriptStatus.row(item, TranscriptSummary(mediaId = "m1", status = "READY")).status)
        assertEquals(TranscriptStatus.FAILED, ExplorerTranscriptStatus.row(item, TranscriptSummary(mediaId = "m1", status = "NEEDS_ATTENTION")).status)
        assertEquals(TranscriptStatus.TRANSCRIBING, ExplorerTranscriptStatus.row(item, TranscriptSummary(mediaId = "m1", status = "QUEUED")).status)
        assertEquals(TranscriptStatus.READY, ExplorerTranscriptStatus.row(item, null).status)
        assertNull(ExplorerTranscriptStatus.row(item, TranscriptSummary(mediaId = "m1", status = "TRANSLATING")).percent)
    }

    @QFeature("X9")
    @Test fun `a failed transcript carries the server's reason`() {
        val row = ExplorerTranscriptStatus.row(item, TranscriptSummary(mediaId = "m1", status = "NEEDS_ATTENTION", statusMessage = "No speech found in the recording"))
        assertEquals(TranscriptStatus.FAILED, row.status)
        assertEquals("No speech found in the recording", row.message)
        assertNull(ExplorerTranscriptStatus.row(item, TranscriptSummary(mediaId = "m1", status = "NEEDS_ATTENTION", statusMessage = " ")).message)
    }

    @QFeature("X12")
    @Test fun `a count still loading reads as pending, never as zero`() {
        assertEquals("All …", ExplorerTranscriptStatus.countLabel("All", null))
        assertEquals("Failed …", ExplorerTranscriptStatus.countLabel("Failed", null, more = true))
        assertEquals("Ready 0", ExplorerTranscriptStatus.countLabel("Ready", 0))
        assertEquals("All 2,544", ExplorerTranscriptStatus.countLabel("All", 2544))
        assertEquals("All 500+", ExplorerTranscriptStatus.countLabel("All", 500, more = true))
    }
}
