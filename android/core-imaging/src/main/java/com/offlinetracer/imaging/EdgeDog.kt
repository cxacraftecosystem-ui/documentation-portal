package com.offlinetracer.imaging

import kotlin.math.tanh

object EdgeDog {

    fun dog(src: GrayF, sigma: Float, k: Float = 1.6f, tau: Float = 0.98f): GrayF {
        val t = if (tau < 0f) 0f else if (tau > 0.999f) 0.999f else tau
        val s1 = if (sigma < 0f) 0f else sigma
        val s2 = if (k > 0f) s1 * k else s1
        val fine = Convolve.gaussianBlur(src, s1)
        val coarse = Convolve.gaussianBlur(src, s2)
        val a = fine.data
        val b = coarse.data
        val out = FloatArray(a.size)
        for (i in out.indices) out[i] = a[i] - t * b[i]
        return GrayF(src.width, src.height, out)
    }

    fun xdog(
        src: GrayF,
        sigma: Float,
        k: Float = 1.6f,
        tau: Float = 0.98f,
        epsilon: Float = 0.5f,
        phi: Float = 20f,
    ): GrayF {
        val t = if (tau < 0f) 0f else if (tau > 0.999f) 0.999f else tau

        val s1 = if (sigma < 0f) 0f else sigma
        val s2 = if (k > 0f) s1 * k else s1
        val a = Convolve.gaussianBlurDouble(src, s1)
        val b = Convolve.gaussianBlurDouble(src, s2)
        val td = t.toDouble()
        val scale = 1.0 / (1.0 - td)
        val out = FloatArray(a.size)
        Parallel.chunks(out.size, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) {
                val u = (a[i] - td * b[i]) * scale
                out[i] = softThreshold(u.toFloat(), epsilon, phi)
            }
        }
        return GrayF(src.width, src.height, out)
    }

    fun softThreshold(u: Float, epsilon: Float, phi: Float): Float {
        if (u.isNaN()) return 1f
        if (u >= epsilon) return 1f
        val v = 1.0 + tanh(phi.toDouble() * (u.toDouble() - epsilon.toDouble()))
        return if (v < 0.0) 0f else if (v > 1.0) 1f else v.toFloat()
    }
}
