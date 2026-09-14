package com.fieldrepository.app.data

import com.fieldrepository.app.data.PhotoMeasure.DEFAULT_MARK_SIGMA_PX
import com.fieldrepository.app.data.PhotoMeasure.LENGTH_UNITS
import com.fieldrepository.app.data.PhotoMeasure.MIN_REFERENCE_PIXELS
import com.fieldrepository.app.data.PhotoMeasure.applyHomography
import com.fieldrepository.app.data.PhotoMeasure.convertLength
import com.fieldrepository.app.data.PhotoMeasure.distanceBetween
import com.fieldrepository.app.data.PhotoMeasure.distanceSigma
import com.fieldrepository.app.data.PhotoMeasure.markSigmaForDisplayScale
import com.fieldrepository.app.data.PhotoMeasure.measureByRectification
import com.fieldrepository.app.data.PhotoMeasure.measureBySameScale
import com.fieldrepository.app.data.PhotoMeasure.propagateUncertainty
import com.fieldrepository.app.data.PhotoMeasure.roundToUncertainty
import com.fieldrepository.app.data.PhotoMeasure.solveHomography
import com.fieldrepository.app.data.PhotoMeasure.solveLinearSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * [PhotoMeasure] — the projective geometry behind "measure this from a photo".
 *
 * THE CASES ARE `frontend/e2e/photo-measure-unit.spec.ts`, CASE FOR CASE AND NUMBER FOR NUMBER. That file
 * is this module's specification, so a case that exists there and not here is a place the two clients
 * are free to disagree, and the numbers below are the web's own expectations rather than whatever
 * this port printed the day it was written.
 *
 * WHY SYNTHETIC CASES AND NOT PHOTOGRAPHS. Every case here has an answer that is known in advance
 * because the case was CONSTRUCTED from the answer: a scale of exactly two, a homography written down
 * and then inverted, a segment whose world length is √24400. A test against a real photograph could
 * only assert that the module agrees with whatever it printed the day it was written, which is a
 * regression test for arithmetic nobody ever checked.
 *
 * WHY THE NUMBERS ARE PINNED AND NOT THE VERDICTS. `assertTrue(result is Measurement)` passes for a
 * function that returns the reference length unchanged. Every assertion here is on a VALUE, to a
 * stated precision, and the precisions are chosen so that a real defect cannot slip under them: 1e-9
 * on a homography recovery, 1e-6 on a rectified length in millimetres (a micron on an A4 sheet).
 *
 * THE FIFTH POINT IS THE WHOLE HOMOGRAPHY TEST. Any solve that returns something at all reproduces
 * the four points it was fitted to — that is what "fitted" means, and a completely wrong
 * implementation still passes it. A point that took no part in the fit is the only evidence that what
 * came back is the transform rather than a curve through four dots.
 *
 * THREE CASES ARE NOT IN THE WEB SPEC, because they pin seams that exist only in Kotlin:
 * `an exact tie rounds the way the browser rounds it`, `a negative value rounded away to nothing
 * keeps its sign` and `a corner count other than four is refused`. The first is the one that would
 * have shipped: reaching for a JVM rounding reflex in this module — `BigDecimal` half-to-even, or
 * letting `String.format` do the rounding — puts a different figure on the handset from the one the
 * browser proposes, for the same photograph and the same marks.
 */
class PhotoMeasureTest {

    // -----------------------------------------------------------------------
    // Fixtures — the web spec's, unchanged
    // -----------------------------------------------------------------------

    /**
     * A deliberately awkward projective transform: it rotates a little, shears a little, translates a
     * long way, and — the part that matters — has a non-zero bottom row, so parallel lines in the
     * world genuinely converge in the image. A transform with h31 = h32 = 0 is an affine map, and an
     * affine map is recovered correctly by code that has no projective handling in it at all.
     */
    private val tilt = Homography(1.2, 0.15, 300.0, 0.05, 1.1, 250.0, 0.0004, 0.0002, 1.0)

    /**
     * The same shape of transform with a much steeper bottom row — a handset held over a sheet at
     * arm's length rather than square above it, which is how these photographs are actually taken.
     *
     * It exists because [tilt] is too polite to make the argument: under it the naive two-mark reading
     * is only 1.6% short, and a test asserting that would suggest the four-point method is a
     * refinement. Under this one it is 31% short, which is what an oblique photograph really costs.
     */
    private val oblique = Homography(1.2, 0.15, 300.0, 0.05, 1.1, 250.0, 0.0012, 0.0009, 1.0)

    /**
     * The same sheet as photographed by an actual handset: an A4 spread across a 4000x3000 frame.
     *
     * THIS FIXTURE IS THE ONLY ONE THAT TESTS THE CONDITIONING. The 8x8 system built from these
     * coordinates mixes a constant column with x·u terms of order 1e7, and that is where a raw solve
     * starts throwing away digits. A suite that only ever measures a 500 px toy image would report
     * full marks on a port that had quietly lost six of them.
     */
    private val handset = Homography(9.6, 1.2, 700.0, 0.4, 8.8, 500.0, 0.00035, 0.00018, 1.0)

    /** A4, in millimetres — the sheet a researcher in a village actually has. */
    private val a4 = KnownRectangle(width = 210.0, height = 297.0, unit = "mm")

    /** The A4 corners in world millimetres, in order around the sheet. */
    private val a4Corners = listOf(
        MeasurePoint(0.0, 0.0),
        MeasurePoint(210.0, 0.0),
        MeasurePoint(210.0, 297.0),
        MeasurePoint(0.0, 297.0),
    )

    private fun projectAll(h: Homography, points: List<MeasurePoint>): List<MeasurePoint> =
        points.map { applyHomography(h, it) }

    /**
     * The web spec's `toBeCloseTo(expected, digits)`, which passes when the difference is under half
     * of the last asserted place. Spelled out rather than approximated with a round number, so a
     * precision that was tightened on the web tightens here by the same amount.
     */
    private fun assertCloseTo(expected: Double, actual: Double, digits: Int, what: String = "") {
        assertEquals(what, expected, actual, 0.5 * 10.0.pow(-digits))
    }

    /** The measurement, or a failure naming the refusal — never a silent skip past the assertions. */
    private fun measured(result: MeasureResult): MeasureResult.Measurement = when (result) {
        is MeasureResult.Measurement -> result
        is MeasureResult.Refusal -> throw AssertionError("expected a measurement, got: ${result.reason}")
    }

    private fun refusal(result: MeasureResult): MeasureResult.Refusal = when (result) {
        is MeasureResult.Refusal -> result
        is MeasureResult.Measurement -> throw AssertionError("expected a refusal, got ${result.value} ${result.unit}")
    }

    // -----------------------------------------------------------------------
    // The linear algebra, on its own
    // -----------------------------------------------------------------------

    @Test
    fun `solves a small system exactly and refuses a singular one`() {
        // 2x + y = 5 ; x - y = 1  →  x = 2, y = 1.
        val solved = solveLinearSystem(
            listOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, -1.0)),
            doubleArrayOf(5.0, 1.0),
        )
        assertNotNull(solved)
        assertCloseTo(2.0, solved!![0], 12)
        assertCloseTo(1.0, solved[1], 12)

        // The second row is the first, doubled: there is no unique answer, and returning one would be
        // a fabrication. NOT an Infinity, NOT a NaN — null, so every caller has to decide what to say.
        assertNull(
            solveLinearSystem(
                listOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0)),
                doubleArrayOf(3.0, 6.0),
            )
        )
    }

    @Test
    fun `partial pivoting survives a zero in the first pivot position`() {
        // Without a row swap the very first division is by zero and every later entry is NaN.
        val solved = solveLinearSystem(
            listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 0.0)),
            doubleArrayOf(3.0, 4.0),
        )
        assertNotNull(solved)
        assertCloseTo(4.0, solved!![0], 12)
        assertCloseTo(3.0, solved[1], 12)
    }

    @Test
    fun `elimination does not write through into the caller's rows`() {
        // Kotlin's DoubleArray is shared by reference where the web's `map(row => row.slice())` copies
        // it. A solve that eliminated in place would leave the caller holding a triangularised matrix,
        // and in [measureByRectification] the same coordinates are solved twenty-five times.
        val row = doubleArrayOf(2.0, 1.0)
        val rhs = doubleArrayOf(5.0, 1.0)
        solveLinearSystem(listOf(row, doubleArrayOf(1.0, -1.0)), rhs)
        assertEquals(2.0, row[0], 0.0)
        assertEquals(1.0, row[1], 0.0)
        assertEquals(5.0, rhs[0], 0.0)
    }

    // -----------------------------------------------------------------------
    // The homography
    // -----------------------------------------------------------------------

    @Test
    fun `applyHomography divides by the third row`() {
        // Hand-computed: x' = 1.2·210 + 300 = 552 ; y' = 0.05·210 + 250 = 260.5 ;
        // w = 0.0004·210 + 1 = 1.084.
        val mapped = applyHomography(tilt, MeasurePoint(210.0, 0.0))
        assertCloseTo(552 / 1.084, mapped.x, 9)
        assertCloseTo(260.5 / 1.084, mapped.y, 9)
        assertCloseTo(509.2250922509225, mapped.x, 9)
        assertCloseTo(240.3136531365314, mapped.y, 9)
    }

    @Test
    fun `a known homography applied to known points is recovered from them`() {
        val imaged = projectAll(tilt, a4Corners)
        val solved = solveHomography(imaged, a4Corners)
        assertNotNull(solved)
        val recovered: Homography = solved!!

        // The four fitted corners come back exactly — necessary, and on its own worth nothing.
        for (index in 0 until 4) {
            val back = applyHomography(recovered, imaged[index])
            assertCloseTo(a4Corners[index].x, back.x, 9)
            assertCloseTo(a4Corners[index].y, back.y, 9)
        }

        // THE FIFTH POINT — see the class header. It took no part in the fit, so only a genuinely
        // recovered transform sends it home.
        val fifth = MeasurePoint(137.5, 88.25)
        val roundTrip = applyHomography(recovered, applyHomography(tilt, fifth))
        assertCloseTo(137.5, roundTrip.x, 9)
        assertCloseTo(88.25, roundTrip.y, 9)

        // A sixth, far outside the marked rectangle, where a badly-conditioned solve goes wrong first.
        val outsideBack = applyHomography(recovered, applyHomography(tilt, MeasurePoint(-60.0, 420.0)))
        assertCloseTo(-60.0, outsideBack.x, 8)
        assertCloseTo(420.0, outsideBack.y, 8)
    }

    @Test
    fun `four collinear points are refused rather than solved`() {
        val collinear = listOf(
            MeasurePoint(100.0, 100.0),
            MeasurePoint(200.0, 150.0),
            MeasurePoint(300.0, 200.0),
            MeasurePoint(400.0, 250.0),
        )
        assertNull(solveHomography(collinear, a4Corners))
    }

    @Test
    fun `three collinear points out of four are refused too`() {
        // The failure mode that actually happens: a researcher marks three corners along the edge of a
        // sheet lying flat against a wall. The system is singular and Gaussian elimination on it
        // produces ±Infinity, which then propagates into a finite-looking millimetre figure.
        val nearlyDegenerate = listOf(
            MeasurePoint(100.0, 100.0),
            MeasurePoint(200.0, 100.0),
            MeasurePoint(300.0, 100.0),
            MeasurePoint(150.0, 400.0),
        )
        assertNull(solveHomography(nearlyDegenerate, a4Corners))
    }

    @Test
    fun `a duplicated corner is refused`() {
        val duplicated = listOf(
            MeasurePoint(100.0, 100.0),
            MeasurePoint(100.0, 100.0),
            MeasurePoint(300.0, 220.0),
            MeasurePoint(120.0, 260.0),
        )
        assertNull(solveHomography(duplicated, a4Corners))
    }

    // -----------------------------------------------------------------------
    // The same-plane scale method
    // -----------------------------------------------------------------------

    @Test
    fun `an exact scale of two returns exactly twice the reference length`() {
        val result = measured(
            measureBySameScale(
                reference = ScaleReference(MeasurePoint(100.0, 100.0), MeasurePoint(300.0, 100.0), 100.0, "mm"),
                target = MeasureSegment(MeasurePoint(100.0, 400.0), MeasurePoint(500.0, 400.0)),
            )
        )
        assertEquals(200.0, result.value, 0.0)
        assertEquals(200.0, result.referencePixels, 0.0)
        assertEquals(400.0, result.targetPixels, 0.0)
        assertEquals(PhotoMeasure.METHOD_SCALE, result.method)
        assertEquals("mm", result.unit)
    }

    @Test
    fun `the scale is invariant to the direction the marks were placed in`() {
        val forward = measured(
            measureBySameScale(
                reference = ScaleReference(MeasurePoint(100.0, 100.0), MeasurePoint(300.0, 100.0), 100.0, "mm"),
                target = MeasureSegment(MeasurePoint(100.0, 400.0), MeasurePoint(340.0, 320.0)),
            )
        )
        val reversed = measured(
            measureBySameScale(
                reference = ScaleReference(MeasurePoint(300.0, 100.0), MeasurePoint(100.0, 100.0), 100.0, "mm"),
                target = MeasureSegment(MeasurePoint(340.0, 320.0), MeasurePoint(100.0, 400.0)),
            )
        )
        // 240-80 → hypot(240, 80) = 252.98221281347036 px over a 200 px / 100 mm reference.
        assertCloseTo(126.49110640673518, forward.value, 12)
        assertCloseTo(forward.value, reversed.value, 12)
    }

    @Test
    fun `a diagonal reference measures a diagonal target`() {
        // 3-4-5, twice over: the reference is 50 px for 25 mm, the target is 250 px.
        val result = measured(
            measureBySameScale(
                reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(30.0, 40.0), 25.0, "mm"),
                target = MeasureSegment(MeasurePoint(10.0, 10.0), MeasurePoint(160.0, 210.0)),
            )
        )
        assertCloseTo(50.0, result.referencePixels, 12)
        assertCloseTo(250.0, result.targetPixels, 12)
        assertCloseTo(125.0, result.value, 12)
    }

    // -----------------------------------------------------------------------
    // Uncertainty
    // -----------------------------------------------------------------------

    @Test
    fun `a distance between two independently placed marks is root two times as uncertain as one`() {
        assertCloseTo(2.8284271247461903, distanceSigma(2.0), 12)
        assertCloseTo(4.242640687119285, distanceSigma(3.0), 12)
        assertEquals(0.0, distanceSigma(0.0), 0.0)
    }

    @Test
    fun `the brief's case - a 3 px error on a 200 px reference is 1_5 percent of the reference`() {
        // Stated as the module's own arithmetic rather than as a comment, so it cannot drift: with the
        // per-endpoint sigma set to 3/√2 the DISTANCE sigma is exactly 3 px.
        val markSigmaPx = 3 / sqrt(2.0)
        assertCloseTo(3.0, distanceSigma(markSigmaPx), 12)

        val result = measured(
            measureBySameScale(
                reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm"),
                target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(400.0, 50.0)),
                markSigmaPx = markSigmaPx,
            )
        )
        // 3/200 = 1.5% on the reference, 3/400 = 0.75% on the target, in quadrature:
        // √(0.015² + 0.0075²) = 0.016770509831248423.
        assertCloseTo(0.016770509831248423, result.relativeUncertainty, 12)
        assertEquals(200.0, result.value, 0.0)
        assertCloseTo(3.3541019662496847, result.uncertainty, 12)
    }

    @Test
    fun `a shorter reference is a wider error bar, and the widening is quantified`() {
        val markSigmaPx = 3 / sqrt(2.0)
        val target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(400.0, 50.0))
        val long = measured(
            measureBySameScale(
                reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm"),
                target = target,
                markSigmaPx = markSigmaPx,
            )
        )
        val short = measured(
            measureBySameScale(
                reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(50.0, 0.0), 25.0, "mm"),
                target = target,
                markSigmaPx = markSigmaPx,
            )
        )
        // Same answer — 400 px against 50 px/25 mm is still 200 mm — and four times the doubt from the
        // reference: √((3/50)² + (3/400)²) = √0.00365625 = 0.06046693311223912.
        assertEquals(200.0, short.value, 0.0)
        assertCloseTo(0.06046693311223912, short.relativeUncertainty, 12)
        // The widening is pinned as a RATIO as well, because that is the statement a researcher acts on:
        // marking against a scale bar a quarter as long in the frame is 3.6 times the doubt.
        assertCloseTo(3.605551275463989, short.relativeUncertainty / long.relativeUncertainty, 9)
    }

    @Test
    fun `a known reference length that is itself uncertain widens the bar further`() {
        val reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm")
        val target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(400.0, 50.0))
        val markSigmaPx = 3 / sqrt(2.0)
        val exact = measured(measureBySameScale(reference, target, markSigmaPx))
        val sloppy = measured(measureBySameScale(reference, target, markSigmaPx, referenceLengthSigma = 2.0))
        // 2 mm on a 100 mm card is another 2%, in quadrature with 1.677%:
        // √(0.00028125 + 0.0004) = √0.00068125 = 0.026100766272276376.
        assertCloseTo(0.026100766272276376, sloppy.relativeUncertainty, 12)
        assertTrue(sloppy.relativeUncertainty > exact.relativeUncertainty)
    }

    @Test
    fun `the numerical propagation reproduces the closed form it replaces`() {
        // The length of a segment between two marks, as a function of its four coordinates. The
        // analytic answer is √2·σ, and the generic propagator is what the rectified path has to trust
        // — so it is checked here against a case whose answer is known rather than only inside one.
        val propagatedOrNull = propagateUncertainty(doubleArrayOf(0.0, 0.0, 300.0, 400.0), 2.0) { c ->
            distanceBetween(MeasurePoint(c[0], c[1]), MeasurePoint(c[2], c[3]))
        }
        assertNotNull(propagatedOrNull)
        val propagated: Double = propagatedOrNull!!

        // AGREEMENT TO ~4e-6 RELATIVE, AND NOT TO MACHINE PRECISION, ON PURPOSE. The propagator steps
        // by σ itself, so what is left over is the genuine second-order curvature of `distance` across
        // ±2 px of a 500 px segment — a property of a first-order propagation, not a defect in it.
        // Pinning the residual rather than hiding it behind a loose tolerance is what makes this test
        // fail if the step size or the differencing scheme is ever changed.
        val analytic = 2 * sqrt(2.0)
        assertCloseTo(analytic, propagated, 4)
        assertTrue(abs(propagated - analytic) / analytic < 1e-5)
    }

    @Test
    fun `a mark placed at a higher zoom is a smaller uncertainty, in image pixels`() {
        // A 4000 px photograph shown 400 px wide is displayed at 0.1, so one screen pixel IS ten image
        // pixels: careful marking at that zoom is still worth only ±15 image px.
        assertCloseTo(15.0, markSigmaForDisplayScale(0.1), 12)
        assertCloseTo(1.5, markSigmaForDisplayScale(1.0), 12)
        assertCloseTo(0.375, markSigmaForDisplayScale(4.0), 12)
        // A caller that cannot say what zoom a mark was placed at gets the 1:1 fallback rather than a
        // division by zero — and never a NaN sigma, which would silently erase the whole error bar.
        assertEquals(DEFAULT_MARK_SIGMA_PX, markSigmaForDisplayScale(0.0), 0.0)
        assertEquals(DEFAULT_MARK_SIGMA_PX, markSigmaForDisplayScale(Double.NaN), 0.0)
        assertEquals(DEFAULT_MARK_SIGMA_PX, markSigmaForDisplayScale(-2.0), 0.0)
    }

    @Test
    fun `zooming in narrows the error bar on the very same marks`() {
        val reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm")
        val target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(400.0, 50.0))
        val placedFarOut = measured(
            measureBySameScale(reference, target, markSigmaPx = markSigmaForDisplayScale(0.25))
        )
        val placedZoomedIn = measured(
            measureBySameScale(reference, target, markSigmaPx = markSigmaForDisplayScale(4.0))
        )
        // The ANSWER is identical — marks are stored in image pixels, so zoom cannot move a
        // measurement — and only the doubt about it changes, by the factor the zoom changed by.
        assertEquals(placedFarOut.value, placedZoomedIn.value, 0.0)
        assertCloseTo(16.0, placedFarOut.relativeUncertainty / placedZoomedIn.relativeUncertainty, 9)
    }

    @Test
    fun `the propagator refuses when the function refuses anywhere in the neighbourhood`() {
        // A perturbation that lands on a degenerate configuration means the error bar cannot be
        // computed, and an error bar that quietly omits one of its twelve terms understates the doubt.
        assertNull(propagateUncertainty(doubleArrayOf(1.0, 2.0), 1.0) { null })
        assertNull(propagateUncertainty(doubleArrayOf(1.0, 2.0), 1.0) { c -> if (c[0] > 1.5) null else 10.0 })
    }

    // -----------------------------------------------------------------------
    // The four-point rectification
    // -----------------------------------------------------------------------

    @Test
    fun `a length measured across a known tilt comes back to a micron`() {
        // A segment whose WORLD length is √(120² + 100²) = √24400 = 156.20499351813308 mm,
        // photographed through the tilt along with the A4 sheet it lies on.
        val imaged = projectAll(tilt, a4Corners)
        val (targetFrom, targetTo) = projectAll(tilt, listOf(MeasurePoint(30.0, 40.0), MeasurePoint(150.0, 140.0)))

        val result = measured(
            measureByRectification(
                corners = imaged,
                rectangle = a4,
                target = MeasureSegment(targetFrom, targetTo),
            )
        )
        assertEquals(PhotoMeasure.METHOD_RECTIFIED, result.method)
        assertEquals("mm", result.unit)
        assertCloseTo(156.20499351813308, result.value, 6)
        assertTrue(result.uncertainty.isFinite())
        assertTrue(result.uncertainty > 0.0)
    }

    @Test
    fun `the tilt correction is reported, and it is the reason the feature exists`() {
        val imaged = projectAll(oblique, a4Corners)
        val (targetFrom, targetTo) =
            projectAll(oblique, listOf(MeasurePoint(30.0, 40.0), MeasurePoint(150.0, 140.0)))
        val result = measured(
            measureByRectification(imaged, a4, MeasureSegment(targetFrom, targetTo))
        )

        // The rectified answer is still exact, however oblique the photograph.
        assertCloseTo(156.20499351813308, result.value, 6)

        // What the SAME two marks would have said with the sheet's first edge used as a plain scale
        // bar: 108.10 mm against a true 156.20 mm. THIS IS THE WHOLE ARGUMENT FOR THE FOUR-POINT
        // METHOD — a 31% error, on a dimension that is printed on the record sheet, from a photograph
        // that looks perfectly reasonable.
        assertNotNull(result.uncorrectedValue)
        assertCloseTo(108.098357173005, result.uncorrectedValue!!, 6)
        assertCloseTo(0.30797118108483285, result.tiltCorrection!!, 9)

        // And the gentler tilt is reported as gentler, so the number means something rather than
        // always reading "large": the same object, the same marks, a less oblique camera.
        val gentle = measured(
            measureByRectification(
                corners = projectAll(tilt, a4Corners),
                rectangle = a4,
                target = MeasureSegment(
                    applyHomography(tilt, MeasurePoint(30.0, 40.0)),
                    applyHomography(tilt, MeasurePoint(150.0, 140.0)),
                ),
            )
        )
        assertCloseTo(156.20499351813308, gentle.value, 6)
        assertCloseTo(0.01592637686383975, gentle.tiltCorrection!!, 9)
    }

    @Test
    fun `at real handset resolution the rectified length is still exact`() {
        // A 12 MP frame, an A4 sheet across the middle of it, and a 156.20499351813308 mm segment on
        // the sheet. See `handset` for why this case is not redundant with the 500 px ones above.
        val imaged = projectAll(handset, a4Corners)
        for (corner in imaged) {
            assertTrue(corner.x > 0.0 && corner.x < 4000.0)
            assertTrue(corner.y > 0.0 && corner.y < 3000.0)
        }

        val (targetFrom, targetTo) =
            projectAll(handset, listOf(MeasurePoint(30.0, 40.0), MeasurePoint(150.0, 140.0)))
        val result = measured(measureByRectification(imaged, a4, MeasureSegment(targetFrom, targetTo)))
        // A nanometre on an A4 sheet. Nothing in the field needs this precision; it is asserted
        // because it is what separates a properly conditioned solve from one that is merely close.
        assertCloseTo(156.20499351813308, result.value, 9)

        // And the transform itself, on a point that took no part in the fit, to the same precision.
        val recovered = solveHomography(imaged, a4Corners)
        assertNotNull(recovered)
        val fifth = applyHomography(recovered!!, applyHomography(handset, MeasurePoint(137.5, 88.25)))
        assertCloseTo(137.5, fifth.x, 9)
        assertCloseTo(88.25, fifth.y, 9)
    }

    @Test
    fun `an untilted rectangle rectifies to the same answer the scale method gives`() {
        // The correction must not INVENT a difference where there is none: with the sheet square to
        // the sensor the two methods have to agree exactly, or the four-point path would silently move
        // every dimension a researcher bothered to be careful about.
        val corners = listOf(
            MeasurePoint(100.0, 100.0),
            MeasurePoint(520.0, 100.0),
            MeasurePoint(520.0, 694.0),
            MeasurePoint(100.0, 694.0),
        )
        val target = MeasureSegment(MeasurePoint(150.0, 200.0), MeasurePoint(350.0, 200.0))
        val rectified = measured(measureByRectification(corners, a4, target))
        val scaled = measured(
            measureBySameScale(
                reference = ScaleReference(corners[0], corners[1], a4.width, "mm"),
                target = target,
            )
        )
        assertCloseTo(100.0, rectified.value, 9)
        assertCloseTo(100.0, scaled.value, 9)
        assertCloseTo(0.0, rectified.tiltCorrection!!, 9)
    }

    @Test
    fun `collinear corners are refused with a reason and never with a number`() {
        val result = measureByRectification(
            corners = listOf(
                MeasurePoint(100.0, 100.0),
                MeasurePoint(200.0, 100.0),
                MeasurePoint(300.0, 100.0),
                MeasurePoint(400.0, 100.0),
            ),
            rectangle = a4,
            target = MeasureSegment(MeasurePoint(120.0, 200.0), MeasurePoint(260.0, 260.0)),
        )
        assertTrue(refusal(result).reason.length > 20)
        // The web spec asserts here that the refusal has no `value` PROPERTY. The sealed interface is
        // that assertion moved into the compiler: [MeasureResult.Refusal] declares no `value`, so
        // there is no cast, no default and no null that gets a caller to a number.
        assertTrue(result is MeasureResult.Refusal)
    }

    @Test
    fun `corners marked in a crossing order are refused rather than folded`() {
        // A bow-tie. The homography still solves — it is a perfectly good projective map — and it
        // produces a plausible-looking millimetre figure from a mirror-folded plane. There is nothing
        // downstream that could ever notice, so it has to be caught here.
        val imaged = projectAll(tilt, a4Corners)
        val result = measureByRectification(
            corners = listOf(imaged[0], imaged[1], imaged[3], imaged[2]),
            rectangle = a4,
            target = MeasureSegment(imaged[0], imaged[2]),
        )
        assertTrue(refusal(result).reason.contains("order"))
    }

    // -----------------------------------------------------------------------
    // Refusals, and the NaN guard
    // -----------------------------------------------------------------------

    @Test
    fun `a reference too short to measure against is refused, not scaled up`() {
        val result = measureBySameScale(
            reference = ScaleReference(
                MeasurePoint(100.0, 100.0),
                MeasurePoint(100.0 + MIN_REFERENCE_PIXELS - 1, 100.0),
                100.0,
                "mm",
            ),
            target = MeasureSegment(MeasurePoint(100.0, 300.0), MeasurePoint(900.0, 300.0)),
        )
        val reason = refusal(result).reason
        assertTrue(reason.contains("reference"))
        // The pixel count is a WHOLE NUMBER in the sentence. `39.0 pixels` is what a Kotlin Double
        // interpolates to, and it reads as a spurious precision in a sentence about being too coarse
        // to measure — the web writes `Math.round(...)`, so this side rounds and drops the fraction.
        assertTrue("the sentence must say “39 pixels”: $reason", reason.contains("39 pixels"))
    }

    @Test
    fun `exactly at the floor it is accepted - the floor is a floor, not a fence`() {
        val result = measureBySameScale(
            reference = ScaleReference(
                MeasurePoint(100.0, 100.0),
                MeasurePoint(100.0 + MIN_REFERENCE_PIXELS, 100.0),
                100.0,
                "mm",
            ),
            target = MeasureSegment(MeasurePoint(100.0, 300.0), MeasurePoint(900.0, 300.0)),
        )
        assertTrue(result is MeasureResult.Measurement)
    }

    @Test
    fun `a zero-length target is refused`() {
        val result = measureBySameScale(
            reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm"),
            target = MeasureSegment(MeasurePoint(50.0, 50.0), MeasurePoint(50.0, 50.0)),
        )
        assertTrue(result is MeasureResult.Refusal)
    }

    @Test
    fun `a reference of zero or negative real length is refused`() {
        for (length in listOf(0.0, -10.0)) {
            val result = measureBySameScale(
                reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), length, "mm"),
                target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(400.0, 50.0)),
            )
            assertTrue("a reference of $length mm must be refused", result is MeasureResult.Refusal)
        }
    }

    @Test
    fun `NaN and Infinity in, refusal out - never a finite-looking answer`() {
        val poisoned = measureBySameScale(
            reference = ScaleReference(MeasurePoint(Double.NaN, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm"),
            target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(400.0, 50.0)),
        )
        assertTrue(poisoned is MeasureResult.Refusal)

        val infinite = measureByRectification(
            corners = listOf(
                MeasurePoint(0.0, 0.0),
                MeasurePoint(Double.POSITIVE_INFINITY, 0.0),
                MeasurePoint(400.0, 500.0),
                MeasurePoint(0.0, 500.0),
            ),
            rectangle = a4,
            target = MeasureSegment(MeasurePoint(10.0, 10.0), MeasurePoint(20.0, 20.0)),
        )
        assertTrue(infinite is MeasureResult.Refusal)
    }

    @Test
    fun `every field of a successful measurement is a finite number`() {
        // Written out rather than reflected over, which is what the web's Object.entries loop does:
        // reflection on Android is the one thing that behaves differently under R8, and a test that
        // stops seeing a field silently stops testing it.
        val result = measured(
            measureBySameScale(
                reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm"),
                target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(400.0, 50.0)),
                markSigmaPx = DEFAULT_MARK_SIGMA_PX,
            )
        )
        assertTrue("value", result.value.isFinite())
        assertTrue("uncertainty", result.uncertainty.isFinite())
        assertTrue("relativeUncertainty", result.relativeUncertainty.isFinite())
        assertTrue("referencePixels", result.referencePixels.isFinite())
        assertTrue("targetPixels", result.targetPixels.isFinite())
        // SCALE carries neither of the rectified-only fields at all, which is the same statement the
        // web makes by leaving them `undefined`.
        assertNull(result.uncorrectedValue)
        assertNull(result.tiltCorrection)
    }

    @Test
    fun `a corner count other than four is refused`() {
        // TypeScript's four-tuple is a compile-time guarantee a List cannot make, so the check the web
        // never needed is a refusal here rather than an IndexOutOfBoundsException on a handset.
        for (count in listOf(0, 1, 3, 5)) {
            val corners = projectAll(tilt, a4Corners).let { imaged ->
                List(count) { imaged[it % 4] }
            }
            val result = measureByRectification(
                corners = corners,
                rectangle = a4,
                target = MeasureSegment(MeasurePoint(400.0, 300.0), MeasurePoint(500.0, 400.0)),
            )
            assertTrue("$count corners must be refused", result is MeasureResult.Refusal)
        }
    }

    @Test
    fun `a unit the module cannot convert is refused by name`() {
        // Reachable in Kotlin in a way it is not on the web, because a `RecordDimension`'s declared
        // unit arrives as a String. A column declaring `unit = "hands"` must be refused, never assumed
        // to be centimetres.
        val result = measureBySameScale(
            reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "hands"),
            target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(400.0, 50.0)),
        )
        assertTrue(refusal(result).reason.contains("hands"))

        val rectified = measureByRectification(
            corners = projectAll(tilt, a4Corners),
            rectangle = KnownRectangle(210.0, 297.0, "furlong"),
            target = MeasureSegment(MeasurePoint(400.0, 300.0), MeasurePoint(500.0, 400.0)),
        )
        assertTrue(refusal(rectified).reason.contains("furlong"))
    }

    // -----------------------------------------------------------------------
    // Proposing at an honest precision
    // -----------------------------------------------------------------------

    @Test
    fun `a value is rounded to the decimal place its own error bar reaches`() {
        // 200 mm ± 3.35 mm: the doubt is in the units column, so the answer is quoted there too.
        assertEquals(RoundedValue(200.0, 0), roundToUncertainty(199.847123, 3.3541))
        // ± 0.335 → one decimal.
        assertEquals(RoundedValue(20.0, 1), roundToUncertainty(19.98471, 0.335))
        // ± 0.0335 → two.
        assertEquals(RoundedValue(19.98, 2), roundToUncertainty(19.98471, 0.0335))
        // ± 0.00335 → three.
        assertEquals(RoundedValue(19.985, 3), roundToUncertainty(19.98471, 0.00335))
    }

    @Test
    fun `an absurdly narrow bar is capped rather than quoting floating-point noise`() {
        val rounded = roundToUncertainty(19.984712345678, 1e-9)
        assertEquals(4, rounded.decimals)
        assertCloseTo(19.9847, rounded.value, 12)
    }

    @Test
    fun `a missing or impossible error bar never produces NaN`() {
        assertEquals(12.3456, roundToUncertainty(12.3456, 0.0).value, 0.0)
        assertEquals(12.3456, roundToUncertainty(12.3456, -1.0).value, 0.0)
        assertTrue(roundToUncertainty(Double.NaN, 1.0).value.isNaN())
        assertEquals(0, roundToUncertainty(Double.NaN, 1.0).decimals)
    }

    @Test
    fun `the rounded proposal survives the unit conversion into a record column`() {
        // The whole chain a proposal actually travels: measured in mm, written into a `unit = "cm"`
        // column.
        val result = measured(
            measureBySameScale(
                reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm"),
                target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(400.0, 50.0)),
                markSigmaPx = 3 / sqrt(2.0),
            )
        )
        val asCm = convertLength(result.value, result.unit, "cm")
        val doubtCm = convertLength(result.uncertainty, result.unit, "cm")
        assertNotNull(asCm)
        assertNotNull(doubtCm)
        // 200 mm ± 3.354 mm becomes 20 cm ± 0.335 cm, quoted to one decimal.
        assertCloseTo(20.0, asCm!!, 12)
        assertCloseTo(0.33541019662496847, doubtCm!!, 12)
        assertEquals(RoundedValue(20.0, 1), roundToUncertainty(asCm, doubtCm))
    }

    /**
     * THE ONE CASE THAT IS NOT IN THE WEB SPEC AND IS THE REASON THIS PORT COULD HAVE SHIPPED WRONG.
     *
     * `roundToUncertainty` rounds through JavaScript's `Math.round` — ties toward positive infinity —
     * because the web's `photoMeasure` module is the authority for this file and there is no
     * server-side counterpart to it. The JVM reflexes are `BigDecimal` half-to-EVEN and letting
     * `String.format` do the rounding, and on an exact binary tie each of them disagrees with the
     * browser by one in the last quoted place, in a different direction.
     *
     * The tie is not exotic. A 100 mm reference marked 200 px long against a 401 px target is exactly
     * 200.5 mm, and its own error bar puts the quote in the units column — so the browser proposes
     * 201 mm and a half-to-even port would propose 200 mm, for the same photograph and the same marks,
     * into a field that is printed on the record sheet.
     */
    @Test
    fun `an exact tie rounds the way the browser rounds it, not the way the JVM reflexes do`() {
        val measurement = measured(
            measureBySameScale(
                reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm"),
                target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(401.0, 50.0)),
            )
        )
        // Exactly representable, so nothing about the arithmetic that produced it is in doubt.
        assertEquals(200.5, measurement.value, 0.0)

        val proposal = roundToUncertainty(measurement.value, measurement.uncertainty)
        assertEquals(0, proposal.decimals)
        assertEquals("Math.round(200.5) is 201", 201.0, proposal.value, 0.0)
        // Named rather than described, so the divergence is a fact in the suite rather than a claim in
        // a comment: this is what the reflex would have proposed instead.
        assertEquals(
            "BigDecimal HALF_EVEN makes 200.5 into 200",
            200.0,
            java.math.BigDecimal(200.5).setScale(0, java.math.RoundingMode.HALF_EVEN).toDouble(),
            0.0,
        )

        // The same disagreement two decimals in, where a hundredth of an inch is being quoted.
        assertEquals(RoundedValue(0.13, 2), roundToUncertainty(0.125, 0.0335))
        assertEquals(
            "BigDecimal HALF_EVEN makes 0.125 into 0.12",
            0.12,
            java.math.BigDecimal(0.125).setScale(2, java.math.RoundingMode.HALF_EVEN).toDouble(),
            0.0,
        )

        // AND THE FORMATTER IS NOT ALLOWED TO ROUND EITHER, which is the other half of the same rule.
        // `ui/RecordMeasureField.formatRounded` prints an ALREADY-rounded value, so `%.Nf` has nothing
        // left to decide. Handed a raw value it decides differently: HALF_UP on the decimal expansion
        // sends a negative tie away from zero, where `Math.round` sends it toward positive infinity.
        assertEquals("-3", "%.0f".format(java.util.Locale.ROOT, PhotoMeasure.jsRound(-3.5)))
        assertEquals("-4", "%.0f".format(java.util.Locale.ROOT, -3.5))
    }

    @Test
    fun `a negative value rounded away to nothing keeps its sign`() {
        // JavaScript's `Math.round(-0.4)` is `-0`, and `-0 / 1` is `-0`. Kotlin's obvious spelling
        // produces `+0.0`, which is a different double and prints differently everywhere that reaches
        // for the raw value. Asserted on the BITS, because -0.0 == 0.0 is true and every ordinary
        // assertion would pass either way.
        val rounded = roundToUncertainty(-0.4, 1.0)
        assertEquals(0, rounded.decimals)
        assertEquals(
            "a value that rounds away to nothing must keep the sign of the marks",
            java.lang.Double.doubleToRawLongBits(-0.0),
            java.lang.Double.doubleToRawLongBits(rounded.value),
        )
    }

    // -----------------------------------------------------------------------
    // Units
    // -----------------------------------------------------------------------

    @Test
    fun `converts between the units a record dimension can declare`() {
        assertCloseTo(100.0, convertLength(1.0, "m", "cm")!!, 12)
        assertCloseTo(25.0, convertLength(250.0, "mm", "cm")!!, 12)
        assertCloseTo(25.4, convertLength(1.0, "in", "mm")!!, 12)
        assertCloseTo(12.0, convertLength(30.48, "cm", "in")!!, 12)
        assertEquals(7.0, convertLength(7.0, "cm", "cm")!!, 0.0)
    }

    @Test
    fun `an unknown unit is refused rather than assumed`() {
        // A column declaring `unit = "hands"` must not have a centimetre figure written into it.
        assertNull(convertLength(1.0, "cm", "hands"))
        assertNull(convertLength(1.0, "furlong", "cm"))
        assertNull(convertLength(Double.NaN, "cm", "mm"))
    }

    @Test
    fun `the unit table is the membership test and holds only lengths`() {
        // `ui/RecordMeasureField.measurableDimensions` asks this map whether a record column's
        // declared unit is a length before offering that column as a destination. A unit added here is
        // a column this module may write into, so the table is pinned rather than merely used.
        assertEquals(setOf("mm", "cm", "m", "in"), LENGTH_UNITS.keys)
        assertEquals(1.0, LENGTH_UNITS["mm"]!!, 0.0)
        assertEquals(10.0, LENGTH_UNITS["cm"]!!, 0.0)
        assertEquals(1000.0, LENGTH_UNITS["m"]!!, 0.0)
        assertEquals(25.4, LENGTH_UNITS["in"]!!, 0.0)
    }
}
