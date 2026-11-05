package com.fieldrepository.app.explorer.ui

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

    fun key(owner: String?, path: String, query: String) = "${owner.orEmpty()}|$path|${query.trim().lowercase()}"

    fun get(key: String): ExplorerReport? = synchronized(held) { held[key] }

    fun put(key: String, report: ExplorerReport) = synchronized(held) { held[key] = report }

    fun forget(owner: String?, path: String) = synchronized(held) {
        val prefix = "${owner.orEmpty()}|$path|"
        held.keys.filter { it.startsWith(prefix) }.forEach { held.remove(it) }
    }

    fun clear() = synchronized(held) { held.clear() }
}

const val TABLE_PREVIEW_ROWS = 20

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

    var requests = 0
        private set

    private var job: Job? = null

    val ready: TablesLoad.Ready? get() = load as? TablesLoad.Ready

    fun show(target: String) {
        if (target == path && (load is TablesLoad.Ready || load is TablesLoad.Loading || job?.isActive == true)) return
        if (target != path) {
            query = ""
            sheetName = null
        }
        path = target
        fetch(immediate = true)
    }

    fun search(text: String) {
        if (text == query) return
        query = text
        fetch(immediate = false)
    }

    fun pick(sheet: String) {
        sheetName = sheet
    }

    fun retry() = fetch(immediate = true)

    fun reload() {
        val at = path ?: return
        ExplorerTablesMemory.forget(owner, at)
        fetch(immediate = true)
    }

    fun clear() {
        job?.cancel()
        job = null
        path = null
        query = ""
        sheetName = null
        load = TablesLoad.Idle
        refreshing = false
        busySeconds = null
        refreshFailed = false
    }

    private fun fetch(immediate: Boolean) {
        val at = path ?: return
        val words = query
        val key = ExplorerTablesMemory.key(owner, at, words)
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
                val report = withBusyRetry { api.reportJson(at, words.trim().ifBlank { null }) }
                ExplorerTablesMemory.put(key, report)
                if (path == at && query == words) load = TablesLoad.Ready(report, at, words)
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
    }
}

@Composable
internal fun ExplorerTables(
    state: ExplorerTablesState,
    online: Boolean,
    onCsv: (sheet: String) -> Unit,
    onReport: () -> Unit,
) {
    val ready = state.ready
    val searching = state.query.isNotBlank()
    val sheet = ready?.let { ExplorerTablesState.picked(it.report, state.sheetName, it.query.isNotBlank()) }
    Column(Modifier.fillMaxWidth().testTag("explorer.tables"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ready != null && ready.report.sheets.isNotEmpty() && sheet != null) {
            SheetPicker(ready.report.sheets, sheet, ready.query.isNotBlank(), onPick = state::pick)
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = state::search,
            singleLine = true,
            placeholder = { Text(ExplorerUiText.searchRows) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searching && sheet != null && ready?.query?.isNotBlank() == true) {
                        Text(
                            ExplorerUiText.matchCount(ExplorerTablesState.count(sheet, true), sheet.totalRows ?: sheet.rows.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("explorer.tables.count"),
                        )
                    }
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { state.search("") }) { Icon(Icons.Filled.Close, contentDescription = ExplorerText.clear, modifier = Modifier.size(18.dp)) }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("explorer.tables.search"),
        )
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
                TextButton(onClick = state::retry, modifier = Modifier.heightIn(min = 44.dp)) { Text(ExplorerText.retry) }
            }
        }
        when (val now = state.load) {
            TablesLoad.Idle, TablesLoad.Loading -> if (state.busySeconds == null) TableSkeleton()
            TablesLoad.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HintLine(ExplorerText.tablesFailed)
                SheetButtons(primary = ExplorerText.retry, onPrimary = state::retry, primaryTag = "explorer.tables.retry")
            }
            is TablesLoad.Ready -> {
                if (now.report.sheets.isEmpty() || sheet == null) {
                    HintLine(ExplorerText.noRows)
                } else {
                    val rows = sheet.rows.take(TABLE_PREVIEW_ROWS)
                    val words = now.query.trim()
                    if (rows.isEmpty()) HintLine(if (words.isNotBlank()) ExplorerText.matches(0, sheet.totalRows ?: 0) else ExplorerText.noRows)
                    rows.forEachIndexed { index, row ->
                        RowCard(sheet.headers, row, words, sheet.rowNumbers?.getOrNull(index) ?: if (words.isBlank()) index + 1 else null, "${sheet.name}.$index")
                    }
                    val shown = rows.size
                    val total = ExplorerTablesState.count(sheet, words.isNotBlank())
                    if (total > shown) Text(ExplorerUiText.showingRows(shown, total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onCsv(sheet.name) }, enabled = online, modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("explorer.csv.${sheet.name}")) {
                            Text(ExplorerUiText.thisTable, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(onClick = onReport, enabled = online, modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("explorer.tables.report")) {
                            Text(ExplorerUiText.fullReport, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetPicker(sheets: List<ExplorerSheet>, current: ExplorerSheet, searching: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.medium)
                .clickable { open = true }.testTag("explorer.tables.sheet"),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ExplorerUiText.sheet, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${current.name} · ${ExplorerText.rows(current.totalRows ?: current.rows.size)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
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
            val on = s.name == current.name
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
                contentColor = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.heightIn(min = 36.dp)
                    .border(if (on) 0.dp else 1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.extraLarge)
                    .clickable { onPick(s.name) }.testTag("explorer.tables.pill.${s.name}"),
            ) {
                Box(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("${s.name} ${ExplorerTablesState.count(s, searching)}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun RowCard(headers: List<String>, row: List<kotlinx.serialization.json.JsonElement>, query: String, number: Int?, key: String) {
    var open by remember(key) { mutableStateOf(false) }
    val cells = row.map { ExplorerCells.text(it) }
    val (markBg, markFg) = explorerMark()
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.large)
            .padding(14.dp)
            .testTag("explorer.tables.row.$key"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                highlighted(cells.firstOrNull().orEmpty().ifBlank { "—" }, query, markBg, markFg),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            number?.let { Text(ExplorerUiText.row(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (open) {
            headers.drop(1).forEachIndexed { i, label ->
                val value = cells.getOrNull(i + 1).orEmpty()
                if (value.isNotBlank()) {
                    Column {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(highlighted(value, query, markBg, markFg), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
                    }
                }
            }
        } else {
            val summary = cells.drop(1).filter { it.isNotBlank() }.take(4).joinToString(" · ")
            if (summary.isNotBlank()) Text(highlighted(summary, query, markBg, markFg), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (headers.size > 2) {
            TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 40.dp)) {
                Text(if (open) ExplorerUiText.showFewer else ExplorerUiText.allColumns(headers.size))
            }
        }
    }
}

@Composable
private fun TableSkeleton() {
    Column(Modifier.fillMaxWidth().testTag("explorer.tables.loading"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonBar(0.6f, 20)
        repeat(3) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBar(0.7f, 14)
                SkeletonBar(0.45f, 10)
            }
        }
    }
}

@Composable
internal fun SkeletonBar(fraction: Float, height: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(fraction).height(height.dp).background(MaterialTheme.field.surface100, MaterialTheme.shapes.small))
}

internal fun highlighted(text: String, query: String, background: Color, foreground: Color): AnnotatedString {
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
