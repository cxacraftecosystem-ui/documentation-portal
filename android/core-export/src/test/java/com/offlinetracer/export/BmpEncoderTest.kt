package com.offlinetracer.export

import com.offlinetracer.imaging.RgbaImage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BMP is verified by reading the header fields back and walking the pixel array in the order the
 * header claims. The two things that go wrong are the row order (BMP stores bottom-up) and the
 * channel order (the bit masks describe a little-endian DWORD, so the bytes on disk are BGRA).
 */
class BmpEncoderTest {

    @Test
    fun headerDescribesA32BitV4Bitmap() {
        val bmp = BmpEncoder.encode(Fixtures.image(7, 5))
        assertEquals('B'.code, bmp[0].toInt() and 0xFF)
        assertEquals('M'.code, bmp[1].toInt() and 0xFF)
        assertEquals(bmp.size, le32(bmp, 2), "file size field must match the actual length")
        assertEquals(122, le32(bmp, 10), "pixel data starts after the 14 + 108 byte headers")
        assertEquals(108, le32(bmp, 14), "BITMAPV4HEADER size")
        assertEquals(7, le32(bmp, 18), "width")
        assertEquals(5, le32(bmp, 22), "height is positive, meaning bottom-up rows")
        assertEquals(1, le16(bmp, 26), "planes")
        assertEquals(32, le16(bmp, 28), "bits per pixel")
        assertEquals(3, le32(bmp, 30), "BI_BITFIELDS, without which alpha is undefined")
        assertEquals(7 * 5 * 4, le32(bmp, 34), "image byte count")
        assertEquals(0x00FF0000, le32(bmp, 54), "red mask")
        assertEquals(0x0000FF00, le32(bmp, 58), "green mask")
        assertEquals(0x000000FF, le32(bmp, 62), "blue mask")
        assertEquals(0xFF000000.toInt(), le32(bmp, 66), "alpha mask")
        assertEquals(0x73524742, le32(bmp, 70), "LCS_sRGB colour space")
        assertEquals(122 + 7 * 5 * 4, bmp.size)
    }

    @Test
    fun rowsAreStoredBottomUpWithBgraBytes() {
        val src = Fixtures.image(11, 6)
        val bmp = BmpEncoder.encode(src)
        val off = le32(bmp, 10)
        val w = 11
        val h = 6
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Row y of the image is stored at row (h - 1 - y) of the file.
                val o = off + ((h - 1 - y) * w + x) * 4
                val argb = src[x, y]
                assertEquals(argb and 0xFF, bmp[o].toInt() and 0xFF, "B at $x,$y")
                assertEquals((argb ushr 8) and 0xFF, bmp[o + 1].toInt() and 0xFF, "G at $x,$y")
                assertEquals((argb ushr 16) and 0xFF, bmp[o + 2].toInt() and 0xFF, "R at $x,$y")
                assertEquals((argb ushr 24) and 0xFF, bmp[o + 3].toInt() and 0xFF, "A at $x,$y")
            }
        }
    }

    @Test
    fun resolutionIsOmittedUnlessRequested() {
        val plain = BmpEncoder.encode(Fixtures.image(4, 4))
        assertEquals(0, le32(plain, 38), "X pixels-per-metre stays unset")
        assertEquals(0, le32(plain, 42), "Y pixels-per-metre stays unset")
        val dotted = BmpEncoder.encode(Fixtures.image(4, 4), 300)
        assertEquals(11811, le32(dotted, 38))
        assertEquals(11811, le32(dotted, 42))
    }

    @Test
    fun singlePixelImageIsValid() {
        val bmp = BmpEncoder.encode(RgbaImage(1, 1, intArrayOf(0x11223344)))
        assertEquals(126, bmp.size)
        val off = le32(bmp, 10)
        assertEquals(0x44, bmp[off].toInt() and 0xFF)
        assertEquals(0x33, bmp[off + 1].toInt() and 0xFF)
        assertEquals(0x22, bmp[off + 2].toInt() and 0xFF)
        assertEquals(0x11, bmp[off + 3].toInt() and 0xFF)
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
