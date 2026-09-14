package com.offlinetracer.imaging

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Channels [Color.channel] can extract from an [RgbaImage].
 *
 * Declared at file level rather than nested inside [Color] so every consumer can spell it exactly
 * as the API contract writes it (`Channel.SATURATION`) with no import and no qualifier — the same
 * reason [GradientOp] lives at file level next to `Convolve`.
 */
enum class Channel { RED, GREEN, BLUE, ALPHA, LUMA, MAX, MIN, SATURATION, VALUE }

/**
 * Colour space conversions.
 *
 * Two luminances exist on purpose. [toGray] is Rec.601 luma on the *gamma-encoded* channels, which
 * is what OpenCV's `COLOR_RGB2GRAY` computes, so every threshold and sigma in the literature
 * transfers to this engine unchanged. [toGrayLinear] is Rec.709 luminance on *linearised* channels
 * and is used only by matting, where alpha is a linear quantity and mixing gamma-encoded values
 * into it produces visibly wrong edges on a composite.
 */
object Color {

    private const val INV255 = 1f / 255f

    /**
     * The same reciprocal in double, for the one expression that is required to be bit-identical to
     * the TypeScript engine's — see [toGray].
     */
    private const val INV255D = 1.0 / 255.0

    /**
     * sRGB transfer applied to the 256 possible 8-bit channel values.
     *
     * Built once because [linearize] costs a `pow` per channel: on a 12 MP image the straight
     * evaluation is 36 million `pow` calls, which measured slower than the entire rest of the
     * preprocessing chain.
     */
    private val SRGB_TO_LINEAR = FloatArray(256) { linearize(it * INV255) }

    // sRGB D65 primaries -> XYZ, and the D65 white point the Lab conversion normalises against.
    private const val XN = 0.95047f
    private const val YN = 1.00000f
    private const val ZN = 1.08883f

    // f(t) split point (6/29)^3 and the linear-segment coefficients 1/(3*(6/29)^2) and 4/29.
    private const val LAB_EPS = 0.008856452f
    private const val LAB_K = 7.787037f
    private const val LAB_OFF = 0.13793103f

    /**
     * Rec.601 luma of every pixel, `Y = 0.299R + 0.587G + 0.114B` on gamma-encoded channels,
     * scaled to 0..1. Alpha is ignored (not multiplied in): a transparent pixel keeps its colour,
     * because the matting stage owns the decision of what transparency means.
     *
     * **The weighted sum is evaluated in double and rounded to Float exactly once**, which is what
     * JavaScript does for free and what the TypeScript engine therefore already did. Float weights
     * round three times over and land up to 2 ulp (1.2e-7) away: measured on the `gradient-blob`
     * fixture, 186 of its 432 pixels differed. That is invisible against §14's 1e-4 on this stage and it
     * is *not* invisible downstream — it was the entire remaining 5.9e-6 residual in `EdgeDog.xdog`,
     * whose error gain is about a thousand (§7.2). Computing it identically in both engines is what
     * takes that stage to bit-identity, and it costs one narrowing per pixel.
     */
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

    /**
     * Linear-light Rec.709 luminance, `Y = 0.2126R + 0.7152G + 0.0722B` on linearised channels.
     * The result is linear light in 0..1, deliberately *not* re-encoded to sRGB — the consumer
     * (matting) needs it linear.
     */
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

    /**
     * Grey to packed ARGB, values clamped and rounded to 0..255.
     *
     * With [opaque] false the grey doubles as the alpha channel, which is how an ink-density map or
     * a matte is previewed directly without a second buffer.
     */
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

    /**
     * One channel of [src] as 0..1 floats. [Channel.MAX]/[Channel.VALUE] are the HSV value,
     * [Channel.SATURATION] is the HSV saturation `(max-min)/max` (0 for black, which has no hue).
     */
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

    /** Alpha channel of [src] as 0..1 floats. */
    fun alphaOf(src: RgbaImage): GrayF {
        val n = src.size
        val p = src.pixels
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = ((p[i] ushr 24) and 0xFF) * INV255
        return GrayF(src.width, src.height, out)
    }

    /**
     * Copy of [src] with its alpha replaced by [alpha] (0..1, clamped). RGB is untouched, i.e. the
     * result is non-premultiplied. Requires matching dimensions.
     */
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

    /** sRGB transfer, gamma-encoded 0..1 to linear light. Values below the knee use the linear leg. */
    fun linearize(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** Inverse of [linearize]: linear light to gamma-encoded sRGB. */
    fun delinearize(c: Float): Float =
        if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f

    /**
     * sRGB (gamma-encoded 0..1) to CIELAB D65, written into [out] as `L, a, b`.
     * [out] must have room for 3 floats; it is reused by the caller so this allocates nothing.
     */
    fun srgbToLab(r: Float, g: Float, b: Float, out: FloatArray) {
        require(out.size >= 3) { "srgbToLab(): out must hold 3 floats, got ${out.size}" }
        val rl = linearize(r)
        val gl = linearize(g)
        val bl = linearize(b)
        labFromLinear(rl, gl, bl, out, 0, 1, 2)
    }

    /** Plain ΔE76 — Euclidean distance in Lab. */
    fun labDistance(l1: Float, a1: Float, b1: Float, l2: Float, a2: Float, b2: Float): Float {
        val dl = l1 - l2
        val da = a1 - a2
        val db = b1 - b2
        return sqrt(dl * dl + da * da + db * db)
    }

    /**
     * Whole image as three planar Lab channels, `[L, a, b]`, each `width*height` in row-major order.
     * Planar rather than interleaved because every consumer (flood fill, magic wand) reads one
     * neighbour's three components at a stride of 1 image, and planes keep those three reads
     * cache-friendly across the scan.
     */
    fun toLabPlanes(src: RgbaImage): Array<FloatArray> {
        val n = src.size
        val p = src.pixels
        val l = FloatArray(n)
        val a = FloatArray(n)
        val b = FloatArray(n)
        val lut = SRGB_TO_LINEAR
        // Three `cbrt`s and a 3×3 matrix per pixel — the most expensive colour conversion in the engine
        // and the one matting waits on. The `tmp` triple is allocated **per share**: it exists to keep
        // the conversion allocation-free, and one triple shared between threads would hand a pixel
        // another pixel's `a` and `b`.
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
