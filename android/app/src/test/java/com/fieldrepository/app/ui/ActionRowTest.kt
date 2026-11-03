package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRowTest {

    private val row = 360 - 16 * 2 - 16 * 2
    private val gap = 8

    private fun natural(label: String, glyph: Double = 7.0, icon: Int = 0) =
        48 + icon + (label.length * glyph).toInt()

    private fun min(label: String, glyph: Double = 7.0, icon: Int = 0) =
        48 + icon + (label.split(' ').maxOf { it.length } * glyph).toInt()

    private val longestPairs = listOf(
        Triple("Use current GPS", "Pick on map", 7.0),
        Triple("Clear all filters", "Done", 7.0),
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
        assertFalse(pairSharesLine(row, gap, listOf(min("Supercalifragilistic", glyph = 14.0), min("Ok"))))
    }
}
