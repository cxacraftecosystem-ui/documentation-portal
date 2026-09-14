package com.offlinetracer.vector

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BezierFitTest {

    // -----------------------------------------------------------------------------------------
    // Accuracy
    // -----------------------------------------------------------------------------------------

    @Test
    fun recoversACurveItsOwnSamplesCameFrom() {
        val p0 = VecPoint(0f, 0f)
        val c1 = VecPoint(20f, 60f)
        val c2 = VecPoint(80f, -40f)
        val p3 = VecPoint(100f, 0f)
        val samples = ArrayList<VecPoint>(61)
        for (i in 0..60) samples.add(cubicAt(p0, c1, c2, p3, i / 60f))

        val segs = BezierFit.fit(samples, 1e-4f)
        assertTrue(segs.isNotEmpty(), "fit produced nothing")
        assertTrue(segs.size <= 16, "a smooth cubic should not need ${segs.size} segments")

        val fitted = densify(p0, segs)
        for (s in samples) {
            assertTrue(
                distanceToPolyline(fitted, s) < 0.01f,
                "sample (${s.x}, ${s.y}) is more than 0.01 from the fitted curve",
            )
        }
    }

    @Test
    fun endpointsAreInterpolatedExactly() {
        val pts = (0..20).map { VecPoint(it * 3f, kotlin.math.sin(it * 0.2f) * 10f) }
        val segs = BezierFit.fit(pts, 0.5f)
        assertTrue(segs.isNotEmpty())
        val end = segs[segs.size - 1].to
        assertEquals(pts[pts.size - 1].x, end.x, 1e-3f)
        assertEquals(pts[pts.size - 1].y, end.y, 1e-3f)
    }

    @Test
    fun straightLineFitsOneSegmentWithControlPointsOnTheLine() {
        val pts = (0..10).map { VecPoint(it.toFloat(), 0f) }
        val segs = BezierFit.fit(pts, 1f)
        assertEquals(1, segs.size, "a straight line must not be split")
        val s = segs[0]
        assertTrue(abs(s.c1.y) < 1e-3f, "c1 left the line: ${s.c1.y}")
        assertTrue(abs(s.c2.y) < 1e-3f, "c2 left the line: ${s.c2.y}")
        // Wu/Barsky spacing: the exact cubic form of a line puts controls at 1/3 and 2/3.
        assertEquals(10f / 3f, s.c1.x, 0.05f)
        assertEquals(20f / 3f, s.c2.x, 0.05f)
        assertEquals(10f, s.to.x, 1e-4f)
    }

    // -----------------------------------------------------------------------------------------
    // Corners
    // -----------------------------------------------------------------------------------------

    @Test
    fun ninetyDegreeCornerSurvivesFitPath() {
        val pts = ArrayList<VecPoint>()
        for (x in 0..10) pts.add(VecPoint(x.toFloat(), 0f))
        for (y in 1..10) pts.add(VecPoint(10f, y.toFloat()))

        val path = BezierFit.fitPath(pts, 1f, false, 100f)
        assertTrue(path.segments.size >= 2, "the corner should split the path into at least two runs")

        // Locate the join whose anchor is the corner, then measure the angle the fit left there.
        var joinIndex = -1
        for (i in path.segments.indices) {
            val to = anchorOf(path.segments[i])
            if (abs(to.x - 10f) < 1e-3f && abs(to.y) < 1e-3f) {
                joinIndex = i
                break
            }
        }
        assertTrue(joinIndex in 0 until path.segments.size - 1, "no anchor sits on the corner")

        val incoming = path.segments[joinIndex] as VecSeg.Cubic
        val outgoing = path.segments[joinIndex + 1] as VecSeg.Cubic
        // Back along the path from the corner, and forward along the path from the corner.
        val bx = incoming.c2.x - incoming.to.x
        val by = incoming.c2.y - incoming.to.y
        val fx = outgoing.c1.x - incoming.to.x
        val fy = outgoing.c1.y - incoming.to.y
        val angle = angleBetween(bx, by, fx, fy)
        assertTrue(angle < 100.0, "the corner was rounded off: interior angle is $angle degrees")
    }

    @Test
    fun smoothCurveIsNotSplitAtEveryPoint() {
        val pts = (0..40).map {
            val t = it / 40f
            VecPoint(t * 100f, kotlin.math.sin(t * 3.14159f) * 20f)
        }
        val path = BezierFit.fitPath(pts, 1.6f, false, 100f)
        assertTrue(path.segments.size <= 6, "a smooth arc became ${path.segments.size} segments")
    }

    @Test
    fun closedInputProducesAClosedPath() {
        val pts = (0 until 32).map {
            val a = it / 32.0 * 2.0 * Math.PI
            VecPoint((50.0 + 20.0 * kotlin.math.cos(a)).toFloat(), (50.0 + 20.0 * kotlin.math.sin(a)).toFloat())
        }
        val path = BezierFit.fitPath(pts, 0.5f, true, 100f)
        assertTrue(path.closed)
        assertTrue(path.segments.isNotEmpty())
        // Every anchor must stay near the circle it came from.
        for (p in path.points()) {
            val r = sqrt((p.x - 50f) * (p.x - 50f) + (p.y - 50f) * (p.y - 50f))
            assertTrue(abs(r - 20f) < 1.5f, "anchor drifted off the circle: r = $r")
        }
    }

    // -----------------------------------------------------------------------------------------
    // Robustness
    // -----------------------------------------------------------------------------------------

    @Test
    fun controlPointsNeverShootOffScreen() {
        // A tight zigzag is the classic trigger for the near-singular normal equations; without the
        // alpha fallback the solve returns control points thousands of units away.
        val pts = listOf(
            VecPoint(0f, 0f), VecPoint(1f, 4f), VecPoint(2f, 0f), VecPoint(3f, 4f),
            VecPoint(4f, 0f), VecPoint(5f, 4f), VecPoint(6f, 0f), VecPoint(6.0001f, 0.0001f),
        )
        val segs = BezierFit.fit(pts, 0.01f)
        for (s in segs) {
            for (p in listOf(s.c1, s.c2, s.to)) {
                assertTrue(p.x.isFinite() && p.y.isFinite(), "non-finite control point")
                assertTrue(abs(p.x) < 200f && abs(p.y) < 200f, "control point escaped: (${p.x}, ${p.y})")
            }
        }
    }

    @Test
    fun degenerateInputsReturnSensibleResults() {
        assertTrue(BezierFit.fit(emptyList(), 1f).isEmpty())
        assertTrue(BezierFit.fit(listOf(VecPoint(1f, 1f)), 1f).isEmpty())
        // Every point identical collapses to nothing rather than dividing by a zero chord.
        assertTrue(BezierFit.fit(List(5) { VecPoint(2f, 2f) }, 1f).isEmpty())

        val two = BezierFit.fit(listOf(VecPoint(0f, 0f), VecPoint(9f, 0f)), 1f)
        assertEquals(1, two.size)
        assertEquals(9f, two[0].to.x, 1e-4f)

        val empty = BezierFit.fitPath(emptyList(), 1f, false)
        assertTrue(empty.segments.isEmpty())
        val single = BezierFit.fitPath(listOf(VecPoint(4f, 4f)), 1f, true)
        assertTrue(single.segments.isEmpty())
        assertEquals(4f, single.start.x, 1e-4f)
    }

    @Test
    fun zeroToleranceTerminates() {
        val pts = (0..50).map { VecPoint(it.toFloat(), (it % 3).toFloat()) }
        val segs = BezierFit.fit(pts, 0f, 6)
        assertTrue(segs.isNotEmpty())
        for (s in segs) assertTrue(s.to.x.isFinite() && s.to.y.isFinite())
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private fun cubicAt(p0: VecPoint, c1: VecPoint, c2: VecPoint, p3: VecPoint, t: Float): VecPoint {
        val u = 1f - t
        val b0 = u * u * u
        val b1 = 3f * t * u * u
        val b2 = 3f * t * t * u
        val b3 = t * t * t
        return VecPoint(
            p0.x * b0 + c1.x * b1 + c2.x * b2 + p3.x * b3,
            p0.y * b0 + c1.y * b1 + c2.y * b2 + p3.y * b3,
        )
    }

    private fun densify(start: VecPoint, segs: List<VecSeg.Cubic>): List<VecPoint> {
        val out = ArrayList<VecPoint>(segs.size * 200 + 1)
        var from = start
        out.add(from)
        for (s in segs) {
            for (i in 1..200) out.add(cubicAt(from, s.c1, s.c2, s.to, i / 200f))
            from = s.to
        }
        return out
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

    private fun anchorOf(seg: VecSeg): VecPoint = when (seg) {
        is VecSeg.Line -> seg.to
        is VecSeg.Cubic -> seg.to
        is VecSeg.Quad -> seg.to
    }

    private fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float): Double {
        val la = sqrt(ax.toDouble() * ax + ay.toDouble() * ay)
        val lb = sqrt(bx.toDouble() * bx + by.toDouble() * by)
        if (la < 1e-9 || lb < 1e-9) return 180.0
        var c = (ax.toDouble() * bx + ay.toDouble() * by) / (la * lb)
        if (c > 1.0) c = 1.0
        if (c < -1.0) c = -1.0
        return acos(c) * 180.0 / Math.PI
    }
}
