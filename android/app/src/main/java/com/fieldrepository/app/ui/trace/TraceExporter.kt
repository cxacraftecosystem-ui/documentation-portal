package com.fieldrepository.app.ui.trace

import androidx.compose.runtime.Immutable

/**
 * **THE SEAM BETWEEN "SAVE THIS DRAWING AS A PDF" AND WHATEVER RUNS THE VENDORED WRITERS.**
 *
 * ── WHY THERE IS A SECOND SEAM AT ALL ─────────────────────────────────────────────────────────
 *
 * `TraceEngine.kt` already draws the boundary for TRACING, and a finished trace hands back one written
 * artefact: [TraceResult.svg]. That is the right thing for it to hand back — it is what the record is
 * offered and it is the string the cross-runtime parity harness compares exactly — but it is not enough
 * to write a PDF, an EPS, a DXF or a PNG. Every one of those writers takes a `VecDocument`, and a
 * `VecDocument` never crosses a boundary: a 50,000-path trace is roughly a million coordinates, and as
 * objects that is a million allocations.
 *
 * So this seam asks for BYTES, and hands back the flat arrays the engine already produced.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * ALL FIVE FORMATS WRITE ON THIS HANDSET, BY THREE DIFFERENT ROUTES
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [traceExportPlan] chooses between them, in the order of what each needs:
 *
 *  * **SVG** needs nothing. The engine's own writer ran on the way out and its string is in hand; see
 *    this file's proof below that re-printing it here would be a second SVG writer.
 *  * **PNG** needs the geometry and this device's own encoder. `Bitmap.compress` here,
 *    `canvas.toBlob` in the browser, on the engine's own rule that the platform layer owns the pixel
 *    formats it already has an encoder for.
 *  * **PDF, EPS and DXF** need the vendored writers, and [TraceEngineExporter] is the implementation
 *    that calls them — `:core-export`'s `PdfWriter`, `EpsWriter` and `DxfWriter`, compiled into this
 *    APK.
 *
 * ── AND WHY THE GEOMETRY GOES BACK IN RATHER THAN A DOCUMENT BEING KEPT ───────────────────────
 *
 * The cheap-looking alternative is for the runtime to RETAIN the last traced `VecDocument` and write
 * from it. It is the wrong shape. A document at the shape ceiling is tens of megabytes; a researcher
 * who traces a sheet, looks at it, decides, and then presses Save may be minutes past the trace; and
 * those megabytes would be held all that time against the composition, the draft store and every bitmap
 * the app has open — exactly the heap `TraceRuntime.kt` refuses oversized traces to protect. Passing
 * the geometry in is stateless, costs one walk on a button press, and cannot fail that way.
 *
 * ── WHAT THIS SEAM DELIBERATELY DOES NOT DO ───────────────────────────────────────────────────
 *
 * **It never touches the engine's own SVG string.** [traceExportPlan] routes every SVG save straight to
 * [TraceResult.svg], and that is a PROOF rather than an approximation: the SVG in hand was written by
 * `SvgExport.export` with precision 2, metadata off and layers flattened, which is exactly what
 * [TraceEngineExporter] would ask for — over the same document, with the document's own background,
 * because [traceExportBackground] is a pass-through. So re-printing, re-indenting or "tidying" it here
 * would be a second SVG writer in a second language — the divergence the whole vendoring discipline
 * exists to prevent — as well as putting the saved copy out of step with the file the record holds.
 *
 * **The proof depends on the export not choosing a background of its own.** The moment somebody adds an
 * export-time white that the traced document does not have, this shortcut stops being valid for that
 * case and the SVG must route through the exporter like the other four. That is the reason
 * [traceExportBackground] exists as a named pass-through instead of an argument anybody can fill in.
 *
 * ── MAIN-SAFETY AND CANCELLATION, INHERITED RATHER THAN RESTATED ──────────────────────────────
 *
 * [TraceEngineExporter.export] is a `suspend fun` for the reasons [TraceEngineRuntime] gives at length:
 * an implementation puts its own `withContext` inside, because every caller here is a composable's
 * scope and that is the main thread.
 *
 * A cancelled export throws `CancellationException` and **must not be reported as a failure**. Writing
 * a file is not a pipeline run, so there are no stages and no progress: the longest of these is one
 * marshalling pass plus, for the PNG, one rasterisation. The card shows a working line, not a bar.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * What an export asks for
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One export, fully specified.
 *
 * ── THE GEOMETRY IS `TraceWire.kt`'s, NOT A SECOND SHAPE ──────────────────────────────────────
 *
 * [TraceGeometry] is the flat-array mirror that file already declares and validates. It is used here
 * rather than wrapped, copied or re-abstracted, under its own rule: "no other file in this app holds
 * geometry in any other shape". The moment there were two shapes there would be a conversion, and a
 * conversion is a place for one client to disagree with the other about what a cubic segment is.
 *
 * [width] and [height] are the DOCUMENT's own frame — the source photograph's size, or the rectified
 * page — carried beside the geometry because the geometry does not know them and both the writers'
 * output size and [tracePngScale] need them. They come straight off the result; nothing here derives
 * them from the coordinates, which would be a bounding box rather than a frame and would move the
 * artwork relative to the photograph. Somebody who traces a photo and then imports the SVG over it
 * expects the two to line up.
 *
 * ── THREE OPTIONS OUT OF `ExportOptions`' TEN, AND THE SEVEN ARE NOT AN OVERSIGHT ─────────────
 *
 * `ExportOptions` has ten fields (`format`, `width`, `height`, `scale`, `background`, `quality`,
 * `dpi`, `precision`, `includeMetadata`, `flattenLayers`). This exposes three, because the other seven
 * have one correct answer here and a control over any of them would be a way to get it wrong:
 *
 *  * `width`/`height` stay 0, which means "the document's own size" — see the frame note above.
 *  * `scale` is derived, never chosen: 1 for every vector format, [tracePngScale] for the PNG.
 *  * `quality` is JPEG-only and JPEG is not offered.
 *  * `precision` stays at the engine's default of 2. A coordinate printed to two decimals on a 400px
 *    document is 4.75 µm across an A4 column — a fifth of a 1200-dpi imagesetter dot. There is nothing
 *    to buy by moving it and a parity story to lose.
 *  * `includeMetadata` stays FALSE. Trap 2 in `TraceExport.kt`'s header is the argument, and it is the
 *    one place this build deliberately differs from the web, which passes true.
 *  * `flattenLayers` stays false, so the single layer keeps its name — which the DXF writer turns into
 *    the layer a CAD operator assigns a tool by.
 *  * `dpi` is [TRACE_PNG_DPI], whose own doc-comment explains why it is 72 and not 300.
 *
 * A runtime therefore fills those seven from this file's constants and does not take instructions about
 * them. An option surface is a place for two clients to diverge, and this one is as small as the five
 * formats allow.
 */
@Immutable
class TraceExportRequest(
    /** The traced geometry, exactly as the runtime decoded it. Never re-shaped. */
    val geometry: TraceGeometry,
    /** The document's own frame width. See the class note. */
    val width: Int,
    /** The document's own frame height. */
    val height: Int,
    val format: TraceExportFormat,
    /**
     * The DOCUMENT'S OWN background, passed through rather than chosen — [traceExportBackground].
     *
     * Never null-as-a-shortcut. `ExportOptions.background = null` means "leave the document alone" to
     * the vector writers and "transparent" to the rasteriser, so passing the document's real value is
     * what keeps a PDF and a PNG of one drawing from disagreeing about their ground.
     */
    val background: Int?,
    /**
     * The sentence to write into the file where the writer has somewhere to put one.
     *
     * **NOWHERE ON THIS BUILD**, because `ExportOptions` has no title field — see
     * [traceProvenanceNote], which says where the note would land and why it is still built. Carried
     * and dropped, which [traceExportLosses] states on screen rather than leaving to be discovered.
     */
    val provenanceNote: String,
)

/** Bytes, or a refusal in one sentence. Cancellation is NEITHER — see [TraceEngineExporter.export]. */
sealed class TraceExportOutcome {
    /**
     * The encoded file.
     *
     * A `ByteArray` and not a `File`: whoever writes it to the flash is [traceSaveExport], which is the
     * only place in this feature that knows what a Downloads folder is. Splitting it that way is what
     * lets `TraceExportTest` exercise the naming, the losses and the plan on a JVM with no device
     * attached.
     *
     * SIZE IS BOUNDED BY THE DRAWING AND IS NOT ENORMOUS. A 20,975-shape trace was measured as a 1.5 MB
     * result string; a 2048px PNG of line art compresses to a few hundred kilobytes because it is very
     * nearly bilevel.
     */
    class Done(val bytes: ByteArray) : TraceExportOutcome()

    /**
     * The file could not be written, in one sentence a researcher can act on.
     *
     * A REFUSAL IS NOT AN EXCEPTION, for [TraceOutcome.Refused]'s reason: the caller has to print it,
     * and a sentence that has to be printed is a value. Exceptions are for the cases nobody wrote a
     * sentence for.
     */
    class Refused(val reason: String) : TraceExportOutcome()
}

/* ────────────────────────────────────────────────────────────────────────────
 * The exporter
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Whatever runs the vendored engine's writers on this phone.
 *
 * SEPARATE FROM [TraceEngineRuntime] AS AN INTERFACE, THOUGH ON THIS BUILD ONE OBJECT COULD SERVE
 * BOTH. Tracing and writing can fail for unrelated reasons and are asked about at different moments, so
 * a host composing [TraceExportCard] with no engine behind it can still be handed an exporter, and a
 * future build that loses one capability can say so without claiming the other went with it.
 *
 * @throws kotlinx.coroutines.CancellationException when the job is cancelled. Not a failure.
 */
interface TraceEngineExporter {

    /**
     * Why this phone cannot write the PDF, the EPS and the DXF, or null when it can.
     *
     * **NULL ON EVERY BUILD OF THIS APP** — [TraceEngineExporter] has one implementation and it answers
     * null, because `:core-export` is compiled into the APK by the same build that compiles this file.
     * The field stays because it is what [traceExportPlan] routes on, and what lets a host with nothing
     * behind it say so: a sentence here is the difference between a greyed row that explains itself and
     * a button that fails on the press.
     *
     * A sentence and not a boolean, on the rule every refusal in this feature is held to — a dead
     * button teaches a researcher the feature is broken, and a sentence teaches them what still works.
     *
     * IT NEVER GOVERNED THE PNG. That format leaves through [TraceExportPlan.FromPlatformRaster] and
     * never reaches this interface, so even an exporter that refused everything left two of the five
     * formats working.
     */
    val refusal: String?

    /** Write one file. Main-safe: the implementation puts its own `withContext` inside. */
    suspend fun export(request: TraceExportRequest): TraceExportOutcome
}

/**
 * The exporter for a host with no writers behind it, which SAYS SO and does nothing else.
 *
 * **NOTHING IN THIS APP MOUNTS IT.** It is kept because [TraceExportCard] deliberately takes primitives
 * instead of a runtime so that a host with nothing behind it can still compose the card — a preview, a
 * screen that only wants the SVG door — and such a host needs an exporter to hand it. That is the same
 * reason [TRACE_NO_GEOMETRY_SENTENCE] exists for a caller with no geometry.
 *
 * [export] answers a [TraceExportOutcome.Refused] rather than throwing. A press that reaches this is a
 * researcher who chose a format this host cannot write, which is a sentence and not a crash.
 */
class TraceExporterUnavailable(reason: String = TRACE_NO_EXPORTER_SENTENCE) : TraceEngineExporter {
    override val refusal: String = reason

    override suspend fun export(request: TraceExportRequest): TraceExportOutcome =
        TraceExportOutcome.Refused(refusal)
}

/**
 * The sentence a host with no writers behind it shows.
 *
 * **UNREACHABLE IN THIS APP AS SHIPPED**, because [rememberTraceExporter] mounts the real one and its
 * `refusal` is null. It is the default of [TraceExporterUnavailable].
 *
 * IT NAMES WHAT WORKS RATHER THAN ONLY WHAT DOES NOT, which is the shape every refusal in this feature
 * is held to and which the web reached independently: its own writer-unavailable sentence ends "The SVG
 * download needs nothing extra and works either way."
 */
const val TRACE_NO_EXPORTER_SENTENCE: String =
    "This phone can save the drawing as an SVG, which is the full vector line work, and as a " +
        "picture. PDF, EPS and DXF are not available here yet — the portal can write all three from " +
        "this same photograph on a laptop when you next have a connection."

/* ────────────────────────────────────────────────────────────────────────────
 * Which route a save takes
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * How one save will be satisfied: from the string already in hand, or by asking the exporter.
 *
 * A VALUE RATHER THAN A BRANCH INSIDE THE BUTTON, so that the card can grey a row it cannot satisfy
 * BEFORE a researcher presses it, and so that `TraceExportTest` can pin the routing without an
 * exporter, a runtime or a device. The web has the same split and learned it the hard way — its own
 * writer-unavailable sentence exists because a failed dynamic import surfaced through a catch-all as
 * "Failed to fetch dynamically imported module: …/chunk-a91f2c.js".
 */
sealed class TraceExportPlan {
    /**
     * The bytes are the engine's own SVG string, UTF-8, unaltered.
     *
     * See this file's header for the proof that this is the same call the exporter would make, and for
     * what would invalidate it.
     */
    data object FromTraceSvg : TraceExportPlan()

    /**
     * The bytes are a picture this device paints and this device's own PNG encoder writes.
     *
     * **THE THIRD ROUTE, AND THE REASON A BUILD WITH NO WRITERS WOULD STILL OFFER TWO FORMATS.** It
     * needs no marshalling pass: `TracePlates.renderTrace` already walks [TraceGeometry] into an
     * `android.graphics.Path` in production for the comparator, and `Bitmap.compress` is the platform's
     * PNG encoder. [traceRenderPngBytes] is the whole of it.
     *
     * IT IS THE WEB'S ARRANGEMENT AND NOT A HANDSET SHORTCUT. Its PNG row records that the portal
     * writes its PNG with `canvas.toBlob` rather than with the engine's encoder, on the rule that "the
     * platform layer owns the pixel formats the browser already has an encoder for". Two platforms, two
     * platform encoders, and one cap — [tracePngSize] — so the two files are the same number of pixels
     * across.
     *
     * WHAT IT NEEDS THAT THE SVG DOES NOT: the geometry. A trace whose shapes did not survive can still
     * save its SVG and cannot paint anything, which is why [traceExportPlan] takes `hasGeometry` and
     * why that is a DIFFERENT refusal from a host with no writers.
     */
    data object FromPlatformRaster : TraceExportPlan()

    /** The runtime has to write it. */
    data object FromExporter : TraceExportPlan()

    /**
     * Nothing can write it on this handset, and this is the sentence to show.
     *
     * Reached only for a format the SVG shortcut does not cover, on a build whose exporter refuses.
     * Carries the exporter's OWN refusal rather than a generic one, because the causes a researcher
     * might meet are different sentences with different remedies.
     */
    data class Refused(val reason: String) : TraceExportPlan()
}

/**
 * Decide the route for one format.
 *
 * PURE, and it takes the exporter's refusal as a STRING rather than the exporter itself, so the whole
 * routing rule is testable with no interface to stub. Pass null when an exporter is present and
 * willing.
 *
 * ── THREE ROUTES, IN THE ORDER OF WHAT THEY NEED ──────────────────────────────────────────────
 *
 * The SVG needs nothing — the string is in hand. The PNG needs the geometry and this device's own
 * encoder. The other three need the vendored writers. Asking the questions in that order is what lets a
 * phone with no writers still offer two formats.
 *
 * ── "NO GEOMETRY" AND "NO WRITERS" ARE TWO SENTENCES, NOT ONE ─────────────────────────────────
 *
 * They look like one fact to a researcher and are not. A build with no writers but with geometry saves
 * the SVG **and** the picture; a trace that came back with no geometry saves the SVG alone. Those are
 * different sets of working controls, so they are different sentences with different remedies: one fact
 * spelled two ways needs one sentence, and two facts need two.
 */
fun traceExportPlan(
    format: TraceExportFormat,
    exporterRefusal: String?,
    hasGeometry: Boolean = true,
): TraceExportPlan {
    if (format.id == "svg") return TraceExportPlan.FromTraceSvg
    if (!hasGeometry) return TraceExportPlan.Refused(TRACE_NO_GEOMETRY_SENTENCE)
    if (format.id == "png") return TraceExportPlan.FromPlatformRaster
    if (exporterRefusal != null) return TraceExportPlan.Refused(exporterRefusal)
    return TraceExportPlan.FromExporter
}

/**
 * The sentence for a trace whose shapes are not in hand.
 *
 * ── WHEN THIS IS REACHED, WHICH IS ALMOST NEVER, AND WHY IT IS STILL WRITTEN ──────────────────
 *
 * [TraceResult.geometry] is filled from the decoded result at the one place a result is built, and that
 * field is not nullable — so on this build the answer is always yes. It is nullable on the RESULT, and
 * this sentence exists, because [TraceExportCard] deliberately takes primitives rather than a result
 * object precisely so it can be composed by a host that has no geometry to give. A host in that
 * position must get a sentence naming what still works, not a button that fails on the press.
 */
const val TRACE_NO_GEOMETRY_SENTENCE: String =
    "The drawing's shapes did not come back with this trace, so the picture and the three take-away " +
        "formats cannot be made from it here. The SVG still saves — the engine wrote it on the way " +
        "out and it is the full vector line work. Trace the sheet again if you need the rest."
