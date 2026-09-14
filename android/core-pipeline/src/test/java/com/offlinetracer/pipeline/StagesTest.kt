package com.offlinetracer.pipeline

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.vector.FillRule
import com.offlinetracer.vector.VecPath
import com.offlinetracer.vector.VecPoint
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The stage catalogue and the individual stage transforms.
 *
 * Two separate things are being tested here and they fail differently:
 *
 *  - the **catalogue** is a compatibility surface. A progress UI enumerates [Stages.ids] ahead of
 *    time and matches the ids it is later handed, so the list, its order and its ids are pinned
 *    literally rather than derived from [Stages.ALL] — a test that reads its expectations out of the
 *    thing it is testing cannot catch a rename.
 *  - the **transforms** are pure functions of their arguments, which is what lets [Pipeline] and
 *    [Preview] run the identical list. Each one is exercised on geometry small enough that the right
 *    answer can be worked out by hand.
 */
class StagesTest {

    private val expectedIds = listOf(
        "orient",
        "perspective",
        "downscale",
        "grayscale",
        "denoise",
        "contrast",
        "matte",
        "crop",
        "edge",
        "binarise",
        "morphology",
        "blobs",
        "bridge",
        "skeleton",
        "prune",
        "distance",
        "vectorise",
        "style",
        "assemble",
    )

    // -------------------------------------------------------------------------------------------
    // The catalogue
    // -------------------------------------------------------------------------------------------

    @Test
    fun thereAreNineteenStagesInTheDocumentedOrder() {
        assertEquals(19, Stages.ALL.size)
        assertEquals(expectedIds, Stages.ids())
    }

    @Test
    fun theCatalogueAndThePipelineCannotDisagreeAboutTheStages() {
        // A progress bar built against Pipeline.stageIds() that reaches 100% two stages early is a bug
        // that only shows up on somebody else's phone, so the two lists are asserted to be one list.
        assertEquals(Stages.ids(), Pipeline.stageIds())
        assertEquals(Stages.ALL.map { it.id }, Pipeline.stageIds())
    }

    @Test
    fun idsAreUniqueAndEveryOneIsReachableThroughById() {
        assertEquals(Stages.ALL.size, Stages.ALL.map { it.id }.toSet().size)
        for (id in expectedIds) assertEquals(id, assertNotNull(Stages.byId(id)).id)
        assertNull(Stages.byId("not-a-stage"))
        assertNull(Stages.byId(""))
    }

    @Test
    fun everyStageHasADistinctHumanReadableLabel() {
        val labels = Stages.ALL.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "two stages share a label")
        for (s in Stages.ALL) {
            assertTrue(s.label.isNotBlank(), "${s.id} has no label")
            // Sentence case and no full stop: these are shown as a running status line, not as prose.
            assertTrue(s.label[0].isUpperCase(), "'${s.label}' is not sentence case")
            assertFalse(s.label.endsWith("."), "'${s.label}' must not end in a full stop")
        }
    }

    @Test
    fun tileLocalityPartitionsTheStagesAndOnlyThreeStagesMayBeTiled() {
        val local = Stages.tileLocal()
        val global = Stages.global()
        assertEquals(Stages.ALL.size, local.size + global.size, "the partition lost or duplicated a stage")
        assertTrue(local.none { it in global }, "a stage is in both halves of the partition")

        // isTileLocal is a correctness annotation, not a performance one (ALGORITHMS §13), so the set
        // is pinned: adding a stage to it silently licenses running that stage per tile.
        assertEquals(listOf(Stages.GRAYSCALE, Stages.DENOISE, Stages.MORPHOLOGY), local.map { it.id })

        // The reasons, restated as assertions: CLAHE needs a global tile lattice, Otsu a global
        // histogram, and components, thinning and contour tracing a global view of connectivity.
        for (id in listOf(
            Stages.CONTRAST, Stages.MATTE, Stages.EDGE, Stages.BINARISE,
            Stages.BLOBS, Stages.BRIDGE, Stages.SKELETON, Stages.PRUNE, Stages.VECTORISE,
        )) {
            assertFalse(assertNotNull(Stages.byId(id)).isTileLocal, "$id must see the whole frame")
        }
    }

    // -------------------------------------------------------------------------------------------
    // orient · downscale · grayscale
    // -------------------------------------------------------------------------------------------

    @Test
    fun orientIsDeliberatelyAPassThrough() {
        // The platform decoder has already applied EXIF by the time an RgbaImage exists, so rotating
        // here would rotate twice. The stage exists as a documented hook, and asserting identity is
        // what stops somebody "fixing" it into a second rotation.
        val src = RgbaImage(3, 2).fill(0xFF808080.toInt())
        assertSame(src, StageOps.orient(src, PreprocessParams()))
        assertSame(src, StageOps.orient(src, PreprocessParams(autoOrient = false)))
    }

    @Test
    fun downscaleFitsTheLongEdgeAndSaysSoInTheNotes() {
        val notes = ArrayList<String>()
        val small = StageOps.downscale(
            RgbaImage(400, 300).fill(0xFFFFFFFF.toInt()),
            PreprocessParams(workingLongEdge = 256),
            notes,
        )
        // 400 x 300 capped at 256 on the long edge is 256 x 192 exactly.
        assertEquals(256, small.width)
        assertEquals(192, small.height)
        assertEquals(1, notes.size)
        assertTrue(notes[0].contains("256x192"), notes[0])
        assertTrue(notes[0].contains("400x300"), notes[0])
        // "Why is my 12 MP photo traced at 2048 px" is a reasonable worry, so the note answers it.
        assertTrue(notes[0].contains("scaled back up"), notes[0])
    }

    @Test
    fun downscaleIsASilentNoOpWhenTheImageAlreadyFits() {
        val notes = ArrayList<String>()
        val src = RgbaImage(400, 300).fill(0xFFFFFFFF.toInt())
        // Never an upscale: fitWithin returns the source size when the long edge is already inside.
        assertSame(src, StageOps.downscale(src, PreprocessParams(workingLongEdge = 2048), notes))
        assertTrue(notes.isEmpty(), "a no-op must not produce a note: $notes")
    }

    @Test
    fun grayscaleInvertsForWhiteOnBlackSources() {
        val white = RgbaImage(2, 2).fill(0xFFFFFFFF.toInt())
        val plain = StageOps.grayscale(white, PreprocessParams(invertInput = false))
        assertEquals(1f, plain[0, 0], 1e-3f)
        // Every stage after this one treats dark as ink, so a white-on-black source must be flipped
        // here or the whole cleanup chain keeps the background and removes the artwork.
        val flipped = StageOps.grayscale(white, PreprocessParams(invertInput = true))
        assertEquals(0f, flipped[0, 0], 1e-3f)
    }

    // -------------------------------------------------------------------------------------------
    // matte · binarise
    // -------------------------------------------------------------------------------------------

    @Test
    fun theMatteIsNeverAppliedUnlessItWasChosen() {
        val notes = ArrayList<String>()
        val gray = GrayF(4, 4).fill(0.5f)
        val working = RgbaImage(4, 4).fill(0xFF404040.toInt())
        // A wrong matte deleting half of somebody's artwork is the worst failure this app can have,
        // so MatteMode.NONE is an exact identity and not a cheap approximation of one.
        assertSame(gray, StageOps.matte(working, gray, MatteParams(mode = MatteMode.NONE), notes))
        assertTrue(notes.isEmpty())
    }

    /** A red block on an even blue-grey backdrop: one subject, one background, nothing else. */
    private fun subjectOnBackdrop(size: Int = 96, blob: Int = 32): RgbaImage {
        val img = RgbaImage(size, size).fill(0xFF6E82A5.toInt())
        val lo = (size - blob) / 2
        for (y in lo until lo + blob) for (x in lo until lo + blob) img[x, y] = 0xFFC82828.toInt()
        return img
    }

    @Test
    fun theSubjectMatteRemovesTheBackgroundAndReportsHowMuchItTook() {
        val notes = ArrayList<String>()
        val working = subjectOnBackdrop()
        val gray = StageOps.grayscale(working, PreprocessParams())
        assertTrue(gray[2, 2] < 0.8f, "the backdrop must start out darker than paper: ${gray[2, 2]}")

        val out = StageOps.matte(working, gray, MatteParams(mode = MatteMode.SUBJECT, feather = 0f), notes)

        // Composited over 1.0, not 0: everything downstream reads dark as ink, so a background
        // knocked out to black would become the largest ink region in the image.
        assertTrue(out[2, 2] > 0.95f, "the backdrop survived the matte: ${out[2, 2]}")
        assertTrue(out[48, 48] < 0.6f, "the subject was matted away: ${out[48, 48]}")
        assertTrue(
            notes.any { it.contains("subject background matte removed") },
            "a matte that ran without saying so is indistinguishable from one that did not: $notes",
        )
    }

    @Test
    fun anUnconfidentSubjectMatteIsNotAppliedAndSaysWhyInsteadOfDeletingTheArtwork() {
        // A flat frame has nothing to separate, so the fused matte's confidence is 0. The stage must
        // fall through to no matte at all — the failure a user can see and fix is "the background is
        // still there", never "most of my drawing is gone".
        val notes = ArrayList<String>()
        val working = RgbaImage(64, 64).fill(0xFF8C8C8C.toInt())
        val gray = StageOps.grayscale(working, PreprocessParams())

        val out = StageOps.matte(working, gray, MatteParams(mode = MatteMode.SUBJECT), notes)

        assertSame(gray, out, "an unconfident matte must be an exact identity, not an approximate one")
        val note = notes.single()
        assertTrue(note.contains("not sure enough"), "the refusal must be stated: $note")
        assertTrue(note.trim().endsWith("."), "notes are sentences: $note")
    }

    @Test
    fun binarisePassesThroughAMaskTheEngineAlreadyDecided() {
        // Canny, LoG and the adaptive threshold end in a decision of their own. Re-thresholding a
        // decision can only lose information, so the outcome's own mask is returned untouched.
        val mask = Mask(4, 4)
        mask[1, 1] = true
        val outcome = EdgeOutcome(mask.toGray(), mask, EdgeEngine.CANNY)
        assertSame(mask, StageOps.binarise(outcome, EdgeParams()))
    }

    @Test
    fun binariseSeparatesInkFromPaperOnAContinuousResponse() {
        // A response with a bar of full ink strength on empty paper. Otsu lands between the two
        // levels whatever it computes exactly, so the bar is ink and the paper is not.
        val response = GrayF(20, 20)
        for (y in 8..11) for (x in 2..17) response[x, y] = 1f
        val mask = StageOps.binarise(EdgeOutcome(response, null, EdgeEngine.XDOG), EdgeParams())
        assertTrue(mask[10, 9], "the bar did not survive binarisation")
        assertFalse(mask[0, 0], "empty paper became ink")
    }

    @Test
    fun raisingSensitivityCanOnlyAddInk() {
        // The Otsu scale factor is 1.6x closed down, 1.0x at the midpoint and 0.4x wide open, and
        // hysteresis is monotone in its thresholds — so the ink sets are nested. A slider that is
        // monotone in the direction its label promises is the whole reason `sensitivity` exists.
        val ramp = GrayF(64, 8)
        for (y in 0 until 8) for (x in 0 until 64) ramp[x, y] = x / 63f
        fun ink(sensitivity: Float): Int =
            StageOps.binarise(
                EdgeOutcome(ramp, null, EdgeEngine.XDOG),
                EdgeParams(sensitivity = sensitivity),
            ).countTrue()

        val closed = ink(0f)
        val middle = ink(0.5f)
        val open = ink(1f)
        assertTrue(closed <= middle, "$closed ink at sensitivity 0 but $middle at 0.5")
        assertTrue(middle <= open, "$middle ink at sensitivity 0.5 but $open at 1")
        assertTrue(closed < open, "sensitivity changed nothing: $closed vs $open")
    }

    // -------------------------------------------------------------------------------------------
    // blob filter — the counting is the point
    // -------------------------------------------------------------------------------------------

    /** A 3x3 speck (area 9) and a 6x6 region (area 36), far enough apart to be 8-disconnected. */
    private fun twoBlobs(): Mask {
        val m = Mask(20, 20)
        for (y in 2..4) for (x in 2..4) m[x, y] = true
        for (y in 10..15) for (x in 10..15) m[x, y] = true
        return m
    }

    @Test
    fun theBlobFilterNamesHowManyRegionsItRemovedAndWhy() {
        val notes = ArrayList<String>()
        val out = StageOps.blobFilter(
            twoBlobs(),
            // removeIsolated off so this test measures one cap at a time.
            CleanupParams(minBlobArea = 20, removeIsolated = false, keepLargest = 0),
            notes,
            CancellationToken(),
        )
        assertEquals(36, out.countTrue(), "the 6x6 region should be all that is left")
        assertEquals(1, notes.size)
        // A pipeline that discards regions and one that found nothing look identical on screen.
        assertTrue(notes[0].contains("Removed 1 of 2 regions"), notes[0])
        assertTrue(notes[0].contains("smaller than 20 pixels"), notes[0])
    }

    @Test
    fun keepLargestReportsWhatItDiscardedAndZeroMeansDisabled() {
        val kept = ArrayList<String>()
        val out = StageOps.blobFilter(
            twoBlobs(),
            CleanupParams(minBlobArea = 0, removeIsolated = false, keepLargest = 1),
            kept,
            CancellationToken(),
        )
        assertEquals(36, out.countTrue())
        assertEquals(1, kept.size)
        assertTrue(kept[0].contains("Kept only the 1 largest region"), kept[0])
        assertTrue(kept[0].contains("discarded 1"), kept[0])

        // 0 means "no cap", not "keep none" — the difference between a preset and an empty canvas.
        val disabled = ArrayList<String>()
        val all = StageOps.blobFilter(
            twoBlobs(),
            CleanupParams(minBlobArea = 0, removeIsolated = false, keepLargest = 0),
            disabled,
            CancellationToken(),
        )
        assertEquals(45, all.countTrue(), "keepLargest = 0 removed something")
        assertTrue(disabled.isEmpty())
    }

    @Test
    fun frameTouchingRegionsAreCountedWhenTheyAreDropped() {
        val m = Mask(20, 20)
        // A region wedged into the top-left corner, which is what a scan border looks like.
        for (y in 0..2) for (x in 0..2) m[x, y] = true
        for (y in 10..15) for (x in 10..15) m[x, y] = true
        val notes = ArrayList<String>()
        val out = StageOps.blobFilter(
            m,
            CleanupParams(minBlobArea = 0, removeIsolated = false, removeBorderTouching = true),
            notes,
            CancellationToken(),
        )
        assertEquals(36, out.countTrue())
        assertEquals(1, notes.size)
        assertTrue(notes[0].contains("Discarded 1 region touching the frame edge"), notes[0])
    }

    // -------------------------------------------------------------------------------------------
    // bridge · thin · prune · distance
    // -------------------------------------------------------------------------------------------

    @Test
    fun bridgingJoinsFacingStrokeEndsAndSaysHowManyGapsItClosed() {
        // Two collinear strokes with a 4 px gap: closing cannot span it without also fusing adjacent
        // strokes, which is why gap closing is two stages and not one (ALGORITHMS §9).
        val m = Mask(30, 9)
        for (x in 2..11) m[x, 4] = true
        for (x in 16..27) m[x, 4] = true
        val before = m.countTrue()

        val notes = ArrayList<String>()
        val bridged = StageOps.bridge(
            m,
            CleanupParams(bridgeGaps = true, maxGap = 12, maxBridgeAngle = 60f),
            notes,
            CancellationToken(),
        )
        assertTrue(bridged.countTrue() > before, "nothing was added, so nothing was bridged")
        for (x in 12..15) assertTrue(bridged[x, 4], "the gap is still open at x=$x")
        assertEquals(1, notes.size)
        assertTrue(notes[0].contains("Bridged 1 gap"), notes[0])
        assertTrue(notes[0].contains("12 px"), notes[0])
    }

    @Test
    fun bridgingIsExactlyIdentityWhenItIsSwitchedOffOrHasNoReach() {
        val m = Mask(30, 9)
        for (x in 2..11) m[x, 4] = true
        for (x in 16..27) m[x, 4] = true
        val notes = ArrayList<String>()
        assertSame(m, StageOps.bridge(m, CleanupParams(bridgeGaps = false), notes, CancellationToken()))
        assertSame(m, StageOps.bridge(m, CleanupParams(maxGap = 0), notes, CancellationToken()))
        // A gap wider than the reach leaves the mask alone rather than returning a rebuilt copy.
        assertSame(
            m,
            StageOps.bridge(m, CleanupParams(maxGap = 2), notes, CancellationToken()),
        )
        assertTrue(notes.isEmpty(), "a no-op produced $notes")
    }

    @Test
    fun thinningReducesInkToCentrelinesAndPruningRemovesTheHairItGrows() {
        val bar = Mask(30, 11)
        for (y in 4..6) for (x in 2..27) bar[x, y] = true
        for (mode in listOf(ThinningMode.ZHANG_SUEN, ThinningMode.GUO_HALL)) {
            val thinned = StageOps.thin(bar, CleanupParams(thinning = mode))
            // A 26 x 3 bar has 78 ink pixels; its centreline is about 26.
            assertTrue(
                thinned.countTrue() < bar.countTrue() / 2,
                "$mode left ${thinned.countTrue()} of ${bar.countTrue()} pixels",
            )
            assertTrue(thinned.countTrue() > 0, "$mode deleted the bar entirely")
        }

        val skeleton = Mask(24, 11)
        for (x in 2..21) skeleton[x, 5] = true
        skeleton[12, 6] = true
        skeleton[12, 7] = true
        val pruned = StageOps.prune(skeleton, CleanupParams(pruneSpurs = 4))
        // Unpruned hair becomes dozens of three-point paths in the SVG, each a real object the user
        // then has to select and delete.
        assertFalse(pruned[12, 7], "the two pixel hair survived")
        assertFalse(pruned[12, 6], "the two pixel hair survived")
        assertTrue(pruned[12, 5], "the trunk was pruned")

        // 0 disables pruning outright rather than pruning by zero.
        assertSame(skeleton, StageOps.prune(skeleton, CleanupParams(pruneSpurs = 0)))
    }

    @Test
    fun theDistanceTransformMeasuresHalfWidthOnTheFullWidthMask() {
        // A bar 5 px tall: the centre row is 3 px from the nearest background row, which is the
        // half-width plus the half pixel the centre itself occupies.
        val bar = Mask(21, 11)
        for (y in 3..7) for (x in 2..18) bar[x, y] = true
        val dt = StageOps.distance(bar)
        assertEquals(3f, dt[10, 5], 1e-3f)
        assertEquals(1f, dt[10, 3], 1e-3f)
        assertEquals(0f, dt[10, 0], 1e-3f)
    }

    // -------------------------------------------------------------------------------------------
    // vectorise — the drop count is a UI contract
    // -------------------------------------------------------------------------------------------

    @Test
    fun vectoriseCountsAndNamesThePathsItDropped() {
        // Three isolated strokes 16, 5 and 1 units long, six rows apart so none is 8-connected.
        val m = Mask(21, 21)
        for (x in 1..17) m[x, 3] = true
        for (x in 1..6) m[x, 9] = true
        for (x in 1..2) m[x, 15] = true

        val notes = ArrayList<String>()
        val kept = StageOps.vectorise(
            m,
            OutputParams(minPathLength = 10f),
            null,
            notes,
            CancellationToken(),
        )
        assertEquals(1, kept.size, "only the 16 unit stroke is longer than 10")
        assertEquals(1, notes.size)
        // Four thousand discarded paths and an empty result are the same picture without this.
        assertTrue(notes[0].contains("Dropped 2 of 3"), notes[0])
        assertTrue(notes[0].contains("shorter than 10 px"), notes[0])
        assertTrue(notes[0].contains("keeping 1"), notes[0])
    }

    @Test
    fun vectoriseSaysNothingWhenItDroppedNothing() {
        val m = Mask(21, 9)
        for (x in 1..17) m[x, 4] = true
        val notes = ArrayList<String>()
        val kept = StageOps.vectorise(m, OutputParams(minPathLength = 0f), null, notes, CancellationToken())
        assertEquals(1, kept.size)
        assertTrue(notes.isEmpty(), "a note was invented for a cap that did not apply: $notes")
    }

    @Test
    fun vectoriseReturnsNothingRatherThanThrowingOnAnEmptyMask() {
        val notes = ArrayList<String>()
        val kept = StageOps.vectorise(Mask(16, 16), OutputParams(), null, notes, CancellationToken())
        assertTrue(kept.isEmpty())
        // No paths were traced, so no paths were dropped: the "dropped" note would be a lie here.
        assertTrue(notes.isEmpty(), notes.toString())
    }

    // -------------------------------------------------------------------------------------------
    // style
    // -------------------------------------------------------------------------------------------

    private fun openShape(): VecShape = VecShape(
        VecPath(
            start = VecPoint(0f, 0f),
            segments = listOf(VecSeg.Line(VecPoint(5f, 0f)), VecSeg.Line(VecPoint(10f, 0f))),
            closed = false,
            id = "stroke",
        ),
        VecStyle(),
    )

    @Test
    fun styleAppliesTheUsersColourWithoutTouchingTheGeometry() {
        val shape = openShape()
        val out = StageOps.style(
            listOf(shape),
            OutputParams(strokeColor = 0xFF00FF00.toInt(), strokeWidth = 3.5f),
        )
        assertEquals(1, out.size)
        // Vectorize returns everything in plain black because it is the one stage that does not know
        // the palette; restyling in exactly one place is what stops two stroke colours drifting apart.
        assertEquals(0xFF00FF00.toInt(), out[0].style.stroke)
        assertEquals(3.5f, out[0].style.strokeWidth)
        assertNull(out[0].style.fill, "an open path must not be filled")
        assertEquals(shape.path, out[0].path, "the style stage moved a point")
    }

    @Test
    fun fillClosedOnlyFillsPathsThatAreActuallyClosed() {
        val ring = VecShape(
            VecPath(
                start = VecPoint(0f, 0f),
                segments = listOf(
                    VecSeg.Line(VecPoint(4f, 0f)),
                    VecSeg.Line(VecPoint(4f, 4f)),
                    VecSeg.Line(VecPoint(0f, 4f)),
                ),
                closed = true,
            ),
            VecStyle(),
        )
        val o = OutputParams(fillClosed = true, strokeColor = 0xFF123456.toInt())
        val out = StageOps.style(listOf(ring, openShape()), o)
        assertEquals(0xFF123456.toInt(), out[0].style.fill)
        assertNull(out[1].style.fill, "an open path was filled, which paints a shape that is not there")
    }

    @Test
    fun aWidthModulatedStrokeBecomesAFilledOutlineBecauseSvgHasNoVariableStroke() {
        val path = VecPath(
            start = VecPoint(0f, 0f),
            segments = listOf(VecSeg.Line(VecPoint(10f, 0f)), VecSeg.Line(VecPoint(20f, 0f))),
            closed = false,
            id = "brush",
            strokeWidths = floatArrayOf(4f, 4f, 4f),
        )
        val out = StageOps.style(
            listOf(VecShape(path, VecStyle())),
            OutputParams(modulateWidth = true, strokeColor = 0xFF804020.toInt()),
        )
        assertEquals(1, out.size)
        assertNull(out[0].style.stroke, "the ribbon is filled, not stroked")
        assertEquals(0xFF804020.toInt(), out[0].style.fill)
        // NONZERO, not EVENODD: a ribbon that crosses itself at a hairpin would render with a hole
        // through the overlap under EVENODD.
        assertEquals(FillRule.NONZERO, out[0].style.fillRule)
        assertEquals("brush", out[0].path.id, "the outline lost the path's identity")
        val b = out[0].path.bounds()
        // Width 4 around a horizontal centreline is a ribbon 4 units tall.
        assertTrue(b[3] - b[1] > 3f, "the ribbon is only ${b[3] - b[1]} tall")
    }

    @Test
    fun modulationFallsBackToAUniformStrokeRatherThanLosingTheShape() {
        // One anchor and one width: there is no ribbon to build. A shape that silently disappears is
        // worse than one drawn at the wrong weight.
        val degenerate = VecPath(
            start = VecPoint(3f, 3f),
            segments = emptyList(),
            closed = false,
            id = "dot",
            strokeWidths = floatArrayOf(2f),
        )
        val out = StageOps.style(
            listOf(VecShape(degenerate, VecStyle())),
            OutputParams(modulateWidth = true, strokeColor = 0xFF000000.toInt(), strokeWidth = 1.1f),
        )
        assertEquals(1, out.size, "the shape was dropped instead of being stroked uniformly")
        assertEquals(0xFF000000.toInt(), out[0].style.stroke)
        assertEquals(1.1f, out[0].style.strokeWidth)
    }

    @Test
    fun styleOfNothingIsNothing() {
        assertTrue(StageOps.style(emptyList(), OutputParams()).isEmpty())
    }

    // -------------------------------------------------------------------------------------------
    // assemble — the resolution-independence step
    // -------------------------------------------------------------------------------------------

    @Test
    fun assembleScalesWorkingCoordinatesIntoTheFrame() {
        val shape = VecShape(
            VecPath(
                start = VecPoint(0f, 0f),
                segments = listOf(VecSeg.Line(VecPoint(10f, 20f))),
                closed = false,
                id = "p",
            ),
            VecStyle(strokeWidth = 2f),
        )
        // 100 x 100 working into a 300 x 200 frame: 3x horizontally, 2x vertically.
        val doc = StageOps.assemble(listOf(shape), 100, 100, 300, 200, OutputParams())
        assertEquals(300f, doc.width)
        assertEquals(200f, doc.height)

        val b = doc.bounds()
        assertEquals(0f, b[0], 1e-3f)
        assertEquals(0f, b[1], 1e-3f)
        assertEquals(30f, b[2], 1e-3f)
        assertEquals(40f, b[3], 1e-3f)

        // Stroke widths carry through with the geometric mean of the two scales, which is what keeps
        // a hairline a hairline instead of several times too thin on a downscaled 12 MP source.
        assertEquals(
            2f * sqrt(6f),
            doc.layers[0].shapes[0].style.strokeWidth,
            1e-3f,
        )
    }

    @Test
    fun assembleRestatesTheCanvasExactlyRatherThanLeavingItAProduct() {
        // 6001 / 3000 is not representable, and a document that comes out 6000.9995 px wide crops a
        // column off every raster export and rounds down in every page box.
        val doc = StageOps.assemble(emptyList(), 3000, 2000, 6001, 4001, OutputParams())
        assertEquals(6001f, doc.width)
        assertEquals(4001f, doc.height)
    }

    @Test
    fun assembleDoesNotTransformWhenTheWorkingSizeIsAlreadyTheFrame() {
        val shape = VecShape(
            VecPath(VecPoint(1f, 2f), listOf(VecSeg.Line(VecPoint(3f, 4f)))),
            VecStyle(strokeWidth = 1.6f),
        )
        val doc = StageOps.assemble(listOf(shape), 512, 384, 512, 384, OutputParams())
        assertEquals(512f, doc.width)
        assertEquals(384f, doc.height)
        // Identity, not "a scale by 1.0": a float round trip through a transform is not free.
        assertEquals(shape.path, doc.layers[0].shapes[0].path)
        assertEquals(1.6f, doc.layers[0].shapes[0].style.strokeWidth)
    }

    @Test
    fun assembleAlwaysProducesTheOneNamedTraceLayerAndCarriesTheBackground() {
        val doc = StageOps.assemble(
            emptyList(),
            64, 64, 64, 64,
            OutputParams(background = 0xFF10315C.toInt()),
        )
        assertEquals(1, doc.layers.size)
        assertEquals("trace", doc.layers[0].id)
        assertEquals("Trace", doc.layers[0].name)
        assertEquals(0, doc.shapeCount())
        assertEquals(0xFF10315C.toInt(), doc.background)

        // A transparent page is null and not black, or every export would gain a solid background.
        assertNull(StageOps.assemble(emptyList(), 64, 64, 64, 64, OutputParams()).background)
    }

    @Test
    fun assembleSurvivesAOnePixelFrame() {
        val doc = StageOps.assemble(emptyList(), 1, 1, 1, 1, OutputParams())
        assertEquals(1f, doc.width)
        assertEquals(1f, doc.height)
        assertEquals(0, doc.shapeCount())
    }
}
