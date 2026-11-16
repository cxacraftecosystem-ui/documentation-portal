package com.fieldrepository.app.questionnaire

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerAudioState
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.shots.DesignBoards
import com.fieldrepository.app.explorer.ui.ExplorerMediaLoader
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.ui.AttachKind
import com.fieldrepository.app.questionnaire.ui.InPlaceTake
import com.fieldrepository.app.questionnaire.ui.InPlaceTakes
import com.fieldrepository.app.questionnaire.ui.LeaveSessionPrompt
import com.fieldrepository.app.questionnaire.ui.LocalTakes
import com.fieldrepository.app.questionnaire.ui.PickedFile
import com.fieldrepository.app.questionnaire.ui.RevealClearance
import com.fieldrepository.app.questionnaire.ui.UploadPlan
import com.fieldrepository.app.questionnaire.ui.UploadSheetBody
import com.fieldrepository.app.questionnaire.ui.WholeSessionBlock
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.ui.SelectOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class WholeSessionUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val uploaded = InPlaceTake("r1", DesignBoards.AUDIO_ID, "r1", "Take 1 · 41:02", "13 Nov · 10:42", DesignBoards.AUDIO_MS)
    private val uploading = InPlaceTake("local:c1", "local:c1", null, "Take 2 · 42:10", "Uploading 40%", 2_530_000, "/data/user/0/missing.m4a")
    private val failed = InPlaceTake("local:c2", "local:c2", null, "Take 3 · 18:03", "Upload failed", 1_083_000, "/data/user/0/missing.m4a", retryStep = "step-2")

    private val loader = object : ExplorerMediaLoader {
        override suspend fun source(entry: ExplorerEntry): String? = "https://example.invalid/${entry.mediaId}"
        override suspend fun transcript(mediaId: String): Transcript? = if (mediaId == DesignBoards.AUDIO_ID) DesignBoards.audioTranscript() else null
    }

    @Before fun fakes() {
        ExplorerMoreApi.override = DesignBoards.More()
    }

    @After fun reset() {
        ExplorerMoreApi.override = null
        ExplorerAudio.stage(ExplorerAudioState())
    }

    @QFeature("Q3")
    @Test fun theWholeSessionBlockListsItsTakesWithRecordAndUploadAsAnEqualPair() {
        var open by mutableStateOf<String?>(null)
        var recorded = 0
        var uploads = 0
        val retried = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                WholeSessionBlock(
                    takes = listOf(uploaded, uploading, failed), open = open, showPair = true,
                    onOpen = { open = it }, onRecord = { recorded++ }, onUpload = { uploads++ },
                    onRetry = { retried += it }, loader = loader
                )
            }
        }
        compose.onNodeWithText("Whole session").assertIsDisplayed()
        compose.onNodeWithText("3 takes").assertIsDisplayed()
        val recordNode = compose.onNode(hasTestTag("questionnaire.pair.record") and hasAnyAncestor(hasTestTag("questionnaire.whole"))).fetchSemanticsNode()
        val uploadNode = compose.onNode(hasTestTag("questionnaire.pair.upload") and hasAnyAncestor(hasTestTag("questionnaire.whole"))).fetchSemanticsNode()
        assertEquals(recordNode.size, uploadNode.size)
        assertEquals(recordNode.boundsInRoot.top, uploadNode.boundsInRoot.top)
        assertTrue(recordNode.boundsInRoot.right <= uploadNode.boundsInRoot.left)
        compose.onNodeWithText("Record").performClick()
        compose.onNodeWithText("Upload").performClick()
        assertEquals(1, recorded)
        assertEquals(1, uploads)
        compose.onNodeWithText("Uploading 40%").assertIsDisplayed()
        compose.onNodeWithText("Upload failed").assertIsDisplayed()
        compose.onNodeWithTag("questionnaire.take.local:c2.retry").performClick()
        assertEquals(listOf("step-2"), retried)
        compose.onAllNodesWithTag("questionnaire.take.local:c1.retry").assertCountEquals(0)
        compose.onNodeWithTag("questionnaire.take.r1.head").performClick()
        compose.waitForIdle()
        assertEquals(InPlaceTakes.openKey(InPlaceTakes.WHOLE, uploaded), open)
        compose.onNode(hasTestTag("questionnaire.take.preview") and hasAnyAncestor(hasTestTag("questionnaire.take.r1"))).assertExists()
    }

    @QFeature("Q3")
    @Test fun aFinishedSessionShowsItsWholeSessionTakesWithoutThePair() {
        compose.setContent {
            MaterialTheme {
                WholeSessionBlock(takes = listOf(uploaded), open = null, showPair = false, onOpen = {}, onRecord = {}, onUpload = {})
            }
        }
        compose.onNodeWithText("Take 1 · 41:02").assertIsDisplayed()
        compose.onAllNodesWithTag("questionnaire.pair.record").assertCountEquals(0)
        compose.onAllNodesWithTag("questionnaire.pair.upload").assertCountEquals(0)
    }

    @QFeature("Q4")
    @Test fun uploadFromTheWholeSessionOpensWithWholeSessionChosen() {
        compose.setContent {
            MaterialTheme {
                UploadSheetBody(
                    rows = emptyList(), checking = false, attach = UploadPlan.defaultKind(null, null), sectionCode = null, sectionQuestions = 0,
                    questionOptions = listOf(SelectOption("l1", "E1 · Process?")), questionId = null, sessionDate = null,
                    onAttach = {}, onQuestion = {}, onChoose = {}, onRemove = {}, onUpload = {}, onDismiss = {}
                )
            }
        }
        assertEquals(AttachKind.WHOLE, UploadPlan.defaultKind(null, null))
        compose.onNode(hasText("Whole session")).assertIsSelected()
    }

    @QFeature("Q4")
    @Test fun aPickedFileAppearsWhereItWasAttachedScrolledClearOfTheBarsAndTurnsIntoTheUploadedTake() {
        val picked = PickedFile("/data/user/0/missing.m4a", "shaX", 10, "audio/mp4", "m4a", Instant.parse("2026-11-13T05:12:00Z"), 120_000, name = "Sandesh.m4a")
        var recordings by mutableStateOf<List<QRecording>>(emptyList())
        var reveal by mutableStateOf<String?>(null)
        val arriving = LocalTakes.arriving(listOf("cX" to picked), InPlaceTakes.WHOLE, online = true)
        compose.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val above = maxHeight - 120.dp
                    LazyColumn(Modifier.fillMaxSize().testTag("list"), contentPadding = PaddingValues(top = 64.dp, bottom = 140.dp)) {
                        item { Text("Sections", Modifier.fillMaxWidth().height(above)) }
                        item {
                            WholeSessionBlock(
                                takes = InPlaceTakes.whole(recordings, LocalTakes.merge(emptyList(), if (recordings.isEmpty()) arriving else emptyList())),
                                open = null, showPair = true, onOpen = {}, onRecord = {}, onUpload = {},
                                reveal = reveal, clearance = RevealClearance(top = 72.dp, bottom = 148.dp)
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(64.dp).background(Color.Gray).testTag("bar").align(Alignment.TopCenter))
                    Box(Modifier.fillMaxWidth().height(140.dp).background(Color.Gray).testTag("dock").align(Alignment.BottomCenter))
                }
            }
        }
        reveal = InPlaceTakes.localKey("cX")
        compose.waitUntil(5_000) {
            runCatching {
                val row = compose.onNodeWithTag("questionnaire.take.local:cX").getBoundsInRoot()
                val bar = compose.onNodeWithTag("bar").getBoundsInRoot()
                val dock = compose.onNodeWithTag("dock").getBoundsInRoot()
                row.top >= bar.bottom && row.bottom <= dock.top
            }.getOrDefault(false)
        }
        compose.onNodeWithText("Uploading 0%").assertIsDisplayed()
        recordings = listOf(QRecording(id = "r9", mediaFileId = "m9", clientClipKey = "cX", capturedAt = "2026-11-13T05:12:00Z", durationMs = 120_000))
        compose.waitForIdle()
        compose.onAllNodesWithTag("questionnaire.take.local:cX").assertCountEquals(0)
        compose.onNodeWithTag("questionnaire.take.r9").assertExists()
        compose.onNodeWithText("13 Nov · 10:42").assertExists()
    }

    @QFeature("B38")
    @Test fun leavingSaysTakesKeepUploadingAndFailedOnesWaitForRetry() {
        var stayed = 0
        var left = 0
        compose.setContent {
            MaterialTheme {
                LeaveSessionPrompt(2, onStay = { stayed++ }, failed = 1) { left++ }
            }
        }
        compose.onNodeWithText("Leave this session?").assertIsDisplayed()
        compose.onNodeWithText(
            "Uploading 2 takes. They carry on in the background after you leave, and wait for signal if there is none.\n1 take didn't upload. They wait here for Retry."
        ).assertIsDisplayed()
        compose.onNodeWithText("Leave").performClick()
        assertEquals(1, left)
        assertEquals(0, stayed)
    }
}
