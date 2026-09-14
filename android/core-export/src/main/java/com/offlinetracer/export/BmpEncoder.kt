package com.offlinetracer.export

import com.offlinetracer.imaging.RgbaImage
import kotlin.math.roundToInt

/**
 * 32-bit BMP writer.
 *
 * Uses a **BITMAPV4HEADER** rather than the far more common 40-byte BITMAPINFOHEADER, because the
 * V3 header has no way to say "these 8 bits are alpha". A 32-bit V3 bitmap has an undefined fourth
 * byte, and roughly half of the software that reads it treats a traced line drawing on a
 * transparent background as an opaque black rectangle. V4 declares the channel masks and an sRGB
 * colour space explicitly, so alpha survives the round trip.
 *
 * Everything in BMP is little-endian and rows are stored **bottom-up** when the height is
 * positive, which is the other detail that silently produces a vertically mirrored export.
 */
object BmpEncoder {

    private const val FILE_HEADER = 14
    private const val V4_HEADER = 108
    private const val BI_BITFIELDS = 3
    private const val LCS_SRGB = 0x73524742   // 'sRGB' as a big-endian FourCC, stored as a DWORD

    /**
     * Encodes [src] as a 32-bit BGRA bottom-up BMP with a BITMAPV4HEADER.
     *
     * @param dpi when positive, fills the pixels-per-metre fields; 0 leaves them zero, which is
     *   how BMP says "unspecified".
     * @return the complete BMP file bytes.
     */
    fun encode(src: RgbaImage, dpi: Int = 0): ByteArray {
        val w = src.width
        val h = src.height
        val dataSize = w.toLong() * h.toLong() * 4L
        val totalSize = dataSize + FILE_HEADER + V4_HEADER
        require(totalSize <= Int.MAX_VALUE) {
            "BMP is a 32-bit container: ${w}x$h needs $totalSize bytes, which no reader can index"
        }

        val data = dataSize.toInt()
        val offBits = FILE_HEADER + V4_HEADER
        val total = totalSize.toInt()
        val b = ByteArray(total)

        // BITMAPFILEHEADER
        b[0] = 'B'.code.toByte()
        b[1] = 'M'.code.toByte()
        putLe32(b, 2, total)
        putLe16(b, 6, 0)          // reserved
        putLe16(b, 8, 0)          // reserved
        putLe32(b, 10, offBits)

        // BITMAPV4HEADER
        val ppm = if (dpi > 0) (dpi / 0.0254).roundToInt() else 0
        val p = FILE_HEADER
        putLe32(b, p, V4_HEADER)
        putLe32(b, p + 4, w)
        putLe32(b, p + 8, h)      // positive height: bottom-up rows
        putLe16(b, p + 12, 1)     // planes
        putLe16(b, p + 14, 32)    // bits per pixel
        putLe32(b, p + 16, BI_BITFIELDS)
        putLe32(b, p + 20, data)
        putLe32(b, p + 24, ppm)
        putLe32(b, p + 28, ppm)
        putLe32(b, p + 32, 0)     // colours used
        putLe32(b, p + 36, 0)     // colours important
        putLe32(b, p + 40, 0x00FF0000)          // red mask
        putLe32(b, p + 44, 0x0000FF00)          // green mask
        putLe32(b, p + 48, 0x000000FF)          // blue mask
        putLe32(b, p + 52, 0xFF000000.toInt())  // alpha mask
        putLe32(b, p + 56, LCS_SRGB)
        // bV4Endpoints (36 bytes) and the three gamma DWORDs stay zero: they are ignored when the
        // colour space is LCS_sRGB, and writing anything else there is how a file ends up with a
        // colour cast in one viewer and not another.

        // Pixels. The masks above describe a little-endian DWORD, which puts the bytes on disk in
        // B, G, R, A order.
        val px = src.pixels
        var o = offBits
        var y = h - 1
        while (y >= 0) {
            var i = y * w
            var x = 0
            while (x < w) {
                val argb = px[i]
                b[o] = (argb and 0xFF).toByte()
                b[o + 1] = ((argb ushr 8) and 0xFF).toByte()
                b[o + 2] = ((argb ushr 16) and 0xFF).toByte()
                b[o + 3] = ((argb ushr 24) and 0xFF).toByte()
                o += 4
                i++
                x++
            }
            y--
        }
        return b
    }

    private fun putLe16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
