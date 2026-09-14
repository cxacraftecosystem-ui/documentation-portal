package com.offlinetracer.pipeline

import com.offlinetracer.imaging.RgbaImage
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The interactive preview.
 *
 * One rule is being defended here: **a preview is not a cheaper different pipeline.** It runs the
 * identical stage list at a smaller working size, so the only thing it is allowed to disagree with the
 * final render about is resolution. Everything else — the frame the geometry lives in, the stroke
 * weight, which caps fired — has to match, because the user tunes twenty sliders against the preview
 * and then finds out on export whether it was telling the truth.
 */
class PreviewTest {

    private val white = RgbaImage.argb(255, 255, 255, 255)
    private val black = RgbaImage.argb(255, 0, 0, 0)

    /**
     * A black plus on white paper: arms that run the full width and height, so the drawing's extent is
     * the frame itself and a preview that disagrees with the export about the frame cannot hide it in
     * rounding.
     */
    private fun cross(size: Int, thickness: Int): RgbaImage {
        val img = RgbaImage(size, size).fill(white)
        val lo = (size - thickness) / 2
        val hi = lo + thickness
        for (y in 0 until size) for (x in lo until hi) img[x, y] = black
        for (x in 0 until size) for (y in lo until hi) img[x, y] = black
        return img
    }

    /**
     * The local threshold rather than the default flow engine, for the same reason the pipeline tests
     * use it: on synthetic two-level input its answer is arithmetic (ink is what is darker than its
     * neighbourhood), so "the preview found the drawing too" is not a question about detector tuning.
     */
    private val params = TraceParams(
        preprocess = PreprocessParams(claheEnabled = false, denoise = DenoiseMode.NONE),
        edge = EdgeParams(engine = EdgeEngine.ADAPTIVE, adaptiveRadius = 12),
    )

    private fun longEdgeOf(r: TraceResult): Int = max(r.workingWidth, r.workingHeight)

    // -------------------------------------------------------------------------------------------
    // A preview and the export must agree about everything but resolution
    // -------------------------------------------------------------------------------------------

    @Test
    fun aPreviewIsSmallerInWorkingSpaceAndIdenticalInSourceSpace() {
        val src = cross(512, 48)
        val full = Pipeline.run(src, params, null, CancellationToken(), classify = false)
        val preview = Preview.runPreview(src, params, 256)

        // Smaller where it is supposed to be smaller.
        assertEquals(512, longEdgeOf(full))
        assertEquals(256, longEdgeOf(preview))
        assertTrue(preview.preview.width < full.preview.width, "the mask overlay did not shrink")

        // Identical where a disagreement would be a lie: the frame the geometry lives in.
        assertEquals(full.sourceWidth, preview.sourceWidth)
        assertEquals(full.sourceHeight, preview.sourceHeight)
        assertEquals(512f, preview.document.width)
        assertEquals(512f, preview.document.height)
        assertEquals(full.document.width, preview.document.width)
        assertEquals(full.document.height, preview.document.height)

        assertTrue(full.document.shapeCount() > 0, "full notes were ${full.notes}")
        assertTrue(
            preview.document.shapeCount() > 0,
            "the export found ${full.document.shapeCount()} shapes and the preview found none, " +
                "which is the preview lying: ${preview.notes}",
        )
    }

    @Test
    fun aPreviewRunsTheWholeStageListAndNotAShorterOne() {
        val src = cross(512, 48)
        val preview = Preview.runPreview(src, params, 256)
        // Any shortcut — skipping the matte, skipping spur pruning, substituting a faster engine —
        // would make the preview lie, and this is the assertion that notices one being added.
        assertEquals(Pipeline.stageIds(), preview.stages.map { it.id })
        assertEquals(Pipeline.stageIds().size, preview.stages.size)
    }

    @Test
    fun aPreviewDoesNotPayForClassification() {
        // Classify answers from its own 512 px proxy, so it would return the same profile for the same
        // source and cost the same on every keystroke.
        assertNull(Preview.runPreview(cross(512, 48), params, 256).profile)
    }

    @Test
    fun thePreviewAndTheExportAgreeAboutStrokeWeight() {
        // The arithmetic this is guarding: scaleToPreview multiplies the stroke width by the resolution
        // ratio, and the assemble stage later multiplies it by frame/working — which is larger by
        // exactly that same ratio. The two cancel, so a 256 px preview of a 512 px source renders its
        // strokes at the export's weight rather than at half of it.
        val src = cross(512, 48)
        val weighted = params.copy(output = params.output.copy(strokeWidth = 1.6f))
        val full = Pipeline.run(src, weighted, null, CancellationToken(), classify = false)
        val preview = Preview.runPreview(src, weighted, 256)

        val fullWidths = full.document.layers.flatMap { it.shapes }.map { it.style.strokeWidth }
        val previewWidths = preview.document.layers.flatMap { it.shapes }.map { it.style.strokeWidth }
        assertTrue(fullWidths.isNotEmpty() && previewWidths.isNotEmpty(), "nothing was traced")
        // Working 512 into a 512 frame is a 1x scale, so the export carries the nominal weight.
        for (w in fullWidths) assertEquals(1.6f, w, 1e-3f)
        // Working 256 into a 512 frame is 2x, applied to a width that was halved. 1.6 either way.
        for (w in previewWidths) assertEquals(1.6f, w, 1e-3f)
    }

    // -------------------------------------------------------------------------------------------
    // A preview never enlarges, and never goes below the working floor
    // -------------------------------------------------------------------------------------------

    @Test
    fun aSourceSmallerThanThePreviewSizeIsTracedAsItselfAndNotRescaled() {
        // This case matters because it is every small image, and returning a *rescaled* version of the
        // parameters for it would make a thumbnail-sized source render differently from itself.
        val src = cross(200, 20)
        val full = Pipeline.run(src, params, null, CancellationToken(), classify = false)
        val preview = Preview.runPreview(src, params, Preview.DEFAULT_LONG_EDGE)

        assertEquals(200, longEdgeOf(preview), "the preview enlarged the trace")
        assertEquals(longEdgeOf(full), longEdgeOf(preview))
        assertEquals(200f, preview.document.width)
        // Identical parameters mean an identical trace, down to the geometry and the notes.
        assertEquals(full.document.layers, preview.document.layers)
        assertEquals(full.notes, preview.notes)
    }

    @Test
    fun aPreviewIsNeverAskedToRunBelowTheMinimumWorkingEdge() {
        // 256 is the sanitiser's floor: below it the working image cannot hold a legible line, so an
        // absurdly small request is raised to it rather than honoured.
        val preview = Preview.runPreview(cross(512, 48), params, 16)
        assertEquals(256, longEdgeOf(preview))
        assertEquals(512f, preview.document.width, "the frame is still the source frame")
    }

    @Test
    fun theWorkingCapStillAppliesUnderneathThePreviewCap() {
        // A 900 px source capped at 512 would trace at 512; a 256 px preview of it is a preview of
        // that, not of the original — 256/512 and not 256/900 — or the two would disagree about every
        // knob measured in working pixels.
        val src = cross(900, 90)
        val capped = params.copy(preprocess = params.preprocess.copy(workingLongEdge = 512))
        val full = Pipeline.run(src, capped, null, CancellationToken(), classify = false)
        val preview = Preview.runPreview(src, capped, 256)

        assertEquals(512, longEdgeOf(full))
        assertEquals(256, longEdgeOf(preview))
        assertEquals(900f, full.document.width)
        assertEquals(900f, preview.document.width)
    }

    @Test
    fun theTwoEntryPointsAreTheSameCall() {
        // Pipeline.runPreview delegates here; a second implementation would be a second thing to keep
        // in step with the stage list.
        val src = cross(512, 48)
        val direct = Preview.runPreview(src, params, 256)
        val viaPipeline = Pipeline.runPreview(src, params, 256)
        assertEquals(direct.workingWidth, viaPipeline.workingWidth)
        assertEquals(direct.document.layers, viaPipeline.document.layers)
        assertEquals(direct.notes, viaPipeline.notes)
    }

    // -------------------------------------------------------------------------------------------
    // Cancellation and progress
    // -------------------------------------------------------------------------------------------

    @Test
    fun aPreviewIsCancellable() {
        // Previews are the thing being cancelled most often: every slider tick abandons the last one.
        val token = CancellationToken()
        token.cancel()
        assertFailsWith<CancelledException> { Preview.runPreview(cross(512, 48), params, 256, token) }
    }

    @Test
    fun aPreviewReportsProgressForEveryStageJustAsTheFullTraceDoes() {
        val ids = ArrayList<String>()
        Preview.runPreview(
            cross(512, 48),
            params,
            256,
            CancellationToken(),
            ProgressListener { id, label, fraction ->
                assertTrue(label.isNotBlank(), "stage $id reported a blank label")
                assertTrue(fraction in 0f..1f, "fraction $fraction out of range")
                if (ids.isEmpty() || ids[ids.size - 1] != id) ids.add(id)
            },
        )
        assertEquals(Pipeline.stageIds(), ids)
    }

    // -------------------------------------------------------------------------------------------
    // scaleToPreview: which knobs are lengths and which are not
    // -------------------------------------------------------------------------------------------

    /** Distinctive legal values, so every scaled field can be checked against arithmetic. */
    private val knobs = TraceParams(
        preprocess = PreprocessParams(
            workingLongEdge = 2048,
            denoise = DenoiseMode.MEDIAN,
            denoiseStrength = 0.65f,
            medianRadius = 4,
            claheClip = 2.5f,
            unsharpAmount = 0.6f,
            unsharpSigma = 1.5f,
        ),
        matte = MatteParams(mode = MatteMode.BORDER_FLOOD, tolerance = 0.2f, feather = 3f),
        edge = EdgeParams(
            engine = EdgeEngine.ADAPTIVE,
            sensitivity = 0.45f,
            blurSigma = 1.2f,
            dogSigma = 1f,
            logSigma = 1.4f,
            adaptiveRadius = 24,
            flow = FlowSettings(tensorSigma = 2f, sigmaC = 1f, sigmaM = 3f),
        ),
        cleanup = CleanupParams(
            minBlobArea = 260,
            closeRadius = 3,
            openRadius = 0,
            maxGap = 28,
            maxBridgeAngle = 95f,
            pruneSpurs = 8,
            fillHolesUpTo = 400,
            keepLargest = 12,
        ),
        output = OutputParams(
            simplify = 1.8f,
            fitError = 2.6f,
            corner = 130f,
            smoothIterations = 2,
            strokeWidth = 2.6f,
            minPathLength = 24f,
        ),
    )

    @Test
    fun theKnobsMeasuredAgainstFeaturesInTheImageAreScaled() {
        val half = Preview.scaleToPreview(knobs, 1024, 0.5f)
        assertEquals(1024, half.preprocess.workingLongEdge)

        // Lengths scale by r: halve the resolution and the same feature is half as many pixels across.
        assertEquals(2, half.preprocess.medianRadius, "dust is a feature of the image")
        assertEquals(1.5f, half.matte.feather, 1e-6f)
        assertEquals(12, half.edge.adaptiveRadius, "a threshold window is sized against stroke width")
        assertEquals(2, half.cleanup.closeRadius)
        assertEquals(14, half.cleanup.maxGap)
        assertEquals(4, half.cleanup.pruneSpurs)
        assertEquals(0.9f, half.output.simplify, 1e-6f)
        assertEquals(1.3f, half.output.fitError, 1e-6f)
        assertEquals(1.3f, half.output.strokeWidth, 1e-6f)
        assertEquals(12f, half.output.minPathLength, 1e-6f)

        // Areas scale by r², or a preview would drop specks the export keeps — and the two would then
        // report different numbers of discarded regions, which is a contradiction on screen.
        assertEquals(65, half.cleanup.minBlobArea, "260 px of area at half resolution is 65")
        assertEquals(100, half.cleanup.fillHolesUpTo)
    }

    @Test
    fun theFilterSigmasAndTheDimensionlessKnobsAreLeftAlone() {
        val half = Preview.scaleToPreview(knobs, 1024, 0.5f)

        // σ is measured against the *pixel grid*, and the box-average downscale is itself a low-pass
        // matched to the new grid. Scaling σ down as well would double-count the resampler's own
        // filtering and hand the edge engine a noisier response than the export ever sees.
        assertEquals(knobs.edge.blurSigma, half.edge.blurSigma)
        assertEquals(knobs.edge.dogSigma, half.edge.dogSigma)
        assertEquals(knobs.edge.logSigma, half.edge.logSigma)
        assertEquals(knobs.edge.flow.sigmaC, half.edge.flow.sigmaC)
        assertEquals(knobs.edge.flow.sigmaM, half.edge.flow.sigmaM)
        assertEquals(knobs.edge.flow.tensorSigma, half.edge.flow.tensorSigma)
        assertEquals(knobs.preprocess.unsharpSigma, half.preprocess.unsharpSigma)

        // Strengths, angles, counts and colours are not lengths at all.
        assertEquals(knobs.preprocess.denoiseStrength, half.preprocess.denoiseStrength)
        assertEquals(knobs.preprocess.claheClip, half.preprocess.claheClip)
        assertEquals(knobs.preprocess.unsharpAmount, half.preprocess.unsharpAmount)
        assertEquals(knobs.matte.tolerance, half.matte.tolerance)
        assertEquals(knobs.edge.sensitivity, half.edge.sensitivity)
        assertEquals(knobs.cleanup.maxBridgeAngle, half.cleanup.maxBridgeAngle)
        assertEquals(knobs.cleanup.keepLargest, half.cleanup.keepLargest)
        assertEquals(knobs.output.corner, half.output.corner)
        assertEquals(knobs.output.smoothIterations, half.output.smoothIterations)
        assertEquals(knobs.output.vectorMode, half.output.vectorMode)
        assertEquals(knobs.styleId, half.styleId, "a preview is still the same style")
    }

    @Test
    fun aRadiusThatWouldRoundAwayToNothingStaysAtOneAndAZeroStaysZero() {
        val tiny = Preview.scaleToPreview(knobs, 256, 0.02f)
        // A closeRadius that becomes 0 turns "join what nearly touches" into "do nothing", so the
        // preview would report a different number of bridged gaps than the export.
        assertEquals(1, tiny.cleanup.closeRadius)
        assertEquals(1, tiny.cleanup.maxGap)
        assertEquals(1, tiny.cleanup.pruneSpurs)
        assertEquals(1, tiny.cleanup.minBlobArea)
        assertEquals(1, tiny.cleanup.fillHolesUpTo)
        assertEquals(1, tiny.preprocess.medianRadius)
        assertEquals(1, tiny.edge.adaptiveRadius)
        // A knob the caller deliberately set to 0 means "disabled" and is not resurrected.
        assertEquals(0, tiny.cleanup.openRadius)
    }

    @Test
    fun aRatioOfOneOrMoreIsAPassThroughRatherThanAnEnlargement() {
        for (ratio in listOf(1f, 2f, 1000f, Float.NaN, Float.POSITIVE_INFINITY, 0f, -3f)) {
            val scaled = Preview.scaleToPreview(knobs, 2048, ratio)
            assertEquals(
                knobs, scaled,
                "ratio $ratio should have left every knob alone at an unchanged working edge",
            )
        }
    }

    @Test
    fun scalingIsLegalForEveryRatioAndEveryStyle() {
        // The UI drives this on every keystroke with whatever ratio the current image implies, so the
        // result has to be a legal parameter tree for all 20 presets at any ratio at all.
        for (style in Styles.ALL) {
            for (ratio in listOf(Float.NaN, Float.NEGATIVE_INFINITY, -1f, 0f, 1e-6f, 0.35f, 1f, 9f)) {
                val scaled = Preview.scaleToPreview(style.params, 256, ratio)
                assertEquals(
                    scaled, scaled.sanitized(),
                    "${style.id} at ratio $ratio produced an unsanitised tree",
                )
                assertEquals(256, scaled.preprocess.workingLongEdge)
                assertEquals(style.params.styleId, scaled.styleId)
                assertEquals(style.params.output.vectorMode, scaled.output.vectorMode)
                assertEquals(style.params.edge.engine, scaled.edge.engine)
            }
        }
    }

    @Test
    fun scalingLeavesTheCallersParametersAlone() {
        val snapshot = knobs.copy()
        Preview.scaleToPreview(knobs, 512, 0.25f)
        assertEquals(snapshot, knobs, "scaleToPreview mutated the tree it was handed")
    }

    @Test
    fun theDefaultPreviewEdgeIsTheDocumentedSevenTwenty() {
        assertEquals(720, Preview.DEFAULT_LONG_EDGE)
    }

    // -------------------------------------------------------------------------------------------
    // Degenerate input
    // -------------------------------------------------------------------------------------------

    @Test
    fun aOnePixelSourcePreviewsWithoutThrowing() {
        val one = RgbaImage(1, 1).fill(black)
        val preview = Preview.runPreview(one, TraceParams(), 256)
        assertEquals(1f, preview.document.width)
        assertEquals(1, preview.workingWidth)
        assertEquals(Pipeline.stageIds().size, preview.stages.size)
        // An empty drawing always carries its reason, in a preview exactly as in an export.
        if (preview.document.shapeCount() == 0) assertTrue(preview.notes.isNotEmpty())
    }
}
