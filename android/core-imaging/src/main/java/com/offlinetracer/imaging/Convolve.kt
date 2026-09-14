package com.offlinetracer.imaging

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Which first-derivative operator [Convolve.gradients] uses.
 *
 * File level, not nested, because every edge engine names it unqualified.
 */
enum class GradientOp { SOBEL, SCHARR }

/**
 * Convolution, derivatives and summed-area tables.
 *
 * Border policy for everything in this file is **clamp to edge**, matching [GrayF.clamped]. That is
 * the opposite of the mask/morphology policy and it is deliberate: zero-padding an analytic filter
 * manufactures a step from the image content down to black all the way round the frame, and the
 * gradient operator then reports a bright edge along every border of every photograph.
 */
object Convolve {

    /**
     * Gradient field of an image, as produced by [gradients].
     *
     * Sign convention: `gx` is positive where intensity **decreases** to the right and `gy` is
     * positive where it decreases downwards (y grows down). This is the formulation the
     * non-maximum-suppression step and every published Canny threshold assume; flipping it silently
     * mirrors every direction-dependent stage.
     */
    class Gradients(@JvmField val gx: GrayF, @JvmField val gy: GrayF) {

        /**
         * True Euclidean magnitude `sqrt(gx² + gy²)`.
         *
         * Never the `|gx| + |gy|` shortcut: L1 overstates diagonal edges by up to 41%, and since
         * every automatic threshold in the engine is derived from the median of this image, that
         * bias moves the threshold on every image that happens to contain diagonals.
         */
        fun magnitude(): GrayF {
            val a = gx.data
            val b = gy.data
            val out = FloatArray(a.size)
            Parallel.chunks(a.size, Parallel.PIXELS_MAP) { from, to ->
                for (i in from until to) {
                    val x = a[i]
                    val y = b[i]
                    out[i] = sqrt(x * x + y * y)
                }
            }
            return GrayF(gx.width, gx.height, out)
        }

        /** Gradient direction `atan2(gy, gx)` in radians, in `-π..π`, measured with y pointing down. */
        fun direction(): GrayF {
            val a = gx.data
            val b = gy.data
            val out = FloatArray(a.size)
            Parallel.chunks(a.size, Parallel.PIXELS_MAP) { from, to ->
                for (i in from until to) out[i] = atan2(b[i], a[i])
            }
            return GrayF(gx.width, gx.height, out)
        }
    }

    /**
     * Normalised 1-D Gaussian, odd length `2r+1` with `r = max(1, ceil(3σ))`.
     *
     * A σ at or below 0.05 returns the 3-tap identity `[0, 1, 0]` instead of a degenerate spike, so
     * callers that build a kernel unconditionally still get a well-formed odd kernel.
     */
    fun gaussianKernel(sigma: Float): FloatArray {
        if (sigma <= 0.05f) return floatArrayOf(0f, 1f, 0f)
        val r = max(1, ceil(3.0 * sigma).toInt())
        val n = 2 * r + 1
        val k = FloatArray(n)
        val inv = -1.0 / (2.0 * sigma * sigma)
        var sum = 0.0
        for (i in 0 until n) {
            val d = (i - r).toDouble()
            val v = exp(d * d * inv)
            k[i] = v.toFloat()
            sum += v
        }
        val norm = (1.0 / sum).toFloat()
        for (i in 0 until n) k[i] *= norm
        return k
    }

    /**
     * The same taps as [gaussianKernel], carried in `Double` and normalised in `Double`.
     *
     * [gaussianKernel] rounds each `exp` to `Float` and then scales by a `Float` reciprocal, so its
     * taps sit up to 1 ulp from the exactly-normalised value. TypeScript has no `Float` arithmetic
     * and cannot reproduce that rounding sequence: its taps land on the correctly-rounded float32
     * instead, and 4 of the 7 taps at σ=1 differ from Kotlin's by exactly 1 ulp (1.5e-8). That is
     * invisible in a blur and decisive in [EdgeDog.xdog], where the 1/(1−τ) rescale and the soft
     * threshold together multiply a tap error by ~1000 — it was 1.18e-4 of the 1.17e-4 total
     * disagreement on the `gradient-blob` fixture, the single largest term.
     *
     * Building the taps in `Double` removes the rounding sequence rather than trying to mirror it, so
     * both engines evaluate the same expression in the same arithmetic and agree to the last bit.
     *
     * [sigma] is a `Float` on purpose, not a `Double`: it is the same knob [gaussianKernel] takes, and
     * `ceil(3σ)` and `exp(−d²/2σ²)` must be evaluated from the *same* σ in both engines. See §7.3's
     * note on quantising the knobs to float32 on entry.
     */
    fun gaussianKernelDouble(sigma: Float): DoubleArray {
        if (sigma <= 0.05f) return doubleArrayOf(0.0, 1.0, 0.0)
        val r = max(1, ceil(3.0 * sigma).toInt())
        val n = 2 * r + 1
        val k = DoubleArray(n)
        val inv = -1.0 / (2.0 * sigma * sigma)
        var sum = 0.0
        for (i in 0 until n) {
            val d = (i - r).toDouble()
            val v = exp(d * d * inv)
            k[i] = v
            sum += v
        }
        val norm = 1.0 / sum
        for (i in 0 until n) k[i] *= norm
        return k
    }

    /**
     * Separable Gaussian blur, edge-clamped.
     *
     * σ ≤ 0.05 returns a copy. A one-tap "blur" is not free — it is two full passes over the image
     * that provably change nothing, and the pipeline asks for a blur with σ=0 whenever the user
     * turns smoothing off.
     */
    fun gaussianBlur(src: GrayF, sigma: Float): GrayF {
        if (sigma <= 0.05f) return src.copy()
        val k = gaussianKernel(sigma)
        return separable(src, k, k)
    }

    /**
     * [gaussianBlur] with [gaussianKernelDouble]'s taps, a `Double` accumulator and a `Double`
     * intermediate plane — nothing on the path is rounded to `Float`.
     *
     * **This exists for one consumer shape: a filter that subtracts two nearly equal blurs and then
     * divides by the small remainder.** It is not a better [gaussianBlur] to reach for by default. It
     * costs two `Double` planes instead of two `Float` ones, and for a well-conditioned consumer the
     * extra digits are discarded by the `Float` store on the way out.
     *
     * Returning a raw [DoubleArray] rather than a [GrayF] is the point: a [GrayF] would round the
     * result back to `Float` and throw away exactly the digits this function exists to keep. At
     * τ = 0.98 the `Float` store of the two blur planes alone is worth 2.5e-5 of cross-engine
     * disagreement in [EdgeDog.xdog] — under the §14 tolerance, but four fifths of the budget for
     * nothing. The caller combines the two planes in `Double` and stores once.
     *
     * @return `width * height` samples, row-major, in the same layout as [GrayF.data].
     */
    fun gaussianBlurDouble(src: GrayF, sigma: Float): DoubleArray {
        val k = gaussianKernelDouble(sigma)
        val w = src.width
        val h = src.height
        val s = src.data
        val r = k.size / 2
        // One straightforward clamped loop per axis, with no interior fast path. `separable` has one
        // and it is worth having there; here the two engines have to accumulate the *same* taps in the
        // *same* order for the result to be reproducible bit for bit, and a second code path is a
        // second chance for the two to drift apart.
        // Both passes split over rows, and the split changes nothing: every destination sample is a
        // sum of the same taps in the same order over source values no share writes to. The two
        // passes must stay separated, though, and they are — `Parallel.rows` returns only when every
        // share has finished, so the vertical pass starts on a `mid` plane that is complete.
        val mid = DoubleArray(w * h)
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY until toY) {
                val base = y * w
                for (x in 0 until w) {
                    var acc = 0.0
                    for (i in k.indices) {
                        var xx = x - r + i
                        if (xx < 0) xx = 0
                        if (xx > w - 1) xx = w - 1
                        acc += k[i] * s[base + xx]
                    }
                    mid[base + x] = acc
                }
            }
        }
        val out = DoubleArray(w * h)
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY until toY) {
                val base = y * w
                for (x in 0 until w) {
                    var acc = 0.0
                    for (i in k.indices) {
                        var yy = y - r + i
                        if (yy < 0) yy = 0
                        if (yy > h - 1) yy = h - 1
                        acc += k[i] * mid[yy * w + x]
                    }
                    out[base + x] = acc
                }
            }
        }
        return out
    }

    /**
     * Box blur of radius [radius], edge-clamped, via a running sum so cost is independent of radius.
     * A non-positive radius returns a copy.
     *
     * **Deliberately single-threaded**, unlike [separable] and [gaussianBlurDouble], and the reason is
     * worth stating because the obvious reading is the wrong one. The illegal split is *along* a line:
     * a share starting mid-row would have to re-seed the running sum, reaching the same total by a
     * different sequence of adds and subtracts, which in floating point is a different total — the
     * `Double` accumulator below bounds that drift precisely so it is reproducible, and a split that
     * moved it would undo that. The legal split is *across* lines (each row owns its own `sum`, each
     * column its own), and it is simply not worth taking: the only callers are adaptive-mean
     * thresholding and the 64×64 saliency proxy, neither of which is on the path this engine spends
     * its minutes in.
     */
    fun boxBlur(src: GrayF, radius: Int): GrayF {
        if (radius <= 0) return src.copy()
        val w = src.width
        val h = src.height
        val r = radius
        val s = src.data
        val tmp = FloatArray(w * h)
        val inv = 1.0 / (2 * r + 1)
        for (y in 0 until h) {
            val base = y * w
            // Double accumulator: a float running sum drifts over a few thousand add/subtract steps
            // and the drift is a slow horizontal ramp, which is exactly what a later contrast
            // stretch amplifies into a visible gradient.
            var sum = 0.0
            for (i in -r..r) {
                val xx = if (i < 0) 0 else if (i > w - 1) w - 1 else i
                sum += s[base + xx]
            }
            tmp[base] = (sum * inv).toFloat()
            for (x in 1 until w) {
                val addX = if (x + r > w - 1) w - 1 else x + r
                val subX = if (x - r - 1 < 0) 0 else x - r - 1
                sum += s[base + addX] - s[base + subX]
                tmp[base + x] = (sum * inv).toFloat()
            }
        }
        val out = FloatArray(w * h)
        for (x in 0 until w) {
            var sum = 0.0
            for (i in -r..r) {
                val yy = if (i < 0) 0 else if (i > h - 1) h - 1 else i
                sum += tmp[yy * w + x]
            }
            out[x] = (sum * inv).toFloat()
            for (y in 1 until h) {
                val addY = if (y + r > h - 1) h - 1 else y + r
                val subY = if (y - r - 1 < 0) 0 else y - r - 1
                sum += tmp[addY * w + x] - tmp[subY * w + x]
                out[y * w + x] = (sum * inv).toFloat()
            }
        }
        return GrayF(w, h, out)
    }

    /**
     * Separable convolution: [kx] horizontally then [ky] vertically, edge-clamped.
     * Kernels are expected to be odd-length; the anchor is `size/2`. An empty kernel skips that axis.
     *
     * **The tap accumulator is `Float`, and widening it is not the free improvement it looks like.**
     * It was tried, to close the XDoG parity gap (`G(σ) − τ·G(kσ)` rescaled by `1/(1−τ)` is a
     * catastrophic cancellation followed by a 50× amplification, so a `Float` sum's rounding arrives
     * downstream at roughly the §14 tolerance). On its own it closed less than half the gap — the
     * larger term was [gaussianKernel]'s `Float` taps, which the two engines round differently — and
     * it *did* flip a zero-crossing in `EdgeLog.detect`, where a more accurate blur moved a value that
     * sits within an ulp of zero onto the other side of it and changed the mask.
     *
     * So the precision of this function is load-bearing for consumers that compare against zero, and
     * changing it is a cross-engine decision rather than a local one. A consumer that needs more
     * precision takes [gaussianBlurDouble] — its own widened path, with no `Float` rounding anywhere
     * on it — instead of moving the floor underneath every other stage. That is what `EdgeDog.xdog`
     * and FDoG do.
     */
    fun separable(src: GrayF, kx: FloatArray, ky: FloatArray): GrayF {
        val w = src.width
        val h = src.height
        val mid = if (kx.isEmpty()) src.data.copyOf() else FloatArray(w * h)
        // Both passes split over rows. The horizontal one is obviously row-local; the vertical one
        // reads `mid` up to `ry` rows either side of the row it writes, which is safe for a different
        // reason — `mid` is finished. `Parallel.rows` does not return until every share has run, so the
        // call below is the barrier between the two passes, and a share of the vertical pass can read
        // any row of `mid` it likes because no share is still writing one.
        if (kx.isNotEmpty()) {
            val rx = kx.size / 2
            val s = src.data
            Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
                for (y in fromY until toY) {
                    val base = y * w
                    for (x in 0 until w) {
                        var acc = 0f
                        if (x >= rx && x + rx < w) {
                            var i = 0
                            var idx = base + x - rx
                            while (i < kx.size) {
                                acc += kx[i] * s[idx]
                                i++
                                idx++
                            }
                        } else {
                            for (i in kx.indices) {
                                var xx = x - rx + i
                                if (xx < 0) xx = 0
                                if (xx > w - 1) xx = w - 1
                                acc += kx[i] * s[base + xx]
                            }
                        }
                        mid[base + x] = acc
                    }
                }
            }
        }
        if (ky.isEmpty()) return GrayF(w, h, mid)
        val ry = ky.size / 2
        val out = FloatArray(w * h)
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY until toY) {
                val base = y * w
                val interior = y >= ry && y + ry < h
                for (x in 0 until w) {
                    var acc = 0f
                    if (interior) {
                        var i = 0
                        var idx = base + x - ry * w
                        while (i < ky.size) {
                            acc += ky[i] * mid[idx]
                            i++
                            idx += w
                        }
                    } else {
                        for (i in ky.indices) {
                            var yy = y - ry + i
                            if (yy < 0) yy = 0
                            if (yy > h - 1) yy = h - 1
                            acc += ky[i] * mid[yy * w + x]
                        }
                    }
                    out[base + x] = acc
                }
            }
        }
        return GrayF(w, h, out)
    }

    /** 3×3 convolution with [k] in row-major order (`k[0]` is the top-left tap), edge-clamped. */
    fun convolve3(src: GrayF, k: FloatArray): GrayF {
        require(k.size == 9) { "convolve3(): kernel must be 9 taps, got ${k.size}" }
        return apply3x3(src, k, 1f)
    }

    /**
     * First derivatives of [src].
     *
     * Scharr is the default because its `[3 10 3]` weighting is markedly closer to rotationally
     * symmetric than Sobel's `[1 2 1]`. Sobel's directional bias is small in isolation but it
     * survives every later stage and emerges as staircasing along diagonals in the traced vector.
     */
    fun gradients(src: GrayF, op: GradientOp = GradientOp.SCHARR): Gradients {
        return if (op == GradientOp.SCHARR) {
            Gradients(
                apply3x3(src, SCHARR_X, 1f / 32f),
                apply3x3(src, SCHARR_Y, 1f / 32f),
            )
        } else {
            Gradients(
                apply3x3(src, SOBEL_X, 1f / 8f),
                apply3x3(src, SOBEL_Y, 1f / 8f),
            )
        }
    }

    /** 8-neighbour Laplacian (`[1 1 1 / 1 -8 1 / 1 1 1]`), edge-clamped, unnormalised. */
    fun laplacian(src: GrayF): GrayF = apply3x3(src, LAPLACIAN_8, 1f)

    /**
     * Summed-area table of [src], `(width+1) * (height+1)` with a zero first row and column, so
     * `sat[(y+1)*(w+1) + (x+1)]` is the sum of the rectangle from the origin through `(x,y)`.
     *
     * `DoubleArray` and not `FloatArray`: a float accumulator loses the low bits once the running
     * total passes a few million and the error shows up as horizontal banding in every SAT-based
     * threshold, which is very hard to attribute back to the table.
     *
     * **Not parallelisable at all**, and this is the genuine case rather than [boxBlur]'s judgement
     * call: row `y` of the table is defined as row `y-1` plus this row's prefix, so there is a true
     * cross-row dependency and no share can start before its predecessor has finished. (The textbook
     * fix — a parallel prefix scan — reassociates the additions, which is exactly what a stage held to
     * bit-identity may not do.)
     */
    fun summedAreaTable(src: GrayF): DoubleArray {
        val w = src.width
        val h = src.height
        val sw = w + 1
        val sat = DoubleArray(sw * (h + 1))
        val s = src.data
        for (y in 0 until h) {
            var rowSum = 0.0
            val srcBase = y * w
            val cur = (y + 1) * sw
            val prev = y * sw
            for (x in 0 until w) {
                rowSum += s[srcBase + x]
                sat[cur + x + 1] = sat[prev + x + 1] + rowSum
            }
        }
        return sat
    }

    /**
     * Sum over the **inclusive** rectangle `[x0..x1] x [y0..y1]` of the image [sat] was built from,
     * where [w] and [h] are the *image* dimensions. The rectangle is clamped into the image and an
     * empty rectangle returns 0.
     */
    fun rectSum(sat: DoubleArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val ax = if (x0 < 0) 0 else x0
        val ay = if (y0 < 0) 0 else y0
        val bx = if (x1 > w - 1) w - 1 else x1
        val by = if (y1 > h - 1) h - 1 else y1
        if (bx < ax || by < ay) return 0.0
        val sw = w + 1
        val top = ay * sw
        val bot = (by + 1) * sw
        return sat[bot + bx + 1] - sat[top + bx + 1] - sat[bot + ax] + sat[top + ax]
    }

    private val SCHARR_X = floatArrayOf(3f, 0f, -3f, 10f, 0f, -10f, 3f, 0f, -3f)
    private val SCHARR_Y = floatArrayOf(3f, 10f, 3f, 0f, 0f, 0f, -3f, -10f, -3f)
    private val SOBEL_X = floatArrayOf(1f, 0f, -1f, 2f, 0f, -2f, 1f, 0f, -1f)
    private val SOBEL_Y = floatArrayOf(1f, 2f, 1f, 0f, 0f, 0f, -1f, -2f, -1f)
    private val LAPLACIAN_8 = floatArrayOf(1f, 1f, 1f, 1f, -8f, 1f, 1f, 1f, 1f)

    /**
     * The nine taps are accumulated in **double** and the sum narrowed to Float exactly once, which is
     * what JavaScript does for free and therefore what the TypeScript engine already did (§3).
     *
     * It is not only a parity concern, it is the difference between right and wrong on a flat region.
     * ∇² of a constant is *exactly* zero; in a Float accumulator `Σ 8c − 8c` rounds for most values of
     * `c` and leaves a residue of about one ulp — signed. §7.5 defines an edge as a strict sign change
     * precisely so the zero plateau beyond a blur tail is not one, and that argument needs a bit-zero
     * plateau to stand on: with the Float accumulator, `EdgeLog.detect` marked a false edge one pixel
     * wide at the outer end of every tail, on whichever side the residue happened to fall. It was
     * invisible only because `color.toGray`'s own Float rounding was, for the `step-edge` fixture,
     * landing on a `c` whose `8c` happened to be exact.
     *
     * Every kernel here scales by an exact power of two (1, 1/8, 1/32), so scaling the double sum is
     * identical to folding the scale into the taps, which is how the TypeScript side spells it.
     */
    private fun apply3x3(src: GrayF, k: FloatArray, scale: Float): GrayF {
        val w = src.width
        val h = src.height
        val s = src.data
        val out = FloatArray(w * h)
        val k0 = k[0].toDouble(); val k1 = k[1].toDouble(); val k2 = k[2].toDouble()
        val k3 = k[3].toDouble(); val k4 = k[4].toDouble(); val k5 = k[5].toDouble()
        val k6 = k[6].toDouble(); val k7 = k[7].toDouble(); val k8 = k[8].toDouble()
        val sc = scale.toDouble()
        // The interior is rows 1..h-2, so the share range is offset by one rather than being
        // `rows(h)`. A share must not be handed row 0 or row h-1: those go through the clamped reader
        // below, and computing them twice by two different code paths is how the two engines would
        // end up disagreeing about a border pixel.
        Parallel.chunks(h - 2, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY + 1..toY) {
                var i = y * w + 1
                val end = y * w + w - 1
                while (i < end) {
                    val a = i - w
                    val b = i + w
                    out[i] = ((k0 * s[a - 1] + k1 * s[a] + k2 * s[a + 1] +
                        k3 * s[i - 1] + k4 * s[i] + k5 * s[i + 1] +
                        k6 * s[b - 1] + k7 * s[b] + k8 * s[b + 1]) * sc).toFloat()
                    i++
                }
            }
        }
        // Border ring (and the whole image when it is thinner than 3 px) through the clamped reader.
        // Left sequential: it is O(w + h) work, and the shares above have finished by now, so nothing
        // here can be racing an interior write.
        for (y in 0 until h) {
            if (y == 0 || y == h - 1) {
                for (x in 0 until w) out[y * w + x] = tap3x3(src, k, scale, x, y)
            } else {
                out[y * w] = tap3x3(src, k, scale, 0, y)
                if (w > 1) out[y * w + w - 1] = tap3x3(src, k, scale, w - 1, y)
            }
        }
        return GrayF(w, h, out)
    }

    private fun tap3x3(src: GrayF, k: FloatArray, scale: Float, x: Int, y: Int): Float =
        (
            (
                k[0].toDouble() * src.clamped(x - 1, y - 1) +
                    k[1].toDouble() * src.clamped(x, y - 1) +
                    k[2].toDouble() * src.clamped(x + 1, y - 1) +
                    k[3].toDouble() * src.clamped(x - 1, y) +
                    k[4].toDouble() * src.clamped(x, y) +
                    k[5].toDouble() * src.clamped(x + 1, y) +
                    k[6].toDouble() * src.clamped(x - 1, y + 1) +
                    k[7].toDouble() * src.clamped(x, y + 1) +
                    k[8].toDouble() * src.clamped(x + 1, y + 1)
                ) * scale.toDouble()
            ).toFloat()
}
