package com.offlinetracer.imaging

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Exact Euclidean distance transform.
 *
 * Felzenszwalb & Huttenlocher's two-pass 1-D lower-envelope algorithm: O(n) per row and per column,
 * and **exact**. The usual 3-4 or 5-7-11 chamfer approximation is not good enough here because the
 * only consumer is stroke-width estimation (`2 × distance at the skeleton`) — a chamfer's few
 * percent of anisotropic error becomes a stroke that visibly changes width as it turns a corner,
 * which is precisely the artefact width modulation exists to avoid.
 */
object Distance {

    /**
     * Stand-in for infinity. A real `POSITIVE_INFINITY` propagates `INF - INF = NaN` through the
     * parabola intersection whenever two unreachable columns are compared, and the NaN then poisons
     * the envelope for the rest of the row. This value is finite, so the intersection degenerates
     * to the harmless midpoint, and it is far larger than any squared distance an image can produce
     * (a 2^15-pixel edge gives ~2.1e9).
     */
    private const val FAR = 1.0e20

    /**
     * Euclidean distance (not squared) from every pixel to the nearest pixel of the opposite class,
     * as a [GrayF] in pixel units.
     *
     * With [insideForeground] true — the default and what stroke width needs — foreground pixels
     * hold their distance to the nearest background pixel and background pixels are 0. With it
     * false the roles swap, giving the distance from empty space to the nearest ink.
     *
     * Out-of-bounds is *not* treated as background here, unlike the mask/morphology convention: the
     * transform is defined on the array it is given, so a shape that runs off the edge keeps
     * growing rather than being cut by an imaginary border. A mask with no pixels of the reference
     * class at all returns all zeros, since every distance would otherwise be infinite.
     */
    fun euclidean(src: Mask, insideForeground: Boolean = true): GrayF {
        val w = src.width
        val h = src.height
        val n = w * h
        val d = src.data
        val f = DoubleArray(n)
        var hasSource = false
        for (i in 0 until n) {
            val isSource = if (insideForeground) !d[i] else d[i]
            if (isSource) {
                hasSource = true
            } else {
                f[i] = FAR
            }
        }
        if (!hasSource) return GrayF(w, h)

        val g = DoubleArray(n)
        val m = max(w, h)

        // Column pass over columns, row pass over rows, with a barrier between them — the row pass
        // reads every element of `g`, so it cannot start until the last column has been written, and
        // `Parallel.chunks` returning is that barrier.
        //
        // **Each share allocates its own `v`/`z`.** Hoisting them out here, the way the single-threaded
        // version did, is the classic way to break a parallel Felzenszwalb transform: `v` is the list of
        // vertices of the lower envelope and `z` the breakpoints between them, and the second half of
        // [dt1d] reads back the list the first half built. A neighbouring share overwriting entry `k`
        // in between substitutes another line's parabola, and the result is not garbage — it is a
        // plausible-looking distance field that is quietly wrong, which is the worst kind. The
        // allocation is O(threads) per call, not O(pixels).
        Parallel.chunks(w, Parallel.ROWS_KERNEL) { fromX, toX ->
            val v = IntArray(m)
            val z = DoubleArray(m + 1)
            for (x in fromX until toX) dt1d(f, x, w, g, x, w, h, v, z)
        }
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            val v = IntArray(m)
            val z = DoubleArray(m + 1)
            for (y in fromY until toY) dt1d(g, y * w, 1, f, y * w, 1, w, v, z)
        }

        val out = FloatArray(n)
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) out[i] = sqrt(f[i]).toFloat()
        }
        return GrayF(w, h, out)
    }

    /**
     * Stroke width implied by a distance transform at `(x, y)`: `2 × dt`, since the distance at the
     * centreline is the half-width. Coordinates are edge-clamped, so a caller walking a polyline
     * that ends exactly on the border still gets a usable width instead of an exception.
     */
    fun strokeWidthAt(dt: GrayF, x: Int, y: Int): Float = 2f * dt.clamped(x, y)

    /**
     * Lower envelope of the parabolas `(q - i)² + f[i]` along one strided line.
     *
     * [f] and [out] must be different arrays: the second loop reads `f` at the vertex of the
     * winning parabola, which can lie *ahead* of the position being written, so writing in place
     * corrupts values that have not been read yet.
     *
     * [v] and [z] are caller-supplied scratch and must belong to **one thread**. They carry state from
     * the first loop to the second, so two lines sharing them is a correctness bug, not a performance
     * one. See the allocation site in [euclidean].
     */
    private fun dt1d(
        f: DoubleArray, fOff: Int, fStride: Int,
        out: DoubleArray, oOff: Int, oStride: Int,
        n: Int, v: IntArray, z: DoubleArray,
    ) {
        if (n <= 0) return
        var k = 0
        v[0] = 0
        z[0] = -FAR
        z[1] = FAR
        for (q in 1 until n) {
            val fq = f[fOff + q * fStride] + q.toDouble() * q
            var vk = v[k]
            var s = (fq - (f[fOff + vk * fStride] + vk.toDouble() * vk)) / (2.0 * q - 2.0 * vk)
            while (k > 0 && s <= z[k]) {
                k--
                vk = v[k]
                s = (fq - (f[fOff + vk * fStride] + vk.toDouble() * vk)) / (2.0 * q - 2.0 * vk)
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = FAR
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val vk = v[k]
            val dq = (q - vk).toDouble()
            out[oOff + q * oStride] = dq * dq + f[fOff + vk * fStride]
        }
    }
}
