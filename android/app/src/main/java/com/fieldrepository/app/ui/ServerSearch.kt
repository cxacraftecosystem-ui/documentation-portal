package com.fieldrepository.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/**
 * RECORD PICKERS THAT SEARCH THE SERVER (DC-A09).
 *
 * The craft, artisan and interview pickers used to walk up to twenty 100-row pages of their table at
 * start-up (2,000 rows) and filter that copy on the device. That cost twenty sequential requests
 * before the first form could be trusted, held every row in memory, and still stopped somewhere.
 *
 * Now a picker holds ONE page and asks the list route itself: typing goes to the route's own
 * `search` parameter after [SEARCH_DEBOUNCE_MS] of quiet, and "Load more" fetches the next page of
 * whatever is listed — the plain list or the matches. Nothing is out of reach, so nothing has to be
 * explained.
 *
 * OFFLINE the last answers still come back from the HTTP read cache (`data/OfflineReadCache.kt`), so a
 * page or a term asked before is served as it was. A term never asked fails, and the picker falls back
 * to filtering every row it has already seen, with [ServerSearchState.note] saying so in one line.
 *
 * Rows are only ever ADDED to [ServerSearchState.seen]: a row picked from a search result must still
 * have a label once the query is cleared, and a form that reads a picked row back (the product form
 * fills artisan + place from it) must still find it.
 */

/** Rows one request asks for. The server's own ceiling (`MAX_PAGE_SIZE`). */
const val SERVER_SEARCH_PAGE_SIZE = 100

/** What the sheet says when the list it shows came from the device instead of the server. */
const val SERVER_SEARCH_OFFLINE_NOTE = "Offline — showing saved records."
const val SERVER_SEARCH_FAILED_NOTE = "Couldn't search — showing saved records."

/**
 * What a searchable sheet needs from a caller whose rows live on the server. Handed to
 * [SearchableSelectField] / [SearchableMultiSelectField] as `server`; null keeps the local filter.
 *
 * @param rows what the sheet lists for the current query — already matched, so the sheet does not
 *   filter them again (the server matches fields the label does not show, such as notes).
 * @param total how many rows the current listing has in all, or null when unknown.
 */
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

/** Space-split terms, every one required, case-insensitive — the sheet's own local rule. */
fun serverSearchMatches(text: String, query: String): Boolean {
    val terms = query.trim().split(' ', '\t', '\n').filter { it.isNotBlank() }
    return terms.all { text.contains(it, ignoreCase = true) }
}

/** Union by id, first writer wins, nothing removed. */
fun <T> mergeRowsById(previous: List<T>, incoming: List<T>, idOf: (T) -> String): List<T> {
    if (incoming.isEmpty()) return previous
    val seen = previous.mapTo(HashSet()) { idOf(it) }
    val added = incoming.filter { seen.add(idOf(it)) }
    return if (added.isEmpty()) previous else previous + added
}

/**
 * WHAT THE SHEET LISTS — a pure function so a unit test can reach every arm.
 *
 *  • Blank query: the scope's own pages once they have arrived, the cached rows before that.
 *  • A query the server has answered: exactly that answer.
 *  • A query still in flight, or one the server could not answer: every row already on the device,
 *    filtered locally — so typing never shows an empty sheet while a request is out.
 */
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

/** Is there another page of what the sheet is listing? Never offline: a page not cached will fail. */
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

/**
 * One picker's server-backed list. Create with [rememberServerSearch].
 *
 * @property query what is in the sheet's box right now (raw; the debounce trims it).
 */
@Stable
class ServerSearchState<T> internal constructor(
    private val idOf: (T) -> String,
    private val textOf: (T) -> String
) {
    var query by mutableStateOf("")
        private set
    var browse by mutableStateOf<List<T>>(emptyList())
        private set
    /** True once the scope's page 1 has arrived (or a seed stood in for it). */
    var browseLoaded by mutableStateOf(false)
        private set
    var browseTotal by mutableIntStateOf(0)
        private set
    /** The scope [browse] was loaded for, so a caller never reads one scope's rows as another's. */
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
    /** Every row any answer has carried, for labels and read-backs. Only ever grows. */
    var seen by mutableStateOf<List<T>>(emptyList())
        private set
    // What this SCOPE's answers carried — the offline fallback searches these and not [seen], so a
    // workshop-scoped picker never offers the previous workshop's people while a request is out.
    private var scopeSeen by mutableStateOf<List<T>>(emptyList())

    // A plain field, deliberately not snapshot state: it is written during composition, and a state
    // write there that a reader of the same frame observes is a backwards write.
    internal var cached: List<T> = emptyList()
    internal var fetch: suspend (search: String?, page: Int) -> PageResponse<T> = { _, _ -> error("no source") }
    internal var scope: CoroutineScope? = null
    private var moreJob: Job? = null

    /** What the sheet lists now. See [serverSearchRows]. */
    val rows: List<T>
        get() = serverSearchRows(query, browse, browseLoaded, cached, resultsFor, results, scopeSeen, idOf, textOf)

    val hasMore: Boolean
        get() = serverSearchHasMore(query, browseLoaded, browse.size, browseTotal, resultsFor, results.size, resultsTotal)

    /** The total of what is listed, when the server has said. */
    val total: Int?
        get() {
            val term = query.trim()
            return when {
                term.isEmpty() && browseLoaded -> browseTotal
                term.isNotEmpty() && resultsFor == term -> resultsTotal
                else -> null
            }
        }

    /** Every row this picker knows, for a form that reads a chosen row back. */
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

    /** Ask for the scope's first page again — the Try again on a list that never arrived. */
    fun retry() {
        val launcher = scope ?: return
        launcher.launch { loadBrowse() }
    }

    /** The next page of whatever is listed. Ignored while one is already on its way. */
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
                    // A short page past the count means the table shrank under us: stop asking.
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

    /** The sheet's half, with rows turned into options by [toOption]. */
    fun asServerSearch(toOption: (T) -> SelectOption): ServerSearch =
        asServerSearchRows(countsRows = true) { list -> list.map(toOption) }

    /**
     * The same, for a caller that reorders or folds the rows (the tool form sorts by craft; the
     * interview picker folds sittings into one entry). [countsRows] false when folding changes the
     * count, so the sheet prints what it shows rather than the server's row total.
     */
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

/**
 * Remember a server-searched list for one scope.
 *
 * @param key the scope (a craft list, a workshop list, a record type). A change starts over.
 * @param cached rows already on the device, shown before the first answer and searched offline.
 * @param seed page 1 of this scope when the caller already holds it (the app-wide lookups), so the
 *   picker costs no request until somebody types or asks for more. Null fetches page 1 itself.
 * @param fetch one page of the scope, `search` null for the plain list.
 */
@Composable
fun <T> rememberServerSearch(
    key: Any?,
    cached: List<T>,
    seed: PageResponse<T>?,
    idOf: (T) -> String,
    textOf: (T) -> String,
    enabled: Boolean = true,
    fetch: suspend (search: String?, page: Int) -> PageResponse<T>
): ServerSearchState<T> {
    val state = remember { ServerSearchState(idOf, textOf) }
    val latestFetch by rememberUpdatedState(fetch)
    state.fetch = { search, page -> latestFetch(search, page) }
    state.scope = rememberCoroutineScope()
    state.cached = cached

    LaunchedEffect(key, seed, enabled) {
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
