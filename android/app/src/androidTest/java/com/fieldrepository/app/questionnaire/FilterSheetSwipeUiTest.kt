package com.fieldrepository.app.questionnaire

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.questionnaire.data.QFilterOption
import com.fieldrepository.app.questionnaire.data.QFilterOptions
import com.fieldrepository.app.questionnaire.data.QuestionnaireFilters
import com.fieldrepository.app.questionnaire.ui.FilterBar
import com.fieldrepository.app.questionnaire.ui.FilterField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FilterSheetSwipeUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val artisans = (1..60).map { QFilterOption(id = "a$it", label = "Artisan $it", count = it) }

    private fun capture(n: Int) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "design-fidelity/AndroidFilterSheet")
        root.mkdirs()
        File(root, "impl-android-$n.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun top(tag: String): Float = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.top

    private fun open(fontScale: Float, fields: List<FilterField>) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    var filters by remember { mutableStateOf(QuestionnaireFilters()) }
                    FilterBar(filters, { filters = it }, "Search forms", options = { _, _, _ -> QFilterOptions(artisans = artisans) }, fields = fields)
                }
            }
        }
        compose.onNodeWithTag("filter-open").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("filter-sheet")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun dragUpHeld(tag: String): Float {
        compose.onNodeWithTag(tag).performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(0f, -height / 12f)) }
        }
        compose.waitForIdle()
        val held = top("filter-sheet-surface")
        compose.onNodeWithTag(tag).performTouchInput { up() }
        compose.waitForIdle()
        return held
    }

    @Test fun swipingUpOnTheFormsFilterSheetKeepsItOpenAndStill() {
        open(1.6f, FilterField.FORMS)
        val start = top("filter-sheet-surface")
        repeat(4) { n ->
            compose.onNodeWithTag("filter-sheet").performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertEquals("the sheet's top after swipe ${n + 1}", start, top("filter-sheet-surface"), 1f)
            assertEquals("the sheet's top mid-drag ${n + 1}", start, dragUpHeld("filter-sheet"), 1f)
            compose.onNodeWithTag("filter-sheet").assertIsDisplayed()
            if (n < 3) capture(n + 1)
        }
    }

    @Test fun swipingUpOnSixtyOptionsScrollsTheListAndLeavesTheSheetOpen() {
        open(1f, FilterField.ALL)
        val start = top("filter-sheet-surface")
        compose.onNodeWithText("Artisans").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("filter-option").fetchSemanticsNodes().isNotEmpty() }
        val search = top("filter-picker-search")
        repeat(5) {
            compose.onNodeWithTag("filter-picker-list").performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertEquals(search, top("filter-picker-search"), 1f)
        }
        assertTrue(compose.onAllNodes(hasText("Artisan 1", substring = false)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("filter-sheet").assertIsDisplayed()
        assertEquals(start, top("filter-sheet-surface"), 1f)
    }
}
