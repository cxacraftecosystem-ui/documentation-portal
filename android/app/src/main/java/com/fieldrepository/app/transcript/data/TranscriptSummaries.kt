package com.fieldrepository.app.transcript.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The entry rows' badges, asked for together: every row on a page that wants a summary within
 * [WINDOW_MS] of the first goes in one `GET /transcripts/batch` (at most [BATCH_MAX] ids), so a
 * record with twenty recordings costs one request, not twenty.
 */
class TranscriptSummaryBatcher(
    private val scope: CoroutineScope,
    private val fetch: suspend (List<String>) -> List<TranscriptSummary>,
) {
    private val mutex = Mutex()
    private val waiting = LinkedHashMap<String, MutableList<CompletableDeferred<TranscriptSummary?>>>()
    private var flusher: Job? = null

    /** The summary, null when the server left the id out (unreadable, or no transcript). */
    suspend fun get(mediaId: String): TranscriptSummary? {
        val answer = CompletableDeferred<TranscriptSummary?>()
        mutex.withLock {
            waiting.getOrPut(mediaId) { mutableListOf() }.add(answer)
            if (flusher == null) flusher = scope.launch { delay(WINDOW_MS); flush() }
        }
        return answer.await()
    }

    private suspend fun flush() {
        val batch = mutex.withLock {
            val taken = LinkedHashMap(waiting)
            waiting.clear()
            flusher = null
            taken
        }
        for (ids in batch.keys.chunked(BATCH_MAX)) {
            val result = runCatching { fetch(ids) }
            for (id in ids) {
                val listeners = batch[id].orEmpty()
                result.fold(
                    onSuccess = { items -> val s = items.firstOrNull { it.mediaId == id }; listeners.forEach { it.complete(s) } },
                    onFailure = { e -> listeners.forEach { it.completeExceptionally(e) } },
                )
            }
        }
    }

    companion object {
        const val WINDOW_MS = 40L
        const val BATCH_MAX = 50
    }
}
