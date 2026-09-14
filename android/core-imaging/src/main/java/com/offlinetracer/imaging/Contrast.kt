package com.offlinetracer.imaging

import kotlin.math.max
import kotlin.math.min

/**
 * Tone and contrast operators.
 *
 * Everything in this file except [histogram] returns values in 0..1. That is the one place in the
 * engine where clamping is correct: these are tone controls defined on a 0..1 signal, and a
 * negative or >1 result would be silently folded into the end bins of the next stage's 256-bin
 * histogram, which reads as a mysterious spike at pure black or pure white.
 */
object Contrast {

    /**
     * Contrast-limited adaptive histogram equalisation.
     *
     * Local contrast is what lets a faint pencil line survive the same threshold that a bold ink
     * line survives, which is why this is on by default for photographed artwork.
     *
     * The clip limit is `max(1, clipLimit * tilePixels / 256)`; the excess above it is summed and
     * redistributed uniformly across all 256 bins in **one** pass. Redistribution can push bins back
     * over the limit and the standard accepts that — iterating to a fixed point measurably flattens
     * the result for no visible gain.
     *
     * The per-tile LUTs are then **bilinearly interpolated using tile centres as the lattice**, with
     * the border half-tile clamping to the edge tiles. Sampling each pixel from its own tile's LUT
     * instead is the single most common way CLAHE is implemented wrong, and it produces a visible
     * grid of seams that survives every later stage and gets traced as straight lines.
     *
     * A tile holding a **single grey level** is mapped by the identity instead of by its CDF. The
     * literal algorithm sends that level to 255 — its CDF is 1.0 at the one occupied bin — so a
     * constant tile, a 1×1 image, or any image small enough that a tile is one pixel would come back
     * pure white. "Enhance local contrast" has no meaning where there is one level to enhance, and
     * white is not an answer a user would accept for a mid-grey input.
     */
    fun clahe(src: GrayF, tilesX: Int = 8, tilesY: Int = 8, clipLimit: Float = 2f): GrayF {
        val w = src.width
        val h = src.height
        val tx = Px.clamp(tilesX, 1, w)
        val ty = Px.clamp(tilesY, 1, h)
        val s = src.data

        // Tile bounds by exact integer partition. These same bounds define the interpolation lattice
        // below, which is why they are materialised rather than recomputed: a lattice derived from
        // the *nominal* tile size w/tx does not sit on the centres of the tiles actually
        // histogrammed whenever w is not a multiple of tx, and every pixel is then blended from the
        // wrong pair of LUTs. Clamping tx to w also guarantees every tile owns at least one pixel.
        val xb = IntArray(tx + 1) { it * w / tx }
        val yb = IntArray(ty + 1) { it * h / ty }

        val lut = IntArray(tx * ty * 256)

        // Split over **tile rows**, because that is the coarsest unit that owns a disjoint slice of
        // both the image and `lut`: tile row `tyi` reads image rows `yb[tyi] until yb[tyi+1]` and writes
        // `lut[tyi*tx*256 ...]`. Splitting finer — inside a tile row — would mean two shares
        // accumulating into one tile's histogram, which is a shared accumulator.
        //
        // The grain is expressed in *image* rows and converted, because `ty` is a fixed 8 whatever the
        // image size: a share of `t` tile rows covers about `t*h/ty` image rows, so requiring
        // ROWS_KERNEL image rows per share gives `ceil(ROWS_KERNEL * ty / h)` tile rows. On a 1600-row
        // photo that is 1 (all eight tile rows in parallel); on the 18-row parity fixture it is 15, so
        // `ty / 15 == 0` shares and the whole thing runs inline, which is what a fixture must do.
        val minTileRows = max(1, (Parallel.ROWS_KERNEL * ty + h - 1) / h)
        Parallel.chunks(ty, minTileRows) { fromT, toT ->
            val hist = IntArray(256)
            for (tyi in fromT until toT) {
                for (txi in 0 until tx) {
                    java.util.Arrays.fill(hist, 0)
                    var total = 0
                    var occupied = 0
                    for (y in yb[tyi] until yb[tyi + 1]) {
                        val base = y * w
                        for (x in xb[txi] until xb[txi + 1]) {
                            val b = Px.toByte255(s[base + x])
                            if (hist[b] == 0) occupied++
                            hist[b]++
                            total++
                        }
                    }
                    val off = (tyi * tx + txi) * 256
                    if (occupied <= 1) {
                        // One level, or (impossibly, since tx <= w) none at all: pass the tile through.
                        // See the note in the KDoc — equalising one level yields white, and the empty
                        // case would divide by zero below and poison a quarter of the interpolation.
                        for (b in 0 until 256) lut[off + b] = b
                        continue
                    }
                    if (clipLimit > 0f) {
                        // In Double, so the truncation cannot land on the other side of an integer from
                        // the TypeScript engine's; a limit that differs by one bin changes the CDF.
                        val limit = max(1, (clipLimit.toDouble() * total / 256.0).toInt())
                        var excess = 0
                        for (b in 0 until 256) {
                            val v = hist[b]
                            if (v > limit) {
                                excess += v - limit
                                hist[b] = limit
                            }
                        }
                        val inc = excess / 256
                        val rem = excess - inc * 256
                        for (b in 0 until 256) hist[b] += inc + (if (b < rem) 1 else 0)
                    }
                    // Redistribution moves counts between bins and never changes their sum, so the CDF
                    // still ends at `total`.
                    var cum = 0
                    val denom = 2L * total
                    for (b in 0 until 256) {
                        cum += hist[b]
                        // round(cum * 255 / total) in exact integer arithmetic. As a float product the
                        // result lands either side of a .5 tie depending on the working precision —
                        // Float here, Double in TypeScript — and one LUT step is 1/255, forty times the
                        // parity tolerance. Long because cum * 510 overflows Int on a 12 MP single tile.
                        lut[off + b] = ((cum.toLong() * 510 + total) / denom).toInt()
                    }
                }
            }
        }

        // Interpolation lattice: the centre of each tile's own pixel range, i.e. the mean of its
        // first and last index. Using tile origins instead shifts every mapping half a tile and
        // shows up as a bright or dark rim along the border half-tile.
        val cx = FloatArray(tx) { (xb[it] + xb[it + 1] - 1) * 0.5f }
        val cy = FloatArray(ty) { (yb[it] + yb[it + 1] - 1) * 0.5f }

        val xi0 = IntArray(w)
        val xi1 = IntArray(w)
        val xf = FloatArray(w)
        for (x in 0 until w) {
            var i0 = 0
            while (i0 < tx - 2 && x > cx[i0 + 1]) i0++
            val i1 = min(tx - 1, i0 + 1)
            val span = cx[i1] - cx[i0]
            xi0[x] = i0
            xi1[x] = i1
            // Clamped, so a pixel in the border half-tile takes the edge tile's LUT whole.
            xf[x] = if (span > 0f) Px.clamp((x - cx[i0]) / span, 0f, 1f) else 0f
        }

        // The interpolation is the expensive half of CLAHE — a quantisation, four LUT reads and three
        // lerps for every pixel in the image — and it splits perfectly: `lut` and the two lattices are
        // finished and read-only, and `j0` is rediscovered from zero on every row, so no state crosses
        // a row boundary for a share to have to reproduce.
        val out = FloatArray(w * h)
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY until toY) {
                var j0 = 0
                while (j0 < ty - 2 && y > cy[j0 + 1]) j0++
                val j1 = min(ty - 1, j0 + 1)
                val spanY = cy[j1] - cy[j0]
                val fy = if (spanY > 0f) Px.clamp((y - cy[j0]) / spanY, 0f, 1f) else 0f
                val rowTop = j0 * tx * 256
                val rowBot = j1 * tx * 256
                val base = y * w
                for (x in 0 until w) {
                    val bin = Px.toByte255(s[base + x])
                    val fx = xf[x]
                    val o0 = xi0[x] * 256 + bin
                    val o1 = xi1[x] * 256 + bin
                    val v00 = lut[rowTop + o0].toFloat()
                    val v01 = lut[rowTop + o1].toFloat()
                    val v10 = lut[rowBot + o0].toFloat()
                    val v11 = lut[rowBot + o1].toFloat()
                    val top = v00 + (v01 - v00) * fx
                    val bot = v10 + (v11 - v10) * fx
                    out[base + x] = (top + (bot - top) * fy) / 255f
                }
            }
        }
        return GrayF(w, h, out)
    }

    /**
     * Global histogram equalisation over 256 bins. A flat image (every pixel in one bin) is returned
     * unchanged rather than stretched into meaningless noise.
     */
    fun equalize(src: GrayF): GrayF {
        val hist = histogram(src, 256)
        val n = src.size
        var cdfMin = 0
        var first = -1
        for (b in 0 until 256) {
            if (hist[b] != 0) {
                first = b
                cdfMin = hist[b]
                break
            }
        }
        if (first < 0) return src.copy()
        val denom = n - cdfMin
        if (denom <= 0) return src.copy()
        val lut = FloatArray(256)
        var cum = 0
        val inv = 1f / denom
        for (b in 0 until 256) {
            cum += hist[b]
            val v = (cum - cdfMin) * inv
            lut[b] = if (v < 0f) 0f else if (v > 1f) 1f else v
        }
        val s = src.data
        val out = FloatArray(n)
        // The LUT is finished before a single pixel is mapped, so the apply pass splits; the histogram
        // it was built from does not, for the reason [histogram] gives.
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) out[i] = lut[Px.toByte255(s[i])]
        }
        return GrayF(src.width, src.height, out)
    }

    /**
     * `out = clamp01(in) ^ (1/gamma)`, so gamma > 1 brightens. Matches the exponent used by
     * [levels]. A non-positive gamma returns a copy.
     */
    fun gamma(src: GrayF, gamma: Float): GrayF {
        if (gamma <= 0f) return src.copy()
        if (gamma == 1f) {
            val out = FloatArray(src.size)
            for (i in out.indices) out[i] = Px.clamp01(src.data[i])
            return GrayF(src.width, src.height, out)
        }
        val e = 1.0 / gamma
        val s = src.data
        val out = FloatArray(s.size)
        // Split: one `pow` per pixel is real work. The `gamma == 1f` branch above is not split — it is a
        // clamp, which runs at memory speed and gains nothing from a second core.
        Parallel.chunks(s.size, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) out[i] = Math.pow(Px.clamp01(s[i]).toDouble(), e).toFloat()
        }
        return GrayF(src.width, src.height, out)
    }

    /**
     * `out = ((clamp(in, black, white) - black) / (white - black)) ^ (1/gamma)`.
     *
     * A degenerate range (`white <= black`) becomes a hard step at [white] instead of dividing by
     * zero — that is what the user dragging the two handles together is asking for anyway.
     */
    fun levels(src: GrayF, black: Float, white: Float, gamma: Float): GrayF {
        val s = src.data
        val out = FloatArray(s.size)
        val span = white - black
        if (span <= 1e-8f) {
            for (i in s.indices) out[i] = if (s[i] >= white) 1f else 0f
            return GrayF(src.width, src.height, out)
        }
        val inv = 1f / span
        val g = if (gamma <= 0f) 1f else gamma
        if (g == 1f) {
            for (i in s.indices) out[i] = Px.clamp01((s[i] - black) * inv)
        } else {
            val e = 1.0 / g
            Parallel.chunks(s.size, Parallel.PIXELS_MAP) { from, to ->
                for (i in from until to) {
                    out[i] = Math.pow(Px.clamp01((s[i] - black) * inv).toDouble(), e).toFloat()
                }
            }
        }
        return GrayF(src.width, src.height, out)
    }

    /**
     * Brightness (additive) and contrast (multiplicative about mid-grey), both in -1..1.
     *
     * Contrast maps through `(1+c)/(1-c)` so 0 is identity and the control is symmetric in effect
     * between darkening and brightening; it is clamped just short of ±1 because the mapping is a
     * pole there and a slider that reaches its end should not produce a pure black-and-white image.
     */
    fun brightnessContrast(src: GrayF, brightness: Float, contrast: Float): GrayF {
        val c = Px.clamp(contrast, -0.99f, 0.99f)
        val factor = (1f + c) / (1f - c)
        val s = src.data
        val out = FloatArray(s.size)
        for (i in s.indices) out[i] = Px.clamp01((s[i] + brightness - 0.5f) * factor + 0.5f)
        return GrayF(src.width, src.height, out)
    }

    /**
     * `out = in + amount * (in - blur(in, sigma))`, applied only where `|in - blur| > threshold`.
     *
     * The threshold protects flat areas: without it the same amount that sharpens a stroke also
     * multiplies film grain in the paper around it, and the edge detector then finds the grain.
     */
    fun unsharpMask(src: GrayF, sigma: Float, amount: Float, threshold: Float = 0f): GrayF {
        if (amount == 0f) return src.copy()
        val blur = Convolve.gaussianBlur(src, sigma)
        val s = src.data
        val b = blur.data
        val out = FloatArray(s.size)
        for (i in s.indices) {
            val d = s[i] - b[i]
            val ad = if (d < 0f) -d else d
            out[i] = if (ad > threshold) Px.clamp01(s[i] + amount * d) else Px.clamp01(s[i])
        }
        return GrayF(src.width, src.height, out)
    }

    /** Linear stretch of the actual `[min, max]` range onto `[0, 1]`. A flat image returns a copy. */
    fun stretch(src: GrayF): GrayF {
        val r = src.range()
        val lo = r.first
        val span = r.second - lo
        if (span <= 1e-8f) return src.copy()
        val inv = 1f / span
        val s = src.data
        val out = FloatArray(s.size)
        for (i in s.indices) out[i] = (s[i] - lo) * inv
        return GrayF(src.width, src.height, out)
    }

    /**
     * Stretch between the [percentile]-th and (100-[percentile])-th percentiles, clamping outside.
     *
     * This and not [stretch] is what you want on a photograph: one specular highlight or one dust
     * speck otherwise owns an end of the range and the stretch does nothing. [percentile] is clamped
     * to 0..49 and the percentiles are read from a 256-bin histogram of the actual value range,
     * **interpolated within the boundary bin** by [percentileAt].
     */
    fun percentileStretch(src: GrayF, percentile: Float = 1f): GrayF {
        val p = Px.clamp(percentile, 0f, 49f)
        val r = src.range()
        val lo = r.first
        val span = r.second - lo
        if (span <= 1e-8f) return src.copy()
        val bins = 256
        val hist = IntArray(bins)
        val s = src.data
        val invSpan = 1f / span
        for (i in s.indices) {
            var b = ((s[i] - lo) * invSpan * bins).toInt()
            if (b < 0) b = 0
            if (b > bins - 1) b = bins - 1
            hist[b]++
        }
        val n = s.size
        val loV = lo + percentileAt(hist, n, p / 100f * n) * span
        val hiV = lo + percentileAt(hist, n, (100f - p) / 100f * n) * span
        if (hiV - loV <= 1e-8f) return stretch(src)
        val inv = 1f / (hiV - loV)
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = Px.clamp01((s[i] - loV) * inv)
        return GrayF(src.width, src.height, out)
    }

    /**
     * Locates the value at cumulative [rank] in [hist] and returns it as a **fraction of the
     * histogram's range** (0..1), interpolating linearly inside the bin the rank falls in.
     *
     * The interpolation is the whole point. Returning a bin *edge* costs up to a full bin width of
     * error, and a bin is only narrow when the data fills the range — which is precisely the case
     * [percentileStretch] does not care about. In the case it exists for, one outlier stretches the
     * range so far that all the real data crowds into a handful of bins, and there the edge error is
     * enormous:
     *
     *   100 samples, 99 of them spread evenly over 0.20..0.396, one at 8.0. The range is 7.8, so a
     *   bin spans 0.0305 and the real data occupies bins 0..6. For the 95th percentile the crossing
     *   bin is 6, which holds 7 samples starting at cumulative 92. The bin's top edge is 0.4133; the
     *   true 95th percentile is 0.390. Interpolating gives 0.2 + (6 + 3/7)/256 * 7.8 = 0.3959 — an
     *   error of 0.006 instead of 0.023, i.e. the difference between the artwork reaching the top of
     *   the output range and being compressed into the bottom 84% of it.
     *
     * @param rank the cumulative sample index to locate, 0..[n]; clamped into range.
     * @return the position in 0..1 across the histogram's span.
     */
    private fun percentileAt(hist: IntArray, n: Int, rank: Float): Float {
        val bins = hist.size
        if (bins == 0 || n <= 0) return 0f
        val target = Px.clamp(rank, 0f, n.toFloat())
        var cum = 0
        for (b in 0 until bins) {
            val count = hist[b]
            if (count == 0) continue
            val next = cum + count
            if (next >= target) {
                // Where the target sits inside this bin's occupants. `count` is > 0 here, so the
                // division is safe; empty bins were skipped precisely to keep it that way.
                val fraction = Px.clamp((target - cum) / count.toFloat(), 0f, 1f)
                return (b + fraction) / bins
            }
            cum = next
        }
        return 1f
    }

    /** `out = 1 - in`. Not clamped, so an out-of-range analytic image inverts without losing data. */
    fun invert(src: GrayF): GrayF {
        val s = src.data
        val out = FloatArray(s.size)
        for (i in s.indices) out[i] = 1f - s[i]
        return GrayF(src.width, src.height, out)
    }

    /**
     * Histogram of [src] over `[0, 1]` with [bins] levels (at least 1). Values outside the range
     * land in the end bins.
     *
     * The bin index is `round(clamp01(v) * (bins-1))`, i.e. quantisation to [bins] *levels* rather
     * than [bins] equal-width buckets. That makes `bins = 256` produce exactly [Px.toByte255], so a
     * histogram bin and the LUT it indexes cannot disagree — with floor-binning they differ by one
     * bin for most values and every equalised image comes out a level dark.
     *
     * **Left single-threaded.** Every pixel increments one of [bins] shared counters, which is the one
     * loop shape a row split cannot have. Integer counts *would* merge exactly in any order, so
     * per-share partial histograms are a legitimate fix — but the body is a quantise and an increment,
     * so the loop is bandwidth-bound and the merge would cost as much as it saved. `clahe`'s per-tile
     * histograms are the case where the split does pay, and they are split by tile row.
     */
    fun histogram(src: GrayF, bins: Int = 256): IntArray {
        val n = max(1, bins)
        val out = IntArray(n)
        val s = src.data
        if (n == 1) {
            out[0] = s.size
            return out
        }
        val scale = (n - 1).toFloat()
        for (i in s.indices) {
            var b = (Px.clamp01(s[i]) * scale + 0.5f).toInt()
            if (b < 0) b = 0
            if (b > n - 1) b = n - 1
            out[b]++
        }
        return out
    }
}
