package com.offlinetracer.imaging

/**
 * Tiled processing (ALGORITHMS §13) for images too large to hold several full-resolution
 * intermediates at once.
 *
 * Only tile-local stages belong here. CLAHE, Otsu, connected components, thinning, contour tracing
 * and saliency are all global: run per tile they produce a visible seam at every tile boundary, and
 * the fix is to compute them on a downscaled proxy and broadcast the result (LUT, threshold,
 * labels) to the tiles. That is why the pipeline's stage list is annotated with `isTileLocal`.
 */
object Tiling {

    /**
     * One unit of work. [x], [y], [w], [h] describe the **interior** — the region written back —
     * and [halo] is the extra margin of context read around it on every side.
     */
    data class Tile(val x: Int, val y: Int, val w: Int, val h: Int, val halo: Int)

    /**
     * Partitions a [width]×[height] image into tiles of roughly [tileSize] pixels a side, each
     * carrying a [halo] of context. Returns an empty list for a degenerate size.
     *
     * Tiles are sized evenly rather than "full tiles plus whatever is left": a remainder tile 3 px
     * wide still costs a full halo to compute, and its interior is narrower than the filter support
     * that produced it, which is exactly the condition that shows up as a seam down the right-hand
     * edge of the image.
     */
    fun plan(width: Int, height: Int, tileSize: Int, halo: Int): List<Tile> {
        if (width <= 0 || height <= 0) return emptyList()
        val margin = if (halo < 0) 0 else halo
        val size = if (tileSize < 1) maxOf(width, height) else tileSize
        val nx = ((width + size - 1) / size).coerceAtLeast(1)
        val ny = ((height + size - 1) / size).coerceAtLeast(1)

        val tiles = ArrayList<Tile>(nx * ny)
        var y0 = 0
        for (ty in 0 until ny) {
            val th = height / ny + if (ty < height % ny) 1 else 0
            var x0 = 0
            for (tx in 0 until nx) {
                val tw = width / nx + if (tx < width % nx) 1 else 0
                tiles.add(Tile(x0, y0, tw, th, margin))
                x0 += tw
            }
            y0 += th
        }
        return tiles
    }

    /**
     * Runs [op] over each tile of [src] and assembles the interiors into one image the same size as
     * the source. An empty tile list returns a copy of the source unchanged.
     *
     * [op] is handed the tile **plus its halo**, edge-clamped against the image border to match the
     * border policy of every analytic filter — reading zero outside would draw a dark frame around
     * every tile. It must return an image of exactly the size it was given, and it is invoked
     * concurrently on several threads, so it must be pure: no shared mutable state, no reuse of a
     * scratch buffer captured from the caller.
     */
    fun process(src: GrayF, tiles: List<Tile>, op: (GrayF) -> GrayF): GrayF {
        val out = src.copy()
        if (tiles.isEmpty()) return out
        Parallel.chunks(tiles.size, 1) { from, to ->
            for (i in from until to) processTile(src, tiles[i], op, out)
        }
        return out
    }

    private fun processTile(src: GrayF, tile: Tile, op: (GrayF) -> GrayF, dst: GrayF) {
        val w = src.width
        val h = src.height
        val tw = tile.w
        val th = tile.h
        if (tw <= 0 || th <= 0) return
        val halo = if (tile.halo < 0) 0 else tile.halo
        val ew = tw + 2 * halo
        val eh = th + 2 * halo

        val patch = GrayF(ew, eh)
        val pd = patch.data
        val sd = src.data
        for (y in 0 until eh) {
            val sy = Px.clamp(tile.y - halo + y, 0, h - 1)
            val srcRow = sy * w
            val dstRow = y * ew
            for (x in 0 until ew) {
                val sx = Px.clamp(tile.x - halo + x, 0, w - 1)
                pd[dstRow + x] = sd[srcRow + sx]
            }
        }

        val result = op(patch)
        require(result.width == ew && result.height == eh) {
            "Tiling.process(): op returned ${result.width}x${result.height} for a ${ew}x$eh tile; " +
                "a tile operation must be size-preserving"
        }

        // Bounds are hoisted out of the copy-back loop: a tile is only ever partly outside the
        // image when the caller hand-built the list, but testing per pixel would cost a branch on
        // every one of them.
        val xStart = maxOf(0, -tile.x)
        val xEnd = minOf(tw, w - tile.x)
        val yStart = maxOf(0, -tile.y)
        val yEnd = minOf(th, h - tile.y)
        if (xStart >= xEnd || yStart >= yEnd) return
        val rd = result.data
        val od = dst.data
        for (y in yStart until yEnd) {
            val srcRow = (y + halo) * ew + halo
            val dstRow = (tile.y + y) * w + tile.x
            for (x in xStart until xEnd) od[dstRow + x] = rd[srcRow + x]
        }
    }
}
