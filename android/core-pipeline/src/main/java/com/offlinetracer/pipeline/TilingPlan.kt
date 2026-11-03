package com.offlinetracer.pipeline

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import com.offlinetracer.imaging.Parallel
import com.offlinetracer.imaging.Tiling
import kotlin.math.max

internal class TilePlan(
    val tiled: Boolean,
    val width: Int,
    val height: Int,
    val tileSize: Int,
    val tilesX: Int,
    val tilesY: Int,
) {
    val tileCount: Int get() = tilesX * tilesY

    val tileWidth: Int get() = width / tilesX
    val tileHeight: Int get() = height / tilesY

    fun tiles(halo: Int): List<Tiling.Tile> =
        if (!tiled) emptyList() else Tiling.plan(width, height, tileSize, halo)

    fun matches(w: Int, h: Int): Boolean = width == w && height == h
}

internal object TilingPlan {

    const val TILE_THRESHOLD = 4096

    const val TILE_SIZE = 1024

    const val HALO_MARGIN = 32

    fun decide(width: Int, height: Int, threshold: Int = TILE_THRESHOLD, tileSize: Int = TILE_SIZE): TilePlan {
        val w = max(1, width)
        val h = max(1, height)
        val size = if (tileSize < 1) TILE_SIZE else tileSize
        val limit = max(1, threshold)
        if (max(w, h) <= limit) return TilePlan(false, w, h, size, 1, 1)

        val nx = ((w + size - 1) / size).coerceAtLeast(1)
        val ny = ((h + size - 1) / size).coerceAtLeast(1)
        if (nx * ny <= 1) return TilePlan(false, w, h, size, 1, 1)
        return TilePlan(true, w, h, size, nx, ny)
    }

    fun halo(support: Int): Int = max(0, support) + HALO_MARGIN

    fun runGray(stage: Stage, plan: TilePlan, support: Int, src: GrayF, op: (GrayF) -> GrayF): GrayF {
        requireTileLocal(stage)
        if (!plan.tiled) return op(src)
        requireMatchingFrame(stage, plan, src.width, src.height, "runGray")
        val tiles = plan.tiles(halo(support))
        if (tiles.size <= 1) return op(src)

        val out = src.copy()
        Parallel.chunks(tiles.size, 1) { from, to ->
            for (i in from until to) grayTile(src, tiles[i], op, out)
        }
        return out
    }

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
