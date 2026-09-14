package com.offlinetracer.vector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgWriterTest {

    private val black = 0xFF000000.toInt()

    private fun line(): VecPath =
        VecPath(VecPoint(0f, 0f), listOf(VecSeg.Line(VecPoint(10f, 10f))), id = "s1")

    private fun doc(
        layers: List<VecLayer>,
        w: Float = 800f,
        h: Float = 600f,
        background: Int? = null,
    ) = VecDocument(w, h, layers, background)

    private fun oneLayerDoc(style: VecStyle = VecStyle()): VecDocument =
        doc(listOf(VecLayer("l1", "Outlines", listOf(VecShape(line(), style)))))

    // -----------------------------------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------------------------------

    @Test
    fun emitsRootAttributesAndDeclaration() {
        val svg = SvgWriter.write(oneLayerDoc())
        assertTrue(svg.startsWith("<?xml version=\"1.0\""), svg.take(80))
        assertTrue(svg.contains("xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(svg.contains("version=\"1.1\""))
        assertTrue(svg.contains("width=\"800px\""))
        assertTrue(svg.contains("height=\"600px\""))
        assertTrue(svg.contains("viewBox=\"0 0 800 600\""))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
    }

    @Test
    fun widthUnitIsHonoured() {
        val svg = SvgWriter.write(oneLayerDoc(), SvgWriter.SvgOptions(widthUnit = "mm"))
        assertTrue(svg.contains("width=\"800mm\""))
        assertTrue(svg.contains("viewBox=\"0 0 800 600\""), "viewBox must stay unitless")
    }

    @Test
    fun oneGroupPerLayerCarryingIdNameAndOpacity() {
        val d = doc(
            listOf(
                VecLayer("l1", "Outlines", listOf(VecShape(line(), VecStyle()))),
                VecLayer("l2", "Detail", listOf(VecShape(line(), VecStyle())), opacity = 0.5f),
            )
        )
        val svg = SvgWriter.write(d)
        assertTrue(svg.contains("<g id=\"l1\" data-name=\"Outlines\""))
        assertTrue(svg.contains("<g id=\"l2\" data-name=\"Detail\""))
        assertTrue(svg.contains("opacity=\"0.5\""))
        assertEquals(2, countOccurrences(svg, "</g>"))
    }

    @Test
    fun hiddenLayerIsMarkedDisplayNoneRatherThanDropped() {
        val d = doc(listOf(VecLayer("l1", "Hidden", listOf(VecShape(line(), VecStyle())), visible = false)))
        val svg = SvgWriter.write(d)
        assertTrue(svg.contains("style=\"display:none\""))
        assertTrue(svg.contains("<path"), "the geometry must still be in the file")
    }

    @Test
    fun ungroupedOutputDropsHiddenLayersEntirely() {
        val d = doc(
            listOf(
                VecLayer("l1", "Hidden", listOf(VecShape(line(), VecStyle())), visible = false),
                VecLayer("l2", "Shown", listOf(VecShape(line(), VecStyle()))),
            )
        )
        val svg = SvgWriter.write(d, SvgWriter.SvgOptions(groupByLayer = false))
        assertTrue(!svg.contains("<g "))
        assertEquals(1, countOccurrences(svg, "<path"))
    }

    @Test
    fun backgroundIsEmittedAsARect() {
        val svg = SvgWriter.write(doc(listOf(VecLayer("l", "L", listOf(VecShape(line(), VecStyle())))), background = 0xFFFF0000.toInt()))
        assertTrue(svg.contains("<rect"))
        assertTrue(svg.contains("fill=\"#FF0000\""))
    }

    @Test
    fun transparentBackgroundIsNotEmitted() {
        val svg = SvgWriter.write(doc(listOf(VecLayer("l", "L", listOf(VecShape(line(), VecStyle())))), background = 0))
        assertTrue(!svg.contains("<rect"))
    }

    // -----------------------------------------------------------------------------------------
    // Styling
    // -----------------------------------------------------------------------------------------

    @Test
    fun strokeStyleMapsToSvgAttributes() {
        val style = VecStyle(
            stroke = black, strokeWidth = 2.5f, fill = null,
            cap = LineCap.SQUARE, join = LineJoin.MITER, miterLimit = 6f,
        )
        val svg = SvgWriter.write(oneLayerDoc(style))
        assertTrue(svg.contains("fill=\"none\""))
        assertTrue(svg.contains("stroke=\"#000000\""))
        assertTrue(svg.contains("stroke-width=\"2.5\""))
        assertTrue(svg.contains("stroke-linecap=\"square\""))
        assertTrue(svg.contains("stroke-linejoin=\"miter\""))
        assertTrue(svg.contains("stroke-miterlimit=\"6\""))
    }

    @Test
    fun fillRuleIsEmittedOnlyWhenThereIsAFill() {
        val filled = SvgWriter.write(oneLayerDoc(VecStyle(fill = black, fillRule = FillRule.EVENODD)))
        assertTrue(filled.contains("fill-rule=\"evenodd\""))
        val unfilled = SvgWriter.write(oneLayerDoc(VecStyle(fill = null)))
        assertTrue(!unfilled.contains("fill-rule"))
    }

    @Test
    fun colourAlphaBecomesAnOpacityAttribute() {
        val svg = SvgWriter.write(oneLayerDoc(VecStyle(stroke = 0x80000000.toInt())))
        assertTrue(svg.contains("stroke-opacity="), svg)
    }

    @Test
    fun variableWidthPathIsWrittenAsAFilledOutline() {
        val path = VecPath(
            VecPoint(0f, 0f),
            listOf(VecSeg.Line(VecPoint(10f, 0f)), VecSeg.Line(VecPoint(20f, 0f))),
            strokeWidths = floatArrayOf(1f, 4f, 1f),
        )
        val svg = SvgWriter.write(doc(listOf(VecLayer("l", "L", listOf(VecShape(path, VecStyle(stroke = black)))))))
        assertTrue(svg.contains("fill=\"#000000\""), "the outline must be filled with the stroke colour")
        assertTrue(!svg.contains("stroke-width="), "a variable-width path has no single stroke width")
    }

    // -----------------------------------------------------------------------------------------
    // Safety
    // -----------------------------------------------------------------------------------------

    @Test
    fun escapesTheFiveXmlEntities() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", SvgWriter.escapeXml("a&b<c>d\"e'f"))
        assertEquals("plain", SvgWriter.escapeXml("plain"))
    }

    @Test
    fun escapesUserSuppliedLayerNames() {
        val d = doc(listOf(VecLayer("id&1", "Fish & <Chips>", listOf(VecShape(line(), VecStyle())))))
        val svg = SvgWriter.write(d)
        assertTrue(svg.contains("data-name=\"Fish &amp; &lt;Chips&gt;\""), svg)
        assertTrue(svg.contains("id=\"id&amp;1\""))
    }

    @Test
    fun neverEmitsNaNOrInfinity() {
        val path = VecPath(
            VecPoint(Float.NaN, Float.POSITIVE_INFINITY),
            listOf(VecSeg.Line(VecPoint(Float.NEGATIVE_INFINITY, 5f))),
        )
        val d = doc(
            listOf(VecLayer("l", "L", listOf(VecShape(path, VecStyle(strokeWidth = Float.NaN))), opacity = Float.NaN)),
            w = Float.NaN,
            h = 0f,
        )
        val svg = SvgWriter.write(d)
        assertTrue(!svg.contains("NaN"), svg)
        assertTrue(!svg.contains("Infinity"), svg)
        assertTrue(svg.contains("width=\"1px\""), "a non-finite document width must fall back, not vanish")
    }

    @Test
    fun emptyDocumentStillProducesAValidFile() {
        val svg = SvgWriter.write(VecDocument(100f, 100f, emptyList()))
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
        assertTrue(!svg.contains("<path"))
    }

    @Test
    fun prettyPrintOffProducesASingleLineBody() {
        val svg = SvgWriter.write(oneLayerDoc(), SvgWriter.SvgOptions(prettyPrint = false))
        assertEquals(0, countOccurrences(svg, "\n"))
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }
}
