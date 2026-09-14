package com.offlinetracer.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tone operators (ALGORITHMS §5). */
class ContrastTest {

    private fun rampImage(n: Int): GrayF {
        val g = GrayF(n, 1)
        for (x in 0 until n) g[x, 0] = x / (n - 1).toFloat()
        return g
    }

    @Test
    fun histogramCountsEveryPixelExactlyOnce() {
        val src = rampImage(101)
        val h = Contrast.histogram(src, 256)
        assertEquals(256, h.size)
        var total = 0
        for (v in h) total += v
        assertEquals(101, total)
    }

    @Test
    fun histogramPutsBlackAndWhiteInTheEndBins() {
        val src = GrayF(2, 1, floatArrayOf(0f, 1f))
        val h = Contrast.histogram(src, 256)
        assertEquals(1, h[0])
        assertEquals(1, h[255])
    }

    @Test
    fun histogramClampsOutOfRangeValuesInsteadOfCrashing() {
        // GrayF is never clamped between stages, so a histogram of a DoG response legitimately sees
        // values outside 0..1; an unguarded bin index is an ArrayIndexOutOfBounds in production.
        val src = GrayF(3, 1, floatArrayOf(-2f, 0.5f, 7f))
        val h = Contrast.histogram(src, 16)
        var total = 0
        for (v in h) total += v
        assertEquals(3, total)
    }

    @Test
    fun invertIsItsOwnInverse() {
        val src = rampImage(17)
        val back = Contrast.invert(Contrast.invert(src))
        for (i in src.data.indices) assertEquals(src.data[i], back.data[i], 1e-6f)
        assertEquals(1f, Contrast.invert(src)[0, 0], 1e-6f)
    }

    @Test
    fun stretchMapsTheActualRangeToZeroOne() {
        val src = GrayF(4, 1, floatArrayOf(0.3f, 0.4f, 0.5f, 0.7f))
        val out = Contrast.stretch(src)
        assertEquals(0f, out[0, 0], 1e-5f)
        assertEquals(1f, out[3, 0], 1e-5f)
        assertEquals(0.25f, out[1, 0], 1e-5f)
    }

    @Test
    fun stretchOfAConstantDoesNotDivideByZero() {
        val out = Contrast.stretch(GrayF(5, 5).fill(0.42f))
        for (v in out.data) assertTrue(!v.isNaN() && !v.isInfinite(), "got $v")
    }

    @Test
    fun percentileStretchIgnoresASingleOutlier() {
        // One specular highlight otherwise owns the whole top of the range and the artwork ends up
        // compressed into the bottom 20%.
        val src = GrayF(100, 1)
        for (x in 0 until 99) src[x, 0] = 0.2f + 0.002f * x
        src[99, 0] = 8f
        // A plain stretch would map the real data into the bottom 2.5% of the range.
        val out = Contrast.percentileStretch(src, 5f)
        assertTrue(out[90, 0] > 0.85f, "the top of the real data must reach the top, got ${out[90, 0]}")
        assertTrue(out[0, 0] < 0.1f, "the bottom of the real data must reach the bottom")
    }

    @Test
    fun gammaOfOneIsIdentity() {
        val src = rampImage(21)
        val out = Contrast.gamma(src, 1f)
        for (i in src.data.indices) assertEquals(src.data[i], out.data[i], 1e-4f)
    }

    @Test
    fun gammaIsMonotonicAndFixesTheEndpoints() {
        val src = rampImage(21)
        for (g in floatArrayOf(0.5f, 2.2f)) {
            val out = Contrast.gamma(src, g)
            assertEquals(0f, out[0, 0], 1e-4f)
            assertEquals(1f, out[20, 0], 1e-4f)
            for (x in 1 until 21) assertTrue(out[x, 0] >= out[x - 1, 0] - 1e-5f, "gamma must be monotonic")
        }
    }

    @Test
    fun gammaOfZeroDoesNotProduceInfinity() {
        val out = Contrast.gamma(rampImage(5), 0f)
        for (v in out.data) assertTrue(!v.isNaN() && !v.isInfinite(), "got $v")
    }

    @Test
    fun levelsClipAndRescale() {
        // out = ((clamp(in, black, white) - black) / (white - black)) ^ (1/gamma)
        val src = GrayF(5, 1, floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f))
        val out = Contrast.levels(src, 0.2f, 0.8f, 1f)
        assertEquals(0f, out[0, 0], 1e-5f)
        assertEquals(0f, out[1, 0], 1e-5f)
        assertEquals(0.5f, out[2, 0], 1e-5f)
        assertEquals(1f, out[3, 0], 1e-5f)
        assertEquals(1f, out[4, 0], 1e-5f)
    }

    @Test
    fun levelsWithADegenerateWindowDoesNotDivideByZero() {
        val out = Contrast.levels(rampImage(5), 0.5f, 0.5f, 1f)
        for (v in out.data) assertTrue(!v.isNaN() && !v.isInfinite(), "got $v")
    }

    @Test
    fun brightnessContrastAtZeroIsIdentity() {
        val src = rampImage(11)
        val out = Contrast.brightnessContrast(src, 0f, 0f)
        for (i in src.data.indices) assertEquals(src.data[i], out.data[i], 1e-5f)
    }

    @Test
    fun brightnessLiftsAndContrastSpreadsAboutTheMidPoint() {
        val src = GrayF(3, 1, floatArrayOf(0.25f, 0.5f, 0.75f))
        val brighter = Contrast.brightnessContrast(src, 0.2f, 0f)
        for (i in 0 until 3) assertTrue(brighter.data[i] > src.data[i], "brightness must lift")

        val punchy = Contrast.brightnessContrast(src, 0f, 0.5f)
        assertEquals(0.5f, punchy[1, 0], 1e-4f)
        assertTrue(punchy[0, 0] < 0.25f, "contrast must push below-mid values down")
        assertTrue(punchy[2, 0] > 0.75f, "contrast must push above-mid values up")
    }

    @Test
    fun unsharpMaskWithZeroAmountIsIdentity() {
        val src = rampImage(15)
        val out = Contrast.unsharpMask(src, 1.5f, 0f)
        for (i in src.data.indices) assertEquals(src.data[i], out.data[i], 1e-5f)
    }

    @Test
    fun unsharpMaskOfAConstantIsTheConstant() {
        val out = Contrast.unsharpMask(GrayF(12, 12).fill(0.6f), 2f, 1.5f)
        for (v in out.data) assertEquals(0.6f, v, 1e-4f)
    }

    @Test
    fun unsharpMaskOvershootsAtAStep() {
        val src = GrayF(16, 1)
        for (x in 0 until 16) src[x, 0] = if (x < 8) 0.2f else 0.8f
        val out = Contrast.unsharpMask(src, 1.5f, 1f)
        assertTrue(out[7, 0] < 0.2f, "the dark side of a sharpened step must undershoot")
        assertTrue(out[8, 0] > 0.8f, "the light side of a sharpened step must overshoot")
    }

    @Test
    fun unsharpThresholdProtectsFlatAreas() {
        val src = GrayF(12, 12).fill(0.5f)
        src[6, 6] = 0.505f
        val out = Contrast.unsharpMask(src, 1.5f, 4f, 0.2f)
        for (y in 0 until 12) for (x in 0 until 12) {
            if (x == 6 && y == 6) continue
            assertEquals(0.5f, out[x, y], 1e-3f)
        }
    }

    @Test
    fun equalizeIsMonotonicAndSpansTheRange() {
        val src = GrayF(64, 1)
        for (x in 0 until 64) src[x, 0] = 0.4f + 0.002f * x
        val out = Contrast.equalize(src)
        for (x in 1 until 64) assertTrue(out[x, 0] >= out[x - 1, 0] - 1e-5f, "equalisation must be monotonic")
        val r = out.range()
        assertTrue(r.second - r.first > 0.8f, "a flat histogram must fill the range, got $r")
    }

    @Test
    fun equalizeOfAConstantDoesNotProduceNaN() {
        val out = Contrast.equalize(GrayF(8, 8).fill(0.5f))
        for (v in out.data) assertTrue(!v.isNaN(), "got $v")
    }

    @Test
    fun claheOfAConstantIsTheConstant() {
        // Every tile histogram is one spike; the CDF is a step and the interpolation must still
        // return a flat image rather than a tile-shaped pattern.
        val out = Contrast.clahe(GrayF(64, 64).fill(0.5f), 8, 8, 2f)
        val r = out.range()
        assertTrue(r.second - r.first < 0.02f, "constant input produced a range of $r")
    }

    @Test
    fun claheOfASingleGreyLevelIsThatLevelAndNotWhite() {
        // Pure white is also flat, so the test above passes on the exact failure this one catches:
        // the literal algorithm maps a one-level tile to 255 because its CDF is 1.0 at the only
        // occupied bin. The value is asserted at every size where a tile can hold one level — a 1x1
        // image, an image smaller than the tile grid so every tile is a single pixel, and a constant
        // image large enough that the tiles are ordinary.
        val expected = Px.toByte255(0.5f) / 255f
        for (src in listOf(GrayF(1, 1, floatArrayOf(0.5f)), GrayF(3, 2).fill(0.5f), GrayF(64, 64).fill(0.5f))) {
            val out = Contrast.clahe(src, 8, 8, 2f)
            for (v in out.data) {
                assertEquals(expected, v, 1e-6f, "${src.width}x${src.height} came back $v")
            }
        }
    }

    @Test
    fun claheHasNoTileSeams() {
        // A smooth global gradient must come out smooth. Skipping the bilinear interpolation between
        // tile LUTs is the most common way CLAHE is implemented wrong, and it shows up here as a
        // jump at a tile boundary that is many times the average step.
        val n = 128
        val src = GrayF(n, n)
        for (y in 0 until n) for (x in 0 until n) src[x, y] = x / (n - 1f)
        val out = Contrast.clahe(src, 4, 4, 3f)
        var maxJump = 0f
        var meanJump = 0f
        for (y in 0 until n) for (x in 1 until n) {
            val d = kotlin.math.abs(out[x, y] - out[x - 1, y])
            meanJump += d
            if (d > maxJump) maxJump = d
        }
        meanJump /= (n * (n - 1)).toFloat()
        assertTrue(maxJump < meanJump * 10f + 0.08f, "seam: max step $maxJump vs mean $meanJump")
    }

    @Test
    fun everyOperatorSurvivesAOnePixelImage() {
        val one = GrayF(1, 1, floatArrayOf(0.5f))
        Contrast.clahe(one, 8, 8, 2f)
        Contrast.equalize(one)
        Contrast.gamma(one, 2.2f)
        Contrast.levels(one, 0f, 1f, 1f)
        Contrast.brightnessContrast(one, 0.5f, 0.5f)
        Contrast.unsharpMask(one, 2f, 1f)
        Contrast.stretch(one)
        Contrast.percentileStretch(one)
        Contrast.invert(one)
        assertEquals(1, Contrast.histogram(one, 8).sum())
    }

    @Test
    fun everyOperatorSurvivesAnAllZeroImage() {
        val zero = GrayF(16, 16)
        for (out in listOf(
            Contrast.clahe(zero),
            Contrast.equalize(zero),
            Contrast.gamma(zero, 2f),
            Contrast.levels(zero, 0f, 1f, 1f),
            Contrast.brightnessContrast(zero, 0f, 0f),
            Contrast.unsharpMask(zero, 1f, 1f),
            Contrast.stretch(zero),
            Contrast.percentileStretch(zero),
            Contrast.invert(zero),
        )) {
            for (v in out.data) assertTrue(!v.isNaN() && !v.isInfinite(), "got $v")
        }
    }
}
