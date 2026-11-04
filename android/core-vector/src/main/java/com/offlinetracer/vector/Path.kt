package com.offlinetracer.vector

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class VecPoint(val x: Float, val y: Float)

sealed interface VecSeg {

    val to: VecPoint

    data class Line(override val to: VecPoint) : VecSeg

    data class Cubic(val c1: VecPoint, val c2: VecPoint, override val to: VecPoint) : VecSeg

    data class Quad(val c: VecPoint, override val to: VecPoint) : VecSeg
}

data class VecPath(
    val start: VecPoint,
    val segments: List<VecSeg>,
    val closed: Boolean = false,
    val id: String = "",
    val strokeWidths: FloatArray? = null,
) {

    fun points(): List<VecPoint> {
        val out = ArrayList<VecPoint>(segments.size + 1)
        out.add(start)
        for (i in segments.indices) out.add(segments[i].to)
        return out
    }

    fun flatten(tolerance: Float = 0.25f): List<VecPoint> {
        val out = ArrayList<VecPoint>(segments.size * 4 + 1)
        out.add(start)
        if (segments.isEmpty()) return out

        val tol = if (tolerance < MIN_FLATTEN_TOLERANCE) MIN_FLATTEN_TOLERANCE else tolerance
        val tolSq = tol * tol
        val stack = FloatArray(8 * (FLATTEN_MAX_DEPTH + 2))
        val depths = IntArray(FLATTEN_MAX_DEPTH + 2)

        var cx = start.x
        var cy = start.y
        for (i in segments.indices) {
            when (val s = segments[i]) {
                is VecSeg.Line -> out.add(s.to)

                is VecSeg.Cubic -> {
                    flattenCubic(
                        out, stack, depths,
                        cx, cy, s.c1.x, s.c1.y, s.c2.x, s.c2.y, s.to.x, s.to.y, tolSq,
                    )
                }

                is VecSeg.Quad -> {
                    val c1x = cx + 2f / 3f * (s.c.x - cx)
                    val c1y = cy + 2f / 3f * (s.c.y - cy)
                    val c2x = s.to.x + 2f / 3f * (s.c.x - s.to.x)
                    val c2y = s.to.y + 2f / 3f * (s.c.y - s.to.y)
                    flattenCubic(
                        out, stack, depths,
                        cx, cy, c1x, c1y, c2x, c2y, s.to.x, s.to.y, tolSq,
                    )
                }
            }
            val end = segments[i].to
            cx = end.x
            cy = end.y
        }

        if (closed && out.size > 1) {
            val last = out[out.size - 1]
            if (last.x == start.x && last.y == start.y) out.removeAt(out.size - 1)
        }
        return out
    }

    fun bounds(): FloatArray {
        var minX = start.x
        var minY = start.y
        var maxX = start.x
        var maxY = start.y
        val acc = FloatArray(2)

        var cx = start.x
        var cy = start.y
        for (i in segments.indices) {
            val s = segments[i]
            val e = s.to
            when (s) {
                is VecSeg.Line -> {
                    if (e.x < minX) minX = e.x
                    if (e.x > maxX) maxX = e.x
                    if (e.y < minY) minY = e.y
                    if (e.y > maxY) maxY = e.y
                }

                is VecSeg.Cubic -> {
                    cubicExtent(cx, s.c1.x, s.c2.x, e.x, acc)
                    if (acc[0] < minX) minX = acc[0]
                    if (acc[1] > maxX) maxX = acc[1]
                    cubicExtent(cy, s.c1.y, s.c2.y, e.y, acc)
                    if (acc[0] < minY) minY = acc[0]
                    if (acc[1] > maxY) maxY = acc[1]
                }

                is VecSeg.Quad -> {
                    quadExtent(cx, s.c.x, e.x, acc)
                    if (acc[0] < minX) minX = acc[0]
                    if (acc[1] > maxX) maxX = acc[1]
                    quadExtent(cy, s.c.y, e.y, acc)
                    if (acc[0] < minY) minY = acc[0]
                    if (acc[1] > maxY) maxY = acc[1]
                }
            }
            cx = e.x
            cy = e.y
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    fun transform(m: Mat2D): VecPath {
        val segs = ArrayList<VecSeg>(segments.size)
        for (i in segments.indices) {
            segs.add(
                when (val s = segments[i]) {
                    is VecSeg.Line -> VecSeg.Line(m.apply(s.to))
                    is VecSeg.Cubic -> VecSeg.Cubic(m.apply(s.c1), m.apply(s.c2), m.apply(s.to))
                    is VecSeg.Quad -> VecSeg.Quad(m.apply(s.c), m.apply(s.to))
                }
            )
        }
        var widths: FloatArray? = null
        val src = strokeWidths
        if (src != null) {
            val k = m.meanScale()
            val w = FloatArray(src.size)
            for (i in src.indices) w[i] = src[i] * k
            widths = w
        }
        return VecPath(m.apply(start), segs, closed, id, widths)
    }

    fun length(): Float {
        val pts = flatten()
        if (pts.size < 2) return 0f
        var total = 0.0
        for (i in 1 until pts.size) {
            val dx = (pts[i].x - pts[i - 1].x).toDouble()
            val dy = (pts[i].y - pts[i - 1].y).toDouble()
            total += sqrt(dx * dx + dy * dy)
        }
        if (closed) {
            val dx = (pts[0].x - pts[pts.size - 1].x).toDouble()
            val dy = (pts[0].y - pts[pts.size - 1].y).toDouble()
            total += sqrt(dx * dx + dy * dy)
        }
        return total.toFloat()
    }

    fun reversed(): VecPath {
        if (segments.isEmpty()) return this
        val anchors = points()
        val segs = ArrayList<VecSeg>(segments.size)
        for (i in segments.indices.reversed()) {
            val from = anchors[i]
            segs.add(
                when (val s = segments[i]) {
                    is VecSeg.Line -> VecSeg.Line(from)
                    is VecSeg.Cubic -> VecSeg.Cubic(s.c2, s.c1, from)
                    is VecSeg.Quad -> VecSeg.Quad(s.c, from)
                }
            )
        }
        var widths: FloatArray? = null
        val src = strokeWidths
        if (src != null) {
            val w = FloatArray(src.size)
            for (i in src.indices) w[i] = src[src.size - 1 - i]
            widths = w
        }
        return VecPath(anchors[anchors.size - 1], segs, closed, id, widths)
    }

    fun isEmpty(): Boolean = segments.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VecPath) return false
        if (start != other.start) return false
        if (closed != other.closed) return false
        if (id != other.id) return false
        if (segments != other.segments) return false
        val a = strokeWidths
        val b = other.strokeWidths
        if (a == null) return b == null
        if (b == null) return false
        return a.contentEquals(b)
    }

    override fun hashCode(): Int {
        var result = start.hashCode()
        result = 31 * result + segments.hashCode()
        result = 31 * result + closed.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + (strokeWidths?.contentHashCode() ?: 0)
        return result
    }

    private companion object {

        const val FLATTEN_MAX_DEPTH = 18

        const val MIN_FLATTEN_TOLERANCE = 1e-3f

        fun flattenCubic(
            out: ArrayList<VecPoint>,
            stack: FloatArray,
            depths: IntArray,
            px0: Float, py0: Float,
            px1: Float, py1: Float,
            px2: Float, py2: Float,
            px3: Float, py3: Float,
            toleranceSq: Float,
        ) {
            stack[0] = px0; stack[1] = py0
            stack[2] = px1; stack[3] = py1
            stack[4] = px2; stack[5] = py2
            stack[6] = px3; stack[7] = py3
            depths[0] = 0
            var sp = 1

            while (sp > 0) {
                sp--
                val b = sp * 8
                val x0 = stack[b]; val y0 = stack[b + 1]
                val x1 = stack[b + 2]; val y1 = stack[b + 3]
                val x2 = stack[b + 4]; val y2 = stack[b + 5]
                val x3 = stack[b + 6]; val y3 = stack[b + 7]
                val depth = depths[sp]

                if (depth >= FLATTEN_MAX_DEPTH ||
                    isFlatCubic(x0, y0, x1, y1, x2, y2, x3, y3, toleranceSq)
                ) {
                    out.add(VecPoint(x3, y3))
                    continue
                }

                val x01 = (x0 + x1) * 0.5f; val y01 = (y0 + y1) * 0.5f
                val x12 = (x1 + x2) * 0.5f; val y12 = (y1 + y2) * 0.5f
                val x23 = (x2 + x3) * 0.5f; val y23 = (y2 + y3) * 0.5f
                val x012 = (x01 + x12) * 0.5f; val y012 = (y01 + y12) * 0.5f
                val x123 = (x12 + x23) * 0.5f; val y123 = (y12 + y23) * 0.5f
                val xm = (x012 + x123) * 0.5f; val ym = (y012 + y123) * 0.5f

                var t = sp * 8
                stack[t] = xm; stack[t + 1] = ym
                stack[t + 2] = x123; stack[t + 3] = y123
                stack[t + 4] = x23; stack[t + 5] = y23
                stack[t + 6] = x3; stack[t + 7] = y3
                depths[sp] = depth + 1
                sp++

                t = sp * 8
                stack[t] = x0; stack[t + 1] = y0
                stack[t + 2] = x01; stack[t + 3] = y01
                stack[t + 4] = x012; stack[t + 5] = y012
                stack[t + 6] = xm; stack[t + 7] = ym
                depths[sp] = depth + 1
                sp++
            }
        }

        fun isFlatCubic(
            x0: Float, y0: Float, x1: Float, y1: Float,
            x2: Float, y2: Float, x3: Float, y3: Float,
            toleranceSq: Float,
        ): Boolean {
            var ux = 3f * x1 - 2f * x0 - x3
            var uy = 3f * y1 - 2f * y0 - y3
            var vx = 3f * x2 - 2f * x3 - x0
            var vy = 3f * y2 - 2f * y3 - y0
            ux *= ux; uy *= uy; vx *= vx; vy *= vy
            val mx = if (ux > vx) ux else vx
            val my = if (uy > vy) uy else vy
            return mx + my <= 16f * toleranceSq
        }

        fun cubicExtent(p0: Float, p1: Float, p2: Float, p3: Float, out: FloatArray) {
            var lo = if (p0 < p3) p0 else p3
            var hi = if (p0 > p3) p0 else p3

            val a = (-p0 + 3f * p1 - 3f * p2 + p3).toDouble()
            val b = (2f * (p0 - 2f * p1 + p2)).toDouble()
            val c = (p1 - p0).toDouble()

            if (abs(a) < 1e-9) {
                if (abs(b) > 1e-9) {
                    val t = -c / b
                    if (t > 0.0 && t < 1.0) {
                        val v = cubicAt(p0, p1, p2, p3, t)
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                }
            } else {
                val disc = b * b - 4.0 * a * c
                if (disc >= 0.0) {
                    val sq = sqrt(disc)
                    var t = (-b + sq) / (2.0 * a)
                    if (t > 0.0 && t < 1.0) {
                        val v = cubicAt(p0, p1, p2, p3, t)
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                    t = (-b - sq) / (2.0 * a)
                    if (t > 0.0 && t < 1.0) {
                        val v = cubicAt(p0, p1, p2, p3, t)
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                }
            }
            out[0] = lo
            out[1] = hi
        }

        fun cubicAt(p0: Float, p1: Float, p2: Float, p3: Float, t: Double): Float {
            val mt = 1.0 - t
            return (mt * mt * mt * p0 + 3.0 * mt * mt * t * p1 +
                3.0 * mt * t * t * p2 + t * t * t * p3).toFloat()
        }

        fun quadExtent(p0: Float, p1: Float, p2: Float, out: FloatArray) {
            var lo = if (p0 < p2) p0 else p2
            var hi = if (p0 > p2) p0 else p2
            val den = (p0 - 2f * p1 + p2).toDouble()
            if (abs(den) > 1e-9) {
                val t = (p0 - p1).toDouble() / den
                if (t > 0.0 && t < 1.0) {
                    val mt = 1.0 - t
                    val v = (mt * mt * p0 + 2.0 * mt * t * p1 + t * t * p2).toFloat()
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
            }
            out[0] = lo
            out[1] = hi
        }
    }
}

data class Mat2D(
    val a: Float, val b: Float, val c: Float, val d: Float,
    val e: Float, val f: Float,
) {

    fun apply(p: VecPoint): VecPoint = VecPoint(a * p.x + c * p.y + e, b * p.x + d * p.y + f)

    operator fun times(o: Mat2D): Mat2D = Mat2D(
        a = a * o.a + c * o.b,
        b = b * o.a + d * o.b,
        c = a * o.c + c * o.d,
        d = b * o.c + d * o.d,
        e = a * o.e + c * o.f + e,
        f = b * o.e + d * o.f + f,
    )

    fun meanScale(): Float = sqrt(abs(a * d - b * c))

    companion object {
        val IDENTITY: Mat2D = Mat2D(1f, 0f, 0f, 1f, 0f, 0f)

        fun translate(tx: Float, ty: Float): Mat2D = Mat2D(1f, 0f, 0f, 1f, tx, ty)

        fun scale(sx: Float, sy: Float): Mat2D = Mat2D(sx, 0f, 0f, sy, 0f, 0f)

        fun rotate(radians: Float): Mat2D {
            val cs = cos(radians.toDouble()).toFloat()
            val sn = sin(radians.toDouble()).toFloat()
            return Mat2D(cs, sn, -sn, cs, 0f, 0f)
        }

        fun rotateAbout(radians: Float, cx: Float, cy: Float): Mat2D =
            translate(cx, cy) * rotate(radians) * translate(-cx, -cy)
    }
}

enum class FillRule { NONZERO, EVENODD }

enum class LineCap { BUTT, ROUND, SQUARE }

enum class LineJoin { MITER, ROUND, BEVEL }

data class VecStyle(
    val stroke: Int? = 0xFF000000.toInt(),
    val strokeWidth: Float = 1.5f,
    val fill: Int? = null,
    val fillRule: FillRule = FillRule.EVENODD,
    val cap: LineCap = LineCap.ROUND,
    val join: LineJoin = LineJoin.ROUND,
    val miterLimit: Float = 4f,
    val opacity: Float = 1f,
)

data class VecShape(val path: VecPath, val style: VecStyle)

data class VecLayer(
    val id: String,
    val name: String,
    val shapes: List<VecShape>,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
)

data class VecDocument(
    val width: Float,
    val height: Float,
    val layers: List<VecLayer>,
    val background: Int? = null,
) {

    fun bounds(): FloatArray {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (layer in layers) {
            for (shape in layer.shapes) {
                val b = shape.path.bounds()
                if (b[0] < minX) minX = b[0]
                if (b[1] < minY) minY = b[1]
                if (b[2] > maxX) maxX = b[2]
                if (b[3] > maxY) maxY = b[3]
            }
        }
        if (minX > maxX || minY > maxY) return floatArrayOf(0f, 0f, width, height)
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    fun shapeCount(): Int {
        var n = 0
        for (layer in layers) n += layer.shapes.size
        return n
    }

    fun nodeCount(): Int {
        var n = 0
        for (layer in layers) {
            for (shape in layer.shapes) n += shape.path.segments.size + 1
        }
        return n
    }

    fun transform(m: Mat2D): VecDocument {
        val sx = hypot(m.a.toDouble(), m.b.toDouble()).toFloat()
        val sy = hypot(m.c.toDouble(), m.d.toDouble()).toFloat()
        val out = ArrayList<VecLayer>(layers.size)
        for (layer in layers) {
            val shapes = ArrayList<VecShape>(layer.shapes.size)
            for (shape in layer.shapes) {
                shapes.add(
                    VecShape(
                        shape.path.transform(m),
                        shape.style.copy(strokeWidth = shape.style.strokeWidth * m.meanScale()),
                    )
                )
            }
            out.add(layer.copy(shapes = shapes))
        }
        return VecDocument(width * sx, height * sy, out, background)
    }
}

