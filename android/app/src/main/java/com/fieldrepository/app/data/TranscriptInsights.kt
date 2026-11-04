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

suspend fun <T> insightOrHidden(call: suspend () -> T): T? =
    try {
        call()
    } catch (_: HttpException) {
        null
    } catch (_: IOException) {
        null
    }

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
        const val BATCH_MAX = 50
        const val WINDOW_MILLIS = 40L
    }
}

object TranscriptInsightStates {
    const val READY = "READY"
    const val QUEUED = "QUEUED"
    const val PROCESSING = "PROCESSING"
    const val FAILED = "FAILED"

    fun isWorking(view: TranscriptInsightViewDto?): Boolean =
        view?.state == QUEUED || view?.state == PROCESSING

    fun isShown(view: TranscriptInsightViewDto?): Boolean = when (view?.state) {
        READY -> view.insight != null
        QUEUED, PROCESSING -> true
        FAILED -> !view.error.isNullOrBlank()
        else -> false
    }

    fun hasTranslation(insight: TranscriptInsightDto): Boolean =
        (insight.translation.status == "COMPLETED" || insight.translation.status == "PARTIAL") &&
            !insight.translation.text.isNullOrBlank()

    fun offersTranslation(insight: TranscriptInsightDto, loadedText: String? = null): Boolean {
        if (insight.translation.status != "COMPLETED" && insight.translation.status != "PARTIAL") return false
        val known = loadedText ?: insight.translation.text
        return known == null || known.isNotBlank()
    }

    fun fullTranslationText(view: TranscriptInsightViewDto?): String? {
        val insight = view?.insight ?: return null
        return insight.translation.text.orEmpty()
    }

    fun provenanceLine(insight: TranscriptInsightDto, formatDate: (String?) -> String?): String {
        val edited = insight.edited
        if (edited != null) {
            return listOfNotNull("Edited by a researcher", formatDate(edited.at)).joinToString(" · ")
        }
        val model = insight.provenance.modelId.takeIf { it.isNotBlank() && it != "UNRECORDED" } ?: "model not recorded"
        return listOfNotNull("Machine-generated", model, formatDate(insight.provenance.generatedAt)).joinToString(" · ")
    }
}
