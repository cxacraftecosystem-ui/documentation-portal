package com.offlinetracer.vector

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimplifyTest {

    // -----------------------------------------------------------------------------------------
    // Douglas-Peucker
    // -----------------------------------------------------------------------------------------

    @Test
    fun collinearRunCollapsesToItsEndpoints() {
        val pts = (0..10).map { VecPoint(it.toFloat(), 0f) }
        val out = Simplify.douglasPeucker(pts, 0.5f)
        assertEquals(2, out.size, "a straight line needs two points, got ${out.size}")
        assertEquals(pts[0], out[0])
        assertEquals(pts[10], out[1])

        // Collinear but not axis aligned, and unevenly spaced: the perpendicular-distance test must
        // not depend on either.
        val diagonal = listOf(
            VecPoint(0f, 0f), VecPoint(1f, 2f), VecPoint(1.5f, 3f),
            VecPoint(7f, 14f), VecPoint(10f, 20f),
        )
        assertEquals(2, Simplify.douglasPeucker(diagonal, 0.25f).size)
    }

    @Test
    fun endpointsAreAlwaysKeptAndZeroEpsilonKeepsEverything() {
        val pts = (0..20).map { VecPoint(it.toFloat(), (it % 4).toFloat()) }
        assertEquals(pts, Simplify.douglasPeucker(pts, 0f), "epsilon 0 must be the identity")
        assertEquals(pts, Simplify.douglasPeucker(pts, -3f), "a negative epsilon must be the identity")

        for (epsilon in listOf(0.05f, 0.5f, 1f, 5f, 1000f)) {
            val out = Simplify.douglasPeucker(pts, epsilon)
            assertTrue(out.size >= 2, "epsilon $epsilon returned ${out.size} points")
            assertEquals(pts[0], out[0], "epsilon $epsilon moved the first point")
            assertEquals(pts[pts.size - 1], out[out.size - 1], "epsilon $epsilon moved the last point")
            assertTrue(out.size <= pts.size)
        }
        // Beyond the whole extent of the path nothing can survive but the two ends.
        assertEquals(2, Simplify.douglasPeucker(pts, 1000f).size)
    }

    @Test
    fun deviationIsComparedAgainstEpsilonExactly() {
        // The middle point sits exactly `d` from the chord, so epsilon straddles it.
        fun spike(d: Float) = listOf(VecPoint(0f, 0f), VecPoint(5f, d), VecPoint(10f, 0f))

        assertEquals(3, Simplify.douglasPeucker(spike(2f), 1f).size, "a 2 px deviation must survive 1 px")
        assertEquals(2, Simplify.douglasPeucker(spike(0.5f), 1f).size, "a 0.5 px deviation must be dropped")
        // Larger epsilon on the same shape can only remove points, never add them.
        val tight = Simplify.douglasPeucker(spike(2f), 0.1f).size
        val loose = Simplify.douglasPeucker(spike(2f), 5f).size
        assertTrue(tight >= loose)
    }

    @Test
    fun everySurvivingPointIsWithinEpsilonOfTheOriginal() {
        // Gentle slopes on purpose: the deviation test measures distance to the infinite line
        // through a span's ends, so a near-vertical chord is the one case where "within epsilon of
        // the line" and "within epsilon of the retained segment" could differ.
        val pts = ArrayList<VecPoint>(400)
        for (i in 0 until 400) {
            val t = i / 399.0
            pts.add(VecPoint((t * 200.0).toFloat(), (10.0 * sin(t * 7.0)).toFloat()))
        }
        val epsilon = 1.5f
        val out = Simplify.douglasPeucker(pts, epsilon)
        assertTrue(out.size < pts.size / 4, "simplification barely reduced the path: ${out.size}")
        for (p in pts) {
            assertTrue(
                distanceToPolyline(out, p) <= epsilon + 1e-3f,
                "(${p.x}, ${p.y}) is further than $epsilon from the simplified polyline",
            )
        }
    }

    @Test
    fun twoHundredThousandPointsDoNotOverflowTheStack() {
        // Ordinary input off a noisy photograph. The recursive textbook form dies here, and on
        // Android the StackOverflowError surfaces as an apparently random OOM.
        val n = 200_000
        val pts = ArrayList<VecPoint>(n)
        for (i in 0 until n) {
            val a = i * PI / (n - 1)
            pts.add(VecPoint((5000.0 * cos(a)).toFloat(), (5000.0 * sin(a)).toFloat()))
        }
        val out = Simplify.douglasPeucker(pts, 2f)
        assertTrue(out.size >= 2)
        assertTrue(out.size < 1000, "a smooth arc should not need ${out.size} points at 2 px")
        assertEquals(pts[0], out[0])
        assertEquals(pts[n - 1], out[out.size - 1])
    }

    @Test
    fun worstCaseSplitDepthDoesNotOverflowTheStack() {
        // A constant-amplitude sawtooth is the pathological case: the point of maximum deviation is
        // always the one next to the span's start, so the split depth is O(n) rather than O(log n)
        // and the explicit stack has to grow. Kept at 5 000 points because that same property makes
        // the pass quadratic in time.
        val n = 5000
        val pts = ArrayList<VecPoint>(n)
        for (i in 0 until n) pts.add(VecPoint(i.toFloat(), if (i % 2 == 0) 0f else 2f))
        val out = Simplify.douglasPeucker(pts, 0.5f)
        assertEquals(n, out.size, "every sawtooth vertex deviates by 2 px and must survive")
    }

    // -----------------------------------------------------------------------------------------
    // removeCollinear
    // -----------------------------------------------------------------------------------------

    @Test
    fun removeCollinearDropsOnlyTheNearCollinearPoints() {
        val pts = listOf(
            VecPoint(0f, 0f), VecPoint(1f, 0f), VecPoint(2f, 0f),
            VecPoint(3f, 1f),
            VecPoint(4f, 0f), VecPoint(5f, 0f), VecPoint(6f, 0f),
        )
        val out = Simplify.removeCollinear(pts, 0.05f)
        assertTrue(out.size < pts.size, "nothing was removed from a run of collinear points")
        assertTrue(out.contains(VecPoint(3f, 1f)), "the spike was removed")
        assertEquals(pts[0], out[0])
        assertEquals(pts[pts.size - 1], out[out.size - 1])
        // (1,0) and (5,0) lie on the line through their kept neighbours; (2,0) and (4,0) are the
        // spike's own neighbours and cannot be, so they stay.
        assertTrue(!out.contains(VecPoint(1f, 0f)))
        assertTrue(!out.contains(VecPoint(5f, 0f)))

        assertEquals(2, Simplify.removeCollinear((0..4).map { VecPoint(it.toFloat(), 0f) }, 0.05f).size)
    }

    @Test
    fun removeCollinearKeepsAZigzagIntact() {
        val zigzag = listOf(
            VecPoint(0f, 0f), VecPoint(1f, 1f), VecPoint(2f, 0f),
            VecPoint(3f, 1f), VecPoint(4f, 0f),
        )
        assertEquals(zigzag, Simplify.removeCollinear(zigzag, 0.05f))
        assertEquals(zigzag, Simplify.removeCollinear(zigzag, 0f), "tolerance 0 must be the identity")
    }

    // -----------------------------------------------------------------------------------------
    // Corners
    // -----------------------------------------------------------------------------------------

    @Test
    fun squareOutlineHasExactlyFourCorners() {
        val square = squareOutline(20)
        val closed = Simplify.detectCorners(square, 100f, 3, true)
        assertEquals(listOf(0, 20, 40, 60), closed.toList(), "expected one corner per square corner")

        // Open, the two ends already bound the path, so index 0 is not reported as a corner.
        val open = Simplify.detectCorners(square, 100f, 3, false)
        assertEquals(listOf(20, 40, 60), open.toList())
    }

    @Test
    fun circleHasNoCorners() {
        val n = 180
        val circle = ArrayList<VecPoint>(n)
        for (i in 0 until n) {
            val a = i * 2.0 * PI / n
            circle.add(VecPoint((30.0 * cos(a)).toFloat(), (30.0 * sin(a)).toFloat()))
        }
        assertEquals(0, Simplify.detectCorners(circle, 100f, 3, true).size)
        assertEquals(0, Simplify.detectCorners(circle, 100f, 3, false).size)
        // Loosening the threshold past the circle's own turn does start reporting corners, which is
        // what makes the default a meaningful setting rather than an always-empty one.
        assertTrue(Simplify.detectCorners(circle, 179f, 3, true).isNotEmpty())
    }

    @Test
    fun cornersAreAscendingAndNonMaximumSuppressed() {
        // A right angle in the middle of an open path, sampled a pixel at a time. One physical
        // corner must produce one index, not the five its window spans.
        val pts = ArrayList<VecPoint>(21)
        for (x in 0..10) pts.add(VecPoint(x.toFloat(), 0f))
        for (y in 1..10) pts.add(VecPoint(10f, y.toFloat()))
        val corners = Simplify.detectCorners(pts, 100f, 3, false)
        assertEquals(1, corners.size, "one corner produced ${corners.size} indices")
        assertEquals(10, corners[0])
        for (i in 1 until corners.size) assertTrue(corners[i] > corners[i - 1])
    }

    // -----------------------------------------------------------------------------------------
    // splitAtCorners
    // -----------------------------------------------------------------------------------------

    @Test
    fun splitAtCornersPartitionsWithoutLosingPoints() {
        val square = squareOutline(20)
        val corners = Simplify.detectCorners(square, 100f, 3, false)
        val runs = Simplify.splitAtCorners(square, corners)
        assertEquals(corners.size + 1, runs.size)

        for (run in runs) assertTrue(run.size >= 2, "a run of ${run.size} points is unusable")
        // Consecutive runs share the corner exactly: that shared anchor is what makes the two
        // independently fitted Beziers meet instead of leaving a gap the width of the fit error.
        for (i in 0 until runs.size - 1) {
            assertEquals(runs[i][runs[i].size - 1], runs[i + 1][0], "run $i does not meet run ${i + 1}")
        }
        assertEquals(square, rejoin(runs), "the runs do not rejoin to the input")
    }

    @Test
    fun splitAtCornersIgnoresBoundaryAndDuplicateIndices() {
        val pts = (0..9).map { VecPoint(it.toFloat(), 0f) }
        assertEquals(listOf(pts), Simplify.splitAtCorners(pts, IntArray(0)))
        // 0 and n-1 are already run boundaries, and an out-of-range index is not a corner at all.
        assertEquals(listOf(pts), Simplify.splitAtCorners(pts, intArrayOf(0, 9, -4, 40)))

        val duplicated = Simplify.splitAtCorners(pts, intArrayOf(3, 3, 3))
        assertEquals(2, duplicated.size)
        assertEquals(pts, rejoin(duplicated))

        // Unsorted input must partition the same way as sorted input.
        val unsorted = Simplify.splitAtCorners(pts, intArrayOf(6, 3))
        assertEquals(3, unsorted.size)
        assertEquals(pts, rejoin(unsorted))
    }

    // -----------------------------------------------------------------------------------------
    // resample
    // -----------------------------------------------------------------------------------------

    @Test
    fun resampleSpacesPointsEvenlyAlongALine() {
        val line = listOf(VecPoint(0f, 0f), VecPoint(100f, 0f))
        val out = Simplify.resample(line, 5f)
        assertEquals(21, out.size)
        for (i in 1 until out.size) assertEquals(5f, distance(out[i - 1], out[i]), 1e-3f)
        assertEquals(line[0], out[0])
        assertEquals(100f, out[out.size - 1].x, 1e-3f)
    }

    @Test
    fun resamplePreservesEndpointsWhenTheyDoNotLandOnAStep() {
        val line = listOf(VecPoint(0f, 0f), VecPoint(7f, 0f))
        val out = Simplify.resample(line, 5f)
        assertEquals(3, out.size)
        assertEquals(0f, out[0].x, 1e-4f)
        assertEquals(5f, out[1].x, 1e-4f)
        assertEquals(7f, out[2].x, 1e-4f, "the final point must be kept even on a short remainder")
    }

    @Test
    fun resampleFollowsArcLengthAroundACorner() {
        val path = ArrayList<VecPoint>(41)
        for (x in 0..20) path.add(VecPoint(x.toFloat(), 0f))
        for (y in 1..20) path.add(VecPoint(20f, y.toFloat()))

        val spacing = 3f
        val out = Simplify.resample(path, spacing)
        assertEquals(path[0], out[0])
        assertEquals(path[path.size - 1], out[out.size - 1])
        // Samples are placed at exact arc-length multiples, so the straight-line gap between two of
        // them is at most the spacing and only shortens where the path turns.
        for (i in 1 until out.size - 1) {
            val d = distance(out[i - 1], out[i])
            assertTrue(d <= spacing + 1e-3f, "gap $d exceeds the requested spacing")
            assertTrue(d > spacing * 0.7f, "gap $d is far below the requested spacing")
        }
    }

    @Test
    fun resampleCoercesANonPositiveSpacing() {
        // Zero spacing would otherwise make the output unbounded.
        val out = Simplify.resample(listOf(VecPoint(0f, 0f), VecPoint(1f, 0f)), 0f)
        assertTrue(out.size in 2..200, "a zero spacing produced ${out.size} points")
        for (p in out) assertTrue(p.x.isFinite() && p.y.isFinite())
        assertEquals(1f, out[out.size - 1].x, 1e-3f)
    }

    // -----------------------------------------------------------------------------------------
    // Degenerate input
    // -----------------------------------------------------------------------------------------

    @Test
    fun emptyAndTinyInputsReturnSensibleResults() {
        val empty = emptyList<VecPoint>()
        val one = listOf(VecPoint(2f, 3f))
        val two = listOf(VecPoint(2f, 3f), VecPoint(9f, 3f))

        for (input in listOf(empty, one, two)) {
            assertEquals(input, Simplify.douglasPeucker(input, 1f))
            assertEquals(input, Simplify.removeCollinear(input, 0.05f))
            assertEquals(0, Simplify.detectCorners(input, 100f, 3, false).size)
            assertEquals(0, Simplify.detectCorners(input, 100f, 3, true).size)
        }

        assertEquals(emptyList<List<VecPoint>>(), Simplify.splitAtCorners(empty, intArrayOf(1)))
        assertEquals(listOf(one), Simplify.splitAtCorners(one, intArrayOf(0, 1)))
        assertEquals(listOf(two), Simplify.splitAtCorners(two, intArrayOf(1)))

        assertEquals(empty, Simplify.resample(empty, 2f))
        assertEquals(one, Simplify.resample(one, 2f))
        // Coincident points have no direction to step along; one point out is the honest answer.
        val coincident = Simplify.resample(listOf(VecPoint(4f, 4f), VecPoint(4f, 4f)), 2f)
        assertEquals(1, coincident.size)
        assertEquals(VecPoint(4f, 4f), coincident[0])

        // A window wider than the path, and a degenerate one, must not read out of range.
        val square = squareOutline(4)
        Simplify.detectCorners(square, 100f, 40, true)
        Simplify.detectCorners(square, 100f, 0, true)
        Simplify.detectCorners(square, 100f, -5, false)
    }

    @Test
    fun duplicatedPointsAreNotReportedAsCorners() {
        // Zero-length chords give 0/0; a NaN let through here poisons every later comparison.
        val pts = listOf(
            VecPoint(0f, 0f), VecPoint(0f, 0f), VecPoint(0f, 0f),
            VecPoint(0f, 0f), VecPoint(0f, 0f), VecPoint(0f, 0f), VecPoint(0f, 0f),
        )
        assertEquals(0, Simplify.detectCorners(pts, 100f, 3, false).size)
        assertEquals(0, Simplify.detectCorners(pts, 100f, 3, true).size)
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * A square outline sampled one unit at a time, starting at `(0, 0)` and running clockwise, so
     * the four corners land on indices `0`, `side`, `2·side` and `3·side`.
     */
    private fun squareOutline(side: Int): List<VecPoint> {
        val pts = ArrayList<VecPoint>(4 * side)
        for (i in 0 until side) pts.add(VecPoint(i.toFloat(), 0f))
        for (i in 0 until side) pts.add(VecPoint(side.toFloat(), i.toFloat()))
        for (i in 0 until side) pts.add(VecPoint((side - i).toFloat(), side.toFloat()))
        for (i in 0 until side) pts.add(VecPoint(0f, (side - i).toFloat()))
        return pts
    }

    /** Concatenates runs, dropping each run's shared leading point. */
    private fun rejoin(runs: List<List<VecPoint>>): List<VecPoint> {
        val out = ArrayList<VecPoint>()
        for (r in runs.indices) {
            val run = runs[r]
            for (i in run.indices) if (r == 0 || i > 0) out.add(run[i])
        }
        return out
    }

    private fun distance(a: VecPoint, b: VecPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun distanceToPolyline(poly: List<VecPoint>, p: VecPoint): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until poly.size - 1) {
            val d = pointSegmentDistance(p, poly[i], poly[i + 1])
            if (d < best) best = d
        }
        return best
    }

    private fun pointSegmentDistance(p: VecPoint, a: VecPoint, b: VecPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        var t = if (len2 < 1e-20f) 0f else ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
        if (t < 0f) t = 0f
        if (t > 1f) t = 1f
        val qx = a.x + dx * t - p.x
        val qy = a.y + dy * t - p.y
        return sqrt(qx * qx + qy * qy)
    }
}
