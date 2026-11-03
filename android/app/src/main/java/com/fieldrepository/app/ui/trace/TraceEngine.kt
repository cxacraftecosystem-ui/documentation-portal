package com.fieldrepository.app.ui.trace

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
sealed class TraceValue {
    data class Num(val value: Double) : TraceValue()

    data class Flag(val value: Boolean) : TraceValue()

    data class Choice(val value: String) : TraceValue()

    data object Absent : TraceValue()
}

@Immutable
class TraceValues(
    private val leaves: Map<String, TraceValue>,
    val wire: String,
) {
    operator fun get(key: String): TraceValue? = leaves[key]

    fun number(key: String): Double? = (leaves[key] as? TraceValue.Num)?.value

    fun flag(key: String): Boolean? = (leaves[key] as? TraceValue.Flag)?.value

    fun choice(key: String): String? = (leaves[key] as? TraceValue.Choice)?.value

    fun present(key: String): Boolean {
        val leaf = leaves[key] ?: return false
        return leaf != TraceValue.Absent
    }

    val keys: Set<String> get() = leaves.keys

    val styleId: String get() = choice(TRACE_STYLE_ID_KEY).orEmpty()
}

const val TRACE_STYLE_ID_KEY: String = "styleId"

@Immutable
data class TracePreset(
    val id: String,
    val name: String,
    val description: String,
    val group: String,
)

@Immutable
data class TracePresetTables(
    val styles: List<TracePreset>,
    val subjects: List<TracePreset>,
)

enum class TraceRunKind {
    PREVIEW,

    ATTACH,

    EXPORT,
}

val TraceRunKind.isFullResolution: Boolean get() = this != TraceRunKind.PREVIEW

@Immutable
data class TraceRequest(
    val photograph: Uri,
    val params: TraceValues,
    val kind: TraceRunKind,
    val frame: TraceFrameChoice? = null,
    val plateLongEdgePx: Int = TRACE_PLATE_LONG_EDGE_PX,
)

const val TRACE_PLATE_LONG_EDGE_PX: Int = 1024

@Immutable
data class TraceProgress(
    val stageId: String,
    val label: String,
    val fraction: Float,
)

@Immutable
data class TraceStageTiming(val id: String, val label: String, val millis: Long)

@Immutable
class TraceResult(
    val svg: String,
    val geometry: TraceGeometry?,
    val tracePlate: Bitmap?,
    val photographPlate: Bitmap?,
    val plateRefusal: String,
    val width: Int,
    val height: Int,
    val workingWidth: Int,
    val workingHeight: Int,
    val shapeCount: Int,
    val nodeCount: Int,
    val stages: List<TraceStageTiming>,
    val totalMillis: Long,
    val notes: List<String>,
    val appliedParams: TraceValues,
    val autoSubjectId: String,
    val suggestedStyleId: String,
    val frameNote: String = "",
) {
    val isPreview: Boolean get() = workingWidth < width || workingHeight < height

    val hasPlates: Boolean get() = tracePlate != null && photographPlate != null
}

sealed class TraceOutcome {
    data class Done(val result: TraceResult) : TraceOutcome()

    data class Refused(val reason: String) : TraceOutcome()
}

@Immutable
data class TraceAvailability(
    val maxWorkingLongEdge: Int,
    val fdogMaxWorkingLongEdge: Int,
    val measuredOn: String?,
)

interface TraceEngineRuntime {

    val availability: TraceAvailability

    suspend fun presets(): TracePresetTables

    suspend fun defaults(): TraceValues

    suspend fun withOverrides(base: TraceValues, patch: Map<String, TraceValue>): TraceValues

    suspend fun applyStyle(base: TraceValues, styleId: String): TraceValues

    suspend fun applySubject(base: TraceValues, subjectId: String): TraceValues

    suspend fun trace(
        request: TraceRequest,
        onProgress: (TraceProgress) -> Unit,
    ): TraceOutcome
}

const val TRACE_ENGINE_SILENT_SENTENCE: String =
    "Tracing controls didn't load. Close and reopen."
