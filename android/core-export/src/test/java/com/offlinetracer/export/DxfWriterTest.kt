package com.offlinetracer.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DXF is parsed back into group-code/value pairs, which is the only way to check the one thing
 * that actually breaks: a value written on the same line as its code, or a section left unclosed.
 * The layer table is checked entry by entry because the per-layer mapping is the whole reason a
 * CNC or laser user asks for DXF instead of SVG.
 */
class DxfWriterTest {

    private class Pair2(val code: Int, val value: String)

    private fun pairs(dxf: ByteArray): List<Pair2> {
        val lines = String(dxf, Charsets.US_ASCII).split("\r\n")
        val out = ArrayList<Pair2>(lines.size / 2)
        var i = 0
        while (i + 1 < lines.size) {
            val code = lines[i].trim().toIntOrNull()
                ?: fail("line $i is not a group code: '${lines[i]}'")
            out.add(Pair2(code, lines[i + 1]))
            i += 2
        }
        assertEquals("", lines[lines.size - 1], "the file must end with a line terminator")
        return out
    }

    @Test
    fun everyValueFollowsItsGroupCodeOnItsOwnLine() {
        val lines = String(DxfWriter.export(Fixtures.document(), ExportOptions(ExportFormat.DXF)),
            Charsets.US_ASCII).split("\r\n")
        // Trailing "" from the final CRLF, so the real content is an even number of lines.
        assertEquals(1, lines.size % 2, "code and value lines must pair up exactly")
        var i = 0
        while (i + 1 < lines.size) {
            assertTrue(
                lines[i].trim().toIntOrNull() != null,
                "expected a group code at line $i, got '${lines[i]}'",
            )
            i += 2
        }
    }

    @Test
    fun sectionsAreBalancedAndTheFileEndsWithEof() {
        val p = pairs(DxfWriter.export(Fixtures.document(), ExportOptions(ExportFormat.DXF)))
        var open = 0
        var sections = 0
        for (e in p) {
            if (e.code != 0) continue
            when (e.value) {
                "SECTION" -> {
                    open++
                    sections++
                }

                "ENDSEC" -> open--
            }
            assertTrue(open in 0..1, "sections may not nest")
        }
        assertEquals(0, open, "every SECTION needs an ENDSEC")
        assertEquals(3, sections, "HEADER, TABLES and ENTITIES")

        val last = p[p.size - 1]
        assertEquals(0, last.code)
        assertEquals("EOF", last.value, "a DXF file terminates with a 0/EOF pair")

        var tables = 0
        var endtabs = 0
        for (e in p) {
            if (e.code != 0) continue
            if (e.value == "TABLE") tables++
            if (e.value == "ENDTAB") endtabs++
        }
        assertEquals(tables, endtabs, "every TABLE needs an ENDTAB")
        assertEquals(2, tables, "LTYPE and LAYER")
    }

    @Test
    fun headerDeclaresR12AndSaneExtents() {
        val p = pairs(DxfWriter.export(Fixtures.document(), ExportOptions(ExportFormat.DXF)))
        val acadIdx = p.indexOfFirst { it.code == 9 && it.value == "\$ACADVER" }
        assertTrue(acadIdx >= 0, "\$ACADVER is mandatory")
        assertEquals("AC1009", p[acadIdx + 1].value, "AC1009 is R12")

        val minIdx = p.indexOfFirst { it.code == 9 && it.value == "\$EXTMIN" }
        val maxIdx = p.indexOfFirst { it.code == 9 && it.value == "\$EXTMAX" }
        assertTrue(minIdx >= 0 && maxIdx > minIdx)
        val minX = p[minIdx + 1].value.toFloat()
        val minY = p[minIdx + 2].value.toFloat()
        val maxX = p[maxIdx + 1].value.toFloat()
        val maxY = p[maxIdx + 2].value.toFloat()
        assertTrue(maxX > minX && maxY > minY, "extents must be a real box")
        // 100 x 50 document pixels at the default 300 dpi is 8.47 x 4.23 mm, and the geometry
        // lies inside that. A DXF in pixels would put these in the thousands.
        assertTrue(maxX <= 8.5f, "geometry is in millimetres, got $maxX")
        assertTrue(maxY <= 4.3f, "geometry is in millimetres, got $maxY")
    }

    @Test
    fun oneSanitisedLayerPerVecLayerPlusTheMandatoryZero() {
        val doc = Fixtures.document()
        val p = pairs(DxfWriter.export(doc, ExportOptions(ExportFormat.DXF)))
        val names = ArrayList<String>()
        var i = 0
        while (i < p.size) {
            if (p[i].code == 0 && p[i].value == "LAYER") names.add(p[i + 1].value)
            i++
        }
        assertEquals(doc.layers.size + 1, names.size, "layer 0 plus one layer per VecLayer")
        assertEquals("0", names[0], "DXF requires the default layer 0")
        assertEquals(names.size, names.toSet().size, "layer names must be unique")
        for (name in names) {
            assertTrue(name.isNotEmpty(), "a layer name may not be empty")
            assertTrue(name.length <= 31, "R12 layer names are at most 31 characters")
            for (ch in name) {
                assertTrue(
                    (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '$' || ch == '-' || ch == '_',
                    "illegal character '$ch' in layer name '$name'",
                )
            }
        }
        // The fixture's "Cut lines!" and "engrave 2" both need work to become legal.
        assertTrue(names.contains("CUT_LINES_"), "expected CUT_LINES_ in $names")
        assertTrue(names.contains("ENGRAVE_2"), "expected ENGRAVE_2 in $names")
    }

    @Test
    fun entitiesAreLwPolylinesOnTheirOwnLayerWithMatchingVertexCounts() {
        val doc = Fixtures.document()
        val p = pairs(DxfWriter.export(doc, ExportOptions(ExportFormat.DXF)))
        val entitiesAt = p.indexOfFirst { it.code == 2 && it.value == "ENTITIES" }
        assertTrue(entitiesAt > 0, "an ENTITIES section is required")

        var polylines = 0
        var i = entitiesAt
        while (i < p.size) {
            if (p[i].code == 0 && p[i].value == "LWPOLYLINE") {
                polylines++
                assertEquals(8, p[i + 1].code, "the layer name follows the entity type")
                assertTrue(p[i + 1].value.isNotEmpty())
                assertEquals(90, p[i + 2].code, "vertex count")
                val declared = p[i + 2].value.toInt()
                assertEquals(70, p[i + 3].code, "polyline flags")
                assertTrue(p[i + 3].value == "0" || p[i + 3].value == "1")
                var vertices = 0
                var j = i + 4
                while (j < p.size && p[j].code != 0) {
                    if (p[j].code == 10) {
                        vertices++
                        assertEquals(20, p[j + 1].code, "every x needs a y")
                        // A DXF real with no decimal point is rejected by strict readers.
                        assertTrue(p[j].value.contains('.'), "x '${p[j].value}' needs a decimal point")
                        assertTrue(p[j + 1].value.contains('.'), "y needs a decimal point")
                    }
                    j++
                }
                assertEquals(declared, vertices, "group 90 must equal the number of 10/20 pairs")
                assertTrue(vertices >= 2, "a polyline needs at least two vertices")
            }
            i++
        }
        assertEquals(Fixtures.SHAPE_COUNT, polylines, "one polyline per drawable shape")
    }

    @Test
    fun yIsFlippedRelativeToTheDocument() {
        // The fixture's topmost geometry is the open curve's start at document y = 45, which is
        // the *lowest* point in DXF space. If y were not flipped the extents would be inverted.
        val doc = Fixtures.document()
        val p = pairs(DxfWriter.export(doc, ExportOptions(ExportFormat.DXF, dpi = 72)))
        val entitiesAt = p.indexOfFirst { it.code == 2 && it.value == "ENTITIES" }
        var firstY = Float.NaN
        var i = entitiesAt
        while (i < p.size) {
            if (p[i].code == 0 && p[i].value == "LWPOLYLINE") {
                // First vertex of the first entity: the rectangle's corner at document (10, 10).
                var j = i
                while (p[j].code != 10) j++
                firstY = p[j + 1].value.toFloat()
                break
            }
            i++
        }
        val mm = 25.4f / 72f
        assertEquals((Fixtures.DOC_H - 10f) * mm, firstY, 0.01f, "y must be measured up from the bottom")
    }

    @Test
    fun emptyDocumentStillProducesAValidFile() {
        val p = pairs(DxfWriter.export(Fixtures.emptyDocument(), ExportOptions(ExportFormat.DXF)))
        assertEquals("EOF", p[p.size - 1].value)
        var sections = 0
        var ends = 0
        for (e in p) {
            if (e.code != 0) continue
            if (e.value == "SECTION") sections++
            if (e.value == "ENDSEC") ends++
        }
        assertEquals(3, sections)
        assertEquals(3, ends)
        assertTrue(p.none { it.code == 0 && it.value == "LWPOLYLINE" }, "nothing to draw")
    }

    @Test
    fun exportIsByteReproducible() {
        val o = ExportOptions(ExportFormat.DXF)
        assertTrue(
            DxfWriter.export(Fixtures.document(), o).contentEquals(
                DxfWriter.export(Fixtures.document(), o)
            )
        )
    }
}
