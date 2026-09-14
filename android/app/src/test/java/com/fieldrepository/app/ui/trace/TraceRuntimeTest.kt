package com.fieldrepository.app.ui.trace

import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.pipeline.EdgeEngine
import com.offlinetracer.pipeline.Pipeline
import com.offlinetracer.pipeline.TraceParams
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecPath
import com.offlinetracer.vector.VecPoint
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime's arithmetic and its two ends: pixels in, document out.
 *
 * The runtime CLASS holds a `Context` and cannot be built here — but everything that can be quietly
 * wrong about it is a top-level function that cannot, which is why they are top-level functions.
 * `traceRunEngine` is exercised for real against the vendored pipeline, on a tiny frame, because a
 * trace that never ran in a test is a trace nobody has checked the marshalling of.
 */
class TraceRuntimeTest {

    /* ── The memory arithmetic ──────────────────────────────────────────────────────────────── */

    /**
     * **THE TWO FIGURES `TraceRuntime.kt`'s HEADER STATES, PINNED SO THE PARAGRAPH CANNOT ROT.** A full
     * trace of a 1600x1200 frame costs 112 MB with the shipped flow default and 317 MB with Canny, in
     * this app's own Java heap.
     */
    @Test
    fun theHeadersOwnMemoryFiguresAreTheArithmeticsOwn() {
        val pixels = 1600L * 1200L
        val mb = { bytes: Long -> Math.round(bytes / (1024.0 * 1024.0)) }
        assertEquals(112L, mb(tracePeakBytes(pixels, pixels, EdgeEngine.FDOG)))
        assertEquals(317L, mb(tracePeakBytes(pixels, pixels, EdgeEngine.CANNY)))
    }

    /**
     * **AN UNMEASURED ENGINE TAKES THE LARGEST MEASURED VALUE.** Being wrong here costs a refusal with a
     * named remedy at the top of the resolution range; being wrong the other way costs an
     * `OutOfMemoryError` in a village.
     */
    @Test
    fun theUnmeasuredEnginesArePessimistic() {
        val worst = TRACE_ENGINE_BPP.values.max()
        assertEquals(worst, TRACE_ENGINE_BPP[EdgeEngine.XDOG])
        assertEquals(worst, TRACE_ENGINE_BPP[EdgeEngine.MODEL])
        assertEquals(worst, TRACE_ENGINE_BPP[EdgeEngine.CANNY])
        assertTrue("Flow is the cheapest per pixel and the slowest per second", TRACE_ENGINE_BPP[EdgeEngine.FDOG]!! < worst)
    }

    /** Every engine has a figure, so nothing silently falls through to the maximum by accident. */
    @Test
    fun everyEdgeEngineHasACostFigure() {
        EdgeEngine.entries.forEach {
            assertNotNull("$it has no bytes-per-pixel figure", TRACE_ENGINE_BPP[it])
        }
    }

    /**
     * **THE STAGES RUN AT THE WORKING SIZE, SO THE WORKING SIZE IS WHAT IS COUNTED.** Using the source
     * size would refuse traces the downscale makes comfortable.
     */
    @Test
    fun theWorkingSizeIsWhatDominatesTheEstimate() {
        val source = 4096L * 3072L
        val working = 1024L * 768L
        assertTrue(
            tracePeakBytes(source, working, EdgeEngine.CANNY) <
                tracePeakBytes(source, source, EdgeEngine.CANNY),
        )
    }

    @Test
    fun theWorkingPixelCountNeverUpscales() {
        val params = TraceParams(
            preprocess = TraceParams().preprocess.copy(workingLongEdge = 4096),
        ).sanitized()
        assertEquals(
            "The pipeline never upscales, so a small source stays small",
            800L * 600L,
            traceWorkingPixels(800, 600, params),
        )
    }

    /** A trace that fits is not refused, and one that does not names both numbers and two remedies. */
    @Test
    fun aRefusalNamesBothNumbersAndTheTwoControlsThatChangeThem() {
        val pixels = 1600L * 1200L
        assertNull(
            "A trace with room to spare must not be refused",
            traceMemoryRefusal(pixels, pixels, EdgeEngine.FDOG, heapBytes = 512L * 1024L * 1024L),
        )
        val refusal = traceMemoryRefusal(pixels, pixels, EdgeEngine.CANNY, heapBytes = 128L * 1024L * 1024L)
        assertNotNull(refusal)
        assertTrue("The sentence must carry what it needs", refusal!!.contains("317 MB"))
        assertTrue("…and what is available", refusal.contains("96 MB"))
        assertTrue("The resolution multiplies the working pixels", refusal.contains("resolution"))
        assertTrue("The engine multiplies the bytes each of them costs", refusal.contains("edge engine"))
    }

    /** The reserve is real: a trace that fits only by taking every byte kills the screen it is shown on. */
    @Test
    fun theReserveIsHeldBackForTheRestOfTheApp() {
        val pixels = 100L * 100L
        val exact = tracePeakBytes(pixels, pixels, EdgeEngine.FDOG)
        assertNotNull(
            "A trace that fits with nothing left over must still be refused",
            traceMemoryRefusal(pixels, pixels, EdgeEngine.FDOG, heapBytes = exact),
        )
        assertNull(
            traceMemoryRefusal(
                pixels, pixels, EdgeEngine.FDOG,
                heapBytes = exact + TRACE_ENGINE_HEAP_RESERVE_BYTES,
            ),
        )
    }

    /** The megabytes in the sentence are rounded, so they match the header's figures exactly. */
    @Test
    fun aNegativeHeadroomReadsAsZeroRatherThanAsANegativeNumber() {
        val refusal = traceMemoryRefusal(1L, 1L, EdgeEngine.CANNY, heapBytes = 0L)
        assertTrue(refusal!!.contains("about 0 MB"))
    }

    /* ── The preview divergence ─────────────────────────────────────────────────────────────── */

    /**
     * **THE ONE PLACE THIS FILE DECLINES THE VENDORED KOTLIN AND FOLLOWS THE VENDORED TYPESCRIPT.** The
     * engine's own preview helper rescales thirteen geometric knobs; the panel adopts a run's applied
     * parameters afterwards, so that would silently rewrite thirteen of somebody's settings to
     * preview-sized values and the full-resolution trace they press next would run with them.
     */
    @Test
    fun aPreviewLowersTheWorkingEdgeAndNothingElse() {
        val base = TraceParams(
            cleanup = TraceParams().cleanup.copy(minBlobArea = 24),
            output = TraceParams().output.copy(strokeWidth = 1.5f, minPathLength = 3f),
        ).sanitized()
        val preview = tracePreviewParams(base)

        assertEquals(TRACE_PREVIEW_LONG_EDGE, preview.preprocess.workingLongEdge)
        assertEquals(
            "If this ever changes, the panel must stop adopting a preview's applied parameters first",
            base.copy(preprocess = base.preprocess.copy(workingLongEdge = TRACE_PREVIEW_LONG_EDGE)).sanitized(),
            preview,
        )
        assertEquals(24, preview.cleanup.minBlobArea)
        assertEquals(1.5f, preview.output.strokeWidth, 1e-6f)
    }

    /* ── The SVG writer ─────────────────────────────────────────────────────────────────────── */

    private fun document(shapes: Int): VecDocument = VecDocument(
        width = 100f,
        height = 100f,
        layers = listOf(
            VecLayer(
                id = "trace",
                name = TRACE_LAYER_NAME,
                shapes = (0 until shapes).map {
                    VecShape(
                        path = VecPath(
                            VecPoint(it.toFloat(), 0f),
                            listOf(VecSeg.Line(VecPoint(it.toFloat(), 10f))),
                        ),
                        style = VecStyle(),
                    )
                },
            )
        ),
    )

    /** The branding is off, which is the one option here that is not a formatting preference. */
    @Test
    fun theWriterIsGivenNoMetadataAndFlattensItsOneLayer() {
        val svg = traceSvgOf(document(3))
        assertFalse("Another product's name must not reach an archived record", svg.svg.contains("Offline Tracer"))
        assertEquals(3, svg.shapesWritten)
        assertNull(svg.truncationNote)
        assertTrue(svg.svg.startsWith("<?xml"))
    }

    /** The SVG the panel attaches is the exporter's own answer, which is why the SVG route is a shortcut. */
    @Test
    fun theWriterIdentifiesItself() {
        assertEquals("core-export/SvgExport#export", TRACE_SVG_WRITER)
    }

    /**
     * **THE CAP IS REPORTED, NEVER SILENT**, and the geometry is NOT trimmed with it: the comparison
     * plate still shows the whole drawing and only the file is cut.
     */
    @Test
    fun theShapeCeilingCutsTheFileAndSaysSo() {
        assertNull(traceTruncationNote(100, 100))
        val note = traceTruncationNote(250_000, TRACE_MAX_SHAPES)
        assertNotNull(note)
        assertTrue("Raise “Minimum speck” and “Simplify” are controls this panel really has", note!!.contains("Minimum speck"))
        assertTrue(note.contains("Simplify"))
    }

    /**
     * **THE INDIAN DIGIT GROUPING IS WRITTEN OUT RATHER THAN DELEGATED.** A platform formatter answers
     * "250,000" on a desktop JVM and "2,50,000" on Android — the worst shape a formatting bug can take,
     * because the sentence somebody reads would differ from the sentence the test that guards it reads.
     */
    @Test
    fun theCountIsGroupedTheIndianWayOnEveryRuntime() {
        assertTrue(traceTruncationNote(250_000, 200_000)!!.contains("2,50,000"))
        assertTrue(traceTruncationNote(250_000, 200_000)!!.contains("2,00,000"))
        assertTrue(traceTruncationNote(1_500, 999)!!.contains("1,500"))
        assertTrue(traceTruncationNote(999, 500)!!.contains("999"))
    }

    /* ── The stage list ─────────────────────────────────────────────────────────────────────── */

    @Test
    fun theStageListIsReadFromTheEngineRatherThanTranscribed() {
        assertEquals(
            com.offlinetracer.pipeline.Stages.ALL.map { it.id to it.label },
            TRACE_ENGINE_STAGES.map { it.id to it.label },
        )
    }

    /** The suggestion is empty on this route, and the reason is written down rather than re-derived. */
    @Test
    fun theSuggestionNoteExplainsWhyTheFieldIsAlwaysEmpty() {
        assertTrue(TRACE_NO_SUGGESTION_NOTE.contains("sentence rather than a style id"))
    }

    /* ══════════════════════════════════════════════════════════════════════════════════════════
     * One real trace, end to end
     * ══════════════════════════════════════════════════════════════════════════════════════════ */

    /**
     * **A REAL RUN THROUGH THE VENDORED PIPELINE, ON A FRAME SMALL ENOUGH TO BE FREE.**
     *
     * The marshalling, the geometry serialisation, the SVG writer and the decode into the port's own
     * shapes are four conversions in a row, and each of them is the kind that produces a plausible wrong
     * answer rather than a failure. So one trace is run for real: a black bar on white, which produces
     * shapes, so the arrays below are not all empty.
     *
     * `runBlocking` is safe here because the engine is plain arithmetic with no looper and no main
     * dispatcher — the runtime's own `withContext(Dispatchers.Main)` hop is at the panel boundary, not
     * inside `traceRunEngine`, which is exactly why the progress pump takes the CALLER's context.
     */
    @Test
    fun aRealTraceProducesGeometryAnSvgAndTheStagesThatRan() = runBlocking {
        val width = 64
        val height = 64
        val pixels = IntArray(width * height) { index ->
            val y = index / width
            if (y in 28..35) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val decoded = traceRunEngine(
            src = RgbaImage(width, height, pixels),
            params = TraceParams().sanitized(),
            preview = false,
            onProgress = {},
        )

        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        assertTrue("A black bar on white must produce at least one path", decoded.shapeCount > 0)
        assertTrue(decoded.svg.startsWith("<?xml"))
        assertEquals(decoded.shapeCount, decoded.geometry.shapeCount)
        // The one check that would catch a serialiser that wrote a plausible wrong layout.
        decoded.geometry.validate()
        assertEquals(
            "Every stage the engine ran must be reported, or the weights cannot be measured",
            com.offlinetracer.pipeline.Stages.ALL.size,
            decoded.stages.size,
        )
        assertEquals("This engine's classifier cannot fill this field", "", decoded.suggestedStyleId)
    }

    /**
     * **ONE EVENT PER STAGE, AT ITS START.** This engine calls its listener TWICE per stage where the
     * TypeScript posts once, and the end event carries the same id — so forwarding both would show every
     * stage twice and make the fraction mean two different things.
     */
    @Test
    fun progressIsReportedOncePerStageAndNeverReachesOne() = runBlocking {
        val seen = mutableListOf<TraceProgress>()
        traceRunEngine(
            src = RgbaImage(32, 32, IntArray(32 * 32) { 0xFFFFFFFF.toInt() }),
            params = TraceParams().sanitized(),
            preview = false,
            onProgress = { seen += it },
        )
        assertEquals(
            "A stage was reported twice, which is the engine's end event leaking through",
            seen.map { it.stageId },
            seen.map { it.stageId }.distinct(),
        )
        assertTrue(seen.isNotEmpty())
        assertTrue("The engine's own fraction never reaches 1.0", seen.all { it.fraction < 1f })
    }

    /** A PREVIEW EMITS NO PROGRESS AT ALL, because the vendored worker passes no listener to one. */
    @Test
    fun aPreviewReportsNoProgressBecauseThereIsNoneToReport() = runBlocking {
        val seen = mutableListOf<TraceProgress>()
        traceRunEngine(
            src = RgbaImage(32, 32, IntArray(32 * 32) { 0xFFFFFFFF.toInt() }),
            params = TraceParams().sanitized(),
            preview = true,
            onProgress = { seen += it },
        )
        assertEquals(
            "A bar with no events would sit at zero and read as a hang; a working line is the truth",
            emptyList<TraceProgress>(),
            seen,
        )
    }

    /**
     * The port's `TraceDecoded` carries what the engine reported and recomputes nothing — in particular
     * the parameters the stages ACTUALLY RAN WITH, because auto-detection runs before the first stage.
     */
    @Test
    fun theDecodedResultCarriesTheParametersThatRan() {
        val result = Pipeline.run(
            RgbaImage(32, 32, IntArray(32 * 32) { 0xFFFFFFFF.toInt() }),
            TraceParams().sanitized(),
            null,
            com.offlinetracer.pipeline.CancellationToken(),
            classify = false,
        )
        val decoded = traceDecodedOf(result)
        assertEquals(
            traceValuesOfParams(result.appliedParams).wire,
            decoded.appliedParams.wire,
        )
        assertEquals(result.notes, decoded.notes)
        assertEquals(result.totalMillis, decoded.totalMillis)
    }

    /* ── The document, back out again ───────────────────────────────────────────────────────── */

    /**
     * **`traceDocumentOf` IS THE EXACT INVERSE OF `traceGeometryOf`.** They are walked minutes apart —
     * one at the end of a trace and one on an export button — so a layout disagreement between them
     * would be a PDF that does not match the SVG beside it.
     */
    @Test
    fun theGeometryRoundTripsBackIntoADocument() {
        val original = VecDocument(
            width = 200f,
            height = 100f,
            layers = listOf(
                VecLayer(
                    id = "trace",
                    name = TRACE_LAYER_NAME,
                    shapes = listOf(
                        VecShape(
                            VecPath(
                                VecPoint(1f, 2f),
                                listOf(
                                    VecSeg.Line(VecPoint(3f, 4f)),
                                    VecSeg.Quad(VecPoint(5f, 6f), VecPoint(7f, 8f)),
                                    VecSeg.Cubic(VecPoint(9f, 10f), VecPoint(11f, 12f), VecPoint(13f, 14f)),
                                ),
                                closed = true,
                            ),
                            VecStyle(),
                        )
                    ),
                )
            ),
        )
        val geometry = traceGeometryOf(original)
        geometry.validate()
        val back = traceDocumentOf(geometry, 200, 100, background = null).document

        assertEquals(original.shapeCount(), back.shapeCount())
        assertEquals(original.nodeCount(), back.nodeCount())
        assertEquals(
            original.layers[0].shapes[0].path.segments,
            back.layers[0].shapes[0].path.segments,
        )
        assertEquals(VecPoint(1f, 2f), back.layers[0].shapes[0].path.start)
        assertTrue(back.layers[0].shapes[0].path.closed)
    }

    /** A degenerate page size is clamped once here rather than by four writers each rescuing itself. */
    @Test
    fun aZeroPageSizeIsClampedAtTheSource() {
        val geometry = traceGeometryOf(document(1))
        val doc = traceDocumentOf(geometry, 0, 0, null).document
        assertEquals(1f, doc.width, 0f)
        assertEquals(1f, doc.height, 0f)
    }

    /** The style table is de-duplicated, so a run of identically-styled paths is one entry. */
    @Test
    fun identicallyStyledShapesShareOneStyleEntry() {
        val geometry = traceGeometryOf(document(50))
        assertEquals(50, geometry.shapeCount)
        assertEquals("50 identically-styled paths must not be 50 style rows", 1, geometry.styleTable.size)
    }
}
