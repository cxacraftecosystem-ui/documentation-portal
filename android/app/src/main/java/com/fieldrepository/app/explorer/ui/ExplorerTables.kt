package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.data.ExplorerBusy
import com.fieldrepository.app.explorer.data.ExplorerCells
import com.fieldrepository.app.explorer.data.ExplorerReport
import com.fieldrepository.app.explorer.data.ExplorerSheet
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

sealed interface TablesLoad {
    data object Loading : TablesLoad
    data class Busy(val seconds: Int) : TablesLoad
    data class Ready(val report: ExplorerReport) : TablesLoad
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

    fun clear() = synchronized(held) { held.clear() }
}

const val TABLE_PREVIEW_ROWS = 10

@Composable
internal fun ExplorerTables(
    api: ExplorerApi,
    owner: String?,
    path: String,
    online: Boolean,
    onCsv: (sheet: String) -> Unit,
) {
    var query by rememberSaveable(path) { mutableStateOf("") }
    var load by remember(path) { mutableStateOf<TablesLoad>(TablesLoad.Loading) }
    var attempt by remember(path) { mutableStateOf(0) }
    LaunchedEffect(path, query, attempt) {
        val key = ExplorerTablesMemory.key(owner, path, query)
        ExplorerTablesMemory.get(key)?.let {
            load = TablesLoad.Ready(it)
            return@LaunchedEffect
        }
        if (query.isNotBlank()) delay(400)
        load = TablesLoad.Loading
        load = try {
            val report = ExplorerBusy.retryOnce({ left -> load = if (left != null) TablesLoad.Busy(left) else TablesLoad.Loading }) {
                api.reportJson(path, query.trim().ifBlank { null })
            }
            ExplorerTablesMemory.put(key, report)
            TablesLoad.Ready(report)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            TablesLoad.Failed
        }
    }
    Column(Modifier.fillMaxWidth().testTag("explorer.tables"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(ExplorerText.searchRows) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = ExplorerText.clear, modifier = Modifier.size(18.dp)) }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("explorer.tables.search"),
        )
        when (val now = load) {
            TablesLoad.Loading -> LoadingLine(ExplorerText.preparing)
            is TablesLoad.Busy -> Text(
                ExplorerText.busy(now.seconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("explorer.tables.busy"),
            )
            TablesLoad.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HintLine(ExplorerText.tablesFailed)
                SheetButtons(primary = ExplorerText.retry, onPrimary = { attempt++ }, primaryTag = "explorer.tables.retry")
            }
            is TablesLoad.Ready -> {
                val sheets = now.report.sheets
                if (sheets.isEmpty()) HintLine(ExplorerText.noRows)
                sheets.forEach { sheet -> SheetCard(sheet, query.isNotBlank(), online) { onCsv(sheet.name) } }
            }
        }
    }
}

@Composable
private fun SheetCard(sheet: ExplorerSheet, searching: Boolean, online: Boolean, onCsv: () -> Unit) {
    val total = sheet.totalRows ?: sheet.rows.size
    val line = if (searching) ExplorerText.matches(sheet.matchedRows ?: sheet.rows.size, total) else ExplorerText.rows(total)
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.field.surface50, MaterialTheme.shapes.medium).padding(12.dp).testTag("explorer.sheet.${sheet.name}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sheet.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onCsv, enabled = online, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.csv.${sheet.name}")) { Text(ExplorerText.csv) }
        }
        val rows = sheet.rows.take(TABLE_PREVIEW_ROWS)
        if (rows.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Column {
                    Row { sheet.headers.forEach { Cell(it, header = true) } }
                    HorizontalDivider(color = MaterialTheme.field.hairline)
                    rows.forEach { row -> Row { row.forEach { Cell(ExplorerCells.text(it), header = false) } } }
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String, header: Boolean) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        color = if (header) MaterialTheme.colorScheme.onSurface else MaterialTheme.field.body,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(140.dp).padding(horizontal = 6.dp, vertical = 6.dp),
    )
}
