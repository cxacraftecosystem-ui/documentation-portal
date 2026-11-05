package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fieldrepository.app.data.SearchResultsDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal typealias SearchPageFetch = suspend (types: List<String>?, page: Int, pageSize: Int) -> SearchResultsDto

internal data class SearchSection(
    val recordType: String,
    val title: String,
    val total: Int,
    val rows: List<SearchRow>,
    val nextPage: Int = 2,
    val loading: Boolean = false,
) {
    val remaining: Int get() = (total - rows.size).coerceAtLeast(0)
    val more: Boolean get() = remaining > 0
}

internal object SearchAllText {
    const val searching = "Searching…"
    const val nothing = "No matching records."
    const val hide = "Hide"
    const val failed = "Search failed"
    const val tryAgain = "Try again"
    fun showAll(total: Int) = "Show all $total"
    fun title(total: Int) = if (total == 1) "1 record" else "$total records"
    fun more(n: Int, title: String) = "Show $n more ${title.lowercase()}"
    fun count(shown: Int, total: Int) = if (shown >= total) "$total" else "$shown of $total"
    fun shown(shown: Int, total: Int) = if (shown >= total) "All $shown shown" else "$shown of $total shown"
}

internal object SearchAllRules {
    const val PAGE_SIZE = SEARCH_PAGE_SIZE

    fun sections(result: SearchResultsDto, filters: SearchFilters): List<SearchSection> =
        result.toSearchBuckets()
            .filter { filters.includes(it.recordType) && (it.total > 0 || it.rows.isNotEmpty()) }
            .map { SearchSection(it.recordType, it.title, maxOf(it.total, it.rows.size), it.rows.distinctBy { r -> r.id }) }

    fun rowsOf(result: SearchResultsDto, recordType: String): List<SearchRow> =
        result.toSearchBuckets().firstOrNull { it.recordType == recordType }?.rows.orEmpty()

    fun append(section: SearchSection, page: List<SearchRow>): SearchSection {
        val seen = section.rows.map { it.id }.toHashSet()
        val fresh = page.filter { seen.add(it.id) }
        val rows = section.rows + fresh
        val total = if (fresh.isEmpty()) rows.size else section.total
        return section.copy(rows = rows, total = total, nextPage = section.nextPage + 1, loading = false)
    }
}

@Stable
internal class SearchAllState(private val filters: SearchFilters, private val fetch: SearchPageFetch) {
    var sections by mutableStateOf<List<SearchSection>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var searched by mutableStateOf(false)
        private set

    val total: Int get() = sections.sumOf { it.total }

    val shown: Int get() = sections.sumOf { it.rows.size }

    suspend fun start() {
        loading = true
        error = null
        try {
            sections = SearchAllRules.sections(fetch(filters.bucketTypes(), 1, SearchAllRules.PAGE_SIZE), filters)
            searched = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.apiErrorMessage(SearchAllText.failed)
        }
        loading = false
    }

    suspend fun more(recordType: String) {
        val section = sections.firstOrNull { it.recordType == recordType } ?: return
        if (section.loading || !section.more) return
        update(recordType) { it.copy(loading = true) }
        try {
            val page = fetch(listOf(SearchRecordTypes.bucket(recordType)), section.nextPage, SearchAllRules.PAGE_SIZE)
            update(recordType) { SearchAllRules.append(it, SearchAllRules.rowsOf(page, recordType)) }
            error = null
        } catch (cancelled: CancellationException) {
            update(recordType) { it.copy(loading = false) }
            throw cancelled
        } catch (failure: Throwable) {
            update(recordType) { it.copy(loading = false) }
            error = failure.apiErrorMessage(SearchAllText.failed)
        }
    }

    private fun update(recordType: String, change: (SearchSection) -> SearchSection) {
        sections = sections.map { if (it.recordType == recordType) change(it) else it }
    }
}

@Composable
internal fun rememberSearchAll(filters: SearchFilters, fetch: SearchPageFetch): SearchAllState {
    val state = remember(filters) { SearchAllState(filters, fetch) }
    LaunchedEffect(state) { state.start() }
    return state
}

@Composable
internal fun SearchAllList(
    state: SearchAllState,
    onOpen: (recordType: String, recordId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    gutter: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth()) {
        if (state.sections.isNotEmpty()) {
            Text(
                SearchAllText.shown(state.shown, state.total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = gutter, end = gutter, bottom = 4.dp).testTag("search.all.shown"),
            )
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("search.all.list"),
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading && state.sections.isEmpty()) {
                item("loading") { Text(SearchAllText.searching, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            state.error?.let { message ->
                item("error") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        if (state.sections.isEmpty()) {
                            TextButton(onClick = { scope.launch { state.start() } }, modifier = Modifier.heightIn(min = 44.dp)) { Text(SearchAllText.tryAgain) }
                        }
                    }
                }
            }
            if (state.searched && !state.loading && state.sections.isEmpty() && state.error == null) {
                item("none") { Text(SearchAllText.nothing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("search.all.none")) }
            }
            state.sections.forEach { section ->
                item("head.${section.recordType}") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("search.all.section.${section.recordType}"), verticalAlignment = Alignment.CenterVertically) {
                        Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).semantics { heading() })
                        Text(SearchAllText.count(section.rows.size, section.total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("search.all.count.${section.recordType}"))
                    }
                }
                items(section.rows, key = { "${section.recordType}:${it.id}" }) { row ->
                    Box(Modifier.testTag("search.all.row.${section.recordType}.${row.id}")) {
                        SearchResultRow(row = row, onOpen = { onOpen(row.recordType, row.id, row.title) })
                    }
                }
                if (section.more) {
                    item("more.${section.recordType}") {
                        LaunchedEffect(section.rows.size) { state.more(section.recordType) }
                        OutlinedButton(
                            onClick = { scope.launch { state.more(section.recordType) } },
                            enabled = !section.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search.all.more.${section.recordType}"),
                        ) {
                            Text(if (section.loading) SearchAllText.searching else SearchAllText.more(minOf(SearchAllRules.PAGE_SIZE, section.remaining), section.title))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchAllDialog(
    filters: SearchFilters,
    fetch: SearchPageFetch,
    onOpen: (recordType: String, recordId: String, title: String) -> Unit,
    onClose: () -> Unit,
) {
    val state = rememberSearchAll(filters, fetch)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("search.all"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("search.all.back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.searched) SearchAllText.title(state.total) else SearchAllText.searching,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.semantics { heading() }.testTag("search.all.title"),
                        )
                        filters.query.trim().takeIf { it.isNotEmpty() }?.let { q ->
                            Text("“$q”", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                SearchAllList(
                    state = state,
                    onOpen = { type, id, title -> onClose(); onOpen(type, id, title) },
                    modifier = Modifier.weight(1f),
                    gutter = 16.dp,
                )
            }
        }
    }
}
