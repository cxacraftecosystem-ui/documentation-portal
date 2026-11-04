package com.fieldrepository.app.ui.trace

import com.offlinetracer.export.ExportFormat
import com.offlinetracer.export.ExportOptions
import com.offlinetracer.export.Exporter
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecPath
import com.offlinetracer.vector.VecPoint
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceExportTest {

    @Test
    fun everyEngineFormatIsEitherOfferedOrRefusedInWriting() {
        assertEquals(5, TRACE_EXPORT_FORMAT_COUNT)
        assertEquals(5, TRACE_NOT_OFFERED.size)
        assertEquals(ExportFormat.entries.size, TRACE_EXPORT_FORMAT_COUNT + TRACE_NOT_OFFERED.size)

        val named = TRACE_EXPORT_FORMATS.map { it.engineFormat } + TRACE_NOT_OFFERED.map { it.engineFormat }
        assertEquals(
            "A format is named twice, or one of the engine's is named by neither list",
            ExportFormat.entries.map { it.name }.toSet(),
            named.toSet(),
        )
    }

    @Test
    fun everyOfferedFormatIsOneTheEngineOrThePlatformCanWrite() {
        TRACE_EXPORT_FORMATS.forEach { row ->
            val format = ExportFormat.entries.firstOrNull { it.name == row.engineFormat }
            assertNotNull("${row.id} names no engine format", format)
            assertTrue("${row.id} names a format the engine refuses", Exporter.supports(format!!))
            assertEquals("${row.id} disagrees with the engine about its extension", format.extension, row.extension)
            assertEquals("${row.id} disagrees with the engine about its MIME type", format.mimeType, row.mime)
            assertEquals("${row.id} disagrees with the engine about being vector", format.isVector, row.isVector)
        }
    }

    @Test
    fun theAttachableRowsAreDerivedFromTheTable() {
        assertEquals(TRACE_EXPORT_FORMATS.filter { it.attachable }, TRACE_ATTACHABLE_FORMATS)
        assertEquals("svg", TRACE_EXPORT_FORMATS.first().id)
    }

    @Test
    fun anUnknownIdAnswersNullRatherThanTheFirstRow() {
        assertNotNull(traceExportFormat("svg"))
        assertEquals(null, traceExportFormat("SVG"))
        assertEquals(null, traceExportFormat("tiff"))
    }

    private fun oneLineDocument(): VecDocument = VecDocument(
        width = 100f,
        height = 100f,
        layers = listOf(
            VecLayer(
                id = "trace",
                name = TRACE_LAYER_NAME,
                shapes = listOf(
                    VecShape(
                        path = VecPath(VecPoint(0f, 0f), listOf(VecSeg.Line(VecPoint(50f, 50f)))),
                        style = VecStyle(),
                    )
                ),
            )
        ),
    )

    private fun write(format: ExportFormat): String = String(
        Exporter.export(
            oneLineDocument(),
            null,
            ExportOptions(
                format = format,
                width = 0,
                height = 0,
                scale = 1f,
                background = null,
                dpi = TRACE_PNG_DPI,
                precision = 2,
                includeMetadata = false,
                flattenLayers = false,
            ),
        ),
        Charsets.ISO_8859_1,
    )

    @Test
    fun onlyTheEpsWriterStampsTheTracersNameWithMetadataOff() {
        assertFalse("SvgExport stamped a title with includeMetadata off", write(ExportFormat.SVG).contains("Offline Tracer"))
        assertFalse("PdfWriter stamped an /Info object with includeMetadata off", write(ExportFormat.PDF).contains("Offline Tracer"))
        assertFalse("DxfWriter has no product name under any option", write(ExportFormat.DXF).contains("Offline Tracer"))
        assertTrue(
            "EpsWriter writes %%Creator outside the includeMetadata guard. If this ever becomes " +
                "false, TRACE_EXPORT_ENGINE_NAME_SENTENCE must stop being printed for the EPS.",
            write(ExportFormat.EPS).contains("Offline Tracer"),
        )
    }

    @Test
    fun thereIsNowhereForTheProvenanceNoteToGo() {
        val note = traceProvenanceNote("sheet-3.jpg", 412, 8100)
        listOf(ExportFormat.SVG, ExportFormat.PDF, ExportFormat.EPS, ExportFormat.DXF).forEach {
            assertFalse("$it somehow carried the note", write(it).contains("sheet-3.jpg"))
        }
        assertTrue("The note is still built, for the seam and for the day a title exists", note.contains("sheet-3.jpg"))
    }

    @Test
    fun theDxfCarriesTheLayerName() {
        assertTrue(write(ExportFormat.DXF).contains("LINE_ART"))
    }

    @Test
    fun aDocumentInsideTheCapIsNotEnlarged() {
        val size = tracePngSize(800, 600)
        assertEquals(800, size.width)
        assertEquals(600, size.height)
        assertFalse(size.reduced)
        assertEquals("", tracePngReductionNote(800, 600, size))
    }

    @Test
    fun theCapBindsOnTheLongEdgeWhicheverItIs() {
        val landscape = tracePngSize(4096, 2048)
        assertEquals(TRACE_PNG_MAX_EDGE_PX, landscape.width)
        assertEquals(1024, landscape.height)

        val portrait = tracePngSize(2048, 4096)
        assertEquals(1024, portrait.width)
        assertEquals(TRACE_PNG_MAX_EDGE_PX, portrait.height)
    }

    @Test
    fun noEdgeEverReachesZero() {
        val size = tracePngSize(4096, 3)
        assertTrue(size.height >= 1)
        assertEquals(TRACE_PNG_MAX_EDGE_PX, size.width)
    }

    @Test
    fun aDegenerateDocumentDoesNotDivideByZero() {
        val size = tracePngSize(0, 0)
        assertEquals(1, size.width)
        assertEquals(1, size.height)
        assertEquals(1.0, tracePngScale(0, 0), 0.0)
    }

    @Test
    fun theReductionIsSaidAfterTheFactWithBothNumbersInIt() {
        val note = tracePngReductionNote(4096, 2048, tracePngSize(4096, 2048))
        assertTrue(note.contains("2048x1024"))
        assertTrue(note.contains("4096x2048"))
        assertTrue("It names the remedy", note.contains("SVG"))
    }

    @Test
    fun aDerivedNameKeepsThePhotographsStem() {
        assertEquals("sheet-3-line-art.svg", traceExportFileName("sheet-3.jpg", "svg"))
        assertEquals(
            "sheet-3-traced.png",
            traceExportFileName("sheet-3.jpg", "png", TRACE_RENDER_SUFFIX),
        )
    }

    @Test
    fun aPathIsReducedToItsLastSegment() {
        assertEquals("sheet-line-art.svg", traceExportFileName("/storage/emulated/0/DCIM/sheet.jpg", "svg"))
        assertEquals("sheet-line-art.svg", traceExportFileName("C:\\pictures\\sheet.jpg", "svg"))
    }

    @Test
    fun theSanitiserRemovesWhatMediaStoreRefusesAndKeepsTheSpace() {
        assertEquals("Ikat_Bandha-line-art.svg", traceExportFileName("Ikat:Bandha.jpg", "svg"))
        assertEquals("Bandha-line-art.svg", traceExportFileName("Ikat/Bandha.jpg", "svg"))
        assertEquals("sheet 3-line-art.svg", traceExportFileName("sheet 3.jpg", "svg"))
    }

    @Test
    fun anEmptyNameFallsBackRatherThanProducingADotFile() {
        assertEquals("sketch-line-art.svg", traceExportFileName("", "svg"))
        assertEquals("sketch-line-art.svg", traceExportFileName(".jpg", "svg"))
    }

    @Test
    fun aStemIsCappedRatherThanHandedToTheFilesystemWhole() {
        val long = "a".repeat(200) + ".jpg"
        val name = traceExportFileName(long, "svg")
        assertEquals(80 + "-line-art.svg".length, name.length)
    }

    @Test
    fun onlyTheRasterGetsTheOtherSuffix() {
        TRACE_EXPORT_FORMATS.forEach {
            val expected = if (it.isVector) TRACE_SAVE_SUFFIX else TRACE_RENDER_SUFFIX
            assertEquals(expected, traceSaveSuffix(it))
        }
        assertEquals(TRACE_RENDER_SUFFIX, traceSaveSuffix(TRACE_EXPORT_FORMATS.first { it.id == "png" }))
    }

    @Test
    fun theExportBackgroundIsThePassThroughAndNothingElse() {
        assertEquals(null, traceExportBackground(null))
        assertEquals(0xFFFFFFFF.toInt(), traceExportBackground(0xFFFFFFFF.toInt()))
    }

    @Test
    fun theBackgroundIsReadOffTheParametersThatActuallyRan() {
        val white = traceValuesOf("""{"output":{"background":4294967295}}""")
        assertEquals(0xFFFFFFFF.toInt(), traceDocumentBackground(white))
        assertTrue(traceBackgroundIsWhite(traceDocumentBackground(white)))
        assertEquals("White", traceBackgroundLabel(traceDocumentBackground(white)))

        val transparent = traceValuesOf("""{"output":{"background":null}}""")
        assertEquals(null, traceDocumentBackground(transparent))
        assertEquals("Transparent", traceBackgroundLabel(null))
    }

    @Test
    fun everyFormatLeadsWithWhatASavedCopyIsAndIsNot() {
        TRACE_EXPORT_FORMATS.forEach {
            assertEquals(
                "${it.id} does not lead with the difference between this phone and the record",
                TRACE_EXPORT_KEEP_SENTENCE,
                traceExportLosses(it, documentBackground = null).first(),
            )
        }
    }

    @Test
    fun everyFormatSaysItCannotRecordItsSource() {
        TRACE_EXPORT_FORMATS.forEach {
            assertTrue(
                "${it.id} does not say it records nothing about which photograph it came from",
                TRACE_EXPORT_NO_PROVENANCE_SENTENCE in traceExportLosses(it, documentBackground = null),
            )
        }
    }

    @Test
    fun theCropSentenceAppearsOnlyForATraceThatWasCropped() {
        val svg = TRACE_EXPORT_FORMATS.first { it.id == "svg" }
        assertFalse(TRACE_EXPORT_NO_FRAME_SENTENCE in traceExportLosses(svg, null))
        assertTrue(
            TRACE_EXPORT_NO_FRAME_SENTENCE in
                traceExportLosses(svg, null, frameNote = "Cropped on the device to 900x1200 at (30, 40) of 2000x1500."),
        )
    }

    @Test
    fun onlyTheEpsRowWarnsAboutTheTracersName() {
        TRACE_EXPORT_FORMATS.forEach {
            val warned = TRACE_EXPORT_ENGINE_NAME_SENTENCE in traceExportLosses(it, null)
            assertEquals("${it.id} disagrees with the writers about branding", it.id == "eps", warned)
        }
    }

    @Test
    fun thePngCapIsNamedOnlyWhenItApplies() {
        val png = TRACE_EXPORT_FORMATS.first { it.id == "png" }
        val capMentioned = { longEdge: Int ->
            traceExportLosses(png, null, documentLongEdgePx = longEdge).any { it.contains("no bigger than") }
        }
        assertTrue("A 4096px drawing is reduced, and that must be said", capMentioned(4096))
        assertFalse("A 900px drawing is not reduced", capMentioned(900))
        assertTrue("An unknown size states the cap, which is the safe direction", capMentioned(0))
    }

    @Test
    fun theDxfSaysTheBackgroundChoiceDoesNothingToIt() {
        val dxf = TRACE_EXPORT_FORMATS.first { it.id == "dxf" }
        assertTrue(traceExportLosses(dxf, null).any { it.contains("carries no background") })
        assertFalse(
            "…and it is therefore the one format that does not get the transparency warning",
            traceExportLosses(dxf, null).any { it.contains("page shows through") },
        )
    }

    @Test
    fun aTransparentDrawingWarnsAboutThePageShowingThrough() {
        val svg = TRACE_EXPORT_FORMATS.first { it.id == "svg" }
        assertTrue(traceExportLosses(svg, null).any { it.contains("page shows through") })
        assertFalse(traceExportLosses(svg, 0xFFFFFFFF.toInt()).any { it.contains("page shows through") })
    }

    @Test
    fun theSavedCopyAndTheAddedCopyAreDescribedSeparately() {
        assertNotEquals(TRACE_EXPORT_KEEP_SENTENCE, TRACE_ATTACH_SENTENCE)
        assertTrue(TRACE_EXPORT_KEEP_SENTENCE.contains("Downloads"))
        assertTrue(TRACE_EXPORT_KEEP_SENTENCE.contains("not attached"))
        assertTrue(TRACE_ATTACH_SENTENCE.contains("queues"))
        assertTrue(
            "The panel must not promise the picture above is what gets attached",
            TRACE_ATTACH_SENTENCE.contains("only for comparing"),
        )
    }

    private fun assertNotEquals(a: String, b: String) {
        assertTrue("Two facts collapsed into one sentence", a != b)
    }
}
