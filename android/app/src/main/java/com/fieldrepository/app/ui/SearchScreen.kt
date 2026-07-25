package com.fieldrepository.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.SearchResultsDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Global cross-record search — the Android mirror of the web `/search` page.
 *
 * `GET /search` runs ONE shared skip/take across five independent buckets (artisans, workshops,
 * products, tools, media), so a page can be full in one bucket and empty in another. That single
 * fact drives most of the decisions below: one pager for everything, per-bucket totals rather than
 * per-bucket pagers, and a footer that says out loud that the buckets page together.
 *
 * Open to any authenticated user; the API already filters every row down to what the caller may see.
 */

// ---------------------------------------------------------------------------------------------
// Public contract
// ---------------------------------------------------------------------------------------------

/**
 * The `recordType` values [SearchScreen] hands to `onOpenRecord`, one per bucket the API returns.
 * Lower-case to match the backend's own record-type vocabulary (`artisan`, `product`, `tool`, …);
 * `workshop` is not in that list server-side but is a real bucket here, so it is named the same way.
 */
object SearchRecordTypes {
    const val ARTISAN = "artisan"
    const val WORKSHOP = "workshop"
    const val PRODUCT = "product"
    const val TOOL = "tool"
    const val MEDIA = "media"
}

/**
 * Rows per bucket per page. `GET /search` caps pageSize at 50 and applies one shared skip/take to
 * all five buckets, so this is the page size of every bucket at once. 20 matches the web page.
 */
const val SEARCH_PAGE_SIZE = 20

/** How long the inputs must settle before a query is sent. One request per typed word, not per key. */
const val SEARCH_DEBOUNCE_MILLIS = 350L

/**
 * Everything `GET /search` filters on. Held as one value so the debounce can compare "what is typed"
 * against "what was last searched" in a single equality check, and so paging reads a frozen snapshot
 * instead of drifting with a half-typed box.
 */
@Immutable
data class SearchFilters(
    val query: String = "",
    val place: String = "",
    val craftId: String = "",
    val artisanId: String = "",
    val mediaType: String = "",
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null
) {
    /** No filter at all — searching this would list the whole repository, so it needs an explicit ask. */
    val isEmpty: Boolean
        get() = query.isBlank() && place.isBlank() && craftId.isBlank() && artisanId.isBlank() &&
            mediaType.isBlank() && dateFrom == null && dateTo == null

    /** Filters set BESIDES the free-text box — what the "More filters" toggle advertises. */
    val activeFilterCount: Int
        get() = listOf(
            place.isNotBlank(),
            craftId.isNotBlank(),
            artisanId.isNotBlank(),
            mediaType.isNotBlank(),
            dateFrom != null,
            dateTo != null
        ).count { it }
}

/** Media types the API accepts for `mediaType`, in the backend enum's own order. */
private val SEARCH_MEDIA_TYPES = listOf("IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER")

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

/**
 * Search across every record type at once.
 *
 * @param onOpenRecord called with a [SearchRecordTypes] value and the record's id when a result is
 *   tapped; the host routes that into the existing detail/edit screen for that type.
 * @param onBack invoked by the back control — only rendered when [showBackAction] is true, because
 *   the app's own chrome already draws a Back pill above every non-dashboard screen. Host this
 *   screen outside that chrome and pass `showBackAction = true`.
 */
@Composable
fun SearchScreen(
    repository: FieldRepository,
    onOpenRecord: (recordType: String, recordId: String) -> Unit,
    onBack: () -> Unit,
    showBackAction: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Live inputs.
    var filters by remember { mutableStateOf(SearchFilters()) }
    // The filters the CURRENT results belong to. The pager walks these, never `filters`.
    var applied by remember { mutableStateOf(SearchFilters()) }
    var page by remember { mutableStateOf(1) }
    // Bumped by the Search button so pressing it re-runs an identical query (same filters, same page).
    var runCount by remember { mutableStateOf(0) }
    // Set once the researcher explicitly asks for an unfiltered listing.
    var browseAll by remember { mutableStateOf(false) }

    var results by remember { mutableStateOf<SearchResultsDto?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }

    var crafts by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }

    // Craft/artisan pickers are a convenience, not a dependency: if either lookup fails the picker
    // simply stays hidden and the text, media-type and date filters still work.
    LaunchedEffect(Unit) {
        runCatching { repository.crafts() }.onSuccess { crafts = it }
        runCatching { repository.artisans() }.onSuccess { artisans = it }
    }

    // Debounce. Editing any input restarts this effect, so the query is only promoted to `applied`
    // once the inputs have been still for SEARCH_DEBOUNCE_MILLIS.
    LaunchedEffect(filters) {
        if (filters == applied) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MILLIS)
        applied = filters
        page = 1
    }

    // The one place a request is made. Re-keying cancels the in-flight call, so a stale response can
    // never overwrite a newer one.
    LaunchedEffect(applied, page, browseAll, runCount) {
        if (applied.isEmpty && !browseAll) {
            results = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        runCatching {
            repository.search(
                q = applied.query.trim().ifBlank { null },
                craftId = applied.craftId.ifBlank { null },
                place = applied.place.trim().ifBlank { null },
                artisanId = applied.artisanId.ifBlank { null },
                mediaType = applied.mediaType.ifBlank { null },
                // A picked day means the WHOLE day: `dateFrom` opens it and `dateTo` closes it at
                // 23:59:59, because the API compares against createdAt with gte/lte — a bare
                // start-of-day `lte` would drop every record made on the chosen end day.
                dateFrom = applied.dateFrom?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toString(),
                dateTo = applied.dateTo?.atTime(23, 59, 59)?.atZone(ZoneId.systemDefault())?.toInstant()?.toString(),
                page = page,
                pageSize = SEARCH_PAGE_SIZE
            )
        }
            .onSuccess { results = it; error = null }
            .onFailure { error = it.apiErrorMessage("Search failed") }
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
            OutlinedButton(
                onClick = onBack,
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Back")
            }
        }

        SearchCard(title = "Search", icon = Icons.Filled.Search) {
            Text(
                "Search across artisans, workshops, products, tools and media with shared API filters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = filters.query,
                onValueChange = { filters = filters.copy(query = it) },
                label = { Text("Search repository") },
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

            OutlinedTextField(
                value = filters.place,
                onValueChange = { filters = filters.copy(place = it) },
                label = { Text("Place filter") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = { showFilters = !showFilters }) {
                    Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (showFilters) "Hide filters"
                        else if (filters.activeFilterCount > 0) "More filters (${filters.activeFilterCount})"
                        else "More filters"
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!filters.isEmpty) {
                    TextButton(onClick = { filters = SearchFilters() }) { Text("Clear filters") }
                }
            }

            if (showFilters) {
                if (crafts.isNotEmpty()) {
                    SearchDropdownField(
                        label = "Craft",
                        options = crafts.map { craft ->
                            craft.id to listOfNotNull(
                                craft.name.ifBlank { "Untitled craft" },
                                craft.place?.takeIf { it.isNotBlank() }
                            ).joinToString(" · ")
                        },
                        selectedValue = filters.craftId,
                        placeholder = "Any craft",
                        onSelect = { filters = filters.copy(craftId = it) }
                    )
                }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SearchDateField(
                        label = "From date",
                        value = filters.dateFrom,
                        onChange = { filters = filters.copy(dateFrom = it) },
                        modifier = Modifier.weight(1f)
                    )
                    SearchDateField(
                        label = "To date",
                        value = filters.dateTo,
                        onChange = { filters = filters.copy(dateTo = it) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Craft, artisan and media type narrow only the buckets that carry them; the date " +
                        "range matches when a record was documented.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                    "Looking across all five record types.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            data == null -> SearchCard(title = "Nothing searched yet") {
                Text(
                    "Type what you are looking for — a name, a place, a filename — and results appear as " +
                        "you pause. Press Search on an empty form to list the most recent records instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                val buckets = remember(data) { data.toSearchBuckets() }
                val shown = buckets.sumOf { it.rows.size }
                // The API reports `pageCount` (the last page of its LONGEST bucket), so Next is exact.
                // The fallback — "some bucket came back full" — keeps this working against an API
                // that predates those keys; it can walk one page too far when a bucket's total is an
                // exact multiple of the page size, which is precisely why the server-side count wins.
                val hasMore = if (data.totalsReported()) {
                    page < data.pageCount
                } else {
                    buckets.any { it.rows.size == SEARCH_PAGE_SIZE }
                }

                SearchCard(title = "Results") {
                    Text(
                        if (data.totalsReported()) {
                            "${data.total} match${if (data.total == 1) "" else "es"} across every record type."
                        } else {
                            "$shown result${if (shown == 1) "" else "s"} on this page."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        buckets.forEach { bucket ->
                            SearchCountPill(
                                text = "${bucket.title} ${bucket.total}",
                                emphasised = bucket.total > 0
                            )
                        }
                    }

                    if (shown == 0) {
                        HorizontalDivider(color = MaterialTheme.field.hairline)
                        Text(
                            if (page > 1) "No more results" else "No matching records",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (page > 1) {
                            Text(
                                "Every result type has run out on this page. Go back to see the earlier matches.",
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

// ---------------------------------------------------------------------------------------------
// Result model
// ---------------------------------------------------------------------------------------------

/**
 * One tappable result. Deliberately carries no id in anything it RENDERS — [id] exists only to hand
 * back to `onOpenRecord`; the design system never shows an internal id to a researcher.
 */
private data class SearchRow(
    val recordType: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val date: String?
)

/** A result type with its page of rows and how many matches it has in total. */
private data class SearchBucket(
    val title: String,
    val total: Int,
    val rows: List<SearchRow>
)

/**
 * True when the response carries the per-bucket counts. `totals`/`total` default to zero in the DTO,
 * so "all zero while rows came back" is how an older API that never sent them looks — and the only
 * case where the page has to fall back to guessing at a next page.
 */
private fun SearchResultsDto.totalsReported(): Boolean =
    total > 0 || totals.artisans > 0 || totals.workshops > 0 || totals.products > 0 ||
        totals.tools > 0 || totals.media > 0

private fun SearchResultsDto.toSearchBuckets(): List<SearchBucket> {
    val reported = totalsReported()
    fun total(counted: Int, rows: Int) = if (reported) counted else rows

    val artisanRows = artisans.map { item ->
        SearchRow(
            recordType = SearchRecordTypes.ARTISAN,
            id = item.id,
            title = item.name.ifBlank { "Unnamed artisan" },
            // The craft relation is not expanded by /search, so it is shown only when it is there —
            // printing "No craft" for every row would state something the response never claimed.
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
            title = item.title.ifBlank { "Untitled workshop" },
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
            // A media file carries no review status of its own in this payload; the badge is skipped.
            status = "",
            date = item.createdAt
        )
    }

    return listOf(
        SearchBucket("Artisans", total(totals.artisans, artisanRows.size), artisanRows),
        SearchBucket("Workshops", total(totals.workshops, workshopRows.size), workshopRows),
        SearchBucket("Products", total(totals.products, productRows.size), productRows),
        SearchBucket("Tools", total(totals.tools, toolRows.size), toolRows),
        SearchBucket("Media", total(totals.media, mediaRows.size), mediaRows)
    )
}

// ---------------------------------------------------------------------------------------------
// Result rendering
// ---------------------------------------------------------------------------------------------

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
private fun SearchResultRow(row: SearchRow, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.medium)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
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

/**
 * Prev/Next footer. It states only what the contract really knows: which page this is, how many rows
 * it holds, and that all five result types page together — the buckets do not have pagers of their own.
 */
@Composable
private fun SearchPager(page: Int, shown: Int, hasMore: Boolean, loading: Boolean, onPage: (Int) -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Page $page · $shown result${if (shown == 1) "" else "s"} on this page · every result type pages together",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onPage(page - 1) },
                    enabled = !loading && page > 1,
                    modifier = Modifier.weight(1f)
                ) { Text("Previous") }
                OutlinedButton(
                    onClick = { onPage(page + 1) },
                    enabled = !loading && hasMore,
                    modifier = Modifier.weight(1f)
                ) { Text("Next") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Local widgets
//
// Deliberate local copies of shapes that live inside MainActivity.kt (RecordCard, DropdownField,
// DatePickerField): that file is 10k lines and owned by one agent, so this screen restates the few
// pieces it needs rather than forcing an import out of it.
// ---------------------------------------------------------------------------------------------

@Composable
private fun SearchCard(
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

/** Review status, worded exactly as the web StatusBadge words it. */
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
    // SOME_STATUS -> "Some status", the same fallback the web badge uses.
    else -> status.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
}

@Composable
private fun SearchDropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    placeholder: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedValue }?.second
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    selectedLabel ?: placeholder,
                    color = if (selectedLabel != null) MaterialTheme.field.body else MaterialTheme.field.placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                DropdownMenuItem(
                    text = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingIcon = { if (selectedValue.isBlank()) Text("✓", color = MaterialTheme.colorScheme.primary) },
                    onClick = { onSelect(""); expanded = false }
                )
                options.forEach { (value, text) ->
                    val isSelected = value == selectedValue
                    DropdownMenuItem(
                        text = {
                            Text(
                                text,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.field.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = { if (isSelected) Text("✓", color = MaterialTheme.colorScheme.primary) },
                        onClick = { onSelect(value); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchDateField(
    label: String,
    value: LocalDate?,
    onChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            onClick = {
                val initial = value ?: LocalDate.now()
                DatePickerDialog(
                    context,
                    { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day)) },
                    initial.year,
                    initial.monthValue - 1,
                    initial.dayOfMonth
                ).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                value?.format(searchDayFormatter) ?: "Any date",
                color = if (value != null) MaterialTheme.field.body else MaterialTheme.field.placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (value != null) {
            TextButton(onClick = { onChange(null) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Dates
// ---------------------------------------------------------------------------------------------

private val searchDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
private val searchDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.getDefault())

/** The web's `formatDateTime` — "-" for a missing or unparseable value, never a raw ISO string. */
private fun formatSearchDateTime(value: String?): String {
    if (value.isNullOrBlank()) return "-"
    val instant = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
        ?: return "-"
    return runCatching { searchDateTimeFormatter.format(instant.atZone(ZoneId.systemDefault())) }.getOrElse { "-" }
}
