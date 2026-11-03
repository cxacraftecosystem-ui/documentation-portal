package com.offlinetracer.imaging

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

object EdgeFlow {

    class FlowField(
        @JvmField val width: Int,
        @JvmField val height: Int,
        @JvmField val tx: FloatArray,
        @JvmField val ty: FloatArray,
        @JvmField val magnitude: FloatArray,
    ) {
        init {
            require(width > 0 && height > 0) { "FlowField must be non-empty, got ${width}x$height" }
            require(tx.size == width * height && ty.size == width * height && magnitude.size == width * height) {
                "FlowField arrays must all be ${width * height} long"
            }
        }

        fun copy(): FlowField = FlowField(width, height, tx.copyOf(), ty.copyOf(), magnitude.copyOf())
    }

    data class FlowParams(
        val tensorSigma: Float = 2f,
        val etfIterations: Int = 3,
        val etfRadius: Int = 5,
        val sigmaC: Float = 1f,
        val sigmaM: Float = 3f,
        val tau: Float = 0.99f,
        val fdogIterations: Int = 3,
        val epsilon: Float = 0.5f,
        val phi: Float = 20f,
    )

    fun structureTensorFlow(src: GrayF, sigma: Float = 2f): FlowField {
        val w = src.width
        val h = src.height
        val n = w * h
        val g = Convolve.gradients(src, GradientOp.SCHARR)
        val gx = g.gx.data
        val gy = g.gy.data

        val exx = FloatArray(n)
        val exy = FloatArray(n)
        val eyy = FloatArray(n)
        val mag = FloatArray(n)
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) {
                val a = gx[i]
                val b = gy[i]
                exx[i] = a * a
                exy[i] = a * b
                eyy[i] = b * b
                mag[i] = sqrt(a * a + b * b)
            }
        }
        var maxMag = 0f
        for (i in 0 until n) {
            val m = mag[i]
            if (m > maxMag) maxMag = m
        }
        val inv = if (maxMag > 1e-20f) 1f / maxMag else 0f
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) mag[i] = mag[i] * inv
        }

        val s = if (sigma < 0f) 0f else sigma
        val jxx = Convolve.gaussianBlur(GrayF(w, h, exx), s).data
        val jxy = Convolve.gaussianBlur(GrayF(w, h, exy), s).data
        val jyy = Convolve.gaussianBlur(GrayF(w, h, eyy), s).data

        val tx = FloatArray(n)
        val ty = FloatArray(n)
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) {
                val a = jxx[i].toDouble()
                val b = jxy[i].toDouble()
                val c = jyy[i].toDouble()
                val diff = a - c
                val disc = sqrt(diff * diff + 4.0 * b * b)
                val lmin = 0.5 * (a + c - disc)

                var vx = b
                var vy = lmin - a
                val n1 = vx * vx + vy * vy
                val ux = lmin - c
                val uy = b
                val n2 = ux * ux + uy * uy
                var norm = n1
                if (n2 > n1) {
                    vx = ux
                    vy = uy
                    norm = n2
                }
                if (norm > 1e-30) {
                    val len = sqrt(norm)
                    var nx = vx / len
                    var ny = vy / len
                    if (ny < 0.0 || (ny == 0.0 && nx < 0.0)) {
                        nx = -nx
                        ny = -ny
                    }
                    tx[i] = nx.toFloat()
                    ty[i] = ny.toFloat()
                } else {
                    tx[i] = 1f
                    ty[i] = 0f
                }
            }
        }
        return FlowField(w, h, tx, ty, mag)
    }

    fun refineEtf(field: FlowField, iterations: Int = 3, radius: Int = 5): FlowField {
        if (iterations <= 0 || radius <= 0) return field.copy()
        val iters = if (iterations > 16) 16 else iterations
        val r = if (radius > 32) 32 else radius
        val w = field.width
        val h = field.height
        val n = w * h
        val mag = field.magnitude

        val r2 = r * r
        var count = 0
        for (dy in -r..r) {
            for (dx in -r..r) if (dx * dx + dy * dy < r2) count++
        }
        val offX = IntArray(count)
        val offY = IntArray(count)
        val offFlat = IntArray(count)
        var k = 0
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy < r2) {
                    offX[k] = dx
                    offY[k] = dy
                    offFlat[k] = dy * w + dx
                    k++
                }
            }
        }

        val expMag = FloatArray(n)
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) expMag[i] = exp(2f * mag[i])
        }

        var curX = field.tx.copyOf()
        var curY = field.ty.copyOf()
        var dstX = FloatArray(n)
        var dstY = FloatArray(n)

        var it = 0
        while (it < iters) {
            val srcX = curX
            val srcY = curY
            val outX = dstX
            val outY = dstY
            Parallel.rows(h, Parallel.ROWS_NEIGHBOURHOOD) { fromY, toY ->
                for (y in fromY until toY) {
                    val row = y * w
                    val rowInterior = y >= r && y < h - r
                    for (x in 0 until w) {
                        val i = row + x
                        val t0x = srcX[i]
                        val t0y = srcY[i]
                        val e0 = expMag[i]
                        val interior = rowInterior && x >= r && x < w - r
                        var sx = 0f
                        var sy = 0f
                        var j = 0
                        while (j < count) {
                            val q: Int
                            if (interior) {
                                q = i + offFlat[j]
                            } else {
                                var nx = x + offX[j]
                                var ny = y + offY[j]
                                if (nx < 0) nx = 0 else if (nx > w - 1) nx = w - 1
                                if (ny < 0) ny = 0 else if (ny > h - 1) ny = h - 1
                                q = ny * w + nx
                            }
                            j++
                            val t1x = srcX[q]
                            val t1y = srcY[q]
                            val dot = t0x * t1x + t0y * t1y
                            val wd = if (dot < 0f) -dot else dot
                            if (wd <= 0f) continue
                            val e1 = expMag[q]
                            val wm = e1 / (e1 + e0)
                            val ww = if (dot >= 0f) wm * wd else -(wm * wd)
                            sx += t1x * ww
                            sy += t1y * ww
                        }
                        val len = sqrt(sx * sx + sy * sy)
                        if (len > 1e-12f) {
                            outX[i] = sx / len
                            outY[i] = sy / len
                        } else {
                            outX[i] = t0x
                            outY[i] = t0y
                        }
                    }
                }
            }
            val swapX = curX
            curX = dstX
            dstX = swapX
            val swapY = curY
            curY = dstY
            dstY = swapY
            it++
        }
        return FlowField(w, h, curX, curY, mag.copyOf())
    }

    fun fdog(
        src: GrayF,
        field: FlowField,
        sigmaC: Float = 1f,
        sigmaM: Float = 3f,
        tau: Float = 0.99f,
        iterations: Int = 3,
        epsilon: Float = 0.5f,
        phi: Float = 20f,
    ): GrayF {
        require(field.width == src.width && field.height == src.height) {
            "fdog(): flow ${field.width}x${field.height} does not match image ${src.width}x${src.height}"
        }
        val w = src.width
        val h = src.height
        val iters = if (iterations < 1) 1 else if (iterations > 16) 16 else iterations
        val sc = clampSigma(sigmaC, 16f)
        val sm = clampSigma(sigmaM, 32f)
        val ss = 1.6f * sc
        val t = if (tau < 0f) 0f else if (tau > 0.999f) 0.999f else tau

        val rc = radiusFor(ss)
        val kernAcross = DoubleArray(2 * rc + 1)
        run {
            val gc = DoubleArray(2 * rc + 1)
            val gs = DoubleArray(2 * rc + 1)
            val dc = 2.0 * sc * sc
            val ds = 2.0 * ss * ss
            var sumC = 0.0
            var sumS = 0.0
            for (i in -rc..rc) {
                val fi = i.toDouble()
                val a = exp(-(fi * fi) / dc)
                val b = exp(-(fi * fi) / ds)
                gc[i + rc] = a
                gs[i + rc] = b
                sumC += a
                sumS += b
            }
            for (i in kernAcross.indices) kernAcross[i] = gc[i] / sumC - t * (gs[i] / sumS)
        }
        val scale = 1.0 / (1.0 - t)

        val rm = radiusFor(sm)
        val kernAlong = DoubleArray(rm + 1)
        run {
            val dm = 2.0 * sm * sm
            for (i in 0..rm) {
                val fi = i.toDouble()
                kernAlong[i] = exp(-(fi * fi) / dm)
            }
        }

        val work = src.data.copyOf()
        val ink = FloatArray(work.size)
        var pass = 0
        while (true) {
            val across = dogAcross(work, w, h, field, kernAcross, rc, scale)
            val along = blurAlong(across, w, h, field, kernAlong, rm)
            Parallel.chunks(ink.size, Parallel.PIXELS_MAP) { from, to ->
                for (i in from until to) ink[i] = EdgeDog.softThreshold(along[i], epsilon, phi)
            }
            pass++
            if (pass >= iters) break
            Parallel.chunks(work.size, Parallel.PIXELS_MAP) { from, to ->
                for (i in from until to) if (ink[i] < work[i]) work[i] = ink[i]
            }
        }
        return GrayF(w, h, ink)
    }

    fun coherentLineDrawing(src: GrayF, params: FlowParams): GrayF {
        val field = refineEtf(
            structureTensorFlow(src, params.tensorSigma),
            params.etfIterations,
            params.etfRadius,
        )
        return fdog(
            src, field, params.sigmaC, params.sigmaM, params.tau,
            params.fdogIterations, params.epsilon, params.phi,
        )
    }

    private fun dogAcross(
        img: FloatArray,
        w: Int,
        h: Int,
        f: FlowField,
        kern: DoubleArray,
        radius: Int,
        scale: Double,
    ): FloatArray {
        val out = FloatArray(img.size)
        val tx = f.tx
        val ty = f.ty
        Parallel.rows(h, Parallel.ROWS_NEIGHBOURHOOD) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    val gx = ty[i].toDouble()
                    val gy = -tx[i].toDouble()
                    var acc = kern[radius] * img[i]
                    var s = 1
                    while (s <= radius) {
                        val ox = gx * s
                        val oy = gy * s
                        acc += kern[radius + s] * sample(img, w, h, x + ox, y + oy)
                        acc += kern[radius - s] * sample(img, w, h, x - ox, y - oy)
                        s++
                    }
                    out[i] = (acc * scale).toFloat()
                }
            }
        }
        return out
    }

    private fun blurAlong(
        img: FloatArray,
        w: Int,
        h: Int,
        f: FlowField,
        kern: DoubleArray,
        radius: Int,
    ): FloatArray {
        val out = FloatArray(img.size)
        val tx = f.tx
        val ty = f.ty
        val maxX = (w - 1).toDouble()
        val maxY = (h - 1).toDouble()
        Parallel.rows(h, Parallel.ROWS_NEIGHBOURHOOD) { fromY, toY ->
            for (y in fromY until toY) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    var acc = kern[0] * img[i]
                    var wsum = kern[0]

                    var side = 0
                    while (side < 2) {
                        var dx = if (side == 0) tx[i].toDouble() else -tx[i].toDouble()
                        var dy = if (side == 0) ty[i].toDouble() else -ty[i].toDouble()
                        var cx = x.toDouble()
                        var cy = y.toDouble()
                        var s = 1
                        while (s <= radius) {
                            cx += dx
                            cy += dy
                            if (cx < 0.0 || cy < 0.0 || cx > maxX || cy > maxY) break
                            val kw = kern[s]
                            acc += kw * sample(img, w, h, cx, cy)
                            wsum += kw
                            var jx = cx.roundToInt()
                            var jy = cy.roundToInt()
                            if (jx < 0) jx = 0 else if (jx > w - 1) jx = w - 1
                            if (jy < 0) jy = 0 else if (jy > h - 1) jy = h - 1
                            val q = jy * w + jx
                            var ntx = tx[q].toDouble()
                            var nty = ty[q].toDouble()
                            if (ntx * dx + nty * dy < 0.0) {
                                ntx = -ntx
                                nty = -nty
                            }
                            if (ntx * ntx + nty * nty < 1e-12) break
                            dx = ntx
                            dy = nty
                            s++
                        }
                        side++
                    }
                    out[i] = if (wsum > 0.0) (acc / wsum).toFloat() else img[i]
                }
            }
        }
        return out
    }

    private fun sample(d: FloatArray, w: Int, h: Int, fx: Double, fy: Double): Double {
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        if (ix >= 0 && iy >= 0 && ix < w - 1 && iy < h - 1) {
            val tx = fx - ix
            val ty = fy - iy
            val p = iy * w + ix
            val v00 = d[p].toDouble()
            val v10 = d[p + 1].toDouble()
            val v01 = d[p + w].toDouble()
            val v11 = d[p + w + 1].toDouble()
            val a = v00 + (v10 - v00) * tx
            val b = v01 + (v11 - v01) * tx
            return a + (b - a) * ty
        }
        val tx = fx - ix
        val ty = fy - iy
        val x0 = if (ix < 0) 0 else if (ix > w - 1) w - 1 else ix
        val y0 = if (iy < 0) 0 else if (iy > h - 1) h - 1 else iy
        val xp = ix + 1
        val yp = iy + 1
        val x1 = if (xp < 0) 0 else if (xp > w - 1) w - 1 else xp
        val y1 = if (yp < 0) 0 else if (yp > h - 1) h - 1 else yp
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

    private fun clampSigma(v: Float, hi: Float): Float =
        if (v.isNaN() || v < 0.05f) 0.05f else if (v > hi) hi else v

    private fun radiusFor(sigma: Float): Int {
        val r = ceil(3f * sigma).toInt()
        return if (r < 1) 1 else r
    }
}
