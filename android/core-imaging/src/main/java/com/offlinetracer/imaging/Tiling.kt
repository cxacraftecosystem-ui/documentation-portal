package com.offlinetracer.imaging

object Tiling {

    data class Tile(val x: Int, val y: Int, val w: Int, val h: Int, val halo: Int)

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
