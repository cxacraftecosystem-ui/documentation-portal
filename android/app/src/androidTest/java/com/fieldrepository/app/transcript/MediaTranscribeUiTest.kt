package com.fieldrepository.app.transcript

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.ui.TakeRow
import com.fieldrepository.app.questionnaire.ui.TakeSheet
import com.fieldrepository.app.transcript.data.AskAnswer
import com.fieldrepository.app.transcript.data.AutoDraftResult
import com.fieldrepository.app.transcript.data.AutoTranscribeSetting
import com.fieldrepository.app.transcript.data.BackfillEstimate
import com.fieldrepository.app.transcript.data.BackfillProgress
import com.fieldrepository.app.transcript.data.BackfillView
import com.fieldrepository.app.transcript.data.BudgetMeter
import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.DownloadedFile
import com.fieldrepository.app.transcript.data.GlossaryTerm
import com.fieldrepository.app.transcript.data.PipelineStatus
import com.fieldrepository.app.transcript.data.QuoteCard
import com.fieldrepository.app.transcript.data.SpeakerMappingBody
import com.fieldrepository.app.transcript.data.SpeakerSuggestion
import com.fieldrepository.app.transcript.data.TranscribeChoices
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptAudio
import com.fieldrepository.app.transcript.data.TranscriptBackend
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.TranscriptSummary
import com.fieldrepository.app.transcript.data.TranscriptTurn
import com.fieldrepository.app.transcript.data.TranscriptWord
import com.fieldrepository.app.transcript.data.TranscriptionSwitchBody
import com.fieldrepository.app.transcript.data.TranscriptionSwitches
import com.fieldrepository.app.transcript.data.TurnEditBody
import com.fieldrepository.app.transcript.data.TurnHistoryEntry
import com.fieldrepository.app.transcript.data.UploadPolicy
import com.fieldrepository.app.transcript.data.UploadTranscribe
import com.fieldrepository.app.transcript.ui.ManualPlayback
import com.fieldrepository.app.transcript.ui.TranscribeChoiceItem
import com.fieldrepository.app.transcript.ui.TranscribeChoiceRows
import com.fieldrepository.app.transcript.ui.TranscriptAutoSwitchesCard
import com.fieldrepository.app.transcript.ui.TranscriptHost
import com.fieldrepository.app.transcript.ui.TranscriptViewer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaTranscribeUiTest {
    @get:Rule
    val compose = createComposeRule()

    @After fun forgetChoices() = TranscribeChoices.clear()

    private val batch = listOf(
        TranscribeChoiceItem("u-video", "Loom.mp4", "video/mp4"),
        TranscribeChoiceItem("u-audio", "Artisan.m4a", "audio/mp4"),
        TranscribeChoiceItem("u-photo", "Loom.jpg", "image/jpeg"),
    )

    private fun item(key: String) = compose.onNodeWithTag("upload.transcribe.item:$key")

    @QFeature("F2")
    @Test fun eachAudioAndVideoFileHasItsOwnBoxOffByDefault() {
        compose.setContent { MaterialTheme { TranscribeChoiceRows(batch, policy = UploadPolicy()) } }
        item("u-video").assertIsDisplayed().assertIsOff()
        item("u-audio").assertIsDisplayed().assertIsOff()
        compose.onAllNodesWithTag("upload.transcribe.item:u-photo").assertCountEquals(0)
        item("u-video").performClick()
        compose.waitForIdle()
        item("u-video").assertIsOn()
        item("u-audio").assertIsOff()
        assertEquals(listOf(UploadTranscribe.TRANSCRIPTION), TranscribeChoices.processingFor("u-video", "video/mp4"))
        assertEquals(emptyList<String>(), TranscribeChoices.processingFor("u-audio", "audio/mp4"))
    }

    @QFeature("F3")
    @Test fun transcribeAllTicksEveryFileAndEachBoxStaysChangeable() {
        compose.setContent { MaterialTheme { TranscribeChoiceRows(batch, policy = UploadPolicy()) } }
        compose.onNodeWithText(TranscriptStrings.uploadTranscribeAll).assertIsDisplayed()
        compose.onNodeWithTag("upload.transcribe.all").performClick()
        compose.waitForIdle()
        item("u-video").assertIsOn()
        item("u-audio").assertIsOn()
        item("u-audio").performClick()
        compose.waitForIdle()
        item("u-audio").assertIsOff()
        assertTrue(TranscribeChoices.isOn("u-video"))
        assertFalse(TranscribeChoices.isOn("u-audio"))
    }

    @QFeature("F3")
    @Test fun aSingleFileHasNoBatchToggle() {
        compose.setContent { MaterialTheme { TranscribeChoiceRows(batch.take(1), policy = UploadPolicy()) } }
        compose.onAllNodesWithTag("upload.transcribe.all").assertCountEquals(0)
    }

    @QFeature("F4")
    @Test fun anAutoSwitchShowsTheBoxTickedAndLockedWithANote() {
        compose.setContent { MaterialTheme { TranscribeChoiceRows(batch, policy = UploadPolicy(autoTranscribeVideo = true)) } }
        item("u-video").assertIsOn().assertIsNotEnabled()
        item("u-audio").assertIsOff().assertIsEnabled()
        compose.onNodeWithTag("upload.transcribe.note").assertIsDisplayed()
        compose.onNodeWithText(TranscriptStrings.uploadAutoVideo).assertIsDisplayed()
        assertEquals(listOf(UploadTranscribe.TRANSCRIPTION), UploadTranscribe.processing("video/mp4", chosen = false, policy = UploadPolicy(autoTranscribeVideo = true)))
    }

    private class Switches(var state: TranscriptionSwitches) : TranscriptBackend {
        val sent = mutableListOf<TranscriptionSwitchBody>()
        override suspend fun transcriptionSwitches() = state
        override suspend fun backfill() = BackfillView(BackfillEstimate(recordings = 12, hours = 3.25, costUsd = 0.81), state.backfill)
        override suspend fun setTranscriptionSwitches(body: TranscriptionSwitchBody): TranscriptionSwitches {
            sent += body
            state = TranscriptionSwitches(
                AutoTranscribeSetting(body.autoTranscribeVideo ?: state.autoTranscribe.video, body.autoTranscribeAudio ?: state.autoTranscribe.audio),
                if (body.transcriptBackfill != null && (body.confirmBackfill || body.transcriptBackfill == false)) state.backfill.copy(enabled = body.transcriptBackfill) else state.backfill,
            )
            return state
        }
        override suspend fun get(mediaId: String): Transcript = error("unused")
        override suspend fun batch(ids: List<String>) = emptyList<TranscriptSummary>()
        override suspend fun mapSpeakers(mediaId: String, body: SpeakerMappingBody): Transcript = error("unused")
        override suspend fun speakerSuggestions(mediaId: String) = emptyList<SpeakerSuggestion>()
        override suspend fun editTurn(mediaId: String, turnId: String, body: TurnEditBody): TranscriptTurn = error("unused")
        override suspend fun turnHistory(mediaId: String, turnId: String) = emptyList<TurnHistoryEntry>()
        override suspend fun retranslate(mediaId: String, turnId: String): TranscriptTurn = error("unused")
        override suspend fun retry(mediaId: String): Transcript = error("unused")
        override suspend fun transcribeNow(mediaId: String): Transcript = error("unused")
        override suspend fun download(mediaId: String, format: String, track: CopyTrack): DownloadedFile = error("unused")
        override suspend fun ask(mediaId: String, question: String) = AskAnswer()
        override suspend fun autodraft(mediaId: String) = AutoDraftResult()
        override suspend fun quoteCard(mediaId: String, turnIds: List<String>) = QuoteCard()
        override suspend fun glossary(craftId: String?, q: String?) = emptyList<GlossaryTerm>()
        override suspend fun budget() = BudgetMeter()
    }

    @QFeature("F4")
    @Test fun theMasterAdminSwitchesTheAutoTranscriptionOfNewVideosAndAudio() {
        val fake = Switches(TranscriptionSwitches())
        compose.setContent { MaterialTheme { TranscriptAutoSwitchesCard(canChange = true, backend = fake) } }
        compose.waitForIdle()
        compose.onNodeWithText(TranscriptStrings.settingsAutoVideo).assertIsDisplayed()
        compose.onNodeWithText(TranscriptStrings.settingsAutoAudio).assertIsDisplayed()
        compose.onNodeWithTag("transcript.autoVideo").performClick()
        compose.waitForIdle()
        assertEquals(TranscriptionSwitchBody(autoTranscribeVideo = true), fake.sent.single())
        compose.onNodeWithText(TranscriptStrings.settingsAutoVideoOn).assertIsDisplayed()
    }

    @QFeature("F4")
    @Test fun anAdminWhoIsNotTheMasterAdminReadsTheSwitches() {
        val fake = Switches(TranscriptionSwitches(AutoTranscribeSetting(video = true)))
        compose.setContent { MaterialTheme { TranscriptAutoSwitchesCard(canChange = false, backend = fake) } }
        compose.waitForIdle()
        compose.onNodeWithTag("transcript.autoVideo").performClick()
        compose.waitForIdle()
        assertTrue(fake.sent.isEmpty())
    }

    @QFeature("F5")
    @Test fun theBackfillShowsItsEstimateAndStartsOnlyAfterTheConfirmation() {
        val fake = Switches(TranscriptionSwitches())
        compose.setContent { MaterialTheme { TranscriptAutoSwitchesCard(canChange = true, backend = fake) } }
        compose.waitForIdle()
        compose.onNodeWithText(TranscriptStrings.settingsBackfillOff).assertIsDisplayed()
        compose.onNodeWithText("12 files, about 3.3 h, about \$0.81").assertIsDisplayed()
        compose.onNodeWithTag("transcript.backfill").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(TranscriptStrings.settingsBackfillConfirmTitle).assertIsDisplayed()
        compose.onNodeWithTag("transcript.backfill.cancel").performClick()
        compose.waitForIdle()
        assertTrue("cancelling sends nothing", fake.sent.isEmpty())
        compose.onNodeWithTag("transcript.backfill").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("transcript.backfill.confirm").performClick()
        compose.waitForIdle()
        assertEquals(TranscriptionSwitchBody(transcriptBackfill = true, confirmBackfill = true), fake.sent.single())
        compose.onNodeWithText(TranscriptStrings.settingsBackfillOn).assertIsDisplayed()
        compose.onNodeWithTag("transcript.backfill.progress").assertIsDisplayed()
    }

    private val video = Transcript(
        mediaId = "m_video",
        title = "Workshop · Loom",
        status = PipelineStatus.READY,
        durationMs = 20_000,
        turns = listOf(
            TranscriptTurn(
                id = "turn_v", index = 0, speakerName = "Giriraj", startMs = 1000, endMs = 4000, language = "hi",
                text = "ये करघा है",
                words = listOf(TranscriptWord("ये", 1000, 1500, 0), TranscriptWord("करघा", 1600, 2500, 3), TranscriptWord("है", 2600, 4000, 8)),
            ),
        ),
        audio = TranscriptAudio(url = null, durationMs = 20_000, video = true),
    )

    @QFeature("F1")
    @Test fun aVideoPlaysInTheViewerAndItsWordsLightInStep() {
        val playback = ManualPlayback(available = true, duration = 20.0)
        compose.setContent { MaterialTheme { TranscriptViewer(video, playback, object : TranscriptHost() {}) } }
        compose.onNodeWithTag("transcript.video").assertIsDisplayed()
        playback.seek(1.8)
        compose.waitForIdle()
        compose.onNodeWithTag("turn.text.turn_v").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "करघा"))
        playback.seek(3.0)
        compose.waitForIdle()
        compose.onNodeWithTag("turn.text.turn_v").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "है"))
    }

    @QFeature("F1")
    @Test fun anAudioRecordingShowsNoVideoSurface() {
        compose.setContent { MaterialTheme { TranscriptViewer(video.copy(audio = TranscriptAudio(durationMs = 20_000)), ManualPlayback(available = true, duration = 20.0), object : TranscriptHost() {}) } }
        compose.onAllNodesWithTag("transcript.video").assertCountEquals(0)
    }

    @QFeature("F7")
    @Test fun aQuestionnaireTakeHasNoTranscribeBox() {
        var recorded = 0
        compose.setContent {
            MaterialTheme {
                TakeSheet(
                    heading = "Section D · Giriraj",
                    takes = listOf(TakeRow("r1", 1, "Take 1  14 min", "Solo", null, local = false, removable = false)),
                    locked = false, playing = null,
                    onPlay = {}, onRemove = {}, onRecord = { recorded++ }, onUpload = {}, onDismiss = {},
                    onRestore = {}, onHistory = {},
                    note = "Add a recording?"
                )
            }
        }
        compose.onNodeWithText("Add a recording?").assertIsDisplayed()
        compose.onAllNodesWithTag("upload.transcribe").assertCountEquals(0)
        compose.onAllNodesWithText(TranscriptStrings.uploadTranscribe).assertCountEquals(0)
    }
}
