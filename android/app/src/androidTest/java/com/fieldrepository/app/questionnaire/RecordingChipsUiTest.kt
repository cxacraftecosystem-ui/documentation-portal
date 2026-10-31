package com.fieldrepository.app.questionnaire

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QuestionnaireFilters
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import com.fieldrepository.app.questionnaire.ui.FilterBar
import com.fieldrepository.app.questionnaire.ui.RecordingChips
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recording chips on a device (owner, 31 Oct 2026): a list of takes, each with its chips; tapping an
 * artisan's chip filters the list to that artisan's takes, the filter shows as a pill, and removing
 * the pill brings every take back. The filtering is the offline rule ([QuestionnaireFilters.matches])
 * the lists use with no signal.
 */
@RunWith(AndroidJUnit4::class)
class RecordingChipsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val takes = listOf(
        """{"id":"r1","subjects":[{"artisanId":"a1"}],"chips":{"session":{"id":"s1","title":"Interview one"},"questionnaire":{"id":"f1","title":"2nd Craft Toolkit"},"targets":[{"kind":"SECTION","sectionId":"secA","sectionCode":"A","sectionTitle":"Materials"}],"artisans":[{"id":"a1","name":"Giriraj"},{"id":"a2","name":"Meena","role":"PRESENT"}],"sessionDate":"2026-10-22","durationMs":1524000}}""",
        """{"id":"r2","subjects":[{"artisanId":"a3"}],"chips":{"session":{"id":"s2","title":"Interview two"},"questionnaire":{"id":"f1","title":"2nd Craft Toolkit"},"targets":[{"kind":"SECTION","sectionId":"secB","sectionCode":"B","sectionTitle":"Tools"}],"artisans":[{"id":"a3","name":"Ravi"}],"sessionDate":"2026-10-23","durationMs":60000}}"""
    ).map { QuestionnairePortal.json.decodeFromString(QRecording.serializer(), it) }

    @QFeature("E1")
    @QFeature("E2")
    @QFeature("E6")
    @Test fun tappingAnArtisansChipFiltersTheListAndThePillTakesItOff() {
        var latest = QuestionnaireFilters()
        compose.setContent {
            MaterialTheme {
                var filters by remember { mutableStateOf(QuestionnaireFilters()) }
                latest = filters
                Column {
                    FilterBar(filters, { filters = it }, "Search recordings", options = { _, _, _ -> null })
                    takes.filter(filters::matches).forEach { r ->
                        Column(Modifier.testTag("take-row")) {
                            Text(r.chips?.session?.title.orEmpty())
                            RecordingChips(r.chips, filters = filters, onChip = { f, label -> filters = filters.applyChip(f, label) })
                        }
                    }
                }
            }
        }
        // E1: the chips read as the shared vectors say.
        compose.onNodeWithText("A · Materials").assertIsDisplayed()
        compose.onNodeWithText("Meena · present").assertIsDisplayed()
        compose.onNodeWithText("22 Oct 2026").assertIsDisplayed()
        compose.onNodeWithText("25:24").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithTag("take-row").fetchSemanticsNodes().size)

        // E2: tapping Giriraj filters the list to his take.
        compose.onAllNodesWithText("Giriraj").onFirst().performClick()
        compose.waitForIdle()
        assertEquals(listOf("a1"), latest["artisanId"])
        assertEquals(1, compose.onAllNodesWithTag("take-row").fetchSemanticsNodes().size)
        compose.onNodeWithText("Interview one").assertIsDisplayed()
        compose.onNodeWithText("Filters (1)").assertIsDisplayed()

        // The pill removes itself and every take is back.
        compose.onNodeWithTag("filter-pill").performClick()
        compose.waitForIdle()
        assertEquals(2, compose.onAllNodesWithTag("take-row").fetchSemanticsNodes().size)
    }
}
