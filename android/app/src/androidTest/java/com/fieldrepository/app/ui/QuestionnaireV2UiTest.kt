package com.fieldrepository.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.data.QAnswer
import com.fieldrepository.app.data.QAnswerResult
import com.fieldrepository.app.data.StepKind
import com.fieldrepository.app.ui.questionnaire.KeepOneChoice
import com.fieldrepository.app.ui.questionnaire.KeepOnePrompt
import com.fieldrepository.app.ui.questionnaire.OutboxTray
import com.fieldrepository.app.ui.questionnaire.TakeRow
import com.fieldrepository.app.ui.questionnaire.TakeSheet
import com.fieldrepository.app.ui.questionnaire.TrayRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The questionnaire v2 controls the 21 Oct 2026 audit found missing on Android, drawn on a device:
 * the fix tray (H20), Keep one… (H13) and the take sheet's Restore (removed takes).
 */
@RunWith(AndroidJUnit4::class)
class QuestionnaireV2UiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theFixTrayOffersSendAgainDiscardAndP9() {
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
                    onKeepMine = { sent += "mine:${it.stepId}" }
                )
            }
        }
        compose.onNodeWithText("Not sent (2)").assertIsDisplayed()
        compose.onNodeWithText("Send again").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Keep mine").performClick()
        compose.onNodeWithText("Keep theirs").performClick()
        assertEquals(listOf("again:s1", "discard:s1", "mine:s2", "theirs:s2"), sent)
    }

    @Test
    fun keepOneOffersPartTwoToEveryoneAndKeepingOneToContentAdminsOnly() {
        var chosen: KeepOneChoice? = null
        compose.setContent { MaterialTheme { KeepOnePrompt(body = "D3 · Giriraj", mayRemove = false, onDismiss = {}, onChoose = { chosen = it }) } }
        assertEquals(0, compose.onAllNodesWithText("Keep this take").fetchSemanticsNodes().size)
        compose.onNodeWithText("Continue as part 2").performClick()
        assertEquals(KeepOneChoice.PART_TWO, chosen)
    }

    @Test
    fun aContentAdminCanKeepThisTake() {
        var chosen: KeepOneChoice? = null
        compose.setContent { MaterialTheme { KeepOnePrompt(body = "D3 · Giriraj", mayRemove = true, onDismiss = {}, onChoose = { chosen = it }) } }
        compose.onNodeWithText("Keep this take").performClick()
        assertEquals(KeepOneChoice.THIS, chosen)
    }

    @Test
    fun aRemovedTakeIsRestoredFromShowRemoved() {
        var restored: String? = null
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
                    onRestore = { restored = it.recordingId }
                )
            }
        }
        compose.onNodeWithText("Show removed (1)").performClick()
        compose.onNodeWithText("Restore").performClick()
        assertEquals("r2", restored)
    }
}
