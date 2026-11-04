package com.offlinetracer.vector

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask

object Vectorize {

    enum class VectorMode { CENTERLINE, OUTLINE }

    data class VectorizeParams(
        val mode: VectorMode = VectorMode.CENTERLINE,
        val simplifyEpsilon: Float = 1.0f,
        val fitError: Float = 1.6f,
        val cornerThresholdDegrees: Float = 100f,
        val smoothIterations: Int = 1,
        val minPathLength: Float = 3f,
        val strokeWidth: Float = 1.6f,
        val modulateWidth: Boolean = false,
        val widthScale: Float = 1f,
        val minWidth: Float = 0.4f,
        val maxWidth: Float = 12f,
        val fillRegions: Boolean = false,
        val chainStrokes: Boolean = true,
        val chainMaxTurnDegrees: Float = 55f,
        val chainTangentSpan: Float = 6f,
        val chainPruneBranch: Float = 0f,
        val chainMaxNodeDegree: Int = 4,
    )

    private const val COLLINEAR_TOLERANCE = 0.05f

    private const val MAX_SMOOTH_ITERATIONS = 8

    private const val DEFAULT_STROKE = 0xFF000000.toInt()

    fun run(mask: Mask, params: VectorizeParams, distanceTransform: GrayF? = null): List<VecShape> {
        val out = ArrayList<VecShape>()
        val dt = if (params.modulateWidth) distanceTransform else null

        if (params.mode == VectorMode.OUTLINE) {
            val contours = if (params.fillRegions) {
                ContourTrace.traceOuterOnly(mask)
            } else {
                ContourTrace.trace(mask)
            }
            var holeIndex = 0
            var lastLabel = Int.MIN_VALUE
            for (c in orderByRegion(contours)) {
                if (c.label != lastLabel) {
                    lastLabel = c.label
                    holeIndex = 0
                }
                val id = if (c.isHole) "r${c.label}h${holeIndex++}" else "r${c.label}"
                val shape = build(c.points, true, params, null, id, params.fillRegions)
                if (shape != null) out.add(shape)
            }
        } else {
            var lines = SkeletonTrace.trace(mask)
            if (params.chainStrokes) {
                lines = SkeletonTrace.chain(
                    lines,
                    SkeletonTrace.ChainParams(
                        maxTurnDegrees = params.chainMaxTurnDegrees,
                        tangentSpan = params.chainTangentSpan,
                        minBranchLength = params.chainPruneBranch,
                        maxNodeDegree = params.chainMaxNodeDegree,
                    ),
                )
            }
            var index = 0
            for (l in lines) {
                val shape = build(l.points, l.closed, params, dt, "p$index", false)
                index++
                if (shape != null) out.add(shape)
            }
        }
        return out
    }

    private fun orderByRegion(contours: List<ContourTrace.Contour>): List<ContourTrace.Contour> {
        val n = contours.size
        if (n < 2) return contours
        val holesByLabel = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val c = contours[i]
            if (c.isHole) holesByLabel.getOrPut(c.label) { ArrayList() }.add(i)
        }
        val out = ArrayList<ContourTrace.Contour>(n)
        val taken = BooleanArray(n)
        for (i in 0 until n) {
            val c = contours[i]
            if (c.isHole) continue
            out.add(c)
            taken[i] = true
            val holes = holesByLabel[c.label] ?: continue
            for (j in holes) {
                if (taken[j]) continue
                out.add(contours[j])
                taken[j] = true
            }
        }
        for (i in 0 until n) if (!taken[i]) out.add(contours[i])
        return out
    }

    private fun build(
        source: List<VecPoint>,
        closed: Boolean,
        params: VectorizeParams,
        dt: GrayF?,
        id: String,
        filled: Boolean,
    ): VecShape? {
        if (source.size < 2) return null

        var pts: List<VecPoint> = source

        val iterations = when {
            params.smoothIterations < 0 -> 0
            params.smoothIterations > MAX_SMOOTH_ITERATIONS -> MAX_SMOOTH_ITERATIONS
            else -> params.smoothIterations
        }
        if (iterations > 0 && pts.size >= 3) pts = Smooth.chaikin(pts, iterations, closed)
        if (pts.size < 2) return null

        pts = Simplify.removeCollinear(pts, COLLINEAR_TOLERANCE)
        val epsilon = params.simplifyEpsilon
        if (epsilon > 0f && epsilon.isFinite() && pts.size > 2) {
            pts = Simplify.douglasPeucker(pts, epsilon)
        }
        if (pts.size < 2) return null

        val error = if (params.fitError > 0f && params.fitError.isFinite()) params.fitError else 1.6f
        var path = BezierFit.fitPath(pts, error, closed && pts.size >= 3, params.cornerThresholdDegrees)
        if (path.segments.isEmpty()) return null

        val minLength = if (params.minPathLength.isFinite() && params.minPathLength > 0f) {
            params.minPathLength
        } else {
            0f
        }
        if (minLength > 0f && path.length() < minLength) return null

        var widths: FloatArray? = null
        if (dt != null) {
            widths = sampleWidths(path, dt, params)
        }

        path = path.copy(id = id, strokeWidths = widths)

        val strokeWidth = if (params.strokeWidth.isFinite() && params.strokeWidth > 0f) {
            params.strokeWidth
        } else {
            1f
        }
        val style = if (filled) {
            VecStyle(
                stroke = null,
                strokeWidth = strokeWidth,
                fill = DEFAULT_STROKE,
                fillRule = FillRule.NONZERO,
                cap = LineCap.ROUND,
                join = LineJoin.ROUND,
                miterLimit = 4f,
                opacity = 1f,
            )
        } else {
            VecStyle(
                stroke = DEFAULT_STROKE,
                strokeWidth = strokeWidth,
                fill = null,
                fillRule = FillRule.EVENODD,
                cap = LineCap.ROUND,
                join = LineJoin.ROUND,
                miterLimit = 4f,
                opacity = 1f,
            )
        }
        return VecShape(path, style)
    }

    private fun sampleWidths(path: VecPath, dt: GrayF, params: VectorizeParams): FloatArray? {
        val anchors = path.points()
        if (anchors.isEmpty()) return null
        val scale = if (params.widthScale.isFinite() && params.widthScale > 0f) params.widthScale else 1f
        var lo = params.minWidth
        var hi = params.maxWidth
        if (!lo.isFinite() || lo < 0f) lo = 0f
        if (!hi.isFinite() || hi < lo) hi = if (lo > 0f) lo else 1f
        val raw = FloatArray(anchors.size)
        for (i in anchors.indices) {
            val p = anchors[i]
            val d = if (p.x.isFinite() && p.y.isFinite()) dt.sampleBilinear(p.x, p.y) else 0f
            var w = 2f * d * scale
            if (!w.isFinite()) w = lo
            if (w < lo) w = lo
            if (w > hi) w = hi
            raw[i] = w
        }
        return StrokeStyle.smoothWidths(raw, 5)
    }
}

typealias VectorMode = Vectorize.VectorMode
typealias VectorizeParams = Vectorize.VectorizeParams
