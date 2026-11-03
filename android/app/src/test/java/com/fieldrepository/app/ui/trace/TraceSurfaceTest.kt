package com.fieldrepository.app.ui.trace

import com.offlinetracer.pipeline.Knobs
import com.offlinetracer.pipeline.Stages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceSurfaceTest {

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

    @Test
    fun theSharedStageIdsSitAtDifferentPositions() {
        val shared = TRACE_STAGES.map { it.id }.intersect(TRACE_ENGINE_STAGES.map { it.id }.toSet())
        assertTrue("The two tables share nothing at all, which is not what was measured", shared.isNotEmpty())
        val moved = shared.count { id ->
            TRACE_STAGES.indexOfFirst { it.id == id } != TRACE_ENGINE_STAGES.indexOfFirst { it.id == id }
        }
        assertTrue("No shared id moved, so the wrong-number bug could not happen", moved > 0)
    }

    @Test
    fun theSpokenStageNumberIsAgainstTheStagesThatActuallyRan() {
        val stage = TRACE_ENGINE_STAGES[6]
        val progress = TraceProgress(stage.id, stage.label, 6f / TRACE_ENGINE_STAGES.size)
        assertEquals(
            "${stage.label}. Stage 7 of ${TRACE_ENGINE_STAGES.size}.",
            traceProgressSentence(progress, TRACE_ENGINE_STAGES),
        )
    }

    @Test
    fun anUnknownStageIsDescribedByItsLabelAlone() {
        val progress = TraceProgress("something-newer", "Doing something new", 0.5f)
        assertEquals("Doing something new", traceProgressSentence(progress, TRACE_STAGES))
    }

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

    @Test
    fun emptyOrZeroTimingsFallBackRatherThanDividingByZero() {
        assertFalse(TraceProgressWeights.from(emptyList()).measured)
        assertFalse(
            TraceProgressWeights.from(listOf(TraceStageTiming("prepare", "Preparing", 0L))).measured,
        )
    }

    @Test
    fun anUnknownIdFallsBackToTheEnginesOwnFraction() {
        assertEquals(0.42f, TraceProgressWeights.Unweighted.fractionAt("assemble", 0.42f), 1e-6f)
    }

    @Test
    fun theProtectedKnobNamesAreTheEnginesOwn() {
        assertEquals(Knobs.ALL, TRACE_KNOB_NAMES)
        assertEquals(Knobs.ALL.toSet(), TRACE_KNOB_KEY_FOR_NAME.keys)
    }

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

    @Test
    fun aTinyFrameDoesNotProduceAnImpossibleBox() {
        val clamped = traceClampCrop(TraceCropRect(0, 0, 100, 100), 8, 8)
        assertEquals(8, clamped.width)
        assertEquals(8, clamped.height)
    }

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

    @Test
    fun theWholeFrameIsNotCopied() {
        val src = ByteArray(4 * 4 * 4)
        val cropped = traceCropRgba(src, 4, 4, traceWholeFrame(4, 4))!!
        assertTrue("A second copy of a 48 MB buffer is how a 2 GB handset dies", cropped.rgba === src)
    }

    @Test
    fun aCropCopiesTheRightRows() {
        val src = ByteArray(40 * 40 * 4) { (it % 251).toByte() }
        val cropped = traceCropRgba(src, 40, 40, TraceCropRect(0, 10, 40, 20))!!
        assertEquals(40 * 20 * 4, cropped.rgba.size)
        assertEquals("The first output byte must be the first byte of source row 10", src[10 * 40 * 4], cropped.rgba[0])
        assertEquals("…and the last must be the last byte of source row 29", src[30 * 40 * 4 - 1], cropped.rgba.last())
    }

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

    @Test
    fun theCropClauseIsSpelledTheWayTheOtherClientSpellsIt() {
        val note = traceCropNote(TraceCropRect(30, 40, 900, 1200), 2000, 1500)
        assertEquals("Cropped on the device to 900x1200 at (30, 40) of 2000x1500.", note)
        assertEquals("", traceCropNote(traceWholeFrame(2000, 1500), 2000, 1500))
    }

    @Test
    fun theReadoutIsAPercentageOfAreaAndNotOfWidth() {
        val readout = traceCropReadout(TraceCropRect(0, 0, 500, 500), 1000, 1000)
        assertTrue("Half the width and half the height keeps a quarter", readout.contains("25%"))
        assertTrue(readout.contains("500×500"))
        assertTrue(traceCropReadout(traceWholeFrame(800, 600), 800, 600).startsWith("The whole photograph"))
    }

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
        assertTrue(note.contains("Use this frame"))
    }

    @Test
    fun theAverageRoundsHalfToEvenRatherThanFlooring() {
        assertEquals("127.5 ties up, to the even 128", 128, traceClampedAverage(255L, 2))
        assertEquals("128.5 ties down, to the even 128", 128, traceClampedAverage(257L, 2))
        assertEquals("1.5 ties up, to the even 2 — flooring would say 1", 2, traceClampedAverage(3L, 2))
        assertEquals("2.5 ties down, to the even 2", 2, traceClampedAverage(5L, 2))
        assertEquals(0, traceClampedAverage(1L, 4))
        assertEquals(1, traceClampedAverage(3L, 4))
    }

    @Test
    fun theAverageIsClampedAndNeverNegative() {
        assertEquals(0, traceClampedAverage(-5L, 2))
        assertEquals(0, traceClampedAverage(10L, 0))
        assertEquals(255, traceClampedAverage(10_000L, 2))
    }

    @Test
    fun aFlatSourceResamplesToItself() {
        val src = ByteArray(8 * 8 * 4) { if (it % 4 == 3) 0xFF.toByte() else 0x40 }
        val out = IntArray(4)
        traceResampleRow(src, 8, 8, 4, 4, 0, out)
        out.forEach { assertEquals(0xFF404040.toInt(), it) }
    }

    @Test
    fun theResampledRowIsAlwaysOpaque() {
        val src = ByteArray(4 * 4 * 4)
        val out = IntArray(2)
        traceResampleRow(src, 4, 4, 2, 2, 0, out)
        out.forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) }
    }

    @Test
    fun theDifferenceIsAbsoluteAndPerChannel() {
        val photograph = intArrayOf(0xFF102030.toInt())
        val trace = intArrayOf(0xFF302010.toInt())
        val out = IntArray(1)
        traceDifferenceRow(photograph, trace, out, 1)
        assertEquals(0xFF200020.toInt(), out[0])
    }

    @Test
    fun agreementReadsAsBlack() {
        val same = intArrayOf(0xFF123456.toInt())
        val out = IntArray(1)
        traceDifferenceRow(same, same, out, 1)
        assertEquals(0xFF000000.toInt(), out[0])
    }

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

    @Test
    fun aReducedPairSaysSoAndAFullSizeOneSaysNothing() {
        assertEquals("", traceComparisonReduction(1024, 768, 1024, 768))
        val note = traceComparisonReduction(1024, 768, 4096, 3072)
        assertTrue(note.contains("1024×768"))
        assertTrue(note.contains("4096×3072"))
    }

    @Test
    fun aPlateRefusalSaysTheDrawingIsUnaffectedFirst() {
        assertTrue(TRACE_PLATE_MEMORY_REFUSAL.contains("unaffected"))
        assertTrue(traceSentence(TraceFailureKind.FRAME_MISMATCH).contains("unaffected"))
        assertTrue(TRACE_DIFFERENCE_REFUSAL.contains("unaffected"))
    }

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

    @Test
    fun everyExportRefusalNamesSomethingThatStillWorks() {
        listOf(TRACE_NO_EXPORTER_SENTENCE, TRACE_NO_GEOMETRY_SENTENCE, TRACE_EXPORT_MEMORY_SENTENCE)
            .forEach {
                assertTrue("A refusal that names no remedy is a dead end: $it", it.contains("SVG"))
            }
    }

    @Test
    fun anUntouchedCardHasNoSummary() {
        assertNull(traceCardSummary("", 0, 0, wasPreview = true, attachedName = ""))
    }

    @Test
    fun theSummaryNamesBothTheSheetAndWhatCameOutOfIt() {
        val summary = traceCardSummary("sheet-3.jpg", 412, 8100, wasPreview = false, attachedName = "")
        assertEquals("traced from “sheet-3.jpg” · 412 paths · 8100 nodes", summary)
    }

    @Test
    fun aPreviewIsNamedAsOne() {
        val summary = traceCardSummary("sheet-3.jpg", 12, 40, wasPreview = true, attachedName = "")
        assertTrue(summary!!.startsWith("a preview traced from"))
    }

    @Test
    fun anAttachmentIsReportedEvenWithNoDrawingLeft() {
        assertEquals(
            "added as “sheet-3-line-art.svg”",
            traceCardSummary("", 0, 0, wasPreview = true, attachedName = "sheet-3-line-art.svg"),
        )
    }

    @Test
    fun theReplaceWarningIsAbsentWhenThereIsNothingToReplace() {
        assertNull(tracePanelReplaceWarning(null))
        assertNull(tracePanelReplaceWarning("   "))
        assertEquals(
            "“sheet-3-line-art.svg” is attached here now. This replaces it.",
            tracePanelReplaceWarning(" sheet-3-line-art.svg "),
        )
    }

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

    @Test
    fun aTreeWithNoResolutionLeafBarsNothing() {
        assertNull(traceCostRefusal(traceValuesOf("{}"), TraceAvailability(2048, 1024, null)))
    }

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
