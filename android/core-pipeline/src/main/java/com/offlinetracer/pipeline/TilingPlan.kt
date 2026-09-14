package com.offlinetracer.pipeline

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import com.offlinetracer.imaging.Parallel
import com.offlinetracer.imaging.Tiling
import kotlin.math.max

/**
 * The tiling decision for one trace, and the guarded runners that carry it out (ALGORITHMS §13).
 *
 * `:core-imaging`'s [Tiling.plan] owns the partition — how a frame is divided into even tiles. This
 * file owns everything else: whether to tile at all, how wide the halo has to be for a given stage,
 * which stages are allowed inside the tile loop, how a tile's read window is assembled, and the
 * sentence the user is shown when tiling ran.
 *
 * It deliberately does **not** use [Tiling.process], which is the obvious thing to do and is wrong for
 * both of the operations this pipeline actually tiles — see [windowFor] for exactly why. That failure
 * has the shape every mistake in this area has: an image that looks plausible and is wrong in a narrow
 * band, surviving every unit test of every individual filter because every individual filter is
 * correct.
 *
 * ### The halo rule, and why it is not a tuning knob
 *
 * A tile is computed from its interior **plus** a margin of context, and only the interior is kept. If
 * that margin is at least the composite support of the operation — the distance a single output pixel
 * can reach back into the input — then the tiled result is not an approximation of the untiled one, it
 * is **bit-identical to it**: every output pixel saw exactly the neighbourhood it would have seen, in
 * exactly the same order, and float addition is deterministic. Below that support the result is wrong
 * at every tile edge and right everywhere else, which is the definition of a seam.
 *
 * So the halo is `support + `[HALO_MARGIN]. The margin is not a safety fudge for an unknown support;
 * every caller states its support exactly. It is there because the supports are derived from
 * parameters (a σ becomes `ceil(2σ)`, an iteration count becomes a radius), and a rounding rule that
 * changes in `:core-imaging` should cost a few wasted pixels of context rather than correctness.
 */
internal class TilePlan(
    /** Whether the tile-local stages should run per tile. `false` means "run whole-frame". */
    val tiled: Boolean,
    /** The working frame this plan was decided for. Every tile-local stage must be handed this size. */
    val width: Int,
    val height: Int,
    /** The requested interior size; the realised tiles are the even split closest to it. */
    val tileSize: Int,
    val tilesX: Int,
    val tilesY: Int,
) {
    val tileCount: Int get() = tilesX * tilesY

    /** Interior width of the narrowest tile. Tiles differ by at most one pixel — see [Tiling.plan]. */
    val tileWidth: Int get() = width / tilesX
    val tileHeight: Int get() = height / tilesY

    /**
     * The tiles this plan describes, each carrying [halo] pixels of context.
     *
     * Delegates to [Tiling.plan] rather than restating the even split, so the boundaries a test
     * inspects are the boundaries the runners actually used. Empty when the plan is untiled, because
     * "no tiles" and "one tile covering everything" are different instructions and only one of them
     * skips the halo copy.
     */
    fun tiles(halo: Int): List<Tiling.Tile> =
        if (!tiled) emptyList() else Tiling.plan(width, height, tileSize, halo)

    fun matches(w: Int, h: Int): Boolean = width == w && height == h
}

internal object TilingPlan {

    /** Long edge above which tiling is armed (ALGORITHMS §13). */
    const val TILE_THRESHOLD = 4096

    /** Requested tile interior, in working pixels. */
    const val TILE_SIZE = 1024

    /** Added to every stage's stated support to get its halo. See the class comment. */
    const val HALO_MARGIN = 32

    /**
     * Decides whether a [width]×[height] working frame should be tiled.
     *
     * A pure function of the working size, which is what makes it safe for each tile-local stage to
     * ask independently: every one of them runs at the working resolution, so they all get the same
     * answer, and there is no way for one stage to tile while the next does not. That failure mode is
     * worth designing out rather than commenting on — it produces a seam that is present in the mask
     * and absent from the greyscale it came from, which reads as a bug in the wrong stage entirely.
     *
     * A frame that comes out as a single tile is reported as **untiled**. One tile is the whole frame
     * plus a halo copy and an interior write-back, i.e. the same answer for strictly more work.
     */
    fun decide(width: Int, height: Int, threshold: Int = TILE_THRESHOLD, tileSize: Int = TILE_SIZE): TilePlan {
        val w = max(1, width)
        val h = max(1, height)
        val size = if (tileSize < 1) TILE_SIZE else tileSize
        val limit = max(1, threshold)
        if (max(w, h) <= limit) return TilePlan(false, w, h, size, 1, 1)

        // The same ceiling division Tiling.plan performs, so tilesX/tilesY here and the tile list it
        // returns can never disagree about how many tiles there are.
        val nx = ((w + size - 1) / size).coerceAtLeast(1)
        val ny = ((h + size - 1) / size).coerceAtLeast(1)
        if (nx * ny <= 1) return TilePlan(false, w, h, size, 1, 1)
        return TilePlan(true, w, h, size, nx, ny)
    }

    /** The halo for an operation whose output reaches [support] pixels back into its input. */
    fun halo(support: Int): Int = max(0, support) + HALO_MARGIN

    /**
     * Runs [op] over [src] in tiles, or whole-frame when [plan] says not to tile.
     *
     * @param stage the stage this operation belongs to. It is **required** to be tile-local, which is
     *   the point of taking it at all: [Stage.isTileLocal] is a correctness annotation, and a
     *   parameter that has to be produced to get into the tile loop turns it from a comment into
     *   something the compiler and the test suite can hold somebody to. CLAHE, Otsu, connected
     *   components, thinning and contour tracing seam when tiled, and the way that ships is somebody
     *   adding a call here because the stage next to it was already tiled.
     * @param support how far one output pixel reaches back into the input, in pixels.
     */
    fun runGray(stage: Stage, plan: TilePlan, support: Int, src: GrayF, op: (GrayF) -> GrayF): GrayF {
        requireTileLocal(stage)
        if (!plan.tiled) return op(src)
        requireMatchingFrame(stage, plan, src.width, src.height, "runGray")
        val tiles = plan.tiles(halo(support))
        if (tiles.size <= 1) return op(src)

        val out = src.copy()
        // One tile per task: a tile is already a substantial unit of work and the operations that get
        // here allocate their own buffers, so batching them would only lengthen the tail.
        Parallel.chunks(tiles.size, 1) { from, to ->
            for (i in from until to) grayTile(src, tiles[i], op, out)
        }
        return out
    }

    /** [runGray] for a binary mask. Same geometry, different buffer type. */
    fun runMask(stage: Stage, plan: TilePlan, support: Int, src: Mask, op: (Mask) -> Mask): Mask {
        requireTileLocal(stage)
        if (!plan.tiled) return op(src)
        requireMatchingFrame(stage, plan, src.width, src.height, "runMask")
        val tiles = plan.tiles(halo(support))
        if (tiles.size <= 1) return op(src)

        val out = src.copy()
        Parallel.chunks(tiles.size, 1) { from, to ->
            for (i in from until to) maskTile(src, tiles[i], op, out)
        }
        return out
    }

    private fun requireTileLocal(stage: Stage) {
        require(stage.isTileLocal) {
            "${stage.id} (\"${stage.label}\") is not tile-local and must not be run per tile: it needs " +
                "a global view — a whole histogram, a whole tile lattice or whole-frame connectivity — " +
                "and per-tile it leaves a visible seam at every tile boundary (ALGORITHMS §13). Compute " +
                "it across the frame instead."
        }
    }

    private fun requireMatchingFrame(stage: Stage, plan: TilePlan, w: Int, h: Int, fn: String) {
        require(plan.matches(w, h)) {
            "$fn(${stage.id}): the plan was decided for ${plan.width}x${plan.height} but the image is " +
                "${w}x$h; a plan from a different resolution would tile at the wrong boundaries"
        }
    }

    /**
     * The window of [src] one tile is computed from: the interior grown by the halo and then **clipped
     * to the frame**.
     *
     * Clipped, not extended, and this is the subtle half of the whole file. `:core-imaging`'s
     * [Tiling.process] builds a fixed `(w + 2·halo) × (h + 2·halo)` patch and fills the part that falls
     * outside the frame by replicating the edge. That is exactly right for a single-pass filter with an
     * edge-clamped border — the replicated column is what the untiled filter's own clamping would have
     * read — and it is wrong for the two kinds of operation this pipeline actually tiles:
     *
     *  - **An iterated filter.** Perona–Malik diffusion reflects at the border on *every* pass. Given a
     *    replicated margin, that margin is a snapshot of pass 0 which then diffuses on its own, so by
     *    pass 10 it is not what reflecting off the frame would have produced. The tiled and untiled
     *    results then differ in a band the width of the iteration count around the whole image.
     *  - **Mask morphology.** A [Mask] reads out of bounds as *background*, so erosion eats the frame
     *    and closing is deliberately not extensive at the border. A margin padded with `false` is not
     *    the same thing: it is in-bounds background, which the dilate half of a close will happily fill
     *    in, and the following erode then finds support where the untiled version found nothing. A
     *    margin padded by replication is worse again.
     *
     * Clipping sidesteps both. Where a tile touches the frame the patch border *is* the frame border, so
     * the operation's own policy — whatever it is — applies in exactly the place it would have applied
     * untiled. Where a tile does not touch the frame, the patch border is [halo] pixels from the
     * interior and the operation's support cannot reach it. Either way the interior is bit-identical to
     * the untiled result, with no assumption at all about how the operation treats its border.
     *
     * The cost is that tiles are no longer uniform in size — an edge tile allocates less — which is
     * cheaper, not more expensive.
     */
    private class Window(val x0: Int, val y0: Int, val w: Int, val h: Int)

    private fun windowFor(tile: Tiling.Tile, frameW: Int, frameH: Int): Window {
        val margin = if (tile.halo < 0) 0 else tile.halo
        val x0 = maxOf(0, tile.x - margin)
        val y0 = maxOf(0, tile.y - margin)
        val x1 = minOf(frameW, tile.x + tile.w + margin)
        val y1 = minOf(frameH, tile.y + tile.h + margin)
        return Window(x0, y0, x1 - x0, y1 - y0)
    }

    private fun grayTile(src: GrayF, tile: Tiling.Tile, op: (GrayF) -> GrayF, dst: GrayF) {
        val writeW = minOf(tile.w, src.width - tile.x)
        val writeH = minOf(tile.h, src.height - tile.y)
        if (tile.x < 0 || tile.y < 0 || writeW <= 0 || writeH <= 0) return
        val win = windowFor(tile, src.width, src.height)
        if (win.w <= 0 || win.h <= 0) return

        val patch = GrayF(win.w, win.h)
        for (y in 0 until win.h) {
            java.lang.System.arraycopy(
                src.data, (win.y0 + y) * src.width + win.x0, patch.data, y * win.w, win.w,
            )
        }

        val result = op(patch)
        requireSizePreserving(result.width, result.height, win)

        // Where the interior sits inside the patch. Zero on a side the clip removed the halo from.
        val ix = tile.x - win.x0
        val iy = tile.y - win.y0
        for (y in 0 until writeH) {
            java.lang.System.arraycopy(
                result.data, (iy + y) * win.w + ix, dst.data, (tile.y + y) * src.width + tile.x, writeW,
            )
        }
    }

    private fun maskTile(src: Mask, tile: Tiling.Tile, op: (Mask) -> Mask, dst: Mask) {
        val writeW = minOf(tile.w, src.width - tile.x)
        val writeH = minOf(tile.h, src.height - tile.y)
        if (tile.x < 0 || tile.y < 0 || writeW <= 0 || writeH <= 0) return
        val win = windowFor(tile, src.width, src.height)
        if (win.w <= 0 || win.h <= 0) return

        val patch = Mask(win.w, win.h)
        for (y in 0 until win.h) {
            java.lang.System.arraycopy(
                src.data, (win.y0 + y) * src.width + win.x0, patch.data, y * win.w, win.w,
            )
        }

        val result = op(patch)
        requireSizePreserving(result.width, result.height, win)

        val ix = tile.x - win.x0
        val iy = tile.y - win.y0
        for (y in 0 until writeH) {
            java.lang.System.arraycopy(
                result.data, (iy + y) * win.w + ix, dst.data, (tile.y + y) * src.width + tile.x, writeW,
            )
        }
    }

    private fun requireSizePreserving(w: Int, h: Int, win: Window) {
        require(w == win.w && h == win.h) {
            "a tile operation returned ${w}x$h for a ${win.w}x${win.h} tile; it must be size-preserving"
        }
    }

    /**
     * The sentence shown when tiling was armed.
     *
     * Every cap and fallback in this pipeline is visible on screen, and tiling is a cap: it changes how
     * the work was carried out, and if a seam ever does appear the user needs to already know that the
     * image was tiled and at what size rather than discovering it from a support thread.
     *
     * Both halves of the sentence are needed. Without the tile size the note cannot be checked against
     * a suspected artefact; without the list of stages that ran **whole-frame** the note reads as
     * "tiling was used, therefore anything odd is a tiling artefact", and the reader stops looking at
     * the stage that actually did it.
     *
     * The wording is a statement about the *plan*, not about which stages had work to do. That is
     * deliberate: this is emitted from the first tile-local stage, which cannot see the cleanup
     * parameters, so a claim like "noise removal ran in 5 tiles" would be false whenever denoising is
     * switched off. Everything asserted here is true for any parameter set.
     */
    fun note(plan: TilePlan, threshold: Int = TILE_THRESHOLD): String =
        "The working image is ${plan.width}x${plan.height} px, past the $threshold px tiling " +
            "threshold, so this trace ran tiled: ${plan.tileCount} tiles of about " +
            "${plan.tileWidth}x${plan.tileHeight} px, each computed with a margin of surrounding " +
            "context and only its interior written back. Noise removal and the morphological " +
            "close/open are the only stages tiling is applied to. Local contrast, thresholding, hole " +
            "filling, speck filtering, gap bridging, thinning and path tracing each need the whole " +
            "frame and ran across it, because computing any of them one tile at a time leaves a seam " +
            "at every tile edge that no later stage can remove."
}
