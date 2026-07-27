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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Searchable selects: one picker interaction for every long list in the app.
//
// WHERE THIS COMES FROM. The phone field's country dialog already had the interaction the rest of
// the app was missing — type "Nepal", the 200-row list collapses to one. Every other list in the
// app (74 tools, 37 states, 25 interviews, the user directory) was a plain anchored DropdownMenu
// you had to scroll past. Rather than write a second search box with its own habits, this file
// LIFTS that dialog into a shared picker and the phone field's list becomes one caller among many.
// A researcher learns "tap, type, commit" once.
//
// WHY A BOTTOM SHEET AND NOT A DROPDOWN OR A DIALOG. Three things decide it, and all three are
// about a thumb on a handset rather than a cursor on a laptop:
//
//   1. The KEYBOARD is the whole problem. The moment the researcher types, the IME takes the lower
//      half of the screen. An anchored DropdownMenu is positioned against its trigger, so a field
//      near the bottom of a long form opens a menu that the IME then sits on top of — the rows are
//      drawn where the keyboard now is. A sheet is anchored to the screen instead, and
//      [Modifier.imePadding] lets the keyboard SHRINK the list rather than cover it.
//   2. REACH. A sheet grows from the bottom edge, so its search box, its Select-all row and its
//      first rows land inside the arc a thumb can cover one-handed. A centred AlertDialog puts the
//      same controls in the middle of the screen, which is the one place a thumb has to stretch
//      for; the phone field's dialog is fine for a control you touch once per artisan and wrong as
//      the app's everyday picker.
//   3. DISMISSAL. Swipe down. A researcher who opened the wrong field gets out without aiming at
//      anything, which matters on a dusty screen in a workshop.
//
// [rememberModalBottomSheetState] is asked to skip the partially-expanded state. A half-height
// sheet plus an IME is a three-row peephole — the exact failure the sheet was chosen to avoid.
//
// WHY SHORT LISTS KEEP THE OLD MENU. Below [SEARCH_THRESHOLD] options there is nothing to search:
// "Draft / Pending / Approved" fits on screen, and making the researcher cross a sheet and dismiss
// a keyboard to pick one of four is worse than the dropdown it replaced. The threshold is a
// property of the LIST, not of the screen, so the same field behaves the same way on every device
// — and it is the same number the web uses, so a field that searches on the laptop searches on the
// phone.
// ---------------------------------------------------------------------------------------------

/**
 * Options at or above this count get the searchable sheet; below it, the anchored menu.
 *
 * Eight is where an anchored menu stops fitting between a mid-form trigger and the bottom of a
 * small handset, so it is the point at which the researcher starts scrolling a floating menu whose
 * position they did not choose. MUST match the web's threshold — see the note at the top of the
 * file.
 */
const val SEARCH_THRESHOLD: Int = 8

/**
 * Above this count the search box takes focus as the sheet opens, so the first keystroke filters.
 *
 * Deliberately higher than [SEARCH_THRESHOLD]: for a dozen rows the researcher can very likely SEE
 * the one they want, and popping the IME unasked would hide half of them behind a keyboard they
 * then have to dismiss. Past ~16 rows the list no longer fits on a handset either way, so typing is
 * the faster route in and the keyboard is what they came for.
 */
private const val AUTOFOCUS_THRESHOLD: Int = 16

/** Fraction of the screen the sheet may grow to before its list starts scrolling instead. */
private const val SHEET_HEIGHT_FRACTION: Float = 0.88f

/** A picker row: the stored [value], the [label] read aloud, and an optional trailing [hint]. */
@Immutable
data class SelectOption(val value: String, val label: String, val hint: String? = null)

/** Adapt the `value to label` pairs the record forms already build. */
fun List<Pair<String, String>>.asSelectOptions(): List<SelectOption> =
    map { (value, label) -> SelectOption(value, label) }

/**
 * Case-insensitive substring match over label, hint and stored value.
 *
 * Split on whitespace and required in full, so "ram bagru" finds "Ram Kumar · Bagru" — which a
 * single contiguous `contains` does not, and which is how a researcher who half-remembers two
 * things about an artisan actually types. The value is searched too because some lists (dial codes,
 * status names) carry the meaning there rather than in the label.
 */
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

// ---------------------------------------------------------------------------------------------
// Single select
// ---------------------------------------------------------------------------------------------

/**
 * A one-of-many field. Long lists open the searchable sheet; short ones keep the anchored menu.
 *
 * [includeNone] adds the "no selection" row, labelled with [placeholder] — the blank the record
 * forms rely on to unlink a record.
 */
@Composable
fun SearchableSelectField(
    label: String,
    options: List<SelectOption>,
    selectedValue: String,
    modifier: Modifier = Modifier,
    placeholder: String = "Select",
    includeNone: Boolean = true,
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    var sheetOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label
    val searchable = options.size >= SEARCH_THRESHOLD

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MaterialTheme.field.muted, fontSize = 12.sp)
        Box(modifier = Modifier.fillMaxWidth()) {
            SelectTrigger(
                // The visible label sits in a separate Text above the button, which TalkBack reads
                // as its own node — so a researcher swiping onto the control alone would hear
                // "Ram Kumar, button" with no idea which field it belongs to. Naming the node with
                // both halves is the only way the control is self-describing wherever focus lands.
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
    }

    if (sheetOpen) {
        SearchablePickerSheet(
            title = label,
            options = options,
            selected = if (selectedValue.isBlank()) emptySet() else setOf(selectedValue),
            multiple = false,
            noneLabel = if (includeNone) placeholder else null,
            onDismiss = { sheetOpen = false },
            onApply = { next -> onSelect(next.firstOrNull().orEmpty()) }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Multi select
// ---------------------------------------------------------------------------------------------

/**
 * A many-of-many field: a summary trigger, the chosen rows as chips beneath it, and the same sheet.
 *
 * The sheet is used at EVERY length here, unlike the single-select. What it replaces is a wall of
 * checkboxes rendered straight into the form — which has no summary line, so the only way to see
 * what is ticked is to scroll the form back over it, and no room for a Select-all row without
 * pushing the next field further down a page that is already long. The chips give the form back its
 * at-a-glance reading without the wall.
 */
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
    onSelectedChange: (Set<String>) -> Unit
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val chosen = options.filter { it.value in selected }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "$label (${chosen.size} selected)",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )
        if (options.isEmpty()) {
            Text(emptyMessage, color = MaterialTheme.field.muted, fontSize = 12.sp)
        } else {
            SelectTrigger(
                speech = "$label. " + if (chosen.isEmpty()) {
                    "Nothing selected"
                } else {
                    "${chosen.size} of ${options.size} selected: ${chosen.joinToString { it.label }}"
                },
                text = if (chosen.isEmpty()) placeholder else "${chosen.size} of ${options.size} selected",
                hasSelection = chosen.isNotEmpty(),
                enabled = enabled,
                onClick = { sheetOpen = true }
            )
            if (chosen.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    // Cleared and re-set: the trigger above already reads every chip aloud, so
                    // letting TalkBack walk them again is the same list twice. They are a glance
                    // aid; removal happens in the sheet, where the row carries its checked state.
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "" }
                ) {
                    chosen.forEach { option ->
                        Text(
                            option.label,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
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
            onDismiss = { sheetOpen = false },
            onApply = onSelectedChange
        )
    }
}

/**
 * The picker with no trigger of its own, for a field that already has one of a shape a button
 * cannot take — the phone field's ISD box, which is a text field measured to the widest dial code.
 * Single-select: the tap commits and closes.
 *
 * [onSelect] must NOT close the sheet itself; [onDismiss] fires once the sheet has finished sliding
 * away, and a caller that drops the sheet out of composition on select cuts that animation short.
 */
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

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

/** The tick that marks the chosen row. Purple-700 is the app's only action colour. */
@Composable
private fun SelectedTick() {
    Icon(
        Icons.Filled.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp)
    )
}

/**
 * The closed field. Same outlined button and caret the app's dropdowns have always had, so
 * adopting the sheet does not redraw thirty-odd forms.
 */
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
            // A button is already a merged semantics node, so naming it here REPLACES the child
            // text rather than adding to it — which is what lets one description carry both the
            // field name and the selection.
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

/**
 * The picker itself.
 *
 * Single-select commits and closes on the first tap — a second "Done" would be a tap that can only
 * confirm what the researcher just did. Multi-select holds a DRAFT of the selection and commits on
 * Done, because ticking six artisans against a form that re-derives its options on every change
 * (the tool form drops artisans when their craft is unticked) would otherwise pull rows out from
 * under the finger mid-list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchablePickerSheet(
    title: String,
    options: List<SelectOption>,
    selected: Set<String>,
    multiple: Boolean,
    noneLabel: String?,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(selected) }

    val terms = queryTerms(query)
    val filtered = remember(options, query) { options.filter { it.matches(terms) } }
    val searching = terms.isNotEmpty()

    // Only meaningful while filtering. Unfiltered, "the first row" is whatever happens to sort
    // first and committing it on a stray IME tap would be a silent wrong answer.
    val highlighted = if (searching) filtered.firstOrNull() else null

    fun close() {
        // Hide first so the sheet slides away rather than vanishing, matching the filter sheet on
        // the search screen; the flag drops once the animation has actually finished.
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    fun commitSingle(value: String) {
        onApply(if (value.isBlank()) emptySet() else setOf(value))
        close()
    }

    fun toggle(value: String) {
        draft = if (value in draft) draft - value else draft + value
    }

    /**
     * What the IME's action key does — the phone's stand-in for Enter, which a handset has not got.
     *
     * Single: commit the one highlighted row and leave. Multi: tick it and CLEAR THE QUERY, keeping
     * the keyboard and the focus, so "bagru ⏎ jaipur ⏎ akola ⏎" ticks three without a tap in
     * between. Clearing is the whole trick — leaving the query would leave the researcher staring
     * at the row they just ticked with no room for the next name.
     */
    fun onImeAction() {
        val row = highlighted ?: return
        if (multiple) {
            toggle(row.value)
            query = ""
            scope.launch { listState.scrollToItem(0) }
        } else {
            commitSingle(row.value)
        }
    }

    LaunchedEffect(Unit) {
        if (options.size >= AUTOFOCUS_THRESHOLD) focusRequester.requestFocus()
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
                // ORDER MATTERS, and the other way round is wrong. A padding modifier subtracts
                // from the constraints it passes inward and adds the same back to the size it
                // reports outward, so with `imePadding()` on the outside the cap only ever sees the
                // content — the sheet then reports content PLUS a 500dp keyboard and grows to the
                // full height of the screen, sliding its own drag handle up under the status bar
                // with no strip of the form left visible behind it. Capping first bounds the whole
                // node, keyboard included, so 12% of the screen stays visible whatever the IME does
                // and the researcher can still see which form they are picking into.
                .heightIn(max = maxSheetHeight)
                // Still outside the LIST, though: the keyboard has to SHRINK the rows rather than
                // pad them, or the Done bar ends up underneath the IME. The sheet has its own
                // window, which the activity's inset handling does not reach.
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
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    // Just "Search", not "Search $title". A field label in this app is a whole
                    // phrase — "Artisans of selected crafts", "State / union territory" — and
                    // prefixing it wrapped the label onto two lines, which grows the box and, on a
                    // required field, produced "Search Craft *". The heading directly above already
                    // names the list, and it is the first thing TalkBack lands on inside the sheet,
                    // so the short label loses nothing.
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
                            IconButton(onClick = { query = "" }) {
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
                // A FlowRow and not a Row: "Select all 74 shown" and "Clear all" beside a count are
                // about 230dp at font scale 1 and will not fit a 360dp screen at font scale 2, and
                // a Row answers that by drawing off the edge. Wrapping is the only answer that
                // holds at every scale, and at scale 1 it is still one line.
                FlowRow(
                    verticalArrangement = Arrangement.Center,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        countLine(filtered.size, options.size, searching),
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            // Polite, not Assertive: it fires on every keystroke, and a screen
                            // reader that interrupts its own echo of the letter just typed makes
                            // the field unusable.
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    if (multiple) {
                        // SELECT ALL TAKES THE VISIBLE ROWS, NOT THE WHOLE LIST — and the count in
                        // the label is what says so, in both states, which is why the word "shown"
                        // stays there even when nothing is filtered. Filtering to "Bagru" and
                        // ticking the nine that match is the reason to have a search box in a
                        // multi-select at all; a Select-all that quietly reached past the filter
                        // and took all 74 tools would be the one action in the app capable of
                        // undoing a careful search in a single tap. Clear all is deliberately the
                        // OTHER way round — it empties the selection entirely, filtered or not,
                        // because a half-cleared selection you cannot see is not an escape hatch.
                        // Same wording and same split as the web's assignment builder.
                        TextButton(
                            onClick = { draft = draft + filtered.map { it.value } },
                            enabled = visibleUnselected > 0
                        ) { Text("Select all ${filtered.size} shown", fontSize = 12.sp) }
                        TextButton(
                            onClick = { draft = emptySet() },
                            enabled = draft.isNotEmpty()
                        ) { Text("Clear all", fontSize = 12.sp) }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)

            LazyColumn(
                state = listState,
                // fill = false so a five-row list makes a five-row sheet; the cap above is what
                // stops a 200-row one from trying to be taller than the screen.
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
                // NO ITEM KEYS anywhere in this list, deliberately. A key has to be unique and
                // an option value need not be: the country list gives twenty countries the dial
                // code "+1", and a language list can offer a record's existing language twice.
                // Keyed on the value, LazyColumn throws on the duplicate — the picker would crash
                // on exactly the longest lists it exists for. Falling back to position keys costs
                // only scroll-position stability across a filter change, and jumping back to the
                // top is what filtering should do anyway.
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
                if (filtered.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Nothing matches “${query.trim()}”.",
                                color = MaterialTheme.field.muted,
                                fontSize = 13.sp
                            )
                            TextButton(onClick = { query = "" }) { Text("Clear search") }
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
                        // The sheet's own bottom inset is consumed by imePadding once the keyboard
                        // is up; with it down, this is what keeps Done off the gesture bar.
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

/** "12 of 74 match" while filtering, plain "74 options" otherwise. Read aloud on every keystroke. */
private fun countLine(shown: Int, total: Int, searching: Boolean): String = when {
    !searching -> if (total == 1) "1 option" else "$total options"
    shown == 0 -> "No matches"
    else -> "$shown of $total match"
}

/**
 * One row of the picker.
 *
 * The whole row is the target, not the checkbox: `toggleable`/`selectable` on the Row is what gives
 * TalkBack a single node with the right role and its checked state, and the Checkbox is handed a
 * null callback so it stops being a focus stop of its own. A 48dp floor because these rows sit in a
 * scrolling list where a mis-hit picks the neighbour.
 */
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
            Checkbox(checked = selected, onCheckedChange = null)
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
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
            hint?.let {
                Text(it, color = MaterialTheme.field.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (isHighlighted) {
            // Says what the keyboard's action key is about to do, so committing with it is never a
            // guess about which row "the highlighted one" means.
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
