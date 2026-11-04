package com.offlinetracer.export

import com.offlinetracer.imaging.RgbaImage
import kotlin.math.roundToInt

object BmpEncoder {

    private const val FILE_HEADER = 14
    private const val V4_HEADER = 108
    private const val BI_BITFIELDS = 3
    private const val LCS_SRGB = 0x73524742

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

        b[0] = 'B'.code.toByte()
        b[1] = 'M'.code.toByte()
        putLe32(b, 2, total)
        putLe16(b, 6, 0)
        putLe16(b, 8, 0)
        putLe32(b, 10, offBits)

        val ppm = if (dpi > 0) (dpi / 0.0254).roundToInt() else 0
        val p = FILE_HEADER
        putLe32(b, p, V4_HEADER)
        putLe32(b, p + 4, w)
        putLe32(b, p + 8, h)
        putLe16(b, p + 12, 1)
        putLe16(b, p + 14, 32)
        putLe32(b, p + 16, BI_BITFIELDS)
        putLe32(b, p + 20, data)
        putLe32(b, p + 24, ppm)
        putLe32(b, p + 28, ppm)
        putLe32(b, p + 32, 0)
        putLe32(b, p + 36, 0)
        putLe32(b, p + 40, 0x00FF0000)
        putLe32(b, p + 44, 0x0000FF00)
        putLe32(b, p + 48, 0x000000FF)
        putLe32(b, p + 52, 0xFF000000.toInt())
        putLe32(b, p + 56, LCS_SRGB)

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
