package com.offlinetracer.vector

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

object Simplify {

    fun douglasPeucker(points: List<VecPoint>, epsilon: Float): List<VecPoint> {
        val n = points.size
        if (n <= 2 || epsilon <= 0f) return points

        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val p = points[i]
            xs[i] = p.x
            ys[i] = p.y
        }

        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true

        var stack = IntArray(64)
        var sp = 0
        stack[sp++] = 0
        stack[sp++] = n - 1

        val epsSq = epsilon.toDouble() * epsilon.toDouble()

        while (sp > 0) {
            val last = stack[--sp]
            val first = stack[--sp]
            if (last <= first + 1) continue

            val x0 = xs[first].toDouble()
            val y0 = ys[first].toDouble()
            val dx = xs[last] - x0
            val dy = ys[last] - y0
            val lenSq = dx * dx + dy * dy

            var bestIndex = -1
            var bestValue = 0.0
            var split = false

            if (lenSq < 1e-12) {
                for (i in first + 1 until last) {
                    val ex = xs[i] - x0
                    val ey = ys[i] - y0
                    val d = ex * ex + ey * ey
                    if (d > bestValue) {
                        bestValue = d
                        bestIndex = i
                    }
                }
                split = bestIndex >= 0 && bestValue > epsSq
            } else {
                for (i in first + 1 until last) {
                    val cross = (xs[i] - x0) * dy - (ys[i] - y0) * dx
                    val d = cross * cross
                    if (d > bestValue) {
                        bestValue = d
                        bestIndex = i
                    }
                }
                split = bestIndex >= 0 && bestValue > epsSq * lenSq
            }

            if (split) {
                keep[bestIndex] = true
                if (sp + 4 > stack.size) stack = stack.copyOf(stack.size shl 1)
                stack[sp++] = first
                stack[sp++] = bestIndex
                stack[sp++] = bestIndex
                stack[sp++] = last
            }
        }

        val out = ArrayList<VecPoint>(n)
        for (i in 0 until n) if (keep[i]) out.add(points[i])
        return out
    }

    fun removeCollinear(points: List<VecPoint>, tolerance: Float = 0.05f): List<VecPoint> {
        val n = points.size
        if (n <= 2 || tolerance <= 0f) return points

        val out = ArrayList<VecPoint>(n)
        var anchor = points[0]
        out.add(anchor)
        val tolSq = tolerance.toDouble() * tolerance.toDouble()

        for (i in 1 until n - 1) {
            val p = points[i]
            val q = points[i + 1]
            val dx = (q.x - anchor.x).toDouble()
            val dy = (q.y - anchor.y).toDouble()
            val lenSq = dx * dx + dy * dy
            val keep: Boolean
            if (lenSq < 1e-12) {
                keep = true
            } else {
                val cross = (p.x - anchor.x) * dy - (p.y - anchor.y) * dx
                keep = cross * cross > tolSq * lenSq
            }
            if (keep) {
                out.add(p)
                anchor = p
            }
        }
        out.add(points[n - 1])
        return out
    }

    fun detectCorners(
        points: List<VecPoint>,
        thresholdDegrees: Float = 100f,
        window: Int = 3,
        closed: Boolean = false,
    ): IntArray {
        val n = points.size
        if (n < 3) return IntArray(0)

        var w = if (window < 1) 1 else window
        if (closed) {
            val maxW = n / 3
            if (w > maxW) w = if (maxW < 1) 1 else maxW
        }

        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val p = points[i]
            xs[i] = p.x
            ys[i] = p.y
        }

        val cosThreshold = cos(thresholdDegrees.toDouble() * PI / 180.0)
        val score = DoubleArray(n) { NOT_A_CORNER }

        val lo = if (closed) 0 else 1
        val hi = if (closed) n - 1 else n - 2
        for (i in lo..hi) {
            val ib = arm(xs, ys, n, i, 1, w, closed, indexAt(i, -w, n, closed))
            val ia = arm(xs, ys, n, i, -1, w, closed, ib)
            if (ia == ib) continue
            val ax = (xs[ia] - xs[i]).toDouble()
            val ay = (ys[ia] - ys[i]).toDouble()
            val bx = (xs[ib] - xs[i]).toDouble()
            val by = (ys[ib] - ys[i]).toDouble()
            val la = sqrt(ax * ax + ay * ay)
            val lb = sqrt(bx * bx + by * by)
            if (la < 1e-9 || lb < 1e-9) continue
            val c = (ax * bx + ay * by) / (la * lb)
            if (c > cosThreshold) score[i] = c
        }

        val picked = IntArray(n)
        var m = 0
        for (i in 0 until n) {
            val s = score[i]
            if (s == NOT_A_CORNER) continue
            var best = true
            for (d in -(w - 1)..(w - 1)) {
                if (d == 0) continue
                var j = i + d
                if (closed) {
                    j = ((j % n) + n) % n
                } else if (j < 0 || j >= n) {
                    continue
                }
                val sj = score[j]
                if (sj > s) {
                    best = false
                    break
                }
            }
            if (best) picked[m++] = i
        }
        return picked.copyOf(m)
    }

    fun splitAtCorners(points: List<VecPoint>, corners: IntArray): List<List<VecPoint>> {
        val n = points.size
        if (n == 0) return emptyList()
        if (n < 2 || corners.isEmpty()) return listOf(points)

        val sorted = corners.copyOf()
        sorted.sort()

        val bounds = IntArray(sorted.size + 2)
        var m = 0
        bounds[m++] = 0
        for (c in sorted) {
            if (c > 0 && c < n - 1 && c != bounds[m - 1]) bounds[m++] = c
        }
        if (bounds[m - 1] != n - 1) bounds[m++] = n - 1

        val out = ArrayList<List<VecPoint>>(m - 1)
        for (i in 0 until m - 1) {
            val a = bounds[i]
            val b = bounds[i + 1]
            if (b <= a) continue
            out.add(ArrayList(points.subList(a, b + 1)))
        }
        return if (out.isEmpty()) listOf(points) else out
    }

    fun resample(points: List<VecPoint>, spacing: Float): List<VecPoint> {
        val n = points.size
        if (n < 2) return points
        val step = (if (spacing < MIN_SPACING) MIN_SPACING else spacing).toDouble()

        val out = ArrayList<VecPoint>(n)
        val first = points[0]
        out.add(first)

        var cx = first.x.toDouble()
        var cy = first.y.toDouble()
        var remaining = step
        var i = 1
        while (i < n) {
            val tx = points[i].x.toDouble()
            val ty = points[i].y.toDouble()
            val dx = tx - cx
            val dy = ty - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d < 1e-12) {
                i++
                continue
            }
            if (d >= remaining) {
                val t = remaining / d
                cx += dx * t
                cy += dy * t
                out.add(VecPoint(cx.toFloat(), cy.toFloat()))
                remaining = step
            } else {
                remaining -= d
                cx = tx
                cy = ty
                i++
            }
        }

        val last = points[n - 1]
        val tail = out[out.size - 1]
        if (tail.x != last.x || tail.y != last.y) out.add(last)
        return out
    }

    private fun indexAt(i: Int, d: Int, n: Int, closed: Boolean): Int =
        if (closed) (((i + d) % n) + n) % n
        else if (i + d < 0) 0 else if (i + d > n - 1) n - 1 else i + d

    private fun arm(
        xs: FloatArray, ys: FloatArray, n: Int,
        i: Int, dir: Int, w: Int, closed: Boolean, stop: Int,
    ): Int {
        var j = indexAt(i, dir * w, n, closed)
        for (e in 1..w) {
            val dx = (xs[j] - xs[i]).toDouble()
            val dy = (ys[j] - ys[i]).toDouble()
            if (dx * dx + dy * dy >= MIN_ARM_SQ) break
            val next = indexAt(i, dir * (w + e), n, closed)
            if (next == stop || next == i || next == j) break
            j = next
        }
        return j
    }

    private const val MIN_ARM_SQ = 1.0

    private const val NOT_A_CORNER = -2.0

    private const val MIN_SPACING = 0.01f
}
