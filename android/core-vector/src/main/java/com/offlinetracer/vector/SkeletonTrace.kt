package com.offlinetracer.vector

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import com.offlinetracer.imaging.Px
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Skeleton → polylines: the geometry source for **centreline** mode, where one stroke of the
 * original becomes one path.
 *
 * This is a graph walk, not a scan of 8-connected runs. Pixels are classified by their 8-neighbour
 * count — 1 endpoint, 2 interior, ≥3 junction — and every edge is walked from a node (endpoint or
 * junction) through interior pixels to the next node, with the interior pixels marked so each edge
 * is emitted exactly once. Whatever is left over is a cycle with no node on it, and gets seeded
 * separately as a closed path.
 *
 * The junction handling is the whole point. Without it a five-way star traces as five paths that
 * each stop one pixel short of the centre, and the SVG has a visible hole at every junction —
 * which on a dense drawing means a hole everywhere two strokes cross. Here every edge *includes*
 * its node pixels, so adjacent edges share an endpoint exactly and the join is seamless.
 *
 * Helpers live inside the object because sibling files in this package have other authors and a
 * top-level `DX8` would be a package-wide redeclaration.
 */
object SkeletonTrace {

    /**
     * One traced stroke. [points] are pixel centres in path space. When [closed] is true the
     * polygon is **implicitly closed** — the first point is not repeated at the end.
     */
    data class Polyline(val points: List<VecPoint>, val closed: Boolean)

    /**
     * Every edge of the skeleton graph as a polyline, in raster order of the seed pixel.
     *
     * Guarantees the rest of the pipeline relies on:
     *  - an edge's first and last points are the node pixels at its ends, so two edges meeting at a
     *    junction share that exact coordinate;
     *  - each edge is emitted once, in one direction only;
     *  - an isolated pixel (no neighbours) is emitted as a single-point open polyline — a stippled
     *    dot is real artwork, and dropping it is the caller's minimum-length decision, not this
     *    function's;
     *  - an empty skeleton returns an empty list.
     *
     * [skeleton] is expected to be one pixel thick (post-thinning). A thicker mask still traces —
     * every pixel is classified and walked — but the result follows the blob's internal graph and
     * is not a centreline; that is a cleanup-stage decision, so nothing here rejects it.
     */
    fun trace(skeleton: Mask): List<Polyline> {
        val w = skeleton.width
        val h = skeleton.height
        val n = w * h
        val fg = skeleton.data
        val polys = ArrayList<Polyline>()

        val degree = degrees(skeleton)
        val used = BooleanArray(n)   // interior pixels already consumed by an emitted edge

        // Pass 1: every edge that touches a node, walked from that node.
        for (i in 0 until n) {
            if (!fg[i]) continue
            val deg = degree[i].toInt()
            if (deg == 2) continue
            if (deg == 0) {
                polys.add(Polyline(listOf(pointOf(i, w)), false))
                continue
            }
            val x = i % w
            val y = i / w
            for (k in 0 until 8) {
                val nx = x + DX8[k]
                val ny = y + DY8[k]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val q = ny * w + nx
                if (!fg[q]) continue
                if (degree[q].toInt() == 2) {
                    if (used[q]) continue        // the far node already walked this edge
                    polys.add(walkEdge(skeleton, degree, used, i, q, n))
                } else if (i < q) {
                    // Node touching node: there are no interior pixels to mark, so the edge is
                    // deduplicated by emitting it only from the lower-indexed end.
                    polys.add(Polyline(listOf(pointOf(i, w), pointOf(q, w)), false))
                }
            }
        }

        // Pass 2: whatever interior pixels remain form cycles with no node on them — a closed ring
        // has no endpoint and no junction, so pass 1 never reaches it.
        for (i in 0 until n) {
            if (!fg[i] || used[i] || degree[i].toInt() != 2) continue
            polys.add(walkLoop(skeleton, used, i, n))
        }
        return polys
    }

    /**
     * [trace] plus, for each polyline, the **raw** stroke diameter `2 · DT` sampled at every vertex
     * (ALGORITHMS §10.7). The two lists are index-aligned and `widths[i].size == polylines[i].points.size`.
     *
     * Deliberately raw: no moving average, no min/max clamp, no `widthScale`. Distance-transform
     * values at 1 px resolution are quantised and noisy, so a width taken straight from them reads
     * as a wobble along the stroke; smoothing and clamping happen once, downstream, where the
     * user's parameters live — doing it in two places is how the two engines drift apart.
     *
     * @throws IllegalArgumentException if [dt] does not match [skeleton]'s dimensions. That is a
     *   wiring mistake, not degenerate input, and it must fail loudly rather than silently sample
     *   the wrong pixels.
     */
    fun traceWithWidths(skeleton: Mask, dt: GrayF): Pair<List<Polyline>, List<FloatArray>> {
        require(dt.width == skeleton.width && dt.height == skeleton.height) {
            "traceWithWidths(): distance transform ${dt.width}x${dt.height} does not match " +
                "skeleton ${skeleton.width}x${skeleton.height}"
        }
        val polys = trace(skeleton)
        val w = skeleton.width
        val h = skeleton.height
        val src = dt.data
        val widths = ArrayList<FloatArray>(polys.size)
        for (p in polys) {
            val pts = p.points
            val out = FloatArray(pts.size)
            for (j in pts.indices) {
                val x = Px.clamp(pts[j].x.roundToInt(), 0, w - 1)
                val y = Px.clamp(pts[j].y.roundToInt(), 0, h - 1)
                out[j] = 2f * src[y * w + x]
            }
            widths.add(out)
        }
        return polys to widths
    }

    // -----------------------------------------------------------------------------------------
    // Stroke chaining
    // -----------------------------------------------------------------------------------------

    /**
     * Tuning for [chain].
     *
     * @property maxTurnDegrees how far a stroke may turn at a node and still count as *the same*
     *   stroke continuing.
     *
     *   **The usable window is (45°, 80°), and both ends of it are forced rather than chosen.**
     *
     *   The floor is the lattice. A skeleton is 8-connected, so the edges immediately around a
     *   junction are one to three pixels long and the only directions that exist between neighbouring
     *   pixels are multiples of 45°. A threshold at or below 45° therefore refuses to walk through a
     *   single diagonal step — which is not a corner, it is rasterisation — and the measurement is
     *   unambiguous: sweeping the threshold over a 900x1200 shaded subject, every value from 30° to
     *   44° gives 4552 paths at a 8.1 px median and every value from 46° to 75° gives ~3540 paths at
     *   a 11.7 px median. The step is exactly at 45 and there is nothing else in the sweep.
     *
     *   The ceiling is [MAX_TURN_DEGREES] = 80°, which is exactly where `Simplify.detectCorners`'
     *   default begins calling a bend a corner (it measures the arm angle, so its 100° default is an
     *   80° turn). Chaining past that point would fuse two strokes through a vertex the corner
     *   detector exists to protect, and invent geometry that was never traced.
     *
     *   The default sits in the middle of that window with margin on both sides. Above 46° the result
     *   is flat to within 1.5%, so the exact value is not load-bearing — which is the point: it is
     *   chosen to be far from both cliffs rather than tuned to a subject.
     * @property tangentSpan chord length in pixels used to measure the direction a stroke arrives at
     *   a node with. It has to span several pixels: consecutive skeleton pixels are one pixel apart,
     *   so the only directions that exist between neighbours are multiples of 45°, and a turn
     *   threshold measured against that quantisation means nothing.
     * @property minBranchLength leaf branches shorter than this are dropped **before** chaining.
     *   Thinning a noisy region grows hair, every hair is a spurious junction, and a spurious
     *   junction is what breaks a real contour into pieces — so pruning it ought to be cheaper than
     *   chaining around it.
     *
     *   **It is off by default, because it was measured and it does not pay.** Sweeping 0 to 14 px on
     *   the shaded subject moved the path count by 3% (3540 to 3435) and the flat-graphic subject not
     *   at all: on a real skeleton the tone response arrives as closed contours and connected runs
     *   rather than as leaves, so there is very little that fits the definition of a spur. Three
     *   percent does not justify deleting ink, and where hair genuinely dominates, `Thinning.pruneSpurs`
     *   removes it at the pixel level before the graph is built (ALGORITHMS §9), which is both a
     *   documented preset knob and the earlier, cheaper place to do it. The parameter stays because it
     *   is the right lever for a caller who has already measured that it helps on their source.
     * @property pruneRounds rounds of leaf removal; removing one leaf can expose the next. Bounded by
     *   [MAX_PRUNE_ROUNDS].
     * @property maxNodeDegree a node where more than this many strokes meet is a blob, not a
     *   crossing. Two strokes crossing gives degree 4 and a T-junction 3; degree 8 is an unthinned
     *   region, where every pair of arms is equally plausible and picking one is guessing.
     *
     *   Measured: 3 is clearly wrong — refusing to chain at a 4-way crossing loses a fifth of the
     *   total path length on the shaded subject (52 087 px against 64 734) because the crossings stay
     *   fragmented and the fragments fall under the caller's minimum length. 5 and above buy under 1%
     *   over 4 and give up the property that makes an unthinned mask degrade safely: with the cap at 4
     *   a solid region, every pixel of which is a degree-8 node, chains nothing at all and comes back
     *   as the runt paths it really is rather than as invented strokes across a blob.
     */
    data class ChainParams(
        val maxTurnDegrees: Float = 40f,
        val tangentSpan: Float = 6f,
        val minBranchLength: Float = 0f,
        val pruneRounds: Int = 3,
        val maxNodeDegree: Int = 4,
    )

    /**
     * Link polylines that meet at a node and continue through it, so one contour comes back as one
     * path.
     *
     * [trace] emits one polyline per *graph edge*, which is the only decomposition that is a function
     * of the skeleton alone — but it means a long contour crossed by two other strokes comes back as
     * three paths, and a skeleton with spurious junctions (which is any skeleton of a photograph)
     * comes back shattered. Measured on a 900x1200 shaded subject: 88% of the kept paths were under
     * 20 px and the median was 7 px, for a subject whose smallest real feature is 150 px long.
     *
     * The rule, in one sentence: **at a node, the incoming stroke continues into whichever other arm
     * turns least, provided that turn is under [ChainParams.maxTurnDegrees].**
     *
     * Properties this is required to have, and how each is obtained:
     *
     *  - **Deterministic.** No RNG and no reliance on hash iteration order. Nodes are numbered in the
     *    order the polylines are visited; candidate pairs are ordered by a *total* key — straightest
     *    first, then node index, then the two end ids — so the greedy pass cannot depend on anything
     *    but the geometry.
     *  - **Each skeleton edge used at most once.** A polyline has exactly two ends and the greedy pass
     *    links an end only when both ends of the pair are still free, so an edge can join at most one
     *    predecessor and one successor: the links form disjoint paths and cycles, never a branch.
     *  - **Greedy, and bounded.** It is a greedy pass rather than a global matching because the
     *    geometry does not deserve better: the candidate that is straightest at a node is the
     *    continuation, and no rearrangement of the rest changes that. Cost is
     *    `O(maxNodeDegree² · nodes)` candidates plus one sort — the degree cap is what bounds it.
     *  - **Closed loops stay closed.** A ring already arrives from [trace] as a closed polyline and is
     *    passed through untouched; a chain that walks back to its own start is emitted closed, with
     *    the duplicated seam vertex dropped, rather than opened at an arbitrary point.
     *  - **Corners survive.** See [ChainParams.maxTurnDegrees].
     *
     * @return polylines in the order of the lowest-indexed member of each chain. Pruned spurs are
     *   *absent*, so the caller's own count of what it received is the honest one.
     */
    fun chain(polylines: List<Polyline>, params: ChainParams = ChainParams()): List<Polyline> {
        val n = polylines.size
        if (n == 0) return polylines

        // Only an open polyline with two distinct ends can be chained or pruned. A closed ring has no
        // ends; a one-point polyline (an isolated pixel) has no direction.
        val chainable = BooleanArray(n)
        val nodeOf = IntArray(2 * n) { -1 }
        val keyToNode = HashMap<Long, Int>(2 * n)
        var nodeCount = 0
        for (i in 0 until n) {
            val pl = polylines[i]
            if (pl.closed || pl.points.size < 2) continue
            chainable[i] = true
            for (port in 0..1) {
                val p = if (port == 0) pl.points[0] else pl.points[pl.points.size - 1]
                val k = nodeKey(p)
                val existing = keyToNode[k]
                if (existing != null) {
                    nodeOf[2 * i + port] = existing
                } else {
                    nodeOf[2 * i + port] = nodeCount
                    keyToNode[k] = nodeCount
                    nodeCount++
                }
            }
        }
        if (nodeCount == 0) return polylines

        val alive = chainable.copyOf()

        // --- prune leaves -------------------------------------------------------------------------
        // A spur is a short branch whose far end is a junction (ALGORITHMS §9). A short branch whose
        // *both* ends are free is not a spur, it is an isolated fragment, and dropping it here would
        // pre-empt the caller's own minimum-length decision.
        if (params.minBranchLength > 0f && params.pruneRounds > 0) {
            val rounds = if (params.pruneRounds > MAX_PRUNE_ROUNDS) MAX_PRUNE_ROUNDS else params.pruneRounds
            for (round in 0 until rounds) {
                val degree = degreesOfNodes(nodeOf, alive, nodeCount, n)
                val doomed = BooleanArray(n)
                var removed = 0
                for (i in 0 until n) {
                    if (!alive[i]) continue
                    val na = nodeOf[2 * i]
                    val nb = nodeOf[2 * i + 1]
                    if (na == nb) continue
                    if (lengthOf(polylines[i]) >= params.minBranchLength) continue
                    if ((degree[na] == 1 && degree[nb] >= 3) || (degree[nb] == 1 && degree[na] >= 3)) {
                        doomed[i] = true
                        removed++
                    }
                }
                if (removed == 0) break
                // Applied after the whole pass, never in place: an in-place removal makes the result
                // depend on iteration order, which is how the two engines drift apart.
                for (i in 0 until n) if (doomed[i]) alive[i] = false
            }
        }

        // --- arrival directions -------------------------------------------------------------------
        val span = if (params.tangentSpan > 0f) params.tangentSpan else 1f
        val tanX = DoubleArray(2 * n)
        val tanY = DoubleArray(2 * n)
        val hasTangent = BooleanArray(2 * n)
        val scratch = DoubleArray(2)
        for (i in 0 until n) {
            if (!alive[i]) continue
            for (port in 0..1) {
                if (!tangentAt(polylines[i].points, port == 0, span, scratch)) continue
                tanX[2 * i + port] = scratch[0]
                tanY[2 * i + port] = scratch[1]
                hasTangent[2 * i + port] = true
            }
        }

        // --- candidate pairs ----------------------------------------------------------------------
        val degree = degreesOfNodes(nodeOf, alive, nodeCount, n)
        val offset = IntArray(nodeCount + 1)
        for (v in 0 until nodeCount) offset[v + 1] = offset[v] + degree[v]
        val cursor = offset.copyOf()
        val endsAt = IntArray(offset[nodeCount])
        for (i in 0 until n) {
            if (!alive[i]) continue
            endsAt[cursor[nodeOf[2 * i]]++] = 2 * i
            endsAt[cursor[nodeOf[2 * i + 1]]++] = 2 * i + 1
        }

        var turn = params.maxTurnDegrees
        if (!turn.isFinite() || turn < 0f) turn = 0f
        if (turn > MAX_TURN_DEGREES) turn = MAX_TURN_DEGREES
        val cosLimit = cos(turn.toDouble() * PI / 180.0)
        val maxDegree = if (params.maxNodeDegree < 2) 2 else params.maxNodeDegree

        val candDot = ArrayList<Double>()
        val candNode = ArrayList<Int>()
        val candA = ArrayList<Int>()
        val candB = ArrayList<Int>()
        for (v in 0 until nodeCount) {
            val d = degree[v]
            if (d < 2 || d > maxDegree) continue
            for (a in offset[v] until offset[v + 1]) {
                val ea = endsAt[a]
                if (!hasTangent[ea]) continue
                for (b in a + 1 until offset[v + 1]) {
                    val eb = endsAt[b]
                    if (!hasTangent[eb]) continue
                    // Both ends of one polyline at one node is a loop, not a crossing; [trace]
                    // already emits those closed, and joining a polyline to itself here would make
                    // the link graph inconsistent.
                    if ((ea shr 1) == (eb shr 1)) continue
                    // Both tangents point *away* from the node along their own polyline, so a stroke
                    // that runs straight through has them exactly opposed: the continuation score is
                    // the negated dot product, +1 for straight through and -1 for a hairpin.
                    val dot = -(tanX[ea] * tanX[eb] + tanY[ea] * tanY[eb])
                    if (dot < cosLimit) continue
                    candDot.add(dot)
                    candNode.add(v)
                    candA.add(ea)
                    candB.add(eb)
                }
            }
        }

        // --- greedy linkage -----------------------------------------------------------------------
        // `(node, endA, endB)` is unique per candidate, so this is a total order and the sort cannot
        // depend on stability. Straightest first: at a crossing, the pair that continues wins over
        // the pair that turns, whichever order they were generated in.
        val order = (0 until candDot.size).sortedWith(
            compareByDescending<Int> { candDot[it] }
                .thenBy { candNode[it] }
                .thenBy { candA[it] }
                .thenBy { candB[it] }
        )
        val link = IntArray(2 * n) { -1 }
        for (c in order) {
            val ea = candA[c]
            val eb = candB[c]
            if (link[ea] >= 0 || link[eb] >= 0) continue
            link[ea] = eb
            link[eb] = ea
        }

        // --- walk the chains ----------------------------------------------------------------------
        val out = ArrayList<Polyline>(n)
        val visited = BooleanArray(n)
        for (i in 0 until n) {
            if (!chainable[i]) {
                out.add(polylines[i])
                continue
            }
            if (!alive[i] || visited[i]) continue

            // Walk backwards to the chain's head. A walk that arrives back at the seed means the
            // links close a cycle, and the chain is a ring.
            var headPoly = i
            var headRev = false
            var cyclic = false
            var steps = 0
            while (steps <= n) {
                steps++
                val q = link[2 * headPoly + (if (headRev) 1 else 0)]
                if (q < 0) break
                val prevPoly = q shr 1
                if (prevPoly == i) {
                    cyclic = true
                    break
                }
                // We leave the previous polyline through the port the link names. Leaving through
                // port 0 means it is traversed from its last point to its first, i.e. reversed.
                headRev = (q and 1) == 0
                headPoly = prevPoly
            }
            if (cyclic) {
                headPoly = i
                headRev = false
            }

            val pts = ArrayList<VecPoint>(32)
            var curPoly = headPoly
            var curRev = headRev
            var used = 0
            while (used <= n) {
                used++
                visited[curPoly] = true
                appendRun(pts, polylines[curPoly].points, curRev, pts.isNotEmpty())
                val q = link[2 * curPoly + (if (curRev) 0 else 1)]
                if (q < 0) break
                val nextPoly = q shr 1
                if (visited[nextPoly]) break
                curRev = (q and 1) == 1
                curPoly = nextPoly
            }

            if (cyclic && pts.size >= 4) {
                // The last polyline ends on the node the first one started from, so the closing
                // vertex is already in the list twice. Dropping it is what keeps a ring one closed
                // path instead of a path with a zero-length final segment.
                pts.removeAt(pts.size - 1)
                out.add(Polyline(pts, true))
            } else {
                out.add(Polyline(pts, false))
            }
        }
        return out
    }

    // -----------------------------------------------------------------------------------------

    /** 8-neighbour offsets, same clockwise ordering as the contour tracer: E, SE, S, SW, W, NW, N, NE. */
    private val DX8 = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DY8 = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    /** See [ChainParams.maxTurnDegrees]. */
    private const val MAX_TURN_DEGREES = 80f

    /** See [ChainParams.pruneRounds]; the same bound `Thinning.pruneSpurs` uses. */
    private const val MAX_PRUNE_ROUNDS = 10

    /**
     * A node's identity is its **rounded pixel coordinate**, packed into one Long.
     *
     * Rounding is exact here — every point [trace] emits is a pixel centre — and packing rather than
     * hashing a pair means the lookup cannot collide, so two strokes are judged to meet when they
     * meet on the same pixel and never otherwise.
     */
    private fun nodeKey(p: VecPoint): Long =
        p.y.roundToInt().toLong() * NODE_KEY_STRIDE + p.x.roundToInt().toLong()

    /** Wider than any image this engine will process, and small enough that the product is exact. */
    private const val NODE_KEY_STRIDE = 4_194_304L

    private fun degreesOfNodes(
        nodeOf: IntArray,
        alive: BooleanArray,
        nodeCount: Int,
        polylineCount: Int,
    ): IntArray {
        val degree = IntArray(nodeCount)
        for (i in 0 until polylineCount) {
            if (!alive[i]) continue
            degree[nodeOf[2 * i]]++
            degree[nodeOf[2 * i + 1]]++
        }
        return degree
    }

    /** Flattened length of a polyline, including the closing edge when it is closed. */
    private fun lengthOf(pl: Polyline): Double {
        val pts = pl.points
        var total = 0.0
        for (i in 1 until pts.size) {
            val dx = (pts[i].x - pts[i - 1].x).toDouble()
            val dy = (pts[i].y - pts[i - 1].y).toDouble()
            total += sqrt(dx * dx + dy * dy)
        }
        if (pl.closed && pts.size > 1) {
            val dx = (pts[0].x - pts[pts.size - 1].x).toDouble()
            val dy = (pts[0].y - pts[pts.size - 1].y).toDouble()
            total += sqrt(dx * dx + dy * dy)
        }
        return total
    }

    /**
     * Unit vector pointing from one end of [points] **into** the polyline, over a chord of at least
     * [span] pixels (or the whole polyline, whichever is shorter).
     *
     * @param fromStart true for the first point, false for the last
     * @return false when the chord is degenerate, which leaves that end unchainable rather than
     *   producing a direction from a zero-length vector.
     */
    private fun tangentAt(
        points: List<VecPoint>,
        fromStart: Boolean,
        span: Float,
        out: DoubleArray,
    ): Boolean {
        val n = points.size
        if (n < 2) return false
        val step = if (fromStart) 1 else -1
        var idx = if (fromStart) 0 else n - 1
        val end = points[idx]
        var far = end
        var acc = 0.0
        var count = 0
        while (count < n - 1) {
            val next = points[idx + step]
            val dx = (next.x - points[idx].x).toDouble()
            val dy = (next.y - points[idx].y).toDouble()
            acc += sqrt(dx * dx + dy * dy)
            idx += step
            far = next
            count++
            if (acc >= span) break
        }
        val vx = (far.x - end.x).toDouble()
        val vy = (far.y - end.y).toDouble()
        val len = sqrt(vx * vx + vy * vy)
        if (len < 1e-9) return false
        out[0] = vx / len
        out[1] = vy / len
        return true
    }

    /** Appends [src] to [dst], reversed if asked, skipping its first point when it duplicates a node. */
    private fun appendRun(
        dst: MutableList<VecPoint>,
        src: List<VecPoint>,
        reversed: Boolean,
        skipFirst: Boolean,
    ) {
        val n = src.size
        val from = if (skipFirst) 1 else 0
        if (reversed) {
            for (i in n - 1 - from downTo 0) dst.add(src[i])
        } else {
            for (i in from until n) dst.add(src[i])
        }
    }

    /** Foreground 8-neighbour count per pixel; 0 for background. Byte because the range is 0..8. */
    private fun degrees(skeleton: Mask): ByteArray {
        val w = skeleton.width
        val h = skeleton.height
        val fg = skeleton.data
        val degree = ByteArray(w * h)
        // Flat offsets for the interior fast path. Only valid away from the border, which is
        // exactly where the branch below applies them.
        val off = intArrayOf(1, 1 + w, w, w - 1, -1, -1 - w, -w, 1 - w)
        for (y in 0 until h) {
            val row = y * w
            val interiorRow = y > 0 && y < h - 1
            for (x in 0 until w) {
                val i = row + x
                if (!fg[i]) continue
                var c = 0
                if (interiorRow && x > 0 && x < w - 1) {
                    for (k in 0 until 8) if (fg[i + off[k]]) c++
                } else {
                    // Out of bounds reads as background (Mask.safe), which is what keeps a stroke
                    // running along the image edge from being treated as if it continued.
                    for (k in 0 until 8) if (skeleton.safe(x + DX8[k], y + DY8[k])) c++
                }
                degree[i] = c.toByte()
            }
        }
        return degree
    }

    /**
     * Walks one edge from [startNode] through [first] and every interior pixel beyond it, stopping
     * at the next node. Interior pixels are marked in [used] so the far node does not re-emit it.
     */
    private fun walkEdge(
        skeleton: Mask,
        degree: ByteArray,
        used: BooleanArray,
        startNode: Int,
        first: Int,
        cells: Int,
    ): Polyline {
        val w = skeleton.width
        val pts = ArrayList<VecPoint>(32)
        pts.add(pointOf(startNode, w))
        pts.add(pointOf(first, w))
        used[first] = true

        var prev = startNode
        var cur = first
        var endIndex = first
        var steps = 0
        while (steps <= cells) {
            steps++
            val next = otherNeighbour(skeleton, cur, prev)
            if (next < 0) break
            pts.add(pointOf(next, w))
            endIndex = next
            if (degree[next].toInt() != 2) break     // reached a node: the edge is complete
            if (used[next]) break                    // defensive; a clean graph cannot reach here
            used[next] = true
            prev = cur
            cur = next
        }

        // A branch that leaves a node and comes back to it is a loop. Emitting it closed drops the
        // duplicated anchor that would otherwise become a zero-length closing segment in the SVG.
        if (endIndex == startNode && pts.size >= 4) {
            pts.removeAt(pts.size - 1)
            return Polyline(pts, true)
        }
        return Polyline(pts, false)
    }

    /** Walks a cycle of interior-only pixels starting anywhere on it. */
    private fun walkLoop(skeleton: Mask, used: BooleanArray, seed: Int, cells: Int): Polyline {
        val w = skeleton.width
        val pts = ArrayList<VecPoint>(32)
        used[seed] = true
        pts.add(pointOf(seed, w))

        var prev = seed
        var cur = otherNeighbour(skeleton, seed, -1)   // either direction; a ring is symmetric
        var steps = 0
        while (cur >= 0 && cur != seed && steps <= cells) {
            steps++
            used[cur] = true
            pts.add(pointOf(cur, w))
            val next = otherNeighbour(skeleton, cur, prev)
            prev = cur
            cur = next
        }
        return Polyline(pts, true)
    }

    /** First foreground 8-neighbour of [i] other than [prev], or -1. Pass `prev = -1` for "any". */
    private fun otherNeighbour(skeleton: Mask, i: Int, prev: Int): Int {
        val w = skeleton.width
        val h = skeleton.height
        val fg = skeleton.data
        val x = i % w
        val y = i / w
        for (k in 0 until 8) {
            val nx = x + DX8[k]
            val ny = y + DY8[k]
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
            val q = ny * w + nx
            if (fg[q] && q != prev) return q
        }
        return -1
    }

    private fun pointOf(index: Int, w: Int): VecPoint =
        VecPoint((index % w).toFloat(), (index / w).toFloat())
}
