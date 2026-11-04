package com.offlinetracer.imaging

import kotlin.math.max
import kotlin.math.sqrt

object Threshold {

    fun otsu(src: GrayF): Float {
        val hist = Contrast.histogram(src, 256)
        return (otsuIndex(hist, src.size) + 0.5f) / 255f
    }

    fun otsuSeparability(src: GrayF): Float {
        val hist = Contrast.histogram(src, 256)
        val n = src.size
        if (n == 0) return 0f
        var sum = 0.0
        for (b in 0 until 256) sum += b.toDouble() * hist[b]
        val mean = sum / n
        var variance = 0.0
        for (b in 0 until 256) {
            val d = b - mean
            variance += d * d * hist[b]
        }
        variance /= n
        if (variance <= 1e-12) return 0f
        val t = otsuIndex(hist, n)
        var w0 = 0.0
        var s0 = 0.0
        for (b in 0..t) {
            w0 += hist[b].toDouble()
            s0 += b.toDouble() * hist[b]
        }
        val w1 = n - w0
        if (w0 <= 0.0 || w1 <= 0.0) return 0f
        val m0 = s0 / w0
        val m1 = (sum - s0) / w1
        val between = (w0 / n) * (w1 / n) * (m0 - m1) * (m0 - m1)
        val ratio = between / variance
        return Px.clamp(ratio, 0.0, 1.0).toFloat()
    }

    fun fixed(src: GrayF, t: Float, invert: Boolean = false): Mask {
        val s = src.data
        val out = BooleanArray(s.size)
        for (i in s.indices) out[i] = (s[i] > t) != invert
        return Mask(src.width, src.height, out)
    }

    fun adaptiveMean(src: GrayF, radius: Int, c: Float, invert: Boolean = false): Mask {
        val w = src.width
        val h = src.height
        val r = max(0, radius)
        val sat = Convolve.summedAreaTable(src)
        val sw = w + 1
        val s = src.data
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = if (y - r < 0) 0 else y - r
            val y1 = if (y + r > h - 1) h - 1 else y + r
            val top = y0 * sw
            val bot = (y1 + 1) * sw
            val rows = y1 - y0 + 1
            val base = y * w
            for (x in 0 until w) {
                val x0 = if (x - r < 0) 0 else x - r
                val x1 = if (x + r > w - 1) w - 1 else x + r
                val area = (x1 - x0 + 1) * rows
                val sum = sat[bot + x1 + 1] - sat[top + x1 + 1] - sat[bot + x0] + sat[top + x0]
                val mean = (sum / area).toFloat()
                out[base + x] = (s[base + x] > mean - c) != invert
            }
        }
        return Mask(w, h, out)
    }

    fun adaptiveGaussian(src: GrayF, sigma: Float, c: Float, invert: Boolean = false): Mask {
        val blur = Convolve.gaussianBlur(src, sigma)
        val s = src.data
        val b = blur.data
        val out = BooleanArray(s.size)
        for (i in s.indices) out[i] = (s[i] > b[i] - c) != invert
        return Mask(src.width, src.height, out)
    }

    fun sauvola(src: GrayF, radius: Int, k: Float = 0.2f, invert: Boolean = false): Mask {
        val w = src.width
        val h = src.height
        val r = max(0, radius)
        val s = src.data
        val sat = Convolve.summedAreaTable(src)
        val sw = w + 1
        val satSq = DoubleArray(sw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            val srcBase = y * w
            val cur = (y + 1) * sw
            val prev = y * sw
            for (x in 0 until w) {
                val v = s[srcBase + x].toDouble()
                rowSum += v * v
                satSq[cur + x + 1] = satSq[prev + x + 1] + rowSum
            }
        }
        val out = BooleanArray(w * h)
        val invR = 1f / 0.5f
        for (y in 0 until h) {
            val y0 = if (y - r < 0) 0 else y - r
            val y1 = if (y + r > h - 1) h - 1 else y + r
            val top = y0 * sw
            val bot = (y1 + 1) * sw
            val rows = y1 - y0 + 1
            val base = y * w
            for (x in 0 until w) {
                val x0 = if (x - r < 0) 0 else x - r
                val x1 = if (x + r > w - 1) w - 1 else x + r
                val area = ((x1 - x0 + 1) * rows).toDouble()
                val sum = sat[bot + x1 + 1] - sat[top + x1 + 1] - sat[bot + x0] + sat[top + x0]
                val sumSq = satSq[bot + x1 + 1] - satSq[top + x1 + 1] - satSq[bot + x0] + satSq[top + x0]
                val m = sum / area
                var v = sumSq / area - m * m
                if (v < 0.0) v = 0.0
                val sd = sqrt(v).toFloat()
                val t = m.toFloat() * (1f + k * (sd * invR - 1f))
                out[base + x] = (s[base + x] > t) != invert
            }
        }
        return Mask(w, h, out)
    }

    fun hysteresis(src: GrayF, low: Float, high: Float): Mask {
        val w = src.width
        val h = src.height
        val s = src.data
        val n = w * h
        val res = BooleanArray(n)
        val hi = if (high < low) low else high
        var stack = IntArray(1024)
        var sp = 0
        for (i in 0 until n) {
            if (s[i] > hi) {
                res[i] = true
                if (sp == stack.size) stack = stack.copyOf(stack.size shl 1)
                stack[sp++] = i
            }
        }
        while (sp > 0) {
            val i = stack[--sp]
            val y = i / w
            val x = i - y * w
            val y0 = if (y > 0) y - 1 else 0
            val y1 = if (y < h - 1) y + 1 else h - 1
            val x0 = if (x > 0) x - 1 else 0
            val x1 = if (x < w - 1) x + 1 else w - 1
            for (ny in y0..y1) {
                val base = ny * w
                for (nx in x0..x1) {
                    val j = base + nx
                    if (!res[j] && s[j] > low) {
                        res[j] = true
                        if (sp == stack.size) stack = stack.copyOf(stack.size shl 1)
                        stack[sp++] = j
                    }
                }
            }
        }
        return Mask(w, h, res)
    }

    fun autoCannyThresholds(magnitude: GrayF, sigma: Float = 0.33f): FloatArray {
        val m = median(magnitude)
        val s = if (sigma < 0f) 0f else sigma
        val lo = max(0f, (1f - s) * m)
        var hi = kotlin.math.min(1f, (1f + s) * m)
        if (hi < lo) hi = lo
        return floatArrayOf(lo, hi)
    }

    fun median(src: GrayF): Float {
        val r = src.range()
        val lo = r.first
        val span = r.second - lo
        if (span <= 1e-12f) return lo
        val bins = 256
        val hist = IntArray(bins)
        val s = src.data
        val inv = 1f / span
        for (i in s.indices) {
            var b = ((s[i] - lo) * inv * bins).toInt()
            if (b < 0) b = 0
            if (b > bins - 1) b = bins - 1
            hist[b]++
        }
        val half = s.size / 2
        var cum = 0
        for (b in 0 until bins) {
            cum += hist[b]
            if (cum > half) return lo + (b + 0.5f) / bins * span
        }
        return r.second
    }

    private fun otsuIndex(hist: IntArray, total: Int): Int {
        if (total <= 0) return 0
        var sum = 0.0
        for (b in hist.indices) sum += b.toDouble() * hist[b]
        var wB = 0.0
        var sumB = 0.0
        var best = 0.0
        var bestT = 0
        for (t in hist.indices) {
            wB += hist[t].toDouble()
            if (wB <= 0.0) continue
            val wF = total - wB
            if (wF <= 0.0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                bestT = t
            }
        }
        return bestT
    }
}
