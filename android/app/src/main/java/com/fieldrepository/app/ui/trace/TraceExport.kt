package com.fieldrepository.app.ui.trace

import androidx.compose.runtime.Immutable

const val TRACE_PNG_MAX_EDGE_PX: Int = 2048

fun tracePngScale(width: Int, height: Int): Double {
    val safeWidth = if (width > 0) width else 1
    val safeHeight = if (height > 0) height else 1
    val longEdge = if (safeWidth > safeHeight) safeWidth else safeHeight
    return (TRACE_PNG_MAX_EDGE_PX.toDouble() / longEdge.toDouble()).coerceAtMost(1.0)
}

@Immutable
data class TracePngSize(
    val width: Int,
    val height: Int,
    val reduced: Boolean,
)

fun tracePngSize(width: Int, height: Int): TracePngSize {
    val safeWidth = if (width > 0) width else 1
    val safeHeight = if (height > 0) height else 1
    val scale = tracePngScale(safeWidth, safeHeight)
    val outWidth = Math.round(safeWidth * scale).toInt().coerceAtLeast(1)
    val outHeight = Math.round(safeHeight * scale).toInt().coerceAtLeast(1)
    return TracePngSize(outWidth, outHeight, reduced = scale < 1.0)
}

fun tracePngReductionNote(
    documentWidth: Int,
    documentHeight: Int,
    size: TracePngSize,
): String {
    if (!size.reduced) return ""
    return "Picture reduced to ${size.width}x${size.height} (from ${documentWidth}x$documentHeight). The SVG is full size."
}

const val TRACE_PNG_MEMORY_REFUSAL: String =
    "Not enough memory for a picture this size. Save the SVG, or trace a smaller region."

const val TRACE_PNG_DPI: Int = 72

@Immutable
data class TraceExportFormat(
    val id: String,
    val label: String,
    val engineFormat: String,
    val extension: String,
    val mime: String,
    val attachable: Boolean,
    val isVector: Boolean,
    val save: String,
    val hint: String,
)

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
        hint = "Vector — scales to any size. Opens in Illustrator, Inkscape, CorelDRAW.",
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
        hint = "Transparent picture, up to ${TRACE_PNG_MAX_EDGE_PX}px. Opens anywhere.",
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
        hint = "Vector that opens anywhere. Best for email.",
    ),
    TraceExportFormat(
        id = "dxf",
        label = "DXF",
        engineFormat = "DXF",
        extension = "dxf",
        mime = "image/vnd.dxf",
        attachable = false,
        isVector = true,
        save = "Save for CAD (DXF)",
        hint = "Outlines for laser cutters, CNC and CAD. No colour or fill.",
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
        hint = "For print shops and sign cutters that don't take SVG. No transparency.",
    ),
)

val TRACE_EXPORT_FORMAT_COUNT: Int = TRACE_EXPORT_FORMATS.size

val TRACE_ATTACHABLE_FORMATS: List<TraceExportFormat> =
    TRACE_EXPORT_FORMATS.filter { it.attachable }

fun traceExportFormat(id: String): TraceExportFormat? =
    TRACE_EXPORT_FORMATS.firstOrNull { it.id == id }

@Immutable
data class TraceExportAbsence(val engineFormat: String, val reason: String)

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

fun traceExportBackground(documentBackground: Int?): Int? = documentBackground

fun traceDocumentBackground(appliedParams: TraceValues): Int? =
    appliedParams.number("output.background")?.toLong()?.toInt()

fun traceBackgroundIsWhite(documentBackground: Int?): Boolean = documentBackground != null

fun traceBackgroundLabel(documentBackground: Int?): String =
    if (traceBackgroundIsWhite(documentBackground)) "White" else "Transparent"

const val TRACE_BACKGROUND_RETRACE_SENTENCE: String =
    "Changing this re-traces, which takes a few seconds."

const val TRACE_ATTACH_SUFFIX: String = "line-art"

const val TRACE_SAVE_SUFFIX: String = "line-art"

const val TRACE_RENDER_SUFFIX: String = "traced"

fun traceSaveSuffix(format: TraceExportFormat): String =
    if (format.isVector) TRACE_SAVE_SUFFIX else TRACE_RENDER_SUFFIX

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

const val TRACE_EXPORT_KEEP_SENTENCE: String =
    "Saved to Downloads only — not attached to the record. Use “Add the line art” for that."

const val TRACE_ATTACH_SENTENCE: String =
    "Added to the record as an SVG; it queues and uploads like any other file. The picture above is only for comparing."

const val TRACE_EXPORT_PREVIEW_SENTENCE: String =
    "This is a quick preview. Trace at full size before saving."

const val TRACE_EXPORT_ENGINE_NAME_SENTENCE: String =
    "The file notes the tracing software used — nothing about you."

const val TRACE_EXPORT_NO_PROVENANCE_SENTENCE: String =
    "The file doesn't record its source photo. Keep them together."

const val TRACE_EXPORT_NO_FRAME_SENTENCE: String =
    "You traced part of the photo. Mention which part when sharing."

fun traceExportLosses(
    format: TraceExportFormat,
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
                out += "Pixels, no bigger than $TRACE_PNG_MAX_EDGE_PX px. Use SVG to enlarge."
            }
            out += TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
        "dxf" -> {
            out += "Geometry only — no colour, fill or line weight."
            out += TRACE_EXPORT_NO_PROVENANCE_SENTENCE
            out += "It carries no background."
        }
        "eps" -> {
            out += "No transparency — see-through parts are flattened."
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
        out += "Transparent — the page shows through. Turn on white above for printing."
    }
    return out
}
