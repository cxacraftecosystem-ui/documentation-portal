package com.fieldrepository.app.ui.trace

import androidx.compose.runtime.Immutable

@Immutable
class TraceExportRequest(
    val geometry: TraceGeometry,
    val width: Int,
    val height: Int,
    val format: TraceExportFormat,
    val background: Int?,
    val provenanceNote: String,
)

sealed class TraceExportOutcome {
    class Done(val bytes: ByteArray) : TraceExportOutcome()

    class Refused(val reason: String) : TraceExportOutcome()
}

interface TraceEngineExporter {

    val refusal: String?

    suspend fun export(request: TraceExportRequest): TraceExportOutcome
}

class TraceExporterUnavailable(reason: String = TRACE_NO_EXPORTER_SENTENCE) : TraceEngineExporter {
    override val refusal: String = reason

    override suspend fun export(request: TraceExportRequest): TraceExportOutcome =
        TraceExportOutcome.Refused(refusal)
}

const val TRACE_NO_EXPORTER_SENTENCE: String =
    "Couldn't save in that format. Save as SVG or a picture."

sealed class TraceExportPlan {
    data object FromTraceSvg : TraceExportPlan()

    data object FromPlatformRaster : TraceExportPlan()

    data object FromExporter : TraceExportPlan()

    data class Refused(val reason: String) : TraceExportPlan()
}

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

const val TRACE_NO_GEOMETRY_SENTENCE: String =
    "Only the SVG can be saved from this trace. Trace again for other formats."
