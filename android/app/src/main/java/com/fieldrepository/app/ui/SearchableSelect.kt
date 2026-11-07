package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

const val SEARCH_THRESHOLD: Int = 8

private const val AUTOFOCUS_THRESHOLD: Int = 16

private const val SHEET_HEIGHT_FRACTION: Float = 0.88f

@Immutable
data class SelectOption(val value: String, val label: String, val hint: String? = null)

fun List<Pair<String, String>>.asSelectOptions(): List<SelectOption> =
    map { (value, label) -> SelectOption(value, label) }

private fun SelectOption.matches(terms: List<String>): Boolean {
    if (terms.isEmpty()) return true
    val haystack = buildString {
        append(label)
        hint?.let { append(' '); append(it) }
        append(' ')
        append(value)
    }
    return terms.all { haystack.contains(it, ignoreCase = true) }
}

private fun queryTerms(query: String): List<String> =
    query.trim().split(' ', '\t', '\n').filter { it.isNotBlank() }

@Composable
fun SearchableSelectField(
    label: String,
    options: List<SelectOption>,
    selectedValue: String,
    modifier: Modifier = Modifier,
    placeholder: String = "Select",
    includeNone: Boolean = true,
    enabled: Boolean = true,
    searchable: Boolean? = null,
    onSearch: ((String) -> Unit)? = null,
    clearable: Boolean = false,
    server: ServerSearch? = null,
    onSelect: (String) -> Unit
) {
    var sheetOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label
    val searchable = server != null || (searchable ?: (options.size >= SEARCH_THRESHOLD))

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            SelectTrigger(
                speech = "$label. ${selectedLabel ?: "Nothing selected"}",
                text = selectedLabel ?: placeholder,
                hasSelection = selectedLabel != null,
                enabled = enabled,
                onClick = { if (searchable) sheetOpen = true else menuOpen = true }
            )
            if (!searchable) {
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (includeNone) {
                        DropdownMenuItem(
                            text = { Text(placeholder, color = MaterialTheme.field.muted) },
                            trailingIcon = { if (selectedValue.isBlank()) SelectedTick() },
                            onClick = { onSelect(""); menuOpen = false }
                        )
                    }
                    options.forEach { option ->
                        val isSelected = option.value == selectedValue
                        DropdownMenuItem(
                            text = {
                                Text(
                                    option.label,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.field.body
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingIcon = { if (isSelected) SelectedTick() },
                            onClick = { onSelect(option.value); menuOpen = false }
                        )
                    }
                }
            }
        }
        if (clearable && enabled && selectedLabel != null) {
            IconButton(onClick = { onSelect("") }) {
                Icon(Icons.Filled.Close, contentDescription = "Clear $label", tint = MaterialTheme.field.muted)
            }
        }
        }
    }

    if (sheetOpen) {
        SearchablePickerSheet(
            title = label,
            options = options,
            selected = if (selectedValue.isBlank()) emptySet() else setOf(selectedValue),
            multiple = false,
            noneLabel = if (includeNone) placeholder else null,
            onSearch = onSearch,
            server = server,
            onDismiss = {
                sheetOpen = false
                onSearch?.invoke("")
                server?.onQuery?.invoke("")
            },
            onApply = { next -> onSelect(next.firstOrNull().orEmpty()) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchableMultiSelectField(
    label: String,
    options: List<SelectOption>,
    selected: Set<String>,
    modifier: Modifier = Modifier,
    placeholder: String = "Select",
    emptyMessage: String = "No options available.",
    enabled: Boolean = true,
    showChips: Boolean = true,
    server: ServerSearch? = null,
    onSelectedChange: (Set<String>) -> Unit
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val chosen = options.filter { it.value in selected }
    val ofTotal = if (server == null) " of ${options.size}" else ""

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "$label (${chosen.size} selected)",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (options.isEmpty() && (server == null || (server.rows.isEmpty() && !server.loading && server.note == null))) {
            Text(emptyMessage, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        } else {
            SelectTrigger(
                speech = "$label. " + if (chosen.isEmpty()) {
                    "Nothing selected"
                } else {
                    "${chosen.size}$ofTotal selected: ${chosen.joinToString { it.label }}"
                },
                text = if (chosen.isEmpty()) placeholder else "${chosen.size}$ofTotal selected",
                hasSelection = chosen.isNotEmpty(),
                enabled = enabled,
                onClick = { sheetOpen = true }
            )
            if (showChips && chosen.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    chosen.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(50)
                                )
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                option.label,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (enabled) {
                                IconButton(onClick = { onSelectedChange(selected - option.value) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove ${option.label}",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                Spacer(Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        SearchablePickerSheet(
            title = label,
            options = options,
            selected = selected,
            multiple = true,
            noneLabel = null,
            server = server,
            onDismiss = { sheetOpen = false; server?.onQuery?.invoke("") },
            onApply = onSelectedChange
        )
    }
}

@Composable
fun SearchableSelectSheet(
    title: String,
    options: List<SelectOption>,
    selectedValue: String = "",
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    SearchablePickerSheet(
        title = title,
        options = options,
        selected = if (selectedValue.isBlank()) emptySet() else setOf(selectedValue),
        multiple = false,
        noneLabel = null,
        onDismiss = onDismiss,
        onApply = { next -> onSelect(next.firstOrNull().orEmpty()) }
    )
}

@Composable
private fun SelectedTick() {
    Icon(
        Icons.Filled.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp)
    )
}

@Composable
private fun SelectTrigger(
    speech: String,
    text: String,
    hasSelection: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = speech
                role = Role.DropdownList
            }
    ) {
        Text(
            text,
            color = if (hasSelection) MaterialTheme.field.body else MaterialTheme.field.placeholder,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.field.muted,
            modifier = Modifier.size(18.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchablePickerSheet(
    title: String,
    options: List<SelectOption>,
    selected: Set<String>,
    multiple: Boolean,
    noneLabel: String?,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit,
    onSearch: ((String) -> Unit)? = null,
    server: ServerSearch? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var queryState by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(selected) }

    fun setQuery(next: String) {
        queryState = next
        onSearch?.invoke(next)
        server?.onQuery?.invoke(next)
    }

    val query = queryState

    val terms = queryTerms(query)
    val filtered = if (server != null) server.rows else remember(options, query) { options.filter { it.matches(terms) } }
    val searching = terms.isNotEmpty()

    val highlighted = if (searching) filtered.firstOrNull() else null

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    fun commitSingle(value: String) {
        onApply(if (value.isBlank()) emptySet() else setOf(value))
        close()
    }

    fun toggle(value: String) {
        draft = if (value in draft) draft - value else draft + value
    }

    fun onImeAction() {
        val row = highlighted ?: return
        if (multiple) {
            toggle(row.value)
            setQuery("")
            scope.launch { listState.scrollToItem(0) }
        } else {
            commitSingle(row.value)
        }
    }

    LaunchedEffect(Unit) {
        if (server != null || options.size >= AUTOFOCUS_THRESHOLD) focusRequester.requestFocus()
    }

    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * SHEET_HEIGHT_FRACTION
    val visibleUnselected = filtered.count { it.value !in draft }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    title,
                    display = true,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { setQuery(it) },
                    label = { Text("Search") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.field.muted,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { setQuery("") }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.field.muted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onImeAction() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                FlowRow(
                    verticalArrangement = Arrangement.Center,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (server != null) serverCountLine(filtered.size, server.total, searching, server.loading)
                        else countLine(filtered.size, options.size, searching),
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    if (multiple) {
                        TextButton(
                            onClick = { draft = draft + filtered.map { it.value } },
                            enabled = visibleUnselected > 0
                        ) { Text("Select all ${filtered.size} shown", style = MaterialTheme.typography.labelMedium) }
                        TextButton(
                            onClick = { draft = emptySet() },
                            enabled = draft.isNotEmpty()
                        ) { Text("Clear all", style = MaterialTheme.typography.labelMedium) }
                    }
                }
                server?.note?.let { Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 6.dp,
                    bottom = 6.dp
                )
            ) {
                if (noneLabel != null && !searching) {
                    item {
                        PickerRow(
                            label = noneLabel,
                            hint = null,
                            selected = draft.isEmpty(),
                            multiple = false,
                            isHighlighted = false,
                            muted = true,
                            onClick = { commitSingle("") }
                        )
                    }
                }
                items(filtered) { option ->
                    PickerRow(
                        label = option.label,
                        hint = option.hint,
                        selected = option.value in draft,
                        multiple = multiple,
                        isHighlighted = option.value == highlighted?.value,
                        muted = false,
                        onClick = { if (multiple) toggle(option.value) else commitSingle(option.value) }
                    )
                }
                if (server != null && server.hasMore) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = server.onLoadMore, enabled = !server.loading) {
                                Text(if (server.loading) "Loading…" else "Load more")
                            }
                        }
                    }
                }
                if (filtered.isEmpty() && !(server != null && server.loading)) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Nothing matches “${query.trim()}”.",
                                color = MaterialTheme.field.muted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = { setQuery("") }) { Text("Clear search") }
                        }
                    }
                }
            }

            if (multiple) {
                HorizontalDivider(color = MaterialTheme.field.hairline)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    OutlinedButton(
                        onClick = { close() },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Cancel") }
                    Button(
                        onClick = { onApply(draft); close() },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Done · ${draft.size} selected", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

private fun serverCountLine(shown: Int, total: Int?, searching: Boolean, loading: Boolean): String {
    val n = total ?: shown
    return when {
        loading && shown == 0 -> "Searching…"
        !searching -> if (n == 1) "1 option" else "$n options"
        n == 0 -> "No matches"
        n == 1 -> "1 match"
        else -> "$n match"
    }
}

private fun countLine(shown: Int, total: Int, searching: Boolean): String = when {
    !searching -> if (total == 1) "1 option" else "$total options"
    shown == 0 -> "No matches"
    else -> "$shown of $total match"
}

@Composable
private fun PickerRow(
    label: String,
    hint: String?,
    selected: Boolean,
    multiple: Boolean,
    isHighlighted: Boolean,
    muted: Boolean,
    onClick: () -> Unit
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 1.dp)
        .background(
            if (isHighlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            RoundedCornerShape(8.dp)
        )
        .let {
            if (isHighlighted) {
                it.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            } else {
                it
            }
        }
        .let {
            if (multiple) {
                it.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
            } else {
                it.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            }
        }
        .heightIn(min = 48.dp)
        .padding(horizontal = 8.dp, vertical = 6.dp)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier) {
        if (multiple) {
            FieldCheckbox(checked = selected, onCheckedChange = null)
            Spacer(Modifier.size(8.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                label,
                color = when {
                    muted -> MaterialTheme.field.muted
                    selected && !multiple -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.field.body
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
            hint?.let {
                Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (isHighlighted) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardReturn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        if (selected && !multiple) {
            Spacer(Modifier.size(6.dp))
            SelectedTick()
        }
    }
}
