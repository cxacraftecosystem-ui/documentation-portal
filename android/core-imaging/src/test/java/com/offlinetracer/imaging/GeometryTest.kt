package com.offlinetracer.imaging

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Orientation, perspective and document detection. */
class GeometryTest {

    private fun opaque(v: Int): Int = RgbaImage.argb(255, v, v, v)

    /** Every pixel distinct, so any transposition or mirroring shows up immediately. */
    private fun numbered(w: Int, h: Int): RgbaImage {
        val img = RgbaImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            img[x, y] = RgbaImage.argb(255, x * 7 % 256, y * 11 % 256, (x + y) * 5 % 256)
        }
        return img
    }

    private fun assertSame(a: RgbaImage, b: RgbaImage) {
        assertEquals(a.width, b.width, "width")
        assertEquals(a.height, b.height, "height")
        for (i in a.pixels.indices) {
            if (a.pixels[i] != b.pixels[i]) {
                throw AssertionError("differs at (${i % a.width}, ${i / a.width})")
            }
        }
    }

    @Test
    fun rotate90FourTimesIsTheIdentity() {
        val src = numbered(5, 3)
        var img = src
        repeat(4) { img = Geometry.rotate90(img, 1) }
        assertSame(src, img)
    }

    @Test
    fun rotate90TurnsClockwiseAndSwapsTheDimensions() {
        val src = numbered(4, 2)
        val out = Geometry.rotate90(src, 1)
        assertEquals(2, out.width)
        assertEquals(4, out.height)
        // The source top-left ends up at the destination top-right.
        assertEquals(src[0, 0], out[out.width - 1, 0])
        assertEquals(src[3, 0], out[out.width - 1, 3])
        assertEquals(src[0, 1], out[0, 0])
    }

    @Test
    fun rotate90NegativeAndOutOfRangeTurnsWrap() {
        val src = numbered(4, 3)
        assertSame(Geometry.rotate90(src, 3), Geometry.rotate90(src, -1))
        assertSame(Geometry.rotate90(src, 1), Geometry.rotate90(src, 5))
        assertSame(src, Geometry.rotate90(src, 0))
        assertSame(src, Geometry.rotate90(src, 8))
    }

    @Test
    fun flipMirrorsTheRequestedAxes() {
        val src = numbered(4, 3)
        val h = Geometry.flip(src, horizontal = true, vertical = false)
        for (y in 0 until 3) for (x in 0 until 4) assertEquals(src[3 - x, y], h[x, y])
        val v = Geometry.flip(src, horizontal = false, vertical = true)
        for (y in 0 until 3) for (x in 0 until 4) assertEquals(src[x, 2 - y], v[x, y])
        assertSame(Geometry.rotate90(src, 2), Geometry.flip(src, horizontal = true, vertical = true))
        assertSame(src, Geometry.flip(src, horizontal = false, vertical = false))
    }

    @Test
    fun homographyOfIdenticalQuadsIsTheIdentity() {
        val quad = floatArrayOf(0f, 0f, 10f, 0f, 10f, 8f, 0f, 8f)
        val h = Geometry.solveHomography(quad, quad)
        assertEquals(9, h.size)
        val expected = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        for (i in 0 until 9) assertEquals(expected[i], h[i], 1e-6, "coefficient $i")
    }

    @Test
    fun homographyOfAScaleIsAScaleMatrix() {
        val src = floatArrayOf(0f, 0f, 2f, 0f, 2f, 2f, 0f, 2f)
        val dst = floatArrayOf(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        val h = Geometry.solveHomography(src, dst)
        assertEquals(2.0, h[0], 1e-6)
        assertEquals(0.0, h[1], 1e-6)
        assertEquals(0.0, h[2], 1e-6)
        assertEquals(0.0, h[3], 1e-6)
        assertEquals(2.0, h[4], 1e-6)
        assertEquals(1.0, h[8], 1e-6)
    }

    @Test
    fun homographyMapsEveryCornerOntoItsTarget() {
        // A genuinely projective quad: no affine matrix can do this, so it exercises h[6] and h[7].
        val src = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val dst = floatArrayOf(10f, 12f, 90f, 4f, 105f, 96f, 3f, 88f)
        val h = Geometry.solveHomography(src, dst)
        for (i in 0 until 4) {
            val x = src[i * 2].toDouble()
            val y = src[i * 2 + 1].toDouble()
            val w = h[6] * x + h[7] * y + h[8]
            val px = (h[0] * x + h[1] * y + h[2]) / w
            val py = (h[3] * x + h[4] * y + h[5]) / w
            assertEquals(dst[i * 2].toDouble(), px, 1e-4, "corner $i x")
            assertEquals(dst[i * 2 + 1].toDouble(), py, 1e-4, "corner $i y")
        }
    }

    @Test
    fun aDegenerateQuadReturnsTheIdentityRatherThanNaN() {
        val collapsed = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val target = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        val h = Geometry.solveHomography(collapsed, target)
        for (v in h) assertTrue(!v.isNaN() && !v.isInfinite(), "got $v")
        assertEquals(1.0, h[8], 1e-9)
    }

    @Test
    fun warpWithTheIdentityReproducesTheImage() {
        val src = numbered(8, 6)
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val out = Geometry.warpPerspective(src, identity, 8, 6)
        assertSame(src, out)
    }

    @Test
    fun warpUsesInverseMappingSoAnUpscaleHasNoHoles() {
        // Forward mapping scatters source pixels into the destination and leaves a lattice of
        // untouched (transparent) pixels; this is what that bug looks like from the outside.
        val src = RgbaImage(4, 4).fill(opaque(200))
        val h = doubleArrayOf(3.0, 0.0, 0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 1.0)
        val out = Geometry.warpPerspective(src, h, 12, 12)
        for (y in 0 until 10) for (x in 0 until 10) {
            assertEquals(255, RgbaImage.alphaOf(out[x, y]), "hole at ($x, $y)")
        }
    }

    @Test
    fun warpAppliesTheForwardTransformToTheContent() {
        val src = RgbaImage(8, 8).fill(opaque(0))
        for (y in 0 until 4) for (x in 0 until 4) src[x, y] = opaque(255)
        val h = doubleArrayOf(2.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0)
        val out = Geometry.warpPerspective(src, h, 16, 16)
        assertEquals(255, RgbaImage.redOf(out[2, 2]), "the bright block must scale up")
        assertEquals(0, RgbaImage.redOf(out[12, 12]), "the dark region must scale up too")
    }

    @Test
    fun warpLeavesUncoveredPixelsTransparent() {
        val src = RgbaImage(4, 4).fill(opaque(255))
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val out = Geometry.warpPerspective(src, identity, 8, 8)
        assertEquals(255, RgbaImage.alphaOf(out[1, 1]))
        assertEquals(0, RgbaImage.alphaOf(out[7, 7]), "outside the source must stay transparent")
    }

    @Test
    fun warpAndItsInverseRoundTrip() {
        val src = numbered(24, 20)
        val quad = floatArrayOf(0f, 0f, 23f, 0f, 23f, 19f, 0f, 19f)
        val skewed = floatArrayOf(2f, 1f, 21f, 3f, 22f, 18f, 1f, 17f)
        val forward = Geometry.solveHomography(quad, skewed)
        val back = Geometry.solveHomography(skewed, quad)
        val once = Geometry.warpPerspective(src, forward, 24, 20)
        val twice = Geometry.warpPerspective(once, back, 24, 20)
        // Resampling twice is lossy, so this checks structure rather than exact equality.
        var diff = 0
        for (y in 6 until 14) for (x in 6 until 18) {
            if (abs(RgbaImage.redOf(twice[x, y]) - RgbaImage.redOf(src[x, y])) > 40) diff++
        }
        assertTrue(diff < 12, "$diff of 96 interior pixels drifted after a round trip")
    }

    @Test
    fun orderQuadProducesTopLeftFirstClockwise() {
        // Supplied bottom-right, bottom-left, top-left, top-right.
        val quad = floatArrayOf(90f, 80f, 10f, 78f, 12f, 8f, 88f, 6f)
        val o = Geometry.orderQuad(quad)
        assertEquals(12f, o[0], 1e-4f)
        assertEquals(8f, o[1], 1e-4f)
        assertEquals(88f, o[2], 1e-4f)
        assertEquals(6f, o[3], 1e-4f)
        assertEquals(90f, o[4], 1e-4f)
        assertEquals(80f, o[5], 1e-4f)
        assertEquals(10f, o[6], 1e-4f)
        assertEquals(78f, o[7], 1e-4f)
    }

    @Test
    fun orderQuadIsIdempotent() {
        val quad = floatArrayOf(90f, 80f, 10f, 78f, 12f, 8f, 88f, 6f)
        val once = Geometry.orderQuad(quad)
        val twice = Geometry.orderQuad(once)
        for (i in 0 until 8) assertEquals(once[i], twice[i], 1e-5f)
    }

    @Test
    fun orderQuadHandlesARotatedQuadWithoutDuplicatingACorner() {
        // A diamond: min(x+y) and max(x-y) pick the same vertex, which is exactly the case that
        // breaks the usual corner rules and produces a singular homography downstream.
        val diamond = floatArrayOf(50f, 0f, 100f, 50f, 50f, 100f, 0f, 50f)
        val o = Geometry.orderQuad(diamond)
        val seen = HashSet<String>()
        for (i in 0 until 4) seen.add("${o[i * 2]},${o[i * 2 + 1]}")
        assertEquals(4, seen.size, "every corner must appear exactly once")
    }

    @Test
    fun orderQuadOfAWrongSizedArrayIsReturnedUnchanged() {
        val short = floatArrayOf(1f, 2f)
        val o = Geometry.orderQuad(short)
        assertEquals(2, o.size)
        assertEquals(1f, o[0], 0f)
    }

    @Test
    fun rotateByZeroIsACopy() {
        val src = numbered(6, 4)
        assertSame(src, Geometry.rotate(src, 0f))
    }

    @Test
    fun rotateByExactQuarterTurnsMatchesRotate90() {
        val src = numbered(6, 4)
        assertSame(Geometry.rotate90(src, 1), Geometry.rotate(src, 90f))
        assertSame(Geometry.rotate90(src, 2), Geometry.rotate(src, 180f))
        assertSame(Geometry.rotate90(src, 3), Geometry.rotate(src, 270f))
        assertSame(Geometry.rotate90(src, 3), Geometry.rotate(src, -90f))
    }

    @Test
    fun rotateWithExpandGrowsTheCanvasAndKeepsTheContent() {
        val src = RgbaImage(20, 10).fill(opaque(255))
        val out = Geometry.rotate(src, 45f, expand = true)
        assertTrue(out.width > 20 && out.height > 10, "expand must grow the canvas, got ${out.width}x${out.height}")
        assertEquals(255, RgbaImage.alphaOf(out[out.width / 2, out.height / 2]), "the centre must be covered")
        assertEquals(0, RgbaImage.alphaOf(out[0, 0]), "the new corner must be empty")
    }

    @Test
    fun rotateWithoutExpandKeepsTheCanvasSize() {
        val src = RgbaImage(20, 10).fill(opaque(255))
        val out = Geometry.rotate(src, 30f, expand = false)
        assertEquals(20, out.width)
        assertEquals(10, out.height)
    }

    @Test
    fun detectDocumentQuadFindsASyntheticPage() {
        val w = 160
        val h = 128
        val g = GrayF(w, h).fill(0.05f)
        for (y in 16..111) for (x in 20..139) g[x, y] = 0.95f
        val quad = Geometry.detectDocumentQuad(g)
        assertNotNull(quad, "an obvious page filling 56% of the frame must be found")
        assertEquals(8, quad.size)
        val corners = arrayOf(
            floatArrayOf(20f, 16f), floatArrayOf(139f, 16f),
            floatArrayOf(139f, 111f), floatArrayOf(20f, 111f),
        )
        for (i in 0 until 4) {
            assertTrue(
                abs(quad[i * 2] - corners[i][0]) <= 8f && abs(quad[i * 2 + 1] - corners[i][1]) <= 8f,
                "corner $i was (${quad[i * 2]}, ${quad[i * 2 + 1]}), expected about " +
                    "(${corners[i][0]}, ${corners[i][1]})",
            )
        }
    }

    @Test
    fun detectDocumentQuadRejectsAFrameFillingLessThanFifteenPercent() {
        val g = GrayF(120, 120).fill(0.05f)
        for (y in 50..69) for (x in 50..69) g[x, y] = 0.95f
        assertNull(Geometry.detectDocumentQuad(g), "a 2.8% blob is not a page")
    }

    @Test
    fun detectDocumentQuadReturnsNullOnAnEmptyFrame() {
        assertNull(Geometry.detectDocumentQuad(GrayF(64, 64).fill(0.5f)))
        assertNull(Geometry.detectDocumentQuad(GrayF(64, 64)))
    }

    @Test
    fun everythingSurvivesAOnePixelImage() {
        val one = RgbaImage(1, 1, intArrayOf(opaque(128)))
        Geometry.rotate90(one, 3)
        Geometry.flip(one, horizontal = true, vertical = true)
        Geometry.rotate(one, 37f)
        Geometry.rotate(one, 37f, expand = false)
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        Geometry.warpPerspective(one, identity, 4, 4)
        assertNull(Geometry.detectDocumentQuad(GrayF(1, 1)))
    }

    @Test
    fun warpToADegenerateOutputSizeDoesNotThrow() {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val out = Geometry.warpPerspective(numbered(4, 4), identity, 0, -3)
        assertTrue(out.width >= 1 && out.height >= 1)
    }
}
