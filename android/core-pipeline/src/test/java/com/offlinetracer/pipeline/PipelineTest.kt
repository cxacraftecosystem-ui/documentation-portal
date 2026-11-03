package com.offlinetracer.pipeline

import com.offlinetracer.imaging.RgbaImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineTest {

    private val white = RgbaImage.argb(255, 255, 255, 255)
    private val black = RgbaImage.argb(255, 0, 0, 0)

    private fun blank(size: Int): RgbaImage = RgbaImage(size, size).fill(white)

    private fun cross(size: Int, thickness: Int): RgbaImage {
        val img = blank(size)
        val lo = (size - thickness) / 2
        val hi = lo + thickness
        for (y in 0 until size) for (x in lo until hi) img[x, y] = black
        for (x in 0 until size) for (y in lo until hi) img[x, y] = black
        return img
    }

    private fun longEdgeOf(result: TraceResult): Int = max(result.workingWidth, result.workingHeight)

    @Test
    fun aSyntheticImageRunsEndToEndAndProducesGeometry() {
        val result = Pipeline.run(cross(128, 12), TraceParams())
        assertEquals(Pipeline.stageIds(), result.stages.map { it.id })
        assertTrue(result.document.shapeCount() > 0, "no geometry; notes were ${result.notes}")
        assertTrue(result.document.nodeCount() > result.document.shapeCount())
        assertEquals(128f, result.document.width)
        assertEquals(128f, result.document.height)
        assertEquals(128, result.workingWidth)
        assertEquals(128, result.sourceWidth)
        assertNotNull(result.profile)
        assertTrue(result.totalMillis >= 0)
        assertEquals(128, result.preview.width)
        assertEquals(128, result.processedGray.width)
        assertNull(result.distanceTransform)
    }

    @Test
    fun everyStageIsTimedIncludingTheOnesThatDidNothing() {
        val result = Pipeline.run(blank(64), TraceParams(), classify = false)
        assertEquals(Pipeline.stageIds().size, result.stages.size)
        for (s in result.stages) {
            assertTrue(s.millis >= 0, "${s.id} reported ${s.millis} ms")
            assertTrue(s.label.isNotBlank(), "${s.id} has no label")
        }
        var summed = 0L
        for (s in result.stages) summed += s.millis
        assertEquals(summed, result.totalMillis)
    }

    @Test
    fun theSourceImageIsNeverModified() {
        val src = cross(96, 10)
        val before = src.pixels.copyOf()
        Pipeline.run(src, TraceParams(), classify = false)
        assertTrue(before.contentEquals(src.pixels), "the pipeline wrote into the caller's image")
    }

    @Test
    fun theSameInputAlwaysProducesTheSameGeometryAndTheSameNotes() {
        val src = cross(96, 10)
        val a = Pipeline.run(src, TraceParams(), classify = false)
        val b = Pipeline.run(src, TraceParams(), classify = false)
        assertEquals(a.document.layers, b.document.layers)
        assertEquals(a.notes, b.notes)
    }

    @Test
    fun theDocumentIsInSourceCoordinatesAndNotInWorkingCoordinates() {
        val src = cross(768, 72)
        val result = Pipeline.run(
            src,
            TraceParams(preprocess = PreprocessParams(workingLongEdge = 256)),
            classify = false,
        )
        assertEquals(256, result.workingWidth, "the trace should have run at the working size")
        assertEquals(768, result.sourceWidth)
        assertEquals(768f, result.document.width, "the canvas must be the source frame")
        assertEquals(768f, result.document.height)
        assertTrue(result.document.shapeCount() > 0, "no geometry; notes were ${result.notes}")

        val bounds = result.document.bounds()
        assertTrue(bounds[2] > 512f, "maxX ${bounds[2]} is still in working coordinates")
        assertTrue(bounds[3] > 512f, "maxY ${bounds[3]} is still in working coordinates")
        assertTrue(bounds[2] <= 769f, "maxX ${bounds[2]} escaped the frame")
        assertTrue(bounds[3] <= 769f, "maxY ${bounds[3]} escaped the frame")
        assertTrue(
            result.notes.any { it.contains("scaled back up") },
            "the downscale must be explained: ${result.notes}",
        )
    }

    private fun markInCorner(size: Int): RgbaImage {
        val img = RgbaImage(size, size).fill(white)
        val x0 = size / 3
        val x1 = 2 * size / 3
        val y0 = size / 4
        val y1 = 5 * size / 9
        val thickness = size / 30
        for (y in y0..y1) for (x in x0 until x0 + thickness) img[x, y] = black
        for (y in y0..y1) for (x in x1 - thickness until x1) img[x, y] = black
        val mid = (y0 + y1) / 2
        for (x in x0..x1) for (y in mid until mid + thickness) img[x, y] = black
        return img
    }

    private fun autoCrop(allowCrop: Boolean): TraceParams = TraceParams(
        preprocess = PreprocessParams(workingLongEdge = 300),
        auto = AutoParams(
            mode = AutoMode.APPLY,
            subjectId = "photo",
            allowCrop = allowCrop,
            allowMatte = false,
        ),
    )

    @Test
    fun aCroppedTraceStillReturnsTheDocumentInSourceCoordinates() {
        val src = markInCorner(900)
        val whole = Pipeline.run(src, autoCrop(allowCrop = false), classify = false)
        val cropped = Pipeline.run(src, autoCrop(allowCrop = true), classify = false)

        assertEquals(0, whole.cropX, "the control run must not have cropped")
        assertEquals(0, whole.cropY)
        assertTrue(cropped.cropX > 0, "the crop did not happen; notes were ${cropped.notes}")
        assertTrue(cropped.cropY > 0)
        assertTrue(
            cropped.workingWidth < whole.workingWidth,
            "a crop must leave a smaller working image: ${cropped.workingWidth}",
        )
        assertTrue(
            cropped.notes.any { it.contains("Cropped the working image") },
            "a crop that says nothing is indistinguishable from a bug: ${cropped.notes}",
        )

        assertEquals(900f, cropped.document.width)
        assertEquals(900f, cropped.document.height)
        assertEquals(whole.document.width, cropped.document.width)

        assertTrue(whole.document.shapeCount() > 0, "control notes were ${whole.notes}")
        assertTrue(cropped.document.shapeCount() > 0, "cropped notes were ${cropped.notes}")

        val a = whole.document.bounds()
        val b = cropped.document.bounds()
        for (i in 0..3) {
            assertTrue(
                abs(a[i] - b[i]) < 24f,
                "the cropped trace moved the drawing: ${a.toList()} vs ${b.toList()}",
            )
        }
        assertTrue(b[2] > 400f, "maxX ${b[2]} is still in cropped working coordinates")
    }

    @Test
    fun theCropIsRefusedOnABusyFrameAndSaysWhy() {
        val src = RgbaImage(256, 256)
        for (y in 0 until 256) for (x in 0 until 256) {
            val v = ((x * 37 + y * 53) % 97) * 255 / 96
            val u = ((x * 13 + y * 7) % 61) * 255 / 60
            src[x, y] = RgbaImage.argb(255, v, u, (v + u) / 2)
        }
        val result = Pipeline.run(src, autoCrop(allowCrop = true), classify = false)
        assertEquals(0, result.cropX, "a busy frame must not be cropped")
        assertEquals(0, result.cropY)
        assertTrue(
            result.notes.any { it.startsWith("The working image was not cropped to the subject.") },
            "a refusal has to be visible too: ${result.notes}",
        )
    }

    @Test
    fun theCropDoesNotRunWhenAutoIsNotApplying() {
        val src = markInCorner(900)
        for (mode in listOf(AutoMode.OFF, AutoMode.SUGGEST)) {
            val result = Pipeline.run(
                src,
                autoCrop(allowCrop = true).let { it.copy(auto = it.auto.copy(mode = mode)) },
                classify = false,
            )
            assertEquals(0, result.cropX, "$mode must not crop")
            assertTrue(
                result.notes.none { it.contains("Cropped the working image") },
                "$mode reported a crop it did not take: ${result.notes}",
            )
        }
    }

    @Test
    fun theDefaultModeSuggestsAndDoesNotApply() {
        val result = Pipeline.run(
            cross(128, 12),
            TraceParams(cleanup = CleanupParams(minBlobArea = 1_000_000)),
        )
        assertEquals(AutoMode.SUGGEST, result.appliedParams.auto.mode)
        assertEquals(1_000_000, result.appliedParams.cleanup.minBlobArea)
        assertEquals("", result.autoSubjectId)
        assertNotNull(result.profile, "SUGGEST still classifies — that is the point of it")
        assertTrue(
            result.notes.any { it.contains("suggest only") },
            "a suggestion nobody is shown is the state this replaced: ${result.notes}",
        )
    }

    @Test
    fun applyingAutoReportsWhatItChangedAndCarriesTheAppliedParametersBack() {
        val src = cross(256, 16)
        val params = TraceParams(auto = AutoParams(mode = AutoMode.APPLY, allowCrop = false))
        val result = Pipeline.run(src, params)

        assertNotNull(result.profile)
        if (result.autoSubjectId.isEmpty()) {
            assertTrue(
                result.notes.any { it.contains("not sure enough") },
                "auto neither applied nor explained itself: ${result.notes}",
            )
        } else {
            assertNotNull(Subjects.byId(result.autoSubjectId))
            assertTrue(
                result.notes.any { it.contains("Auto-detection read this as") },
                "an applied change must be named: ${result.notes}",
            )
            assertNotEquals(
                params.sanitized(), result.appliedParams,
                "auto reported a subject but the parameters it ran with were unchanged",
            )
        }
        assertEquals(result.appliedParams, result.appliedParams.sanitized())
    }

    @Test
    fun aHandTunedKnobSurvivesAutoDetectionEndToEnd() {
        val result = Pipeline.run(
            cross(256, 16),
            TraceParams(
                edge = EdgeParams(sensitivity = 0.83f),
                auto = AutoParams(
                    mode = AutoMode.APPLY,
                    handTuned = setOf(Knobs.EDGE_SENSITIVITY),
                    allowCrop = false,
                ),
            ),
        )
        assertEquals(0.83f, result.appliedParams.edge.sensitivity)
    }

    @Test
    fun theStrokeWidthIsCarriedThroughTheScaleSoAHairlineStaysAHairline() {
        val src = cross(768, 72)
        val params = TraceParams(
            preprocess = PreprocessParams(workingLongEdge = 256),
            output = OutputParams(strokeWidth = 2f),
        )
        val result = Pipeline.run(src, params, classify = false)
        val shapes = result.document.layers.flatMap { it.shapes }
        assertTrue(shapes.isNotEmpty(), "notes were ${result.notes}")
        for (shape in shapes) {
            assertEquals(6f, shape.style.strokeWidth, 0.01f)
        }
    }

    @Test
    fun anAlreadyCancelledTokenStopsTheTraceBeforeAnyWork() {
        val token = CancellationToken()
        token.cancel()
        assertTrue(token.isCancelled)
        assertFailsWith<CancelledException> {
            Pipeline.run(cross(96, 10), TraceParams(), null, token)
        }
    }

    @Test
    fun cancellingPartWayThroughStopsEveryLaterStage() {
        val token = CancellationToken()
        val seen = ArrayList<String>()
        val listener = ProgressListener { id, _, _ ->
            if (seen.isEmpty() || seen[seen.size - 1] != id) seen.add(id)
            if (id == Stages.DENOISE) token.cancel()
        }
        assertFailsWith<CancelledException> {
            Pipeline.run(cross(128, 12), TraceParams(), listener, token)
        }
        assertTrue(seen.contains(Stages.DENOISE), "the cancel point was never reached: $seen")
        assertTrue(!seen.contains(Stages.CONTRAST), "a stage after the cancel still ran: $seen")
        assertTrue(!seen.contains(Stages.VECTORISE), "a stage after the cancel still ran: $seen")
    }

    @Test
    fun aTokenIsOnlyCancelledWhenItIsCancelled() {
        val token = CancellationToken()
        assertTrue(!token.isCancelled)
        token.throwIfCancelled()
        Pipeline.run(blank(48), TraceParams(), null, token, classify = false)
        assertTrue(!token.isCancelled, "running a trace must not cancel the caller's token")
    }

    @Test
    fun progressIsReportedForEveryStageAndRisesMonotonicallyToOne() {
        val ids = ArrayList<String>()
        val fractions = ArrayList<Float>()
        Pipeline.run(
            cross(96, 10),
            TraceParams(),
            ProgressListener { id, label, fraction ->
                assertTrue(label.isNotBlank(), "stage $id reported a blank label")
                ids.add(id)
                fractions.add(fraction)
            },
            classify = false,
        )
        for (id in Pipeline.stageIds()) assertTrue(ids.contains(id), "stage $id was never reported")
        assertEquals(0f, fractions[0])
        assertEquals(1f, fractions[fractions.size - 1])
        var previous = -1f
        for (f in fractions) {
            assertTrue(f >= previous, "progress went backwards: $fractions")
            assertTrue(f in 0f..1f, "fraction $f is out of range")
            previous = f
        }
    }

    @Test
    fun theBlobFilterReportsHowManyRegionsItRemoved() {
        val result = Pipeline.run(
            cross(128, 12),
            TraceParams(cleanup = CleanupParams(minBlobArea = 1_000_000)),
            classify = false,
        )
        assertTrue(
            result.notes.any { it.contains("smaller than 1000000 pixels") },
            "the blob cap was not reported: ${result.notes}",
        )
        assertEquals(0, result.document.shapeCount())
        assertTrue(
            result.notes.any { it.contains("No ink survived") },
            "an empty result must distinguish itself from a discarded one: ${result.notes}",
        )
    }

    @Test
    fun theLengthFilterReportsHowManyPathsItDropped() {
        val result = Pipeline.run(
            cross(128, 12),
            TraceParams(output = OutputParams(minPathLength = 5000f)),
            classify = false,
        )
        assertTrue(
            result.notes.any { it.contains("Dropped") && it.contains("shorter than 5000 px") },
            "the length cap was not reported: ${result.notes}",
        )
        assertEquals(0, result.document.shapeCount())
        assertTrue(
            result.notes.any { it.contains("no path was long enough") },
            "'discarded everything' must not read as 'found nothing': ${result.notes}",
        )
    }

    @Test
    fun anEdgeModelThatIsNotInstalledIsNamedAndTheClassicalEngineRunsInstead() {
        val result = Pipeline.run(
            cross(96, 10),
            TraceParams(edge = EdgeParams(engine = EdgeEngine.MODEL, modelId = "pidinet-tiny")),
            classify = false,
        )
        assertTrue(
            result.notes.any { it.contains("pidinet-tiny") && it.contains("not installed") },
            "a substituted engine must be reported: ${result.notes}",
        )
        assertTrue(
            result.document.shapeCount() > 0,
            "a missing model must degrade, not fail: ${result.notes}",
        )
    }

    @Test
    fun choosingTheModelEngineWithNoModelChosenIsAlsoReported() {
        val result = Pipeline.run(
            blank(48),
            TraceParams(edge = EdgeParams(engine = EdgeEngine.MODEL, modelId = "")),
            classify = false,
        )
        assertTrue(
            result.notes.any { it.contains("no model was chosen") },
            "notes were ${result.notes}",
        )
    }

    @Test
    fun aMatteThatEatsMostOfTheFrameSaysSoInPlainEnglish() {
        val result = Pipeline.run(
            cross(128, 12),
            TraceParams(matte = MatteParams(mode = MatteMode.BORDER_FLOOD, tolerance = 0.2f)),
            classify = false,
        )
        val note = assertNotNull(
            result.notes.firstOrNull { it.contains("background matte removed") },
            "the matte was not reported at all: ${result.notes}",
        )
        assertTrue(note.contains("%"), "the note must quantify: '$note'")
        assertTrue(
            note.contains("if part of your artwork is missing"),
            "a matte over the alarm threshold must warn: '$note'",
        )
    }

    @Test
    fun outlineModeExplainsWhyItSkippedSkeletonisation() {
        val result = Pipeline.run(
            cross(128, 12),
            TraceParams(
                cleanup = CleanupParams(skeletonize = true),
                output = OutputParams(vectorMode = VectorModeParam.OUTLINE),
            ),
            classify = false,
        )
        assertTrue(
            result.notes.any { it.contains("Outline mode traces region boundaries") },
            "notes were ${result.notes}",
        )
        assertTrue(result.document.shapeCount() > 0, "notes were ${result.notes}")
    }

    @Test
    fun everyNoteIsASentenceAndNoneAreBlank() {
        val result = Pipeline.run(
            cross(128, 12),
            TraceParams(
                matte = MatteParams(mode = MatteMode.BORDER_FLOOD, tolerance = 0.2f),
                cleanup = CleanupParams(minBlobArea = 64, keepLargest = 1),
                output = OutputParams(minPathLength = 6f),
            ),
            classify = false,
        )
        assertTrue(result.notes.isNotEmpty())
        for (note in result.notes) {
            assertTrue(note.isNotBlank(), "a blank note is worse than no note")
            assertTrue(note.length > 20, "a note has to be readable: '$note'")
            assertTrue(note.trim().endsWith("."), "a note has to be a sentence: '$note'")
        }
    }

    @Test
    fun aOnePixelImageProducesAnEmptyDocumentRatherThanThrowing() {
        val img = RgbaImage(1, 1, intArrayOf(RgbaImage.argb(255, 10, 10, 10)))
        val result = Pipeline.run(img, TraceParams(), classify = false)
        assertEquals(Pipeline.stageIds().size, result.stages.size)
        assertEquals(0, result.document.shapeCount())
        assertEquals(1f, result.document.width)
        assertTrue(result.notes.isNotEmpty(), "an empty result must explain itself")
    }

    @Test
    fun aBlankImageProducesAnEmptyDocumentAndSaysWhy() {
        val result = Pipeline.run(blank(64), TraceParams(), classify = false)
        assertEquals(0, result.document.shapeCount())
        assertTrue(
            result.notes.any { it.contains("No ink survived") },
            "notes were ${result.notes}",
        )
        assertEquals(1, result.document.layers.size)
    }

    @Test
    fun aOneRowImageSurvivesEveryStage() {
        val img = RgbaImage(64, 1).fill(white)
        for (x in 20 until 40) img[x, 0] = black
        val result = Pipeline.run(img, TraceParams(), classify = false)
        assertEquals(Pipeline.stageIds().size, result.stages.size)
        assertEquals(64f, result.document.width)
        assertEquals(1f, result.document.height)
    }

    @Test
    fun widthModulationMeasuresTheStrokeAndEmitsFilledOutlines() {
        val result = Pipeline.run(
            cross(128, 16),
            TraceParams(
                edge = EdgeParams(engine = EdgeEngine.ADAPTIVE),
                output = OutputParams(modulateWidth = true),
            ),
            classify = false,
        )
        val dt = assertNotNull(result.distanceTransform, "modulateWidth must produce a transform")
        assertEquals(result.workingWidth, dt.width)
        assertEquals(result.workingHeight, dt.height)
        assertTrue(dt.range().second > 4f, "peak distance was ${dt.range().second}")
        assertTrue(result.document.shapeCount() > 0, "notes were ${result.notes}")

        val shapes = result.document.layers.flatMap { it.shapes }
        assertTrue(
            shapes.any { it.style.fill != null && it.style.stroke == null },
            "SVG has no variable-width stroke, so these must be filled outlines",
        )
    }

    @Test
    fun theStageListIsStableUniqueAndFullyLabelled() {
        assertEquals(19, Stages.ALL.size)
        assertEquals(Stages.ALL.map { it.id }, Pipeline.stageIds())
        assertEquals(Stages.ALL.size, Stages.ALL.map { it.id }.toSet().size)
        for (s in Stages.ALL) {
            assertTrue(s.label.isNotBlank(), "${s.id} has no label")
            assertEquals(s, Stages.byId(s.id))
        }
        assertNull(Stages.byId("not-a-stage"))
    }

    @Test
    fun theStagesThatSeamWhenTiledAreMarkedGlobal() {
        val global = Stages.global().map { it.id }.toSet()
        val local = Stages.tileLocal().map { it.id }.toSet()
        assertEquals(Stages.ALL.size, global.size + local.size)
        assertTrue(Stages.CONTRAST in global, "CLAHE needs a global tile lattice")
        assertTrue(Stages.BINARISE in global, "Otsu needs a global histogram")
        assertTrue(Stages.BLOBS in global, "connected components need global connectivity")
        assertTrue(Stages.SKELETON in global, "thinning needs global connectivity")
        assertTrue(Stages.VECTORISE in global, "contour tracing needs global connectivity")
        assertTrue(Stages.MATTE in global, "a border flood and an FFT are both whole-image")
        assertTrue(Stages.DENOISE in local, "a noise filter is exactly what tiling is for")
    }

    @Test
    fun theStageOrderMatchesTheDocumentedPipeline() {
        val ids = Pipeline.stageIds()
        fun before(a: String, b: String) =
            assertTrue(ids.indexOf(a) < ids.indexOf(b), "$a must run before $b")

        before(Stages.DOWNSCALE, Stages.GRAYSCALE)
        before(Stages.DENOISE, Stages.CONTRAST)
        before(Stages.CONTRAST, Stages.MATTE)
        before(Stages.MATTE, Stages.EDGE)
        before(Stages.EDGE, Stages.BINARISE)
        before(Stages.BINARISE, Stages.MORPHOLOGY)
        before(Stages.MORPHOLOGY, Stages.BLOBS)
        before(Stages.BLOBS, Stages.BRIDGE)
        before(Stages.BRIDGE, Stages.SKELETON)
        before(Stages.SKELETON, Stages.PRUNE)
        before(Stages.PRUNE, Stages.VECTORISE)
        before(Stages.DISTANCE, Stages.VECTORISE)
        before(Stages.VECTORISE, Stages.STYLE)
        before(Stages.STYLE, Stages.ASSEMBLE)
    }

    @Test
    fun aPreviewRunsTheSameStagesAtALowerResolutionAndAgreesAboutTheFrame() {
        val src = cross(512, 48)
        val params = TraceParams(edge = EdgeParams(engine = EdgeEngine.ADAPTIVE, adaptiveRadius = 12))
        val full = Pipeline.run(src, params, classify = false)
        val preview = Pipeline.runPreview(src, params, 256)

        assertEquals(full.stages.map { it.id }, preview.stages.map { it.id })
        assertEquals(512, longEdgeOf(full))
        assertEquals(256, longEdgeOf(preview))
        assertNull(preview.profile, "a preview must not pay for classification")

        assertEquals(full.document.width, preview.document.width)
        assertEquals(full.document.height, preview.document.height)
        assertTrue(preview.document.shapeCount() > 0, "preview notes were ${preview.notes}")
        assertTrue(full.document.shapeCount() > 0, "full notes were ${full.notes}")

        val fullBounds = full.document.bounds()
        val previewBounds = preview.document.bounds()
        for (i in 0..3) {
            assertTrue(
                abs(fullBounds[i] - previewBounds[i]) < 24f,
                "preview and export disagree about the drawing's extent: " +
                    "${fullBounds.toList()} vs ${previewBounds.toList()}",
            )
        }
    }

    @Test
    fun aPreviewOfAnImageSmallerThanThePreviewSizeIsJustTheFullTrace() {
        val src = cross(200, 20)
        val preview = Pipeline.runPreview(src, TraceParams(), 720)
        assertEquals(200, longEdgeOf(preview))
        assertEquals(200f, preview.document.width)
    }

    @Test
    fun previewScalingMovesTheGeometricKnobsAndLeavesTheFilterSigmasAlone() {
        val base = Styles.default().params
        val scaled = Preview.scaleToPreview(base, 1024, 0.5f)

        assertEquals(1024, scaled.preprocess.workingLongEdge)
        assertEquals(base.output.strokeWidth * 0.5f, scaled.output.strokeWidth)
        assertEquals(base.output.minPathLength * 0.5f, scaled.output.minPathLength)
        assertEquals(base.output.simplify * 0.5f, scaled.output.simplify)
        assertEquals(base.cleanup.minBlobArea / 4, scaled.cleanup.minBlobArea)
        assertTrue(scaled.cleanup.closeRadius >= 1, "a non-zero radius must not scale away to nothing")

        assertEquals(base.edge.blurSigma, scaled.edge.blurSigma)
        assertEquals(base.edge.dogSigma, scaled.edge.dogSigma)
        assertEquals(base.edge.flow.sigmaM, scaled.edge.flow.sigmaM)
        assertEquals(base.output.corner, scaled.output.corner)
        assertEquals(base.output.smoothIterations, scaled.output.smoothIterations)
        assertEquals(base.edge.sensitivity, scaled.edge.sensitivity)
        assertEquals(base.cleanup.keepLargest, scaled.cleanup.keepLargest)
    }

    @Test
    fun previewScalingIsLegalForEveryStyleAtEveryRatio() {
        for (style in Styles.ALL) {
            for (ratio in listOf(0f, 0.1f, 0.5f, 1f, 2f, Float.NaN)) {
                val scaled = Preview.scaleToPreview(style.params, 512, ratio)
                assertEquals(
                    scaled, scaled.sanitized(),
                    "${style.id} at ratio $ratio produced an unsanitised result",
                )
                assertEquals(512, scaled.preprocess.workingLongEdge)
            }
        }
    }
}
