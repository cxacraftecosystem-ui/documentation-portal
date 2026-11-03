package com.offlinetracer.imaging

enum class SeShape { RECT, CROSS, ELLIPSE }

object Morphology {

    fun dilate(src: Mask, radius: Int, shape: SeShape = SeShape.ELLIPSE): Mask {
        if (radius <= 0) return src.copy()
        return when (shape) {
            SeShape.RECT -> rect(src, radius, true)
            SeShape.CROSS -> cross(src, radius, true)
            SeShape.ELLIPSE -> ellipse(src, radius, true)
        }
    }

    fun erode(src: Mask, radius: Int, shape: SeShape = SeShape.ELLIPSE): Mask {
        if (radius <= 0) return src.copy()
        return when (shape) {
            SeShape.RECT -> rect(src, radius, false)
            SeShape.CROSS -> cross(src, radius, false)
            SeShape.ELLIPSE -> ellipse(src, radius, false)
        }
    }

    fun open(src: Mask, radius: Int, shape: SeShape = SeShape.ELLIPSE): Mask =
        if (radius <= 0) src.copy() else dilate(erode(src, radius, shape), radius, shape)

    fun close(src: Mask, radius: Int, shape: SeShape = SeShape.ELLIPSE): Mask =
        if (radius <= 0) src.copy() else erode(dilate(src, radius, shape), radius, shape)

    fun gradient(src: Mask, radius: Int, shape: SeShape = SeShape.ELLIPSE): Mask {
        if (radius <= 0) return Mask.like(src)
        return dilate(src, radius, shape).subtract(erode(src, radius, shape))
    }

    fun dilateGray(src: GrayF, radius: Int): GrayF = grayRect(src, radius, true)

    fun erodeGray(src: GrayF, radius: Int): GrayF = grayRect(src, radius, false)

    private fun rect(src: Mask, radius: Int, dilateOp: Boolean): Mask {
        val w = src.width
        val h = src.height
        val d = src.data
        val win = 2 * radius + 1
        val tmp = BooleanArray(d.size)

        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                var count = 0
                var i = 0
                while (i <= radius) {
                    if (i < w && d[row + i]) count++
                    i++
                }
                tmp[row] = if (dilateOp) count > 0 else count == win
                for (x in 1 until w) {
                    val rem = x - radius - 1
                    if (rem >= 0 && d[row + rem]) count--
                    val add = x + radius
                    if (add < w && d[row + add]) count++
                    tmp[row + x] = if (dilateOp) count > 0 else count == win
                }
            }
        }

        val out = BooleanArray(d.size)
        Parallel.chunks(w, Parallel.ROWS_KERNEL) { fromX, toX ->
            for (x in fromX until toX) {
                var count = 0
                var i = 0
                while (i <= radius) {
                    if (i < h && tmp[i * w + x]) count++
                    i++
                }
                out[x] = if (dilateOp) count > 0 else count == win
                for (y in 1 until h) {
                    val rem = y - radius - 1
                    if (rem >= 0 && tmp[rem * w + x]) count--
                    val add = y + radius
                    if (add < h && tmp[add * w + x]) count++
                    out[y * w + x] = if (dilateOp) count > 0 else count == win
                }
            }
        }
        return Mask(w, h, out)
    }

    private fun cross(src: Mask, radius: Int, dilateOp: Boolean): Mask {
        val w = src.width
        val h = src.height
        val d = src.data
        val out = BooleanArray(d.size)
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                for (x in 0 until w) {
                    var hit = d[row + x]
                    if (dilateOp) {
                        if (!hit) {
                            var k = 1
                            while (k <= radius) {
                                if ((x - k >= 0 && d[row + x - k]) || (x + k < w && d[row + x + k]) ||
                                    (y - k >= 0 && d[row - k * w + x]) ||
                                    (y + k < h && d[row + k * w + x])
                                ) {
                                    hit = true
                                    break
                                }
                                k++
                            }
                        }
                        out[row + x] = hit
                    } else {
                        if (hit) {
                            var k = 1
                            while (k <= radius) {
                                if (!(x - k >= 0 && d[row + x - k]) || !(x + k < w && d[row + x + k]) ||
                                    !(y - k >= 0 && d[row - k * w + x]) ||
                                    !(y + k < h && d[row + k * w + x])
                                ) {
                                    hit = false
                                    break
                                }
                                k++
                            }
                        }
                        out[row + x] = hit
                    }
                }
            }
        }
        return Mask(w, h, out)
    }

    private fun ellipse(src: Mask, radius: Int, dilateOp: Boolean): Mask {
        val w = src.width
        val h = src.height
        val d = src.data
        val r2 = radius * radius

        var n = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) if (dx * dx + dy * dy <= r2) n++
        }
        val offX = IntArray(n)
        val offY = IntArray(n)
        var k = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dx * dx + dy * dy <= r2) {
                    offX[k] = dx
                    offY[k] = dy
                    k++
                }
            }
        }

        val out = BooleanArray(d.size)
        Parallel.rows(h, Parallel.ROWS_NEIGHBOURHOOD) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                for (x in 0 until w) {
                    if (dilateOp) {
                        var hit = false
                        var j = 0
                        while (j < n) {
                            val nx = x + offX[j]
                            val ny = y + offY[j]
                            if (nx >= 0 && ny >= 0 && nx < w && ny < h && d[ny * w + nx]) {
                                hit = true
                                break
                            }
                            j++
                        }
                        out[row + x] = hit
                    } else {
                        var keep = true
                        var j = 0
                        while (j < n) {
                            val nx = x + offX[j]
                            val ny = y + offY[j]
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h || !d[ny * w + nx]) {
                                keep = false
                                break
                            }
                            j++
                        }
                        out[row + x] = keep
                    }
                }
            }
        }
        return Mask(w, h, out)
    }

    private fun grayRect(src: GrayF, radius: Int, maxOp: Boolean): GrayF {
        if (radius <= 0) return src.copy()
        val w = src.width
        val h = src.height
        val d = src.data
        val tmp = FloatArray(d.size)

        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                for (x in 0 until w) {
                    var best = d[row + x]
                    var k = -radius
                    while (k <= radius) {
                        var nx = x + k
                        if (nx < 0) nx = 0 else if (nx > w - 1) nx = w - 1
                        val v = d[row + nx]
                        if (maxOp) {
                            if (v > best) best = v
                        } else {
                            if (v < best) best = v
                        }
                        k++
                    }
                    tmp[row + x] = best
                }
            }
        }

        val out = FloatArray(d.size)
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                for (x in 0 until w) {
                    var best = tmp[row + x]
                    var k = -radius
                    while (k <= radius) {
                        var ny = y + k
                        if (ny < 0) ny = 0 else if (ny > h - 1) ny = h - 1
                        val v = tmp[ny * w + x]
                        if (maxOp) {
                            if (v > best) best = v
                        } else {
                            if (v < best) best = v
                        }
                        k++
                    }
                    out[row + x] = best
                }
            }
        }
        return GrayF(w, h, out)
    }
}
