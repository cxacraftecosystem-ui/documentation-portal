package com.fieldrepository.app.questionnaire

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.data.FormBundleInbox
import com.fieldrepository.app.data.FormBundles
import com.fieldrepository.app.data.FormKinds
import com.fieldrepository.app.data.FormTab
import com.fieldrepository.app.data.QForm
import com.fieldrepository.app.data.QFormQuestion
import com.fieldrepository.app.data.QFormSection
import com.fieldrepository.app.data.QGrid
import com.fieldrepository.app.data.QGridArtisan
import com.fieldrepository.app.data.QGridCell
import com.fieldrepository.app.data.QGridSection
import com.fieldrepository.app.data.QRevision
import com.fieldrepository.app.data.QUserRef
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.ui.questionnaire.AnswerHistoryDialog
import com.fieldrepository.app.ui.questionnaire.DeleteFormPrompt
import com.fieldrepository.app.ui.questionnaire.FormsLibraryList
import com.fieldrepository.app.ui.questionnaire.GuestModel
import com.fieldrepository.app.ui.questionnaire.GuestsPanel
import com.fieldrepository.app.ui.questionnaire.InstrumentQuestion
import com.fieldrepository.app.ui.questionnaire.LockedAnswerNote
import com.fieldrepository.app.ui.questionnaire.OverrideDialog
import com.fieldrepository.app.ui.questionnaire.ReadLine
import com.fieldrepository.app.ui.questionnaire.ReceivedFormPrompt
import com.fieldrepository.app.ui.questionnaire.SessionReadView
import com.fieldrepository.app.ui.questionnaire.UpdateBanner
import com.fieldrepository.app.ui.questionnaire.WorkshopCoverageGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The forms library and the unified session screens of Field Repository Android, drawn on a device
 * (stream U4): the library tabs with Deleted for content admins, P16 and P17, the answer lock (P15),
 * a guest's answer, workshop coverage with ● ✎ and the three overrides, History, the read view, and
 * the version banner.
 */
@RunWith(AndroidJUnit4::class)
class QuestionnaireFormsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun user(id: String, role: String) = UserDto(id = id, email = "$id@x", name = id, role = role)
    private val forms = listOf(
        QForm(id = "f1", title = "My toolkit", createdById = "p1", sectionCount = 2, questionCount = 5),
        QForm(id = "f2", title = "Standard form", isShared = true, isDefault = true, kind = FormKinds.WORKSHOP_INTERVIEW),
        QForm(id = "f3", title = "Retired form", createdById = "p1", isActive = false)
    )

    @QFeature("A13")
    @Test fun aContentAdminSeesDeletedAndAProfessorDoesNot() {
        var opened: String? = null
        var actions = mutableListOf<String>()
        compose.setContent {
            var tab by remember { mutableStateOf(FormTab.MINE) }
            MaterialTheme {
                FormsLibraryList(
                    forms = forms, localForms = emptyList(), user = user("ad", "ADMIN"), tab = tab, kind = null, online = true,
                    onTab = { tab = it }, onKind = {}, onAction = { actions += it }, onOpen = { opened = it.id }
                )
            }
        }
        compose.onNodeWithText("Deleted").performClick()
        compose.onNodeWithText("Retired form").assertIsDisplayed().performClick()
        assertEquals("f3", opened)
        compose.onNodeWithText("Download pro-forma").performClick()
        assertTrue("Download pro-forma" in actions)
    }

    @QFeature("A6")
    @Test fun theLibraryListsMyFormsAndOffersNewFormToAProfessor() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                FormsLibraryList(
                    forms = forms, localForms = listOf(QForm(id = "local-form-1", title = "Written offline", ownerId = "p1")), user = user("p1", "PROFESSOR"),
                    tab = FormTab.MINE, kind = null, online = false, onTab = {}, onKind = {}, onAction = { actions += it }, onOpen = {}
                )
            }
        }
        compose.onNodeWithText("My toolkit").assertIsDisplayed()
        compose.onNodeWithText("Written offline").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Deleted").fetchSemanticsNodes().size)
        compose.onNodeWithText("New form").performClick()
        assertEquals(listOf("New form"), actions)
    }

    @QFeature("A13")
    @Test fun p16SaysAdminsCanRestoreAndDeletes() {
        var deleted = false
        compose.setContent { MaterialTheme { DeleteFormPrompt(onCancel = {}, onDelete = { deleted = true }) } }
        compose.onNodeWithText("Delete this form?").assertIsDisplayed()
        compose.onNodeWithText("Admins can restore it from Deleted.").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertTrue(deleted)
    }

    @QFeature("A10")
    @Test fun p17OffersNotNowAndAddForAReceivedForm() {
        val form = QForm(id = "f1", title = "2nd Craft Toolkit", sections = listOf(QFormSection("s1", "A", "About", questions = listOf(QFormQuestion("q1", prompt = "Name?")))))
        val bundle = FormBundles.of(form)
        var added: String? = null
        compose.setContent { MaterialTheme { ReceivedFormPrompt(FormBundleInbox.Received(bundle, null, "x.frwq"), onDismiss = {}, onAdd = { added = it.form.title }) } }
        compose.onNodeWithText("Received a form").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        compose.onNodeWithText("Add").performClick()
        assertEquals("2nd Craft Toolkit", added)
    }

    @QFeature("A11")
    @Test fun aLockedAnswerReadsP15() {
        compose.setContent { MaterialTheme { LockedAnswerNote("Answered by Nandini. Only they or a lead can change it.") } }
        compose.onNodeWithText("Answered by Nandini. Only they or a lead can change it.").assertIsDisplayed()
    }

    @QFeature("A18")
    @Test fun aGuestAnswersAQuestion() {
        var saved: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme {
                GuestsPanel(
                    guests = listOf(GuestModel("guest-1", "Meena", null, null)),
                    questions = listOf(InstrumentQuestion("L1", "A1", "Which fibre?", "sA")),
                    answerOf = { _, _ -> null }, locked = false, online = false,
                    onSave = { g, q, text, _ -> saved = g.name to "${q.displayCode}:$text" }, onEdit = {}
                )
            }
        }
        compose.onNodeWithTag("guest-guest-1-L1").performTextInput("Cotton")
        compose.onNodeWithText("Save").performClick()
        assertEquals("Meena" to "A1:Cotton", saved)
    }

    private val grid = QGrid(
        "f1", listOf(QGridArtisan("a1", "Giriraj"), QGridArtisan("a2", "Meena")),
        listOf(QGridSection("sA", "A", "About", listOf(
            QGridCell("a1", state = "RECORDED", covered = 14, total = 24, source = "RECORDED"),
            QGridCell("a2", state = "PARTIAL", covered = 3, total = 24, source = "TYPED", addRecording = true, override = "NEEDS_REVIEW")
        )))
    )

    @QFeature("B12")
    @QFeature("D4")
    @Test fun workshopCoverageMarksRecordedAndTypedAndAsksForARecording() {
        compose.setContent { MaterialTheme { WorkshopCoverageGrid(grid, mayOverride = false) { _, _ -> } } }
        compose.onNodeWithText("● 14 of 24").assertIsDisplayed()
        compose.onNodeWithText("✎ 3 of 24").assertIsDisplayed()
        compose.onNodeWithText("Add a recording?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Meena, A, Typed, 3 of 24, Needs review").assertIsDisplayed()
    }

    @QFeature("D1")
    @QFeature("B13")
    @Test fun anAdminOverridesASectionWithOneOfThreeStates() {
        var picked: String? = "unset"
        compose.setContent { MaterialTheme { OverrideDialog(current = "NEEDS_REVIEW", onDismiss = {}, onPick = { picked = it }) } }
        compose.onNodeWithText("Marked complete").assertIsDisplayed()
        compose.onNodeWithText("Needs review ✓").assertIsDisplayed()
        compose.onNodeWithText("Needs redo").performClick()
        assertEquals("NEEDS_REDO", picked)
    }

    @QFeature("B21")
    @Test fun historyListsEachVersionWithItsAuthor() {
        compose.setContent {
            MaterialTheme {
                AnswerHistoryDialog(listOf(
                    QRevision("v2", "Teak", "TYPED", actor = QUserRef("u2", "Nandini"), createdAt = "2026-07-07T08:50:00Z", current = true),
                    QRevision("v1", "Sal", "TRANSCRIPT", actor = QUserRef("u1", "Ravi"), createdAt = "2026-07-01T06:30:00Z")
                ), onDismiss = {})
            }
        }
        compose.onNodeWithText("Teak").assertIsDisplayed()
        compose.onNodeWithText("Nandini · 7 Jul 14:20 · Typed · Current").assertIsDisplayed()
        compose.onNodeWithText("Sal").assertIsDisplayed()
    }

    @QFeature("B27")
    @Test fun theReadViewShowsAnswersAndAReviewerApproves() {
        var approved = false
        compose.setContent {
            MaterialTheme {
                SessionReadView(
                    lines = listOf(ReadLine("A1", "Name?", listOf("Giriraj: Giriraj Prasad"), 1)),
                    title = "Interview_2026-07-01_Bagru", status = "PENDING", mayApprove = true, onApprove = { approved = true }, onBack = {}
                )
            }
        }
        compose.onNodeWithText("Giriraj: Giriraj Prasad").assertIsDisplayed()
        compose.onNodeWithText("Approve").performClick()
        assertTrue(approved)
    }

    @QFeature("B35")
    @Test fun anOldBuildSeesTheUpdateBanner() {
        var tapped = false
        compose.setContent { MaterialTheme { UpdateBanner(onUpdate = { tapped = true }) } }
        compose.onNodeWithText("Update").performClick()
        assertTrue(tapped)
    }
}
