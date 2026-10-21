package com.fieldrepository.app.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Collects the insight reads asked for within [windowMillis] of each other and answers them from
 * `GET /media/insights?ids=` — at most [BATCH_MAX] ids per request — instead of one
 * `GET /media/{id}/insights` per card. A screen composes all of its transcript cards in one frame,
 * and their 15 s polls fall due together, so each load and each poll is one request for the list.
 *
 * [fetchBatch] answers null when the request failed (switched off, offline): every id in it reads
 * as null, exactly what the single read gave. An id the answer leaves out is null too. The batch
 * carries no translation text; the card reads the single item when the translation is opened.
 */
class TranscriptInsightBatcher(
    private val fetchBatch: suspend (List<String>) -> Map<String, TranscriptInsightViewDto>?,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val lock = Mutex()
    private var pending = LinkedHashMap<String, MutableList<CompletableDeferred<TranscriptInsightViewDto?>>>()
    private var flushJob: Job? = null

    suspend fun load(mediaId: String): TranscriptInsightViewDto? {
        val answer = CompletableDeferred<TranscriptInsightViewDto?>()
        lock.withLock {
            pending.getOrPut(mediaId) { mutableListOf() }.add(answer)
            if (flushJob == null) {
                flushJob = scope.launch {
                    delay(windowMillis)
                    flush()
                }
            }
        }
        return answer.await()
    }

    private suspend fun flush() {
        val batch = lock.withLock {
            val taken = pending
            pending = LinkedHashMap()
            flushJob = null
            taken
        }
        batch.keys.toList().chunked(BATCH_MAX).forEach { slice ->
            val answers = try {
                fetchBatch(slice)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                null
            }
            slice.forEach { id -> batch[id]?.forEach { it.complete(answers?.get(id)) } }
        }
    }

    companion object {
        /** The server's cap on ids per request. */
        const val BATCH_MAX = 50
        /** Long enough for one frame's cards to join; short enough to be invisible. */
        const val WINDOW_MILLIS = 40L
    }
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

    /**
     * Whether the disclosure is drawn for an item read through the batch, which leaves the text out
     * (null): a finished translation is offered and its text read when it is opened. [loadedText] is
     * that text once the full read has answered; a blank one (like an empty string in the batch) is
     * a translation with nothing in it, and the panel is hidden.
     */
    fun offersTranslation(insight: TranscriptInsightDto, loadedText: String? = null): Boolean {
        if (insight.translation.status != "COMPLETED" && insight.translation.status != "PARTIAL") return false
        val known = loadedText ?: insight.translation.text
        return known == null || known.isNotBlank()
    }

    /**
     * The translation text a full read of one item answered with, or null when the read failed (no
     * answer, or no insight in it) so the disclosure asks again the next time it is opened. An insight
     * that came back without text has none: "" (hidden by [offersTranslation]), never a retry loop.
     */
    fun fullTranslationText(view: TranscriptInsightViewDto?): String? {
        val insight = view?.insight ?: return null
        return insight.translation.text.orEmpty()
    }

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
