package com.offlinetracer.vector

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VectorizeTest {

    // -----------------------------------------------------------------------------------------
    // Centreline mode
    // -----------------------------------------------------------------------------------------

    @Test
    fun centrelineCrossHasNoHoleAtTheJunction() {
        // A one-pixel-thick plus with 9 px arms. One pixel thick because :core-vector does not thin
        // — the cleanup stage owns that decision — so this is what a skeleton actually looks like
        // when it reaches here.
        val m = Mask(21, 21)
        hLine(m, 10, 1, 19)
        vLine(m, 10, 1, 19)

        // minPathLength 0 so nothing is filtered: this test is about the geometry the tracer emits,
        // and the length filter is exercised separately.
        val shapes = Vectorize.run(m, VectorizeParams(mode = VectorMode.CENTERLINE, minPathLength = 0f))
        assertTrue(shapes.isNotEmpty(), "the cross produced no paths at all")
        for (s in shapes) {
            assertTrue(!s.path.closed, "a centreline stroke came back closed")
            assertNull(s.path.strokeWidths, "widths must be null unless modulation was asked for")
        }

        // The four arms are COVERED, but they need not be four separate paths.
        //
        // This assertion used to read `assertEquals(4, arms)`, which pinned the tracer to emitting one
        // path per skeleton graph edge — junction to junction. That is exactly the behaviour that made
        // real output unusable: on a photograph the skeleton carries thousands of incidental junctions,
        // so a single long contour was shattered into dozens of 5-20 px fragments, 88% of kept paths
        // came out under 20 px, and a 900x1200 trace produced a 457 kB SVG of confetti.
        //
        // The tracer now walks THROUGH a junction when a stroke has an unambiguous continuation, so the
        // plus is emitted as two crossing strokes rather than four stubs. That is strictly better
        // geometry — the horizontal and vertical strokes are each continuous through the centre, which
        // is also why the junction-coverage assertions below get stronger rather than weaker — and the
        // count is an implementation detail, so the test now asserts the property it was always about:
        // every arm is covered, and nothing has a hole at the crossing.
        val armReach = floatArrayOf(0f, 0f, 0f, 0f)   // left, right, up, down, as distance from centre
        for (s in shapes) {
            for (p in s.path.flatten()) {
                if (abs(p.y - 10f) < 0.51f) {
                    if (p.x < 10f) armReach[0] = maxOf(armReach[0], 10f - p.x)
                    if (p.x > 10f) armReach[1] = maxOf(armReach[1], p.x - 10f)
                }
                if (abs(p.x - 10f) < 0.51f) {
                    if (p.y < 10f) armReach[2] = maxOf(armReach[2], 10f - p.y)
                    if (p.y > 10f) armReach[3] = maxOf(armReach[3], p.y - 10f)
                }
            }
        }
        for ((i, name) in listOf("left", "right", "up", "down").withIndex()) {
            assertTrue(armReach[i] > 8f, "the $name arm reaches only ${armReach[i]} px from the centre")
        }

        // The junction itself. A tracer that walks 8-connected runs without classifying junctions
        // leaves every arm a pixel short of the centre, and the SVG then has a hole wherever two
        // strokes cross — which on a dense drawing is everywhere.
        assertTrue(
            distanceToShapes(shapes, 10f, 10f) < 0.01f,
            "nothing passes through the junction: the centre is ${distanceToShapes(shapes, 10f, 10f)} away",
        )

        // Two or more paths must PASS OVER the crossing — measured as distance to the path geometry,
        // not as the presence of a vertex there.
        //
        // This was `abs(p.x - 10) < 1e-3 && abs(p.y - 10) < 1e-3` over `flatten()`, i.e. "some path has
        // an anchor exactly at the centre". That held only while the tracer stopped at every junction
        // and therefore had to put an endpoint there. It is now false for the best possible reason: the
        // stroke is chained straight through, `removeCollinear` correctly reduces a 19-point straight
        // run to its two endpoints, and the segment from (10,1) to (10,19) covers the centre without
        // owning a vertex anywhere near it. A vertex test would demand the tracer re-introduce exactly
        // the break this test exists to forbid.
        var touching = 0
        for (s in shapes) {
            if (distanceToShapes(listOf(s), 10f, 10f) < 0.01f) touching++
        }
        assertTrue(touching >= 2, "only $touching path(s) cross the junction, so the join is a seam")

        // Every inked pixel of the cross is covered by the union of the paths.
        for (t in 1..19) {
            assertTrue(
                distanceToShapes(shapes, t.toFloat(), 10f) < 0.6f,
                "the horizontal bar is not covered at x = $t",
            )
            assertTrue(
                distanceToShapes(shapes, 10f, t.toFloat()) < 0.6f,
                "the vertical bar is not covered at y = $t",
            )
        }
        // Both tips of both bars, so the union spans the cross rather than a middle portion of it.
        for (tip in listOf(VecPoint(1f, 10f), VecPoint(19f, 10f), VecPoint(10f, 1f), VecPoint(10f, 19f))) {
            assertTrue(distanceToShapes(shapes, tip.x, tip.y) < 0.05f, "tip (${tip.x}, ${tip.y}) is missing")
        }
    }

    @Test
    fun centrelineStrokeStaysOpenAndOnTheInk() {
        val m = Mask(21, 5)
        hLine(m, 2, 1, 19)
        val shapes = Vectorize.run(m, VectorizeParams(mode = VectorMode.CENTERLINE))
        assertEquals(1, shapes.size)
        val path = shapes[0].path
        assertTrue(!path.closed)
        assertEquals(18f, path.length(), 0.2f)
        for (p in path.flatten()) assertEquals(2f, p.y, 0.05f)
    }

    // -----------------------------------------------------------------------------------------
    // Outline mode
    // -----------------------------------------------------------------------------------------

    @Test
    fun outlineOfASquareWithAHoleWindsBothWays() {
        val m = Mask(21, 21)
        for (y in 3..17) for (x in 3..17) m[x, y] = true
        for (y in 8..12) for (x in 8..12) m[x, y] = false

        val shapes = Vectorize.run(m, VectorizeParams(mode = VectorMode.OUTLINE))
        assertEquals(2, shapes.size, "expected one outer contour and one hole")
        for (s in shapes) assertTrue(s.path.closed, "an outline contour came back open")

        val areaA = Boolean2D.polygonArea(shapes[0].path.flatten())
        val areaB = Boolean2D.polygonArea(shapes[1].path.flatten())
        assertTrue(abs(areaA) > 1f && abs(areaB) > 1f, "a contour collapsed to zero area")
        // Opposite winding is what lets the SVG writer emit both rings in one path with
        // fill-rule="evenodd" and get the hole for free — no nesting analysis at all.
        assertTrue(areaA * areaB < 0f, "both contours wind the same way: $areaA and $areaB")

        val outer = if (abs(areaA) > abs(areaB)) shapes[0].path else shapes[1].path
        val hole = if (abs(areaA) > abs(areaB)) shapes[1].path else shapes[0].path
        val ob = outer.bounds()
        val hb = hole.bounds()
        assertTrue(hb[0] > ob[0] && hb[1] > ob[1] && hb[2] < ob[2] && hb[3] < ob[3], "the hole is not inside")

        // The ink spans pixel centres 3..17, so the true outline encloses exactly 14 x 14 = 196.
        //
        // Asserting that figure against the DEFAULT params would be asserting the wrong thing. The
        // defaults deliberately soften the shape twice over: `smoothIterations = 1` runs Chaikin,
        // whose entire job is to cut corners so a pixel staircase stops looking like a staircase, and
        // `fitError = 1.6` is a SQUARED tolerance, so the fitter is licensed to bow by sqrt(1.6) =
        // 1.26 px anywhere along the ring. On a 14 px square that licence is worth roughly
        // (14 + 2*1.26)^2 = 272 at the extreme, so a default-params area near 196 would mean the
        // defaults were not doing what they claim.
        //
        // So the fidelity claim is tested where it is actually a claim — smoothing off, tolerance
        // tight — and the defaults are checked only for staying inside the band their own parameters
        // permit. Splitting the two is what makes either assertion mean anything.
        val faithful = Vectorize.run(
            m,
            VectorizeParams(
                mode = VectorMode.OUTLINE,
                smoothIterations = 0,
                simplifyEpsilon = 0f,
                fitError = 0.001f,
            ),
        )
        val faithfulOuter = faithful
            .map { abs(Boolean2D.polygonArea(it.path.flatten())) }
            .maxOrNull() ?: 0f
        // Exact: with the corners preserved every run is a straight line, so there is nothing to
        // approximate. A regression here means corner detection has stopped finding the four
        // vertices and the fitter is smoothing through them.
        assertEquals(196f, faithfulOuter, 0.5f, "an unsmoothed, tightly fitted square must be exact")

        val defaultOuter = abs(Boolean2D.polygonArea(outer.flatten()))
        assertTrue(
            defaultOuter in 170f..280f,
            "the default-params area left the band its own smoothing and 1.26 px fit licence " +
                "allow, got $defaultOuter",
        )
    }

    @Test
    fun outlineOfASolidBlobIsASingleClosedRing() {
        val m = Mask(12, 12)
        for (y in 2..9) for (x in 2..9) m[x, y] = true
        val shapes = Vectorize.run(m, VectorizeParams(mode = VectorMode.OUTLINE))
        assertEquals(1, shapes.size)
        assertTrue(shapes[0].path.closed)
        // The boundary runs through pixel centres 2..9, so it is a 7 x 7 ring less corner rounding.
        assertEquals(28f, shapes[0].path.length(), 6f)
    }

    // -----------------------------------------------------------------------------------------
    // minPathLength
    // -----------------------------------------------------------------------------------------

    @Test
    fun raisingMinPathLengthDropsTheShortPaths() {
        // Three isolated strokes, six rows apart so none of them is 8-connected to another.
        val m = Mask(21, 21)
        hLine(m, 3, 1, 17)     // 16 units long
        hLine(m, 9, 1, 6)      // 5 units long
        hLine(m, 15, 1, 2)     // 1 unit long

        val base = VectorizeParams(mode = VectorMode.CENTERLINE)
        val all = Vectorize.run(m, base.copy(minPathLength = 0.5f)).size
        val medium = Vectorize.run(m, base.copy(minPathLength = 3f)).size
        val long = Vectorize.run(m, base.copy(minPathLength = 10f)).size
        val none = Vectorize.run(m, base.copy(minPathLength = 100f)).size

        assertEquals(3, all)
        assertEquals(2, medium, "the 1 unit stroke should have been dropped")
        assertEquals(1, long, "only the 16 unit stroke is longer than 10")
        assertEquals(0, none)
        assertTrue(all > medium && medium > long && long > none)
    }

    // -----------------------------------------------------------------------------------------
    // Width modulation
    // -----------------------------------------------------------------------------------------

    @Test
    fun modulatedWidthFollowsTheDistanceTransform() {
        // Two centrelines and the distance transform of the bars they came from: half-width 4 on the
        // upper bar, half-width 1 on the lower one.
        val m = Mask(21, 21)
        hLine(m, 4, 2, 18)
        hLine(m, 15, 2, 18)

        val dt = GrayF(21, 21)
        for (y in 3..5) for (x in 0 until 21) dt[x, y] = 4f
        for (y in 14..16) for (x in 0 until 21) dt[x, y] = 1f

        val params = VectorizeParams(mode = VectorMode.CENTERLINE, modulateWidth = true)
        val shapes = Vectorize.run(m, params, dt)
        assertEquals(2, shapes.size)

        val thick = pathNear(shapes, 4f)
        val thin = pathNear(shapes, 15f)
        val thickWidths = assertNotNull(thick.strokeWidths, "modulation produced no widths")
        val thinWidths = assertNotNull(thin.strokeWidths, "modulation produced no widths")
        assertEquals(thick.points().size, thickWidths.size, "widths are per anchor")
        assertEquals(thin.points().size, thinWidths.size, "widths are per anchor")

        // w = clamp(2 * DT * widthScale, minWidth, maxWidth).
        for (w in thickWidths) assertEquals(8f, w, 0.2f)
        for (w in thinWidths) assertEquals(2f, w, 0.2f)
        assertTrue(thickWidths[0] > thinWidths[0], "the thick bar did not come out thicker")
    }

    @Test
    fun modulatedWidthIsClampedAndScaled() {
        val m = Mask(21, 9)
        hLine(m, 4, 2, 18)
        val dt = GrayF(21, 9)
        for (y in 3..5) for (x in 0 until 21) dt[x, y] = 4f

        val base = VectorizeParams(mode = VectorMode.CENTERLINE, modulateWidth = true)
        val capped = assertNotNull(Vectorize.run(m, base.copy(maxWidth = 5f), dt)[0].path.strokeWidths)
        for (w in capped) assertEquals(5f, w, 1e-3f)

        val floored = assertNotNull(Vectorize.run(m, base.copy(widthScale = 0f, minWidth = 0.4f), dt)[0].path.strokeWidths)
        // A non-positive scale is coerced rather than collapsing the stroke to nothing.
        for (w in floored) assertTrue(w >= 0.4f, "width $w fell below minWidth")

        val halved = assertNotNull(Vectorize.run(m, base.copy(widthScale = 0.5f), dt)[0].path.strokeWidths)
        for (w in halved) assertEquals(4f, w, 0.2f)
    }

    @Test
    fun widthsAreNullWithoutModulation() {
        val m = Mask(21, 9)
        hLine(m, 4, 2, 18)
        val dt = GrayF(21, 9)
        for (y in 3..5) for (x in 0 until 21) dt[x, y] = 4f

        val off = Vectorize.run(m, VectorizeParams(mode = VectorMode.CENTERLINE), dt)
        assertEquals(1, off.size)
        assertNull(off[0].path.strokeWidths, "a transform was supplied but modulation was off")

        // Asking for modulation without a transform is not an error: the path is simply uniform.
        val noDt = Vectorize.run(m, VectorizeParams(mode = VectorMode.CENTERLINE, modulateWidth = true))
        assertEquals(1, noDt.size)
        assertNull(noDt[0].path.strokeWidths)
    }

    // -----------------------------------------------------------------------------------------
    // Degenerate input
    // -----------------------------------------------------------------------------------------

    @Test
    fun allBackgroundMaskYieldsNoShapes() {
        val m = Mask(16, 16)
        for (mode in listOf(VectorMode.CENTERLINE, VectorMode.OUTLINE)) {
            val shapes = Vectorize.run(m, VectorizeParams(mode = mode, minPathLength = 0f))
            assertTrue(shapes.isEmpty(), "$mode invented ${shapes.size} shapes from an empty mask")
        }
    }

    @Test
    fun onePixelMasksNeverThrow() {
        for (ink in listOf(false, true)) {
            val m = Mask(1, 1)
            m[0, 0] = ink
            for (mode in listOf(VectorMode.CENTERLINE, VectorMode.OUTLINE)) {
                // A single pixel is a one-point contour or polyline: there is no geometry to draw,
                // but it must not be an error either.
                val shapes = Vectorize.run(m, VectorizeParams(mode = mode, minPathLength = 0f))
                assertTrue(shapes.isEmpty(), "$mode produced ${shapes.size} shapes for a 1x1 mask")
            }
        }
    }

    @Test
    fun fullyForegroundMaskNeverThrows() {
        val m = Mask(6, 6).fill(true)

        // Outline mode is meaningful here: the whole image is one region.
        val outline = Vectorize.run(m, VectorizeParams(mode = VectorMode.OUTLINE))
        assertEquals(1, outline.size)
        assertTrue(outline[0].path.closed)

        // Centreline mode is not, and says so by producing nothing: a solid blob is all junctions,
        // so every graph edge is one pixel long and falls under the minimum length. Feeding an
        // unthinned mask to centreline mode is a cleanup-stage mistake, not an exception here.
        val centreline = Vectorize.run(m, VectorizeParams(mode = VectorMode.CENTERLINE))
        assertTrue(centreline.isEmpty())

        val unfiltered = Vectorize.run(m, VectorizeParams(mode = VectorMode.CENTERLINE, minPathLength = 0f))
        for (s in unfiltered) {
            val b = s.path.bounds()
            for (v in b) assertTrue(v.isFinite(), "non-finite bounds")
        }
    }

    @Test
    fun pathologicalParametersAreCoerced() {
        val m = Mask(21, 21)
        hLine(m, 10, 1, 19)
        vLine(m, 10, 1, 19)
        val hostile = VectorizeParams(
            simplifyEpsilon = Float.NaN,
            fitError = -1f,
            cornerThresholdDegrees = 0f,
            smoothIterations = 500,
            minPathLength = Float.NaN,
            strokeWidth = 0f,
            modulateWidth = true,
            widthScale = Float.NaN,
            minWidth = -3f,
            maxWidth = Float.NaN,
        )
        val dt = GrayF(21, 21).fill(Float.NaN)
        val shapes = Vectorize.run(m, hostile, dt)
        for (s in shapes) {
            assertTrue(s.style.strokeWidth > 0f, "a zero stroke width would render nothing")
            for (p in s.path.points()) assertTrue(p.x.isFinite() && p.y.isFinite())
            val widths = s.path.strokeWidths
            if (widths != null) for (w in widths) assertTrue(w.isFinite(), "non-finite stroke width")
        }
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private fun hLine(m: Mask, y: Int, x0: Int, x1: Int) {
        for (x in x0..x1) m[x, y] = true
    }

    private fun vLine(m: Mask, x: Int, y0: Int, y1: Int) {
        for (y in y0..y1) m[x, y] = true
    }

    /** The one path whose start sits on row [y]. */
    private fun pathNear(shapes: List<VecShape>, y: Float): VecPath {
        for (s in shapes) if (abs(s.path.start.y - y) < 1.5f) return s.path
        throw AssertionError("no path starts near y = $y")
    }

    /** Distance from `(x, y)` to the nearest point of the union of the flattened paths. */
    private fun distanceToShapes(shapes: List<VecShape>, x: Float, y: Float): Float {
        val p = VecPoint(x, y)
        var best = Float.MAX_VALUE
        for (s in shapes) {
            val pts = s.path.flatten()
            if (pts.size == 1) {
                val d = distance(p, pts[0])
                if (d < best) best = d
                continue
            }
            for (i in 0 until pts.size - 1) {
                val d = pointSegmentDistance(p, pts[i], pts[i + 1])
                if (d < best) best = d
            }
            if (s.path.closed && pts.size > 2) {
                val d = pointSegmentDistance(p, pts[pts.size - 1], pts[0])
                if (d < best) best = d
            }
        }
        return best
    }

    private fun distance(a: VecPoint, b: VecPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
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
