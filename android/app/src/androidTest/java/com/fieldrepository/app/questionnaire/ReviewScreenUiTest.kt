package com.fieldrepository.app.questionnaire

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.questionnaire.data.QSnapshotTake
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.questionnaire.ui.Instrument
import com.fieldrepository.app.questionnaire.ui.InstrumentQuestion
import com.fieldrepository.app.questionnaire.ui.InstrumentSection
import com.fieldrepository.app.questionnaire.ui.ReviewModel
import com.fieldrepository.app.questionnaire.ui.ReviewScreen
import com.fieldrepository.app.questionnaire.ui.SessionEvidence
import com.fieldrepository.app.questionnaire.ui.SessionMenuIcons
import com.fieldrepository.app.questionnaire.ui.SessionMenuItem
import com.fieldrepository.app.questionnaire.ui.SessionMenuSheet
import com.fieldrepository.app.questionnaire.ui.TrayRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewScreenUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrument = Instrument("f", "Survey", ('A'..'F').map { c ->
        InstrumentSection("sec$c", "$c", "Section $c title", (1..3).map { InstrumentQuestion("l$c$it", "$c$it", "Q $c$it", "sec$c") })
    })

    @QFeature("Q6")
    @Test fun reviewSummarisesNeedsYouAndStillToAskAndFinishes() {
        val takes = listOf(
            QSnapshotTake(recordingId = "r1", sessionId = "s1", artisanId = "a1", targetKey = "S:secA"),
            QSnapshotTake(recordingId = "r2", sessionId = "s1", artisanId = "a1", targetKey = "Q:lB1", filing = "NEEDS_DECISION")
        )
        val model = ReviewModel.of(
            instrument, SessionEvidence(setOf("a1"), takes, emptyList(), emptySet()), listOf("Meera Devi"), pending = 2,
            tray = listOf(TrayRow("st1", StepKind.CLIP, "D3", "Storage refused", files = 1))
        )
        val acted = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ReviewScreen(model, locked = false, onBack = { acted += "back" }, onNeedsYou = { acted += it.key }, onOpenSection = { acted += it }, onFinish = { acted += "finish" })
            }
        }
        compose.onNodeWithText("4 of 18 questions answered").assertIsDisplayed()
        compose.onNodeWithText("1 section done for Meera Devi. 2 takes still uploading.").assertIsDisplayed()
        compose.onNodeWithText("B1 has two takes").assertIsDisplayed()
        compose.onNodeWithText("Take of D3 did not upload").assertIsDisplayed()
        compose.onNodeWithText("STILL TO ASK · 5 SECTIONS").assertIsDisplayed()
        compose.onNodeWithText("2 left").assertIsDisplayed()
        compose.onAllNodesWithText("Not asked · 3").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Show all 5").performScrollTo().performClick()
        compose.onNodeWithText("Section F title").performScrollTo().performClick()
        compose.onNodeWithText("Finish").performClick()
        compose.onNodeWithText("Keep going").performClick()
        assertEquals(listOf("step:st1", "secF", "finish", "back"), acted)
    }

    @QFeature("Q8")
    @Test fun theSessionMenuStartsWithRecordByAndKeepsTheDestructiveItemsLast() {
        var recordBy = "SECTION"
        compose.setContent {
            MaterialTheme {
                SessionMenuSheet(
                    recordBy = "SECTION", recordByEnabled = true, onRecordBy = { recordBy = it },
                    items = listOf(
                        SessionMenuItem("Delete session", SessionMenuIcons.delete, destructive = true) {},
                        SessionMenuItem("Edit details", SessionMenuIcons.edit) {},
                        SessionMenuItem("Join code and QR", SessionMenuIcons.join) {}
                    ),
                    onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("RECORD BY").assertIsDisplayed()
        compose.onNodeWithText("Applies to this session").assertIsDisplayed()
        val edit = compose.onNodeWithText("Edit details").fetchSemanticsNode().boundsInRoot.top
        val join = compose.onNodeWithText("Join code and QR").fetchSemanticsNode().boundsInRoot.top
        val delete = compose.onNodeWithText("Delete session").fetchSemanticsNode().boundsInRoot.top
        assertTrue(edit < join && join < delete)
        compose.onNodeWithText("Question").performClick()
        assertEquals("QUESTION", recordBy)
    }
}
