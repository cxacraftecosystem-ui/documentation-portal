package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerReportSheetInfo
import com.fieldrepository.app.explorer.data.ExplorerReportSheets
import com.fieldrepository.app.explorer.ui.ReportFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFiltersTest {
    private val rejectedOnly = ExplorerReportSheets(
        sheets = listOf(
            ExplorerReportSheetInfo(key = "workshops", name = "Workshops", count = 0, record = true),
            ExplorerReportSheetInfo(key = "crafts", name = "Crafts", count = 460, record = false),
            ExplorerReportSheetInfo(key = "media", name = "Media", count = 0, record = true),
            ExplorerReportSheetInfo(key = "transcripts", name = "Transcripts", count = null, record = true),
        ),
        records = 0,
    )

    @QFeature("X6")
    @Test fun `the active status and search are named, and switching to all statuses drops the status`() {
        assertEquals(listOf("Review status" to "Rejected", "Search" to "indigo"), ReportFilters.chips("Rejected", false, " indigo "))
        assertEquals(listOf("Search" to "indigo"), ReportFilters.chips("Rejected", true, "indigo"))
        assertEquals(emptyList<Pair<String, String>>(), ReportFilters.chips(null, true, ""))
    }

    @QFeature("X6")
    @Test fun `a status value shows its label`() {
        val options = listOf("ALL" to "All", "REJECTED" to "Rejected")
        assertEquals("Rejected", ReportFilters.statusLabel("REJECTED", options))
        assertEquals("ODD", ReportFilters.statusLabel("ODD", options))
        assertNull(ReportFilters.statusLabel(null, options))
    }

    @QFeature("X6")
    @Test fun `filters that leave no records are caught before any download, with the record counts`() {
        assertTrue(ReportFilters.leavesNothing(rejectedOnly, true))
        assertFalse(ReportFilters.leavesNothing(rejectedOnly, false))
        assertFalse(ReportFilters.leavesNothing(rejectedOnly.copy(records = 4), true))
        assertFalse(ReportFilters.leavesNothing(null, true))
        assertEquals("Workshops 0 · Media 0", ReportFilters.recordLine(rejectedOnly))
    }
}
