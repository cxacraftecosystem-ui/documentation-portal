package com.offlinetracer.vector

import kotlin.math.ceil
import kotlin.math.exp

/**
 * Polyline smoothing.
 *
 * Order matters and is fixed by ALGORITHMS §10.6: smoothing runs **before** Douglas–Peucker, never
 * after. Smoothing a simplified path just re-adds points DP was asked to remove, and the result is
 * both heavier and rounder than either stage alone would produce.
 *
 * Every function here leaves the endpoints of an **open** path exactly where they were. That is not
 * a nicety: adjacent centreline paths share the junction pixel they were traced from, and if two of
 * them move their shared endpoint by different amounts the SVG gets a visible hole at every
 * junction — the exact defect the junction-aware tracer exists to avoid.
 *
 * Helpers are scoped inside the object because sibling files in this package have other authors and
 * a top-level helper would be a package-wide redeclaration.
 */
object Smooth {

    /**
     * Chaikin corner cutting: each segment is replaced by its points at 1/4 and 3/4, [iterations]
     * times. Cheap, and the right tool against the 8-connected staircase a pixel tracer produces —
     * one iteration removes the staircase without visibly moving the stroke.
     *
     * For a closed path all segments are cut, including the closing one. For an open path the two
     * endpoints are kept exactly and the first/last cuts are omitted, so no near-duplicate point
     * appears next to an endpoint.
     *
     * [iterations] is clamped to 8 and the point count to about a million: each round doubles the
     * points, so an unclamped `iterations = 20` on a 200 000-point contour asks for 200 billion
     * points. Fewer than three points, or a non-positive [iterations], returns the input unchanged.
     */
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

    /**
     * Gaussian smoothing of the point sequence with the engine's standard kernel (radius `ceil(3σ)`,
     * normalised to 1). Indices wrap when [closed] and clamp to the ends otherwise — the same border
     * policy the imaging filters use.
     *
     * The two endpoints of an open path are pinned exactly (see the note on this object).
     * σ ≤ 0.05 is treated as identity, matching `Convolve.gaussianBlur`; σ is capped at 64 px,
     * beyond which the kernel is wider than any stroke and the answer is a straight line anyway.
     * Fewer than three points returns the input unchanged.
     */
    fun gaussian(points: List<VecPoint>, sigma: Float, closed: Boolean): List<VecPoint> {
        val n = points.size
        if (n < 3 || sigma <= MIN_SIGMA) return points
        val kernel = gaussianKernel(sigma)
        return convolve(points, kernel, closed)
    }

    /**
     * Gaussian smoothing whose kernel shrinks to nothing at every index in [corners] and ramps back
     * to the full [sigma] `ceil(3σ)` points away.
     *
     * The corner detector has just decided those points must survive Bezier fitting; a uniform
     * smoothing pass immediately afterwards would round off exactly what was protected, and the
     * building whose corners were preserved comes out a blob anyway. Corner points are therefore
     * left untouched and their neighbours are smoothed progressively, so there is no kink at the
     * boundary between the protected and smoothed regions either.
     *
     * Corner indices outside the point range are ignored. With no usable corners this is exactly
     * [gaussian]. Endpoints of an open path are pinned.
     */
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

        // Index distance to the nearest protected corner, by two sweeps. `n` is a safe "unreached"
        // value: it exceeds any real index distance and cannot overflow when incremented.
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
            // Two laps each way: the first lap carries values up to the wrap point, the second
            // carries them across it. Two are provably enough for a ring.
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

        // One kernel per distance level, not per point: the level is an integer in 0..radius, so
        // there are at most radius+1 distinct kernels and building them inside the point loop would
        // allocate once per vertex.
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

    /**
     * Centred moving average of [values] with an edge-clamped window, returned as a new array.
     *
     * [window] is coerced to at least 1 and forced odd — an even window has no centre and shifts the
     * whole series half a tap, which on stroke widths shows up as the taper starting in the wrong
     * place. An empty input returns an empty array; a window of 1 returns a copy.
     */
    fun movingAverage(values: FloatArray, window: Int): FloatArray {
        val n = values.size
        if (n == 0) return FloatArray(0)
        var win = if (window < 1) 1 else window
        if (win and 1 == 0) win++
        if (win == 1 || n == 1) return values.copyOf()

        val r = win / 2
        // Prefix sums in double: a long width array accumulated in float loses the low bits and the
        // averaged result drifts along the stroke.
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

    // -----------------------------------------------------------------------------------------

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

    /** Radius `ceil(3σ)`, taps `exp(-(i-r)²/2σ²)`, normalised — identical to `Convolve.gaussianKernel`. */
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
