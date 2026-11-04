package com.offlinetracer.vector

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object StrokeStyle {

    private const val MAX_ARC_STEPS = 64

    private const val EPS = 1e-6f

    fun outlineStroke(
        path: VecPath,
        width: Float,
        cap: LineCap,
        join: LineJoin,
        miterLimit: Float = 4f,
        flatten: Float = 0.25f,
    ): VecPath {
        val err = if (flatten > 1e-4f && flatten.isFinite()) flatten else 0.25f
        val half = width * 0.5f
        if (!half.isFinite() || half <= 0f) return VecPath(path.start, emptyList(), false)

        val pts = dedupe(path.flatten(err))
        if (pts.size < 2) {
            val c = if (pts.isEmpty()) path.start else pts[0]
            return if (cap == LineCap.ROUND) circle(c, half, err) else VecPath(c, emptyList(), false)
        }

        val limit = if (miterLimit.isFinite() && miterLimit >= 1f) miterLimit else 1f

        if (path.closed && pts.size >= 3) {
            val outerA = offsetSide(pts, half, join, limit, err, true)
            val outerB = offsetSide(pts.reversed(), half, join, limit, err, true)
            if (outerA.size < 3 || outerB.size < 3) return VecPath(pts[0], emptyList(), false)
            val ringOuter: List<VecPoint>
            val ringInner: List<VecPoint>
            if (abs(Boolean2D.polygonArea(outerA)) >= abs(Boolean2D.polygonArea(outerB))) {
                ringOuter = outerA; ringInner = outerB
            } else {
                ringOuter = outerB; ringInner = outerA
            }
            val combined = ArrayList<VecPoint>(ringOuter.size + ringInner.size + 2)
            combined.addAll(ringOuter)
            combined.add(ringOuter[0])
            combined.addAll(ringInner)
            combined.add(ringInner[0])
            return polygonToPath(combined)
        }

        val n = pts.size
        val left = offsetSide(pts, half, join, limit, err, false)
        val right = offsetSide(pts.reversed(), half, join, limit, err, false)
        if (left.isEmpty() || right.isEmpty()) return VecPath(pts[0], emptyList(), false)

        val out = ArrayList<VecPoint>(left.size + right.size + 8)
        out.addAll(left)
        val eIdx = n - 1
        var dx = pts[eIdx].x - pts[eIdx - 1].x
        var dy = pts[eIdx].y - pts[eIdx - 1].y
        var len = sqrt(dx * dx + dy * dy)
        if (len < EPS) { dx = 1f; dy = 0f; len = 1f }
        appendCap(out, pts[eIdx], -dy / len, dx / len, dx / len, dy / len, half, cap, err)
        out.addAll(right)
        dx = pts[0].x - pts[1].x
        dy = pts[0].y - pts[1].y
        len = sqrt(dx * dx + dy * dy)
        if (len < EPS) { dx = -1f; dy = 0f; len = 1f }
        appendCap(out, pts[0], -dy / len, dx / len, dx / len, dy / len, half, cap, err)
        return polygonToPath(out)
    }

    fun variableWidthOutline(path: VecPath, widths: FloatArray, cap: LineCap): VecPath {
        val pts = dedupe(path.points())
        if (pts.isEmpty()) return VecPath(path.start, emptyList(), false)
        val half = resampleWidths(widths, pts.size)
        for (i in half.indices) {
            val h = half[i] * 0.5f
            half[i] = if (h.isFinite() && h > 0f) h else 0f
        }
        if (pts.size < 2) {
            return if (cap == LineCap.ROUND && half[0] > 0f) circle(pts[0], half[0], 0.25f)
            else VecPath(pts[0], emptyList(), false)
        }

        val closed = path.closed && pts.size >= 3
        if (closed) {
            val ringA = variableSide(pts, half, true)
            val ringB = variableSide(pts.reversed(), half.reversedCopy(), true)
            if (ringA.size < 3 || ringB.size < 3) return VecPath(pts[0], emptyList(), false)
            val ringOuter: List<VecPoint>
            val ringInner: List<VecPoint>
            if (abs(Boolean2D.polygonArea(ringA)) >= abs(Boolean2D.polygonArea(ringB))) {
                ringOuter = ringA; ringInner = ringB
            } else {
                ringOuter = ringB; ringInner = ringA
            }
            val combined = ArrayList<VecPoint>(ringOuter.size + ringInner.size + 2)
            combined.addAll(ringOuter)
            combined.add(ringOuter[0])
            combined.addAll(ringInner)
            combined.add(ringInner[0])
            return polygonToPath(combined)
        }

        val n = pts.size
        val left = variableSide(pts, half, false)
        val right = variableSide(pts.reversed(), half.reversedCopy(), false)
        val out = ArrayList<VecPoint>(left.size + right.size + 8)
        out.addAll(left)
        var dx = pts[n - 1].x - pts[n - 2].x
        var dy = pts[n - 1].y - pts[n - 2].y
        var len = sqrt(dx * dx + dy * dy)
        if (len < EPS) { dx = 1f; dy = 0f; len = 1f }
        appendCap(out, pts[n - 1], -dy / len, dx / len, dx / len, dy / len, half[n - 1], cap, 0.25f)
        out.addAll(right)
        dx = pts[0].x - pts[1].x
        dy = pts[0].y - pts[1].y
        len = sqrt(dx * dx + dy * dy)
        if (len < EPS) { dx = -1f; dy = 0f; len = 1f }
        appendCap(out, pts[0], -dy / len, dx / len, dx / len, dy / len, half[0], cap, 0.25f)
        return polygonToPath(out)
    }

    fun taper(widths: FloatArray, headFraction: Float, tailFraction: Float): FloatArray {
        val n = widths.size
        if (n == 0) return FloatArray(0)
        val out = widths.copyOf()
        if (n == 1) return out
        val head = clamp01(headFraction)
        val tail = clamp01(tailFraction)
        if (head <= 0f && tail <= 0f) return out
        val last = (n - 1).toFloat()
        for (i in 0 until n) {
            val s = i / last
            var k = 1f
            if (head > 0f && s < head) k = s / head
            if (tail > 0f && s > 1f - tail) {
                val kt = (1f - s) / tail
                if (kt < k) k = kt
            }
            out[i] = widths[i] * k
        }
        return out
    }

    fun smoothWidths(widths: FloatArray, window: Int = 5): FloatArray {
        val n = widths.size
        if (n == 0) return FloatArray(0)
        var w = if (window < 1) 1 else window
        if (w % 2 == 0) w++
        if (w <= 1 || n == 1) return widths.copyOf()
        val r = w / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            var count = 0
            for (k in -r..r) {
                var j = i + k
                if (j < 0) j = 0
                if (j >= n) j = n - 1
                acc += widths[j]
                count++
            }
            out[i] = acc / count
        }
        return out
    }

    private fun offsetSide(
        pts: List<VecPoint>,
        half: Float,
        join: LineJoin,
        miterLimit: Float,
        err: Float,
        ring: Boolean,
    ): MutableList<VecPoint> {
        val n = pts.size
        val segCount = if (ring) n else n - 1
        val out = ArrayList<VecPoint>(segCount * 2 + 8)
        if (segCount < 1) return out

        val dx = FloatArray(segCount)
        val dy = FloatArray(segCount)
        for (i in 0 until segCount) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            var ex = b.x - a.x
            var ey = b.y - a.y
            val len = sqrt(ex * ex + ey * ey)
            if (len < EPS) { ex = 1f; ey = 0f } else { ex /= len; ey /= len }
            dx[i] = ex
            dy[i] = ey
        }

        for (i in 0 until segCount) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            val nx = -dy[i]
            val ny = dx[i]
            out.add(VecPoint(a.x + nx * half, a.y + ny * half))
            out.add(VecPoint(b.x + nx * half, b.y + ny * half))
            val hasNext = ring || i < segCount - 1
            if (hasNext) {
                val j = (i + 1) % segCount
                appendJoin(
                    out, b, half,
                    nx, ny, -dy[j], dx[j],
                    dx[i], dy[i], dx[j], dy[j],
                    join, miterLimit, err,
                )
            }
        }
        return out
    }

    private fun variableSide(
        pts: List<VecPoint>,
        half: FloatArray,
        ring: Boolean,
    ): MutableList<VecPoint> {
        val n = pts.size
        val out = ArrayList<VecPoint>(n + 4)
        if (n < 2) return out
        val segCount = if (ring) n else n - 1
        val dx = FloatArray(segCount)
        val dy = FloatArray(segCount)
        for (i in 0 until segCount) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            var ex = b.x - a.x
            var ey = b.y - a.y
            val len = sqrt(ex * ex + ey * ey)
            if (len < EPS) { ex = 1f; ey = 0f } else { ex /= len; ey /= len }
            dx[i] = ex
            dy[i] = ey
        }
        for (i in 0 until n) {
            val prev = if (i == 0) (if (ring) segCount - 1 else 0) else i - 1
            val next = if (i >= segCount) segCount - 1 else i
            var bx = (-dy[prev]) + (-dy[next])
            var by = dx[prev] + dx[next]
            var bl = sqrt(bx * bx + by * by)
            if (bl < EPS) { bx = -dy[next]; by = dx[next]; bl = 1f }
            bx /= bl
            by /= bl
            val c = bx * (-dy[next]) + by * dx[next]
            val scale = if (c > 0.5f) 1f / c else 2f
            val h = half[i] * scale
            out.add(VecPoint(pts[i].x + bx * h, pts[i].y + by * h))
        }
        return out
    }

    private fun appendJoin(
        out: MutableList<VecPoint>,
        v: VecPoint,
        half: Float,
        nPrevX: Float, nPrevY: Float, nNextX: Float, nNextY: Float,
        dPrevX: Float, dPrevY: Float, dNextX: Float, dNextY: Float,
        join: LineJoin,
        miterLimit: Float,
        err: Float,
    ) {
        val cross = dPrevX * dNextY - dPrevY * dNextX
        if (cross > 0f) return
        when (join) {
            LineJoin.BEVEL -> return
            LineJoin.MITER -> {
                val dot = dPrevX * dNextX + dPrevY * dNextY
                val s = (1f + dot) * 0.5f
                if (s <= 1e-12f) return
                val ratio = 1f / sqrt(s)
                if (!ratio.isFinite() || ratio > miterLimit) return
                var mx = nPrevX + nNextX
                var my = nPrevY + nNextY
                val ml = sqrt(mx * mx + my * my)
                if (ml < EPS) return
                mx /= ml
                my /= ml
                out.add(VecPoint(v.x + mx * half * ratio, v.y + my * half * ratio))
            }
            LineJoin.ROUND -> {
                val a1 = atan2(nPrevY.toDouble(), nPrevX.toDouble())
                val a2 = atan2(nNextY.toDouble(), nNextX.toDouble())
                var delta = a2 - a1
                while (delta > Math.PI) delta -= 2.0 * Math.PI
                while (delta < -Math.PI) delta += 2.0 * Math.PI
                appendArc(out, v.x, v.y, half, a1, delta, err)
            }
        }
    }

    private fun appendCap(
        out: MutableList<VecPoint>,
        c: VecPoint,
        nx: Float, ny: Float,
        dx: Float, dy: Float,
        half: Float,
        cap: LineCap,
        err: Float,
    ) {
        if (half <= 0f) return
        when (cap) {
            LineCap.BUTT -> return
            LineCap.SQUARE -> {
                out.add(VecPoint(c.x + nx * half + dx * half, c.y + ny * half + dy * half))
                out.add(VecPoint(c.x - nx * half + dx * half, c.y - ny * half + dy * half))
            }
            LineCap.ROUND -> {
                val a1 = atan2(ny.toDouble(), nx.toDouble())
                appendArc(out, c.x, c.y, half, a1, -Math.PI, err)
            }
        }
    }

    private fun appendArc(
        out: MutableList<VecPoint>,
        cx: Float, cy: Float, r: Float, a1: Double, delta: Double, err: Float,
    ) {
        if (abs(delta) < 1e-6 || r <= 0f) return
        var sag = (err / r).toDouble()
        if (sag < 1e-4) sag = 1e-4
        if (sag > 0.5) sag = 0.5
        val maxStep = 2.0 * acos(1.0 - sag)
        var steps = ceil(abs(delta) / maxStep).toInt()
        if (steps < 1) steps = 1
        if (steps > MAX_ARC_STEPS) steps = MAX_ARC_STEPS
        for (i in 1 until steps) {
            val a = a1 + delta * i / steps
            out.add(VecPoint((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat()))
        }
    }

    private fun polygonToPath(points: List<VecPoint>): VecPath {
        val clean = dedupe(points)
        if (clean.size < 3) return VecPath(if (clean.isEmpty()) VecPoint(0f, 0f) else clean[0], emptyList(), false)
        val segs = ArrayList<VecSeg>(clean.size - 1)
        for (i in 1 until clean.size) segs.add(VecSeg.Line(clean[i]))
        return VecPath(clean[0], segs, true)
    }

    private fun circle(c: VecPoint, r: Float, err: Float): VecPath {
        val pts = ArrayList<VecPoint>(MAX_ARC_STEPS)
        pts.add(VecPoint(c.x + r, c.y))
        appendArc(pts, c.x, c.y, r, 0.0, 2.0 * Math.PI, err)
        return polygonToPath(pts)
    }

    private fun dedupe(points: List<VecPoint>): List<VecPoint> {
        val out = ArrayList<VecPoint>(points.size)
        for (p in points) {
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            if (out.isNotEmpty()) {
                val q = out[out.size - 1]
                if (abs(p.x - q.x) <= EPS && abs(p.y - q.y) <= EPS) continue
            }
            out.add(p)
        }
        if (out.size > 1) {
            val a = out[0]
            val b = out[out.size - 1]
            if (abs(a.x - b.x) <= EPS && abs(a.y - b.y) <= EPS) out.removeAt(out.size - 1)
        }
        return out
    }

    private fun resampleWidths(widths: FloatArray, n: Int): FloatArray {
        val out = FloatArray(n)
        if (widths.isEmpty() || n == 0) return out
        if (widths.size == n) return widths.copyOf()
        if (n == 1) { out[0] = widths[0]; return out }
        val lastSrc = widths.size - 1
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1).toFloat() * lastSrc
            var i0 = t.toInt()
            if (i0 < 0) i0 = 0
            if (i0 > lastSrc) i0 = lastSrc
            val i1 = if (i0 + 1 > lastSrc) lastSrc else i0 + 1
            val f = t - i0
            out[i] = widths[i0] + (widths[i1] - widths[i0]) * f
        }
        return out
    }

    private fun FloatArray.reversedCopy(): FloatArray {
        val out = FloatArray(size)
        for (i in indices) out[i] = this[size - 1 - i]
        return out
    }

    private fun clamp01(v: Float): Float = if (!v.isFinite() || v < 0f) 0f else if (v > 1f) 1f else v
}
