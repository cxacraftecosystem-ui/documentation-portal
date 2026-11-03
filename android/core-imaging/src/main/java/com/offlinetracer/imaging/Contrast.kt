package com.offlinetracer.imaging

import kotlin.math.max
import kotlin.math.min

object Contrast {

    fun clahe(src: GrayF, tilesX: Int = 8, tilesY: Int = 8, clipLimit: Float = 2f): GrayF {
        val w = src.width
        val h = src.height
        val tx = Px.clamp(tilesX, 1, w)
        val ty = Px.clamp(tilesY, 1, h)
        val s = src.data

        val xb = IntArray(tx + 1) { it * w / tx }
        val yb = IntArray(ty + 1) { it * h / ty }

        val lut = IntArray(tx * ty * 256)

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
                        for (b in 0 until 256) lut[off + b] = b
                        continue
                    }
                    if (clipLimit > 0f) {
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
                    var cum = 0
                    val denom = 2L * total
                    for (b in 0 until 256) {
                        cum += hist[b]
                        lut[off + b] = ((cum.toLong() * 510 + total) / denom).toInt()
                    }
                }
            }
        }

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
            xf[x] = if (span > 0f) Px.clamp((x - cx[i0]) / span, 0f, 1f) else 0f
        }

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
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) out[i] = lut[Px.toByte255(s[i])]
        }
        return GrayF(src.width, src.height, out)
    }

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
        Parallel.chunks(s.size, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) out[i] = Math.pow(Px.clamp01(s[i]).toDouble(), e).toFloat()
        }
        return GrayF(src.width, src.height, out)
    }

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

    fun brightnessContrast(src: GrayF, brightness: Float, contrast: Float): GrayF {
        val c = Px.clamp(contrast, -0.99f, 0.99f)
        val factor = (1f + c) / (1f - c)
        val s = src.data
        val out = FloatArray(s.size)
        for (i in s.indices) out[i] = Px.clamp01((s[i] + brightness - 0.5f) * factor + 0.5f)
        return GrayF(src.width, src.height, out)
    }

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
                val fraction = Px.clamp((target - cum) / count.toFloat(), 0f, 1f)
                return (b + fraction) / bins
            }
            cum = next
        }
        return 1f
    }

    fun invert(src: GrayF): GrayF {
        val s = src.data
        val out = FloatArray(s.size)
        for (i in s.indices) out[i] = 1f - s[i]
        return GrayF(src.width, src.height, out)
    }

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
