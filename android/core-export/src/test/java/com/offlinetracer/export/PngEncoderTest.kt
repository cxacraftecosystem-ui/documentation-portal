package com.offlinetracer.export

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Px
import com.offlinetracer.imaging.RgbaImage
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Verifies the PNG bytes by decoding them: the chunk CRCs are recomputed, the IDAT stream is
 * inflated and the scanline filters are undone. A test that only checks the signature would pass
 * for a file no decoder can read.
 */
class PngEncoderTest {

    @Test
    fun signatureIsExact() {
        val png = PngEncoder.encode(Fixtures.image(9, 7))
        val expected = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in expected.indices) {
            assertEquals(expected[i], png[i].toInt() and 0xFF, "signature byte $i")
        }
    }

    @Test
    fun everyChunkCrcValidates() {
        val chunks = parse(PngEncoder.encode(Fixtures.image(16, 11)))
        assertEquals("IHDR", chunks.first().type, "IHDR must be the first chunk")
        assertEquals("IEND", chunks.last().type, "IEND must be the last chunk")
        assertTrue(chunks.any { it.type == "IDAT" }, "there must be image data")
        assertEquals(0, chunks.last().data.size, "IEND carries no data")
    }

    @Test
    fun ihdrDescribesAnEightBitRgbaImage() {
        val ihdr = parse(PngEncoder.encode(Fixtures.image(13, 5))).first { it.type == "IHDR" }.data
        assertEquals(13, be32(ihdr, 0), "width is big-endian")
        assertEquals(5, be32(ihdr, 4), "height is big-endian")
        assertEquals(8, ihdr[8].toInt(), "bit depth")
        assertEquals(6, ihdr[9].toInt(), "colour type 6 = truecolour with alpha")
        assertEquals(0, ihdr[10].toInt(), "compression method")
        assertEquals(0, ihdr[11].toInt(), "filter method")
        assertEquals(0, ihdr[12].toInt(), "interlace")
    }

    @Test
    fun rgbaPixelsRoundTripExactly() {
        val src = Fixtures.image(23, 17)
        val raw = decode(PngEncoder.encode(src), 4)
        assertEquals(23 * 4, raw.stride)
        for (y in 0 until 17) {
            for (x in 0 until 23) {
                val o = y * raw.stride + x * 4
                val argb = src[x, y]
                assertEquals((argb ushr 16) and 0xFF, raw.bytes[o].toInt() and 0xFF, "R at $x,$y")
                assertEquals((argb ushr 8) and 0xFF, raw.bytes[o + 1].toInt() and 0xFF, "G at $x,$y")
                assertEquals(argb and 0xFF, raw.bytes[o + 2].toInt() and 0xFF, "B at $x,$y")
                assertEquals((argb ushr 24) and 0xFF, raw.bytes[o + 3].toInt() and 0xFF, "A at $x,$y")
            }
        }
    }

    @Test
    fun grayPixelsRoundTripAndClamp() {
        val src = Fixtures.gray(19, 9)
        val png = PngEncoder.encodeGray(src)
        val ihdr = parse(png).first { it.type == "IHDR" }.data
        assertEquals(0, ihdr[9].toInt(), "colour type 0 = greyscale")
        val raw = decode(png, 1)
        for (y in 0 until 9) {
            for (x in 0 until 19) {
                assertEquals(
                    Px.toByte255(src[x, y]),
                    raw.bytes[y * raw.stride + x].toInt() and 0xFF,
                    "grey at $x,$y",
                )
            }
        }
        // The out-of-range samples the fixture plants must clamp, not wrap.
        assertEquals(0, raw.bytes[0].toInt() and 0xFF)
        assertEquals(255, raw.bytes[raw.bytes.size - 1].toInt() and 0xFF)
    }

    @Test
    fun singlePixelImageIsValid() {
        val src = RgbaImage(1, 1, intArrayOf(0x8899AABB.toInt()))
        val raw = decode(PngEncoder.encode(src), 4)
        assertEquals(0x99, raw.bytes[0].toInt() and 0xFF)
        assertEquals(0xAA, raw.bytes[1].toInt() and 0xFF)
        assertEquals(0xBB, raw.bytes[2].toInt() and 0xFF)
        assertEquals(0x88, raw.bytes[3].toInt() and 0xFF)
    }

    @Test
    fun singlePixelGrayImageIsValid() {
        val raw = decode(PngEncoder.encodeGray(GrayF(1, 1, floatArrayOf(0.5f))), 1)
        assertEquals(128, raw.bytes[0].toInt() and 0xFF)
    }

    @Test
    fun physIsWrittenOnlyWhenAResolutionIsGiven() {
        assertTrue(parse(PngEncoder.encode(Fixtures.image(4, 4))).none { it.type == "pHYs" })
        val withDpi = parse(PngEncoder.encode(Fixtures.image(4, 4), 300))
        val phys = withDpi.firstOrNull { it.type == "pHYs" }
        assertNotNull(phys, "pHYs expected when dpi > 0")
        assertEquals(11811, be32(phys.data, 0), "300 dpi is 11811 pixels per metre")
        assertEquals(1, phys.data[8].toInt(), "unit specifier: metre")
    }

    @Test
    fun filterSelectionShrinksFlatArtwork() {
        // A solid image compresses to almost nothing once Up is chosen for every row after the
        // first. This is the property the adaptive filter exists for; asserting it stops a
        // regression to "always filter 0" from passing silently.
        val flat = RgbaImage(256, 256).fill(0xFF204080.toInt())
        assertTrue(
            PngEncoder.encode(flat).size < 4096,
            "a solid 256x256 image should compress to well under 4 KiB",
        )
    }

    // ------------------------------------------------------------------ decoding

    private class Chunk(val type: String, val data: ByteArray)

    private class Raw(val bytes: ByteArray, val stride: Int)

    private fun parse(png: ByteArray): List<Chunk> {
        assertTrue(png.size > 8, "file is too short to be a PNG")
        var p = 8
        val chunks = ArrayList<Chunk>()
        while (p + 8 <= png.size) {
            val len = be32(png, p)
            assertTrue(len >= 0 && p + 12 + len <= png.size, "chunk length $len overruns the file")
            val type = String(png, p + 4, 4, Charsets.US_ASCII)
            val data = png.copyOfRange(p + 8, p + 8 + len)
            val stored = be32(png, p + 8 + len)
            val crc = CRC32()
            crc.update(png, p + 4, 4 + len)
            assertEquals(crc.value.toInt(), stored, "CRC mismatch on chunk $type")
            chunks.add(Chunk(type, data))
            p += 12 + len
        }
        assertEquals(png.size, p, "trailing bytes after the last chunk")
        return chunks
    }

    /** Inflates the IDAT stream and undoes the per-scanline filters. */
    private fun decode(png: ByteArray, bpp: Int): Raw {
        val chunks = parse(png)
        val ihdr = chunks.first { it.type == "IHDR" }.data
        val w = be32(ihdr, 0)
        val h = be32(ihdr, 4)
        val compressed = ByteArrayOutputStream()
        for (c in chunks) if (c.type == "IDAT") compressed.write(c.data)
        val filtered = inflate(compressed.toByteArray())
        val stride = w * bpp
        assertEquals((stride + 1) * h, filtered.size, "unexpected raw scanline length")

        val out = ByteArray(stride * h)
        val prev = ByteArray(stride)
        var sp = 0
        for (y in 0 until h) {
            val type = filtered[sp].toInt() and 0xFF
            sp++
            val row = y * stride
            for (i in 0 until stride) {
                val x = filtered[sp + i].toInt() and 0xFF
                val a = if (i >= bpp) out[row + i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val v = when (type) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + ((a + b) shr 1)
                    4 -> x + paeth(a, b, c)
                    else -> fail("illegal filter type $type on row $y")
                }
                out[row + i] = (v and 0xFF).toByte()
            }
            sp += stride
            System.arraycopy(out, row, prev, 0, stride)
        }
        return Raw(out, stride)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4 + 64)
        val buf = ByteArray(1 shl 16)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
