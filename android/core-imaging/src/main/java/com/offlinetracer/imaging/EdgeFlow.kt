package com.offlinetracer.imaging

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Flow-based difference of Gaussians with Edge Tangent Flow — Kang, Lee & Chui's coherent line
 * drawing (ALGORITHMS §7.3). This is the quality tier: instead of deciding about every pixel
 * independently the way Canny and DoG do, it builds a smooth field of edge tangents and filters
 * *across* it and *along* it, which is what turns a stipple of detections into long, deliberate
 * strokes.
 *
 * Three pieces, and each has one detail that silently ruins the result if it is left out:
 *
 *  - **Structure tensor** ([structureTensorFlow]) — the minor eigenvector of the smoothed gradient
 *    outer product is the edge tangent. Taking the major one gives the gradient direction and the
 *    output looks like a photographic negative of what you wanted.
 *  - **ETF refinement** ([refineEtf]) — the `φ = sign(t(x)·t(y))` term is mandatory. A tangent is a
 *    *director*, `t` and `−t` mean the same line, and averaging neighbours without first flipping
 *    them into a common half-plane makes the field cancel itself and collapse to zero within one
 *    iteration.
 *  - **FDoG** ([fdog]) — the along-flow walk must flip the tangent when it disagrees with the
 *    direction of travel, or the walk reverses and integrates the same three pixels forever.
 */
object EdgeFlow {

    /**
     * A unit-length tangent per pixel plus the normalised gradient magnitude that generated it.
     *
     * [tx]/[ty] are **directors**: `(tx, ty)` and `(−tx, −ty)` describe the same edge, so any code
     * that compares two tangents must take `|dot|` or align the signs first. [magnitude] is the
     * gradient magnitude scaled so the image maximum is 1 — that normalisation is what makes the
     * ETF's `wm` term scale-free, so a faint pencil sketch and a black ink drawing refine the same.
     */
    class FlowField(
        @JvmField val width: Int,
        @JvmField val height: Int,
        @JvmField val tx: FloatArray,
        @JvmField val ty: FloatArray,
        @JvmField val magnitude: FloatArray,
    ) {
        init {
            require(width > 0 && height > 0) { "FlowField must be non-empty, got ${width}x$height" }
            require(tx.size == width * height && ty.size == width * height && magnitude.size == width * height) {
                "FlowField arrays must all be ${width * height} long"
            }
        }

        /** @return an independent copy; the arrays are never shared with the original. */
        fun copy(): FlowField = FlowField(width, height, tx.copyOf(), ty.copyOf(), magnitude.copyOf())
    }

    /** Every knob of [coherentLineDrawing] in one value, defaults matching the reference paper. */
    data class FlowParams(
        val tensorSigma: Float = 2f,
        val etfIterations: Int = 3,
        val etfRadius: Int = 5,
        val sigmaC: Float = 1f,
        val sigmaM: Float = 3f,
        val tau: Float = 0.99f,
        val fdogIterations: Int = 3,
        val epsilon: Float = 0.5f,
        val phi: Float = 20f,
    )

    /**
     * Builds the initial tangent field from the structure tensor
     * `J = Gσ * [gx² gxgy; gxgy gy²]`, whose **minor** eigenvector is the local edge tangent.
     *
     * The 2×2 symmetric eigen-problem is solved in closed form (and in double precision, because the
     * discriminant is a difference of similar squares and cancels badly in float on smooth regions).
     * Of the two textbook eigenvector expressions `(Jxy, λ−Jxx)` and `(λ−Jyy, Jxy)` the one with the
     * larger norm is used, since each degenerates in the case the other handles. Where the tensor
     * itself vanishes — a genuinely flat region — the tangent is set to `(1, 0)` with magnitude 0
     * rather than left at zero length, so downstream walks always have a finite direction to step.
     *
     * @param sigma Gaussian smoothing of the tensor components; larger = smoother, more coherent
     *   flow that ignores fine structure.
     * @return a [FlowField] the size of [src] with unit-length tangents.
     */
    fun structureTensorFlow(src: GrayF, sigma: Float = 2f): FlowField {
        val w = src.width
        val h = src.height
        val n = w * h
        val g = Convolve.gradients(src, GradientOp.SCHARR)
        val gx = g.gx.data
        val gy = g.gy.data

        val exx = FloatArray(n)
        val exy = FloatArray(n)
        val eyy = FloatArray(n)
        val mag = FloatArray(n)
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) {
                val a = gx[i]
                val b = gy[i]
                exx[i] = a * a
                exy[i] = a * b
                eyy[i] = b * b
                mag[i] = sqrt(a * a + b * b)
            }
        }
        // The maximum is a separate sequential scan, not folded into the share above. A `var maxMag`
        // written from several threads is a shared accumulator, and while a maximum happens to be
        // order-independent for floats (NaN loses either way, since `NaN > x` is false), keeping it out
        // of the parallel body means there is nothing to reason about at all. It costs nothing: one
        // load-and-compare per pixel runs at memory speed, and the three blurs below are an order of
        // magnitude more work.
        var maxMag = 0f
        for (i in 0 until n) {
            val m = mag[i]
            if (m > maxMag) maxMag = m
        }
        val inv = if (maxMag > 1e-20f) 1f / maxMag else 0f
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) mag[i] = mag[i] * inv
        }

        val s = if (sigma < 0f) 0f else sigma
        val jxx = Convolve.gaussianBlur(GrayF(w, h, exx), s).data
        val jxy = Convolve.gaussianBlur(GrayF(w, h, exy), s).data
        val jyy = Convolve.gaussianBlur(GrayF(w, h, eyy), s).data

        val tx = FloatArray(n)
        val ty = FloatArray(n)
        // One closed-form eigen-solve per pixel, reading three finished planes and writing only its own
        // index — the textbook shape for a split, and the arithmetic is untouched by it.
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) {
                val a = jxx[i].toDouble()
                val b = jxy[i].toDouble()
                val c = jyy[i].toDouble()
                val diff = a - c
                val disc = sqrt(diff * diff + 4.0 * b * b)
                val lmin = 0.5 * (a + c - disc)

                var vx = b
                var vy = lmin - a
                val n1 = vx * vx + vy * vy
                val ux = lmin - c
                val uy = b
                val n2 = ux * ux + uy * uy
                var norm = n1
                if (n2 > n1) {
                    vx = ux
                    vy = uy
                    norm = n2
                }
                if (norm > 1e-30) {
                    val len = sqrt(norm)
                    // Canonical half-plane: ty > 0, or ty == 0 and tx >= 0.
                    //
                    // A tangent is a DIRECTOR — t and -t describe the same line — and which of the two
                    // an eigen-solver hands back is an accident of the expressions it evaluated and the
                    // signs of `b` and `lmin - a`. Leaving that accident in the output makes the field
                    // unreproducible between implementations: the Kotlin and TypeScript engines
                    // returned (0, -1) and (0, +1) for the same vertical edge, an error of 2.0 against
                    // a 1e-4 parity tolerance, for two answers that are geometrically identical.
                    //
                    // Canonicalising costs nothing downstream because every consumer already treats the
                    // sign as meaningless and re-derives it locally: [refineEtf] carries the explicit
                    // `sign(t(x)·t(y))` term that exists precisely to resolve this, and the FDoG
                    // streamline walk flips the tangent whenever it opposes the direction of travel.
                    // What it buys is a field that is a function of the image alone.
                    var nx = vx / len
                    var ny = vy / len
                    if (ny < 0.0 || (ny == 0.0 && nx < 0.0)) {
                        nx = -nx
                        ny = -ny
                    }
                    tx[i] = nx.toFloat()
                    ty[i] = ny.toFloat()
                } else {
                    // No dominant orientation at all. (1, 0) is already canonical.
                    tx[i] = 1f
                    ty[i] = 0f
                }
            }
        }
        return FlowField(w, h, tx, ty, mag)
    }

    /**
     * Iterative ETF refinement (Kang et al.):
     * `t'(x) = normalize( Σ φ(x,y)·t(y)·ws·wm·wd )` over the disc of radius `r` around `x`, with
     * `φ = sign(t(x)·t(y))`, `wm = (1 + tanh(ĝ(y) − ĝ(x)))/2` (η = 1) and `wd = |t(x)·t(y)|`.
     *
     * `ws` is the disc membership itself (`‖x−y‖ < r`). The centre pixel is included, which is what
     * keeps the field from drifting when a neighbourhood is evenly balanced. `wm` biases each pixel
     * toward its stronger-gradient neighbours, so the flow of a weak edge lines up with the strong
     * edge it belongs to; `wd` stops a perpendicular edge from bleeding across a corner.
     *
     * The neighbourhood is edge-clamped (analytic border policy), and a pixel whose weighted sum
     * cancels to zero keeps its previous tangent rather than becoming undefined.
     *
     * @param iterations `≤ 0` returns a copy; capped at 16.
     * @param radius `≤ 0` returns a copy; capped at 32 (a 65×65 neighbourhood is already 3 000
     *   neighbour pairs per pixel per iteration).
     * @return a new [FlowField]; [field] is not modified.
     */
    fun refineEtf(field: FlowField, iterations: Int = 3, radius: Int = 5): FlowField {
        if (iterations <= 0 || radius <= 0) return field.copy()
        val iters = if (iterations > 16) 16 else iterations
        val r = if (radius > 32) 32 else radius
        val w = field.width
        val h = field.height
        val n = w * h
        val mag = field.magnitude

        val r2 = r * r
        var count = 0
        for (dy in -r..r) {
            for (dx in -r..r) if (dx * dx + dy * dy < r2) count++
        }
        val offX = IntArray(count)
        val offY = IntArray(count)
        val offFlat = IntArray(count)
        var k = 0
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy < r2) {
                    offX[k] = dx
                    offY[k] = dy
                    offFlat[k] = dy * w + dx
                    k++
                }
            }
        }

        // wm = (1 + tanh(ĝ(y) − ĝ(x)))/2 = e^2ĝ(y) / (e^2ĝ(y) + e^2ĝ(x)), which is an identity, not
        // an approximation: (1+tanh z)/2 = e^2z/(e^2z + 1), then multiply through by e^2ĝ(x).
        // Precomputing e^2ĝ per pixel turns the innermost operation of the most expensive stage in
        // the engine from a transcendental into one divide. On a 1.2 MP image that is the difference
        // between 25 seconds and 2, and the values agree to float precision.
        val expMag = FloatArray(n)
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) expMag[i] = exp(2f * mag[i])
        }

        var curX = field.tx.copyOf()
        var curY = field.ty.copyOf()
        var dstX = FloatArray(n)
        var dstY = FloatArray(n)

        var it = 0
        while (it < iters) {
            // **The iteration loop stays sequential and the double buffer keeps swapping.** Only the
            // rows *within* one iteration are split: every pixel of iteration k reads the whole
            // `src*` field from iteration k-1 and writes only its own index of `out*`, so shares are
            // disjoint in what they write and read nothing anyone is writing. Splitting the iterations
            // instead — or dropping the double buffer so a share reads a neighbour's fresh answer —
            // makes the result depend on the row band boundaries, which is the whole failure mode this
            // change exists to avoid.
            //
            // The four planes are bound to locals per iteration rather than captured: `curX` and
            // friends are `var`s, and capturing a `var` in a lambda boxes it into a `Ref` object that
            // is then dereferenced on the innermost path of the most expensive loop in the engine.
            val srcX = curX
            val srcY = curY
            val outX = dstX
            val outY = dstY
            Parallel.rows(h, Parallel.ROWS_NEIGHBOURHOOD) { fromY, toY ->
                for (y in fromY until toY) {
                    val row = y * w
                    val rowInterior = y >= r && y < h - r
                    for (x in 0 until w) {
                        val i = row + x
                        val t0x = srcX[i]
                        val t0y = srcY[i]
                        val e0 = expMag[i]
                        // Away from the border no coordinate can leave the image, so the neighbour
                        // index is one add instead of two clamps and a multiply. At r = 5 that fast
                        // path takes ~98% of the pixels of any real image.
                        val interior = rowInterior && x >= r && x < w - r
                        var sx = 0f
                        var sy = 0f
                        var j = 0
                        while (j < count) {
                            val q: Int
                            if (interior) {
                                q = i + offFlat[j]
                            } else {
                                var nx = x + offX[j]
                                var ny = y + offY[j]
                                if (nx < 0) nx = 0 else if (nx > w - 1) nx = w - 1
                                if (ny < 0) ny = 0 else if (ny > h - 1) ny = h - 1
                                q = ny * w + nx
                            }
                            j++
                            val t1x = srcX[q]
                            val t1y = srcY[q]
                            val dot = t0x * t1x + t0y * t1y
                            val wd = if (dot < 0f) -dot else dot
                            if (wd <= 0f) continue
                            val e1 = expMag[q]
                            val wm = e1 / (e1 + e0)
                            val ww = if (dot >= 0f) wm * wd else -(wm * wd)
                            sx += t1x * ww
                            sy += t1y * ww
                        }
                        val len = sqrt(sx * sx + sy * sy)
                        if (len > 1e-12f) {
                            outX[i] = sx / len
                            outY[i] = sy / len
                        } else {
                            outX[i] = t0x
                            outY[i] = t0y
                        }
                    }
                }
            }
            val swapX = curX
            curX = dstX
            dstX = swapX
            val swapY = curY
            curY = dstY
            dstY = swapY
            it++
        }
        return FlowField(w, h, curX, curY, mag.copyOf())
    }

    /**
     * Flow-based DoG.
     *
     * Per iteration: a 1-D DoG **across** the flow (a straight walk along the gradient direction,
     * `±ceil(3·1.6σc)` samples, bilinearly interpolated), then a Gaussian integration **along** the
     * flow (a unit-step Euler walk down the streamline, re-reading the tangent at every step and
     * flipping it whenever it opposes the direction of travel). All but the last iteration feed the
     * thresholded result back as `min(image, ink)` — Kang's superimposition — which is what sharpens
     * a hesitant edge into a committed line over 2–3 rounds.
     *
     * **Returns ink density in 0..1 where 1 = paper and 0 = full ink**, the same polarity as
     * [EdgeDog.xdog], and for the same reason the response is rescaled by `1/(1−τ)` before the soft
     * threshold: it puts [epsilon] in intensity units so the documented default of 0.5 means "ink
     * below mid-grey" instead of "the entire page is black". See [EdgeDog.xdog] for the derivation.
     *
     * The along-flow walk **stops at the image border** instead of clamping. Clamping would make the
     * walk sit on the same edge pixel for the rest of its samples and smear a bright band around the
     * whole frame; the accumulated weights are renormalised so a truncated walk is unbiased.
     *
     * **Every intermediate here is Double, and only the arrays are Float** — the same decision, for the
     * same reason, as the eigen-solve in [structureTensorFlow]. At the default τ = 0.99 this filter
     * subtracts two nearly identical blurs and multiplies the remainder by 100, so a Float accumulator's
     * last two digits become the answer's first two: ~1e-5 of noise in the value ε is compared against.
     * The soft threshold then multiplies that by up to φ. On the `gradient-blob` fixture a Float
     * accumulator put one pixel 1.2e-4 away from the TypeScript engine — over the §14 tolerance — for an
     * identical algorithm, and TypeScript has no Float arithmetic to meet it half way. Double costs
     * nothing on any JVM (the FPU is 64-bit either way) and removes the whole class of disagreement.
     *
     * @param field must be the same size as [src] — it is the flow of *this* image.
     * @param sigmaC width of the fine Gaussian across the flow (the coarse one is 1.6σc).
     * @param sigmaM width of the Gaussian along the flow; this is the "how long is a stroke" knob.
     * @param tau how much of the coarse scale is subtracted, clamped to 0..0.999.
     * @param iterations feedback rounds, clamped to 1..16.
     * @return a new [GrayF] the size of [src], every value in 0..1.
     */
    fun fdog(
        src: GrayF,
        field: FlowField,
        sigmaC: Float = 1f,
        sigmaM: Float = 3f,
        tau: Float = 0.99f,
        iterations: Int = 3,
        epsilon: Float = 0.5f,
        phi: Float = 20f,
    ): GrayF {
        require(field.width == src.width && field.height == src.height) {
            "fdog(): flow ${field.width}x${field.height} does not match image ${src.width}x${src.height}"
        }
        val w = src.width
        val h = src.height
        val iters = if (iterations < 1) 1 else if (iterations > 16) 16 else iterations
        val sc = clampSigma(sigmaC, 16f)
        val sm = clampSigma(sigmaM, 32f)
        val ss = 1.6f * sc
        val t = if (tau < 0f) 0f else if (tau > 0.999f) 0.999f else tau

        val rc = radiusFor(ss)
        val kernAcross = DoubleArray(2 * rc + 1)
        run {
            val gc = DoubleArray(2 * rc + 1)
            val gs = DoubleArray(2 * rc + 1)
            val dc = 2.0 * sc * sc
            val ds = 2.0 * ss * ss
            var sumC = 0.0
            var sumS = 0.0
            for (i in -rc..rc) {
                val fi = i.toDouble()
                val a = exp(-(fi * fi) / dc)
                val b = exp(-(fi * fi) / ds)
                gc[i + rc] = a
                gs[i + rc] = b
                sumC += a
                sumS += b
            }
            // Each Gaussian is normalised over its *truncated* support, so a constant region of
            // intensity I answers exactly (1−τ)·I and the 1/(1−τ) rescale below is exact rather than
            // approximately right — which matters, because ε is then compared against it.
            for (i in kernAcross.indices) kernAcross[i] = gc[i] / sumC - t * (gs[i] / sumS)
        }
        val scale = 1.0 / (1.0 - t)

        val rm = radiusFor(sm)
        val kernAlong = DoubleArray(rm + 1)
        run {
            val dm = 2.0 * sm * sm
            for (i in 0..rm) {
                val fi = i.toDouble()
                kernAlong[i] = exp(-(fi * fi) / dm)
            }
        }

        val work = src.data.copyOf()
        val ink = FloatArray(work.size)
        var pass = 0
        while (true) {
            val across = dogAcross(work, w, h, field, kernAcross, rc, scale)
            val along = blurAlong(across, w, h, field, kernAlong, rm)
            Parallel.chunks(ink.size, Parallel.PIXELS_MAP) { from, to ->
                for (i in from until to) ink[i] = EdgeDog.softThreshold(along[i], epsilon, phi)
            }
            pass++
            if (pass >= iters) break
            // Kang's superimposition, and it stays a **separate full pass after a barrier**. Folding it
            // into the walk above would let a pixel of pass k read a `work` value another share had
            // already darkened for pass k+1, and the answer would then depend on which share got there
            // first. `Parallel.chunks` returning is that barrier: `ink` is complete before a single
            // element of `work` moves.
            Parallel.chunks(work.size, Parallel.PIXELS_MAP) { from, to ->
                for (i in from until to) if (ink[i] < work[i]) work[i] = ink[i]
            }
        }
        return GrayF(w, h, ink)
    }

    /**
     * Convenience for the whole tier: structure tensor → ETF refinement → FDoG.
     *
     * @return a new [GrayF] the size of [src]: **ink density, 1 = paper**, as [fdog].
     */
    fun coherentLineDrawing(src: GrayF, params: FlowParams): GrayF {
        val field = refineEtf(
            structureTensorFlow(src, params.tensorSigma),
            params.etfIterations,
            params.etfRadius,
        )
        return fdog(
            src, field, params.sigmaC, params.sigmaM, params.tau,
            params.fdogIterations, params.epsilon, params.phi,
        )
    }

    // ---------------------------------------------------------------------------------------

    /** Step 1: 1-D DoG along the gradient direction, i.e. perpendicular to the tangent. */
    private fun dogAcross(
        img: FloatArray,
        w: Int,
        h: Int,
        f: FlowField,
        kern: DoubleArray,
        radius: Int,
        scale: Double,
    ): FloatArray {
        val out = FloatArray(img.size)
        val tx = f.tx
        val ty = f.ty
        // Row-split: `img` and the flow are read-only here and `out` is written one index at a time, so
        // a share computes the same accumulation in the same order for its own pixels and nothing else.
        Parallel.rows(h, Parallel.ROWS_NEIGHBOURHOOD) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    // Perpendicular of the tangent; the walk is symmetric so the choice of which
                    // perpendicular does not matter, only that it is unit length.
                    val gx = ty[i].toDouble()
                    val gy = -tx[i].toDouble()
                    var acc = kern[radius] * img[i]
                    var s = 1
                    while (s <= radius) {
                        val ox = gx * s
                        val oy = gy * s
                        acc += kern[radius + s] * sample(img, w, h, x + ox, y + oy)
                        acc += kern[radius - s] * sample(img, w, h, x - ox, y - oy)
                        s++
                    }
                    out[i] = (acc * scale).toFloat()
                }
            }
        }
        return out
    }

    /** Step 2: Gaussian integration along the streamline of the flow, both ways from each pixel. */
    private fun blurAlong(
        img: FloatArray,
        w: Int,
        h: Int,
        f: FlowField,
        kern: DoubleArray,
        radius: Int,
    ): FloatArray {
        val out = FloatArray(img.size)
        val tx = f.tx
        val ty = f.ty
        val maxX = (w - 1).toDouble()
        val maxY = (h - 1).toDouble()
        // Row-split. The streamline walk wanders anywhere in the image — well outside the share's own
        // rows — which is exactly why this is safe: it only ever *reads*, and every value it reads
        // belongs to the finished `across` plane and the flow field, neither of which any share writes.
        Parallel.rows(h, Parallel.ROWS_NEIGHBOURHOOD) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    var acc = kern[0] * img[i]
                    var wsum = kern[0]

                    var side = 0
                    while (side < 2) {
                        var dx = if (side == 0) tx[i].toDouble() else -tx[i].toDouble()
                        var dy = if (side == 0) ty[i].toDouble() else -ty[i].toDouble()
                        var cx = x.toDouble()
                        var cy = y.toDouble()
                        var s = 1
                        while (s <= radius) {
                            cx += dx
                            cy += dy
                            if (cx < 0.0 || cy < 0.0 || cx > maxX || cy > maxY) break
                            val kw = kern[s]
                            acc += kw * sample(img, w, h, cx, cy)
                            wsum += kw
                            var jx = cx.roundToInt()
                            var jy = cy.roundToInt()
                            if (jx < 0) jx = 0 else if (jx > w - 1) jx = w - 1
                            if (jy < 0) jy = 0 else if (jy > h - 1) jy = h - 1
                            val q = jy * w + jx
                            // Nearest neighbour, not bilinear: a director field cannot be interpolated
                            // component-wise without first aligning signs, and an averaged (t, −t) pair
                            // is the zero vector, which would stall the walk.
                            var ntx = tx[q].toDouble()
                            var nty = ty[q].toDouble()
                            if (ntx * dx + nty * dy < 0.0) {
                                ntx = -ntx
                                nty = -nty
                            }
                            if (ntx * ntx + nty * nty < 1e-12) break
                            dx = ntx
                            dy = nty
                            s++
                        }
                        side++
                    }
                    out[i] = if (wsum > 0.0) (acc / wsum).toFloat() else img[i]
                }
            }
        }
        return out
    }

    /**
     * Edge-clamped bilinear read of a flat array; the analytic border policy (ALGORITHMS §0).
     *
     * Double in, Double out, over a Float array — see the precision note on [fdog]; the interpolation
     * itself is well conditioned, but its result feeds a difference that is amplified a hundredfold.
     *
     * The interior fast path is not premature: this is called ~30 times per pixel per FDoG
     * iteration, so the four clamps it skips are the single most executed branch in the engine.
     */
    private fun sample(d: FloatArray, w: Int, h: Int, fx: Double, fy: Double): Double {
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        if (ix >= 0 && iy >= 0 && ix < w - 1 && iy < h - 1) {
            val tx = fx - ix
            val ty = fy - iy
            val p = iy * w + ix
            val v00 = d[p].toDouble()
            val v10 = d[p + 1].toDouble()
            val v01 = d[p + w].toDouble()
            val v11 = d[p + w + 1].toDouble()
            val a = v00 + (v10 - v00) * tx
            val b = v01 + (v11 - v01) * tx
            return a + (b - a) * ty
        }
        val tx = fx - ix
        val ty = fy - iy
        val x0 = if (ix < 0) 0 else if (ix > w - 1) w - 1 else ix
        val y0 = if (iy < 0) 0 else if (iy > h - 1) h - 1 else iy
        val xp = ix + 1
        val yp = iy + 1
        val x1 = if (xp < 0) 0 else if (xp > w - 1) w - 1 else xp
        val y1 = if (yp < 0) 0 else if (yp > h - 1) h - 1 else yp
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

    private fun clampSigma(v: Float, hi: Float): Float =
        if (v.isNaN() || v < 0.05f) 0.05f else if (v > hi) hi else v

    /** Kernel half-width, `ceil(3σ)` — the same convention as every other Gaussian in the engine. */
    private fun radiusFor(sigma: Float): Int {
        val r = ceil(3f * sigma).toInt()
        return if (r < 1) 1 else r
    }
}
