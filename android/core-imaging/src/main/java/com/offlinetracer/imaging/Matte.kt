package com.offlinetracer.imaging

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Model-free background matting (ALGORITHMS §8).
 *
 * Three cheap cues, each honest about its limits, plus one function that fuses them
 * ([subjectMatte]). No matte is ever applied without the user accepting it — a matte that silently
 * deletes half of somebody's artwork is the worst failure this app can have — so the single-cue
 * functions return an alpha channel and nothing else, the fused one returns a [MatteResult] that
 * carries the evidence for its own trustworthiness, and every degenerate case deliberately returns
 * "keep everything" rather than "keep nothing".
 *
 * **Arithmetic convention for everything added for [subjectMatte].** Intermediate values are
 * accumulated in `Double` and narrowed to `Float` exactly once, at the point they are stored into a
 * [GrayF]. That is what JavaScript does for free, so it is what keeps this file's TypeScript mirror
 * (`web/src/engine/matte.ts`) on the same side of every `>= 0.5` decision — and this stage makes
 * several, so a 1e-7 rounding difference is not invisible here the way it is in a filter.
 */
object Matte {

    /**
     * ΔE76 radius that `tolerance = 1` maps to. Lab distance has no natural 0..1 range, and every
     * caller of [borderFlood] hands us a 0..1 slider value, so the mapping lives here once instead
     * of being re-invented (differently) at each call site. 100 is roughly black-to-white.
     */
    private const val MAX_LAB_DISTANCE = 100f

    /**
     * Luminance span below which the saliency proxy counts as flat and the spectral residual is
     * declared undefined rather than computed.
     *
     * Scale check: one 8-bit code value is `1/255 = 3.9e-3`, and the float round-off of the luma
     * conversion plus the resample is a few ulps of a 0..1 value, ~1e-7. `1e-5` sits two orders
     * above the round-off and 400× below one code value, so it rejects only proxies whose entire
     * dynamic range is round-off — never an image with content, however faint.
     */
    private const val FLAT_LUMA_SPAN = 1e-5f

    /**
     * Floods 8-connected inwards from all four borders and returns the alpha of what survived:
     * `0` where the flood reached, `1` elsewhere, feathered by a Gaussian of [feather] pixels.
     *
     * A candidate joins the flood when its ΔE76 distance to the **running mean of the already
     * flooded region** is below `tolerance * 100`. Comparing against the running mean rather than
     * the seed colour is the whole point: a photographed background is never one colour, and a
     * fixed reference stops dead halfway down a vignette or a lighting falloff.
     *
     * The flood uses an explicit stack — a 12 MP image overflows the JVM stack under recursion and
     * the crash presents as a random OOM. Returns an all-opaque alpha if the flood consumed the
     * entire image, because "delete everything" is never the answer the user wanted.
     *
     * **The flood itself is inherently sequential and stays that way.** The acceptance test compares a
     * candidate against the running mean of everything flooded *so far*, so which pixels join depends on
     * the order they are popped in — the result is a function of the traversal, not just of the image,
     * and any split would make the matte depend on how many cores the phone has. What this function
     * does get from the pool is where its time actually goes: [Color.toLabPlanes] on the way in and the
     * feather blur on the way out, both split internally.
     */
    fun borderFlood(src: RgbaImage, tolerance: Float, feather: Float = 1.5f): GrayF {
        val w = src.width
        val h = src.height
        val n = w * h
        val alpha = GrayF(w, h)

        val lab = Color.toLabPlanes(src)
        val lp = lab[0]
        val ap = lab[1]
        val bp = lab[2]

        val tol = Px.clamp(tolerance, 0f, 1f) * MAX_LAB_DISTANCE
        val tolSq = tol * tol

        val flooded = BooleanArray(n)
        val queued = BooleanArray(n)
        // Capacity n is exact: `queued` guarantees an index is on the stack at most once.
        val stack = IntArray(n)
        var sp = 0

        // Seed the running mean with the per-channel median of the four corners. The median of four
        // survives one corner landing on the subject, which the mean does not, and it is still a
        // fixed deterministic starting point.
        val c0 = 0
        val c1 = w - 1
        val c2 = (h - 1) * w
        val c3 = (h - 1) * w + (w - 1)
        var mL = median4(lp[c0], lp[c1], lp[c2], lp[c3])
        var mA = median4(ap[c0], ap[c1], ap[c2], ap[c3])
        var mB = median4(bp[c0], bp[c1], bp[c2], bp[c3])

        var x = 0
        while (x < w) {
            val top = x
            if (!queued[top]) {
                queued[top] = true
                stack[sp++] = top
            }
            val bottom = (h - 1) * w + x
            if (!queued[bottom]) {
                queued[bottom] = true
                stack[sp++] = bottom
            }
            x++
        }
        var y = 0
        while (y < h) {
            val left = y * w
            if (!queued[left]) {
                queued[left] = true
                stack[sp++] = left
            }
            val right = y * w + (w - 1)
            if (!queued[right]) {
                queued[right] = true
                stack[sp++] = right
            }
            y++
        }

        var sumL = 0.0
        var sumA = 0.0
        var sumB = 0.0
        var cnt = 0L
        var pops = 0L
        // A pixel can be re-queued once per accepted neighbour (the running mean moves, so a
        // rejection is not final), which is bounded at 8 per pixel. The cap makes termination a
        // property of the code rather than of the data.
        val maxPops = 8L * n + 4L * (w + h) + 64L

        while (sp > 0 && pops < maxPops) {
            val idx = stack[--sp]
            queued[idx] = false
            pops++
            if (flooded[idx]) continue

            val dl = lp[idx] - mL
            val da = ap[idx] - mA
            val db = bp[idx] - mB
            // Squared compare: a sqrt per candidate is the single hottest operation in this loop
            // and the ordering is identical.
            if (dl * dl + da * da + db * db > tolSq) continue

            flooded[idx] = true
            sumL += lp[idx].toDouble()
            sumA += ap[idx].toDouble()
            sumB += bp[idx].toDouble()
            cnt++
            val inv = 1.0 / cnt
            mL = (sumL * inv).toFloat()
            mA = (sumA * inv).toFloat()
            mB = (sumB * inv).toFloat()

            val py = idx / w
            val px = idx - py * w
            val yLo = if (py > 0) py - 1 else 0
            val yHi = if (py < h - 1) py + 1 else h - 1
            val xLo = if (px > 0) px - 1 else 0
            val xHi = if (px < w - 1) px + 1 else w - 1
            var ny = yLo
            while (ny <= yHi) {
                val row = ny * w
                var nx = xLo
                while (nx <= xHi) {
                    val ni = row + nx
                    if (!flooded[ni] && !queued[ni]) {
                        queued[ni] = true
                        stack[sp++] = ni
                    }
                    nx++
                }
                ny++
            }
        }

        if (cnt >= n) return alpha.fill(1f)

        val out = alpha.data
        var i = 0
        while (i < n) {
            out[i] = if (flooded[i]) 0f else 1f
            i++
        }
        if (feather <= 0.05f) return alpha
        val soft = Convolve.gaussianBlur(alpha, feather)
        val sd = soft.data
        var j = 0
        while (j < sd.size) {
            sd[j] = Px.clamp01(sd[j])
            j++
        }
        return soft
    }

    /**
     * Spectral-residual saliency (Hou & Zhang), normalised to 0..1 and bilinearly upsampled back to
     * the source size.
     *
     * `R = log|F| - boxBlur3(log|F|)`, inverse-transformed with the **original phase**, squared,
     * blurred with σ=3 and stretched. Computed on a [proxySize]×[proxySize] proxy because the
     * technique is explicitly a coarse-scale one — at full resolution the residual describes
     * texture, not objects.
     *
     * A power-of-two [proxySize] (the default 64 is one) needs no FFT padding; other values are
     * zero-padded to the next power of two and cropped back, which costs a mild border artefact.
     * Returns an all-zero map for an image that is flat at proxy scale — nothing there is salient.
     */
    fun spectralSaliency(src: RgbaImage, proxySize: Int = 64): GrayF {
        val p = Px.clamp(proxySize, 4, 1024)
        val gray = Color.toGray(src)
        val proxy = Resample.resize(gray, p, p)

        // A flat proxy has no spectral residual to find, and the residual is not merely small there
        // but undefined: every non-DC bin of |F| is *exactly* zero, so `log|F|` is the `ln(0 + 1e-8)`
        // floor across the whole spectrum and `R = log|F| - boxBlur(log|F|)` describes the floor
        // rather than the image. Bailing out here instead of at the normalisation below is the whole
        // point, because by then the artefact looks like a healthy map. Worked through for a constant
        // grey 128 at 64x64: DC is `4096 * 0.50196 = 2056`, `ln 2056 = 7.63`, while all 4095 other
        // bins sit at `ln(1e-8) = -18.42`; the 3x3 box blur at the corner is edge-clamped so DC lands
        // in 4 of its 9 taps, giving `smoothed[0] = -6.84` and `R[0] = exp(7.63 + 6.84) = 1.93e6`.
        // The inverse transform turns that into a constant `1.93e6 / 4096 = 470.6` plus a +1 delta at
        // pixel (0,0) — the 4095 empty bins have no phase, and the (1, 0) stand-in makes them sum
        // coherently at the origin. Squared, the map is `470.6^2 = 2.2146e5` everywhere with a span
        // of only 942 (302 after the σ=3 blur), i.e. 0.14% of its own magnitude. A `[min,max]`
        // stretch of that reports saliency exactly 1.0 in the corner of an image containing nothing,
        // which is how a flat background becomes "the subject" and background removal eats the
        // artwork. A degenerate range means "nothing found", never "everything found".
        val proxyRange = proxy.range()
        if (proxyRange.second - proxyRange.first <= FLAT_LUMA_SPAN) return GrayF(src.width, src.height)

        val fw = Fft.nextPowerOfTwo(p)
        val fh = fw
        val fn = fw * fh
        val re = FloatArray(fn)
        val im = FloatArray(fn)
        for (y in 0 until p) {
            val srcRow = y * p
            val dstRow = y * fw
            for (xx in 0 until p) re[dstRow + xx] = proxy.data[srcRow + xx]
        }

        Fft.transform2d(re, im, fw, fh, false)

        val logMag = FloatArray(fn)
        val cosP = FloatArray(fn)
        val sinP = FloatArray(fn)
        for (i in 0 until fn) {
            val a = re[i]
            val b = im[i]
            val m = sqrt(a * a + b * b)
            logMag[i] = ln(m + 1e-8f)
            if (m > 1e-20f) {
                // Phase kept as (cos, sin) rather than an angle: reconstruction needs exactly these
                // two numbers and this skips an atan2 plus a cos/sin per bin.
                cosP[i] = a / m
                sinP[i] = b / m
            } else {
                cosP[i] = 1f
                sinP[i] = 0f
            }
        }

        val smoothed = Convolve.boxBlur(GrayF(fw, fh, logMag), 1).data
        for (i in 0 until fn) {
            val r = exp(logMag[i] - smoothed[i])
            re[i] = r * cosP[i]
            im[i] = r * sinP[i]
        }

        Fft.transform2d(re, im, fw, fh, true)

        // Crop back before blurring so the padded region cannot bleed into the map.
        val sal = GrayF(p, p)
        for (y in 0 until p) {
            val srcRow = y * fw
            val dstRow = y * p
            for (xx in 0 until p) {
                val a = re[srcRow + xx]
                val b = im[srcRow + xx]
                sal.data[dstRow + xx] = a * a + b * b
            }
        }

        val blurred = Convolve.gaussianBlur(sal, 3f)
        val range = blurred.range()
        val lo = range.first
        val span = range.second - lo
        // Second line of defence, not the first: this catches only an exactly-constant map, and the
        // flat-input guard above is what keeps a span that is pure round-off from being stretched to
        // full scale. A relative test (`span <= eps * hi`) cannot replace that guard either — an
        // all-black frame produces a map of [0, 1] whose relative span is a perfectly healthy 1.0
        // even though every bit of it is the fabricated origin delta. Same invariant in both places:
        // a degenerate range yields an empty map, because the opposite answer erases the artwork.
        if (span <= 1e-12f) return GrayF(src.width, src.height)
        val inv = 1f / span
        val bd = blurred.data
        for (i in bd.indices) bd[i] = Px.clamp01((bd[i] - lo) * inv)
        return Resample.resize(blurred, src.width, src.height)
    }

    /**
     * Thresholds [spectralSaliency] at [threshold], fills interior holes and feathers the result
     * into an alpha channel (1 = keep).
     *
     * If the thresholded region is empty or covers less than 0.5% of the frame the matte is
     * abandoned and an all-opaque alpha is returned. A saliency map that found nothing is not a
     * licence to erase the image, and the caller shows "no matte" instead.
     */
    fun saliencyMatte(src: RgbaImage, threshold: Float = 0.5f, feather: Float = 2f): GrayF {
        val w = src.width
        val h = src.height
        val n = w * h
        val sal = spectralSaliency(src)
        val t = Px.clamp(threshold, 0f, 1f)

        val mask = Mask(w, h)
        var on = 0
        for (i in 0 until n) {
            if (sal.data[i] >= t) {
                mask.data[i] = true
                on++
            }
        }
        // Note the polarity, which is the opposite of [spectralSaliency]'s and deliberately so: a
        // degenerate *map* is all-zero ("nothing is salient"), a degenerate *matte* is all-one ("keep
        // every pixel"). Both are the same statement — nothing was found — and neither is ever
        // allowed to come out as "everything was found". Thresholds and areas are the only measured
        // quantities here; nothing in this function divides by a measured range.
        if (on.toLong() * 200L < n.toLong()) return GrayF(w, h).fill(1f)

        // The interior of a subject frequently falls below the threshold even when its outline does
        // not; without the hole fill the matte punches holes through the middle of the subject.
        val solid = Components.fillHoles(mask, maxOf(64, n / 100))
        val alpha = solid.toGray()
        if (feather <= 0.05f) return alpha
        val soft = Convolve.gaussianBlur(alpha, feather)
        val sd = soft.data
        for (i in sd.indices) sd[i] = Px.clamp01(sd[i])
        return soft
    }

    /**
     * Composites [src] over the solid colour [background] using [alpha] (source-over, straight
     * alpha). The source's own alpha channel is multiplied into the matte, so an already-cut-out
     * PNG stays cut out. Requires [alpha] to match [src] in size.
     */
    fun applyMatte(src: RgbaImage, alpha: GrayF, background: Int): RgbaImage {
        require(alpha.width == src.width && alpha.height == src.height) {
            "applyMatte(): alpha ${alpha.width}x${alpha.height} does not match " +
                "source ${src.width}x${src.height}"
        }
        val out = RgbaImage(src.width, src.height)
        val bgA = RgbaImage.alphaOf(background) / 255f
        val bgR = RgbaImage.redOf(background).toFloat()
        val bgG = RgbaImage.greenOf(background).toFloat()
        val bgB = RgbaImage.blueOf(background).toFloat()
        val sp = src.pixels
        val op = out.pixels
        val ad = alpha.data
        // A divide and four roundings per pixel over a full-resolution image, all of it a pure function
        // of `sp[i]` and `ad[i]`. `op` starts zeroed, which the `continue` relies on.
        Parallel.chunks(sp.size, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) {
                val px = sp[i]
                val a = Px.clamp01(ad[i]) * (((px ushr 24) and 0xFF) / 255f)
                val back = bgA * (1f - a)
                val outA = a + back
                if (outA <= 0f) {
                    op[i] = 0
                    continue
                }
                val invA = 1f / outA
                val r = (((px ushr 16) and 0xFF) * a + bgR * back) * invA
                val g = (((px ushr 8) and 0xFF) * a + bgG * back) * invA
                val b = ((px and 0xFF) * a + bgB * back) * invA
                op[i] = RgbaImage.argb(
                    Px.toByte255(outA),
                    Px.clamp(r.roundToInt(), 0, 255),
                    Px.clamp(g.roundToInt(), 0, 255),
                    Px.clamp(b.roundToInt(), 0, 255),
                )
            }
        }
        return out
    }

    /**
     * Grey-channel form of [applyMatte]: `out = src * a + background * (1 - a)`, with `a` clamped
     * to 0..1. Requires [alpha] to match [src] in size.
     */
    fun applyMatte(src: GrayF, alpha: GrayF, background: Float): GrayF {
        require(alpha.width == src.width && alpha.height == src.height) {
            "applyMatte(): alpha ${alpha.width}x${alpha.height} does not match " +
                "source ${src.width}x${src.height}"
        }
        val out = GrayF(src.width, src.height)
        val sd = src.data
        val ad = alpha.data
        val od = out.data
        for (i in sd.indices) {
            val a = Px.clamp01(ad[i])
            od[i] = sd[i] * a + background * (1f - a)
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // The third cue: does this colour resemble the border region?
    // ---------------------------------------------------------------------------------------------

    /**
     * Per-pixel probability that a pixel's colour belongs to the **border band** rather than to the
     * interior: `1` means "this colour is what the edge of the frame is made of", `0` means "this
     * colour only ever appears inside".
     *
     * Two quantised Lab histograms are built in one pass — one over a band [bandFraction] of the
     * short side wide around the frame, one over everything inside it — and each pixel is scored with
     * the posterior of its own bin under equal priors:
     * ```
     * fBand = countBand[bin] / pixelsInBand      fInner = countInner[bin] / pixelsInside
     * likeness = fBand / (fBand + fInner)
     * ```
     * **The densities, not the raw counts, are what make this usable.** The band is a few per cent of
     * the frame, so a ratio of counts would call almost every colour "interior" purely because the
     * interior is twenty times larger.
     *
     * This is the one cue with no connectivity assumption in it, which is exactly why [subjectMatte]
     * needs it: [borderFlood] fails outright when the subject touches an edge, and this does not —
     * a subject touching the edge contributes its colour to the band, but it contributes it to the
     * interior far more strongly, so the posterior still lands below 0.5.
     *
     * A bin holding fewer than [MIN_BIN_EVIDENCE] pixels in total scores exactly `0.5` — no opinion.
     * One stray pixel of a colour is not evidence about a region, and without that floor a single
     * JPEG artefact in the band would mark every pixel that shares its bin as background.
     *
     * @param bandFraction width of the border band as a fraction of the short side, clamped to
     *   `0..0.49`; the band is always at least 1 px and never wider than half the short side.
     * @return a 0..1 map the size of [src]; a flat `0.5` (no information) for any image with no
     *   interior left once the band is taken, which includes everything under 3 px.
     */
    fun borderLikeness(src: RgbaImage, bandFraction: Float = 0.06f): GrayF {
        val w = src.width
        val h = src.height
        val n = w * h
        val out = GrayF(w, h)
        val shortSide = if (w < h) w else h
        // Under 3 px there is no "inside" to contrast the border against, so there is nothing this
        // function can measure. 0.5 everywhere is the honest answer and it makes the fusion in
        // [subjectMatte] fall back on its other two cues instead of on a fabricated one.
        if (shortSide < 3) return out.fill(0.5f)
        val band = Px.clamp(
            (Px.clamp(bandFraction, 0f, 0.49f) * shortSide).roundToInt(),
            1,
            (shortSide - 1) / 2,
        )

        val lab = Color.toLabPlanes(src)
        val lp = lab[0]
        val ap = lab[1]
        val bp = lab[2]

        val bandHist = IntArray(BIN_COUNT)
        val innerHist = IntArray(BIN_COUNT)
        var bandTotal = 0
        var innerTotal = 0
        // The bin per pixel is cached rather than recomputed on the second pass: the quantisation is
        // three clamps and three multiplies, and it would otherwise run twice over the whole image.
        val bin = IntArray(n)
        var y = 0
        while (y < h) {
            val row = y * w
            val edgeRow = y < band || y >= h - band
            var x = 0
            while (x < w) {
                val i = row + x
                val q = labBin(lp[i], ap[i], bp[i])
                bin[i] = q
                if (edgeRow || x < band || x >= w - band) {
                    bandHist[q]++
                    bandTotal++
                } else {
                    innerHist[q]++
                    innerTotal++
                }
                x++
            }
            y++
        }
        if (bandTotal == 0 || innerTotal == 0) return out.fill(0.5f)

        // Once per bin, not once per pixel: 2048 divisions instead of one per pixel of a 12 MP image.
        val posterior = FloatArray(BIN_COUNT)
        for (q in 0 until BIN_COUNT) {
            val cb = bandHist[q]
            val ci = innerHist[q]
            posterior[q] = if (cb + ci < MIN_BIN_EVIDENCE) {
                0.5f
            } else {
                val fb = cb.toDouble() / bandTotal
                val fi = ci.toDouble() / innerTotal
                (fb / (fb + fi)).toFloat()
            }
        }
        val od = out.data
        for (i in 0 until n) od[i] = posterior[bin[i]]
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Boundary refinement
    // ---------------------------------------------------------------------------------------------

    /**
     * Guided filter (He, Sun & Tang), the model-free way to move a coarse alpha's edge onto the
     * object's edge.
     *
     * Inside every `(2r+1)²` window the output is assumed to be a **linear function of the guide**,
     * `q = a·I + b`, with `a` and `b` the least-squares fit to [input] over that window; the
     * per-pixel result is the average of the fits of all windows containing it:
     * ```
     * a = cov(I, p) / (var(I) + eps)      b = mean(p) - a·mean(I)
     * q = boxMean(a)·I + boxMean(b)
     * ```
     * That linear assumption is the whole trick: `q` can only step where `I` steps, so an alpha whose
     * own boundary is a pixel or two out comes back with **its steepest transition on the luminance
     * edge underneath it**. Everything downstream traces that boundary, so a matte edge in the wrong
     * place is that many pixels of wrong drawing.
     *
     * Be precise about what that does and does not promise. It is a re-*shaping*, not a re-*locating*:
     * the transition is rebuilt at the guide's edge, and the level at which the result crosses 0.5
     * therefore moves toward it, but a boundary displaced further than about [radius] cannot be
     * pulled all the way back — the windows that would have to see both edges at once do not exist.
     * Feed it a boundary that is roughly right and it makes it exactly right; feed it a boundary that
     * is wrong and it stays wrong.
     *
     * Cost is six [Convolve.boxBlur] passes and is independent of [radius], which is why the radius
     * can be a useful fraction of the image instead of a token 2 px.
     *
     * @param eps regularisation in the units of `guide²`. Larger means smoother: luminance
     *   differences below `sqrt(eps)` are treated as flat and get averaged across.
     * @param radius `<= 0` returns a copy of [input] — a zero-radius window has no neighbourhood to
     *   fit anything to.
     * @return an unclamped field the size of [input]; a caller matting with it should clamp, because
     *   the linear fit legitimately overshoots slightly on either side of a hard edge.
     * @throws IllegalArgumentException if [guide] and [input] differ in size.
     */
    fun guidedFilter(guide: GrayF, input: GrayF, radius: Int, eps: Float): GrayF {
        require(guide.width == input.width && guide.height == input.height) {
            "guidedFilter(): guide ${guide.width}x${guide.height} does not match " +
                "input ${input.width}x${input.height}"
        }
        if (radius <= 0) return input.copy()
        val w = guide.width
        val h = guide.height
        val n = w * h
        val g = guide.data
        val p = input.data

        val gg = GrayF(w, h)
        val gp = GrayF(w, h)
        for (i in 0 until n) {
            val gi = g[i].toDouble()
            gg.data[i] = (gi * gi).toFloat()
            gp.data[i] = (gi * p[i]).toFloat()
        }
        val meanG = Convolve.boxBlur(guide, radius).data
        val meanP = Convolve.boxBlur(input, radius).data
        val meanGG = Convolve.boxBlur(gg, radius).data
        val meanGP = Convolve.boxBlur(gp, radius).data

        val a = GrayF(w, h)
        val b = GrayF(w, h)
        val e = if (eps < 0f) 0.0 else eps.toDouble()
        for (i in 0 until n) {
            val mg = meanG[i].toDouble()
            val mp = meanP[i].toDouble()
            // `E[I²] - E[I]²` is a subtraction of two nearly equal numbers wherever the window is
            // flat, so it lands a hair below zero there; a negative variance would flip the sign of
            // `a` and put a dark halo around every flat region.
            var varG = meanGG[i] - mg * mg
            if (varG < 0.0) varG = 0.0
            val den = varG + e
            val ai = if (den <= 0.0) 0.0 else (meanGP[i] - mg * mp) / den
            a.data[i] = ai.toFloat()
            b.data[i] = (mp - ai * mg).toFloat()
        }
        val ma = Convolve.boxBlur(a, radius).data
        val mb = Convolve.boxBlur(b, radius).data
        val out = GrayF(w, h)
        for (i in 0 until n) out.data[i] = (ma[i].toDouble() * g[i] + mb[i]).toFloat()
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // The fused matte
    // ---------------------------------------------------------------------------------------------

    /**
     * A matte together with everything a caller needs to decide whether to believe it.
     *
     * The point of the structure is that [alpha] is never handed over bare. A caller that applies a
     * matte with [confidence] `0.1` and no `if` has quietly deleted somebody's artwork, and that
     * failure is not detectable from the alpha alone — an alpha that keeps 3% of the frame looks
     * exactly like an alpha of a small subject.
     *
     * @property coverage mean alpha, i.e. the fraction of the frame the matte keeps, 0..1.
     * @property confidence 0..1; the weakest of the three checks in [subjectMatte]. At or above
     *   [MIN_CONFIDENCE] ([confident]) the matte is safe to apply; below it the caller must ask, or
     *   fall back to the whole frame.
     * @property reason one sentence, in the user's language, naming what the evidence actually did.
     *   Written to be shown, not logged.
     */
    class MatteResult(
        @JvmField val alpha: GrayF,
        @JvmField val coverage: Float,
        @JvmField val confidence: Float,
        @JvmField val reason: String,
    ) {
        /** `true` when [confidence] is at or above [MIN_CONFIDENCE]. */
        val confident: Boolean get() = confidence >= Matte.MIN_CONFIDENCE
    }

    /** [MatteResult.confidence] at or above which a matte may be applied without asking. */
    const val MIN_CONFIDENCE = 0.5f

    /**
     * Foreground/background separation from all three cues at once, with a confidence figure.
     *
     * Neither existing matte is trustworthy on its own — [borderFlood] fails when the subject touches
     * an edge or the background is textured, [saliencyMatte] is a 64×64 map that misses the flat
     * interior of a large object — so this fuses them with [borderLikeness], refines the result
     * against the image, and reports how well it went.
     *
     * **The fusion rule.** Each cue votes for *foreground* in 0..1: `c` = not reached by the border
     * flood, `b` = colour unlike the border band (`1 - borderLikeness`), `s` = spectrally salient.
     * ```
     * score = 0.45·c + 0.35·b + 0.20·s
     * ```
     * with two overrides for the cases where the two **structural** cues agree, because there the
     * answer is not a matter of degree:
     *  - flooded **and** border-coloured → `0`. Both cues say background by different arguments;
     *    saliency, which leaks roughly one 64th of the frame past every object boundary, does not
     *    get to drag a halo of background back in.
     *  - not flooded **and** not border-coloured → `1`. Likewise, a coarse map that found nothing
     *    salient in the flat middle of a white pot does not get to punch a hole in it.
     *
     * The weights say what each cue is worth where they *disagree*, which is the only place the
     * arithmetic matters. Connectivity is the largest because where it fires it is exact — an
     * explicit 8-connected path of background-coloured pixels to the frame edge — but it is one
     * threshold away from failing completely. The colour posterior is next because it is per-pixel
     * and survives the case connectivity cannot (a subject touching the edge), but it cannot tell
     * apart a subject that happens to share the background's colours. Saliency is smallest and is
     * deliberately never decisive alone: at 64×64 one proxy pixel covers 1/64 of the frame.
     *
     * The vote is then thresholded at 0.5 — it exists to make the *decision*, not to be the alpha —
     * and the resulting mask is cleaned in order: specks below 0.25% of the frame are dropped (**and
     * the prune is reverted wholesale if it would cost more than a tenth of the foreground** — a
     * speck filter may tidy, it may not amputate); enclosed holes up to 2% of the frame are filled,
     * which is what stops the pattern *inside* a subject reading as background. Only then does the
     * hard mask go through [guidedFilter] against the luma, which is what puts the steep part of the
     * alpha's transition on the object's own edge instead of wherever the vote's 0.5 happened to
     * fall.
     *
     * **Confidence** is the minimum of three independent checks, so it is as strong as its weakest
     * link and [MatteResult.reason] can name which one that was:
     *  1. *coverage plausibility* — a matte keeping 0.5% or 99% of the frame separated nothing;
     *  2. *cue agreement* — the fraction of the frame on which connectivity and colour reached the
     *     same verdict. Two independent cues agreeing is the only evidence available here that the
     *     answer is about the image rather than about one cue's failure mode;
     *  3. *decisiveness* — the fraction of the refined alpha that is actually near 0 or near 1. An
     *     alpha that is grey everywhere has located no boundary at all.
     *
     * @param tolerance ΔE76 tolerance for the border flood, 0..1 (see [borderFlood]).
     * @param feather optional extra Gaussian softening of the finished alpha, in pixels; `<= 0.05`
     *   leaves the guided filter's own edge alone, which is normally what you want.
     * @return a [MatteResult] whose alpha is **all-opaque** whenever the separation failed or the
     *   image is too small to separate — never an alpha that empties the frame.
     */
    fun subjectMatte(src: RgbaImage, tolerance: Float = 0.18f, feather: Float = 0f): MatteResult {
        val w = src.width
        val h = src.height
        val n = w * h
        if (n < MIN_MATTE_PIXELS) {
            return MatteResult(
                GrayF(w, h).fill(1f),
                1f,
                0f,
                "A ${w}x$h image is too small to tell a subject from a background, so the whole " +
                    "frame was kept.",
            )
        }

        // Feather is applied once, at the end, to the fused alpha. Feathering the flood here would
        // put a soft ramp into a cue that the fusion then compares against 0.5, which turns a
        // boundary decision into a coin toss along the whole outline.
        val connect = borderFlood(src, tolerance, feather = 0f).data
        val likeness = borderLikeness(src).data
        val salience = spectralSaliency(src).data

        val mask = Mask(w, h)
        val md = mask.data
        var on = 0
        var agree = 0
        for (i in 0 until n) {
            val c = connect[i]
            val b = likeness[i]
            val fgByConnect = c >= 0.5f
            val fgByColour = b < 0.5f
            if (fgByConnect == fgByColour) agree++
            val score = when {
                !fgByConnect && !fgByColour -> 0.0
                fgByConnect && fgByColour -> 1.0
                else -> W_CONNECT * c + W_COLOUR * (1.0 - b) + W_SALIENCY * salience[i]
            }
            if (score >= 0.5) {
                md[i] = true
                on++
            }
        }

        var cleaned = mask
        if (on > 0) {
            val pruned = Components.removeSmallBlobs(mask, maxOf(MIN_SPECK_AREA, n / SPECK_AREA_DIVISOR))
            // A speck filter is allowed to tidy and not to amputate. Compared in Long because
            // `on * 9` overflows Int above ~240 MP and that failure would present as a silently
            // discarded foreground rather than as a crash.
            if (pruned.countTrue().toLong() * 10L >= on.toLong() * 9L) cleaned = pruned
        }
        val filled = Components.fillHoles(cleaned, maxOf(MIN_HOLE_AREA, n / HOLE_AREA_DIVISOR))

        // **The vote decides, the guided filter shapes.** What goes into the refinement is a hard
        // 0/1 mask and not the graded score, and the difference is not cosmetic: a fused score is
        // 0.625 across an image where the cues merely disagree everywhere, and feeding that forward
        // produces an alpha that fades the whole picture by 37% instead of keeping it. Uncertainty
        // belongs in [MatteResult.confidence], where a caller can act on it; smeared into the alpha
        // it is just a wash nobody asked for. The softness in the finished alpha comes from the
        // guided filter, which puts it where the image has an edge.
        val pre = GrayF(w, h)
        val pd = pre.data
        val fd = filled.data
        for (i in 0 until n) pd[i] = if (fd[i]) 1f else 0f

        val shortSide = if (w < h) w else h
        val radius = maxOf(2, (GUIDE_RADIUS_FRACTION * shortSide).roundToInt())
        val refined = guidedFilter(Color.toGray(src), pre, radius, GUIDE_EPS)
        var alpha = GrayF(w, h)
        for (i in 0 until n) alpha.data[i] = Px.clamp01(refined.data[i])
        if (feather > 0.05f) {
            val soft = Convolve.gaussianBlur(alpha, feather)
            for (i in 0 until n) soft.data[i] = Px.clamp01(soft.data[i])
            alpha = soft
        }

        var kept = 0.0
        var decided = 0
        val ad = alpha.data
        for (i in 0 until n) {
            val v = ad[i]
            kept += v.toDouble()
            if (v <= DECIDED_LOW || v >= DECIDED_HIGH) decided++
        }
        val coverage = (kept / n).toFloat()
        if (coverage < MIN_KEEP_FRACTION) {
            // Same invariant as [saliencyMatte] and [borderFlood]: a separation that found nothing
            // returns "keep everything", never "keep nothing". The reported coverage is the
            // returned alpha's, which is 1 — the failed measurement is in the sentence.
            return MatteResult(
                GrayF(w, h).fill(1f),
                1f,
                0f,
                "Background separation kept only ${percent(coverage)}% of the frame, which reads as " +
                    "a failed matte rather than a small subject, so the whole frame was kept.",
            )
        }

        val agreement = (agree.toDouble() / n).toFloat()
        val decisiveness = (decided.toDouble() / n).toFloat()
        val cCoverage = minOf(
            ramp(coverage, MIN_KEEP_FRACTION, HEALTHY_LOW),
            1f - ramp(coverage, HEALTHY_HIGH, MAX_KEEP_FRACTION),
        )
        val cAgreement = ramp(agreement, AGREE_LOW, AGREE_HIGH)
        val cDecisive = ramp(decisiveness, DECISIVE_LOW, DECISIVE_HIGH)
        val confidence = minOf(cCoverage, cAgreement, cDecisive)

        val reason = when {
            confidence >= MIN_CONFIDENCE ->
                "The border flood and the border-colour model agree on ${percent(agreement)}% of the " +
                    "frame; the matte keeps ${percent(coverage)}% of it and ${percent(decisiveness)}% " +
                    "of that is a clear decision rather than a soft edge."
            cCoverage <= cAgreement && cCoverage <= cDecisive ->
                if (coverage >= HEALTHY_HIGH) {
                    "The matte kept ${percent(coverage)}% of the frame, so almost nothing was " +
                        "separated out — treat this image as having no background to remove."
                } else {
                    "The matte kept only ${percent(coverage)}% of the frame, which is small enough " +
                        "that it is more likely a failed separation than a small subject."
                }
            cAgreement <= cDecisive ->
                "The border flood and the border-colour model disagree about " +
                    "${percent(1f - agreement)}% of the frame, so where the subject ends is a guess " +
                    "rather than a measurement."
            else ->
                "${percent(1f - decisiveness)}% of the matte is neither clearly subject nor clearly " +
                    "background, so it has softened an edge rather than found one."
        }
        return MatteResult(alpha, coverage, confidence, reason)
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    /** Lightness bins. 8 over 0..100 is 12.5 L units — one bin is a visible but not a large step. */
    private const val BINS_L = 8

    /**
     * Chroma bins per axis, over the full `-128..128` Lab range, so one bin is 16 units — roughly
     * the ΔE at which two colours stop reading as the same paper. Finer bins make the histograms
     * sparse enough that a single pixel decides a bin; coarser ones merge a subject into its
     * background.
     */
    private const val BINS_AB = 16

    private const val BIN_COUNT = BINS_L * BINS_AB * BINS_AB

    /** Pixels a colour bin needs before [borderLikeness] will express an opinion about it. */
    private const val MIN_BIN_EVIDENCE = 4

    // Fusion weights. They sum to exactly 1, so `score` is a 0..1 quantity that can be compared
    // against 0.5 without a second normalisation. See [subjectMatte] for why they are in this order.
    private const val W_CONNECT = 0.45
    private const val W_COLOUR = 0.35
    private const val W_SALIENCY = 0.20

    /** Smallest surviving blob, as an absolute floor and as a fraction (0.25%) of the frame. */
    private const val MIN_SPECK_AREA = 16
    private const val SPECK_AREA_DIVISOR = 400

    /** Largest enclosed background region treated as a hole: 2% of the frame, floored at 64 px. */
    private const val MIN_HOLE_AREA = 64
    private const val HOLE_AREA_DIVISOR = 50

    /**
     * Guided-filter window as a fraction of the short side, and its regularisation.
     *
     * `sqrt(1e-4) = 0.01` is 2.5 code values of an 8-bit luma: luminance steps smaller than that are
     * treated as flat and averaged across, larger ones are treated as edges and preserved. Below
     * about 1e-5 the variance term is competing with `Float` cancellation noise in `E[I²] - E[I]²`
     * and the filter starts sharpening the noise instead of the edge.
     */
    private const val GUIDE_RADIUS_FRACTION = 0.03f
    private const val GUIDE_EPS = 1e-4f

    /** Below 4×4 there is no border band, no interior and no neighbourhood — nothing to separate. */
    private const val MIN_MATTE_PIXELS = 16

    /** Coverage below which the separation is declared failed and the whole frame is kept. */
    private const val MIN_KEEP_FRACTION = 0.005f

    // Coverage confidence ramps: full marks between 3% and 90% of the frame, falling off to zero at
    // the two ends. Outside that band the matte is not describing a subject on a background.
    private const val HEALTHY_LOW = 0.03f
    private const val HEALTHY_HIGH = 0.90f
    private const val MAX_KEEP_FRACTION = 0.99f

    // Two independent cues agreeing on half the frame is chance; 90% is a real signal.
    private const val AGREE_LOW = 0.5f
    private const val AGREE_HIGH = 0.9f

    // An alpha pixel counts as decided outside this band, and the matte as decisive when at least
    // 90% of it is (60% or less is a matte made of soft edges rather than one boundary).
    private const val DECIDED_LOW = 0.15f
    private const val DECIDED_HIGH = 0.85f
    private const val DECISIVE_LOW = 0.6f
    private const val DECISIVE_HIGH = 0.9f

    /**
     * Quantises one Lab colour to a [BIN_COUNT] histogram index.
     *
     * The multiplications are in `Double` and truncated once, which is what the TypeScript mirror
     * does for free: a bin index computed in `Float` on one engine and `Double` on the other lands
     * on opposite sides of a bin edge for colours that sit exactly on one, and a whole bin's worth
     * of pixels then gets a different posterior.
     */
    private fun labBin(l: Float, a: Float, b: Float): Int {
        val li = Px.clamp((l * (BINS_L / 100.0)).toInt(), 0, BINS_L - 1)
        val ai = Px.clamp(((a + 128.0) * (BINS_AB / 256.0)).toInt(), 0, BINS_AB - 1)
        val bi = Px.clamp(((b + 128.0) * (BINS_AB / 256.0)).toInt(), 0, BINS_AB - 1)
        return (li * BINS_AB + ai) * BINS_AB + bi
    }

    /** Linear 0..1 ramp between [lo] and [hi]; a degenerate range is a step, never a divide by zero. */
    private fun ramp(v: Float, lo: Float, hi: Float): Float =
        if (hi <= lo) (if (v >= hi) 1f else 0f) else Px.clamp01((v - lo) / (hi - lo))

    /**
     * 0..1 as a whole percent for the sentences above. Deliberately integer arithmetic and not a
     * formatter: `String.format` is locale-aware and would emit "0,5" in half of Europe.
     */
    private fun percent(v: Float): Int = (Px.clamp01(v) * 100f).roundToInt()

    /** Median of four values (mean of the middle two), by explicit compare — no sorting, no boxing. */
    private fun median4(a: Float, b: Float, c: Float, d: Float): Float {
        var v0 = a
        var v1 = b
        var v2 = c
        var v3 = d
        var t: Float
        if (v0 > v1) { t = v0; v0 = v1; v1 = t }
        if (v2 > v3) { t = v2; v2 = v3; v3 = t }
        if (v0 > v2) { t = v0; v0 = v2; v2 = t }
        if (v1 > v3) { t = v1; v1 = v3; v3 = t }
        if (v1 > v2) { t = v1; v1 = v2; v2 = t }
        return (v1 + v2) * 0.5f
    }
}
