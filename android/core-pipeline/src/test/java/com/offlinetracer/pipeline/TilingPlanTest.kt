package com.offlinetracer.pipeline

import com.offlinetracer.imaging.Components
import com.offlinetracer.imaging.Denoise
import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import com.offlinetracer.imaging.Morphology
import com.offlinetracer.imaging.Px
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.imaging.SeShape
import com.offlinetracer.imaging.Tiling
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tiled processing (ALGORITHMS §13).
 *
 * The claim this file has to establish is narrow and strong: **on an oversized image the tiled result
 * is not an approximation of the untiled one, it is bit-identical to it.** That is achievable because
 * every tile is computed from its interior plus a halo of at least the operation's composite support,
 * so every output pixel sees exactly the neighbourhood it would have seen, in the same order, and
 * float arithmetic is deterministic. Asserting "within a tolerance" instead would be the weaker
 * statement *and* the more dangerous one: a halo one pixel too small produces a result that is wrong
 * only in a 1 px column at each tile edge, which passes any tolerance averaged over the frame and is
 * plainly visible on screen.
 *
 * The comparisons are made one level below [Pipeline.run], at [StageOps] and [TilingPlan], for a
 * mechanical reason: `Pipeline` derives `tiled` from the working size, so there is no way to ask it for
 * an untiled trace of an above-threshold frame to compare against. Testing the stage lets both arms of
 * the comparison run on the *same* pixels at the *same* resolution, which is the comparison that means
 * something — a pair of traces at two working resolutions would differ for reasons that have nothing
 * to do with tiling.
 */
class TilingPlanTest {

    // A long thin frame is the cheap way over the 4096 px threshold: 4200x48 is 202 000 pixels, about
    // a fifth of a phone screen, and still splits into five tiles under the real 1024 px tile size. A
    // square 4200² frame would be 17.6 M pixels per buffer and would make this file the slowest test in
    // the project for no extra coverage.
    private val wideW = 4200
    private val wideH = 48

    // -------------------------------------------------------------------------------------------
    // The decision
    // -------------------------------------------------------------------------------------------

    @Test
    fun tilingIsArmedOnlyPastTheThreshold() {
        assertEquals(4096, TilingPlan.TILE_THRESHOLD, "the threshold is ALGORITHMS §13's, not a tuning knob")
        assertEquals(1024, TilingPlan.TILE_SIZE)
        assertEquals(32, TilingPlan.HALO_MARGIN)

        assertFalse(TilingPlan.decide(2048, 1536).tiled, "a 2048 px frame must not be tiled")
        // Exactly at the threshold is not past it.
        assertFalse(TilingPlan.decide(4096, 4096).tiled, "4096 is the threshold, not the first tiled size")
        assertTrue(TilingPlan.decide(4097, 16).tiled, "one pixel past the threshold arms tiling")

        val plan = TilingPlan.decide(wideW, wideH)
        assertTrue(plan.tiled)
        assertEquals(5, plan.tilesX, "ceil(4200/1024)")
        assertEquals(1, plan.tilesY, "48 px of height is one tile")
        assertEquals(5, plan.tileCount)
        assertEquals(840, plan.tileWidth)
        assertEquals(48, plan.tileHeight)
    }

    @Test
    fun aFrameThatComesOutAsOneTileIsReportedUntiled() {
        // Past the threshold but under one tile: the halo copy and the interior write-back would be
        // pure overhead for the identical answer, so the plan says "don't".
        val plan = TilingPlan.decide(5000, 10, threshold = 4096, tileSize = 8192)
        assertFalse(plan.tiled)
        assertEquals(1, plan.tileCount)
        assertTrue(plan.tiles(32).isEmpty(), "an untiled plan has no tiles, rather than one big one")
    }

    @Test
    fun theDecisionIsAPureFunctionOfTheWorkingSize() {
        // This is what licenses each tile-local stage to ask independently instead of being handed a
        // plan: two calls with the same working size cannot disagree, so one stage cannot tile while
        // the next runs whole-frame — the failure that puts a seam in the mask and not in the greyscale
        // it came from.
        val a = TilingPlan.decide(wideW, wideH)
        val b = TilingPlan.decide(wideW, wideH)
        assertEquals(a.tiled, b.tiled)
        assertEquals(a.tilesX, b.tilesX)
        assertEquals(a.tilesY, b.tilesY)
        assertEquals(a.tiles(40).map { it.x }, b.tiles(40).map { it.x })
    }

    @Test
    fun degenerateSizesDoNotThrow() {
        for (size in listOf(0 to 0, 1 to 1, -4 to 9, 1 to 9000)) {
            val plan = TilingPlan.decide(size.first, size.second)
            assertTrue(plan.width >= 1 && plan.height >= 1, "clamped to a legal frame")
            assertTrue(plan.tilesX >= 1 && plan.tilesY >= 1)
        }
    }

    @Test
    fun theHaloIsTheStatedSupportPlusTheMargin() {
        assertEquals(32, TilingPlan.halo(0))
        assertEquals(36, TilingPlan.halo(4))
        assertEquals(32, TilingPlan.halo(-9), "a negative support is a bug upstream, not a negative halo")

        // The halo is carried on every tile, not just the interior ones: a tile at the frame edge still
        // needs context on its inward sides, and giving it a different halo would make it the one tile
        // computed differently from the rest.
        val tiles = TilingPlan.decide(wideW, wideH).tiles(36)
        assertEquals(5, tiles.size)
        assertTrue(tiles.all { it.halo == 36 })
        assertEquals(wideW, tiles.sumOf { it.w }, "the interiors must exactly cover the frame")
    }

    // -------------------------------------------------------------------------------------------
    // The guard: a global stage must not reach the tile loop
    // -------------------------------------------------------------------------------------------

    @Test
    fun aStageThatIsNotTileLocalIsRefusedRatherThanTiled() {
        val plan = TilingPlan.decide(wideW, wideH)
        val gray = texturedRamp(wideW, wideH)
        val mask = barMask(wideW, wideH, 20, 27)

        // Otsu, CLAHE, connected components, thinning and contour tracing all seam when tiled. The
        // annotation on the stage list says so; this is what makes it enforceable rather than advisory,
        // because the way a global stage gets tiled is somebody adding a call next to one that already
        // was.
        for (id in listOf(Stages.CONTRAST, Stages.BINARISE, Stages.BLOBS, Stages.SKELETON, Stages.VECTORISE)) {
            val stage = Stages.required(id)
            val fromGray = assertFailsWith<IllegalArgumentException>("$id was allowed into runGray") {
                TilingPlan.runGray(stage, plan, 4, gray) { it }
            }
            assertTrue(
                fromGray.message!!.contains("not tile-local"),
                "the refusal must say why: ${fromGray.message}",
            )
            assertFailsWith<IllegalArgumentException>("$id was allowed into runMask") {
                TilingPlan.runMask(stage, plan, 4, mask) { it }
            }
        }
    }

    @Test
    fun aPlanFromADifferentResolutionIsRefused() {
        // A plan carries the boundaries; applying one decided for another frame would tile at the wrong
        // columns, which looks exactly like a halo bug and is not one.
        val plan = TilingPlan.decide(wideW, wideH)
        val wrongSize = texturedRamp(wideW / 2, wideH)
        val failure = assertFailsWith<IllegalArgumentException> {
            TilingPlan.runGray(Stages.required(Stages.DENOISE), plan, 4, wrongSize) { it }
        }
        assertTrue(failure.message!!.contains("decided for"), failure.message!!)
    }

    @Test
    fun anUntiledPlanRunsTheOperationOnceOnTheWholeFrame() {
        val plan = TilingPlan.decide(256, 256)
        assertFalse(plan.tiled)
        var calls = 0
        val src = texturedRamp(256, 256)
        val out = TilingPlan.runGray(Stages.required(Stages.DENOISE), plan, 4, src) { calls++; it }
        assertEquals(1, calls)
        assertTrue(out === src, "an untiled plan must not copy the frame for nothing")
    }

    // -------------------------------------------------------------------------------------------
    // Tiled == untiled, exactly
    // -------------------------------------------------------------------------------------------

    @Test
    fun everyNoiseFilterTiledIsBitIdenticalToTheSameFilterWholeFrame() {
        val gray = texturedRamp(wideW, wideH)
        val notes = ArrayList<String>()
        val token = CancellationToken()

        // All four modes, because the four derive their supports differently and only one of them is a
        // plain radius: bilateral's comes from ceil(2σ), the median's is the radius itself, and
        // anisotropic diffusion's is its *iteration count* — one pixel of propagation per pass. A halo
        // sized from a nominal "kernel radius" would be far too small for the last of those, and the
        // artefact is a faint cross at every tile corner rather than an obvious edge.
        for (mode in DenoiseMode.entries) {
            val p = PreprocessParams(denoise = mode, denoiseStrength = 0.7f, medianRadius = 3)
            val whole = StageOps.denoise(gray, p, tiled = false, notes = notes, cancel = token)
            val tiled = StageOps.denoise(gray, p, tiled = true, notes = notes, cancel = token)
            assertIdentical(whole, tiled, "denoise($mode)")
        }
    }

    @Test
    fun theMorphologyStageTiledIsBitIdenticalToTheUntiledComposition() {
        // Compared against the primitives directly rather than against a second call, because
        // StageOps.morphology derives its own plan from the mask's size and so cannot be asked for an
        // untiled run of an above-threshold mask. Spelling the reference out also pins the composition:
        // close, then open, then fill holes, in that order.
        val mask = holedBarMask(wideW, wideH)
        val p = CleanupParams(closeRadius = 2, openRadius = 1, fillHolesUpTo = 40)

        val reference = Components.fillHoles(
            Morphology.open(Morphology.close(mask, p.closeRadius, SeShape.ELLIPSE), p.openRadius, SeShape.ELLIPSE),
            p.fillHolesUpTo,
        )
        val tiled = StageOps.morphology(mask, p, CancellationToken())
        assertIdentical(reference, tiled, "morphology(close=2, open=1, fillHoles=40)")
    }

    @Test
    fun theHoleFillStaysGlobalWhenTheRestOfTheStageIsTiled() {
        // The one connected-component call inside an otherwise tile-local stage. A hole straddling a
        // tile boundary is two smaller holes per tile, each of which passes an area test the whole hole
        // fails — so tiling it would fill a hole at every boundary and nowhere else. The hole here is
        // wide enough that no single tile contains it and its area is over the threshold, so a tiled
        // hole fill is the only way for it to get filled.
        val boundaries = tileBoundaries(TilingPlan.decide(wideW, wideH))
        assertTrue(boundaries.isNotEmpty())
        val seam = boundaries[0]

        val mask = Mask(wideW, wideH).fill(true)
        // A 1200 px hole centred on the first tile boundary: 1200 x 8 = 9600 px, far over the 400 px
        // threshold, and wider than the 840 px tile so it lands in two tiles at once.
        for (y in 20 until 28) for (x in (seam - 600) until (seam + 600)) mask[x, y] = false

        val filled = StageOps.morphology(
            mask,
            CleanupParams(closeRadius = 0, openRadius = 0, fillHolesUpTo = 400),
            CancellationToken(),
        )
        assertFalse(
            filled[seam, 24],
            "a 9600 px hole was filled, so the hole fill saw only part of it — the stage tiled a " +
                "connected-component operation",
        )

        // And with close/open switched on — the path that does tile — the hole must still survive.
        val alsoTiled = StageOps.morphology(
            mask,
            CleanupParams(closeRadius = 1, openRadius = 0, fillHolesUpTo = 400),
            CancellationToken(),
        )
        assertFalse(alsoTiled[seam, 24], "the hole was filled once the close/open pass tiled")
    }

    @Test
    fun aTiledMaskOperationMatchesTheUntiledOneAtTheFrameBorderToo() {
        // The border is where the two plausible halo policies diverge. `Tiling.process` edge-replicates
        // because that is a GrayF filter's border policy; a Mask reads out of bounds as *background*
        // (Mask.safe), so erosion eats the frame and closing is not extensive at the border. Padding
        // the halo by replication instead of with `false` would make the tiled close preserve a 1 px
        // rim the untiled one trims — a seam around the outside of the image rather than through it.
        val mask = Mask(wideW, wideH)
        for (y in 0 until wideH) for (x in 0 until wideW) {
            // Ink right up to all four edges, so the border policy is actually exercised.
            mask[x, y] = (x % 11 < 6) || y < 2 || y >= wideH - 2
        }
        val plan = TilingPlan.decide(wideW, wideH)
        val op: (Mask) -> Mask = { Morphology.close(it, 3, SeShape.ELLIPSE) }
        val tiled = TilingPlan.runMask(Stages.required(Stages.MORPHOLOGY), plan, 6, mask, op)
        assertIdentical(op(mask), tiled, "close(3) at the frame border")
    }

    // -------------------------------------------------------------------------------------------
    // No seam
    // -------------------------------------------------------------------------------------------

    @Test
    fun noColumnAtATileBoundaryHasAnAnomalousGradient() {
        val plan = TilingPlan.decide(wideW, wideH)
        val boundaries = tileBoundaries(plan)
        assertEquals(4, boundaries.size, "five tiles have four interior boundaries")

        val src = ramp(wideW, wideH)
        // A strong blur, so a missing halo would show. σs = 3 gives a support of 6 px.
        val op: (GrayF) -> GrayF = { Denoise.bilateral(it, 3f, 0.26f) }
        val proper = TilingPlan.runGray(Stages.required(Stages.DENOISE), plan, 6, src, op)
        val properRatio = seamRatio(proper, boundaries)
        assertTrue(
            properRatio < 1.2,
            "a tile boundary column is ${properRatio}x the typical column step — that is a seam",
        )

        // The other half of the test, and the reason the first half means anything: the same metric on
        // the same image with the halo removed must *fail*. A seam detector that reports clean on a
        // deliberately seamed image reports clean forever. Measured: 1.00 with the halo, 3.21 without.
        //
        // The metric has a floor — it degrades smoothly with halo width (1.14 at a halo of 4 against a
        // support of 6) so it would not catch a halo that is only a pixel or two short. That case is
        // covered by the bit-exactness tests above, which fail on a single wrong pixel. The two are
        // complementary and neither is redundant: exactness proves the halo is sufficient, this proves
        // the *shape* of the failure when it is not is the one being looked for.
        val seamed = Tiling.process(src, Tiling.plan(wideW, wideH, TilingPlan.TILE_SIZE, 0), op)
        val seamedRatio = seamRatio(seamed, boundaries)
        assertTrue(
            seamedRatio > 2.5,
            "removing the halo must produce a seam this metric catches, but it scored $seamedRatio",
        )
    }

    @Test
    fun noRowAtATileBoundaryHasAnAnomalousGradientEither() {
        // Rows need their own check and the wide frame is one tile tall, so the threshold and tile size
        // are injected here to get a 4x4 grid out of a 320 px frame. That is the same code path with
        // the same halo rule — only the numbers that decide *whether* to tile are different, and those
        // are pinned by tilingIsArmedOnlyPastTheThreshold above.
        val n = 320
        val plan = TilingPlan.decide(n, n, threshold = 128, tileSize = 96)
        assertTrue(plan.tiled)
        assertEquals(4, plan.tilesX)
        assertEquals(4, plan.tilesY)

        val src = transpose(ramp(n, n))
        val op: (GrayF) -> GrayF = { Denoise.bilateral(it, 3f, 0.26f) }
        val out = TilingPlan.runGray(Stages.required(Stages.DENOISE), plan, 6, src, op)
        // Transposing turns the row question into the column question, so one metric answers both.
        val rowBoundaries = plan.tiles(0).filter { it.x == 0 && it.y > 0 }.map { it.y }.toIntArray()
        assertEquals(3, rowBoundaries.size)
        val ratio = seamRatio(transpose(out), rowBoundaries)
        assertTrue(ratio < 1.2, "a tile row boundary is ${ratio}x the typical row step")
    }

    @Test
    fun aStrokeCrossingEveryTileBoundaryStaysOneConnectedRegion() {
        // The seam metric measures intensity; this measures the thing the user actually loses. A bar
        // spanning the frame is one 8-connected component, and it has to still be one after a tiled
        // morphological pass — a seam at each of the four boundaries would make it five.
        val mask = barMask(wideW, wideH, 20, 27)
        assertEquals(1, Components.label(mask, 8).count)

        val out = StageOps.morphology(
            mask,
            CleanupParams(closeRadius = 2, openRadius = 2, fillHolesUpTo = 0),
            CancellationToken(),
        )
        val labels = Components.label(out, 8)
        assertEquals(
            1,
            labels.count,
            "the bar was cut at a tile boundary and came back as more than one region",
        )
        // And the one region still spans the frame: a bar broken into five and then re-merged by chance
        // would satisfy the count on its own. The few pixels of slack at each end are the erosion eating
        // the frame edge, which is Mask's documented out-of-bounds policy and happens untiled too.
        assertTrue(labels.bounds[0 + 4] <= 4, "the bar lost its left end: x0=${labels.bounds[4]}")
        assertTrue(
            labels.bounds[2 + 4] >= wideW - 5,
            "the bar lost its right end: x1=${labels.bounds[6]} of ${wideW - 1}",
        )
    }

    // -------------------------------------------------------------------------------------------
    // What the user is told
    // -------------------------------------------------------------------------------------------

    @Test
    fun theNoteNamesTheTileCountTheTileSizeAndWhatWasNotTiled() {
        val note = TilingPlan.note(TilingPlan.decide(wideW, wideH))
        assertTrue(note.contains("4200x48 px"), note)
        assertTrue(note.contains("4096 px tiling threshold"), note)
        assertTrue(note.contains("5 tiles of about 840x48 px"), note)
        // The second half matters as much as the first: without it the note reads as "tiling was used,
        // so anything odd is a tiling artefact" and the reader stops looking at the stage that did it.
        assertTrue(note.contains("Local contrast, thresholding"), note)
        assertTrue(note.contains("need the whole frame"), note)
    }

    @Test
    fun anAboveThresholdTraceSaysItTiledAndABelowThresholdOneSaysNothing() {
        val big = Pipeline.run(barImage(wideW, wideH), tiledParams(), classify = false)
        assertEquals(4200, big.workingWidth, "the frame must not have been downscaled under the threshold")
        val note = big.notes.firstOrNull { it.contains("tiling threshold") }
        assertTrue(note != null, "an oversized trace must say it tiled: ${big.notes}")
        assertTrue(note!!.contains("5 tiles of about 840x48 px"), note)

        val small = Pipeline.run(barImage(512, 48), tiledParams(), classify = false)
        assertTrue(
            small.notes.none { it.contains("tiling") },
            "a 512 px trace must not claim to have tiled: ${small.notes}",
        )
    }

    @Test
    fun theTilingNoteAppearsEvenWithEveryNoiseFilterSwitchedOff() {
        // The note used to be emitted from inside the noise filter's own tiling branch, so switching
        // denoising off hid it — on a trace where the morphological pass was still tiling. That is the
        // one configuration where somebody chasing a seam most needs to be told.
        val params = tiledParams().let {
            it.copy(
                preprocess = it.preprocess.copy(denoise = DenoiseMode.NONE),
                cleanup = it.cleanup.copy(closeRadius = 2),
            )
        }
        val result = Pipeline.run(barImage(wideW, wideH), params, classify = false)
        assertTrue(
            result.notes.any { it.contains("this trace ran tiled") },
            "the note went missing with denoising off: ${result.notes}",
        )
    }

    // -------------------------------------------------------------------------------------------
    // Fixtures and metrics
    // -------------------------------------------------------------------------------------------

    /** Working long edge above the tile threshold, and a cheap engine — the engine is not what is
     *  being tested and FDoG's edge tangent flow is the most expensive stage in the project. */
    private fun tiledParams(): TraceParams = TraceParams(
        preprocess = PreprocessParams(workingLongEdge = 8192, claheEnabled = false),
        edge = EdgeParams(engine = EdgeEngine.ADAPTIVE, useSauvola = true),
    )

    private fun ramp(w: Int, h: Int): GrayF {
        val g = GrayF(w, h)
        val span = (w - 1).toFloat()
        for (y in 0 until h) for (x in 0 until w) g[x, y] = x.toFloat() / span
        return g
    }

    /**
     * A ramp with deterministic texture on top, so the noise filters have something to remove.
     *
     * Arithmetic rather than a seeded RNG: there is no RNG anywhere in this engine (§14 determinism),
     * and a test that introduced one would be the only place where the same input could produce two
     * answers.
     */
    private fun texturedRamp(w: Int, h: Int): GrayF {
        val g = GrayF(w, h)
        val span = (w - 1).toFloat()
        for (y in 0 until h) for (x in 0 until w) {
            val grain = ((x * 37 + y * 91) % 17).toFloat() / 17f
            g[x, y] = Px.clamp01(0.8f * (x.toFloat() / span) + 0.2f * grain)
        }
        return g
    }

    private fun barMask(w: Int, h: Int, y0: Int, y1: Int): Mask {
        val m = Mask(w, h)
        for (y in y0 until y1) for (x in 0 until w) m[x, y] = true
        return m
    }

    /** A bar with periodic pinholes, so the hole fill has work at every tile and between them. */
    private fun holedBarMask(w: Int, h: Int): Mask {
        val m = barMask(w, h, 14, 34)
        for (x in 0 until w step 7) {
            if (x + 2 >= w) break
            for (y in 20 until 23) for (dx in 0 until 3) m[x + dx, y] = false
        }
        return m
    }

    /** White paper with one black bar, as an [RgbaImage] for the pipeline-level tests. */
    private fun barImage(w: Int, h: Int): RgbaImage {
        val img = RgbaImage(w, h).fill(0xFFFFFFFF.toInt())
        val y0 = h / 2 - 2
        for (y in y0 until y0 + 4) for (x in 0 until w) img[x, y] = 0xFF101010.toInt()
        return img
    }

    private fun transpose(src: GrayF): GrayF {
        val out = GrayF(src.height, src.width)
        for (y in 0 until src.height) for (x in 0 until src.width) out[y, x] = src[x, y]
        return out
    }

    /** x of the first column of every tile but the leftmost — where a seam would land. */
    private fun tileBoundaries(plan: TilePlan): IntArray =
        plan.tiles(0).filter { it.y == 0 && it.x > 0 }.map { it.x }.toIntArray()

    /**
     * How much more the image steps across a tile boundary than it does across a typical column.
     *
     * The baseline is the **median** over the non-boundary columns, not the mean: a seam is a handful of
     * large values among thousands of small ones, and it would drag a mean up far enough to hide itself.
     */
    private fun seamRatio(img: GrayF, boundaries: IntArray): Double {
        val step = DoubleArray(img.width)
        for (x in 1 until img.width) {
            var sum = 0.0
            for (y in 0 until img.height) sum += abs(img[x, y] - img[x - 1, y]).toDouble()
            step[x] = sum / img.height
        }
        val skip = boundaries.toHashSet()
        val baseline = (1 until img.width).filter { it !in skip }.map { step[it] }.sorted()
        val median = baseline[baseline.size / 2]
        assertTrue(median > 0.0, "the fixture has no gradient, so this metric cannot say anything")
        var worst = 0.0
        for (b in boundaries) {
            val ratio = step[b] / median
            if (ratio > worst) worst = ratio
        }
        return worst
    }

    private fun assertIdentical(expected: GrayF, actual: GrayF, what: String) {
        assertEquals(expected.width, actual.width, "$what: width")
        assertEquals(expected.height, actual.height, "$what: height")
        var worst = 0f
        var wx = -1
        var wy = -1
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val d = abs(expected[x, y] - actual[x, y])
            if (d > worst) {
                worst = d
                wx = x
                wy = y
            }
        }
        assertTrue(worst == 0f, "$what: tiled and untiled differ by $worst at ($wx, $wy)")
    }

    private fun assertIdentical(expected: Mask, actual: Mask, what: String) {
        assertEquals(expected.width, actual.width, "$what: width")
        assertEquals(expected.height, actual.height, "$what: height")
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            if (expected[x, y] != actual[x, y]) {
                val nearest = nearestBoundary(x, expected.width)
                throw AssertionError(
                    "$what: tiled and untiled differ at ($x, $y) — ${expected[x, y]} vs " +
                        "${actual[x, y]}; the nearest tile boundary is $nearest px away"
                )
            }
        }
    }

    /** Distance from [x] to the nearest tile boundary, so a failure message names the likely cause. */
    private fun nearestBoundary(x: Int, width: Int): Int {
        val boundaries = tileBoundaries(TilingPlan.decide(width, 1))
        var best = Int.MAX_VALUE
        for (b in boundaries) {
            val d = abs(x - b)
            if (d < best) best = d
        }
        return if (best == Int.MAX_VALUE) -1 else best
    }
}
