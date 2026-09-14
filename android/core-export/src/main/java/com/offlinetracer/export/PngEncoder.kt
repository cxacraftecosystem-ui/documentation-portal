package com.offlinetracer.export

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Px
import com.offlinetracer.imaging.RgbaImage
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToInt

/**
 * A complete PNG encoder in pure Kotlin — no `android.graphics.Bitmap`, no ImageIO, no NDK.
 *
 * The engine is a plain Kotlin/JVM library so that the whole pipeline is unit-testable without an
 * emulator; an encoder that needed `Bitmap.compress` would drag the Android runtime into every
 * test. `java.util.zip` is JDK core and present on ART, so DEFLATE and CRC-32 come from there
 * rather than being reimplemented.
 *
 * Two details are the classic ways to get PNG wrong and both are handled here:
 *  - **every multi-byte integer in the format is big-endian**, including chunk lengths, the IHDR
 *    dimensions and the trailing CRC — a little-endian slip produces a file that some decoders
 *    open and others reject, which is far harder to diagnose than a file nothing opens;
 *  - the per-scanline filter is *chosen*, not fixed. Filter 0 alone is legal and simple, but on
 *    line art — long runs of identical pixels with occasional 1px transitions — Paeth and Up
 *    roughly halve the compressed size for a few adds per byte.
 */
object PngEncoder {

    private const val COLOUR_TYPE_GRAY = 0
    private const val COLOUR_TYPE_RGBA = 6

    private const val FILTER_NONE = 0
    private const val FILTER_SUB = 1
    private const val FILTER_UP = 2
    private const val FILTER_AVERAGE = 3
    private const val FILTER_PAETH = 4

    private val EMPTY = ByteArray(0)

    /**
     * The fixed 8-byte signature. The 0x89 high bit catches 7-bit-clean transfers and the
     * CR-LF / LF pair catches a transfer that "helpfully" translated line endings.
     */
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
    )

    /**
     * Encodes [src] as an 8-bit RGBA PNG (colour type 6), preserving alpha exactly.
     *
     * @param dpi when positive, writes a `pHYs` chunk so print software picks up the physical
     *   size; 0 omits the chunk entirely rather than asserting a resolution nobody supplied.
     * @return the complete PNG file bytes.
     */
    fun encode(src: RgbaImage, dpi: Int = 0): ByteArray {
        val idat = deflateRgba(src)
        val out = ByteArrayOutputStream(idat.size + 128)
        out.write(SIGNATURE)
        writeIhdr(out, src.width, src.height, COLOUR_TYPE_RGBA)
        if (dpi > 0) writePhys(out, dpi)
        writeChunk(out, "IDAT", idat)
        writeChunk(out, "IEND", EMPTY)
        return out.toByteArray()
    }

    /**
     * Encodes [src] as an 8-bit greyscale PNG (colour type 0). Values are clamped to 0..1 and
     * rounded through [Px.toByte255], so an un-normalised intermediate buffer exports as a
     * clipped image rather than as wrapped-around noise.
     *
     * @param dpi when positive, writes a `pHYs` chunk; 0 omits it.
     * @return the complete PNG file bytes.
     */
    fun encodeGray(src: GrayF, dpi: Int = 0): ByteArray {
        val idat = deflateGray(src)
        val out = ByteArrayOutputStream(idat.size + 128)
        out.write(SIGNATURE)
        writeIhdr(out, src.width, src.height, COLOUR_TYPE_GRAY)
        if (dpi > 0) writePhys(out, dpi)
        writeChunk(out, "IDAT", idat)
        writeChunk(out, "IEND", EMPTY)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- image data

    private fun deflateRgba(src: RgbaImage): ByteArray {
        val w = src.width
        val h = src.height
        val stride = w * 4
        val cur = ByteArray(stride)
        val filter = RowFilter(stride, 4)
        val sink = ByteArrayOutputStream(1 shl 16)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val dos = DeflaterOutputStream(sink, deflater, 1 shl 16)
        val px = src.pixels
        var y = 0
        while (y < h) {
            var i = y * w
            var o = 0
            var x = 0
            while (x < w) {
                val argb = px[i]
                cur[o] = ((argb ushr 16) and 0xFF).toByte()
                cur[o + 1] = ((argb ushr 8) and 0xFF).toByte()
                cur[o + 2] = (argb and 0xFF).toByte()
                cur[o + 3] = ((argb ushr 24) and 0xFF).toByte()
                o += 4
                i++
                x++
            }
            filter.emit(cur, dos)
            y++
        }
        dos.finish()
        dos.flush()
        deflater.end()
        return sink.toByteArray()
    }

    private fun deflateGray(src: GrayF): ByteArray {
        val w = src.width
        val h = src.height
        val cur = ByteArray(w)
        val filter = RowFilter(w, 1)
        val sink = ByteArrayOutputStream(1 shl 16)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val dos = DeflaterOutputStream(sink, deflater, 1 shl 16)
        val data = src.data
        var y = 0
        while (y < h) {
            var i = y * w
            var x = 0
            while (x < w) {
                cur[x] = Px.toByte255(data[i]).toByte()
                i++
                x++
            }
            filter.emit(cur, dos)
            y++
        }
        dos.finish()
        dos.flush()
        deflater.end()
        return sink.toByteArray()
    }

    /**
     * Per-scanline filter selection. Holds the previous raw row and one scratch buffer per
     * candidate filter so the encoder allocates once per image, not once per row.
     */
    private class RowFilter(private val stride: Int, private val bpp: Int) {
        private val prev = ByteArray(stride)
        private val sub = ByteArray(stride)
        private val up = ByteArray(stride)
        private val avg = ByteArray(stride)
        private val pae = ByteArray(stride)

        fun emit(cur: ByteArray, dos: DeflaterOutputStream) {
            val n = stride
            val b = bpp

            var i = 0
            while (i < b && i < n) {
                sub[i] = cur[i]
                i++
            }
            while (i < n) {
                sub[i] = (cur[i] - cur[i - b]).toByte()
                i++
            }

            i = 0
            while (i < n) {
                up[i] = (cur[i] - prev[i]).toByte()
                i++
            }

            i = 0
            while (i < n) {
                val left = if (i >= b) cur[i - b].toInt() and 0xFF else 0
                val above = prev[i].toInt() and 0xFF
                // (left + above) shr 1 is floor of the average; both operands are already
                // non-negative so the shift and an integer divide agree.
                avg[i] = ((cur[i].toInt() and 0xFF) - ((left + above) shr 1)).toByte()
                i++
            }

            i = 0
            while (i < n) {
                val a = if (i >= b) cur[i - b].toInt() and 0xFF else 0
                val bb = prev[i].toInt() and 0xFF
                val c = if (i >= b) prev[i - b].toInt() and 0xFF else 0
                val p = a + bb - c
                var pa = p - a
                if (pa < 0) pa = -pa
                var pb = p - bb
                if (pb < 0) pb = -pb
                var pc = p - c
                if (pc < 0) pc = -pc
                val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) bb else c
                pae[i] = ((cur[i].toInt() and 0xFF) - pred).toByte()
                i++
            }

            // Minimum sum of absolute *signed* byte values: the heuristic the PNG spec itself
            // recommends. It is not optimal, but it is one pass and it picks Up on flat artwork
            // and Paeth on gradients, which is where the whole saving comes from.
            var bestType = FILTER_NONE
            var bestScore = score(cur, n)
            var s = score(sub, n)
            if (s < bestScore) {
                bestScore = s
                bestType = FILTER_SUB
            }
            s = score(up, n)
            if (s < bestScore) {
                bestScore = s
                bestType = FILTER_UP
            }
            s = score(avg, n)
            if (s < bestScore) {
                bestScore = s
                bestType = FILTER_AVERAGE
            }
            s = score(pae, n)
            if (s < bestScore) {
                bestScore = s
                bestType = FILTER_PAETH
            }

            dos.write(bestType)
            when (bestType) {
                FILTER_NONE -> dos.write(cur, 0, n)
                FILTER_SUB -> dos.write(sub, 0, n)
                FILTER_UP -> dos.write(up, 0, n)
                FILTER_AVERAGE -> dos.write(avg, 0, n)
                else -> dos.write(pae, 0, n)
            }
            System.arraycopy(cur, 0, prev, 0, n)
        }

        private fun score(a: ByteArray, n: Int): Long {
            var sum = 0L
            var i = 0
            while (i < n) {
                val v = a[i].toInt()
                sum += if (v < 0) (-v).toLong() else v.toLong()
                i++
            }
            return sum
        }
    }

    // ---------------------------------------------------------------- chunks

    private fun writeIhdr(out: ByteArrayOutputStream, w: Int, h: Int, colourType: Int) {
        val d = ByteArray(13)
        putBe32(d, 0, w)
        putBe32(d, 4, h)
        d[8] = 8               // bit depth
        d[9] = colourType.toByte()
        d[10] = 0              // compression method: DEFLATE, the only legal value
        d[11] = 0              // filter method: adaptive, the only legal value
        d[12] = 0              // interlace: none
        writeChunk(out, "IHDR", d)
    }

    private fun writePhys(out: ByteArrayOutputStream, dpi: Int) {
        // pHYs is in pixels per metre, so the conversion is dpi / 0.0254 and not dpi * anything.
        val ppm = (dpi / 0.0254).roundToInt()
        val d = ByteArray(9)
        putBe32(d, 0, ppm)
        putBe32(d, 4, ppm)
        d[8] = 1               // unit specifier: metre
        writeChunk(out, "pHYs", d)
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeBe32(out, data.size)
        val t = ByteArray(4)
        var i = 0
        while (i < 4) {
            t[i] = type[i].code.toByte()
            i++
        }
        out.write(t)
        out.write(data)
        // The CRC covers the type *and* the data but never the length field — a decoder that
        // includes the length rejects every well-formed PNG, and vice versa.
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        writeBe32(out, crc.value.toInt())
    }

    private fun writeBe32(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun putBe32(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }
}
