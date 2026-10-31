package com.fieldrepository.app.transcript

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.transcript.data.AskAnswer
import com.fieldrepository.app.transcript.data.AutoDraftResult
import com.fieldrepository.app.transcript.data.BudgetMeter
import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.DataRecording
import com.fieldrepository.app.transcript.data.DataSpeaker
import com.fieldrepository.app.transcript.data.DataTranscriptFile
import com.fieldrepository.app.transcript.data.DataTranscriptPage
import com.fieldrepository.app.transcript.data.DataTurn
import com.fieldrepository.app.transcript.data.DownloadedFile
import com.fieldrepository.app.transcript.data.GlossaryTerm
import com.fieldrepository.app.transcript.data.QuoteCard
import com.fieldrepository.app.transcript.data.SpeakerMappingBody
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptBackend
import com.fieldrepository.app.transcript.data.TranscriptLanguage
import com.fieldrepository.app.transcript.data.TranscriptSummary
import com.fieldrepository.app.transcript.data.TranscriptTurn
import com.fieldrepository.app.transcript.data.TranscriptUiText
import com.fieldrepository.app.transcript.data.TurnEditBody
import com.fieldrepository.app.transcript.data.TurnHistoryEntry
import com.fieldrepository.app.transcript.ui.TranscriptDataPanel
import com.fieldrepository.app.transcript.ui.TranscriptFileChips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * View data and Download data on a device (0.0.6): recordings with speakers, times, English,
 * summary and glossary; a refused recording shows no words; the filters reach the server; each
 * dataset downloads; a recording's transcript files download one tap each.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptDataUiTest {
    @get:Rule
    val compose = createComposeRule()

    private class Fake : TranscriptBackend {
        val pages = mutableListOf<Map<String, String>>()
        val files = mutableListOf<Pair<String, String>>()
        val downloads = mutableListOf<Triple<String, CopyTrack, Boolean>>()
        val rows = listOf(
            DataRecording(
                mediaId = "m1", file = "Interview-Giriraj.m4a", mediaType = "AUDIO", uploadedAt = "2026-06-24T10:00:00Z",
                languages = listOf(TranscriptLanguage("hi", "Hindi")), durationMs = 1_524_000, timed = true, source = "TURNS",
                speakers = listOf(DataSpeaker("speaker_0", "Nandini (researcher)", "s0"), DataSpeaker("speaker_1", "Giriraj", "s1")),
                turns = listOf(DataTurn(0, "speaker_1", "Giriraj", "s1", 9000, 15200, "00:00:09", "00:00:15", "hi", "मिट्टी और गोंद", "Clay and gum.")),
                summary = listOf("Dabu paste is clay and gum."),
            ),
            DataRecording(mediaId = "m2", file = "Refused.m4a", mediaType = "AUDIO", withheld = "CONSENT_REFUSED"),
        )
        override suspend fun dataPage(filters: Map<String, String>, cursor: String?): DataTranscriptPage { pages += filters; return DataTranscriptPage(rows) }
        override suspend fun dataFile(kind: String, fmt: String, filters: Map<String, String>, sink: File): String? { files += kind to fmt; sink.writeText("x"); return "Dataset_Test.$fmt" }
        override suspend fun download(mediaId: String, format: String, track: CopyTrack) = download(mediaId, format, track, true)
        override suspend fun download(mediaId: String, format: String, track: CopyTrack, timestamps: Boolean): DownloadedFile { downloads += Triple(format, track, timestamps); return DownloadedFile("Transcript_Test.$format", "x".toByteArray()) }
        override suspend fun get(mediaId: String): Transcript = error("unused")
        override suspend fun batch(ids: List<String>) = emptyList<TranscriptSummary>()
        override suspend fun mapSpeakers(mediaId: String, body: SpeakerMappingBody): Transcript = error("unused")
        override suspend fun speakerSuggestions(mediaId: String) = emptyList<com.fieldrepository.app.transcript.data.SpeakerSuggestion>()
        override suspend fun editTurn(mediaId: String, turnId: String, body: TurnEditBody): TranscriptTurn = error("unused")
        override suspend fun turnHistory(mediaId: String, turnId: String) = emptyList<TurnHistoryEntry>()
        override suspend fun retranslate(mediaId: String, turnId: String): TranscriptTurn = error("unused")
        override suspend fun retry(mediaId: String): Transcript = error("unused")
        override suspend fun transcribeNow(mediaId: String): Transcript = error("unused")
        override suspend fun ask(mediaId: String, question: String) = AskAnswer()
        override suspend fun autodraft(mediaId: String) = AutoDraftResult()
        override suspend fun quoteCard(mediaId: String, turnIds: List<String>) = QuoteCard()
        override suspend fun glossary(craftId: String?, q: String?) = emptyList<GlossaryTerm>()
        override suspend fun budget() = BudgetMeter()
    }

    @Test fun viewDataShowsWhatThePipelineAddedAndNothingOfARefusedRecording() {
        val fake = Fake()
        compose.setContent { MaterialTheme { TranscriptDataPanel(backend = fake) } }
        compose.waitForIdle()
        compose.onNodeWithTag("transcript.data.row.m1").assertIsDisplayed()
        compose.onNodeWithText("Giriraj  मिट्टी और गोंद", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Clay and gum.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("00:00:09", substring = true).assertIsDisplayed()
        compose.onNodeWithText("• Dabu paste is clay and gum.").assertIsDisplayed()
        compose.onNodeWithTag("transcript.data.withheld").assertIsDisplayed()
        compose.onNodeWithText(TranscriptUiText.dataWithheld).assertIsDisplayed()
    }

    @Test fun theRecordingFiltersReachTheServer() {
        val fake = Fake()
        compose.setContent { MaterialTheme { TranscriptDataPanel(backend = fake, extraFilters = mapOf("questionnaireId" to "q1")) } }
        compose.waitForIdle()
        assertEquals(mapOf("questionnaireId" to "q1"), fake.pages.first())
        compose.onNodeWithTag("transcript.data.english").performClick()
        compose.onNodeWithTag("transcript.data.language.hi").performClick()
        compose.onNodeWithTag("transcript.data.speaker").performTextInput("Giriraj")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(mapOf("questionnaireId" to "q1", "hasEnglish" to "true", "language" to "hi", "speaker" to "Giriraj"), fake.pages.last())
    }

    @Test fun eachDatasetDownloads() {
        val fake = Fake()
        compose.setContent { MaterialTheme { TranscriptDataPanel(backend = fake) } }
        compose.onNodeWithTag("transcript.data.download.turns").performClick()
        compose.onNodeWithTag("transcript.data.download.turns.csv").performClick()
        compose.waitUntil(5_000) { fake.files.isNotEmpty() }
        assertEquals(listOf("turns" to "csv"), fake.files)
    }

    @Test fun aRecordingsTranscriptFilesDownloadOneTapEach() {
        val fake = Fake()
        val files = listOf(
            DataTranscriptFile("a.md", "md", "both", true), DataTranscriptFile("b.md", "md", "both", false),
            DataTranscriptFile("c.docx", "docx", "both", true), DataTranscriptFile("d.vtt", "vtt", "english", true),
        )
        compose.setContent { MaterialTheme { TranscriptFileChips("m1", files, backend = fake) } }
        compose.onNodeWithText(TranscriptUiText.fileLabel("md", false, false)).assertIsDisplayed()
        compose.onNodeWithText("VTT, English").assertIsDisplayed()
        compose.onNodeWithTag("transcript.file.m1.md.both.false").performClick()
        compose.waitUntil(5_000) { fake.downloads.isNotEmpty() }
        assertEquals(Triple("md", CopyTrack.BOTH, false), fake.downloads.single())
        assertTrue(TranscriptUiText.fileLabel("docx", false, true) == "Word")
    }
}
