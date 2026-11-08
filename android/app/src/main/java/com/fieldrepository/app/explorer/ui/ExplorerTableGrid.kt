package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.ui.field

object ExplorerTableWidths {
    const val MIN = 160
    const val MAX = 420
    const val NUMBER = 64
    private const val PER_CHAR = 8
    private const val PADDING = 28

    fun of(text: String): Int {
        val longestWord = text.split(Regex("\\s+")).maxOfOrNull { it.length } ?: 0
        val wanted = maxOf(minOf(text.length, 60), minOf(longestWord, 60))
        return (wanted * PER_CHAR + PADDING).coerceIn(MIN, MAX)
    }

    fun columns(headers: List<String>, rows: List<List<String>>, scale: Float = 1f): List<Int> =
        headers.indices.map { column ->
            val cells = rows.mapNotNull { it.getOrNull(column)?.takeIf { cell -> cell.isNotBlank() } }
            val widest = (cells.map { of(it) } + of(headers[column])).max()
            (widest * scale).toInt().coerceIn(MIN, MAX)
        }
}

object ExplorerTableColumns {
    private val ID_VALUE = Regex("^c[a-z0-9]{20,}$")
    private val TITLE = Regex("(^|\\s)(name|title)$")

    fun idLike(header: String, values: List<String>): Boolean {
        val h = header.trim().lowercase()
        if (h == "id" || h.endsWith(" id") || h == "record id" || h == "uuid") return true
        val filled = values.filter { it.isNotBlank() }
        return filled.isNotEmpty() && filled.all { ID_VALUE.matches(it.trim()) }
    }

    fun order(headers: List<String>, rows: List<List<String>>): List<Int> {
        if (headers.isEmpty()) return emptyList()
        val values = headers.indices.map { c -> rows.map { it.getOrNull(c).orEmpty() } }
        val ids = headers.indices.filter { idLike(headers[it], values[it]) }.toSet()
        val filled = headers.indices.filter { c -> values[c].any { it.isNotBlank() } }
        val title = filled.firstOrNull { it !in ids && TITLE.containsMatchIn(headers[it].trim().lowercase()) }
            ?: filled.firstOrNull { it !in ids }
            ?: 0
        return listOf(title) + headers.indices.filter { it != title && it !in ids } + headers.indices.filter { it != title && it in ids }
    }

    fun visibleSheets(sheets: List<com.fieldrepository.app.explorer.data.ExplorerSheet>, current: String?, searching: Boolean): List<com.fieldrepository.app.explorer.data.ExplorerSheet> =
        sheets.filter { it.name == current || ExplorerTablesState.count(it, searching) > 0 }
}

@Composable
internal fun ExplorerTableGrid(
    rawHeaders: List<String>,
    rawRows: List<Pair<List<String>, Int?>>,
    query: String,
    keyPrefix: String,
    modifier: Modifier = Modifier,
) {
    val scale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val order = remember(rawHeaders, rawRows) { ExplorerTableColumns.order(rawHeaders, rawRows.map { it.first }) }
    val headers = remember(order, rawHeaders) { order.map { rawHeaders.getOrElse(it) { "" } } }
    val rows = remember(order, rawRows) { rawRows.map { (cells, n) -> order.map { cells.getOrElse(it) { "" } } to n } }
    val widths = remember(headers, rows, scale) { ExplorerTableWidths.columns(headers, rows.map { it.first }, scale) }
    val (markBg, markFg) = explorerMark()
    val line = MaterialTheme.field.hairline
    val numbered = rows.any { it.second != null }
    Box(
        modifier
            .fillMaxWidth()
            .clip(ExplorerShapes.control)
            .border(1.dp, line, ExplorerShapes.control)
            .horizontalScroll(rememberScrollState())
            .testTag("explorer.tables.grid"),
    ) {
        SelectionContainer {
            Column(Modifier.width(IntrinsicSize.Max)) {
                Row(Modifier.height(IntrinsicSize.Min).background(MaterialTheme.field.surface50).testTag("explorer.tables.header")) {
                    if (numbered) GridCell(ExplorerTableWidths.NUMBER, "#", header = true, divider = true, tag = "explorer.tables.head.number")
                    headers.forEachIndexed { index, label ->
                        GridCell(widths[index], label, header = true, divider = index < headers.lastIndex, tag = "explorer.tables.head.$index")
                    }
                }
                rows.forEachIndexed { rowIndex, (cells, number) ->
                    HorizontalDivider(color = line)
                    val key = "$keyPrefix.$rowIndex"
                    Row(Modifier.height(IntrinsicSize.Min).testTag("explorer.tables.row.$key")) {
                        if (numbered) GridCell(ExplorerTableWidths.NUMBER, number?.takeIf { it > 0 }?.toString().orEmpty(), header = false, divider = true, muted = true, tag = "explorer.tables.cell.$key.number")
                        headers.indices.forEach { column ->
                            val value = cells.getOrNull(column).orEmpty()
                            Box(
                                Modifier.width(widths[column].dp).fillMaxHeight()
                                    .then(if (column < headers.lastIndex) Modifier.gridDivider(line) else Modifier)
                                    .testTag("explorer.tables.cell.$key.$column")
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    markWords(value, query, markBg, markFg),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (column == 0) FontWeight.SemiBold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    softWrap = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCell(width: Int, text: String, header: Boolean, divider: Boolean, tag: String, muted: Boolean = false) {
    val line = MaterialTheme.field.hairline
    Box(
        Modifier.width(width.dp).fillMaxHeight()
            .then(if (divider) Modifier.gridDivider(line) else Modifier)
            .testTag(tag)
            .then(if (header) Modifier.semantics { heading() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            softWrap = true,
        )
    }
}

private fun Modifier.gridDivider(color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), stroke)
}
