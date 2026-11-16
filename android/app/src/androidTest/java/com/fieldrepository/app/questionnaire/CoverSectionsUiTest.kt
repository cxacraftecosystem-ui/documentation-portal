package com.fieldrepository.app.questionnaire

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.questionnaire.data.QTargetRef
import com.fieldrepository.app.questionnaire.ui.AttachKind
import com.fieldrepository.app.questionnaire.ui.ChangeSectionsChooser
import com.fieldrepository.app.questionnaire.ui.InPlaceTake
import com.fieldrepository.app.questionnaire.ui.PickedFile
import com.fieldrepository.app.questionnaire.ui.SectionChoice
import com.fieldrepository.app.questionnaire.ui.SectionCover
import com.fieldrepository.app.questionnaire.ui.TakesList
import com.fieldrepository.app.questionnaire.ui.UploadPlan
import com.fieldrepository.app.questionnaire.ui.UploadRow
import com.fieldrepository.app.questionnaire.ui.UploadSheetBody
import com.fieldrepository.app.questionnaire.ui.WholeSessionBlock
import com.fieldrepository.app.ui.SelectOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class CoverSectionsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val sections = listOf(
        SectionChoice("secE", "E", "Process of making and workflow"),
        SectionChoice("secF", "F", "Tools and equipment"),
        SectionChoice("secG", "G", "Markets and selling")
    )

    private fun pick(vararg labels: String) {
        val trigger = compose.onNode(hasContentDescription("Sections.", substring = true) and hasClickAction())
        runCatching { trigger.performScrollTo() }
        trigger.performClick()
        compose.waitForIdle()
        labels.forEach { compose.onNode(hasText(it) and hasClickAction()).performClick() }
        compose.onNodeWithText("Done", substring = true).performClick()
        compose.waitForIdle()
    }

    @QFeature("Q4")
    @Test fun sectionsAttachTakesSeveralSectionsCountsOnceAndWholeSessionHasNoPicker() {
        val file = PickedFile("/x/Sandesh.m4a", "sha1", 10, "audio/mp4", "m4a", Instant.parse("2026-11-13T05:12:00Z"), 2_530_000, name = "Sandesh.m4a")
        var chosen by mutableStateOf(UploadPlan.preselected("secE", null))
        var attach by mutableStateOf(UploadPlan.defaultKind("secE", null))
        compose.setContent {
            MaterialTheme {
                UploadSheetBody(
                    rows = listOf(UploadRow(file)), checking = false, attach = attach, sections = sections, chosenSections = chosen,
                    questionOptions = listOf(SelectOption("lE1", "E1 · Process?")), questionId = null, sessionDate = null,
                    onAttach = { attach = it }, onSections = { chosen = it }, onQuestion = {}, onChoose = {}, onRemove = {}, onUpload = {}, onDismiss = {},
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        }
        assertEquals(AttachKind.SECTION, attach)
        compose.onNodeWithText("Sections").assertIsDisplayed()
        compose.onNodeWithText("Counts for all questions in E").performScrollTo().assertIsDisplayed()
        pick("G · Markets and selling")
        assertEquals(listOf("secE", "secG"), chosen.toList())
        assertEquals(listOf(QTargetRef(sectionId = "secE"), QTargetRef(sectionId = "secG")), SectionCover.targets(chosen, sections))
        compose.onNodeWithText("Counts for all questions in E, G").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("2 of 3 selected", substring = true).assertCountEquals(1)
        compose.onAllNodes(hasText("selected)", substring = true)).assertCountEquals(0)
        compose.onNodeWithTag("questionnaire.upload.submit").performScrollTo().assertIsEnabled()
        pick("E · Process of making and workflow", "G · Markets and selling")
        compose.onNodeWithTag("questionnaire.upload.submit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Whole session").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("questionnaire.sections").assertCountEquals(0)
        compose.onNodeWithTag("questionnaire.upload.submit").performScrollTo().assertIsEnabled()
    }

    @QFeature("Q4")
    @Test fun changeSectionsSavesTheSectionsChosenAndClearingMovesTheTakeBackToTheWholeSession() {
        var chosen by mutableStateOf(setOf("secE"))
        val saved = mutableListOf<Set<String>>()
        var cancelled = 0
        compose.setContent {
            MaterialTheme {
                ChangeSectionsChooser(sections, chosen, onChange = { chosen = it }, onCancel = { cancelled++ }, onSave = { saved += chosen })
            }
        }
        compose.onNodeWithText("Change sections").assertIsDisplayed()
        compose.onNodeWithText("Counts for all questions in E").assertIsDisplayed()
        pick("F · Tools and equipment")
        compose.onNodeWithText("Counts for all questions in E, F").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        pick("E · Process of making and workflow", "F · Tools and equipment")
        compose.onNodeWithText("Marks no section").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf(setOf("secE", "secF"), emptySet<String>()), saved)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }

    @QFeature("Q3")
    @Test fun wholeSessionAndSectionTakesOfferChangeSectionsOnlyToThoseWhoMayChangeThem() {
        val mine = InPlaceTake("r1", "m1", "r1", "Take 1 · 41:02", "13 Nov · 10:42", 2_462_000)
        val theirs = InPlaceTake("r2", "m2", "r2", "Take 2 · 4:51", "13 Nov · 11:20", 291_000)
        val local = InPlaceTake("local:c1", "local:c1", null, "Take 3 · 0:30", "Uploading 0%", 30_000, "/data/user/0/missing.m4a")
        val sectionTake = InPlaceTake("r7", "m7", "r7", "Section take · 14:06", "13 Nov · 10:42", 846_000)
        val changed = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    WholeSessionBlock(
                        takes = listOf(mine, theirs, local), open = null, showPair = true, onOpen = {}, onRecord = {}, onUpload = {},
                        onMark = { changed += it.key }, markable = { !it.local && it.key != "r2" }
                    )
                    TakesList(
                        heading = "Takes", takes = listOf(sectionTake), scope = "S:secE", open = null, name = "Section E", onOpen = {},
                        onMark = { changed += it.key }, markable = { !it.local }
                    )
                }
            }
        }
        compose.onAllNodesWithTag("questionnaire.take.r2.mark").assertCountEquals(0)
        compose.onAllNodesWithTag("questionnaire.take.local:c1.mark").assertCountEquals(0)
        compose.onNodeWithTag("questionnaire.take.r1.mark").assertIsDisplayed().performClick()
        compose.onNodeWithTag("questionnaire.take.r7.mark").assertIsDisplayed().performClick()
        compose.onNode(hasContentDescription("Change sections for Take 1 · 41:02")).assertIsDisplayed()
        assertEquals(listOf("r1", "r7"), changed)
    }
}
