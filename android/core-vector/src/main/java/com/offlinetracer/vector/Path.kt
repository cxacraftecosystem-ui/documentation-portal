package com.offlinetracer.vector

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The geometry primitives every vector stage speaks.
 *
 * **Coordinate convention.** Pixel centres sit on *integer* coordinates, matching
 * `GrayF.sampleBilinear`, which treats `floor(fx)` as the pixel index. A contour or centreline
 * vertex at `(3, 7)` is the centre of pixel `(3, 7)`, not its top-left corner. Every tracer, the
 * rasteriser and the SVG writer have to agree on this: if one of them assumes the half-pixel
 * offset the vector output drifts half a pixel against the preview, which reads as "the tracer is
 * slightly wrong" rather than as a convention mismatch and is very hard to find later.
 *
 * **Closed paths never repeat their first point.** `closed = true` means "there is an implicit
 * segment from the last anchor back to [VecPath.start]". Repeating the start as an explicit final
 * anchor is legal input (the tracers do not produce it, but SVG parsing can), and [VecPath.flatten]
 * drops the duplicate so downstream stages never see a zero-length segment.
 */

/** A point in path space. Immutable so paths can be shared across the undo stack without copying. */
data class VecPoint(val x: Float, val y: Float)

/** One segment of a path, always ending at an on-curve anchor. */
sealed interface VecSeg {

    /**
     * The on-curve anchor this segment ends at. Declared on the interface so the common
     * "walk the anchors" loop does not need a `when` over three subtypes in a hot path.
     */
    val to: VecPoint

    data class Line(override val to: VecPoint) : VecSeg

    data class Cubic(val c1: VecPoint, val c2: VecPoint, override val to: VecPoint) : VecSeg

    data class Quad(val c: VecPoint, override val to: VecPoint) : VecSeg
}

/**
 * A single subpath: one start anchor plus a list of segments, optionally closed.
 *
 * @param strokeWidths optional per-anchor stroke width (index 0 = [start], index i+1 = the anchor
 *   ending `segments[i]`). `null` means "uniform width, take it from the style".
 */
data class VecPath(
    val start: VecPoint,
    val segments: List<VecSeg>,
    val closed: Boolean = false,
    val id: String = "",
    val strokeWidths: FloatArray? = null,
) {

    /** Anchors only — [start] followed by each segment's end point. Control points are not included. */
    fun points(): List<VecPoint> {
        val out = ArrayList<VecPoint>(segments.size + 1)
        out.add(start)
        for (i in segments.indices) out.add(segments[i].to)
        return out
    }

    /**
     * The path as a polyline, curves subdivided **adaptively** until each piece is within
     * [tolerance] of its chord.
     *
     * Adaptive and not a fixed step count on purpose: a fixed count over-tessellates a 2 px curve
     * into dozens of collinear points that the simplifier then has to remove, and under-tessellates
     * a 2000 px curve into a visible polygon. The subdivision uses an explicit stack with a hard
     * depth cap, so a pathological (cusped, self-overlapping) curve degrades in smoothness rather
     * than hanging.
     *
     * For a closed path the returned list does **not** repeat [start]; a trailing anchor that
     * already coincides with [start] is dropped so the caller never sees a zero-length closing edge.
     */
    fun flatten(tolerance: Float = 0.25f): List<VecPoint> {
        val out = ArrayList<VecPoint>(segments.size * 4 + 1)
        out.add(start)
        if (segments.isEmpty()) return out

        val tol = if (tolerance < MIN_FLATTEN_TOLERANCE) MIN_FLATTEN_TOLERANCE else tolerance
        val tolSq = tol * tol
        // Allocated once per call, not once per segment: flatten() runs over every path in the
        // document on every preview.
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
                    // Degree-elevate to a cubic so there is exactly one subdivision code path.
                    // A separate quadratic flattener is a second place for the tolerance test to
                    // drift out of agreement with the cubic one.
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

    /**
     * Tight bounding box as `[minX, minY, maxX, maxY]`.
     *
     * Curve extrema are solved analytically (the roots of the derivative), not approximated by the
     * control polygon: a control-hull box is up to a third too large on a strongly curved segment,
     * and the document bounds become the SVG `viewBox`, so a loose box shows as unexpected margin
     * around every export.
     *
     * A path with no segments returns the degenerate box at [start].
     */
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

    /**
     * Every anchor and control point mapped through [m].
     *
     * [strokeWidths] are scaled by `sqrt(|det m|)` — the geometric mean of the two axis scales, and
     * exactly 1 for any rigid motion. This is not cosmetic: the pipeline traces at working
     * resolution and multiplies by `original / working` on export, so a width that did not scale
     * with the geometry would come out several times too thin on a downscaled 12 MP source.
     * A singular transform yields zero widths, which is the honest answer for collapsed geometry.
     */
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

    /**
     * Arc length of the flattened path, including the closing edge when [closed].
     * This is the polyline approximation at the default flatness, not the analytic curve length;
     * every consumer (minimum-path-length filtering, dash spacing) compares it against a
     * pixel-scale threshold where the difference is far below one pixel.
     */
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

    /**
     * The same geometry walked in the opposite direction: anchors reversed, cubic control points
     * swapped, [strokeWidths] reversed. Used to make two adjacent contours wind the same way and to
     * join a path to its neighbour without a visible seam.
     */
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

    /** True when the path draws nothing — a lone anchor with no segments. */
    fun isEmpty(): Boolean = segments.isEmpty()

    /**
     * Hand-written because [strokeWidths] is an array: the generated `equals` would compare it by
     * identity, so two paths built from the same trace would never be equal and undo/redo
     * deduplication would silently stop collapsing no-op edits.
     */
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

    /** Mirrors [equals]; [strokeWidths] contributes via `contentHashCode`. */
    override fun hashCode(): Int {
        var result = start.hashCode()
        result = 31 * result + segments.hashCode()
        result = 31 * result + closed.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + (strokeWidths?.contentHashCode() ?: 0)
        return result
    }

    /**
     * Curve maths, kept inside the class rather than at file scope on purpose: private *top-level*
     * declarations still occupy the package namespace in Kotlin, and every other file in
     * `com.offlinetracer.vector` has a different author who is just as likely to want a helper
     * called `cubicAt`.
     */
    private companion object {

        /**
         * 2^18 pieces is far past anything a sane tolerance asks for; the cap exists only so a
         * cusped or degenerate curve (coincident control points, infinities from a bad parse)
         * emits a coarse polyline instead of subdividing until the heap gives out.
         */
        const val FLATTEN_MAX_DEPTH = 18

        /** Below this the flatness test is float noise and subdivision never terminates early. */
        const val MIN_FLATTEN_TOLERANCE = 1e-3f

        /**
         * Adaptive de Casteljau subdivision with an explicit stack.
         *
         * The right half is pushed before the left so the left pops first and the emitted points
         * stay in parameter order. Only the *end* point of each flat piece is emitted; the caller
         * has already emitted the start.
         */
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

                var t = sp * 8            // right half
                stack[t] = xm; stack[t + 1] = ym
                stack[t + 2] = x123; stack[t + 3] = y123
                stack[t + 4] = x23; stack[t + 5] = y23
                stack[t + 6] = x3; stack[t + 7] = y3
                depths[sp] = depth + 1
                sp++

                t = sp * 8                // left half
                stack[t] = x0; stack[t + 1] = y0
                stack[t + 2] = x01; stack[t + 3] = y01
                stack[t + 4] = x012; stack[t + 5] = y012
                stack[t + 6] = xm; stack[t + 7] = ym
                depths[sp] = depth + 1
                sp++
            }
        }

        /**
         * The standard "control points close to the chord" flatness test: it bounds the true
         * deviation without a square root or a division, which matters because it runs once per
         * subdivision step of every curve in the document.
         */
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

        /** Writes `[min, max]` of a cubic Bezier component into [out], including analytic extrema. */
        fun cubicExtent(p0: Float, p1: Float, p2: Float, p3: Float, out: FloatArray) {
            var lo = if (p0 < p3) p0 else p3
            var hi = if (p0 > p3) p0 else p3

            // B'(t)/3 = A t² + B t + C, with A = -p0+3p1-3p2+p3, B = 2(p0-2p1+p2), C = p1-p0.
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

        /** Writes `[min, max]` of a quadratic Bezier component into [out], including its extremum. */
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

/**
 * A 2-D affine transform laid out as
 * ```
 * [ a  c  e ]
 * [ b  d  f ]
 * [ 0  0  1 ]
 * ```
 * i.e. the same `(a, b, c, d, e, f)` order SVG's `matrix(...)` and Android's `Matrix` use, so the
 * bridge to either is a straight copy.
 */
data class Mat2D(
    val a: Float, val b: Float, val c: Float, val d: Float,
    val e: Float, val f: Float,
) {

    /** `(a·x + c·y + e, b·x + d·y + f)`. */
    fun apply(p: VecPoint): VecPoint = VecPoint(a * p.x + c * p.y + e, b * p.x + d * p.y + f)

    /**
     * Matrix product `this · o`, which means **[o] is applied first and `this` second**:
     * `(this * o).apply(p) == this.apply(o.apply(p))`.
     *
     * Stated explicitly because the opposite convention is equally common and the two differ only
     * on non-commuting pairs — a scale-then-rotate composed the wrong way round still looks
     * plausible on a square test image and only goes visibly wrong on real artwork. Every
     * composition in this codebase reads right-to-left, e.g. [rotateAbout].
     */
    operator fun times(o: Mat2D): Mat2D = Mat2D(
        a = a * o.a + c * o.b,
        b = b * o.a + d * o.b,
        c = a * o.c + c * o.d,
        d = b * o.c + d * o.d,
        e = a * o.e + c * o.f + e,
        f = b * o.e + d * o.f + f,
    )

    /**
     * The isotropic scale factor `sqrt(|det|)`: 1 for any rotation/translation/reflection, `s` for
     * a uniform scale `s`, and the geometric mean for an anisotropic one. This is the factor stroke
     * widths and flatness tolerances scale by, since neither has a direction.
     */
    fun meanScale(): Float = sqrt(abs(a * d - b * c))

    companion object {
        val IDENTITY: Mat2D = Mat2D(1f, 0f, 0f, 1f, 0f, 0f)

        fun translate(tx: Float, ty: Float): Mat2D = Mat2D(1f, 0f, 0f, 1f, tx, ty)

        fun scale(sx: Float, sy: Float): Mat2D = Mat2D(sx, 0f, 0f, sy, 0f, 0f)

        /** Rotation by [radians], positive turning +x toward +y — clockwise on screen, since y is down. */
        fun rotate(radians: Float): Mat2D {
            val cs = cos(radians.toDouble()).toFloat()
            val sn = sin(radians.toDouble()).toFloat()
            return Mat2D(cs, sn, -sn, cs, 0f, 0f)
        }

        /** [rotate] about `(cx, cy)` — composed right-to-left: translate back ∘ rotate ∘ translate to origin. */
        fun rotateAbout(radians: Float, cx: Float, cy: Float): Mat2D =
            translate(cx, cy) * rotate(radians) * translate(-cx, -cy)
    }
}

enum class FillRule { NONZERO, EVENODD }

enum class LineCap { BUTT, ROUND, SQUARE }

enum class LineJoin { MITER, ROUND, BEVEL }

/**
 * Paint for one shape. Colours are packed ARGB exactly as `RgbaImage` stores them; `null` means
 * "no stroke" / "no fill" rather than transparent black, so an unset fill and a deliberately
 * invisible one stay distinguishable through a save/load round trip.
 */
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

/** One drawable: geometry plus paint. */
data class VecShape(val path: VecPath, val style: VecStyle)

/** A named, orderable group of shapes. */
data class VecLayer(
    val id: String,
    val name: String,
    val shapes: List<VecShape>,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
)

/** The whole drawing: a canvas size, ordered layers, and an optional page background. */
data class VecDocument(
    val width: Float,
    val height: Float,
    val layers: List<VecLayer>,
    val background: Int? = null,
) {

    /**
     * Union of every shape's bounds as `[minX, minY, maxX, maxY]`, **including hidden and locked
     * layers** — hiding a layer must not change the export box, or toggling visibility would
     * silently reflow the whole document. A document with no shapes returns the canvas box
     * `[0, 0, width, height]`.
     */
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

    /** Total shapes across all layers. */
    fun shapeCount(): Int {
        var n = 0
        for (layer in layers) n += layer.shapes.size
        return n
    }

    /** Total anchors across all layers — the number the UI shows as "nodes", control points excluded. */
    fun nodeCount(): Int {
        var n = 0
        for (layer in layers) {
            for (shape in layer.shapes) n += shape.path.segments.size + 1
        }
        return n
    }

    /**
     * Every shape mapped through [m], with the canvas scaled by the transform's column norms
     * (`hypot(a, b)` × `hypot(c, d)`). A rotation therefore leaves the canvas alone, which is what
     * the export path wants: it composes a working→output scale and never a rotation, and a canvas
     * that silently grew under a rotation would crop on the next export.
     */
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

