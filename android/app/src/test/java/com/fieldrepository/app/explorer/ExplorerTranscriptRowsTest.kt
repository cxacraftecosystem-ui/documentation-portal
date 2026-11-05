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
}
