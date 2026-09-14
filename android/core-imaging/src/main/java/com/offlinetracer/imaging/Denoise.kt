package com.offlinetracer.imaging

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max

/**
 * Noise reduction.
 *
 * Three filters are offered because they fail in different ways and the failure is what matters:
 * [bilateral] is right for sensor noise (it preserves the edges the tracer is looking for),
 * [median] is right for dust and salt-and-pepper on a scan (bilateral treats a dust speck as an
 * edge and protects it), and [anisotropicDiffusion] is right for suppressing woven or carved
 * texture that neither of the others touches.
 */
object Denoise {

    /**
     * Edge-preserving bilateral filter.
     *
     * Spatial radius is `ceil(2 * sigmaSpace)` — 2σ, not the 3σ used for a plain Gaussian, because
     * the range term already suppresses the tail and the kernel here is 2-D, so the outer ring costs
     * quadratically for a contribution the range weight has usually already zeroed.
     *
     * Non-positive sigmas return a copy.
     */
    fun bilateral(src: GrayF, sigmaSpace: Float, sigmaRange: Float): GrayF {
        if (sigmaSpace <= 0.05f || sigmaRange <= 0f) return src.copy()
        val w = src.width
        val h = src.height
        val s = src.data
        val r = max(1, ceil(2.0 * sigmaSpace).toInt())
        val side = 2 * r + 1

        val spatial = FloatArray(side * side)
        val invS = -1.0 / (2.0 * sigmaSpace * sigmaSpace)
        var si = 0
        for (dy in -r..r) {
            for (dx in -r..r) {
                spatial[si] = exp((dx * dx + dy * dy).toDouble() * invS).toFloat()
                si++
            }
        }
        // 256-entry range LUT indexed by round(|ΔI| * 255). Evaluating exp() per neighbour pair made
        // this single stage cost more than the rest of the pipeline put together on a 2048px image.
        val range = FloatArray(256)
        val invR = -1.0 / (2.0 * sigmaRange * sigmaRange)
        for (i in 0 until 256) {
            val d = i / 255.0
            range[i] = exp(d * d * invR).toFloat()
        }

        val out = FloatArray(w * h)
        // The documented hot spot, and the cleanest possible split: both lookup tables are finished and
        // read-only, `s` is never written, and each pixel's `(2r+1)²` accumulation lands in `out[i]`
        // alone. A share therefore evaluates the identical sum in the identical order for its own rows.
        Parallel.rows(h, Parallel.ROWS_NEIGHBOURHOOD) { fromY, toY ->
            for (y in fromY until toY) {
                val interiorY = y >= r && y + r < h
                val rowBase = y * w
                for (x in 0 until w) {
                    val i = rowBase + x
                    val c = s[i]
                    var wsum = 0f
                    var vsum = 0f
                    if (interiorY && x >= r && x + r < w) {
                        var ki = 0
                        var rowStart = i - r * w - r
                        for (dy in -r..r) {
                            var idx = rowStart
                            for (dx in -r..r) {
                                val v = s[idx]
                                var d = v - c
                                if (d < 0f) d = -d
                                var li = (d * 255f + 0.5f).toInt()
                                if (li > 255) li = 255
                                val ww = spatial[ki] * range[li]
                                wsum += ww
                                vsum += ww * v
                                ki++
                                idx++
                            }
                            rowStart += w
                        }
                    } else {
                        var ki = 0
                        for (dy in -r..r) {
                            var yy = y + dy
                            if (yy < 0) yy = 0
                            if (yy > h - 1) yy = h - 1
                            val base = yy * w
                            for (dx in -r..r) {
                                var xx = x + dx
                                if (xx < 0) xx = 0
                                if (xx > w - 1) xx = w - 1
                                val v = s[base + xx]
                                var d = v - c
                                if (d < 0f) d = -d
                                var li = (d * 255f + 0.5f).toInt()
                                if (li > 255) li = 255
                                val ww = spatial[ki] * range[li]
                                wsum += ww
                                vsum += ww * v
                                ki++
                            }
                        }
                    }
                    out[i] = if (wsum > 0f) vsum / wsum else c
                }
            }
        }
        return GrayF(w, h, out)
    }

    /**
     * Median filter over a `(2r+1)²` window, edge-clamped. A non-positive radius returns a copy.
     *
     * Radius 1 sorts the nine exact float samples. Radius ≥ 2 slides a 256-bin histogram across each
     * row, which makes the cost independent of the window area — the input at this point in the
     * pipeline came from 8-bit pixels, so the histogram is lossless for real sources and the
     * alternative (sorting 25+ floats per pixel) is minutes rather than seconds on a large scan.
     */
    fun median(src: GrayF, radius: Int): GrayF {
        if (radius <= 0) return src.copy()
        return if (radius == 1) median3(src) else medianHistogram(src, radius)
    }

    /**
     * Perona–Malik anisotropic diffusion with conduction `c(g) = exp(-(g/κ)²)`.
     *
     * [lambda] is the explicit-scheme step; 0.25 is the stability limit for a 4-neighbourhood and
     * larger values oscillate, so it is clamped there. Non-positive [iterations] or [kappa] return
     * a copy.
     */
    fun anisotropicDiffusion(src: GrayF, iterations: Int, kappa: Float, lambda: Float = 0.25f): GrayF {
        if (iterations <= 0 || kappa <= 0f) return src.copy()
        val w = src.width
        val h = src.height
        val lam = Px.clamp(lambda, 0f, 0.25f)
        val invK2 = -1.0 / (kappa.toDouble() * kappa)
        var cur = src.data.copyOf()
        var next = FloatArray(w * h)
        for (pass in 0 until iterations) {
            // Rows within one diffusion step are split; the step loop itself is not, and the double
            // buffer is what makes that legal — every pixel reads `rd` (finished last step) and writes
            // only `wr[i]`. Diffusing in place would make each pixel see whichever of its neighbours
            // its own share had already updated, i.e. an answer that depends on the band boundaries.
            //
            // Bound to locals rather than captured because `cur`/`next` are `var`s and a captured `var`
            // becomes a heap `Ref` read four times per pixel.
            val rd = cur
            val wr = next
            Parallel.rows(h, Parallel.ROWS_NEIGHBOURHOOD) { fromY, toY ->
                for (y in fromY until toY) {
                    val base = y * w
                    val up = if (y > 0) base - w else base
                    val dn = if (y < h - 1) base + w else base
                    for (x in 0 until w) {
                        val i = base + x
                        val v = rd[i]
                        val gN = rd[up + x] - v
                        val gS = rd[dn + x] - v
                        val gW = rd[base + (if (x > 0) x - 1 else 0)] - v
                        val gE = rd[base + (if (x < w - 1) x + 1 else w - 1)] - v
                        val cN = exp(gN.toDouble() * gN * invK2)
                        val cS = exp(gS.toDouble() * gS * invK2)
                        val cW = exp(gW.toDouble() * gW * invK2)
                        val cE = exp(gE.toDouble() * gE * invK2)
                        wr[i] = v + lam * (cN * gN + cS * gS + cW * gW + cE * gE).toFloat()
                    }
                }
            }
            val swap = cur
            cur = next
            next = swap
        }
        return GrayF(w, h, cur)
    }

    /**
     * Replace a pixel by the local median only where it differs from that median by more than
     * [threshold]; everything else is passed through untouched.
     *
     * This is what a dust-removal pass should do. A plain median softens every stroke in the image
     * to remove a handful of specks, and on faint pencil work that loss is not recoverable.
     */
    fun despeckle(src: GrayF, radius: Int, threshold: Float): GrayF {
        if (radius <= 0) return src.copy()
        val med = median(src, radius)
        val s = src.data
        val m = med.data
        val out = FloatArray(s.size)
        for (i in s.indices) {
            var d = s[i] - m[i]
            if (d < 0f) d = -d
            out[i] = if (d > threshold) m[i] else s[i]
        }
        return GrayF(src.width, src.height, out)
    }

    private fun median3(src: GrayF): GrayF {
        val w = src.width
        val h = src.height
        val out = FloatArray(w * h)
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            // The sort scratch is allocated **per share**. Hoisting it out of the lambda the way the
            // single-threaded version did would have every thread sorting into the same nine floats,
            // and the corruption would look like plausible speckle rather than like a bug.
            val buf = FloatArray(9)
            for (y in fromY until toY) {
                for (x in 0 until w) {
                    var c = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            buf[c] = src.clamped(x + dx, y + dy)
                            c++
                        }
                    }
                    for (i in 1 until 9) {
                        val v = buf[i]
                        var j = i - 1
                        while (j >= 0 && buf[j] > v) {
                            buf[j + 1] = buf[j]
                            j--
                        }
                        buf[j + 1] = v
                    }
                    out[y * w + x] = buf[4]
                }
            }
        }
        return GrayF(w, h, out)
    }

    private fun medianHistogram(src: GrayF, r: Int): GrayF {
        val w = src.width
        val h = src.height
        val n = w * h
        val q = IntArray(n)
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) q[i] = Px.toByte255(src.data[i])
        }
        val out = FloatArray(n)
        val side = 2 * r + 1
        val windowSize = side * side
        val rank = windowSize / 2
        // The sliding window is a *within-row* dependency, not a cross-row one: the histogram is rebuilt
        // from scratch at the start of every row, so a row's answer is a function of `q` alone and rows
        // split cleanly. Each share needs its own 256 bins for the same reason [median3] needs its own
        // nine floats.
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            val hist = IntArray(256)
            for (y in fromY until toY) {
                java.util.Arrays.fill(hist, 0)
                for (dy in -r..r) {
                    var yy = y + dy
                    if (yy < 0) yy = 0
                    if (yy > h - 1) yy = h - 1
                    val base = yy * w
                    for (dx in -r..r) {
                        var xx = dx
                        if (xx < 0) xx = 0
                        if (xx > w - 1) xx = w - 1
                        hist[q[base + xx]]++
                    }
                }
                // Huang's moving median: track the bin holding the median and the count strictly below
                // it, so a step costs O(window width) instead of a 256-bin rescan per pixel.
                var mdn = 0
                var below = 0
                while (below + hist[mdn] <= rank) {
                    below += hist[mdn]
                    mdn++
                }
                out[y * w] = mdn * (1f / 255f)
                for (x in 1 until w) {
                    var outCol = x - r - 1
                    if (outCol < 0) outCol = 0
                    if (outCol > w - 1) outCol = w - 1
                    var inCol = x + r
                    if (inCol < 0) inCol = 0
                    if (inCol > w - 1) inCol = w - 1
                    for (dy in -r..r) {
                        var yy = y + dy
                        if (yy < 0) yy = 0
                        if (yy > h - 1) yy = h - 1
                        val base = yy * w
                        val ov = q[base + outCol]
                        hist[ov]--
                        if (ov < mdn) below--
                        val iv = q[base + inCol]
                        hist[iv]++
                        if (iv < mdn) below++
                    }
                    while (below > rank) {
                        mdn--
                        below -= hist[mdn]
                    }
                    while (below + hist[mdn] <= rank) {
                        below += hist[mdn]
                        mdn++
                    }
                    out[y * w + x] = mdn * (1f / 255f)
                }
            }
        }
        return GrayF(w, h, out)
    }
}
