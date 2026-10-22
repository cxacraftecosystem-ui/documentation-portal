package com.fieldrepository.app.questionnaire

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QAnswer
import com.fieldrepository.app.questionnaire.data.QAnswerResult
import com.fieldrepository.app.questionnaire.data.QArtisanOption
import com.fieldrepository.app.questionnaire.data.QFormOption
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.questionnaire.ui.AnswerLine
import com.fieldrepository.app.questionnaire.ui.CellMark
import com.fieldrepository.app.questionnaire.ui.CoverageGrid
import com.fieldrepository.app.questionnaire.ui.GridCell
import com.fieldrepository.app.questionnaire.ui.GridRow
import com.fieldrepository.app.questionnaire.ui.HeaderParticipant
import com.fieldrepository.app.questionnaire.ui.InstrumentQuestion
import com.fieldrepository.app.questionnaire.ui.InstrumentSection
import com.fieldrepository.app.questionnaire.ui.OutboxTray
import com.fieldrepository.app.questionnaire.ui.QuestionBlock
import com.fieldrepository.app.questionnaire.ui.SessionStartSheet
import com.fieldrepository.app.questionnaire.ui.TakeRow
import com.fieldrepository.app.questionnaire.ui.TakeSheet
import com.fieldrepository.app.questionnaire.ui.TrayRow
import com.fieldrepository.app.questionnaire.ui.UpdateBanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The questionnaire session screens on a device (UNIFY §5.4, Android): a guest session started with
 * no artisan, a locked answer with P15, the fix tray with "Send without the conflicting answers",
 * the take sheet's history and restore, the ● ✎ marks and the overrides on the grid, and the
 * version-code banner.
 */
@RunWith(AndroidJUnit4::class)
class QuestionnaireSessionUiTest {
    @get:Rule
    val compose = createComposeRule()

    @QFeature("A18")
    @Test fun aGuestSessionStartsFromTheSheetAndWaitsOnThePhone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val v2 = QuestionnaireV2.get(context)
        var started: String? = null
        compose.setContent {
            MaterialTheme {
                SessionStartSheet(
                    v2 = v2, workshops = emptyList(), forms = listOf(QFormOption("form-device-test", "Market survey", isDefault = true)),
                    artisans = listOf(QArtisanOption("a1", "Giriraj", "Bagru")), meId = null, meName = "Nandini",
                    onDismiss = {}, onStarted = { started = it }, onJoined = {}, onError = {}
                )
            }
        }
        compose.onNodeWithText("Someone else").performScrollTo().performClick()
        compose.onNodeWithText("Their name").performTextInput("Shop owner")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Start session").performScrollTo().performClick()
        compose.waitUntil(15_000) { started != null }
        val unit = runBlocking { v2.outbox.unit(started!!) }!!
        val guests = v2.outbox.guestsOf(unit)
        assertEquals(listOf("Shop owner"), guests.map { it.second })
        assertTrue(guests.single().first.startsWith("R:local-"))
        runBlocking { v2.outbox.forget(started!!) }
    }

    @QFeature("A11")
    @Test fun anAnswerSomeoneElseWroteReadsLockedWithP15() {
        val question = InstrumentQuestion("lin1", "D3", "Which wood?", "secD")
        val p15 = "Answered by Nandini. Only they or a lead can change it."
        compose.setContent {
            MaterialTheme {
                QuestionBlock(
                    question = question, roster = listOf(HeaderParticipant("a1", "Giriraj", true)), known = emptyList(), overlay = emptyList(),
                    typed = setOf("a1" to "lin1"), locked = false, typeAnswers = true,
                    answerLines = listOf(AnswerLine("a1", "Teak", null, onPhone = false, lockedLine = p15, answerId = "ans1")),
                    currentText = { "Teak" }, lockedFor = { p15 }, nameOf = { "Giriraj" },
                    onRecord = {}, onOpenTakes = {}, onSaveAnswer = { _, _, _, _ -> }, onHistory = {}
                )
            }
        }
        assertEquals(2, compose.onAllNodesWithText(p15).fetchSemanticsNodes().size)
        assertEquals("no answer box to type over it", 0, compose.onAllNodesWithText("Add a note").fetchSemanticsNodes().size)
        compose.onNodeWithText("History").assertIsDisplayed()
    }

    @QFeature("C8")
    @Test fun anAnswerIsWrittenInTheRichAnswerBox() {
        val question = InstrumentQuestion("lin2", "D4", "How is the dye fixed?", "secD")
        var saved: String? = null
        compose.setContent {
            MaterialTheme {
                QuestionBlock(
                    question = question, roster = listOf(HeaderParticipant("a1", "Giriraj", true)), known = emptyList(), overlay = emptyList(),
                    typed = emptySet(), locked = false, typeAnswers = true, answerLines = emptyList(),
                    currentText = { "" }, lockedFor = { null }, nameOf = { "Giriraj" },
                    onRecord = {}, onOpenTakes = {}, onSaveAnswer = { text, _, _, _ -> saved = text }, onHistory = {}
                )
            }
        }
        // The formatting toolbar is the rich box's (drawn while one of its blocks holds focus); a plain
        // text field has none.
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Bold").assertIsDisplayed()
        assertEquals(null, saved)
    }

    @QFeature("B39")
    @Test fun theFixTrayOffersSendAgainDiscardP9AndSendWithoutTheConflictingAnswers() {
        val sent = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                OutboxTray(
                    rows = listOf(
                        TrayRow("s1", StepKind.CLIP, "Section D (7 Jul)", "Storage refused the upload (403).", files = 1),
                        TrayRow(
                            "s2", StepKind.ANSWERS, "D3 · Giriraj", "Teak",
                            conflict = QAnswerResult("a1", "lin3", "CONFLICT", current = QAnswer(version = 4, text = "Teak")),
                            mine = "Bamboo", sendAgain = false
                        )
                    ),
                    onSendAgain = { sent += "again:${it.stepId}" },
                    onDiscard = { sent += "discard:${it.stepId}" },
                    onKeepTheirs = { sent += "theirs:${it.stepId}" },
                    onKeepMine = { sent += "mine:${it.stepId}" },
                    onSendWithoutConflicts = { sent += "without:$it" }
                )
            }
        }
        compose.onNodeWithText("Not sent (2)").assertIsDisplayed()
        compose.onNodeWithText("Send again").performClick()
        compose.onNodeWithText("Keep mine").performClick()
        compose.onNodeWithText("Send without the conflicting answers").performClick()
        assertEquals(listOf("again:s1", "mine:s2", "without:s2"), sent)
    }

    @QFeature("B40")
    @Test fun aTakeOpensItsHistoryAndARemovedOneIsRestored() {
        val picked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                TakeSheet(
                    heading = "Section D · Giriraj",
                    takes = listOf(
                        TakeRow("r1", 1, "Take 1  14 min", "Solo", null, local = false, removable = false),
                        TakeRow("r2", 0, "3 min", "", null, local = false, removable = false, removed = true)
                    ),
                    locked = false, playing = null,
                    onPlay = {}, onRemove = {}, onRecord = {}, onUpload = {}, onDismiss = {},
                    onRestore = { picked += "restore:${it.recordingId}" },
                    onHistory = { picked += "history:${it.recordingId}" },
                    note = "Add a recording?"
                )
            }
        }
        compose.onNodeWithText("Add a recording?").assertIsDisplayed()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Show removed (1)").performClick()
        compose.onNodeWithText("Restore").performClick()
        assertEquals(listOf("history:r1", "restore:r2"), picked)
    }

    @QFeature("D4")
    @Test fun theGridMarksRecordedTypedAndTheOverrides() {
        val section = InstrumentSection("secD", "D", "Process", emptyList())
        compose.setContent {
            MaterialTheme {
                CoverageGrid(
                    rows = listOf(GridRow(section, listOf(
                        GridCell("a1", CellMark.RECORDED, 1), GridCell("a2", CellMark.TYPED), GridCell("a3", CellMark.NONE, override = "NEEDS_REDO")
                    ))),
                    names = mapOf("a1" to "Giriraj", "a2" to "Meena", "a3" to "Ravi"), currentSectionId = null,
                    onOpenSection = {}, onOpenCell = { _, _ -> }
                )
            }
        }
        // A phone shows one summary per section: two of three covered, typed, and the override.
        compose.onNodeWithText("D", substring = false).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("✎", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @QFeature("B35")
    @Test fun anOldBuildSeesTheUpdateBanner() {
        var updated = false
        compose.setContent { MaterialTheme { UpdateBanner { updated = true } } }
        compose.onNodeWithText("Update when you have Wi-Fi. Your recordings are safe.").assertIsDisplayed()
        compose.onNodeWithText("Update").performClick()
        assertTrue(updated)
    }
}
