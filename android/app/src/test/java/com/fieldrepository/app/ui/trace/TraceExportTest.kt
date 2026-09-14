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

/**
 * The export table, its arithmetic, its sentences, and the one claim about the vendored writers that
 * would otherwise be a paragraph nobody had checked.
 */
class TraceExportTest {

    /* ── The table against the engine's own enum ────────────────────────────────────────────── */

    /**
     * **TEN IS THE WHOLE OF `ExportFormat`: five offered plus five refused in writing.** The web could
     * only assert this on its own side because its enum was behind a bundle; here the enum is on this
     * module's compile classpath, so the bijection is checked directly.
     */
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

    /** Every offered row resolves to a format `:core-export` can actually write on its own. */
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

    /** The attachable list is DERIVED, never a second list that could go stale. */
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

    /* ── The claim about the vendored writers ───────────────────────────────────────────────── */

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
                // The switch this whole section is about.
                includeMetadata = false,
                flattenLayers = false,
            ),
        ),
        Charsets.ISO_8859_1,
    )

    /**
     * **THE BRANDING CLAIM, CHECKED AGAINST THE WRITERS RATHER THAN ASSERTED IN PROSE.**
     *
     * `TRACE_EXPORT_ENGINE_NAME_SENTENCE` tells somebody that one of the five formats records the
     * tracing library's name and the others do not. That is a claim about bytes in a file that reaches
     * an archive, so it is read out of the writers here. SVG, PDF and DXF must be clean with
     * `includeMetadata = false`; EPS must NOT be, because it writes its `%%Creator` outside that guard
     * and no option removes it.
     *
     * The PNG is the fifth and cannot be checked on a desktop JVM — it leaves through
     * `Bitmap.compress`, which is an Android stub here. It is clean because that encoder writes no text
     * chunk and is given no dpi, which is a property of the platform call rather than of anything this
     * app passes.
     */
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

    /**
     * **AND THE PROVENANCE NOTE REACHES NONE OF THEM**, because `ExportOptions` has no title field. The
     * sentence that says so is printed for all five; this is why.
     */
    @Test
    fun thereIsNowhereForTheProvenanceNoteToGo() {
        val note = traceProvenanceNote("sheet-3.jpg", 412, 8100)
        listOf(ExportFormat.SVG, ExportFormat.PDF, ExportFormat.EPS, ExportFormat.DXF).forEach {
            assertFalse("$it somehow carried the note", write(it).contains("sheet-3.jpg"))
        }
        assertTrue("The note is still built, for the seam and for the day a title exists", note.contains("sheet-3.jpg"))
    }

    /** The layer name is load-bearing in exactly one format: a CAD operator assigns a tool by it. */
    @Test
    fun theDxfCarriesTheLayerName() {
        assertTrue(write(ExportFormat.DXF).contains("LINE_ART"))
    }

    /* ── The PNG cap ────────────────────────────────────────────────────────────────────────── */

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

    /**
     * THE FLOOR OF ONE PIXEL IS NOT DECORATION: a 4096x3 document scales its short edge to 1.5, and a
     * bitmap of height 0 throws inside a save.
     */
    @Test
    fun noEdgeEverReachesZero() {
        val size = tracePngSize(4096, 3)
        assertTrue(size.height >= 1)
        assertEquals(TRACE_PNG_MAX_EDGE_PX, size.width)
    }

    /** A non-positive dimension is read as 1 by the ordinary rule rather than by a special case. */
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

    /* ── Naming ─────────────────────────────────────────────────────────────────────────────── */

    @Test
    fun aDerivedNameKeepsThePhotographsStem() {
        assertEquals("sheet-3-line-art.svg", traceExportFileName("sheet-3.jpg", "svg"))
        assertEquals(
            "sheet-3-traced.png",
            traceExportFileName("sheet-3.jpg", "png", TRACE_RENDER_SUFFIX),
        )
    }

    /** A path or a Uri segment: the last segment past either separator. */
    @Test
    fun aPathIsReducedToItsLastSegment() {
        assertEquals("sheet-line-art.svg", traceExportFileName("/storage/emulated/0/DCIM/sheet.jpg", "svg"))
        assertEquals("sheet-line-art.svg", traceExportFileName("C:\\pictures\\sheet.jpg", "svg"))
    }

    /**
     * A name carrying a colon produces a MediaStore insert that fails with a bare
     * `IllegalArgumentException` after the whole file has already been built. A SPACE does not, and is
     * kept — the web's rule, so the two clients name one file one way.
     *
     * A SLASH NEVER REACHES THE SANITISER AT ALL, and that is worth asserting rather than assuming: it
     * is taken as a path separator by the segment extraction above, exactly as the web's rule starts
     * from a `File.name`. So "Ikat/Bandha.jpg" is the file "Bandha.jpg" in a directory called "Ikat",
     * which is what every filesystem on earth also thinks it is.
     */
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

    /** The split is raster-versus-vector: is this a picture OF the drawing, or the drawing. */
    @Test
    fun onlyTheRasterGetsTheOtherSuffix() {
        TRACE_EXPORT_FORMATS.forEach {
            val expected = if (it.isVector) TRACE_SAVE_SUFFIX else TRACE_RENDER_SUFFIX
            assertEquals(expected, traceSaveSuffix(it))
        }
        assertEquals(TRACE_RENDER_SUFFIX, traceSaveSuffix(TRACE_EXPORT_FORMATS.first { it.id == "png" }))
    }

    /* ── The background ─────────────────────────────────────────────────────────────────────── */

    /**
     * **THE PASS-THROUGH IS THE WHOLE RULE.** `background = null` means "leave the document alone" to
     * the vector writers and "transparent" to the rasteriser, so an exporter that substituted null
     * would produce a white PDF beside a transparent PNG for one drawing.
     */
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

    /* ── The losses ─────────────────────────────────────────────────────────────────────────── */

    /** The "yours to keep" sentence is always first, on every format, unconditionally. */
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

    /** All five are silent about their source, so all five say so. */
    @Test
    fun everyFormatSaysItCannotRecordItsSource() {
        TRACE_EXPORT_FORMATS.forEach {
            assertTrue(
                "${it.id} does not say it records nothing about which photograph it came from",
                TRACE_EXPORT_NO_PROVENANCE_SENTENCE in traceExportLosses(it, documentBackground = null),
            )
        }
    }

    /**
     * The crop sentence is keyed on the provenance sentence already being present rather than on a
     * second list of ids — the question both answer is the same one, and a second list is the register
     * that goes stale the day a sixth format is added.
     */
    @Test
    fun theCropSentenceAppearsOnlyForATraceThatWasCropped() {
        val svg = TRACE_EXPORT_FORMATS.first { it.id == "svg" }
        assertFalse(TRACE_EXPORT_NO_FRAME_SENTENCE in traceExportLosses(svg, null))
        assertTrue(
            TRACE_EXPORT_NO_FRAME_SENTENCE in
                traceExportLosses(svg, null, frameNote = "Cropped on the device to 900x1200 at (30, 40) of 2000x1500."),
        )
    }

    /** Only the EPS carries the branding sentence, because only the EPS carries the branding. */
    @Test
    fun onlyTheEpsRowWarnsAboutTheTracersName() {
        TRACE_EXPORT_FORMATS.forEach {
            val warned = TRACE_EXPORT_ENGINE_NAME_SENTENCE in traceExportLosses(it, null)
            assertEquals("${it.id} disagrees with the writers about branding", it.id == "eps", warned)
        }
    }

    /** The PNG's cap is named when it bites and not when it does not. */
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

    /** The DXF carries no background at all, so it says the choice changes nothing in that file. */
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

    /**
     * The two sentences about where a file goes are DIFFERENT sentences, because two different files
     * have two different fates. Saying "yours to keep" of the attached one would be false in the other
     * direction.
     */
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
