package com.offlinetracer.imaging

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rigid and projective geometry: orientation fixes, perspective correction and the document-corner
 * detector that drives it.
 *
 * Two conventions hold throughout:
 *  - A pixel's coordinate is its **centre at integer (x, y)**, matching `GrayF.sampleBilinear`.
 *  - Positive rotation angles turn the image **clockwise on screen**, because y runs down. This is
 *    the same direction as `rotate90(+1)`, and disagreeing with it would make "rotate right" in the
 *    UI mean two different things depending on which function ran.
 */
object Geometry {

    /**
     * Rotates by [quarterTurns] × 90° clockwise. Negative and out-of-range values wrap. Odd turn
     * counts swap the dimensions. This is exact — no resampling — which is why every 90° case is
     * routed here instead of through [rotate].
     */
    fun rotate90(src: RgbaImage, quarterTurns: Int): RgbaImage {
        val q = ((quarterTurns % 4) + 4) % 4
        if (q == 0) return src.copy()
        val w = src.width
        val h = src.height
        val sp = src.pixels
        return when (q) {
            1 -> {
                val out = RgbaImage(h, w)
                val op = out.pixels
                for (y in 0 until w) {
                    val dstRow = y * h
                    for (x in 0 until h) op[dstRow + x] = sp[(h - 1 - x) * w + y]
                }
                out
            }
            2 -> {
                val out = RgbaImage(w, h)
                val op = out.pixels
                for (y in 0 until h) {
                    val dstRow = y * w
                    val srcRow = (h - 1 - y) * w
                    for (x in 0 until w) op[dstRow + x] = sp[srcRow + (w - 1 - x)]
                }
                out
            }
            else -> {
                val out = RgbaImage(h, w)
                val op = out.pixels
                for (y in 0 until w) {
                    val dstRow = y * h
                    for (x in 0 until h) op[dstRow + x] = sp[x * w + (w - 1 - y)]
                }
                out
            }
        }
    }

    /** Mirrors the image. Both flags together are the same as a 180° turn. Neither is a copy. */
    fun flip(src: RgbaImage, horizontal: Boolean, vertical: Boolean): RgbaImage {
        val w = src.width
        val h = src.height
        val out = RgbaImage(w, h)
        val sp = src.pixels
        val op = out.pixels
        for (y in 0 until h) {
            val sy = if (vertical) h - 1 - y else y
            val srcRow = sy * w
            val dstRow = y * w
            if (horizontal) {
                for (x in 0 until w) op[dstRow + x] = sp[srcRow + (w - 1 - x)]
            } else {
                System.arraycopy(sp, srcRow, op, dstRow, w)
            }
        }
        return out
    }

    /**
     * Solves the homography mapping the four points of [srcQuad] (8 floats, x,y interleaved) onto
     * [dstQuad], returned as 9 row-major coefficients with `h[8] = 1`.
     *
     * The 8×8 linear system is solved by Gauss–Jordan elimination with partial pivoting. A
     * degenerate quad (three collinear corners, a zero-area quad) makes the system singular; rather
     * than returning a matrix full of NaN that poisons every pixel downstream, the identity
     * homography is returned. Callers get an untransformed image, which is visibly wrong and
     * therefore fixable, instead of a blank one.
     */
    fun solveHomography(srcQuad: FloatArray, dstQuad: FloatArray): DoubleArray {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        if (srcQuad.size < 8 || dstQuad.size < 8) return identity

        val m = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = srcQuad[i * 2].toDouble()
            val y = srcQuad[i * 2 + 1].toDouble()
            val u = dstQuad[i * 2].toDouble()
            val v = dstQuad[i * 2 + 1].toDouble()
            val r0 = m[i * 2]
            r0[0] = x; r0[1] = y; r0[2] = 1.0
            r0[3] = 0.0; r0[4] = 0.0; r0[5] = 0.0
            r0[6] = -u * x; r0[7] = -u * y; r0[8] = u
            val r1 = m[i * 2 + 1]
            r1[0] = 0.0; r1[1] = 0.0; r1[2] = 0.0
            r1[3] = x; r1[4] = y; r1[5] = 1.0
            r1[6] = -v * x; r1[7] = -v * y; r1[8] = v
        }

        for (col in 0 until 8) {
            var pivot = col
            var best = abs(m[col][col])
            for (r in col + 1 until 8) {
                val v = abs(m[r][col])
                if (v > best) {
                    best = v
                    pivot = r
                }
            }
            if (best < 1e-12) return identity
            if (pivot != col) {
                val tmp = m[pivot]
                m[pivot] = m[col]
                m[col] = tmp
            }
            val prow = m[col]
            val inv = 1.0 / prow[col]
            for (c in col until 9) prow[c] *= inv
            for (r in 0 until 8) {
                if (r == col) continue
                val row = m[r]
                val f = row[col]
                if (f == 0.0) continue
                for (c in col until 9) row[c] -= f * prow[c]
            }
        }

        val out = DoubleArray(9)
        for (i in 0 until 8) out[i] = m[i][8]
        out[8] = 1.0
        return out
    }

    /**
     * Warps [src] through the homography [h] (source → destination, as produced by
     * [solveHomography]) into an [outW]×[outH] image.
     *
     * The loop runs over **destination** pixels and samples the source through `h⁻¹`. Forward
     * mapping is the classic mistake here: it scatters source pixels into the destination and
     * leaves a lattice of unwritten holes wherever the transform expands. Destination pixels whose
     * source falls outside the image are left fully transparent.
     */
    fun warpPerspective(src: RgbaImage, h: DoubleArray, outW: Int, outH: Int): RgbaImage {
        val w = if (outW < 1) 1 else outW
        val hh = if (outH < 1) 1 else outH
        val out = RgbaImage(w, hh)
        if (h.size < 9) return out
        val inv = invert3x3(h) ?: return out

        val i0 = inv[0]; val i1 = inv[1]; val i2 = inv[2]
        val i3 = inv[3]; val i4 = inv[4]; val i5 = inv[5]
        val i6 = inv[6]; val i7 = inv[7]; val i8 = inv[8]
        val op = out.pixels
        Parallel.rows(hh) { from, to ->
            for (y in from until to) {
                val dy = y.toDouble()
                val row = y * w
                var nx = i1 * dy + i2
                var ny = i4 * dy + i5
                var nw = i7 * dy + i8
                for (x in 0 until w) {
                    if (abs(nw) > 1e-12) {
                        val invW = 1.0 / nw
                        op[row + x] = sampleArgb(src, (nx * invW).toFloat(), (ny * invW).toFloat())
                    }
                    // Incremental step along the row: the projective map is affine in x, so one add
                    // per coefficient replaces three multiplies per pixel.
                    nx += i0
                    ny += i3
                    nw += i6
                }
            }
        }
        return out
    }

    /**
     * Finds the four corners of a document-like quadrilateral in [src], returned as 8 floats
     * (x,y interleaved, ordered TL,TR,BR,BL), or `null` when there is no confident candidate.
     *
     * Canny → largest edge component → convex hull → polygon approximation, accepting only a
     * 4-vertex result that covers more than 15% of the frame. Both rejections matter: a 3- or
     * 5-vertex hull means the page border was not closed, and a small quad is almost always a
     * picture frame, a sticker or a shadow rather than the page.
     */
    fun detectDocumentQuad(src: GrayF): FloatArray? {
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return null

        // Canny's median-based auto thresholds collapse to zero on a mostly-flat frame (the median
        // gradient of a page on a plain background is 0), which marks every pixel as an edge. Otsu
        // over the actual magnitude range does not have that failure mode.
        val blurred = Convolve.gaussianBlur(src, 1.4f)
        val magnitude = Convolve.gradients(blurred).magnitude()
        val range = magnitude.range()
        if (range.second - range.first <= 1e-6f) return null
        val t = Threshold.otsu(Contrast.stretch(magnitude))
        val high = range.first + t * (range.second - range.first)
        val edges = EdgeCanny.detect(src, 1.4f, high * 0.4f, high)

        // A page border broken by a highlight or a low-contrast corner otherwise splits into two
        // components and the hull covers only half the page.
        val joined = Morphology.close(edges, 2)
        val labels = Components.label(joined, 8)
        if (labels.count < 1) return null
        var bestLabel = 0
        var bestArea = 0
        for (l in 1..labels.count) {
            val a = labels.areaOf(l)
            if (a > bestArea) {
                bestArea = a
                bestLabel = l
            }
        }
        if (bestLabel == 0) return null

        val hull = convexHullOf(labels.maskOf(bestLabel)) ?: return null
        val perimeter = polygonPerimeter(hull)
        if (perimeter <= 0f) return null
        val frameArea = w.toFloat() * h.toFloat()

        // Deterministic epsilon sweep from fine to coarse; the first approximation that lands on
        // exactly four vertices wins. A fixed epsilon works on one image and not the next.
        var step = 0
        while (step < 20) {
            val eps = (0.010f + 0.005f * step) * perimeter
            val approx = approxPolyClosed(hull, eps)
            val vertices = approx.size / 2
            if (vertices == 4) {
                if (abs(polygonArea(approx)) > 0.15f * frameArea) return orderQuad(approx)
                return null
            }
            if (vertices < 4) return null
            step++
        }
        return null
    }

    /**
     * Reorders four corner points (8 floats) into TL, TR, BR, BL. Input in any rotation or winding
     * is accepted; arrays that are not exactly 8 long are returned unchanged.
     *
     * Sorting by angle about the centroid rather than by the usual `min(x+y)` / `max(x-y)` corner
     * rules: those rules pick the same point twice on a strongly rotated or sheared quad, and the
     * resulting duplicate corner produces a singular homography.
     */
    fun orderQuad(quad: FloatArray): FloatArray {
        if (quad.size != 8) return quad.copyOf()
        var cx = 0f
        var cy = 0f
        for (i in 0 until 4) {
            cx += quad[i * 2]
            cy += quad[i * 2 + 1]
        }
        cx *= 0.25f
        cy *= 0.25f

        val angle = FloatArray(4)
        for (i in 0 until 4) angle[i] = atan2(quad[i * 2 + 1] - cy, quad[i * 2] - cx)
        val order = intArrayOf(0, 1, 2, 3)
        for (i in 1 until 4) {
            val key = order[i]
            val a = angle[key]
            var j = i - 1
            while (j >= 0 && angle[order[j]] > a) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = key
        }

        // With y down, ascending atan2 walks the quad clockwise, so starting the cycle at the
        // top-left corner yields TL, TR, BR, BL directly.
        var startPos = 0
        var best = Float.MAX_VALUE
        for (i in 0 until 4) {
            val p = order[i]
            val s = quad[p * 2] + quad[p * 2 + 1]
            if (s < best) {
                best = s
                startPos = i
            }
        }
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val p = order[(startPos + i) % 4]
            out[i * 2] = quad[p * 2]
            out[i * 2 + 1] = quad[p * 2 + 1]
        }
        return out
    }

    /**
     * Rotates by an arbitrary angle (clockwise, degrees) about the image centre with bilinear
     * sampling. With [expand] the canvas grows to hold the whole rotated image; without it the
     * canvas keeps the source size and the corners are cropped. Uncovered pixels are transparent.
     *
     * Exact quarter turns are routed to [rotate90]: resampling them is lossless in theory and
     * visibly soft in practice, because bilinear taps at exactly .0 offsets still round-trip
     * through float.
     */
    fun rotate(src: RgbaImage, degrees: Float, expand: Boolean = true): RgbaImage {
        val w = src.width
        val h = src.height
        val turns = degrees / 90f
        val nearest = turns.roundToInt()
        if (abs(turns - nearest) < 1e-4f) {
            val q = ((nearest % 4) + 4) % 4
            if (expand || q % 2 == 0 || w == h) return rotate90(src, q)
        }

        val rad = degrees.toDouble() * Math.PI / 180.0
        val cosA = cos(rad)
        val sinA = sin(rad)
        val outW: Int
        val outH: Int
        if (expand) {
            outW = maxOf(1, ceil(abs(w * cosA) + abs(h * sinA) - 1e-6).toInt())
            outH = maxOf(1, ceil(abs(w * sinA) + abs(h * cosA) - 1e-6).toInt())
        } else {
            outW = w
            outH = h
        }
        val out = RgbaImage(outW, outH)
        val op = out.pixels
        val srcCx = (w - 1) * 0.5
        val srcCy = (h - 1) * 0.5
        val dstCx = (outW - 1) * 0.5
        val dstCy = (outH - 1) * 0.5

        Parallel.rows(outH) { from, to ->
            for (y in from until to) {
                val dy = y - dstCy
                val row = y * outW
                for (x in 0 until outW) {
                    val dx = x - dstCx
                    val sx = cosA * dx + sinA * dy + srcCx
                    val sy = -sinA * dx + cosA * dy + srcCy
                    op[row + x] = sampleArgb(src, sx.toFloat(), sy.toFloat())
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------

    /** Adjugate/determinant inverse of a row-major 3×3, or null when it is singular. */
    private fun invert3x3(m: DoubleArray): DoubleArray? {
        val a = m[4] * m[8] - m[5] * m[7]
        val b = m[5] * m[6] - m[3] * m[8]
        val c = m[3] * m[7] - m[4] * m[6]
        val det = m[0] * a + m[1] * b + m[2] * c
        if (abs(det) < 1e-14) return null
        val inv = 1.0 / det
        return doubleArrayOf(
            a * inv,
            (m[2] * m[7] - m[1] * m[8]) * inv,
            (m[1] * m[5] - m[2] * m[4]) * inv,
            b * inv,
            (m[0] * m[8] - m[2] * m[6]) * inv,
            (m[2] * m[3] - m[0] * m[5]) * inv,
            c * inv,
            (m[1] * m[6] - m[0] * m[7]) * inv,
            (m[0] * m[4] - m[1] * m[3]) * inv,
        )
    }

    /**
     * Bilinear ARGB sample, transparent outside the image. Interpolation is done on
     * **premultiplied** channels: interpolating straight alpha pulls the (undefined) colour of
     * fully transparent pixels into the result and haloes every cut-out edge with black.
     */
    private fun sampleArgb(src: RgbaImage, fx: Float, fy: Float): Int {
        val w = src.width
        val h = src.height
        if (fx < -0.5f || fy < -0.5f || fx > w - 0.5f || fy > h - 0.5f) return 0
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val cx0 = Px.clamp(x0, 0, w - 1)
        val cx1 = Px.clamp(x0 + 1, 0, w - 1)
        val cy0 = Px.clamp(y0, 0, h - 1)
        val cy1 = Px.clamp(y0 + 1, 0, h - 1)
        val p = src.pixels
        val p00 = p[cy0 * w + cx0]
        val p10 = p[cy0 * w + cx1]
        val p01 = p[cy1 * w + cx0]
        val p11 = p[cy1 * w + cx1]

        val w00 = (1f - tx) * (1f - ty)
        val w10 = tx * (1f - ty)
        val w01 = (1f - tx) * ty
        val w11 = tx * ty
        val a00 = ((p00 ushr 24) and 0xFF) * w00
        val a10 = ((p10 ushr 24) and 0xFF) * w10
        val a01 = ((p01 ushr 24) and 0xFF) * w01
        val a11 = ((p11 ushr 24) and 0xFF) * w11
        val aSum = a00 + a10 + a01 + a11
        if (aSum <= 0f) return 0
        val invA = 1f / aSum
        val r = (((p00 ushr 16) and 0xFF) * a00 + ((p10 ushr 16) and 0xFF) * a10 +
            ((p01 ushr 16) and 0xFF) * a01 + ((p11 ushr 16) and 0xFF) * a11) * invA
        val g = (((p00 ushr 8) and 0xFF) * a00 + ((p10 ushr 8) and 0xFF) * a10 +
            ((p01 ushr 8) and 0xFF) * a01 + ((p11 ushr 8) and 0xFF) * a11) * invA
        val b = ((p00 and 0xFF) * a00 + (p10 and 0xFF) * a10 +
            (p01 and 0xFF) * a01 + (p11 and 0xFF) * a11) * invA
        return RgbaImage.argb(
            Px.clamp(aSum.roundToInt(), 0, 255),
            Px.clamp(r.roundToInt(), 0, 255),
            Px.clamp(g.roundToInt(), 0, 255),
            Px.clamp(b.roundToInt(), 0, 255),
        )
    }

    /**
     * Convex hull of a mask's foreground, as x,y interleaved floats, or null for fewer than three
     * distinct points.
     *
     * Only the leftmost and rightmost foreground pixel of each row is a hull candidate, so a
     * 12 MP component contributes at most `2 · height` points instead of millions — the hull is
     * identical either way, and this is the difference between a sort of 2 000 items and one of
     * 2 000 000.
     */
    private fun convexHullOf(mask: Mask): FloatArray? {
        val w = mask.width
        val h = mask.height
        val packed = LongArray(2 * h)
        var np = 0
        for (y in 0 until h) {
            val row = y * w
            var lo = -1
            var hi = -1
            for (x in 0 until w) {
                if (mask.data[row + x]) {
                    if (lo < 0) lo = x
                    hi = x
                }
            }
            if (lo >= 0) {
                packed[np++] = (lo.toLong() shl 32) or y.toLong()
                if (hi != lo) packed[np++] = (hi.toLong() shl 32) or y.toLong()
            }
        }
        if (np < 3) return null
        val pts = packed.copyOf(np)
        // x in the high word, y in the low word, both non-negative: the natural long ordering is
        // exactly the (x, then y) ordering monotone chain needs.
        java.util.Arrays.sort(pts)

        val n = pts.size
        val stack = IntArray(2 * n + 1)
        var k = 0
        for (i in 0 until n) {
            while (k >= 2 && cross(pts[stack[k - 2]], pts[stack[k - 1]], pts[i]) <= 0L) k--
            stack[k++] = i
        }
        val lowerEnd = k + 1
        for (i in n - 2 downTo 0) {
            while (k >= lowerEnd && cross(pts[stack[k - 2]], pts[stack[k - 1]], pts[i]) <= 0L) k--
            stack[k++] = i
        }
        val count = k - 1
        if (count < 3) return null
        val out = FloatArray(count * 2)
        for (i in 0 until count) {
            val p = pts[stack[i]]
            out[i * 2] = (p ushr 32).toInt().toFloat()
            out[i * 2 + 1] = (p and 0xFFFFFFFFL).toInt().toFloat()
        }
        return out
    }

    private fun cross(o: Long, a: Long, b: Long): Long {
        val ox = (o ushr 32).toInt().toLong()
        val oy = (o and 0xFFFFFFFFL).toInt().toLong()
        val ax = (a ushr 32).toInt().toLong()
        val ay = (a and 0xFFFFFFFFL).toInt().toLong()
        val bx = (b ushr 32).toInt().toLong()
        val by = (b and 0xFFFFFFFFL).toInt().toLong()
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox)
    }

    /**
     * Douglas–Peucker approximation of a closed polygon, iterative with an explicit stack.
     *
     * The ring is re-based at its two most distant vertices before splitting, so both halves are
     * plain ranges. Running DP straight through index 0 of a ring instead deletes the arbitrary
     * start vertex and unrolls the polygon into a line.
     */
    private fun approxPolyClosed(poly: FloatArray, epsilon: Float): FloatArray {
        val n = poly.size / 2
        if (n <= 4) return poly.copyOf()

        var start = 0
        var best = -1f
        val x0 = poly[0]
        val y0 = poly[1]
        for (i in 1 until n) {
            val dx = poly[i * 2] - x0
            val dy = poly[i * 2 + 1] - y0
            val d = dx * dx + dy * dy
            if (d > best) {
                best = d
                start = i
            }
        }
        var second = start
        best = -1f
        val xs = poly[start * 2]
        val ys = poly[start * 2 + 1]
        for (i in 0 until n) {
            val dx = poly[i * 2] - xs
            val dy = poly[i * 2 + 1] - ys
            val d = dx * dx + dy * dy
            if (d > best) {
                best = d
                second = i
            }
        }

        val order = IntArray(n + 1)
        for (i in 0..n) order[i] = (start + i) % n
        var mid = (second - start + n) % n
        if (mid == 0) mid = n / 2

        val keep = BooleanArray(n + 1)
        keep[0] = true
        keep[mid] = true
        keep[n] = true
        dpRange(poly, order, 0, mid, epsilon, keep)
        dpRange(poly, order, mid, n, epsilon, keep)

        var kept = 0
        for (i in 0 until n) if (keep[i]) kept++
        val out = FloatArray(kept * 2)
        var j = 0
        for (i in 0 until n) {
            if (!keep[i]) continue
            val p = order[i]
            out[j++] = poly[p * 2]
            out[j++] = poly[p * 2 + 1]
        }
        return out
    }

    private fun dpRange(
        poly: FloatArray,
        order: IntArray,
        from: Int,
        to: Int,
        epsilon: Float,
        keep: BooleanArray,
    ) {
        val span = to - from
        if (span < 2) return
        // The stack holds disjoint ranges, so it can never exceed one entry per index in the span.
        val stack = IntArray(2 * span + 4)
        var sp = 0
        stack[sp++] = from
        stack[sp++] = to
        while (sp > 0) {
            val hi = stack[--sp]
            val lo = stack[--sp]
            if (hi - lo < 2) continue
            val ax = poly[order[lo] * 2]
            val ay = poly[order[lo] * 2 + 1]
            val bx = poly[order[hi] * 2]
            val by = poly[order[hi] * 2 + 1]
            var maxD = -1f
            var maxI = -1
            for (i in lo + 1 until hi) {
                val p = order[i]
                val d = pointSegmentDistance(poly[p * 2], poly[p * 2 + 1], ax, ay, bx, by)
                if (d > maxD) {
                    maxD = d
                    maxI = i
                }
            }
            if (maxI >= 0 && maxD > epsilon) {
                keep[maxI] = true
                stack[sp++] = lo
                stack[sp++] = maxI
                stack[sp++] = maxI
                stack[sp++] = hi
            }
        }
    }

    private fun pointSegmentDistance(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 1e-12f) {
            val ex = px - ax
            val ey = py - ay
            return sqrt(ex * ex + ey * ey)
        }
        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        if (t < 0f) t = 0f else if (t > 1f) t = 1f
        val cxp = px - (ax + t * dx)
        val cyp = py - (ay + t * dy)
        return sqrt(cxp * cxp + cyp * cyp)
    }

    private fun polygonPerimeter(poly: FloatArray): Float {
        val n = poly.size / 2
        if (n < 2) return 0f
        var total = 0.0
        var j = n - 1
        for (i in 0 until n) {
            val dx = (poly[i * 2] - poly[j * 2]).toDouble()
            val dy = (poly[i * 2 + 1] - poly[j * 2 + 1]).toDouble()
            total += sqrt(dx * dx + dy * dy)
            j = i
        }
        return total.toFloat()
    }

    /** Signed shoelace area; the sign is the winding direction, so callers take the absolute value. */
    private fun polygonArea(poly: FloatArray): Float {
        val n = poly.size / 2
        if (n < 3) return 0f
        var sum = 0.0
        var j = n - 1
        for (i in 0 until n) {
            sum += (poly[j * 2].toDouble() + poly[i * 2]) *
                (poly[j * 2 + 1].toDouble() - poly[i * 2 + 1])
            j = i
        }
        return (sum * 0.5).toFloat()
    }
}
