package com.offlinetracer.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The four buffer types every stage of the engine speaks.
 *
 * Deliberate choices, because getting these wrong is expensive later:
 *
 *  - [GrayF] is the working type for anything analytic (filters, gradients, DoG, tensors). It is
 *    **not** clamped to 0..1: a gradient magnitude, a Laplacian or a DoG response is legitimately
 *    signed and legitimately larger than 1, and clamping intermediate stages is how detail dies.
 *    Only functions that explicitly say so normalise.
 *  - [Mask] is a `BooleanArray`, not a bitset. Boolean indexing is the single hottest operation in
 *    thinning and connected components; a bitset saves 7/8 of the memory and costs a shift and a
 *    mask on every access, which measured slower on ART for the sizes we work at.
 *  - [RgbaImage] packs ARGB into an `IntArray` in exactly the layout `Bitmap.getPixels` produces,
 *    so the Android bridge is a straight array copy with no per-pixel conversion.
 *  - Every buffer is row-major, index = `y * width + x`, with no stride/padding. Anything that
 *    needs a sub-rectangle copies; there are no views. Views were tried and every off-by-one bug
 *    in the first pipeline traced back to one.
 */

/** Row-major single-channel float image. Values are conventionally 0..1 but are never clamped. */
class GrayF(@JvmField val width: Int, @JvmField val height: Int, @JvmField val data: FloatArray) {

    init {
        require(width > 0 && height > 0) { "GrayF must be non-empty, got ${width}x$height" }
        require(data.size == width * height) {
            "GrayF data size ${data.size} does not match ${width}x$height = ${width * height}"
        }
    }

    constructor(width: Int, height: Int) : this(width, height, FloatArray(width * height))

    val size: Int get() = data.size

    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    operator fun set(x: Int, y: Int, v: Float) {
        data[y * width + x] = v
    }

    /** Read with coordinates clamped to the edge — the border policy the whole engine uses. */
    fun clamped(x: Int, y: Int): Float {
        val cx = if (x < 0) 0 else if (x >= width) width - 1 else x
        val cy = if (y < 0) 0 else if (y >= height) height - 1 else y
        return data[cy * width + cx]
    }

    /** Bilinear sample in pixel coordinates, edge-clamped. */
    fun sampleBilinear(fx: Float, fy: Float): Float {
        val x0 = kotlin.math.floor(fx.toDouble()).toInt()
        val y0 = kotlin.math.floor(fy.toDouble()).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val v00 = clamped(x0, y0)
        val v10 = clamped(x0 + 1, y0)
        val v01 = clamped(x0, y0 + 1)
        val v11 = clamped(x0 + 1, y0 + 1)
        val a = v00 + (v10 - v00) * tx
        val b = v01 + (v11 - v01) * tx
        return a + (b - a) * ty
    }

    fun copy(): GrayF = GrayF(width, height, data.copyOf())

    fun fill(v: Float): GrayF {
        java.util.Arrays.fill(data, v)
        return this
    }

    /** Same dimensions, zeroed. Used everywhere a stage needs a destination buffer. */
    fun blank(): GrayF = GrayF(width, height)

    fun sameSizeAs(other: GrayF): Boolean = width == other.width && height == other.height

    /** Smallest and largest value present. Returns `0f to 1f` semantics only if that is the truth. */
    fun range(): Pair<Float, Float> {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (v in data) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (lo > hi) return 0f to 0f
        return lo to hi
    }

    companion object {
        /** Allocate matching [other]'s dimensions. */
        fun like(other: GrayF): GrayF = GrayF(other.width, other.height)
    }
}

/** Row-major binary image. `true` means "ink" / foreground everywhere in this engine. */
class Mask(@JvmField val width: Int, @JvmField val height: Int, @JvmField val data: BooleanArray) {

    init {
        require(width > 0 && height > 0) { "Mask must be non-empty, got ${width}x$height" }
        require(data.size == width * height) {
            "Mask data size ${data.size} does not match ${width}x$height = ${width * height}"
        }
    }

    constructor(width: Int, height: Int) : this(width, height, BooleanArray(width * height))

    val size: Int get() = data.size

    operator fun get(x: Int, y: Int): Boolean = data[y * width + x]

    operator fun set(x: Int, y: Int, v: Boolean) {
        data[y * width + x] = v
    }

    /**
     * Out-of-bounds reads as background. This is the *opposite* of [GrayF.clamped] and it is
     * deliberate: replicating the edge would make a shape touching the border look closed to the
     * contour tracer, and thinning would grow a skeleton along the frame of every photograph.
     */
    fun safe(x: Int, y: Int): Boolean =
        if (x < 0 || y < 0 || x >= width || y >= height) false else data[y * width + x]

    fun copy(): Mask = Mask(width, height, data.copyOf())

    fun blank(): Mask = Mask(width, height)

    fun countTrue(): Int {
        var n = 0
        for (b in data) if (b) n++
        return n
    }

    fun fill(v: Boolean): Mask {
        java.util.Arrays.fill(data, v)
        return this
    }

    fun invert(): Mask {
        val out = BooleanArray(data.size)
        for (i in data.indices) out[i] = !data[i]
        return Mask(width, height, out)
    }

    fun and(other: Mask): Mask {
        require(width == other.width && height == other.height) { "and(): size mismatch" }
        val out = BooleanArray(data.size)
        for (i in data.indices) out[i] = data[i] && other.data[i]
        return Mask(width, height, out)
    }

    fun or(other: Mask): Mask {
        require(width == other.width && height == other.height) { "or(): size mismatch" }
        val out = BooleanArray(data.size)
        for (i in data.indices) out[i] = data[i] || other.data[i]
        return Mask(width, height, out)
    }

    /** `this AND NOT other` — used to subtract a matte or a protected region. */
    fun subtract(other: Mask): Mask {
        require(width == other.width && height == other.height) { "subtract(): size mismatch" }
        val out = BooleanArray(data.size)
        for (i in data.indices) out[i] = data[i] && !other.data[i]
        return Mask(width, height, out)
    }

    fun toGray(): GrayF {
        val out = FloatArray(data.size)
        for (i in data.indices) out[i] = if (data[i]) 1f else 0f
        return GrayF(width, height, out)
    }

    companion object {
        fun like(other: Mask): Mask = Mask(other.width, other.height)
        fun like(other: GrayF): Mask = Mask(other.width, other.height)
    }
}

/**
 * Packed ARGB image, byte-identical in layout to `Bitmap.getPixels(...)` / `Bitmap.setPixels(...)`
 * (`Bitmap.Config.ARGB_8888`), so the Android bridge never walks pixels.
 */
class RgbaImage(@JvmField val width: Int, @JvmField val height: Int, @JvmField val pixels: IntArray) {

    init {
        require(width > 0 && height > 0) { "RgbaImage must be non-empty, got ${width}x$height" }
        require(pixels.size == width * height) {
            "RgbaImage data size ${pixels.size} does not match ${width}x$height = ${width * height}"
        }
    }

    constructor(width: Int, height: Int) : this(width, height, IntArray(width * height))

    val size: Int get() = pixels.size

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    operator fun set(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    fun copy(): RgbaImage = RgbaImage(width, height, pixels.copyOf())

    fun fill(argb: Int): RgbaImage {
        java.util.Arrays.fill(pixels, argb)
        return this
    }

    companion object {
        fun like(other: RgbaImage): RgbaImage = RgbaImage(other.width, other.height)

        @JvmStatic
        fun argb(a: Int, r: Int, g: Int, b: Int): Int =
            ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

        @JvmStatic fun alphaOf(argb: Int): Int = (argb ushr 24) and 0xFF

        @JvmStatic fun redOf(argb: Int): Int = (argb ushr 16) and 0xFF

        @JvmStatic fun greenOf(argb: Int): Int = (argb ushr 8) and 0xFF

        @JvmStatic fun blueOf(argb: Int): Int = argb and 0xFF
    }
}

/** Clamp helpers used across the engine; centralised so the rounding rule is one rule. */
object Px {
    fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))

    fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))

    fun clamp(v: Double, lo: Double, hi: Double): Double = max(lo, min(hi, v))

    /**
     * 0..1 float to 0..255 byte. `roundToInt` and not `(v * 255).toInt()`: truncation makes 1.0f
     * the only value that reaches 255 and biases every export half a level dark.
     */
    fun toByte255(v: Float): Int = clamp((clamp01(v) * 255f).roundToInt(), 0, 255)
}
