package com.offlinetracer.imaging

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Skeletonisation and skeleton repair (ALGORITHMS §9).
 *
 * The neighbour numbering used throughout is the one the Zhang–Suen paper uses: `P1` is the pixel
 * itself and `P2..P9` run **clockwise from north** in image coordinates (y down):
 *
 * ```
 *   P9 P2 P3
 *   P8 P1 P4
 *   P7 P6 P5
 * ```
 *
 * Every sub-iteration collects its deletions and applies them **after** the full pass. Deleting in
 * place is a one-line "optimisation" that makes the output depend on scan order — the result stops
 * matching the TypeScript port, and worse, it eats through 2px-wide strokes because the left half of
 * a stroke is gone by the time the right half is tested.
 */
object Thinning {

    /**
     * Zhang–Suen thinning, the two-sub-iteration form.
     *
     * **The skeleton of an even-width stroke is off its true centreline by exactly half a pixel.**
     * That is not a rounding error and it is not fixable inside a binary operator: a stroke covering
     * columns `[x0, x0+w-1]` has its centreline at `x0 + (w-1)/2`, which is a half-integer for even
     * `w` and therefore cannot be a pixel. Measured on axis-aligned bars of width 1 to 10, the bias is
     * **exactly 0 for every odd width and exactly −0.5 px for every even one** — toward decreasing x
     * and decreasing y, because sub-iteration 1 deletes the south and east boundary and so always wins
     * the last tie. [guoHall] is the mirror image, exactly **+0.5 px** on the same inputs, which is the
     * proof that the bias belongs to the sub-iteration ordering rather than to the input. A 45° bar of
     * any width measures 0.
     *
     * What it costs downstream: a stroke whose width varies along its length — every brush stroke,
     * every lit edge of a photographed object — crosses between even and odd width repeatedly, and the
     * traced centreline steps half a pixel sideways each time it does. That reads as a wobble.
     * Correcting it needs a half-pixel shift along the local stroke normal wherever the width is even,
     * which requires the distance transform and therefore belongs to the centreline tracer, not here.
     * Until then the bound is **0.5 px, one-sided, on even-width strokes only**.
     *
     * @param maxIterations hard bound on the number of full (two sub-iteration) passes; `≤ 0`
     *   returns a copy. The loop normally converges long before the default 200 — the bound exists
     *   so a pathological input degrades instead of hanging.
     * @return a new [Mask] the size of [src], thinned to (mostly) 8-connected unit-width strokes.
     */
    fun zhangSuen(src: Mask, maxIterations: Int = 200): Mask {
        val w = src.width
        val h = src.height
        val d = src.data.copyOf()
        if (maxIterations <= 0) return Mask(w, h, d)
        var fg = 0
        for (b in d) if (b) fg++
        if (fg == 0) return Mask(w, h, d)

        val del = IntArray(fg)
        var iter = 0
        while (iter < maxIterations) {
            var changed = false
            var step = 0
            while (step < 2) {
                var n = 0
                for (y in 0 until h) {
                    val row = y * w
                    for (x in 0 until w) {
                        val i = row + x
                        if (!d[i]) continue
                        val p2 = at(d, w, h, x, y - 1)
                        val p3 = at(d, w, h, x + 1, y - 1)
                        val p4 = at(d, w, h, x + 1, y)
                        val p5 = at(d, w, h, x + 1, y + 1)
                        val p6 = at(d, w, h, x, y + 1)
                        val p7 = at(d, w, h, x - 1, y + 1)
                        val p8 = at(d, w, h, x - 1, y)
                        val p9 = at(d, w, h, x - 1, y - 1)

                        var b = 0
                        if (p2) b++
                        if (p3) b++
                        if (p4) b++
                        if (p5) b++
                        if (p6) b++
                        if (p7) b++
                        if (p8) b++
                        if (p9) b++
                        if (b < 2 || b > 6) continue

                        var a = 0
                        if (!p2 && p3) a++
                        if (!p3 && p4) a++
                        if (!p4 && p5) a++
                        if (!p5 && p6) a++
                        if (!p6 && p7) a++
                        if (!p7 && p8) a++
                        if (!p8 && p9) a++
                        if (!p9 && p2) a++
                        if (a != 1) continue

                        if (step == 0) {
                            if (p2 && p4 && p6) continue
                            if (p4 && p6 && p8) continue
                        } else {
                            if (p2 && p4 && p8) continue
                            if (p2 && p6 && p8) continue
                        }
                        del[n++] = i
                    }
                }
                if (n > 0) {
                    for (k in 0 until n) d[del[k]] = false
                    changed = true
                }
                step++
            }
            if (!changed) break
            iter++
        }
        return Mask(w, h, d)
    }

    /**
     * Guo–Hall thinning. Preserves diagonal connectivity better than Zhang–Suen and grows noticeably
     * fewer spurs, at the cost of slightly thicker junctions — offered because those two failure
     * modes matter to different sources (engraving vs. pencil).
     *
     * Carries the same half-pixel centreline bias on even-width strokes as [zhangSuen] and in the
     * **opposite** direction, +0.5 px rather than −0.5. See the note there; switching between the two
     * is not a way to remove the bias, only to move it.
     *
     * @param maxIterations hard bound on the number of full passes; `≤ 0` returns a copy.
     * @return a new [Mask] the size of [src].
     */
    fun guoHall(src: Mask, maxIterations: Int = 200): Mask {
        val w = src.width
        val h = src.height
        val d = src.data.copyOf()
        if (maxIterations <= 0) return Mask(w, h, d)
        var fg = 0
        for (b in d) if (b) fg++
        if (fg == 0) return Mask(w, h, d)

        val del = IntArray(fg)
        var iter = 0
        while (iter < maxIterations) {
            var changed = false
            var step = 0
            while (step < 2) {
                var n = 0
                for (y in 0 until h) {
                    val row = y * w
                    for (x in 0 until w) {
                        val i = row + x
                        if (!d[i]) continue
                        val p2 = at(d, w, h, x, y - 1)
                        val p3 = at(d, w, h, x + 1, y - 1)
                        val p4 = at(d, w, h, x + 1, y)
                        val p5 = at(d, w, h, x + 1, y + 1)
                        val p6 = at(d, w, h, x, y + 1)
                        val p7 = at(d, w, h, x - 1, y + 1)
                        val p8 = at(d, w, h, x - 1, y)
                        val p9 = at(d, w, h, x - 1, y - 1)

                        var c = 0
                        if (!p2 && (p3 || p4)) c++
                        if (!p4 && (p5 || p6)) c++
                        if (!p6 && (p7 || p8)) c++
                        if (!p8 && (p9 || p2)) c++
                        if (c != 1) continue

                        var n1 = 0
                        if (p9 || p2) n1++
                        if (p3 || p4) n1++
                        if (p5 || p6) n1++
                        if (p7 || p8) n1++
                        var n2 = 0
                        if (p2 || p3) n2++
                        if (p4 || p5) n2++
                        if (p6 || p7) n2++
                        if (p8 || p9) n2++
                        val nn = if (n1 < n2) n1 else n2
                        if (nn < 2 || nn > 3) continue

                        val m = if (step == 0) (p6 || p7 || !p9) && p8 else (p2 || p3 || !p5) && p4
                        if (m) continue

                        del[n++] = i
                    }
                }
                if (n > 0) {
                    for (k in 0 until n) d[del[k]] = false
                    changed = true
                }
                step++
            }
            if (!changed) break
            iter++
        }
        return Mask(w, h, d)
    }

    /**
     * @return how many of the eight neighbours of `(x, y)` are set. The pixel itself is not
     *   inspected, so this is meaningful (and returns a count) for background pixels too.
     *   Out-of-bounds neighbours count as background, per the binary border policy.
     */
    fun neighbourCount(m: Mask, x: Int, y: Int): Int {
        var n = 0
        if (m.safe(x, y - 1)) n++
        if (m.safe(x + 1, y - 1)) n++
        if (m.safe(x + 1, y)) n++
        if (m.safe(x + 1, y + 1)) n++
        if (m.safe(x, y + 1)) n++
        if (m.safe(x - 1, y + 1)) n++
        if (m.safe(x - 1, y)) n++
        if (m.safe(x - 1, y - 1)) n++
        return n
    }

    /**
     * Counts `0 → 1` transitions in the **ordered** ring `P2,P3,…,P9,P2` (the wrap-around pair is
     * part of the sequence — dropping it is the classic Zhang–Suen bug that leaves the algorithm
     * unable to recognise a simple corner).
     *
     * @return the transition count, 0..4.
     */
    fun transitions(m: Mask, x: Int, y: Int): Int {
        val p2 = m.safe(x, y - 1)
        val p3 = m.safe(x + 1, y - 1)
        val p4 = m.safe(x + 1, y)
        val p5 = m.safe(x + 1, y + 1)
        val p6 = m.safe(x, y + 1)
        val p7 = m.safe(x - 1, y + 1)
        val p8 = m.safe(x - 1, y)
        val p9 = m.safe(x - 1, y - 1)
        var a = 0
        if (!p2 && p3) a++
        if (!p3 && p4) a++
        if (!p4 && p5) a++
        if (!p5 && p6) a++
        if (!p6 && p7) a++
        if (!p7 && p8) a++
        if (!p8 && p9) a++
        if (!p9 && p2) a++
        return a
    }

    /**
     * Removes branches shorter than [minBranchLength] that run from a degree-1 pixel to a junction —
     * the "hair" that thinning grows on every noisy blob and that otherwise becomes dozens of
     * three-point paths in the SVG.
     *
     * A run that ends at another degree-1 pixel is a whole short stroke, not a branch, and is left
     * alone; dropping those is [Components.removeSmallBlobs]'s job and conflating the two silently
     * deletes legitimate detail. The classification is recomputed from scratch after each round,
     * because removing one branch changes the degree of the junction it hung from.
     *
     * The walk stops at a **crossing number** ≥ 3 ([transitions]), not at a neighbour count ≥ 3.
     * They disagree on exactly the case that matters. In any 8-connected skeleton a branch leaving a
     * horizontal trunk looks like
     * ```
     *   . X .        the middle row's X has FOUR neighbours — up, and all three trunk pixels
     *   . X .        below it — yet it is plainly part of the branch, not a junction.
     *   X X X        Its crossing number is 2 (one run of neighbours above, one below): interior.
     * ```
     * Stopping on the neighbour count therefore deletes only the tip and leaves a permanent 1px stub
     * on every branch it prunes — visible in the SVG as a two-point path hanging off the line, which
     * is precisely the artefact this function exists to remove.
     *
     * @param minBranchLength branches of exactly this many pixels survive; `≤ 0` returns a copy.
     * @param maxRounds hard bound on the recompute-and-prune loop.
     * @return a new [Mask] the size of [skeleton].
     */
    fun pruneSpurs(skeleton: Mask, minBranchLength: Int, maxRounds: Int = 10): Mask {
        val w = skeleton.width
        val h = skeleton.height
        val d = skeleton.data.copyOf()
        if (minBranchLength <= 0 || maxRounds <= 0) return Mask(w, h, d)

        // Capped so the path buffer stays small; nothing at working resolution has a 65k-pixel spur.
        val limit = if (minBranchLength > 65536) 65536 else minBranchLength
        val path = IntArray(limit)
        val toDelete = BooleanArray(d.size)

        var round = 0
        while (round < maxRounds) {
            java.util.Arrays.fill(toDelete, false)
            var removedAny = false
            for (start in d.indices) {
                if (!d[start]) continue
                if (degree(d, w, h, start) != 1) continue

                var prev = -1
                var cur = start
                var n = 0
                var hitJunction = false
                while (n < limit) {
                    if (n > 0 && crossing(d, w, h, cur) >= 3) {
                        hitJunction = true
                        break
                    }
                    path[n++] = cur
                    val nxt = pickNext(d, w, h, cur, prev)
                    if (nxt < 0) break
                    prev = cur
                    cur = nxt
                }
                if (hitJunction && n < limit) {
                    for (k in 0 until n) toDelete[path[k]] = true
                    removedAny = true
                }
            }
            if (!removedAny) break
            for (i in d.indices) if (toDelete[i]) d[i] = false
            round++
        }
        return Mask(w, h, d)
    }

    /**
     * Joins skeleton endpoints across gaps of at most [maxGap] pixels (ALGORITHMS §9, stage 2).
     *
     * Pairs are considered in ascending distance order and **each endpoint is used at most once** —
     * greedy nearest-first. A pair is joined only when
     *  - the two outgoing directions face each other: `dot(dirA, −dirB) > cos(maxAngle)`, and
     *  - the straight segment between them crosses no existing ink (including ink drawn by an
     *    earlier bridge in this same call, which is what stops a fan of endpoints being stitched
     *    into a cat's cradle).
     *
     * Each endpoint's direction is estimated from the ~5 skeleton pixels behind it, so it is the
     * direction of the stroke rather than of its last staircase step. Endpoints whose walk-back
     * finds nothing (an isolated pixel) have no direction and are never paired.
     *
     * @param maxGap `≤ 0` returns a copy; internally capped at 1024 px.
     * @param maxAngleDegrees half-angle of the acceptance cone, clamped to 0..180.
     * @return a new [Mask] the size of [skeleton].
     */
    fun bridgeEndpoints(skeleton: Mask, maxGap: Int, maxAngleDegrees: Float = 60f): Mask {
        val w = skeleton.width
        val h = skeleton.height
        val d = skeleton.data.copyOf()
        if (maxGap <= 0) return Mask(w, h, d)
        val gap = if (maxGap > 1024) 1024 else maxGap

        val eps = endpointIndices(d, w, h)
        val m = eps.size
        // The candidate key packs (distance², i, j) into one long: 21 + 20 + 20 bits. More than a
        // million endpoints means the skeleton is noise, and bridging noise helps nobody.
        if (m < 2 || m > 0xFFFFF) return Mask(w, h, d)

        val dirX = FloatArray(m)
        val dirY = FloatArray(m)
        val hasDir = BooleanArray(m)
        for (i in 0 until m) {
            val e = eps[i]
            val back = walkBack(d, w, h, e, 5)
            if (back == e) continue
            val vx = (e % w - back % w).toFloat()
            val vy = (e / w - back / w).toFloat()
            val len = sqrt(vx * vx + vy * vy)
            if (len > 1e-6f) {
                dirX[i] = vx / len
                dirY[i] = vy / len
                hasDir[i] = true
            }
        }

        // Uniform grid with cells the size of the gap, so each endpoint only tests the 3×3 cells
        // around it. All-pairs is O(E²) and a noisy skeleton has tens of thousands of endpoints.
        val gw = (w + gap - 1) / gap
        val gh = (h + gap - 1) / gap
        val cellOf = IntArray(m)
        val cellStart = IntArray(gw * gh + 1)
        for (i in 0 until m) {
            val c = (eps[i] / w / gap) * gw + (eps[i] % w / gap)
            cellOf[i] = c
            cellStart[c + 1]++
        }
        for (c in 1..gw * gh) cellStart[c] += cellStart[c - 1]
        val cursor = cellStart.copyOf()
        val order = IntArray(m)
        for (i in 0 until m) {
            order[cursor[cellOf[i]]] = i
            cursor[cellOf[i]]++
        }

        val g2 = gap * gap
        var keys = LongArray(256)
        var nk = 0
        for (i in 0 until m) {
            if (!hasDir[i]) continue
            val xi = eps[i] % w
            val yi = eps[i] / w
            val cx = xi / gap
            val cy = yi / gap
            var ny = if (cy > 0) cy - 1 else 0
            val nyEnd = if (cy + 1 < gh) cy + 1 else gh - 1
            while (ny <= nyEnd) {
                var nx = if (cx > 0) cx - 1 else 0
                val nxEnd = if (cx + 1 < gw) cx + 1 else gw - 1
                while (nx <= nxEnd) {
                    val c = ny * gw + nx
                    var p = cellStart[c]
                    val end = cellStart[c + 1]
                    while (p < end) {
                        val j = order[p]
                        p++
                        if (j <= i || !hasDir[j]) continue
                        val dx = eps[j] % w - xi
                        val dy = eps[j] / w - yi
                        val dist2 = dx * dx + dy * dy
                        if (dist2 == 0 || dist2 > g2) continue
                        if (nk == keys.size) {
                            if (keys.size >= (1 shl 22)) break
                            keys = keys.copyOf(keys.size * 2)
                        }
                        keys[nk++] = (dist2.toLong() shl 40) or (i.toLong() shl 20) or j.toLong()
                    }
                    nx++
                }
                ny++
            }
        }
        if (nk == 0) return Mask(w, h, d)

        java.util.Arrays.sort(keys, 0, nk)
        val angle = if (maxAngleDegrees < 0f) 0f else if (maxAngleDegrees > 180f) 180f else maxAngleDegrees
        val cosLimit = cos(angle.toDouble() * PI / 180.0).toFloat()
        val used = BooleanArray(m)
        for (k in 0 until nk) {
            val key = keys[k]
            val i = ((key ushr 20) and 0xFFFFFL).toInt()
            val j = (key and 0xFFFFFL).toInt()
            if (used[i] || used[j]) continue
            val facing = dirX[i] * -dirX[j] + dirY[i] * -dirY[j]
            if (facing <= cosLimit) continue
            if (crossesInk(d, w, h, eps[i], eps[j])) continue
            drawLine(d, w, h, eps[i], eps[j])
            used[i] = true
            used[j] = true
        }
        return Mask(w, h, d)
    }

    /**
     * @return packed `y * width + x` indices of every set pixel with exactly one set neighbour, in
     *   scan order. Empty when there are none.
     */
    fun endpoints(skeleton: Mask): IntArray =
        collect(skeleton.data, skeleton.width, skeleton.height, 1, 1)

    /**
     * @return packed `y * width + x` indices of every set pixel with three or more set neighbours,
     *   in scan order. Empty when there are none. These are the branch points the centreline tracer
     *   must not walk through.
     */
    fun junctions(skeleton: Mask): IntArray =
        collect(skeleton.data, skeleton.width, skeleton.height, 3, 8)

    // ---------------------------------------------------------------------------------------

    /** Set pixels whose neighbour count falls in `[lo, hi]`, counted then filled — two passes so the
     *  result array is exact and nothing has to grow. */
    private fun collect(d: BooleanArray, w: Int, h: Int, lo: Int, hi: Int): IntArray {
        var n = 0
        for (i in d.indices) {
            if (!d[i]) continue
            val deg = degree(d, w, h, i)
            if (deg in lo..hi) n++
        }
        val out = IntArray(n)
        var k = 0
        for (i in d.indices) {
            if (!d[i]) continue
            val deg = degree(d, w, h, i)
            if (deg in lo..hi) out[k++] = i
        }
        return out
    }

    private fun endpointIndices(d: BooleanArray, w: Int, h: Int): IntArray = collect(d, w, h, 1, 1)

    private fun at(d: BooleanArray, w: Int, h: Int, x: Int, y: Int): Boolean =
        if (x < 0 || y < 0 || x >= w || y >= h) false else d[y * w + x]

    private fun degree(d: BooleanArray, w: Int, h: Int, idx: Int): Int {
        val x = idx % w
        val y = idx / w
        var n = 0
        if (at(d, w, h, x, y - 1)) n++
        if (at(d, w, h, x + 1, y - 1)) n++
        if (at(d, w, h, x + 1, y)) n++
        if (at(d, w, h, x + 1, y + 1)) n++
        if (at(d, w, h, x, y + 1)) n++
        if (at(d, w, h, x - 1, y + 1)) n++
        if (at(d, w, h, x - 1, y)) n++
        if (at(d, w, h, x - 1, y - 1)) n++
        return n
    }

    /** Crossing number of [idx] — [transitions] over a raw [BooleanArray]. */
    private fun crossing(d: BooleanArray, w: Int, h: Int, idx: Int): Int {
        val x = idx % w
        val y = idx / w
        val p2 = at(d, w, h, x, y - 1)
        val p3 = at(d, w, h, x + 1, y - 1)
        val p4 = at(d, w, h, x + 1, y)
        val p5 = at(d, w, h, x + 1, y + 1)
        val p6 = at(d, w, h, x, y + 1)
        val p7 = at(d, w, h, x - 1, y + 1)
        val p8 = at(d, w, h, x - 1, y)
        val p9 = at(d, w, h, x - 1, y - 1)
        var a = 0
        if (!p2 && p3) a++
        if (!p3 && p4) a++
        if (!p4 && p5) a++
        if (!p5 && p6) a++
        if (!p6 && p7) a++
        if (!p7 && p8) a++
        if (!p8 && p9) a++
        if (!p9 && p2) a++
        return a
    }

    /**
     * The neighbour of [idx] (other than [exclude]) that the branch walk should continue to: the
     * candidate with the **highest crossing number**, ties going to the first in ring order
     * `P2..P9`. Preferring the highest is what steers the last step of a branch onto the junction
     * itself rather than off along the trunk, which would make the walk overrun its length limit and
     * conclude the branch was long enough to keep.
     *
     * @return the chosen neighbour index, or -1 when there is none.
     */
    private fun pickNext(d: BooleanArray, w: Int, h: Int, idx: Int, exclude: Int): Int {
        val x = idx % w
        val y = idx / w
        var best = -1
        var bestCrossing = -1
        var k = 0
        while (k < 8) {
            val nx = x + RING_X[k]
            val ny = y + RING_Y[k]
            k++
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
            val j = ny * w + nx
            if (!d[j] || j == exclude) continue
            val c = crossing(d, w, h, j)
            if (c > bestCrossing) {
                bestCrossing = c
                best = j
            }
        }
        return best
    }

    /** P2..P9 offsets: clockwise from north, y down. */
    private val RING_X = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private val RING_Y = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

    /** The single set neighbour of [idx] other than [exclude], or -1 when there is none or more than
     *  one. Returning -1 at a junction is what makes the walkers stop there instead of guessing. */
    private fun soleNeighbour(d: BooleanArray, w: Int, h: Int, idx: Int, exclude: Int): Int {
        val x = idx % w
        val y = idx / w
        var found = -1
        var n = 0
        var dy = -1
        while (dy <= 1) {
            val ny = y + dy
            if (ny in 0 until h) {
                var dx = -1
                while (dx <= 1) {
                    val nx = x + dx
                    if ((dx != 0 || dy != 0) && nx >= 0 && nx < w) {
                        val j = ny * w + nx
                        if (d[j] && j != exclude) {
                            n++
                            if (n > 1) return -1
                            found = j
                        }
                    }
                    dx++
                }
            }
            dy++
        }
        return if (n == 1) found else -1
    }

    /** Walk inward from an endpoint up to [steps] pixels, stopping early at a junction or a dead
     *  end. Returns the pixel reached, which equals [start] when the walk could not move. */
    private fun walkBack(d: BooleanArray, w: Int, h: Int, start: Int, steps: Int): Int {
        var prev = -1
        var cur = start
        var i = 0
        while (i < steps) {
            val nxt = soleNeighbour(d, w, h, cur, prev)
            if (nxt < 0) break
            prev = cur
            cur = nxt
            i++
        }
        return cur
    }

    /** True when any pixel strictly between [a] and [b] on the Bresenham line is already set. */
    private fun crossesInk(d: BooleanArray, w: Int, h: Int, a: Int, b: Int): Boolean {
        var x = a % w
        var y = a / w
        val x1 = b % w
        val y1 = b / w
        val dx = if (x1 > x) x1 - x else x - x1
        val dy = -(if (y1 > y) y1 - y else y - y1)
        val sx = if (x < x1) 1 else -1
        val sy = if (y < y1) 1 else -1
        var err = dx + dy
        while (true) {
            if (x == x1 && y == y1) return false
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
            if (x == x1 && y == y1) return false
            if (d[y * w + x]) return true
        }
    }

    /** Set every pixel on the Bresenham line from [a] to [b], endpoints included. */
    private fun drawLine(d: BooleanArray, w: Int, h: Int, a: Int, b: Int) {
        var x = a % w
        var y = a / w
        val x1 = b % w
        val y1 = b / w
        val dx = if (x1 > x) x1 - x else x - x1
        val dy = -(if (y1 > y) y1 - y else y - y1)
        val sx = if (x < x1) 1 else -1
        val sy = if (y < y1) 1 else -1
        var err = dx + dy
        while (true) {
            d[y * w + x] = true
            if (x == x1 && y == y1) return
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
        }
    }
}
