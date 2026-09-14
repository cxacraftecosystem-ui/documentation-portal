package com.offlinetracer.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Connected components and blob cleanup (ALGORITHMS §9). */
class ComponentsTest {

    private fun fillRect(m: Mask, x0: Int, y0: Int, x1: Int, y1: Int) {
        for (y in y0..y1) for (x in x0..x1) m[x, y] = true
    }

    /** Three blobs of 4, 9 and 1 pixels, none of them touching. */
    private fun threeBlobs(): Mask {
        val m = Mask(20, 20)
        fillRect(m, 1, 1, 2, 2)
        fillRect(m, 6, 6, 8, 8)
        m[15, 15] = true
        return m
    }

    @Test
    fun labellingFindsEveryBlobAndItsArea() {
        val labels = Components.label(threeBlobs(), 8)
        assertEquals(3, labels.count)
        val areas = IntArray(labels.count) { labels.areaOf(it + 1) }
        areas.sort()
        assertEquals(1, areas[0])
        assertEquals(4, areas[1])
        assertEquals(9, areas[2])
    }

    @Test
    fun everyForegroundPixelGetsANonZeroLabelAndBackgroundGetsZero() {
        val m = threeBlobs()
        val labels = Components.label(m, 8)
        for (y in 0 until 20) for (x in 0 until 20) {
            val l = labels.labels[y * 20 + x]
            if (m[x, y]) {
                assertTrue(l in 1..labels.count, "foreground at ($x, $y) had label $l")
            } else {
                assertEquals(0, l, "background at ($x, $y) had label $l")
            }
        }
    }

    @Test
    fun maskOfReturnsExactlyThatComponent() {
        val m = threeBlobs()
        val labels = Components.label(m, 8)
        var total = 0
        for (l in 1..labels.count) {
            val only = labels.maskOf(l)
            assertEquals(labels.areaOf(l), only.countTrue(), "maskOf($l) must match areaOf($l)")
            total += only.countTrue()
            // Every pixel of the sub-mask must be foreground in the original.
            for (i in only.data.indices) if (only.data[i]) assertTrue(m.data[i])
        }
        assertEquals(m.countTrue(), total)
    }

    @Test
    fun connectivityChangesTheAnswerOnADiagonalChain() {
        val m = Mask(9, 9)
        m[2, 2] = true
        m[3, 3] = true
        m[4, 4] = true
        assertEquals(1, Components.label(m, 8).count, "8-connected sees one diagonal chain")
        assertEquals(3, Components.label(m, 4).count, "4-connected sees three separate pixels")
    }

    @Test
    fun boundsAreTheTightBoxOfEachComponent() {
        val m = Mask(20, 20)
        fillRect(m, 4, 7, 9, 11)
        val labels = Components.label(m, 8)
        assertEquals(1, labels.count)
        // bounds are [x0, y0, x1, y1] per label; label 1 is the first entry after the background.
        val b = labels.bounds
        var found = false
        for (i in 0..b.size - 4 step 4) {
            if (b[i] == 4 && b[i + 1] == 7 && b[i + 2] == 9 && b[i + 3] == 11) found = true
        }
        assertTrue(found, "expected a [4,7,9,11] box somewhere in ${b.toList()}")
    }

    @Test
    fun removeSmallBlobsKeepsExactlyTheBigOnes() {
        val out = Components.removeSmallBlobs(threeBlobs(), 4)
        assertEquals(13, out.countTrue(), "the 4 and 9 pixel blobs must stay, the 1 pixel one must go")
        assertFalse(out[15, 15])
        assertTrue(out[1, 1])
    }

    @Test
    fun removeSmallBlobsWithAThresholdOfOneKeepsEverything() {
        val m = threeBlobs()
        assertEquals(m.countTrue(), Components.removeSmallBlobs(m, 1).countTrue())
    }

    @Test
    fun keepLargestKeepsTheRequestedCount() {
        val one = Components.keepLargest(threeBlobs(), 1)
        assertEquals(9, one.countTrue())
        assertTrue(one[7, 7])
        val two = Components.keepLargest(threeBlobs(), 2)
        assertEquals(13, two.countTrue())
        val all = Components.keepLargest(threeBlobs(), 99)
        assertEquals(14, all.countTrue())
    }

    @Test
    fun removeBorderTouchingDropsOnlyBlobsOnTheFrame() {
        val m = Mask(12, 12)
        fillRect(m, 0, 0, 2, 2)
        fillRect(m, 5, 5, 7, 7)
        val out = Components.removeBorderTouching(m)
        assertEquals(9, out.countTrue())
        assertTrue(out[6, 6])
        assertFalse(out[1, 1])
    }

    @Test
    fun removeIsolatedClearsLonePixelsButNotStrokes() {
        val m = Mask(12, 12)
        for (x in 2..9) m[x, 5] = true
        m[1, 1] = true
        val out = Components.removeIsolated(m, 1, 8)
        assertFalse(out[1, 1], "a pixel with no neighbours must go")
        for (x in 3..8) assertTrue(out[x, 5], "the interior of a stroke must stay")
    }

    @Test
    fun removeIsolatedTerminatesOnAWholeFieldOfLonePixels() {
        // Every pass removes the current endpoints and creates new ones; the pass cap is what stops
        // this from running until the mask is empty (or forever).
        val m = Mask(16, 16)
        for (y in 0 until 16 step 2) for (x in 0 until 16 step 2) m[x, y] = true
        val out = Components.removeIsolated(m, 1, 8)
        assertEquals(0, out.countTrue())
    }

    @Test
    fun fillHolesClosesSmallHolesAndLeavesBigOnes() {
        val m = Mask(24, 24)
        fillRect(m, 2, 2, 20, 20)
        m[5, 5] = false
        for (y in 10..15) for (x in 10..15) m[x, y] = false
        val out = Components.fillHoles(m, 4)
        assertTrue(out[5, 5], "a one pixel hole must be filled")
        assertFalse(out[12, 12], "a 36 pixel hole must survive a cap of 4")
    }

    @Test
    fun fillHolesDoesNotFillTheBackground() {
        val m = Mask(16, 16)
        fillRect(m, 4, 4, 8, 8)
        val out = Components.fillHoles(m, 10000)
        assertFalse(out[0, 0], "the outside is not a hole no matter how large the cap")
        assertEquals(25, out.countTrue())
    }

    @Test
    fun everythingSurvivesAnEmptyMask() {
        val empty = Mask(10, 10)
        val labels = Components.label(empty, 8)
        assertEquals(0, labels.count)
        assertEquals(0, Components.removeSmallBlobs(empty, 5).countTrue())
        assertEquals(0, Components.keepLargest(empty, 3).countTrue())
        assertEquals(0, Components.removeBorderTouching(empty).countTrue())
        assertEquals(0, Components.removeIsolated(empty).countTrue())
        assertEquals(0, Components.fillHoles(empty, 100).countTrue())
    }

    @Test
    fun everythingSurvivesAOnePixelMask() {
        val one = Mask(1, 1).fill(true)
        assertEquals(1, Components.label(one, 8).count)
        assertEquals(1, Components.removeSmallBlobs(one, 1).countTrue())
        assertEquals(1, Components.keepLargest(one, 1).countTrue())
        assertEquals(0, Components.removeBorderTouching(one).countTrue())
        Components.removeIsolated(one)
        Components.fillHoles(one, 4)
    }

    @Test
    fun aFullMaskIsOneComponent() {
        val full = Mask(8, 8).fill(true)
        val labels = Components.label(full, 8)
        assertEquals(1, labels.count)
        assertEquals(64, labels.areaOf(1))
    }
}
