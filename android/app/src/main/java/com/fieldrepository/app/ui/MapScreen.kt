package com.fieldrepository.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.MapCountsDto
import com.fieldrepository.app.data.MapPointDto
import com.fieldrepository.app.data.MapPointRecordDto
import com.fieldrepository.app.data.MapPointsDto
import com.fieldrepository.app.data.MapUnplacedDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * WHERE THE WORK COMES FROM — the repository read as geography. The Android counterpart of the web's
 * `app/(protected)/map/page.tsx`, and it says the same things in the same words on purpose: a
 * researcher who reads "state only — drawn at the state's seat" on the laptop must not be shown a
 * paraphrase of it on the phone, because the two would then look like two different claims about
 * one pin.
 *
 * THE TWO LAYERS. Read the header of `backend/app/api/routes/map_points.py` before changing
 * anything here. In short: the GPS fixes say where the recording HAPPENED (one venue, in this
 * corpus) and the addresses say where the craft is FROM (a dozen districts across eight states), and
 * showing either one alone tells a lie about the other. They are never added together and never
 * merged; a pin's [MapPointDto.layer] says which it is, and this screen tells them apart by SHAPE —
 * a filled disc for ORIGIN, a ring for CAPTURE — because purple is the app's only action colour and
 * hue is therefore not available to carry the distinction.
 *
 * THE TWO CONTROLS, and what each one is:
 *   * the workshops — WHICH RECORDS. The shared `workshopIds` scope, same wire format, same default
 *     (the most recent workshop) and same vocabulary as the web's `WorkshopScopeSelect`.
 *   * the detail level — HOW THE SAME RECORDS ARE GROUPED. Nation / state / district. It is a view
 *     setting, not a filter, and it never changes a total.
 * The web has a third — the shared `/search` filter bar — which this screen deliberately does not
 * carry; see the note above [MapScreen] for what that costs and how the empty-state copy accounts
 * for it.
 *
 * WHY THE BOUNDARIES ARE BAKED INTO THE APK. See the header of [IndiaOutline.kt]: `res/raw/
 * india_outline.bin` carries the official Government of India depiction, which is a legal
 * requirement here and the one thing a map SDK's own boundary for Jammu & Kashmir would get wrong.
 * The interior borders ship beside it — `state_borders.bin` and `district_borders.bin`, built by
 * `scripts/build_boundaries.py` — and are drawn UNDER the pins as context for the level: state borders
 * at State and District, district borders at District. Both are CLIPPED to the outline, because the
 * source they were decomposed from disagrees with the official depiction by ~2 km at the coast and the
 * frontier must be one line at all three levels, not two nearly-identical ones.
 *
 * Nothing here may draw a boundary the geometry does not contain. Records are still sized, labelled
 * POINTS rather than shaded regions — the border says where a unit ends, the pin says how much is in
 * it — and the copy in [DetailLevelControl] names the one gap that is real: 43 of 795 districts were
 * notified after the source's vintage and have no border. The exact list is
 * `frontend/public/boundaries/manifest.json` under "missing".
 *
 * THE LIST BELOW THE MAP IS NOT A FALLBACK. The canvas is one semantics node — a picture — so the
 * place list is the only way a TalkBack user reads this screen, and it is therefore held to a higher
 * bar than a caption: every place, every count, and the PRECISION of every point in words. A sighted
 * user gets the shape of the country; everyone gets the numbers.
 */

// ---------------------------------------------------------------------------------------------
// Vocabulary — the server's, and the web's words for it
// ---------------------------------------------------------------------------------------------

// The workshop scope — [UNASSIGNED_WORKSHOP], [rememberWorkshopScope], [WorkshopScopeSelect] and the
// summary sentence — used to be declared privately here. It now lives in `ui/WorkshopScope.kt`,
// shared with the completion matrix and the consolidated questionnaire, because three copies of this
// control is three defaults that can drift apart and therefore three screens quietly disagreeing
// about which records are in scope.

/**
 * The levels to render the toggle from when the server has not answered yet.
 *
 * The response's own `levels` wins once it arrives — the toggle is built from the server's
 * vocabulary so the two cannot drift — and this exists only so the control is not blank on the first
 * frame. Same order and same values as the web's `ADMIN_LEVELS`.
 */
private val FALLBACK_ADMIN_LEVELS = listOf("NATION", "STATE", "DISTRICT")

private const val DEFAULT_ADMIN_LEVEL = "DISTRICT"

/** The web's `LEVEL_COPY[…].label`. Unknown values are echoed rather than blanked. */
private fun levelLabel(level: String): String = when (level) {
    "NATION" -> "Nation"
    "STATE" -> "State"
    "DISTRICT" -> "District"
    else -> level.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
}

/**
 * The web's `LEVEL_COPY[…].hint`. Each level says what it GROUPS BY and which border it is read
 * inside, because "state" alone would leave a reader guessing whether it filters or aggregates.
 *
 * Null for a level this build has never heard of: an invented hint would be a claim about grouping
 * that nothing in the app knows to be true.
 */
private fun levelHint(level: String): String? = when (level) {
    "NATION" -> "Every placed record as one point, inside the international border."
    "STATE" -> "One point per state. Districts inside a state fold together."
    "DISTRICT" -> "One point per district — the finest unit an address can name."
    else -> null
}

/** The web's `SCOPE_COPY`. `scope` defaults to "" in the DTO, and an absent enum is not a claim. */
private fun scopeCopy(scope: String): String = when (scope) {
    "all" -> "Everything in the repository"
    "filtered" -> "The records matching your filters"
    "record" -> "One record, shown in context"
    else -> "The records on this map"
}

/**
 * How each precision tier is described in words — the web's `PRECISION_NOTE`, verbatim.
 *
 * The map draws uncertainty as a halo; this says the same thing in a sentence, because a halo
 * communicates nothing to a screen reader. Two of the six are MEASUREMENTS and four are lookups, and
 * the wording keeps them apart on purpose: a reader who cannot tell a dropped pin from a state
 * capital will read the second as the first.
 */
private fun precisionNote(precision: String): String = when (precision) {
    "SUBJECT_PIN" -> "pin dropped on the subject's own place"
    "MEASURED" -> "GPS fix taken while recording"
    "TOWN" -> "town located from the typed place name"
    "DISTRICT" -> "district position learned from pins inside it"
    "STATE" -> "state only — drawn at the state's seat"
    "NATION" -> "every placed record, folded into one point"
    else -> "position of unstated precision"
}

/**
 * The web's `PRECISION_BADGE`. The server's vocabulary can gain a tier before this client is
 * shipped, and an empty pill beside a real count reads as a broken row — so an unknown tier shows
 * its raw name rather than nothing.
 */
private fun precisionBadge(precision: String): String = when (precision) {
    "SUBJECT_PIN" -> "Pinned"
    "MEASURED" -> "Measured"
    "TOWN" -> "Town"
    "DISTRICT" -> "District"
    "STATE" -> "State"
    "NATION" -> "Nation"
    else -> precision
}

/**
 * The five buckets in the order the server counts, reads and serialises them, which is the order the
 * web's `Object.entries(point.counts)` walks. Written out rather than derived so a sixth bucket has
 * to be added here deliberately.
 */
private fun MapCountsDto.byBucket(): List<Pair<String, Int>> = listOf(
    "artisans" to artisans,
    "workshops" to workshops,
    "products" to products,
    "tools" to tools,
    "media" to media
)

/** The web's `countLabel` + `TYPE_NOUN`: "3 artisans", "1 media file". */
private fun countLabel(bucket: String, count: Int): String {
    val noun = when (bucket) {
        "artisans" -> if (count == 1) "artisan" else "artisans"
        "workshops" -> if (count == 1) "workshop" else "workshops"
        "products" -> if (count == 1) "product" else "products"
        "tools" -> if (count == 1) "tool" else "tools"
        // "medias" is not a word, which is why the API's own bucket name is the singular one.
        "media" -> if (count == 1) "media file" else "media files"
        else -> bucket
    }
    return "$count $noun"
}

/**
 * The API's PLURAL bucket name turned into the app's singular record type — the vocabulary
 * `SearchRecordTypes`, `EntryMode` routing and `/data/locate` all use.
 *
 * Written out one bucket at a time rather than trimming an "s", because the fifth is `media` and
 * `media`.dropLast(1) is `medi`. Only used to hand a tapped row to [MapScreen]'s optional
 * `onOpenRecord`; nothing on the wire depends on it.
 */
private fun singularRecordType(bucket: String): String = when (bucket) {
    "artisans" -> "artisan"
    "workshops" -> "workshop"
    "products" -> "product"
    "tools" -> "tool"
    "media" -> "media"
    else -> bucket
}

/** Review status, worded exactly as the web's `StatusBadge` words it. */
private fun statusLabel(status: String): String = when (status.uppercase(Locale.ROOT)) {
    "DRAFT" -> "Draft"
    "PENDING" -> "Pending"
    "APPROVED" -> "Approved"
    "REJECTED" -> "Rejected"
    "NEEDS_REVISION" -> "Needs revision"
    else -> status.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
}

/** The web's `formatSpread`. */
private fun formatSpread(metres: Int): String = when {
    metres < 1 -> "a single point"
    metres < 1000 -> "$metres m"
    else -> "${String.format(Locale.ROOT, "%.1f", metres / 1000.0)} km"
}

/**
 * A measured distance as the web prints it. JavaScript renders 26.0 as "26"; Kotlin renders it as
 * "26.0", and a phone claiming "median accuracy ±26.0 m" beside a laptop claiming "±26 m" reads as
 * two different measurements of the same fix.
 */
private fun formatMetres(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
    else String.format(Locale.ROOT, "%.1f", value)

// ---------------------------------------------------------------------------------------------
// The screen
// ---------------------------------------------------------------------------------------------

/**
 * The map. Hosted in the shared chrome: a bare [Column] and nothing else, because HomeScreen draws
 * the scaffold, the app bar, the scroll and the back button. It must NOT scroll itself — a nested
 * `verticalScroll` is measured with an infinite height budget and throws before it can draw.
 *
 * WHAT THIS SCREEN DOES NOT CARRY, stated so the omission is a decision rather than a gap: the web
 * puts the shared `/search` filter bar (text, place, record types, date range) above the map. This
 * does not, so the only narrowing available here is the workshop scope. The empty-state copy accounts
 * for it — "no records with a mapped address in the chosen workshops" is true on both clients, but
 * the web's unscoped sentence ("…match these filters") would name a control this screen has not got,
 * so the unscoped case says what is actually the case instead.
 *
 * [focusType] and [focusId] ask for one record in context and must be passed together — one alone is
 * ignored by the server. The map still draws the whole corpus; the focus only names which pins hold
 * that record, and those pins get a dashed ring and a "This record" badge in the list. This is what a
 * "show this on the map" tap from a record screen would pass.
 *
 * [onOpenRecord] receives `(recordType, recordId)` with recordType in the app's SINGULAR vocabulary
 * (`artisan`, `workshop`, `product`, `tool`, `media`). Null — the default — leaves the records behind
 * a pin as plain rows: a map you cannot leave is an ornament, but a row that looks tappable and is
 * not is worse than a row that does not.
 *
 * [onError] is called with the message the API meant a human to read. Every failure is also shown
 * inline, because a snackbar that has already faded leaves a blank map with no explanation on it.
 */
@Composable
fun MapScreen(
    repository: FieldRepository,
    onError: (String) -> Unit,
    focusType: String? = null,
    focusId: String? = null,
    onOpenRecord: ((recordType: String, recordId: String) -> Unit)? = null
) {
    val context = LocalContext.current

    // --- The national outline. Loaded once, off the main thread: ~12k points of allocation. ---
    var geometry by remember { mutableStateOf<IndiaGeometry?>(null) }
    var geometryFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { loadIndiaGeometry(context) } }
            .onSuccess { geometry = it }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                // The list below still carries every place, every count and every precision, so a
                // failed outline costs the picture and not the screen. Say so rather than reporting
                // it as a failed map.
                geometryFailed = true
            }
    }

    // --- The state borders. 26 KB / 81 polylines, read alongside the outline because both the STATE
    // and the DISTRICT level draw them and neither should wait for a file read after a chip tap.
    // A failure here is silent on purpose: the outline, the pins and the place list are all unaffected,
    // so the cost is a set of interior lines, and there is no honest sentence to print about the
    // absence of a line that was never claimed to be the boundary. `geometryFailed` above stays the
    // only load failure with copy, because that one costs the picture.
    var stateBorders by remember { mutableStateOf<IndiaGeometry?>(null) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { loadStateBorders(context) } }
            .onSuccess { stateBorders = it }
            .onFailure { failure -> if (failure is CancellationException) throw failure }
    }

    // The district borders are loaded LAZILY — see the effect below `drawnLevel`, which is where the
    // level the map is actually drawn at is known.
    var districtBorders by remember { mutableStateOf<IndiaGeometry?>(null) }

    // --- The workshop scope. The shared control: same default (the most recent workshop), same three
    // states and same wire format as the web and as the completion matrix. `settled` is why the first
    // request below waits — firing early would draw the whole repository for a moment and then replace
    // it with the scoped answer, which is two requests and a visible flash of the wrong map.
    val workshopScope = rememberWorkshopScope(repository = repository, onError = onError)

    // --- The detail level. A view setting: it re-groups the same records and never changes a total.
    var level by remember { mutableStateOf(DEFAULT_ADMIN_LEVEL) }

    // --- The focus, and the way out of it. Clearing drops the parameters and re-requests, which is
    // what the web's "Clear" link does by navigating to a bare /map.
    var focusCleared by remember { mutableStateOf(false) }
    val activeFocusType = if (focusCleared) null else focusType?.takeIf { it.isNotBlank() }
    val activeFocusId = if (focusCleared) null else focusId?.takeIf { it.isNotBlank() }

    var data by remember { mutableStateOf<MapPointsDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(level, workshopScope.requestKey, workshopScope.settled, activeFocusType, activeFocusId) {
        if (!workshopScope.settled) return@LaunchedEffect
        loading = true
        // Cleared BEFORE the request, not after it: a failed load followed by a level change would
        // otherwise keep showing the old error card over a request that is already in flight, which
        // reads as the retry not having happened.
        error = null
        runCatching {
            repository.mapPoints(
                workshopIds = workshopScope.workshopIds,
                level = level,
                focusType = activeFocusType,
                focusId = activeFocusId
            )
        }
            .onSuccess { result ->
                data = result
                // Opening straight onto the focused record's point saves a reader hunting for the
                // ring. Changing the level RE-KEYS every pin, so the previous selection is dropped
                // rather than left pointing at a key that no longer exists.
                selectedKey = result.focus?.pointKeys?.firstOrNull()
                error = null
                loading = false
            }
            .onFailure { failure ->
                // Re-keying this effect cancels the in-flight call, and runCatching catches that
                // cancellation like any other Throwable — which is how a plain superseded request
                // ends up reported as a failed map. Rethrowing also skips `loading = false`, and
                // must: the pass that replaced this one already owns the flag.
                if (failure is CancellationException) throw failure
                val message = failure.apiErrorMessage("The map could not be loaded.")
                error = message
                onError(message)
                loading = false
            }
    }

    val current = data
    val points = current?.points ?: emptyList()
    val drawnLevel = current?.level?.ifBlank { level } ?: level
    // Pulled out as its own val rather than read through `current?.focus` at each use: a nullable
    // property read twice is two reads the compiler will not smart-cast between.
    val focus = current?.focus
    val focusKeys = focus?.pointKeys ?: emptyList()
    val selected = points.firstOrNull { it.key == selectedKey }

    // --- The district borders, read the first time this map is actually DRAWN at district detail.
    // 82 KB and 972 polylines — three times the state file and the largest thing in res/raw — and the
    // NATION and STATE levels never draw a district border, so a reader who stays at those levels
    // never pays for it. Kept once read: a reader toggling District/State/District would otherwise
    // re-read the file on every third tap.
    //
    // Keyed on `drawnLevel`, not on `level`: `drawnLevel` is the level the CURRENT pins are grouped by,
    // so the borders and the pins change over together rather than the lines arriving one response
    // early and framing the previous level's points.
    LaunchedEffect(drawnLevel) {
        if (drawnLevel != "DISTRICT" || districtBorders != null) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { loadDistrictBorders(context) } }
            .onSuccess { districtBorders = it }
            .onFailure { failure -> if (failure is CancellationException) throw failure }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MapCard(title = "Where the work comes from", icon = Icons.Filled.Place) {
            Text(
                "Every documented record placed on the map twice over: where the craft is from, and " +
                    "where the recording was actually made. The two are not the same place, and the " +
                    "map says which is which.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )

            WorkshopScopeSelect(scope = workshopScope, label = "Workshops on this map")

            HorizontalDivider(color = MaterialTheme.field.hairline)

            DetailLevelControl(
                levels = current?.levels?.takeIf { it.isNotEmpty() } ?: FALLBACK_ADMIN_LEVELS,
                selected = level,
                onSelect = { level = it }
            )
        }

        if (focus != null) {
            FocusBanner(
                title = focus.title,
                place = focus.place,
                onClear = { focusCleared = true }
            )
        }

        when {
            error != null -> MapCard(title = "The map could not be loaded") {
                Text(error.orEmpty(), color = MaterialTheme.field.body, fontSize = 13.sp)
            }

            current == null -> MapCard(title = "Placing the records…") {
                Text(
                    "Reading every record's address and every recorded fix.",
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp
                )
            }

            else -> {
                MapCard(title = "The map") {
                    MapLegend(level = drawnLevel)
                    when {
                        points.isEmpty() -> Text(
                            if (!workshopScope.isAllRecords) {
                                "No records with a mapped address in the chosen workshops. Widen " +
                                    "the workshop scope, or choose All records."
                            } else {
                                "No records with a mapped address anywhere in the repository yet."
                            },
                            color = MaterialTheme.field.muted,
                            fontSize = 13.sp
                        )

                        geometryFailed -> Text(
                            "The national outline could not be read from the app, so the picture is " +
                                "not drawn. Every place is listed below with its counts and its " +
                                "precision.",
                            color = MaterialTheme.field.body,
                            fontSize = 13.sp
                        )

                        geometry == null -> Text(
                            "Drawing the outline…",
                            color = MaterialTheme.field.muted,
                            fontSize = 12.sp
                        )

                        else -> IndiaMapCanvas(
                            geometry = geometry!!,
                            stateBorders = stateBorders,
                            districtBorders = districtBorders,
                            level = drawnLevel,
                            points = points,
                            focusKeys = focusKeys,
                            selectedKey = selectedKey,
                            onSelect = { key -> selectedKey = if (selectedKey == key) null else key }
                        )
                    }
                }

                MapSummaryCard(
                    data = current,
                    drawnLevel = drawnLevel,
                    pointCount = points.size,
                    scopeSummary = workshopScope.summary
                )

                if (selected != null) {
                    MapPointPanel(
                        repository = repository,
                        point = selected,
                        level = level,
                        workshopScope = workshopScope.workshopIds,
                        onClose = { selectedKey = null },
                        onOpenRecord = onOpenRecord,
                        onError = onError
                    )
                }

                MapPlaceListCard(
                    points = points,
                    unplaced = current.unplaced,
                    focusKeys = focusKeys,
                    selectedKey = selectedKey,
                    onSelect = { key -> selectedKey = if (selectedKey == key) null else key }
                )
            }
        }

        if (loading && current != null) {
            Text("Placing the records…", color = MaterialTheme.field.muted, fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The controls
// ---------------------------------------------------------------------------------------------

/**
 * HOW THE SAME RECORDS ARE GROUPED. Three levels, because that is what an Indian address has above a
 * village, and each is a real unit somebody could draw a border around.
 *
 * The honesty paragraph is not decoration, and it is not the same paragraph it was. The app now ships
 * interior borders as well as the frontier (`res/raw/state_borders.bin`, `res/raw/district_borders.bin`
 * — see [IndiaOutline.kt]), so the old sentence claiming no state or district geometry was shipped had
 * become the misleading one. What replaces it says which lines are drawn at which level, that the
 * frontier does not move between levels because the interior lines are clipped to it, and the one gap
 * that is real: 43 of the 795 districts have no border in the published boundary data. Most were
 * notified after it was published; three of Delhi's thirteen are simply names the source does not use.
 * Naming the number is the point — a reader who spots a district with no line around it must be able to
 * tell a known gap from a bug in the drawing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailLevelControl(
    levels: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Detail", color = MaterialTheme.field.muted, fontSize = 12.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "How closely to group the records" }
        ) {
            levels.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(levelLabel(option), fontSize = 12.sp) }
                )
            }
        }
        levelHint(selected)?.let { hint ->
            Text(hint, color = MaterialTheme.field.muted, fontSize = 11.sp)
        }
        Text(
            "The outline is the international border, as the Government of India depicts it, and it " +
                "is the same line at every level: state and district borders are clipped to it, so " +
                "changing the detail never moves the national boundary. State borders are drawn at " +
                "State and District; district borders at District. Records stay labelled, sized " +
                "points per state or district rather than shaded regions — the pin says how many " +
                "records are there, the border says where the unit ends. 43 of the 795 districts have " +
                "no border of their own in the published boundary data — most were notified after it " +
                "was published. Their records are placed and counted exactly like every other, inside " +
                "the parent district each was carved from, or their state where that is not on record.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
    }
}

/** One record in context. The web's focus banner, with Clear dropping the focus rather than routing. */
@Composable
private fun FocusBanner(title: String, place: String?, onClear: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            .padding(14.dp)
    ) {
        Text(
            "Showing $title in context" +
                (place?.takeIf { it.isNotBlank() }?.let { " — recorded as “$it”" } ?: "") + ".",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = 13.sp
        )
        TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp)) {
            Text("Clear", fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The picture
// ---------------------------------------------------------------------------------------------

/** Smallest a pin may be drawn. Set by the COUNT THAT HAS TO FIT INSIDE IT, not by taste. */
private val PIN_MIN_RADIUS = 11.dp

/** Largest. Past this a busy district covers its neighbours rather than describing itself. */
private val PIN_MAX_RADIUS = 22.dp

/** Clear space between two pin edges, so neighbours read as two marks and not a figure of eight. */
private val PIN_GAP = 2.dp

/** Displacement is capped: past this a leader line is longer than the distance it explains. */
private val PIN_MAX_SHIFT = 34.dp

/** Breathing room around the coastline, so a pin on the Kerala coast is not half cut off. */
private val MAP_PADDING = 10.dp

/** Extra tap radius around a pin. A 22dp mark must not be a 22dp target on a dusty screen. */
private val PIN_TAP_SLOP = 10.dp

private const val PIN_LAYOUT_PASSES = 60

/**
 * How far from the drawn point the record could really be, in kilometres — the precision tiers made
 * visible. A STATE-precision pin is drawn at a seat and could be anywhere in the state, and it should
 * look like that, because the alternative is a confident dot on Jaipur for a record that only ever
 * said "Rajasthan".
 *
 * The zeroes are the two MEASUREMENTS (a device fix, and a pin somebody dropped on the subject's own
 * place) plus TOWN, where a halo would overstate the uncertainty in the other direction — and NATION,
 * which is the weighted mean of every placed record and so IS the aggregate rather than a guess at
 * where it might be. An unknown tier gets no halo: an invented radius is a claim.
 */
private fun uncertaintyKilometres(precision: String): Float = when (precision) {
    "DISTRICT" -> 45f
    "STATE" -> 220f
    else -> 0f
}

/**
 * The projection the outline and every pin share, resolved to this canvas's pixels.
 *
 * ONE function places the coastline and the pins, which is what stops a pin drifting off the land it
 * belongs to. World space is unit-width with equal scaling on both axes ([IndiaGeometry]), so the
 * whole frame is a scale and a translate.
 */
private class MapFrame(val geometry: IndiaGeometry, val widthPx: Float, padPx: Float) {
    /** The outline's width in pixels, inset so a coastal pin has somewhere to sit. */
    val contentWidth: Float = max(1f, widthPx - padPx * 2f)

    /** Canvas height. The box is `aspectRatio(1 / aspect)`, so this follows from the width. */
    val heightPx: Float = widthPx * geometry.aspect

    val offsetX: Float = padPx

    // The horizontal inset costs `padPx * aspect` of vertical room; half above, half below, so the
    // country sits centred in its box rather than riding the top edge.
    val offsetY: Float = padPx * geometry.aspect

    fun toCanvas(latitude: Double, longitude: Double): Offset {
        val world = geometry.world(latitude, longitude)
        return Offset(offsetX + world.x * contentWidth, offsetY + world.y * contentWidth)
    }

    /**
     * Pixels per kilometre, measured rather than assumed: one degree of longitude at the middle of
     * the country, projected, divided by the same degree's great-circle length.
     *
     * Mercator inflates north-south distance with latitude, so this is exact at the reference
     * latitude and slightly generous over Kashmir. That is the right direction to be wrong in for the
     * one thing it sizes — an uncertainty halo — and the scale bar is labelled, not claimed as
     * uniform.
     */
    val pxPerKilometre: Float = run {
        val latitude = 22.5
        val left = geometry.world(latitude, 78.0)
        val right = geometry.world(latitude, 79.0)
        val kilometres = haversineMetres(latitude, 78.0, latitude, 79.0) / 1000.0
        (((right.x - left.x) * contentWidth) / kilometres).toFloat()
    }
}

/** A point resolved to where its mark actually goes, and to the count drawn on it. */
private class PlacedPin(
    val point: MapPointDto,
    /** Where the mark is drawn. */
    val x: Float,
    val y: Float,
    /** Where the point actually is. Differs from x/y only when the pin had to be pushed clear. */
    val anchorX: Float,
    val anchorY: Float,
    val radius: Float,
    val displaced: Boolean,
    /** Radius of the "could be anywhere in here" halo, or 0 when a halo would be the overstatement. */
    val uncertainty: Float,
    val count: TextLayoutResult
)

/**
 * Turning points into pins that can actually be told apart.
 *
 * THE PROBLEM. One workshop with sixteen artisans is sixteen records on one coordinate, and a pile of
 * identical circles reads as a single record — the map understates itself by an order of magnitude
 * and gives no hint that it is doing so. There are two different collisions and they need two
 * different answers:
 *
 *   IDENTICAL coordinates are folded on the SERVER into one pin carrying `total`, `fixes` and
 *   `spreadMetres`. That is the only honest reduction: they really are one place.
 *
 *   NEARBY BUT DISTINCT places are handled HERE, by displacement rather than by folding. Bagru and
 *   Sanganer are 25 km apart — a few pixels at the scale a whole country is drawn at — but they are
 *   two towns with two different craft traditions, and merging them would erase a real distinction.
 *   The smaller pin is pushed clear and a hairline leader ties it back to its true coordinate, which
 *   is the standard cartographic answer and the only one that neither hides a place nor lies about
 *   where it is.
 *
 * Deterministic — same points in, same pins out — so nothing moves between frames, and it runs once
 * per data or size change rather than per frame.
 */
private fun layoutPins(
    points: List<MapPointDto>,
    frame: MapFrame,
    density: Density,
    measurer: TextMeasurer,
    originCountColor: Color,
    captureCountColor: Color
): List<PlacedPin> {
    // Four separate conversions rather than one `with(density) { … }` block: Kotlin forbids
    // initialising a captured `val` inside a lambda, so a block that assigned all four would not
    // compile.
    val minRadius = with(density) { PIN_MIN_RADIUS.toPx() }
    val maxRadius = with(density) { PIN_MAX_RADIUS.toPx() }
    val gap = with(density) { PIN_GAP.toPx() }
    val maxShift = with(density) { PIN_MAX_SHIFT.toPx() }

    val busiest = points.fold(0) { most, point -> max(most, point.total) }

    // Busiest first: the pin carrying the most records keeps its true position, and the ones pushed
    // aside are the ones whose displacement matters least.
    val ordered = points.sortedWith(compareByDescending<MapPointDto> { it.total }.thenBy { it.key })

    val placed = ArrayList<PlacedPin>(ordered.size)
    for (point in ordered) {
        val anchor = frame.toCanvas(point.latitude, point.longitude)
        // Square root, so the AREA of a pin is proportional to its count. Scaling the radius directly
        // would draw the venue's 317 records as a blob covering half the country.
        val share =
            if (busiest <= 0) 0f else (sqrt(point.total.toDouble()) / sqrt(busiest.toDouble())).toFloat()
        val radius = minRadius + (maxRadius - minRadius) * share

        var x = anchor.x
        var y = anchor.y
        for (pass in 0 until PIN_LAYOUT_PASSES) {
            val clash = placed.firstOrNull { other ->
                hypot(other.x - x, other.y - y) < other.radius + radius + gap
            } ?: break

            val needed = clash.radius + radius + gap
            var dx = x - clash.x
            var dy = y - clash.y
            var distance = hypot(dx, dy)
            if (distance < 0.001f) {
                // Exactly on top of one another. Step off along a direction derived from the KEY
                // rather than at random, so the same data always produces the same picture.
                val angle = (stableHash(point.key) % 360) * (PI / 180.0)
                dx = cos(angle).toFloat()
                dy = sin(angle).toFloat()
                distance = 1f
            }
            val push = (needed - distance) / distance
            x += dx * push
            y += dy * push

            // Never let a pin wander further from the truth than a leader line can usefully explain.
            val drift = hypot(x - anchor.x, y - anchor.y)
            if (drift > maxShift) {
                x = anchor.x + ((x - anchor.x) / drift) * maxShift
                y = anchor.y + ((y - anchor.y) / drift) * maxShift
                break
            }
        }

        x = x.coerceIn(radius, max(radius, frame.widthPx - radius))
        y = y.coerceIn(radius, max(radius, frame.heightPx - radius))

        placed += PlacedPin(
            point = point,
            x = x,
            y = y,
            anchorX = anchor.x,
            anchorY = anchor.y,
            radius = radius,
            displaced = hypot(x - anchor.x, y - anchor.y) > 0.75f,
            uncertainty = uncertaintyKilometres(point.precision) * frame.pxPerKilometre,
            count = measureCount(
                total = point.total,
                radius = radius,
                density = density,
                measurer = measurer,
                color = if (point.layer == "CAPTURE") captureCountColor else originCountColor
            )
        )
    }

    // Back into a stable draw order — largest last, so a big pin never hides a small one it was laid
    // out before. The layout order above is about who wins a collision, not about who is on top.
    return placed.sortedByDescending { it.radius }
}

/**
 * The count, measured so it can be centred on its pin.
 *
 * THE SIZE DELIBERATELY IGNORES THE SYSTEM FONT SCALE. This number lives inside a mark whose radius
 * comes from a record count, not from type: at font scale 2.0 a scaled "317" is wider than the pin it
 * is supposed to label, and a digit sticking out of a circle is worse than a small digit. Dividing by
 * `fontScale` cancels the multiplication Compose is about to apply. The accessible reading of every
 * count is the place list below, which is real text and honours the setting in full.
 */
private fun measureCount(
    total: Int,
    radius: Float,
    density: Density,
    measurer: TextMeasurer,
    color: Color
): TextLayoutResult {
    // Fewer digits, more room each — so "317" fits a big pin and "1" does not rattle around in a
    // small one. The same three steps the web's pin text uses.
    val factor = when {
        total >= 100 -> 0.72f
        total >= 10 -> 0.90f
        else -> 1.05f
    }
    val targetPx = radius * factor
    val fontSize = (targetPx / (density.density * density.fontScale)).sp
    return measurer.measure(
        AnnotatedString(total.toString()),
        style = TextStyle(
            fontFamily = FieldDisplayFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            color = color
        )
    )
}

/** Deterministic, so a tied pair steps apart the same way on every device. The web's `hash`. */
private fun stableHash(text: String): Int {
    var value = 0
    for (index in text.indices) {
        value = value * 31 + text[index].code
    }
    return value and 0x7FFFFFFF
}

/**
 * The country, the borders inside it, and its pins.
 *
 * IT IS A GRAPHIC, AND IT IS MARKED AS ONE — a single semantics node with a description, not a
 * tabbable pin per point. An accessibility tree full of circles is a screen reader saying "button,
 * button, button" with no way to compare anything; the place list below says everything this picture
 * says, as text, with the same selection. The description names the list so a TalkBack user is told
 * where the real interface is rather than left with an unexplained image.
 *
 * NO MAP SDK, NO TILES, NO ZOOM AND NO PAN. The boundary is the shipped official depiction (see
 * [IndiaOutline.kt]); a tile source would draw its own line for Jammu & Kashmir, need a key and a
 * network, and this app is used in villages with no signal.
 *
 * THE THREE LINE WEIGHTS ARE A HIERARCHY, AND THEY ARE ALL NEUTRAL. Frontier heaviest, state medium,
 * district lightest — because at district detail this canvas carries over a thousand polylines and a
 * flat weight would turn the country into a mesh with pins somewhere in it. None of them may be
 * purple: purple is this product's only action colour, it is what a pin is drawn in, and a purple
 * border would compete with the marks the map exists to show. They come off the ink ladder instead.
 *
 * [stateBorders] and [districtBorders] are null until their asset has been read (and stay null if the
 * read failed), so every layer below is conditional on its geometry as well as on [level].
 */
@Composable
private fun IndiaMapCanvas(
    geometry: IndiaGeometry,
    stateBorders: IndiaGeometry?,
    districtBorders: IndiaGeometry?,
    level: String,
    points: List<MapPointDto>,
    focusKeys: List<String>,
    selectedKey: String?,
    onSelect: (String) -> Unit
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    // The land and the coastline are derived from the ONE hue the design system has rather than from
    // a new colour: a map is not an action, so it may not introduce a second accent, and a wash of
    // primary reads as land in both the light and the dark scheme.
    val primary = MaterialTheme.colorScheme.primary
    val land = primary.copy(alpha = 0.08f)
    val coast = primary.copy(alpha = 0.38f)
    // The interior borders come off the INK ladder, not the accent: ink-500 for the states, ink-300 for
    // the districts. Both are neutral in light and dark, both sit below the coastline in weight, and
    // neither can be mistaken for a pin.
    val stateBorderInk = MaterialTheme.field.muted.copy(alpha = 0.65f)
    val districtBorderInk = MaterialTheme.field.placeholder.copy(alpha = 0.60f)
    val pinRim = MaterialTheme.colorScheme.surface
    val haloFill = primary.copy(alpha = 0.06f)
    val haloEdge = primary.copy(alpha = 0.30f)
    val leader = primary.copy(alpha = 0.55f)
    val scaleInk = MaterialTheme.field.muted
    val originCount = MaterialTheme.colorScheme.onPrimary
    val captureCount = primary

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        // A host that hands this an unbounded width would make every number below infinite, and
        // `2^ceil(log2(∞))` is what `buildOutlinePath` would then be asked to scale by — a path of NaN
        // points, which Skia draws as nothing at all after doing the work. Draw nothing on purpose
        // instead; the place list carries the whole reading either way.
        if (!widthPx.isFinite() || widthPx <= 0f) return@BoxWithConstraints
        val padPx = with(density) { MAP_PADDING.toPx() }
        val frame = remember(geometry, widthPx, padPx) { MapFrame(geometry, widthPx, padPx) }

        val pins = remember(points, frame, density, measurer, originCount, captureCount) {
            layoutPins(points, frame, density, measurer, originCount, captureCount)
        }

        // Built once per BUCKET rather than per frame: a Path with ~12k points is expensive to build
        // and cheap for Skia to transform, so the geometry is baked at the bucket scale and the
        // residual is applied as a transform. See `outlineBucketScale`.
        val bucketScale = remember(frame.contentWidth) { outlineBucketScale(frame.contentWidth) }
        val outline = remember(geometry, bucketScale) {
            buildOutlinePath(geometry, bucketScale).apply {
                // EvenOdd, because the geometry carries a hole. With the default winding rule the
                // hole fills in and the map claims land where there is none.
                fillType = PathFillType.EvenOdd
            }
        }
        val residual = frame.contentWidth / bucketScale

        // WHICH INTERIOR LAYERS THIS LEVEL EARNS. State borders from the state level down, because a
        // state-level pin is read inside a state; district borders only at district detail, where they
        // are the unit the pin names. An unknown level from a newer server draws neither — an invented
        // border is a claim, exactly as `levelHint` refuses to invent a hint.
        val showStateBorders = level == "STATE" || level == "DISTRICT"
        val showDistrictBorders = level == "DISTRICT"

        // Built once per bucket, on the same discipline as the outline, and NOT built at all for a
        // layer this level does not draw: the district file is 972 polylines and building its path to
        // then not stroke it is the one cost this screen can avoid for free.
        val stateBorderPath = remember(stateBorders, bucketScale, showStateBorders) {
            if (!showStateBorders) null else stateBorders?.let { buildBorderPath(it, bucketScale) }
        }
        val districtBorderPath = remember(districtBorders, bucketScale, showDistrictBorders) {
            if (!showDistrictBorders) null else districtBorders?.let { buildBorderPath(it, bucketScale) }
        }

        val focused = remember(focusKeys) { focusKeys.toSet() }
        val slopPx = with(density) { PIN_TAP_SLOP.toPx() }
        val hairlinePx = with(density) { 1.dp.toPx() }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                // A FIXED-HEIGHT box, derived from the country's own proportions — never
                // fillMaxSize. The host is a scrolling Column, so an unbounded height would either
                // collapse the canvas to nothing or hand it an infinite budget. The floor guards
                // against a corrupt aspect turning the divisor into zero.
                .aspectRatio(1f / max(0.1f, geometry.aspect))
                .semantics {
                    contentDescription = "Map of India showing where the documented records come " +
                        "from, with ${points.size} " + (if (points.size == 1) "point" else "points") +
                        ". Every place is listed below with its counts and how precisely it is known."
                }
                .pointerInput(pins, slopPx) {
                    detectTapGestures { tap ->
                        // Nearest hit wins, and small pins are tested too — a 1-record dot beside a
                        // 300-record disc must still be reachable.
                        pins
                            .filter { hypot(it.x - tap.x, it.y - tap.y) <= it.radius + slopPx }
                            .minByOrNull { hypot(it.x - tap.x, it.y - tap.y) }
                            ?.let { onSelect(it.point.key) }
                    }
                }
        ) {
            translate(left = frame.offsetX, top = frame.offsetY) {
                scale(scaleX = residual, scaleY = residual, pivot = Offset.Zero) {
                    drawPath(outline, color = land, style = Fill)
                    // The stroke width is divided back out of the residual scale so it stays a
                    // hairline. That hairline is what makes Lakshadweep visible: those islands are
                    // genuinely sub-pixel at this scale, and a fill alone would render the territory
                    // as nothing at all.
                    drawPath(outline, color = coast, style = Stroke(width = hairlinePx / residual))

                    // EVERY INTERIOR BORDER IS CLIPPED TO THE NATIONAL OUTLINE, and the reason is
                    // measured rather than tidy-minded: the district source these polylines were
                    // decomposed from disagrees with the shipped official depiction by up to ~0.02
                    // degrees (~2 km) at its outer extent, so unclipped they poke a few pixels past the
                    // frontier at the coast — a second, slightly wrong national boundary drawn beside
                    // the right one, and only at two of the three detail levels. Clipping makes the
                    // visible national boundary identical at all three levels by construction, which is
                    // the same guarantee `scripts/build_boundaries.py` gets by refusing to emit the
                    // frontier at all. It also costs nothing to state: the clip is the outline path
                    // that was just drawn, in the same coordinate space, so the two cannot drift.
                    if (stateBorderPath != null || districtBorderPath != null) {
                        clipPath(outline) {
                            // State first, district over it. They never coincide — an edge shared by
                            // two states is a state border and an edge inside one state is a district
                            // border, so the decomposition put each segment in exactly one file — but
                            // the order keeps the heavier line underneath where they meet at a corner.
                            stateBorderPath?.let {
                                drawPath(
                                    it,
                                    color = stateBorderInk,
                                    style = Stroke(width = hairlinePx * 0.8f / residual)
                                )
                            }
                            districtBorderPath?.let {
                                drawPath(
                                    it,
                                    color = districtBorderInk,
                                    style = Stroke(width = hairlinePx * 0.55f / residual)
                                )
                            }
                        }
                    }
                }
            }

            // Uncertainty first, under everything: a halo is context for its pin, not a mark of its
            // own.
            pins.filter { it.uncertainty > 3f }.forEach { pin ->
                drawCircle(
                    color = haloFill,
                    radius = pin.uncertainty,
                    center = Offset(pin.anchorX, pin.anchorY)
                )
                drawCircle(
                    color = haloEdge,
                    radius = pin.uncertainty,
                    center = Offset(pin.anchorX, pin.anchorY),
                    style = Stroke(
                        width = hairlinePx,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(hairlinePx * 4, hairlinePx * 5))
                    )
                )
            }

            // Leader lines tie a displaced pin back to where the place really is.
            pins.filter { it.displaced }.forEach { pin ->
                drawLine(
                    color = leader,
                    start = Offset(pin.anchorX, pin.anchorY),
                    end = Offset(pin.x, pin.y),
                    strokeWidth = hairlinePx * 1.2f
                )
                drawCircle(
                    color = leader,
                    radius = hairlinePx * 1.6f,
                    center = Offset(pin.anchorX, pin.anchorY)
                )
            }

            pins.forEach { pin ->
                drawPin(
                    pin = pin,
                    primary = primary,
                    rim = pinRim,
                    isFocused = focused.contains(pin.point.key),
                    isSelected = pin.point.key == selectedKey,
                    hairlinePx = hairlinePx
                )
            }

            drawScaleBar(frame = frame, measurer = measurer, density = density, ink = scaleInk)
        }
    }
}

/**
 * One mark.
 *
 * THE TWO LAYERS ARE TOLD APART BY SHAPE, NOT COLOUR: ORIGIN is a filled disc, CAPTURE is a ring.
 * Purple is this product's only action colour, so hue is not available to carry the distinction, and
 * a second hue would put a colour on screen that belongs to no design system.
 *
 * The web draws the capture ring as a target — a thick ring with a bullseye dot. Here the bullseye is
 * a second concentric ring instead, because the middle of the mark is where the COUNT goes and a dot
 * under a digit is a smudge. It still reads as a target and still cannot be mistaken for a disc.
 */
private fun DrawScope.drawPin(
    pin: PlacedPin,
    primary: Color,
    rim: Color,
    isFocused: Boolean,
    isSelected: Boolean,
    hairlinePx: Float
) {
    val centre = Offset(pin.x, pin.y)

    if (isFocused) {
        drawCircle(
            color = primary,
            radius = pin.radius + hairlinePx * 5,
            center = centre,
            style = Stroke(
                width = hairlinePx * 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(hairlinePx * 5, hairlinePx * 4))
            )
        )
    }
    if (isSelected) {
        drawCircle(color = primary, radius = pin.radius + hairlinePx * 2.5f, center = centre, style = Stroke(width = hairlinePx * 2f))
    }

    if (pin.point.layer == "CAPTURE") {
        val ring = hairlinePx * 3.5f
        drawCircle(color = rim, radius = pin.radius, center = centre)
        drawCircle(color = primary, radius = pin.radius - ring / 2f, center = centre, style = Stroke(width = ring))
        drawCircle(
            color = primary.copy(alpha = 0.45f),
            radius = max(hairlinePx, pin.radius - ring * 2f),
            center = centre,
            style = Stroke(width = hairlinePx)
        )
    } else {
        drawCircle(color = primary, radius = pin.radius, center = centre)
        drawCircle(color = rim, radius = pin.radius, center = centre, style = Stroke(width = hairlinePx * 1.6f))
    }

    // The count belongs ON the pin, on EVERY pin: it is the one mark that stops a place holding nine
    // records from reading exactly like a place holding one.
    drawText(
        pin.count,
        topLeft = Offset(
            pin.x - pin.count.size.width / 2f,
            pin.y - pin.count.size.height / 2f
        )
    )
}

/** Without one, nobody can tell whether two pins are 20 km apart or 200. */
private fun DrawScope.drawScaleBar(
    frame: MapFrame,
    measurer: TextMeasurer,
    density: Density,
    ink: Color
) {
    val kilometres = 500
    val length = kilometres * frame.pxPerKilometre
    if (length <= 0f || length > frame.contentWidth * 0.6f) return

    val hairline = with(density) { 1.dp.toPx() }
    val x = frame.offsetX + with(density) { 4.dp.toPx() }
    val y = frame.heightPx - with(density) { 18.dp.toPx() }
    val tick = with(density) { 4.dp.toPx() }

    drawLine(ink, Offset(x, y), Offset(x + length, y), strokeWidth = hairline * 1.6f)
    drawLine(ink, Offset(x, y - tick), Offset(x, y + tick), strokeWidth = hairline * 1.6f)
    drawLine(ink, Offset(x + length, y - tick), Offset(x + length, y + tick), strokeWidth = hairline * 1.6f)

    val label = measurer.measure(
        AnnotatedString("$kilometres km"),
        style = TextStyle(fontFamily = FieldBodyFontFamily, fontSize = 10.sp, color = ink)
    )
    drawText(label, topLeft = Offset(x, y + tick + hairline))
}

/**
 * What the shapes mean. Drawn with the same primitives the map uses, so the swatch and the mark
 * cannot drift apart, and worded exactly as the web's legend words it — with one addition the web has
 * not got yet: the line entry names the interior borders this client now draws. A key that listed only
 * the frontier while the canvas carried three different line weights would leave a reader to guess
 * what the two faint ones are, which is worse than being one entry ahead of the laptop.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapLegend(level: String) {
    val primary = MaterialTheme.colorScheme.primary
    val rim = MaterialTheme.colorScheme.surface

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        LegendEntry("Where the craft is from") { extent ->
            drawCircle(color = primary, radius = extent / 2f, center = Offset(extent / 2f, extent / 2f))
        }
        LegendEntry("Where it was recorded") { extent ->
            drawCircle(color = rim, radius = extent / 2f, center = Offset(extent / 2f, extent / 2f))
            drawCircle(
                color = primary,
                radius = extent / 2f - extent * 0.14f,
                center = Offset(extent / 2f, extent / 2f),
                style = Stroke(width = extent * 0.28f)
            )
        }
        LegendEntry("How far off the point could be") { extent ->
            drawCircle(
                color = primary.copy(alpha = 0.06f),
                radius = extent / 2f,
                center = Offset(extent / 2f, extent / 2f)
            )
            drawCircle(
                color = primary.copy(alpha = 0.30f),
                radius = extent / 2f,
                center = Offset(extent / 2f, extent / 2f),
                style = Stroke(
                    width = extent * 0.08f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
                )
            )
        }
        Text(
            when (level) {
                "DISTRICT" -> "Lines: international border, then state and district borders"
                "STATE" -> "Lines: international border, then state borders"
                // Nation — and any level a newer server invents, which draws no interior border.
                else -> "Outline: international border"
            },
            color = MaterialTheme.field.placeholder,
            fontSize = 11.sp
        )
    }
}

/** [swatch] is handed the square's side in pixels; naming it `extent` keeps `DrawScope.size` clear. */
@Composable
private fun LegendEntry(label: String, swatch: DrawScope.(Float) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Canvas(modifier = Modifier.size(11.dp)) { swatch(size.minDimension) }
        Text(label, color = MaterialTheme.field.muted, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// What the map is standing on
// ---------------------------------------------------------------------------------------------

/**
 * The numbers behind the picture, including the ones that say how good the picture is.
 *
 * `summary.address` and `anchoredDistricts`/`anchorPins` are on screen because the map's quality is
 * now a FUNCTION of those fields: a pin sitting at a state capital is not a bug in the map, it is a
 * district nobody filled in — and that is only actionable if the numbers are visible.
 */
@Composable
private fun MapSummaryCard(
    data: MapPointsDto,
    drawnLevel: String,
    pointCount: Int,
    scopeSummary: String
) {
    val summary = data.summary
    MapCard(title = scopeCopy(data.scope)) {
        Text(
            "Grouped by ${levelLabel(drawnLevel).lowercase(Locale.ROOT)}. $scopeSummary",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MapStatTile("Records matched", summary.records, null, Modifier.weight(1f))
            MapStatTile(
                when (drawnLevel) {
                    "DISTRICT" -> "Districts on the map"
                    "STATE" -> "States on the map"
                    else -> "Points on the map"
                },
                pointCount,
                null,
                Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MapStatTile("Placed by address", summary.originRecords, Icons.Filled.Place, Modifier.weight(1f))
            MapStatTile("Placed by GPS fix", summary.captureRecords, Icons.Filled.GpsFixed, Modifier.weight(1f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier.size(14.dp)
            )
            Text(
                buildString {
                    append(
                        "The two counts overlap and do not add up: one record can be both from " +
                            "Bagru and recorded at a workshop in Kharagpur."
                    )
                    if (summary.originExcludes.isNotEmpty()) {
                        // The bucket names arrive lower-case from the API and this is the start of a
                        // sentence; "media carry no place" reads as a broken line.
                        append(" ")
                        append(
                            summary.originExcludes.joinToString(", ") {
                                it.replaceFirstChar { c -> c.uppercaseChar() }
                            }
                        )
                        append(
                            " carry no place column of their own, so they are placed only by the " +
                                "address on the location they were captured at."
                        )
                    }
                    if (summary.captureTruncated) {
                        append(" More GPS fixes matched than this map will fold; the busiest are shown.")
                    }
                    if (summary.clusterKilometres > 0) {
                        append(" GPS fixes within ${summary.clusterKilometres} km are drawn as one pin.")
                    }
                },
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
        }

        val address = summary.address
        if (address.locations > 0) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    "Address detail on the ${address.locations} " +
                        (if (address.locations == 1) "location" else "locations") + " in scope",
                    color = MaterialTheme.field.muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${address.withState} with a state · ${address.withDistrict} with a district · " +
                        "${address.withPincode} with a pincode · ${address.withSubjectPin} with a " +
                        "pin on the subject’s place.",
                    color = MaterialTheme.field.body,
                    fontSize = 11.sp
                )
                if (address.withDistrict < address.locations) {
                    Text(
                        "Records with no district are drawn at their state’s seat and say so. " +
                            "Filling in the district on the record moves the pin.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                }
                Text(
                    "The map knows a position for ${summary.anchoredDistricts} " +
                        (if (summary.anchoredDistricts == 1) "district" else "districts") +
                        ", learned from ${summary.anchorPins} dropped " +
                        (if (summary.anchorPins == 1) "pin" else "pins") + ".",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun MapStatTile(label: String, value: Int, icon: ImageVector?, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                label,
                color = MaterialTheme.field.muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            value.toString(),
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The map as text — the other half of the same instrument
// ---------------------------------------------------------------------------------------------

/**
 * Every place, as a list. Not a fallback: the canvas is one unlabelled graphic, so this is the only
 * way a TalkBack user reads the map, and it is therefore held to a higher bar than a caption —
 * every place, every count, the precision of every point, and the same selection.
 */
@Composable
private fun MapPlaceListCard(
    points: List<MapPointDto>,
    unplaced: List<MapUnplacedDto>,
    focusKeys: List<String>,
    selectedKey: String?,
    onSelect: (String) -> Unit
) {
    MapCard(title = "Every place, as a list") {
        Text(
            "The same information as the map, at the same detail level. Choose a place to see the " +
                "records there.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )

        if (points.isEmpty() && unplaced.isEmpty()) {
            Text("Nothing to place.", color = MaterialTheme.field.muted, fontSize = 12.sp)
            return@MapCard
        }

        points.forEach { point ->
            PlaceRow(
                point = point,
                isSelected = point.key == selectedKey,
                isFocused = focusKeys.contains(point.key),
                onClick = { onSelect(point.key) }
            )
        }

        if (unplaced.isNotEmpty()) UnplacedBlock(unplaced)
    }
}

@Composable
private fun PlaceRow(
    point: MapPointDto,
    isSelected: Boolean,
    isFocused: Boolean,
    onClick: () -> Unit
) {
    val buckets = point.counts.byBucket().filter { it.second > 0 }

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.field.surface50,
                MaterialTheme.shapes.small
            )
            .border(
                1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.field.hairline,
                MaterialTheme.shapes.small
            )
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        // The layer, as the shape on the map says it: a place mark for an origin, a crosshair for a
        // measured fix.
        Icon(
            if (point.layer == "CAPTURE") Icons.Filled.GpsFixed else Icons.Filled.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    point.label,
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                MapPill(precisionBadge(point.precision))
                if (isFocused) MapPill("This record", emphasised = true)
            }

            Text(point.region, color = MaterialTheme.field.muted, fontSize = 11.sp)

            Text(
                "${point.total} " + (if (point.total == 1) "record" else "records") +
                    (
                        if (buckets.isEmpty()) "" else buckets.joinToString(
                            separator = ", ",
                            prefix = " — "
                        ) { (bucket, count) -> countLabel(bucket, count) }
                        ),
                color = MaterialTheme.field.body,
                fontSize = 12.sp
            )

            Text(
                buildString {
                    append(precisionNote(point.precision))
                    if (point.layer == "CAPTURE" && point.fixes > 0) {
                        append(" · ${point.fixes} ")
                        append(if (point.fixes == 1) "fix" else "fixes")
                        append(" spread across ${formatSpread(point.spreadMetres)}")
                        point.medianAccuracy?.let { append(", median accuracy ±${formatMetres(it)} m") }
                    }
                    // Grouping to a district loses no information — it moves the finer names here.
                    if (point.layer == "ORIGIN" && point.places.isNotEmpty()) {
                        append(" · covers ")
                        append(point.places.take(3).joinToString(", ") { "“$it”" })
                        if (point.places.size > 3) append(" and ${point.places.size - 3} more")
                    }
                    // How much of this point's POSITION is measurement. A district holding forty
                    // records of which two carry a pin is a different thing from one where all forty
                    // do, and the pin looks identical either way.
                    if (point.layer == "ORIGIN" && point.total > 0) {
                        append(" · ")
                        append(
                            when {
                                point.pinnedRecords == 0 -> "no dropped pins here yet"
                                point.pinnedRecords == point.total -> "every record here is pinned"
                                else -> "${point.pinnedRecords} of ${point.total} pinned"
                            }
                        )
                    }
                    if (point.fromPlaceText > 0) {
                        append(" · ${point.fromPlaceText} ")
                        append(if (point.fromPlaceText == 1) "record placed" else "records placed")
                        append(" from a typed place name rather than a stated address")
                    }
                },
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * What could not be placed at all.
 *
 * A place quietly missing from a map is indistinguishable from a place with no records, so this is
 * named rather than dropped. These rows are counted in every total above and have no pin.
 */
@Composable
private fun UnplacedBlock(unplaced: List<MapUnplacedDto>) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.warningContainer, MaterialTheme.shapes.small)
            .padding(12.dp)
    ) {
        Text(
            "Not on the map",
            display = true,
            color = MaterialTheme.field.onWarningContainer,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "These records carry no state on their location and no place name the atlas can " +
                "resolve, so there is nothing to place them by. They are counted in the totals but " +
                "have no pin. Adding a state — and ideally a district — to the record puts it on " +
                "the map.",
            color = MaterialTheme.field.onWarningContainer,
            fontSize = 11.sp
        )
        unplaced.forEach { entry ->
            Text(
                "${entry.label} — ${entry.total} " + (if (entry.total == 1) "record" else "records"),
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 11.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// What is behind one pin
// ---------------------------------------------------------------------------------------------

/**
 * The records at one point, fetched when a reader opens it rather than carried by `/map/points` —
 * the aggregate is a couple of dozen pins, but the records behind every pin would be the whole corpus
 * in a payload that exists to draw thirteen dots.
 *
 * FETCHED WITH THE SAME FILTERS THE MAP WAS DRAWN WITH, [level] and [workshopScope] included. The key
 * names an administrative unit; which records sit in it is exactly what the scope decides, so a panel
 * fetched with a different scope would list records the pin was not counting.
 */
@Composable
private fun MapPointPanel(
    repository: FieldRepository,
    point: MapPointDto,
    level: String,
    workshopScope: List<String>,
    onClose: () -> Unit,
    onOpenRecord: ((recordType: String, recordId: String) -> Unit)?,
    onError: (String) -> Unit
) {
    var items by remember(point.key) { mutableStateOf<List<MapPointRecordDto>?>(null) }
    var truncated by remember(point.key) { mutableStateOf(false) }
    var panelError by remember(point.key) { mutableStateOf<String?>(null) }

    val workshopKey = workshopScope.joinToString(",")
    LaunchedEffect(point.key, level, workshopKey) {
        items = null
        panelError = null
        runCatching {
            repository.mapPointRecords(
                key = point.key,
                workshopIds = workshopScope,
                level = level
            )
        }
            .onSuccess { result ->
                items = result.items
                truncated = result.truncated
            }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                val message = failure.apiErrorMessage("These records could not be loaded.")
                panelError = message
                onError(message)
            }
    }

    MapCard(
        title = point.label,
        trailing = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close ${point.label}",
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    ) {
        Text(point.region, color = MaterialTheme.field.muted, fontSize = 11.sp)

        // Every finer place name that folded into this point. Grouping to a district does not lose
        // the town it was documented in — it says so here.
        if (point.places.isNotEmpty()) {
            Text(
                "Covers ${point.places.take(4).joinToString(", ")}" +
                    (if (point.places.size > 4) " and ${point.places.size - 4} more" else "") + ".",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }

        if (point.layer == "ORIGIN" && point.total > 0) {
            Text(
                when {
                    point.pinnedRecords == point.total ->
                        "Every record here carries a pin on the subject's own place."
                    point.pinnedRecords == 0 ->
                        "No record here carries a pin on the subject's place — this point comes " +
                            "from the stated address."
                    else ->
                        "${point.pinnedRecords} of ${point.total} records here carry a pin on the " +
                            "subject's place."
                },
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }

        val rows = items
        when {
            panelError != null -> Text(
                panelError.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )

            rows == null -> Text(
                "Loading the records here…",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )

            rows.isEmpty() -> Text(
                "No records to list at this point.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )

            else -> {
                rows.forEach { row -> PointRecordRow(row, onOpenRecord) }
                if (truncated) {
                    Text(
                        "Showing the most recent of each type. Open Browse records for the full list.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PointRecordRow(
    row: MapPointRecordDto,
    onOpenRecord: ((recordType: String, recordId: String) -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
            // Only clickable when the host gave us somewhere to go. A row that looks tappable and is
            // not is worse than a row that does not.
            .let { base ->
                if (onOpenRecord == null) base
                else base.clickable {
                    onOpenRecord?.invoke(singularRecordType(row.type), row.id)
                }
            }
            .heightIn(min = 44.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            row.type.uppercase(Locale.ROOT),
            color = MaterialTheme.field.muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.width(62.dp)
        )
        Text(
            row.title.ifBlank { "Untitled" },
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (row.status.isNotBlank()) MapPill(statusLabel(row.status))
    }
}

// ---------------------------------------------------------------------------------------------
// Local shapes
//
// A deliberate local copy of the app's standard record card. MainActivity's `RecordCard` is
// file-private in a 10k-line file owned by one agent, so — exactly as `AppearanceScreen`'s
// `PreferenceCard` and `SearchScreen`'s `SearchCard` do — this screen restates the one shape it needs
// rather than forcing an import out of that file.
// ---------------------------------------------------------------------------------------------

@Composable
private fun MapCard(
    title: String,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    title,
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

/** A small pill: a precision tier, a status, or "This record". */
@Composable
private fun MapPill(text: String, emphasised: Boolean = false) {
    val background =
        if (emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.field.surface100
    val foreground =
        if (emphasised) MaterialTheme.colorScheme.onPrimary else MaterialTheme.field.muted
    Text(
        text,
        color = foreground,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .background(background, CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}
