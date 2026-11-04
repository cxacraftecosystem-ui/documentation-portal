package com.fieldrepository.app.ui.trace

import androidx.compose.runtime.Immutable

@Immutable
data class TraceStage(val id: String, val label: String)

val TRACE_STAGES: List<TraceStage> = listOf(
    TraceStage("prepare", "Preparing image"),
    TraceStage("matte", "Separating background"),
    TraceStage("crop", "Cropping to the subject"),
    TraceStage("gray", "Converting to grey"),
    TraceStage("denoise", "Reducing noise"),
    TraceStage("contrast", "Enhancing contrast"),
    TraceStage("edge", "Detecting edges"),
    TraceStage("cleanup", "Cleaning up"),
    TraceStage("skeleton", "Thinning strokes"),
    TraceStage("distance", "Measuring stroke width"),
    TraceStage("vectorize", "Tracing vectors"),
    TraceStage("document", "Assembling document"),
)

val TRACE_STAGE_COUNT: Int = TRACE_STAGES.size

fun traceStageIndex(stageId: String): Int = TRACE_STAGES.indexOfFirst { it.id == stageId }

val TRACE_SLOW_STAGE_IDS: List<String> = listOf("edge", "vectorize")

@Immutable
class TraceProgressWeights private constructor(
    private val startFractions: Map<String, Float>,
    val measured: Boolean,
) {

    fun fractionAt(stageId: String, fallback: Float): Float =
        startFractions[stageId] ?: fallback

    companion object {
        val Unweighted: TraceProgressWeights = TraceProgressWeights(
            startFractions = TRACE_STAGES.withIndex().associate { (index, stage) ->
                stage.id to index.toFloat() / TRACE_STAGE_COUNT
            },
            measured = false,
        )

        fun from(timings: List<TraceStageTiming>): TraceProgressWeights {
            val total = timings.sumOf { it.millis }
            if (timings.isEmpty() || total <= 0L) return Unweighted
            var elapsed = 0L
            val out = LinkedHashMap<String, Float>(timings.size)
            for (timing in timings) {
                out[timing.id] = elapsed.toFloat() / total.toFloat()
                elapsed += timing.millis
            }
            return TraceProgressWeights(startFractions = out, measured = true)
        }
    }
}

fun traceProgressSentence(
    progress: TraceProgress,
    stages: List<TraceStage> = TRACE_STAGES,
): String {
    val index = stages.indexOfFirst { it.id == progress.stageId }
    return if (index < 0) {
        progress.label
    } else {
        "${progress.label}. Stage ${index + 1} of ${stages.size}."
    }
}

val TRACE_KNOBS: Map<String, String> = linkedMapOf(
    "EDGE_SENSITIVITY" to "edge sensitivity",
    "STROKE_WIDTH" to "stroke width",
    "SIMPLIFY" to "simplify",
    "CORNER" to "corner threshold",
    "MIN_PATH_LENGTH" to "minimum path length",
    "MIN_BLOB_AREA" to "minimum blob area",
)

val TRACE_KNOB_NAMES: List<String> = TRACE_KNOBS.values.toList()

val TRACE_KNOB_KEY_FOR_NAME: Map<String, String> = mapOf(
    "edge sensitivity" to "edge.sensitivity",
    "stroke width" to "output.strokeWidth",
    "simplify" to "output.simplify",
    "corner threshold" to "output.corner",
    "minimum path length" to "output.minPathLength",
    "minimum blob area" to "cleanup.minBlobArea",
)
