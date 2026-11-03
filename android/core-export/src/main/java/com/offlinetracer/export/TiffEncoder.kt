package com.offlinetracer.export

import com.offlinetracer.imaging.RgbaImage

object TiffEncoder {

    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5

    private const val HEADER_SIZE = 8
    private const val ENTRY_COUNT = 15
    private const val IFD_SIZE = 2 + 12 * ENTRY_COUNT + 4

    private const val SOFTWARE_NAME = "Offline Tracer"

    fun encode(src: RgbaImage, dpi: Int = 72): ByteArray {
        val w = src.width
        val h = src.height
        val dataSize = w.toLong() * h.toLong() * 4L

        val softwareLen = SOFTWARE_NAME.length + 1
        val softwarePadded = softwareLen + (softwareLen and 1)

        val stripOffset = HEADER_SIZE
        val ifdOffset = HEADER_SIZE + dataSize
        val bpsOffset = ifdOffset + IFD_SIZE
        val xResOffset = bpsOffset + 8
        val yResOffset = xResOffset + 8
        val softwareOffset = yResOffset + 8
        val totalSize = softwareOffset + softwarePadded

        require(totalSize <= Int.MAX_VALUE) {
            "Baseline TIFF offsets are 32-bit: ${w}x$h needs $totalSize bytes"
        }

        val b = ByteArray(totalSize.toInt())

        b[0] = 'I'.code.toByte()
        b[1] = 'I'.code.toByte()
        putLe16(b, 2, 42)
        putLe32(b, 4, ifdOffset.toInt())

        val px = src.pixels
        var o = stripOffset
        var i = 0
        val n = w * h
        while (i < n) {
            val argb = px[i]
            b[o] = ((argb ushr 16) and 0xFF).toByte()
            b[o + 1] = ((argb ushr 8) and 0xFF).toByte()
            b[o + 2] = (argb and 0xFF).toByte()
            b[o + 3] = ((argb ushr 24) and 0xFF).toByte()
            o += 4
            i++
        }

        var p = ifdOffset.toInt()
        putLe16(b, p, ENTRY_COUNT)
        p += 2
        p = entry(b, p, 256, TYPE_LONG, 1, w)
        p = entry(b, p, 257, TYPE_LONG, 1, h)
        p = entry(b, p, 258, TYPE_SHORT, 4, bpsOffset.toInt())
        p = entry(b, p, 259, TYPE_SHORT, 1, 1)
        p = entry(b, p, 262, TYPE_SHORT, 1, 2)
        p = entry(b, p, 273, TYPE_LONG, 1, stripOffset)
        p = entry(b, p, 277, TYPE_SHORT, 1, 4)
        p = entry(b, p, 278, TYPE_LONG, 1, h)
        p = entry(b, p, 279, TYPE_LONG, 1, dataSize.toInt())
        p = entry(b, p, 282, TYPE_RATIONAL, 1, xResOffset.toInt())
        p = entry(b, p, 283, TYPE_RATIONAL, 1, yResOffset.toInt())
        p = entry(b, p, 284, TYPE_SHORT, 1, 1)
        p = entry(b, p, 296, TYPE_SHORT, 1, 2)
        p = entry(b, p, 305, TYPE_ASCII, softwareLen, softwareOffset.toInt())
        p = entry(b, p, 338, TYPE_SHORT, 1, 2)
        putLe32(b, p, 0)

        var q = bpsOffset.toInt()
        putLe16(b, q, 8)
        putLe16(b, q + 2, 8)
        putLe16(b, q + 4, 8)
        putLe16(b, q + 6, 8)

        val res = if (dpi > 0) dpi else 72
        q = xResOffset.toInt()
        putLe32(b, q, res)
        putLe32(b, q + 4, 1)
        q = yResOffset.toInt()
        putLe32(b, q, res)
        putLe32(b, q + 4, 1)

        q = softwareOffset.toInt()
        var s = 0
        while (s < SOFTWARE_NAME.length) {
            b[q + s] = (SOFTWARE_NAME[s].code and 0xFF).toByte()
            s++
        }
        b[q + SOFTWARE_NAME.length] = 0

        return b
    }

    private fun entry(b: ByteArray, p: Int, tag: Int, type: Int, count: Int, value: Int): Int {
        putLe16(b, p, tag)
        putLe16(b, p + 2, type)
        putLe32(b, p + 4, count)
        if (type == TYPE_SHORT && count == 1) {
            putLe16(b, p + 8, value)
            putLe16(b, p + 10, 0)
        } else {
            putLe32(b, p + 8, value)
        }
        return p + 12
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
