package com.offlinetracer.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Background matting (ALGORITHMS §8). */
class MatteTest {

    private fun opaque(r: Int, g: Int, b: Int): Int = RgbaImage.argb(255, r, g, b)

    /** A uniform background with a strongly different square in the middle. */
    private fun subjectOnFlatBackground(): RgbaImage {
        val img = RgbaImage(24, 24).fill(opaque(250, 250, 245))
        for (y in 8..15) for (x in 8..15) img[x, y] = opaque(200, 20, 30)
        return img
    }

    /** [size]² of paper with a [blob]² subject centred on it — big enough for a real guided filter. */
    private fun centredSubject(size: Int, blob: Int): RgbaImage {
        val img = RgbaImage(size, size).fill(opaque(248, 246, 240))
        val lo = (size - blob) / 2
        for (y in lo until lo + blob) for (x in lo until lo + blob) img[x, y] = opaque(30, 60, 160)
        return img
    }

    @Test
    fun borderFloodRemovesTheBackgroundAndKeepsTheSubject() {
        val alpha = Matte.borderFlood(subjectOnFlatBackground(), 0.15f, feather = 0f)
        assertEquals(24, alpha.width)
        assertEquals(0f, alpha[0, 0], 1e-5f)
        assertEquals(0f, alpha[23, 23], 1e-5f)
        assertEquals(0f, alpha[4, 12], 1e-5f)
        assertEquals(1f, alpha[12, 12], 1e-5f)
        assertEquals(1f, alpha[8, 8], 1e-5f)
    }

    @Test
    fun borderFloodFollowsAVignette() {
        // The whole point of comparing against the RUNNING MEAN: the background drifts from white at
        // the top to mid grey at the bottom, a total Lab distance far beyond the tolerance, and a
        // flood that compares against the seed colour stops halfway down.
        val w = 24
        val h = 24
        val img = RgbaImage(w, h)
        for (y in 0 until h) {
            val level = 255 - (55 * y) / (h - 1)
            for (x in 0 until w) img[x, y] = opaque(level, level, level)
        }
        for (y in 9..14) for (x in 9..14) img[x, y] = opaque(220, 0, 0)

        val alpha = Matte.borderFlood(img, 0.15f, feather = 0f)
        assertEquals(0f, alpha[12, 0], 1e-5f)
        assertEquals(0f, alpha[12, h - 1], 1e-5f)
        assertEquals(0f, alpha[0, h - 1], 1e-5f)
        assertEquals(1f, alpha[12, 12], 1e-5f)
    }

    @Test
    fun borderFloodWithZeroToleranceKeepsAlmostEverything() {
        val alpha = Matte.borderFlood(subjectOnFlatBackground(), 0f, feather = 0f)
        assertEquals(1f, alpha[12, 12], 1e-5f)
    }

    @Test
    fun borderFloodOfAUniformImageRefusesToDeleteEverything() {
        // Flooding the whole frame means "remove the entire artwork", which is never the answer the
        // user wanted; the guard returns an opaque matte instead.
        val alpha = Matte.borderFlood(RgbaImage(16, 16).fill(opaque(30, 30, 30)), 0.5f, feather = 0f)
        for (v in alpha.data) assertEquals(1f, v, 1e-5f)
    }

    @Test
    fun borderFloodFeatherSoftensTheEdgeWithoutLeavingTheRange() {
        val alpha = Matte.borderFlood(subjectOnFlatBackground(), 0.15f, feather = 2f)
        for (v in alpha.data) assertTrue(v >= -1e-5f && v <= 1f + 1e-5f, "alpha out of range: $v")
        assertTrue(alpha[12, 12] > 0.8f, "the middle of the subject must stay opaque")
        assertTrue(alpha[0, 0] < 0.1f, "the corner must stay transparent")
        val edge = alpha[8, 12]
        assertTrue(edge > 0.05f && edge < 0.99f, "the boundary must be feathered, got $edge")
    }

    @Test
    fun spectralSaliencyIsNormalisedAndFullSize() {
        val img = RgbaImage(40, 30).fill(opaque(240, 240, 240))
        for (y in 12..18) for (x in 16..24) img[x, y] = opaque(20, 20, 20)
        val sal = Matte.spectralSaliency(img)
        assertEquals(40, sal.width)
        assertEquals(30, sal.height)
        val range = sal.range()
        assertTrue(range.first >= -1e-5f && range.second <= 1f + 1e-5f, "saliency must be 0..1, got $range")
        // The map is normalised at proxy resolution and then resampled, so the peak survives the
        // resample only approximately; what must not happen is a map that never leaves the floor.
        assertTrue(range.second > 0.5f, "the normalisation must use the full range, got ${range.second}")
    }

    @Test
    fun spectralSaliencyOfAConstantImageIsEmptyRatherThanNaN() {
        // Exactly 0, not merely small: with no dynamic range the log-spectrum is the ln(0 + 1e-8)
        // floor and the residual is undefined, so Matte returns a zeroed map instead of normalising
        // by a span (~1.4e-3 of its own magnitude here) that is entirely the floor's own artefact.
        val sal = Matte.spectralSaliency(RgbaImage(32, 32).fill(opaque(128, 128, 128)))
        for (v in sal.data) {
            assertTrue(!v.isNaN() && !v.isInfinite(), "got $v")
            assertEquals(0f, v, 1e-5f)
        }
    }

    @Test
    fun spectralSaliencyAcceptsANonPowerOfTwoProxySize() {
        val img = RgbaImage(20, 20).fill(opaque(200, 200, 200))
        for (y in 8..11) for (x in 8..11) img[x, y] = opaque(10, 10, 10)
        val sal = Matte.spectralSaliency(img, 48)
        assertEquals(20, sal.width)
        for (v in sal.data) assertTrue(!v.isNaN(), "zero padding must be cropped, not left in")
    }

    @Test
    fun saliencyMatteReturnsAnAlphaInRange() {
        val img = RgbaImage(48, 48).fill(opaque(245, 245, 245))
        for (y in 18..30) for (x in 18..30) img[x, y] = opaque(15, 15, 15)
        val alpha = Matte.saliencyMatte(img, 0.5f, 1f)
        assertEquals(48, alpha.width)
        for (v in alpha.data) assertTrue(v >= -1e-5f && v <= 1f + 1e-5f, "alpha out of range: $v")
    }

    @Test
    fun saliencyMatteRefusesToReturnAnEmptyMatte() {
        // Nothing is salient in a flat image; erasing the whole frame is the worst possible answer.
        val alpha = Matte.saliencyMatte(RgbaImage(32, 32).fill(opaque(100, 100, 100)), 0.5f, 0f)
        for (v in alpha.data) assertEquals(1f, v, 1e-5f)
    }

    @Test
    fun applyMatteToGrayIsALinearBlend() {
        val src = GrayF(3, 1, floatArrayOf(1f, 1f, 1f))
        val alpha = GrayF(3, 1, floatArrayOf(1f, 0f, 0.25f))
        val out = Matte.applyMatte(src, alpha, 0f)
        assertEquals(1f, out[0, 0], 1e-6f)
        assertEquals(0f, out[1, 0], 1e-6f)
        assertEquals(0.25f, out[2, 0], 1e-6f)
    }

    @Test
    fun applyMatteToGrayClampsAlphaRatherThanExtrapolating() {
        val out = Matte.applyMatte(GrayF(2, 1, floatArrayOf(1f, 1f)), GrayF(2, 1, floatArrayOf(4f, -3f)), 0f)
        assertEquals(1f, out[0, 0], 1e-6f)
        assertEquals(0f, out[1, 0], 1e-6f)
    }

    @Test
    fun applyMatteToRgbaCompositesOverTheBackground() {
        val src = RgbaImage(3, 1, intArrayOf(opaque(255, 0, 0), opaque(255, 0, 0), opaque(255, 0, 0)))
        val alpha = GrayF(3, 1, floatArrayOf(1f, 0f, 0.5f))
        val out = Matte.applyMatte(src, alpha, opaque(0, 0, 255))
        assertEquals(255, RgbaImage.redOf(out[0, 0]))
        assertEquals(0, RgbaImage.blueOf(out[0, 0]))
        assertEquals(0, RgbaImage.redOf(out[1, 0]))
        assertEquals(255, RgbaImage.blueOf(out[1, 0]))
        assertTrue(kotlin.math.abs(RgbaImage.redOf(out[2, 0]) - 128) <= 2)
        assertTrue(kotlin.math.abs(RgbaImage.blueOf(out[2, 0]) - 128) <= 2)
        assertEquals(255, RgbaImage.alphaOf(out[2, 0]))
    }

    @Test
    fun applyMatteToRgbaOverATransparentBackgroundCutsOut() {
        val src = RgbaImage(2, 1, intArrayOf(opaque(10, 20, 30), opaque(10, 20, 30)))
        val out = Matte.applyMatte(src, GrayF(2, 1, floatArrayOf(1f, 0f)), 0)
        assertEquals(255, RgbaImage.alphaOf(out[0, 0]))
        assertEquals(10, RgbaImage.redOf(out[0, 0]))
        assertEquals(0, RgbaImage.alphaOf(out[1, 0]))
    }

    @Test
    fun applyMatteRespectsTheSourceAlpha() {
        val src = RgbaImage(1, 1, intArrayOf(RgbaImage.argb(0, 255, 255, 255)))
        val out = Matte.applyMatte(src, GrayF(1, 1, floatArrayOf(1f)), 0)
        assertEquals(0, RgbaImage.alphaOf(out[0, 0]), "an already transparent pixel must stay transparent")
    }

    @Test
    fun everythingSurvivesAOnePixelImage() {
        val one = RgbaImage(1, 1, intArrayOf(opaque(20, 40, 60)))
        Matte.borderFlood(one, 0.2f)
        val sal = Matte.spectralSaliency(one)
        assertEquals(1, sal.width)
        for (v in sal.data) assertTrue(!v.isNaN())
        Matte.saliencyMatte(one)
        Matte.applyMatte(one, GrayF(1, 1, floatArrayOf(0.5f)), opaque(0, 0, 0))
        Matte.applyMatte(GrayF(1, 1, floatArrayOf(0.5f)), GrayF(1, 1, floatArrayOf(0.5f)), 1f)
    }

    @Test
    fun everythingSurvivesAnAllZeroImage() {
        val zero = RgbaImage(16, 16)
        for (v in Matte.borderFlood(zero, 0.2f).data) assertTrue(!v.isNaN())
        for (v in Matte.spectralSaliency(zero).data) assertTrue(!v.isNaN())
        for (v in Matte.saliencyMatte(zero).data) assertTrue(!v.isNaN())
    }

    // ---------------------------------------------------------------------------------------------
    // borderLikeness — the cue that has no connectivity assumption in it
    // ---------------------------------------------------------------------------------------------

    @Test
    fun borderLikenessSeparatesBackgroundColourFromSubjectColour() {
        val img = RgbaImage(40, 40).fill(opaque(250, 250, 245))
        for (y in 14..25) for (x in 14..25) img[x, y] = opaque(200, 20, 30)
        val like = Matte.borderLikeness(img)
        assertEquals(40, like.width)
        assertTrue(like[0, 0] > 0.5f, "the paper colour must read as border-like, got ${like[0, 0]}")
        assertEquals(0f, like[20, 20], 1e-5f)
    }

    @Test
    fun borderLikenessStillCallsASubjectTouchingAnEdgeForeground() {
        // The whole reason this cue exists. A flood seeded from the border is one bad seed away from
        // eating a subject that touches the frame; a per-pixel colour posterior is not, because the
        // subject's colour is far denser inside the frame than in the band even when it reaches the
        // edge. Worked through for this image: the band holds 48 of the 336 subject pixels
        // (48/304 = 0.158 of the band) against 288 in the interior (288/1296 = 0.222), so the
        // posterior is 0.158/(0.158+0.222) = 0.42 — below 0.5, i.e. foreground.
        val img = RgbaImage(40, 40).fill(opaque(250, 250, 245))
        for (y in 8..31) for (x in 0..13) img[x, y] = opaque(200, 20, 30)
        val like = Matte.borderLikeness(img)
        assertTrue(like[6, 20] < 0.5f, "a subject touching an edge must stay foreground, got ${like[6, 20]}")
        assertTrue(like[36, 4] > 0.5f, "the paper must still read as background, got ${like[36, 4]}")
    }

    @Test
    fun borderLikenessHasNoOpinionWhenThereIsNoInterior() {
        // Under 3 px the band and the frame are the same pixels, so there is nothing to contrast.
        // 0.5 everywhere is "no information", which is what makes the fusion fall back on its other
        // cues instead of on a number this function invented.
        for (v in Matte.borderLikeness(RgbaImage(2, 2).fill(opaque(10, 20, 30))).data) {
            assertEquals(0.5f, v, 1e-6f)
        }
        for (v in Matte.borderLikeness(RgbaImage(1, 1).fill(opaque(10, 20, 30))).data) {
            assertEquals(0.5f, v, 1e-6f)
        }
    }

    @Test
    fun borderLikenessOfAUniformImageIsUndecidedRatherThanCertain() {
        // One colour everywhere means the band and the interior have identical densities, so the
        // posterior is exactly 0.5. Anything else here would let a flat scan be declared all
        // background.
        for (v in Matte.borderLikeness(RgbaImage(32, 32).fill(opaque(128, 128, 128))).data) {
            assertEquals(0.5f, v, 1e-5f)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // guidedFilter — boundary refinement
    // ---------------------------------------------------------------------------------------------

    @Test
    fun guidedFilterRebuildsTheAlphaEdgeOnTheLuminanceEdge() {
        // The guide steps at x = 24; the input mask steps two pixels late, at x = 26. The property
        // that matters downstream is where the alpha's *transition* is, because that is what gets
        // traced — so the assertion is on the steepest step, not on the 0.5 crossing, which is a
        // level and moves toward the edge rather than onto it (see `guidedFilter`).
        val w = 48
        val h = 24
        val guide = GrayF(w, h)
        val input = GrayF(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                guide[x, y] = if (x < 24) 0f else 1f
                input[x, y] = if (x < 26) 0f else 1f
            }
        }

        val out = Matte.guidedFilter(guide, input, 4, 1e-4f)
        var steepestAt = -1
        var steepest = Float.NEGATIVE_INFINITY
        var crossing = -1
        for (x in 0 until w - 1) {
            val step = out[x + 1, 12] - out[x, 12]
            if (step > steepest) {
                steepest = step
                steepestAt = x + 1
            }
            if (crossing < 0 && out[x, 12] < 0.5f && out[x + 1, 12] >= 0.5f) crossing = x + 1
        }
        assertEquals(24, steepestAt, "the alpha's transition must be rebuilt on the object's edge")
        assertTrue(steepest > 0.2f, "the rebuilt transition must be a real step, got $steepest")
        assertTrue(crossing in 24..25, "the 0.5 level must move to the edge, got $crossing (was 26)")
        assertTrue(out[4, 12] < 0.05f, "well inside the dark side must be background, got ${out[4, 12]}")
        assertTrue(out[44, 12] > 0.85f, "well inside the light side must be subject, got ${out[44, 12]}")
    }

    @Test
    fun guidedFilterWithZeroRadiusIsTheIdentity() {
        val input = GrayF(4, 3, FloatArray(12) { it * 0.05f })
        val out = Matte.guidedFilter(GrayF(4, 3).fill(0.5f), input, 0, 1e-4f)
        for (i in input.data.indices) assertEquals(input.data[i], out.data[i], 1e-7f)
    }

    @Test
    fun guidedFilterOfAFlatGuideCannotDivideByZero() {
        // var(I) is exactly 0 everywhere here, so `cov / (var + eps)` is the one place this function
        // can produce an Infinity. eps carries it; eps = 0 must fall back to a = 0 rather than NaN.
        val flat = GrayF(8, 8).fill(0.25f)
        val p = GrayF(8, 8).fill(0.75f)
        for (v in Matte.guidedFilter(flat, p, 2, 0f).data) {
            assertTrue(!v.isNaN() && !v.isInfinite(), "got $v")
            assertEquals(0.75f, v, 1e-4f)
        }
    }

    @Test
    fun guidedFilterRejectsASizeMismatch() {
        assertFailsWith<IllegalArgumentException> {
            Matte.guidedFilter(GrayF(4, 4), GrayF(4, 5), 2, 1e-4f)
        }
    }

    @Test
    fun guidedFilterSurvivesAOnePixelImage() {
        val out = Matte.guidedFilter(GrayF(1, 1, floatArrayOf(0.3f)), GrayF(1, 1, floatArrayOf(0.8f)), 4, 1e-4f)
        assertTrue(!out.data[0].isNaN(), "a radius larger than the image must clamp, not overflow")
    }

    // ---------------------------------------------------------------------------------------------
    // subjectMatte — the fused matte and its confidence
    // ---------------------------------------------------------------------------------------------

    @Test
    fun subjectMatteKeepsTheSubjectRemovesTheBackgroundAndSaysSo() {
        val r = Matte.subjectMatte(centredSubject(96, 32))
        assertEquals(96, r.alpha.width)
        assertTrue(r.alpha[48, 48] > 0.9f, "the middle of the subject must survive, got ${r.alpha[48, 48]}")
        assertTrue(r.alpha[2, 2] < 0.1f, "the corner must be removed, got ${r.alpha[2, 2]}")
        // 32x32 of 96x96 is 11.1%; the refinement moves the boundary by a pixel or two either way.
        assertTrue(r.coverage in 0.05f..0.25f, "coverage out of the expected band: ${r.coverage}")
        assertTrue(r.confident, "a clean subject on plain paper must be confident, got ${r.confidence}")
        assertTrue(r.reason.isNotEmpty())
        for (v in r.alpha.data) assertTrue(v >= -1e-6f && v <= 1f + 1e-6f, "alpha out of range: $v")
    }

    @Test
    fun subjectMatteOnAFlatImageKeepsEverythingAndRefusesToBeBelieved() {
        // Nothing to separate. The alpha must stay opaque — deleting a flat scan is the worst answer
        // available — and the confidence must be low enough that a caller acting on `confident`
        // never applies it.
        val r = Matte.subjectMatte(RgbaImage(64, 64).fill(opaque(140, 140, 140)))
        for (v in r.alpha.data) assertTrue(v > 0.9f, "a flat image must be kept whole, got $v")
        assertTrue(!r.confident, "confidence was ${r.confidence}")
        assertTrue(r.reason.isNotEmpty())
    }

    @Test
    fun subjectMatteNeverHandsBackAnEmptyFrame() {
        // The invariant the whole file is built on, checked across every shape of degeneracy: either
        // the matte keeps a believable amount, or it keeps everything. "Keep nothing" is never a
        // legal answer, whatever the input.
        val cases = listOf(
            RgbaImage(1, 1, intArrayOf(opaque(20, 40, 60))),
            RgbaImage(3, 3).fill(opaque(0, 0, 0)),
            RgbaImage(16, 16),
            RgbaImage(64, 64).fill(opaque(255, 255, 255)),
            centredSubject(64, 4),
            centredSubject(64, 62),
            subjectOnFlatBackground(),
        )
        for (img in cases) {
            val r = Matte.subjectMatte(img)
            var sum = 0.0
            for (v in r.alpha.data) {
                assertTrue(!v.isNaN() && !v.isInfinite(), "${img.width}x${img.height}: got $v")
                assertTrue(v >= -1e-6f && v <= 1f + 1e-6f, "${img.width}x${img.height}: got $v")
                sum += v.toDouble()
            }
            val mean = sum / r.alpha.size
            assertTrue(
                mean >= 0.005,
                "${img.width}x${img.height} came back keeping ${mean * 100}% of the frame",
            )
            assertEquals(r.confidence >= Matte.MIN_CONFIDENCE, r.confident)
        }
    }

    @Test
    fun subjectMatteOfAOnePixelImageKeepsItAndAdmitsItLearnedNothing() {
        val r = Matte.subjectMatte(RgbaImage(1, 1, intArrayOf(opaque(20, 40, 60))))
        assertEquals(1f, r.alpha[0, 0], 1e-6f)
        assertEquals(0f, r.confidence, 1e-6f)
        assertTrue(!r.confident)
    }

    @Test
    fun subjectMatteIsDeterministic() {
        val img = centredSubject(96, 32)
        val a = Matte.subjectMatte(img)
        val b = Matte.subjectMatte(img)
        assertEquals(a.coverage, b.coverage, 0f)
        assertEquals(a.confidence, b.confidence, 0f)
        assertEquals(a.reason, b.reason)
        for (i in a.alpha.data.indices) assertEquals(a.alpha.data[i], b.alpha.data[i], 0f)
    }

    @Test
    fun subjectMatteFeatherSoftensWithoutLeavingTheRange() {
        val r = Matte.subjectMatte(centredSubject(96, 32), feather = 3f)
        for (v in r.alpha.data) assertTrue(v >= -1e-6f && v <= 1f + 1e-6f, "alpha out of range: $v")
        assertTrue(r.alpha[48, 48] > 0.8f)
    }

    @Test
    fun borderFloodDoesNotOverflowOnALargeImage() {
        // The flood is 8-connected across the whole frame; the recursive form dies here and the
        // crash presents as a random OOM rather than as a stack overflow.
        val big = RgbaImage(600, 600).fill(opaque(200, 200, 200))
        for (y in 250..350) for (x in 250..350) big[x, y] = opaque(10, 200, 10)
        val alpha = Matte.borderFlood(big, 0.1f, feather = 0f)
        assertEquals(0f, alpha[0, 0], 1e-5f)
        assertEquals(1f, alpha[300, 300], 1e-5f)
    }
}
