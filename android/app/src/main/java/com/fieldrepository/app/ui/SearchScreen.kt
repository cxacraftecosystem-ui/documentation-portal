package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.fieldrepository.app.questionnaire.ui.rememberKept
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.occurrenceDate
import com.fieldrepository.app.data.workshopDisplayTitle
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.SearchResultsDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height

object SearchRecordTypes {
    const val ARTISAN = "artisan"
    const val WORKSHOP = "workshop"
    const val PRODUCT = "product"
    const val TOOL = "tool"
    const val MEDIA = "media"

    const val CRAFT = "craft"
    const val PROCESS = "process"
    const val QUESTIONNAIRE = "questionnaire"

    val ALL: List<String> = listOf(ARTISAN, WORKSHOP, PRODUCT, TOOL, MEDIA)

    fun label(recordType: String): String = when (recordType) {
        ARTISAN -> "Artisans"
        WORKSHOP -> "Workshops"
        PRODUCT -> "Products"
        TOOL -> "Tools"
        MEDIA -> "Media"
        else -> com.fieldrepository.app.explorer.ui.folderKind(recordType) ?: recordType
    }

    fun bucket(recordType: String): String = when (recordType) {
        ARTISAN -> "artisans"
        WORKSHOP -> "workshops"
        PRODUCT -> "products"
        TOOL -> "tools"
        MEDIA -> "media"
        else -> recordType
    }
}

enum class SearchRange(val label: String) {
    ANY("Any time"),
    TODAY("Today"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    LAST_90_DAYS("Last 90 days"),
    THIS_MONTH("This month"),
    THIS_YEAR("This year"),
    CUSTOM("Custom range")
}

const val SEARCH_PAGE_SIZE = 20

const val SEARCH_DEBOUNCE_MILLIS = 350L

@Immutable
data class SearchFilters(
    val query: String = "",
    val place: String = "",
    val types: Set<String> = emptySet(),
    val range: SearchRange = SearchRange.ANY,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val craftId: String = "",
    val artisanId: String = "",
    val mediaType: String = "",
    val explorer: Map<String, String> = emptyMap()
) {
    val typed: Pair<String, String>
        get() = query to place

    val sheetFilterCount: Int
        get() = listOf(
            place.isNotBlank(),
            range != SearchRange.ANY,
            craftId.isNotBlank(),
            artisanId.isNotBlank(),
            mediaType.isNotBlank()
        ).count { it }

    val hasFilters: Boolean
        get() = types.isNotEmpty() || sheetFilterCount > 0

    val isEmpty: Boolean
        get() = query.isBlank() && !hasFilters

    fun includes(recordType: String): Boolean = types.isEmpty() || recordType in types

    fun bucketTypes(): List<String>? = SearchRecordTypes.ALL
        .filter { it in types }
        .map(SearchRecordTypes::bucket)
        .takeIf { it.isNotEmpty() }

    fun resolveDateRange(today: LocalDate = LocalDate.now()): Pair<String?, String?> {
        val endOfToday = endOfDay(today)
        return when (range) {
            SearchRange.ANY -> null to null
            SearchRange.TODAY -> startOfDay(today) to endOfToday
            SearchRange.LAST_7_DAYS -> startOfDay(today.minusDays(6)) to endOfToday
            SearchRange.LAST_30_DAYS -> startOfDay(today.minusDays(29)) to endOfToday
            SearchRange.LAST_90_DAYS -> startOfDay(today.minusDays(89)) to endOfToday
            SearchRange.THIS_MONTH -> startOfDay(today.withDayOfMonth(1)) to endOfToday
            SearchRange.THIS_YEAR -> startOfDay(today.withDayOfYear(1)) to endOfToday
            SearchRange.CUSTOM -> from?.let(::startOfDay) to to?.let(::endOfDay)
        }
    }
}

private fun startOfDay(date: LocalDate): String =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toString()

private fun endOfDay(date: LocalDate): String =
    date.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toString()

private val SEARCH_MEDIA_TYPES = listOf("IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER")

@Composable
fun SearchScreen(
    repository: FieldRepository,
    onOpenRecord: (recordType: String, recordId: String) -> Unit,
    onBack: () -> Unit,
    showBackAction: Boolean = false,
    initialRecordType: String? = null,
    modifier: Modifier = Modifier
) {
    val seed = remember(initialRecordType) { SearchFilters(types = setOfNotNull(initialRecordType)) }
    val kept = "search.${initialRecordType.orEmpty()}"
    var filters by rememberKept("$kept.filters") { seed }
    var applied by rememberKept("$kept.applied") { seed }
    var page by rememberKept("$kept.page") { 1 }
    var runCount by remember { mutableStateOf(0) }
    var browseAll by rememberKept("$kept.browseAll") { initialRecordType != null }

    var results by rememberKept("$kept.results") { null as SearchResultsDto? }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    val craftListContext = LocalContext.current.applicationContext

    LaunchedEffect(Unit) {
        runCatching { repository.artisans() }.onSuccess { artisans = it }
    }

    LaunchedEffect(filters) {
        if (filters == applied) return@LaunchedEffect
        if (filters.typed != applied.typed) delay(SEARCH_DEBOUNCE_MILLIS)
        applied = filters
        page = 1
    }

    LaunchedEffect(applied, page, browseAll, runCount) {
        if (applied.isEmpty && !browseAll) {
            results = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            val (dateFrom, dateTo) = applied.resolveDateRange()
            results = repository.search(
                q = applied.query.trim().ifBlank { null },
                craftId = applied.craftId.ifBlank { null },
                place = applied.place.trim().ifBlank { null },
                artisanId = applied.artisanId.ifBlank { null },
                mediaType = applied.mediaType.ifBlank { null },
                types = applied.bucketTypes(),
                dateFrom = dateFrom,
                dateTo = dateTo,
                page = page,
                pageSize = SEARCH_PAGE_SIZE
            )
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.apiErrorMessage("Search failed")
        }
        loading = false
    }

    fun runNow() {
        if (filters.isEmpty) browseAll = true
        applied = filters
        page = 1
        runCount += 1
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showBackAction) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        SearchCard(title = "Search", icon = Icons.Filled.Search) {
            Text(
                "Artisans, workshops, products, tools and media.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = filters.query,
                onValueChange = { filters = filters.copy(query = it) },
                label = { Text("Search records") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (filters.query.isNotBlank()) {
                        IconButton(onClick = { filters = filters.copy(query = "") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            SearchFilterBar(
                value = filters,
                onChange = { filters = it },
                extraFilters = {
                    CraftPickerField(
                        label = "Craft",
                        selectedIds = listOfNotNull(filters.craftId.ifBlank { null }),
                        placeholder = "Any craft",
                        refresh = { repository.craftCatalogue(craftListContext) },
                    ) { ids -> filters = filters.copy(craftId = ids.firstOrNull().orEmpty()) }
                    if (artisans.isNotEmpty()) {
                        SearchDropdownField(
                            label = "Artisan",
                            options = artisans.map { artisan ->
                                artisan.id to listOf(artisan.name, artisan.place)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                            },
                            selectedValue = filters.artisanId,
                            placeholder = "Any artisan",
                            onSelect = { filters = filters.copy(artisanId = it) }
                        )
                    }
                    SearchDropdownField(
                        label = "Media type",
                        options = SEARCH_MEDIA_TYPES.map { it to it },
                        selectedValue = filters.mediaType,
                        placeholder = "Any media type",
                        onSelect = { filters = filters.copy(mediaType = it) }
                    )
                    Text(
                        "Craft, artisan and media type filter only the types that have them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            Button(onClick = { runNow() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "Searching…" else "Search")
            }

            error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        val data = results
        when {
            data == null && loading -> SearchCard(title = "Searching…") {
                Text(
                    if (applied.types.isEmpty()) {
                        "All record types."
                    } else {
                        "${applied.types.size} of 5 record types."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            data == null -> SearchCard(title = "Nothing searched yet") {
                Text(
                    "Search a name, place or filename. Search with nothing entered to see the latest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                val allBuckets = remember(data) { data.toSearchBuckets() }
                val buckets = remember(allBuckets, applied) {
                    allBuckets.filter { applied.includes(it.recordType) }
                }
                val shown = buckets.sumOf { it.rows.size }
                val matched = buckets.sumOf { it.total }
                val hasMore = if (data.totalsReported()) {
                    val selectedMax = buckets.maxOfOrNull { it.total } ?: 0
                    val pageCount = maxOf(1, (selectedMax + SEARCH_PAGE_SIZE - 1) / SEARCH_PAGE_SIZE)
                    page < pageCount
                } else {
                    buckets.any { it.rows.size == SEARCH_PAGE_SIZE }
                }

                SearchCard(title = "Results") {
                    Text(
                        if (data.totalsReported()) {
                            val scope = if (applied.types.isEmpty()) {
                                "across every record type"
                            } else {
                                "in the selected record types"
                            }
                            "$matched match${if (matched == 1) "" else "es"} $scope."
                        } else {
                            "$shown result${if (shown == 1) "" else "s"} on this page."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (shown == 0) {
                        HorizontalDivider(color = MaterialTheme.field.hairline)
                        Text(
                            if (page > 1) "No more results" else "No matching records",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (page > 1) {
                            Text(
                                "Go back for earlier results.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                buckets.filter { it.rows.isNotEmpty() }.forEach { bucket ->
                    SearchBucketSection(bucket = bucket, onOpenRecord = onOpenRecord)
                }

                SearchPager(
                    page = page,
                    shown = shown,
                    hasMore = hasMore,
                    loading = loading,
                    onPage = { page = it }
                )
            }
        }
    }
}

internal data class SearchRow(
    val recordType: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val date: String?
)

internal data class SearchBucket(
    val recordType: String,
    val title: String,
    val total: Int,
    val rows: List<SearchRow>
)

private fun SearchResultsDto.totalsReported(): Boolean =
    total > 0 || totals.artisans > 0 || totals.workshops > 0 || totals.products > 0 ||
        totals.tools > 0 || totals.media > 0

internal fun SearchResultsDto.toSearchBuckets(): List<SearchBucket> {
    val reported = totalsReported()
    fun total(counted: Int, rows: Int) = if (reported) counted else rows

    val artisanRows = artisans.map { item ->
        SearchRow(
            recordType = SearchRecordTypes.ARTISAN,
            id = item.id,
            title = item.name.ifBlank { "Unnamed artisan" },
            subtitle = listOfNotNull(
                item.place.takeIf { it.isNotBlank() },
                item.craft?.name?.takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            status = item.status,
            date = item.createdAt
        )
    }
    val workshopRows = workshops.map { item ->
        SearchRow(
            recordType = SearchRecordTypes.WORKSHOP,
            id = item.id,
            title = workshopDisplayTitle(item.title, date = item.occurrenceDate(), place = item.place),
            subtitle = item.place,
            status = item.status,
            date = item.startDate ?: item.date
        )
    }
    val productRows = products.map { item ->
        SearchRow(
            recordType = SearchRecordTypes.PRODUCT,
            id = item.id,
            title = item.productName.ifBlank { "Untitled product" },
            subtitle = listOf(item.craftName, item.artisanName, item.place)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            status = item.status,
            date = item.createdAt
        )
    }
    val toolRows = tools.map { item ->
        SearchRow(
            recordType = SearchRecordTypes.TOOL,
            id = item.id,
            title = item.toolkitName.ifBlank { "Untitled toolkit" },
            subtitle = listOf(item.craftName, item.artisanName, item.place)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            status = item.status,
            date = item.createdAt
        )
    }
    val mediaRows = media.map { item ->
        SearchRow(
            recordType = SearchRecordTypes.MEDIA,
            id = item.id,
            title = item.caption?.trim()?.takeIf { it.isNotEmpty() } ?: item.originalFilename,
            subtitle = listOfNotNull(item.mediaType.takeIf { it.isNotBlank() }, item.mimeType?.takeIf { it.isNotBlank() })
                .joinToString(" · "),
            status = "",
            date = item.createdAt
        )
    }

    return listOf(
        SearchBucket(SearchRecordTypes.ARTISAN, "Artisans", total(totals.artisans, artisanRows.size), artisanRows),
        SearchBucket(SearchRecordTypes.WORKSHOP, "Workshops", total(totals.workshops, workshopRows.size), workshopRows),
        SearchBucket(SearchRecordTypes.PRODUCT, "Products", total(totals.products, productRows.size), productRows),
        SearchBucket(SearchRecordTypes.TOOL, "Tools", total(totals.tools, toolRows.size), toolRows),
        SearchBucket(SearchRecordTypes.MEDIA, "Media", total(totals.media, mediaRows.size), mediaRows)
    )
}

internal const val QUICK_SEARCH_LIMIT = 8

internal const val QUICK_SEARCH_MIN_CHARS = 2

internal fun quickSearchRuns(filters: SearchFilters, browse: Boolean): Boolean =
    browse || filters.query.trim().length >= QUICK_SEARCH_MIN_CHARS || filters.hasFilters

@Stable
internal class QuickSearchState(private val repository: FieldRepository) {
    var hits by mutableStateOf<List<SearchRow>>(emptyList())
        private set

    var total by mutableStateOf(0)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var searched by mutableStateOf(false)
        private set

    private var scheduledTyped: Pair<String, String> = SearchFilters().typed

    fun awaitsTyping(typed: Pair<String, String>): Boolean {
        val changed = scheduledTyped != typed
        scheduledTyped = typed
        return changed
    }

    fun reset() {
        hits = emptyList()
        total = 0
        loading = false
        error = null
        searched = false
        scheduledTyped = SearchFilters().typed
    }

    suspend fun run(filters: SearchFilters, limit: Int) {
        loading = true
        try {
            val (dateFrom, dateTo) = filters.resolveDateRange()
            val results = repository.search(
                q = filters.query.trim().ifBlank { null },
                craftId = filters.craftId.ifBlank { null },
                place = filters.place.trim().ifBlank { null },
                artisanId = filters.artisanId.ifBlank { null },
                mediaType = filters.mediaType.ifBlank { null },
                types = filters.bucketTypes(),
                dateFrom = dateFrom,
                dateTo = dateTo,
                page = 1,
                pageSize = limit,
                filters = filters.explorer
            )
            hits = results.toQuickHits(filters, limit)
            total = results.selectedTotal(filters)
            error = null
            searched = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.apiErrorMessage("Search failed")
        }
        loading = false
    }
}

@Composable
internal fun rememberQuickSearch(
    repository: FieldRepository,
    filters: SearchFilters,
    limit: Int = QUICK_SEARCH_LIMIT,
    browse: Boolean = false
): QuickSearchState {
    val state = remember(repository) { QuickSearchState(repository) }
    val request = remember(filters) { filters.copy(query = filters.query.trim(), place = filters.place.trim()) }
    LaunchedEffect(state, request, limit, browse) {
        if (!quickSearchRuns(request, browse)) {
            state.reset()
            return@LaunchedEffect
        }
        if (state.awaitsTyping(request.typed)) delay(SEARCH_DEBOUNCE_MILLIS)
        state.run(request, limit)
    }
    return state
}

private fun SearchResultsDto.toQuickHits(filters: SearchFilters, limit: Int): List<SearchRow> {
    val buckets = toSearchBuckets()
        .filter { filters.includes(it.recordType) }
        .map { it.rows }
        .filter { it.isNotEmpty() }
    val hits = mutableListOf<SearchRow>()
    var index = 0
    while (hits.size < limit && buckets.any { index < it.size }) {
        for (rows in buckets) {
            if (hits.size == limit) break
            rows.getOrNull(index)?.let { hits += it }
        }
        index++
    }
    return hits
}

private fun SearchResultsDto.selectedTotal(filters: SearchFilters): Int =
    toSearchBuckets().filter { filters.includes(it.recordType) }.sumOf { it.total }

@Composable
private fun SearchBucketSection(bucket: SearchBucket, onOpenRecord: (String, String) -> Unit) {
    SearchCard(title = bucket.title, trailing = {
        SearchCountPill(
            text = if (bucket.total > bucket.rows.size) "${bucket.rows.size} of ${bucket.total}" else "${bucket.total}",
            emphasised = true
        )
    }) {
        bucket.rows.forEach { row ->
            SearchResultRow(row = row, onOpen = { onOpenRecord(row.recordType, row.id) })
        }
    }
}

@Composable
internal fun SearchResultRow(row: SearchRow, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClickLabel = "Open ${row.title}", onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Open ›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (row.subtitle.isNotBlank()) {
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (row.status.isNotBlank()) SearchStatusBadge(row.status)
            Text(
                formatSearchDateTime(row.date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchPager(page: Int, shown: Int, hasMore: Boolean, loading: Boolean, onPage: (Int) -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Page $page · $shown result${if (shown == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ActionRow {
                OutlinedButton(
                    onClick = { onPage(page - 1) },
                    enabled = !loading && page > 1,
                ) { Text("Previous") }
                OutlinedButton(
                    onClick = { onPage(page + 1) },
                    enabled = !loading && hasMore,
                ) { Text("Next") }
            }
        }
    }
}

private enum class ChipTone { ON, PART, OFF }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SearchFilterBar(
    value: SearchFilters,
    onChange: (SearchFilters) -> Unit,
    modifier: Modifier = Modifier,
    extraFilters: (@Composable ColumnScope.() -> Unit)? = null,
    showSheet: Boolean = true
) {
    var open by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hidden = value.sheetFilterCount

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) open = false }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchChip(
                text = "Everything",
                tone = if (value.types.isEmpty()) ChipTone.ON else ChipTone.OFF,
                onClick = { onChange(value.copy(types = emptySet())) }
            )
            SearchRecordTypes.ALL.forEach { recordType ->
                SearchChip(
                    text = SearchRecordTypes.label(recordType),
                    tone = when {
                        value.types.size == 1 && recordType in value.types -> ChipTone.ON
                        recordType in value.types -> ChipTone.PART
                        else -> ChipTone.OFF
                    },
                    onClick = { onChange(value.copy(types = setOf(recordType))) }
                )
            }
            if (showSheet) SearchChip(
                text = if (hidden > 0) "Filters · $hidden" else "Filters",
                tone = if (open || hidden > 0) ChipTone.PART else ChipTone.OFF,
                icon = Icons.Filled.FilterList,
                onClick = { open = true }
            )
        }

        if (value.types.size > 1) {
            Text(
                "Searching ${value.types.size} record types.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (open && showSheet) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Filters",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    value = value.place,
                    onValueChange = { onChange(value.copy(place = it)) },
                    label = { Text("Place") },
                    placeholder = { Text("Any place") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                SearchDropdownField(
                    label = "Record time",
                    options = SearchRange.entries.map { it.name to it.label },
                    selectedValue = value.range.name,
                    placeholder = SearchRange.ANY.label,
                    allowNone = false,
                    onSelect = { picked -> onChange(value.copy(range = SearchRange.valueOf(picked))) }
                )

                if (value.range == SearchRange.CUSTOM) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        CalendarField(
                            label = "From",
                            value = value.from,
                            onValueChange = { onChange(value.copy(from = it)) },
                            maximum = value.to,
                            placeholder = "Any date",
                            clearable = true
                        )
                        CalendarField(
                            label = "To",
                            value = value.to,
                            onValueChange = { onChange(value.copy(to = it)) },
                            minimum = value.from,
                            placeholder = "Any date",
                            clearable = true
                        )
                    }
                }

                SearchableMultiSelectField(
                    label = "Record types",
                    options = SearchRecordTypes.ALL.map { SelectOption(it, SearchRecordTypes.label(it)) },
                    selected = value.types,
                    placeholder = "Everything",
                    onSelectedChange = { next ->
                        onChange(value.copy(types = SearchRecordTypes.ALL.filter { it in next }.toSet()))
                    }
                )
                Text(
                    "Nothing picked searches everything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                extraFilters?.invoke(this)

                ActionRow {
                    if (value.hasFilters) {
                        TextButton(onClick = { onChange(SearchFilters(query = value.query)) }) {
                            Text("Clear all filters")
                        }
                    }
                    Button(onClick = { close() }) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun SearchChip(text: String, tone: ChipTone, onClick: () -> Unit, icon: ImageVector? = null) {
    val background = when (tone) {
        ChipTone.ON -> MaterialTheme.colorScheme.primary
        ChipTone.PART -> MaterialTheme.colorScheme.primaryContainer
        ChipTone.OFF -> MaterialTheme.colorScheme.surface
    }
    val foreground = when (tone) {
        ChipTone.ON -> MaterialTheme.colorScheme.onPrimary
        ChipTone.PART -> MaterialTheme.colorScheme.onPrimaryContainer
        ChipTone.OFF -> MaterialTheme.field.body
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .height(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(background, CircleShape)
            .then(
                if (tone == ChipTone.OFF) Modifier.border(1.dp, MaterialTheme.field.hairline, CircleShape) else Modifier
            )
            .padding(horizontal = 12.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(16.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = foreground,
            maxLines = 1
        )
    }
}

@Composable
internal fun SearchCard(
    title: String,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun SearchCountPill(text: String, emphasised: Boolean) {
    val background = if (emphasised) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.field.surface100
    val foreground = if (emphasised) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.field.placeholder
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        maxLines = 1,
        modifier = Modifier
            .background(background, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun SearchStatusBadge(status: String) {
    val key = status.uppercase(Locale.ROOT)
    val background: Color
    val foreground: Color
    when (key) {
        "APPROVED" -> {
            background = MaterialTheme.field.successContainer
            foreground = MaterialTheme.field.onSuccessContainer
        }
        "PENDING" -> {
            background = MaterialTheme.field.warningContainer
            foreground = MaterialTheme.field.onWarningContainer
        }
        "REJECTED" -> {
            background = MaterialTheme.colorScheme.errorContainer
            foreground = MaterialTheme.colorScheme.onErrorContainer
        }
        "NEEDS_REVISION" -> {
            background = MaterialTheme.colorScheme.primaryContainer
            foreground = MaterialTheme.colorScheme.onPrimaryContainer
        }
        else -> {
            background = MaterialTheme.field.surface100
            foreground = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Text(
        searchStatusLabel(key),
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        maxLines = 1,
        modifier = Modifier
            .background(background, CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

private fun searchStatusLabel(status: String): String = when (status) {
    "DRAFT" -> "Draft"
    "PENDING" -> "Pending"
    "APPROVED" -> "Approved"
    "REJECTED" -> "Rejected"
    "NEEDS_REVISION" -> "Needs revision"
    else -> status.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
}

@Composable
private fun SearchDropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    placeholder: String,
    onSelect: (String) -> Unit,
    allowNone: Boolean = true
) {
    SearchableSelectField(
        label = label,
        options = remember(options) { options.asSelectOptions() },
        selectedValue = selectedValue,
        placeholder = placeholder,
        includeNone = allowNone,
        onSelect = onSelect
    )
}

private val searchDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.getDefault())

private fun formatSearchDateTime(value: String?): String {
    if (value.isNullOrBlank()) return "-"
    val instant = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
        ?: return "-"
    return runCatching { searchDateTimeFormatter.format(instant.atZone(ZoneId.systemDefault())) }.getOrElse { "-" }
}
