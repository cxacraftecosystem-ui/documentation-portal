package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerInfoField
import com.fieldrepository.app.explorer.data.ExplorerKeyValues
import com.fieldrepository.app.explorer.ui.ExplorerPaneWidth
import com.fieldrepository.app.explorer.ui.ExplorerTableWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerViewingTest {
    private val details = """
        Bagru block printing workshop
        Workshop id: 6a1f0c2e
        Scheme: প্রধানমন্ত্রী বিশ্বকর্মা যোজনা, হস্তশিল্প উন্নয়ন কর্মসূচির অধীনে কারিগর প্রশিক্ষণ
        Place: Bagru, Rajasthan
        Dates: 2026-01-10 to 2026-01-14
    """.trimIndent()

    @QFeature("X1")
    @Test fun `details text becomes a field and value table under its title`() {
        val body = ExplorerKeyValues.parse(details)
        assertTrue(body.tabular)
        assertEquals("Bagru block printing workshop", body.title)
        assertEquals(listOf("Workshop id", "Scheme", "Place", "Dates"), body.fields.map { it.label })
        assertTrue(body.fields[1].value.startsWith("প্রধানমন্ত্রী"))
        assertTrue(body.paragraphs.isEmpty())
    }

    @QFeature("X1")
    @Test fun `a value with a colon or a continuation line stays in one cell`() {
        val body = ExplorerKeyValues.parse("Recorded: 10:30\nNotes: first line\nsecond line\nSize: 4 MB")
        assertEquals(
            listOf(ExplorerInfoField("Recorded", "10:30"), ExplorerInfoField("Notes", "first line\nsecond line"), ExplorerInfoField("Size", "4 MB")),
            body.fields,
        )
        assertNull(body.title)
    }

    @QFeature("X1")
    @Test fun `answers keep the header as a table and the rest as paragraphs`() {
        val body = ExplorerKeyValues.parse("Sitting 1\nArtisan: Meena\nWorkshop: Bagru\n\n[A1] Who taught you?\n  -> My mother\n\n[A2] Since when?\n  -> 1998")
        assertTrue(body.tabular)
        assertEquals(2, body.fields.size)
        assertEquals(2, body.paragraphs.size)
        assertTrue(body.paragraphs[0].contains("Who taught you?"))
    }

    @QFeature("X1")
    @Test fun `prose, links and times are not mistaken for fields`() {
        assertFalse(ExplorerKeyValues.parse("We met at the dye pit.\nThe colours were indigo and madder.").tabular)
        assertFalse(ExplorerKeyValues.parse("See https://example.org/a\nand https://example.org/b").tabular)
        assertFalse(ExplorerKeyValues.parse("10:30 start\n11:45 lunch").tabular)
        assertNull(ExplorerKeyValues.pair("- item: thing"))
        assertNull(ExplorerKeyValues.pair("A very long sentence that keeps going and going: then a colon"))
        assertEquals(2, ExplorerKeyValues.parse("One paragraph.\n\nAnother one.").paragraphs.size)
    }

    @QFeature("X6")
    @Test fun `table columns stay between 160 and 420 dp`() {
        assertEquals(ExplorerTableWidths.MIN, ExplorerTableWidths.of("Id"))
        assertEquals(ExplorerTableWidths.MAX, ExplorerTableWidths.of("প্রধানমন্ত্রী বিশ্বকর্মা যোজনা, হস্তশিল্প উন্নয়ন কর্মসূচির অধীনে কারিগর প্রশিক্ষণ এবং আরও অনেক কিছু"))
        val widths = ExplorerTableWidths.columns(listOf("Name", "Scheme"), listOf(listOf("Meena", "x".repeat(900)), listOf("Giriraj", "")))
        assertEquals(ExplorerTableWidths.MIN, widths[0])
        assertEquals(ExplorerTableWidths.MAX, widths[1])
        assertTrue(ExplorerTableWidths.columns(listOf("A"), listOf(listOf("short")), scale = 2f).single() <= ExplorerTableWidths.MAX)
    }

    @QFeature("X1")
    @Test fun `the tablet pane always leaves the content its minimum width`() {
        listOf(840f, 1024f, 1200f, 1280f, 1440f).forEach { screen ->
            listOf(0f, 280f, 500f, 5_000f).forEach { asked ->
                val pane = ExplorerPaneWidth.clamp(asked, screen)
                assertTrue("pane $pane at $screen", pane >= ExplorerPaneWidth.MIN)
                assertTrue("content ${ExplorerPaneWidth.content(asked, screen)} at $screen", ExplorerPaneWidth.content(asked, screen) >= ExplorerPaneWidth.CONTENT_MIN)
                assertEquals(screen, pane + ExplorerPaneWidth.DIVIDER + ExplorerPaneWidth.content(asked, screen), 0.01f)
            }
        }
        assertEquals(416f, ExplorerPaneWidth.max(840f))
        assertEquals(720f, ExplorerPaneWidth.max(1200f))
    }
}
