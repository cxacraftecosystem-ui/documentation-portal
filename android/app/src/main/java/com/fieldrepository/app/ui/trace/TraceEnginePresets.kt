package com.fieldrepository.app.ui.trace

import com.offlinetracer.pipeline.Knobs
import com.offlinetracer.pipeline.StylePreset
import com.offlinetracer.pipeline.Styles
import com.offlinetracer.pipeline.SubjectPreset
import com.offlinetracer.pipeline.Subjects
import com.offlinetracer.pipeline.TraceParams

internal const val TRACE_SUBJECT_COMPOUNDS_NOTE: String =
    "Tapping again builds on the current settings."

internal val TRACE_SUBJECTS_ONLY_ON_THIS_ENGINE: List<String> =
    listOf("wood-carving", "stone-carving", "metalwork")

internal val TRACE_SUBJECTS_ONLY_ON_THE_PORTAL: List<String> = listOf("carving")

internal val TRACE_SUBJECT_DIVERGENCE_NOTES: Map<String, String> = emptyMap()

internal fun tracePresetTables(): TracePresetTables = TRACE_PRESET_TABLES

private val TRACE_PRESET_TABLES: TracePresetTables = TracePresetTables(
    styles = Styles.ALL.map(::traceStyleRow),
    subjects = Subjects.ALL.map(::traceSubjectRow),
)

private fun traceStyleRow(style: StylePreset): TracePreset = TracePreset(
    id = style.id,
    name = style.name,
    description = style.description,
    group = style.group,
)

private fun traceSubjectRow(subject: SubjectPreset): TracePreset = TracePreset(
    id = subject.id,
    name = subject.name,
    description = buildString {
        append(subject.hint)
        append(' ')
        append(TRACE_SUBJECT_COMPOUNDS_NOTE)
        TRACE_SUBJECT_DIVERGENCE_NOTES[subject.id]?.let {
            append(' ')
            append(it)
        }
    },
    group = "",
)

@Suppress("UNUSED_PARAMETER")
internal fun traceApplyStyle(base: TraceParams, styleId: String): TraceParams {
    val preset = Styles.byId(styleId)
        ?: throw IllegalArgumentException(traceNoSuchStyleSentence(styleId))
    return preset.params.sanitized()
}

internal fun traceApplySubject(base: TraceParams, subjectId: String): TraceParams {
    val preset = Subjects.byId(subjectId)
        ?: throw IllegalArgumentException(traceNoSuchSubjectSentence(subjectId))
    val adjusted = tracePreserveIdentity(base, preset.adjust(base))
    return Knobs.restore(base, adjusted, base.auto.handTuned).sanitized()
}

private fun tracePreserveIdentity(before: TraceParams, after: TraceParams): TraceParams = after.copy(
    edge = after.edge.copy(engine = before.edge.engine),
    output = after.output.copy(
        vectorMode = before.output.vectorMode,
        fillClosed = before.output.fillClosed,
    ),
    styleId = before.styleId,
    auto = before.auto,
)

internal fun traceNoSuchStyleSentence(styleId: String): String =
    "There is no style called \"${styleId.trim()}\"."

internal fun traceNoSuchSubjectSentence(subjectId: String): String {
    val bare = "There is no subject called \"${subjectId.trim()}\"."
    if (subjectId.trim() !in TRACE_SUBJECTS_ONLY_ON_THE_PORTAL) return bare
    return bare + " On this phone “Wood & stone carving” is two materials, “Wood carving” and " +
        "“Stone carving”. Choose one of those."
}

internal fun traceApplyStyle(base: TraceValues, styleId: String): TraceValues =
    traceValuesOfParams(traceApplyStyle(traceParamsOf(base), styleId))

internal fun traceApplySubject(base: TraceValues, subjectId: String): TraceValues =
    traceValuesOfParams(traceApplySubject(traceParamsOf(base), subjectId))
