package com.fieldrepository.app.explorer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.explorer.data.ExplorerMore
import com.fieldrepository.app.explorer.data.ExplorerReportOptions
import com.fieldrepository.app.explorer.data.ExplorerReportOptionsBody
import com.fieldrepository.app.explorer.data.ExplorerReportSheetInfo
import com.fieldrepository.app.explorer.data.ExplorerReportSheets
import com.fieldrepository.app.explorer.data.ExplorerShares
import com.fieldrepository.app.explorer.data.ExplorerViews
import com.fieldrepository.app.explorer.ui.ExplorerTab
import com.fieldrepository.app.explorer.ui.ReportOptionsSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class ReportOptionsSheetUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val asked = mutableListOf<String?>()
    private val made = mutableListOf<Pair<ExplorerReportOptions, String?>>()

    private fun sheets(status: String?): ExplorerReportSheets {
        val narrowed = status != null
        return ExplorerReportSheets(
            sheets = listOf(
                ExplorerReportSheetInfo(key = "artisans", name = "Artisans", count = if (narrowed) 0 else 201, record = true),
                ExplorerReportSheetInfo(key = "media", name = "Media", count = if (narrowed) 0 else 955, record = true),
                ExplorerReportSheetInfo(key = "crafts", name = "Crafts", count = 460, record = false),
            ),
            records = if (narrowed) 0 else 1156,
        )
    }

    private val more: ExplorerMore = Proxy.newProxyInstance(ExplorerMore::class.java.classLoader, arrayOf(ExplorerMore::class.java)) { _, method, args ->
        when (method.name) {
            "reportSheets" -> {
                val status = args?.getOrNull(1) as String?
                synchronized(asked) { asked += status }
                sheets(status)
            }
            "reportOptions" -> ExplorerReportOptionsBody(null)
            "shares" -> ExplorerShares()
            "views" -> ExplorerViews()
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "ReportOptionsSheetUiTest.more"
            else -> throw IllegalStateException("${method.name} is not used by the report sheet")
        }
    } as ExplorerMore

    private fun show(status: String?) {
        compose.setContent {
            MaterialTheme {
                ReportOptionsSheet(
                    folderTitle = "Bagru 2026",
                    path = "by-workshop",
                    fileName = "Report.xlsx",
                    sheets = emptyList(),
                    query = "",
                    tab = ExplorerTab.FILES,
                    more = more,
                    onMake = { options, _, chosen -> made += options to chosen },
                    onOpenView = {},
                    onMessage = {},
                    onDismiss = {},
                    status = status,
                )
            }
        }
        compose.waitUntil(5_000) { synchronized(asked) { asked.isNotEmpty() } }
        compose.waitForIdle()
    }

    @QFeature("X6")
    @Test fun withNoFilterTheSheetSaysSoAndOffersNoStatusSwitch() {
        show(null)
        compose.onNodeWithTag("explorer.report.filters").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("explorer.report.filters").assert(androidx.compose.ui.test.hasAnyDescendant(hasText("None. Every record you can see.")))
        compose.onAllNodesWithTag("explorer.report.allStatuses").assertCountEquals(0)
        compose.onAllNodesWithTag("explorer.report.empty").assertCountEquals(0)
    }

    @QFeature("X6")
    @Test fun aStatusThatLeavesNothingShowsTheCountsAndMakesTheUnfilteredReportInOneTap() {
        show("REJECTED")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("explorer.report.empty").fetchSemanticsNodes().isNotEmpty() }
        val card = compose.onNodeWithTag("explorer.report.empty").performScrollTo()
        card.assertIsDisplayed()
        card.assert(androidx.compose.ui.test.hasAnyDescendant(hasText("No records match these filters.")))
        card.assert(androidx.compose.ui.test.hasAnyDescendant(hasText("Artisans 0 · Media 0")))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Without filters: Artisans 201 · Media 955")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explorer.report.filters").assert(androidx.compose.ui.test.hasAnyDescendant(hasText("Review status", substring = true)))
        assertTrue(made.isEmpty())
        compose.onNodeWithTag("explorer.report.unfiltered").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, made.size)
        assertNull(made[0].second)
        assertEquals("all", made[0].first.rows)
    }

    @QFeature("X6")
    @Test fun includeAllStatusesRecountsAndTheReportFollowsTheSwitch() {
        show("REJECTED")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("explorer.report.empty").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explorer.report.allStatuses").performScrollTo().performClick()
        compose.waitUntil(5_000) { synchronized(asked) { asked.contains(null) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("explorer.report.empty").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("explorer.report.filters").assert(androidx.compose.ui.test.hasAnyDescendant(hasText("None. Every record you can see.")))
        compose.onNodeWithTag("explorer.report.make").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, made.size)
        assertNull(made[0].second)
    }
}
