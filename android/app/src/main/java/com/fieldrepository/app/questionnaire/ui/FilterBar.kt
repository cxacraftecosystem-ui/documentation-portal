package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.questionnaire.data.ChipRules
import com.fieldrepository.app.questionnaire.data.QFilterOption
import com.fieldrepository.app.questionnaire.data.QFilterOptions
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireFilters
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The searchable filters a screen offers (some screens fix one, e.g. a form's sessions fix the form). */
enum class FilterField(val key: String, val facet: String?, val labelKey: String) {
    QUESTIONNAIRE("questionnaireId", "questionnaires", "filterQuestionnaire"),
    SECTION("sectionId", "sections", "filterSection"),
    ARTISANS("artisanId", "artisans", "filterArtisans"),
    RESEARCHER("researcherId", "researchers", "filterResearcher"),
    WORKSHOP("workshopId", "workshops", "filterWorkshop"),
    DATES("dateFrom", null, "filterDates"),
    KIND("kind", null, "filterKind"),
    SOURCE("source", null, "filterSource"),
    TRANSCRIPT("transcript", null, "filterTranscript"),
    LANGUAGE("languageCode", "languages", "filterLanguage");

    companion object {
        val ALL = entries.toList()
        /** The forms library: a form's own fields, and who and where its sessions were. */
        val FORMS = listOf(ARTISANS, RESEARCHER, WORKSHOP, DATES, KIND, SOURCE, TRANSCRIPT, LANGUAGE)
    }
}

/** How a filter value reads in an active pill. */
internal fun pillLabel(filters: QuestionnaireFilters, key: String, value: String): String = when (key) {
    "kind" -> if (value == "NONE") QuestionnaireCopy.t("kindNotStated") else value.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    "source" -> sourceLabel(value)
    "transcript" -> transcriptLabel(value)
    "languageCode" -> ChipRules.languageLabel(value)
    else -> filters.labels[value] ?: value
}

internal fun sourceLabel(v: String) = when (v) {
    "UPLOAD" -> QuestionnaireCopy.t("sourceUpload")
    "HANDOFF" -> QuestionnaireCopy.t("sourceHandoff")
    else -> QuestionnaireCopy.t("sourceApp")
}

internal fun transcriptLabel(v: String) = when (v) {
    "HAS" -> QuestionnaireCopy.t("transcriptHas")
    "NONE" -> QuestionnaireCopy.t("transcriptNone")
    "FAILED" -> QuestionnaireCopy.t("transcriptFailed")
    else -> QuestionnaireCopy.t("transcriptPending")
}

/** Every value set, as (key, value) pairs in the shared key order; a date range is one pill. */
internal fun activePills(filters: QuestionnaireFilters, fixed: Set<String>): List<Pair<String, String>> =
    QuestionnaireFilters.KEYS.filter { it != "q" && it != "dateTo" && it !in fixed }.flatMap { key ->
        if (key == "dateFrom") {
            if (filters.dateFrom == null && filters.dateTo == null) emptyList()
            else listOf("dates" to QuestionnaireCopy.t(
                "filterDateRange",
                "from" to (filters.dateFrom?.let(ChipRules::dayLabel) ?: "…"), "to" to (filters.dateTo?.let(ChipRules::dayLabel) ?: "…")
            ))
        } else filters[key].map { key to it }
    }

/**
 * The search box and the filters of every questionnaire list (sessions, forms, a form's sessions,
 * recordings, media): a search field, "Filters (n)" opening a sheet of proper controls (searchable
 * selects, a date range calendar, pills), and the active filters as pills that remove themselves.
 *
 * @param options reads one filter's values from the server (`GET /questionnaire/filters`), given the
 *   facet name, the typed text and the forms chosen; null with no signal.
 * @param fixed filter keys this screen sets itself and does not show (a form's sessions fix its form).
 * @param offline shows "Offline: filtering what is on this device." under the bar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterBar(
    filters: QuestionnaireFilters,
    onChange: (QuestionnaireFilters) -> Unit,
    searchHint: String,
    options: suspend (facet: String, q: String, questionnaireIds: List<String>) -> QFilterOptions?,
    modifier: Modifier = Modifier,
    fields: List<FilterField> = FilterField.ALL,
    fixed: Set<String> = emptySet(),
    offline: Boolean = false
) {
    var sheet by remember { mutableStateOf(false) }
    var text by remember(filters.q) { mutableStateOf(filters.q) }
    LaunchedEffect(text) {
        if (text == filters.q) return@LaunchedEffect
        delay(350)
        onChange(filters.withQ(text))
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            label = { Text(searchHint) }, modifier = Modifier.fillMaxWidth().testTag("filter-search")
        )
        val pills = activePills(filters, fixed)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(
                selected = pills.isNotEmpty(), onClick = { sheet = true },
                label = { Text(if (pills.isEmpty()) QuestionnaireCopy.t("filtersTitle") else QuestionnaireCopy.t("filterCount", "count" to pills.size)) },
                modifier = Modifier.heightIn(min = 40.dp).testTag("filter-open")
            )
            pills.forEach { (key, value) ->
                InputChip(
                    selected = true,
                    onClick = { onChange(if (key == "dates") filters.without("dateFrom", "dateTo") else filters.with(key, filters[key] - value)) },
                    label = { Text(if (key == "dates") value else pillLabel(filters, key, value), style = MaterialTheme.typography.labelSmall) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove") },
                    modifier = Modifier.heightIn(min = 40.dp).testTag("filter-pill")
                )
            }
        }
        if (offline) Text(QuestionnaireCopy.t("filterOfflineCached"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        FilterSheet(filters, onChange, options, fields.filterNot { it.key in fixed }) { sheet = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    filters: QuestionnaireFilters,
    onChange: (QuestionnaireFilters) -> Unit,
    options: suspend (String, String, List<String>) -> QFilterOptions?,
    fields: List<FilterField>,
    onDone: () -> Unit
) {
    var picking by remember { mutableStateOf<FilterField?>(null) }
    var dates by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(QuestionnaireCopy.t("filtersTitle"), style = MaterialTheme.typography.titleMedium)
        fields.forEach { field ->
            val label = QuestionnaireCopy.t(field.labelKey)
            when (field) {
                FilterField.KIND, FilterField.SOURCE, FilterField.TRANSCRIPT -> {
                    val values = when (field) {
                        FilterField.KIND -> QuestionnaireFilters.KINDS
                        FilterField.SOURCE -> QuestionnaireFilters.SOURCES
                        else -> QuestionnaireFilters.TRANSCRIPT
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        values.forEach { v ->
                            val on = v in filters[field.key]
                            FilterChip(
                                selected = on, onClick = { onChange(filters.with(field.key, if (on) filters[field.key] - v else filters[field.key] + v)) },
                                label = { Text(pillLabel(filters, field.key, v)) }, modifier = Modifier.heightIn(min = 40.dp)
                            )
                        }
                    }
                }
                FilterField.DATES -> FieldRow(
                    label,
                    if (filters.dateFrom == null && filters.dateTo == null) QuestionnaireCopy.t("filterAny")
                    else QuestionnaireCopy.t("filterDateRange", "from" to (filters.dateFrom?.let(ChipRules::dayLabel) ?: "…"), "to" to (filters.dateTo?.let(ChipRules::dayLabel) ?: "…"))
                ) { dates = true }
                else -> {
                    val chosen = filters[field.key] + if (field == FilterField.SECTION) filters["questionLineageId"] else emptyList()
                    val chosenWorkshops = if (field == FilterField.WORKSHOP) filters["designWorkshopId"] else emptyList()
                    val all = chosen + chosenWorkshops
                    FieldRow(label, if (all.isEmpty()) QuestionnaireCopy.t("filterAny") else all.joinToString(", ") { filters.labels[it] ?: it }) { picking = field }
                }
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)
        }
        PromptButtons(listOf(
            PromptButton(QuestionnaireCopy.t("filterClearAll")) { onChange(QuestionnaireFilters(labels = filters.labels).withQ(filters.q)) },
            PromptButton(QuestionnaireCopy.t("filterDone"), primary = true, onClick = onDone)
        ))
    }
    picking?.let { field ->
        OptionPicker(field, filters, options, onDismiss = { picking = null }) { onChange(it); picking = null }
    }
    if (dates) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = filters.dateFrom?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
            initialSelectedEndDateMillis = filters.dateTo?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        )
        fun day(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
        DatePickerDialog(
            onDismissRequest = { dates = false },
            confirmButton = {
                PromptButtons(listOf(
                    PromptButton(QuestionnaireCopy.t("filterClearAll")) { onChange(filters.without("dateFrom", "dateTo")); dates = false },
                    PromptButton(QuestionnaireCopy.t("filterDone"), primary = true) {
                        val from = day(state.selectedStartDateMillis); val to = day(state.selectedEndDateMillis) ?: from
                        onChange(filters.with("dateFrom", listOfNotNull(from)).with("dateTo", listOfNotNull(to)))
                        dates = false
                    }
                ), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        ) { DateRangePicker(state = state, modifier = Modifier.heightIn(max = 520.dp)) }
    }
}

@Composable
private fun FieldRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted, maxLines = 1)
    }
}

/** A searchable select over the server's values for one filter; several may be ticked (one for researcher). */
@Composable
private fun OptionPicker(
    field: FilterField,
    filters: QuestionnaireFilters,
    options: suspend (String, String, List<String>) -> QFilterOptions?,
    onDismiss: () -> Unit,
    onApply: (QuestionnaireFilters) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<QFilterOption>>(emptyList()) }
    var questions by remember { mutableStateOf<List<QFilterOption>>(emptyList()) }
    var draft by remember { mutableStateOf(filters) }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) delay(250)
        val forms = filters["questionnaireId"]
        val got = options(field.facet ?: return@LaunchedEffect, query, forms)
        found = when (field) {
            FilterField.QUESTIONNAIRE -> got?.questionnaires
            FilterField.SECTION -> got?.sections
            FilterField.ARTISANS -> got?.artisans
            FilterField.RESEARCHER -> got?.researchers
            FilterField.WORKSHOP -> got?.workshops
            FilterField.LANGUAGE -> got?.languages?.map { it.copy(label = ChipRules.languageLabel(it.id)) }
            else -> null
        }.orEmpty()
        questions = if (field == FilterField.SECTION && forms.isNotEmpty()) options("questions", query, forms)?.questions.orEmpty() else emptyList()
    }
    fun keyOf(o: QFilterOption): String = when {
        field == FilterField.WORKSHOP && o.type == "DESIGN" -> "designWorkshopId"
        field == FilterField.SECTION && o in questions -> "questionLineageId"
        else -> field.key
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(QuestionnaireCopy.t(field.labelKey)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("filter-picker-search"))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(found + questions, key = { keyOf(it) + it.id }) { o ->
                        val key = keyOf(o)
                        val on = o.id in draft[key]
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                val single = field == FilterField.RESEARCHER
                                val next = if (on) draft[key] - o.id else if (single) listOf(o.id) else draft[key] + o.id
                                draft = draft.with(key, next, mapOf(o.id to o.label))
                            }.testTag("filter-option"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Text(o.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (o.count > 0) Text("${o.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            PromptButtons(listOf(
                PromptButton(QuestionnaireCopy.s("session.cancel"), onClick = onDismiss),
                PromptButton(QuestionnaireCopy.t("filterDone"), primary = true) { onApply(draft) }
            ))
        }
    )
}
