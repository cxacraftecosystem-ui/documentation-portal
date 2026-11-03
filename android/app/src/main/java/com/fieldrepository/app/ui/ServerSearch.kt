package com.fieldrepository.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.fieldrepository.app.questionnaire.ui.rememberKeptValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.fieldrepository.app.data.PageResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

const val SERVER_SEARCH_PAGE_SIZE = 100

const val SERVER_SEARCH_OFFLINE_NOTE = "Offline — showing saved records."
const val SERVER_SEARCH_FAILED_NOTE = "Couldn't search — showing saved records."

@Immutable
data class ServerSearch(
    val rows: List<SelectOption>,
    val total: Int?,
    val hasMore: Boolean,
    val loading: Boolean,
    val note: String?,
    val onQuery: (String) -> Unit,
    val onLoadMore: () -> Unit
)

fun serverSearchMatches(text: String, query: String): Boolean {
    val terms = query.trim().split(' ', '\t', '\n').filter { it.isNotBlank() }
    return terms.all { text.contains(it, ignoreCase = true) }
}

fun <T> keepLaterPages(first: List<T>, kept: List<T>, idOf: (T) -> String): List<T> {
    val fresh = first.mapTo(HashSet()) { idOf(it) }
    return first + kept.filter { idOf(it) !in fresh }
}

fun <T> mergeRowsById(previous: List<T>, incoming: List<T>, idOf: (T) -> String): List<T> {
    if (incoming.isEmpty()) return previous
    val seen = previous.mapTo(HashSet()) { idOf(it) }
    val added = incoming.filter { seen.add(idOf(it)) }
    return if (added.isEmpty()) previous else previous + added
}

fun <T> serverSearchRows(
    query: String,
    browse: List<T>,
    browseLoaded: Boolean,
    cached: List<T>,
    resultsFor: String?,
    results: List<T>,
    seen: List<T>,
    idOf: (T) -> String,
    textOf: (T) -> String
): List<T> {
    val term = query.trim()
    if (term.isEmpty()) return if (browseLoaded) browse else mergeRowsById(cached, browse, idOf)
    if (resultsFor == term) return results
    val pool = mergeRowsById(mergeRowsById(browse, cached, idOf), seen, idOf)
    return pool.filter { serverSearchMatches(textOf(it), term) }
}

fun serverSearchHasMore(
    query: String,
    browseLoaded: Boolean,
    browseCount: Int,
    browseTotal: Int,
    resultsFor: String?,
    resultsCount: Int,
    resultsTotal: Int
): Boolean {
    val term = query.trim()
    return when {
        term.isEmpty() -> browseLoaded && browseCount < browseTotal
        resultsFor == term -> resultsCount < resultsTotal
        else -> false
    }
}

@Stable
class ServerSearchState<T> internal constructor(
    private val idOf: (T) -> String,
    private val textOf: (T) -> String
) {
    var query by mutableStateOf("")
        private set
    var browse by mutableStateOf<List<T>>(emptyList())
        private set
    var browseLoaded by mutableStateOf(false)
        private set
    var browseTotal by mutableIntStateOf(0)
        private set
    var browseKey by mutableStateOf<Any?>(null)
        private set
    internal var currentKey: Any? = null
    var resultsFor by mutableStateOf<String?>(null)
        private set
    var results by mutableStateOf<List<T>>(emptyList())
        private set
    var resultsTotal by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var note by mutableStateOf<String?>(null)
        private set
    var seen by mutableStateOf<List<T>>(emptyList())
        private set
    private var scopeSeen by mutableStateOf<List<T>>(emptyList())

    internal var cached: List<T> = emptyList()
    internal var fetch: suspend (search: String?, page: Int) -> PageResponse<T> = { _, _ -> error("no source") }
    internal var scope: CoroutineScope? = null
    private var moreJob: Job? = null

    val rows: List<T>
        get() = serverSearchRows(query, browse, browseLoaded, cached, resultsFor, results, scopeSeen, idOf, textOf)

    val hasMore: Boolean
        get() = serverSearchHasMore(query, browseLoaded, browse.size, browseTotal, resultsFor, results.size, resultsTotal)

    val total: Int?
        get() {
            val term = query.trim()
            return when {
                term.isEmpty() && browseLoaded -> browseTotal
                term.isNotEmpty() && resultsFor == term -> resultsTotal
                else -> null
            }
        }

    val known: List<T>
        get() = mergeRowsById(mergeRowsById(browse, cached, idOf), seen, idOf)

    fun updateQuery(next: String) {
        query = next
    }

    private fun keep(rows: List<T>) {
        seen = mergeRowsById(seen, rows, idOf)
        scopeSeen = mergeRowsById(scopeSeen, rows, idOf)
    }

    internal fun seed(page: PageResponse<T>) {
        browse = page.items
        browseTotal = page.total
        browseLoaded = true
        browseKey = currentKey
        keep(page.items)
    }

    internal fun reset() {
        moreJob?.cancel()
        browse = emptyList()
        browseLoaded = false
        browseTotal = 0
        browseKey = null
        scopeSeen = emptyList()
        resultsFor = null
        results = emptyList()
        resultsTotal = 0
        note = null
        loading = false
    }

    private fun failed(failure: Throwable) {
        note = if (failure is IOException) SERVER_SEARCH_OFFLINE_NOTE else SERVER_SEARCH_FAILED_NOTE
    }

    internal suspend fun loadBrowse() {
        loading = true
        try {
            val page = fetch(null, 1)
            seed(page)
            note = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failed(failure)
        } finally {
            loading = false
        }
    }

    internal suspend fun search(term: String) {
        loading = true
        try {
            val page = fetch(term, 1)
            results = page.items
            resultsTotal = page.total
            resultsFor = term
            keep(page.items)
            note = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failed(failure)
        } finally {
            loading = false
        }
    }

    internal suspend fun refreshFirst() {
        try {
            val page = fetch(null, 1)
            browse = keepLaterPages(page.items, browse, idOf)
            browseTotal = page.total
            keep(page.items)
            note = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
        }
    }

    fun retry() {
        val launcher = scope ?: return
        launcher.launch { loadBrowse() }
    }

    fun loadMore() {
        if (moreJob?.isActive == true || !hasMore) return
        val term = query.trim()
        val launcher = scope ?: return
        moreJob = launcher.launch {
            loading = true
            try {
                if (term.isEmpty()) {
                    val next = browse.size / SERVER_SEARCH_PAGE_SIZE + 1
                    val page = fetch(null, next)
                    browse = mergeRowsById(browse, page.items, idOf)
                    browseTotal = page.total
                    if (page.items.isEmpty()) browseTotal = browse.size
                    keep(page.items)
                } else {
                    val next = results.size / SERVER_SEARCH_PAGE_SIZE + 1
                    val page = fetch(term, next)
                    if (resultsFor == term) {
                        results = mergeRowsById(results, page.items, idOf)
                        resultsTotal = if (page.items.isEmpty()) results.size else page.total
                    }
                    keep(page.items)
                }
                note = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failed(failure)
            } finally {
                loading = false
            }
        }
    }

    fun asServerSearch(toOption: (T) -> SelectOption): ServerSearch =
        asServerSearchRows(countsRows = true) { list -> list.map(toOption) }

    fun asServerSearchRows(countsRows: Boolean, rowsOf: (List<T>) -> List<SelectOption>): ServerSearch = ServerSearch(
        rows = rowsOf(rows),
        total = if (countsRows) total else null,
        hasMore = hasMore,
        loading = loading,
        note = note,
        onQuery = ::updateQuery,
        onLoadMore = ::loadMore
    )
}

@Composable
fun <T> rememberServerSearch(
    key: Any?,
    cached: List<T>,
    seed: PageResponse<T>?,
    idOf: (T) -> String,
    textOf: (T) -> String,
    enabled: Boolean = true,
    keepAs: String? = null,
    fetch: suspend (search: String?, page: Int) -> PageResponse<T>
): ServerSearchState<T> {
    val state = if (keepAs != null) rememberKeptValue(keepAs) { ServerSearchState(idOf, textOf) } else remember { ServerSearchState(idOf, textOf) }
    val latestFetch by rememberUpdatedState(fetch)
    state.fetch = { search, page -> latestFetch(search, page) }
    state.scope = rememberCoroutineScope()
    state.cached = cached

    LaunchedEffect(key, seed, enabled) {
        if (keepAs != null && state.browseLoaded && state.browseKey == key) {
            state.currentKey = key
            if (enabled) state.refreshFirst()
            return@LaunchedEffect
        }
        state.reset()
        state.currentKey = key
        if (!enabled) return@LaunchedEffect
        if (seed != null) state.seed(seed) else state.loadBrowse()
    }
    LaunchedEffect(key, state.query, enabled) {
        val term = state.query.trim()
        if (!enabled || term.isEmpty() || term == state.resultsFor) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        state.search(term)
    }
    return state
}
