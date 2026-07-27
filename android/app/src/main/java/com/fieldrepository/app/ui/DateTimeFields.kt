package com.fieldrepository.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerColors
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// =================================================================================================
// Themed date & time entry — the app's ONLY date/time controls.
//
// WHY THIS FILE EXISTS
// --------------------
// Three screens each opened `android.app.DatePickerDialog` — the PLATFORM dialog, not a Compose one.
// That dialog is styled by `res/values/styles.xml`, which the platform selects from the SYSTEM's
// night mode, while the app's own appearance setting is resolved in Compose
// (`resolveDarkTheme(preferences.theme, isSystemInDarkTheme())`). The two disagree the moment a
// researcher picks Dark (or Light) in Settings against a phone set the other way: the app renders
// dark and the calendar that opens on top of it is a white Holo-shaped sheet — a different type
// ramp, a different corner radius, a different accent, and no relation to the theme underneath.
// A resource qualifier cannot see an in-app preference, so no amount of styles.xml fixes it. The
// calendar has to be a Compose surface reading the same `MaterialTheme.colorScheme` as the screen
// that opened it, which is what everything below is.
//
// WHY EVERY SLOT IS SET BY HAND
// -----------------------------
// Material 3 pickers do NOT simply inherit `colorScheme`. `DatePickerColors` has 24 colour slots and
// `TimePickerColors` 14, each defaulting to a token from the M3 *baseline* mapping rather than to
// the nearest thing in the app's scheme — so a picker dropped in unconfigured renders its headline,
// weekday row, today ring, month-navigation chevrons, year grid and divider in colours this design
// system never declares. All 38 are assigned in [fieldDatePickerColors] / [fieldTimePickerColors].
//
// WHY THE TYPED PATH IS OURS AND NOT MATERIAL'S
// ---------------------------------------------
// See [FieldDateField]. In one sentence: M3's keyboard-entry mode derives its date pattern from the
// device locale, and the web's is always dd/mm/yyyy.
// =================================================================================================

/** The one input format, both clients: the web's `DateRangeField` placeholder, verbatim. */
const val FIELD_DATE_HINT: String = "dd/mm/yyyy"

/**
 * dd/MM/yyyy on [Locale.ROOT], not the default locale.
 *
 * A numeric pattern still picks its NUMBERING SYSTEM from the locale, so `Locale.getDefault()` on a
 * phone set to Bengali or Arabic formats the same day as "০৩/০৪/২০২৬". The web writes ASCII digits
 * and [parseFieldDate] reads ASCII digits, so this side has to produce them too — otherwise a date
 * this field printed is a date it cannot read back.
 */
private val fieldDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

/** A day as the dd/mm/yyyy the web shows in the same box. */
fun formatFieldDate(date: LocalDate): String = date.format(fieldDateFormatter)

private val DMY = Regex("""^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})$""")
private val ISO = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")

/**
 * The web's `parseTyped`, ported: dd/mm/yyyy (also `-` or `.` separators) and yyyy-mm-dd, with
 * impossible dates rejected rather than rolled forward.
 *
 * `LocalDate.of` is what does the rejecting — it throws on 31/02, where a `SimpleDateFormat` would
 * have quietly handed back 3 March. The web reaches the same place by round-tripping the components
 * back out of the constructed Date; both refuse, which is the behaviour that matters.
 */
fun parseFieldDate(text: String): LocalDate? {
    val trimmed = text.trim()
    val dmy = DMY.find(trimmed)
    val (year, month, day) = when {
        dmy != null -> Triple(dmy.groupValues[3].toInt(), dmy.groupValues[2].toInt(), dmy.groupValues[1].toInt())
        else -> {
            val iso = ISO.find(trimmed) ?: return null
            Triple(iso.groupValues[1].toInt(), iso.groupValues[2].toInt(), iso.groupValues[3].toInt())
        }
    }
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

/**
 * A day as the UTC epoch millis every Material 3 picker state speaks, and back.
 *
 * UTC on BOTH legs, never the device zone. The web file carries a long note about the same trap:
 * read a stored instant with local components and a workshop that ended on the 12th reopens on the
 * 13th east of Greenwich, saves, and walks a day further every time. Here the risk is smaller in
 * scope but identical in shape — `DatePickerState` is documented as UTC-midnight, so converting
 * through `ZoneId.systemDefault()` shifts the selected cell by a day for anyone not on UTC.
 */
private fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toPickerDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

// -------------------------------------------------------------------------------------------------
// Colours
// -------------------------------------------------------------------------------------------------

/**
 * The text field Material 3 uses in its own keyboard-entry mode. Set even though
 * [FieldDateField] keeps that mode switched off (see its note): an unset slot is a slot that
 * renders the baseline palette the day somebody turns the toggle on.
 */
@Composable
private fun fieldDateTextFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    val tokens = MaterialTheme.field
    return TextFieldDefaults.colors(
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        disabledTextColor = tokens.placeholder,
        errorTextColor = scheme.error,
        focusedContainerColor = tokens.surface100,
        unfocusedContainerColor = tokens.surface100,
        disabledContainerColor = tokens.surface100,
        errorContainerColor = tokens.surface100,
        cursorColor = scheme.primary,
        errorCursorColor = scheme.error,
        focusedIndicatorColor = scheme.primary,
        unfocusedIndicatorColor = tokens.hairline,
        disabledIndicatorColor = tokens.hairline,
        errorIndicatorColor = scheme.error,
        focusedLabelColor = scheme.primary,
        unfocusedLabelColor = tokens.muted,
        disabledLabelColor = tokens.placeholder,
        errorLabelColor = scheme.error,
        focusedPlaceholderColor = tokens.placeholder,
        unfocusedPlaceholderColor = tokens.placeholder,
        focusedSupportingTextColor = tokens.muted,
        unfocusedSupportingTextColor = tokens.muted,
        errorSupportingTextColor = scheme.error
    )
}

/**
 * Every one of the 24 `DatePickerColors` slots, mapped onto the design tokens.
 *
 * The mapping IS the web calendar (`components/ui/calendar.tsx`), slot for slot: day numbers on
 * ink-900, the weekday letters and the month caption on ink-500, a selected day white on purple-700,
 * today marked in purple-700, the in-range wash on the purple container rung, hairline dividers on
 * line-200. Dark mode inherits the scheme's own tonal shift — `primary` climbs to purple-400 there,
 * as ui/Theme.kt explains — so the action colour stays the single purple ramp in both modes and no
 * amber/gold token is reachable from here at all.
 *
 * Material's month grid draws no leading/trailing days from the neighbouring months, so the web's
 * `outside: text-ink-300` has no counterpart to set; `disabledDayContentColor` is the closest thing
 * — days a `minimum`/`maximum` has ruled out — and takes the same ink-300 rung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun fieldDatePickerColors(): DatePickerColors {
    val scheme = MaterialTheme.colorScheme
    val tokens = MaterialTheme.field
    return DatePickerDefaults.colors(
        containerColor = scheme.surface,
        titleContentColor = tokens.muted,
        headlineContentColor = scheme.onSurface,
        weekdayContentColor = tokens.muted,
        subheadContentColor = tokens.muted,
        navigationContentColor = tokens.body,
        yearContentColor = tokens.body,
        disabledYearContentColor = tokens.placeholder,
        currentYearContentColor = scheme.primary,
        selectedYearContentColor = scheme.onPrimary,
        disabledSelectedYearContentColor = tokens.placeholder,
        selectedYearContainerColor = scheme.primary,
        disabledSelectedYearContainerColor = tokens.surface200,
        dayContentColor = scheme.onSurface,
        disabledDayContentColor = tokens.placeholder,
        selectedDayContentColor = scheme.onPrimary,
        disabledSelectedDayContentColor = tokens.placeholder,
        selectedDayContainerColor = scheme.primary,
        disabledSelectedDayContainerColor = tokens.surface200,
        todayContentColor = scheme.primary,
        todayDateBorderColor = scheme.primary,
        dayInSelectionRangeContentColor = scheme.onPrimaryContainer,
        dayInSelectionRangeContainerColor = scheme.primaryContainer,
        dividerColor = tokens.hairline,
        dateTextFieldColors = fieldDateTextFieldColors()
    )
}

/**
 * Every one of the 14 `TimePickerColors` slots. The dial sits on the tinted panel rung rather than
 * on the card, so the clock face reads as a distinct object the way the web's bordered calendar
 * panel does; the selected hand and the active hour/minute box are the action purple.
 *
 * The AM/PM period toggle is coloured too even though every caller runs the picker in 24-hour mode
 * — the app stores `HH:mm` and the batch window is documented in IST — because the toggle appears
 * the moment a caller passes `is24Hour = false`, and an unset slot then renders the baseline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun fieldTimePickerColors(): TimePickerColors {
    val scheme = MaterialTheme.colorScheme
    val tokens = MaterialTheme.field
    return TimePickerDefaults.colors(
        clockDialColor = tokens.surface100,
        clockDialSelectedContentColor = scheme.onPrimary,
        clockDialUnselectedContentColor = scheme.onSurface,
        selectorColor = scheme.primary,
        containerColor = scheme.surface,
        periodSelectorBorderColor = tokens.hairline,
        periodSelectorSelectedContainerColor = scheme.primaryContainer,
        periodSelectorUnselectedContainerColor = scheme.surface,
        periodSelectorSelectedContentColor = scheme.onPrimaryContainer,
        periodSelectorUnselectedContentColor = tokens.muted,
        timeSelectorSelectedContainerColor = scheme.primaryContainer,
        timeSelectorUnselectedContainerColor = tokens.surface100,
        timeSelectorSelectedContentColor = scheme.onPrimaryContainer,
        timeSelectorUnselectedContentColor = scheme.onSurface
    )
}

// -------------------------------------------------------------------------------------------------
// Single date
// -------------------------------------------------------------------------------------------------

/**
 * Inserts the dd/mm/yyyy slashes over a value held as bare digits, so the field can ask for the
 * NUMERIC keypad and still read as a formatted date.
 *
 * The alternative — a plain text field the researcher types "03/04/2026" into — needs a "/", and the
 * numeric keypad on this handset has none. Asking for the full keyboard instead would put a
 * qwerty under a field that only ever takes eight digits.
 */
private object DateSlashTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text.take(8)
        val out = buildString {
            digits.forEachIndexed { index, c ->
                if (index == 2 || index == 4) append('/')
                append(c)
            }
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = when {
                offset <= 2 -> offset
                offset <= 4 -> offset + 1
                else -> offset + 2
            }.coerceAtMost(out.length)

            override fun transformedToOriginal(offset: Int): Int = when {
                offset <= 2 -> offset
                offset <= 5 -> offset - 1
                else -> offset - 2
            }.coerceIn(0, digits.length)
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}

/** The eight digits behind a dd/mm/yyyy value, or "" for no date. */
private fun LocalDate?.toDateDigits(): String = this?.format(fieldDateFormatter)?.filter { it.isDigit() } ?: ""

/**
 * A labelled date field: type the date, or tap the calendar for the themed picker.
 *
 * TYPING IS THE FIELD ITSELF, NOT A MODE INSIDE THE DIALOG — and that is a decision about data, not
 * about taste. Material 3's picker does have a keyboard-entry toggle, but the pattern it types in
 * comes from `CalendarModel.getDateInputFormat(locale)`, which is internal and has no public
 * override. On this handset (en-GB) it renders dd/mm/yyyy; on an en-US phone the very same build
 * renders mm/dd/yyyy. A researcher entering a workshop on the 3rd of April would type 03/04/2026 on
 * one phone and 04/03/2026 on another, and the record would be off by a month with nothing on
 * screen to show it. The web has one format everywhere, so this field does too: eight digits, always
 * read day-month-year, shown through [DateSlashTransformation] and parsed by [parseFieldDate] —
 * which is the web's `parseTyped` line for line. `showModeToggle = false` on the dialog then keeps
 * the ambiguous mode off the screen entirely rather than leaving two typed paths that disagree.
 *
 * Bounds are enforced twice over: [minimum]/[maximum] grey the impossible days out in the calendar,
 * and a typed date outside them shows an inline reason instead of being silently accepted.
 */
@Composable
fun FieldDateField(
    label: String,
    value: LocalDate?,
    onValueChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = FIELD_DATE_HINT,
    minimum: LocalDate? = null,
    maximum: LocalDate? = null,
    clearable: Boolean = false,
    supportingText: String? = null
) {
    var digits by rememberSaveable { mutableStateOf(value.toDateDigits()) }
    var rangeError by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    // The owner can move the value on its own — the range field pulls a start date back to meet an
    // earlier end, and a form reset clears everything. Re-render from the value unless the box
    // already holds exactly that day, which is what keeps a half-typed "03/0" from being wiped.
    LaunchedEffect(value) {
        if (parseFieldDate(digits.withDateSlashes()) != value) digits = value.toDateDigits()
    }

    fun commit(raw: String) {
        // A paste arrives whole ("03/04/2026", or the yyyy-mm-dd the API speaks); read it as a date
        // first and fall back to stripping, so both formats the web accepts land here too.
        val pasted = parseFieldDate(raw)
        digits = if (pasted != null) pasted.toDateDigits() else raw.filter { it.isDigit() }.take(8)
        val parsed = parseFieldDate(digits.withDateSlashes())
        rangeError = when {
            digits.isEmpty() -> null
            parsed == null -> null // Still being typed — an error on every keystroke is just noise.
            minimum != null && parsed.isBefore(minimum) -> "Not before ${formatFieldDate(minimum)}"
            maximum != null && parsed.isAfter(maximum) -> "Not after ${formatFieldDate(maximum)}"
            else -> null
        }
        if (rangeError != null) return
        if (digits.isEmpty()) onValueChange(null) else if (parsed != null) onValueChange(parsed)
    }

    OutlinedTextField(
        value = digits,
        onValueChange = ::commit,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = MaterialTheme.field.placeholder) },
        singleLine = true,
        isError = rangeError != null,
        visualTransformation = DateSlashTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = (rangeError ?: supportingText)?.let { msg -> { Text(msg) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.field.hairline,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.field.muted,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedTrailingIconColor = MaterialTheme.colorScheme.primary,
            unfocusedTrailingIconColor = MaterialTheme.field.muted,
            focusedSupportingTextColor = MaterialTheme.field.muted,
            unfocusedSupportingTextColor = MaterialTheme.field.muted
        ),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (clearable && digits.isNotEmpty()) {
                    IconButton(onClick = { digits = ""; rangeError = null; onValueChange(null) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear $label")
                    }
                }
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = "Choose $label from a calendar")
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            // TalkBack reads the box's raw contents, which here are eight bare digits — "zero three
            // zero four two zero two six" says nothing about which of them is the month. The SPOKEN
            // value is the formatted date instead; the visual value is untouched, and because this
            // adds a description rather than clearing the node, the field stays focusable and
            // editable with the keyboard.
            .semantics {
                contentDescription = value?.let { "$label, ${formatFieldDate(it)}" }
                    ?: "$label, not set, format day slash month slash year"
            }
            .onFocusChanged { state ->
                // The web's onBlur: leaving a half-typed box restores the committed day rather than
                // stranding "03/0" on screen.
                if (!state.isFocused && parseFieldDate(digits.withDateSlashes()) == null) {
                    digits = value.toDateDigits()
                    rangeError = null
                }
            }
    )

    if (showPicker) {
        FieldDatePickerDialog(
            title = label,
            initial = value,
            minimum = minimum,
            maximum = maximum,
            onDismiss = { showPicker = false },
            onConfirm = { picked ->
                showPicker = false
                digits = picked.toDateDigits()
                rangeError = null
                onValueChange(picked)
            }
        )
    }
}

/** Eight digits back into the dd/mm/yyyy [parseFieldDate] reads. Partial input stays unparseable. */
private fun String.withDateSlashes(): String =
    if (length == 8) "${substring(0, 2)}/${substring(2, 4)}/${substring(4)}" else this

/**
 * The single-day calendar. A modal in the app's own theme, so it arrives over the form instead of
 * pushing it down — which is the one half of the web complaint that does not apply here, the web
 * calendar being an inline panel in the page flow and this one a dialog by construction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldDatePickerDialog(
    title: String,
    initial: LocalDate?,
    minimum: LocalDate?,
    maximum: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    val colors = fieldDatePickerColors()
    val selectable = remember(minimum, maximum) { boundedDates(minimum, maximum) }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.toPickerMillis(),
        yearRange = pickerYearRange(minimum, maximum),
        selectableDates = selectable
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = colors,
        shape = MaterialTheme.shapes.extraLarge,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onConfirm(it.toPickerDate()) } },
                enabled = state.selectedDateMillis != null
            ) { Text("Set date") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(
            state = state,
            colors = colors,
            // Off deliberately — see the note on [FieldDateField]. The typed path is the field
            // itself, in one unambiguous format; Material's is whatever the phone's locale says.
            showModeToggle = false,
            title = {
                Text(
                    title,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                )
            },
            headline = {
                // Material's own headline formats through the locale skeleton ("3 Apr 2026" here,
                // "Apr 3, 2026" in en-US). The headline is the confirmation of what is about to be
                // saved, so it shows the same dd/mm/yyyy the field above it holds.
                Text(
                    state.selectedDateMillis?.let { formatFieldDate(it.toPickerDate()) } ?: FIELD_DATE_HINT,
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp)
                )
            }
        )
    }
}

/** `minimum`..`maximum` as the picker's own notion of a selectable day. */
@OptIn(ExperimentalMaterial3Api::class)
private fun boundedDates(minimum: LocalDate?, maximum: LocalDate?): SelectableDates =
    object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
            val day = utcTimeMillis.toPickerDate()
            if (minimum != null && day.isBefore(minimum)) return false
            if (maximum != null && day.isAfter(maximum)) return false
            return true
        }

        override fun isSelectableYear(year: Int): Boolean =
            (minimum == null || year >= minimum.year) && (maximum == null || year <= maximum.year)
    }

/**
 * The years the grid offers. Material's default is 1900..2100, which is 200 rows to scroll past for
 * a field recording a workshop; a bounded field narrows to its bounds and an unbounded one to a
 * window around today wide enough for an artisan's date of birth at one end and a planned workshop
 * at the other.
 */
private fun pickerYearRange(minimum: LocalDate?, maximum: LocalDate?): IntRange {
    val thisYear = LocalDate.now().year
    return (minimum?.year ?: (thisYear - 100))..(maximum?.year ?: (thisYear + 5))
}

// -------------------------------------------------------------------------------------------------
// Date range
// -------------------------------------------------------------------------------------------------

/**
 * Start date + end date as ONE answer, the way the web's `DateRangeField` puts it: a single heading
 * over two typed boxes, both feeding one range calendar.
 *
 * The two are stacked rather than sat side by side, which is also what the web does on a phone — its
 * grid only splits at the `sm:` breakpoint (640px), above any handset in portrait. Side by side here
 * would have given each box half a screen to hold "dd/mm/yyyy" plus a 48dp calendar button, and the
 * first thing to go at a large font scale would be the value.
 *
 * AN END BEFORE A START IS IMPOSSIBLE TO ENTER, and the correction is the web's, both directions:
 * moving the start past the end drags the end with it, and setting an end before the start pulls the
 * start back onto it (the range collapses to that single day rather than inverting). The Android
 * form previously enforced only the first of those — `endDate = it`, unconditionally — so an end
 * before a start saved cleanly and a workshop came back with a negative duration.
 */
@Composable
fun FieldDateRangeField(
    label: String,
    start: LocalDate?,
    end: LocalDate?,
    onRangeChange: (LocalDate?, LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    startLabel: String = "Start date",
    endLabel: String = "End date"
) {
    var showCalendar by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showCalendar = true }) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Pick on a calendar")
            }
        }
        FieldDateField(
            label = startLabel,
            value = start,
            onValueChange = { picked -> onRangeChange(picked, clampEnd(picked, end)) }
        )
        FieldDateField(
            label = endLabel,
            value = end,
            onValueChange = { picked -> onRangeChange(clampStart(start, picked), picked) }
        )
    }

    if (showCalendar) {
        FieldDateRangeDialog(
            title = label,
            start = start,
            end = end,
            onDismiss = { showCalendar = false },
            onConfirm = { from, to ->
                showCalendar = false
                onRangeChange(from, to ?: from)
            }
        )
    }
}

/** The web's `handleFromChange`: a start that has overshot the end takes the end with it. */
private fun clampEnd(start: LocalDate?, end: LocalDate?): LocalDate? =
    if (start != null && (end == null || end.isBefore(start))) start else end

/** The web's `handleToChange`: an end before the start pulls the start onto it, never inverts. */
private fun clampStart(start: LocalDate?, end: LocalDate?): LocalDate? =
    if (end != null && (start == null || start.isAfter(end))) end else start

/**
 * The range calendar, full-screen.
 *
 * Material sizes `DateRangePicker` for a full-screen dialog and not for the boxed one the single
 * picker uses — it is a continuous vertical list of months rather than one paged grid, so it needs
 * the height and it brings its own scrolling. Which is also what makes it the safe shape at a 2×
 * system font: the months scroll, so growing type lengthens the list instead of overflowing a fixed
 * box. The bar is pinned above it so Cancel and Save stay reachable however tall the content gets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldDateRangeDialog(
    title: String,
    start: LocalDate?,
    end: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate?, LocalDate?) -> Unit
) {
    val colors = fieldDatePickerColors()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = start?.toPickerMillis(),
        initialSelectedEndDateMillis = end?.toPickerMillis(),
        yearRange = pickerYearRange(null, null)
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close $title without changing the dates",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        title,
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    )
                    TextButton(
                        onClick = {
                            onConfirm(
                                state.selectedStartDateMillis?.toPickerDate(),
                                state.selectedEndDateMillis?.toPickerDate()
                            )
                        },
                        enabled = state.selectedStartDateMillis != null
                    ) { Text("Save") }
                }
                HorizontalDivider(color = MaterialTheme.field.hairline)
                DateRangePicker(
                    state = state,
                    colors = colors,
                    // Same reason as the single picker: Material's typed mode reads the locale, the
                    // two boxes behind this dialog read dd/mm/yyyy.
                    showModeToggle = false,
                    title = {
                        Text(
                            "Tap the first day, then the last",
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp)
                        )
                    },
                    headline = {
                        val from = state.selectedStartDateMillis?.toPickerDate()
                        val to = state.selectedEndDateMillis?.toPickerDate()
                        Text(
                            when {
                                from == null -> FIELD_DATE_HINT
                                // The web's own summary line, wording included.
                                to == null -> formatFieldDate(from)
                                else -> "${formatFieldDate(from)} to ${formatFieldDate(to)}"
                            },
                            display = true,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Time
// -------------------------------------------------------------------------------------------------

/** `HH:mm` as hour and minute, coerced into range; anything unreadable starts at midnight. */
private fun parseHourMinute(value: String): Pair<Int, Int> {
    val parts = value.split(":").mapNotNull { it.trim().toIntOrNull() }
    return (parts.getOrNull(0)?.coerceIn(0, 23) ?: 0) to (parts.getOrNull(1)?.coerceIn(0, 59) ?: 0)
}

/**
 * A labelled `HH:mm` time, with both of Material's entry modes themed and a real toggle between them.
 *
 * The toggle survives here where the date one did not, because a 24-hour time has no locale
 * ambiguity to introduce: `TimeInput` shows two numeric boxes labelled Hour and Minute, which mean
 * the same thing on every phone. Anyone who knows the time types it; anyone reaching for the dial
 * still has it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldTimeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var show by remember { mutableStateOf(false) }
    val (hour, minute) = remember(value) { parseHourMinute(value) }

    // The box-plus-overlay shape ArtisanPhoneField uses for its dial-code picker, and for the same
    // reason: a read-only OutlinedTextField still consumes taps on its own box, so the target has to
    // be drawn over it. matchParentSize borrows the field's 56dp height, which is what holds the
    // target above 48dp at every font scale without a hardcoded minimum.
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.field.hairline,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.field.muted,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTrailingIconColor = MaterialTheme.field.muted,
                focusedTrailingIconColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties { canFocus = false }
                .clearAndSetSemantics {}
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(OutlinedTextFieldDefaults.shape)
                .clickable(onClickLabel = "Change $label", role = Role.Button) { show = true }
                .semantics { contentDescription = "$label, $value" }
        )
    }

    if (show) {
        FieldTimePickerDialog(
            title = label,
            hour = hour,
            minute = minute,
            onDismiss = { show = false },
            onConfirm = { h, m ->
                show = false
                onValueChange("%02d:%02d".format(Locale.ROOT, h, m))
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldTimePickerDialog(
    title: String,
    hour: Int,
    minute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    val colors = fieldTimePickerColors()
    var keyboardEntry by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(24.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    title,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.labelLarge
                )
                if (keyboardEntry) TimeInput(state = state, colors = colors)
                else TimePicker(state = state, colors = colors)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { keyboardEntry = !keyboardEntry }) {
                        Icon(
                            if (keyboardEntry) Icons.Outlined.Schedule else Icons.Outlined.Keyboard,
                            contentDescription = if (keyboardEntry) "Switch to the clock" else "Switch to typing the time",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("Set time") }
                }
            }
        }
    }
}
