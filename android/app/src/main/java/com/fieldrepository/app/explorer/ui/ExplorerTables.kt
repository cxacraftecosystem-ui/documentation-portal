package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.data.ExplorerBusy
import com.fieldrepository.app.explorer.data.ExplorerCells
import com.fieldrepository.app.explorer.data.ExplorerReport
import com.fieldrepository.app.explorer.data.ExplorerSheet
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

sealed interface TablesLoad {
    data object Idle : TablesLoad
    data object Loading : TablesLoad
    data class Ready(val report: ExplorerReport, val path: String, val query: String) : TablesLoad
    data object Failed : TablesLoad
}

object ExplorerTablesMemory {
    private const val KEEP = 24
    private val held = object : LinkedHashMap<String, ExplorerReport>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExplorerReport>?): Boolean = size > KEEP
    }

    fun key(owner: String?, path: String, query: String, filters: Map<String, String> = emptyMap()) =
        "${owner.orEmpty()}|$path|${query.trim().lowercase()}" + filters.entries.sortedBy { it.key }.joinToString("") { "|${it.key}=${it.value}" }

    fun get(key: String): ExplorerReport? = synchronized(held) { held[key] }

    fun put(key: String, report: ExplorerReport) = synchronized(held) { held[key] = report }

    fun forget(owner: String?, path: String) = synchronized(held) {
        val prefix = "${owner.orEmpty()}|$path|"
        held.keys.filter { it.startsWith(prefix) }.forEach { held.remove(it) }
    }

    fun clear() = synchronized(held) { held.clear() }
}

const val TABLE_PREVIEW_ROWS = 20

const val TABLE_MORE_ROWS = 50

@Stable
class ExplorerTablesState(
    private val api: ExplorerApi,
    private val scope: CoroutineScope,
    private val owner: String?,
    private val debounceMs: Long = 400,
    private val busyTickMs: Long = 1_000,
    private val attempts: Int = 4,
) {
    var path by mutableStateOf<String?>(null)
        private set
    var query by mutableStateOf("")
        private set
    var load by mutableStateOf<TablesLoad>(TablesLoad.Idle)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var busySeconds by mutableStateOf<Int?>(null)
        private set
    var refreshFailed by mutableStateOf(false)
        private set
    var sheetName by mutableStateOf<String?>(null)
        private set
    var shown by mutableStateOf(TABLE_PREVIEW_ROWS)
        private set
    var loadingRows by mutableStateOf(false)
        private set
    var rowsFailed by mutableStateOf(false)
        private set
    var filters by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var requests = 0
        private set

    private var job: Job? = null
    private var rowsJob: Job? = null

    private fun rewind() {
        rowsJob?.cancel()
        rowsJob = null
        shown = TABLE_PREVIEW_ROWS
        loadingRows = false
        rowsFailed = false
    }

    val ready: TablesLoad.Ready? get() = load as? TablesLoad.Ready

    fun show(target: String) {
        if (target == path && (load is TablesLoad.Ready || load is TablesLoad.Loading || job?.isActive == true)) return
        if (target != path) {
            query = ""
            sheetName = null
            rewind()
        }
        path = target
        fetch(immediate = true)
    }

    fun filter(next: Map<String, String>) {
        if (next == filters) return
        filters = next
        rewind()
        if (path == null) return
        load = TablesLoad.Loading
        fetch(immediate = true)
    }

    fun search(text: String) {
        if (text == query) return
        query = text
        rewind()
        fetch(immediate = false)
    }

    fun pick(sheet: String) {
        if (sheet != sheetName) rewind()
        sheetName = sheet
    }

    fun more(sheet: ExplorerSheet) {
        val now = ready ?: return
        val searching = now.query.isNotBlank()
        val next = shown + TABLE_MORE_ROWS
        val loaded = dataRows(sheet)
        if (next <= loaded || !serverHasMore(sheet, searching)) {
            shown = next
            return
        }
        if (rowsJob?.isActive == true) return
        val at = now.path
        val words = now.query
        val name = sheet.name
        val chosen = filters
        rowsFailed = false
        loadingRows = true
        rowsJob = scope.launch {
            try {
                val page = withBusyRetry { api.reportJson(at, words.trim().ifBlank { null }, sheet = name, offset = loaded, filters = chosen) }
                val fresh = page.sheets.firstOrNull { it.name == name }
                val current = ready
                if (fresh == null || fresh.offset != loaded) {
                    rowsFailed = true
                } else if (current != null && current.path == at && current.query == words && filters == chosen) {
                    val merged = current.report.copy(sheets = current.report.sheets.map { if (it.name == name) joined(it, fresh) else it })
                    ExplorerTablesMemory.put(ExplorerTablesMemory.key(owner, at, words, chosen), merged)
                    load = TablesLoad.Ready(merged, at, words)
                    shown = next
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                rowsFailed = true
            } finally {
                loadingRows = false
                busySeconds = null
            }
        }
    }

    fun retry() = fetch(immediate = true)

    fun reload() {
        val at = path ?: return
        rewind()
        ExplorerTablesMemory.forget(owner, at)
        fetch(immediate = true)
    }

    fun clear() {
        job?.cancel()
        job = null
        path = null
        query = ""
        sheetName = null
        rewind()
        load = TablesLoad.Idle
        refreshing = false
        busySeconds = null
        refreshFailed = false
    }

    private fun fetch(immediate: Boolean) {
        val at = path ?: return
        val words = query
        val chosen = filters
        val key = ExplorerTablesMemory.key(owner, at, words, chosen)
        job?.cancel()
        refreshFailed = false
        busySeconds = null
        ExplorerTablesMemory.get(key)?.let {
            load = TablesLoad.Ready(it, at, words)
            refreshing = false
            return
        }
        val kept = (load as? TablesLoad.Ready)?.takeIf { it.path == at }
        if (kept == null) {
            load = TablesLoad.Loading
            refreshing = false
        } else {
            refreshing = true
        }
        job = scope.launch {
            if (!immediate && words.isNotBlank()) delay(debounceMs)
            try {
                val report = withBusyRetry { api.reportJson(at, words.trim().ifBlank { null }, filters = chosen) }
                ExplorerTablesMemory.put(key, report)
                if (path == at && query == words && filters == chosen) load = TablesLoad.Ready(report, at, words)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (path == at && query == words) {
                    if (kept != null) refreshFailed = true else load = TablesLoad.Failed
                }
            } finally {
                if (path == at && query == words) {
                    refreshing = false
                    busySeconds = null
                }
            }
        }
    }

    private suspend fun <T> withBusyRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                requests++
                return block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val busy = ExplorerBusy.of(failure)
                attempt++
                if (busy == null || attempt >= attempts) throw failure
                var left = minOf(busy.retryAfterSeconds.toLong() shl (attempt - 1), ExplorerBusy.MAX_SECONDS.toLong()).toInt()
                while (left > 0) {
                    busySeconds = left
                    delay(busyTickMs)
                    left--
                }
                busySeconds = null
            }
        }
    }

    companion object {
        fun picked(report: ExplorerReport, name: String?, searching: Boolean): ExplorerSheet? {
            val sheets = report.sheets
            name?.let { wanted -> sheets.firstOrNull { it.name == wanted }?.let { return it } }
            if (searching) sheets.firstOrNull { (it.matchedRows ?: it.rows.size) > 0 }?.let { return it }
            return sheets.firstOrNull { it.rows.isNotEmpty() } ?: sheets.firstOrNull()
        }

        fun count(sheet: ExplorerSheet, searching: Boolean): Int =
            if (searching) sheet.matchedRows ?: sheet.rows.size else sheet.totalRows ?: sheet.rows.size

        fun dataRows(sheet: ExplorerSheet): Int = sheet.rowNumbers?.let { numbers -> sheet.rows.indices.count { numbers.getOrNull(it) != 0 } } ?: sheet.rows.size

        fun serverHasMore(sheet: ExplorerSheet, searching: Boolean): Boolean {
            val loaded = dataRows(sheet)
            val known = if (searching) sheet.matchedRows else sheet.totalRows
            return if (known != null) known > loaded else sheet.preview && loaded > 0
        }

        fun window(sheet: ExplorerSheet, searching: Boolean, shown: Int): List<Pair<List<JsonElement>, Int?>> {
            val numbers = sheet.rowNumbers
            val paired = sheet.rows.mapIndexed { index, row -> row to numbers?.getOrNull(index) }
            val rows = if (serverHasMore(sheet, searching)) paired.filter { it.second != 0 } else paired
            return rows.take(shown)
        }

        fun canShowMore(sheet: ExplorerSheet, searching: Boolean, shown: Int): Boolean =
            shown < sheet.rows.size || serverHasMore(sheet, searching)

        fun joined(old: ExplorerSheet, fresh: ExplorerSheet): ExplorerSheet {
            val numbers = old.rowNumbers
            val incomingNumbers = fresh.rowNumbers
            val keep = if (numbers == null) old.rows.indices.toList() else old.rows.indices.filter { numbers.getOrNull(it) != 0 }
            val seen = keep.mapNotNull { numbers?.getOrNull(it) }.toHashSet()
            val incoming = fresh.rows.indices.filter { i -> incomingNumbers?.getOrNull(i)?.let { it == 0 || it !in seen } ?: true }
            return fresh.copy(
                rows = keep.map { old.rows[it] } + incoming.map { fresh.rows[it] },
                rowNumbers = if (numbers == null || incomingNumbers == null) null else keep.map { numbers[it] } + incoming.map { incomingNumbers[it] },
                offset = 0,
            )
        }
    }
}

@Composable
internal fun TablesScreen(
    folder: String,
    tables: ExplorerTablesState,
    online: Boolean,
    onBack: () -> Unit,
    onCsv: (sheet: String) -> Unit,
    onReport: () -> Unit,
    modifier: Modifier = Modifier,
    filterBar: (@Composable () -> Unit)? = null,
    localOff: Boolean = false,
    onClearFilters: () -> Unit = {},
) {
    val large = LocalDensity.current.fontScale >= 1.5f
    val ready = tables.ready
    val sheet = ready?.let { ExplorerTablesState.picked(it.report, tables.sheetName, it.query.isNotBlank()) }
    Column(modifier.fillMaxWidth().testTag("explorer.tables.screen")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp).testTag("explorer.tables.back")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = ExplorerText.back, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(26.dp))
            }
            Text(ExplorerText.tables, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f).semantics { heading() })
            Text(folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 180.dp))
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("explorer.tree"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            if (filterBar != null) item("filters") { Box(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { filterBar() } }
            if (localOff) item("tables") { FilteredEmptyCard("", localOff = true, onClear = onClearFilters) } else item("tables") { ExplorerTables(tables, online, onCsv, onReport, buttons = false) }
        }
        if (sheet != null && !localOff) {
            val csv: @Composable (Modifier) -> Unit = { m -> ExplorerSecondaryButton(ExplorerUiText.thisTable, onClick = { onCsv(sheet.name) }, enabled = online, modifier = m.testTag("explorer.csv.${sheet.name}")) }
            val full: @Composable (Modifier) -> Unit = { m -> ExplorerPrimaryButton(ExplorerUiText.fullReport, onClick = onReport, enabled = online, modifier = m.testTag("explorer.tables.report")) }
            if (large) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    full(Modifier.fillMaxWidth())
                    csv(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    csv(Modifier.weight(1f))
                    full(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun ExplorerTables(
    state: ExplorerTablesState,
    online: Boolean,
    onCsv: (sheet: String) -> Unit,
    onReport: () -> Unit,
    buttons: Boolean = true,
) {
    val ready = state.ready
    val searching = state.query.isNotBlank()
    val sheet = ready?.let { ExplorerTablesState.picked(it.report, state.sheetName, it.query.isNotBlank()) }
    Column(Modifier.fillMaxWidth().testTag("explorer.tables"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ready != null && ready.report.sheets.isNotEmpty() && sheet != null) {
            SheetPicker(ready.report.sheets, sheet, ready.query.isNotBlank(), onPick = state::pick)
        }
        TableSearch(state, sheet, searching && sheet != null && ready?.query?.isNotBlank() == true)
        state.busySeconds?.let { seconds ->
            Text(
                ExplorerText.busy(seconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("explorer.tables.busy"),
            )
        }
        if (state.refreshing && state.busySeconds == null) LoadingLine(ExplorerUiText.refreshing)
        if (state.refreshFailed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ExplorerText.tablesFailed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                ExplorerLinkButton(ExplorerText.retry, onClick = state::retry)
            }
        }
        when (val now = state.load) {
            TablesLoad.Idle, TablesLoad.Loading -> if (state.busySeconds == null) TableSkeleton()
            TablesLoad.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HintLine(ExplorerText.tablesFailed)
                ExplorerPrimaryButton(ExplorerText.retry, onClick = state::retry, modifier = Modifier.fillMaxWidth().testTag("explorer.tables.retry"))
            }
            is TablesLoad.Ready -> {
                if (now.report.sheets.isEmpty() || sheet == null) {
                    HintLine(ExplorerText.noRows)
                } else {
                    val words = now.query.trim()
                    val searchingRows = words.isNotBlank()
                    val rows = ExplorerTablesState.window(sheet, searchingRows, state.shown)
                    if (rows.isEmpty()) HintLine(if (searchingRows) ExplorerText.matches(0, sheet.totalRows ?: 0) else ExplorerText.noRows)
                    if (rows.isNotEmpty()) {
                        ExplorerTableGrid(
                            headers = sheet.headers,
                            rows = rows.mapIndexed { index, (row, number) -> row.map { ExplorerCells.text(it) } to (number ?: if (words.isBlank()) index + 1 else null) },
                            query = words,
                            keyPrefix = sheet.name,
                        )
                    }
                    val shown = rows.count { it.second != 0 }
                    val total = ExplorerTablesState.count(sheet, searchingRows)
                    if (total > shown) Text(ExplorerUiText.showingRows(shown, total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("explorer.tables.showing"))
                    if (ExplorerTablesState.canShowMore(sheet, searchingRows, state.shown)) {
                        val next = if (total > shown) minOf(TABLE_MORE_ROWS, total - shown) else TABLE_MORE_ROWS
                        ExplorerSecondaryButton(
                            text = if (state.loadingRows) ExplorerUiText.loadingRows else ExplorerUiText.moreRows(next),
                            onClick = { state.more(sheet) },
                            enabled = !state.loadingRows,
                            modifier = Modifier.fillMaxWidth().testTag("explorer.tables.more"),
                        )
                    }
                    if (state.rowsFailed) HintLine(ExplorerUiText.rowsFailed)
                    if (buttons) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ExplorerSecondaryButton(ExplorerUiText.thisTable, onClick = { onCsv(sheet.name) }, enabled = online, modifier = Modifier.weight(1f).testTag("explorer.csv.${sheet.name}"))
                            ExplorerPrimaryButton(ExplorerUiText.fullReport, onClick = onReport, enabled = online, modifier = Modifier.weight(1f).testTag("explorer.tables.report"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableSearch(state: ExplorerTablesState, sheet: ExplorerSheet?, showCount: Boolean) {
    var focused by remember { mutableStateOf(false) }
    val line = if (focused || state.query.isNotEmpty()) (if (MaterialTheme.field.isDark) MaterialTheme.colorScheme.primary else ExplorerInk.focus) else explorerControlLine()
    BasicTextField(
        value = state.query,
        onValueChange = state::search,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.testTag("explorer.tables.search"),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surface, ExplorerShapes.control).border(1.dp, line, ExplorerShapes.control).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (state.query.isEmpty()) Text(ExplorerUiText.searchRows, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.field.placeholder)
                    field()
                }
                if (showCount && sheet != null) {
                    Text(
                        ExplorerUiText.matchCount(ExplorerTablesState.count(sheet, true), sheet.totalRows ?: sheet.rows.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = if (state.query.isEmpty()) 8.dp else 0.dp).testTag("explorer.tables.count"),
                    )
                }
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { state.search("") }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Close, contentDescription = ExplorerText.clear, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                }
            }
        },
    )
}

@Composable
private fun SheetPicker(sheets: List<ExplorerSheet>, current: ExplorerSheet, searching: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clip(ExplorerShapes.control)
                .background(MaterialTheme.colorScheme.surface, ExplorerShapes.control)
                .border(1.dp, explorerControlLine(), ExplorerShapes.control)
                .clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("explorer.tables.sheet"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(ExplorerUiText.sheet, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = MaterialTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) { append(current.name) }
                        withStyle(SpanStyle(fontSize = MaterialTheme.typography.bodyMedium.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" · ${ExplorerText.rows(current.totalRows ?: current.rows.size)}") }
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sheets.forEach { s ->
                DropdownMenuItem(
                    text = { Text("${s.name} · ${ExplorerTablesState.count(s, searching)}") },
                    onClick = { open = false; onPick(s.name) },
                    modifier = Modifier.testTag("explorer.tables.pick.${s.name}"),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sheets.forEach { s ->
            ExplorerPill(
                "${s.name} ${ExplorerTablesState.count(s, searching)}",
                selected = s.name == current.name,
                onClick = { onPick(s.name) },
                ink = true,
                minHeight = 32.dp,
                style = MaterialTheme.typography.bodySmall,
                role = Role.RadioButton,
                modifier = Modifier.testTag("explorer.tables.pill.${s.name}"),
            )
        }
    }
}

@Composable
private fun TableSkeleton() {
    Column(Modifier.fillMaxWidth().testTag("explorer.tables.loading"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonBar(1f, 52)
        SkeletonBar(0.6f, 32)
        repeat(3) {
            Column(Modifier.fillMaxWidth().explorerCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBar(0.7f, 14)
                SkeletonBar(0.45f, 10)
            }
        }
    }
}

@Composable
internal fun SkeletonBar(fraction: Float, height: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(fraction).height(height.dp).background(MaterialTheme.field.surface100, RoundedCornerShape(8.dp)))
}

internal fun markWords(text: String, query: String, background: Color, foreground: Color): AnnotatedString {
    val words = query.trim().split(Regex("\\s+")).filter { it.length >= 2 }
    if (words.isEmpty()) return AnnotatedString(text)
    val lower = text.lowercase()
    val marks = ArrayList<IntRange>()
    words.forEach { w ->
        val needle = w.lowercase()
        var at = lower.indexOf(needle)
        while (at >= 0) {
            marks += at until at + needle.length
            at = lower.indexOf(needle, at + needle.length)
        }
    }
    return marked(text, marks, background, foreground)
}

internal fun marked(text: String, ranges: List<IntRange>, background: Color, foreground: Color): AnnotatedString {
    val sorted = ranges.filter { it.first >= 0 && it.last < text.length && !it.isEmpty() }.sortedBy { it.first }
    if (sorted.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        sorted.forEach { r ->
            if (r.last < cursor) return@forEach
            val start = maxOf(r.first, cursor)
            if (start > cursor) append(text.substring(cursor, start))
            withStyle(SpanStyle(background = background, color = foreground, fontWeight = FontWeight.SemiBold)) { append(text.substring(start, r.last + 1)) }
            cursor = r.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
