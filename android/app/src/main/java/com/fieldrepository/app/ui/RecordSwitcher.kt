package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.WorkshopPickerState
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.PageResponse
import com.fieldrepository.app.data.ProcessDetailDto
import com.fieldrepository.app.data.ProductDetailDto
import com.fieldrepository.app.data.ToolDetailDto
import com.fieldrepository.app.data.toolArtisanNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class RecordSwitchKind(
    val singular: String,
    val plural: String
) {
    ARTISAN("artisan", "artisans"),
    CRAFT("craft", "crafts"),
    PROCESS("process", "processes"),
    PRODUCT("product", "products"),
    TOOL("tool", "tools");

    val noun: String get() = "$plural in this workshop"

    fun nounFor(workshopId: String): String = if (workshopId.isBlank()) plural else noun
}

const val ALL_WORKSHOPS_LABEL = "All workshops"

fun recordSwitchWorkshopOptions(workshops: List<SelectOption>): List<SelectOption> =
    listOf(SelectOption("", ALL_WORKSHOPS_LABEL)) + workshops.filter { it.value.isNotBlank() }

fun recordSwitchWorkshopFor(selectedId: String, recordWorkshopId: String?, available: Collection<String>): String =
    if (selectedId.isBlank() && !recordWorkshopId.isNullOrBlank() && recordWorkshopId in available) {
        recordWorkshopId
    } else {
        selectedId
    }

const val LABEL_SEPARATOR = " · "

private fun joined(head: String?, tail: String?): String {
    val left = head?.trim().orEmpty()
    val right = tail?.trim().orEmpty()
    return when {
        left.isEmpty() -> right
        right.isEmpty() -> left
        else -> "$left$LABEL_SEPARATOR$right"
    }
}

fun recordOptionLabel(record: ArtisanDto): String =
    recordOptionLabel(RecordSwitchKind.ARTISAN, joined(record.name, record.place))

fun recordOptionLabel(record: CraftDto): String =
    recordOptionLabel(RecordSwitchKind.CRAFT, joined(record.name, record.place))

fun recordOptionLabel(record: ProcessDetailDto): String =
    recordOptionLabel(RecordSwitchKind.PROCESS, joined(record.name, record.product?.productName))

fun recordOptionLabel(record: ProductDetailDto): String =
    recordOptionLabel(RecordSwitchKind.PRODUCT, joined(record.productName, record.artisanName))

fun recordOptionLabel(record: ToolDetailDto): String =
    recordOptionLabel(RecordSwitchKind.TOOL, joined(record.toolkitName, record.toolArtisanNames()))

fun recordOptionLabel(kind: RecordSwitchKind, label: String): String =
    label.trim().ifBlank { "Untitled ${kind.singular}" }

fun recordSwitcherTitle(kind: RecordSwitchKind): String = "Open a different ${kind.singular}"

enum class RecordListState { PENDING, LOADED, UNAVAILABLE }

fun recordsAreForWorkshop(loadedForWorkshop: String?, workshopId: String): Boolean =
    loadedForWorkshop != null && loadedForWorkshop == workshopId

fun recordListMessage(
    kind: RecordSwitchKind,
    state: RecordListState,
    loadedForWorkshop: String?,
    workshopId: String,
    shown: Int
): String? = when {
    state == RecordListState.UNAVAILABLE ->
        "These ${kind.plural} could not be loaded. The record open below is unaffected."
    workshopId.isBlank() && (state == RecordListState.PENDING || loadedForWorkshop != workshopId) ->
        "Loading ${kind.plural}…"
    workshopId.isBlank() && shown == 0 -> "No ${kind.plural} yet."
    state == RecordListState.PENDING || !recordsAreForWorkshop(loadedForWorkshop, workshopId) ->
        "Loading this workshop's records…"
    shown == 0 ->
        "No ${kind.noun} yet. Pick another workshop above, or file the first one."
    else -> null
}

const val SEARCH_DEBOUNCE_MS = 350L

fun shouldSearchServer(loaded: Int, total: Int, query: String): Boolean =
    total > loaded && query.isNotBlank()

data class RecordSwitchPage(val options: List<SelectOption>, val total: Int)

suspend fun loadRecordSwitchPage(
    repository: FieldRepository,
    kind: RecordSwitchKind,
    workshopId: String,
    search: String? = null
): RecordSwitchPage = when (kind) {
    RecordSwitchKind.ARTISAN ->
        repository.artisansForWorkshopPage(workshopId, search).toSwitchPage { recordOptionLabel(it) }
    RecordSwitchKind.CRAFT ->
        repository.craftsForWorkshopPage(workshopId, search).toSwitchPage { recordOptionLabel(it) }
    RecordSwitchKind.PROCESS ->
        repository.processesForWorkshopPage(workshopId, search).toSwitchPage { recordOptionLabel(it) }
    RecordSwitchKind.PRODUCT ->
        repository.productsForWorkshopPage(workshopId, search).toSwitchPage { recordOptionLabel(it) }
    RecordSwitchKind.TOOL ->
        repository.toolsForWorkshopPage(workshopId, search).toSwitchPage { recordOptionLabel(it) }
}

private inline fun <T> PageResponse<T>.toSwitchPage(label: (T) -> String): RecordSwitchPage =
    RecordSwitchPage(items.map { SelectOption(idOf(it), label(it)) }, total)

private fun idOf(row: Any?): String = when (row) {
    is ArtisanDto -> row.id
    is CraftDto -> row.id
    is ProcessDetailDto -> row.id
    is ProductDetailDto -> row.id
    is ToolDetailDto -> row.id
    else -> throw IllegalArgumentException("RecordSwitcher cannot identify a ${row?.javaClass?.simpleName}")
}

fun mergeRecordOptions(previous: List<SelectOption>, incoming: List<SelectOption>): List<SelectOption> {
    if (incoming.isEmpty()) return previous
    val seen = previous.mapTo(HashSet()) { it.value }
    val added = incoming.filter { it.value !in seen }
    return if (added.isEmpty()) previous else previous + added
}

@Stable
class RecordSwitcherState internal constructor(
    private val repository: FieldRepository,
    private val kind: RecordSwitchKind
) {
    var options: List<SelectOption> by mutableStateOf(emptyList())
        private set

    var state: RecordListState by mutableStateOf(RecordListState.PENDING)
        private set

    var loadedForWorkshop: String? by mutableStateOf(null)
        private set

    var total: Int by mutableStateOf(0)
        private set

    var query: String by mutableStateOf("")

    val cutNotice: String?
        get() = if (state == RecordListState.LOADED) {
            listCutNotice(options.size, total, kind.nounFor(selectedWorkshopId), ListCutReach.SEARCH)
        } else {
            null
        }

    val message: String?
        get() = recordListMessage(kind, state, loadedForWorkshop, selectedWorkshopId, options.size)

    internal var selectedWorkshopId: String by mutableStateOf("")

    internal suspend fun loadWorkshop(workshopId: String) {
        selectedWorkshopId = workshopId
        state = RecordListState.PENDING
        options = emptyList()
        loadedForWorkshop = null
        total = 0
        runCatching { loadRecordSwitchPage(repository, kind, workshopId) }
            .onSuccess { page ->
                options = page.options
                total = page.total
                loadedForWorkshop = workshopId
                state = RecordListState.LOADED
            }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                state = RecordListState.UNAVAILABLE
            }
    }

    internal suspend fun searchServer(workshopId: String, term: String) {
        runCatching { loadRecordSwitchPage(repository, kind, workshopId, term) }
            .onSuccess { page ->
                if (loadedForWorkshop == workshopId) options = mergeRecordOptions(options, page.options)
            }
            .onFailure { failure -> if (failure is CancellationException) throw failure }
    }
}

@Composable
internal fun rememberRecordSwitcher(
    repository: FieldRepository,
    kind: RecordSwitchKind,
    workshop: WorkshopPickerState
): RecordSwitcherState {
    val state = remember(repository, kind) { RecordSwitcherState(repository, kind) }

    LaunchedEffect(state, workshop.selectedId) { state.loadWorkshop(workshop.selectedId) }

    LaunchedEffect(state, workshop.selectedId, state.query, state.total, state.options.size) {
        if (!shouldSearchServer(state.options.size, state.total, state.query)) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        state.searchServer(workshop.selectedId, state.query.trim())
    }

    return state
}

@Composable
internal fun RecordSwitcher(
    kind: RecordSwitchKind,
    workshop: WorkshopPickerState,
    switcher: RecordSwitcherState,
    currentId: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    currentWorkshopId: String? = null
) {
    val workshopOptions = remember(workshop.workshops) {
        recordSwitchWorkshopOptions(workshop.workshops.map { SelectOption(it.id, workshopScopeLabel(it)) })
    }
    LaunchedEffect(currentWorkshopId, workshop.workshops) {
        workshop.selectedId = recordSwitchWorkshopFor(
            workshop.selectedId, currentWorkshopId, workshop.workshops.map { it.id }
        )
    }
    val showsCurrentRecord = switcher.options.any { it.value == currentId }

    val canJumpToRecordWorkshop = currentWorkshopId != null &&
        currentWorkshopId != workshop.selectedId &&
        workshopOptions.any { it.value == currentWorkshopId }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SearchableSelectField(
            label = "Workshop",
            options = workshopOptions,
            selectedValue = workshop.selectedId,
            placeholder = ALL_WORKSHOPS_LABEL,
            includeNone = false,
            onSelect = { workshop.selectedId = it }
        )

        if (canJumpToRecordWorkshop) {
            ActionRow {
                TextButton(onClick = { workshop.selectedId = currentWorkshopId }) {
                    Text("Show this ${kind.singular}'s own workshop", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        SearchableSelectField(
            label = kind.singular.replaceFirstChar { it.uppercase() },
            options = switcher.options,
            selectedValue = if (showsCurrentRecord) currentId else "",
            placeholder = "Search ${kind.singular} records…",
            includeNone = false,
            enabled = switcher.state != RecordListState.UNAVAILABLE,
            searchable = true,
            onSearch = { switcher.query = it },
            onSelect = { picked -> if (picked.isNotBlank() && picked != currentId) onPick(picked) }
        )

        switcher.message?.let {
            Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        switcher.cutNotice?.let {
            Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
