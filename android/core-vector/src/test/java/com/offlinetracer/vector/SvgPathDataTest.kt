package com.offlinetracer.vector

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgPathDataTest {

    // -----------------------------------------------------------------------------------------
    // Round trip
    // -----------------------------------------------------------------------------------------

    @Test
    fun toDParseToDIsByteIdentical() {
        val path = VecPath(
            VecPoint(10f, 20.5f),
            listOf(
                VecSeg.Line(VecPoint(30f, 20.5f)),
                VecSeg.Line(VecPoint(30f, -3.25f)),
                VecSeg.Cubic(VecPoint(40f, -3.25f), VecPoint(50f, 6.75f), VecPoint(50f, 16.75f)),
                VecSeg.Cubic(VecPoint(50f, 26f), VecPoint(40f, 30f), VecPoint(30f, 30f)),
                VecSeg.Quad(VecPoint(15f, 30f), VecPoint(10f, 20.5f)),
            ),
            closed = true,
        )
        val first = SvgPathData.toD(path, 2)
        val reparsed = SvgPathData.parse(first)
        assertEquals(1, reparsed.size)
        val second = SvgPathData.toD(reparsed[0], 2)
        assertEquals(first, second)
        assertTrue(reparsed[0].closed)
    }

    // The three expectations below are the compact canonical `d` form of ALGORITHMS.md §10: no space
    // after a command letter, repeated letters elided, one space between numbers, nothing before Z.
    // They used to assert the spaced form ("M 1 0 L 2.5 3.14"), which is equally valid SVG but is not
    // what the TypeScript engine writes — and §14 compares this string exactly, so exactly one of the
    // two spellings can be right.

    @Test
    fun formattingStripsTrailingZerosAndNegativeZero() {
        val path = VecPath(VecPoint(1f, -0.001f), listOf(VecSeg.Line(VecPoint(2.5f, 3.140f))))
        assertEquals("M1 0L2.5 3.14", SvgPathData.toD(path, 2))
    }

    @Test
    fun openPathHasNoClosepath() {
        val path = VecPath(VecPoint(0f, 0f), listOf(VecSeg.Line(VecPoint(5f, 5f))), closed = false)
        assertEquals("M0 0L5 5", SvgPathData.toD(path, 2))
    }

    @Test
    fun repeatedCommandLettersAreOmitted() {
        val path = VecPath(
            VecPoint(0f, 0f),
            listOf(VecSeg.Line(VecPoint(1f, 0f)), VecSeg.Line(VecPoint(2f, 0f)), VecSeg.Line(VecPoint(3f, 0f))),
        )
        assertEquals("M0 0L1 0 2 0 3 0", SvgPathData.toD(path, 2))
    }

    // -----------------------------------------------------------------------------------------
    // Grammar
    // -----------------------------------------------------------------------------------------

    @Test
    fun parsesRunTogetherNumbersAndExponents() {
        val paths = SvgPathData.parse("M10-5L1e2 3")
        assertEquals(1, paths.size)
        val p = paths[0]
        assertEquals(10f, p.start.x, 1e-4f)
        assertEquals(-5f, p.start.y, 1e-4f)
        assertEquals(1, p.segments.size)
        val line = p.segments[0] as VecSeg.Line
        assertEquals(100f, line.to.x, 1e-4f)
        assertEquals(3f, line.to.y, 1e-4f)
    }

    @Test
    fun implicitRepeatsAfterMovetoBecomeLines() {
        val p = SvgPathData.parse("M 1 1 2 2 3 3")[0]
        assertEquals(2, p.segments.size)
        assertEquals(2f, (p.segments[0] as VecSeg.Line).to.x, 1e-4f)
        assertEquals(3f, (p.segments[1] as VecSeg.Line).to.y, 1e-4f)
    }

    @Test
    fun relativeCommandsAccumulate() {
        val p = SvgPathData.parse("m10 10 l5 0 5 0 v5 h-5")[0]
        assertEquals(10f, p.start.x, 1e-4f)
        val anchors = p.points()
        assertEquals(VecPoint(15f, 10f), anchors[1])
        assertEquals(VecPoint(20f, 10f), anchors[2])
        assertEquals(VecPoint(20f, 15f), anchors[3])
        assertEquals(VecPoint(15f, 15f), anchors[4])
    }

    @Test
    fun smoothCubicReflectsOnlyAfterACubic() {
        val afterCubic = SvgPathData.parse("M0 0 C 10 0 20 0 30 0 S 50 10 60 0")[0]
        val reflected = afterCubic.segments[1] as VecSeg.Cubic
        // c2 of the previous cubic is (20,0); reflected about the current point (30,0) is (40,0).
        assertEquals(40f, reflected.c1.x, 1e-3f)
        assertEquals(0f, reflected.c1.y, 1e-3f)

        val afterLine = SvgPathData.parse("M0 0 L 10 0 S 20 10 30 0")[0]
        val notReflected = afterLine.segments[1] as VecSeg.Cubic
        // The previous command was not a cubic, so the first control point is the current point.
        assertEquals(10f, notReflected.c1.x, 1e-3f)
        assertEquals(0f, notReflected.c1.y, 1e-3f)
    }

    @Test
    fun smoothQuadReflectsOnlyAfterAQuad() {
        val afterQuad = SvgPathData.parse("M0 0 Q 10 10 20 0 T 40 0")[0]
        val reflected = afterQuad.segments[1] as VecSeg.Quad
        // Control (10,10) reflected about the current point (20,0) is (30,-10).
        assertEquals(30f, reflected.c.x, 1e-3f)
        assertEquals(-10f, reflected.c.y, 1e-3f)

        val afterLine = SvgPathData.parse("M0 0 L10 0 T 30 0")[0]
        val notReflected = afterLine.segments[1] as VecSeg.Quad
        assertEquals(10f, notReflected.c.x, 1e-3f)
        assertEquals(0f, notReflected.c.y, 1e-3f)
    }

    @Test
    fun closepathStartsANewSubpathAtTheSubpathOrigin() {
        val paths = SvgPathData.parse("M0 0 L10 0 Z M20 0 L30 0")
        assertEquals(2, paths.size)
        assertTrue(paths[0].closed)
        assertTrue(!paths[1].closed)
        assertEquals(20f, paths[1].start.x, 1e-4f)
    }

    @Test
    fun closepathWithoutAFollowingMovetoDoesNotEmitAnEmptyPath() {
        val paths = SvgPathData.parse("M0 0 L10 0 L10 10 Z")
        assertEquals(1, paths.size)
        assertTrue(paths[0].closed)
    }

    // -----------------------------------------------------------------------------------------
    // Arcs
    // -----------------------------------------------------------------------------------------

    @Test
    fun arcReachesItsEndpoint() {
        val p = SvgPathData.parse("M0 0 A 10 10 0 0 1 20 0")[0]
        val anchors = p.points()
        val end = anchors[anchors.size - 1]
        assertEquals(20f, end.x, 1e-2f)
        assertEquals(0f, end.y, 1e-2f)
        // A semicircle needs two cubics; one would be visibly wrong.
        assertEquals(2, p.segments.size)
    }

    @Test
    fun arcBulgesToTheCorrectSide() {
        val sweepUp = SvgPathData.parse("M0 0 A 10 10 0 0 0 20 0")[0].points()
        val sweepDown = SvgPathData.parse("M0 0 A 10 10 0 0 1 20 0")[0].points()
        val midUp = sweepUp[1].y
        val midDown = sweepDown[1].y
        assertTrue(midUp * midDown < 0f, "sweep flag did not change the side of the bulge")
    }

    @Test
    fun degenerateArcs() {
        assertTrue(SvgPathData.arcToCubics(5f, 5f, 10f, 10f, 0f, false, true, 5f, 5f).isEmpty())
        val zeroRadius = SvgPathData.arcToCubics(0f, 0f, 0f, 0f, 0f, false, true, 10f, 0f)
        assertEquals(1, zeroRadius.size)
        assertEquals(10f, zeroRadius[0].to.x, 1e-4f)
        assertTrue(abs(zeroRadius[0].c1.y) < 1e-4f, "a zero-radius arc must be a straight line")
    }

    @Test
    fun undersizedRadiiAreScaledUpRatherThanRejected() {
        // The endpoints are 20 apart but the radii only span 10; F.6.6 says scale, not fail.
        val cubics = SvgPathData.arcToCubics(0f, 0f, 5f, 5f, 0f, false, true, 20f, 0f)
        assertTrue(cubics.isNotEmpty())
        assertEquals(20f, cubics[cubics.size - 1].to.x, 1e-2f)
    }

    // -----------------------------------------------------------------------------------------
    // Robustness
    // -----------------------------------------------------------------------------------------

    @Test
    fun malformedInputNeverThrows() {
        assertTrue(SvgPathData.parse("").isEmpty())
        SvgPathData.parse("garbage")
        SvgPathData.parse("M")
        SvgPathData.parse("M 1")
        SvgPathData.parse("L 1 2 3")
        SvgPathData.parse("M0 0 A")
        SvgPathData.parse("M0 0 C 1 2")
        SvgPathData.parse("@@@ ### M 1 1 L 2 2 %%%")
        SvgPathData.parse("Z Z Z")
    }

    @Test
    fun nonFiniteCoordinatesAreClampedNotEmitted() {
        val path = VecPath(
            VecPoint(Float.NaN, 0f),
            listOf(VecSeg.Line(VecPoint(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))),
        )
        val d = SvgPathData.toD(path, 2)
        assertTrue(!d.contains("NaN"), "emitted NaN: $d")
        assertTrue(!d.contains("Infinity"), "emitted Infinity: $d")
        assertTrue(d.startsWith("M0 0"))
    }
}
