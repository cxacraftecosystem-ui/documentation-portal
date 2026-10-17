package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-button rule at phone width: two buttons share one line, and a long label wraps inside its
 * own half instead of pushing the pair into a stack.
 *
 * Widths are in dp at density 1 and estimated the way a Material button measures: 24dp of padding on
 * each side, plus an average glyph width for the label's size. The row is a 360dp phone less a 16dp
 * screen gutter and a 16dp card padding on each side — 296dp, so each half is 144dp. The labels are
 * the longest that really sit in a pair.
 */
class ActionRowTest {

    private val row = 360 - 16 * 2 - 16 * 2
    private val gap = 8

    /** Natural (one-line) width of a button with [label]. */
    private fun natural(label: String, glyph: Double = 7.0, icon: Int = 0) =
        48 + icon + (label.length * glyph).toInt()

    /** Min-intrinsic width: the longest word, since the label may wrap between words. */
    private fun min(label: String, glyph: Double = 7.0, icon: Int = 0) =
        48 + icon + (label.split(' ').maxOf { it.length } * glyph).toInt()

    private val longestPairs = listOf(
        // FieldComponents.kt / LocationCapture.kt — 14sp labels.
        Triple("Use current GPS", "Pick on map", 7.0),
        // SearchScreen.kt — the filter sheet's footer, 14sp.
        Triple("Clear all filters", "Done", 7.0),
        // TraceCropPanel.kt — 12sp labels.
        Triple("Use whole photo", "Use this frame", 6.0),
    )

    @Test
    fun `the longest real pairs share one line on a 360dp phone`() {
        for ((a, b, glyph) in longestPairs) {
            assertTrue(
                "'$a' + '$b' stack on a 360dp phone; a long label should wrap inside its half",
                pairSharesLine(row, gap, listOf(min(a, glyph), min(b, glyph))),
            )
        }
    }

    @Test
    fun `a label wider than its half wraps rather than stacking the pair`() {
        // One line of "Clear all filters" needs more than half the row — the case that used to stack.
        assertTrue(natural("Clear all filters") > (row - gap) / 2)
        assertTrue(pairSharesLine(row, gap, listOf(min("Clear all filters"), min("Done"))))
    }

    @Test
    fun `a pair splits the row into equal halves on a phone`() {
        val each = pairButtonWidth(row, gap, listOf(natural("Save"), natural("Cancel")), compact = true)
        assertEquals((row - gap) / 2, each)
    }

    @Test
    fun `on a wide window a pair is content-fit but never wider than half`() {
        val wide = 840
        assertEquals(natural("Cancel"), pairButtonWidth(wide, gap, listOf(natural("Save"), natural("Cancel")), compact = false))
        val long = natural("A label long enough to want more than half of the row it sits in, really")
        assertEquals((wide - gap) / 2, pairButtonWidth(wide, gap, listOf(long, natural("Ok")), compact = false))
    }

    @Test
    fun `a pair stacks only when one word cannot fit its half`() {
        // A 200% font scale doubles every glyph: "Supercalifragilistic" alone outgrows a 144dp half.
        assertFalse(pairSharesLine(row, gap, listOf(min("Supercalifragilistic", glyph = 14.0), min("Ok"))))
    }
}
