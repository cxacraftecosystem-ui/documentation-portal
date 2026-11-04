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

const val FIELD_DATE_HINT: String = "dd/mm/yyyy"

private val fieldDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

fun formatFieldDate(date: LocalDate): String = date.format(fieldDateFormatter)

private val DMY = Regex("""^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})$""")
private val ISO = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")

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

private fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toPickerDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

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

private fun LocalDate?.toDateDigits(): String = this?.format(fieldDateFormatter)?.filter { it.isDigit() } ?: ""

@Composable
fun CalendarField(
    label: String,
    value: LocalDate?,
    onValueChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = FIELD_DATE_HINT,
    minimum: LocalDate? = null,
    maximum: LocalDate? = null,
    clearable: Boolean = true,
    supportingText: String? = null
) = FieldDateField(
    label = label,
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    placeholder = placeholder,
    minimum = minimum,
    maximum = maximum,
    clearable = clearable,
    supportingText = supportingText
)

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

    LaunchedEffect(value) {
        if (parseFieldDate(digits.withDateSlashes()) != value) digits = value.toDateDigits()
    }

    fun commit(raw: String) {
        val pasted = parseFieldDate(raw)
        digits = if (pasted != null) pasted.toDateDigits() else raw.filter { it.isDigit() }.take(8)
        val parsed = parseFieldDate(digits.withDateSlashes())
        rangeError = when {
            digits.isEmpty() -> null
            parsed == null -> null
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
            .semantics {
                contentDescription = value?.let { "$label, ${formatFieldDate(it)}" }
                    ?: "$label, not set, format day slash month slash year"
            }
            .onFocusChanged { state ->
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

private fun String.withDateSlashes(): String =
    if (length == 8) "${substring(0, 2)}/${substring(2, 4)}/${substring(4)}" else this

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

private fun pickerYearRange(minimum: LocalDate?, maximum: LocalDate?): IntRange {
    val thisYear = LocalDate.now().year
    return (minimum?.year ?: (thisYear - 100))..(maximum?.year ?: (thisYear + 5))
}

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
                style = MaterialTheme.typography.titleMedium,
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

private fun clampEnd(start: LocalDate?, end: LocalDate?): LocalDate? =
    if (start != null && (end == null || end.isBefore(start))) start else end

private fun clampStart(start: LocalDate?, end: LocalDate?): LocalDate? =
    if (end != null && (start == null || start.isAfter(end))) end else start

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

private fun parseHourMinute(value: String): Pair<Int, Int> {
    val parts = value.split(":").mapNotNull { it.trim().toIntOrNull() }
    return (parts.getOrNull(0)?.coerceIn(0, 23) ?: 0) to (parts.getOrNull(1)?.coerceIn(0, 59) ?: 0)
}

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
