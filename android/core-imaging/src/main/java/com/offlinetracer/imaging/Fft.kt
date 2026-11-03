package com.offlinetracer.imaging

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Fft {

    fun nextPowerOfTwo(n: Int): Int {
        if (n <= 1) return 1
        if (n > (1 shl 30)) return 1 shl 30
        var v = n - 1
        v = v or (v shr 1)
        v = v or (v shr 2)
        v = v or (v shr 4)
        v = v or (v shr 8)
        v = v or (v shr 16)
        return v + 1
    }

    fun transform(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        val n = real.size
        require(imag.size == n) { "transform(): real/imag length mismatch ($n vs ${imag.size})" }
        if (n <= 1) return
        require(n and (n - 1) == 0) { "transform(): length must be a power of two, got $n" }
        val cosT = DoubleArray(n shr 1)
        val sinT = DoubleArray(n shr 1)
        fillTwiddles(cosT, sinT, n, inverse)
        fftStrided(real, imag, 0, 1, n, cosT, sinT)
        if (inverse) {
            val inv = (1.0 / n).toFloat()
            for (i in 0 until n) {
                real[i] *= inv
                imag[i] *= inv
            }
        }
    }

    fun transform2d(real: FloatArray, imag: FloatArray, w: Int, h: Int, inverse: Boolean) {
        require(w > 0 && h > 0) { "transform2d(): empty image ${w}x$h" }
        require(real.size == w * h && imag.size == w * h) {
            "transform2d(): buffers must be ${w * h}, got ${real.size}/${imag.size}"
        }
        require(w and (w - 1) == 0 && h and (h - 1) == 0) {
            "transform2d(): dimensions must be powers of two, got ${w}x$h"
        }
        if (w > 1) {
            val cosT = DoubleArray(w shr 1)
            val sinT = DoubleArray(w shr 1)
            fillTwiddles(cosT, sinT, w, inverse)
            for (y in 0 until h) fftStrided(real, imag, y * w, 1, w, cosT, sinT)
        }
        if (h > 1) {
            val cosT = DoubleArray(h shr 1)
            val sinT = DoubleArray(h shr 1)
            fillTwiddles(cosT, sinT, h, inverse)
            for (x in 0 until w) fftStrided(real, imag, x, w, h, cosT, sinT)
        }
        if (inverse) {
            val inv = (1.0 / (w.toDouble() * h)).toFloat()
            for (i in real.indices) {
                real[i] *= inv
                imag[i] *= inv
            }
        }
    }

    private fun fillTwiddles(cosT: DoubleArray, sinT: DoubleArray, n: Int, inverse: Boolean) {
        val sign = if (inverse) 1.0 else -1.0
        for (i in cosT.indices) {
            val a = sign * 2.0 * PI * i / n
            cosT[i] = cos(a)
            sinT[i] = sin(a)
        }
    }

    private fun fftStrided(
        re: FloatArray, im: FloatArray, off: Int, stride: Int, n: Int,
        cosT: DoubleArray, sinT: DoubleArray,
    ) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val ii = off + i * stride
                val jj = off + j * stride
                var t = re[ii]
                re[ii] = re[jj]
                re[jj] = t
                t = im[ii]
                im[ii] = im[jj]
                im[jj] = t
            }
        }
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val step = n / len
            var base = 0
            while (base < n) {
                for (k in 0 until halfLen) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val i1 = off + (base + k) * stride
                    val i2 = off + (base + k + halfLen) * stride
                    val xr = re[i2].toDouble()
                    val xi = im[i2].toDouble()
                    val vr = xr * wr - xi * wi
                    val vi = xr * wi + xi * wr
                    val ur = re[i1].toDouble()
                    val ui = im[i1].toDouble()
                    re[i1] = (ur + vr).toFloat()
                    im[i1] = (ui + vi).toFloat()
                    re[i2] = (ur - vr).toFloat()
                    im[i2] = (ui - vi).toFloat()
                }
                base += len
            }
            len = len shl 1
        }
    }
}
