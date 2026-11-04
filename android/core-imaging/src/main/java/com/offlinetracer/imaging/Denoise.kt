package com.offlinetracer.imaging

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max

object Denoise {

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
        val range = FloatArray(256)
        val invR = -1.0 / (2.0 * sigmaRange * sigmaRange)
        for (i in 0 until 256) {
            val d = i / 255.0
            range[i] = exp(d * d * invR).toFloat()
        }

        val out = FloatArray(w * h)
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

    fun median(src: GrayF, radius: Int): GrayF {
        if (radius <= 0) return src.copy()
        return if (radius == 1) median3(src) else medianHistogram(src, radius)
    }

    fun anisotropicDiffusion(src: GrayF, iterations: Int, kappa: Float, lambda: Float = 0.25f): GrayF {
        if (iterations <= 0 || kappa <= 0f) return src.copy()
        val w = src.width
        val h = src.height
        val lam = Px.clamp(lambda, 0f, 0.25f)
        val invK2 = -1.0 / (kappa.toDouble() * kappa)
        var cur = src.data.copyOf()
        var next = FloatArray(w * h)
        for (pass in 0 until iterations) {
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
