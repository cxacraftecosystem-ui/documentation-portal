package com.offlinetracer.vector

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Point-count reduction and corner analysis for traced polylines.
 *
 * Both tracers emit one vertex per pixel, so a photographed drawing arrives here as hundreds of
 * thousands of points. Everything downstream — Bezier fitting, boolean ops, the SVG writer — costs
 * per point, so this stage decides how big the export is and how long the fit takes.
 *
 * Helpers are scoped inside the object because sibling files in this package have other authors and
 * a top-level helper would be a package-wide redeclaration.
 */
object Simplify {

    /**
     * Douglas–Peucker simplification with tolerance [epsilon] in path units (working pixels).
     * The first and last points are always kept; every dropped point lies within [epsilon] of the
     * retained polyline.
     *
     * **Iterative, with an explicit stack.** The textbook recursive form is not usable here: a
     * 200 000-point contour off a noisy photograph is ordinary input, its recursion depth is O(n) in
     * the worst case (a monotone staircase), and the resulting `StackOverflowError` on Android is
     * usually reported as a random OOM in whatever allocated next.
     *
     * The input is treated as an **open** polyline. A closed contour keeps its first and last points
     * — which is what preserves the closing edge — so callers may pass one directly; rotating a
     * contour so its extreme point comes first gives a slightly better result and is the caller's
     * choice, not this function's.
     *
     * Returns the input unchanged for fewer than three points or a non-positive [epsilon].
     */
    fun douglasPeucker(points: List<VecPoint>, epsilon: Float): List<VecPoint> {
        val n = points.size
        if (n <= 2 || epsilon <= 0f) return points

        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val p = points[i]
            xs[i] = p.x
            ys[i] = p.y
        }

        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true

        var stack = IntArray(64)
        var sp = 0
        stack[sp++] = 0
        stack[sp++] = n - 1

        val epsSq = epsilon.toDouble() * epsilon.toDouble()

        while (sp > 0) {
            val last = stack[--sp]
            val first = stack[--sp]
            if (last <= first + 1) continue

            val x0 = xs[first].toDouble()
            val y0 = ys[first].toDouble()
            val dx = xs[last] - x0
            val dy = ys[last] - y0
            val lenSq = dx * dx + dy * dy

            var bestIndex = -1
            var bestValue = 0.0
            var split = false

            if (lenSq < 1e-12) {
                // The span's endpoints coincide, which happens whenever a closed contour is handed
                // in whole. Perpendicular distance is undefined there, so fall back to radial
                // distance from the shared endpoint; without this the whole span collapses to two
                // points and a ring simplifies to nothing.
                for (i in first + 1 until last) {
                    val ex = xs[i] - x0
                    val ey = ys[i] - y0
                    val d = ex * ex + ey * ey
                    if (d > bestValue) {
                        bestValue = d
                        bestIndex = i
                    }
                }
                split = bestIndex >= 0 && bestValue > epsSq
            } else {
                // Compare cross² against ε²·len² instead of dividing: one multiply beats a divide
                // and a square root in the innermost loop of the whole vector stage.
                for (i in first + 1 until last) {
                    val cross = (xs[i] - x0) * dy - (ys[i] - y0) * dx
                    val d = cross * cross
                    if (d > bestValue) {
                        bestValue = d
                        bestIndex = i
                    }
                }
                split = bestIndex >= 0 && bestValue > epsSq * lenSq
            }

            if (split) {
                keep[bestIndex] = true
                if (sp + 4 > stack.size) stack = stack.copyOf(stack.size shl 1)
                stack[sp++] = first
                stack[sp++] = bestIndex
                stack[sp++] = bestIndex
                stack[sp++] = last
            }
        }

        val out = ArrayList<VecPoint>(n)
        for (i in 0 until n) if (keep[i]) out.add(points[i])
        return out
    }

    /**
     * Drops points whose perpendicular deviation from the line through the previous *kept* point
     * and the next point is below [tolerance] (default 0.05 px). Endpoints are always kept.
     *
     * Run before [douglasPeucker]: it is a single pass with no stack, and on axis-aligned or
     * pixel-stepped artwork it roughly halves DP's input — pure win, since DP's inner loop is the
     * expensive one.
     */
    fun removeCollinear(points: List<VecPoint>, tolerance: Float = 0.05f): List<VecPoint> {
        val n = points.size
        if (n <= 2 || tolerance <= 0f) return points

        val out = ArrayList<VecPoint>(n)
        var anchor = points[0]
        out.add(anchor)
        val tolSq = tolerance.toDouble() * tolerance.toDouble()

        for (i in 1 until n - 1) {
            val p = points[i]
            val q = points[i + 1]
            val dx = (q.x - anchor.x).toDouble()
            val dy = (q.y - anchor.y).toDouble()
            val lenSq = dx * dx + dy * dy
            val keep: Boolean
            if (lenSq < 1e-12) {
                keep = true          // anchor and the lookahead coincide: no line to measure against
            } else {
                val cross = (p.x - anchor.x) * dy - (p.y - anchor.y) * dx
                keep = cross * cross > tolSq * lenSq
            }
            if (keep) {
                out.add(p)
                anchor = p
            }
        }
        out.add(points[n - 1])
        return out
    }

    /**
     * Indices of points where the path turns sharper than [thresholdDegrees], in **ascending
     * order**.
     *
     * The angle is measured between the chord running back [window] points and the chord running
     * forward [window] points: a straight run gives 180°, a right angle gives 90°, so a point is a
     * corner when its angle is *below* the threshold — equivalently `cos θ > cos(threshold)`, which
     * is how it is evaluated (no `acos`, and the comparison stays exact at the ends of the range).
     *
     * Chords rather than immediate neighbours because at pixel resolution consecutive points are
     * one pixel apart and the only angles that exist are multiples of 45°; every staircase step
     * would read as a corner. The ±k window measures the shape rather than the quantisation — and
     * because that is a statement about *length*, an arm shorter than one working pixel is walked
     * further out (see [arm]), which is what keeps the measuring scale intact when a smoother or a
     * simplifier has left the points closer together than the lattice they came from.
     *
     * Candidates strictly **inside** each other's window are non-maximum suppressed, keeping the
     * sharpest. One physical corner spans several points once the window is wider than a pixel, and
     * emitting all of them splits the path into 1–2 point runs that the Bezier fitter can do nothing
     * with; the radius is `window - 1` rather than `window` for the reason written out at the
     * suppression loop itself.
     *
     * For an open path the endpoints are never reported: they already bound the path. For a closed
     * path indices wrap, and [window] is reduced if it would otherwise reach more than halfway
     * round. Fewer than three points returns an empty array.
     */
    fun detectCorners(
        points: List<VecPoint>,
        thresholdDegrees: Float = 100f,
        window: Int = 3,
        closed: Boolean = false,
    ): IntArray {
        val n = points.size
        if (n < 3) return IntArray(0)

        var w = if (window < 1) 1 else window
        if (closed) {
            // Each arm of the window may span at most a third of the loop.
            //
            // The obvious clamp — `w <= (n-1)/2`, "the arms must not overlap" — is not enough, and the
            // failure is silent. On a 5-point loop it permits w = 2, and on a 5-cycle `i-2` and `i+2`
            // differ by 4 ≡ -1: the two arms land on points that are ADJACENT TO EACH OTHER on the far
            // side of the loop. The angle then measured at `i` is the angle subtended by two
            // neighbours halfway round the shape, which has nothing to do with the corner at `i`.
            //
            // What that cost in practice: a traced square reduces to four corners plus a seam point,
            // and this clamp found ONE of its four corners. The other three were handed to the fitter
            // as smooth interior points, so it ran a continuous curve through them — control points at
            // y = -11 and y = 20 on a shape spanning y = 3..17, and a measured area of 303 against a
            // true 196. Every small closed polygon was affected, which is to say every stencil,
            // silhouette, vinyl cut and laser path the OUTLINE mode exists to produce.
            //
            // A third keeps a clear gap of unclaimed points between the two arms at every size, so the
            // angle is always the local one: n = 3..5 → w = 1 (immediate neighbours, exact for a
            // polygon), n = 6..8 → 2, n ≥ 9 → the requested 3.
            val maxW = n / 3
            if (w > maxW) w = if (maxW < 1) 1 else maxW
        }

        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val p = points[i]
            xs[i] = p.x
            ys[i] = p.y
        }

        val cosThreshold = cos(thresholdDegrees.toDouble() * PI / 180.0)
        // Double, not Float. The cosine is computed in double and narrowing it before the
        // suppression comparison below is a cross-engine divergence with no upside: the TypeScript
        // engine has no float arithmetic, so it compares the double, and two neighbouring candidates
        // whose true scores differ by less than a float ulp then suppress each other differently in
        // the two engines. That is not hypothetical on symmetric input — a ring sampled at equal
        // angles scores every point identically in exact arithmetic and the winner is decided
        // entirely by which bits survived.
        val score = DoubleArray(n) { NOT_A_CORNER }

        val lo = if (closed) 0 else 1
        val hi = if (closed) n - 1 else n - 2
        for (i in lo..hi) {
            val ib = arm(xs, ys, n, i, 1, w, closed, indexAt(i, -w, n, closed))
            val ia = arm(xs, ys, n, i, -1, w, closed, ib)
            // The two arms can only land on the same point once extension is in play, and only on a
            // loop of exactly `3w` points every one of which is under a pixel from `i` — a ring
            // smaller than one pixel. Both chords would then be identical, the dot product exactly 1,
            // and every point on the ring would report as a needle-sharp corner. There is no angle at
            // `i` to measure; skip it.
            if (ia == ib) continue
            val ax = (xs[ia] - xs[i]).toDouble()
            val ay = (ys[ia] - ys[i]).toDouble()
            val bx = (xs[ib] - xs[i]).toDouble()
            val by = (ys[ib] - ys[i]).toDouble()
            val la = sqrt(ax * ax + ay * ay)
            val lb = sqrt(bx * bx + by * by)
            // Coincident points give a zero-length chord and 0/0; they are not corners, they are
            // duplicates, and letting a NaN through here poisons every comparison below.
            if (la < 1e-9 || lb < 1e-9) continue
            val c = (ax * bx + ay * by) / (la * lb)
            if (c > cosThreshold) score[i] = c
        }

        val picked = IntArray(n)
        var m = 0
        // Suppression reaches `w - 1` points, NOT `w`. One physical corner at index c makes exactly
        // the indices `|i - c| < w` report a bend, because those are the points whose two arms
        // straddle it; the point at exactly `c ± w` has one arm endpoint sitting ON the corner and
        // both arms lying along straight runs, so it measures 180° and is never a candidate. The
        // cluster a single corner produces is therefore 2w-1 wide, and a radius of w reaches one
        // point past its own edge — into a DIFFERENT corner.
        //
        // That over-reach is not theoretical. [BezierFit.fitPath] runs this on points a simplifier
        // has already reduced to the shape's vertices, where consecutive indices are a whole side of
        // the polygon apart and every one of them is a real corner. A traced 100×70 rectangle arrives
        // here as five points, so the closed clamp above sets w = 1 and the old radius let each
        // corner suppress both its neighbours: three of the four corners died, the fitter ran one
        // smooth loop through them, and the rectangle came back with an area 48% too large and its
        // corners rounded by 0.74 px. With the radius at w-1 the same rectangle keeps all four
        // corners, its area error falls to 3.5% and the residual rounding to 0.25 px — the rest being
        // the Chaikin pre-pass, not this stage.
        //
        // Nothing changes on a dense pixel-sampled contour, which is the input the window was sized
        // for: there the cluster is 2w-1 wide and a radius of w-1 still collapses it onto its peak.
        for (i in 0 until n) {
            val s = score[i]
            if (s == NOT_A_CORNER) continue
            var best = true
            for (d in -(w - 1)..(w - 1)) {
                if (d == 0) continue
                var j = i + d
                if (closed) {
                    j = ((j % n) + n) % n
                } else if (j < 0 || j >= n) {
                    continue
                }
                val sj = score[j]
                // STRICTLY greater only. A tie must never suppress.
                //
                // The natural-looking tie-break "on equal scores the lower index wins" is a
                // catastrophe on a polygon, because it cascades. Suppression is judged against the raw
                // score array, so on a traced square — where all four vertices are corners, all are
                // adjacent in index space, and all score cos = 0 exactly — vertex 1 is suppressed by
                // 0, vertex 2 by 1, and vertex 3 by 2. One corner out of four survives, the fitter
                // smooths through the other three, and a square comes out as a blob 55% too large.
                //
                // Keeping ties costs, at worst, two anchors where one would do, in the uncommon case
                // that a smooth curve produces exactly equal scores at neighbouring samples: the
                // fitter emits an extra run and the geometry is unchanged. Dropping a real corner
                // changes the shape. Those are not comparable, so this suppression is deliberately
                // the weakest one that still collapses a genuine cluster around a single peak.
                if (sj > s) {
                    best = false
                    break
                }
            }
            if (best) picked[m++] = i
        }
        return picked.copyOf(m)
    }

    /**
     * Splits [points] into runs at each index in [corners].
     *
     * Consecutive runs **share** the corner point: it is the last point of one run and the first of
     * the next. That overlap is not redundancy — it is what makes the independently fitted Beziers
     * meet exactly at the corner instead of leaving a gap the width of the fit error.
     *
     * Corner indices outside `1 .. size-2` are ignored (the ends are already run boundaries),
     * duplicates collapse, and runs shorter than two points are dropped. An empty [corners], or
     * fewer than two points, returns the input as a single run.
     */
    fun splitAtCorners(points: List<VecPoint>, corners: IntArray): List<List<VecPoint>> {
        val n = points.size
        if (n == 0) return emptyList()
        if (n < 2 || corners.isEmpty()) return listOf(points)

        val sorted = corners.copyOf()
        sorted.sort()

        val bounds = IntArray(sorted.size + 2)
        var m = 0
        bounds[m++] = 0
        for (c in sorted) {
            if (c > 0 && c < n - 1 && c != bounds[m - 1]) bounds[m++] = c
        }
        if (bounds[m - 1] != n - 1) bounds[m++] = n - 1

        val out = ArrayList<List<VecPoint>>(m - 1)
        for (i in 0 until m - 1) {
            val a = bounds[i]
            val b = bounds[i + 1]
            if (b <= a) continue
            out.add(ArrayList(points.subList(a, b + 1)))
        }
        return if (out.isEmpty()) listOf(points) else out
    }

    /**
     * Re-points the polyline at a uniform arc-length [spacing], keeping the first and last points.
     * Used to give the Bezier fitter an evenly parameterised input, where chord-length
     * parameterisation is closest to the true arc length and the least-squares fit is best
     * conditioned.
     *
     * [spacing] is coerced to at least 0.01 px so that zero or negative input cannot make the
     * output unbounded. Fewer than two points returns the input unchanged.
     */
    fun resample(points: List<VecPoint>, spacing: Float): List<VecPoint> {
        val n = points.size
        if (n < 2) return points
        val step = (if (spacing < MIN_SPACING) MIN_SPACING else spacing).toDouble()

        val out = ArrayList<VecPoint>(n)
        val first = points[0]
        out.add(first)

        var cx = first.x.toDouble()
        var cy = first.y.toDouble()
        var remaining = step
        var i = 1
        while (i < n) {
            val tx = points[i].x.toDouble()
            val ty = points[i].y.toDouble()
            val dx = tx - cx
            val dy = ty - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d < 1e-12) {
                i++
                continue
            }
            if (d >= remaining) {
                val t = remaining / d
                cx += dx * t
                cy += dy * t
                out.add(VecPoint(cx.toFloat(), cy.toFloat()))
                remaining = step
            } else {
                remaining -= d
                cx = tx
                cy = ty
                i++
            }
        }

        val last = points[n - 1]
        val tail = out[out.size - 1]
        if (tail.x != last.x || tail.y != last.y) out.add(last)
        return out
    }

    /**
     * Index `d` steps from [i], wrapping for a closed run and clamping to the ends for an open one.
     */
    private fun indexAt(i: Int, d: Int, n: Int, closed: Boolean): Int =
        if (closed) (((i + d) % n) + n) % n
        else if (i + d < 0) 0 else if (i + d > n - 1) n - 1 else i + d

    /**
     * Walks one arm out from [i] in direction [dir] until it is at least [MIN_ARM_SQ] long (squared),
     * up to [w] points past the nominal window and never onto [stop] or [i] itself.
     *
     * `window` is a point count but its *purpose* is a length — it exists so the angle describes the
     * shape rather than the lattice the points were sampled on. Those two coincide only while the
     * points are about a pixel apart, which is true of a raw traced contour and false of anything a
     * smoother has been over: Chaikin doubles the point count per iteration, so a ±3-point window that
     * spans 3 px on the contour spans 1.5 px after one pass and 0.75 px after two, and the detector
     * goes back to measuring quantisation. Worse, [douglasPeucker] keeps the first and last point of
     * what is really a ring, so a closed contour whose seam falls on a corner arrives here with two
     * points a third of a pixel apart: both measure 135° against each other, neither is reported, and
     * the fitter runs one curve through a right angle. That cost a traced rectangle 3.5% of its area
     * (0.43% with this walk).
     *
     * A chord under one working pixel is below the resolution of every stage upstream, so it cannot
     * describe a direction the raster was able to represent. Extending past it restores the intended
     * measuring scale, and it is a no-op on a raw contour where every step is already 1 px or 1.41 px.
     */
    private fun arm(
        xs: FloatArray, ys: FloatArray, n: Int,
        i: Int, dir: Int, w: Int, closed: Boolean, stop: Int,
    ): Int {
        var j = indexAt(i, dir * w, n, closed)
        for (e in 1..w) {
            val dx = (xs[j] - xs[i]).toDouble()
            val dy = (ys[j] - ys[i]).toDouble()
            if (dx * dx + dy * dy >= MIN_ARM_SQ) break
            val next = indexAt(i, dir * (w + e), n, closed)
            if (next == stop || next == i || next == j) break
            j = next
        }
        return j
    }

    /**
     * Shortest chord [detectCorners] will measure an angle against, squared, in working pixels.
     *
     * One pixel, because that is the lattice every stage upstream samples on: a chord shorter than one
     * pixel is below the resolution of the data and the direction it reports is quantisation, not
     * shape.
     */
    private const val MIN_ARM_SQ = 1.0

    /** Below any legal cosine, so it doubles as "no candidate here" without a second array. */
    private const val NOT_A_CORNER = -2.0

    private const val MIN_SPACING = 0.01f
}
