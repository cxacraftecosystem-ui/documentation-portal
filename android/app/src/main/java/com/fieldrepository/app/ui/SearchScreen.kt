package com.fieldrepository.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftDto
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

    /**
     * Every bucket, in the order `GET /search` counts, reads and returns them.
     *
     * Type selections are always re-derived by filtering THIS list, never stored in the order the
     * user happened to tick them. Two researchers who picked the same three buckets in different
     * orders are asking one question, and it must reach the API as one query string.
     */
    val ALL: List<String> = listOf(ARTISAN, WORKSHOP, PRODUCT, TOOL, MEDIA)

    /** The bucket's own heading, as the web's `TYPE_LABEL` words it. */
    fun label(recordType: String): String = when (recordType) {
        ARTISAN -> "Artisans"
        WORKSHOP -> "Workshops"
        PRODUCT -> "Products"
        TOOL -> "Tools"
        MEDIA -> "Media"
        else -> recordType.replaceFirstChar { it.uppercase() }
    }

    /**
     * The name `GET /search?types=` knows this bucket by.
     *
     * The API's vocabulary there differs from the app's, which is singular everywhere else — singular
     * is what `onOpenRecord`, `EntryMode` routing and `/data/locate` all take — so the two are
     * translated in one place instead of a second vocabulary being kept in step by hand.
     *
     * NOT `recordType + "s"`. The API's list is `artisans, workshops, products, tools, media`
     * (`backend/app/api/routes/search.py`): plural for four of them and `media` for the fifth,
     * because "medias" is not a word. Appending an s sent `medias`, and that route answers an
     * unrecognised name with a 422 rather than dropping it — so ticking the Media chip did not
     * narrow the search, it made every search that included Media fail and show nothing at all.
     * Written out one bucket at a time so that the next bucket added has to be written out too.
     */
    fun bucket(recordType: String): String = when (recordType) {
        ARTISAN -> "artisans"
        WORKSHOP -> "workshops"
        PRODUCT -> "products"
        TOOL -> "tools"
        MEDIA -> "media"
        // Unreachable while every caller filters [ALL] first, and left as-is rather than guessed at:
        // an unknown name reaching the API as itself is a 422 naming the real problem, where a name
        // this function invented would be a 422 naming something the app made up.
        else -> recordType
    }
}

/**
 * The record-time presets, resolved to concrete dates by [SearchFilters.resolveDateRange] before any
 * request is made. The API takes dates and never preset names, deliberately: "Last 30 days" is a
 * phrase in a UI counted against the researcher's own clock, and only this client knows that clock.
 */
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

/**
 * Rows per bucket per page. `GET /search` caps pageSize at 50 and applies one shared skip/take to
 * all five buckets, so this is the page size of every bucket at once. 20 matches the web page.
 */
const val SEARCH_PAGE_SIZE = 20

/** How long the inputs must settle before a query is sent. One request per typed word, not per key. */
const val SEARCH_DEBOUNCE_MILLIS = 350L

/**
 * Everything `GET /search` filters on, for BOTH surfaces that search: this screen and the panel at
 * the top of the Data Browser.
 *
 * They were drifting apart — the search screen had count pills and a place box, the browser had a
 * bare text field — which meant the same question got two answers depending on which screen the
 * researcher was standing on. The vocabulary (what a type is, what "Last 30 days" resolves to, how
 * the filters become a request) lives here once so the two cannot disagree again.
 *
 * Held as ONE value so the debounce can compare "what is set now" against "what was last searched"
 * in a single equality check, and so paging reads a frozen snapshot instead of drifting with a
 * half-typed box.
 */
@Immutable
data class SearchFilters(
    val query: String = "",
    val place: String = "",
    /**
     * The buckets to search. EMPTY MEANS EVERY BUCKET — the set never lists all five explicitly, so
     * "nothing ticked" and "everything ticked" cannot both exist and mean the same thing. Held in
     * [SearchRecordTypes.ALL] order, whatever order the ticks went in.
     */
    val types: Set<String> = emptySet(),
    val range: SearchRange = SearchRange.ANY,
    /** Only read when [range] is [SearchRange.CUSTOM]; either bound may stand alone. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val craftId: String = "",
    val artisanId: String = "",
    val mediaType: String = ""
) {
    /**
     * The parts a person TYPES, as one comparable value. Everything else here is clicked, and the
     * two deserve different timing: typing has to settle before it is worth a request, a tapped chip
     * is already the finished thought. A pair rather than a joined string, so that no separator can
     * collapse ("a", "b c") and ("a b", "c") into one value and swallow one of the two changes.
     */
    val typed: Pair<String, String>
        get() = query to place

    /**
     * How many filters the sheet is hiding. Types are deliberately NOT counted: the chips show them
     * whether the sheet is open or shut, and a badge counting something already on screen reads as a
     * second, disagreeing filter.
     */
    val sheetFilterCount: Int
        get() = listOf(
            place.isNotBlank(),
            range != SearchRange.ANY,
            craftId.isNotBlank(),
            artisanId.isNotBlank(),
            mediaType.isNotBlank()
        ).count { it }

    /** True when anything at all is narrowing the search — a bare filter is a real question. */
    val hasFilters: Boolean
        get() = types.isNotEmpty() || sheetFilterCount > 0

    /** No filter at all — searching this would list the whole repository, so it needs an explicit ask. */
    val isEmpty: Boolean
        get() = query.isBlank() && !hasFilters

    /** Whether a bucket survives the type filter — the client half of the `types` contract. */
    fun includes(recordType: String): Boolean = types.isEmpty() || recordType in types

    /**
     * The selected buckets as [FieldRepository.search] wants them: the API's plural names, in
     * canonical order, or null for "everything".
     */
    fun bucketTypes(): List<String>? = SearchRecordTypes.ALL
        .filter { it in types }
        .map(SearchRecordTypes::bucket)
        .takeIf { it.isNotEmpty() }

    /**
     * [range] as the concrete `dateFrom`/`dateTo` instants the API takes.
     *
     * Resolved against [today] at REQUEST time rather than when the preset was picked, so a screen
     * left open overnight does not keep searching yesterday. Both bounds are built in the device's
     * own zone and serialised as instants: the end of a chosen day is 23:59:59, because the API
     * compares with `lte` and a bare start-of-day bound would drop every record made on that day.
     */
    fun resolveDateRange(today: LocalDate = LocalDate.now()): Pair<String?, String?> {
        val endOfToday = endOfDay(today)
        return when (range) {
            SearchRange.ANY -> null to null
            SearchRange.TODAY -> startOfDay(today) to endOfToday
            // Inclusive of today, so "last 7 days" really is seven days and not eight.
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
    /**
     * Open showing only this bucket — a [SearchRecordTypes] value, or null for all five.
     *
     * This is what a tapped dashboard total means. "74 tools" is a question, and the honest answer
     * is the list of those tools, not a page of five headings where four are empty. Arriving with
     * one also implies the listing itself: the tap already said what it wanted, so the screen must
     * not sit on an empty form waiting to be told again.
     */
    initialRecordType: String? = null,
    modifier: Modifier = Modifier
) {
    /*
     * KEYED on initialRecordType, and that is not a detail: Compose keeps a slot's `remember` across
     * a navigation that lands on the same composable, so an unkeyed one would hold the FIRST focus
     * for ever. Tapping "Tools" after having tapped "Artisans" showed a page headed "Every tools
     * record" with the Artisans bucket still selected — the caller had moved on and the state had
     * not. Everything seeded from the parameter is keyed on it for the same reason, `results`
     * included: the previous bucket's rows must not sit under the new bucket's heading.
     */
    val seed = remember(initialRecordType) { SearchFilters(types = setOfNotNull(initialRecordType)) }
    // Live inputs.
    var filters by remember(seed) { mutableStateOf(seed) }
    // The filters the CURRENT results belong to. The pager and the rendered buckets walk THESE,
    // never `filters`: they describe the rows on screen, and a half-typed box does not.
    var applied by remember(seed) { mutableStateOf(seed) }
    var page by remember(seed) { mutableStateOf(1) }
    // Bumped by the Search button so pressing it re-runs an identical query (same filters, same page).
    var runCount by remember { mutableStateOf(0) }
    // Set once the researcher explicitly asks for an unfiltered listing — or immediately, when the
    // screen was opened from a dashboard total, which is that same request made by tapping a number.
    var browseAll by remember(seed) { mutableStateOf(initialRecordType != null) }

    var results by remember(seed) { mutableStateOf<SearchResultsDto?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var crafts by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }

    // Craft/artisan pickers are a convenience, not a dependency: if either lookup fails the picker
    // simply stays hidden and the text, place, type and date filters still work.
    LaunchedEffect(Unit) {
        runCatching { repository.crafts() }.onSuccess { crafts = it }
        runCatching { repository.artisans() }.onSuccess { artisans = it }
    }

    // Editing any input restarts this effect, so a change is only promoted to `applied` once the
    // inputs have been still for SEARCH_DEBOUNCE_MILLIS — but only TYPING has to be still. A chip, a
    // range or a type tick is one deliberate tap and refreshes at once; waiting on it would read as
    // the filter not working. Both paths still go through this one effect, so the cancel-and-restart
    // that guards against stale responses stays the only way a request is ever made.
    LaunchedEffect(filters) {
        if (filters == applied) return@LaunchedEffect
        if (filters.typed != applied.typed) delay(SEARCH_DEBOUNCE_MILLIS)
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
        try {
            // Every active filter goes into ONE request, so they AND rather than being applied in
            // passes. The presets become concrete dates here, at request time, against the device's
            // own clock — see SearchFilters.resolveDateRange.
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
            // Every settled keystroke re-keys this effect, and Compose FORGETS the old one — which
            // cancels the in-flight call with "The coroutine scope left the composition". runCatching
            // catches that like any other Throwable, which is how a plain superseded request ended up
            // reported as a failed search. Rethrowing also skips the `loading = false` below, and must:
            // the pass that replaced this one already owns the flag.
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
            // The same arrow every other screen uses, not a pill: one action, one shape.
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

            // The place box lives in the sheet with the rest of the filters rather than beside the
            // query box: one value with two controls on screen at once is exactly the confusion the
            // chips and the multi-select are carefully avoiding, and a phone has no width to spare.
            SearchFilterBar(
                value = filters,
                onChange = { filters = it },
                // Craft, artisan and media type are this screen's own, and the slot says so out
                // loud: the shared filters above them are one implementation, and an addition
                // declared here cannot quietly become a second copy that drifts.
                extraFilters = {
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
                    Text(
                        "Craft, artisan and media type narrow only the buckets that carry them.",
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
                        "Looking across all five record types."
                    } else {
                        "Looking in ${applied.types.size} of the five record types."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            data == null -> SearchCard(title = "Nothing searched yet") {
                Text(
                    "Type what you are looking for — a name, a place, a filename — and results appear as " +
                        "you pause. A chip or a date on its own is a question too. Press Search on an " +
                        "empty form to list the most recent records instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                // Filtered against `applied`, not the live `filters`: these buckets describe rows
                // that are already on screen, and they must not disappear the instant a chip is
                // touched but before the response for it has landed.
                //
                // Dropped on the CLIENT as well as in the request, because `types` is the one filter
                // key an older deployment may not know: a server that ignores it answers with all
                // five buckets, and a screen that trusted that would show artisans to a researcher
                // who asked for media.
                val allBuckets = remember(data) { data.toSearchBuckets() }
                val buckets = remember(allBuckets, applied) {
                    allBuckets.filter { applied.includes(it.recordType) }
                }
                val shown = buckets.sumOf { it.rows.size }
                val matched = buckets.sumOf { it.total }
                // The API reports `pageCount` (the last page of its LONGEST bucket), so Next is
                // exact — but that longest bucket may be one this search is not showing, so the page
                // count is re-derived from the SELECTED buckets' own totals. The last fallback —
                // "some bucket came back full" — keeps this working against an API that predates
                // those keys; it can walk one page too far when a bucket's total is an exact
                // multiple of the page size, which is precisely why the server-side counts win.
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
internal data class SearchRow(
    val recordType: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val date: String?
)

/** A result type with its page of rows and how many matches it has in total. */
private data class SearchBucket(
    /** A [SearchRecordTypes] value. Carried on the bucket, not read off its first row, because an
     *  EMPTY bucket still has to be nameable — that is exactly the one a type filter has to match. */
    val recordType: String,
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
        SearchBucket(SearchRecordTypes.ARTISAN, "Artisans", total(totals.artisans, artisanRows.size), artisanRows),
        SearchBucket(SearchRecordTypes.WORKSHOP, "Workshops", total(totals.workshops, workshopRows.size), workshopRows),
        SearchBucket(SearchRecordTypes.PRODUCT, "Products", total(totals.products, productRows.size), productRows),
        SearchBucket(SearchRecordTypes.TOOL, "Tools", total(totals.tools, toolRows.size), toolRows),
        SearchBucket(SearchRecordTypes.MEDIA, "Media", total(totals.media, mediaRows.size), mediaRows)
    )
}

// ---------------------------------------------------------------------------------------------
// Quick search
//
// The same query for screens that search in order to GO somewhere rather than to list results.
// Shared from here so the app has one debounce, one flattening rule and one result row, instead of
// a second search that drifts away from this one.
// ---------------------------------------------------------------------------------------------

/** How many hits a quick search shows. Short enough to sit above a screen's own content. */
internal const val QUICK_SEARCH_LIMIT = 8

/** Below this a query matches so much of the repository that the list is noise, not a shortlist. */
internal const val QUICK_SEARCH_MIN_CHARS = 2

/**
 * A debounced search over the same [SearchFilters] the full screen uses, owned by
 * [rememberQuickSearch] and written only from its effect.
 *
 * Single-flight by construction: the caller re-keys the effect on the whole filter value, so a
 * superseded request is cancelled before its response can land on top of a newer one.
 */
@Stable
internal class QuickSearchState(private val repository: FieldRepository) {
    var hits by mutableStateOf<List<SearchRow>>(emptyList())
        private set

    /** Matches across the SELECTED buckets, so "of N" can never promise more than the filter allows. */
    var total by mutableStateOf(0)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** A query has actually run — the difference between "no matches" and "nothing asked yet". */
    var searched by mutableStateOf(false)
        private set

    /**
     * The typed text the last scheduled run carried, so a clicked filter can skip the typist's
     * pause. Seeded with the empty pair rather than null: the first chip tapped on an untouched
     * panel is a deliberate click too, and should not sit through a debounce meant for keystrokes.
     */
    private var scheduledTyped: Pair<String, String> = SearchFilters().typed

    /** True when [typed] is not what the last run was scheduled for — i.e. typing is still settling. */
    fun awaitsTyping(typed: Pair<String, String>): Boolean {
        val changed = scheduledTyped != typed
        scheduledTyped = typed
        return changed
    }

    /** Back to "nothing asked": what a blank, too-short and unfiltered form shows. */
    fun reset() {
        hits = emptyList()
        total = 0
        loading = false
        error = null
        searched = false
        // Nothing is scheduled any more either, so the next word typed gets its full pause back and
        // the next chip tapped on the emptied form still answers at once.
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
                pageSize = limit
            )
            hits = results.toQuickHits(filters, limit)
            total = results.selectedTotal(filters)
            error = null
            searched = true
        } catch (cancelled: CancellationException) {
            // Superseded by a newer keystroke, or the screen was left. Neither is a failed search,
            // and `loading` now belongs to the pass that replaced this one — so rethrow rather than
            // fall through. See the same guard on the full screen's own request.
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.apiErrorMessage("Search failed")
        }
        loading = false
    }
}

/**
 * A [QuickSearchState] bound to [filters] and to this composition.
 *
 * Re-keying on the whole filter value is what cancels both the pending debounce and any request
 * already in flight, so only the newest state of the form can produce hits.
 */
@Composable
internal fun rememberQuickSearch(
    repository: FieldRepository,
    filters: SearchFilters,
    limit: Int = QUICK_SEARCH_LIMIT
): QuickSearchState {
    val state = remember(repository) { QuickSearchState(repository) }
    // Trimmed before it becomes the effect key, so a trailing space is not a new question.
    val request = remember(filters) { filters.copy(query = filters.query.trim(), place = filters.place.trim()) }
    LaunchedEffect(state, request, limit) {
        // Two characters of text OR any filter at all. A chip on its own is a real question here —
        // "the media from this workshop week" — and answering it with a blank panel reads as broken.
        if (request.query.length < QUICK_SEARCH_MIN_CHARS && !request.hasFilters) {
            state.reset()
            return@LaunchedEffect
        }
        if (state.awaitsTyping(request.typed)) delay(SEARCH_DEBOUNCE_MILLIS)
        state.run(request, limit)
    }
    return state
}

/**
 * The selected buckets flattened into one shortlist, round-robin rather than concatenated:
 * `GET /search` fills every bucket to the same page size, so appending them in order would spend the
 * whole list on artisans and hide the workshop the researcher was actually typing.
 *
 * The type filter is applied here as well as in the request, for the same reason the full screen
 * applies it twice: a deployment that does not know `types` yet answers with all five buckets.
 */
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

/** Matches in the buckets this search is showing. Falls back to row counts on an API without totals. */
private fun SearchResultsDto.selectedTotal(filters: SearchFilters): Int =
    toSearchBuckets().filter { filters.includes(it.recordType) }.sumOf { it.total }

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
internal fun SearchResultRow(row: SearchRow, onOpen: () -> Unit) {
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
// The shared filter bar
//
// ONE implementation, used by the search screen and by the Data Browser's search panel. The nav bar
// and the drawer were each written twice in this app and each pair drifted; a filter set is worse,
// because the divergence is silent — the same question simply answers differently depending on
// which screen you asked it from.
// ---------------------------------------------------------------------------------------------

/** How a chip reads: the filter, a member of a multi-type filter, or off. */
private enum class ChipTone { ON, PART, OFF }

/**
 * The six category chips, the sheet button, and the sheet behind it.
 *
 * THE CHIPS AND THE MULTI-SELECT ARE ONE PIECE OF STATE, not two. [SearchFilters.types] is the only
 * store of which types are being searched; the chip row and the checkbox list are two editors of
 * that same set, which is why they cannot fall out of step:
 *
 *   - a chip is the shortcut for "only this" — tapping one REPLACES the set with that single type,
 *     and Everything empties it;
 *   - a checkbox adds or removes one member and leaves the rest alone.
 *
 * The chips keep saying what the set is even when the set is something chips alone cannot express:
 * with two or more types selected no chip is the solid "this is the filter" fill, the members are
 * drawn in the lighter included style instead, and a line of text says how many are in play.
 *
 * A bottom sheet rather than the inline disclosure the web uses: three filters and a five-way tick
 * list unfolding in place would push the results off a phone screen every time they were consulted.
 *
 * @param extraFilters screen-specific fields, appended below the shared ones. A declared slot rather
 *   than a second bar — an addition that has to be passed in cannot quietly become a copy.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SearchFilterBar(
    value: SearchFilters,
    onChange: (SearchFilters) -> Unit,
    modifier: Modifier = Modifier,
    extraFilters: (@Composable ColumnScope.() -> Unit)? = null
) {
    // Not seeded from any parameter, so an unkeyed remember is right here: whether the sheet is open
    // belongs to this composition and to nothing else.
    var open by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hidden = value.sheetFilterCount

    fun close() {
        // Hide first so the sheet slides away instead of vanishing; the flag drops once it has.
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) open = false }
    }

    fun toggleType(recordType: String) {
        val next = if (recordType in value.types) value.types - recordType else value.types + recordType
        // Stored in bucket order so the state reads the same as the row of chips above it, whatever
        // order the ticks went in. `bucketTypes()` re-derives it anyway; this keeps the state honest.
        onChange(value.copy(types = SearchRecordTypes.ALL.filter { it in next }.toSet()))
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            SearchChip(
                text = if (hidden > 0) "Filters · $hidden" else "Filters",
                tone = if (open || hidden > 0) ChipTone.PART else ChipTone.OFF,
                icon = Icons.Filled.FilterList,
                onClick = { open = true }
            )
        }

        if (value.types.size > 1) {
            Text(
                "Searching ${value.types.size} record types. A chip narrows to just that one; " +
                    "tick more under Filters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Outside the scroll, so the keyboard SHRINKS the scrollable area rather than
                    // padding the content inside it — the place box is the last thing that should
                    // end up underneath the IME, and the sheet has its own window, which the
                    // activity's inset handling does not reach.
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
                    // "Any time" is a real choice in this list, so there is no blank row above it.
                    allowNone = false,
                    onSelect = { picked -> onChange(value.copy(range = SearchRange.valueOf(picked))) }
                )

                if (value.range == SearchRange.CUSTOM) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        // Each end bounds the other in the picker itself, so an inverted range —
                        // which matches nothing and looks like a broken filter — cannot be entered.
                        SearchDateField(
                            label = "From",
                            value = value.from,
                            onChange = { onChange(value.copy(from = it)) },
                            maximum = value.to,
                            modifier = Modifier.weight(1f)
                        )
                        SearchDateField(
                            label = "To",
                            value = value.to,
                            onChange = { onChange(value.copy(to = it)) },
                            minimum = value.from,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Record types",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "The same setting as the chips above. Tick any number; nothing ticked " +
                            "searches everything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SearchRecordTypes.ALL.forEach { recordType ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { toggleType(recordType) }
                    ) {
                        Checkbox(checked = recordType in value.types, onCheckedChange = { toggleType(recordType) })
                        Text(
                            SearchRecordTypes.label(recordType),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.field.body
                        )
                    }
                }

                extraFilters?.invoke(this)

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (value.hasFilters) {
                        // Clears the FILTERS and keeps the query: they are separate questions, and
                        // wiping a typed name to widen a date range would be its own small betrayal.
                        TextButton(onClick = { onChange(SearchFilters(query = value.query)) }) {
                            Text("Clear all filters")
                        }
                    }
                    Spacer(Modifier.weight(1f))
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
        ChipTone.OFF -> MaterialTheme.field.surface50
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
            // Clipped before it is clickable, or the ripple squares off the pill.
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(background, CircleShape)
            .then(
                if (tone == ChipTone.OFF) Modifier.border(1.dp, MaterialTheme.field.hairline, CircleShape) else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
            maxLines = 1
        )
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

/**
 * A bucket's "3 of 12". It only REPORTS: choosing which buckets to search is the chips' job, and a
 * count that also filtered would be a second control for a setting already on screen.
 */
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
    onSelect: (String) -> Unit,
    /** False when "no filter" is already one of [options], so the list does not offer it twice. */
    allowNone: Boolean = true
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
                if (allowNone) {
                    DropdownMenuItem(
                        text = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingIcon = {
                            if (selectedValue.isBlank()) Text("✓", color = MaterialTheme.colorScheme.primary)
                        },
                        onClick = { onSelect(""); expanded = false }
                    )
                }
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

/** The platform date picker, so a date is entered the way every other Android app enters one. */
@Composable
private fun SearchDateField(
    label: String,
    value: LocalDate?,
    onChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    minimum: LocalDate? = null,
    maximum: LocalDate? = null
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            onClick = {
                val initial = value ?: maximum ?: minimum ?: LocalDate.now()
                DatePickerDialog(
                    context,
                    { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day)) },
                    initial.year,
                    initial.monthValue - 1,
                    initial.dayOfMonth
                ).apply {
                    // Bounds go on the widget, not on a validation message: a day the range cannot
                    // hold should never be tappable in the first place.
                    minimum?.let { datePicker.minDate = epochMillis(it) }
                    maximum?.let { datePicker.maxDate = epochMillis(it) }
                }.show()
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

/**
 * A day as the epoch millis `DatePicker.minDate`/`maxDate` want. Noon, not midnight: the widget
 * compares against the device's own zone, and a midnight bound east of Greenwich excludes the very
 * day it was meant to allow.
 */
private fun epochMillis(date: LocalDate): Long =
    date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
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
