package com.fieldrepository.app.ui.trace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.offlinetracer.export.ExportFormat
import com.offlinetracer.export.ExportOptions
import com.offlinetracer.export.Exporter
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * **THE PDF, THE EPS AND THE DXF, WRITTEN ON THE HANDSET BY THE VENDORED WRITERS.**
 *
 * `TraceExporter.kt` declares [TraceEngineExporter] and this is its one real implementation.
 * `:core-export` is compiled into this APK and carries `PdfWriter`, `EpsWriter` and `DxfWriter`
 * outright, so [refusal] is null and stays null.
 *
 * ── THE ONE THING THIS HAS TO BUILD: A DOCUMENT OUT OF FLAT ARRAYS ────────────────────────────
 *
 * Every writer takes a `VecDocument`, and what the surface holds is [TraceGeometry] — the flat mirror
 * `TraceWire.kt` declares and forbids re-modelling. [traceDocumentOf] is the exact inverse of
 * [traceGeometryOf], and it answers the same three questions the web's own `geometryToDocument`
 * answered for the portal, the same way, because two answers would be two drawings:
 *
 *  * **One layer, named "Line art"** ([TRACE_LAYER_NAME]). The flat arrays concatenate shapes across
 *    layers in layer order and keep no boundary between them, so layer identity is not recoverable here
 *    and inventing one would be a fiction. It costs nothing: the pipeline assembles exactly one layer,
 *    so there has never been a second one to lose. The name is load-bearing in exactly one format —
 *    `DxfWriter` emits one DXF layer per `VecLayer` and a CAD operator assigns a tool per layer — and
 *    the alternative is that writer's own fallback, which says nothing to the person opening it in a
 *    machine controller.
 *  * **The same shape ceiling, and the same sentence when it bites.** [TRACE_MAX_SHAPES] is already the
 *    portal's own to the digit, and [traceTruncationNote] is already its sentence. Somebody who saves
 *    one drawing as an SVG and again as a PDF must get one drawing, so both stop at the same count and
 *    report the cut the same way.
 *  * **A style index outside the table draws as a plain black hairline** rather than being dropped: a
 *    shape whose paint did not survive is still a line somebody drew.
 *
 * ── AND THE TWO THINGS THE VENDORED WRITERS WILL NOT DO, MEASURED IN THEIR SOURCE ─────────────
 *
 * **`includeMetadata = false`, for the reason [traceSvgOf] sets it: branding.** `PdfWriter` writes
 * `/Producer (Offline Tracer) /Creator (Offline Tracer) /Title (Offline Tracer export)` into an `/Info`
 * object, and `EpsWriter` writes `%%Title: Offline Tracer export`. Another product's name has no
 * business in an archived record. False switches that off for the PDF entirely — the whole `/Info`
 * object is omitted — and for the EPS's `%%Title`.
 *
 * **IT DOES NOT SWITCH OFF `%%Creator`, AND THAT IS STATED RATHER THAN HIDDEN.** `EpsWriter` appends
 * `%%Creator: Offline Tracer` UNCONDITIONALLY, outside the `includeMetadata` guard. So an EPS saved from
 * this handset carries that string in its header comments and there is no option that removes it. It is
 * a comment line in a PostScript preamble, not anything a print shop renders, and the remedy — editing
 * a vendored file — would break the SHA-256 in `android/UPSTREAM-MANIFEST-KOTLIN.txt` and the parity
 * discipline it exists to enforce. Recorded here so nobody discovers it in a file that has already been
 * sent, and said on screen by [TRACE_EXPORT_ENGINE_NAME_SENTENCE]. The PDF and the DXF are clean.
 *
 * **[TraceExportRequest.provenanceNote] CANNOT REACH THESE FILES.** `ExportOptions` has ten fields and
 * none of them is a title, so the only strings those two slots can hold are the hard-coded ones above.
 * The note is therefore carried and dropped for all three formats, which [traceExportLosses] states on
 * screen. Writing the note in by hand would mean this file assembling PDF or PostScript syntax beside a
 * vendored writer, which is the second speller of one format that the whole vendoring discipline exists
 * to prevent.
 *
 * ── MAIN-SAFETY AND CANCELLATION ──────────────────────────────────────────────────────────────
 *
 * [export] does its work on `Dispatchers.Default` and checks for cancellation twice — once before
 * building the document and once before writing — because both halves are single synchronous calls that
 * cannot be interrupted from inside. A cancelled export throws `CancellationException` and is never
 * reported as a failure.
 */

/**
 * The name the single layer carries, and therefore the DXF layer a CAD operator sees.
 *
 * The web's own `TRACE_LAYER_NAME`, verbatim. `DxfWriter` upper-cases it and replaces the space, so it
 * arrives in the file as `LINE_ART`.
 */
const val TRACE_LAYER_NAME: String = "Line art"

/** The layer's id. Never shown; `DxfWriter` falls back to it only if the name is empty. */
private const val TRACE_LAYER_ID: String = "trace"

/**
 * What [traceDocumentOf] produced: the document, how much of the drawing reached it, and the cut. The
 * mirror of [TraceSvg].
 */
class TraceDocument(
    val document: VecDocument,
    val shapesWritten: Int,
    /** Non-null exactly when [TRACE_MAX_SHAPES] truncated the drawing. Ready to show. */
    val truncationNote: String?,
)

/**
 * The flat arrays, back as the document the vendored writers take.
 *
 * **THE EXACT INVERSE OF [traceGeometryOf]**, walking the layout that function wrote and
 * [TraceGeometry]'s KDoc specifies: a shape's coordinate run begins with its start point, then two
 * floats per line, four per quad and six per cubic, with the two `starts` arrays one longer than the
 * shape count so an extent is a subtraction.
 *
 * [TraceGeometry.validate] is what makes the walk safe to write without a bounds check per read — it
 * proves every extent increases, every verb is one of the three, and every shape's coordinate count is
 * exactly what its verbs need. It is called here rather than assumed, because this function is
 * reachable from an export button and the arrays it is handed came off a result that may have been
 * sitting in a composition for minutes.
 *
 * @param width the DOCUMENT's own frame width, not a bounding box of the coordinates. See
 *   [TraceExportRequest]: a bounding box would move the artwork relative to the photograph.
 * @param background the document's own background, passed through — [traceExportBackground].
 * @throws TraceHostFailure when the geometry is self-inconsistent.
 */
fun traceDocumentOf(
    geometry: TraceGeometry,
    width: Int,
    height: Int,
    background: Int?,
): TraceDocument {
    geometry.validate()

    val shapeCount = geometry.shapeCount
    val written = if (shapeCount > TRACE_MAX_SHAPES) TRACE_MAX_SHAPES else shapeCount
    val shapes = ArrayList<VecShape>(written)

    for (i in 0 until written) {
        val coordStart = geometry.coordStarts[i]
        val verbFrom = geometry.verbStarts[i]
        val verbTo = geometry.verbStarts[i + 1]

        var c = coordStart
        val start = VecPoint(geometry.coords[c], geometry.coords[c + 1])
        c += 2

        val segments = ArrayList<VecSeg>(verbTo - verbFrom)
        for (v in verbFrom until verbTo) {
            when (geometry.verbs[v]) {
                TRACE_VERB_LINE -> {
                    segments.add(VecSeg.Line(VecPoint(geometry.coords[c], geometry.coords[c + 1])))
                    c += 2
                }
                TRACE_VERB_QUAD -> {
                    segments.add(
                        VecSeg.Quad(
                            VecPoint(geometry.coords[c], geometry.coords[c + 1]),
                            VecPoint(geometry.coords[c + 2], geometry.coords[c + 3]),
                        )
                    )
                    c += 4
                }
                else -> {
                    // CUBIC. `validate` has already refused any fourth value, so this is exhaustive
                    // rather than a silent default — an `else` that could swallow an unknown verb is why
                    // that check runs first.
                    segments.add(
                        VecSeg.Cubic(
                            VecPoint(geometry.coords[c], geometry.coords[c + 1]),
                            VecPoint(geometry.coords[c + 2], geometry.coords[c + 3]),
                            VecPoint(geometry.coords[c + 4], geometry.coords[c + 5]),
                        )
                    )
                    c += 6
                }
            }
        }

        shapes.add(
            VecShape(
                path = VecPath(start = start, segments = segments, closed = geometry.isClosed(i)),
                style = traceVecStyleOf(geometry.styleTable.getOrNull(geometry.styleIndex[i])),
            )
        )
    }

    return TraceDocument(
        document = VecDocument(
            width = traceDimension(width),
            height = traceDimension(height),
            layers = listOf(
                VecLayer(id = TRACE_LAYER_ID, name = TRACE_LAYER_NAME, shapes = shapes)
            ),
            background = background,
        ),
        shapesWritten = written,
        truncationNote = traceTruncationNote(shapeCount, written),
    )
}

/**
 * One row of the style table, back as the engine's own `VecStyle`.
 *
 * A NULL STYLE IS A BLACK HAIRLINE, NOT A DROPPED SHAPE. A style index outside the table means the
 * geometry was not built by the serialiser this expects, and a line somebody actually drew is worth
 * more than a consistent paint. `VecStyle`'s own defaults are a black stroke at 1.5, so the only
 * overrides here are `cap` and `join`, which the web pins to BUTT/MITER for this case.
 *
 * The three enums arrive as STRINGS because [TraceStyle] keeps them as strings — its KDoc argues why —
 * and an unrecognised value falls back to the engine's own default rather than throwing. A newer
 * upstream that adds a join style must not be a crash on a phone in a village.
 */
private fun traceVecStyleOf(style: TraceStyle?): VecStyle {
    if (style == null) return VecStyle(cap = LineCap.BUTT, join = LineJoin.MITER)
    return VecStyle(
        stroke = style.stroke,
        strokeWidth = if (style.strokeWidth.isFinite() && style.strokeWidth > 0f) style.strokeWidth else 1f,
        fill = style.fill,
        fillRule = if (style.fillRule == "NONZERO") FillRule.NONZERO else FillRule.EVENODD,
        cap = when (style.cap) {
            "ROUND" -> LineCap.ROUND
            "SQUARE" -> LineCap.SQUARE
            else -> LineCap.BUTT
        },
        join = when (style.join) {
            "ROUND" -> LineJoin.ROUND
            "BEVEL" -> LineJoin.BEVEL
            else -> LineJoin.MITER
        },
        miterLimit = if (style.miterLimit.isFinite() && style.miterLimit > 0f) style.miterLimit else 4f,
        opacity = if (style.opacity.isFinite()) style.opacity.coerceIn(0f, 1f) else 1f,
    )
}

/**
 * A page dimension the writers can use: at least one, never a NaN.
 *
 * Every vector writer divides by the document size to work out its scale, substituting 1 for anything
 * not finite and positive — so a zero here would not crash, it would silently produce a page of the
 * wrong size. Clamping at the source means one answer instead of four writers each rescuing themselves.
 */
private fun traceDimension(value: Int): Float = if (value < 1) 1f else value.toFloat()

/* ────────────────────────────────────────────────────────────────────────────
 * The exporter
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The [TraceEngineExporter] backed by `:core-export`.
 *
 * **[refusal] IS NULL AND CANNOT BE ANYTHING ELSE.** The writers are compiled into the APK by the same
 * build that compiles this file, so there is no device-shaped question left to ask. A format nothing can
 * write still refuses, and it does so per press with a sentence naming that format, which is a better
 * answer than one string decided at construction for all five.
 *
 * Stateless and allocation-free, so `remember` is a formality rather than a cache.
 */
class TraceExporterImpl : TraceEngineExporter {

    override val refusal: String? = null

    override suspend fun export(request: TraceExportRequest): TraceExportOutcome =
        withContext(Dispatchers.Default) {
            val format = traceExportEngineFormat(request.format)
                ?: return@withContext TraceExportOutcome.Refused(
                    traceUnwritableSentence(request.format)
                )

            // BEFORE THE DOCUMENT AND BEFORE THE WRITE. Both are single synchronous calls over as many
            // as 200,000 shapes and neither can be interrupted from inside, so these two checks are the
            // whole of this route's cancellation granularity — one level coarser than a trace's,
            // because there are no stages here.
            traceEnsureActive()

            val built = try {
                traceDocumentOf(
                    geometry = request.geometry,
                    width = request.width,
                    height = request.height,
                    background = request.background,
                )
            } catch (failure: TraceHostFailure) {
                return@withContext TraceExportOutcome.Refused(failure.message.orEmpty())
            }

            traceEnsureActive()

            val bytes = try {
                Exporter.export(
                    doc = built.document,
                    // NULL, SO THE WRITER RASTERISES IF IT NEEDS TO. It never needs to here: this
                    // exporter is reached only for PDF, EPS and DXF, and all three are vector writers
                    // that ignore the argument entirely. Passing a plate would be handing a raster to a
                    // path that does not read one.
                    raster = null,
                    o = ExportOptions(
                        format = format,
                        // 0/0 means "the document's own size" — the engine's own rule, and the frame
                        // the geometry's coordinates are already in.
                        width = 0,
                        height = 0,
                        scale = 1f,
                        background = request.background,
                        // A PNG-NAMED CONSTANT ON THREE VECTOR FORMATS, DELIBERATELY. `dpi` is what
                        // converts document units to physical ones in a PDF, an EPS and a DXF, and 72 is
                        // the PostScript/PDF identity — one document pixel becomes one point. Its own
                        // docblock argues the choice: nobody in this flow has measured how big the sheet
                        // was, so 300 would assert a physical size nobody knows.
                        dpi = TRACE_PNG_DPI,
                        precision = 2,
                        // See the file header. This is the switch that keeps another product's name out
                        // of a file that reaches a record.
                        includeMetadata = false,
                        // FALSE, so the layer structure survives. There is one layer either way, and
                        // `DxfWriter` is the writer that reads it — flattening would cost the layer name
                        // a CAD operator assigns a tool by.
                        flattenLayers = false,
                    ),
                )
            } catch (unsupported: UnsupportedOperationException) {
                // `Exporter.export` throws for the formats it has no writer for. None of them is offered
                // by `TRACE_EXPORT_FORMATS`, so reaching this is a table that gained a row without a
                // route — a wiring bug, which is why the sentence names the format rather than
                // apologising for the device.
                return@withContext TraceExportOutcome.Refused(
                    traceUnwritableSentence(request.format)
                )
            } catch (memory: OutOfMemoryError) {
                // A DRAWING TOO BIG TO HOLD TWICE. The document and the encoded bytes are both live at
                // the moment of return, and at the shape ceiling that is tens of megabytes on a heap
                // that has just finished a trace. It is caught rather than allowed to kill the app
                // because the trace itself is still on screen and still saveable as an SVG.
                return@withContext TraceExportOutcome.Refused(TRACE_EXPORT_MEMORY_SENTENCE)
            }

            TraceExportOutcome.Done(bytes)
        }
}

/**
 * [TraceExportFormat.engineFormat] as the engine's own enum, or null when it names nothing.
 *
 * THE STRING IS THE SEAM AND THIS IS THE ONLY PLACE IT IS RESOLVED, which is exactly what that field's
 * KDoc asks for: "The runtime maps this string; nothing here does." A second Kotlin register of
 * `ExportFormat`'s ten members is the failure that file warns about.
 *
 * Null rather than a throw for an unknown name, because a table row naming a format the engine dropped
 * is a sentence on a button and not a crash on a phone.
 */
private fun traceExportEngineFormat(format: TraceExportFormat): ExportFormat? {
    val named = ExportFormat.entries.firstOrNull { it.name == format.engineFormat } ?: return null
    return if (Exporter.supports(named)) named else null
}

/**
 * The sentence for a format this build cannot write, naming the format and what still works.
 *
 * NAMES WHAT WORKS RATHER THAN ONLY WHAT DOES NOT, which is the shape every refusal in this feature is
 * held to. Unreachable on this build — all five rows of [TRACE_EXPORT_FORMATS] map to a format
 * `Exporter.supports` — and written anyway, because the alternative when a row is added without a route
 * is a button that does nothing.
 */
private fun traceUnwritableSentence(format: TraceExportFormat): String =
    "This app cannot write ${format.label} files. The SVG and the picture still save, and the portal " +
        "can write every format from this same photograph on a laptop when you next have a connection."

/**
 * The sentence for an export that ran out of memory.
 *
 * IT NAMES THE REMEDY THAT ACTUALLY CHANGES THE ARITHMETIC — fewer shapes — rather than "try again",
 * which on a heap this full will fail the same way. The two controls named are the same two
 * [traceTruncationNote] names for the shape ceiling, because the problem is the same problem in both:
 * this drawing has too many separate paths in it.
 */
const val TRACE_EXPORT_MEMORY_SENTENCE: String =
    "This drawing has too many separate paths for this phone to write out as a file. The SVG still " +
        "saves. Raise “Minimum speck” or “Simplify” and trace again for a drawing that fits."

/**
 * The exporter a host mounts, in one place, so a mount site does not have to know which it is.
 */
@Composable
fun rememberTraceExporter(): TraceEngineExporter = remember { TraceExporterImpl() }

/**
 * `ensureActive` on the current coroutine context.
 *
 * A named one-liner rather than the two-line incantation repeated twice above, so the two cancellation
 * points read as what they are. `withContext(Dispatchers.Default)` does not itself poll for
 * cancellation between two synchronous calls, which is why they have to be written.
 */
private suspend fun traceEnsureActive() {
    kotlin.coroutines.coroutineContext.ensureActive()
}
