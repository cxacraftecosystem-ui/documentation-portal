package com.fieldrepository.app.ui.trace

import androidx.compose.runtime.Immutable

/**
 * **WHAT A TRACED SKETCH CAN BE WRITTEN OUT AS, AND WHAT THAT FILE DOES AND DOES NOT REACH.**
 *
 * ── WHAT THIS FILE IS ─────────────────────────────────────────────────────────────────────────
 *
 * The vendored engine already has the writers — `:core-export`'s `SvgExport`, `DxfWriter`,
 * `EpsWriter`, `PdfWriter` and `PngEncoder`, dispatched by `Exporter.export` — so the handset gets
 * every one of them for free. **What needed writing down is which of them a researcher is offered,
 * what each one loses, and where the file goes.** That is this file, its platform half in
 * `TraceExportFile.kt`, the raster in `TraceExportRaster.kt`, and the card in `TraceExportCard.kt`.
 *
 * **ONE OF THE FIVE IS NOT THE ENGINE'S TO WRITE, ON BOTH CLIENTS.** The PNG is a picture, and every
 * platform this app runs on already has a PNG encoder that is better tested than any bundled one could
 * be. The web's own table records the decision from its side — its PNG is written by `canvas.toBlob`
 * rather than by the engine's own PNG encoder, because "the platform layer owns the pixel formats the
 * browser already has an encoder for". This handset makes the same call with `android.graphics` and
 * `Bitmap.compress`. [tracePngSize] holds the one rule the two platforms must agree on.
 *
 * PURE. No `Context`, no Compose surface, no `android.graphics`, no clock, no randomness — the same
 * split `data/PhotoMeasure.kt` states for its own feature, and for its reason rather than for tidiness:
 * there is no Robolectric in this module, so anything touching the framework is by construction code no
 * unit test can reach. Everything here is pinned by `TraceExportTest`.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE SENTENCE THAT MATTERS MOST ON THIS SCREEN, AND WHY IT IS NOT THE DESIGNER PORTAL'S
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The design-workshop original carries a long paragraph here establishing, from three named backend
 * modules, that a vector export never reaches the ministry report a workshop submits — and it prints
 * that claim on every save. **That paragraph must not be copied into this repository, and this is the
 * whole reason it is not.** There is no report builder in this backend at all: `backend/app/services/`
 * holds `records.py`, `csv_export.py`, `media_queue.py` and their siblings, and nothing that assembles
 * a document with placed images. A sentence naming what "the report" does with an attachment would be a
 * claim about a feature this product does not have, made on a surface that cannot check it — which is
 * precisely the failure the original's own header spends a page warning about, and which it counts as
 * having shipped five times in one day in that repository.
 *
 * **The DISTINCTION the original was protecting is real here and is kept.** A researcher pressing Save
 * and a researcher pressing Attach are doing two different things with two different fates, and
 * neither is obvious from the buttons:
 *
 *  * SAVE writes to this phone's own Downloads folder and offers the share sheet. Nothing goes to a
 *    record, nothing is uploaded, nothing syncs. If the phone is lost, so is that file.
 *  * ATTACH hands ONE derived file to the screen that mounted this panel, which puts it through the
 *    ordinary media door — the same `uploadMedia` a camera capture takes — so it is queued, retried and
 *    held in the offline store exactly like every other capture.
 *
 * [TRACE_EXPORT_KEEP_SENTENCE] and [TRACE_ATTACH_SENTENCE] are those two facts, one each, and every
 * surface in this feature prints one of them rather than phrasing its own.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE TABLE IS THE WEB'S TABLE
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [TRACE_EXPORT_FORMATS] mirrors the web's `EXPORT_FORMATS` row for row — same ids, same order, same
 * extensions, same MIME types, same `attachable` flags, and the hints carried across rather than
 * re-written. That is not deference; it is the same rule the vendored engine is under. One sheet of
 * paper traced on two clients must produce one drawing, and a researcher who saved a DXF on a laptop
 * and cannot find it on the handset has been given two different products.
 *
 * [TRACE_NOT_OFFERED] is the other half and it is a strange thing to ship on purpose. Ten formats exist
 * in `ExportFormat`; five are offered and five are refused IN WRITING, because three finished writers
 * once sat in the engine unreachable from any control for as long as the feature existed, "and nothing
 * anywhere said whether that was a decision or an oversight — which is precisely why it read as an
 * oversight to the audit that found it".
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * TWO TRAPS IN THE ENGINE'S EXPORT OPTIONS THAT A HANDSET CARD WILL OTHERWISE WALK INTO
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * **1. `background = null` MEANS TWO DIFFERENT THINGS IN THE TWO ARMS OF ONE DISPATCHER.** The vector
 * arm leaves the document's own background alone when the option is null. The raster arm clears with
 * the argument and ignores the document entirely, so null means *transparent*. For a document traced
 * with `output.background = null` (the engine's default, and the only spelling of transparent) the two
 * coincide. For a document traced with white they do not, and one press of one control would then
 * produce a white PDF beside a transparent PNG.
 *
 * The rule that makes them agree by construction, and it is what [traceExportBackground] encodes:
 * **an exporter passes the DOCUMENT'S OWN background through, always, and never null.** Then a
 * transparent document keeps null on the vector side and becomes transparent on the raster side, and a
 * white document is rebuilt with the white it already had and cleared to that same white. Every value
 * agrees across both arms with no special case.
 *
 * **2. THE FILES CARRY THE UPSTREAM TRACER'S NAME UNLESS `includeMetadata` IS OFF — AND TURNING IT OFF
 * ALSO REMOVES THE PROVENANCE NOTE.** With it on, `PdfWriter` emits `/Producer (Offline Tracer)` and
 * `EpsWriter` emits `%%Title: Offline Tracer export`. Another product's name has no business in an
 * archived record, so this build passes `false` everywhere — and therefore no file this app writes can
 * carry a note saying which photograph it came from. Both halves are stated on screen by
 * [traceExportLosses] rather than discovered in somebody's inbox, and [TRACE_EXPORT_ENGINE_NAME_SENTENCE]
 * covers the one writer where `false` is not enough.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Sizes — declared above the table, because the table quotes them
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The longest edge a rendered PNG may have.
 *
 * The web's own number and its own argument: a trace of a 4096px photograph would otherwise produce a
 * 4096px canvas, which is 67 MB of RGBA before compression, and 2048 is still about 17 cm across
 * printed at 300 dpi with four vector formats beside it that have no ceiling at all.
 *
 * IT BITES HARDER HERE THAN IT DOES THERE. On the web an oversized raster is a `toBlob` that returns
 * null; on a mid-range handset already holding Compose, a camera preview and an unsaved form it is an
 * `OutOfMemoryError`. So this is not a courtesy cap.
 *
 * DECLARED ABOVE THE TABLE BECAUSE THE TABLE QUOTES IT — the PNG row's hint interpolates this constant
 * rather than repeating the digits, so the number a researcher reads and the number [tracePngScale]
 * enforces cannot drift apart.
 */
const val TRACE_PNG_MAX_EDGE_PX: Int = 2048

/**
 * How far a PNG of this document must be shrunk to sit inside [TRACE_PNG_MAX_EDGE_PX].
 *
 * A CEILING, NEVER AN ENLARGEMENT. The answer is 1.0 for any document whose long edge already fits,
 * because upscaling line art adds no information and multiplies the buffer the note above says a phone
 * dies on. Above the cap it is cap-over-long-edge, so the long edge lands exactly on the cap and the
 * aspect ratio is untouched.
 *
 * LONG EDGE, NOT WIDTH. A portrait sheet is capped on its height; a landscape one on its width. The cap
 * is a statement about the biggest buffer that may be allocated, and the biggest buffer is set by
 * whichever edge is longer.
 *
 * THE DEGENERATE CASE IS NOT HYPOTHETICAL, WHICH IS WHY IT IS NOT AN EXCEPTION. A non-positive
 * dimension reaches these writers — it is the reason the engine sanitises its own dimensions — and a
 * division by zero here would be a crash in the middle of unsaved work. A non-positive edge is
 * therefore read as 1, which lands on 1.0 by the ordinary rule rather than by a special case.
 *
 * THE SAME ARITHMETIC AS THE WEB, DELIBERATELY, so one drawing exported from a handset and from a
 * laptop is the same number of pixels across.
 */
fun tracePngScale(width: Int, height: Int): Double {
    val safeWidth = if (width > 0) width else 1
    val safeHeight = if (height > 0) height else 1
    val longEdge = if (safeWidth > safeHeight) safeWidth else safeHeight
    return (TRACE_PNG_MAX_EDGE_PX.toDouble() / longEdge.toDouble()).coerceAtMost(1.0)
}

/** The pixel size a rendered PNG of one document comes out at. See [tracePngSize]. */
@Immutable
data class TracePngSize(
    val width: Int,
    val height: Int,
    /** True when [TRACE_PNG_MAX_EDGE_PX] actually bit, i.e. the picture is smaller than the drawing. */
    val reduced: Boolean,
)

/**
 * The bitmap a rendered PNG of a [width] x [height] document is allocated at.
 *
 * **THE SAME THREE LINES AS THE WEB'S `exportPngFile`, IN THE SAME ORDER**, because the two clients
 * must hand one drawing to one printer at one size:
 *
 *     const scale  = Math.min(1, maxEdge / Math.max(sourceWidth, sourceHeight));
 *     const width  = Math.max(1, Math.round(sourceWidth  * scale));
 *     const height = Math.max(1, Math.round(sourceHeight * scale));
 *
 * `Math.round` and Kotlin's `Math.round` are the same rule for the values that reach here — both round
 * half AWAY from zero for a positive number — and every value that reaches here is positive because
 * [tracePngScale] coerces a non-positive edge to 1 before dividing. That coincidence is worth naming
 * rather than relying on: the two languages differ at NEGATIVE halves, and a dimension is never one.
 *
 * THE FLOOR OF ONE PIXEL IS NOT DECORATION. A 4096x3 document scales its short edge to 1.5, and a
 * `Bitmap.createBitmap` of height 0 throws `IllegalArgumentException` — inside a save, on a screen with
 * unsaved work.
 *
 * [reduced] is what the success sentence is conditioned on, and it is computed HERE rather than by a
 * caller comparing sizes, so the number a researcher reads and the bitmap that was allocated can never
 * come from two different pieces of arithmetic.
 */
fun tracePngSize(width: Int, height: Int): TracePngSize {
    val safeWidth = if (width > 0) width else 1
    val safeHeight = if (height > 0) height else 1
    val scale = tracePngScale(safeWidth, safeHeight)
    val outWidth = Math.round(safeWidth * scale).toInt().coerceAtLeast(1)
    val outHeight = Math.round(safeHeight * scale).toInt().coerceAtLeast(1)
    return TracePngSize(outWidth, outHeight, reduced = scale < 1.0)
}

/**
 * What is said after a PNG has been written and the cap bit, or empty when it did not.
 *
 * SAID AFTER THE FACT AND NOT ONLY BEFORE IT. [traceExportLosses] already warns that the cap exists, in
 * the future tense, for a researcher choosing a format. This is the past tense, with the two real
 * numbers in it, for the researcher who now has a file: a picture 2048 px across of a drawing that was
 * 4096 is a reduction they can act on — by saving the SVG beside it — and a reduction nothing in the
 * file itself records.
 */
fun tracePngReductionNote(
    documentWidth: Int,
    documentHeight: Int,
    size: TracePngSize,
): String {
    if (!size.reduced) return ""
    return "The picture is ${size.width}x${size.height}, reduced from " +
        "${documentWidth}x$documentHeight so it stays inside what a phone can hold. The SVG " +
        "beside it has no such limit."
}

/**
 * What is said when a phone could not allocate the bitmap a PNG of this drawing needs.
 *
 * A SENTENCE AND NOT A CRASH, which is this app's settled disposition for every large allocation: a
 * plate that could not be built is a sentence on screen and never a crash in the middle of unsaved
 * work. The arithmetic behind it is worth stating because it is the largest single allocation this
 * feature makes — a PNG at the cap is 2048 x 2048 x 4 = 16,777,216 bytes of ARGB_8888, twice either
 * display plate.
 *
 * IT NAMES TWO REMEDIES AND BOTH ARE REAL. The SVG is the same drawing and is already in hand, so it
 * costs no allocation at all; and the picture's size follows the DOCUMENT's size rather than the trace
 * resolution, so the control that actually shrinks it is the frame — which is named by the label the
 * frame panel puts on itself, so a researcher is sent to a heading they can see.
 */
const val TRACE_PNG_MEMORY_REFUSAL: String =
    "This phone could not make room for a picture that size. The SVG is the same drawing and needs " +
        "almost no room — or choose a smaller region under “The part of the photograph to trace” " +
        "and trace again, because the picture is as big as the part of the sheet you traced."

/**
 * The dots-per-inch given to `ExportOptions`.
 *
 * A REFUSAL DRESSED AS A NUMBER. `ExportOptions.dpi` is clamped to 1..2400 with a default of 300, and
 * the engine's PNG encoder writes a `pHYs` chunk for any non-zero value — so it gives no way to decline
 * the claim, and every PNG it writes asserts a physical size. The document's units are the photograph's
 * own pixels; nobody in this flow has said how big the sheet was. 300 would assert that a 2048px plate
 * is 6.8 inches across, which nobody measured.
 *
 * 72 is the value that makes a reader show the image at its pixel size, it is the PostScript/PDF
 * identity — one document pixel becomes one point — and it is the engine's own choice for the two
 * formats where it had a free hand.
 *
 * ── IT DOES NOT REACH THE PNG THIS APP ACTUALLY WRITES, WHICH IS THE BETTER OUTCOME ───────────
 *
 * [traceRenderPngBytes] goes through `Bitmap.compress`, and that encoder takes no dpi argument and is
 * given none here — this side writes no `pHYs` chunk of its own. So a PNG saved from this handset
 * asserts NO physical size, which is what this constant was chosen to approximate in the first place,
 * arrived at by declining to make the claim rather than by making a modest one. The constant stays
 * because it is still what the three VECTOR writers are given, where the number decides how document
 * units become physical ones.
 */
const val TRACE_PNG_DPI: Int = 72

/* ────────────────────────────────────────────────────────────────────────────
 * The formats
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One row of the export table.
 *
 * [engineFormat] names the member of `:core-export`'s `ExportFormat` this row corresponds to, as a
 * plain string. A string and not a Kotlin enum reference, for the same reason the web keeps it a
 * string: the enum belongs to the vendored engine, and a second Kotlin declaration of its ten members
 * would be a second register of somebody else's list — the failure this repository has already met in
 * its own dashboard tiles. `TraceKotlinExporter.kt` maps this string in exactly one place; nothing here
 * does.
 */
@Immutable
data class TraceExportFormat(
    val id: String,
    /** The word on the chip. */
    val label: String,
    /** The `ExportFormat` member. See the class note. */
    val engineFormat: String,
    /** Without the dot. */
    val extension: String,
    val mime: String,
    /**
     * Whether this form may be ATTACHED to the record through the host's `onAttach`.
     *
     * CARRIED HERE, ACTED ON ELSEWHERE. The attach door is the panel's, not this file's. It lives in
     * the table because the table is the one register of what each format is for, and a second list of
     * "the attachable ones" is the pattern that goes stale for months.
     */
    val attachable: Boolean,
    /** True for SVG, PDF, EPS and DXF. Mirrors `ExportOptions.isVector`. */
    val isVector: Boolean,
    /**
     * The words on this format's button.
     *
     * "Save" AND NOT "DOWNLOAD", which is the one place the wording deliberately leaves the web's.
     * Downloading is what a browser does; here nothing is fetched — the bytes are produced on this
     * handset and written into its own Downloads folder. Calling it a download on a phone with no
     * signal would be the wrong verb for the one condition this product is built for.
     */
    val save: String,
    /**
     * The sentence under the row, verbatim from the web's table.
     *
     * ALWAYS-VISIBLE TEXT, NEVER A TOOLTIP. A phone has no hover, and these hints are the only
     * documentation a researcher offline for a fortnight has.
     */
    val hint: String,
)

/**
 * The five formats offered, in the order the card lists them.
 *
 * SVG LEADS because it is the only form that can still be edited, re-scaled or sent to a plotter
 * afterwards, and it is what the attach door files. The two attachable rows come first so that a
 * chooser above and the buttons below list them in one order.
 */
val TRACE_EXPORT_FORMATS: List<TraceExportFormat> = listOf(
    TraceExportFormat(
        id = "svg",
        label = "SVG",
        engineFormat = "SVG",
        extension = "svg",
        mime = "image/svg+xml",
        attachable = true,
        isVector = true,
        save = "Save the trace (SVG)",
        hint = "The traced paths themselves. Scales to any size without ever going blocky, opens in " +
            "Illustrator, Inkscape and CorelDRAW, and is what gets attached to the record.",
    ),
    TraceExportFormat(
        id = "png",
        label = "PNG",
        engineFormat = "PNG",
        extension = "png",
        mime = "image/png",
        attachable = true,
        isVector = false,
        save = "Save the rendered image (PNG)",
        // "2048px" AND NOT "2048 px": the web's row interpolates the same constant with no space, and
        // a lane whose rule is that one format is described one way on both clients cannot spell one
        // number two ways. The `${…}` braces are what keep the space out of the Kotlin interpolation.
        hint = "The drawing rendered as a picture, transparent wherever the drawing is not, up to " +
            "${TRACE_PNG_MAX_EDGE_PX}px on its long edge. Opens anywhere and drops straight into " +
            "a letter or a slide, but it is pixels — enlarge it and it goes soft.",
    ),
    TraceExportFormat(
        id = "pdf",
        label = "PDF",
        engineFormat = "PDF",
        extension = "pdf",
        mime = "application/pdf",
        attachable = false,
        isVector = true,
        save = "Save a PDF to send on",
        hint = "Vector, and it opens on every machine you could mail it to without anybody installing " +
            "anything. The one to attach to an email, or to hand to somebody who only needs to look " +
            "at it.",
    ),
    TraceExportFormat(
        id = "dxf",
        label = "DXF",
        engineFormat = "DXF",
        extension = "dxf",
        mime = "image/vnd.dxf",
        attachable = false,
        isVector = true,
        save = "Save for a CAD or cutting machine (DXF)",
        hint = "The outlines as CAD geometry, for a laser cutter, a CNC router or a drafting package. " +
            "It is DXF R12, which every controller reads: curves arrive as many short straight lines, " +
            "and colour, fill and line thickness are not carried at all.",
    ),
    TraceExportFormat(
        id = "eps",
        label = "EPS",
        engineFormat = "EPS",
        extension = "eps",
        mime = "application/postscript",
        attachable = false,
        isVector = true,
        save = "Save for a print shop (EPS)",
        hint = "Vector PostScript, for a print shop or sign-cutting software that will not take an " +
            "SVG. PostScript has no transparency, so anything part-see-through is flattened onto the " +
            "background as the file is written.",
    ),
)

/**
 * How many formats this handset offers.
 *
 * ONE CONSTANT, DERIVED, AND THE SCREEN PRINTS IT. The web's own parameter table records that its total
 * "has been mis-stated three different ways in prose" and binds the next writer to keep the number in
 * one place; the same rule applies to a smaller list for the same reason. Nothing in this feature may
 * write a count into a KDoc or into a sentence on screen except by reading this.
 */
val TRACE_EXPORT_FORMAT_COUNT: Int = TRACE_EXPORT_FORMATS.size

/**
 * The rows that may be attached to the record.
 *
 * DERIVED, NEVER A SECOND LIST. Flipping [TraceExportFormat.attachable] on a row is the whole of the
 * change needed to move a format between the two surfaces.
 */
val TRACE_ATTACHABLE_FORMATS: List<TraceExportFormat> =
    TRACE_EXPORT_FORMATS.filter { it.attachable }

/** The row for [id], or null. Case-sensitive: these ids are written into saved state. */
fun traceExportFormat(id: String): TraceExportFormat? =
    TRACE_EXPORT_FORMATS.firstOrNull { it.id == id }

/** One engine format this handset deliberately does not offer, and the reason. */
@Immutable
data class TraceExportAbsence(val engineFormat: String, val reason: String)

/**
 * The five members of `ExportFormat` this card does NOT offer, and why not.
 *
 * These are reasons rather than apologies — every one would be a fair thing to change if a researcher
 * asked for it. Carried across from the web's own list so that the two clients refuse the same five
 * things for the same five reasons; a format offered on one client and silently absent from the other
 * is somebody discovering the difference in front of an artisan.
 *
 * TEN IS THE WHOLE OF `ExportFormat`: five here plus [TRACE_EXPORT_FORMAT_COUNT] offered.
 * `TraceExportTest` asserts that bijection against the real enum, which it CAN do here — the enum is on
 * this module's compile classpath, where on the web it was on the far side of a bundle.
 */
val TRACE_NOT_OFFERED: List<TraceExportAbsence> = listOf(
    TraceExportAbsence(
        engineFormat = "JPEG",
        reason = "Lossy, and lossy is at its worst on exactly this: hard black edges on white come " +
            "back with grey mush around them. It carries no transparency either, so a traced drawing " +
            "would arrive on a white rectangle. The engine's dispatcher throws for it by design; PNG " +
            "is the raster answer.",
    ),
    TraceExportAbsence(
        engineFormat = "WEBP",
        reason = "The same lossy objection as JPEG, and it is a web delivery format rather than one a " +
            "print shop, a CAD package or an archive would take. The dispatcher throws for it too.",
    ),
    TraceExportAbsence(
        engineFormat = "TIFF",
        reason = "The engine writes it uncompressed — a ${TRACE_PNG_MAX_EDGE_PX}px plate is " +
            "16 MB of RGBA — for a use nobody has asked for. PNG is the same pixels an order of " +
            "magnitude smaller, and the vector formats are the answer to “I need it bigger”.",
    ),
    TraceExportAbsence(
        engineFormat = "BMP",
        reason = "Uncompressed 32bpp, the same 16 MB, in a format whose only advantage is opening on " +
            "a computer from 1998. PNG covers every reader that would take a BMP.",
    ),
    TraceExportAbsence(
        engineFormat = "PROJECT",
        reason = "The tracer's own session file — geometry plus every parameter, so a trace can be " +
            "reopened and re-tuned. It is not a drawing, it is useful only inside an application the " +
            "researcher does not have, and the dispatcher refuses it as well. Worth revisiting if " +
            "this panel ever grows a “reopen this trace” door; there is nothing to reopen it with " +
            "today, because nothing on this panel is stored.",
    ),
)

/* ────────────────────────────────────────────────────────────────────────────
 * The background, which is a property of the FILE and not of the trace
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * **THERE IS ONE BACKGROUND AND IT IS `output.background`. THIS FILE DOES NOT DECLARE A SECOND.**
 *
 * ── WHY NOT, WHEN THE CONTROL LIVES ON THIS STEP ──────────────────────────────────────────────
 *
 * `TraceParams.kt` relocates the "White background" toggle to [TraceTier.EXPORT], which is this card —
 * but it relocates the CONTROL, not the VALUE. The value stays a leaf of the engine's own parameter
 * tree, sanitised like every other leaf, with `null` as the only spelling of transparent and
 * [TRACE_OPAQUE_WHITE] as the spelling of white. An `ExportBackground` enum here would be a SECOND path
 * to one value, and two paths to one value means the two can disagree about which decided.
 *
 * So the export reads the background as a FACT — [documentBackground] on a finished trace, which is
 * what the document stage actually used — and never as a choice of its own.
 *
 * ── AND READ OFF THE DOCUMENT, NOT OFF THE REQUEST ────────────────────────────────────────────
 *
 * Auto-detection runs before the first stage, so `appliedParams` and the request can differ, and
 * dropping that distinction "would leave the client with a dock that says one thing and a drawing
 * produced by another". A file labelled White because a toggle says White, over a document the engine
 * wrote transparent, is that defect with a printer at the end of it.
 *
 * ── THE RULE THAT MAKES ALL FIVE FORMATS AGREE, WHICH IS THE POINT OF THIS SECTION ────────────
 *
 * Trap 1 in this file's header. **An exporter must pass the document's OWN background through, always,
 * rather than passing null.** Then:
 *
 *   * document transparent → vector keeps null → no background rectangle; raster clears to transparent.
 *   * document white → the vector arm rebuilds the document with the same white it already had; the
 *     raster arm clears to that white. Both white.
 *
 * Both arms agree for every value, by construction, with no special case. This is a named function
 * rather than a bare argument precisely because "pass the document's background" and "pass null" look
 * identical at a call site and are not.
 *
 * ── ONE CONSEQUENCE WORTH STATING ON SCREEN ───────────────────────────────────────────────────
 *
 * Because the value is a trace parameter and `output.background` is read at the LAST pipeline stage,
 * changing it means running the pipeline again — twelve to twenty seconds on a mid-range handset by the
 * feasibility extrapolation. That is a real cost and [TRACE_BACKGROUND_RETRACE_SENTENCE] is where it is
 * said. It is not a defect of the relocation: `ExportOptions.background` could override it without a
 * re-trace, but then the SVG already in hand would disagree with the PDF beside it, and one drawing
 * would leave this phone two ways.
 */
fun traceExportBackground(documentBackground: Int?): Int? = documentBackground

/**
 * The document's background read off the parameters the pipeline ACTUALLY RAN WITH.
 *
 * A function rather than three lines at a call site because the narrowing is the kind of thing one
 * caller gets right and the next does not: the leaf is a `Double` (the parameter tree carries numbers),
 * `4294967295.0` is opaque white, and `.toLong().toInt()` is what turns it into the packed ARGB `Int`
 * the engine and `Paint` both want. A direct `toInt()` on the `Double` saturates to `Int.MAX_VALUE` —
 * a transparent-ish grey — instead of wrapping to `0xFFFFFFFF`. **The narrowing IS the conversion, not
 * a loss of information.**
 *
 * @return packed ARGB, or null for a transparent document — the engine's only spelling of it.
 */
fun traceDocumentBackground(appliedParams: TraceValues): Int? =
    appliedParams.number("output.background")?.toLong()?.toInt()

/** True when the traced document has a ground under it rather than being transparent. */
fun traceBackgroundIsWhite(documentBackground: Int?): Boolean = documentBackground != null

/** "White" or "Transparent", for a line of copy. Never a control's own state — see above. */
fun traceBackgroundLabel(documentBackground: Int?): String =
    if (traceBackgroundIsWhite(documentBackground)) "White" else "Transparent"

/**
 * What changing the background costs, said where a researcher can act on it.
 *
 * See the section above for why it costs a re-trace at all. The copy says "a few seconds" rather than a
 * number because the number belongs in [TraceAvailability.measuredOn], where there is somewhere to say
 * whether anybody has measured it on this model of phone.
 */
const val TRACE_BACKGROUND_RETRACE_SENTENCE: String =
    "Changing this traces the sheet again, which takes a few seconds — the background is part of the " +
        "drawing the engine writes, so every format below changes together and none of them can " +
        "disagree with the others."

/* ────────────────────────────────────────────────────────────────────────────
 * Naming
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The word between the photograph's stem and the extension for a file ATTACHED to the record.
 *
 * `sketch-line-art.svg` is what the record already holds on the web. Unchanged.
 */
const val TRACE_ATTACH_SUFFIX: String = "line-art"

/**
 * The suffix for a saved VECTOR form — `.svg`, `.pdf`, `.dxf`, `.eps`.
 *
 * DELIBERATELY THE SAME WORD as [TRACE_ATTACH_SUFFIX]. The SVG a researcher saves is byte-for-byte the
 * file the record holds, and giving the copy on their phone a different name would invite the belief
 * that it is a different drawing; the other three are that same drawing written out for a different
 * machine, so they say so by sharing the word and are told apart by their extension, which is what
 * anybody reads a file by anyway.
 *
 * TWO CONSTANTS RATHER THAN ONE ALIAS, because they answer different questions: if the archive ever
 * renames its attached artefact, the saved copy must not silently follow.
 */
const val TRACE_SAVE_SUFFIX: String = "line-art"

/**
 * The suffix for the saved RENDERED raster.
 *
 * A DIFFERENT WORD, because a PNG named `-line-art.png` is exactly what an ATTACHED PNG is called, and
 * both would land in one Downloads folder where the record's own provenance is not there to tell them
 * apart.
 */
const val TRACE_RENDER_SUFFIX: String = "traced"

/**
 * The suffix a [TraceExportFormat] takes when it is saved to the device.
 *
 * DERIVED FROM [TraceExportFormat.isVector] rather than from the id, so a sixth format added to the
 * table gets the right word by existing. The split is raster-versus-vector and not
 * attachable-versus-not: the question a suffix answers is "is this a picture OF the drawing, or the
 * drawing", and only the PNG is the former.
 */
fun traceSaveSuffix(format: TraceExportFormat): String =
    if (format.isVector) TRACE_SAVE_SUFFIX else TRACE_RENDER_SUFFIX

/**
 * A name for the derived file, built from the photograph's own.
 *
 * The source name is kept and a suffix added, rather than a fresh name being invented, because the two
 * files sit in one record and a reviewer has to be able to tell which photograph a drawing came from.
 *
 * ── A TRANSLITERATION OF THE WEB'S `derivedFileName`, AND IT HAS TO BE ────────────────────────
 *
 * Every rule below is that function's, in the same order: strip one extension, fall back to "sketch",
 * replace anything outside `[A-Za-z0-9_\-. ]` with `_`, cap the stem at 80 characters, sanitise the
 * suffix the same way, and join with a hyphen. Two naming implementations mean two capture surfaces
 * drift into naming the same kind of file differently, and two CLIENTS is that hazard one register
 * wider.
 *
 * Kotlin's `\w` is Java's `[a-zA-Z_0-9]`, which is JavaScript's, so the character classes match
 * exactly. `String.trim()` here strips characters up to and including space where JavaScript's strips
 * Unicode whitespace; the difference can only appear on an exotic space inside a filename, where it
 * would turn one character into `_` on one client — worth knowing about, not worth a second
 * implementation of `\s` to close.
 *
 * ── AND WHY THE WEB'S SANITISER IS SAFE FOR MediaStore, WHICH IS NOT OBVIOUS ──────────────────
 *
 * This name becomes `MediaStore.Downloads.DISPLAY_NAME`. A name carrying a slash ("Ikat/Bandha") or a
 * colon produces a MediaStore insert that fails with a bare `IllegalArgumentException` after the whole
 * file has already been built. Both of those characters are outside the web's class too and become `_`
 * here. What survives here and not under a stricter filter is the SPACE, which MediaStore has never
 * objected to and which every photograph named by a phone gallery contains. So the web's rule is kept —
 * the two clients name one file one way — rather than tightened into a rule that would rename it.
 *
 * [sourceName] may be a path or a Uri's last segment: the last segment past either separator is taken
 * first, because the handset holds a photograph as a `content://` Uri where the browser holds a
 * `File.name`.
 */
fun traceExportFileName(
    sourceName: String,
    extension: String,
    suffix: String = TRACE_ATTACH_SUFFIX,
): String {
    val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
    val trimmed = leaf.replace(Regex("\\.[^./\\\\]+$"), "").trim()
    val base = if (trimmed.isNotEmpty()) trimmed else "sketch"
    val safe = base.replace(Regex("[^\\w\\-. ]+"), "_").take(80)
    val tag = suffix.replace(Regex("[^\\w\\-. ]+"), "_").trim()
    return if (tag.isNotEmpty()) "$safe-$tag.$extension" else "$safe.$extension"
}

/* ────────────────────────────────────────────────────────────────────────────
 * Provenance
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sentence that WOULD be written into a derived file, where a format had somewhere to put one.
 *
 * NOTHING IDENTIFYING. No researcher, no account, no timestamp beyond what is passed in. A derived file
 * is handed on and opened by people who never saw this panel, and a comment naming the person who
 * traced it would be a disclosure nobody asked for.
 *
 * [frameNote] is the crop clause, built by [traceCropNote] in the web's exact words, because the two
 * clients' files land in one archive and a reviewer holding one of each must not have to decide whether
 * two phrasings of "cropped to 900x1200 at (30, 40)" mean two different operations.
 *
 * ── WHERE IT ACTUALLY LANDS TODAY, WHICH IS NOWHERE, AND WHY IT IS STILL BUILT ────────────────
 *
 * `:core-export`'s `ExportOptions` has ten fields and none of them is a title, so the only strings the
 * PDF's `/Title` and the EPS's `%%Title:` can hold are the writers' own hard-coded ones — which
 * `TraceKotlinExporter` switches off as another product's branding. The DXF writer takes no metadata
 * argument at all and `Bitmap.compress` writes no text chunk. So on this build the note reaches NO
 * file, and [TRACE_EXPORT_NO_PROVENANCE_SENTENCE] says so on screen for all five.
 *
 * It is still built, and handed to the exporter, for two reasons. The seam is the one place a title
 * could arrive if a future `ExportOptions` grows one, and writing the note by hand into PDF or
 * PostScript syntax beside a vendored writer would be a second speller of one format — the divergence
 * the whole vendoring discipline exists to prevent.
 */
fun traceProvenanceNote(
    sourceName: String,
    shapeCount: Int,
    nodeCount: Int,
    frameNote: String = "",
): String {
    val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
        .ifBlank { "a photograph" }
    val head = "Traced on the device from $leaf by the Field Repository app. " +
        "$shapeCount paths, $nodeCount nodes."
    return if (frameNote.isBlank()) head else "$head ${frameNote.trim()}"
}

/* ────────────────────────────────────────────────────────────────────────────
 * What each file does not carry
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * **WHAT A SAVED COPY IS AND IS NOT, PRINTED BY EVERY SAVE IN THIS FEATURE.**
 *
 * See this file's header for why this is not the design workshop's sentence about a ministry report:
 * there is no report builder in this backend, and a claim about a document this product does not
 * assemble would be unverifiable from the surface making it.
 *
 * WHAT IT SAYS IS CHECKABLE FROM THIS PACKAGE. `TraceExportFile.kt` is the only thing in the feature
 * that writes to the flash, it writes to `MediaStore.Downloads` and nowhere else, and it touches no
 * record, no upload queue and no offline store. A researcher who wants this drawing ON the record
 * presses Attach, which is a different button with a different fate — [TRACE_ATTACH_SENTENCE].
 *
 * WHAT IT DOES NOT SAY, ON PURPOSE. It does not say the export is pointless and it does not tell the
 * researcher to stop. Sending the drawing on is the whole point of the four take-away formats.
 */
const val TRACE_EXPORT_KEEP_SENTENCE: String =
    "This file is yours to keep and to send on. It is written to this phone's Downloads folder and " +
        "goes no further — it is not attached to the record, not uploaded and not synced, so if this " +
        "handset is lost so is this copy. Use “Add the line art” above for the copy that reaches the " +
        "record."

/**
 * **WHAT THE ATTACHED FILE IS, PRINTED UNDER THE BUTTON THAT ATTACHES IT.**
 *
 * ── WHY A SECOND CONSTANT IS NOT A SECOND PHRASING ────────────────────────────────────────────
 *
 * [TRACE_EXPORT_KEEP_SENTENCE] is about a SAVED copy — "yours to keep", which reaches no record at
 * all — and saying that of the SVG the panel attaches would be false in the other direction: that file
 * IS in the archive, it syncs, and nobody needs to be sent it. Two different files, two different
 * fates, two sentences.
 *
 * ── AND WHAT IT PROMISES, WHICH IS ONLY WHAT THE PANEL CAN SEE ────────────────────────────────
 *
 * The panel hands ONE Uri to an `onAttach` callback the host owns and knows nothing else. So this
 * sentence claims exactly that: a vector file goes to the screen that opened this panel, through the
 * same door a camera capture takes — which is what gives it the retry, the queue and the offline store
 * for free — and the photograph is not touched. It does not claim what any particular host screen does
 * with it afterwards, because this panel cannot know and a promise it cannot keep is worse than none.
 */
const val TRACE_ATTACH_SENTENCE: String =
    "The drawing is added as an SVG — vector line work that prints at any size without ever going " +
        "blocky. It goes through the same door a photograph taken with the camera goes through, so it " +
        "queues, retries and waits offline exactly like any other file on this record. The " +
        "photograph itself is not changed, and the picture above is only for comparing — it is never " +
        "what gets attached."

/**
 * The sentence shown when the trace on screen is a PREVIEW rather than a full-resolution run.
 *
 * NAMES THE REMEDY, because a refusal that does not is a dead end.
 *
 * ── WHY A PREVIEW IS NEVER SAVED AND NEVER ATTACHED ───────────────────────────────────────────
 *
 * A preview traces at a smaller working resolution so a slider can be judged in under a second. The
 * drawing it produces is not the drawing a full run produces — fewer paths, coarser corners — and
 * nothing inside an SVG, a PDF or a DXF says which it was. Saving the preview "hands the researcher a
 * coarser drawing than the one they approved, with nothing on screen to say so".
 *
 * It matters more on the handset than on the laptop, because the file here is going into a print shop's
 * workflow or an archive, and "it came out blurry" discovered four days later at a desk is not a
 * recoverable failure — the sheet of paper is a fortnight away.
 */
const val TRACE_EXPORT_PREVIEW_SENTENCE: String =
    "This drawing was traced at a smaller size so it could be tuned quickly. Trace it once at full " +
        "size before saving it — a preview saved now would be a coarser drawing than the one on " +
        "screen, and nothing in the file would say so."

/**
 * The sentence about the tracer's own name in the file's metadata.
 *
 * Trap 2 in this file's header is the whole argument. Printed for the format that carries it, so that a
 * researcher meets it here rather than in somebody's inbox.
 *
 * ── IT IS ONE FORMAT, AND THAT WAS READ OUT OF THE WRITERS RATHER THAN ASSUMED ────────────────
 *
 * Both places this app writes pass `includeMetadata = false`. Read out of the vendored writers:
 *
 *  * **SVG — clean.** `SvgExport` emits a title and description only under `includeMetadata`.
 *  * **PDF — clean.** `PdfWriter` omits the whole `/Info` object under the same flag, so the
 *    `/Producer`, `/Creator` and `/Title` are never written.
 *  * **DXF — clean.** `DxfWriter` writes no product name at all, under any option.
 *  * **PNG — clean.** `Bitmap.compress` writes no text chunk and is given no dpi.
 *  * **EPS — NOT clean, and no option makes it so.** `EpsWriter` appends `%%Creator: Offline Tracer`
 *    OUTSIDE the `includeMetadata` guard that gates `%%Title` on the next line. Editing that file would
 *    break the SHA-256 in `android/UPSTREAM-MANIFEST-KOTLIN.txt` and the parity discipline it enforces,
 *    so the honest thing is to say it. `TraceExportTest` asserts all five of these against the real
 *    writers, so this list cannot rot into a claim about a file nobody read.
 */
const val TRACE_EXPORT_ENGINE_NAME_SENTENCE: String =
    "The file records that it was made by the Offline Tracer engine, which is the tracing library " +
        "this app uses on the device. That is a note about the software — not about the drawing, " +
        "and not about you."

/**
 * The sentence for a format that cannot say what it was traced from — which is all five.
 *
 * `traceProvenanceNote` builds a line naming the photograph and the counts, and there is nowhere on
 * this build for it to go: `:core-export`'s `ExportOptions` has no title field, so `PdfWriter` can only
 * ever write its own hard-coded `/Title` and `EpsWriter` its own `%%Title:`, both of which this app
 * switches off as another product's branding. `DxfWriter` takes no metadata argument at all, and the
 * PNG leaves through `Bitmap.compress`, which writes no text chunk.
 *
 * **THE PORTAL'S SVG STILL CARRIES IT**, which is a real difference between the two clients rather than
 * a hypothetical one: the web writes its own SVG through a page-side builder that emits the note as an
 * XML comment. So the same drawing saved on a laptop names its source photograph and saved on a handset
 * does not. That is worth an owner's attention and it is stated here rather than left for somebody to
 * notice in a print shop.
 *
 * Written once and shared by every row, because two spellings of one fact is how the two drift.
 */
const val TRACE_EXPORT_NO_PROVENANCE_SENTENCE: String =
    "This file records nothing about which photograph it was traced from. None of the five forms " +
        "this app writes has anywhere to put that note, so keep the photograph beside it if somebody " +
        "will need to know which sheet it came from."

/**
 * The sentence for a CROPPED trace saved in a format that cannot record the crop.
 *
 * ── WHY THIS IS A SECOND SENTENCE AND NOT A CLAUSE ON THE ONE ABOVE ───────────────────────────
 *
 * [TRACE_EXPORT_NO_PROVENANCE_SENTENCE] is true of every save and is a fact about the FORMAT. This is
 * true only of a researcher who framed part of the sheet, and it is a fact about THEIR drawing — the
 * photograph in the record shows a whole sheet and the file in their hand shows a corner of it, with
 * nothing anywhere to say the two are the same sketch. Merging them would put a conditional clause
 * inside an unconditional sentence, which is how a reader learns to skip the block.
 *
 * It is keyed on [TRACE_EXPORT_NO_PROVENANCE_SENTENCE] already being in the list rather than on a
 * second literal set of ids. The question both answer is the same one — "has this file anywhere to put
 * a sentence about how it was made" — and a second list of the silent formats is the register that goes
 * stale the day a sixth is added.
 */
const val TRACE_EXPORT_NO_FRAME_SENTENCE: String =
    "You traced part of the photograph rather than the whole sheet. This file has nowhere to record " +
        "that either, so somebody holding it beside the photograph cannot tell why the two do not " +
        "match. Say which part you traced when you send it on."

/**
 * Everything a researcher should be told about the file they are about to save, beside the file.
 *
 * ── WHY BESIDE AND NOT INSIDE ─────────────────────────────────────────────────────────────────
 *
 * Two of the five formats have nowhere inside them to put a sentence at all, so beside is the only
 * place all five can be treated alike. The discipline is the one this app already applies to a
 * generated export: somebody who is told the file is not in their chosen typeface can send the other
 * format instead; the same difference unmentioned is a defect somebody else notices first, in an
 * office, about a document that has already been sent.
 *
 * ── WHAT IS DELIBERATELY NOT IN THE LIST ──────────────────────────────────────────────────────
 *
 * The hints in the table already say what each format IS. These are only the LOSSES — things that are
 * true of the bytes and invisible in them. A sentence that merely repeated the hint would train a
 * reader to skip the block that carries the one that matters, and the one that matters is always first.
 *
 * [documentLongEdgePx] is the traced document's long edge, used only to decide whether the PNG's cap
 * actually bites on this drawing. Pass 0 when it is not known and the cap is stated unconditionally,
 * which is the safe direction: a cap named when it did not apply costs a reader one sentence, and a cap
 * that applied and was not named costs them a drawing softer than the one they approved.
 *
 * [frameNote] is [TraceResult.frameNote] — non-empty exactly when the researcher traced part of the
 * photograph. Empty is the safe direction here in the OTHER direction from the cap above: warning about
 * a crop nobody made would teach a reader that this block describes situations they are not in.
 */
fun traceExportLosses(
    format: TraceExportFormat,
    /** What the document stage actually wrote. See [traceExportBackground]. */
    documentBackground: Int?,
    documentLongEdgePx: Int = 0,
    frameNote: String = "",
): List<String> {
    val transparent = !traceBackgroundIsWhite(documentBackground)
    val out = mutableListOf<String>()
    out += TRACE_EXPORT_KEEP_SENTENCE
    when (format.id) {
        "png" -> {
            val capBites = documentLongEdgePx <= 0 || documentLongEdgePx > TRACE_PNG_MAX_EDGE_PX
            if (capBites) {
                out += "Pixels, not curves, and no bigger than $TRACE_PNG_MAX_EDGE_PX px on the " +
                    "long edge — a larger one is more memory than these phones have. Save the " +
                    "SVG instead if it has to be enlarged."
            }
            out += TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
        "dxf" -> {
            out += "DXF R12 carries geometry and nothing else: no colour, no fill, no line " +
                "thickness, and curves arrive as many short straight lines."
            out += TRACE_EXPORT_NO_PROVENANCE_SENTENCE
            out += "It carries no background either, so the choice above changes nothing in this file."
        }
        "eps" -> {
            out += "PostScript has no transparency. Anything part-see-through is flattened onto the " +
                "background as the file is written, and the engine records in the file that it did so."
            // THE ONLY FORMAT STILL BRANDED. `EpsWriter` writes `%%Creator: Offline Tracer` outside the
            // `includeMetadata` guard, so no option removes it — see that constant, which reads all
            // five writers and says which are clean.
            out += TRACE_EXPORT_ENGINE_NAME_SENTENCE
            out += TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
        "pdf" -> {
            out += TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
        "svg" -> {
            out += TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
    }
    if (frameNote.isNotBlank() && TRACE_EXPORT_NO_PROVENANCE_SENTENCE in out) {
        out += TRACE_EXPORT_NO_FRAME_SENTENCE
    }
    if (transparent && format.id != "dxf") {
        // DRAWN FOR THE PNG TOO. A transparent PNG dropped into a letter shows the page through it,
        // which is the same surprise a transparent SVG produces in a PDF — the format that genuinely
        // cannot express it is the DXF, which carries no background at all, and that one is named on
        // its own row above.
        out += "Transparent means the page shows through wherever the drawing is not. In an email or " +
            "a document that is whatever colour the reader's page happens to be — turn the white " +
            "background on above if somebody is going to print it."
    }
    return out
}
