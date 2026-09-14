package com.offlinetracer.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Source classification (ALGORITHMS §12). */
class ClassifyTest {

    private fun opaque(r: Int, g: Int, b: Int): Int = RgbaImage.argb(255, r, g, b)

    /** Black strokes on white paper: two hard modes at the ends of the range. */
    private fun penDrawing(): RgbaImage {
        val img = RgbaImage(128, 128).fill(opaque(255, 255, 255))
        for (y in 20..100) for (x in 30..33) img[x, y] = opaque(0, 0, 0)
        for (y in 20..100) for (x in 80..83) img[x, y] = opaque(0, 0, 0)
        for (x in 30..83) for (y in 60..63) img[x, y] = opaque(0, 0, 0)
        return img
    }

    /** Deterministic high-frequency texture, the "textile or foliage" case. */
    private fun texture(): RgbaImage {
        val img = RgbaImage(128, 128)
        for (y in 0 until 128) for (x in 0 until 128) {
            val v = ((x * 37 + y * 53) % 97) * 255 / 96
            val u = ((x * 13 + y * 7) % 61) * 255 / 60
            img[x, y] = opaque(v, u, (v + u) / 2)
        }
        return img
    }

    @Test
    fun aPenDrawingIsClassifiedAsLineArt() {
        val p = Classify.profile(penDrawing())
        assertTrue(p.bimodality > 0.75f, "bimodality was ${p.bimodality}")
        assertTrue(p.isLineArt, "a pure black-on-white drawing must classify as line art")
        assertTrue(p.suggestion.isNotEmpty())
        assertTrue(p.suggestion.contains("Ink Scan"), "the suggestion must name a preset: ${p.suggestion}")
    }

    @Test
    fun aPhotographOfATextureIsClassifiedAsHighTexture() {
        val p = Classify.profile(texture())
        assertTrue(p.edgeDensity > 0.18f, "edge density was ${p.edgeDensity}")
        assertTrue(p.isHighTexture)
        assertTrue(!p.isFlatGraphic, "noise is not a flat graphic")
    }

    @Test
    fun aSmoothGradientIsNeitherLineArtNorHighTexture() {
        val img = RgbaImage(96, 96)
        for (y in 0 until 96) for (x in 0 until 96) {
            val v = (x + y) * 255 / 190
            img[x, y] = opaque(v, v, v)
        }
        val p = Classify.profile(img)
        assertTrue(!p.isLineArt, "a smooth ramp has no two modes")
        assertTrue(!p.isHighTexture, "a smooth ramp has almost no edges, got ${p.edgeDensity}")
        assertTrue(p.suggestion.isNotEmpty())
    }

    @Test
    fun everyStatisticStaysInItsDeclaredRange() {
        for (img in listOf(penDrawing(), texture(), RgbaImage(64, 64).fill(opaque(120, 30, 200)))) {
            val p = Classify.profile(img)
            assertTrue(p.bimodality in 0f..1f, "bimodality ${p.bimodality}")
            assertTrue(p.edgeDensity in 0f..1f, "edge density ${p.edgeDensity}")
            assertTrue(p.orientationEntropy in 0f..1f, "entropy ${p.orientationEntropy}")
            assertTrue(p.saturationSpread in 0f..1f, "saturation spread ${p.saturationSpread}")
        }
    }

    @Test
    fun aFlatColourFieldHasNoSaturationSpreadAndNoEntropy() {
        val p = Classify.profile(RgbaImage(64, 64).fill(opaque(200, 40, 40)))
        assertEquals(0f, p.saturationSpread, 1e-4f)
        assertEquals(0f, p.orientationEntropy, 1e-4f)
        assertEquals(0f, p.edgeDensity, 1e-4f)
    }

    @Test
    fun aTwoColourLogoSpreadsSaturation() {
        val img = RgbaImage(64, 64).fill(opaque(255, 255, 255))
        for (y in 16..47) for (x in 16..47) img[x, y] = opaque(220, 0, 0)
        val p = Classify.profile(img)
        assertTrue(p.saturationSpread > 0.1f, "a saturated mark on white must spread saturation")
    }

    @Test
    fun profilingIsDeterministic() {
        val img = texture()
        val a = Classify.profile(img)
        val b = Classify.profile(img)
        assertEquals(a, b, "the same input must always produce the same profile")
    }

    @Test
    fun theSuggestionIsASentenceThatNamesAPreset() {
        for (img in listOf(penDrawing(), texture(), RgbaImage(32, 32).fill(opaque(9, 9, 9)))) {
            val s = Classify.profile(img).suggestion
            assertTrue(s.length > 20, "the suggestion must be readable, got '$s'")
            assertTrue(s.contains("preset"), "the suggestion must name a preset, got '$s'")
            assertTrue(s.trim().endsWith("."), "the suggestion must be a sentence, got '$s'")
        }
    }

    @Test
    fun profilingSurvivesAOnePixelImage() {
        val p = Classify.profile(RgbaImage(1, 1, intArrayOf(opaque(10, 20, 30))))
        assertTrue(!p.bimodality.isNaN())
        assertTrue(!p.edgeDensity.isNaN())
        assertTrue(!p.orientationEntropy.isNaN())
        assertTrue(!p.saturationSpread.isNaN())
        assertTrue(p.suggestion.isNotEmpty())
    }

    @Test
    fun profilingSurvivesAnAllZeroImage() {
        val p = Classify.profile(RgbaImage(32, 32))
        assertTrue(!p.orientationEntropy.isNaN())
        assertEquals(0f, p.edgeDensity, 1e-5f)
    }

    // -------------------------------------------------------------------------------------------
    // The added statistics, and the confidence that decides whether acting on them is defensible
    // -------------------------------------------------------------------------------------------

    /** Two flat colours on a flat ground: bimodal, palette-flat, and not a stroke anywhere. */
    private fun flatGraphic(): RgbaImage {
        val img = RgbaImage(200, 200).fill(opaque(250, 248, 240))
        for (y in 30..170) for (x in 30..100) img[x, y] = opaque(20, 60, 160)
        for (y in 70..130) for (x in 100..170) img[x, y] = opaque(220, 40, 40)
        return img
    }

    /** A shaded object on a clean pale sweep — the case background separation exists for. */
    private fun objectOnCleanGround(): RgbaImage {
        val img = RgbaImage(240, 300).fill(opaque(238, 236, 232))
        for (y in 60 until 250) {
            val t = (y - 60) / 190.0
            val radius = 30 + 45 * kotlin.math.sin(Math.PI * (0.2 + 0.7 * t))
            for (x in 0 until 240) {
                val dx = (x - 120).toDouble()
                if (kotlin.math.abs(dx) > radius) continue
                val shade = 1.0 - 0.4 * (dx / radius) * (dx / radius) - 0.12 * t
                img[x, y] = opaque((190 * shade).toInt(), (110 * shade).toInt(), (70 * shade).toInt())
            }
        }
        return img
    }

    @Test
    fun everyAddedStatisticStaysInItsDeclaredRange() {
        val images = listOf(penDrawing(), texture(), flatGraphic(), objectOnCleanGround())
        for (img in images) {
            val p = Classify.profile(img)
            assertTrue(p.backgroundUniformity in 0f..1f, "uniformity ${p.backgroundUniformity}")
            assertTrue(p.subjectCoverage in 0f..1f, "coverage ${p.subjectCoverage}")
            assertTrue(p.strokeWidthConsistency in 0f..1f, "consistency ${p.strokeWidthConsistency}")
            assertTrue(p.paletteFlatness in 0f..1f, "palette ${p.paletteFlatness}")
            assertTrue(p.orientationConcentration in 0f..1f, "concentration ${p.orientationConcentration}")
            assertTrue(p.textureEnergy in 0f..1f, "texture ${p.textureEnergy}")
            assertTrue(p.confidence in 0f..1f, "confidence ${p.confidence}")
            assertTrue(p.strokeWidthPx >= 0f && p.strokeWidthPx.isFinite())
            assertTrue(p.dominantOrientation >= 0f && p.dominantOrientation <= Math.PI.toFloat())
            assertTrue(p.colourCount >= 0)
            for (s in listOf(
                p.scores.lineArt, p.scores.flatGraphic, p.scores.textured,
                p.scores.smoothObject, p.scores.photograph,
            )) {
                assertTrue(s in 0f..1f, "a class score escaped 0..1: $s")
            }
        }
    }

    @Test
    fun aPenDrawingIsClassifiedAsLineArtWithEnoughConfidenceToActOn() {
        val p = Classify.profile(penDrawing())
        assertEquals(Classify.SourceKind.LINE_ART, p.kind)
        assertTrue(p.confidence > 0.55f, "confidence was ${p.confidence}, too low to act on")
        // A pen has one nib, and that is what separates a drawing from a photograph of dark shapes.
        assertTrue(
            p.strokeWidthConsistency > 0.7f,
            "a uniform-width drawing must read as consistent: ${p.strokeWidthConsistency}",
        )
    }

    @Test
    fun aTwoColourGraphicIsNotLineArtBecauseItsStrokesHaveNoSingleWidth() {
        val p = Classify.profile(flatGraphic())
        // It is as bimodal as the pen drawing and both modes sit at the ends of the range, which is
        // exactly the family §12's own test cannot separate. The stroke-width term is what does.
        assertTrue(p.bimodality > 0.75f, "the fixture must be bimodal to be a real test: ${p.bimodality}")
        assertTrue(
            p.strokeWidthConsistency < 0.6f,
            "solid blocks have no single stroke width: ${p.strokeWidthConsistency}",
        )
        assertTrue(!p.isLineArt, "a two-colour poster is not line art")
        assertEquals(Classify.SourceKind.FLAT_GRAPHIC, p.kind)
    }

    @Test
    fun aBlankFrameIsUnknownRatherThanConfidentlyAnything() {
        for (img in listOf(
            RgbaImage(64, 64).fill(opaque(255, 255, 255)),
            RgbaImage(64, 64).fill(opaque(9, 9, 9)),
            RgbaImage(1, 1, intArrayOf(opaque(10, 20, 30))),
        )) {
            val p = Classify.profile(img)
            assertEquals(Classify.SourceKind.UNKNOWN, p.kind, "an empty frame was classified")
            assertEquals(0f, p.confidence, "an empty frame cannot be classified with confidence")
            assertTrue(!p.separableSubject, "there is no subject to separate")
        }
    }

    @Test
    fun aCleanBackgroundIsDetectedAndABusyOneIsNot() {
        val clean = Classify.profile(objectOnCleanGround())
        assertTrue(clean.backgroundUniformity > 0.8f, "uniformity ${clean.backgroundUniformity}")
        assertTrue(clean.separableSubject, "a subject on a plain sweep must be separable")

        val busy = Classify.profile(texture())
        assertTrue(busy.backgroundUniformity < 0.4f, "uniformity ${busy.backgroundUniformity}")
        assertTrue(!busy.separableSubject, "there is no background to separate in a full-frame texture")
    }

    @Test
    fun textureEnergyMeasuresAreaAndNotEdgeStrength() {
        // The whole reason this statistic is an *area*: a hard-edged drawing and a woven surface both
        // saturate a mean-|Laplacian| measure, and only one of them is textured.
        val drawing = Classify.profile(penDrawing()).textureEnergy
        val woven = Classify.profile(texture()).textureEnergy
        assertTrue(
            woven > drawing * 2f,
            "a full-frame texture must read as far more textured than a line drawing: $woven vs $drawing",
        )
    }

    // -------------------------------------------------------------------------------------------
    // figureGround — the two ring statistics, not a segmentation
    // -------------------------------------------------------------------------------------------

    @Test
    fun figureGroundMeasuresACleanBackgroundAndTheSubjectOnIt() {
        val img = RgbaImage(200, 200).fill(opaque(252, 252, 250))
        for (y in 60..140) for (x in 40..120) img[x, y] = opaque(30, 30, 30)
        val fg = Classify.figureGround(img)

        assertTrue(fg.backgroundUniformity > 0.95f, "uniformity ${fg.backgroundUniformity}")
        // 81x81 of 200x200 is 16.4%, and the measurement has no segmentation in it to blur that.
        assertEquals(0.164f, fg.coverage, 0.02f)
    }

    @Test
    fun figureGroundReportsNoCleanBackgroundForAFullFrameTexture() {
        val fg = Classify.figureGround(texture())
        assertTrue(
            fg.backgroundUniformity < 0.4f,
            "a frame with no backdrop must not report one: ${fg.backgroundUniformity}",
        )
    }

    @Test
    fun figureGroundSurvivesDegenerateInput() {
        for (img in listOf(
            RgbaImage(1, 1, intArrayOf(opaque(10, 20, 30))),
            RgbaImage(64, 64).fill(opaque(255, 255, 255)),
            RgbaImage(3, 100).fill(opaque(0, 0, 0)),
        )) {
            val fg = Classify.figureGround(img)
            assertTrue(fg.backgroundUniformity in 0f..1f, "uniformity ${fg.backgroundUniformity}")
            assertTrue(fg.coverage in 0f..1f, "coverage ${fg.coverage}")
            assertEquals(0f, fg.coverage, "a frame of one colour has no subject in it")
        }
    }

    @Test
    fun aTransparentRegionIsBackgroundWhateverItsColourChannelsSay() {
        // An already cut-out PNG carries whatever the encoder left in the RGB of its erased pixels,
        // and that colour is meaningless. Two identically-red regions here, one opaque and one fully
        // transparent: only the opaque one counts towards coverage.
        val img = RgbaImage(120, 120).fill(opaque(250, 250, 250))
        for (y in 20..50) for (x in 20..50) img[x, y] = opaque(220, 30, 30)
        for (y in 70..100) for (x in 70..100) img[x, y] = RgbaImage.argb(0, 220, 30, 30)
        val fg = Classify.figureGround(img)
        // One 31x31 square of 120x120 is 6.7%; counting both would report 13.3%.
        assertEquals(0.067f, fg.coverage, 0.01f)
    }

    @Test
    fun kindOfFallsBackToTheFlagLadderForAProfileThatWasNeverMeasured() {
        fun flags(line: Boolean = false, flat: Boolean = false, texture: Boolean = false) =
            Classify.SourceProfile(
                bimodality = 0.5f, edgeDensity = 0.1f, orientationEntropy = 0.8f,
                saturationSpread = 0.2f, isLineArt = line, isHighTexture = texture,
                isFlatGraphic = flat, suggestion = "synthetic",
            )
        assertEquals(Classify.SourceKind.LINE_ART, Classify.kindOf(flags(line = true)))
        assertEquals(Classify.SourceKind.FLAT_GRAPHIC, Classify.kindOf(flags(flat = true)))
        assertEquals(Classify.SourceKind.TEXTURED, Classify.kindOf(flags(texture = true)))
        assertEquals(Classify.SourceKind.PHOTOGRAPH, Classify.kindOf(flags()))
        // Line art wins the ladder, because edge-detecting existing strokes doubles every line.
        assertEquals(
            Classify.SourceKind.LINE_ART,
            Classify.kindOf(flags(line = true, flat = true, texture = true)),
        )
        // An explicit kind is never second-guessed by the flags.
        val measured = flags(line = true).copy(kind = Classify.SourceKind.TEXTURED)
        assertEquals(Classify.SourceKind.TEXTURED, Classify.kindOf(measured))
        // And a profile nobody measured carries no confidence, which is what makes a caller refuse.
        assertEquals(0f, flags(line = true).confidence)
    }

    @Test
    fun everyKindHasAName() {
        for (k in Classify.SourceKind.values()) {
            assertTrue(Classify.nameOf(k).isNotBlank(), "$k has no name")
        }
    }

    @Test
    fun theSuggestionStatesItsOwnConfidence() {
        val p = Classify.profile(penDrawing())
        assertTrue(
            p.suggestion.contains("% confident"),
            "the sentence gets pasted into bug reports, so it has to carry the confidence: " +
                p.suggestion,
        )
    }
}
