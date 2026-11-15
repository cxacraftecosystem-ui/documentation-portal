package com.fieldrepository.app.questionnaire

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerAudioState
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.shots.DesignBoards
import com.fieldrepository.app.explorer.ui.ExplorerMediaLoader
import com.fieldrepository.app.explorer.ui.ExplorerUiText
import com.fieldrepository.app.explorer.ui.audioItem
import com.fieldrepository.app.explorer.ui.audioLines
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.ui.InPlaceTake
import com.fieldrepository.app.questionnaire.ui.InPlaceTakes
import com.fieldrepository.app.questionnaire.ui.InstrumentQuestion
import com.fieldrepository.app.questionnaire.ui.OpenTakeGuard
import com.fieldrepository.app.questionnaire.ui.OverflowItem
import com.fieldrepository.app.questionnaire.ui.QuestionRow
import com.fieldrepository.app.questionnaire.ui.QuestionState
import com.fieldrepository.app.questionnaire.ui.QuestionStatus
import com.fieldrepository.app.questionnaire.ui.TakeRow
import com.fieldrepository.app.questionnaire.ui.TakeSheet
import com.fieldrepository.app.questionnaire.ui.TakesList
import com.fieldrepository.app.transcript.data.Transcript
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TakesInPlaceUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val sectionTake = InPlaceTake("r1", DesignBoards.AUDIO_ID, "r1", "Section take · 41:02", "15 Nov · 10:42", DesignBoards.AUDIO_MS)
    private val partTwo = InPlaceTake("r2", "aud-part-2", "r2", "Part 2 · 4:51", "15 Nov · 11:20", 291_000)
    private val questionTake = InPlaceTake("r3", "aud-withheld", "r3", "15 Nov · 10:50", null, 192_000)
    private val localTake = InPlaceTake("local:c1", "local:c1", null, "15 Nov · 11:40", "On this device", 30_000, "/data/user/0/missing.m4a")

    private val loader = object : ExplorerMediaLoader {
        override suspend fun source(entry: ExplorerEntry): String? = "https://example.invalid/${entry.mediaId}"
        override suspend fun transcript(mediaId: String): Transcript? = if (mediaId == DesignBoards.AUDIO_ID) DesignBoards.audioTranscript() else null
    }

    private var recording by mutableStateOf(false)
    private var reviewing by mutableStateOf(false)
    private var sectionShown by mutableStateOf(listOf(sectionTake, partTwo))
    private var opened: String? = null
    private var managed = 0

    @Before fun fakes() {
        ExplorerMoreApi.override = DesignBoards.More()
        val entry = InPlaceTakes.entry(sectionTake, "Section E")
        ExplorerAudio.stage(ExplorerAudioState(item = audioItem(entry, "https://example.invalid/a", audioLines(DesignBoards.audioTranscript())), playing = true, positionMs = 1_066_000, durationMs = DesignBoards.AUDIO_MS))
    }

    @After fun reset() {
        ExplorerMoreApi.override = null
        ExplorerAudio.stage(ExplorerAudioState())
    }

    private fun session() {
        compose.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf<String?>(null) }
                opened = open
                OpenTakeGuard(open, InPlaceTakes.mediaOf(open, sectionShown + listOf(questionTake, localTake)), hidden = reviewing) { open = null }
                LaunchedEffect(recording) { if (recording) open = null }
                if (!reviewing) Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    TakesList(QuestionnaireCopy.t("takesHeading"), sectionShown, "S:secE", open, "Section E", { open = it }, onManage = { managed++ }, loader = loader)
                    TakesList(null, listOf(questionTake), "Q:lE2", open, "E2", { open = it }, loader = loader)
                    TakesList(null, listOf(localTake), "Q:lE3", open, "E3", { open = it })
                }
            }
        }
    }

    private fun preview(of: String) = compose.onNode(hasTestTag("questionnaire.take.preview") and hasAnyAncestor(hasTestTag("questionnaire.take.$of")))

    @QFeature("TP2")
    @Test fun aSectionTakeOpensTheExplorersPlayerAndTranscriptInPlaceWithoutItsChrome() {
        session()
        compose.onNodeWithTag("questionnaire.take.r1.head").performClick()
        compose.waitForIdle()
        preview("r1").assertExists()
        compose.onNodeWithTag("explorer.audio.card").assertExists()
        compose.onNodeWithTag("explorer.audio.lines").assertExists()
        compose.onAllNodesWithText("What goes into the mud before you print?").assertCountEquals(1)
        compose.onAllNodesWithTag("explorer.preview.close").assertCountEquals(0)
        compose.onAllNodesWithTag("explorer.preview.next").assertCountEquals(0)
        compose.onAllNodesWithTag("explorer.preview.docx").assertCountEquals(0)
        compose.onNodeWithText(QuestionnaireCopy.t("openFullView")).assertIsDisplayed()
        assertEquals("S:secE|r1", opened)
    }

    @QFeature("TP3")
    @Test fun openingAnotherTakeCollapsesTheFirst() {
        session()
        compose.onNodeWithTag("questionnaire.take.r1.head").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("questionnaire.take.r3.head").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("questionnaire.take.preview").assertCountEquals(1)
        preview("r3").assertExists()
        assertEquals("Q:lE2|r3", opened)
    }

    @QFeature("TP3")
    @Test fun collapsingATakeStopsItsPlayback() {
        session()
        compose.onNodeWithTag("questionnaire.take.r1.head").performClick()
        compose.waitForIdle()
        assertEquals(DesignBoards.AUDIO_ID, ExplorerAudio.state.value.item?.mediaId)
        compose.onNodeWithTag("questionnaire.take.r1.head").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("questionnaire.take.preview").assertCountEquals(0)
        assertNull(ExplorerAudio.state.value.item)
    }

    @QFeature("TP3")
    @Test fun theRecorderStartingClosesTheOpenTakeAndStopsIt() {
        session()
        compose.onNodeWithTag("questionnaire.take.r1.head").performClick()
        compose.waitForIdle()
        recording = true
        compose.waitForIdle()
        compose.onAllNodesWithTag("questionnaire.take.preview").assertCountEquals(0)
        assertNull(ExplorerAudio.state.value.item)
    }

    @QFeature("TP6")
    @Test fun aTakeThatLeavesTheListWhileOpenStopsPlaying() {
        session()
        compose.onNodeWithTag("questionnaire.take.r1.head").performClick()
        compose.waitForIdle()
        assertEquals(DesignBoards.AUDIO_ID, ExplorerAudio.state.value.item?.mediaId)
        sectionShown = listOf(partTwo)
        compose.waitForIdle()
        compose.onAllNodesWithTag("questionnaire.take.preview").assertCountEquals(0)
        assertNull(ExplorerAudio.state.value.item)
        assertNull(opened)
    }

    @QFeature("TP3")
    @Test fun openingReviewStopsTheOpenTake() {
        session()
        compose.onNodeWithTag("questionnaire.take.r1.head").performClick()
        compose.waitForIdle()
        assertEquals(DesignBoards.AUDIO_ID, ExplorerAudio.state.value.item?.mediaId)
        reviewing = true
        compose.waitForIdle()
        assertNull(ExplorerAudio.state.value.item)
        assertNull(opened)
        reviewing = false
        compose.waitForIdle()
        compose.onAllNodesWithTag("questionnaire.take.preview").assertCountEquals(0)
    }

    @QFeature("TP7")
    @Test fun theQuestionPlayControlKeepsA48dpTargetBySection() {
        val question = InstrumentQuestion("lE2", "E2", "Which stages require the highest level of skill?", "secE")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth()) {
                    QuestionRow(
                        question = question, status = QuestionStatus(QuestionState.IN_SECTION_TAKE), byQuestion = false, locked = false,
                        typedPreview = null, typing = false, overflow = listOf(OverflowItem(QuestionnaireCopy.t("typeAnAnswer"), Icons.Filled.Keyboard) {}),
                        showPair = false, showUpload = true, onRecord = {}, onUpload = {}, onOpenTyped = {}, onOpenTakes = {}
                    ) {}
                }
            }
        }
        compose.onNodeWithTag("questionnaire.question.E2.play").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @QFeature("TP5")
    @Test fun aWithheldTranscriptShowsWhatTheExplorerShows() {
        session()
        compose.onNodeWithTag("questionnaire.take.r3.head").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ExplorerUiText.noTranscript).assertExists()
    }

    @QFeature("TP6")
    @Test fun aTakeStillOnThePhoneSaysTranscriptAfterUploadAndHasNoFullView() {
        session()
        compose.onNodeWithTag("questionnaire.take.local:c1.head").performClick()
        compose.waitForIdle()
        preview("local:c1").assertExists()
        compose.onNodeWithText(QuestionnaireCopy.t("transcriptAfterUpload")).assertExists()
        compose.onAllNodesWithTag("questionnaire.take.full").assertCountEquals(0)
    }

    @QFeature("TP2")
    @Test fun openFullViewShowsTheExplorersOwnPreviewAndKeepsPlaying() {
        session()
        compose.onNodeWithTag("questionnaire.take.r1.head").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("questionnaire.take.full").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.preview.close").assertExists()
        compose.onNodeWithTag("explorer.preview.close").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("explorer.preview.close").assertCountEquals(0)
        preview("r1").assertExists()
        assertEquals(DesignBoards.AUDIO_ID, ExplorerAudio.state.value.item?.mediaId)
    }

    @QFeature("TP4")
    @Test fun manageTakesStillOpensTheTakeSheet() {
        session()
        compose.onNodeWithText(QuestionnaireCopy.t("manageTakes")).performClick()
        assertEquals(1, managed)
    }

    @QFeature("TP1")
    @Test fun aTakeSheetRowPlaysThroughTheSamePreview() {
        compose.setContent {
            MaterialTheme {
                TakeSheet(
                    heading = "Section E",
                    takes = listOf(TakeRow("r1", 1, "Take 1  41 min", "Solo", null, local = false, removable = false, mediaFileId = DesignBoards.AUDIO_ID, durationMs = DesignBoards.AUDIO_MS)),
                    locked = false,
                    onRemove = {}, onRecord = {}, onUpload = {}, onDismiss = {}
                )
            }
        }
        compose.onNodeWithContentDescription(QuestionnaireCopy.t("playTake")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("questionnaire.take.preview").assertExists()
        compose.onNodeWithTag("explorer.audio.card").assertExists()
    }
}
