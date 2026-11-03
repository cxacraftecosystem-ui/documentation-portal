package com.offlinetracer.vector

import kotlin.math.ceil
import kotlin.math.exp

object Smooth {

    fun chaikin(points: List<VecPoint>, iterations: Int, closed: Boolean): List<VecPoint> {
        if (points.size < 3 || iterations <= 0) return points
        val rounds = if (iterations > MAX_CHAIKIN_ITERATIONS) MAX_CHAIKIN_ITERATIONS else iterations

        var cur = points
        for (round in 0 until rounds) {
            val n = cur.size
            if (n < 3 || n * 2 > MAX_CHAIKIN_POINTS) break
            val out = ArrayList<VecPoint>(n * 2)
            if (closed) {
                for (i in 0 until n) {
                    val p = cur[i]
                    val q = cur[if (i + 1 == n) 0 else i + 1]
                    val dx = q.x - p.x
                    val dy = q.y - p.y
                    out.add(VecPoint(p.x + 0.25f * dx, p.y + 0.25f * dy))
                    out.add(VecPoint(p.x + 0.75f * dx, p.y + 0.75f * dy))
                }
            } else {
                out.add(cur[0])
                for (i in 0 until n - 1) {
                    val p = cur[i]
                    val q = cur[i + 1]
                    val dx = q.x - p.x
                    val dy = q.y - p.y
                    if (i > 0) out.add(VecPoint(p.x + 0.25f * dx, p.y + 0.25f * dy))
                    if (i < n - 2) out.add(VecPoint(p.x + 0.75f * dx, p.y + 0.75f * dy))
                }
                out.add(cur[n - 1])
            }
            cur = out
        }
        return cur
    }

    fun gaussian(points: List<VecPoint>, sigma: Float, closed: Boolean): List<VecPoint> {
        val n = points.size
        if (n < 3 || sigma <= MIN_SIGMA) return points
        val kernel = gaussianKernel(sigma)
        return convolve(points, kernel, closed)
    }

    fun curvatureAware(
        points: List<VecPoint>,
        sigma: Float,
        corners: IntArray,
        closed: Boolean,
    ): List<VecPoint> {
        val n = points.size
        if (n < 3 || sigma <= MIN_SIGMA) return points
        if (corners.isEmpty()) return gaussian(points, sigma, closed)

        val s = if (sigma > MAX_SIGMA) MAX_SIGMA else sigma
        val radius = ceil(3.0 * s).toInt().coerceAtLeast(1)

        val dist = IntArray(n) { n }
        var seeded = false
        for (c in corners) {
            if (c in 0 until n) {
                dist[c] = 0
                seeded = true
            }
        }
        if (!seeded) return gaussian(points, s, closed)

        if (closed) {
            var prev = dist[n - 1]
            repeat(2) {
                for (i in 0 until n) {
                    val cand = prev + 1
                    if (cand < dist[i]) dist[i] = cand
                    prev = dist[i]
                }
            }
            var next = dist[0]
            repeat(2) {
                for (i in n - 1 downTo 0) {
                    val cand = next + 1
                    if (cand < dist[i]) dist[i] = cand
                    next = dist[i]
                }
            }
        } else {
            for (i in 1 until n) {
                val cand = dist[i - 1] + 1
                if (cand < dist[i]) dist[i] = cand
            }
            for (i in n - 2 downTo 0) {
                val cand = dist[i + 1] + 1
                if (cand < dist[i]) dist[i] = cand
            }
        }

        val kernels = arrayOfNulls<FloatArray>(radius + 1)
        for (level in 0..radius) {
            val ls = s * level / radius
            kernels[level] = if (ls <= MIN_SIGMA) null else gaussianKernel(ls)
        }

        val out = ArrayList<VecPoint>(n)
        for (i in 0 until n) {
            if (!closed && (i == 0 || i == n - 1)) {
                out.add(points[i])
                continue
            }
            val level = if (dist[i] > radius) radius else dist[i]
            val kernel = kernels[level]
            if (kernel == null) {
                out.add(points[i])
                continue
            }
            out.add(sampleSmoothed(points, kernel, i, n, closed))
        }
        return out
    }

    fun movingAverage(values: FloatArray, window: Int): FloatArray {
        val n = values.size
        if (n == 0) return FloatArray(0)
        var win = if (window < 1) 1 else window
        if (win and 1 == 0) win++
        if (win == 1 || n == 1) return values.copyOf()

        val r = win / 2
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + values[i]

        val out = FloatArray(n)
        val firstValue = values[0].toDouble()
        val lastValue = values[n - 1].toDouble()
        for (i in 0 until n) {
            val lo = i - r
            val hi = i + r
            val a = if (lo < 0) 0 else lo
            val b = if (hi > n - 1) n - 1 else hi
            var sum = prefix[b + 1] - prefix[a]
            if (lo < 0) sum += -lo * firstValue
            if (hi > n - 1) sum += (hi - (n - 1)) * lastValue
            out[i] = (sum / win).toFloat()
        }
        return out
    }

    private const val MIN_SIGMA = 0.05f
    private const val MAX_SIGMA = 64f
    private const val MAX_CHAIKIN_ITERATIONS = 8
    private const val MAX_CHAIKIN_POINTS = 1_000_000

    private fun convolve(points: List<VecPoint>, kernel: FloatArray, closed: Boolean): List<VecPoint> {
        val n = points.size
        val out = ArrayList<VecPoint>(n)
        for (i in 0 until n) {
            if (!closed && (i == 0 || i == n - 1)) {
                out.add(points[i])
                continue
            }
            out.add(sampleSmoothed(points, kernel, i, n, closed))
        }
        return out
    }

    private fun sampleSmoothed(
        points: List<VecPoint>,
        kernel: FloatArray,
        i: Int,
        n: Int,
        closed: Boolean,
    ): VecPoint {
        val r = kernel.size / 2
        var sx = 0f
        var sy = 0f
        for (j in -r..r) {
            var idx = i + j
            if (closed) {
                idx = ((idx % n) + n) % n
            } else {
                if (idx < 0) idx = 0
                if (idx > n - 1) idx = n - 1
            }
            val p = points[idx]
            val w = kernel[j + r]
            sx += p.x * w
            sy += p.y * w
        }
        return VecPoint(sx, sy)
    }

    private fun gaussianKernel(sigma: Float): FloatArray {
        val s = if (sigma > MAX_SIGMA) MAX_SIGMA else sigma
        val r = ceil(3.0 * s).toInt().coerceAtLeast(1)
        val size = 2 * r + 1
        val k = FloatArray(size)
        val twoSigmaSq = 2.0 * s * s
        var sum = 0.0
        for (i in 0 until size) {
            val d = (i - r).toDouble()
            val v = exp(-d * d / twoSigmaSq)
            k[i] = v.toFloat()
            sum += v
        }
        val inv = (1.0 / sum).toFloat()
        for (i in 0 until size) k[i] *= inv
        return k
    }
}
