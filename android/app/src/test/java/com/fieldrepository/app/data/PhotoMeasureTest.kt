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

class PhotoMeasureTest {

    private val tilt = Homography(1.2, 0.15, 300.0, 0.05, 1.1, 250.0, 0.0004, 0.0002, 1.0)

    private val oblique = Homography(1.2, 0.15, 300.0, 0.05, 1.1, 250.0, 0.0012, 0.0009, 1.0)

    private val handset = Homography(9.6, 1.2, 700.0, 0.4, 8.8, 500.0, 0.00035, 0.00018, 1.0)

    private val a4 = KnownRectangle(width = 210.0, height = 297.0, unit = "mm")

    private val a4Corners = listOf(
        MeasurePoint(0.0, 0.0),
        MeasurePoint(210.0, 0.0),
        MeasurePoint(210.0, 297.0),
        MeasurePoint(0.0, 297.0),
    )

    private fun projectAll(h: Homography, points: List<MeasurePoint>): List<MeasurePoint> =
        points.map { applyHomography(h, it) }

    private fun assertCloseTo(expected: Double, actual: Double, digits: Int, what: String = "") {
        assertEquals(what, expected, actual, 0.5 * 10.0.pow(-digits))
    }

    private fun measured(result: MeasureResult): MeasureResult.Measurement = when (result) {
        is MeasureResult.Measurement -> result
        is MeasureResult.Refusal -> throw AssertionError("expected a measurement, got: ${result.reason}")
    }

    private fun refusal(result: MeasureResult): MeasureResult.Refusal = when (result) {
        is MeasureResult.Refusal -> result
        is MeasureResult.Measurement -> throw AssertionError("expected a refusal, got ${result.value} ${result.unit}")
    }

    @Test
    fun `solves a small system exactly and refuses a singular one`() {
        val solved = solveLinearSystem(
            listOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, -1.0)),
            doubleArrayOf(5.0, 1.0),
        )
        assertNotNull(solved)
        assertCloseTo(2.0, solved!![0], 12)
        assertCloseTo(1.0, solved[1], 12)

        assertNull(
            solveLinearSystem(
                listOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0)),
                doubleArrayOf(3.0, 6.0),
            )
        )
    }

    @Test
    fun `partial pivoting survives a zero in the first pivot position`() {
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
        val row = doubleArrayOf(2.0, 1.0)
        val rhs = doubleArrayOf(5.0, 1.0)
        solveLinearSystem(listOf(row, doubleArrayOf(1.0, -1.0)), rhs)
        assertEquals(2.0, row[0], 0.0)
        assertEquals(1.0, row[1], 0.0)
        assertEquals(5.0, rhs[0], 0.0)
    }

    @Test
    fun `applyHomography divides by the third row`() {
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

        for (index in 0 until 4) {
            val back = applyHomography(recovered, imaged[index])
            assertCloseTo(a4Corners[index].x, back.x, 9)
            assertCloseTo(a4Corners[index].y, back.y, 9)
        }

        val fifth = MeasurePoint(137.5, 88.25)
        val roundTrip = applyHomography(recovered, applyHomography(tilt, fifth))
        assertCloseTo(137.5, roundTrip.x, 9)
        assertCloseTo(88.25, roundTrip.y, 9)

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
        assertCloseTo(126.49110640673518, forward.value, 12)
        assertCloseTo(forward.value, reversed.value, 12)
    }

    @Test
    fun `a diagonal reference measures a diagonal target`() {
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

    @Test
    fun `a distance between two independently placed marks is root two times as uncertain as one`() {
        assertCloseTo(2.8284271247461903, distanceSigma(2.0), 12)
        assertCloseTo(4.242640687119285, distanceSigma(3.0), 12)
        assertEquals(0.0, distanceSigma(0.0), 0.0)
    }

    @Test
    fun `the brief's case - a 3 px error on a 200 px reference is 1_5 percent of the reference`() {
        val markSigmaPx = 3 / sqrt(2.0)
        assertCloseTo(3.0, distanceSigma(markSigmaPx), 12)

        val result = measured(
            measureBySameScale(
                reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm"),
                target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(400.0, 50.0)),
                markSigmaPx = markSigmaPx,
            )
        )
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
        assertEquals(200.0, short.value, 0.0)
        assertCloseTo(0.06046693311223912, short.relativeUncertainty, 12)
        assertCloseTo(3.605551275463989, short.relativeUncertainty / long.relativeUncertainty, 9)
    }

    @Test
    fun `a known reference length that is itself uncertain widens the bar further`() {
        val reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm")
        val target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(400.0, 50.0))
        val markSigmaPx = 3 / sqrt(2.0)
        val exact = measured(measureBySameScale(reference, target, markSigmaPx))
        val sloppy = measured(measureBySameScale(reference, target, markSigmaPx, referenceLengthSigma = 2.0))
        assertCloseTo(0.026100766272276376, sloppy.relativeUncertainty, 12)
        assertTrue(sloppy.relativeUncertainty > exact.relativeUncertainty)
    }

    @Test
    fun `the numerical propagation reproduces the closed form it replaces`() {
        val propagatedOrNull = propagateUncertainty(doubleArrayOf(0.0, 0.0, 300.0, 400.0), 2.0) { c ->
            distanceBetween(MeasurePoint(c[0], c[1]), MeasurePoint(c[2], c[3]))
        }
        assertNotNull(propagatedOrNull)
        val propagated: Double = propagatedOrNull!!

        val analytic = 2 * sqrt(2.0)
        assertCloseTo(analytic, propagated, 4)
        assertTrue(abs(propagated - analytic) / analytic < 1e-5)
    }

    @Test
    fun `a mark placed at a higher zoom is a smaller uncertainty, in image pixels`() {
        assertCloseTo(15.0, markSigmaForDisplayScale(0.1), 12)
        assertCloseTo(1.5, markSigmaForDisplayScale(1.0), 12)
        assertCloseTo(0.375, markSigmaForDisplayScale(4.0), 12)
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
        assertEquals(placedFarOut.value, placedZoomedIn.value, 0.0)
        assertCloseTo(16.0, placedFarOut.relativeUncertainty / placedZoomedIn.relativeUncertainty, 9)
    }

    @Test
    fun `the propagator refuses when the function refuses anywhere in the neighbourhood`() {
        assertNull(propagateUncertainty(doubleArrayOf(1.0, 2.0), 1.0) { null })
        assertNull(propagateUncertainty(doubleArrayOf(1.0, 2.0), 1.0) { c -> if (c[0] > 1.5) null else 10.0 })
    }

    @Test
    fun `a length measured across a known tilt comes back to a micron`() {
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

        assertCloseTo(156.20499351813308, result.value, 6)

        assertNotNull(result.uncorrectedValue)
        assertCloseTo(108.098357173005, result.uncorrectedValue!!, 6)
        assertCloseTo(0.30797118108483285, result.tiltCorrection!!, 9)

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
        val imaged = projectAll(handset, a4Corners)
        for (corner in imaged) {
            assertTrue(corner.x > 0.0 && corner.x < 4000.0)
            assertTrue(corner.y > 0.0 && corner.y < 3000.0)
        }

        val (targetFrom, targetTo) =
            projectAll(handset, listOf(MeasurePoint(30.0, 40.0), MeasurePoint(150.0, 140.0)))
        val result = measured(measureByRectification(imaged, a4, MeasureSegment(targetFrom, targetTo)))
        assertCloseTo(156.20499351813308, result.value, 9)

        val recovered = solveHomography(imaged, a4Corners)
        assertNotNull(recovered)
        val fifth = applyHomography(recovered!!, applyHomography(handset, MeasurePoint(137.5, 88.25)))
        assertCloseTo(137.5, fifth.x, 9)
        assertCloseTo(88.25, fifth.y, 9)
    }

    @Test
    fun `an untilted rectangle rectifies to the same answer the scale method gives`() {
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
        assertTrue(result is MeasureResult.Refusal)
    }

    @Test
    fun `corners marked in a crossing order are refused rather than folded`() {
        val imaged = projectAll(tilt, a4Corners)
        val result = measureByRectification(
            corners = listOf(imaged[0], imaged[1], imaged[3], imaged[2]),
            rectangle = a4,
            target = MeasureSegment(imaged[0], imaged[2]),
        )
        assertTrue(refusal(result).reason.contains("order"))
    }

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
        assertNull(result.uncorrectedValue)
        assertNull(result.tiltCorrection)
    }

    @Test
    fun `a corner count other than four is refused`() {
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

    @Test
    fun `a value is rounded to the decimal place its own error bar reaches`() {
        assertEquals(RoundedValue(200.0, 0), roundToUncertainty(199.847123, 3.3541))
        assertEquals(RoundedValue(20.0, 1), roundToUncertainty(19.98471, 0.335))
        assertEquals(RoundedValue(19.98, 2), roundToUncertainty(19.98471, 0.0335))
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
        assertCloseTo(20.0, asCm!!, 12)
        assertCloseTo(0.33541019662496847, doubtCm!!, 12)
        assertEquals(RoundedValue(20.0, 1), roundToUncertainty(asCm, doubtCm))
    }

    @Test
    fun `an exact tie rounds the way the browser rounds it, not the way the JVM reflexes do`() {
        val measurement = measured(
            measureBySameScale(
                reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm"),
                target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(401.0, 50.0)),
            )
        )
        assertEquals(200.5, measurement.value, 0.0)

        val proposal = roundToUncertainty(measurement.value, measurement.uncertainty)
        assertEquals(0, proposal.decimals)
        assertEquals("Math.round(200.5) is 201", 201.0, proposal.value, 0.0)
        assertEquals(
            "BigDecimal HALF_EVEN makes 200.5 into 200",
            200.0,
            java.math.BigDecimal(200.5).setScale(0, java.math.RoundingMode.HALF_EVEN).toDouble(),
            0.0,
        )

        assertEquals(RoundedValue(0.13, 2), roundToUncertainty(0.125, 0.0335))
        assertEquals(
            "BigDecimal HALF_EVEN makes 0.125 into 0.12",
            0.12,
            java.math.BigDecimal(0.125).setScale(2, java.math.RoundingMode.HALF_EVEN).toDouble(),
            0.0,
        )

        assertEquals("-3", "%.0f".format(java.util.Locale.ROOT, PhotoMeasure.jsRound(-3.5)))
        assertEquals("-4", "%.0f".format(java.util.Locale.ROOT, -3.5))
    }

    @Test
    fun `a negative value rounded away to nothing keeps its sign`() {
        val rounded = roundToUncertainty(-0.4, 1.0)
        assertEquals(0, rounded.decimals)
        assertEquals(
            "a value that rounds away to nothing must keep the sign of the marks",
            java.lang.Double.doubleToRawLongBits(-0.0),
            java.lang.Double.doubleToRawLongBits(rounded.value),
        )
    }

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
        assertNull(convertLength(1.0, "cm", "hands"))
        assertNull(convertLength(1.0, "furlong", "cm"))
        assertNull(convertLength(Double.NaN, "cm", "mm"))
    }

    @Test
    fun `the unit table is the membership test and holds only lengths`() {
        assertEquals(setOf("mm", "cm", "m", "in"), LENGTH_UNITS.keys)
        assertEquals(1.0, LENGTH_UNITS["mm"]!!, 0.0)
        assertEquals(10.0, LENGTH_UNITS["cm"]!!, 0.0)
        assertEquals(1000.0, LENGTH_UNITS["m"]!!, 0.0)
        assertEquals(25.4, LENGTH_UNITS["in"]!!, 0.0)
    }
}
