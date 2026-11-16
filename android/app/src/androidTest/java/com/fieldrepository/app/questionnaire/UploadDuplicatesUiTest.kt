package com.fieldrepository.app.questionnaire

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.questionnaire.ui.AttachKind
import com.fieldrepository.app.questionnaire.ui.PickedFile
import com.fieldrepository.app.questionnaire.ui.SectionChoice
import com.fieldrepository.app.questionnaire.ui.UploadDuplicate
import com.fieldrepository.app.questionnaire.ui.UploadRow
import com.fieldrepository.app.questionnaire.ui.UploadSheetBody
import com.fieldrepository.app.ui.SelectOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class UploadDuplicatesUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun file(name: String, sha: String) = PickedFile("/x/$name", sha, 10, "audio/mp4", "m4a", Instant.parse("2026-11-13T05:12:00Z"), 1_083_000, name = name)

    private val fresh = UploadRow(file("Sandesh_part1.m4a", "sha1"))
    private val twice = UploadRow(file("Sandesh_part1.m4a", "sha1"), UploadDuplicate.InThisList)
    private val queued = UploadRow(file("Sandesh_part2.m4a", "sha2"), UploadDuplicate.OnThisPhone)
    private val here = UploadRow(file("Meera_intro.m4a", "sha3"), UploadDuplicate.OnServer(LocalDate.parse("2026-11-13"), "s-1", "r-3", sameSession = true))
    private val there = UploadRow(file("Sandesh_old.m4a", "sha4"), UploadDuplicate.OnServer(LocalDate.parse("2026-11-13"), "s-2", "r-4", sessionTitle = "Sandesh P · Bagru · 13 Nov"))

    @QFeature("Q4")
    @Test fun eachDuplicateSaysWhereTheFileAlreadyIsAndOnlyAServerCopyOffersOpen() {
        val opened = mutableListOf<UploadRow>()
        var uploads = 0
        compose.setContent {
            MaterialTheme {
                UploadSheetBody(
                    rows = listOf(fresh, twice, queued, here, there), checking = false, attach = AttachKind.SECTION,
                    sections = listOf(SectionChoice("secE", "E", "Process of making and workflow")), chosenSections = setOf("secE"),
                    questionOptions = listOf(SelectOption("l1", "E1 · Process?")), questionId = null, sessionDate = LocalDate.parse("2026-11-13"),
                    onAttach = {}, onSections = {}, onQuestion = {}, onChoose = {}, onRemove = {}, onUpload = { uploads++ }, onDismiss = {},
                    onOpenDuplicate = { opened += it }, modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        }
        compose.onNodeWithText("Already in this list").assertIsDisplayed()
        compose.onNodeWithText("Uploading from this phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("In this session").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("In Sandesh P · Bagru · 13 Nov").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("questionnaire.upload.duplicate").assertCountEquals(4)
        compose.onAllNodesWithTag("questionnaire.upload.duplicate.open").assertCountEquals(2)
        compose.onAllNodes(hasTestTag("questionnaire.upload.duplicate.open") and hasAnyAncestor(hasTestTag("questionnaire.upload.row.sha2"))).assertCountEquals(0)
        compose.onNode(hasTestTag("questionnaire.upload.duplicate.open") and hasAnyAncestor(hasTestTag("questionnaire.upload.row.sha3"))).performScrollTo().performClick()
        compose.onNode(hasTestTag("questionnaire.upload.duplicate.open") and hasAnyAncestor(hasTestTag("questionnaire.upload.row.sha4"))).performScrollTo().performClick()
        assertEquals(listOf(here, there), opened)
        compose.onNodeWithText("Upload 1 file").performScrollTo().performClick()
        assertEquals(1, uploads)
    }
}
