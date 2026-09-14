package com.offlinetracer.vector

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Boolean2DTest {

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float): VecPath =
        VecPath(
            VecPoint(x0, y0),
            listOf(
                VecSeg.Line(VecPoint(x1, y0)),
                VecSeg.Line(VecPoint(x1, y1)),
                VecSeg.Line(VecPoint(x0, y1)),
            ),
            closed = true,
        )

    /** Rasterised area of a result set. Robust to how the chainer happened to walk the rings. */
    private fun area(paths: List<VecPath>): Float {
        if (paths.isEmpty()) return 0f
        val g = Raster.fill(paths, 20, 20, FillRule.NONZERO)
        var s = 0f
        for (v in g.data) s += v
        return s
    }

    private fun covered(paths: List<VecPath>, x: Int, y: Int): Boolean {
        val m = Raster.toMask(paths, 20, 20, FillRule.NONZERO)
        return m[x, y]
    }

    private val a = listOf(rect(0f, 0f, 10f, 10f))
    private val b = listOf(rect(5f, 5f, 15f, 15f))

    // -----------------------------------------------------------------------------------------
    // Primitives
    // -----------------------------------------------------------------------------------------

    @Test
    fun polygonAreaIsSignedAndShoelaceExact() {
        val ccw = listOf(VecPoint(0f, 0f), VecPoint(10f, 0f), VecPoint(10f, 10f), VecPoint(0f, 10f))
        assertEquals(100f, Boolean2D.polygonArea(ccw), 1e-3f)
        assertEquals(-100f, Boolean2D.polygonArea(ccw.reversed()), 1e-3f)
        assertEquals(0f, Boolean2D.polygonArea(emptyList()), 1e-6f)
        assertEquals(0f, Boolean2D.polygonArea(listOf(VecPoint(1f, 1f), VecPoint(2f, 2f))), 1e-6f)
    }

    @Test
    fun pointInPolygonHandlesASimpleSquare() {
        val square = listOf(VecPoint(0f, 0f), VecPoint(10f, 0f), VecPoint(10f, 10f), VecPoint(0f, 10f))
        assertTrue(Boolean2D.pointInPolygon(square, 5f, 5f, FillRule.NONZERO))
        assertTrue(Boolean2D.pointInPolygon(square, 5f, 5f, FillRule.EVENODD))
        assertTrue(!Boolean2D.pointInPolygon(square, 15f, 5f, FillRule.NONZERO))
        assertTrue(!Boolean2D.pointInPolygon(square, 5f, -1f, FillRule.EVENODD))
        assertTrue(!Boolean2D.pointInPolygon(emptyList(), 0f, 0f, FillRule.NONZERO))
    }

    @Test
    fun fillRulesDisagreeAtTheCentreOfAPentagram() {
        val pts = ArrayList<VecPoint>(5)
        for (i in 0 until 5) {
            val ang = -Math.PI / 2 + i * 4.0 * Math.PI / 5.0
            pts.add(VecPoint((10.0 * cos(ang)).toFloat(), (10.0 * sin(ang)).toFloat()))
        }
        // The centre is enclosed twice, so it is inside under non-zero and outside under even-odd.
        assertTrue(Boolean2D.pointInPolygon(pts, 0f, 0f, FillRule.NONZERO))
        assertTrue(!Boolean2D.pointInPolygon(pts, 0f, 0f, FillRule.EVENODD))
        // A star point is enclosed once and is inside under both.
        val tip = pts[0]
        val nearTip = VecPoint(tip.x * 0.8f, tip.y * 0.8f)
        assertTrue(Boolean2D.pointInPolygon(pts, nearTip.x, nearTip.y, FillRule.NONZERO))
        assertTrue(Boolean2D.pointInPolygon(pts, nearTip.x, nearTip.y, FillRule.EVENODD))
    }

    // -----------------------------------------------------------------------------------------
    // Operations on two overlapping squares (100 + 100 with a 25 overlap)
    // -----------------------------------------------------------------------------------------

    @Test
    fun union() {
        val r = Boolean2D.apply(a, b, Boolean2D.BoolOp.UNION)
        assertTrue(r.isNotEmpty())
        assertEquals(175f, area(r), 0.6f)
        assertTrue(covered(r, 2, 2))
        assertTrue(covered(r, 7, 7))
        assertTrue(covered(r, 12, 12))
        assertTrue(!covered(r, 18, 2))
    }

    @Test
    fun intersect() {
        val r = Boolean2D.apply(a, b, Boolean2D.BoolOp.INTERSECT)
        assertTrue(r.isNotEmpty())
        assertEquals(25f, area(r), 0.6f)
        assertTrue(covered(r, 7, 7))
        assertTrue(!covered(r, 2, 2))
        assertTrue(!covered(r, 12, 12))
    }

    @Test
    fun difference() {
        val r = Boolean2D.apply(a, b, Boolean2D.BoolOp.DIFFERENCE)
        assertTrue(r.isNotEmpty())
        assertEquals(75f, area(r), 0.6f)
        assertTrue(covered(r, 2, 2))
        assertTrue(!covered(r, 7, 7))
        assertTrue(!covered(r, 12, 12))
    }

    @Test
    fun xor() {
        val r = Boolean2D.apply(a, b, Boolean2D.BoolOp.XOR)
        assertTrue(r.isNotEmpty())
        assertEquals(150f, area(r), 0.6f)
        assertTrue(covered(r, 2, 2))
        assertTrue(covered(r, 12, 12))
        assertTrue(!covered(r, 7, 7), "the overlap must be removed by XOR")
    }

    @Test
    fun differenceIsNotSymmetric() {
        val ab = area(Boolean2D.apply(a, b, Boolean2D.BoolOp.DIFFERENCE))
        val ba = area(Boolean2D.apply(b, a, Boolean2D.BoolOp.DIFFERENCE))
        assertEquals(75f, ab, 0.6f)
        assertEquals(75f, ba, 0.6f)
        assertTrue(!covered(Boolean2D.apply(b, a, Boolean2D.BoolOp.DIFFERENCE), 2, 2))
    }

    // -----------------------------------------------------------------------------------------
    // Short circuits and degenerate input
    // -----------------------------------------------------------------------------------------

    @Test
    fun emptyOperandsTakeTheAlgebraicAnswer() {
        assertTrue(Boolean2D.apply(emptyList(), emptyList(), Boolean2D.BoolOp.UNION).isEmpty())
        assertEquals(a, Boolean2D.apply(a, emptyList(), Boolean2D.BoolOp.UNION))
        assertEquals(a, Boolean2D.apply(a, emptyList(), Boolean2D.BoolOp.DIFFERENCE))
        assertEquals(a, Boolean2D.apply(a, emptyList(), Boolean2D.BoolOp.XOR))
        assertTrue(Boolean2D.apply(a, emptyList(), Boolean2D.BoolOp.INTERSECT).isEmpty())
        assertEquals(b, Boolean2D.apply(emptyList(), b, Boolean2D.BoolOp.UNION))
        assertTrue(Boolean2D.apply(emptyList(), b, Boolean2D.BoolOp.DIFFERENCE).isEmpty())
    }

    @Test
    fun disjointOperandsShortCircuit() {
        val far = listOf(rect(100f, 100f, 110f, 110f))
        assertEquals(2, Boolean2D.apply(a, far, Boolean2D.BoolOp.UNION).size)
        assertTrue(Boolean2D.apply(a, far, Boolean2D.BoolOp.INTERSECT).isEmpty())
        assertEquals(a, Boolean2D.apply(a, far, Boolean2D.BoolOp.DIFFERENCE))
    }

    @Test
    fun degenerateOperandsNeverThrow() {
        val point = listOf(VecPath(VecPoint(1f, 1f), emptyList(), true))
        val twoPoint = listOf(VecPath(VecPoint(1f, 1f), listOf(VecSeg.Line(VecPoint(2f, 2f))), true))
        for (op in Boolean2D.BoolOp.values()) {
            Boolean2D.apply(point, a, op)
            Boolean2D.apply(a, point, op)
            Boolean2D.apply(twoPoint, twoPoint, op)
            Boolean2D.apply(a, a, op)
        }
    }

    @Test
    fun identicalOperandsUnionToThemselves() {
        val r = Boolean2D.apply(a, a, Boolean2D.BoolOp.UNION)
        assertEquals(100f, area(r), 1.5f)
        assertTrue(abs(area(r) - 100f) < 1.5f)
    }
}
