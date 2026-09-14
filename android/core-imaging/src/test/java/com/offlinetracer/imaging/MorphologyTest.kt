package com.offlinetracer.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Binary morphology (ALGORITHMS §9). The algebraic identities — closing is extensive, opening is
 * anti-extensive, and both are idempotent — are the tests, because they hold for every structuring
 * element and every shape, so they catch a decomposition bug that a single hand-checked example
 * would not.
 */
class MorphologyTest {

    private fun rect(w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Mask {
        val m = Mask(w, h)
        for (y in y0..y1) for (x in x0..x1) m[x, y] = true
        return m
    }

    private fun disc(w: Int, h: Int, cx: Int, cy: Int, r: Int): Mask {
        val m = Mask(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r * r) m[x, y] = true
        }
        return m
    }

    private fun assertSame(a: Mask, b: Mask, message: String) {
        assertEquals(a.width, b.width, message)
        assertEquals(a.height, b.height, message)
        for (i in a.data.indices) {
            if (a.data[i] != b.data[i]) {
                val x = i % a.width
                val y = i / a.width
                throw AssertionError("$message: differs at ($x, $y)")
            }
        }
    }

    private fun assertContains(outer: Mask, inner: Mask, message: String) {
        for (i in inner.data.indices) {
            if (inner.data[i] && !outer.data[i]) {
                val x = i % inner.width
                val y = i / inner.width
                throw AssertionError("$message: ($x, $y) was dropped")
            }
        }
    }

    @Test
    fun dilateGrowsAndErodeShrinksByTheRadius() {
        val m = rect(21, 21, 8, 8, 12, 12)
        val d = Morphology.dilate(m, 2, SeShape.RECT)
        assertTrue(d[6, 6] && d[14, 14], "a rect SE of radius 2 must reach the diagonal corner")
        assertFalse(d[5, 8])
        val e = Morphology.erode(m, 1, SeShape.RECT)
        assertTrue(e[10, 10])
        assertFalse(e[8, 8])
        assertEquals(9, e.countTrue(), "a 5x5 square eroded by radius 1 leaves 3x3")
    }

    @Test
    fun anEllipseStructuringElementDoesNotReachTheDiagonalCorner() {
        val m = Mask(15, 15)
        m[7, 7] = true
        val d = Morphology.dilate(m, 2, SeShape.ELLIPSE)
        assertTrue(d[9, 7] && d[7, 9], "the ellipse must reach along the axes")
        assertFalse(d[9, 9], "an ellipse of radius 2 must not include the (2,2) corner")
    }

    @Test
    fun aCrossStructuringElementOnlyReachesAlongTheAxes() {
        val m = Mask(11, 11)
        m[5, 5] = true
        val d = Morphology.dilate(m, 2, SeShape.CROSS)
        assertTrue(d[3, 5] && d[7, 5] && d[5, 3] && d[5, 7])
        assertFalse(d[6, 6])
        assertEquals(9, d.countTrue())
    }

    @Test
    fun radiusZeroIsIdentity() {
        val m = disc(15, 15, 7, 7, 4)
        assertSame(m, Morphology.dilate(m, 0), "dilate(0)")
        assertSame(m, Morphology.erode(m, 0), "erode(0)")
    }

    @Test
    fun closingIsExtensiveAndOpeningIsAntiExtensive() {
        val m = disc(25, 25, 12, 12, 6)
        for (shape in listOf(SeShape.RECT, SeShape.CROSS, SeShape.ELLIPSE)) {
            assertContains(Morphology.close(m, 2, shape), m, "close must contain the input ($shape)")
            assertContains(m, Morphology.open(m, 2, shape), "open must be contained by the input ($shape)")
        }
    }

    @Test
    fun closingAConvexShapeChangesNothing() {
        // A convex set is already closed with respect to any structuring element, so this is exactly
        // the idempotence check that catches a dilate/erode pair with mismatched offsets.
        val m = rect(25, 25, 8, 6, 16, 18)
        for (shape in listOf(SeShape.RECT, SeShape.ELLIPSE)) {
            assertSame(m, Morphology.close(m, 2, shape), "close($shape) must be a no-op on a rectangle")
        }
    }

    @Test
    fun closingIsIdempotent() {
        val m = Mask(25, 25)
        for (y in 8..16) for (x in 8..16) if (!(x in 11..13 && y == 12)) m[x, y] = true
        val once = Morphology.close(m, 2, SeShape.RECT)
        val twice = Morphology.close(once, 2, SeShape.RECT)
        assertSame(once, twice, "closing twice must equal closing once")
    }

    @Test
    fun openingIsIdempotent() {
        val m = disc(25, 25, 12, 12, 7)
        m[3, 3] = true
        val once = Morphology.open(m, 2, SeShape.ELLIPSE)
        val twice = Morphology.open(once, 2, SeShape.ELLIPSE)
        assertSame(once, twice, "opening twice must equal opening once")
        assertFalse(once[3, 3], "opening must remove an isolated speck")
    }

    @Test
    fun closingBridgesAGapThatOpeningWouldNotReopen() {
        val m = Mask(21, 9)
        for (x in 2..8) m[x, 4] = true
        for (x in 11..18) m[x, 4] = true
        val closed = Morphology.close(m, 2, SeShape.RECT)
        for (x in 9..10) assertTrue(closed[x, 4], "the 2 px gap at x=$x must close")
    }

    @Test
    fun gradientIsTheOutlineOfTheShape() {
        val m = rect(21, 21, 8, 8, 12, 12)
        val g = Morphology.gradient(m, 1, SeShape.RECT)
        assertFalse(g[10, 10], "the interior must not survive dilate minus erode")
        assertTrue(g[7, 10], "the outer ring must survive")
        assertTrue(g[8, 8], "the original border must survive")
    }

    @Test
    fun grayDilateAndErodeAreLocalMaxAndMin() {
        val src = GrayF(9, 9).fill(0.2f)
        src[4, 4] = 0.9f
        val d = Morphology.dilateGray(src, 1)
        assertEquals(0.9f, d[3, 4], 1e-5f)
        assertEquals(0.9f, d[5, 4], 1e-5f)
        assertEquals(0.9f, d[4, 5], 1e-5f)
        assertEquals(0.2f, d[2, 4], 1e-5f)

        val hole = GrayF(9, 9).fill(0.8f)
        hole[4, 4] = 0.1f
        val e = Morphology.erodeGray(hole, 1)
        assertEquals(0.1f, e[3, 4], 1e-5f)
        assertEquals(0.8f, e[2, 4], 1e-5f)
    }

    @Test
    fun outOfBoundsReadsAsBackgroundForBinaryMorphology() {
        // Border policy (ALGORITHMS §0): replicating the edge here would make a shape touching the
        // frame survive erosion forever and grow a skeleton along the border of every photograph.
        val m = Mask(9, 9).fill(true)
        val e = Morphology.erode(m, 1, SeShape.RECT)
        assertFalse(e[0, 0])
        assertFalse(e[4, 0])
        assertTrue(e[4, 4])
    }

    @Test
    fun everyOperatorSurvivesAOnePixelMask() {
        val one = Mask(1, 1).fill(true)
        assertTrue(Morphology.dilate(one, 3)[0, 0])
        assertFalse(Morphology.erode(one, 3)[0, 0])
        Morphology.open(one, 2)
        Morphology.close(one, 2)
        Morphology.gradient(one, 2)
        Morphology.dilateGray(GrayF(1, 1, floatArrayOf(0.5f)), 4)
        Morphology.erodeGray(GrayF(1, 1, floatArrayOf(0.5f)), 4)
    }

    @Test
    fun everyOperatorSurvivesAnEmptyMask() {
        val empty = Mask(12, 12)
        assertEquals(0, Morphology.dilate(empty, 3).countTrue())
        assertEquals(0, Morphology.erode(empty, 3).countTrue())
        assertEquals(0, Morphology.open(empty, 3).countTrue())
        assertEquals(0, Morphology.close(empty, 3).countTrue())
        assertEquals(0, Morphology.gradient(empty, 3).countTrue())
    }
}
