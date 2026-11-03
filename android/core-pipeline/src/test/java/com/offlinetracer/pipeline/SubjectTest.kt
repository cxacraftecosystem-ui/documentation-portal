package com.offlinetracer.pipeline

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.RgbaImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubjectTest {

    private fun alphaWith(w: Int, h: Int, x0: Int, y0: Int, bw: Int, bh: Int): GrayF {
        val a = GrayF(w, h)
        for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) a[x, y] = 1f
        return a
    }

    private fun opaque(r: Int, g: Int, b: Int): Int = RgbaImage.argb(255, r, g, b)

    @Test
    fun boundingBoxIsTheTightBoundsPlusAMargin() {
        val box = Subject.boundingBox(alphaWith(40, 40, 12, 14, 10, 10))
        assertTrue(box.confident, box.reason)
        assertEquals(11, box.x)
        assertEquals(13, box.y)
        assertEquals(12, box.w)
        assertEquals(12, box.h)
        assertEquals(100f / 1600f, box.coverage, 1e-6f)
        assertTrue(box.reason.isNotEmpty())
    }

    @Test
    fun aZeroMarginGivesExactlyTheTightBounds() {
        val box = Subject.boundingBox(alphaWith(40, 40, 12, 14, 10, 10), marginFraction = 0f)
        assertEquals(12, box.x)
        assertEquals(14, box.y)
        assertEquals(10, box.w)
        assertEquals(10, box.h)
    }

    @Test
    fun aNegativeMarginIsTreatedAsNoMarginRatherThanShrinkingTheBox() {
        val box = Subject.boundingBox(alphaWith(40, 40, 12, 14, 10, 10), marginFraction = -0.5f)
        assertEquals(12, box.x)
        assertEquals(10, box.w)
    }

    @Test
    fun theMarginScalesWithTheSubjectAndIsTakenFromItsLongerSide() {
        val box = Subject.boundingBox(alphaWith(64, 64, 18, 8, 4, 24))
        assertEquals(17, box.x)
        assertEquals(6, box.w)
        assertEquals(7, box.y)
        assertEquals(26, box.h)
    }

    @Test
    fun theAspectRatioIsNotForced() {
        val box = Subject.boundingBox(alphaWith(60, 60, 27, 10, 6, 40))
        assertTrue(box.confident, box.reason)
        assertTrue(box.h > box.w * 3, "expected a tall box, got ${box.w}x${box.h}")
    }

    @Test
    fun theBoxIsAlwaysALegalSubRectangleOfTheFrame() {
        for (x0 in intArrayOf(0, 7, 24)) {
            for (y0 in intArrayOf(0, 7, 24)) {
                val box = Subject.boundingBox(alphaWith(32, 32, x0, y0, 8, 8), marginFraction = 0.5f)
                assertTrue(box.x >= 0 && box.y >= 0, "negative origin at ($x0,$y0): $box")
                assertTrue(box.w >= 1 && box.h >= 1, "empty box at ($x0,$y0): $box")
                assertTrue(box.x + box.w <= 32, "runs off the right at ($x0,$y0): $box")
                assertTrue(box.y + box.h <= 32, "runs off the bottom at ($x0,$y0): $box")
            }
        }
    }

    @Test
    fun aSubjectInEachCornerIsFoundAndTheMarginClampsToTheFrame() {
        val corners = listOf(0 to 0, 24 to 0, 0 to 24, 24 to 24)
        for ((x0, y0) in corners) {
            val box = Subject.boundingBox(alphaWith(32, 32, x0, y0, 8, 8))
            assertTrue(box.confident, "corner ($x0,$y0): ${box.reason}")
            assertEquals(if (x0 == 0) 0 else 23, box.x, "corner ($x0,$y0) x")
            assertEquals(if (y0 == 0) 0 else 23, box.y, "corner ($x0,$y0) y")
            assertEquals(9, box.w, "corner ($x0,$y0) w")
            assertEquals(9, box.h, "corner ($x0,$y0) h")
        }
    }

    @Test
    fun aSubjectTouchingEachEdgeIsFoundWithoutRunningOffTheFrame() {
        val cases = listOf(
            Triple("top", intArrayOf(0, 0, 40, 12), intArrayOf(0, 0, 40, 14)),
            Triple("bottom", intArrayOf(0, 28, 40, 12), intArrayOf(0, 26, 40, 14)),
            Triple("left", intArrayOf(0, 0, 12, 40), intArrayOf(0, 0, 14, 40)),
            Triple("right", intArrayOf(28, 0, 12, 40), intArrayOf(26, 0, 14, 40)),
        )
        for ((name, sub, want) in cases) {
            val box = Subject.boundingBox(alphaWith(40, 40, sub[0], sub[1], sub[2], sub[3]))
            assertTrue(box.confident, "$name: ${box.reason}")
            assertEquals(want[0], box.x, "$name x")
            assertEquals(want[1], box.y, "$name y")
            assertEquals(want[2], box.w, "$name w")
            assertEquals(want[3], box.h, "$name h")
        }
    }

    @Test
    fun aSubjectReachingEveryEdgeGivesTheFullFrameAndStillCounts() {
        val a = GrayF(32, 32)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val onFrame = x == 0 || y == 0 || x == 31 || y == 31
                a[x, y] = if (onFrame || (x in 8..23 && y in 8..23)) 1f else 0f
            }
        }
        val box = Subject.boundingBox(a)
        assertTrue(box.confident, box.reason)
        assertEquals(0, box.x)
        assertEquals(32, box.w)
        assertEquals(32, box.h)
    }

    @Test
    fun theThresholdDecidesWhatCountsAsSubject() {
        val a = GrayF(40, 40)
        for (y in 10..29) for (x in 10..29) a[x, y] = 0.6f
        assertTrue(Subject.boundingBox(a, threshold = 0.5f).confident)
        assertFalse(
            Subject.boundingBox(a, threshold = 0.7f).confident,
            "nothing is above 0.7, so there is nothing to crop to",
        )
    }

    @Test
    fun boundingBoxIsDeterministic() {
        val a = alphaWith(40, 40, 12, 14, 10, 10)
        assertEquals(Subject.boundingBox(a), Subject.boundingBox(a))
    }

    @Test
    fun anAllForegroundAlphaRefusesToCropAndReturnsTheFullFrame() {
        val box = Subject.boundingBox(GrayF(40, 40).fill(1f))
        assertFalse(box.confident)
        assertEquals(0, box.x)
        assertEquals(0, box.y)
        assertEquals(40, box.w)
        assertEquals(40, box.h)
        assertEquals(1f, box.coverage, 1e-6f)
        assertTrue(box.reason.isNotEmpty(), "a refusal must say why")
    }

    @Test
    fun anAllBackgroundAlphaRefusesToCropAndReturnsTheFullFrame() {
        val box = Subject.boundingBox(GrayF(40, 40))
        assertFalse(box.confident)
        assertEquals(0, box.x)
        assertEquals(40, box.w)
        assertEquals(40, box.h)
        assertEquals(0f, box.coverage, 1e-6f)
        assertTrue(box.reason.isNotEmpty())
    }

    @Test
    fun aSingleLitPixelRefusesHoweverBigTheFrameIs() {
        val big = Subject.boundingBox(alphaWith(200, 200, 100, 100, 1, 1))
        assertFalse(big.confident, big.reason)
        assertEquals(200, big.w)
        assertEquals(200, big.h)

        val small = Subject.boundingBox(alphaWith(4, 4, 2, 2, 1, 1))
        assertFalse(small.confident, small.reason)
        assertEquals(4, small.w)
        assertEquals(4, small.h)
    }

    @Test
    fun aOnePixelFrameRefusesEitherWay() {
        val lit = Subject.boundingBox(GrayF(1, 1, floatArrayOf(1f)))
        assertFalse(lit.confident, lit.reason)
        assertEquals(1, lit.w)
        assertEquals(1, lit.h)

        val dark = Subject.boundingBox(GrayF(1, 1, floatArrayOf(0f)))
        assertFalse(dark.confident, dark.reason)
        assertEquals(1, dark.w)
        assertEquals(1, dark.h)
    }

    @Test
    fun aSubjectTooSmallToBeOneRefuses() {
        val box = Subject.boundingBox(alphaWith(100, 100, 40, 40, 5, 5))
        assertFalse(box.confident, box.reason)
        assertEquals(100, box.w)
        assertEquals(100, box.h)
        assertEquals(25f / 10000f, box.coverage, 1e-6f)
    }

    @Test
    fun anAlphaThatKeptNearlyEverythingRefuses() {
        val box = Subject.boundingBox(alphaWith(100, 100, 0, 0, 99, 99))
        assertFalse(box.confident, box.reason)
        assertEquals(100, box.w)
        assertEquals(100, box.h)
    }

    @Test
    fun everyRefusalReturnsExactlyTheFullFrame() {
        val refusals = listOf(
            GrayF(40, 40).fill(1f),
            GrayF(40, 40),
            alphaWith(40, 40, 20, 20, 1, 1),
            alphaWith(100, 100, 40, 40, 5, 5),
            alphaWith(100, 100, 0, 0, 100, 100),
        )
        for (a in refusals) {
            val box = Subject.boundingBox(a)
            assertFalse(box.confident, box.reason)
            assertEquals(0, box.x)
            assertEquals(0, box.y)
            assertEquals(a.width, box.w)
            assertEquals(a.height, box.h)
            assertTrue(box.reason.length > 20, "a refusal must explain itself: '${box.reason}'")
        }
    }

    @Test
    fun cropReturnsExactlyTheBoxAndKeepsThePixelsInIt() {
        val img = RgbaImage(40, 40).fill(opaque(10, 20, 30))
        img[15, 17] = opaque(200, 100, 50)
        val box = Subject.boundingBox(alphaWith(40, 40, 12, 14, 10, 10))
        val cut = Subject.crop(img, box)
        assertEquals(box.w, cut.width)
        assertEquals(box.h, cut.height)
        assertEquals(opaque(200, 100, 50), cut[15 - box.x, 17 - box.y])
    }

    @Test
    fun croppingWithARefusedBoxIsTheIdentity() {
        val img = RgbaImage(40, 40).fill(opaque(10, 20, 30))
        val box = Subject.boundingBox(GrayF(40, 40))
        val cut = Subject.crop(img, box)
        assertEquals(40, cut.width)
        assertEquals(40, cut.height)
        val gray = Subject.crop(GrayF(40, 40).fill(0.25f), box)
        assertEquals(40, gray.width)
        assertEquals(40, gray.height)
    }

    @Test
    fun locateFindsARealSubjectAndCropsToIt() {
        val img = RgbaImage(120, 120).fill(opaque(248, 246, 240))
        for (y in 40..79) for (x in 40..79) img[x, y] = opaque(30, 60, 160)
        val found = Subject.locate(img)
        assertTrue(found.confident, found.reason)
        assertTrue(found.box.w < 120 && found.box.h < 120, "nothing was cropped: ${found.box}")
        assertTrue(found.box.x <= 40, "left edge cuts into the subject: ${found.box}")
        assertTrue(found.box.y <= 40, "top edge cuts into the subject: ${found.box}")
        assertTrue(found.box.x + found.box.w >= 80, "right edge cuts into the subject: ${found.box}")
        assertTrue(found.box.y + found.box.h >= 80, "bottom edge cuts into the subject: ${found.box}")
        assertEquals(120, found.alpha.width)
    }

    @Test
    fun locateRefusesOnAnImageWithNothingToSeparate() {
        val found = Subject.locate(RgbaImage(64, 64).fill(opaque(140, 140, 140)))
        assertFalse(found.confident, found.reason)
        assertEquals(0, found.box.x)
        assertEquals(64, found.box.w)
        assertEquals(64, found.box.h)
        assertTrue(found.reason.isNotEmpty())
    }

    @Test
    fun locateSurvivesAOnePixelImage() {
        val found = Subject.locate(RgbaImage(1, 1, intArrayOf(opaque(20, 40, 60))))
        assertFalse(found.confident)
        assertEquals(1, found.box.w)
        assertEquals(1, found.box.h)
        assertEquals(1f, found.alpha[0, 0], 1e-6f)
    }

    @Test
    fun locateIsConfidentOnlyWhenBothHalvesAre() {
        val flat = Subject.locate(RgbaImage(64, 64).fill(opaque(200, 200, 200)))
        assertFalse(flat.confident)
        assertTrue(flat.confidence < com.offlinetracer.imaging.Matte.MIN_CONFIDENCE)
    }
}
