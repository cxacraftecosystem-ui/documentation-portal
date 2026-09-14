package com.offlinetracer.vector

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask

/**
 * Mask to vector shapes — the stage that turns cleaned-up pixels into geometry.
 *
 * Two modes, and choosing between them is the most consequential decision in the app:
 *
 *  - [VectorMode.CENTERLINE] traces the **skeleton**, so one stroke of the original becomes one open
 *    path. This is what line art, CNC, embroidery, pen plotters and single-stroke styles need.
 *  - [VectorMode.OUTLINE] traces **region boundaries**, so every filled region becomes a closed
 *    path. This is what stencils, silhouettes, laser cutting, vinyl and colouring pages need.
 *
 * The chain is the same either way: trace → smooth → simplify → corner-split → fit → drop the runt
 * paths. Smoothing runs *before* simplification on purpose (see ALGORITHMS §10.6): smoothing after
 * Douglas-Peucker merely re-adds the points it just removed.
 *
 * The *colour* is not decided here. Shapes come back in plain black so that the caller — the only
 * thing that knows the user's palette — restyles them once, in one place. Whether a shape is
 * **stroked or painted** is decided here, because it is a geometric decision and not a palette one:
 * see [VectorizeParams.fillRegions].
 */
object Vectorize {

    enum class VectorMode { CENTERLINE, OUTLINE }

    /**
     * @property fillRegions **The distinction between a colouring page and a silhouette.** In
     *   [VectorMode.OUTLINE] a traced region can be presented two ways, and confusing them produces
     *   the two worst outputs this stage is capable of:
     *
     *   - `false` — *an outline you fill in yourself.* Every boundary is traced, holes included, and
     *     every one is emitted as a **stroked** closed path with no fill. This is a colouring page, a
     *     cut file and a transfer pattern: the interior stays paper, and the bands and motifs inside
     *     a shape survive as their own closed paths for a child to colour or a blade to follow.
     *   - `true` — *the region is the mark.* Only **outer** boundaries are traced and each is
     *     **painted**. This is a silhouette, a woodcut mass, a comic black.
     *
     *   Holes are deliberately not traced in the painted case. A [VecPath] is one subpath, so a hole
     *   cannot be expressed as part of the shape that contains it; emitting it as a separate painted
     *   path fills the hole with ink, which is how a colouring book came out as a solid black
     *   silhouette with every interior detail gone. Not tracing a hole loses the white; painting it
     *   loses the white *and* costs a path. Between "a solid region has no interior" and "a solid
     *   region has an interior painted the same colour", only the first is a defensible reading.
     *
     * @property chainStrokes link polylines that continue through a junction, so one contour comes
     *   back as one path rather than one path per graph edge. See [SkeletonTrace.chain]; only
     *   meaningful in [VectorMode.CENTERLINE].
     * @property chainMaxTurnDegrees see [SkeletonTrace.ChainParams.maxTurnDegrees].
     * @property chainTangentSpan see [SkeletonTrace.ChainParams.tangentSpan].
     * @property chainPruneBranch see [SkeletonTrace.ChainParams.minBranchLength].
     * @property chainMaxNodeDegree see [SkeletonTrace.ChainParams.maxNodeDegree].
     */
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

    /** Collinear-merge tolerance. One cheap pass that roughly halves Douglas-Peucker's input. */
    private const val COLLINEAR_TOLERANCE = 0.05f

    /** Chaikin is bounded because each iteration doubles the point count. */
    private const val MAX_SMOOTH_ITERATIONS = 8

    private const val DEFAULT_STROKE = 0xFF000000.toInt()

    /**
     * Vectorises [mask] and returns one [VecShape] per surviving path, in trace order.
     *
     * [distanceTransform] is only read when `params.modulateWidth` is set; it must be the Euclidean
     * distance transform of the *same* mask, in the same coordinates, and is sampled bilinearly at
     * each anchor to give `w = clamp(2·DT·widthScale, minWidth, maxWidth)`. The resulting widths are
     * smoothed with a 5-tap moving average, because raw DT at 1 px resolution is noisy enough that
     * an unsmoothed profile reads as a wobble rather than as brushwork. Passing `modulateWidth`
     * without a transform is not an error — the paths simply come back with a uniform width.
     *
     * Returns an empty list for an all-background mask. Never throws.
     */
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
                // The id records the hole relationship a single-subpath VecPath cannot carry in its
                // geometry: `r7` is a region's outer boundary and `r7h0` is the first hole in it.
                // Combined with the emission order — every region immediately followed by its own
                // holes — a consumer that later gains compound paths can reassemble them exactly.
                val id = if (c.isHole) "r${c.label}h${holeIndex++}" else "r${c.label}"
                // Width modulation is a centreline idea: it reads the distance transform at a point
                // on the *stroke's spine*, and a region boundary is not on any spine.
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

    /**
     * Reorders contours so every region's outer boundary is immediately followed by its own holes.
     *
     * `ContourTrace` returns all outer boundaries and then all holes, which is the right order for a
     * tracer and the wrong one for a painter: a renderer that walks the list in order must meet the
     * region before the holes inside it. Linear, and stable within each group, so the output order is
     * still a function of the mask alone.
     */
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
        // A hole whose region was filtered away upstream is still real ink; it ships in trace order
        // rather than being silently dropped by the reordering.
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
                // NONZERO rather than EVENODD. Only outer boundaries are painted (see
                // [VectorizeParams.fillRegions]), so there is no hole for the two rules to disagree
                // about — but a traced boundary can touch itself at a one-pixel isthmus, and NONZERO
                // is the rule that still renders that solid instead of punching a hole through it.
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

    /** Per-anchor stroke width from the distance transform, clamped then smoothed along the path. */
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

// The two public types above are nested in `Vectorize` to match the API contract's grouping. These
// aliases exist so `com.offlinetracer.vector.VectorizeParams` resolves as well, because the pipeline
// and the TypeScript mirror both refer to them unqualified and a name that only resolves one way is
// a compile error in somebody else's module.
typealias VectorMode = Vectorize.VectorMode
typealias VectorizeParams = Vectorize.VectorizeParams
