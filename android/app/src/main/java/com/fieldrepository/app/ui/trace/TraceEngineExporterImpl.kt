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

const val TRACE_LAYER_NAME: String = "Line art"

private const val TRACE_LAYER_ID: String = "trace"

class TraceDocument(
    val document: VecDocument,
    val shapesWritten: Int,
    val truncationNote: String?,
)

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

private fun traceDimension(value: Int): Float = if (value < 1) 1f else value.toFloat()

class TraceExporterImpl : TraceEngineExporter {

    override val refusal: String? = null

    override suspend fun export(request: TraceExportRequest): TraceExportOutcome =
        withContext(Dispatchers.Default) {
            val format = traceExportEngineFormat(request.format)
                ?: return@withContext TraceExportOutcome.Refused(
                    traceUnwritableSentence(request.format)
                )

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
                    raster = null,
                    o = ExportOptions(
                        format = format,
                        width = 0,
                        height = 0,
                        scale = 1f,
                        background = request.background,
                        dpi = TRACE_PNG_DPI,
                        precision = 2,
                        includeMetadata = false,
                        flattenLayers = false,
                    ),
                )
            } catch (unsupported: UnsupportedOperationException) {
                return@withContext TraceExportOutcome.Refused(
                    traceUnwritableSentence(request.format)
                )
            } catch (memory: OutOfMemoryError) {
                return@withContext TraceExportOutcome.Refused(TRACE_EXPORT_MEMORY_SENTENCE)
            }

            TraceExportOutcome.Done(bytes)
        }
}

private fun traceExportEngineFormat(format: TraceExportFormat): ExportFormat? {
    val named = ExportFormat.entries.firstOrNull { it.name == format.engineFormat } ?: return null
    return if (Exporter.supports(named)) named else null
}

private fun traceUnwritableSentence(format: TraceExportFormat): String =
    "This drawing couldn't be saved as ${format.label}. Save it as an SVG or a picture instead."

const val TRACE_EXPORT_MEMORY_SENTENCE: String =
    "Too many paths for this file type. Save as SVG, or raise “Minimum speck” or “Simplify” and trace again."

@Composable
fun rememberTraceExporter(): TraceEngineExporter = remember { TraceExporterImpl() }

private suspend fun traceEnsureActive() {
    kotlin.coroutines.coroutineContext.ensureActive()
}
