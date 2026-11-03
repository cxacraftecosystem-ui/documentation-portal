package com.offlinetracer.imaging

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Matte {

    private const val MAX_LAB_DISTANCE = 100f

    private const val FLAT_LUMA_SPAN = 1e-5f

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
        val stack = IntArray(n)
        var sp = 0

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
        val maxPops = 8L * n + 4L * (w + h) + 64L

        while (sp > 0 && pops < maxPops) {
            val idx = stack[--sp]
            queued[idx] = false
            pops++
            if (flooded[idx]) continue

            val dl = lp[idx] - mL
            val da = ap[idx] - mA
            val db = bp[idx] - mB
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

    fun spectralSaliency(src: RgbaImage, proxySize: Int = 64): GrayF {
        val p = Px.clamp(proxySize, 4, 1024)
        val gray = Color.toGray(src)
        val proxy = Resample.resize(gray, p, p)

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
        if (span <= 1e-12f) return GrayF(src.width, src.height)
        val inv = 1f / span
        val bd = blurred.data
        for (i in bd.indices) bd[i] = Px.clamp01((bd[i] - lo) * inv)
        return Resample.resize(blurred, src.width, src.height)
    }

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
        if (on.toLong() * 200L < n.toLong()) return GrayF(w, h).fill(1f)

        val solid = Components.fillHoles(mask, maxOf(64, n / 100))
        val alpha = solid.toGray()
        if (feather <= 0.05f) return alpha
        val soft = Convolve.gaussianBlur(alpha, feather)
        val sd = soft.data
        for (i in sd.indices) sd[i] = Px.clamp01(sd[i])
        return soft
    }

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

    fun borderLikeness(src: RgbaImage, bandFraction: Float = 0.06f): GrayF {
        val w = src.width
        val h = src.height
        val n = w * h
        val out = GrayF(w, h)
        val shortSide = if (w < h) w else h
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

    class MatteResult(
        @JvmField val alpha: GrayF,
        @JvmField val coverage: Float,
        @JvmField val confidence: Float,
        @JvmField val reason: String,
    ) {
        val confident: Boolean get() = confidence >= Matte.MIN_CONFIDENCE
    }

    const val MIN_CONFIDENCE = 0.5f

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
            if (pruned.countTrue().toLong() * 10L >= on.toLong() * 9L) cleaned = pruned
        }
        val filled = Components.fillHoles(cleaned, maxOf(MIN_HOLE_AREA, n / HOLE_AREA_DIVISOR))

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

    private const val BINS_L = 8

    private const val BINS_AB = 16

    private const val BIN_COUNT = BINS_L * BINS_AB * BINS_AB

    private const val MIN_BIN_EVIDENCE = 4

    private const val W_CONNECT = 0.45
    private const val W_COLOUR = 0.35
    private const val W_SALIENCY = 0.20

    private const val MIN_SPECK_AREA = 16
    private const val SPECK_AREA_DIVISOR = 400

    private const val MIN_HOLE_AREA = 64
    private const val HOLE_AREA_DIVISOR = 50

    private const val GUIDE_RADIUS_FRACTION = 0.03f
    private const val GUIDE_EPS = 1e-4f

    private const val MIN_MATTE_PIXELS = 16

    private const val MIN_KEEP_FRACTION = 0.005f

    private const val HEALTHY_LOW = 0.03f
    private const val HEALTHY_HIGH = 0.90f
    private const val MAX_KEEP_FRACTION = 0.99f

    private const val AGREE_LOW = 0.5f
    private const val AGREE_HIGH = 0.9f

    private const val DECIDED_LOW = 0.15f
    private const val DECIDED_HIGH = 0.85f
    private const val DECISIVE_LOW = 0.6f
    private const val DECISIVE_HIGH = 0.9f

    private fun labBin(l: Float, a: Float, b: Float): Int {
        val li = Px.clamp((l * (BINS_L / 100.0)).toInt(), 0, BINS_L - 1)
        val ai = Px.clamp(((a + 128.0) * (BINS_AB / 256.0)).toInt(), 0, BINS_AB - 1)
        val bi = Px.clamp(((b + 128.0) * (BINS_AB / 256.0)).toInt(), 0, BINS_AB - 1)
        return (li * BINS_AB + ai) * BINS_AB + bi
    }

    private fun ramp(v: Float, lo: Float, hi: Float): Float =
        if (hi <= lo) (if (v >= hi) 1f else 0f) else Px.clamp01((v - lo) / (hi - lo))

    private fun percent(v: Float): Int = (Px.clamp01(v) * 100f).roundToInt()

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
