package com.fieldrepository.app.forms

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4

import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.questionnaire.QFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The forms library and the unified session screens of Field Repository Android, drawn on a device
 * (stream U4): the library tabs with Deleted for content admins, P16 and P17, and the workshop
 * annexure. The session screens' device tests are the shared tree's (`androidTest/questionnaire`).
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

    @QFeature("A12")
    @Test fun theWorkshopAnnexureGroupsFormsByKindWithTheirAnswers() {
        val annexure = QAnnexure(
            workshopId = "w1", workshopTitle = "Bagru block printing",
            groups = listOf(QAnnexureGroup(kind = "WORKSHOP_INTERVIEW", label = "Workshop interview", forms = listOf(
                QAnnexureForm(id = "f1", title = "2nd Craft Toolkit", sessionCount = 3, sections = listOf(
                    QAnnexureSection("A", "About", listOf(QAnnexureQuestion("A1", "Which wood?", listOf(QAnnexureAnswer("Giriraj Prasad", "Teak")))))
                ))
            )))
        )
        compose.setContent { MaterialTheme { AnnexureView(annexure) } }
        compose.onNodeWithText("Workshop interview").assertIsDisplayed()
        compose.onNodeWithText("2nd Craft Toolkit · 3 sessions").assertIsDisplayed()
        compose.onNodeWithText("Giriraj Prasad: Teak").assertIsDisplayed()
    }

    @QFeature("A12")
    @Test fun anEmptyAnnexureSaysSo() {
        compose.setContent { MaterialTheme { AnnexureView(QAnnexure(workshopId = "w1")) } }
        compose.onNodeWithText("No questionnaires for this workshop yet.").assertIsDisplayed()
    }
}
