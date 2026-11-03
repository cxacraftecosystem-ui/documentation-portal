package com.offlinetracer.imaging

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

enum class GradientOp { SOBEL, SCHARR }

object Convolve {

    class Gradients(@JvmField val gx: GrayF, @JvmField val gy: GrayF) {

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

    fun gaussianBlur(src: GrayF, sigma: Float): GrayF {
        if (sigma <= 0.05f) return src.copy()
        val k = gaussianKernel(sigma)
        return separable(src, k, k)
    }

    fun gaussianBlurDouble(src: GrayF, sigma: Float): DoubleArray {
        val k = gaussianKernelDouble(sigma)
        val w = src.width
        val h = src.height
        val s = src.data
        val r = k.size / 2
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

    fun separable(src: GrayF, kx: FloatArray, ky: FloatArray): GrayF {
        val w = src.width
        val h = src.height
        val mid = if (kx.isEmpty()) src.data.copyOf() else FloatArray(w * h)
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

    fun convolve3(src: GrayF, k: FloatArray): GrayF {
        require(k.size == 9) { "convolve3(): kernel must be 9 taps, got ${k.size}" }
        return apply3x3(src, k, 1f)
    }

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

    fun laplacian(src: GrayF): GrayF = apply3x3(src, LAPLACIAN_8, 1f)

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

    private fun apply3x3(src: GrayF, k: FloatArray, scale: Float): GrayF {
        val w = src.width
        val h = src.height
        val s = src.data
        val out = FloatArray(w * h)
        val k0 = k[0].toDouble(); val k1 = k[1].toDouble(); val k2 = k[2].toDouble()
        val k3 = k[3].toDouble(); val k4 = k[4].toDouble(); val k5 = k[5].toDouble()
        val k6 = k[6].toDouble(); val k7 = k[7].toDouble(); val k8 = k[8].toDouble()
        val sc = scale.toDouble()
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
