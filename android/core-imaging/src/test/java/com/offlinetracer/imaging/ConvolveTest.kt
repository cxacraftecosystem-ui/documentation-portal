package com.offlinetracer.imaging

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kernels and derivatives (ALGORITHMS §3). Every assertion here has a closed form: a normalised
 * kernel sums to one, a blur of a constant is that constant, and a derivative operator applied to a
 * linear ramp returns the ramp's slope exactly.
 */
class ConvolveTest {

    /** `v(x, y) = base + sx*x + sy*y` — the only surface whose exact gradient is known by hand. */
    private fun ramp(w: Int, h: Int, base: Float, sx: Float, sy: Float): GrayF {
        val g = GrayF(w, h)
        for (y in 0 until h) for (x in 0 until w) g[x, y] = base + sx * x + sy * y
        return g
    }

    @Test
    fun gaussianKernelIsNormalisedOddAndSymmetric() {
        for (sigma in floatArrayOf(0.6f, 1f, 2f, 3.5f)) {
            val k = Convolve.gaussianKernel(sigma)
            assertTrue(k.size % 2 == 1, "kernel length must be odd, was ${k.size}")
            val radius = ceil(3.0 * sigma).toInt().coerceAtLeast(1)
            assertEquals(2 * radius + 1, k.size, "radius must be ceil(3 sigma) for sigma=$sigma")
            var sum = 0f
            for (v in k) sum += v
            assertEquals(1f, sum, 1e-5f)
            for (i in k.indices) assertEquals(k[i], k[k.size - 1 - i], 1e-6f)
            val centre = k.size / 2
            for (i in k.indices) assertTrue(k[i] <= k[centre] + 1e-6f, "the centre tap must be the peak")
        }
    }

    /**
     * The Double kernel has to be the *same* kernel — same support, same symmetry, same taps to
     * within the Float one's own rounding — because `gaussianBlurDouble` is only ever used to make a
     * blur reproducible across engines, never to change what a blur means.
     */
    @Test
    fun gaussianKernelDoubleIsTheSameKernelCarriedInDouble() {
        for (sigma in floatArrayOf(0f, 0.05f, 0.5f, 1f, 1.6f, 2f, 3.3f)) {
            val k = Convolve.gaussianKernelDouble(sigma)
            val f = Convolve.gaussianKernel(sigma)
            assertEquals(f.size, k.size, "sigma=$sigma must use the same support as the Float kernel")
            for (i in k.indices) {
                assertEquals(k[i], k[k.size - 1 - i], 0.0, "sigma=$sigma tap $i must be exactly symmetric")
                assertEquals(f[i].toDouble(), k[i], 1e-7, "sigma=$sigma tap $i must be the same tap")
            }
            val centre = k.size / 2
            for (v in k) assertTrue(v <= k[centre], "the centre tap must be the peak at sigma=$sigma")
        }
    }

    /**
     * Normalisation has to hold to *Double* precision, not merely to Float precision.
     *
     * This is the whole reason the function exists. XDoG's `1/(1-τ)` rescale is exact only if each
     * kernel sums to exactly 1 — a flat region of intensity `I` must answer `(1-τ)·I` — and at τ = 0.98
     * the rescale multiplies any shortfall by 50 before ε is compared against it. The Float kernel is
     * normalised to about 1e-7, which is 5e-6 after amplification; this one is normalised to 1e-15.
     */
    @Test
    fun gaussianKernelDoubleSumsToOneToDoublePrecision() {
        for (sigma in floatArrayOf(0.5f, 1f, 1.6f, 2f, 3.3f, 8f)) {
            var sum = 0.0
            for (v in Convolve.gaussianKernelDouble(sigma)) sum += v
            assertEquals(1.0, sum, 1e-15, "sigma=$sigma must be normalised in Double, not in Float")
        }
    }

    @Test
    fun gaussianBlurDoubleAgreesWithTheFloatBlurAndIsExactOnAConstant() {
        val src = GrayF(17, 13).fill(0.42f)
        for (sigma in floatArrayOf(0.5f, 1.5f, 4f)) {
            val out = Convolve.gaussianBlurDouble(src, sigma)
            assertEquals(src.data.size, out.size, "sigma=$sigma must return one sample per pixel")
            for (v in out) assertEquals(0.42f.toDouble(), v, 1e-12)
        }
        // Same answer as the Float blur, to the Float blur's own accuracy: this is a precision change,
        // not a different filter.
        val surface = ramp(19, 11, 0.1f, 0.03f, 0.017f)
        val d = Convolve.gaussianBlurDouble(surface, 1.2f)
        val f = Convolve.gaussianBlur(surface, 1.2f).data
        for (i in d.indices) assertEquals(f[i].toDouble(), d[i], 1e-6)
    }

    @Test
    fun gaussianBlurDoubleReproducesTheInputAtADegenerateSigma() {
        // There is no `sigma <= 0.05` short circuit here — the identity kernel runs both passes — so
        // the exactness of `0*a + 1*b + 0*c` is load-bearing rather than incidental.
        val src = ramp(8, 8, 0f, 0.1f, 0.05f)
        for (sigma in floatArrayOf(0f, 0.01f, 0.05f)) {
            val out = Convolve.gaussianBlurDouble(src, sigma)
            for (i in src.data.indices) {
                assertEquals(src.data[i].toDouble(), out[i], 0.0, "sigma=$sigma must be an exact copy")
            }
        }
    }

    @Test
    fun blurringAConstantReturnsTheConstant() {
        val src = GrayF(17, 13).fill(0.42f)
        for (sigma in floatArrayOf(0.5f, 1.5f, 4f)) {
            val out = Convolve.gaussianBlur(src, sigma)
            for (v in out.data) assertEquals(0.42f, v, 1e-4f)
        }
    }

    @Test
    fun aDegenerateSigmaIsACopyNotAOneTapBlur() {
        val src = ramp(8, 8, 0f, 0.1f, 0f)
        val out = Convolve.gaussianBlur(src, 0.01f)
        for (i in src.data.indices) assertEquals(src.data[i], out.data[i], 0f)
    }

    @Test
    fun blurDoesNotShiftASymmetricFeature() {
        // An off-by-one in the separable pass moves the image by a pixel, which is invisible in a
        // snapshot test and catastrophic once the vector output is overlaid on the original.
        val src = GrayF(9, 9)
        src[4, 4] = 1f
        val out = Convolve.gaussianBlur(src, 1f)
        for (d in 1..3) {
            assertEquals(out[4 - d, 4], out[4 + d, 4], 1e-6f)
            assertEquals(out[4, 4 - d], out[4, 4 + d], 1e-6f)
        }
    }

    @Test
    fun boxBlurOfAConstantIsTheConstant() {
        val src = GrayF(11, 9).fill(0.7f)
        for (r in intArrayOf(1, 2, 5)) {
            val out = Convolve.boxBlur(src, r)
            for (v in out.data) assertEquals(0.7f, v, 1e-4f)
        }
    }

    @Test
    fun boxBlurOfRadiusOneIsTheThreeByThreeMean() {
        val src = ramp(7, 7, 0f, 1f, 10f)
        val out = Convolve.boxBlur(src, 1)
        // The mean of a linear surface over a centred window is the centre value.
        for (y in 1 until 6) for (x in 1 until 6) assertEquals(src[x, y], out[x, y], 1e-3f)
    }

    @Test
    fun scharrOnARampReturnsTheExactSlope() {
        // Sign convention (ALGORITHMS §3): gx is positive where intensity DECREASES to the right,
        // so a ramp rising by 0.1 per pixel has gx = -0.1.
        val g = Convolve.gradients(ramp(9, 9, 0.2f, 0.1f, 0f))
        for (y in 1 until 8) for (x in 1 until 8) {
            assertEquals(-0.1f, g.gx[x, y], 1e-4f)
            assertEquals(0f, g.gy[x, y], 1e-4f)
        }
    }

    @Test
    fun scharrOnAVerticalRampReturnsTheExactSlope() {
        val g = Convolve.gradients(ramp(9, 9, 0f, 0f, 0.25f))
        for (y in 1 until 8) for (x in 1 until 8) {
            assertEquals(0f, g.gx[x, y], 1e-4f)
            assertEquals(-0.25f, g.gy[x, y], 1e-4f)
        }
    }

    @Test
    fun sobelOnARampReturnsTheExactSlopeToo() {
        val g = Convolve.gradients(ramp(9, 9, 0f, 0.1f, 0f), GradientOp.SOBEL)
        for (y in 1 until 8) for (x in 1 until 8) assertEquals(-0.1f, g.gx[x, y], 1e-4f)
    }

    @Test
    fun gradientsOfAConstantAreZero() {
        val g = Convolve.gradients(GrayF(6, 6).fill(0.9f))
        for (v in g.gx.data) assertEquals(0f, v, 1e-6f)
        for (v in g.gy.data) assertEquals(0f, v, 1e-6f)
    }

    @Test
    fun magnitudeIsTrueHypotAndNotTheL1Approximation() {
        val gx = GrayF(1, 1, floatArrayOf(3f))
        val gy = GrayF(1, 1, floatArrayOf(4f))
        val m = Convolve.Gradients(gx, gy).magnitude()
        // |gx| + |gy| would be 7 here; the L1 approximation is up to 41% high on diagonals and
        // biases every auto-threshold that consumes it.
        assertEquals(5f, m[0, 0], 1e-5f)
    }

    @Test
    fun directionIsAtan2OfTheGradient() {
        val gx = GrayF(2, 1, floatArrayOf(1f, 0f))
        val gy = GrayF(2, 1, floatArrayOf(1f, -2f))
        val d = Convolve.Gradients(gx, gy).direction()
        assertEquals(atan2(1f, 1f), d[0, 0], 1e-5f)
        assertEquals(atan2(-2f, 0f), d[1, 0], 1e-5f)
    }

    @Test
    fun laplacianOfALinearSurfaceIsZero() {
        val out = Convolve.laplacian(ramp(9, 9, 0.5f, 0.05f, -0.03f))
        for (y in 1 until 8) for (x in 1 until 8) assertEquals(0f, out[x, y], 1e-4f)
    }

    @Test
    fun laplacianOfAPeakIsNegativeAtTheCentre() {
        val src = GrayF(5, 5)
        src[2, 2] = 1f
        val out = Convolve.laplacian(src)
        assertTrue(out[2, 2] < 0f, "the 8-neighbour kernel has -8 at the centre; got ${out[2, 2]}")
        assertTrue(out[1, 2] > 0f)
    }

    @Test
    fun convolve3WithADeltaKernelIsACopy() {
        val src = ramp(6, 6, 0f, 1f, 3f)
        val k = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
        val out = Convolve.convolve3(src, k)
        for (i in src.data.indices) assertEquals(src.data[i], out.data[i], 1e-5f)
    }

    @Test
    fun separableWithUnitKernelsIsACopy() {
        val src = ramp(6, 5, 0.1f, 0.2f, 0.3f)
        val out = Convolve.separable(src, floatArrayOf(1f), floatArrayOf(1f))
        for (i in src.data.indices) assertEquals(src.data[i], out.data[i], 1e-5f)
    }

    @Test
    fun separableAveragingKernelsMatchTheBoxMean() {
        val src = ramp(7, 7, 0f, 1f, 2f)
        val third = floatArrayOf(1f / 3f, 1f / 3f, 1f / 3f)
        val out = Convolve.separable(src, third, third)
        for (y in 1 until 6) for (x in 1 until 6) assertEquals(src[x, y], out[x, y], 1e-3f)
    }

    @Test
    fun summedAreaTableHoldsThePrefixSums() {
        val src = GrayF(3, 2, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        val sat = Convolve.summedAreaTable(src)
        assertEquals((3 + 1) * (2 + 1), sat.size)
        // The border row and column are zero and sat[(y+1)*(w+1) + x+1] is the sum over [0..x],[0..y].
        for (i in 0..3) assertEquals(0.0, sat[i], 1e-9)
        assertEquals(1.0, sat[1 * 4 + 1], 1e-9)
        assertEquals(3.0, sat[1 * 4 + 2], 1e-9)
        assertEquals(6.0, sat[1 * 4 + 3], 1e-9)
        assertEquals(21.0, sat[2 * 4 + 3], 1e-9)
    }

    @Test
    fun rectSumIsInclusiveOfBothCorners() {
        val src = GrayF(4, 4).fill(1f)
        val sat = Convolve.summedAreaTable(src)
        assertEquals(16.0, Convolve.rectSum(sat, 4, 4, 0, 0, 3, 3), 1e-9)
        assertEquals(1.0, Convolve.rectSum(sat, 4, 4, 2, 2, 2, 2), 1e-9)
        assertEquals(4.0, Convolve.rectSum(sat, 4, 4, 1, 1, 2, 2), 1e-9)
    }

    @Test
    fun everyOperatorSurvivesAOnePixelImage() {
        val one = GrayF(1, 1, floatArrayOf(0.5f))
        assertEquals(0.5f, Convolve.gaussianBlur(one, 2f)[0, 0], 1e-4f)
        assertEquals(0.5f, Convolve.boxBlur(one, 3)[0, 0], 1e-4f)
        assertEquals(0f, Convolve.laplacian(one)[0, 0], 1e-4f)
        val g = Convolve.gradients(one)
        assertEquals(0f, g.magnitude()[0, 0], 1e-5f)
        Convolve.convolve3(one, FloatArray(9) { 1f / 9f })
        Convolve.separable(one, floatArrayOf(0.5f, 0.5f), floatArrayOf(1f))
        assertEquals(4, Convolve.summedAreaTable(one).size)
    }

    @Test
    fun everyOperatorSurvivesAnAllZeroImage() {
        val zero = GrayF(8, 8)
        assertEquals(0f, Convolve.gaussianBlur(zero, 1.5f).range().second, 1e-6f)
        assertEquals(0f, Convolve.boxBlur(zero, 2).range().second, 1e-6f)
        assertEquals(0f, Convolve.laplacian(zero).range().second, 1e-6f)
        val g = Convolve.gradients(zero)
        assertEquals(0f, g.magnitude().range().second, 1e-6f)
        for (v in g.direction().data) assertTrue(!v.isNaN(), "atan2(0,0) must not produce NaN")
        assertEquals(0.0, Convolve.rectSum(Convolve.summedAreaTable(zero), 8, 8, 0, 0, 7, 7), 1e-9)
    }
}
