package com.offlinetracer.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The PDF is verified structurally: the cross-reference table is parsed and every offset in it is
 * followed to check that the object it claims really starts there. Acrobat silently rebuilds a
 * broken table, so a wrong xref passes a "does it open" check and then fails everywhere else.
 */
class PdfWriterTest {

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun headerAndTrailerAreWellFormed() {
        val pdf = PdfWriter.export(Fixtures.document(), ExportOptions(ExportFormat.PDF))
        val s = text(pdf)
        assertTrue(s.startsWith("%PDF-1.4\n"), "version header")
        assertTrue(
            (pdf[9].toInt() and 0xFF) == '%'.code && (pdf[10].toInt() and 0xFF) >= 128,
            "line 2 must be a binary comment so transfer agents do not mangle the stream",
        )
        assertTrue(s.endsWith("%%EOF\n"), "file must end with the EOF marker")
        assertTrue(s.contains("/Type /Catalog"), "catalog")
        assertTrue(s.contains("/Type /Pages"), "page tree")
        assertTrue(s.contains("/Type /Page "), "page")
        assertTrue(s.contains("/Root 1 0 R"), "trailer must name the catalog")
    }

    @Test
    fun everyXrefOffsetPointsAtTheObjectItClaims() {
        val pdf = PdfWriter.export(Fixtures.document(), ExportOptions(ExportFormat.PDF))
        val s = text(pdf)

        val startxref = s.lastIndexOf("startxref")
        assertTrue(startxref > 0, "startxref is mandatory")
        val xrefOffset = s.substring(startxref + 9).trim().substringBefore('\n').trim().toInt()
        assertEquals("xref", s.substring(xrefOffset, xrefOffset + 4), "startxref must point at xref")

        val headerEnd = s.indexOf('\n', xrefOffset + 5)
        val header = s.substring(xrefOffset + 5, headerEnd).trim().split(' ')
        assertEquals("0", header[0], "the subsection must start at object 0")
        val count = header[1].toInt()
        assertTrue(s.contains("/Size $count"), "the trailer /Size must match the xref subsection")

        val entriesAt = headerEnd + 1
        assertEquals(
            "0000000000 65535 f",
            s.substring(entriesAt, entriesAt + 18),
            "object 0 is the head of the free list",
        )
        for (i in 1 until count) {
            val entry = s.substring(entriesAt + i * 20, entriesAt + i * 20 + 20)
            assertEquals(20, entry.length, "every xref entry is exactly 20 bytes")
            assertEquals('n', entry[17], "entry $i must be in use")
            val offset = entry.substring(0, 10).toInt()
            assertTrue(
                s.startsWith("$i 0 obj", offset),
                "xref says object $i starts at $offset, but that is '" +
                    s.substring(offset, minOf(offset + 12, s.length)) + "'",
            )
        }
    }

    @Test
    fun contentStreamLengthMatchesTheActualStream() {
        val pdf = PdfWriter.export(Fixtures.document(), ExportOptions(ExportFormat.PDF))
        val s = text(pdf)
        val declared = Regex("/Length (\\d+) >>\\nstream\\n").find(s)
        assertNotNull(declared, "the content stream must declare its length")
        val start = declared.range.last + 1
        val length = declared.groupValues[1].toInt()
        assertEquals(
            "\nendstream",
            s.substring(start + length, start + length + 10),
            "/Length must be the exact byte count between stream and endstream",
        )
    }

    @Test
    fun mediaBoxIsInPointsAndHonoursDpi() {
        val doc = Fixtures.document()
        // 100 x 50 document pixels at 300 dpi is 24 x 12 points.
        val at300 = mediaBox(PdfWriter.export(doc, ExportOptions(ExportFormat.PDF, dpi = 300)))
        assertEquals(24f, at300[0], 0.01f)
        assertEquals(12f, at300[1], 0.01f)

        // At 72 dpi a document unit *is* a point, so the page is the document size.
        val at72 = mediaBox(PdfWriter.export(doc, ExportOptions(ExportFormat.PDF, dpi = 72)))
        assertEquals(100f, at72[0], 0.01f)
        assertEquals(50f, at72[1], 0.01f)

        // Scale multiplies the page as well as the geometry.
        val scaled = mediaBox(PdfWriter.export(doc, ExportOptions(ExportFormat.PDF, dpi = 72, scale = 2f)))
        assertEquals(200f, scaled[0], 0.01f)
        assertEquals(100f, scaled[1], 0.01f)

        // An explicit width with no height keeps the aspect ratio.
        val fixed = mediaBox(PdfWriter.export(doc, ExportOptions(ExportFormat.PDF, dpi = 72, width = 400)))
        assertEquals(400f, fixed[0], 0.01f)
        assertEquals(200f, fixed[1], 0.01f)
    }

    @Test
    fun theContentStreamFlipsYAndClipsToThePage() {
        val pdf = PdfWriter.export(Fixtures.document(), ExportOptions(ExportFormat.PDF, dpi = 72))
        val s = text(pdf)
        assertTrue(s.contains(" re W n\n"), "the page clip uses the W n operator pair")
        val cm = Regex("([-\\d.]+) 0 0 ([-\\d.]+) 0 ([-\\d.]+) cm").find(s)
        assertNotNull(cm, "a transform matrix is required to flip y")
        val sx = cm.groupValues[1].toFloat()
        val sy = cm.groupValues[2].toFloat()
        val ty = cm.groupValues[3].toFloat()
        assertEquals(1f, sx, 0.001f, "x is unscaled at 72 dpi")
        assertTrue(sy < 0f, "y must be negated or the export is upside down")
        assertEquals(50f, ty, 0.01f, "the flip must translate by the page height")
    }

    @Test
    fun pathAndPaintOperatorsArePresent() {
        val s = text(PdfWriter.export(Fixtures.document(), ExportOptions(ExportFormat.PDF)))
        assertTrue(s.contains(" m\n"), "moveto")
        assertTrue(s.contains(" l\n"), "lineto")
        assertTrue(s.contains(" c\n"), "curveto for the cubic in the fixture")
        assertTrue(s.contains("h\n"), "closepath for the closed rectangles")
        assertTrue(s.contains("\nS\n"), "stroke for the stroked shapes")
        assertTrue(s.contains("f*\n") || s.contains("\nf\n"), "fill for the filled shape")
        assertTrue(s.contains(" RG\n"), "stroke colour")
        assertTrue(s.contains(" rg\n"), "fill colour")
    }

    @Test
    fun emptyDocumentStillProducesAValidFile() {
        val pdf = PdfWriter.export(Fixtures.emptyDocument(), ExportOptions(ExportFormat.PDF))
        val s = text(pdf)
        assertTrue(s.startsWith("%PDF-1.4"))
        assertTrue(s.endsWith("%%EOF\n"))
        val box = mediaBox(pdf)
        assertTrue(box[0] > 0f && box[1] > 0f, "an empty document still has a page")
    }

    @Test
    fun metadataCanBeSuppressed() {
        val withInfo = text(PdfWriter.export(Fixtures.document(), ExportOptions(ExportFormat.PDF)))
        assertTrue(withInfo.contains("/Info "), "metadata is on by default")
        val without = text(
            PdfWriter.export(Fixtures.document(), ExportOptions(ExportFormat.PDF, includeMetadata = false))
        )
        assertTrue(!without.contains("/Info "), "no /Info reference when metadata is off")
    }

    @Test
    fun exportIsByteReproducible() {
        val o = ExportOptions(ExportFormat.PDF)
        assertTrue(
            PdfWriter.export(Fixtures.document(), o).contentEquals(
                PdfWriter.export(Fixtures.document(), o)
            ),
            "no timestamps, no ordering nondeterminism",
        )
    }

    private fun mediaBox(pdf: ByteArray): FloatArray {
        val m = Regex("/MediaBox \\[0 0 ([-\\d.]+) ([-\\d.]+)]").find(text(pdf))
        assertNotNull(m, "every page needs a MediaBox")
        return floatArrayOf(m.groupValues[1].toFloat(), m.groupValues[2].toFloat())
    }
}
