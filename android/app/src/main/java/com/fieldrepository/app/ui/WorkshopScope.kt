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
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.WorkshopDetailDto
import com.fieldrepository.app.data.apiErrorMessage
import com.fieldrepository.app.data.occurrenceDate
import kotlinx.coroutines.CancellationException

/*
 * THE ONE WORKSHOP SCOPE CONTROL on Android, shared by every screen that draws a conclusion from
 * workshops: the questionnaire's completion matrix, the consolidated questionnaire (index and
 * document), and the map. The Kotlin counterpart of the web's `components/WorkshopScopeSelect.tsx`,
 * with the same three states, the same default and the same wire format.
 *
 * WHY IT IS ONE FILE AND NOT THREE COPIES. Those screens answer three different questions about the
 * same unit of fieldwork, and a researcher moves between them holding one thought — "what came out of
 * last week's workshop". Three copies of this control would be three defaults that can drift apart,
 * three opinions about whether "all" is a selection or an absence, and three answers to whether
 * records with no workshop are in or out. Any one of those disagreements makes two screens report
 * different numbers for the same question, with nothing on either screen to say which is right. So
 * the vocabulary lives here once and produces the SAME `workshopIds` parameter that
 * `backend/app/services/record_filters.resolve_workshop_ids` parses.
 *
 * THE THREE STATES, and why "all" is an empty set rather than every id:
 *
 *   emptySet()                  ALL workshops. Sends no parameter at all, which is what the server
 *                               reads as "do not filter". Listing every id instead would silently
 *                               exclude any workshop created since the picker loaded, and would break
 *                               the moment the list outgrew one page.
 *   setOf("w1", "w2")           Those workshops.
 *   setOf(…, UNASSIGNED_WORKSHOP)
 *                               Also (or only) records that are not linked to a workshop. Without
 *                               this, a scope of "every workshop" would quietly drop everything filed
 *                               before workshops existed, and nothing on screen would say so.
 *
 * THE DEFAULT IS THE MOST RECENT WORKSHOP, not "all". These screens are read during and just after a
 * workshop, and a matrix showing every artisan ever recorded against every interview ever taken is
 * not the question anybody opened them to ask. "All records" is one tap away and says so. The one
 * screen where that default would be WRONG passes `defaultToMostRecent = false` — see
 * [rememberWorkshopScope].
 */

/**
 * The reserved workshop id meaning "records not linked to any workshop". Byte-for-byte
 * `record_filters.UNASSIGNED_WORKSHOP` on the server and `UNASSIGNED_WORKSHOP` in the web's
 * `components/WorkshopScopeSelect.tsx` — a reserved word rather than an empty string, because an
 * empty string is what a blank field sends and "the user chose nothing" must not come to mean "show
 * me only the orphans".
 */
const val UNASSIGNED_WORKSHOP = "none"

/**
 * The selection, the loaded workshops, and the one flag a caller has to respect: [settled].
 *
 * HOLD THE FIRST REQUEST UNTIL [settled]. The default selection is only known after the workshops
 * have loaded, so a screen that fires immediately asks for the whole repository, gets an answer, and
 * then replaces it with the scoped one — two requests, and a visible flash of the wrong data. Every
 * consumer keys its `LaunchedEffect` on [settled] and [requestKey] and returns early while false.
 */
@Stable
class WorkshopScopeState internal constructor(
    private val repository: FieldRepository,
    private val defaultToMostRecent: Boolean,
    initialSelection: Set<String>
) {
    /** Every workshop this user can see, most recent occurrence first. */
    var workshops: List<WorkshopDetailDto> by mutableStateOf(emptyList())
        private set

    /** The chosen ids. Empty means every workshop; may contain [UNASSIGNED_WORKSHOP]. */
    var selected: Set<String> by mutableStateOf(initialSelection)
        private set

    /** False until the default has been applied (or there was never one to apply). */
    var settled: Boolean by mutableStateOf(!defaultToMostRecent || initialSelection.isNotEmpty())
        private set

    // The default is applied ONCE, and only if the user has not already chosen. Without this a load
    // that finished after a tap would drag the selection back to the most recent workshop under
    // somebody who had just picked two others.
    private var touched: Boolean = initialSelection.isNotEmpty()

    fun select(next: Set<String>) {
        touched = true
        selected = next
    }

    /** The "All records" shortcut: an ABSENT parameter, which the server reads as every workshop. */
    fun selectAllRecords() = select(emptySet())

    /** Back to the default. Nothing happens when no workshop has loaded — there is nothing to pick. */
    fun selectMostRecent() {
        workshops.firstOrNull()?.let { select(setOf(it.id)) }
    }

    val isAllRecords: Boolean get() = selected.isEmpty()

    /**
     * The value to hand to the repository. Sorted, so re-picking the same two workshops in the other
     * order is one scope and not two — an effect keyed on [requestKey] must not re-request for a
     * selection that means the same thing.
     */
    val workshopIds: List<String> get() = selected.sorted()

    /** A stable key for `LaunchedEffect`. */
    val requestKey: String get() = workshopIds.joinToString(",")

    /** One line naming what is in scope, for a panel subtitle. */
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
                // Rethrowing skips `settled = true`, and must: the composition that cancelled this
                // load is gone, and a state object nobody is reading has no first request to release.
                if (failure is CancellationException) throw failure
                // A picker that could not load its options must not also silence the screen behind
                // it: fall through to every workshop, which is a complete and honest answer.
                onError(failure.apiErrorMessage("The workshops could not be loaded."))
            }
        settled = true
    }
}

/**
 * Loads the workshops once and owns the selection for as long as the screen lives.
 *
 * [defaultToMostRecent] exists for the one screen where the default would be wrong: the consolidated
 * questionnaire DOCUMENT, which is the artifact a researcher cites and whose default meaning has
 * always been "everything this artisan has ever said". Silently narrowing that would change what a
 * citation means without the reader asking. Pass false there; the scope then starts at all records.
 *
 * [initialSelection] seeds the scope from a caller that already knows it — the index page handing its
 * own scope to the document, which is the Android equivalent of the web's `?workshopIds=` link. A
 * non-empty seed counts as a choice, so the most-recent default never overwrites it.
 */
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
    // Read through a snapshot holder rather than captured directly: `onError` is usually a lambda
    // rebuilt on every recomposition, and keying the effect on it would reload the workshop list —
    // and re-apply the default over the user's choice — every time the screen redrew.
    val report = rememberUpdatedState(onError)
    LaunchedEffect(state) { state.load { message -> report.value(message) } }
    return state
}

/** "Chanderi weaving · 2026-07-12" — the same workshop label every other picker in the app shows. */
fun workshopScopeLabel(workshop: WorkshopDetailDto): String {
    val title = workshop.title.ifBlank { "Untitled workshop" }
    val day = workshop.occurrenceDate().take(10)
    return if (day.isBlank()) title else "$title · $day"
}

/** One line naming what is in scope. The web's `useWorkshopScope().summary`, sentence for sentence. */
fun workshopScopeSummary(selected: Set<String>, workshops: List<WorkshopDetailDto>): String {
    if (selected.isEmpty()) return "Every workshop, and records not linked to one."
    val named = selected
        .filter { it != UNASSIGNED_WORKSHOP }
        .map { id ->
            workshops.firstOrNull { it.id == id }?.title?.trim()?.ifBlank { null }
                ?: "Untitled workshop"
        }
    if (named.isEmpty()) return "Only records not linked to a workshop."
    val list = if (named.size <= 3) named.joinToString(", ")
    else "${named.take(3).joinToString(", ")} and ${named.size - 3} more"
    val plusUnassigned =
        if (selected.contains(UNASSIGNED_WORKSHOP)) ", plus records not linked to a workshop" else ""
    return "$list$plusUnassigned."
}

/**
 * The control: a multi-select over the workshops, an "All records" shortcut, a way back to the
 * default, and a line of text saying what is currently in scope.
 *
 * [SearchableMultiSelectField] is reused rather than reimplemented — it already searches past eight
 * options, offers a filter-aware "Select all", holds a draft until Done, and is what every other
 * multi-select in the app looks like.
 *
 * [label] is the one thing each screen says differently, because each is scoping a different artifact:
 * "Workshops on this map", "Workshops in this matrix", "Workshops to read", "Workshops in this
 * document". Those four strings are the web's, byte for byte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkshopScopeSelect(
    scope: WorkshopScopeState,
    label: String = "Workshops",
    modifier: Modifier = Modifier,
    /** Off where the panel around it already carries the sentence. */
    showSummary: Boolean = true
) {
    val options = remember(scope.workshops) {
        scope.workshops.map { SelectOption(it.id, workshopScopeLabel(it)) } +
            // Last, and named as what it is. It is not a workshop, so it does not belong among them
            // in the reading order — but it has to be selectable, or a scope of "every workshop"
            // silently drops every record filed before workshops existed.
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
                label = { Text("All records", fontSize = 12.sp) }
            )
            if (!all && scope.workshops.isNotEmpty()) {
                TextButton(onClick = { scope.selectMostRecent() }) {
                    Text("Most recent only", fontSize = 12.sp)
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
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
