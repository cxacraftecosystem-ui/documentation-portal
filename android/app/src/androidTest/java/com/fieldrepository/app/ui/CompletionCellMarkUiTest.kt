package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.CompletionCellMark
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class CompletionCellMarkUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun cell(status: String?, derived: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(52.dp, 48.dp).testTag("cell"), contentAlignment = Alignment.Center) {
                    CompletionCellMark(status, derived)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertCentred(tag: String) {
        val badge = compose.onNodeWithTag("explorer.mark.badge", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cell = compose.onNodeWithTag("cell").fetchSemanticsNode().boundsInRoot
        assertEquals(badge.center.x, icon.center.x, 0.5f)
        assertEquals(badge.center.y, icon.center.y, 0.5f)
        assertEquals(cell.center.x, badge.center.x, 0.5f)
        assertEquals(cell.center.y, badge.center.y, 0.5f)
        assertEquals(true, abs(icon.width - icon.height) < 0.5f)
    }

    private fun assertNoGlyphText() {
        listOf("✓", "!", "✗").forEach { compose.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(0) }
    }

    @Test
    fun completedDrawsTheCheckIconCentred() {
        cell("COMPLETED")
        assertCentred("explorer.mark.saved")
        assertNoGlyphText()
    }

    @Test
    fun aDerivedCompletionDrawsTheCheckIcon() {
        cell(null, derived = true)
        assertCentred("explorer.mark.saved")
    }

    @Test
    fun requiresReviewDrawsThePriorityIconCentred() {
        cell("NEEDS_REVIEW")
        assertCentred("explorer.mark.failed")
        assertNoGlyphText()
    }

    @Test
    fun needsRedoDrawsTheCloseIconCentred() {
        cell("NEEDS_REDO")
        assertCentred("explorer.mark.redo")
        assertNoGlyphText()
    }

    @Test
    fun notStartedDrawsNoMark() {
        cell(null)
        compose.onAllNodesWithTag("explorer.mark.badge", useUnmergedTree = true).assertCountEquals(0)
        assertNoGlyphText()
    }
}
