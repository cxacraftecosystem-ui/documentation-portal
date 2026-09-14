package com.offlinetracer.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Skeletonisation (ALGORITHMS §9). Two properties define a correct thinning and both are tested
 * here: the result is one pixel wide, and running it again changes nothing. The second is the one
 * that catches in-place deletion — deleting inside a sub-iteration makes the result scan-order
 * dependent, so it keeps changing on every re-run and the Kotlin and TS ports diverge.
 */
class ThinningTest {

    private fun filledRect(w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Mask {
        val m = Mask(w, h)
        for (y in y0..y1) for (x in x0..x1) m[x, y] = true
        return m
    }

    private fun assertNoTwoByTwoBlock(m: Mask, message: String) {
        for (y in 0 until m.height - 1) for (x in 0 until m.width - 1) {
            if (m[x, y] && m[x + 1, y] && m[x, y + 1] && m[x + 1, y + 1]) {
                throw AssertionError("$message: solid 2x2 block at ($x, $y)")
            }
        }
    }

    private fun assertSame(a: Mask, b: Mask, message: String) {
        for (i in a.data.indices) {
            if (a.data[i] != b.data[i]) {
                throw AssertionError("$message: differs at (${i % a.width}, ${i / a.width})")
            }
        }
    }

    @Test
    fun zhangSuenThinsAWideRectangleToASingleColumn() {
        // 9 px wide and tall enough that the diagonal branches into the corners cannot reach the
        // rows being checked.
        val m = filledRect(19, 41, 5, 5, 13, 35)
        val s = Thinning.zhangSuen(m)
        assertTrue(s.countTrue() > 0, "thinning must not erase the shape")
        for (y in 16..24) {
            var inRow = 0
            for (x in 0 until 19) if (s[x, y]) inRow++
            assertEquals(1, inRow, "row $y of the skeleton must be exactly one pixel wide")
        }
        assertNoTwoByTwoBlock(s, "zhangSuen")
    }

    @Test
    fun zhangSuenIsIdempotent() {
        val m = filledRect(19, 41, 5, 5, 13, 35)
        val once = Thinning.zhangSuen(m)
        val twice = Thinning.zhangSuen(once)
        assertSame(once, twice, "thinning an already-thin skeleton must be a no-op")
    }

    @Test
    fun zhangSuenPreservesConnectivity() {
        val m = filledRect(30, 20, 4, 4, 25, 15)
        val s = Thinning.zhangSuen(m)
        assertEquals(1, Components.label(s, 8).count, "a connected blob must thin to a connected skeleton")
    }

    @Test
    fun zhangSuenKeepsAOnePixelLineUnchanged() {
        val m = Mask(15, 5)
        for (x in 2..12) m[x, 2] = true
        val s = Thinning.zhangSuen(m)
        assertSame(m, s, "an already-thin line has nothing to delete")
    }

    @Test
    fun guoHallAlsoThinsAndIsIdempotent() {
        val m = filledRect(19, 41, 5, 5, 13, 35)
        val once = Thinning.guoHall(m)
        assertTrue(once.countTrue() > 0)
        assertTrue(once.countTrue() < m.countTrue() / 4, "the skeleton must be much smaller than the blob")
        assertSame(once, Thinning.guoHall(once), "guoHall must be idempotent")
        assertNoTwoByTwoBlock(once, "guoHall")
    }

    @Test
    fun neighbourCountMatchesTheEightNeighbourhood() {
        val m = Mask(5, 5)
        assertEquals(0, Thinning.neighbourCount(m, 2, 2))
        m[2, 1] = true
        assertEquals(1, Thinning.neighbourCount(m, 2, 2))
        m[1, 1] = true
        m[3, 3] = true
        assertEquals(3, Thinning.neighbourCount(m, 2, 2))
        // The centre pixel itself is never counted.
        m[2, 2] = true
        assertEquals(3, Thinning.neighbourCount(m, 2, 2))
    }

    @Test
    fun neighbourCountTreatsOutOfBoundsAsBackground() {
        val m = Mask(3, 3).fill(true)
        assertEquals(3, Thinning.neighbourCount(m, 0, 0))
        assertEquals(8, Thinning.neighbourCount(m, 1, 1))
    }

    @Test
    fun transitionsCountsZeroToOneRunsAroundTheRing() {
        val m = Mask(5, 5)
        m[2, 2] = true
        // A single neighbour due north: exactly one 0 -> 1 step in P2..P9,P2.
        m[2, 1] = true
        assertEquals(1, Thinning.transitions(m, 2, 2))

        // A plus: north, east, south, west, each isolated from the next.
        m[3, 2] = true
        m[2, 3] = true
        m[1, 2] = true
        assertEquals(4, Thinning.transitions(m, 2, 2))

        // Filling the diagonals surrounds the centre completely, and a fully surrounded pixel has
        // ZERO 0 -> 1 transitions: every term of the sum tests `!p(i) && p(i+1)`, and with all eight
        // neighbours set there is no `!p(i)` anywhere in the ring to open a run.
        //
        // Worth stating plainly because "one connected run of neighbours" and "one 0 -> 1
        // transition" are not the same quantity, and reading A(P1) as the former is a real
        // Zhang-Suen implementation trap. Thinning never deletes such a pixel anyway — condition
        // (a) requires 2 <= B(P1) <= 6 and here B is 8 — so A is not even consulted.
        m[3, 1] = true
        m[3, 3] = true
        m[1, 3] = true
        m[1, 1] = true
        assertEquals(0, Thinning.transitions(m, 2, 2))
    }

    @Test
    fun endpointsAndJunctionsAreFoundOnAT() {
        val m = Mask(11, 11)
        for (x in 2..8) m[x, 5] = true
        for (y in 6..8) m[5, y] = true
        val ends = Thinning.endpoints(m)
        assertEquals(3, ends.size, "a T has three degree-1 pixels, got ${ends.toList()}")
        assertTrue(ends.contains(5 * 11 + 2))
        assertTrue(ends.contains(5 * 11 + 8))
        assertTrue(ends.contains(8 * 11 + 5))

        // The stem's neighbours on the bar are diagonally adjacent to the stem, so a degree>=3 test
        // legitimately flags more than the single crossing pixel; what must hold is that the actual
        // crossing is in the set and that a plain line has none at all.
        val junctions = Thinning.junctions(m)
        assertTrue(junctions.contains(5 * 11 + 5), "the crossing pixel must be a junction")
    }

    @Test
    fun aStraightLineHasTwoEndpointsAndNoJunctions() {
        val m = Mask(12, 3)
        for (x in 1..10) m[x, 1] = true
        assertEquals(2, Thinning.endpoints(m).size)
        assertEquals(0, Thinning.junctions(m).size)
    }

    @Test
    fun pruneSpursRemovesShortBranchesAndKeepsTheTrunk() {
        val m = Mask(24, 11)
        for (x in 2..21) m[x, 5] = true
        // A two pixel hair hanging off the middle of the trunk.
        m[12, 6] = true
        m[12, 7] = true
        val pruned = Thinning.pruneSpurs(m, 4)
        assertFalse(pruned[12, 7], "the hair must go")
        assertFalse(pruned[12, 6], "the hair must go")
        for (x in 4..19) assertTrue(pruned[x, 5], "the trunk must survive at x=$x")
    }

    @Test
    fun pruneSpursLeavesALongBranchAlone() {
        val m = Mask(24, 15)
        for (x in 2..21) m[x, 5] = true
        for (y in 6..12) m[12, y] = true
        val pruned = Thinning.pruneSpurs(m, 3)
        assertTrue(pruned[12, 12], "a 7 pixel branch is longer than the 3 pixel limit")
    }

    @Test
    fun bridgeEndpointsJoinsTwoCollinearStrokes() {
        val m = Mask(30, 9)
        for (x in 2..11) m[x, 4] = true
        for (x in 16..27) m[x, 4] = true
        assertEquals(2, Components.label(m, 8).count)
        val bridged = Thinning.bridgeEndpoints(m, 8, 60f)
        assertEquals(1, Components.label(bridged, 8).count, "the 4 px gap must be bridged")
    }

    @Test
    fun bridgeEndpointsIgnoresGapsThatAreTooWide() {
        val m = Mask(40, 9)
        for (x in 2..11) m[x, 4] = true
        for (x in 30..38) m[x, 4] = true
        val bridged = Thinning.bridgeEndpoints(m, 5, 60f)
        assertEquals(2, Components.label(bridged, 8).count, "an 18 px gap must not be bridged at maxGap=5")
    }

    @Test
    fun bridgeEndpointsIgnoresPerpendicularStrokes() {
        // Two strokes whose tangents disagree by 90 degrees: joining them invents a corner that was
        // never in the artwork, which is why the angle test exists.
        val m = Mask(24, 24)
        for (x in 2..10) m[x, 4] = true
        for (y in 8..20) m[14, y] = true
        val bridged = Thinning.bridgeEndpoints(m, 8, 30f)
        assertEquals(2, Components.label(bridged, 8).count)
    }

    @Test
    fun everythingSurvivesAnEmptyMask() {
        val empty = Mask(12, 12)
        assertEquals(0, Thinning.zhangSuen(empty).countTrue())
        assertEquals(0, Thinning.guoHall(empty).countTrue())
        assertEquals(0, Thinning.endpoints(empty).size)
        assertEquals(0, Thinning.junctions(empty).size)
        assertEquals(0, Thinning.pruneSpurs(empty, 3).countTrue())
        assertEquals(0, Thinning.bridgeEndpoints(empty, 5).countTrue())
        assertEquals(0, Thinning.neighbourCount(empty, 0, 0))
        assertEquals(0, Thinning.transitions(empty, 0, 0))
    }

    // -----------------------------------------------------------------------------------------
    // The centreline bias — the documented bound, asserted so it cannot drift silently
    // -----------------------------------------------------------------------------------------

    /**
     * Mean column of the skeleton of a vertical bar [w] px wide, minus the bar's true centre.
     *
     * Rows within 10 px of either end are excluded: thinning turns the flat end of a bar into a pair
     * of diagonal branches, and those are not the centreline.
     */
    private fun verticalBarBias(w: Int, thin: (Mask) -> Mask): Double {
        val width = 40
        val height = 40
        val x0 = (width - w) / 2
        val trueCentre = x0 + (w - 1) / 2.0
        val m = Mask(width, height)
        for (y in 4 until height - 4) for (x in x0 until x0 + w) m[x, y] = true
        val s = thin(m)
        var n = 0
        var acc = 0.0
        for (y in 10 until height - 10) for (x in 0 until width) if (s[x, y]) {
            acc += x
            n++
        }
        assertTrue(n > 0, "width $w thinned to nothing")
        return acc / n - trueCentre
    }

    @Test
    fun zhangSuenCentresOddWidthStrokesAndSitsHalfAPixelLowOnEvenOnes() {
        // A stroke covering columns [x0, x0+w-1] has its centreline at x0 + (w-1)/2, which for even w
        // is a half-integer and therefore not a pixel. The bias is not a rounding artefact that could
        // be tuned away: it is exactly -0.5 px on every even width and exactly 0 on every odd one,
        // toward decreasing x because sub-iteration 1 deletes the south and east boundary and so wins
        // the last tie. This is the bound the zhangSuen KDoc documents; the assertion exists so the
        // documentation cannot quietly stop being true.
        for (w in 1..10) {
            val bias = verticalBarBias(w) { Thinning.zhangSuen(it) }
            val expected = if (w % 2 == 0) -0.5 else 0.0
            assertTrue(
                kotlin.math.abs(bias - expected) < 1e-9,
                "width $w: expected a bias of $expected px, measured $bias",
            )
        }
    }

    @Test
    fun guoHallCarriesTheOppositeHalfPixelBias() {
        // +0.5 where Zhang-Suen is -0.5, on identical input. That the two are mirror images is the
        // proof that the bias belongs to the sub-iteration ordering rather than to the stroke, and it
        // is why switching thinning modes moves the bias rather than removing it.
        for (w in 1..10) {
            val bias = verticalBarBias(w) { Thinning.guoHall(it) }
            val expected = if (w % 2 == 0) 0.5 else 0.0
            assertTrue(
                kotlin.math.abs(bias - expected) < 1e-9,
                "width $w: expected a bias of $expected px, measured $bias",
            )
        }
    }

    @Test
    fun aDiagonalStrokeHasNoCentrelineBiasAtAnyWidth() {
        // The bias is a property of a lattice-aligned even width, not of thinning in general: on a
        // 45-degree band the skeleton sits exactly on the true axis whatever the band's width.
        val n = 60
        for (w in 1..8) {
            val half = (w / 2.0) * Math.sqrt(2.0)
            val m = Mask(n, n)
            for (y in 6 until n - 6) for (x in 6 until n - 6) {
                if (kotlin.math.abs((x - y).toDouble()) <= half - 1e-9) m[x, y] = true
            }
            val s = Thinning.zhangSuen(m)
            var count = 0
            var acc = 0.0
            for (y in 12 until n - 12) for (x in 0 until n) if (s[x, y]) {
                acc += (x - y) / Math.sqrt(2.0)
                count++
            }
            assertTrue(count > 0, "diagonal width $w thinned to nothing")
            assertTrue(
                kotlin.math.abs(acc / count) < 1e-9,
                "diagonal width $w: perpendicular offset ${acc / count} px, expected 0",
            )
        }
    }

    @Test
    fun everythingSurvivesAOnePixelMask() {
        val one = Mask(1, 1).fill(true)
        assertEquals(1, Thinning.zhangSuen(one).countTrue(), "a lone pixel is already a skeleton")
        assertEquals(1, Thinning.guoHall(one).countTrue())
        Thinning.pruneSpurs(one, 3)
        Thinning.bridgeEndpoints(one, 4)
        Thinning.endpoints(one)
        Thinning.junctions(one)
    }

    @Test
    fun aFullMaskThinsToSomethingThin() {
        val full = Mask(21, 21).fill(true)
        val s = Thinning.zhangSuen(full)
        assertTrue(s.countTrue() in 1 until full.countTrue() / 4, "got ${s.countTrue()}")
        assertNoTwoByTwoBlock(s, "full mask")
    }
}
