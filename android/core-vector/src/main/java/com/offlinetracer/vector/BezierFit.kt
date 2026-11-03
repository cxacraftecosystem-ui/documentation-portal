package com.offlinetracer.vector

import kotlin.math.abs
import kotlin.math.sqrt

object BezierFit {

    private const val DUP_EPS = 1e-7

    private const val NEWTON_ITERATIONS = 4


    private const val MAX_ALPHA_RATIO = 10.0

    fun fit(points: List<VecPoint>, error: Float, maxDepth: Int = 24): List<VecSeg.Cubic> =
        fitInternal(points, error, maxDepth, null)

    fun fitPath(
        points: List<VecPoint>,
        error: Float,
        closed: Boolean,
        cornerThresholdDegrees: Float = 100f,
    ): VecPath {
        val clean = dedupe(points, closed)
        if (clean.isEmpty()) return VecPath(VecPoint(0f, 0f), emptyList(), closed)
        if (clean.size == 1) return VecPath(clean[0], emptyList(), closed)
        if (clean.size == 2) {
            return VecPath(clean[0], fit(clean, error), false)
        }

        val n = clean.size
        val corners = normaliseCorners(Simplify.detectCorners(clean, cornerThresholdDegrees, 3, closed), n, closed)

        if (!closed) {
            val segs = ArrayList<VecSeg>(n)
            var from = 0
            for (c in corners) {
                if (c <= from || c >= n - 1) continue
                segs.addAll(fit(clean.subList(from, c + 1), error))
                from = c
            }
            segs.addAll(fit(clean.subList(from, n), error))
            return VecPath(clean[0], segs, false)
        }

        if (corners.isEmpty()) {
            val run = ArrayList<VecPoint>(n + 1)
            run.addAll(clean)
            run.add(clean[0])
            val t = seamTangent(clean)
            val segs = fitInternal(run, error, 24, doubleArrayOf(t[0], t[1], -t[0], -t[1]))
            return VecPath(clean[0], segs, true)
        }

        val pivot = corners[0]
        val rotated = ArrayList<VecPoint>(n + 1)
        for (i in 0 until n) rotated.add(clean[(pivot + i) % n])
        rotated.add(clean[pivot])

        val segs = ArrayList<VecSeg>(n)
        var from = 0
        for (k in 1 until corners.size) {
            val c = corners[k] - pivot
            if (c <= from || c >= n) continue
            segs.addAll(fit(rotated.subList(from, c + 1), error))
            from = c
        }
        segs.addAll(fit(rotated.subList(from, n + 1), error))
        return VecPath(rotated[0], segs, true)
    }

    private class Poly(@JvmField val xs: DoubleArray, @JvmField val ys: DoubleArray, @JvmField val n: Int)

    private class Task(
        @JvmField val first: Int,
        @JvmField val last: Int,
        @JvmField val t1x: Double,
        @JvmField val t1y: Double,
        @JvmField val t2x: Double,
        @JvmField val t2y: Double,
        @JvmField val depth: Int,
    )

    private fun fitInternal(
        points: List<VecPoint>,
        error: Float,
        maxDepth: Int,
        overrideTangents: DoubleArray?,
    ): List<VecSeg.Cubic> {
        val poly = toPoly(points)
        val n = poly.n
        if (n < 2) return emptyList()
        val xs = poly.xs
        val ys = poly.ys

        var t1x: Double
        var t1y: Double
        var t2x: Double
        var t2y: Double
        if (overrideTangents != null && overrideTangents.size >= 4) {
            t1x = overrideTangents[0]; t1y = overrideTangents[1]
            t2x = overrideTangents[2]; t2y = overrideTangents[3]
        } else {
            val tan = DoubleArray(4)
            val third = n > 2
            endpointTangent(
                xs[0], ys[0], xs[1], ys[1],
                if (third) xs[2] else 0.0, if (third) ys[2] else 0.0, third, tan, 0,
            )
            endpointTangent(
                xs[n - 1], ys[n - 1], xs[n - 2], ys[n - 2],
                if (third) xs[n - 3] else 0.0, if (third) ys[n - 3] else 0.0, third, tan, 2,
            )
            t1x = tan[0]; t1y = tan[1]
            t2x = tan[2]; t2y = tan[3]
        }

        val err = if (error > 0f && error.isFinite()) error.toDouble() else 1e-9
        val cap = if (maxDepth < 0) 0 else if (maxDepth > 64) 64 else maxDepth

        val out = ArrayList<VecSeg.Cubic>(16)
        val stack = ArrayList<Task>(16)
        stack.add(Task(0, n - 1, t1x, t1y, t2x, t2y, 0))
        val splitOut = IntArray(1)

        while (stack.isNotEmpty()) {
            val task = stack.removeAt(stack.size - 1)
            val first = task.first
            val last = task.last
            val count = last - first + 1

            if (count < 2) continue

            if (count == 2) {
                val dist = distance(xs[first], ys[first], xs[last], ys[last]) / 3.0
                emit(
                    out,
                    xs[first] + task.t1x * dist, ys[first] + task.t1y * dist,
                    xs[last] + task.t2x * dist, ys[last] + task.t2y * dist,
                    xs[last], ys[last],
                )
                continue
            }

            val u = chordLengthParameterize(xs, ys, first, last)
            var bez = generateBezier(xs, ys, first, last, u, task.t1x, task.t1y, task.t2x, task.t2y)
            var maxErr = computeMaxError(xs, ys, first, last, bez, u, splitOut)

            if (maxErr < err) {
                emit(out, bez[2], bez[3], bez[4], bez[5], bez[6], bez[7])
                continue
            }

            var previousErr = maxErr
            for (i in 0 until NEWTON_ITERATIONS) {
                reparameterize(xs, ys, first, last, u, bez)
                bez = generateBezier(xs, ys, first, last, u, task.t1x, task.t1y, task.t2x, task.t2y)
                maxErr = computeMaxError(xs, ys, first, last, bez, u, splitOut)
                if (maxErr < err) break
                if (maxErr >= previousErr) break
                previousErr = maxErr
            }
            if (maxErr < err) {
                emit(out, bez[2], bez[3], bez[4], bez[5], bez[6], bez[7])
                continue
            }

            if (task.depth >= cap) {
                for (i in first until last) {
                    val ax = xs[i]; val ay = ys[i]
                    val bx = xs[i + 1]; val by = ys[i + 1]
                    emit(
                        out,
                        ax + (bx - ax) / 3.0, ay + (by - ay) / 3.0,
                        ax + (bx - ax) * 2.0 / 3.0, ay + (by - ay) * 2.0 / 3.0,
                        bx, by,
                    )
                }
                continue
            }

            var split = splitOut[0]
            if (split <= first) split = first + 1
            if (split >= last) split = last - 1

            var cx = xs[split - 1] - xs[split + 1]
            var cy = ys[split - 1] - ys[split + 1]
            var clen = sqrt(cx * cx + cy * cy)
            if (clen < DUP_EPS) {
                cx = xs[split] - xs[split - 1]
                cy = ys[split] - ys[split - 1]
                clen = sqrt(cx * cx + cy * cy)
                if (clen < DUP_EPS) { cx = 1.0; cy = 0.0; clen = 1.0 }
            }
            cx /= clen; cy /= clen

            stack.add(Task(split, last, -cx, -cy, task.t2x, task.t2y, task.depth + 1))
            stack.add(Task(first, split, task.t1x, task.t1y, cx, cy, task.depth + 1))
        }
        return out
    }

    private fun generateBezier(
        xs: DoubleArray, ys: DoubleArray, first: Int, last: Int, u: DoubleArray,
        t1x: Double, t1y: Double, t2x: Double, t2y: Double,
    ): DoubleArray {
        val count = last - first + 1
        val px0 = xs[first]; val py0 = ys[first]
        val px3 = xs[last]; val py3 = ys[last]

        var c00 = 0.0; var c01 = 0.0; var c11 = 0.0
        var xr0 = 0.0; var xr1 = 0.0

        for (i in 0 until count) {
            val ui = u[i]
            val omu = 1.0 - ui
            val b0 = omu * omu * omu
            val b1 = 3.0 * ui * omu * omu
            val b2 = 3.0 * ui * ui * omu
            val b3 = ui * ui * ui

            val a0x = t1x * b1; val a0y = t1y * b1
            val a1x = t2x * b2; val a1y = t2y * b2

            c00 += a0x * a0x + a0y * a0y
            c01 += a0x * a1x + a0y * a1y
            c11 += a1x * a1x + a1y * a1y

            val tmpx = xs[first + i] - (px0 * (b0 + b1) + px3 * (b2 + b3))
            val tmpy = ys[first + i] - (py0 * (b0 + b1) + py3 * (b2 + b3))
            xr0 += a0x * tmpx + a0y * tmpy
            xr1 += a1x * tmpx + a1y * tmpy
        }

        val det = c00 * c11 - c01 * c01
        var alphaL: Double
        var alphaR: Double
        if (abs(det) > 1e-12 * (1.0 + abs(c00 * c11) + abs(c01 * c01))) {
            alphaL = (xr0 * c11 - xr1 * c01) / det
            alphaR = (c00 * xr1 - c01 * xr0) / det
        } else {
            alphaL = Double.NaN
            alphaR = Double.NaN
        }

        var refLen = distance(px0, py0, px3, py3)
        if (refLen < DUP_EPS) {
            var acc = 0.0
            for (i in first until last) acc += distance(xs[i], ys[i], xs[i + 1], ys[i + 1])
            refLen = if (acc > DUP_EPS) acc else 1.0
        }

        val minAlpha = 1e-6 * refLen
        val maxAlpha = MAX_ALPHA_RATIO * refLen
        if (!alphaL.isFinite() || !alphaR.isFinite() ||
            alphaL < minAlpha || alphaR < minAlpha ||
            alphaL > maxAlpha || alphaR > maxAlpha
        ) {
            val d = refLen / 3.0
            alphaL = d
            alphaR = d
        }

        return doubleArrayOf(
            px0, py0,
            px0 + t1x * alphaL, py0 + t1y * alphaL,
            px3 + t2x * alphaR, py3 + t2y * alphaR,
            px3, py3,
        )
    }

    private fun chordLengthParameterize(xs: DoubleArray, ys: DoubleArray, first: Int, last: Int): DoubleArray {
        val count = last - first + 1
        val u = DoubleArray(count)
        for (i in 1 until count) {
            u[i] = u[i - 1] + distance(xs[first + i - 1], ys[first + i - 1], xs[first + i], ys[first + i])
        }
        val total = u[count - 1]
        if (total < DUP_EPS) {
            for (i in 0 until count) u[i] = i.toDouble() / (count - 1).toDouble()
        } else {
            for (i in 1 until count) u[i] /= total
            u[count - 1] = 1.0
        }
        return u
    }

    private fun endpointTangent(
        x0: Double, y0: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        hasThird: Boolean,
        out: DoubleArray,
        offset: Int,
    ) {
        var cx = x1 - x0
        var cy = y1 - y0
        var clen = sqrt(cx * cx + cy * cy)
        if (clen < DUP_EPS) { cx = 1.0; cy = 0.0; clen = 1.0 }
        cx /= clen
        cy /= clen

        if (hasThird) {
            val hx = (-3.0 * x0 + 4.0 * x1 - x2) * 0.5
            val hy = (-3.0 * y0 + 4.0 * y1 - y2) * 0.5
            val hlen = sqrt(hx * hx + hy * hy)
            if (hlen >= DUP_EPS) {
                val ux = hx / hlen
                val uy = hy / hlen
                if (ux * cx + uy * cy > 0.0) {
                    out[offset] = ux
                    out[offset + 1] = uy
                    return
                }
            }
        }
        out[offset] = cx
        out[offset + 1] = cy
    }

    private fun reparameterize(
        xs: DoubleArray, ys: DoubleArray, first: Int, last: Int, u: DoubleArray, bez: DoubleArray,
    ) {
        val count = last - first + 1
        for (i in 0 until count) {
            u[i] = newtonStep(bez, xs[first + i], ys[first + i], u[i])
        }
        u[0] = 0.0
        u[count - 1] = 1.0
    }

    private fun newtonStep(b: DoubleArray, px: Double, py: Double, u: Double): Double {
        val omu = 1.0 - u
        val b0 = omu * omu * omu
        val b1 = 3.0 * u * omu * omu
        val b2 = 3.0 * u * u * omu
        val b3 = u * u * u
        val qx = b[0] * b0 + b[2] * b1 + b[4] * b2 + b[6] * b3
        val qy = b[1] * b0 + b[3] * b1 + b[5] * b2 + b[7] * b3

        val d0x = 3.0 * (b[2] - b[0]); val d0y = 3.0 * (b[3] - b[1])
        val d1x = 3.0 * (b[4] - b[2]); val d1y = 3.0 * (b[5] - b[3])
        val d2x = 3.0 * (b[6] - b[4]); val d2y = 3.0 * (b[7] - b[5])
        val e0x = 2.0 * (d1x - d0x); val e0y = 2.0 * (d1y - d0y)
        val e1x = 2.0 * (d2x - d1x); val e1y = 2.0 * (d2y - d1y)

        val q1x = d0x * omu * omu + d1x * 2.0 * u * omu + d2x * u * u
        val q1y = d0y * omu * omu + d1y * 2.0 * u * omu + d2y * u * u
        val q2x = e0x * omu + e1x * u
        val q2y = e0y * omu + e1y * u

        val dx = qx - px
        val dy = qy - py
        val numerator = dx * q1x + dy * q1y
        val denominator = q1x * q1x + q1y * q1y + dx * q2x + dy * q2y
        if (abs(denominator) < 1e-12) return u
        val next = u - numerator / denominator
        if (!next.isFinite()) return u
        return if (next < 0.0) 0.0 else if (next > 1.0) 1.0 else next
    }

    private fun computeMaxError(
        xs: DoubleArray, ys: DoubleArray, first: Int, last: Int, b: DoubleArray, u: DoubleArray,
        splitOut: IntArray,
    ): Double {
        var maxDist = 0.0
        splitOut[0] = (first + last) / 2
        for (i in first + 1 until last) {
            val ui = u[i - first]
            val omu = 1.0 - ui
            val b0 = omu * omu * omu
            val b1 = 3.0 * ui * omu * omu
            val b2 = 3.0 * ui * ui * omu
            val b3 = ui * ui * ui
            val qx = b[0] * b0 + b[2] * b1 + b[4] * b2 + b[6] * b3
            val qy = b[1] * b0 + b[3] * b1 + b[5] * b2 + b[7] * b3
            val dx = qx - xs[i]
            val dy = qy - ys[i]
            val d = dx * dx + dy * dy
            if (d > maxDist) {
                maxDist = d
                splitOut[0] = i
            }
        }
        return maxDist
    }

    private fun emit(
        out: MutableList<VecSeg.Cubic>,
        c1x: Double, c1y: Double, c2x: Double, c2y: Double, ex: Double, ey: Double,
    ) {
        out.add(
            VecSeg.Cubic(
                VecPoint(c1x.toFloat(), c1y.toFloat()),
                VecPoint(c2x.toFloat(), c2y.toFloat()),
                VecPoint(ex.toFloat(), ey.toFloat()),
            )
        )
    }

    private fun distance(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }

    private fun toPoly(points: List<VecPoint>): Poly {
        val cap = points.size
        val xs = DoubleArray(if (cap > 0) cap else 1)
        val ys = DoubleArray(if (cap > 0) cap else 1)
        var m = 0
        for (i in 0 until cap) {
            val p = points[i]
            val x = p.x.toDouble()
            val y = p.y.toDouble()
            if (!x.isFinite() || !y.isFinite()) continue
            if (m > 0 && abs(x - xs[m - 1]) <= DUP_EPS && abs(y - ys[m - 1]) <= DUP_EPS) continue
            xs[m] = x
            ys[m] = y
            m++
        }
        return Poly(xs, ys, m)
    }

    private fun dedupe(points: List<VecPoint>, closed: Boolean): List<VecPoint> {
        val out = ArrayList<VecPoint>(points.size)
        for (p in points) {
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            if (out.isNotEmpty()) {
                val q = out[out.size - 1]
                if (abs(p.x - q.x) <= 1e-6f && abs(p.y - q.y) <= 1e-6f) continue
            }
            out.add(p)
        }
        if (closed && out.size > 1) {
            val a = out[0]
            val b = out[out.size - 1]
            if (abs(a.x - b.x) <= 1e-6f && abs(a.y - b.y) <= 1e-6f) out.removeAt(out.size - 1)
        }
        return out
    }

    private fun normaliseCorners(corners: IntArray, n: Int, closed: Boolean): IntArray {
        if (corners.isEmpty()) return corners
        val sorted = corners.copyOf()
        sorted.sort()
        val out = IntArray(sorted.size)
        var m = 0
        val lo = if (closed) 0 else 1
        val hi = if (closed) n - 1 else n - 2
        for (c in sorted) {
            if (c < lo || c > hi) continue
            if (m > 0 && out[m - 1] == c) continue
            out[m] = c
            m++
        }
        return if (m == sorted.size) out else out.copyOf(m)
    }

    private fun seamTangent(pts: List<VecPoint>): DoubleArray {
        val n = pts.size
        var ax = (pts[0].x - pts[n - 1].x).toDouble()
        var ay = (pts[0].y - pts[n - 1].y).toDouble()
        var la = sqrt(ax * ax + ay * ay)
        if (la < DUP_EPS) { ax = 1.0; ay = 0.0; la = 1.0 }
        var bx = (pts[1].x - pts[0].x).toDouble()
        var by = (pts[1].y - pts[0].y).toDouble()
        var lb = sqrt(bx * bx + by * by)
        if (lb < DUP_EPS) { bx = 1.0; by = 0.0; lb = 1.0 }
        var tx = ax / la + bx / lb
        var ty = ay / la + by / lb
        var lt = sqrt(tx * tx + ty * ty)
        if (lt < DUP_EPS) { tx = bx / lb; ty = by / lb; lt = 1.0 }
        return doubleArrayOf(tx / lt, ty / lt)
    }
}
