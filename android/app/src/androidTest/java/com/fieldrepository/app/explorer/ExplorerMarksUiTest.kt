package com.fieldrepository.app.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.explorer.data.ExplorerRecent
import com.fieldrepository.app.explorer.data.ExplorerRecentStatus
import com.fieldrepository.app.explorer.ui.RecentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ExplorerMarksUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    @Test
    fun savedFailedAndRunningMarksAreVectorsCentredInTheirBadges() {
        compose.setContent {
            MaterialTheme {
                Column {
                    RecentRow(ExplorerRecent("Block-carving_close-up_03.jpg", 3_400_000, ExplorerRecentStatus.SAVED), active = true)
                    RecentRow(ExplorerRecent("Kiln-firing_wide_07.mov", 220, ExplorerRecentStatus.FAILED), active = true)
                    RecentRow(ExplorerRecent("Interview_Take-1.mp4", 701, ExplorerRecentStatus.RUNNING, 0.4f), active = true)
                }
            }
        }
        compose.waitForIdle()
        val badges = compose.onAllNodesWithTag("explorer.mark.badge", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(3, badges.size)
        val marks = listOf("saved", "failed", "running").map { compose.onNodeWithTag("explorer.mark.$it", useUnmergedTree = true).bounds() }
        val density = compose.density.density
        marks.zip(badges).forEach { (mark, badge) ->
            assertTrue("mark $mark is off centre in $badge", abs(mark.center.x - badge.center.x) <= density * 0.5f)
            assertTrue("mark $mark is off centre in $badge", abs(mark.center.y - badge.center.y) <= density * 0.5f)
            assertEquals(14f * density, mark.width, 1f)
            assertEquals(14f * density, mark.height, 1f)
        }
        val rows = listOf("saved", "failed").map { compose.onNodeWithTag("explorer.recent.$it", useUnmergedTree = true).bounds() }
        assertEquals(marks[0].center.y - rows[0].top, marks[1].center.y - rows[1].top, 1f)
    }
}
