package com.offlinetracer.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TIFF is checked by walking the IFD the way libtiff does: ascending tag order is an invariant,
 * not a nicety, and the strip descriptors have to agree with the actual byte count or a reader
 * either truncates the image or runs off the end of the file.
 */
class TiffEncoderTest {

    private class Entry(val tag: Int, val type: Int, val count: Int, val value: Int)

    @Test
    fun headerIsLittleEndianWithTheVersionMarker() {
        val tiff = TiffEncoder.encode(Fixtures.image(6, 4))
        assertEquals('I'.code, tiff[0].toInt() and 0xFF)
        assertEquals('I'.code, tiff[1].toInt() and 0xFF)
        assertEquals(42, le16(tiff, 2), "TIFF's version marker")
        val ifd = le32(tiff, 4)
        assertTrue(ifd > 0 && ifd < tiff.size, "IFD offset $ifd is outside the file")
        assertEquals(0, ifd and 1, "IFD offsets must be word aligned")
    }

    @Test
    fun ifdEntriesAreSortedByTag() {
        val tiff = TiffEncoder.encode(Fixtures.image(9, 3))
        val entries = readIfd(tiff)
        assertTrue(entries.isNotEmpty())
        for (i in 1 until entries.size) {
            assertTrue(
                entries[i].tag > entries[i - 1].tag,
                "tag ${entries[i].tag} follows ${entries[i - 1].tag}: readers reject unsorted IFDs",
            )
        }
    }

    @Test
    fun mandatoryBaselineTagsAreCorrect() {
        val w = 12
        val h = 7
        val tiff = TiffEncoder.encode(Fixtures.image(w, h), 300)
        val e = readIfd(tiff).associateBy { it.tag }

        assertEquals(w, e[256]?.value, "ImageWidth")
        assertEquals(h, e[257]?.value, "ImageLength")
        assertEquals(1, e[259]?.value, "Compression = none")
        assertEquals(2, e[262]?.value, "PhotometricInterpretation = RGB")
        assertEquals(4, e[277]?.value, "SamplesPerPixel")
        assertEquals(h, e[278]?.value, "RowsPerStrip = the whole image, one strip")
        assertEquals(1, e[284]?.value, "PlanarConfiguration = chunky")
        assertEquals(2, e[296]?.value, "ResolutionUnit = inch")
        assertEquals(2, e[338]?.value, "ExtraSamples = unassociated alpha")

        val bps = assertNotNull(e[258], "BitsPerSample")
        assertEquals(4, bps.count)
        for (i in 0 until 4) assertEquals(8, le16(tiff, bps.value + i * 2), "BitsPerSample[$i]")

        val counts = assertNotNull(e[279], "StripByteCounts")
        assertEquals(w * h * 4, counts.value, "strip byte count must equal the pixel data length")
        val offsets = assertNotNull(e[273], "StripOffsets")
        assertTrue(
            offsets.value > 0 && offsets.value + counts.value <= tiff.size,
            "the strip must lie inside the file",
        )

        val xres = assertNotNull(e[282], "XResolution")
        assertEquals(300, le32(tiff, xres.value), "resolution numerator")
        assertEquals(1, le32(tiff, xres.value + 4), "resolution denominator")
    }

    @Test
    fun pixelsAreStoredAsChunkyRgbaTopDown() {
        val w = 8
        val h = 5
        val src = Fixtures.image(w, h)
        val tiff = TiffEncoder.encode(src)
        val e = readIfd(tiff).associateBy { it.tag }
        val strip = assertNotNull(e[273], "StripOffsets").value
        for (y in 0 until h) {
            for (x in 0 until w) {
                val o = strip + (y * w + x) * 4
                val argb = src[x, y]
                assertEquals((argb ushr 16) and 0xFF, tiff[o].toInt() and 0xFF, "R at $x,$y")
                assertEquals((argb ushr 8) and 0xFF, tiff[o + 1].toInt() and 0xFF, "G at $x,$y")
                assertEquals(argb and 0xFF, tiff[o + 2].toInt() and 0xFF, "B at $x,$y")
                assertEquals((argb ushr 24) and 0xFF, tiff[o + 3].toInt() and 0xFF, "A at $x,$y")
            }
        }
    }

    @Test
    fun softwareTagIsNulTerminated() {
        val tiff = TiffEncoder.encode(Fixtures.image(4, 4))
        val e = assertNotNull(readIfd(tiff).associateBy { it.tag }[305], "Software")
        assertEquals(2, e.type, "ASCII")
        assertEquals(0, tiff[e.value + e.count - 1].toInt(), "ASCII values include the terminator")
        val text = String(tiff, e.value, e.count - 1, Charsets.US_ASCII)
        assertEquals("Offline Tracer", text)
    }

    @Test
    fun singlePixelImageIsValid() {
        val tiff = TiffEncoder.encode(Fixtures.image(1, 1))
        val e = readIfd(tiff).associateBy { it.tag }
        assertEquals(1, e[256]?.value)
        assertEquals(1, e[257]?.value)
        assertEquals(4, e[279]?.value)
    }

    private fun readIfd(tiff: ByteArray): List<Entry> {
        val at = le32(tiff, 4)
        val n = le16(tiff, at)
        val out = ArrayList<Entry>(n)
        for (i in 0 until n) {
            val p = at + 2 + i * 12
            val type = le16(tiff, p + 2)
            val count = le32(tiff, p + 4)
            // A single SHORT lives in the first two bytes of the value field; anything else is
            // either a 4-byte value or a file offset.
            val value = if (type == 3 && count == 1) le16(tiff, p + 8) else le32(tiff, p + 8)
            out.add(Entry(le16(tiff, p), type, count, value))
        }
        assertEquals(0, le32(tiff, at + 2 + n * 12), "there must be no second IFD")
        return out
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
