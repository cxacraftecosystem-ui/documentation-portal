package com.offlinetracer.imaging

import kotlin.math.floor

object EdgeCanny {

    fun detect(
        src: GrayF,
        blurSigma: Float,
        low: Float,
        high: Float,
        op: GradientOp = GradientOp.SCHARR,
    ): Mask {
        val thin = nonMaximumSuppression(gradientsOf(src, blurSigma, op))
        var lo = if (low.isNaN()) 0f else if (low < 0f) 0f else low
        var hi = if (high.isNaN()) 0f else if (high < 0f) 0f else high
        if (lo > hi) {
            val t = lo
            lo = hi
            hi = t
        }
        return Threshold.hysteresis(thin, lo, hi)
    }

    fun detectAuto(src: GrayF, blurSigma: Float, sensitivity: Float = 0.33f): Mask {
        val g = gradientsOf(src, blurSigma, GradientOp.SCHARR)
        val t = Threshold.autoCannyThresholds(g.magnitude(), sensitivity)
        val lo = if (t.size > 0) t[0] else 0f
        val hi = if (t.size > 1) t[1] else lo
        val thin = nonMaximumSuppression(g)
        return Threshold.hysteresis(thin, if (lo <= hi) lo else hi, if (lo <= hi) hi else lo)
    }

    fun nonMaximumSuppression(g: Convolve.Gradients): GrayF {
        val gxImg = g.gx
        val gyImg = g.gy
        require(gxImg.width == gyImg.width && gxImg.height == gyImg.height) {
            "nonMaximumSuppression(): gx ${gxImg.width}x${gxImg.height} != gy ${gyImg.width}x${gyImg.height}"
        }
        val w = gxImg.width
        val h = gxImg.height
        val gx = gxImg.data
        val gy = gyImg.data
        val mag = g.magnitude().data
        val out = FloatArray(mag.size)
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    val m = mag[i]
                    if (m <= 0f) continue
                    val vx = gx[i]
                    val vy = gy[i]
                    val ax = if (vx < 0f) -vx else vx
                    val ay = if (vy < 0f) -vy else vy
                    val amax = if (ax > ay) ax else ay
                    if (amax <= 1e-20f) continue
                    val ux = vx / amax
                    val uy = vy / amax
                    val fx = x + ux
                    val fy = y + uy
                    val bx = x - ux
                    val by = y - uy
                    if (m < sample(mag, w, h, fx, fy)) continue
                    if (m < sample(mag, w, h, bx, by)) continue
                    out[i] = m
                }
            }
        }
        return GrayF(w, h, out)
    }

    class Ridge(
        @JvmField val width: Int,
        @JvmField val height: Int,
        @JvmField val magnitude: FloatArray,
        @JvmField val offsetX: FloatArray,
        @JvmField val offsetY: FloatArray,
    ) {
        fun offsetXAt(x: Int, y: Int): Float =
            if (x < 0 || y < 0 || x >= width || y >= height) 0f else offsetX[y * width + x]

        fun offsetYAt(x: Int, y: Int): Float =
            if (x < 0 || y < 0 || x >= width || y >= height) 0f else offsetY[y * width + x]

        fun snap(xs: FloatArray, ys: FloatArray, n: Int = minOf(xs.size, ys.size)) {
            val count = if (n < 0) 0 else minOf(n, minOf(xs.size, ys.size))
            for (i in 0 until count) {
                val x = Math.round(xs[i])
                val y = Math.round(ys[i])
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                val j = y * width + x
                xs[i] += offsetX[j]
                ys[i] += offsetY[j]
            }
        }
    }

    fun subpixelRidge(g: Convolve.Gradients): Ridge {
        val gxImg = g.gx
        val gyImg = g.gy
        require(gxImg.width == gyImg.width && gxImg.height == gyImg.height) {
            "subpixelRidge(): gx ${gxImg.width}x${gxImg.height} != gy ${gyImg.width}x${gyImg.height}"
        }
        val w = gxImg.width
        val h = gxImg.height
        val gx = gxImg.data
        val gy = gyImg.data
        val mag = g.magnitude().data
        val peak = FloatArray(mag.size)
        val ox = FloatArray(mag.size)
        val oy = FloatArray(mag.size)
        Parallel.rows(h, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    val m = mag[i]
                    peak[i] = m
                    if (m <= 0f) continue
                    val vx = gx[i]
                    val vy = gy[i]
                    val ax = if (vx < 0f) -vx else vx
                    val ay = if (vy < 0f) -vy else vy
                    val amax = if (ax > ay) ax else ay
                    if (amax <= 1e-20f) continue
                    val ux = vx / amax
                    val uy = vy / amax
                    val a = sampleDouble(mag, w, h, x - ux, y - uy)
                    val c = sampleDouble(mag, w, h, x + ux, y + uy)
                    val denom = a - 2.0 * m + c
                    if (denom >= 0.0) continue
                    var t = 0.5 * (a - c) / denom
                    if (!(t > -0.5)) t = -0.5
                    if (!(t < 0.5)) t = 0.5
                    ox[i] = (t * ux).toFloat()
                    oy[i] = (t * uy).toFloat()
                    peak[i] = (m + 0.5 * t * (c - a) + 0.5 * t * t * denom).toFloat()
                }
            }
        }
        return Ridge(w, h, peak, ox, oy)
    }

    private fun gradientsOf(src: GrayF, blurSigma: Float, op: GradientOp): Convolve.Gradients {
        val blurred = if (blurSigma > 0.05f) Convolve.gaussianBlur(src, blurSigma) else src
        return Convolve.gradients(blurred, op)
    }

    private fun sampleDouble(d: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Double {
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        val tx = (fx - ix).toDouble()
        val ty = (fy - iy).toDouble()
        val x0 = if (ix < 0) 0 else if (ix > w - 1) w - 1 else ix
        val y0 = if (iy < 0) 0 else if (iy > h - 1) h - 1 else iy
        val x1p = ix + 1
        val y1p = iy + 1
        val x1 = if (x1p < 0) 0 else if (x1p > w - 1) w - 1 else x1p
        val y1 = if (y1p < 0) 0 else if (y1p > h - 1) h - 1 else y1p
        val r0 = y0 * w
        val r1 = y1 * w
        val v00 = d[r0 + x0].toDouble()
        val v10 = d[r0 + x1].toDouble()
        val v01 = d[r1 + x0].toDouble()
        val v11 = d[r1 + x1].toDouble()
        val a = v00 + (v10 - v00) * tx
        val b = v01 + (v11 - v01) * tx
        return a + (b - a) * ty
    }

    private fun sample(d: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        val tx = fx - ix
        val ty = fy - iy
        val x0 = if (ix < 0) 0 else if (ix > w - 1) w - 1 else ix
        val y0 = if (iy < 0) 0 else if (iy > h - 1) h - 1 else iy
        val x1p = ix + 1
        val y1p = iy + 1
        val x1 = if (x1p < 0) 0 else if (x1p > w - 1) w - 1 else x1p
        val y1 = if (y1p < 0) 0 else if (y1p > h - 1) h - 1 else y1p
        val r0 = y0 * w
        val r1 = y1 * w
        val v00 = d[r0 + x0]
        val v10 = d[r0 + x1]
        val v01 = d[r1 + x0]
        val v11 = d[r1 + x1]
        val a = v00 + (v10 - v00) * tx
        val b = v01 + (v11 - v01) * tx
        return a + (b - a) * ty
    }
}
