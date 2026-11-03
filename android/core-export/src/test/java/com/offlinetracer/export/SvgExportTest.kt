package com.offlinetracer.export

import com.offlinetracer.vector.VecDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SvgExportTest {

    private fun text(o: ExportOptions, doc: VecDocument = Fixtures.document()) =
        String(SvgExport.export(doc, o), Charsets.UTF_8)

    @Test
    fun outputIsUtf8Svg() {
        val s = text(ExportOptions(ExportFormat.SVG))
        assertTrue(s.contains("<svg"), "an SVG root element is required")
        assertTrue(s.contains("</svg>"), "the root element must be closed")
    }

    @Test
    fun scalingIsBakedIntoTheGeometry() {
        val scaled = ExportGeom.scaleDocument(Fixtures.document(), 2f, 2f)
        assertEquals(Fixtures.DOC_W * 2f, scaled.width, 0.001f)
        assertEquals(Fixtures.DOC_H * 2f, scaled.height, 0.001f)

        val original = Fixtures.document().layers[0].shapes[0]
        val doubled = scaled.layers[0].shapes[0]
        val a = original.path.bounds()
        val b = doubled.path.bounds()
        for (i in 0 until 4) {
            assertEquals(a[i] * 2f, b[i], 0.001f, "bounds component $i must double")
        }
        assertEquals(
            original.style.strokeWidth * 2f,
            doubled.style.strokeWidth,
            0.001f,
            "a 2x export must not draw hairlines",
        )
    }

    @Test
    fun explicitWidthKeepsTheAspectRatio() {
        val s = ExportGeom.scaleXY(
            ExportOptions(ExportFormat.SVG, width = 400),
            Fixtures.DOC_W,
            Fixtures.DOC_H,
        )
        assertEquals(4f, s[0], 0.001f)
        assertEquals(4f, s[1], 0.001f, "an unspecified height mirrors the width's scale")

        val scaled = ExportGeom.scaleDocument(Fixtures.document(), s[0], s[1])
        assertEquals(400f, scaled.width, 0.001f)
        assertEquals(200f, scaled.height, 0.001f)
    }

    @Test
    fun anUnscaledDocumentIsReturnedUnchanged() {
        val doc = Fixtures.document()
        assertTrue(ExportGeom.scaleDocument(doc, 1f, 1f) === doc, "identity scaling must not copy")
    }

    @Test
    fun backgroundOverridesTheDocument() {
        val doc = Fixtures.document()
        assertNull(doc.background, "the fixture starts transparent")
        assertTrue(text(ExportOptions(ExportFormat.SVG, background = 0xFFFF0000.toInt())).isNotEmpty())
    }

    @Test
    fun emptyDocumentStillProducesARootElement() {
        val s = text(ExportOptions(ExportFormat.SVG), Fixtures.emptyDocument())
        assertTrue(s.contains("<svg"))
        assertTrue(s.contains("</svg>"))
    }
}
