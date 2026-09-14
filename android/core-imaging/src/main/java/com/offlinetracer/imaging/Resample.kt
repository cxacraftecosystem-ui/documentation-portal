package com.offlinetracer.imaging

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Geometric resampling.
 *
 * Downscaling is **exact box-area averaging**: every destination pixel is the area-weighted mean of
 * the source rectangle it covers, including the fractional pixels at the rectangle's edges. Nothing
 * else is acceptable here. Point-sampled or bilinear downscaling of a 12 MP photograph aliases
 * high-frequency texture into features that are locally indistinguishable from edges, and the edge
 * detector then faithfully traces the alias — the artefact appears as plausible-looking hatching
 * that does not exist in the original.
 *
 * The two axes are resampled independently (the 2-D box filter is exactly separable, so this is the
 * same answer as the 2-D integral, not an approximation), which also means one axis can downscale
 * with a box filter while the other upscales bilinearly.
 */
object Resample {

    /**
     * Per-destination-index tap list for one axis.
     *
     * `start[i]` is the first source index, `count[i]` how many taps, and the weights live at
     * `w[i*stride .. i*stride+count[i]-1]`. Flat arrays rather than a list of arrays because this
     * table is read once per destination pixel per row and any indirection shows up in the profile.
     */
    private class Kernel1D(
        @JvmField val stride: Int,
        @JvmField val start: IntArray,
        @JvmField val count: IntArray,
        @JvmField val w: FloatArray,
    )

    /**
     * Resize a grey image: exact box-area average where the axis shrinks, bilinear where it grows.
     * Non-positive requested sizes are coerced to 1 rather than throwing, and an unchanged size
     * returns a copy without touching a single weight.
     */
    fun resize(src: GrayF, w: Int, h: Int): GrayF {
        val dw = max(1, w)
        val dh = max(1, h)
        if (dw == src.width && dh == src.height) return src.copy()
        return resizeGray(src, dw, dh)
    }

    /**
     * Resize a packed ARGB image. All four channels are averaged independently (non-premultiplied):
     * the pipeline resizes the source before any matte exists, so there is no alpha to premultiply
     * against and doing it anyway would darken every edge of a straight-alpha PNG.
     */
    fun resize(src: RgbaImage, w: Int, h: Int): RgbaImage {
        val dw = max(1, w)
        val dh = max(1, h)
        if (dw == src.width && dh == src.height) return src.copy()
        return resizeRgba(src, dw, dh)
    }

    /**
     * Resize a mask with nearest-neighbour sampling. Averaging is deliberately not offered: a mask
     * that comes back with intermediate values is no longer a mask, and every consumer downstream
     * (morphology, thinning, contour tracing) would have to invent its own re-threshold.
     */
    fun resize(src: Mask, w: Int, h: Int): Mask {
        val dw = max(1, w)
        val dh = max(1, h)
        if (dw == src.width && dh == src.height) return src.copy()
        val sw = src.width
        val sh = src.height
        val out = BooleanArray(dw * dh)
        val sx = sw.toDouble() / dw
        val sy = sh.toDouble() / dh
        val xmap = IntArray(dw)
        for (dx in 0 until dw) {
            var v = ((dx + 0.5) * sx).toInt()
            if (v < 0) v = 0
            if (v > sw - 1) v = sw - 1
            xmap[dx] = v
        }
        val s = src.data
        Parallel.rows(dh, Parallel.ROWS_KERNEL) { fromY, toY ->
            for (dy in fromY until toY) {
                var syi = ((dy + 0.5) * sy).toInt()
                if (syi < 0) syi = 0
                if (syi > sh - 1) syi = sh - 1
                val sBase = syi * sw
                val oBase = dy * dw
                for (dx in 0 until dw) out[oBase + dx] = s[sBase + xmap[dx]]
            }
        }
        return Mask(dw, dh, out)
    }

    /**
     * `[newW, newH]` for fitting `w x h` inside [maxLongEdge] with the aspect ratio preserved.
     * Never upscales, never returns a zero dimension. A non-positive [maxLongEdge] means "no limit".
     */
    fun fitWithin(w: Int, h: Int, maxLongEdge: Int): IntArray {
        val sw = max(1, w)
        val sh = max(1, h)
        if (maxLongEdge <= 0) return intArrayOf(sw, sh)
        val longEdge = max(sw, sh)
        if (longEdge <= maxLongEdge) return intArrayOf(sw, sh)
        return if (sw >= sh) {
            intArrayOf(maxLongEdge, max(1, (sh.toDouble() * maxLongEdge / sw).roundToInt()))
        } else {
            intArrayOf(max(1, (sw.toDouble() * maxLongEdge / sh).roundToInt()), maxLongEdge)
        }
    }

    /** [src] scaled down so its long edge is at most [maxLongEdge]. Returns a copy if it already is. */
    fun scaleToLongEdge(src: RgbaImage, maxLongEdge: Int): RgbaImage {
        val d = fitWithin(src.width, src.height, maxLongEdge)
        return resize(src, d[0], d[1])
    }

    /**
     * Sub-rectangle of [src]. The rectangle is clamped into the image rather than validated: a crop
     * that runs off the edge is a user gesture near the border, not a programming error, and
     * throwing there loses the whole operation.
     */
    fun crop(src: RgbaImage, x: Int, y: Int, w: Int, h: Int): RgbaImage {
        val x0 = Px.clamp(x, 0, src.width - 1)
        val y0 = Px.clamp(y, 0, src.height - 1)
        val cw = Px.clamp(w, 1, src.width - x0)
        val ch = Px.clamp(h, 1, src.height - y0)
        val out = IntArray(cw * ch)
        for (row in 0 until ch) {
            System.arraycopy(src.pixels, (y0 + row) * src.width + x0, out, row * cw, cw)
        }
        return RgbaImage(cw, ch, out)
    }

    /** Sub-rectangle of [src], clamped into the image exactly as the [RgbaImage] overload is. */
    fun crop(src: GrayF, x: Int, y: Int, w: Int, h: Int): GrayF {
        val x0 = Px.clamp(x, 0, src.width - 1)
        val y0 = Px.clamp(y, 0, src.height - 1)
        val cw = Px.clamp(w, 1, src.width - x0)
        val ch = Px.clamp(h, 1, src.height - y0)
        val out = FloatArray(cw * ch)
        for (row in 0 until ch) {
            System.arraycopy(src.data, (y0 + row) * src.width + x0, out, row * cw, cw)
        }
        return GrayF(cw, ch, out)
    }

    /**
     * [src] surrounded by a border of [value]. Negative pad amounts are treated as zero — padding is
     * used to give a filter room to work, and a caller that computes a negative margin wants no
     * padding on that side, not a silent crop.
     */
    fun pad(src: GrayF, left: Int, top: Int, right: Int, bottom: Int, value: Float): GrayF {
        val l = max(0, left)
        val t = max(0, top)
        val r = max(0, right)
        val b = max(0, bottom)
        if (l == 0 && t == 0 && r == 0 && b == 0) return src.copy()
        val nw = src.width + l + r
        val nh = src.height + t + b
        val out = FloatArray(nw * nh)
        if (value != 0f) java.util.Arrays.fill(out, value)
        for (row in 0 until src.height) {
            System.arraycopy(src.data, row * src.width, out, (row + t) * nw + l, src.width)
        }
        return GrayF(nw, nh, out)
    }

    private fun buildKernel(srcN: Int, dstN: Int): Kernel1D {
        val scale = srcN.toDouble() / dstN.toDouble()
        return if (scale > 1.0) boxKernel(srcN, dstN, scale) else linearKernel(srcN, dstN, scale)
    }

    private fun boxKernel(srcN: Int, dstN: Int, scale: Double): Kernel1D {
        val stride = ceil(scale).toInt() + 1
        val start = IntArray(dstN)
        val count = IntArray(dstN)
        val wts = FloatArray(dstN * stride)
        for (i in 0 until dstN) {
            val x0 = i * scale
            val x1 = x0 + scale
            var j0 = floor(x0).toInt()
            var j1 = ceil(x1).toInt() - 1
            if (j0 < 0) j0 = 0
            if (j1 > srcN - 1) j1 = srcN - 1
            if (j1 < j0) j1 = j0
            val off = i * stride
            var c = 0
            var sum = 0.0
            var j = j0
            while (j <= j1 && c < stride) {
                val lo = if (x0 > j.toDouble()) x0 else j.toDouble()
                val hi = if (x1 < (j + 1).toDouble()) x1 else (j + 1).toDouble()
                var ov = hi - lo
                if (ov < 0.0) ov = 0.0
                wts[off + c] = ov.toFloat()
                sum += ov
                c++
                j++
            }
            // Renormalise per destination pixel. The last pixel's source rectangle can end a
            // rounding step past the image, and leaving that row summing to less than 1 shows up as
            // a one-pixel dark seam along the right and bottom edges of every downscale.
            if (sum > 0.0) {
                val inv = (1.0 / sum).toFloat()
                for (t in 0 until c) wts[off + t] *= inv
            } else {
                wts[off] = 1f
                c = 1
            }
            start[i] = j0
            count[i] = c
        }
        return Kernel1D(stride, start, count, wts)
    }

    private fun linearKernel(srcN: Int, dstN: Int, scale: Double): Kernel1D {
        val start = IntArray(dstN)
        val count = IntArray(dstN)
        val wts = FloatArray(dstN * 2)
        for (i in 0 until dstN) {
            // Pixel-centre mapping. The naive `i * scale` shifts the image by half a destination
            // pixel, which is invisible on a photograph and very visible as a drift when a mask and
            // its grey source are resized separately and then compared.
            val pos = (i + 0.5) * scale - 0.5
            var j0 = floor(pos).toInt()
            var t = (pos - j0).toFloat()
            if (j0 < 0) {
                j0 = 0
                t = 0f
            }
            if (j0 >= srcN - 1) {
                j0 = srcN - 1
                t = 0f
            }
            val off = i * 2
            if (t == 0f) {
                wts[off] = 1f
                wts[off + 1] = 0f
                count[i] = 1
            } else {
                wts[off] = 1f - t
                wts[off + 1] = t
                count[i] = 2
            }
            start[i] = j0
        }
        return Kernel1D(2, start, count, wts)
    }

    private fun resizeGray(src: GrayF, dstW: Int, dstH: Int): GrayF {
        val srcW = src.width
        val s = src.data
        val hk = buildKernel(srcW, dstW)
        val vk = buildKernel(src.height, dstH)
        val out = FloatArray(dstW * dstH)
        // Horizontally-resampled source rows are cached in a ring of exactly the vertical kernel's
        // width. A full intermediate image would be src.height * dstW floats — ~100 MB for a 12 MP
        // input — and destination rows only ever walk forward, so a ring is both smaller and enough.
        val ringSize = vk.stride
        // Split over destination rows, each share with **its own ring**. Sharing one would be wrong
        // twice: it is written by one thread while another reads it, and even without the race two
        // shares walking different parts of the image would evict each other on every row and turn the
        // cache into an unconditional recompute. Per share it stays a cache and the answer is unchanged
        // — a ring is only ever a memo of `hk` applied to source row `sy`, so a hit and a miss produce
        // the same float, and the accumulation over `k` runs in the same order either way.
        Parallel.rows(dstH, Parallel.ROWS_KERNEL) { fromY, toY ->
            val ring = FloatArray(ringSize * dstW)
            val ringRow = IntArray(ringSize)
            java.util.Arrays.fill(ringRow, -1)
            for (dy in fromY until toY) {
                val vs = vk.start[dy]
                val vc = vk.count[dy]
                val vo = dy * vk.stride
                val outBase = dy * dstW
                for (k in 0 until vc) {
                    val sy = vs + k
                    val slot = sy % ringSize
                    val rb = slot * dstW
                    if (ringRow[slot] != sy) {
                        ringRow[slot] = sy
                        val srcBase = sy * srcW
                        for (dx in 0 until dstW) {
                            val hc = hk.count[dx]
                            val ho = dx * hk.stride
                            var acc = 0f
                            var t = 0
                            var idx = srcBase + hk.start[dx]
                            while (t < hc) {
                                acc += hk.w[ho + t] * s[idx]
                                t++
                                idx++
                            }
                            ring[rb + dx] = acc
                        }
                    }
                    val wgt = vk.w[vo + k]
                    for (dx in 0 until dstW) out[outBase + dx] += wgt * ring[rb + dx]
                }
            }
        }
        return GrayF(dstW, dstH, out)
    }

    private fun resizeRgba(src: RgbaImage, dstW: Int, dstH: Int): RgbaImage {
        val srcW = src.width
        val p = src.pixels
        val hk = buildKernel(srcW, dstW)
        val vk = buildKernel(src.height, dstH)
        val out = IntArray(dstW * dstH)
        val ringSize = vk.stride
        // Per-share ring **and** per-share row accumulator, for the reason spelled out in [resizeGray];
        // `acc` is the more dangerous of the two because it is not a cache — it is the destination row
        // being built, so two shares sharing it would blend each other's rows together.
        Parallel.rows(dstH, Parallel.ROWS_KERNEL) { fromY, toY ->
            val ring = FloatArray(ringSize * dstW * 4)
            val ringRow = IntArray(ringSize)
            java.util.Arrays.fill(ringRow, -1)
            val acc = FloatArray(dstW * 4)
            for (dy in fromY until toY) {
                java.util.Arrays.fill(acc, 0f)
                val vs = vk.start[dy]
                val vc = vk.count[dy]
                val vo = dy * vk.stride
                for (k in 0 until vc) {
                    val sy = vs + k
                    val slot = sy % ringSize
                    val rb = slot * dstW * 4
                    if (ringRow[slot] != sy) {
                        ringRow[slot] = sy
                        val srcBase = sy * srcW
                        for (dx in 0 until dstW) {
                            val hc = hk.count[dx]
                            val ho = dx * hk.stride
                            var aA = 0f
                            var aR = 0f
                            var aG = 0f
                            var aB = 0f
                            var t = 0
                            var idx = srcBase + hk.start[dx]
                            while (t < hc) {
                                val wv = hk.w[ho + t]
                                val px = p[idx]
                                aA += wv * ((px ushr 24) and 0xFF)
                                aR += wv * ((px ushr 16) and 0xFF)
                                aG += wv * ((px ushr 8) and 0xFF)
                                aB += wv * (px and 0xFF)
                                t++
                                idx++
                            }
                            val o = rb + dx * 4
                            ring[o] = aA
                            ring[o + 1] = aR
                            ring[o + 2] = aG
                            ring[o + 3] = aB
                        }
                    }
                    val wgt = vk.w[vo + k]
                    val n4 = dstW * 4
                    for (t in 0 until n4) acc[t] += wgt * ring[rb + t]
                }
                val outBase = dy * dstW
                for (dx in 0 until dstW) {
                    val o = dx * 4
                    val a = Px.clamp((acc[o] + 0.5f).toInt(), 0, 255)
                    val r = Px.clamp((acc[o + 1] + 0.5f).toInt(), 0, 255)
                    val g = Px.clamp((acc[o + 2] + 0.5f).toInt(), 0, 255)
                    val b = Px.clamp((acc[o + 3] + 0.5f).toInt(), 0, 255)
                    out[outBase + dx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return RgbaImage(dstW, dstH, out)
    }
}
