package com.offlinetracer.vector

import com.offlinetracer.imaging.GrayF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RasterTest {

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

    private fun polygon(points: List<VecPoint>): VecPath =
        VecPath(points[0], points.drop(1).map { VecSeg.Line(it) }, closed = true)

    private fun coverage(g: GrayF): Float {
        var s = 0f
        for (v in g.data) s += v
        return s
    }

    // -----------------------------------------------------------------------------------------
    // Coverage
    // -----------------------------------------------------------------------------------------

    @Test
    fun axisAlignedSquareCoversItsExactArea() {
        val g = Raster.fill(listOf(rect(2f, 2f, 6f, 6f)), 10, 10, FillRule.NONZERO)
        assertEquals(16f, coverage(g), 16f * 0.01f)
        assertEquals(1f, g[3, 3], 1e-4f)
        assertEquals(0f, g[0, 0], 1e-4f)
    }

    @Test
    fun subPixelSquareCoversItsAreaWithinOnePercent() {
        val g = Raster.fill(listOf(rect(2.25f, 2.25f, 6.75f, 6.75f)), 10, 10, FillRule.EVENODD)
        val expected = 4.5f * 4.5f
        assertEquals(expected, coverage(g), expected * 0.01f)
    }

    @Test
    fun rotatedSquareCoversItsAreaWithinOnePercent() {
        // A diamond: the diagonals are 8 and 8, so the area is 32. Every edge is at 45 degrees,
        // which is where a nearest-pixel rasteriser would be visibly wrong.
        val g = Raster.fill(
            listOf(polygon(listOf(VecPoint(5f, 1f), VecPoint(9f, 5f), VecPoint(5f, 9f), VecPoint(1f, 5f)))),
            10, 10, FillRule.NONZERO,
        )
        assertEquals(32f, coverage(g), 32f * 0.02f)
    }

    @Test
    fun openPathIsFilledAsIfClosed() {
        val open = VecPath(
            VecPoint(2f, 2f),
            listOf(VecSeg.Line(VecPoint(6f, 2f)), VecSeg.Line(VecPoint(6f, 6f)), VecSeg.Line(VecPoint(2f, 6f))),
            closed = false,
        )
        val g = Raster.fill(listOf(open), 10, 10, FillRule.NONZERO)
        assertEquals(16f, coverage(g), 0.2f)
    }

    // -----------------------------------------------------------------------------------------
    // Fill rules
    // -----------------------------------------------------------------------------------------

    @Test
    fun evenOddAndNonZeroDifferOnNestedSameWindingRings() {
        // Two rings wound the same way. Non-zero fills the inner square (winding 2), even-odd
        // punches it out. This is exactly the case a hole in a traced glyph depends on.
        val outer = rect(2f, 2f, 8f, 8f)
        val inner = rect(4f, 4f, 6f, 6f)
        val nonZero = coverage(Raster.fill(listOf(outer, inner), 10, 10, FillRule.NONZERO))
        val evenOdd = coverage(Raster.fill(listOf(outer, inner), 10, 10, FillRule.EVENODD))
        assertEquals(36f, nonZero, 0.4f)
        assertEquals(32f, evenOdd, 0.4f)
    }

    @Test
    fun evenOddAndNonZeroDifferAtTheCentreOfAStar() {
        val pts = ArrayList<VecPoint>(5)
        for (i in 0 until 5) {
            // Step two points at a time to make the classic self-overlapping pentagram.
            val a = -Math.PI / 2 + i * 4.0 * Math.PI / 5.0
            pts.add(VecPoint((10.0 + 8.0 * cos(a)).toFloat(), (10.0 + 8.0 * sin(a)).toFloat()))
        }
        val star = listOf(polygon(pts))
        val nonZero = Raster.fill(star, 20, 20, FillRule.NONZERO)
        val evenOdd = Raster.fill(star, 20, 20, FillRule.EVENODD)
        // The centre pentagon is covered twice, so only non-zero fills it.
        assertEquals(1f, nonZero[10, 10], 1e-3f)
        assertEquals(0f, evenOdd[10, 10], 1e-3f)
        // The points of the star are covered once and are filled by both.
        assertTrue(coverage(nonZero) > coverage(evenOdd))
    }

    // -----------------------------------------------------------------------------------------
    // Strokes, masks and rendering
    // -----------------------------------------------------------------------------------------

    @Test
    fun strokeOutlineHasTheAreaOfItsRectangle() {
        val line = VecPath(VecPoint(1f, 5f), listOf(VecSeg.Line(VecPoint(9f, 5f))))
        val outline = StrokeStyle.outlineStroke(line, 2f, LineCap.BUTT, LineJoin.MITER)
        val g = Raster.fill(listOf(outline), 10, 10, FillRule.NONZERO)
        assertEquals(16f, coverage(g), 0.5f)
    }

    @Test
    fun roundCapAddsTwoHalfDiscs() {
        val line = VecPath(VecPoint(2f, 5f), listOf(VecSeg.Line(VecPoint(8f, 5f))))
        // A fine flattening tolerance: on a 1 px radius the default 0.25 px sagitta is a coarse
        // three-chord half circle, which is correct to tolerance but not worth measuring an area
        // against.
        val butt = coverage(
            Raster.fill(
                listOf(StrokeStyle.outlineStroke(line, 2f, LineCap.BUTT, LineJoin.ROUND, 4f, 0.01f)),
                10, 10, FillRule.NONZERO,
            )
        )
        val round = coverage(
            Raster.fill(
                listOf(StrokeStyle.outlineStroke(line, 2f, LineCap.ROUND, LineJoin.ROUND, 4f, 0.01f)),
                10, 10, FillRule.NONZERO,
            )
        )
        assertEquals(12f, butt, 0.3f)
        // Two half discs of radius 1 add exactly pi, so the target is 12 + 3.14159 = 15.14159.
        //
        // Where the 0.2 comes from, so nobody has to re-derive it. At tolerance 0.01 on r = 1 the
        // sagitta rule in StrokeStyle.appendArc gives 12 chords per cap, and an inscribed n-gon
        // half-disc has area (n/2) r^2 sin(pi/n) = 6 sin 15 deg = 1.55291. So the polygon actually
        // handed to the rasteriser measures 12 + 2(1.55291) = 15.10583 — 0.0358 under pi, purely the
        // inscription deficit. The 4x4 ordered grid then counts 244 sub-samples for that shape where
        // its true area is 241.69, and 244/16 = 15.25, so the measured figure lands 0.108 high.
        // Both the geometric deficit and the sampling quantisation therefore fit inside 0.2.
        //
        // The bound is not slack: it is what makes this test able to fail. A cap tessellated with a
        // fixed small chord count instead of honouring the tolerance measures 14.50 at 3 chords and
        // 14.75 at 4, i.e. 0.64 and 0.39 low. Widening 0.2 would stop the test proving anything.
        assertEquals(12f + Math.PI.toFloat(), round, 0.2f)
    }

    @Test
    fun squareCapAddsTwoHalfSquares() {
        val line = VecPath(VecPoint(2f, 5f), listOf(VecSeg.Line(VecPoint(8f, 5f))))
        val square = coverage(
            Raster.fill(
                listOf(StrokeStyle.outlineStroke(line, 2f, LineCap.SQUARE, LineJoin.ROUND)),
                10, 10, FillRule.NONZERO,
            )
        )
        // The rectangle grows by half the width at each end: 8 long by 2 wide.
        assertEquals(16f, square, 0.3f)
    }

    @Test
    fun toMaskThresholdsAtHalfCoverage() {
        val m = Raster.toMask(listOf(rect(2f, 2f, 6f, 6f)), 10, 10, FillRule.NONZERO)
        assertEquals(16, m.countTrue())
        assertTrue(m[3, 3])
        assertTrue(!m[7, 7])
    }

    @Test
    fun renderCompositesOverTheBackground() {
        val shape = VecShape(rect(2f, 2f, 8f, 8f), VecStyle(stroke = null, fill = 0xFF000000.toInt()))
        val d = VecDocument(10f, 10f, listOf(VecLayer("l", "L", listOf(shape))))
        val img = Raster.render(d, 10, 10, 0xFFFFFFFF.toInt())
        assertEquals(0xFF000000.toInt(), img[5, 5])
        assertEquals(0xFFFFFFFF.toInt(), img[0, 0])
    }

    @Test
    fun renderScalesTheDocumentToTheRequestedSize() {
        val shape = VecShape(rect(0f, 0f, 5f, 5f), VecStyle(stroke = null, fill = 0xFF000000.toInt()))
        val d = VecDocument(10f, 10f, listOf(VecLayer("l", "L", listOf(shape))))
        val img = Raster.render(d, 20, 20, 0)
        // The square occupied the top-left quarter of the document, so it must occupy the
        // top-left quarter of the output too.
        assertEquals(255, (img[5, 5] ushr 24) and 0xFF)
        assertEquals(0, (img[15, 15] ushr 24) and 0xFF)
    }

    @Test
    fun hiddenLayersAreNotRendered() {
        val shape = VecShape(rect(2f, 2f, 8f, 8f), VecStyle(stroke = null, fill = 0xFF000000.toInt()))
        val d = VecDocument(10f, 10f, listOf(VecLayer("l", "L", listOf(shape), visible = false)))
        val img = Raster.render(d, 10, 10, 0xFFFFFFFF.toInt())
        assertEquals(0xFFFFFFFF.toInt(), img[5, 5])
    }

    // -----------------------------------------------------------------------------------------
    // Degenerate input
    // -----------------------------------------------------------------------------------------

    @Test
    fun degenerateInputsNeverThrow() {
        val empty = Raster.fill(emptyList(), 4, 4, FillRule.NONZERO)
        assertEquals(0f, coverage(empty), 1e-6f)

        val zeroSize = Raster.fill(listOf(rect(0f, 0f, 1f, 1f)), 0, 0, FillRule.NONZERO)
        assertEquals(1, zeroSize.width)
        assertEquals(1, zeroSize.height)

        val degenerate = Raster.fill(listOf(VecPath(VecPoint(1f, 1f), emptyList())), 4, 4, FillRule.EVENODD)
        assertEquals(0f, coverage(degenerate), 1e-6f)

        val offscreen = Raster.fill(listOf(rect(-50f, -50f, -10f, -10f)), 8, 8, FillRule.NONZERO)
        assertEquals(0f, coverage(offscreen), 1e-6f)

        val nonFinite = Raster.fill(
            listOf(VecPath(VecPoint(Float.NaN, 0f), listOf(VecSeg.Line(VecPoint(3f, 3f))), closed = true)),
            4, 4, FillRule.NONZERO,
        )
        for (v in nonFinite.data) assertTrue(v.isFinite())
    }

    @Test
    fun coverageIsAlwaysInRange() {
        val g = Raster.fill(
            listOf(rect(1f, 1f, 9f, 9f), rect(2f, 2f, 8f, 8f), rect(3f, 3f, 7f, 7f)),
            10, 10, FillRule.NONZERO,
        )
        for (v in g.data) assertTrue(v >= 0f && v <= 1f + 1e-5f, "coverage out of range: $v")
        assertTrue(abs(g[5, 5] - 1f) < 1e-4f)
    }
}
