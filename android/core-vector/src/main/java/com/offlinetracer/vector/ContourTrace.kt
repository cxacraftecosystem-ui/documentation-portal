package com.offlinetracer.vector

import com.offlinetracer.imaging.Mask

/**
 * Region boundaries as closed polygons — the geometry source for **outline** mode.
 *
 * Tracing is Moore-neighbour following with **Jacob's stopping criterion**: the walk ends when the
 * start pixel is re-entered *from the same direction it was first entered from*, not merely when it
 * is revisited. The naive "revisited the start" test is a real hang, not a theoretical one: a shape
 * with a one-pixel isthmus passes through its own start pixel mid-walk, so the naive test either
 * stops after one lobe and silently drops the rest of the outline, or — with the check placed after
 * the step — never stops at all.
 *
 * Winding is **clockwise for outer contours and counter-clockwise for holes**, which is what lets
 * the SVG writer emit one path per component with `fill-rule="evenodd"` and get holes for free:
 * no nesting analysis, no subpath bookkeeping, no even/odd depth counting.
 *
 * Every helper lives inside this object rather than at file scope because sibling files in this
 * package are written by other authors and a top-level `DX8` would be a package-wide redeclaration.
 */
object ContourTrace {

    /**
     * One closed boundary. [points] are pixel centres in path space and the polygon is
     * **implicitly closed** — the first point is not repeated at the end.
     *
     * @param isHole true for an interior boundary (counter-clockwise); false for an outer one.
     * @param label the 1-based connected-component index the boundary belongs to; a hole carries
     *   the label of the component that encloses it, so a component's outline and its holes group
     *   together by equality on this field.
     */
    data class Contour(val points: List<VecPoint>, val isHole: Boolean, val label: Int)

    /**
     * Every outer contour plus every hole contour of [mask]. Outer contours come first, each in
     * raster order of its seed pixel, then holes in raster order of theirs, so the result is
     * deterministic for a given mask.
     *
     * An all-background mask returns an empty list. An isolated single pixel returns a one-point
     * contour rather than nothing: a dot is legitimate artwork, and whether it survives is the
     * caller's minimum-area decision, not this function's.
     */
    fun trace(mask: Mask): List<Contour> = traceInternal(mask, includeHoles = true)

    /**
     * As [trace] but hole boundaries are skipped — used for silhouette and stencil output, where
     * interior detail must fill solid.
     */
    fun traceOuterOnly(mask: Mask): List<Contour> = traceInternal(mask, includeHoles = false)

    // -----------------------------------------------------------------------------------------
    // Neighbourhood tables
    // -----------------------------------------------------------------------------------------

    /**
     * Moore-neighbour offsets in **clockwise** screen order (y is down), so advancing the index
     * turns right: E, SE, S, SW, W, NW, N, NE.
     *
     * The entire orientation contract of this file rests on that ordering — searching the
     * neighbourhood with an increasing index is what makes an outer contour come out clockwise.
     */
    private val DX8 = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DY8 = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    /** Inverse of [DX8]/[DY8]: `DIR_OF[(dy + 1) * 3 + (dx + 1)]` for a unit step, -1 for `(0, 0)`. */
    private val DIR_OF = intArrayOf(5, 6, 7, 4, -1, 0, 3, 2, 1)

    private const val DIR_S = 2
    private const val DIR_W = 4

    // -----------------------------------------------------------------------------------------

    private fun traceInternal(mask: Mask, includeHoles: Boolean): List<Contour> {
        val w = mask.width
        val h = mask.height
        val n = w * h
        val fg = mask.data
        val out = ArrayList<Contour>()

        // 8-connected labelling of the ink, done here rather than borrowed from :core-imaging so
        // this file depends on Mask alone and can be unit-tested without the rest of the engine.
        val labels = IntArray(n)
        val stack = IntStack(1024)
        var count = 0
        for (i in 0 until n) {
            if (fg[i] && labels[i] == 0) {
                count++
                floodForeground(mask, labels, count, i, stack)
            }
        }
        if (count == 0) return out

        // One outer contour per component, seeded at that component's first pixel in raster order.
        // That pixel's west neighbour is necessarily background — a foreground west neighbour
        // would be 8-connected to it and so already labelled — which makes W a valid backtrack and
        // brings the walk out clockwise.
        val seeded = BooleanArray(count + 1)
        for (i in 0 until n) {
            val label = labels[i]
            if (label != 0 && !seeded[label]) {
                seeded[label] = true
                out.add(Contour(moore(mask, i % w, i / w, DIR_W, n), false, label))
            }
        }
        if (!includeHoles) return out

        // Background floods 4-connected against 8-connected foreground. That pairing is what keeps
        // the Jordan property — an 8-connected ink loop always encloses its interior. Flooding both
        // at 8 lets background leak diagonally out through a 1 px loop and every hole vanishes.
        val bgSeen = BooleanArray(n)
        floodOutsideBackground(mask, bgSeen, stack)

        for (i in 0 until n) {
            if (fg[i] || bgSeen[i]) continue
            // The first unseen background pixel in raster order is the topmost-leftmost pixel of
            // this hole, so the pixel above it is ink and belongs to the enclosing component.
            // Backtrack S points into the hole, and that is what flips the walk counter-clockwise.
            val hx = i % w
            val hy = i / w
            floodHoleBackground(mask, bgSeen, i, stack)
            out.add(Contour(moore(mask, hx, hy - 1, DIR_S, n), true, labels[i - w]))
        }
        return out
    }

    /**
     * The boundary walk. [startBacktrack] must point at a background cell adjacent to the seed;
     * *which* background region it points into selects the boundary that gets traced, and with it
     * the winding direction.
     */
    private fun moore(mask: Mask, sx: Int, sy: Int, startBacktrack: Int, cells: Int): List<VecPoint> {
        val pts = ArrayList<VecPoint>(64)
        pts.add(VecPoint(sx.toFloat(), sy.toFloat()))

        // Jacob's criterion has to be anchored to the direction the cycle ACTUALLY arrives at the seed
        // from, which is not necessarily the `startBacktrack` the caller supplied.
        //
        // `startBacktrack` only has to name *a* background neighbour of the seed — that is all the
        // caller can cheaply guarantee. Whether the boundary walk re-enters the seed from that same
        // direction depends on the local shape. When it does not, the initial state is a transient
        // tail feeding a cycle that never contains it, `newBacktrack == startBacktrack` can never
        // hold, and the walk runs to the step cap.
        //
        // Not hypothetical, and not limited to holes: on a 24x18 parity fixture an OUTER contour
        // seeded with W ran to the 3464-step cap and returned 3465 points for a 40-point boundary,
        // and the SVG for it repeated a single cubic about ninety times.
        //
        // [cycleEntryBacktrack] resolves the real entry direction first, so the ring below still
        // begins at the seed — which the parity fixtures record vertex by vertex, and which a
        // rotation would silently break.
        val entryBacktrack = cycleEntryBacktrack(mask, sx, sy, startBacktrack, cells)

        var cx = sx
        var cy = sy
        var backtrack = entryBacktrack

        val maxSteps = 8L * cells + 8L
        var steps = 0L

        while (steps < maxSteps) {
            steps++
            var probed = backtrack       // last background cell examined, in old-centre directions
            var found = -1
            for (j in 1..8) {
                val k = (backtrack + j) and 7
                if (mask.safe(cx + DX8[k], cy + DY8[k])) {
                    found = k
                    break
                }
                probed = k
            }
            if (found < 0) break         // isolated pixel: the single-point contour is the answer

            val nx = cx + DX8[found]
            val ny = cy + DY8[found]
            // New backtrack = direction from the new centre back to the last background cell
            // examined. `probed` and `found` are adjacent in the cyclic order, so their cells are
            // always one unit step apart and DIR_OF is always defined.
            val bx = cx + DX8[probed] - nx
            val by = cy + DY8[probed] - ny
            val newBacktrack = DIR_OF[(by + 1) * 3 + (bx + 1)]

            // Jacob's criterion: same pixel *and* same entry direction.
            if (nx == sx && ny == sy && newBacktrack == entryBacktrack) break

            pts.add(VecPoint(nx.toFloat(), ny.toFloat()))
            cx = nx
            cy = ny
            backtrack = newBacktrack
        }
        return pts
    }

    /**
     * The direction the boundary cycle re-enters the seed from.
     *
     * Walks the same step function without collecting anything and returns the backtrack recorded on
     * the first return to `(sx, sy)`. That state is by construction in the image of the step map, so
     * it lies on the cycle and anchoring Jacob's criterion to it is guaranteed to terminate.
     *
     * Falls back to [startBacktrack] when the seed is never revisited — an isolated pixel, which has
     * no cycle at all, and the caller's single-point contour is the right answer there.
     */
    private fun cycleEntryBacktrack(
        mask: Mask,
        sx: Int,
        sy: Int,
        startBacktrack: Int,
        cells: Int,
    ): Int {
        var cx = sx
        var cy = sy
        var backtrack = startBacktrack
        val maxSteps = 8L * cells + 8L
        var steps = 0L

        while (steps < maxSteps) {
            steps++
            var probed = backtrack
            var found = -1
            for (j in 1..8) {
                val k = (backtrack + j) and 7
                if (mask.safe(cx + DX8[k], cy + DY8[k])) {
                    found = k
                    break
                }
                probed = k
            }
            if (found < 0) return startBacktrack

            val nx = cx + DX8[found]
            val ny = cy + DY8[found]
            val bx = cx + DX8[probed] - nx
            val by = cy + DY8[probed] - ny
            val newBacktrack = DIR_OF[(by + 1) * 3 + (bx + 1)]

            if (nx == sx && ny == sy) return newBacktrack

            cx = nx
            cy = ny
            backtrack = newBacktrack
        }
        return startBacktrack
    }

    private fun floodForeground(mask: Mask, labels: IntArray, label: Int, seed: Int, stack: IntStack) {
        val w = mask.width
        val h = mask.height
        val fg = mask.data
        stack.clear()
        labels[seed] = label
        stack.push(seed)
        while (stack.size > 0) {
            val p = stack.pop()
            val x = p % w
            val y = p / w
            for (k in 0 until 8) {
                val nx = x + DX8[k]
                val ny = y + DY8[k]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val q = ny * w + nx
                if (fg[q] && labels[q] == 0) {
                    labels[q] = label
                    stack.push(q)
                }
            }
        }
    }

    /** Marks every background pixel reachable 4-connected from the image border. */
    private fun floodOutsideBackground(mask: Mask, seen: BooleanArray, stack: IntStack) {
        val w = mask.width
        val h = mask.height
        val fg = mask.data
        stack.clear()
        for (x in 0 until w) {
            val top = x
            if (!fg[top] && !seen[top]) { seen[top] = true; stack.push(top) }
            val bottom = (h - 1) * w + x
            if (!fg[bottom] && !seen[bottom]) { seen[bottom] = true; stack.push(bottom) }
        }
        for (y in 0 until h) {
            val left = y * w
            if (!fg[left] && !seen[left]) { seen[left] = true; stack.push(left) }
            val right = y * w + (w - 1)
            if (!fg[right] && !seen[right]) { seen[right] = true; stack.push(right) }
        }
        drainBackground(mask, seen, stack)
    }

    private fun floodHoleBackground(mask: Mask, seen: BooleanArray, seed: Int, stack: IntStack) {
        stack.clear()
        seen[seed] = true
        stack.push(seed)
        drainBackground(mask, seen, stack)
    }

    private fun drainBackground(mask: Mask, seen: BooleanArray, stack: IntStack) {
        val w = mask.width
        val h = mask.height
        val fg = mask.data
        while (stack.size > 0) {
            val p = stack.pop()
            val x = p % w
            val y = p / w
            if (x > 0) { val q = p - 1; if (!fg[q] && !seen[q]) { seen[q] = true; stack.push(q) } }
            if (x < w - 1) { val q = p + 1; if (!fg[q] && !seen[q]) { seen[q] = true; stack.push(q) } }
            if (y > 0) { val q = p - w; if (!fg[q] && !seen[q]) { seen[q] = true; stack.push(q) } }
            if (y < h - 1) { val q = p + w; if (!fg[q] && !seen[q]) { seen[q] = true; stack.push(q) } }
        }
    }

    /**
     * Growable `IntArray` stack. Every flood fill here uses one instead of recursion: a 12 MP
     * background region is ~12 million deep, and the resulting `StackOverflowError` surfaces on
     * Android as an apparently random OOM inside whatever allocated next — a bug that costs days.
     */
    private class IntStack(initialCapacity: Int) {
        private var data = IntArray(if (initialCapacity < 16) 16 else initialCapacity)

        var size: Int = 0
            private set

        fun push(v: Int) {
            if (size == data.size) data = data.copyOf(data.size shl 1)
            data[size++] = v
        }

        fun pop(): Int = data[--size]

        fun clear() {
            size = 0
        }
    }
}
