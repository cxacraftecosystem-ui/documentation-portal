package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerReportExtras
import com.fieldrepository.app.explorer.data.ExplorerReportOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerReportDefaultsTest {
    private val offered = listOf("charts", "everything", "quotes")

    @Test fun `the default report ticks every sheet and every extra`() {
        val options = ExplorerReportOptions()
        assertNull(options.sheets)
        assertTrue(offered.all { ExplorerReportExtras.on(options.extras, it) })
        assertNull(ExplorerReportExtras.param(options.extras))
        assertFalse(ExplorerMoreApi.reportUrl("by-workshop", options, null, null).contains("extras="))
    }

    @Test fun `an untick is sent, and unticking everything sends none`() {
        val noQuotes = ExplorerReportExtras.with(emptyMap(), offered, "quotes", false)
        assertEquals(mapOf("charts" to true, "everything" to true, "quotes" to false), noQuotes)
        assertEquals("charts,everything", ExplorerReportExtras.param(noQuotes))
        assertTrue(ExplorerMoreApi.reportUrl("by-workshop", ExplorerReportOptions(extras = noQuotes), null, null).contains("extras=charts%2Ceverything"))
        val none = offered.fold(emptyMap<String, Boolean>()) { acc, key -> ExplorerReportExtras.with(acc, offered, key, false) }
        assertEquals(ExplorerReportExtras.NONE, ExplorerReportExtras.param(none))
        assertEquals("charts", ExplorerReportExtras.param(ExplorerReportExtras.with(none, offered, "charts", true)))
    }
}
