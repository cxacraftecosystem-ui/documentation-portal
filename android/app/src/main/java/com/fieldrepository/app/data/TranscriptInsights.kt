package com.fieldrepository.app.data

import java.io.IOException
import retrofit2.HttpException

/**
 * Interview summaries, key quotes and translations, read-only on the handset.
 *
 * The section is optional in every sense: a 404 means the feature is switched off on the server (or
 * the media is gone), and offline there is nothing cached to show. Both read as "draw nothing", never
 * as an error banner over the transcript a researcher came to read.
 */
suspend fun <T> insightOrHidden(call: suspend () -> T): T? =
    try {
        call()
    } catch (_: HttpException) {
        // 404: switched off or gone. Any other refusal also hides the section: it is optional.
        null
    } catch (_: IOException) {
        null
    }

object TranscriptInsightStates {
    const val READY = "READY"
    const val QUEUED = "QUEUED"
    const val PROCESSING = "PROCESSING"
    const val FAILED = "FAILED"

    /** Queued or being written: show a spinner and look again shortly. */
    fun isWorking(view: TranscriptInsightViewDto?): Boolean =
        view?.state == QUEUED || view?.state == PROCESSING

    /**
     * Whether the card draws anything. READY with an insight, QUEUED / PROCESSING, and FAILED with a
     * sentence; NO_TRANSCRIPT, NOT_GENERATED and any state this build does not know draw nothing.
     */
    fun isShown(view: TranscriptInsightViewDto?): Boolean = when (view?.state) {
        READY -> view.insight != null
        QUEUED, PROCESSING -> true
        FAILED -> !view.error.isNullOrBlank()
        else -> false
    }

    /** Whether the English translation is offered under the quotes. */
    fun hasTranslation(insight: TranscriptInsightDto): Boolean =
        (insight.translation.status == "COMPLETED" || insight.translation.status == "PARTIAL") &&
            !insight.translation.text.isNullOrBlank()

    /** "Machine-generated · gpt-4o-mini · 30 Oct 2026", or the edited stamp. */
    fun provenanceLine(insight: TranscriptInsightDto, formatDate: (String?) -> String?): String {
        val edited = insight.edited
        if (edited != null) {
            return listOfNotNull("Edited by a researcher", formatDate(edited.at)).joinToString(" · ")
        }
        val model = insight.provenance.modelId.takeIf { it.isNotBlank() && it != "UNRECORDED" } ?: "model not recorded"
        return listOfNotNull("Machine-generated", model, formatDate(insight.provenance.generatedAt)).joinToString(" · ")
    }
}
