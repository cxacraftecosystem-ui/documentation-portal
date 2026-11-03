package com.offlinetracer.imaging

import kotlin.math.max
import kotlin.math.sqrt

object Distance {

    private const val FAR = 1.0e20

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

    fun strokeWidthAt(dt: GrayF, x: Int, y: Int): Float = 2f * dt.clamped(x, y)

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
