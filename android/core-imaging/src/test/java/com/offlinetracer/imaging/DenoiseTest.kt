package com.offlinetracer.imaging

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Denoising (ALGORITHMS §4). The property that matters for every filter here is that it removes
 * outliers **without** moving the edges — a denoiser that blurs the step is a Gaussian with extra
 * steps, and the edge detector downstream cannot tell the difference until the output is soft.
 */
class DenoiseTest {

    private fun step(w: Int, h: Int, edgeX: Int): GrayF {
        val g = GrayF(w, h)
        for (y in 0 until h) for (x in 0 until w) g[x, y] = if (x < edgeX) 0.1f else 0.9f
        return g
    }

    @Test
    fun bilateralOfAConstantIsTheConstant() {
        val src = GrayF(15, 15).fill(0.3f)
        val out = Denoise.bilateral(src, 3f, 0.2f)
        for (v in out.data) assertEquals(0.3f, v, 1e-4f)
    }

    @Test
    fun bilateralKeepsTheStepSharp() {
        val src = step(16, 8, 8)
        // A range sigma well below the step height means the two sides never mix.
        val out = Denoise.bilateral(src, 2f, 0.05f)
        for (y in 0 until 8) {
            assertEquals(0.1f, out[7, y], 0.02f)
            assertEquals(0.9f, out[8, y], 0.02f)
        }
    }

    @Test
    fun bilateralWithAHugeRangeSigmaApproachesAPlainBlur() {
        // With the range term saturated the filter degenerates to its spatial Gaussian, which is the
        // sanity check that the two weights are actually multiplied and not swapped.
        val src = step(16, 4, 8)
        val out = Denoise.bilateral(src, 2f, 100f)
        assertTrue(out[7, 2] > 0.15f, "expected the edge to soften, got ${out[7, 2]}")
        assertTrue(out[8, 2] < 0.85f, "expected the edge to soften, got ${out[8, 2]}")
    }

    @Test
    fun medianRemovesSaltAndPepper() {
        val src = GrayF(9, 9).fill(0.5f)
        src[4, 4] = 1f
        src[2, 6] = 0f
        val out = Denoise.median(src, 1)
        assertEquals(0.5f, out[4, 4], 1e-4f)
        assertEquals(0.5f, out[2, 6], 1e-4f)
    }

    @Test
    fun medianWithALargeRadiusStillRemovesSaltAndPepper() {
        // Radius >= 2 takes the histogram-sliding path, which is a completely different code path
        // from the insertion sort used at radius 1.
        val src = GrayF(13, 13).fill(0.4f)
        src[6, 6] = 1f
        src[3, 9] = 0f
        val out = Denoise.median(src, 3)
        assertEquals(0.4f, out[6, 6], 2f / 255f)
        assertEquals(0.4f, out[3, 9], 2f / 255f)
    }

    @Test
    fun medianOfAConstantIsTheConstant() {
        val src = GrayF(11, 11).fill(0.625f)
        for (r in intArrayOf(1, 2, 4)) {
            for (v in Denoise.median(src, r).data) assertEquals(0.625f, v, 2f / 255f)
        }
    }

    @Test
    fun anisotropicDiffusionOfAConstantIsTheConstant() {
        val src = GrayF(12, 12).fill(0.8f)
        val out = Denoise.anisotropicDiffusion(src, 10, 0.1f)
        for (v in out.data) assertEquals(0.8f, v, 1e-4f)
    }

    @Test
    fun anisotropicDiffusionPreservesAStrongEdgeAndSmoothsWeakOnes() {
        val src = GrayF(16, 8)
        for (y in 0 until 8) for (x in 0 until 16) {
            src[x, y] = if (x < 8) 0f else 1f
            // A tiny alternating ripple on top of each plateau: below kappa, so it must diffuse.
            if ((x + y) % 2 == 0) src[x, y] = src[x, y] + 0.02f
        }
        val out = Denoise.anisotropicDiffusion(src, 20, 0.1f)
        assertTrue(abs(out[8, 4] - out[7, 4]) > 0.7f, "the strong edge must survive")
        assertTrue(abs(out[3, 4] - out[4, 4]) < 0.015f, "the ripple must diffuse")
    }

    @Test
    fun anisotropicDiffusionWithZeroIterationsIsACopy() {
        val src = step(8, 8, 4)
        val out = Denoise.anisotropicDiffusion(src, 0, 0.1f)
        for (i in src.data.indices) assertEquals(src.data[i], out.data[i], 1e-6f)
    }

    @Test
    fun despeckleOnlyTouchesOutliers() {
        val src = GrayF(11, 11)
        for (y in 0 until 11) for (x in 0 until 11) src[x, y] = 0.4f + 0.01f * x
        val clean = src.copy()
        src[5, 5] = 1f
        val out = Denoise.despeckle(src, 1, 0.2f)
        assertEquals(clean[5, 5], out[5, 5], 0.05f)
        // Everything that was not an outlier must come through byte-identical, which is the whole
        // point of despeckle over a plain median.
        for (y in 1 until 10) for (x in 1 until 10) {
            if (x == 5 && y == 5) continue
            assertEquals(clean[x, y], out[x, y], 1e-5f)
        }
    }

    @Test
    fun despeckleWithAHugeThresholdIsIdentity() {
        val src = GrayF(9, 9)
        for (y in 0 until 9) for (x in 0 until 9) src[x, y] = (x * 7 + y * 3) % 5 / 5f
        val out = Denoise.despeckle(src, 1, 10f)
        for (i in src.data.indices) assertEquals(src.data[i], out.data[i], 1e-6f)
    }

    @Test
    fun everyFilterSurvivesAOnePixelImage() {
        val one = GrayF(1, 1, floatArrayOf(0.5f))
        assertEquals(0.5f, Denoise.bilateral(one, 2f, 0.2f)[0, 0], 1e-4f)
        assertEquals(0.5f, Denoise.median(one, 3)[0, 0], 2f / 255f)
        assertEquals(0.5f, Denoise.anisotropicDiffusion(one, 5, 0.1f)[0, 0], 1e-4f)
        assertEquals(0.5f, Denoise.despeckle(one, 2, 0.1f)[0, 0], 2f / 255f)
    }

    @Test
    fun everyFilterSurvivesAnAllZeroImage() {
        val zero = GrayF(10, 10)
        assertEquals(0f, Denoise.bilateral(zero, 2f, 0.1f).range().second, 1e-5f)
        assertEquals(0f, Denoise.median(zero, 2).range().second, 1e-5f)
        assertEquals(0f, Denoise.anisotropicDiffusion(zero, 3, 0.05f).range().second, 1e-5f)
        assertEquals(0f, Denoise.despeckle(zero, 1, 0.01f).range().second, 1e-5f)
    }

    @Test
    fun aZeroRangeSigmaDoesNotDivideByZero() {
        // Wp collapses to the centre tap alone; the guard is what stops this returning NaN.
        val out = Denoise.bilateral(step(8, 8, 4), 2f, 0f)
        for (v in out.data) assertTrue(!v.isNaN() && !v.isInfinite(), "got $v")
    }
}
