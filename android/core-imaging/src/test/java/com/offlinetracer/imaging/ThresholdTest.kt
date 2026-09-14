package com.offlinetracer.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Binarisation (ALGORITHMS §6). */
class ThresholdTest {

    /** Half the pixels at [lo], half at [hi] — a histogram with exactly two spikes. */
    private fun twoDeltas(lo: Float, hi: Float, n: Int = 100): GrayF {
        val g = GrayF(n, 1)
        for (x in 0 until n) g[x, 0] = if (x < n / 2) lo else hi
        return g
    }

    @Test
    fun otsuOnTwoDeltasLandsStrictlyBetweenThem() {
        val t = Threshold.otsu(twoDeltas(0.2f, 0.8f))
        assertTrue(t > 0.2f && t < 0.8f, "expected a threshold inside (0.2, 0.8), got $t")
    }

    @Test
    fun otsuTracksTheModesWhenTheyMove() {
        val low = Threshold.otsu(twoDeltas(0.05f, 0.35f))
        val high = Threshold.otsu(twoDeltas(0.6f, 0.95f))
        assertTrue(low > 0.05f && low < 0.35f, "got $low")
        assertTrue(high > 0.6f && high < 0.95f, "got $high")
        assertTrue(low < high)
    }

    @Test
    fun otsuOnAConstantImageIsFiniteAndInRange() {
        val t = Threshold.otsu(GrayF(8, 8).fill(0.5f))
        assertTrue(!t.isNaN() && t >= 0f && t <= 1f, "got $t")
    }

    @Test
    fun separabilityIsNearOneForTwoDeltasAndNearZeroForNoise() {
        val bimodal = Threshold.otsuSeparability(twoDeltas(0f, 1f))
        assertTrue(bimodal > 0.9f, "two separated deltas must be highly separable, got $bimodal")

        val flat = GrayF(256, 1)
        for (x in 0 until 256) flat[x, 0] = x / 255f
        val uniform = Threshold.otsuSeparability(flat)
        assertTrue(uniform < 0.85f, "a uniform histogram is not bimodal, got $uniform")
        assertTrue(uniform >= 0f)
    }

    @Test
    fun separabilityOfAConstantImageDoesNotDivideByZero() {
        val s = Threshold.otsuSeparability(GrayF(4, 4).fill(0.3f))
        assertTrue(!s.isNaN() && !s.isInfinite(), "got $s")
    }

    @Test
    fun fixedThresholdAndItsInverseAreComplementary() {
        val src = GrayF(4, 1, floatArrayOf(0.1f, 0.4f, 0.6f, 0.9f))
        val m = Threshold.fixed(src, 0.5f)
        assertFalse(m[0, 0])
        assertFalse(m[1, 0])
        assertTrue(m[2, 0])
        assertTrue(m[3, 0])
        val inv = Threshold.fixed(src, 0.5f, invert = true)
        for (i in 0 until 4) assertTrue(m.data[i] != inv.data[i], "invert must flip every pixel")
    }

    @Test
    fun adaptiveMeanOnAConstantImageIsAllForegroundBecauseOfC() {
        // in > localMean - C, and on a constant image in == localMean, so a positive C makes every
        // pixel foreground. Getting the sign of C wrong inverts the whole document.
        val src = GrayF(20, 20).fill(0.5f)
        val m = Threshold.adaptiveMean(src, 3, 0.02f)
        assertEquals(400, m.countTrue())
        val inverted = Threshold.adaptiveMean(src, 3, 0.02f, invert = true)
        assertEquals(0, inverted.countTrue())
    }

    @Test
    fun adaptiveMeanFindsInkUnderAStrongBackgroundGradient() {
        // The case a global threshold cannot do: the darkest "paper" is darker than the lightest ink.
        val w = 40
        val src = GrayF(w, 12)
        for (y in 0 until 12) for (x in 0 until w) src[x, y] = 1f - 0.8f * x / (w - 1f)
        for (y in 4 until 8) for (x in intArrayOf(6, 20, 34)) src[x, y] = src[x, y] - 0.15f
        val ink = Threshold.adaptiveMean(src, 4, 0.02f, invert = true)
        for (x in intArrayOf(6, 20, 34)) assertTrue(ink[x, 6], "stroke at x=$x was lost")
        assertFalse(ink[12, 1], "background must not be ink")
    }

    @Test
    fun adaptiveGaussianAgreesWithAdaptiveMeanOnASimpleCase() {
        val w = 32
        val src = GrayF(w, 8)
        for (y in 0 until 8) for (x in 0 until w) src[x, y] = if (x in 14..17) 0.2f else 0.8f
        val g = Threshold.adaptiveGaussian(src, 3f, 0.02f, invert = true)
        for (x in 14..17) assertTrue(g[x, 4], "the dark band must be found at x=$x")
        assertFalse(g[2, 4])
    }

    @Test
    fun sauvolaOnAConstantImageIsAllForeground() {
        // s = 0, so T = m(1 + k(0/R - 1)) = m(1 - k) < m, and every pixel is above its own threshold.
        val src = GrayF(16, 16).fill(0.5f)
        assertEquals(256, Threshold.sauvola(src, 4, 0.2f).countTrue())
    }

    @Test
    fun sauvolaSurvivesAFadedBackgroundGradient() {
        val w = 48
        val src = GrayF(w, 16)
        for (y in 0 until 16) for (x in 0 until w) src[x, y] = 0.95f - 0.55f * x / (w - 1f)
        for (y in 5 until 11) for (x in intArrayOf(8, 24, 40)) src[x, y] = src[x, y] - 0.25f
        val ink = Threshold.sauvola(src, 5, 0.2f, invert = true)
        for (x in intArrayOf(8, 24, 40)) assertTrue(ink[x, 8], "stroke at x=$x was lost")
    }

    @Test
    fun hysteresisKeepsWeakPixelsOnlyWhenTheyTouchAStrongOne() {
        val src = GrayF(9, 1, floatArrayOf(0f, 0.5f, 0.5f, 0.9f, 0.5f, 0f, 0.5f, 0.5f, 0f))
        val m = Threshold.hysteresis(src, 0.3f, 0.8f)
        assertTrue(m[3, 0], "the seed itself must survive")
        assertTrue(m[1, 0] && m[2, 0] && m[4, 0], "the connected weak chain must survive")
        assertFalse(m[6, 0], "an isolated weak run must not survive")
        assertFalse(m[7, 0])
        assertFalse(m[0, 0])
    }

    @Test
    fun hysteresisWithNoSeedIsEmpty() {
        val src = GrayF(6, 6).fill(0.4f)
        assertEquals(0, Threshold.hysteresis(src, 0.3f, 0.9f).countTrue())
    }

    @Test
    fun hysteresisFloodsALongChainWithoutOverflowingTheStack() {
        // The recursive form dies somewhere around a few tens of thousands of pixels; this is what
        // the "explicit IntArray stack" requirement in §6 exists for.
        val n = 512
        val src = GrayF(n, n).fill(0.5f)
        src[0, 0] = 1f
        val m = Threshold.hysteresis(src, 0.3f, 0.9f)
        assertEquals(n * n, m.countTrue())
    }

    @Test
    fun autoCannyThresholdsFollowTheMedian() {
        val magnitude = GrayF(9, 9).fill(0.5f)
        val t = Threshold.autoCannyThresholds(magnitude, 0.33f)
        assertEquals(2, t.size)
        assertEquals(0.67f * 0.5f, t[0], 1e-4f)
        assertEquals(1.33f * 0.5f, t[1], 1e-4f)
        assertTrue(t[0] <= t[1])
    }

    @Test
    fun autoCannyThresholdsStayInRange() {
        val bright = GrayF(4, 4).fill(0.95f)
        val t = Threshold.autoCannyThresholds(bright)
        assertTrue(t[0] >= 0f && t[1] <= 1f, "expected clamping to 0..1, got ${t[0]}..${t[1]}")
    }

    @Test
    fun medianOfARampIsTheMiddleValue() {
        val n = 101
        val g = GrayF(n, 1)
        for (x in 0 until n) g[x, 0] = x / (n - 1f)
        assertEquals(0.5f, Threshold.median(g), 0.01f)
    }

    @Test
    fun everyThresholdSurvivesAOnePixelImage() {
        val one = GrayF(1, 1, floatArrayOf(0.5f))
        Threshold.otsu(one)
        Threshold.otsuSeparability(one)
        Threshold.fixed(one, 0.5f)
        Threshold.adaptiveMean(one, 3, 0.01f)
        Threshold.adaptiveGaussian(one, 2f, 0.01f)
        Threshold.sauvola(one, 3)
        Threshold.hysteresis(one, 0.1f, 0.9f)
        Threshold.autoCannyThresholds(one)
        assertEquals(0.5f, Threshold.median(one), 0.01f)
    }

    @Test
    fun everyThresholdSurvivesAnAllZeroImage() {
        val zero = GrayF(12, 12)
        assertEquals(0, Threshold.fixed(zero, 0.5f).countTrue())
        assertEquals(0, Threshold.hysteresis(zero, 0.1f, 0.2f).countTrue())
        Threshold.adaptiveMean(zero, 3, 0.01f)
        Threshold.adaptiveGaussian(zero, 2f, 0.01f)
        Threshold.sauvola(zero, 3)
        val t = Threshold.autoCannyThresholds(zero)
        assertTrue(!t[0].isNaN() && !t[1].isNaN())
    }
}
