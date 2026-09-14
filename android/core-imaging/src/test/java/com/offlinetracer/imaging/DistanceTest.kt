package com.offlinetracer.imaging

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exact Euclidean distance transform (ALGORITHMS §9). "Exact" is the whole point: the 3-4 chamfer
 * approximation is up to 8% off on diagonals, which turns into a visible width wobble once stroke
 * widths are sampled from it.
 */
class DistanceTest {

    @Test
    fun distanceToASingleForegroundPixelIsExactlyHypot() {
        val n = 9
        val m = Mask(n, n)
        m[4, 4] = true
        // insideForeground = false measures the distance from the background to the nearest ink.
        val dt = Distance.euclidean(m, insideForeground = false)
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x - 4).toFloat()
            val dy = (y - 4).toFloat()
            assertEquals(sqrt(dx * dx + dy * dy), dt[x, y], 1e-4f, "at ($x, $y)")
        }
    }

    @Test
    fun diagonalsAreNotTheChamferApproximation() {
        val m = Mask(7, 7)
        m[0, 0] = true
        val dt = Distance.euclidean(m, insideForeground = false)
        assertEquals(sqrt(2f), dt[1, 1], 1e-4f)
        assertEquals(sqrt(8f), dt[2, 2], 1e-4f)
        assertEquals(sqrt(45f), dt[6, 3], 1e-4f)
    }

    @Test
    fun distanceInsideTheForegroundIsTheDistanceToTheNearestBackground() {
        // A bar 5 px wide, well away from the border. The centre column is 3 away from the nearest
        // background column on either side.
        val m = Mask(11, 5)
        for (y in 0 until 5) for (x in 3..7) m[x, y] = true
        val dt = Distance.euclidean(m, insideForeground = true)
        assertEquals(0f, dt[2, 2], 1e-4f)
        assertEquals(1f, dt[3, 2], 1e-4f)
        assertEquals(2f, dt[4, 2], 1e-4f)
        assertEquals(3f, dt[5, 2], 1e-4f)
        assertEquals(2f, dt[6, 2], 1e-4f)
        assertEquals(1f, dt[7, 2], 1e-4f)
        assertEquals(0f, dt[8, 2], 1e-4f)
    }

    @Test
    fun theTransformIsSymmetricUnderReflection() {
        val n = 8
        val m = Mask(n, n)
        m[2, 5] = true
        m[6, 1] = true
        val dt = Distance.euclidean(m, insideForeground = false)
        val flipped = Mask(n, n)
        for (y in 0 until n) for (x in 0 until n) flipped[n - 1 - x, y] = m[x, y]
        val dtFlipped = Distance.euclidean(flipped, insideForeground = false)
        for (y in 0 until n) for (x in 0 until n) {
            assertEquals(dt[x, y], dtFlipped[n - 1 - x, y], 1e-4f, "at ($x, $y)")
        }
    }

    @Test
    fun anEmptyMaskDoesNotProduceNaNOrNegativeDistances() {
        val dt = Distance.euclidean(Mask(6, 6), insideForeground = false)
        for (v in dt.data) assertTrue(!v.isNaN() && v >= 0f, "got $v")
    }

    @Test
    fun aFullMaskIsZeroOutsideAndNeverNaNInside() {
        val full = Mask(6, 6).fill(true)
        for (v in Distance.euclidean(full, insideForeground = false).data) {
            assertEquals(0f, v, 1e-5f)
        }
        // There is no background to measure against, so the value is unspecified — but it must not
        // be NaN, because NaN survives every clamp downstream and silently poisons stroke widths.
        for (v in Distance.euclidean(full, insideForeground = true).data) {
            assertTrue(!v.isNaN(), "got $v")
        }
    }

    @Test
    fun strokeWidthIsTwiceTheDistance() {
        val dt = GrayF(3, 1, floatArrayOf(0f, 2.5f, 7f))
        assertEquals(0f, Distance.strokeWidthAt(dt, 0, 0), 1e-6f)
        assertEquals(5f, Distance.strokeWidthAt(dt, 1, 0), 1e-6f)
        assertEquals(14f, Distance.strokeWidthAt(dt, 2, 0), 1e-6f)
    }

    @Test
    fun aOnePixelImageSurvivesBothDirections() {
        val on = Mask(1, 1).fill(true)
        val off = Mask(1, 1)
        for (v in Distance.euclidean(on, true).data) assertTrue(!v.isNaN())
        for (v in Distance.euclidean(on, false).data) assertEquals(0f, v, 1e-5f)
        for (v in Distance.euclidean(off, true).data) assertEquals(0f, v, 1e-5f)
        for (v in Distance.euclidean(off, false).data) assertTrue(!v.isNaN())
    }

    @Test
    fun aTallColumnStillGetsTheRightVerticalDistances() {
        // Exercises the second (column) pass of the separable lower-envelope algorithm on its own.
        val m = Mask(1, 12)
        m[0, 0] = true
        val dt = Distance.euclidean(m, insideForeground = false)
        for (y in 0 until 12) assertEquals(y.toFloat(), dt[0, y], 1e-4f)
    }
}
