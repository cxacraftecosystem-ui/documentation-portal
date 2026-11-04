package com.offlinetracer.vector

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import com.offlinetracer.imaging.Px
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

object SkeletonTrace {

    data class Polyline(val points: List<VecPoint>, val closed: Boolean)

    fun trace(skeleton: Mask): List<Polyline> {
        val w = skeleton.width
        val h = skeleton.height
        val n = w * h
        val fg = skeleton.data
        val polys = ArrayList<Polyline>()

        val degree = degrees(skeleton)
        val used = BooleanArray(n)

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
                    if (used[q]) continue
                    polys.add(walkEdge(skeleton, degree, used, i, q, n))
                } else if (i < q) {
                    polys.add(Polyline(listOf(pointOf(i, w), pointOf(q, w)), false))
                }
            }
        }

        for (i in 0 until n) {
            if (!fg[i] || used[i] || degree[i].toInt() != 2) continue
            polys.add(walkLoop(skeleton, used, i, n))
        }
        return polys
    }

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

    data class ChainParams(
        val maxTurnDegrees: Float = 40f,
        val tangentSpan: Float = 6f,
        val minBranchLength: Float = 0f,
        val pruneRounds: Int = 3,
        val maxNodeDegree: Int = 4,
    )

    fun chain(polylines: List<Polyline>, params: ChainParams = ChainParams()): List<Polyline> {
        val n = polylines.size
        if (n == 0) return polylines

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
                for (i in 0 until n) if (doomed[i]) alive[i] = false
            }
        }

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
                    if ((ea shr 1) == (eb shr 1)) continue
                    val dot = -(tanX[ea] * tanX[eb] + tanY[ea] * tanY[eb])
                    if (dot < cosLimit) continue
                    candDot.add(dot)
                    candNode.add(v)
                    candA.add(ea)
                    candB.add(eb)
                }
            }
        }

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

        val out = ArrayList<Polyline>(n)
        val visited = BooleanArray(n)
        for (i in 0 until n) {
            if (!chainable[i]) {
                out.add(polylines[i])
                continue
            }
            if (!alive[i] || visited[i]) continue

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
                pts.removeAt(pts.size - 1)
                out.add(Polyline(pts, true))
            } else {
                out.add(Polyline(pts, false))
            }
        }
        return out
    }

    private val DX8 = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DY8 = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    private const val MAX_TURN_DEGREES = 80f

    private const val MAX_PRUNE_ROUNDS = 10

    private fun nodeKey(p: VecPoint): Long =
        p.y.roundToInt().toLong() * NODE_KEY_STRIDE + p.x.roundToInt().toLong()

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

    private fun degrees(skeleton: Mask): ByteArray {
        val w = skeleton.width
        val h = skeleton.height
        val fg = skeleton.data
        val degree = ByteArray(w * h)
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
                    for (k in 0 until 8) if (skeleton.safe(x + DX8[k], y + DY8[k])) c++
                }
                degree[i] = c.toByte()
            }
        }
        return degree
    }

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
            if (degree[next].toInt() != 2) break
            if (used[next]) break
            used[next] = true
            prev = cur
            cur = next
        }

        if (endIndex == startNode && pts.size >= 4) {
            pts.removeAt(pts.size - 1)
            return Polyline(pts, true)
        }
        return Polyline(pts, false)
    }

    private fun walkLoop(skeleton: Mask, used: BooleanArray, seed: Int, cells: Int): Polyline {
        val w = skeleton.width
        val pts = ArrayList<VecPoint>(32)
        used[seed] = true
        pts.add(pointOf(seed, w))

        var prev = seed
        var cur = otherNeighbour(skeleton, seed, -1)
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
