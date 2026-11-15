package com.fieldrepository.app.questionnaire

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.questionnaire.ui.AttachKind
import com.fieldrepository.app.questionnaire.ui.PickedFile
import com.fieldrepository.app.questionnaire.ui.UploadPlan
import com.fieldrepository.app.questionnaire.ui.UploadRow
import com.fieldrepository.app.questionnaire.ui.UploadSheet
import com.fieldrepository.app.ui.SelectOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class UploadSheetUiTest {
    @get:Rule
    val compose = createComposeRule()

    @QFeature("Q4")
    @Test fun aDuplicateIsFlaggedAndSkippedAndTheButtonCountsOnlyWhatUploads() {
        val part1 = PickedFile("/x/a.m4a", "sha1", 10, "audio/mp4", "m4a", Instant.parse("2026-11-14T10:42:00Z"), 2_530_000, name = "Meera_part1.m4a")
        val part2 = PickedFile("/x/b.m4a", "sha2", 10, "audio/mp4", "m4a", Instant.parse("2026-11-14T11:31:00Z"), 1_083_000, name = "Meera_part2.m4a")
        var uploaded = 0
        var attach by mutableStateOf(AttachKind.SECTION)
        compose.setContent {
            MaterialTheme {
                val rows = remember { mutableStateListOf(UploadRow(part1), UploadRow(part2, LocalDate.parse("2026-11-14"))) }
                UploadSheet(
                    rows = rows, checking = false, attach = attach, sectionCode = "E", sectionQuestions = 10,
                    questionOptions = listOf(SelectOption("l1", "E1 · Process?")), questionId = null, sessionDate = LocalDate.parse("2026-11-14"),
                    onAttach = { attach = it }, onQuestion = {}, onChoose = {}, onRemove = { rows.remove(it) },
                    onUpload = { uploaded = UploadPlan.uploading(rows).size }, onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("Upload recordings").assertIsDisplayed()
        compose.onNodeWithText("Meera_part1.m4a").assertIsDisplayed()
        compose.onNodeWithText("42:10 · recorded 14 Nov, 4:12 pm").assertIsDisplayed()
        compose.onNodeWithText("Already uploaded on 14 Nov").assertIsDisplayed()
        compose.onNodeWithText("Section E").assertIsDisplayed()
        compose.onNodeWithText("Counts for all 10 questions in E").assertIsDisplayed()
        compose.onNodeWithText("Upload 1 file").assertIsDisplayed()
        compose.onNodeWithTag("questionnaire.upload.submit").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, uploaded)
        compose.onNodeWithText("A question").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(AttachKind.QUESTION, attach)
        compose.onNodeWithTag("questionnaire.upload.submit").assertIsNotEnabled()
        compose.onNodeWithText("Whole session").performScrollTo().performClick()
        compose.onNodeWithTag("questionnaire.upload.submit").assertIsEnabled()
    }
}
