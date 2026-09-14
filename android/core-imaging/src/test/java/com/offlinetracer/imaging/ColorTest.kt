package com.offlinetracer.imaging

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Colour conversions are tested against the closed-form answers in ALGORITHMS §1, never against a
 * snapshot: a snapshot of a wrong matrix is indistinguishable from a snapshot of a right one.
 */
class ColorTest {

    private fun opaque(r: Int, g: Int, b: Int): Int = RgbaImage.argb(255, r, g, b)

    @Test
    fun lumaIsRec601() {
        val img = RgbaImage(3, 1, intArrayOf(opaque(255, 0, 0), opaque(0, 255, 0), opaque(0, 0, 255)))
        val g = Color.toGray(img)
        assertEquals(0.299f, g[0, 0], 1e-3f)
        assertEquals(0.587f, g[1, 0], 1e-3f)
        assertEquals(0.114f, g[2, 0], 1e-3f)
    }

    @Test
    fun lumaOfGreyIsTheGreyItself() {
        // The three weights sum to exactly 1, so any neutral colour must survive unchanged.
        val img = RgbaImage(1, 1, intArrayOf(opaque(128, 128, 128)))
        assertEquals(128f / 255f, Color.toGray(img)[0, 0], 1e-4f)
    }

    @Test
    fun linearLuminanceIsDarkerThanLumaForMidGrey() {
        // sRGB 0.5 linearises to ~0.214; if the two agree, one of them is not doing its transfer.
        val img = RgbaImage(1, 1, intArrayOf(opaque(128, 128, 128)))
        assertEquals(0.2140f, Color.toGrayLinear(img)[0, 0], 5e-3f)
    }

    @Test
    fun linearizeMatchesTheStandardCurve() {
        assertEquals(0f, Color.linearize(0f), 1e-6f)
        assertEquals(1f, Color.linearize(1f), 1e-6f)
        assertEquals(0.04045f / 12.92f, Color.linearize(0.04045f), 1e-6f)
        assertEquals(0f, Color.delinearize(0f), 1e-6f)
        assertEquals(1f, Color.delinearize(1f), 1e-6f)
    }

    @Test
    fun linearizeAndDelinearizeRoundTrip() {
        for (i in 0..100) {
            val c = i / 100f
            assertEquals(c, Color.delinearize(Color.linearize(c)), 1e-4f)
        }
    }

    @Test
    fun labOfWhiteAndBlackAreTheEndsOfTheLightnessAxis() {
        val out = FloatArray(3)
        Color.srgbToLab(1f, 1f, 1f, out)
        assertEquals(100f, out[0], 0.05f)
        assertEquals(0f, out[1], 0.05f)
        assertEquals(0f, out[2], 0.05f)

        Color.srgbToLab(0f, 0f, 0f, out)
        assertEquals(0f, out[0], 0.05f)
        assertEquals(0f, out[1], 0.05f)
        assertEquals(0f, out[2], 0.05f)
    }

    @Test
    fun labOfMidGreyIsAboutFiftyThree() {
        val out = FloatArray(3)
        Color.srgbToLab(0.5f, 0.5f, 0.5f, out)
        assertEquals(53.39f, out[0], 0.5f)
        assertTrue(abs(out[1]) < 0.5f && abs(out[2]) < 0.5f, "neutral grey must have no chroma")
    }

    @Test
    fun labOfRedHasPositiveAAndB() {
        val out = FloatArray(3)
        Color.srgbToLab(1f, 0f, 0f, out)
        assertEquals(53.24f, out[0], 0.5f)
        assertEquals(80.09f, out[1], 0.8f)
        assertEquals(67.20f, out[2], 0.8f)
    }

    @Test
    fun labDistanceIsPlainEuclidean() {
        assertEquals(0f, Color.labDistance(50f, 10f, -5f, 50f, 10f, -5f), 1e-5f)
        assertEquals(5f, Color.labDistance(0f, 0f, 0f, 3f, 4f, 0f), 1e-5f)
        assertEquals(13f, Color.labDistance(1f, 1f, 1f, 1f, 6f, 13f), 1e-4f)
    }

    @Test
    fun labPlanesAgreeWithTheScalarConversion() {
        val img = RgbaImage(2, 1, intArrayOf(opaque(200, 30, 60), opaque(10, 240, 90)))
        val planes = Color.toLabPlanes(img)
        assertEquals(3, planes.size)
        assertEquals(2, planes[0].size)
        val out = FloatArray(3)
        Color.srgbToLab(200 / 255f, 30 / 255f, 60 / 255f, out)
        assertEquals(out[0], planes[0][0], 1e-3f)
        assertEquals(out[1], planes[1][0], 1e-3f)
        assertEquals(out[2], planes[2][0], 1e-3f)
    }

    @Test
    fun channelExtractsTheRightComponent() {
        val img = RgbaImage(1, 1, intArrayOf(RgbaImage.argb(64, 200, 100, 50)))
        assertEquals(200 / 255f, Color.channel(img, Channel.RED)[0, 0], 1e-4f)
        assertEquals(100 / 255f, Color.channel(img, Channel.GREEN)[0, 0], 1e-4f)
        assertEquals(50 / 255f, Color.channel(img, Channel.BLUE)[0, 0], 1e-4f)
        assertEquals(64 / 255f, Color.channel(img, Channel.ALPHA)[0, 0], 1e-4f)
        assertEquals(200 / 255f, Color.channel(img, Channel.MAX)[0, 0], 1e-4f)
        assertEquals(50 / 255f, Color.channel(img, Channel.MIN)[0, 0], 1e-4f)
        assertEquals(200 / 255f, Color.channel(img, Channel.VALUE)[0, 0], 1e-4f)
        // HSV saturation of (200,100,50) is (max-min)/max = 150/200.
        assertEquals(0.75f, Color.channel(img, Channel.SATURATION)[0, 0], 1e-3f)
        assertEquals(Color.toGray(img)[0, 0], Color.channel(img, Channel.LUMA)[0, 0], 1e-4f)
    }

    @Test
    fun saturationOfBlackIsZeroAndNotNaN() {
        val img = RgbaImage(1, 1, intArrayOf(opaque(0, 0, 0)))
        val s = Color.channel(img, Channel.SATURATION)[0, 0]
        assertTrue(!s.isNaN(), "saturation of black divides by max; it must be guarded")
        assertEquals(0f, s, 1e-6f)
    }

    @Test
    fun grayToRgbaAndBackIsIdentityWithinQuantisation() {
        val g = GrayF(4, 1, floatArrayOf(0f, 0.25f, 0.5f, 1f))
        val back = Color.toGray(Color.toRgba(g))
        for (i in 0 until 4) assertEquals(g.data[i], back.data[i], 1.5f / 255f)
    }

    @Test
    fun toRgbaOpaqueSetsFullAlpha() {
        val img = Color.toRgba(GrayF(1, 1, floatArrayOf(0.5f)), opaque = true)
        assertEquals(255, RgbaImage.alphaOf(img[0, 0]))
    }

    @Test
    fun alphaOfAndWithAlphaAreInverses() {
        val img = RgbaImage(2, 1, intArrayOf(RgbaImage.argb(10, 1, 2, 3), RgbaImage.argb(250, 4, 5, 6)))
        val a = Color.alphaOf(img)
        assertEquals(10 / 255f, a[0, 0], 1e-4f)
        assertEquals(250 / 255f, a[1, 0], 1e-4f)
        val rebuilt = Color.withAlpha(img, a)
        assertEquals(10, RgbaImage.alphaOf(rebuilt[0, 0]))
        assertEquals(250, RgbaImage.alphaOf(rebuilt[1, 0]))
        assertEquals(RgbaImage.redOf(img[1, 0]), RgbaImage.redOf(rebuilt[1, 0]))
    }

    @Test
    fun everythingSurvivesAOnePixelImage() {
        val img = RgbaImage(1, 1, intArrayOf(opaque(17, 200, 3)))
        Color.toGray(img)
        Color.toGrayLinear(img)
        Color.alphaOf(img)
        Color.toLabPlanes(img)
        Color.channel(img, Channel.SATURATION)
        Color.withAlpha(img, GrayF(1, 1, floatArrayOf(0.5f)))
        Color.toRgba(GrayF(1, 1))
    }

    @Test
    fun everythingSurvivesAnAllZeroImage() {
        val img = RgbaImage(4, 4)
        assertEquals(0f, Color.toGray(img).range().second, 1e-6f)
        assertEquals(0f, Color.toGrayLinear(img).range().second, 1e-6f)
        val planes = Color.toLabPlanes(img)
        assertEquals(3, planes.size)
    }
}
