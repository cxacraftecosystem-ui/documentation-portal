package com.fieldrepository.app.ui.trace

import android.app.ActivityManager
import android.content.Context
import com.offlinetracer.export.ExportFormat
import com.offlinetracer.export.ExportOptions
import com.offlinetracer.export.SvgExport
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.pipeline.CancellationToken
import com.offlinetracer.pipeline.CancelledException
import com.offlinetracer.pipeline.EdgeEngine
import com.offlinetracer.pipeline.Pipeline
import com.offlinetracer.pipeline.ProgressListener
import com.offlinetracer.pipeline.Stages
import com.offlinetracer.pipeline.TraceParams
import com.offlinetracer.pipeline.TraceResult as EngineTraceResult
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val TRACE_SVG_WRITER: String = "core-export/SvgExport#export"

val TRACE_ENGINE_STAGES: List<TraceStage> =
    Stages.ALL.map { TraceStage(id = it.id, label = it.label) }

val TRACE_ENGINE_BPP: Map<EdgeEngine, Int> = linkedMapOf(
    EdgeEngine.CANNY to 152,
    EdgeEngine.ADAPTIVE to 51,
    EdgeEngine.LOG to 42,
    EdgeEngine.FDOG to 40,
    EdgeEngine.XDOG to 152,
    EdgeEngine.MODEL to 152,
)

const val TRACE_ENGINE_INPUT_BPP: Int = 8

const val TRACE_ENGINE_RESULT_BYTES: Long = 24L * 1024L * 1024L

const val TRACE_ENGINE_HEAP_RESERVE_BYTES: Long = 32L * 1024L * 1024L

fun traceHeapBytes(runtime: Runtime = Runtime.getRuntime()): Long =
    runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())

fun tracePeakBytes(sourcePixels: Long, workingPixels: Long, engine: EdgeEngine): Long {
    val perPixel = TRACE_ENGINE_BPP[engine] ?: TRACE_ENGINE_BPP.values.max()
    return sourcePixels * TRACE_ENGINE_INPUT_BPP + workingPixels * perPixel + TRACE_ENGINE_RESULT_BYTES
}

fun traceWorkingPixels(width: Int, height: Int, params: TraceParams): Long {
    val (w, h) = traceWorkingSize(width, height, params.preprocess.workingLongEdge)
    return w.toLong() * h.toLong()
}

fun traceMemoryRefusal(
    sourcePixels: Long,
    workingPixels: Long,
    engine: EdgeEngine,
    heapBytes: Long,
): String? {
    val peak = tracePeakBytes(sourcePixels, workingPixels, engine)
    if (peak + TRACE_ENGINE_HEAP_RESERVE_BYTES <= heapBytes) return null
    val need = traceMegabytes(peak)
    val have = traceMegabytes(heapBytes - TRACE_ENGINE_HEAP_RESERVE_BYTES)
    return "Needs about $need MB; about $have MB free. Lower the resolution, choose another edge engine, or close other apps."
}

private fun traceMegabytes(bytes: Long): Long =
    if (bytes <= 0L) 0L else Math.round(bytes / (1024.0 * 1024.0))

fun traceImageOf(rgba: ByteArray, width: Int, height: Int): RgbaImage {
    if (width < 1 || height < 1) {
        throw TraceHostFailure(TraceFailureKind.IMAGE_EMPTY, "${width}x$height")
    }
    val count = width.toLong() * height.toLong()
    if (rgba.size.toLong() < count * 4L) {
        throw TraceHostFailure(
            TraceFailureKind.ENGINE_ERROR,
            "${rgba.size} bytes for a ${width}x$height image, which needs ${count * 4L}",
        )
    }
    val pixels = IntArray(count.toInt())
    var b = 0
    for (i in pixels.indices) {
        val r = rgba[b].toInt() and 0xFF
        val g = rgba[b + 1].toInt() and 0xFF
        val bl = rgba[b + 2].toInt() and 0xFF
        val a = rgba[b + 3].toInt() and 0xFF
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
        b += 4
    }
    return RgbaImage(width, height, pixels)
}

fun traceGeometryOf(doc: VecDocument): TraceGeometry {
    val shapes = ArrayList<VecShape>(doc.shapeCount())
    for (layer in doc.layers) shapes.addAll(layer.shapes)

    var segTotal = 0
    var coordTotal = 0
    for (shape in shapes) {
        val segs = shape.path.segments
        segTotal += segs.size
        coordTotal += 2
        for (seg in segs) coordTotal += traceCoordsOf(seg)
    }

    val coords = FloatArray(coordTotal)
    val verbs = ByteArray(segTotal)
    val verbStarts = IntArray(shapes.size + 1)
    val coordStarts = IntArray(shapes.size + 1)
    val closed = ByteArray(shapes.size)
    val styleIndex = IntArray(shapes.size)
    val styleTable = ArrayList<TraceStyle>()
    val styleKeys = LinkedHashMap<VecStyle, Int>()

    var v = 0
    var c = 0
    for (s in shapes.indices) {
        val shape = shapes[s]
        verbStarts[s] = v
        coordStarts[s] = c
        closed[s] = if (shape.path.closed) 1 else 0

        styleIndex[s] = styleKeys.getOrPut(shape.style) {
            styleTable.add(traceStyleOf(shape.style))
            styleTable.size - 1
        }

        coords[c++] = shape.path.start.x
        coords[c++] = shape.path.start.y
        for (seg in shape.path.segments) {
            when (seg) {
                is VecSeg.Line -> {
                    verbs[v++] = TRACE_VERB_LINE
                    coords[c++] = seg.to.x
                    coords[c++] = seg.to.y
                }

                is VecSeg.Quad -> {
                    verbs[v++] = TRACE_VERB_QUAD
                    coords[c++] = seg.c.x
                    coords[c++] = seg.c.y
                    coords[c++] = seg.to.x
                    coords[c++] = seg.to.y
                }

                is VecSeg.Cubic -> {
                    verbs[v++] = TRACE_VERB_CUBIC
                    coords[c++] = seg.c1.x
                    coords[c++] = seg.c1.y
                    coords[c++] = seg.c2.x
                    coords[c++] = seg.c2.y
                    coords[c++] = seg.to.x
                    coords[c++] = seg.to.y
                }
            }
        }
    }
    verbStarts[shapes.size] = v
    coordStarts[shapes.size] = c

    return TraceGeometry(
        coords = coords,
        verbs = verbs,
        verbStarts = verbStarts,
        coordStarts = coordStarts,
        closed = closed,
        styleTable = styleTable,
        styleIndex = styleIndex,
    )
}

private fun traceCoordsOf(seg: VecSeg): Int = when (seg) {
    is VecSeg.Line -> 2
    is VecSeg.Quad -> 4
    is VecSeg.Cubic -> 6
}

private fun traceStyleOf(style: VecStyle): TraceStyle = TraceStyle(
    stroke = style.stroke,
    strokeWidth = style.strokeWidth,
    fill = style.fill,
    fillRule = style.fillRule.name,
    cap = style.cap.name,
    join = style.join.name,
    miterLimit = style.miterLimit,
    opacity = style.opacity,
)

const val TRACE_MAX_SHAPES: Int = 200000

class TraceSvg(
    val svg: String,
    val shapesWritten: Int,
    val truncationNote: String?,
)

fun traceSvgOf(doc: VecDocument): TraceSvg {
    val shapeCount = doc.shapeCount()
    val written = if (shapeCount > TRACE_MAX_SHAPES) TRACE_MAX_SHAPES else shapeCount
    val target = if (written == shapeCount) doc else traceTrimmed(doc, written)
    val bytes = SvgExport.export(
        target,
        ExportOptions(
            format = ExportFormat.SVG,
            precision = 2,
            includeMetadata = false,
            flattenLayers = true,
        ),
    )
    return TraceSvg(
        svg = String(bytes, Charsets.UTF_8),
        shapesWritten = written,
        truncationNote = traceTruncationNote(shapeCount, written),
    )
}

private fun traceTrimmed(doc: VecDocument, limit: Int): VecDocument {
    var left = limit
    val layers = ArrayList<VecLayer>(doc.layers.size)
    for (layer in doc.layers) {
        if (left <= 0) break
        if (layer.shapes.size <= left) {
            layers.add(layer)
            left -= layer.shapes.size
        } else {
            layers.add(layer.copy(shapes = ArrayList(layer.shapes.subList(0, left))))
            left = 0
        }
    }
    return VecDocument(doc.width, doc.height, layers, doc.background)
}

fun traceTruncationNote(shapeCount: Int, shapesWritten: Int): String? {
    if (shapeCount <= shapesWritten) return null
    val count = traceCount(shapeCount)
    val kept = traceCount(shapesWritten)
    return "Only $kept of $count paths fit. Raise “Minimum speck” or “Simplify” and trace again."
}

private fun traceCount(value: Int): String {
    if (value < 1000) return value.toString()
    val digits = value.toString()
    val head = digits.substring(0, digits.length - 3)
    val out = StringBuilder()
    var i = head.length
    while (i > 2) {
        out.insert(0, "," + head.substring(i - 2, i))
        i -= 2
    }
    out.insert(0, head.substring(0, i))
    return out.append(',').append(digits.substring(digits.length - 3)).toString()
}

const val TRACE_PREVIEW_LONG_EDGE: Int = 720

fun tracePreviewParams(base: TraceParams): TraceParams = base
    .copy(preprocess = base.preprocess.copy(workingLongEdge = TRACE_PREVIEW_LONG_EDGE))
    .sanitized()

suspend fun traceRunEngine(
    src: RgbaImage,
    params: TraceParams,
    preview: Boolean,
    onProgress: suspend (TraceProgress) -> Unit,
): TraceDecoded = coroutineScope {
    val events = Channel<TraceProgress>(Channel.UNLIMITED)
    val pump = launch { for (event in events) onProgress(event) }
    val result = try {
        traceCallPipeline(src, params, preview, events)
    } finally {
        events.close()
    }
    pump.join()
    traceDecodedOf(result)
}

private suspend fun traceCallPipeline(
    src: RgbaImage,
    params: TraceParams,
    preview: Boolean,
    events: Channel<TraceProgress>,
): EngineTraceResult {
    val token = CancellationToken()
    var lastStageId: String? = null
    val listener = if (preview) {
        null
    } else {
        ProgressListener { stageId, label, fraction ->
            if (stageId != lastStageId) {
                lastStageId = stageId
                events.trySend(TraceProgress(stageId = stageId, label = label, fraction = fraction))
            }
        }
    }
    val effective = if (preview) tracePreviewParams(params) else params

    return try {
        withContext(Dispatchers.Default) {
            val guard = launch(Dispatchers.Unconfined) {
                try {
                    awaitCancellation()
                } finally {
                    token.cancel()
                }
            }
            try {
                Pipeline.run(src, effective, listener, token, classify = !preview)
            } finally {
                guard.cancel()
            }
        }
    } catch (cancelled: CancelledException) {
        currentCoroutineContext().ensureActive()
        throw TraceHostFailure(
            TraceFailureKind.ENGINE_ERROR,
            "the trace stopped before it finished, and nothing had asked it to stop",
            cancelled,
        )
    } catch (exhausted: OutOfMemoryError) {
        throw TraceHostFailure(TraceFailureKind.OUT_OF_MEMORY, "", exhausted)
    }
}

fun traceDecodedOf(result: EngineTraceResult): TraceDecoded {
    val doc = result.document
    val svg = traceSvgOf(doc)
    return TraceDecoded(
        svg = svg.svg,
        geometry = traceGeometryOf(doc),
        background = doc.background,
        width = result.sourceWidth,
        height = result.sourceHeight,
        workingWidth = result.workingWidth,
        workingHeight = result.workingHeight,
        shapeCount = doc.shapeCount(),
        nodeCount = doc.nodeCount(),
        stages = result.stages.map { TraceStageTiming(it.id, it.label, it.millis) },
        totalMillis = result.totalMillis,
        notes = if (svg.truncationNote == null) result.notes else result.notes + svg.truncationNote,
        appliedParams = traceValuesOfParams(result.appliedParams),
        autoSubjectId = result.autoSubjectId,
        suggestedStyleId = "",
    )
}

const val TRACE_NO_SUGGESTION_NOTE: String =
    "This engine's classifier answers with a sentence rather than a style id, so this runtime " +
        "suggests no style. See TraceRuntime.kt for the three alternatives and why each is " +
        "worse than an empty field."

private class TraceRefusal(message: String) : Exception(message)

class TraceEngineRuntimeImpl internal constructor(
    override val availability: TraceAvailability,
    private val context: Context,
) : TraceEngineRuntime {

    val svgWriter: String = TRACE_SVG_WRITER

    val stages: List<TraceStage> = TRACE_ENGINE_STAGES

    override suspend fun presets(): TracePresetTables = withContext(Dispatchers.Default) {
        tracePresetTables()
    }

    override suspend fun defaults(): TraceValues = withContext(Dispatchers.Default) {
        traceValuesOfParams(TraceParams())
    }

    override suspend fun withOverrides(
        base: TraceValues,
        patch: Map<String, TraceValue>,
    ): TraceValues = withContext(Dispatchers.Default) {
        traceValuesOfParams(traceApplyLeaves(traceParamsOf(base), patch))
    }

    override suspend fun applyStyle(base: TraceValues, styleId: String): TraceValues =
        withContext(Dispatchers.Default) { traceApplyStyle(base, styleId) }

    override suspend fun applySubject(base: TraceValues, subjectId: String): TraceValues =
        withContext(Dispatchers.Default) { traceApplySubject(base, subjectId) }

    override suspend fun trace(
        request: TraceRequest,
        onProgress: (TraceProgress) -> Unit,
    ): TraceOutcome = try {
        TraceOutcome.Done(runTrace(request, onProgress))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refused: TraceRefusal) {
        TraceOutcome.Refused(refused.message.orEmpty())
    } catch (failure: TraceHostFailure) {
        TraceOutcome.Refused(failure.message.orEmpty())
    } catch (unexpected: Throwable) {
        TraceOutcome.Refused(
            traceSentence(
                TraceFailureKind.ENGINE_ERROR,
                unexpected::class.java.simpleName,
            ),
        )
    }

    private suspend fun runTrace(
        request: TraceRequest,
        onProgress: (TraceProgress) -> Unit,
    ): TraceResult {
        val preview = !request.kind.isFullResolution

        val source = traceDecodeForTrace(context, request.photograph)

        val prepared = withContext(Dispatchers.Default) {
            val rgba = TracePlates.readRgba(source)
                ?: throw TraceHostFailure(TraceFailureKind.IMAGE_UNREADABLE, "reading the pixels back")
            val decodedWidth = source.width
            val decodedHeight = source.height
            source.recycle()

            val frame = request.frame
            val cropped = if (frame == null) {
                null
            } else {
                traceCropRgba(
                    rgba,
                    decodedWidth,
                    decodedHeight,
                    traceCropIn(frame, decodedWidth, decodedHeight),
                ) ?: throw TraceHostFailure(
                    TraceFailureKind.OUT_OF_MEMORY,
                    "taking the chosen frame out of the photograph",
                )
            }
            TraceInput(
                rgba = cropped?.rgba ?: rgba,
                width = cropped?.rect?.width ?: decodedWidth,
                height = cropped?.rect?.height ?: decodedHeight,
                frameNote = cropped?.let { traceCropNote(it.rect, decodedWidth, decodedHeight) }.orEmpty(),
            )
        }

        val params = traceParamsOf(request.params)
        val effective = if (preview) tracePreviewParams(params) else params

        traceMemoryRefusal(
            sourcePixels = prepared.width.toLong() * prepared.height.toLong(),
            workingPixels = traceWorkingPixels(prepared.width, prepared.height, effective),
            engine = effective.edge.engine,
            heapBytes = traceHeapBytes(),
        )?.let { throw TraceRefusal(it) }

        val image = withContext(Dispatchers.Default) {
            traceImageOf(prepared.rgba, prepared.width, prepared.height)
        }

        val decoded = traceRunEngine(image, params, preview) { progress ->
            withContext(Dispatchers.Main) { onProgress(progress) }
        }

        return withContext(Dispatchers.Default) {
            tracePlateResult(
                decoded = decoded,
                rgba = prepared.rgba,
                sourceWidth = prepared.width,
                sourceHeight = prepared.height,
                frameNote = prepared.frameNote,
                request = request,
            )
        }
    }
}

private class TraceInput(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
    val frameNote: String,
)

fun traceRuntime(context: Context): TraceEngineRuntime {
    val app = context.applicationContext
    val (maxEdge, fdogMaxEdge) = traceCeilings(traceTotalRamBytes(app))
    return TraceEngineRuntimeImpl(
        availability = TraceAvailability(
            maxWorkingLongEdge = maxEdge,
            fdogMaxWorkingLongEdge = fdogMaxEdge,
            measuredOn = null,
        ),
        context = app,
    )
}

private fun traceTotalRamBytes(context: Context): Long? = runCatching {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return@runCatching null
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    info.totalMem.takeIf { it > 0L }
}.getOrNull()
