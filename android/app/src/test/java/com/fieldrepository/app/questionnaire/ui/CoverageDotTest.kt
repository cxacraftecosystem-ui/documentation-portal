package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.data.CoverageRecordings
import com.fieldrepository.app.questionnaire.data.QCoverageRecording
import com.fieldrepository.app.questionnaire.data.QGridCell
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QSubject
import com.fieldrepository.app.questionnaire.data.QTarget
import com.fieldrepository.app.questionnaire.data.QTranscript
import com.fieldrepository.app.questionnaire.data.QUserRef
import com.fieldrepository.app.transcript.data.TranscriptPreviewDto
import com.fieldrepository.app.transcript.data.TranscriptPreviewLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.fieldrepository.app.questionnaire.QFeature
import org.junit.Test
import java.time.ZoneOffset

/**
 * 0.0.6: a coverage dot opens its takes (one directly, several through a chooser, none not at all),
 * and a take's list preview is the server's or the same rule over its stored text.
 */
class CoverageDotTest {
    private fun take(id: String, media: String?, at: String, who: String = "Nandini Rao", ms: Long = 840_000) =
        QCoverageRecording(id, media, "s1", at, ms, QUserRef("u1", who))

    @QFeature("E8")
    @Test fun `one take opens directly, several open a chooser, none opens nothing`() {
        val one = QGridCell(artisanId = "a1", takes = 1, recordings = listOf(take("r1", "m1", "2026-07-07T05:12:00Z")))
        assertEquals(CoverageRecordings.Opens.One(CoverageRecordings.ofCell(one).single()), CoverageRecordings.opens(CoverageRecordings.ofCell(one)))
        val two = QGridCell(artisanId = "a1", takes = 2, recordings = listOf(take("r1", "m1", "2026-07-07T05:12:00Z"), take("r2", "m2", "2026-07-08T05:12:00Z")))
        val choose = CoverageRecordings.opens(CoverageRecordings.ofCell(two)) as CoverageRecordings.Opens.Choose
        assertEquals(listOf("r1", "r2"), choose.takes.map { it.recordingId })
        assertEquals(CoverageRecordings.Opens.Nothing, CoverageRecordings.opens(emptyList()))
    }

    @Test fun `a dot answers a tap only with takes behind it`() {
        assertFalse(CoverageRecordings.hasTakes(QGridCell(artisanId = "a1")))
        assertFalse(CoverageRecordings.hasTakes(null))
        assertTrue(CoverageRecordings.hasTakes(QGridCell(artisanId = "a1", takes = 1)))
        // A take with no audio file is listed by the server but cannot be opened.
        assertTrue(CoverageRecordings.ofCell(QGridCell(artisanId = "a1", recordings = listOf(take("r1", null, "2026-07-07T05:12:00Z")))).isEmpty())
    }

    @Test fun `the chooser reads date, researcher and length`() {
        val t = CoverageRecordings.ofCell(QGridCell(artisanId = "a1", recordings = listOf(take("r1", "m1", "2026-07-07T05:12:00Z")))).single()
        val label = CoverageRecordings.label(t, ZoneOffset.UTC)
        assertTrue(label, label.startsWith("7 Jul 2026, 05:12 · Nandini · "))
    }

    @Test fun `an older server's dot asks the list with the cell's own filters`() {
        val f = CoverageRecordings.filters("q1", "sec1", "a1", "w1", designWorkshop = false)
        assertEquals(listOf("q1"), f.get("questionnaireId"))
        assertEquals(listOf("sec1"), f.get("sectionId"))
        assertEquals(listOf("a1"), f.get("artisanId"))
        assertEquals(listOf("w1"), f.get("workshopId"))
        val guest = CoverageRecordings.filters("q1", "sec1", "R:g1", "w1", designWorkshop = true)
        assertTrue(guest.get("artisanId").isEmpty())
        assertEquals(listOf("w1"), guest.get("designWorkshopId"))
        val rows = listOf(
            QRecording("r1", mediaFileId = "m1", capturedAt = "2026-07-08T05:00:00Z", subjects = listOf(QSubject("a1")), targets = listOf(QTarget("S:sec1", sectionId = "sec1"))),
            QRecording("r2", mediaFileId = "m2", capturedAt = "2026-07-07T05:00:00Z", subjects = listOf(QSubject("a1")), targets = listOf(QTarget("Q:x", sectionId = "sec1"))),
            QRecording("r3", mediaFileId = "m3", subjects = listOf(QSubject("a2")), targets = listOf(QTarget("S:sec1", sectionId = "sec1"))),
            QRecording("r4", mediaFileId = null, subjects = listOf(QSubject("a1")), targets = listOf(QTarget("S:sec1", sectionId = "sec1"))),
            QRecording("r5", mediaFileId = "m5", removedAt = "2026-07-09T00:00:00Z", subjects = listOf(QSubject("a1")), targets = listOf(QTarget("S:sec1", sectionId = "sec1"))),
        )
        assertEquals(listOf("r2", "r1"), CoverageRecordings.fromList(rows, "sec1", "a1").map { it.recordingId })
    }

    @QFeature("E9")
    @Test fun `a take's preview is the server's, else its stored text read as speech`() {
        val server = TranscriptPreviewDto("Giriraj: Clay.", listOf(TranscriptPreviewLine("Giriraj", "speaker_1", "s1", "Clay.")))
        assertEquals(server, QTranscript(status = "COMPLETED", text = "**Speaker 1:** x", preview = server).shownPreview())
        val local = QTranscript(status = "COMPLETED", text = "Transcript **Speaker 1:** Wow!").shownPreview()!!
        assertEquals("Speaker 1", local.lines.single().speaker)
        assertEquals("Wow!", local.lines.single().text)
        assertNull(QTranscript(status = "QUEUED").shownPreview())
    }
}
