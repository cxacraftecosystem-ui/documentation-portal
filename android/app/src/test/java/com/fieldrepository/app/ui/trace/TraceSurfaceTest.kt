package com.fieldrepository.app.ui.trace

import com.offlinetracer.pipeline.Knobs
import com.offlinetracer.pipeline.Stages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The surface's own arithmetic and wording: the stage tables, the progress weighting, the crop, the
 * comparison plates, the export routing and the panel's sentences.
 *
 * Every function here is pure, which is the reason the files that hold them import no `android.*` and
 * hold no composition.
 */
class TraceSurfaceTest {

    /* ══════════════════════════════════════════════════════════════════════════════════════════
     * Stages
     * ══════════════════════════════════════════════════════════════════════════════════════════ */

    /**
     * **THE REAL DIVERGENCE BETWEEN THE TWO VENDORED ENGINES.** The TypeScript's table fuses steps this
     * one reports separately, so the twelve-row table and the engine's own list are different lengths
     * and seven ids sit at different positions. The panel passes the engine's list for exactly this
     * reason; a surface that read the table would speak a wrong stage number confidently.
     */
    @Test
    fun theEnginesStageListIsNotTheWebsTable() {
        assertEquals(12, TRACE_STAGE_COUNT)
        assertEquals(Stages.ALL.size, TRACE_ENGINE_STAGES.size)
        assertNotEquals(
            "If these ever become equal, traceProgressSentence can stop taking a parameter",
            TRACE_STAGES.size,
            TRACE_ENGINE_STAGES.size,
        )
        assertEquals(Stages.ALL.map { it.id }, TRACE_ENGINE_STAGES.map { it.id })
    }

    /** And the ids that collide really do sit at different positions, which is the damage. */
    @Test
    fun theSharedStageIdsSitAtDifferentPositions() {
        val shared = TRACE_STAGES.map { it.id }.intersect(TRACE_ENGINE_STAGES.map { it.id }.toSet())
        assertTrue("The two tables share nothing at all, which is not what was measured", shared.isNotEmpty())
        val moved = shared.count { id ->
            TRACE_STAGES.indexOfFirst { it.id == id } != TRACE_ENGINE_STAGES.indexOfFirst { it.id == id }
        }
        assertTrue("No shared id moved, so the wrong-number bug could not happen", moved > 0)
    }

    /** The spoken sentence counts against the list it was GIVEN, never against the table. */
    @Test
    fun theSpokenStageNumberIsAgainstTheStagesThatActuallyRan() {
        val stage = TRACE_ENGINE_STAGES[6]
        val progress = TraceProgress(stage.id, stage.label, 6f / TRACE_ENGINE_STAGES.size)
        assertEquals(
            "${stage.label}. Stage 7 of ${TRACE_ENGINE_STAGES.size}.",
            traceProgressSentence(progress, TRACE_ENGINE_STAGES),
        )
    }

    /** An id the given list does not know is described by its label alone rather than a wrong number. */
    @Test
    fun anUnknownStageIsDescribedByItsLabelAlone() {
        val progress = TraceProgress("something-newer", "Doing something new", 0.5f)
        assertEquals("Doing something new", traceProgressSentence(progress, TRACE_STAGES))
    }

    /**
     * THE BAR IS A STAGE COUNT UNTIL THIS DEVICE HAS FINISHED ONE TRACE, and it says so. After that the
     * weights come from the engine's OWN ids and timings, so the nineteen-stage route gets correctly
     * measured weights with nothing edited.
     */
    @Test
    fun theWeightsAreMeasuredAfterOneRunAndHonestBeforeIt() {
        assertFalse(TraceProgressWeights.Unweighted.measured)
        val weights = TraceProgressWeights.from(
            listOf(
                TraceStageTiming("prepare", "Preparing", 100L),
                TraceStageTiming("edge", "Detecting edges", 900L),
            )
        )
        assertTrue(weights.measured)
        assertEquals(0f, weights.fractionAt("prepare", 0.5f), 1e-6f)
        assertEquals(0.1f, weights.fractionAt("edge", 0.5f), 1e-6f)
    }

    /**
     * A preview reports no timings at all, and a trace of a blank sheet can finish fast enough for every
     * stage to round to zero. Both are real, so neither divides by zero.
     */
    @Test
    fun emptyOrZeroTimingsFallBackRatherThanDividingByZero() {
        assertFalse(TraceProgressWeights.from(emptyList()).measured)
        assertFalse(
            TraceProgressWeights.from(listOf(TraceStageTiming("prepare", "Preparing", 0L))).measured,
        )
    }

    /** An unknown id falls back to the fraction the engine sent, which is the ordinary first-run path. */
    @Test
    fun anUnknownIdFallsBackToTheEnginesOwnFraction() {
        assertEquals(0.42f, TraceProgressWeights.Unweighted.fractionAt("assemble", 0.42f), 1e-6f)
    }

    /* ── The protected knobs ────────────────────────────────────────────────────────────────── */

    /**
     * A CONTRACT WITH A FILE NOBODY HERE MAY EDIT. The engine's own sanitiser does not validate the
     * hand-tuned list against these six names, so a typo would be silently unprotected — which is why
     * the table is written now, pinned now, for the day somebody offers automatic detection.
     */
    @Test
    fun theProtectedKnobNamesAreTheEnginesOwn() {
        assertEquals(Knobs.ALL, TRACE_KNOB_NAMES)
        assertEquals(Knobs.ALL.toSet(), TRACE_KNOB_KEY_FOR_NAME.keys)
    }

    /** And every one of them names a control this panel actually draws. */
    @Test
    fun everyProtectedKnobNamesALeafThisPanelOffers()

    {
        TRACE_KNOB_KEY_FOR_NAME.values.forEach { key ->
            assertTrue("$key is protected but is not an engine leaf", traceIsLeafKey(key))
            assertTrue(
                "$key is protected but this panel draws no control for it, so a future " +
                    "AutoMode.APPLY could not build the hand-tuned set from rows somebody moved",
                TRACE_CONTROLS.any { it.key == key },
            )
        }
    }

    /* ══════════════════════════════════════════════════════════════════════════════════════════
     * The crop
     * ══════════════════════════════════════════════════════════════════════════════════════════ */

    /**
     * **THE CLAMP ORDER MATTERS.** Clamping the origin first lets a large box push itself back off the
     * far edge — a rectangle that reads pixels which are not there, which on this client is a row copy
     * running past the end of a `ByteArray`.
     */
    @Test
    fun anOversizedBoxIsShrunkBeforeItsOriginIsMoved() {
        val clamped = traceClampCrop(TraceCropRect(900, 900, 500, 500), 1000, 1000)
        assertTrue("The box must stay inside the frame", clamped.x + clamped.width <= 1000)
        assertTrue(clamped.y + clamped.height <= 1000)
        assertEquals(500, clamped.width)
        assertEquals(500, clamped.height)
    }

    @Test
    fun aBoxIsNeverSmallerThanTheMinimumEdge() {
        val tiny = traceClampCrop(TraceCropRect(0, 0, 1, 1), 1000, 1000)
        assertEquals(TRACE_CROP_MIN_EDGE_PX, tiny.width)
        assertEquals(TRACE_CROP_MIN_EDGE_PX, tiny.height)
    }

    /** A frame smaller than the minimum is not a reason to refuse: the minimum bends to the frame. */
    @Test
    fun aTinyFrameDoesNotProduceAnImpossibleBox() {
        val clamped = traceClampCrop(TraceCropRect(0, 0, 100, 100), 8, 8)
        assertEquals(8, clamped.width)
        assertEquals(8, clamped.height)
    }

    /**
     * **THE MOVED EDGES ARE CLAMPED, NOT THE FINISHED RECTANGLE.** Building a rectangle first and
     * clamping it turns a negative width into a minimum-width box at the ORIGINAL x, so the box jumps
     * sideways away from the handle being dragged.
     */
    @Test
    fun draggingACornerPastItsOppositeDoesNotMoveTheOtherTwo() {
        val start = TraceCropRect(100, 100, 200, 200)
        val dragged = traceMoveCorner(start, TraceCropCorner.TOP_LEFT, 10_000, 10_000, 1000, 1000)
        assertEquals("The right edge must not move", 300, dragged.x + dragged.width)
        assertEquals("The bottom edge must not move", 300, dragged.y + dragged.height)
        assertEquals(TRACE_CROP_MIN_EDGE_PX, dragged.width)
    }

    @Test
    fun slidingTheBoxKeepsItsSize() {
        val moved = traceMoveCrop(TraceCropRect(100, 100, 200, 200), 10_000, 0, 1000, 1000)
        assertEquals(200, moved.width)
        assertEquals(800, moved.x)
    }

    /**
     * A rectangle drawn on one frame and clamped — not SCALED — into a smaller one traces the top-left
     * corner of what somebody framed, with nothing on screen to say so.
     */
    @Test
    fun aBoxIsRescaledWhenTheDecodeComesBackAtADifferentSize() {
        val choice = TraceFrameChoice(TraceCropRect(2000, 1000, 1000, 500), 4000, 2000)
        val inHalf = traceCropIn(choice, 2000, 1000)
        assertEquals(1000, inHalf.x)
        assertEquals(500, inHalf.y)
        assertEquals(500, inHalf.width)
        assertEquals(250, inHalf.height)
    }

    @Test
    fun theOrdinaryPathIsANoOp() {
        val choice = TraceFrameChoice(TraceCropRect(10, 20, 100, 200), 1000, 1000)
        assertEquals(choice.rect, traceCropIn(choice, 1000, 1000))
    }

    /** The whole frame comes back as the source ITSELF, not as a second copy of a large buffer. */
    @Test
    fun theWholeFrameIsNotCopied() {
        val src = ByteArray(4 * 4 * 4)
        val cropped = traceCropRgba(src, 4, 4, traceWholeFrame(4, 4))!!
        assertTrue("A second copy of a 48 MB buffer is how a 2 GB handset dies", cropped.rgba === src)
    }

    /**
     * The frame is 40 px and not 4, because the minimum edge is 16: on a 4 px frame every crop IS the
     * whole frame and the copy below would never run. That is correct behaviour and it is asserted
     * separately above; this test is about the row arithmetic.
     */
    @Test
    fun aCropCopiesTheRightRows() {
        val src = ByteArray(40 * 40 * 4) { (it % 251).toByte() }
        val cropped = traceCropRgba(src, 40, 40, TraceCropRect(0, 10, 40, 20))!!
        assertEquals(40 * 20 * 4, cropped.rgba.size)
        assertEquals("The first output byte must be the first byte of source row 10", src[10 * 40 * 4], cropped.rgba[0])
        assertEquals("…and the last must be the last byte of source row 29", src[30 * 40 * 4 - 1], cropped.rgba.last())
    }

    /** A crop offset in X is copied from the right COLUMN too, not only the right row. */
    @Test
    fun aCropCopiesTheRightColumns() {
        val src = ByteArray(40 * 40 * 4) { (it % 251).toByte() }
        val cropped = traceCropRgba(src, 40, 40, TraceCropRect(16, 0, 20, 20))!!
        assertEquals(20 * 20 * 4, cropped.rgba.size)
        assertEquals(src[16 * 4], cropped.rgba[0])
    }

    @Test
    fun aBufferTooSmallForItsFrameIsRefusedRatherThanRead() {
        assertNull(traceCropRgba(ByteArray(4), 100, 100, traceWholeFrame(100, 100)))
    }

    /** The provenance clause is the web's spelling, lower-case x and all, because it lands in a file. */
    @Test
    fun theCropClauseIsSpelledTheWayTheOtherClientSpellsIt() {
        val note = traceCropNote(TraceCropRect(30, 40, 900, 1200), 2000, 1500)
        assertEquals("Cropped on the device to 900x1200 at (30, 40) of 2000x1500.", note)
        assertEquals("", traceCropNote(traceWholeFrame(2000, 1500), 2000, 1500))
    }

    /** The on-screen readout uses this app's own typography, and the percentage is of AREA. */
    @Test
    fun theReadoutIsAPercentageOfAreaAndNotOfWidth() {
        val readout = traceCropReadout(TraceCropRect(0, 0, 500, 500), 1000, 1000)
        assertTrue("Half the width and half the height keeps a quarter", readout.contains("25%"))
        assertTrue(readout.contains("500×500"))
        assertTrue(traceCropReadout(traceWholeFrame(800, 600), 800, 600).startsWith("The whole photograph"))
    }

    /** A clamped number announces itself, and the two branches send people to different controls. */
    @Test
    fun aClampedNumberSaysWhichControlToUseNext() {
        val applied = traceClampCrop(TraceCropRect(9999, 0, 500, 500), 1000, 1000)
        assertTrue(traceCropClampNote("Left", 9999, applied, 1000, 1000).contains("Reduce Width first"))
        assertTrue(traceCropClampNote("Top", 9999, applied, 1000, 1000).contains("Reduce Height first"))
        assertTrue(traceCropClampNote("Width", 9999, applied, 1000, 1000).contains("between"))
    }

    @Test
    fun theStaleFrameNoteCarriesTheNumbersBeingTraced() {
        val note = traceCropStaleNote(TraceCropRect(0, 0, 640, 480))
        assertTrue(note.contains("640×480"))
        assertTrue(note.contains("Use this frame for the trace"))
    }

    /* ══════════════════════════════════════════════════════════════════════════════════════════
     * The comparison plates
     * ══════════════════════════════════════════════════════════════════════════════════════════ */

    /**
     * **ROUND HALF TO EVEN, BECAUSE THE OTHER CLIENT DOES.** A `Uint8ClampedArray` assignment is not a
     * cast: it rounds, and rounds a tie to the even neighbour. An integer division here would floor, so
     * a box averaging to 127.5 would be 127 on a handset and 128 in a browser — one count, on every
     * pixel of every plate, forever.
     */
    @Test
    fun theAverageRoundsHalfToEvenRatherThanFlooring() {
        // Every one of these is a TIE, which is the only case where flooring and ECMA's own
        // `ToUint8Clamp` disagree — and a tie is what a box of two identical-ish pixels produces.
        assertEquals("127.5 ties up, to the even 128", 128, traceClampedAverage(255L, 2))
        assertEquals("128.5 ties down, to the even 128", 128, traceClampedAverage(257L, 2))
        assertEquals("1.5 ties up, to the even 2 — flooring would say 1", 2, traceClampedAverage(3L, 2))
        assertEquals("2.5 ties down, to the even 2", 2, traceClampedAverage(5L, 2))
        // …and an ordinary non-tie still rounds the ordinary way.
        assertEquals(0, traceClampedAverage(1L, 4))
        assertEquals(1, traceClampedAverage(3L, 4))
    }

    @Test
    fun theAverageIsClampedAndNeverNegative() {
        assertEquals(0, traceClampedAverage(-5L, 2))
        assertEquals(0, traceClampedAverage(10L, 0))
        assertEquals(255, traceClampedAverage(10_000L, 2))
    }

    /** A flat source resamples to itself, whatever the box boundaries do. */
    @Test
    fun aFlatSourceResamplesToItself() {
        val src = ByteArray(8 * 8 * 4) { if (it % 4 == 3) 0xFF.toByte() else 0x40 }
        val out = IntArray(4)
        traceResampleRow(src, 8, 8, 4, 4, 0, out)
        out.forEach { assertEquals(0xFF404040.toInt(), it) }
    }

    /** THE ROW IS OPAQUE: a translucent photograph layer shows the layer beneath it. */
    @Test
    fun theResampledRowIsAlwaysOpaque() {
        val src = ByteArray(4 * 4 * 4) // every byte zero, i.e. transparent black
        val out = IntArray(2)
        traceResampleRow(src, 4, 4, 2, 2, 0, out)
        out.forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) }
    }

    /**
     * ABSOLUTE DIFFERENCE PER CHANNEL — one definition, no colour-space opinion, and what every image
     * editor's "difference" blend already means.
     */
    @Test
    fun theDifferenceIsAbsoluteAndPerChannel() {
        val photograph = intArrayOf(0xFF102030.toInt())
        val trace = intArrayOf(0xFF302010.toInt())
        val out = IntArray(1)
        traceDifferenceRow(photograph, trace, out, 1)
        // Red differs by 0x20, green not at all, blue by 0x20 — and the SIGN is dropped, which is why
        // a line the trace missed and a line it invented both come out bright.
        assertEquals(0xFF200020.toInt(), out[0])
    }

    @Test
    fun agreementReadsAsBlack() {
        val same = intArrayOf(0xFF123456.toInt())
        val out = IntArray(1)
        traceDifferenceRow(same, same, out, 1)
        assertEquals(0xFF000000.toInt(), out[0])
    }

    /* ── What the comparator says when there is nothing to show ─────────────────────────────── */

    /**
     * **AN ABSENCE IS A SENTENCE, NOT AN EMPTY SPACE**, and the branch order is load-bearing: plates on
     * screen win over everything, and a refusal comes before "tracing" because a refusal replaced by a
     * spinner would never be read.
     */
    @Test
    fun everyWayOfHavingNoComparisonHasItsOwnSentence() {
        assertEquals(
            "",
            traceComparisonStatus(hasPlates = true, running = false, failed = false, plateRefusal = "", hasResult = true),
        )
        assertTrue(
            traceComparisonStatus(true, running = true, failed = false, plateRefusal = "", hasResult = true)
                .contains("newer trace"),
        )
        assertEquals(
            "A refusal must not be replaced by a spinner",
            "why not",
            traceComparisonStatus(false, running = true, failed = false, plateRefusal = "why not", hasResult = true),
        )
        assertTrue(
            traceComparisonStatus(false, running = true, failed = false, plateRefusal = "", hasResult = false)
                .contains("Tracing…"),
        )
        assertTrue(
            "The failed branch points at the message rather than repeating it",
            traceComparisonStatus(false, running = false, failed = true, plateRefusal = "", hasResult = false)
                .contains("The reason is above"),
        )
        assertTrue(
            traceComparisonStatus(false, running = false, failed = false, plateRefusal = "", hasResult = false)
                .contains("as soon as the first trace finishes"),
        )
    }

    /** The reduction is stated, because without it nobody can tell whose loss they are looking at. */
    @Test
    fun aReducedPairSaysSoAndAFullSizeOneSaysNothing() {
        assertEquals("", traceComparisonReduction(1024, 768, 1024, 768))
        val note = traceComparisonReduction(1024, 768, 4096, 3072)
        assertTrue(note.contains("1024×768"))
        assertTrue(note.contains("4096×3072"))
    }

    /** Both plate refusals say the drawing survived, and say it before naming a remedy. */
    @Test
    fun aPlateRefusalSaysTheDrawingIsUnaffectedFirst() {
        assertTrue(TRACE_PLATE_MEMORY_REFUSAL.contains("unaffected"))
        assertTrue(traceSentence(TraceFailureKind.FRAME_MISMATCH).contains("unaffected"))
        assertTrue(TRACE_DIFFERENCE_REFUSAL.contains("unaffected"))
    }

    /* ══════════════════════════════════════════════════════════════════════════════════════════
     * Export routing
     * ══════════════════════════════════════════════════════════════════════════════════════════ */

    /**
     * THREE ROUTES, IN THE ORDER OF WHAT THEY NEED. The SVG needs nothing, the PNG needs the geometry
     * and the platform's encoder, and the other three need the vendored writers — which is what lets a
     * host with no writers still offer two formats.
     */
    @Test
    fun theSvgNeedsNothingAndIsAlwaysAvailable() {
        val svg = TRACE_EXPORT_FORMATS.first { it.id == "svg" }
        assertEquals(TraceExportPlan.FromTraceSvg, traceExportPlan(svg, exporterRefusal = "no writers"))
        assertEquals(TraceExportPlan.FromTraceSvg, traceExportPlan(svg, null, hasGeometry = false))
    }

    @Test
    fun thePngGoesThroughThePlatformAndNotThroughTheExporter() {
        val png = TRACE_EXPORT_FORMATS.first { it.id == "png" }
        assertEquals(
            "An exporter that refused everything must still leave the picture working",
            TraceExportPlan.FromPlatformRaster,
            traceExportPlan(png, exporterRefusal = "no writers"),
        )
    }

    /**
     * "NO GEOMETRY" AND "NO WRITERS" ARE TWO SENTENCES. A build with no writers saves the SVG and the
     * picture; a trace with no shapes saves the SVG alone. Different sets of working controls.
     */
    @Test
    fun theTwoRefusalsAreDifferentSentences() {
        val pdf = TRACE_EXPORT_FORMATS.first { it.id == "pdf" }
        val noGeometry = traceExportPlan(pdf, null, hasGeometry = false)
        val noWriters = traceExportPlan(pdf, exporterRefusal = TRACE_NO_EXPORTER_SENTENCE)
        assertTrue(noGeometry is TraceExportPlan.Refused)
        assertTrue(noWriters is TraceExportPlan.Refused)
        assertTrue(
            (noGeometry as TraceExportPlan.Refused).reason !=
                (noWriters as TraceExportPlan.Refused).reason,
        )
        assertEquals(TRACE_NO_GEOMETRY_SENTENCE, noGeometry.reason)
    }

    @Test
    fun aWillingExporterGetsTheOtherThree() {
        listOf("pdf", "eps", "dxf").forEach { id ->
            val row = TRACE_EXPORT_FORMATS.first { it.id == id }
            assertEquals(TraceExportPlan.FromExporter, traceExportPlan(row, null))
        }
    }

    /** Every refusal in this feature names what still WORKS, not only what does not. */
    @Test
    fun everyExportRefusalNamesSomethingThatStillWorks() {
        listOf(TRACE_NO_EXPORTER_SENTENCE, TRACE_NO_GEOMETRY_SENTENCE, TRACE_EXPORT_MEMORY_SENTENCE)
            .forEach {
                assertTrue("A refusal that names no remedy is a dead end: $it", it.contains("SVG"))
            }
    }

    /* ══════════════════════════════════════════════════════════════════════════════════════════
     * The panel's own sentences
     * ══════════════════════════════════════════════════════════════════════════════════════════ */

    /** A card nobody has touched reads as the invitation it was, rather than as an empty summary. */
    @Test
    fun anUntouchedCardHasNoSummary() {
        assertNull(traceCardSummary("", 0, 0, wasPreview = true, attachedName = ""))
    }

    /** IT NAMES THE PHOTOGRAPH AND THE COUNT, NOT ONE OR THE OTHER. */
    @Test
    fun theSummaryNamesBothTheSheetAndWhatCameOutOfIt() {
        val summary = traceCardSummary("sheet-3.jpg", 412, 8100, wasPreview = false, attachedName = "")
        assertEquals("traced from “sheet-3.jpg” · 412 paths · 8100 nodes", summary)
    }

    /** A preview says so, because a preview is not the drawing that gets added. */
    @Test
    fun aPreviewIsNamedAsOne() {
        val summary = traceCardSummary("sheet-3.jpg", 12, 40, wasPreview = true, attachedName = "")
        assertTrue(summary!!.startsWith("a preview traced from"))
    }

    /** An attachment outlives the drawing that made it, so it can stand alone. */
    @Test
    fun anAttachmentIsReportedEvenWithNoDrawingLeft() {
        assertEquals(
            "added as “sheet-3-line-art.svg”",
            traceCardSummary("", 0, 0, wasPreview = true, attachedName = "sheet-3-line-art.svg"),
        )
    }

    /** The replace warning is null rather than empty, so nothing can render an empty warning box. */
    @Test
    fun theReplaceWarningIsAbsentWhenThereIsNothingToReplace() {
        assertNull(tracePanelReplaceWarning(null))
        assertNull(tracePanelReplaceWarning("   "))
        assertEquals(
            "“sheet-3-line-art.svg” is attached here now. This replaces it.",
            tracePanelReplaceWarning(" sheet-3-line-art.svg "),
        )
    }

    /**
     * **THE HANDSET REFUSES RATHER THAN QUIETLY SUBSTITUTING A FASTER ENGINE.** One sheet of paper must
     * not produce two different drawings depending on which client traced it, so the refusal names the
     * remedy and the person chooses.
     */
    @Test
    fun anOversizedTraceIsRefusedWithTheRemedyNamed() {
        val availability = TraceAvailability(2048, 1024, measuredOn = null)
        assertNull(
            traceCostRefusal(traceValuesOf("""{"preprocess":{"workingLongEdge":2048},"edge":{"engine":"ADAPTIVE"}}"""), availability),
        )
        val tooBig = traceCostRefusal(
            traceValuesOf("""{"preprocess":{"workingLongEdge":4096},"edge":{"engine":"ADAPTIVE"}}"""),
            availability,
        )
        assertTrue(tooBig!!.contains("Choose a lower resolution"))
    }

    /** Flow gets its own ceiling, and the refusal points at a control the screen actually has. */
    @Test
    fun theFlowEngineIsBarredSeparatelyAndNamesTheDisclosureByItsRealName() {
        val availability = TraceAvailability(2048, 1024, measuredOn = null)
        val refusal = traceCostRefusal(
            traceValuesOf("""{"preprocess":{"workingLongEdge":2048},"edge":{"engine":"FDOG"}}"""),
            availability,
        )
        assertTrue(refusal!!.contains("Flow edge engine"))
        assertTrue(
            "A refusal that names a control the screen does not have reads as a different version of the app",
            refusal.contains(TRACE_DISCLOSURE_ACTION),
        )
    }

    /** A tree with no resolution leaf bars nothing, rather than guessing at a number. */
    @Test
    fun aTreeWithNoResolutionLeafBarsNothing() {
        assertNull(traceCostRefusal(traceValuesOf("{}"), TraceAvailability(2048, 1024, null)))
    }

    /**
     * **`Locale.ROOT`, PINNED AT THE FORMATTER.** Under a locale with a comma decimal separator this
     * would otherwise read "3,4 seconds" on a handset and "3.4 seconds" in this test.
     */
    @Test
    fun theDurationUsesADotWhateverTheDeviceLanguageIs() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("3.4 seconds", traceSeconds(3400L))
            assertEquals("820 milliseconds", traceSeconds(820L))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
