package com.offlinetracer.vector

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Boolean2D {

    enum class BoolOp { UNION, INTERSECT, DIFFERENCE, XOR }

    fun polygonArea(points: List<VecPoint>): Float {
        val n = points.size
        if (n < 3) return 0f
        var acc = 0.0
        var j = n - 1
        for (i in 0 until n) {
            val a = points[j]
            val b = points[i]
            acc += a.x.toDouble() * b.y - b.x.toDouble() * a.y
            j = i
        }
        return (acc * 0.5).toFloat()
    }

    fun pointInPolygon(points: List<VecPoint>, x: Float, y: Float, rule: FillRule): Boolean {
        val n = points.size
        if (n < 3) return false
        if (rule == FillRule.EVENODD) {
            var inside = false
            var j = n - 1
            for (i in 0 until n) {
                val pi = points[i]
                val pj = points[j]
                if ((pi.y > y) != (pj.y > y)) {
                    val t = (y - pi.y) / (pj.y - pi.y)
                    if (x < pi.x + t * (pj.x - pi.x)) inside = !inside
                }
                j = i
            }
            return inside
        }
        var wind = 0
        var j = n - 1
        for (i in 0 until n) {
            val a = points[j]
            val b = points[i]
            if (a.y <= y) {
                if (b.y > y && isLeft(a.x, a.y, b.x, b.y, x, y) > 0f) wind++
            } else {
                if (b.y <= y && isLeft(a.x, a.y, b.x, b.y, x, y) < 0f) wind--
            }
            j = i
        }
        return wind != 0
    }

    fun apply(a: List<VecPath>, b: List<VecPath>, op: BoolOp, flatten: Float = 0.25f): List<VecPath> {
        val tol = if (flatten > 1e-4f && flatten.isFinite()) flatten else 0.25f
        val ringsA = toRings(a, tol)
        val ringsB = toRings(b, tol)

        if (ringsA.isEmpty() && ringsB.isEmpty()) return emptyList()
        if (ringsA.isEmpty()) {
            return when (op) {
                BoolOp.UNION, BoolOp.XOR -> b
                BoolOp.INTERSECT, BoolOp.DIFFERENCE -> emptyList()
            }
        }
        if (ringsB.isEmpty()) {
            return when (op) {
                BoolOp.UNION, BoolOp.XOR, BoolOp.DIFFERENCE -> a
                BoolOp.INTERSECT -> emptyList()
            }
        }

        val boundsA = ringBounds(ringsA)
        val boundsB = ringBounds(ringsB)
        val disjoint = boundsA[2] < boundsB[0] || boundsB[2] < boundsA[0] ||
            boundsA[3] < boundsB[1] || boundsB[3] < boundsA[1]
        if (disjoint) {
            return when (op) {
                BoolOp.UNION, BoolOp.XOR -> a + b
                BoolOp.INTERSECT -> emptyList()
                BoolOp.DIFFERENCE -> a
            }
        }

        val extent = max(
            max(boundsA[2] - boundsA[0], boundsA[3] - boundsA[1]),
            max(boundsB[2] - boundsB[0], boundsB[3] - boundsB[1]),
        )
        val eps = max(1e-5f, extent * 1e-6f)

        val segsA = toSegments(ringsA)
        val segsB = toSegments(ringsB)
        if (segsA.n == 0 || segsB.n == 0) return emptyList()

        val kept = SegList(segsA.n + segsB.n)
        classify(segsA, segsB, ringsB, op, true, eps, kept)
        classify(segsB, segsA, ringsA, op, false, eps, kept)
        if (kept.n == 0) return emptyList()

        return chain(kept, eps)
    }

    private class SegList(capacity: Int) {
        @JvmField var x0 = FloatArray(if (capacity > 8) capacity else 8)
        @JvmField var y0 = FloatArray(if (capacity > 8) capacity else 8)
        @JvmField var x1 = FloatArray(if (capacity > 8) capacity else 8)
        @JvmField var y1 = FloatArray(if (capacity > 8) capacity else 8)
        @JvmField var n = 0

        fun add(ax: Float, ay: Float, bx: Float, by: Float) {
            if (n == x0.size) {
                val c = n * 2
                x0 = x0.copyOf(c); y0 = y0.copyOf(c); x1 = x1.copyOf(c); y1 = y1.copyOf(c)
            }
            x0[n] = ax; y0[n] = ay; x1[n] = bx; y1[n] = by
            n++
        }
    }

    private fun toRings(paths: List<VecPath>, tol: Float): List<FloatArray> {
        val out = ArrayList<FloatArray>(paths.size)
        for (p in paths) {
            val pts = p.flatten(tol)
            if (pts.size < 3) continue
            var count = pts.size
            val first = pts[0]
            val last = pts[count - 1]
            if (abs(first.x - last.x) <= 1e-6f && abs(first.y - last.y) <= 1e-6f) count--
            if (count < 3) continue
            val ring = FloatArray(count * 2)
            var k = 0
            for (i in 0 until count) {
                val q = pts[i]
                if (!q.x.isFinite() || !q.y.isFinite()) continue
                ring[k++] = q.x
                ring[k++] = q.y
            }
            if (k >= 6) out.add(if (k == ring.size) ring else ring.copyOf(k))
        }
        return out
    }

    private fun ringBounds(rings: List<FloatArray>): FloatArray {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (r in rings) {
            var i = 0
            while (i < r.size) {
                val x = r[i]
                val y = r[i + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                i += 2
            }
        }
        if (minX > maxX) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun toSegments(rings: List<FloatArray>): SegList {
        var total = 0
        for (r in rings) total += r.size / 2
        val out = SegList(total)
        for (r in rings) {
            val m = r.size / 2
            for (i in 0 until m) {
                val j = (i + 1) % m
                val ax = r[i * 2]; val ay = r[i * 2 + 1]
                val bx = r[j * 2]; val by = r[j * 2 + 1]
                if (ax == bx && ay == by) continue
                out.add(ax, ay, bx, by)
            }
        }
        return out
    }

    private fun classify(
        src: SegList,
        other: SegList,
        otherRings: List<FloatArray>,
        op: BoolOp,
        isA: Boolean,
        eps: Float,
        out: SegList,
    ) {
        var ts = FloatArray(16)
        for (i in 0 until src.n) {
            val ax = src.x0[i]; val ay = src.y0[i]
            val bx = src.x1[i]; val by = src.y1[i]
            val dx = bx - ax
            val dy = by - ay
            val loX = min(ax, bx); val hiX = max(ax, bx)
            val loY = min(ay, by); val hiY = max(ay, by)

            var nt = 0
            for (j in 0 until other.n) {
                val cxx = other.x0[j]; val cyy = other.y0[j]
                val dxx = other.x1[j]; val dyy = other.y1[j]
                if (max(cxx, dxx) < loX || min(cxx, dxx) > hiX) continue
                if (max(cyy, dyy) < loY || min(cyy, dyy) > hiY) continue
                val ex = dxx - cxx
                val ey = dyy - cyy
                val den = dx * ey - dy * ex
                if (abs(den) < 1e-12f) continue
                val rx = cxx - ax
                val ry = cyy - ay
                val t = (rx * ey - ry * ex) / den
                val u = (rx * dy - ry * dx) / den
                if (t <= 1e-6f || t >= 1f - 1e-6f) continue
                if (u < -1e-6f || u > 1f + 1e-6f) continue
                if (nt == ts.size) ts = ts.copyOf(nt * 2)
                ts[nt++] = t
                }
            if (nt > 1) java.util.Arrays.sort(ts, 0, nt)

            var prev = 0f
            var k = 0
            while (k <= nt) {
                val next = if (k == nt) 1f else ts[k]
                k++
                if (next - prev < 1e-7f) { prev = next; continue }
                val px0 = ax + dx * prev
                val py0 = ay + dy * prev
                val px1 = ax + dx * next
                val py1 = ay + dy * next
                prev = next

                val mx = (px0 + px1) * 0.5f
                val my = (py0 + py1) * 0.5f

                if (onBoundary(other, mx, my, eps)) {
                    if (!isA) continue
                    when (op) {
                        BoolOp.UNION, BoolOp.INTERSECT -> out.add(px0, py0, px1, py1)
                        BoolOp.DIFFERENCE, BoolOp.XOR -> {}
                    }
                    continue
                }

                val inside = windingOf(otherRings, mx, my) != 0
                when (op) {
                    BoolOp.UNION -> if (!inside) out.add(px0, py0, px1, py1)
                    BoolOp.INTERSECT -> if (inside) out.add(px0, py0, px1, py1)
                    BoolOp.DIFFERENCE ->
                        if (isA) { if (!inside) out.add(px0, py0, px1, py1) }
                        else { if (inside) out.add(px1, py1, px0, py0) }
                    BoolOp.XOR ->
                        if (inside) out.add(px1, py1, px0, py0) else out.add(px0, py0, px1, py1)
                }
            }
        }
    }

    private fun onBoundary(segs: SegList, px: Float, py: Float, eps: Float): Boolean {
        val e2 = eps * eps
        for (i in 0 until segs.n) {
            if (pointSegDist2(px, py, segs.x0[i], segs.y0[i], segs.x1[i], segs.y1[i]) <= e2) return true
        }
        return false
    }

    private fun pointSegDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 <= 1e-20f) 0f else ((px - ax) * dx + (py - ay) * dy) / len2
        if (t < 0f) t = 0f
        if (t > 1f) t = 1f
        val qx = ax + dx * t - px
        val qy = ay + dy * t - py
        return qx * qx + qy * qy
    }

    private fun windingOf(rings: List<FloatArray>, px: Float, py: Float): Int {
        var wind = 0
        for (r in rings) {
            val m = r.size / 2
            var j = m - 1
            for (i in 0 until m) {
                val axf = r[j * 2]; val ayf = r[j * 2 + 1]
                val bxf = r[i * 2]; val byf = r[i * 2 + 1]
                if (ayf <= py) {
                    if (byf > py && isLeft(axf, ayf, bxf, byf, px, py) > 0f) wind++
                } else {
                    if (byf <= py && isLeft(axf, ayf, bxf, byf, px, py) < 0f) wind--
                }
                j = i
            }
        }
        return wind
    }

    private fun isLeft(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
        (bx - ax) * (py - ay) - (px - ax) * (by - ay)

    private fun chain(segs: SegList, eps: Float): List<VecPath> {
        val quant = if (eps > 0f) eps * 10f else 1e-4f
        val index = HashMap<Long, MutableList<Int>>(segs.n * 2)
        for (i in 0 until segs.n) {
            val qx = Math.round(segs.x0[i] / quant).toLong()
            val qy = Math.round(segs.y0[i] / quant).toLong()
            index.getOrPut(pack(qx, qy)) { ArrayList(2) }.add(i)
        }
        val used = BooleanArray(segs.n)
        val out = ArrayList<VecPath>()
        for (seed in 0 until segs.n) {
            if (used[seed]) continue
            val startX = segs.x0[seed]
            val startY = segs.y0[seed]
            val pts = ArrayList<VecPoint>(16)
            pts.add(VecPoint(startX, startY))
            var cur = seed
            var steps = 0
            while (steps <= segs.n) {
                used[cur] = true
                val ex = segs.x1[cur]
                val ey = segs.y1[cur]
                pts.add(VecPoint(ex, ey))
                if (abs(ex - startX) <= quant && abs(ey - startY) <= quant) break
                val next = findNext(index, ex, ey, quant, used)
                if (next < 0) break
                cur = next
                steps++
            }
            if (pts.size >= 2) {
                val f = pts[0]
                val l = pts[pts.size - 1]
                if (abs(f.x - l.x) <= quant && abs(f.y - l.y) <= quant) pts.removeAt(pts.size - 1)
            }
            if (pts.size < 3) continue
            val segsOut = ArrayList<VecSeg>(pts.size - 1)
            for (i in 1 until pts.size) segsOut.add(VecSeg.Line(pts[i]))
            out.add(VecPath(pts[0], segsOut, true))
        }
        return out
    }

    private fun findNext(
        index: HashMap<Long, MutableList<Int>>,
        x: Float, y: Float, quant: Float, used: BooleanArray,
    ): Int {
        val qx = Math.round(x / quant).toLong()
        val qy = Math.round(y / quant).toLong()
        for (dy in -1..1) {
            for (dx in -1..1) {
                val list = index[pack(qx + dx, qy + dy)] ?: continue
                for (idx in list) if (!used[idx]) return idx
            }
        }
        return -1
    }

    private fun pack(qx: Long, qy: Long): Long = (qx shl 32) xor (qy and 0xFFFF_FFFFL)
}

typealias BoolOp = Boolean2D.BoolOp
