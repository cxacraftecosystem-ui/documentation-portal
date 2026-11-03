package com.offlinetracer.imaging

import kotlin.math.pow
import kotlin.math.sqrt

enum class Channel { RED, GREEN, BLUE, ALPHA, LUMA, MAX, MIN, SATURATION, VALUE }

object Color {

    private const val INV255 = 1f / 255f

    private const val INV255D = 1.0 / 255.0

    private val SRGB_TO_LINEAR = FloatArray(256) { linearize(it * INV255) }

    private const val XN = 0.95047f
    private const val YN = 1.00000f
    private const val ZN = 1.08883f

    private const val LAB_EPS = 0.008856452f
    private const val LAB_K = 7.787037f
    private const val LAB_OFF = 0.13793103f

    fun toGray(src: RgbaImage): GrayF {
        val n = src.size
        val p = src.pixels
        val out = FloatArray(n)
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) {
                val v = p[i]
                val r = (v ushr 16) and 0xFF
                val g = (v ushr 8) and 0xFF
                val b = v and 0xFF
                out[i] = ((0.299 * r + 0.587 * g + 0.114 * b) * INV255D).toFloat()
            }
        }
        return GrayF(src.width, src.height, out)
    }

    fun toGrayLinear(src: RgbaImage): GrayF {
        val n = src.size
        val p = src.pixels
        val out = FloatArray(n)
        val lut = SRGB_TO_LINEAR
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) {
                val v = p[i]
                val r = lut[(v ushr 16) and 0xFF]
                val g = lut[(v ushr 8) and 0xFF]
                val b = lut[v and 0xFF]
                out[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
            }
        }
        return GrayF(src.width, src.height, out)
    }

    fun toRgba(src: GrayF, opaque: Boolean = true): RgbaImage {
        val n = src.size
        val s = src.data
        val out = IntArray(n)
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) {
                val g = Px.toByte255(s[i])
                val a = if (opaque) 255 else g
                out[i] = (a shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return RgbaImage(src.width, src.height, out)
    }

    fun channel(src: RgbaImage, channel: Channel): GrayF {
        val n = src.size
        val p = src.pixels
        val out = FloatArray(n)
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) {
                val v = p[i]
                val a = (v ushr 24) and 0xFF
                val r = (v ushr 16) and 0xFF
                val g = (v ushr 8) and 0xFF
                val b = v and 0xFF
                out[i] = when (channel) {
                    Channel.RED -> r * INV255
                    Channel.GREEN -> g * INV255
                    Channel.BLUE -> b * INV255
                    Channel.ALPHA -> a * INV255
                    Channel.LUMA -> (0.299f * r + 0.587f * g + 0.114f * b) * INV255
                    Channel.MAX, Channel.VALUE -> {
                        val m = if (r > g) (if (r > b) r else b) else (if (g > b) g else b)
                        m * INV255
                    }
                    Channel.MIN -> {
                        val m = if (r < g) (if (r < b) r else b) else (if (g < b) g else b)
                        m * INV255
                    }
                    Channel.SATURATION -> {
                        val mx = if (r > g) (if (r > b) r else b) else (if (g > b) g else b)
                        val mn = if (r < g) (if (r < b) r else b) else (if (g < b) g else b)
                        if (mx == 0) 0f else (mx - mn).toFloat() / mx
                    }
                }
            }
        }
        return GrayF(src.width, src.height, out)
    }

    fun alphaOf(src: RgbaImage): GrayF {
        val n = src.size
        val p = src.pixels
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = ((p[i] ushr 24) and 0xFF) * INV255
        return GrayF(src.width, src.height, out)
    }

    fun withAlpha(src: RgbaImage, alpha: GrayF): RgbaImage {
        require(src.width == alpha.width && src.height == alpha.height) {
            "withAlpha(): ${src.width}x${src.height} vs ${alpha.width}x${alpha.height}"
        }
        val n = src.size
        val p = src.pixels
        val a = alpha.data
        val out = IntArray(n)
        for (i in 0 until n) out[i] = (p[i] and 0x00FFFFFF) or (Px.toByte255(a[i]) shl 24)
        return RgbaImage(src.width, src.height, out)
    }

    fun linearize(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    fun delinearize(c: Float): Float =
        if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f

    fun srgbToLab(r: Float, g: Float, b: Float, out: FloatArray) {
        require(out.size >= 3) { "srgbToLab(): out must hold 3 floats, got ${out.size}" }
        val rl = linearize(r)
        val gl = linearize(g)
        val bl = linearize(b)
        labFromLinear(rl, gl, bl, out, 0, 1, 2)
    }

    fun labDistance(l1: Float, a1: Float, b1: Float, l2: Float, a2: Float, b2: Float): Float {
        val dl = l1 - l2
        val da = a1 - a2
        val db = b1 - b2
        return sqrt(dl * dl + da * da + db * db)
    }

    fun toLabPlanes(src: RgbaImage): Array<FloatArray> {
        val n = src.size
        val p = src.pixels
        val l = FloatArray(n)
        val a = FloatArray(n)
        val b = FloatArray(n)
        val lut = SRGB_TO_LINEAR
        Parallel.chunks(n, Parallel.PIXELS_MAP) { from, to ->
            val tmp = FloatArray(3)
            for (i in from until to) {
                val v = p[i]
                labFromLinear(
                    lut[(v ushr 16) and 0xFF],
                    lut[(v ushr 8) and 0xFF],
                    lut[v and 0xFF],
                    tmp, 0, 1, 2,
                )
                l[i] = tmp[0]
                a[i] = tmp[1]
                b[i] = tmp[2]
            }
        }
        return arrayOf(l, a, b)
    }

    private fun labFromLinear(
        rl: Float, gl: Float, bl: Float,
        out: FloatArray, li: Int, ai: Int, bi: Int,
    ) {
        val x = (0.4124564f * rl + 0.3575761f * gl + 0.1804375f * bl) / XN
        val y = (0.2126729f * rl + 0.7151522f * gl + 0.0721750f * bl) / YN
        val z = (0.0193339f * rl + 0.1191920f * gl + 0.9503041f * bl) / ZN
        val fx = labF(x)
        val fy = labF(y)
        val fz = labF(z)
        out[li] = 116f * fy - 16f
        out[ai] = 500f * (fx - fy)
        out[bi] = 200f * (fy - fz)
    }

    private fun labF(t: Float): Float =
        if (t > LAB_EPS) Math.cbrt(t.toDouble()).toFloat() else LAB_K * t + LAB_OFF
}
