package com.offlinetracer.imaging

/** Structuring element shape for the binary operators in [Morphology]. */
enum class SeShape { RECT, CROSS, ELLIPSE }

/**
 * Binary and grey-level morphology (ALGORITHMS §9).
 *
 * Border policy follows the engine convention and the two halves of this file differ on purpose:
 * the [Mask] operators read out of bounds as **background** (so erosion eats the frame and a shape
 * touching the border is not silently treated as continuing past it), while [dilateGray] and
 * [erodeGray] **clamp to the edge** like every other analytic filter — a grey max/min filter that
 * read out of bounds as 0 or 1 would stamp a hard frame around the whole image.
 */
object Morphology {

    /**
     * Morphological dilation: a pixel is set when *any* structuring-element position over it is set.
     *
     * @param radius structuring element half-size; `≤ 0` returns a copy of [src].
     * @return a new [Mask] the size of [src].
     */
    fun dilate(src: Mask, radius: Int, shape: SeShape = SeShape.ELLIPSE): Mask {
        if (radius <= 0) return src.copy()
        return when (shape) {
            SeShape.RECT -> rect(src, radius, true)
            SeShape.CROSS -> cross(src, radius, true)
            SeShape.ELLIPSE -> ellipse(src, radius, true)
        }
    }

    /**
     * Morphological erosion: a pixel survives only when *every* structuring-element position over it
     * is set. Out-of-bounds counts as unset, so pixels within [radius] of the border always erode.
     *
     * @param radius structuring element half-size; `≤ 0` returns a copy of [src].
     * @return a new [Mask] the size of [src].
     */
    fun erode(src: Mask, radius: Int, shape: SeShape = SeShape.ELLIPSE): Mask {
        if (radius <= 0) return src.copy()
        return when (shape) {
            SeShape.RECT -> rect(src, radius, false)
            SeShape.CROSS -> cross(src, radius, false)
            SeShape.ELLIPSE -> ellipse(src, radius, false)
        }
    }

    /**
     * Opening — erosion then dilation. Removes specks smaller than the structuring element and
     * leaves everything else roughly where it was.
     *
     * (ALGORITHMS §9 writes this as "erode ∘ dilate"; read as function composition that expands to
     * `erode(dilate(x))`, which is closing. The parenthetical "removes specks" in the same line is
     * the authoritative half, and this is the standard definition.)
     *
     * @return a new [Mask] the size of [src].
     */
    fun open(src: Mask, radius: Int, shape: SeShape = SeShape.ELLIPSE): Mask =
        if (radius <= 0) src.copy() else dilate(erode(src, radius, shape), radius, shape)

    /**
     * Closing — dilation then erosion. Bridges gaps up to roughly `2·radius` without growing the
     * strokes permanently.
     *
     * One consequence worth knowing before it surprises you: because [erode] reads out of bounds as
     * background (ALGORITHMS §0), closing is **not** extensive at the image border — ink within
     * [radius] of the frame is trimmed rather than preserved. Libraries that use a `+∞` border for
     * erosion do not have this behaviour, but the engine-wide border policy is what the TypeScript
     * port also implements and the two are required to agree numerically.
     *
     * @return a new [Mask] the size of [src].
     */
    fun close(src: Mask, radius: Int, shape: SeShape = SeShape.ELLIPSE): Mask =
        if (radius <= 0) src.copy() else erode(dilate(src, radius, shape), radius, shape)

    /**
     * Morphological gradient — `dilate − erode`, i.e. a band of the boundary `2·radius` wide.
     *
     * @return a new [Mask] the size of [src]; empty when [radius] `≤ 0`.
     */
    fun gradient(src: Mask, radius: Int, shape: SeShape = SeShape.ELLIPSE): Mask {
        if (radius <= 0) return Mask.like(src)
        return dilate(src, radius, shape).subtract(erode(src, radius, shape))
    }

    /**
     * Grey-level dilation: local **maximum** over a `(2r+1)²` square, edge-clamped.
     *
     * Separable (the max over a rectangle is the max of the row maxima), which is why there is no
     * shape parameter. The inner loop is the plain O(r) scan rather than a van Herk running-max
     * deque: the pipeline uses radii of 1–5 where the deque bookkeeping costs more than it saves.
     *
     * @param radius `≤ 0` returns a copy of [src].
     * @return a new [GrayF] the size of [src].
     */
    fun dilateGray(src: GrayF, radius: Int): GrayF = grayRect(src, radius, true)

    /**
     * Grey-level erosion: local **minimum** over a `(2r+1)²` square, edge-clamped.
     *
     * @param radius `≤ 0` returns a copy of [src].
     * @return a new [GrayF] the size of [src].
     */
    fun erodeGray(src: GrayF, radius: Int): GrayF = grayRect(src, radius, false)

    // ---------------------------------------------------------------------------------------
    // Structuring elements
    // ---------------------------------------------------------------------------------------

    /**
     * Rectangle: horizontal pass then vertical pass, each with a running count of set pixels in the
     * window. Exact for both operators because a rectangle is the Minkowski sum of a horizontal and
     * a vertical line, and O(1) per pixel regardless of radius.
     */
    private fun rect(src: Mask, radius: Int, dilateOp: Boolean): Mask {
        val w = src.width
        val h = src.height
        val d = src.data
        val win = 2 * radius + 1
        val tmp = BooleanArray(d.size)

        // The running count walks *along* each line and each line owns its own counter, so the split is
        // across lines: rows for the horizontal pass, columns for the vertical one. Note that unlike
        // `Convolve.boxBlur`'s float running sum there is nothing here that could reassociate even in
        // principle — the state is an integer count of set pixels, and integer addition is exact.
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

    /** Cross: direct form. A cross is not separable — a horizontal pass followed by a vertical one
     *  builds a rectangle, not a cross — so the arms are tested explicitly. */
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

    /**
     * Ellipse (disc): direct form over a **precomputed** offset list. Testing `dx² + dy² ≤ r²` per
     * neighbour per pixel costs two multiplies and a branch on every one of the `(2r+1)²` positions
     * for a result that is identical for every pixel in the image.
     */
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
        // The disc is a genuine neighbourhood — `n` reads per pixel at radius `r` — so this takes the
        // coarser grain. The offset lists are built above and never written again.
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

    /** Separable grey max/min over a square, edge-clamped. */
    private fun grayRect(src: GrayF, radius: Int, maxOp: Boolean): GrayF {
        if (radius <= 0) return src.copy()
        val w = src.width
        val h = src.height
        val d = src.data
        val tmp = FloatArray(d.size)

        // Both passes over rows. The vertical pass reads `tmp` up to `radius` rows away, which is safe
        // because the horizontal pass has finished: `Parallel.rows` is the barrier.
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
