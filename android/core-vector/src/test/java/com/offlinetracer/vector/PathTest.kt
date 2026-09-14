package com.offlinetracer.vector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathTest {

    // -----------------------------------------------------------------------------------------
    // equals / hashCode — the FloatArray trap
    // -----------------------------------------------------------------------------------------

    @Test
    fun equalPathsWithSeparateStrokeWidthArraysAreEqual() {
        // Two traces of the same geometry allocate their own width arrays. With the generated
        // data-class equals these compare by identity, never match, and undo/redo deduplication
        // silently stops collapsing no-op edits — the defect the hand-written equals exists for.
        val a = VecPath(
            VecPoint(0f, 0f),
            listOf(VecSeg.Line(VecPoint(10f, 0f)), VecSeg.Line(VecPoint(10f, 10f))),
            closed = false,
            id = "p0",
            strokeWidths = floatArrayOf(1f, 2f, 3f),
        )
        val b = VecPath(
            VecPoint(0f, 0f),
            listOf(VecSeg.Line(VecPoint(10f, 0f)), VecSeg.Line(VecPoint(10f, 10f))),
            closed = false,
            id = "p0",
            strokeWidths = floatArrayOf(1f, 2f, 3f),
        )
        assertTrue(a.strokeWidths !== b.strokeWidths, "the test needs two distinct arrays")
        assertEquals(a, b)
        assertEquals(b, a)
        assertEquals(a.hashCode(), b.hashCode())
        // The undo stack deduplicates through a hash container, so content equality has to survive
        // hashing and not just a direct comparison.
        assertEquals(1, hashSetOf(a, b).size)
        assertEquals(1, listOf(a, b).distinct().size)
    }

    @Test
    fun differingStrokeWidthContentMakesPathsUnequal() {
        val base = VecPath(VecPoint(0f, 0f), listOf(VecSeg.Line(VecPoint(4f, 0f))))
        val one = base.copy(strokeWidths = floatArrayOf(1f, 2f))
        val two = base.copy(strokeWidths = floatArrayOf(1f, 2.5f))
        val longer = base.copy(strokeWidths = floatArrayOf(1f, 2f, 2f))
        assertTrue(one != two)
        assertTrue(one != longer)
        assertTrue(one != base, "null widths must not equal supplied widths")
        assertTrue(base != one)
        assertEquals(base, VecPath(VecPoint(0f, 0f), listOf(VecSeg.Line(VecPoint(4f, 0f)))))
    }

    @Test
    fun identityDifferencesInOtherFieldsStillMatter() {
        val base = VecPath(VecPoint(0f, 0f), listOf(VecSeg.Line(VecPoint(4f, 0f))))
        assertTrue(base != base.copy(closed = true))
        assertTrue(base != base.copy(id = "x"))
        assertTrue(base != base.copy(start = VecPoint(1f, 0f)))
    }

    // -----------------------------------------------------------------------------------------
    // flatten
    // -----------------------------------------------------------------------------------------

    @Test
    fun straightCubicFlattensToCollinearPoints() {
        // Control points on the chord but not at 1/3 and 2/3, so the flatness test does subdivide
        // and there is something to check the collinearity of.
        val path = VecPath(
            VecPoint(0f, 0f),
            listOf(VecSeg.Cubic(VecPoint(5f, 0f), VecPoint(25f, 0f), VecPoint(30f, 0f))),
        )
        val pts = path.flatten(0.05f)
        assertTrue(pts.size > 2, "a subdividing cubic emitted only ${pts.size} points")
        for (p in pts) assertEquals(0f, p.y, 1e-4f)
        for (i in 1 until pts.size) {
            assertTrue(pts[i].x > pts[i - 1].x, "flatten() emitted points out of parameter order")
        }
        assertEquals(0f, pts[0].x, 1e-4f)
        assertEquals(30f, pts[pts.size - 1].x, 1e-4f)
    }

    @Test
    fun tighterToleranceEmitsMorePoints() {
        val path = VecPath(
            VecPoint(0f, 0f),
            listOf(VecSeg.Cubic(VecPoint(0f, 100f), VecPoint(100f, 100f), VecPoint(100f, 0f))),
        )
        val coarse = path.flatten(2f).size
        val fine = path.flatten(0.02f).size
        assertTrue(fine > coarse, "0.02 px gave $fine points and 2 px gave $coarse")
        // The cap on subdivision depth means even an absurd tolerance terminates.
        assertTrue(path.flatten(0f).isNotEmpty())
    }

    @Test
    fun lineSegmentsAreEmittedVerbatim() {
        val path = VecPath(
            VecPoint(1f, 1f),
            listOf(VecSeg.Line(VecPoint(2f, 1f)), VecSeg.Line(VecPoint(2f, 2f))),
        )
        assertEquals(3, path.flatten().size)
        assertEquals(path.points(), path.flatten())
    }

    @Test
    fun closedPathDoesNotRepeatItsStart() {
        val explicit = VecPath(
            VecPoint(0f, 0f),
            listOf(
                VecSeg.Line(VecPoint(1f, 0f)),
                VecSeg.Line(VecPoint(1f, 1f)),
                VecSeg.Line(VecPoint(0f, 0f)),
            ),
            closed = true,
        )
        val pts = explicit.flatten()
        assertEquals(3, pts.size, "the duplicated start anchor must be dropped")
        assertEquals(VecPoint(1f, 1f), pts[2])
    }

    @Test
    fun degeneratePathFlattensToItsStart() {
        val lone = VecPath(VecPoint(3f, 4f), emptyList())
        assertEquals(listOf(VecPoint(3f, 4f)), lone.flatten())
        assertTrue(lone.isEmpty())
        assertEquals(0f, lone.length(), 1e-6f)
    }

    // -----------------------------------------------------------------------------------------
    // bounds and length
    // -----------------------------------------------------------------------------------------

    @Test
    fun boundsEnclosesTheAnchorsAndUsesAnalyticExtrema() {
        val path = VecPath(
            VecPoint(0f, 0f),
            listOf(VecSeg.Cubic(VecPoint(0f, 100f), VecPoint(100f, 100f), VecPoint(100f, 0f))),
        )
        val b = path.bounds()
        for (p in path.points()) {
            assertTrue(p.x >= b[0] - 1e-4f && p.x <= b[2] + 1e-4f, "anchor x outside bounds")
            assertTrue(p.y >= b[1] - 1e-4f && p.y <= b[3] + 1e-4f, "anchor y outside bounds")
        }
        assertEquals(0f, b[0], 1e-4f)
        assertEquals(0f, b[1], 1e-4f)
        assertEquals(100f, b[2], 1e-4f)
        // B(0.5).y is exactly 3/8 of the control height. The control hull would say 100, which is
        // what a loose box costs: unexplained margin around every SVG export.
        assertEquals(75f, b[3], 1e-3f)
    }

    @Test
    fun quadraticBoundsAlsoUsesItsExtremum() {
        val path = VecPath(VecPoint(0f, 0f), listOf(VecSeg.Quad(VecPoint(0f, 40f), VecPoint(40f, 0f))))
        val b = path.bounds()
        assertEquals(0f, b[0], 1e-4f)
        assertEquals(0f, b[1], 1e-4f)
        assertEquals(40f, b[2], 1e-4f)
        // B(0.5).y = 0.25*0 + 0.5*40 + 0.25*0 = 20.
        assertEquals(20f, b[3], 1e-3f)
    }

    @Test
    fun unitSquareOutlineHasLengthFour() {
        val square = VecPath(
            VecPoint(0f, 0f),
            listOf(
                VecSeg.Line(VecPoint(1f, 0f)),
                VecSeg.Line(VecPoint(1f, 1f)),
                VecSeg.Line(VecPoint(0f, 1f)),
            ),
            closed = true,
        )
        assertEquals(4f, square.length(), 1e-4f)
        // Open, the closing edge is not counted.
        assertEquals(3f, square.copy(closed = false).length(), 1e-4f)
    }

    // -----------------------------------------------------------------------------------------
    // Mat2D
    // -----------------------------------------------------------------------------------------

    @Test
    fun identityIsANoOp() {
        val p = VecPoint(3.5f, -7.25f)
        assertEquals(p, Mat2D.IDENTITY.apply(p))
        assertEquals(1f, Mat2D.IDENTITY.meanScale(), 1e-6f)
        assertEquals(Mat2D.IDENTITY, Mat2D.IDENTITY * Mat2D.IDENTITY)
    }

    @Test
    fun translateRoundTripsThroughItsInverse() {
        val t = Mat2D.translate(3f, -7f)
        val inv = Mat2D.translate(-3f, 7f)
        val p = VecPoint(11f, 13f)
        assertPointEquals(p, inv.apply(t.apply(p)))
        assertPointEquals(p, (t * inv).apply(p))
        assertPointEquals(p, (inv * t).apply(p))
        // A pure translation carries no scale, so stroke widths must be untouched by it.
        assertEquals(1f, t.meanScale(), 1e-6f)
    }

    @Test
    fun fullTurnIsIdentityAndQuarterTurnTurnsXTowardY() {
        val p = VecPoint(5f, 3f)
        assertPointEquals(p, Mat2D.rotate((2.0 * PI).toFloat()).apply(p), 1e-3f)
        // Positive angles take +x toward +y, i.e. clockwise on a y-down screen.
        assertPointEquals(VecPoint(0f, 1f), Mat2D.rotate((PI / 2).toFloat()).apply(VecPoint(1f, 0f)), 1e-5f)
        assertEquals(1f, Mat2D.rotate(0.9f).meanScale(), 1e-5f)
    }

    @Test
    fun rotateAboutLeavesThePivotFixed() {
        val pivot = VecPoint(4f, 9f)
        for (angle in listOf(0.1f, 0.7f, 2.5f, -1.3f)) {
            val m = Mat2D.rotateAbout(angle, pivot.x, pivot.y)
            assertPointEquals(pivot, m.apply(pivot), 1e-3f)
        }
        // A point one unit away stays one unit away.
        val m = Mat2D.rotateAbout((PI / 2).toFloat(), pivot.x, pivot.y)
        val moved = m.apply(VecPoint(pivot.x + 1f, pivot.y))
        assertPointEquals(VecPoint(pivot.x, pivot.y + 1f), moved, 1e-4f)
    }

    @Test
    fun timesAppliesItsRightOperandFirst() {
        val scale = Mat2D.scale(2f, 3f)
        val move = Mat2D.translate(5f, 7f)
        val p = VecPoint(1f, 1f)
        val composed = scale * move
        assertPointEquals(scale.apply(move.apply(p)), composed.apply(p))
        assertPointEquals(VecPoint(12f, 24f), composed.apply(p))
        // A commuting pair would pass under either convention, which is how the order gets
        // inverted unnoticed; scale and translate do not commute, so this pins it down.
        val reversedOrder = (move * scale).apply(p)
        assertTrue(abs(reversedOrder.x - 12f) > 1f, "times() applied its operands in the wrong order")
    }

    @Test
    fun meanScaleIsTheGeometricMeanOfTheAxisScales() {
        assertEquals(2f, Mat2D.scale(2f, 2f).meanScale(), 1e-5f)
        assertEquals(sqrt(8f), Mat2D.scale(2f, 4f).meanScale(), 1e-5f)
        assertEquals(0f, Mat2D.scale(0f, 4f).meanScale(), 1e-6f)
    }

    // -----------------------------------------------------------------------------------------
    // transform and reversed
    // -----------------------------------------------------------------------------------------

    @Test
    fun transformScalesBoundsProportionally() {
        val path = VecPath(
            VecPoint(0f, 0f),
            listOf(
                VecSeg.Line(VecPoint(10f, 0f)),
                VecSeg.Line(VecPoint(10f, 20f)),
                VecSeg.Line(VecPoint(0f, 20f)),
            ),
            closed = true,
        )
        val before = path.bounds()
        assertEquals(floatArrayOf(0f, 0f, 10f, 20f).toList(), before.toList())

        val scaled = path.transform(Mat2D.scale(2f, 3f)).bounds()
        assertEquals(0f, scaled[0], 1e-4f)
        assertEquals(0f, scaled[1], 1e-4f)
        assertEquals(20f, scaled[2], 1e-4f)
        assertEquals(60f, scaled[3], 1e-4f)

        val moved = path.transform(Mat2D.translate(5f, -5f)).bounds()
        assertEquals(5f, moved[0], 1e-4f)
        assertEquals(-5f, moved[1], 1e-4f)
        assertEquals(15f, moved[2], 1e-4f)
        assertEquals(15f, moved[3], 1e-4f)
    }

    @Test
    fun transformScalesStrokeWidthsIsotropically() {
        val path = VecPath(
            VecPoint(0f, 0f),
            listOf(VecSeg.Line(VecPoint(10f, 0f))),
            strokeWidths = floatArrayOf(2f, 4f),
        )
        val up = assertNotNull(path.transform(Mat2D.scale(3f, 3f)).strokeWidths)
        assertEquals(6f, up[0], 1e-4f)
        assertEquals(12f, up[1], 1e-4f)
        // A rigid motion must not change a width, or a rotated export comes out a different weight.
        val spun = assertNotNull(path.transform(Mat2D.rotate(1.1f)).strokeWidths)
        assertEquals(2f, spun[0], 1e-4f)
        // Uniform-width paths stay uniform; a transform must not invent a per-vertex profile.
        val uniform = VecPath(VecPoint(0f, 0f), listOf(VecSeg.Line(VecPoint(10f, 0f))))
        assertNull(uniform.transform(Mat2D.scale(3f, 3f)).strokeWidths)
    }

    @Test
    fun reversedPreservesGeometry() {
        val path = VecPath(
            VecPoint(0f, 0f),
            listOf(
                VecSeg.Line(VecPoint(10f, 0f)),
                VecSeg.Cubic(VecPoint(15f, 5f), VecPoint(15f, 15f), VecPoint(10f, 20f)),
                VecSeg.Line(VecPoint(0f, 20f)),
            ),
            strokeWidths = floatArrayOf(1f, 2f, 3f, 4f),
        )
        val back = path.reversed()
        assertEquals(path.segments.size, back.segments.size)
        assertEquals(path.points().reversed(), back.points())

        val forward = path.flatten(0.05f)
        val backward = back.flatten(0.05f)
        assertEquals(forward.size, backward.size, "reversing changed the tessellation")
        for (i in forward.indices) {
            assertPointEquals(forward[i], backward[backward.size - 1 - i], 1e-3f)
        }
        assertEquals(path.length(), back.length(), 1e-3f)

        val widths = assertNotNull(back.strokeWidths)
        assertEquals(listOf(4f, 3f, 2f, 1f), widths.toList())
        // Reversing twice is the original path, which is what makes contour re-winding safe to
        // apply speculatively.
        assertEquals(path, back.reversed())
    }

    @Test
    fun reversedIsSafeOnADegeneratePath() {
        val lone = VecPath(VecPoint(1f, 2f), emptyList())
        assertEquals(lone, lone.reversed())
    }

    // -----------------------------------------------------------------------------------------
    // VecDocument
    // -----------------------------------------------------------------------------------------

    @Test
    fun documentCountsAndBoundsCoverEveryLayer() {
        val a = VecShape(
            VecPath(
                VecPoint(0f, 0f),
                listOf(
                    VecSeg.Line(VecPoint(10f, 0f)),
                    VecSeg.Line(VecPoint(10f, 10f)),
                    VecSeg.Line(VecPoint(0f, 10f)),
                ),
                closed = true,
            ),
            VecStyle(),
        )
        val b = VecShape(VecPath(VecPoint(20f, 20f), listOf(VecSeg.Line(VecPoint(30f, 30f)))), VecStyle())
        val c = VecShape(
            VecPath(
                VecPoint(-5f, -5f),
                listOf(VecSeg.Line(VecPoint(-2f, -5f)), VecSeg.Line(VecPoint(0f, 0f))),
            ),
            VecStyle(),
        )
        val doc = VecDocument(
            100f, 50f,
            listOf(
                VecLayer("l1", "Lines", listOf(a, b)),
                VecLayer("l2", "Hidden", listOf(c), visible = false),
            ),
        )

        assertEquals(3, doc.shapeCount())
        assertEquals(4 + 2 + 3, doc.nodeCount())

        // The hidden layer still contributes: toggling visibility must not reflow the export box.
        val bounds = doc.bounds()
        assertEquals(-5f, bounds[0], 1e-4f)
        assertEquals(-5f, bounds[1], 1e-4f)
        assertEquals(30f, bounds[2], 1e-4f)
        assertEquals(30f, bounds[3], 1e-4f)
    }

    @Test
    fun emptyDocumentFallsBackToTheCanvasBox() {
        val noLayers = VecDocument(100f, 50f, emptyList())
        assertEquals(0, noLayers.shapeCount())
        assertEquals(0, noLayers.nodeCount())
        assertEquals(listOf(0f, 0f, 100f, 50f), noLayers.bounds().toList())

        val emptyLayer = VecDocument(8f, 4f, listOf(VecLayer("l", "L", emptyList())))
        assertEquals(0, emptyLayer.shapeCount())
        assertEquals(0, emptyLayer.nodeCount())
        assertEquals(listOf(0f, 0f, 8f, 4f), emptyLayer.bounds().toList())
        // Transforming nothing is still a valid document rather than a divide by zero.
        val scaled = emptyLayer.transform(Mat2D.scale(2f, 2f))
        assertEquals(16f, scaled.width, 1e-4f)
        assertEquals(8f, scaled.height, 1e-4f)
    }

    @Test
    fun documentTransformScalesTheCanvasButARotationDoesNot() {
        val shape = VecShape(
            VecPath(VecPoint(0f, 0f), listOf(VecSeg.Line(VecPoint(10f, 10f)))),
            VecStyle(strokeWidth = 2f),
        )
        val doc = VecDocument(20f, 10f, listOf(VecLayer("l", "L", listOf(shape))))

        val scaled = doc.transform(Mat2D.scale(2f, 4f))
        assertEquals(40f, scaled.width, 1e-4f)
        assertEquals(40f, scaled.height, 1e-4f)
        assertEquals(2f * sqrt(8f), scaled.layers[0].shapes[0].style.strokeWidth, 1e-4f)

        // A rotation has unit column norms, so the canvas is left alone: the export path composes
        // scales only, and a canvas that grew under a rotation would crop on the next export.
        val spun = doc.transform(Mat2D.rotate(0.6f))
        assertEquals(20f, spun.width, 1e-3f)
        assertEquals(10f, spun.height, 1e-3f)
        assertEquals(2f, spun.layers[0].shapes[0].style.strokeWidth, 1e-4f)
    }

    // -----------------------------------------------------------------------------------------

    private fun assertPointEquals(expected: VecPoint, actual: VecPoint, tolerance: Float = 1e-4f) {
        assertEquals(expected.x, actual.x, tolerance, "x mismatch")
        assertEquals(expected.y, actual.y, tolerance, "y mismatch")
    }
}
