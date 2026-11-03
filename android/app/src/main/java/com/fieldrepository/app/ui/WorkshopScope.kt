package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.workshopDisplayTitle
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.WorkshopDetailDto
import com.fieldrepository.app.data.apiErrorMessage
import com.fieldrepository.app.data.occurrenceDate
import kotlinx.coroutines.CancellationException

const val UNASSIGNED_WORKSHOP = "none"

@Stable
class WorkshopScopeState internal constructor(
    private val repository: FieldRepository,
    private val defaultToMostRecent: Boolean,
    initialSelection: Set<String>
) {
    var workshops: List<WorkshopDetailDto> by mutableStateOf(emptyList())
        private set

    var selected: Set<String> by mutableStateOf(initialSelection)
        private set

    var settled: Boolean by mutableStateOf(!defaultToMostRecent || initialSelection.isNotEmpty())
        private set

    private var touched: Boolean = initialSelection.isNotEmpty()

    fun select(next: Set<String>) {
        touched = true
        selected = next
    }

    fun selectAllRecords() = select(emptySet())

    fun selectMostRecent() {
        workshops.firstOrNull()?.let { select(setOf(it.id)) }
    }

    val isAllRecords: Boolean get() = selected.isEmpty()

    val workshopIds: List<String> get() = selected.sorted()

    val requestKey: String get() = workshopIds.joinToString(",")

    val summary: String get() = workshopScopeSummary(selected, workshops)

    internal suspend fun load(onError: (String) -> Unit) {
        runCatching { repository.workshopsByOccurrence() }
            .onSuccess { list ->
                workshops = list
                if (defaultToMostRecent && !touched && list.isNotEmpty()) {
                    selected = setOf(list.first().id)
                }
            }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                onError(failure.apiErrorMessage("The workshops could not be loaded."))
            }
        settled = true
    }
}

@Composable
fun rememberWorkshopScope(
    repository: FieldRepository,
    defaultToMostRecent: Boolean = true,
    initialSelection: Set<String> = emptySet(),
    onError: (String) -> Unit = {}
): WorkshopScopeState {
    val state = remember(repository, defaultToMostRecent) {
        WorkshopScopeState(repository, defaultToMostRecent, initialSelection)
    }
    val report = rememberUpdatedState(onError)
    LaunchedEffect(state) { state.load { message -> report.value(message) } }
    return state
}

fun workshopScopeLabel(workshop: WorkshopDetailDto): String {
    if (workshop.title.isBlank()) return workshopDisplayTitle(null, date = workshop.occurrenceDate(), place = workshop.place)
    val title = workshop.title
    val day = workshop.occurrenceDate().take(10)
    return if (day.isBlank()) title else "$title · $day"
}

fun workshopScopeSummary(selected: Set<String>, workshops: List<WorkshopDetailDto>): String {
    if (selected.isEmpty()) return "Every workshop, and records not linked to one."
    val named = selected
        .filter { it != UNASSIGNED_WORKSHOP }
        .map { id ->
            workshops.firstOrNull { it.id == id }
                ?.let { workshopDisplayTitle(it.title, date = it.occurrenceDate(), place = it.place) }
                ?: workshopDisplayTitle(null)
        }
    if (named.isEmpty()) return "Only records not linked to a workshop."
    val list = if (named.size <= 3) named.joinToString(", ")
    else "${named.take(3).joinToString(", ")} and ${named.size - 3} more"
    val plusUnassigned =
        if (selected.contains(UNASSIGNED_WORKSHOP)) ", plus records not linked to a workshop" else ""
    return "$list$plusUnassigned."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkshopScopeSelect(
    scope: WorkshopScopeState,
    label: String = "Workshops",
    modifier: Modifier = Modifier,
    showSummary: Boolean = true
) {
    val options = remember(scope.workshops) {
        scope.workshops.map { SelectOption(it.id, workshopScopeLabel(it)) } +
            SelectOption(UNASSIGNED_WORKSHOP, "Not linked to a workshop")
    }
    val all = scope.isAllRecords

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier.fillMaxWidth()) {
        SearchableMultiSelectField(
            label = label,
            options = options,
            selected = scope.selected,
            placeholder = if (all) "All workshops" else "Select workshops",
            emptyMessage = "No workshops recorded yet",
            onSelectedChange = { next -> scope.select(next) }
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = all,
                onClick = { scope.selectAllRecords() },
                leadingIcon = {
                    Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = { Text("All records", style = MaterialTheme.typography.labelMedium) }
            )
            if (!all && scope.workshops.isNotEmpty()) {
                TextButton(onClick = { scope.selectMostRecent() }) {
                    Text("Most recent only", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (showSummary) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.Layers,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    scope.summary,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
