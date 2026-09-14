package com.offlinetracer.imaging

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Radix-2 Cooley–Tukey FFT (ALGORITHMS §8). The tests are scale-agnostic wherever the normalisation
 * convention is not pinned by the contract: only the round trip is required to reproduce the input,
 * which is the property the saliency stage actually depends on.
 */
class FftTest {

    /** Deterministic pseudo-data — no RNG anywhere in this project, tests included. */
    private fun sample(n: Int): FloatArray {
        val a = FloatArray(n)
        for (i in 0 until n) a[i] = ((i * 37) % 17) / 17f - 0.5f + 0.01f * (i % 5)
        return a
    }

    @Test
    fun nextPowerOfTwoRoundsUp() {
        assertEquals(2, Fft.nextPowerOfTwo(2))
        assertEquals(4, Fft.nextPowerOfTwo(3))
        assertEquals(4, Fft.nextPowerOfTwo(4))
        assertEquals(8, Fft.nextPowerOfTwo(5))
        assertEquals(64, Fft.nextPowerOfTwo(64))
        assertEquals(128, Fft.nextPowerOfTwo(65))
        assertEquals(1024, Fft.nextPowerOfTwo(1000))
    }

    @Test
    fun forwardThenInverseRoundTripsToTheInput() {
        for (n in intArrayOf(2, 4, 16, 64, 256)) {
            val re = sample(n)
            val im = sample(n + 3).copyOf(n)
            val re0 = re.copyOf()
            val im0 = im.copyOf()
            Fft.transform(re, im, inverse = false)
            Fft.transform(re, im, inverse = true)
            for (i in 0 until n) {
                assertEquals(re0[i], re[i], 1e-4f, "real[$i] at n=$n")
                assertEquals(im0[i], im[i], 1e-4f, "imag[$i] at n=$n")
            }
        }
    }

    @Test
    fun theSpectrumOfADeltaIsFlat() {
        val n = 32
        val re = FloatArray(n)
        val im = FloatArray(n)
        re[0] = 1f
        Fft.transform(re, im, inverse = false)
        val first = sqrt(re[0] * re[0] + im[0] * im[0])
        assertTrue(first > 0f, "a delta must not transform to nothing")
        for (i in 0 until n) {
            val mag = sqrt(re[i] * re[i] + im[i] * im[i])
            assertEquals(first, mag, 1e-4f, "bin $i")
        }
    }

    @Test
    fun theSpectrumOfAConstantIsOnlyTheDcBin() {
        val n = 32
        val re = FloatArray(n) { 0.25f }
        val im = FloatArray(n)
        Fft.transform(re, im, inverse = false)
        val dc = sqrt(re[0] * re[0] + im[0] * im[0])
        assertTrue(dc > 0f)
        for (i in 1 until n) {
            val mag = sqrt(re[i] * re[i] + im[i] * im[i])
            assertTrue(mag < dc * 1e-4f + 1e-5f, "bin $i held $mag against a DC of $dc")
        }
    }

    @Test
    fun aSingleSinusoidLandsInExactlyTwoBins() {
        val n = 64
        val k = 5
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until n) re[i] = kotlin.math.cos(2.0 * Math.PI * k * i / n).toFloat()
        Fft.transform(re, im, inverse = false)
        val peak = sqrt(re[k] * re[k] + im[k] * im[k])
        assertTrue(peak > 0f)
        for (i in 0 until n) {
            if (i == k || i == n - k) continue
            val mag = sqrt(re[i] * re[i] + im[i] * im[i])
            assertTrue(mag < peak * 1e-3f + 1e-4f, "leakage into bin $i: $mag against $peak")
        }
    }

    @Test
    fun theTransformIsLinear() {
        val n = 16
        val aRe = sample(n)
        val bRe = FloatArray(n) { (it % 3).toFloat() }
        val sumRe = FloatArray(n) { aRe[it] + bRe[it] }
        val aIm = FloatArray(n)
        val bIm = FloatArray(n)
        val sumIm = FloatArray(n)
        Fft.transform(aRe, aIm, false)
        Fft.transform(bRe, bIm, false)
        Fft.transform(sumRe, sumIm, false)
        for (i in 0 until n) {
            assertEquals(aRe[i] + bRe[i], sumRe[i], 1e-3f, "bin $i")
            assertEquals(aIm[i] + bIm[i], sumIm[i], 1e-3f, "bin $i")
        }
    }

    @Test
    fun twoDimensionalTransformRoundTrips() {
        val w = 8
        val h = 4
        val re = sample(w * h)
        val im = FloatArray(w * h)
        val re0 = re.copyOf()
        Fft.transform2d(re, im, w, h, inverse = false)
        Fft.transform2d(re, im, w, h, inverse = true)
        for (i in 0 until w * h) {
            assertEquals(re0[i], re[i], 1e-4f, "real[$i]")
            assertEquals(0f, im[i], 1e-4f, "imag[$i]")
        }
    }

    @Test
    fun twoDimensionalTransformOfADeltaIsFlat() {
        val w = 16
        val h = 16
        val re = FloatArray(w * h)
        val im = FloatArray(w * h)
        re[0] = 1f
        Fft.transform2d(re, im, w, h, inverse = false)
        val first = sqrt(re[0] * re[0] + im[0] * im[0])
        for (i in 0 until w * h) {
            val mag = sqrt(re[i] * re[i] + im[i] * im[i])
            assertEquals(first, mag, 1e-4f, "bin $i")
        }
    }

    @Test
    fun aSingleElementTransformIsAnIdentity() {
        val re = floatArrayOf(0.75f)
        val im = floatArrayOf(-0.25f)
        Fft.transform(re, im, false)
        assertEquals(0.75f, re[0], 1e-6f)
        assertEquals(-0.25f, im[0], 1e-6f)
    }

    @Test
    fun allZeroInputStaysAllZero() {
        val re = FloatArray(16)
        val im = FloatArray(16)
        Fft.transform(re, im, false)
        for (i in 0 until 16) assertTrue(abs(re[i]) < 1e-9f && abs(im[i]) < 1e-9f)
        Fft.transform2d(re, im, 4, 4, true)
        for (i in 0 until 16) assertTrue(!re[i].isNaN() && !im[i].isNaN())
    }
}
