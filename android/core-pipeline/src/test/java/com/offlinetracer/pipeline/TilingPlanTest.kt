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

class TilingPlanTest {

    private val wideW = 4200
    private val wideH = 48

    @Test
    fun tilingIsArmedOnlyPastTheThreshold() {
        assertEquals(4096, TilingPlan.TILE_THRESHOLD, "the threshold is ALGORITHMS §13's, not a tuning knob")
        assertEquals(1024, TilingPlan.TILE_SIZE)
        assertEquals(32, TilingPlan.HALO_MARGIN)

        assertFalse(TilingPlan.decide(2048, 1536).tiled, "a 2048 px frame must not be tiled")
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
        val plan = TilingPlan.decide(5000, 10, threshold = 4096, tileSize = 8192)
        assertFalse(plan.tiled)
        assertEquals(1, plan.tileCount)
        assertTrue(plan.tiles(32).isEmpty(), "an untiled plan has no tiles, rather than one big one")
    }

    @Test
    fun theDecisionIsAPureFunctionOfTheWorkingSize() {
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

        val tiles = TilingPlan.decide(wideW, wideH).tiles(36)
        assertEquals(5, tiles.size)
        assertTrue(tiles.all { it.halo == 36 })
        assertEquals(wideW, tiles.sumOf { it.w }, "the interiors must exactly cover the frame")
    }

    @Test
    fun aStageThatIsNotTileLocalIsRefusedRatherThanTiled() {
        val plan = TilingPlan.decide(wideW, wideH)
        val gray = texturedRamp(wideW, wideH)
        val mask = barMask(wideW, wideH, 20, 27)

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

    @Test
    fun everyNoiseFilterTiledIsBitIdenticalToTheSameFilterWholeFrame() {
        val gray = texturedRamp(wideW, wideH)
        val notes = ArrayList<String>()
        val token = CancellationToken()

        for (mode in DenoiseMode.entries) {
            val p = PreprocessParams(denoise = mode, denoiseStrength = 0.7f, medianRadius = 3)
            val whole = StageOps.denoise(gray, p, tiled = false, notes = notes, cancel = token)
            val tiled = StageOps.denoise(gray, p, tiled = true, notes = notes, cancel = token)
            assertIdentical(whole, tiled, "denoise($mode)")
        }
    }

    @Test
    fun theMorphologyStageTiledIsBitIdenticalToTheUntiledComposition() {
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
        val boundaries = tileBoundaries(TilingPlan.decide(wideW, wideH))
        assertTrue(boundaries.isNotEmpty())
        val seam = boundaries[0]

        val mask = Mask(wideW, wideH).fill(true)
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

        val alsoTiled = StageOps.morphology(
            mask,
            CleanupParams(closeRadius = 1, openRadius = 0, fillHolesUpTo = 400),
            CancellationToken(),
        )
        assertFalse(alsoTiled[seam, 24], "the hole was filled once the close/open pass tiled")
    }

    @Test
    fun aTiledMaskOperationMatchesTheUntiledOneAtTheFrameBorderToo() {
        val mask = Mask(wideW, wideH)
        for (y in 0 until wideH) for (x in 0 until wideW) {
            mask[x, y] = (x % 11 < 6) || y < 2 || y >= wideH - 2
        }
        val plan = TilingPlan.decide(wideW, wideH)
        val op: (Mask) -> Mask = { Morphology.close(it, 3, SeShape.ELLIPSE) }
        val tiled = TilingPlan.runMask(Stages.required(Stages.MORPHOLOGY), plan, 6, mask, op)
        assertIdentical(op(mask), tiled, "close(3) at the frame border")
    }

    @Test
    fun noColumnAtATileBoundaryHasAnAnomalousGradient() {
        val plan = TilingPlan.decide(wideW, wideH)
        val boundaries = tileBoundaries(plan)
        assertEquals(4, boundaries.size, "five tiles have four interior boundaries")

        val src = ramp(wideW, wideH)
        val op: (GrayF) -> GrayF = { Denoise.bilateral(it, 3f, 0.26f) }
        val proper = TilingPlan.runGray(Stages.required(Stages.DENOISE), plan, 6, src, op)
        val properRatio = seamRatio(proper, boundaries)
        assertTrue(
            properRatio < 1.2,
            "a tile boundary column is ${properRatio}x the typical column step — that is a seam",
        )

        val seamed = Tiling.process(src, Tiling.plan(wideW, wideH, TilingPlan.TILE_SIZE, 0), op)
        val seamedRatio = seamRatio(seamed, boundaries)
        assertTrue(
            seamedRatio > 2.5,
            "removing the halo must produce a seam this metric catches, but it scored $seamedRatio",
        )
    }

    @Test
    fun noRowAtATileBoundaryHasAnAnomalousGradientEither() {
        val n = 320
        val plan = TilingPlan.decide(n, n, threshold = 128, tileSize = 96)
        assertTrue(plan.tiled)
        assertEquals(4, plan.tilesX)
        assertEquals(4, plan.tilesY)

        val src = transpose(ramp(n, n))
        val op: (GrayF) -> GrayF = { Denoise.bilateral(it, 3f, 0.26f) }
        val out = TilingPlan.runGray(Stages.required(Stages.DENOISE), plan, 6, src, op)
        val rowBoundaries = plan.tiles(0).filter { it.x == 0 && it.y > 0 }.map { it.y }.toIntArray()
        assertEquals(3, rowBoundaries.size)
        val ratio = seamRatio(transpose(out), rowBoundaries)
        assertTrue(ratio < 1.2, "a tile row boundary is ${ratio}x the typical row step")
    }

    @Test
    fun aStrokeCrossingEveryTileBoundaryStaysOneConnectedRegion() {
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
        assertTrue(labels.bounds[0 + 4] <= 4, "the bar lost its left end: x0=${labels.bounds[4]}")
        assertTrue(
            labels.bounds[2 + 4] >= wideW - 5,
            "the bar lost its right end: x1=${labels.bounds[6]} of ${wideW - 1}",
        )
    }

    @Test
    fun theNoteNamesTheTileCountTheTileSizeAndWhatWasNotTiled() {
        val note = TilingPlan.note(TilingPlan.decide(wideW, wideH))
        assertTrue(note.contains("4200x48 px"), note)
        assertTrue(note.contains("4096 px tiling threshold"), note)
        assertTrue(note.contains("5 tiles of about 840x48 px"), note)
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

    private fun holedBarMask(w: Int, h: Int): Mask {
        val m = barMask(w, h, 14, 34)
        for (x in 0 until w step 7) {
            if (x + 2 >= w) break
            for (y in 20 until 23) for (dx in 0 until 3) m[x + dx, y] = false
        }
        return m
    }

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

    private fun tileBoundaries(plan: TilePlan): IntArray =
        plan.tiles(0).filter { it.y == 0 && it.x > 0 }.map { it.x }.toIntArray()

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
