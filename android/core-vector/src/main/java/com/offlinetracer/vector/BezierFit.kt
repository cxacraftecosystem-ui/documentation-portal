package com.offlinetracer.vector

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cubic Bézier fitting — Philip J. Schneider's least-squares algorithm (*Graphics Gems*, 1990).
 *
 * Schneider rather than Potrace's fitter: Potrace is GPL and porting it would relicense this whole
 * codebase. Schneider's is public domain and reaches the same visual class here, because corners are
 * split out *before* fitting (see [fitPath]) — the fitter is never asked to approximate a corner,
 * which is the only place a least-squares fit visibly loses to Potrace's corner-aware pass.
 *
 * **`error` is a squared tolerance**, exactly as in the original: `computeMaxError` reports the
 * squared deviation and it is compared directly against `error`. `error = 1.6` therefore means "no
 * sample deviates by more than sqrt(1.6) ≈ 1.26 px". The convention is kept rather than "fixed"
 * because the Kotlin and TypeScript engines must produce the same segment count for the same input,
 * and a silently different tolerance is the hardest kind of parity failure to find.
 *
 * The recursion of the published algorithm is replaced by an explicit task stack: a contour from a
 * 12 MP photo can carry 200 000 points and the recursive form overflows the JVM stack, where the
 * crash presents as a random OOM rather than as the stack overflow it is.
 */
object BezierFit {

    /** Points closer together than this are treated as one; a zero-length chord has no tangent. */
    private const val DUP_EPS = 1e-7

    /** Newton-Raphson passes per fit attempt, as published. More does not converge better here. */
    private const val NEWTON_ITERATIONS = 4


    /**
     * Upper bound on a tangent magnitude relative to the chord length. Past this the least-squares
     * solution is not a curve any more, it is a control point in another postcode; see the fallback
     * note in [generateBezier].
     */
    private const val MAX_ALPHA_RATIO = 10.0

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    /**
     * Fits a chain of cubic Béziers through [points], in order, starting at `points.first()`.
     *
     * The returned segments are relative to that start point exactly as [VecPath.segments] are: the
     * first cubic begins at `points.first()` and each subsequent one begins where the previous
     * ended. Returns an empty list for fewer than two distinct points.
     *
     * @param error squared deviation tolerance (see the class KDoc).
     * @param maxDepth split-recursion cap; at the cap the range is emitted as a polyline rather than
     *   splitting further, so a pathological input degrades in quality instead of hanging.
     */
    fun fit(points: List<VecPoint>, error: Float, maxDepth: Int = 24): List<VecSeg.Cubic> =
        fitInternal(points, error, maxDepth, null)

    /**
     * Fits [points] into a complete [VecPath], splitting the run at every corner reported by
     * [Simplify.detectCorners] and fitting each run independently.
     *
     * That split is *how* corners are preserved: a least-squares cubic through a 90° turn rounds it
     * off, so the turn is made an endpoint of two separate fits instead, where the tangent is the
     * chord and the corner survives exactly.
     *
     * For [closed] input the loop is rotated to begin at the first corner so the seam falls on a
     * corner rather than in the middle of a smooth arc; a closed loop with no corners at all is
     * fitted with a single averaged tangent across the seam, which is what stops a traced circle
     * showing a kink at 3 o'clock.
     *
     * Returns a path with no segments (but a valid start point) for degenerate input.
     */
    fun fitPath(
        points: List<VecPoint>,
        error: Float,
        closed: Boolean,
        cornerThresholdDegrees: Float = 100f,
    ): VecPath {
        val clean = dedupe(points, closed)
        if (clean.isEmpty()) return VecPath(VecPoint(0f, 0f), emptyList(), closed)
        if (clean.size == 1) return VecPath(clean[0], emptyList(), closed)
        if (clean.size == 2) {
            // Two distinct points cannot be closed and cannot have a corner.
            return VecPath(clean[0], fit(clean, error), false)
        }

        val n = clean.size
        val corners = normaliseCorners(Simplify.detectCorners(clean, cornerThresholdDegrees, 3, closed), n, closed)

        if (!closed) {
            val segs = ArrayList<VecSeg>(n)
            var from = 0
            for (c in corners) {
                if (c <= from || c >= n - 1) continue
                segs.addAll(fit(clean.subList(from, c + 1), error))
                from = c
            }
            segs.addAll(fit(clean.subList(from, n), error))
            return VecPath(clean[0], segs, false)
        }

        if (corners.isEmpty()) {
            // No corner anywhere: fit the whole loop as one run with a tangent that is continuous
            // across the seam, so the start point is not silently promoted to a corner.
            val run = ArrayList<VecPoint>(n + 1)
            run.addAll(clean)
            run.add(clean[0])
            val t = seamTangent(clean)
            val segs = fitInternal(run, error, 24, doubleArrayOf(t[0], t[1], -t[0], -t[1]))
            return VecPath(clean[0], segs, true)
        }

        val pivot = corners[0]
        val rotated = ArrayList<VecPoint>(n + 1)
        for (i in 0 until n) rotated.add(clean[(pivot + i) % n])
        rotated.add(clean[pivot])

        val segs = ArrayList<VecSeg>(n)
        var from = 0
        for (k in 1 until corners.size) {
            val c = corners[k] - pivot
            if (c <= from || c >= n) continue
            segs.addAll(fit(rotated.subList(from, c + 1), error))
            from = c
        }
        segs.addAll(fit(rotated.subList(from, n + 1), error))
        return VecPath(rotated[0], segs, true)
    }

    // ---------------------------------------------------------------------------------------
    // Fitting core
    // ---------------------------------------------------------------------------------------

    /** Deduped point storage; the fitter works on primitive arrays so no per-point boxing occurs. */
    private class Poly(@JvmField val xs: DoubleArray, @JvmField val ys: DoubleArray, @JvmField val n: Int)

    /** One pending sub-range plus the tangents it must honour. Replaces the published recursion. */
    private class Task(
        @JvmField val first: Int,
        @JvmField val last: Int,
        @JvmField val t1x: Double,
        @JvmField val t1y: Double,
        @JvmField val t2x: Double,
        @JvmField val t2y: Double,
        @JvmField val depth: Int,
    )

    private fun fitInternal(
        points: List<VecPoint>,
        error: Float,
        maxDepth: Int,
        overrideTangents: DoubleArray?,
    ): List<VecSeg.Cubic> {
        val poly = toPoly(points)
        val n = poly.n
        if (n < 2) return emptyList()
        val xs = poly.xs
        val ys = poly.ys

        var t1x: Double
        var t1y: Double
        var t2x: Double
        var t2y: Double
        if (overrideTangents != null && overrideTangents.size >= 4) {
            t1x = overrideTangents[0]; t1y = overrideTangents[1]
            t2x = overrideTangents[2]; t2y = overrideTangents[3]
        } else {
            val tan = DoubleArray(4)
            val third = n > 2
            endpointTangent(
                xs[0], ys[0], xs[1], ys[1],
                if (third) xs[2] else 0.0, if (third) ys[2] else 0.0, third, tan, 0,
            )
            endpointTangent(
                xs[n - 1], ys[n - 1], xs[n - 2], ys[n - 2],
                if (third) xs[n - 3] else 0.0, if (third) ys[n - 3] else 0.0, third, tan, 2,
            )
            t1x = tan[0]; t1y = tan[1]
            t2x = tan[2]; t2y = tan[3]
        }

        // A tolerance of exactly zero would make every comparison false and drive the fit straight
        // to the depth cap; a tiny positive floor degrades to "one cubic per chord" instead.
        val err = if (error > 0f && error.isFinite()) error.toDouble() else 1e-9
        val cap = if (maxDepth < 0) 0 else if (maxDepth > 64) 64 else maxDepth

        val out = ArrayList<VecSeg.Cubic>(16)
        val stack = ArrayList<Task>(16)
        stack.add(Task(0, n - 1, t1x, t1y, t2x, t2y, 0))
        val splitOut = IntArray(1)

        while (stack.isNotEmpty()) {
            val task = stack.removeAt(stack.size - 1)
            val first = task.first
            val last = task.last
            val count = last - first + 1

            if (count < 2) continue

            if (count == 2) {
                val dist = distance(xs[first], ys[first], xs[last], ys[last]) / 3.0
                emit(
                    out,
                    xs[first] + task.t1x * dist, ys[first] + task.t1y * dist,
                    xs[last] + task.t2x * dist, ys[last] + task.t2y * dist,
                    xs[last], ys[last],
                )
                continue
            }

            val u = chordLengthParameterize(xs, ys, first, last)
            var bez = generateBezier(xs, ys, first, last, u, task.t1x, task.t1y, task.t2x, task.t2y)
            var maxErr = computeMaxError(xs, ys, first, last, bez, u, splitOut)

            if (maxErr < err) {
                emit(out, bez[2], bez[3], bez[4], bez[5], bez[6], bez[7])
                continue
            }

            // Reparameterise before considering a split — ALWAYS, not behind a gate.
            //
            // Graphics Gems guards this with `maxError < error * error`, and that guard has to go for
            // two independent reasons.
            //
            // First, it inverts its own meaning below 1. `error` here is a SQUARED deviation and
            // callers legitimately pass values well under 1, so `error * error` is SMALLER than
            // `error`; the branch then implies `maxErr < err`, which the acceptance test immediately
            // above has already returned on. Refinement was unreachable dead code.
            //
            // Second, and the reason no gate belongs here at all: `computeMaxError` measures
            // |sample[i] - B(u[i])| with `u` from chord-length parameterisation, and arc length is
            // not the Bernstein parameter. For a curve of any real curvature the two disagree
            // materially, so a cubic that is GEOMETRICALLY almost exact still reports a large error
            // on its first attempt — the residual is the parameter mismatch, not the shape. Measured
            // on 61 samples of a single cubic of extent 100: the first attempt recovers the control
            // point to (20.04, 60.02) against a true (20, 60), and still reports a squared error
            // between 1.0 and 2.56. Any threshold small enough to be a meaningful tolerance is
            // therefore far below the first-attempt error of a perfect fit, and gating on it
            // guarantees a split that was never needed. That is exactly the observed symptom: 17
            // segments for a curve that one segment reproduces.
            //
            // Newton refinement is what converts that parametric residual into the geometric distance
            // the caller asked about, by walking each u[i] to its foot point. It is four passes and
            // it replaces a subdivision that would have cost two whole recursive ranges, so removing
            // the gate makes the fitter cheaper as well as better.
            var previousErr = maxErr
            for (i in 0 until NEWTON_ITERATIONS) {
                reparameterize(xs, ys, first, last, u, bez)
                bez = generateBezier(xs, ys, first, last, u, task.t1x, task.t1y, task.t2x, task.t2y)
                maxErr = computeMaxError(xs, ys, first, last, bez, u, splitOut)
                if (maxErr < err) break
                // Newton is not guaranteed to descend on badly conditioned data. Once it stops
                // improving it will not recover within the iteration budget, and continuing only
                // degrades the split point that `computeMaxError` reports for the fallback below.
                if (maxErr >= previousErr) break
                previousErr = maxErr
            }
            if (maxErr < err) {
                emit(out, bez[2], bez[3], bez[4], bez[5], bez[6], bez[7])
                continue
            }

            if (task.depth >= cap) {
                // The cap exists so a pathological input loses quality instead of the process.
                for (i in first until last) {
                    val ax = xs[i]; val ay = ys[i]
                    val bx = xs[i + 1]; val by = ys[i + 1]
                    emit(
                        out,
                        ax + (bx - ax) / 3.0, ay + (by - ay) / 3.0,
                        ax + (bx - ax) * 2.0 / 3.0, ay + (by - ay) * 2.0 / 3.0,
                        bx, by,
                    )
                }
                continue
            }

            var split = splitOut[0]
            if (split <= first) split = first + 1
            if (split >= last) split = last - 1

            var cx = xs[split - 1] - xs[split + 1]
            var cy = ys[split - 1] - ys[split + 1]
            var clen = sqrt(cx * cx + cy * cy)
            if (clen < DUP_EPS) {
                cx = xs[split] - xs[split - 1]
                cy = ys[split] - ys[split - 1]
                clen = sqrt(cx * cx + cy * cy)
                if (clen < DUP_EPS) { cx = 1.0; cy = 0.0; clen = 1.0 }
            }
            cx /= clen; cy /= clen

            // Push right first: the stack is LIFO, so the left half is processed — and therefore
            // emitted — before the right one, which is what keeps the segment list in path order.
            stack.add(Task(split, last, -cx, -cy, task.t2x, task.t2y, task.depth + 1))
            stack.add(Task(first, split, task.t1x, task.t1y, cx, cy, task.depth + 1))
        }
        return out
    }

    /**
     * Solves the 2×2 normal equations for the two tangent magnitudes and returns the control polygon
     * as `[x0, y0, c1x, c1y, c2x, c2y, x3, y3]`.
     *
     * The fallback is not optional. When the system is near-singular, or either magnitude comes back
     * negative or absurdly large, the Wu/Barsky heuristic `α = ‖p0 − p3‖ / 3` is used instead.
     * Without it the solve occasionally returns control points thousands of units away and the path
     * visibly shoots off screen — a spectacular and very recognisable bug.
     */
    private fun generateBezier(
        xs: DoubleArray, ys: DoubleArray, first: Int, last: Int, u: DoubleArray,
        t1x: Double, t1y: Double, t2x: Double, t2y: Double,
    ): DoubleArray {
        val count = last - first + 1
        val px0 = xs[first]; val py0 = ys[first]
        val px3 = xs[last]; val py3 = ys[last]

        var c00 = 0.0; var c01 = 0.0; var c11 = 0.0
        var xr0 = 0.0; var xr1 = 0.0

        for (i in 0 until count) {
            val ui = u[i]
            val omu = 1.0 - ui
            val b0 = omu * omu * omu
            val b1 = 3.0 * ui * omu * omu
            val b2 = 3.0 * ui * ui * omu
            val b3 = ui * ui * ui

            val a0x = t1x * b1; val a0y = t1y * b1
            val a1x = t2x * b2; val a1y = t2y * b2

            c00 += a0x * a0x + a0y * a0y
            c01 += a0x * a1x + a0y * a1y
            c11 += a1x * a1x + a1y * a1y

            val tmpx = xs[first + i] - (px0 * (b0 + b1) + px3 * (b2 + b3))
            val tmpy = ys[first + i] - (py0 * (b0 + b1) + py3 * (b2 + b3))
            xr0 += a0x * tmpx + a0y * tmpy
            xr1 += a1x * tmpx + a1y * tmpy
        }

        val det = c00 * c11 - c01 * c01
        var alphaL: Double
        var alphaR: Double
        if (abs(det) > 1e-12 * (1.0 + abs(c00 * c11) + abs(c01 * c01))) {
            alphaL = (xr0 * c11 - xr1 * c01) / det
            alphaR = (c00 * xr1 - c01 * xr0) / det
        } else {
            alphaL = Double.NaN
            alphaR = Double.NaN
        }

        // Reference length for the sanity bounds. It is the chord normally, but a closed run has
        // p0 == p3 and a zero chord would make every bound zero and force the fallback to collapse
        // all four control points onto one point.
        var refLen = distance(px0, py0, px3, py3)
        if (refLen < DUP_EPS) {
            var acc = 0.0
            for (i in first until last) acc += distance(xs[i], ys[i], xs[i + 1], ys[i + 1])
            refLen = if (acc > DUP_EPS) acc else 1.0
        }

        val minAlpha = 1e-6 * refLen
        val maxAlpha = MAX_ALPHA_RATIO * refLen
        if (!alphaL.isFinite() || !alphaR.isFinite() ||
            alphaL < minAlpha || alphaR < minAlpha ||
            alphaL > maxAlpha || alphaR > maxAlpha
        ) {
            val d = refLen / 3.0
            alphaL = d
            alphaR = d
        }

        return doubleArrayOf(
            px0, py0,
            px0 + t1x * alphaL, py0 + t1y * alphaL,
            px3 + t2x * alphaR, py3 + t2y * alphaR,
            px3, py3,
        )
    }

    /** Normalised cumulative chord length over `[first, last]`; uniform when the run has no length. */
    private fun chordLengthParameterize(xs: DoubleArray, ys: DoubleArray, first: Int, last: Int): DoubleArray {
        val count = last - first + 1
        val u = DoubleArray(count)
        for (i in 1 until count) {
            u[i] = u[i - 1] + distance(xs[first + i - 1], ys[first + i - 1], xs[first + i], ys[first + i])
        }
        val total = u[count - 1]
        if (total < DUP_EPS) {
            for (i in 0 until count) u[i] = i.toDouble() / (count - 1).toDouble()
        } else {
            for (i in 1 until count) u[i] /= total
            u[count - 1] = 1.0
        }
        return u
    }

    /**
     * Inward-pointing unit tangent at an endpoint, written to `out[offset], out[offset+1]`.
     *
     * Uses the **second-order** one-sided difference `(-3p0 + 4p1 - p2) / 2` when a third point is
     * available, falling back to the plain chord `p1 - p0` otherwise.
     *
     * Graphics Gems uses that plain chord, which is only first-order accurate in the sample spacing,
     * and the direction is never corrected afterwards: reparameterisation refines the parameter
     * values `u`, not the tangent directions, so the least-squares solve can only absorb a bad
     * direction by stretching the tangent MAGNITUDE — which moves the control point along the wrong
     * ray. On smooth input that is what forces the fitter to subdivide a curve it could otherwise
     * reproduce nearly exactly. Measured on 61 samples taken from a single cubic of extent 100 with
     * a 1e-4 squared tolerance: the chord estimate needed 17 segments, this one needs 1.
     *
     * The three-point form carries larger coefficients and so amplifies noise, which matters on the
     * pixel-staircase point sets that come out of contour tracing. It is therefore sanity-checked
     * against the chord and discarded whenever the two disagree by more than 90°, which is precisely
     * the case where the extra term is reacting to a staircase step rather than to curvature.
     */
    private fun endpointTangent(
        x0: Double, y0: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        hasThird: Boolean,
        out: DoubleArray,
        offset: Int,
    ) {
        var cx = x1 - x0
        var cy = y1 - y0
        var clen = sqrt(cx * cx + cy * cy)
        // A zero-length chord has no direction at all; any unit vector is as defensible as another
        // and an arbitrary one keeps the fit finite instead of producing NaN control points.
        if (clen < DUP_EPS) { cx = 1.0; cy = 0.0; clen = 1.0 }
        cx /= clen
        cy /= clen

        if (hasThird) {
            val hx = (-3.0 * x0 + 4.0 * x1 - x2) * 0.5
            val hy = (-3.0 * y0 + 4.0 * y1 - y2) * 0.5
            val hlen = sqrt(hx * hx + hy * hy)
            if (hlen >= DUP_EPS) {
                val ux = hx / hlen
                val uy = hy / hlen
                if (ux * cx + uy * cy > 0.0) {
                    out[offset] = ux
                    out[offset + 1] = uy
                    return
                }
            }
        }
        out[offset] = cx
        out[offset + 1] = cy
    }

    /**
     * One Newton-Raphson step per sample, in place. Endpoints are pinned to 0 and 1 because the
     * closed-form solve assumes them; letting them drift makes the normal equations inconsistent and
     * the fit gets worse with every "refinement" pass.
     */
    private fun reparameterize(
        xs: DoubleArray, ys: DoubleArray, first: Int, last: Int, u: DoubleArray, bez: DoubleArray,
    ) {
        val count = last - first + 1
        for (i in 0 until count) {
            u[i] = newtonStep(bez, xs[first + i], ys[first + i], u[i])
        }
        u[0] = 0.0
        u[count - 1] = 1.0
    }

    private fun newtonStep(b: DoubleArray, px: Double, py: Double, u: Double): Double {
        val omu = 1.0 - u
        val b0 = omu * omu * omu
        val b1 = 3.0 * u * omu * omu
        val b2 = 3.0 * u * u * omu
        val b3 = u * u * u
        val qx = b[0] * b0 + b[2] * b1 + b[4] * b2 + b[6] * b3
        val qy = b[1] * b0 + b[3] * b1 + b[5] * b2 + b[7] * b3

        // First derivative control points (degree 2) and second derivative (degree 1).
        val d0x = 3.0 * (b[2] - b[0]); val d0y = 3.0 * (b[3] - b[1])
        val d1x = 3.0 * (b[4] - b[2]); val d1y = 3.0 * (b[5] - b[3])
        val d2x = 3.0 * (b[6] - b[4]); val d2y = 3.0 * (b[7] - b[5])
        val e0x = 2.0 * (d1x - d0x); val e0y = 2.0 * (d1y - d0y)
        val e1x = 2.0 * (d2x - d1x); val e1y = 2.0 * (d2y - d1y)

        val q1x = d0x * omu * omu + d1x * 2.0 * u * omu + d2x * u * u
        val q1y = d0y * omu * omu + d1y * 2.0 * u * omu + d2y * u * u
        val q2x = e0x * omu + e1x * u
        val q2y = e0y * omu + e1y * u

        val dx = qx - px
        val dy = qy - py
        val numerator = dx * q1x + dy * q1y
        val denominator = q1x * q1x + q1y * q1y + dx * q2x + dy * q2y
        if (abs(denominator) < 1e-12) return u
        val next = u - numerator / denominator
        if (!next.isFinite()) return u
        return if (next < 0.0) 0.0 else if (next > 1.0) 1.0 else next
    }

    /**
     * Largest **squared** deviation of a sample from the curve. Writes the index of the worst sample
     * to `splitOut[0]`, pre-seeded with the midpoint so the caller always has a legal split even when
     * every deviation is identical.
     */
    private fun computeMaxError(
        xs: DoubleArray, ys: DoubleArray, first: Int, last: Int, b: DoubleArray, u: DoubleArray,
        splitOut: IntArray,
    ): Double {
        var maxDist = 0.0
        splitOut[0] = (first + last) / 2
        for (i in first + 1 until last) {
            val ui = u[i - first]
            val omu = 1.0 - ui
            val b0 = omu * omu * omu
            val b1 = 3.0 * ui * omu * omu
            val b2 = 3.0 * ui * ui * omu
            val b3 = ui * ui * ui
            val qx = b[0] * b0 + b[2] * b1 + b[4] * b2 + b[6] * b3
            val qy = b[1] * b0 + b[3] * b1 + b[5] * b2 + b[7] * b3
            val dx = qx - xs[i]
            val dy = qy - ys[i]
            val d = dx * dx + dy * dy
            if (d > maxDist) {
                maxDist = d
                splitOut[0] = i
            }
        }
        return maxDist
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun emit(
        out: MutableList<VecSeg.Cubic>,
        c1x: Double, c1y: Double, c2x: Double, c2y: Double, ex: Double, ey: Double,
    ) {
        out.add(
            VecSeg.Cubic(
                VecPoint(c1x.toFloat(), c1y.toFloat()),
                VecPoint(c2x.toFloat(), c2y.toFloat()),
                VecPoint(ex.toFloat(), ey.toFloat()),
            )
        )
    }

    private fun distance(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }

    private fun toPoly(points: List<VecPoint>): Poly {
        val cap = points.size
        val xs = DoubleArray(if (cap > 0) cap else 1)
        val ys = DoubleArray(if (cap > 0) cap else 1)
        var m = 0
        for (i in 0 until cap) {
            val p = points[i]
            val x = p.x.toDouble()
            val y = p.y.toDouble()
            if (!x.isFinite() || !y.isFinite()) continue
            if (m > 0 && abs(x - xs[m - 1]) <= DUP_EPS && abs(y - ys[m - 1]) <= DUP_EPS) continue
            xs[m] = x
            ys[m] = y
            m++
        }
        return Poly(xs, ys, m)
    }

    /** Drops non-finite and repeated points; for a closed run also drops a trailing copy of the start. */
    private fun dedupe(points: List<VecPoint>, closed: Boolean): List<VecPoint> {
        val out = ArrayList<VecPoint>(points.size)
        for (p in points) {
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            if (out.isNotEmpty()) {
                val q = out[out.size - 1]
                if (abs(p.x - q.x) <= 1e-6f && abs(p.y - q.y) <= 1e-6f) continue
            }
            out.add(p)
        }
        if (closed && out.size > 1) {
            val a = out[0]
            val b = out[out.size - 1]
            if (abs(a.x - b.x) <= 1e-6f && abs(a.y - b.y) <= 1e-6f) out.removeAt(out.size - 1)
        }
        return out
    }

    /** Sorted, de-duplicated, in-range corner indices. Index 0 and n-1 are endpoints, not corners. */
    private fun normaliseCorners(corners: IntArray, n: Int, closed: Boolean): IntArray {
        if (corners.isEmpty()) return corners
        val sorted = corners.copyOf()
        sorted.sort()
        val out = IntArray(sorted.size)
        var m = 0
        val lo = if (closed) 0 else 1
        val hi = if (closed) n - 1 else n - 2
        for (c in sorted) {
            if (c < lo || c > hi) continue
            if (m > 0 && out[m - 1] == c) continue
            out[m] = c
            m++
        }
        return if (m == sorted.size) out else out.copyOf(m)
    }

    /** Unit tangent across the seam of a closed loop: the mean of the incoming and outgoing chords. */
    private fun seamTangent(pts: List<VecPoint>): DoubleArray {
        val n = pts.size
        var ax = (pts[0].x - pts[n - 1].x).toDouble()
        var ay = (pts[0].y - pts[n - 1].y).toDouble()
        var la = sqrt(ax * ax + ay * ay)
        if (la < DUP_EPS) { ax = 1.0; ay = 0.0; la = 1.0 }
        var bx = (pts[1].x - pts[0].x).toDouble()
        var by = (pts[1].y - pts[0].y).toDouble()
        var lb = sqrt(bx * bx + by * by)
        if (lb < DUP_EPS) { bx = 1.0; by = 0.0; lb = 1.0 }
        var tx = ax / la + bx / lb
        var ty = ay / la + by / lb
        var lt = sqrt(tx * tx + ty * ty)
        if (lt < DUP_EPS) { tx = bx / lb; ty = by / lb; lt = 1.0 }
        return doubleArrayOf(tx / lt, ty / lt)
    }
}
