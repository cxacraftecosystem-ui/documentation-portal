package com.offlinetracer.pipeline

import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.vector.FillRule
import com.offlinetracer.vector.LineCap
import com.offlinetracer.vector.LineJoin
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecPath
import com.offlinetracer.vector.VecPoint
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The project format.
 *
 * The three properties asserted here are the ones that decide whether somebody's saved work opens next
 * year: exact round-trips, unknown fields ignored, missing fields defaulted. The fourth — that genuinely
 * corrupt input raises rather than silently decoding to an empty project — matters just as much, because
 * an empty canvas the user then saves over is how the file on disk gets destroyed.
 */
class ProjectTest {

    private fun sampleMeta() = ProjectMeta(
        id = "prj-1",
        name = "Grandmother's teapot",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_900_000L,
        tags = listOf("pottery", "gift"),
        subjectId = "pottery",
        favourite = true,
        sourceWidth = 4032,
        sourceHeight = 3024,
        thumbnailPath = "thumbs/prj-1.png",
    )

    /** Exercises all three segment kinds, per-anchor widths, and a non-default style. */
    private fun sampleDocument(): VecDocument {
        val open = VecPath(
            start = VecPoint(1.5f, 2.25f),
            segments = listOf(
                VecSeg.Line(VecPoint(10f, 2.25f)),
                VecSeg.Cubic(VecPoint(12f, 3f), VecPoint(14f, 5f), VecPoint(15.75f, 8.125f)),
                VecSeg.Quad(VecPoint(16f, 12f), VecPoint(11f, 14f)),
            ),
            closed = false,
            id = "p0",
            strokeWidths = floatArrayOf(1f, 1.5f, 2.25f, 0.75f),
        )
        val closed = VecPath(
            start = VecPoint(30f, 30f),
            segments = listOf(
                VecSeg.Line(VecPoint(40f, 30f)),
                VecSeg.Line(VecPoint(40f, 40f)),
                VecSeg.Line(VecPoint(30f, 40f)),
            ),
            closed = true,
            id = "p1",
        )
        return VecDocument(
            width = 128f,
            height = 96f,
            layers = listOf(
                VecLayer(
                    id = "trace",
                    name = "Trace",
                    shapes = listOf(
                        VecShape(open, VecStyle(stroke = 0xFF112233.toInt(), strokeWidth = 2.5f)),
                        VecShape(
                            closed,
                            VecStyle(
                                stroke = null,
                                strokeWidth = 0.3f,
                                fill = 0xFFAABBCC.toInt(),
                                fillRule = FillRule.NONZERO,
                                cap = LineCap.SQUARE,
                                join = LineJoin.MITER,
                                miterLimit = 6f,
                                opacity = 0.5f,
                            ),
                        ),
                    ),
                    visible = false,
                    locked = true,
                    opacity = 0.75f,
                )
            ),
            background = 0xFFFFFFFF.toInt(),
        )
    }

    // -------------------------------------------------------------------------------------------
    // Envelope
    // -------------------------------------------------------------------------------------------

    @Test
    fun aProjectRoundTripsExactly() {
        val original = ProjectDocument(
            meta = sampleMeta(),
            params = Styles.byId("woodcut")!!.params,
            layersJson = ProjectCodec.encodeDocument(sampleDocument()),
            historyVersion = 3,
        )
        assertEquals(original, ProjectCodec.decode(ProjectCodec.encode(original)))
    }

    @Test
    fun encodingStampsTheCurrentSchemaVersion() {
        val text = ProjectCodec.encode(ProjectDocument(schemaVersion = 999))
        assertEquals(ProjectCodec.SCHEMA_VERSION, ProjectCodec.decode(text).schemaVersion)
        assertTrue(text.contains("schemaVersion"), "the version must be on disk, not implied")
    }

    @Test
    fun aFileFromBeforeAFieldExistedDecodesToTheDefaultForThatField() {
        // Only `meta.name` was ever written. Everything else is a field this build added later.
        val old = """{"meta":{"name":"Old project"}}"""
        val decoded = ProjectCodec.decode(old)
        assertEquals("Old project", decoded.meta.name)
        assertEquals("", decoded.meta.id)
        assertEquals(emptyList<String>(), decoded.meta.tags)
        assertEquals(TraceParams(), decoded.params)
        assertEquals("", decoded.layersJson)
        assertEquals(1, decoded.historyVersion)
        // A file with no version field at all is from before versioning existed.
        assertEquals(1, decoded.schemaVersion)
    }

    @Test
    fun anEmptyJsonObjectIsAValidEmptyProject() {
        val decoded = ProjectCodec.decode("{}")
        assertEquals(ProjectMeta(), decoded.meta)
        assertEquals(TraceParams(), decoded.params)
    }

    @Test
    fun aFileFromANewerBuildOpensAndItsUnknownFieldsAreIgnored() {
        val future = """
            {"schemaVersion":42,
             "meta":{"name":"From the future","colourProfile":"display-p3","tags":["a"]},
             "params":{"styleId":"minimal","neuralStyle":{"weightsId":"x"}},
             "layersJson":"",
             "historyVersion":9,
             "collaborators":[{"id":"u1"}]}
        """.trimIndent()
        val decoded = ProjectCodec.decode(future)
        assertEquals("From the future", decoded.meta.name)
        assertEquals(listOf("a"), decoded.meta.tags)
        assertEquals("minimal", decoded.params.styleId)
        assertEquals(9, decoded.historyVersion)
        assertEquals(42, decoded.schemaVersion)
    }

    @Test
    fun parametersAreClampedOnTheWayInBecauseADiskFileIsUntrusted() {
        val hostile = """
            {"params":{"preprocess":{"workingLongEdge":9999999},
                       "output":{"fitError":-2.0,"corner":900.0},
                       "edge":{"cannyLow":-0.4}}}
        """.trimIndent()
        val p = ProjectCodec.decode(hostile).params
        assertEquals(8192, p.preprocess.workingLongEdge)
        assertTrue(p.output.fitError > 0f)
        assertEquals(180f, p.output.corner)
        assertEquals(EdgeParams.AUTO_THRESHOLD, p.edge.cannyLow)
        assertEquals(p, p.sanitized(), "decode must hand back an already-legal tree")
    }

    @Test
    fun somethingThatIsNotJsonAtAllRaisesInsteadOfDecodingToAnEmptyProject() {
        // Returning a blank project here is how the file on disk gets destroyed: the user sees an
        // empty canvas, saves, and the real drawing is gone.
        assertFailsWith<IllegalArgumentException> { ProjectCodec.decode("not json") }
        assertFailsWith<IllegalArgumentException> { ProjectCodec.decode("") }
        assertFailsWith<IllegalArgumentException> { ProjectCodec.decode("{\"meta\":") }
    }

    // -------------------------------------------------------------------------------------------
    // Geometry
    // -------------------------------------------------------------------------------------------

    @Test
    fun aVectorDocumentRoundTripsExactlyIncludingWidthsAndStyles() {
        val original = sampleDocument()
        val decoded = ProjectCodec.decodeDocument(ProjectCodec.encodeDocument(original))
        assertEquals(original.width, decoded.width)
        assertEquals(original.height, decoded.height)
        assertEquals(original.background, decoded.background)
        assertEquals(original.layers, decoded.layers)
        assertEquals(original, decoded)
    }

    @Test
    fun everySegmentKindSurvivesTheRoundTrip() {
        val decoded = ProjectCodec.decodeDocument(ProjectCodec.encodeDocument(sampleDocument()))
        val segments = decoded.layers.flatMap { it.shapes }.flatMap { it.path.segments }
        assertTrue(segments.any { it is VecSeg.Line }, "lines were lost")
        assertTrue(segments.any { it is VecSeg.Cubic }, "cubics were lost")
        assertTrue(segments.any { it is VecSeg.Quad }, "quads were lost")
    }

    @Test
    fun coordinatesAreNotRounded() {
        // Not stored as an SVG `d` string, precisely so that a save/load cycle cannot move a point.
        // An editor that loses a thousandth of a pixel per save loses a visible amount after fifty.
        val awkward = VecDocument(
            1f, 1f,
            listOf(
                VecLayer(
                    "l", "l",
                    listOf(
                        VecShape(
                            VecPath(
                                VecPoint(0.123456789f, -98765.4321f),
                                listOf(VecSeg.Line(VecPoint(1e-7f, 1.0000001f))),
                            ),
                            VecStyle(),
                        )
                    ),
                )
            ),
        )
        assertEquals(awkward, ProjectCodec.decodeDocument(ProjectCodec.encodeDocument(awkward)))
    }

    @Test
    fun anEmptyLayersJsonIsAnEmptyDrawingAndNotACorruptFile() {
        // A project created and saved before anything was traced has exactly this.
        val decoded = ProjectCodec.decodeDocument("")
        assertEquals(0, decoded.shapeCount())
        assertTrue(decoded.layers.isEmpty())
        assertEquals(0, ProjectCodec.decodeDocument("   ").shapeCount())
    }

    @Test
    fun aDrawingThatIsNotJsonRaises() {
        assertFailsWith<IllegalArgumentException> { ProjectCodec.decodeDocument("<svg/>") }
    }

    @Test
    fun anUnknownStyleEnumNameFallsBackToTheDocumentedDefault() {
        // FillRule, LineCap and LineJoin are stored as names because :core-vector carries no
        // @Serializable; an unrecognised name has to degrade rather than take the file down.
        val text = ProjectCodec.encodeDocument(sampleDocument())
            .replace("\"NONZERO\"", "\"SPIRAL\"")
            .replace("\"SQUARE\"", "\"CHISEL\"")
            .replace("\"MITER\"", "\"KNURLED\"")
        val decoded = ProjectCodec.decodeDocument(text)
        val style = decoded.layers[0].shapes[1].style
        assertEquals(FillRule.EVENODD, style.fillRule)
        assertEquals(LineCap.ROUND, style.cap)
        assertEquals(LineJoin.ROUND, style.join)
    }

    @Test
    fun aTruncatedCoordinateArrayRecoversWhatItCanInsteadOfThrowing() {
        // Hand-built to look like a half-written file: three segments declared, one segment's worth of
        // coordinates present.
        val text = """
            {"schemaVersion":1,"width":10.0,"height":10.0,"background":null,
             "layers":[{"id":"l","name":"l","visible":true,"locked":false,"opacity":1.0,
               "shapes":[{"startX":0.0,"startY":0.0,"kinds":[0,0,0],"coords":[1.0,1.0],
                          "closed":false,"id":"p","widths":null,"stroke":null,"strokeWidth":1.0,
                          "fill":null,"fillRule":"EVENODD","cap":"ROUND","join":"ROUND",
                          "miterLimit":4.0,"opacity":1.0}]}]}
        """.trimIndent()
        val decoded = ProjectCodec.decodeDocument(text)
        assertEquals(1, decoded.layers[0].shapes[0].path.segments.size)
    }

    @Test
    fun aSegmentKindFromAFutureBuildStopsTheWalkRatherThanThrowing() {
        val text = """
            {"width":10.0,"height":10.0,
             "layers":[{"shapes":[{"startX":0.0,"startY":0.0,"kinds":[0,7],
                                   "coords":[1.0,1.0,2.0,2.0,3.0,3.0]}]}]}
        """.trimIndent()
        val decoded = ProjectCodec.decodeDocument(text)
        assertEquals(1, decoded.layers[0].shapes[0].path.segments.size)
    }

    // -------------------------------------------------------------------------------------------
    // The two halves together
    // -------------------------------------------------------------------------------------------

    @Test
    fun aRealTraceSurvivesBeingSavedAndReopened() {
        val src = RgbaImage(96, 96).fill(RgbaImage.argb(255, 255, 255, 255))
        val black = RgbaImage.argb(255, 0, 0, 0)
        for (y in 0 until 96) for (x in 44 until 52) src[x, y] = black
        for (x in 0 until 96) for (y in 44 until 52) src[x, y] = black

        val params = TraceParams(edge = EdgeParams(engine = EdgeEngine.ADAPTIVE, adaptiveRadius = 10))
        val traced = Pipeline.run(src, params, classify = false)
        assertTrue(traced.document.shapeCount() > 0, "notes were ${traced.notes}")

        val saved = ProjectCodec.encode(
            ProjectDocument(
                meta = sampleMeta().copy(sourceWidth = 96, sourceHeight = 96),
                params = params.sanitized(),
                layersJson = ProjectCodec.encodeDocument(traced.document),
            )
        )
        val reopened = ProjectCodec.decode(saved)
        assertEquals(params.sanitized(), reopened.params)
        assertEquals(traced.document, ProjectCodec.decodeDocument(reopened.layersJson))
    }

    @Test
    fun theEncodedFormIsReadableTextSoAHumanCanDiffIt() {
        val text = ProjectCodec.encode(ProjectDocument(meta = sampleMeta()))
        assertTrue(text.contains("\n"), "prettyPrint is on for a reason")
        assertTrue(text.contains("Grandmother's teapot"))
        // encodeDefaults, so "absent" and "at its default" are not the same thing on disk.
        assertTrue(text.contains("historyVersion"))
        assertTrue(text.contains("styleId"))
    }
}
