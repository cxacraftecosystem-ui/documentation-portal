package com.offlinetracer.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Downscaling is the most consequential step in preprocessing (ALGORITHMS §2), so it is tested
 * against the analytic mean of each source rectangle rather than against whatever the code happens
 * to produce. Point-sampling passes a "does it run" test and fails this one.
 */
class ResampleTest {

    private fun gradient(w: Int, h: Int): GrayF {
        val g = GrayF(w, h)
        for (y in 0 until h) for (x in 0 until w) g[x, y] = (y * w + x).toFloat()
        return g
    }

    @Test
    fun downscaleIsTheExactBoxMeanOnAnIntegerRatio() {
        val src = gradient(4, 4)
        val out = Resample.resize(src, 2, 2)
        assertEquals(2, out.width)
        assertEquals(2, out.height)
        for (by in 0 until 2) {
            for (bx in 0 until 2) {
                var expected = 0f
                for (y in 0 until 2) for (x in 0 until 2) expected += src[bx * 2 + x, by * 2 + y]
                assertEquals(expected / 4f, out[bx, by], 1e-4f)
            }
        }
    }

    @Test
    fun downscaleWeightsPartialPixels() {
        // 3 -> 2 has scale 1.5, so the first destination pixel covers all of source 0 and half of
        // source 1: (0 + 0.5*1) / 1.5. Nearest-neighbour and point-sampled bilinear both miss this.
        val src = GrayF(3, 1, floatArrayOf(0f, 1f, 2f))
        val out = Resample.resize(src, 2, 1)
        assertEquals((0f + 0.5f * 1f) / 1.5f, out[0, 0], 1e-4f)
        assertEquals((0.5f * 1f + 2f) / 1.5f, out[1, 0], 1e-4f)
    }

    @Test
    fun downscaleOfAConstantIsTheConstant() {
        val src = GrayF(9, 7).fill(0.375f)
        val out = Resample.resize(src, 4, 3)
        for (v in out.data) assertEquals(0.375f, v, 1e-5f)
    }

    @Test
    fun upscaleOfAConstantIsTheConstant() {
        val src = GrayF(3, 3).fill(0.6f)
        val out = Resample.resize(src, 11, 8)
        assertEquals(11, out.width)
        assertEquals(8, out.height)
        for (v in out.data) assertEquals(0.6f, v, 1e-5f)
    }

    @Test
    fun upscaleStaysWithinTheSourceRangeAndIsMonotonic() {
        val src = GrayF(4, 1, floatArrayOf(0f, 0.25f, 0.5f, 1f))
        val out = Resample.resize(src, 16, 1)
        var prev = -1f
        for (x in 0 until 16) {
            val v = out[x, 0]
            assertTrue(v >= -1e-5f && v <= 1f + 1e-5f, "bilinear upsampling must not overshoot: $v")
            assertTrue(v >= prev - 1e-5f, "a monotonic source must upsample monotonically")
            prev = v
        }
        assertEquals(0f, out[0, 0], 1e-4f)
        assertEquals(1f, out[15, 0], 1e-4f)
    }

    @Test
    fun rgbaDownscaleAveragesEachChannelIndependently() {
        val src = RgbaImage(
            2, 1,
            intArrayOf(RgbaImage.argb(255, 0, 100, 200), RgbaImage.argb(255, 100, 200, 0)),
        )
        val out = Resample.resize(src, 1, 1)
        val p = out[0, 0]
        assertTrue(kotlin.math.abs(RgbaImage.redOf(p) - 50) <= 1, "red was ${RgbaImage.redOf(p)}")
        assertTrue(kotlin.math.abs(RgbaImage.greenOf(p) - 150) <= 1, "green was ${RgbaImage.greenOf(p)}")
        assertTrue(kotlin.math.abs(RgbaImage.blueOf(p) - 100) <= 1, "blue was ${RgbaImage.blueOf(p)}")
        assertEquals(255, RgbaImage.alphaOf(p))
    }

    @Test
    fun maskResizeStaysBinaryAndUsesNearest() {
        val m = Mask(4, 4)
        for (x in 0 until 4) for (y in 0 until 2) m[x, y] = true
        val up = Resample.resize(m, 8, 8)
        assertEquals(8, up.width)
        assertTrue(up.countTrue() > 0, "a non-empty mask must not resize to nothing")
        // The top half is solid and the bottom half is empty, so whichever source row a 2x2
        // destination samples, the answer is the same: two true pixels, still binary.
        val down = Resample.resize(m, 2, 2)
        assertEquals(2, down.width)
        assertEquals(2, down.countTrue())
    }

    @Test
    fun fitWithinPreservesAspectAndNeverUpscales() {
        val a = Resample.fitWithin(4000, 3000, 2048)
        assertEquals(2048, a[0])
        assertEquals(1536, a[1])

        val b = Resample.fitWithin(3000, 4000, 2048)
        assertEquals(1536, b[0])
        assertEquals(2048, b[1])

        val small = Resample.fitWithin(100, 50, 2048)
        assertEquals(100, small[0])
        assertEquals(50, small[1])

        val square = Resample.fitWithin(1000, 1000, 500)
        assertEquals(500, square[0])
        assertEquals(500, square[1])
    }

    @Test
    fun fitWithinNeverReturnsZero() {
        // A 4000x3 panorama scaled to 64 rounds its short edge to zero unless it is clamped, and a
        // zero-sized buffer throws from the GrayF constructor several stages later.
        val r = Resample.fitWithin(4000, 3, 64)
        assertTrue(r[0] >= 1 && r[1] >= 1, "got ${r[0]}x${r[1]}")
    }

    @Test
    fun scaleToLongEdgeMatchesFitWithin() {
        val src = RgbaImage(40, 20)
        val out = Resample.scaleToLongEdge(src, 10)
        assertEquals(10, out.width)
        assertEquals(5, out.height)
        val untouched = Resample.scaleToLongEdge(src, 400)
        assertEquals(40, untouched.width)
        assertEquals(20, untouched.height)
    }

    @Test
    fun cropTakesTheRequestedRectangle() {
        val g = gradient(5, 4)
        val c = Resample.crop(g, 1, 2, 3, 2)
        assertEquals(3, c.width)
        assertEquals(2, c.height)
        for (y in 0 until 2) for (x in 0 until 3) assertEquals(g[1 + x, 2 + y], c[x, y], 0f)

        val img = RgbaImage(3, 3)
        img[2, 2] = RgbaImage.argb(255, 9, 9, 9)
        val ci = Resample.crop(img, 2, 2, 1, 1)
        assertEquals(9, RgbaImage.redOf(ci[0, 0]))
    }

    @Test
    fun padSurroundsTheImageWithTheGivenValue() {
        val g = GrayF(2, 2).fill(1f)
        val p = Resample.pad(g, 1, 2, 3, 4, 0.25f)
        assertEquals(2 + 1 + 3, p.width)
        assertEquals(2 + 2 + 4, p.height)
        assertEquals(0.25f, p[0, 0], 0f)
        assertEquals(1f, p[1, 2], 0f)
        assertEquals(0.25f, p[5, 7], 0f)
    }

    @Test
    fun onePixelImagesSurviveEveryPath() {
        val g = GrayF(1, 1, floatArrayOf(0.5f))
        assertEquals(0.5f, Resample.resize(g, 1, 1)[0, 0], 1e-5f)
        assertEquals(0.5f, Resample.resize(g, 4, 4)[2, 2], 1e-5f)
        val img = RgbaImage(1, 1, intArrayOf(RgbaImage.argb(255, 1, 2, 3)))
        assertEquals(3, Resample.resize(img, 3, 3).width)
        assertEquals(1, Resample.resize(Mask(1, 1), 1, 1).width)
        Resample.crop(g, 0, 0, 1, 1)
        Resample.pad(g, 0, 0, 0, 0, 0f)
    }

    @Test
    fun downscalingAllZerosStaysAllZeros() {
        val out = Resample.resize(GrayF(16, 16), 4, 4)
        for (v in out.data) assertEquals(0f, v, 0f)
    }
}
