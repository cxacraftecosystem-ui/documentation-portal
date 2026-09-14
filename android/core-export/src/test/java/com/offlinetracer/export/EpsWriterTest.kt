package com.offlinetracer.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The assertion that matters here is that `%%BoundingBox` actually encloses the geometry. It is
 * what a placing application uses to size the frame, so a box that is one point short silently
 * crops the artwork rather than failing.
 */
class EpsWriterTest {

    private fun text(bytes: ByteArray) = String(bytes, Charsets.US_ASCII)

    @Test
    fun dscHeaderIsAnEpsf3File() {
        val s = text(EpsWriter.export(Fixtures.document(), ExportOptions(ExportFormat.EPS)))
        assertTrue(s.startsWith("%!PS-Adobe-3.0 EPSF-3.0\n"), "the magic line identifies an EPS")
        assertTrue(s.contains("\n%%EndComments\n"), "the DSC header must be terminated")
        assertTrue(s.contains("\n%%BeginProlog\n") && s.contains("\n%%EndProlog\n"), "prolog")
        assertTrue(s.contains("\n%%Page: 1 1\n"), "one page")
        assertTrue(s.endsWith("%%EOF\n"), "the file must end with the EOF comment")
    }

    @Test
    fun outputIsSevenBitClean() {
        val bytes = EpsWriter.export(Fixtures.document(), ExportOptions(ExportFormat.EPS))
        assertTrue(text(bytes).contains("%%DocumentData: Clean7Bit"))
        for (b in bytes) {
            assertTrue(b >= 0, "a Clean7Bit document may not contain a byte above 127")
        }
    }

    @Test
    fun boundingBoxIsIntegerAndEnclosesTheGeometry() {
        val doc = Fixtures.document()
        val o = ExportOptions(ExportFormat.EPS, dpi = 72)
        val s = text(EpsWriter.export(doc, o))

        val m = Regex("%%BoundingBox: (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+)\n").find(s)
        assertNotNull(m, "%%BoundingBox is mandatory and must be four integers")
        val bx0 = m.groupValues[1].toInt()
        val by0 = m.groupValues[2].toInt()
        val bx1 = m.groupValues[3].toInt()
        val by1 = m.groupValues[4].toInt()
        assertTrue(bx1 > bx0 && by1 > by0, "the box must be non-degenerate")

        // The same geometry, transformed into EPS space by hand: at 72 dpi a document unit is a
        // point, and y is measured up from the bottom of the page.
        val g = assertNotNull(ExportGeom.geometryBounds(doc), "the fixture has geometry")
        val gx0 = g[0]
        val gx1 = g[2]
        val gy0 = Fixtures.DOC_H - g[3]
        val gy1 = Fixtures.DOC_H - g[1]
        assertTrue(bx0 <= gx0, "box left $bx0 must not crop geometry at $gx0")
        assertTrue(by0 <= gy0, "box bottom $by0 must not crop geometry at $gy0")
        assertTrue(bx1 >= gx1, "box right $bx1 must not crop geometry at $gx1")
        assertTrue(by1 >= gy1, "box top $by1 must not crop geometry at $gy1")

        // It must also cover the page itself, so a placed EPS keeps the artboard proportions.
        assertTrue(bx0 <= 0 && by0 <= 0)
        assertTrue(bx1 >= Fixtures.DOC_W.toInt() && by1 >= Fixtures.DOC_H.toInt())
    }

    @Test
    fun hiResBoundingBoxAgreesWithTheIntegerOne() {
        val s = text(EpsWriter.export(Fixtures.document(), ExportOptions(ExportFormat.EPS, dpi = 72)))
        val lo = Regex("%%BoundingBox: (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+)\n").find(s)
        val hi = Regex("%%HiResBoundingBox: ([-\\d.]+) ([-\\d.]+) ([-\\d.]+) ([-\\d.]+)\n").find(s)
        assertNotNull(lo)
        assertNotNull(hi)
        // The integer box is the high-resolution one rounded *outward*, never nearest: rounding
        // inward crops by up to half a point on each side.
        assertTrue(lo.groupValues[1].toInt() <= hi.groupValues[1].toFloat())
        assertTrue(lo.groupValues[2].toInt() <= hi.groupValues[2].toFloat())
        assertTrue(lo.groupValues[3].toInt() >= hi.groupValues[3].toFloat())
        assertTrue(lo.groupValues[4].toInt() >= hi.groupValues[4].toFloat())
    }

    @Test
    fun prologDefinesTheStandardPathOperators() {
        val s = text(EpsWriter.export(Fixtures.document(), ExportOptions(ExportFormat.EPS)))
        for (op in listOf("moveto", "lineto", "curveto", "closepath", "stroke", "fill", "eofill")) {
            assertTrue(s.contains(op), "the prolog must define $op")
        }
        assertTrue(s.contains(" m\n"), "a moveto is emitted for every path")
        assertTrue(s.contains(" l\n"), "lineto")
        assertTrue(s.contains(" c\n"), "curveto for the fixture's cubic")
        assertTrue(s.contains("h\n"), "closepath for the closed rectangles")
        assertTrue(s.contains("showpage\n"), "showpage")
    }

    @Test
    fun theTransformFlipsY() {
        val s = text(EpsWriter.export(Fixtures.document(), ExportOptions(ExportFormat.EPS, dpi = 72)))
        val m = Regex("\\[([-\\d.]+) 0 0 ([-\\d.]+) 0 ([-\\d.]+)] concat").find(s)
        assertNotNull(m, "a concat matrix is required")
        assertEquals(1f, m.groupValues[1].toFloat(), 0.001f)
        assertTrue(m.groupValues[2].toFloat() < 0f, "y must be negated or the export is mirrored")
        assertEquals(50f, m.groupValues[3].toFloat(), 0.01f, "translate by the page height")
    }

    @Test
    fun dpiScalesThePage() {
        val s = text(EpsWriter.export(Fixtures.document(), ExportOptions(ExportFormat.EPS, dpi = 300)))
        val hi = Regex("%%HiResBoundingBox: ([-\\d.]+) ([-\\d.]+) ([-\\d.]+) ([-\\d.]+)\n").find(s)
        assertNotNull(hi)
        // 100 x 50 document pixels at 300 dpi is a 24 x 12 point page.
        assertEquals(24f, hi.groupValues[3].toFloat(), 0.01f)
        assertEquals(12f, hi.groupValues[4].toFloat(), 0.01f)
    }

    @Test
    fun emptyDocumentStillProducesAValidFile() {
        val s = text(EpsWriter.export(Fixtures.emptyDocument(), ExportOptions(ExportFormat.EPS, dpi = 72)))
        assertTrue(s.startsWith("%!PS-Adobe-3.0 EPSF-3.0"))
        assertTrue(s.endsWith("%%EOF\n"))
        assertTrue(s.contains("%%BoundingBox: 0 0 100 50\n"), "the box falls back to the page")
    }

    @Test
    fun exportIsByteReproducible() {
        val o = ExportOptions(ExportFormat.EPS)
        assertTrue(
            EpsWriter.export(Fixtures.document(), o).contentEquals(
                EpsWriter.export(Fixtures.document(), o)
            ),
            "no creation date, no ordering nondeterminism",
        )
    }
}
