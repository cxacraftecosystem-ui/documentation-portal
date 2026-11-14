package com.fieldrepository.app.questionnaire

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.questionnaire.ui.AnsweredBySheet
import com.fieldrepository.app.questionnaire.ui.HeaderParticipant
import com.fieldrepository.app.questionnaire.ui.RecorderControl
import com.fieldrepository.app.questionnaire.ui.SectionDock
import com.fieldrepository.app.recording.RecordingDraft
import com.fieldrepository.app.recording.RecordingService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DockRecorderUiTest {
    @get:Rule
    val compose = createComposeRule()

    @QFeature("Q3")
    @Test fun theDockNamesTheSectionAndOffersRecordAndUploadSideBySide() {
        val tapped = mutableListOf<String>()
        compose.setContent { MaterialTheme { SectionDock("E", onRecord = { tapped += "record" }, onUpload = { tapped += "upload" }) } }
        compose.onNodeWithText("Section E").assertIsDisplayed()
        compose.onNodeWithText("Record section").performClick()
        compose.onNodeWithText("Upload").performClick()
        assertEquals(listOf("record", "upload"), tapped)
        val r = compose.onAllNodes(hasTestTag("questionnaire.pair.record")).fetchSemanticsNodes().single().boundsInRoot
        val u = compose.onAllNodes(hasTestTag("questionnaire.pair.upload")).fetchSemanticsNodes().single().boundsInRoot
        assertEquals(r.width, u.width, 1f)
    }

    @QFeature("Q3")
    @Test fun whileRecordingTheDockIsTheRecorderWithTimerPauseAndStopAndNoUpload() {
        val draft = RecordingDraft(clientClipKey = "k1", sessionKey = "c-1", questionnaireId = "f", adtsPath = "/x.aac", deviceCapturedAt = "2026-11-15T10:00:00Z", label = "Section E")
        val state = RecordingService.State.Recording(draft, android.os.SystemClock.elapsedRealtime() - 462_000, 0, null)
        compose.setContent { MaterialTheme { RecorderControl(state) } }
        compose.onNodeWithText("Recording section E").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag("questionnaire.pair.upload")).fetchSemanticsNodes().size)
    }

    @QFeature("Q2")
    @Test fun afterStopInAGroupThePresentArtisansArePickedAndAwayOnesAreTagged() {
        var saved: Set<String>? = null
        compose.setContent {
            MaterialTheme {
                AnsweredBySheet(
                    participants = listOf(HeaderParticipant("a1", "Ramesh Maharana", true), HeaderParticipant("a2", "Bikash Behera", true), HeaderParticipant("a3", "Sarojini Das", false)),
                    takeLine = "Section E · 12:05", initial = emptySet(), onNotNow = {}, onSave = { saved = it }
                )
            }
        }
        compose.onNodeWithText("Who answered in this take?").assertIsDisplayed()
        compose.onNodeWithText("Present artisans are picked.").assertIsDisplayed()
        compose.onNodeWithText("Away").assertIsDisplayed()
        compose.onNodeWithText("Bikash Behera").performClick()
        compose.onNodeWithText("Save take").performClick()
        assertEquals(setOf("a1"), saved)
    }
}
