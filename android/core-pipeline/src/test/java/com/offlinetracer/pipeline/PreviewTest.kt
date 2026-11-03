package com.offlinetracer.pipeline

import com.offlinetracer.imaging.RgbaImage
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewTest {

    private val white = RgbaImage.argb(255, 255, 255, 255)
    private val black = RgbaImage.argb(255, 0, 0, 0)

    private fun cross(size: Int, thickness: Int): RgbaImage {
        val img = RgbaImage(size, size).fill(white)
        val lo = (size - thickness) / 2
        val hi = lo + thickness
        for (y in 0 until size) for (x in lo until hi) img[x, y] = black
        for (x in 0 until size) for (y in lo until hi) img[x, y] = black
        return img
    }

    private val params = TraceParams(
        preprocess = PreprocessParams(claheEnabled = false, denoise = DenoiseMode.NONE),
        edge = EdgeParams(engine = EdgeEngine.ADAPTIVE, adaptiveRadius = 12),
    )

    private fun longEdgeOf(r: TraceResult): Int = max(r.workingWidth, r.workingHeight)

    @Test
    fun aPreviewIsSmallerInWorkingSpaceAndIdenticalInSourceSpace() {
        val src = cross(512, 48)
        val full = Pipeline.run(src, params, null, CancellationToken(), classify = false)
        val preview = Preview.runPreview(src, params, 256)

        assertEquals(512, longEdgeOf(full))
        assertEquals(256, longEdgeOf(preview))
        assertTrue(preview.preview.width < full.preview.width, "the mask overlay did not shrink")

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
        assertEquals(Pipeline.stageIds(), preview.stages.map { it.id })
        assertEquals(Pipeline.stageIds().size, preview.stages.size)
    }

    @Test
    fun aPreviewDoesNotPayForClassification() {
        assertNull(Preview.runPreview(cross(512, 48), params, 256).profile)
    }

    @Test
    fun thePreviewAndTheExportAgreeAboutStrokeWeight() {
        val src = cross(512, 48)
        val weighted = params.copy(output = params.output.copy(strokeWidth = 1.6f))
        val full = Pipeline.run(src, weighted, null, CancellationToken(), classify = false)
        val preview = Preview.runPreview(src, weighted, 256)

        val fullWidths = full.document.layers.flatMap { it.shapes }.map { it.style.strokeWidth }
        val previewWidths = preview.document.layers.flatMap { it.shapes }.map { it.style.strokeWidth }
        assertTrue(fullWidths.isNotEmpty() && previewWidths.isNotEmpty(), "nothing was traced")
        for (w in fullWidths) assertEquals(1.6f, w, 1e-3f)
        for (w in previewWidths) assertEquals(1.6f, w, 1e-3f)
    }

    @Test
    fun aSourceSmallerThanThePreviewSizeIsTracedAsItselfAndNotRescaled() {
        val src = cross(200, 20)
        val full = Pipeline.run(src, params, null, CancellationToken(), classify = false)
        val preview = Preview.runPreview(src, params, Preview.DEFAULT_LONG_EDGE)

        assertEquals(200, longEdgeOf(preview), "the preview enlarged the trace")
        assertEquals(longEdgeOf(full), longEdgeOf(preview))
        assertEquals(200f, preview.document.width)
        assertEquals(full.document.layers, preview.document.layers)
        assertEquals(full.notes, preview.notes)
    }

    @Test
    fun aPreviewIsNeverAskedToRunBelowTheMinimumWorkingEdge() {
        val preview = Preview.runPreview(cross(512, 48), params, 16)
        assertEquals(256, longEdgeOf(preview))
        assertEquals(512f, preview.document.width, "the frame is still the source frame")
    }

    @Test
    fun theWorkingCapStillAppliesUnderneathThePreviewCap() {
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
        val src = cross(512, 48)
        val direct = Preview.runPreview(src, params, 256)
        val viaPipeline = Pipeline.runPreview(src, params, 256)
        assertEquals(direct.workingWidth, viaPipeline.workingWidth)
        assertEquals(direct.document.layers, viaPipeline.document.layers)
        assertEquals(direct.notes, viaPipeline.notes)
    }

    @Test
    fun aPreviewIsCancellable() {
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

        assertEquals(65, half.cleanup.minBlobArea, "260 px of area at half resolution is 65")
        assertEquals(100, half.cleanup.fillHolesUpTo)
    }

    @Test
    fun theFilterSigmasAndTheDimensionlessKnobsAreLeftAlone() {
        val half = Preview.scaleToPreview(knobs, 1024, 0.5f)

        assertEquals(knobs.edge.blurSigma, half.edge.blurSigma)
        assertEquals(knobs.edge.dogSigma, half.edge.dogSigma)
        assertEquals(knobs.edge.logSigma, half.edge.logSigma)
        assertEquals(knobs.edge.flow.sigmaC, half.edge.flow.sigmaC)
        assertEquals(knobs.edge.flow.sigmaM, half.edge.flow.sigmaM)
        assertEquals(knobs.edge.flow.tensorSigma, half.edge.flow.tensorSigma)
        assertEquals(knobs.preprocess.unsharpSigma, half.preprocess.unsharpSigma)

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
        assertEquals(1, tiny.cleanup.closeRadius)
        assertEquals(1, tiny.cleanup.maxGap)
        assertEquals(1, tiny.cleanup.pruneSpurs)
        assertEquals(1, tiny.cleanup.minBlobArea)
        assertEquals(1, tiny.cleanup.fillHolesUpTo)
        assertEquals(1, tiny.preprocess.medianRadius)
        assertEquals(1, tiny.edge.adaptiveRadius)
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

    @Test
    fun aOnePixelSourcePreviewsWithoutThrowing() {
        val one = RgbaImage(1, 1).fill(black)
        val preview = Preview.runPreview(one, TraceParams(), 256)
        assertEquals(1f, preview.document.width)
        assertEquals(1, preview.workingWidth)
        assertEquals(Pipeline.stageIds().size, preview.stages.size)
        if (preview.document.shapeCount() == 0) assertTrue(preview.notes.isNotEmpty())
    }
}
