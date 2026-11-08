package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.explorer.data.ExplorerFilterChip
import com.fieldrepository.app.explorer.data.ExplorerFilterKey
import com.fieldrepository.app.explorer.data.ExplorerFilterOption
import com.fieldrepository.app.explorer.data.ExplorerFilters
import com.fieldrepository.app.ui.FieldCheckbox
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal object FilterText {
    const val filters = "Filters"
    const val any = "Any"
    const val anyValue = "any"
    const val anyDate = "Any date"
    const val date = "Date"
    const val clear = "Clear"
    const val clearFilters = "Clear filters"
    const val apply = "Apply"
    const val done = "Done"
    const val search = "Search"
    const val loading = "Loading…"
    const val failed = "Couldn't load the choices. Try again."
    const val noChoices = "No choices match."
    fun count(n: Int) = if (n == 0) filters else "$filters · $n"
    fun selected(n: Int, of: Int?) = if (of != null && of > 0) "$n of $of selected" else "$n selected"
    fun selectMatching(n: Int, searching: Boolean) = if (searching) "Select $n matching" else "Select all $n"
    fun remove(label: String) = "Remove $label"
    fun applyCount(n: Int) = if (n == 0) apply else "$apply ($n)"
    const val emptyTitle = "No matches here"
    const val localOffTitle = "This portal is filtered out"
    const val localOffBody = "The Portal filter shows only the other portal. Its matches are listed below."
    const val otherPortal = "From the other portal"
    const val peerNone = "Nothing in the other portal matches."
    const val peerFailed = "Couldn't reach the other portal. Try again."
    const val retry = "Try again"
    fun nothingMatches(summary: String) = if (summary.isBlank()) "Nothing here matches the filters." else "Nothing here matches $summary."
    fun peerShowing(shown: Int, total: Int) = "Showing $shown of $total"
    fun peerMore(n: Int) = "Show $n more"
    fun notApplied(labels: List<String>) = "Not applied in the other portal: ${labels.joinToString(", ")}"
}

typealias ExplorerOptionSource = suspend (key: ExplorerFilterKey, query: String) -> List<ExplorerFilterOption>

object ExplorerOptionMatch {
    fun terms(query: String): List<String> = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun matches(option: ExplorerFilterOption, terms: List<String>): Boolean {
        if (terms.isEmpty()) return true
        val hay = (option.label + " " + option.id).lowercase()
        return terms.all { it in hay }
    }

    fun ordered(options: List<ExplorerFilterOption>, selected: Set<String>, query: String): List<ExplorerFilterOption> {
        val t = terms(query)
        val shown = options.filter { matches(it, t) }
        return shown.filter { it.id in selected } + shown.filterNot { it.id in selected }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExplorerFilterBar(
    filters: ExplorerFilters,
    keys: List<ExplorerFilterKey>,
    source: ExplorerOptionSource,
    onChange: (ExplorerFilters) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val chips = filters.chips()
    Column(modifier.fillMaxWidth().testTag("explorer.filters"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.heightIn(min = 44.dp)
                    .clip(ExplorerShapes.pill)
                    .background(if (chips.isEmpty()) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer, ExplorerShapes.pill)
                    .border(1.dp, if (chips.isEmpty()) explorerControlLine() else MaterialTheme.colorScheme.primary, ExplorerShapes.pill)
                    .clickable(role = Role.Button) { open = true }
                    .padding(horizontal = 14.dp)
                    .testTag("explorer.filters.open"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.FilterList, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                Text(FilterText.count(chips.size), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
            chips.forEach { chip -> FilterChipView(chip) { onChange(filters.without(chip)) } }
            if (chips.isNotEmpty()) ExplorerLinkButton(FilterText.clearFilters, onClick = { onChange(filters.cleared()) }, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.filters.clear"))
        }
    }
    if (open) ExplorerFilterSheet(filters, keys, source, onChange) { open = false }
}

@Composable
internal fun FilterChipView(chip: ExplorerFilterChip, onRemove: () -> Unit) {
    Row(
        Modifier.clip(ExplorerShapes.pill).background(MaterialTheme.colorScheme.primaryContainer, ExplorerShapes.pill).padding(start = 12.dp).testTag("explorer.filters.chip.${chip.key}.${chip.value}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(chip.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        IconButton(onClick = onRemove, modifier = Modifier.size(44.dp).testTag("explorer.filters.remove.${chip.key}.${chip.value}")) {
            Icon(Icons.Filled.Close, contentDescription = FilterText.remove(chip.label), tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExplorerFilterSheet(
    filters: ExplorerFilters,
    keys: List<ExplorerFilterKey>,
    source: ExplorerOptionSource,
    onChange: (ExplorerFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by remember { mutableStateOf<ExplorerFilterKey?>(null) }
    var dates by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val key = picking
        if (key == null) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().testTag("explorer.filters.sheet"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(FilterText.filters, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
                keys.forEach { k ->
                    val picked = filters.picked(k)
                    if (k == ExplorerFilterKey.HAS_TRANSCRIPT) {
                        TranscriptChoice(filters) { onChange(it) }
                        return@forEach
                    }
                    FilterTrigger(
                        label = k.label,
                        value = if (picked.isEmpty()) FilterText.any else picked.joinToString(", ") { filters.labelOf(k, it) },
                        count = if (k == ExplorerFilterKey.PORTAL && !filters.includesOther) 0 else picked.size,
                        tag = "explorer.filter.${k.facet}",
                    ) { picking = k }
                }
                FilterTrigger(
                    label = FilterText.date,
                    value = if (filters.dateFrom == null && filters.dateTo == null) FilterText.anyDate else ExplorerFilters.rangeLabel(filters.dateFrom, filters.dateTo),
                    count = if (filters.dateFrom == null && filters.dateTo == null) 0 else 1,
                    tag = "explorer.filter.dates",
                    icon = true,
                ) { dates = true }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExplorerSecondaryButton(FilterText.clearFilters, onClick = { onChange(filters.cleared()) }, enabled = filters.active, modifier = Modifier.weight(1f).testTag("explorer.filters.sheet.clear"))
                    ExplorerPrimaryButton(FilterText.done, onClick = onDismiss, modifier = Modifier.weight(1f).testTag("explorer.filters.done"))
                }
            }
        } else {
            ExplorerMultiSelectPanel(
                key = key,
                filters = filters,
                source = source,
                onBack = { picking = null },
                onApply = { picked, names -> onChange(filters.with(key, picked, names)); picking = null },
            )
        }
    }
    if (dates) {
        ExplorerDateRangeDialog(filters.dateFrom, filters.dateTo, onDismiss = { dates = false }) { from, to ->
            onChange(filters.dates(from, to))
            dates = false
        }
    }
}

@Composable
private fun TranscriptChoice(filters: ExplorerFilters, onChange: (ExplorerFilters) -> Unit) {
    val key = ExplorerFilterKey.HAS_TRANSCRIPT
    val current = filters.picked(key).firstOrNull() ?: FilterText.anyValue
    Column(Modifier.testTag("explorer.filter.${key.facet}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(key.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Segmented(
            listOf(FilterText.anyValue to FilterText.any) + ExplorerFilters.TRANSCRIPT_CHOICES.map { it to ExplorerFilters.transcriptLabel(it) },
            current,
            key.facet,
        ) { picked -> onChange(filters.with(key, if (picked == FilterText.anyValue) emptyList() else listOf(picked))) }
    }
}

@Composable
private fun FilterTrigger(label: String, value: String, count: Int, tag: String, icon: Boolean = false, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clip(ExplorerShapes.control)
                .background(MaterialTheme.colorScheme.surface, ExplorerShapes.control)
                .border(1.dp, if (count > 0) MaterialTheme.colorScheme.primary else explorerControlLine(), ExplorerShapes.control)
                .clickable(role = Role.DropdownList, onClick = onClick)
                .semantics { contentDescription = "$label. $value"; role = Role.DropdownList }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = if (count > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.field.placeholder, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(if (icon) Icons.Filled.DateRange else Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.field.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ExplorerMultiSelectPanel(
    key: ExplorerFilterKey,
    filters: ExplorerFilters,
    source: ExplorerOptionSource,
    onBack: () -> Unit,
    onApply: (Set<String>, Map<String, String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var draft by remember(key) { mutableStateOf(filters.picked(key)) }
    var options by remember(key) { mutableStateOf<List<ExplorerFilterOption>?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    val names = remember(key) { mutableMapOf<String, String>() }
    LaunchedEffect(key, query) {
        if (query.isNotEmpty()) delay(250)
        failed = false
        try {
            val got = source(key, query)
            got.forEach { names[it.id] = it.label }
            options = got
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failed = true
        }
    }
    val known = options.orEmpty()
    val pinned = draft.filter { id -> known.none { it.id == id } }.map { ExplorerFilterOption(it, filters.labelOf(key, it)) }
    val shown = ExplorerOptionMatch.ordered(pinned.filter { ExplorerOptionMatch.matches(it, ExplorerOptionMatch.terms(query)) } + known, draft, query)
    val searching = ExplorerOptionMatch.terms(query).isNotEmpty()
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).navigationBarsPadding().testTag("explorer.filter.${key.facet}.panel")) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp).testTag("explorer.filter.back")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = FilterText.filters, modifier = Modifier.size(26.dp))
            }
            Text(key.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
            Text(FilterText.selected(draft.size, null), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { OptionSearch(query, key) { query = it } }
        val shownIds = shown.map { it.id }
        if (shownIds.isNotEmpty() && key != ExplorerFilterKey.PORTAL) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                ExplorerLinkButton(FilterText.selectMatching(shownIds.size, searching), onClick = { draft = draft + shownIds; shown.forEach { names[it.id] = it.label } }, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.filter.${key.facet}.all"))
                if (draft.isNotEmpty()) ExplorerLinkButton(FilterText.clear, onClick = { draft = emptySet() }, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.filter.${key.facet}.none"))
            }
        }
        when {
            options == null && !failed -> HintLine(FilterText.loading)
            failed && known.isEmpty() -> HintLine(FilterText.failed)
            shown.isEmpty() -> HintLine(FilterText.noChoices)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("explorer.filter.${key.facet}.list")) {
            items(shown, key = { it.id }) { option ->
                val on = option.id in draft
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .toggleable(value = on, role = Role.Checkbox, onValueChange = { next ->
                            names[option.id] = option.label
                            draft = if (next) draft + option.id else draft - option.id
                        })
                        .padding(horizontal = 16.dp)
                        .testTag("explorer.filter.${key.facet}.option.${option.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FieldCheckbox(checked = on, onCheckedChange = null)
                    Text(option.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    option.count?.let { Text(String.format(java.util.Locale.ROOT, "%,d", it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted) }
                }
                HorizontalDivider(color = MaterialTheme.field.hairline)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExplorerSecondaryButton(FilterText.filters, onClick = onBack, modifier = Modifier.weight(1f))
            ExplorerPrimaryButton(FilterText.applyCount(draft.size), onClick = { onApply(draft, names.toMap()) }, modifier = Modifier.weight(1f).testTag("explorer.filter.${key.facet}.apply"))
        }
    }
}

@Composable
private fun OptionSearch(query: String, key: ExplorerFilterKey, onQuery: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().testTag("explorer.filter.${key.facet}.search"),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surface, ExplorerShapes.control).border(1.dp, explorerControlLine(), ExplorerShapes.control).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("${FilterText.search} ${key.label.lowercase()}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.field.placeholder)
                    field()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)) { Icon(Icons.Filled.Close, contentDescription = FilterText.clear, modifier = Modifier.size(18.dp)) }
                }
            }
        },
    )
}

internal object ExplorerDays {
    fun toMillis(iso: String?): Long? = iso?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() }

    fun fromMillis(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExplorerDateRangeDialog(from: String?, to: String?, onDismiss: () -> Unit, onApply: (String?, String?) -> Unit) {
    val state = rememberDateRangePickerState(initialSelectedStartDateMillis = ExplorerDays.toMillis(from), initialSelectedEndDateMillis = ExplorerDays.toMillis(to))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("explorer.filter.dates.dialog"),
        confirmButton = {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExplorerSecondaryButton(FilterText.clear, onClick = { onApply(null, null) }, modifier = Modifier.testTag("explorer.filter.dates.clear"))
                ExplorerPrimaryButton(FilterText.apply, onClick = {
                    val a = ExplorerDays.fromMillis(state.selectedStartDateMillis)
                    val b = ExplorerDays.fromMillis(state.selectedEndDateMillis) ?: a
                    onApply(a, b)
                }, modifier = Modifier.testTag("explorer.filter.dates.apply"))
            }
        },
    ) { DateRangePicker(state = state, modifier = Modifier.heightIn(max = 520.dp)) }
}
