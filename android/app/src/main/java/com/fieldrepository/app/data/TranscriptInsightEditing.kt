package com.fieldrepository.app.data

import kotlinx.serialization.Serializable

@Serializable
data class TranscriptInsightPatchRequest(
    val version: Int,
    val summaryLines: List<String>,
    val quotes: List<InsightQuoteDto>,
    val translationText: String? = null
)

@Serializable
data class TranscriptInsightRegenerateRequest(val discardEdits: Boolean)

class TranscriptInsightActions(
    val save: suspend (mediaId: String, body: TranscriptInsightPatchRequest) -> TranscriptInsightViewDto,
    val regenerate: suspend (mediaId: String, discardEdits: Boolean) -> TranscriptInsightViewDto
)

object TranscriptInsightEditing {
    const val SUMMARY_MAX_LINES = 5
    const val SUMMARY_LINE_MAX = 400
    const val QUOTES_MAX = 8
    const val NOT_GENERATED = "NOT_GENERATED"
    const val NEED_A_LINE = "Add at least one line."
    const val REPLACE_EDITS = "Regenerating replaces your edits."
    const val SAVE_FAILED = "Couldn't save. Try again."
    const val START_FAILED = "Couldn't start. Try again."

    fun cleanSummaryLines(lines: List<String>): List<String> =
        lines.map { it.trim().take(SUMMARY_LINE_MAX) }.filter { it.isNotEmpty() }.take(SUMMARY_MAX_LINES)

    fun cleanQuotes(quotes: List<InsightQuoteDto>): List<InsightQuoteDto> =
        quotes
            .map { InsightQuoteDto(original = it.original.trim(), english = it.english.trim(), speaker = it.speaker?.trim()?.ifEmpty { null }) }
            .filter { it.original.isNotEmpty() }
            .take(QUOTES_MAX)

    fun editRows(lines: List<String>): List<String> {
        val rows = lines.take(SUMMARY_MAX_LINES).toMutableList()
        while (rows.size < SUMMARY_MAX_LINES) rows.add("")
        return rows
    }

    fun isShown(view: TranscriptInsightViewDto?, editable: Boolean): Boolean =
        TranscriptInsightStates.isShown(view) ||
            (editable && view?.state == NOT_GENERATED && view.canRegenerate)

    fun mayEdit(view: TranscriptInsightViewDto, editable: Boolean): Boolean =
        editable && view.canEdit && view.insight != null && !TranscriptInsightStates.isWorking(view)

    fun mayRegenerate(view: TranscriptInsightViewDto, editable: Boolean): Boolean =
        editable && view.canRegenerate && !TranscriptInsightStates.isWorking(view)

    fun regenerateLabel(view: TranscriptInsightViewDto): String =
        if (view.insight != null) "Regenerate" else "Summarise"

    fun needsRegenerateConfirm(view: TranscriptInsightViewDto?): Boolean = view?.insight?.edited != null

    fun translatable(insight: TranscriptInsightDto): Boolean = insight.translation.status != "NOT_NEEDED"

    fun patch(
        insight: TranscriptInsightDto,
        lines: List<String>,
        quotes: List<InsightQuoteDto>,
        translation: String?
    ): TranscriptInsightPatchRequest? {
        val summary = cleanSummaryLines(lines)
        if (summary.isEmpty()) return null
        return TranscriptInsightPatchRequest(
            version = insight.version,
            summaryLines = summary,
            quotes = cleanQuotes(quotes),
            translationText = translation
        )
    }
}
