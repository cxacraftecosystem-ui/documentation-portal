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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.questionnaire.data.QTargetRef
import com.fieldrepository.app.questionnaire.ui.AttachKind
import com.fieldrepository.app.questionnaire.ui.InPlaceTake
import com.fieldrepository.app.questionnaire.ui.MarkSectionsChooser
import com.fieldrepository.app.questionnaire.ui.PickedFile
import com.fieldrepository.app.questionnaire.ui.SectionChoice
import com.fieldrepository.app.questionnaire.ui.SectionCover
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
        compose.onNode(hasContentDescription("Covers sections", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        labels.forEach { compose.onNodeWithText(it).performClick() }
        compose.onNodeWithText("Done", substring = true).performClick()
        compose.waitForIdle()
    }

    @QFeature("Q4")
    @Test fun aWholeSessionUploadCanCoverSectionsAndSaysWhatItCounts() {
        val file = PickedFile("/x/Sandesh.m4a", "sha1", 10, "audio/mp4", "m4a", Instant.parse("2026-11-13T05:12:00Z"), 2_530_000, name = "Sandesh.m4a")
        var covered by mutableStateOf(emptySet<String>())
        var attach by mutableStateOf(AttachKind.WHOLE)
        compose.setContent {
            MaterialTheme {
                UploadSheetBody(
                    rows = listOf(UploadRow(file)), checking = false, attach = attach, sectionCode = "E", sectionQuestions = 10,
                    questionOptions = listOf(SelectOption("lE1", "E1 · Process?")), questionId = null, sessionDate = null,
                    onAttach = { attach = it }, onQuestion = {}, onChoose = {}, onRemove = {}, onUpload = {}, onDismiss = {},
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    sections = sections, covered = covered, onCovers = { covered = it }
                )
            }
        }
        compose.onNodeWithText("Marks no section").performScrollTo().assertIsDisplayed()
        pick("G · Markets and selling", "E · Process of making and workflow")
        assertEquals(listOf("secE", "secG"), covered.toList())
        assertEquals(listOf(QTargetRef(sectionId = "secE"), QTargetRef(sectionId = "secG")), SectionCover.targets(covered, sections))
        compose.onNodeWithText("Counts for all questions in E, G").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Section E").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("questionnaire.covers").assertCountEquals(0)
    }

    @QFeature("Q4")
    @Test fun markSectionsSavesTheSectionsChosen() {
        var chosen by mutableStateOf(setOf("secE"))
        var saved: Set<String>? = null
        var cancelled = 0
        compose.setContent {
            MaterialTheme {
                MarkSectionsChooser(sections, chosen, onChange = { chosen = it }, onCancel = { cancelled++ }, onSave = { saved = chosen })
            }
        }
        compose.onNodeWithText("Mark sections").assertIsDisplayed()
        compose.onNodeWithText("Counts for all questions in E").assertIsDisplayed()
        pick("F · Tools and equipment")
        compose.onNodeWithText("Counts for all questions in E, F").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        assertEquals(setOf("secE", "secF"), saved)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }

    @QFeature("Q3")
    @Test fun aWholeSessionTakeOffersMarkSectionsOnlyToThoseWhoMayChangeIt() {
        val mine = InPlaceTake("r1", "m1", "r1", "Take 1 · 41:02", "13 Nov · 10:42", 2_462_000)
        val theirs = InPlaceTake("r2", "m2", "r2", "Take 2 · 4:51", "13 Nov · 11:20", 291_000)
        val local = InPlaceTake("local:c1", "local:c1", null, "Take 3 · 0:30", "Uploading 0%", 30_000, "/data/user/0/missing.m4a")
        val marked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                WholeSessionBlock(
                    takes = listOf(mine, theirs, local), open = null, showPair = true, onOpen = {}, onRecord = {}, onUpload = {},
                    onMark = { marked += it.key }, markable = { !it.local && it.key == "r1" }
                )
            }
        }
        compose.onAllNodesWithTag("questionnaire.take.r2.mark").assertCountEquals(0)
        compose.onAllNodesWithTag("questionnaire.take.local:c1.mark").assertCountEquals(0)
        compose.onNodeWithTag("questionnaire.take.r1.mark").assertIsDisplayed().performClick()
        assertEquals(listOf("r1"), marked)
    }
}
