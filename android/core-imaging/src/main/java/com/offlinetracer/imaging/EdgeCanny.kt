package com.offlinetracer.imaging

import kotlin.math.floor

/**
 * Canny edge detection (ALGORITHMS §7.1).
 *
 * The one place this deviates from the textbook implementation is non-maximum suppression: the
 * gradient direction is used *as it is*, with the two comparison samples taken by bilinear
 * interpolation, instead of being quantised to 0/45/90/135°. Quantising is the usual shortcut and it
 * is why most Canny output has a 1px staircase on every non-axis-aligned edge — a staircase that the
 * contour tracer faithfully reproduces and that then survives Douglas–Peucker as real geometry.
 */
object EdgeCanny {

    /**
     * Full Canny: blur → gradients → interpolated NMS → double threshold with hysteresis.
     *
     * [low] and [high] are in **gradient-magnitude units**, not percentiles: a normalised Scharr or
     * Sobel gradient of 0..1 data lands in roughly 0..1, which is the same scale
     * `Threshold.autoCannyThresholds` works in. They are swapped if passed reversed and clamped to
     * be non-negative, so a caller that mixes them up gets a sane result instead of an empty mask.
     *
     * @return a [Mask] the size of [src] where `true` marks an edge pixel.
     */
    fun detect(
        src: GrayF,
        blurSigma: Float,
        low: Float,
        high: Float,
        op: GradientOp = GradientOp.SCHARR,
    ): Mask {
        val thin = nonMaximumSuppression(gradientsOf(src, blurSigma, op))
        var lo = if (low.isNaN()) 0f else if (low < 0f) 0f else low
        var hi = if (high.isNaN()) 0f else if (high < 0f) 0f else high
        if (lo > hi) {
            val t = lo
            lo = hi
            hi = t
        }
        return Threshold.hysteresis(thin, lo, hi)
    }

    /**
     * Canny with thresholds derived from the median gradient magnitude (ALGORITHMS §6):
     * `lo = (1-s)·median`, `hi = (1+s)·median`.
     *
     * [sensitivity] is `s`; smaller keeps fewer edges. The median is taken over the **full** gradient
     * magnitude rather than the suppressed one, because the suppressed image is mostly zeros and its
     * median would collapse to 0 on any normal photograph.
     *
     * @return a [Mask] the size of [src].
     */
    fun detectAuto(src: GrayF, blurSigma: Float, sensitivity: Float = 0.33f): Mask {
        val g = gradientsOf(src, blurSigma, GradientOp.SCHARR)
        val t = Threshold.autoCannyThresholds(g.magnitude(), sensitivity)
        val lo = if (t.size > 0) t[0] else 0f
        val hi = if (t.size > 1) t[1] else lo
        val thin = nonMaximumSuppression(g)
        return Threshold.hysteresis(thin, if (lo <= hi) lo else hi, if (lo <= hi) hi else lo)
    }

    /**
     * Thins the gradient magnitude to single-pixel ridges by keeping only pixels that are `≥` both
     * of their neighbours **along the true gradient direction**.
     *
     * For gradient `(gx, gy)` the step is `(gx, gy) / max(|gx|, |gy|)`, so one component is exactly
     * ±1 and the sample point always lands on the boundary of the 3×3 neighbourhood; the magnitude
     * there is read by bilinear interpolation. Pixels with a zero gradient have no direction and are
     * suppressed.
     *
     * This answers *which* pixel the ridge is in, and nothing more; [subpixelRidge] answers where in
     * that pixel it is, which is worth about a factor of eight in edge position and is what a vector
     * needs to sit on the edge rather than beside it.
     *
     * @return a new [GrayF] holding the magnitude at surviving pixels and 0 everywhere else.
     */
    fun nonMaximumSuppression(g: Convolve.Gradients): GrayF {
        val gxImg = g.gx
        val gyImg = g.gy
        require(gxImg.width == gyImg.width && gxImg.height == gyImg.height) {
            "nonMaximumSuppression(): gx ${gxImg.width}x${gxImg.height} != gy ${gyImg.width}x${gyImg.height}"
        }
        val w = gxImg.width
        val h = gxImg.height
        val gx = gxImg.data
        val gy = gyImg.data
        val mag = g.magnitude().data
        val out = FloatArray(mag.size)
        // Row split. Each pixel reads `mag` at two interpolated points up to one pixel away — possibly
        // in a neighbouring share's rows — and writes only `out[i]`, so the reads are of a finished
        // plane and the writes are disjoint. `out` starts zeroed, which is what the `continue`s rely on.
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    val m = mag[i]
                    if (m <= 0f) continue
                    val vx = gx[i]
                    val vy = gy[i]
                    val ax = if (vx < 0f) -vx else vx
                    val ay = if (vy < 0f) -vy else vy
                    val amax = if (ax > ay) ax else ay
                    // A zero gradient has no direction to suppress along; treating it as an edge would
                    // let flat regions leak through hysteresis wherever `low` is 0.
                    if (amax <= 1e-20f) continue
                    val ux = vx / amax
                    val uy = vy / amax
                    val fx = x + ux
                    val fy = y + uy
                    val bx = x - ux
                    val by = y - uy
                    if (m < sample(mag, w, h, fx, fy)) continue
                    if (m < sample(mag, w, h, bx, by)) continue
                    out[i] = m
                }
            }
        }
        return GrayF(w, h, out)
    }

    /**
     * The gradient ridge located to **sub-pixel** precision (ALGORITHMS §7.1).
     *
     * [nonMaximumSuppression] answers *which* pixel holds the ridge; it cannot answer *where in that
     * pixel* the edge is, so a mask built from it — and therefore every vector traced from that mask —
     * carries the half-pixel quantisation of the grid. Measured on an anti-aliased disc of radius 45
     * whose centre is deliberately off-lattice, the surviving pixels sit an RMS 0.341 px from the true
     * circle (max 0.66) at σ = 1.2; on an axis-aligned edge the whole line is offset by a *systematic*
     * 0.370 px, because every pixel of it rounds the same way.
     *
     * Fitting a parabola through the three magnitudes along the gradient and taking its vertex removes
     * almost all of that: the same disc measures 0.043 px RMS (max 0.12) and the axis-aligned edge
     * 0.005 px. That is the difference between a traced line that sits *beside* the edge and one that
     * sits *on* it.
     *
     * @param offsetX displacement from the pixel centre to the ridge maximum, along the gradient.
     * @param offsetY the same, in y.
     * @param magnitude the parabola's **peak** value — the magnitude the edge would have had if it had
     *   been sampled where it actually is. Always `≥` the pixel's own magnitude at a ridge pixel.
     */
    class Ridge(
        @JvmField val width: Int,
        @JvmField val height: Int,
        @JvmField val magnitude: FloatArray,
        @JvmField val offsetX: FloatArray,
        @JvmField val offsetY: FloatArray,
    ) {
        /** @return the x displacement at `(x, y)`, or 0 outside the image. */
        fun offsetXAt(x: Int, y: Int): Float =
            if (x < 0 || y < 0 || x >= width || y >= height) 0f else offsetX[y * width + x]

        /** @return the y displacement at `(x, y)`, or 0 outside the image. */
        fun offsetYAt(x: Int, y: Int): Float =
            if (x < 0 || y < 0 || x >= width || y >= height) 0f else offsetY[y * width + x]

        /**
         * Moves each of the `n` points in [xs]/[ys] onto the ridge, **in place**.
         *
         * The offset read is the one stored at the point's *nearest* pixel, so this is meaningful for
         * a polyline whose vertices came off the same lattice this ridge was computed on — a traced
         * edge mask, not an arbitrary curve. Points outside the image are left where they are.
         *
         * The displacement is bounded by half the sampling step, so a vertex can never be moved onto a
         * neighbouring feature: see the note in [subpixelRidge].
         */
        fun snap(xs: FloatArray, ys: FloatArray, n: Int = minOf(xs.size, ys.size)) {
            val count = if (n < 0) 0 else minOf(n, minOf(xs.size, ys.size))
            for (i in 0 until count) {
                val x = Math.round(xs[i])
                val y = Math.round(ys[i])
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                val j = y * width + x
                xs[i] += offsetX[j]
                ys[i] += offsetY[j]
            }
        }
    }

    /**
     * Sub-pixel refinement of the gradient ridge: a parabola through the three magnitudes along the
     * gradient direction, evaluated at every pixel.
     *
     * The three samples are **the same points [nonMaximumSuppression] compares against** — the pixel
     * itself and `±(gx, gy) / max(|gx|, |gy|)`, read bilinearly — so the refinement is consistent with
     * the survivor test by construction rather than by coincidence. With `a` and `c` the two
     * neighbours and `m` the centre, the vertex of the parabola through `(-1, a), (0, m), (1, c)` is at
     * `t = ½(a − c) / (a − 2m + c)`, and the displacement is `t · (gx, gy)/max(|gx|, |gy|)`.
     *
     * **A pixel that survived NMS always has |t| ≤ ½.** Writing `a = m − p`, `c = m − q` with
     * `p, q ≥ 0` (which is what `m ≥ a` and `m ≥ c` say), `t = ½(p − q)/(p + q)`, and `|p − q| ≤ p + q`.
     * So the refinement can only move the point within its own half-cell along the gradient, never onto
     * a neighbouring ridge — which is why [Ridge.snap] is safe to apply blindly to a traced polyline.
     * The clamp below therefore never binds on a ridge pixel; it exists for the pixels either side of
     * one, where the vertex genuinely lies elsewhere and an unbounded `t` would be meaningless.
     *
     * A denominator that is `≥ 0` means the three samples are flat or concave-up — no maximum to
     * refine towards — and leaves the offset at zero. So does a zero gradient, which has no direction.
     *
     * @return a [Ridge] the size of the gradients. This is an *addition*: [detect], [detectAuto] and
     *   [nonMaximumSuppression] are unchanged, so no existing output moves.
     */
    fun subpixelRidge(g: Convolve.Gradients): Ridge {
        val gxImg = g.gx
        val gyImg = g.gy
        require(gxImg.width == gyImg.width && gxImg.height == gyImg.height) {
            "subpixelRidge(): gx ${gxImg.width}x${gxImg.height} != gy ${gyImg.width}x${gyImg.height}"
        }
        val w = gxImg.width
        val h = gxImg.height
        val gx = gxImg.data
        val gy = gyImg.data
        val mag = g.magnitude().data
        val peak = FloatArray(mag.size)
        val ox = FloatArray(mag.size)
        val oy = FloatArray(mag.size)
        // Same read/write discipline as `nonMaximumSuppression`: reads of a finished `mag` plane up to
        // one pixel away, writes only to this pixel's own cell in three fresh zeroed planes.
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    val m = mag[i]
                    peak[i] = m
                    if (m <= 0f) continue
                    val vx = gx[i]
                    val vy = gy[i]
                    val ax = if (vx < 0f) -vx else vx
                    val ay = if (vy < 0f) -vy else vy
                    val amax = if (ax > ay) ax else ay
                    if (amax <= 1e-20f) continue
                    val ux = vx / amax
                    val uy = vy / amax
                    val a = sampleDouble(mag, w, h, x - ux, y - uy)
                    val c = sampleDouble(mag, w, h, x + ux, y + uy)
                    // Double from here on. `t` is a ratio whose denominator vanishes on a flat ridge,
                    // so the stage has an unbounded error gain exactly where a real edge is broad —
                    // the §7.2 rule. The samples themselves stay float, because they are the same
                    // float plane `nonMaximumSuppression` compares and the two must not disagree
                    // about which pixel is a ridge.
                    val denom = a - 2.0 * m + c
                    if (denom >= 0.0) continue
                    var t = 0.5 * (a - c) / denom
                    if (!(t > -0.5)) t = -0.5      // also catches NaN
                    if (!(t < 0.5)) t = 0.5
                    ox[i] = (t * ux).toFloat()
                    oy[i] = (t * uy).toFloat()
                    // The parabola evaluated at t, not a closed form for its vertex: the two agree
                    // only when the clamp above did not bind, and a peak that disagrees with its own
                    // offset would be worse than not reporting one.
                    peak[i] = (m + 0.5 * t * (c - a) + 0.5 * t * t * denom).toFloat()
                }
            }
        }
        return Ridge(w, h, peak, ox, oy)
    }

    /** Blur (skipped when degenerate) then differentiate. Shared by [detect] and [detectAuto]. */
    private fun gradientsOf(src: GrayF, blurSigma: Float, op: GradientOp): Convolve.Gradients {
        // The σ ≤ 0.05 test is duplicated from Convolve.gaussianBlur on purpose: skipping the call
        // avoids a full-image copy on every preview frame where the user has the blur slider at 0.
        val blurred = if (blurSigma > 0.05f) Convolve.gaussianBlur(src, blurSigma) else src
        return Convolve.gradients(blurred, op)
    }

    /**
     * [sample] with the interpolation carried out in double.
     *
     * Used only by [subpixelRidge], and it exists so the two engines interpolate identically: the
     * TypeScript `GrayF.sampleBilinear` is double arithmetic over float32 taps because JavaScript has
     * no float arithmetic, and where the consumer is a *comparison* ([nonMaximumSuppression]) a
     * final-ulp difference is invisible, while where it is a *ratio with a vanishing denominator* it is
     * not.
     */
    private fun sampleDouble(d: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Double {
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        val tx = (fx - ix).toDouble()
        val ty = (fy - iy).toDouble()
        val x0 = if (ix < 0) 0 else if (ix > w - 1) w - 1 else ix
        val y0 = if (iy < 0) 0 else if (iy > h - 1) h - 1 else iy
        val x1p = ix + 1
        val y1p = iy + 1
        val x1 = if (x1p < 0) 0 else if (x1p > w - 1) w - 1 else x1p
        val y1 = if (y1p < 0) 0 else if (y1p > h - 1) h - 1 else y1p
        val r0 = y0 * w
        val r1 = y1 * w
        val v00 = d[r0 + x0].toDouble()
        val v10 = d[r0 + x1].toDouble()
        val v01 = d[r1 + x0].toDouble()
        val v11 = d[r1 + x1].toDouble()
        val a = v00 + (v10 - v00) * tx
        val b = v01 + (v11 - v01) * tx
        return a + (b - a) * ty
    }

    /** Edge-clamped bilinear read of a flat array; the analytic border policy (ALGORITHMS §0). */
    private fun sample(d: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        val tx = fx - ix
        val ty = fy - iy
        val x0 = if (ix < 0) 0 else if (ix > w - 1) w - 1 else ix
        val y0 = if (iy < 0) 0 else if (iy > h - 1) h - 1 else iy
        val x1p = ix + 1
        val y1p = iy + 1
        val x1 = if (x1p < 0) 0 else if (x1p > w - 1) w - 1 else x1p
        val y1 = if (y1p < 0) 0 else if (y1p > h - 1) h - 1 else y1p
        val r0 = y0 * w
        val r1 = y1 * w
        val v00 = d[r0 + x0]
        val v10 = d[r0 + x1]
        val v01 = d[r1 + x0]
        val v11 = d[r1 + x1]
        val a = v00 + (v10 - v00) * tx
        val b = v01 + (v11 - v01) * tx
        return a + (b - a) * ty
    }
}
