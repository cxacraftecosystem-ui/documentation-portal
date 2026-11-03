package com.fieldrepository.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import com.fieldrepository.app.data.MapPointChildDto
import com.fieldrepository.app.data.MapPointDto
import com.fieldrepository.app.data.MapPointRecordDto
import com.fieldrepository.app.data.MapPointsDto
import com.fieldrepository.app.data.MapUnplacedDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val FALLBACK_ADMIN_LEVELS = listOf("NATION", "STATE", "DISTRICT")

private const val DEFAULT_ADMIN_LEVEL = "DISTRICT"

private const val FLASH_MILLIS = 1400L

private fun levelLabel(level: String): String = when (level) {
    "NATION" -> "Nation"
    "STATE" -> "State"
    "DISTRICT" -> "District"
    else -> level.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
}

private fun levelHint(level: String): String? = when (level) {
    "NATION" -> "Every placed record as one point, inside the international border."
    "STATE" -> "One point per state. Districts inside a state fold together."
    "DISTRICT" -> "One point per district — the finest unit an address can name."
    else -> null
}

private fun scopeCopy(scope: String): String = when (scope) {
    "all" -> "All records"
    "filtered" -> "The records matching your filters"
    "record" -> "One record, shown in context"
    else -> "The records on this map"
}

private fun precisionNote(precision: String): String = when (precision) {
    "SUBJECT_PIN" -> "pin dropped on the subject's own place"
    "MEASURED" -> "GPS fix taken while recording"
    "TOWN" -> "town located from the typed place name"
    "DISTRICT" -> "district position worked out from pins inside it"
    "STATE" -> "state only — drawn at the state's seat"
    "NATION" -> "every placed record, folded into one point"
    else -> "position of unstated precision"
}

private fun precisionBadge(precision: String): String = when (precision) {
    "SUBJECT_PIN" -> "Pinned"
    "MEASURED" -> "Measured"
    "TOWN" -> "Town"
    "DISTRICT" -> "District"
    "STATE" -> "State"
    "NATION" -> "Nation"
    else -> precision
}

private fun MapCountsDto.byBucket(): List<Pair<String, Int>> = listOf(
    "artisans" to artisans,
    "workshops" to workshops,
    "products" to products,
    "tools" to tools,
    "media" to media
)

private fun countLabel(bucket: String, count: Int): String {
    val noun = when (bucket) {
        "artisans" -> if (count == 1) "artisan" else "artisans"
        "workshops" -> if (count == 1) "workshop" else "workshops"
        "products" -> if (count == 1) "product" else "products"
        "tools" -> if (count == 1) "tool" else "tools"
        "media" -> if (count == 1) "media file" else "media files"
        else -> bucket
    }
    return "$count $noun"
}

private fun singularRecordType(bucket: String): String = when (bucket) {
    "artisans" -> "artisan"
    "workshops" -> "workshop"
    "products" -> "product"
    "tools" -> "tool"
    "media" -> "media"
    else -> bucket
}

private fun statusLabel(status: String): String = when (status.uppercase(Locale.ROOT)) {
    "DRAFT" -> "Draft"
    "PENDING" -> "Pending"
    "APPROVED" -> "Approved"
    "REJECTED" -> "Rejected"
    "NEEDS_REVISION" -> "Needs revision"
    else -> status.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
}

private fun formatSpread(metres: Int): String = when {
    metres < 1 -> "a single point"
    metres < 1000 -> "$metres m"
    else -> "${String.format(Locale.ROOT, "%.1f", metres / 1000.0)} km"
}

private fun formatMetres(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
    else String.format(Locale.ROOT, "%.1f", value)

@Composable
fun MapScreen(
    repository: FieldRepository,
    onError: (String) -> Unit,
    focusType: String? = null,
    focusId: String? = null,
    onOpenRecord: ((recordType: String, recordId: String) -> Unit)? = null
) {
    var view by rememberSaveable { mutableStateOf(MAP_VIEW_PLACES) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MapViewSwitch(view = view, onChange = { view = it })
        if (view == MAP_VIEW_COVERAGE) {
            CoverageScreen(
                fetchCoverage = { query -> repository.coverage(query) },
                fetchUnitRecords = { query, state, district, bucket, cursor ->
                    repository.coverageUnitRecords(state, district, query, bucket, cursor)
                },
                errorMessage = { failure, fallback -> failure.apiErrorMessage(fallback) },
                onError = onError,
                onOpenRecord = onOpenRecord
            )
        } else {
            PlacesMapSection(
                repository = repository,
                onError = onError,
                focusType = focusType,
                focusId = focusId,
                onOpenRecord = onOpenRecord
            )
        }
    }
}

internal const val MAP_VIEW_PLACES = "places"
internal const val MAP_VIEW_COVERAGE = "coverage"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapViewSwitch(view: String, onChange: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        listOf(MAP_VIEW_PLACES to "Places", MAP_VIEW_COVERAGE to "Coverage").forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = view == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                modifier = Modifier.heightIn(min = 48.dp).testTag("map-view-$value")
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun PlacesMapSection(
    repository: FieldRepository,
    onError: (String) -> Unit,
    focusType: String?,
    focusId: String?,
    onOpenRecord: ((recordType: String, recordId: String) -> Unit)?
) {
    val context = LocalContext.current

    var geometry by remember { mutableStateOf<IndiaGeometry?>(null) }
    var geometryFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { loadIndiaGeometry(context) } }
            .onSuccess { geometry = it }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                geometryFailed = true
            }
    }

    var stateBorders by remember { mutableStateOf<IndiaGeometry?>(null) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { loadStateBorders(context) } }
            .onSuccess { stateBorders = it }
            .onFailure { failure -> if (failure is CancellationException) throw failure }
    }

    var districtBorders by remember { mutableStateOf<IndiaGeometry?>(null) }

    val workshopScope = rememberWorkshopScope(repository = repository, onError = onError)

    var level by remember { mutableStateOf(DEFAULT_ADMIN_LEVEL) }

    var stateFilter by rememberSaveable { mutableStateOf("") }
    var districtShapes by remember { mutableStateOf<List<DistrictShape>?>(null) }
    LaunchedEffect(stateFilter) {
        if (stateFilter.isEmpty() || districtShapes != null) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { loadDistrictShapes(context.resources) } }
            .onSuccess { districtShapes = it }
            .onFailure { failure -> if (failure is CancellationException) throw failure }
    }

    var focusCleared by remember { mutableStateOf(false) }
    val activeFocusType = if (focusCleared) null else focusType?.takeIf { it.isNotBlank() }
    val activeFocusId = if (focusCleared) null else focusId?.takeIf { it.isNotBlank() }

    var data by remember { mutableStateOf<MapPointsDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var selectedKey by remember { mutableStateOf<String?>(null) }

    var expandedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    var revealKey by remember { mutableStateOf<String?>(null) }
    var revealNonce by remember { mutableStateOf(0) }
    var flashKey by remember { mutableStateOf<String?>(null) }
    var flashNonce by remember { mutableStateOf(0) }

    var drillTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    val listScroll = rememberScrollState()

    fun reveal(key: String) {
        revealKey = key
        revealNonce += 1
        flashKey = key
        flashNonce += 1
    }

    fun onSelectPoint(key: String) {
        if (selectedKey == key) {
            selectedKey = null
            return
        }
        selectedKey = key
        val point = data?.points?.firstOrNull { it.key == key }
        if ((point?.children?.size ?: 0) > 1) expandedKeys = expandedKeys + key
        reveal(key)
    }

    LaunchedEffect(flashNonce) {
        if (flashKey == null) return@LaunchedEffect
        delay(FLASH_MILLIS)
        flashKey = null
    }

    LaunchedEffect(level, workshopScope.requestKey, workshopScope.settled, activeFocusType, activeFocusId, loadAttempt) {
        if (!workshopScope.settled) return@LaunchedEffect
        loading = true
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
                val drill = drillTarget?.takeIf { it.second == (result.level.ifBlank { level }) }
                if (drill != null || drillTarget?.second != level) drillTarget = null
                val landed = drill?.first?.takeIf { key -> result.points.any { it.key == key } }
                selectedKey = landed ?: result.focus?.pointKeys?.firstOrNull()
                expandedKeys = emptySet()
                landed?.let { reveal(it) }
                error = null
                loading = false
            }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                val message = failure.apiErrorMessage("The map could not be loaded.")
                error = message
                loading = false
                drillTarget = null
            }
    }

    val current = data
    val allPoints = current?.points ?: emptyList()
    val stateOptions = remember(allPoints) { placesStateNames(allPoints) }
    LaunchedEffect(stateOptions, current) {
        if (current != null && stateFilter.isNotEmpty() && stateFilter !in stateOptions) stateFilter = ""
    }
    val points = remember(allPoints, stateFilter) { placesInState(allPoints, stateFilter) }
    val fitBox = remember(geometry, districtShapes, allPoints, stateFilter) {
        geometry?.let { placesStateBox(it, districtShapes, allPoints, stateFilter) }
    }
    val drawnLevel = current?.level?.ifBlank { level } ?: level
    val focus = current?.focus
    val focusKeys = focus?.pointKeys ?: emptyList()
    val selected = points.firstOrNull { it.key == selectedKey }

    LaunchedEffect(drawnLevel) {
        if (drawnLevel != "DISTRICT" || districtBorders != null) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { loadDistrictBorders(context) } }
            .onSuccess { districtBorders = it }
            .onFailure { failure -> if (failure is CancellationException) throw failure }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MapCard(title = "Where the work comes from", icon = Icons.Filled.Place) {
            Text(
                "Where each craft is from, and where it was recorded.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )

            WorkshopScopeSelect(scope = workshopScope, label = "Workshops on this map")

            if (stateOptions.isNotEmpty()) {
                SearchableSelectField(
                    label = "State",
                    options = stateOptions.map { SelectOption(it, it) },
                    selectedValue = stateFilter,
                    placeholder = "All of India",
                    searchable = true,
                    onSelect = { value ->
                        stateFilter = value
                        selectedKey = null
                    },
                    modifier = Modifier.testTag("places-state-filter")
                )
            }

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
            error != null -> LoadFailedCard(
                message = error.orEmpty(),
                onRetry = { loadAttempt += 1 }
            )

            current == null -> MapCard(title = "Placing the records…") {
                Text(
                    "Loading places…",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            else -> {
                MapCard(title = "The map") {
                    MapLegend(level = drawnLevel)
                    when {
                        points.isEmpty() -> Text(
                            if (!workshopScope.isAllRecords) {
                                "No mapped records in these workshops."
                            } else {
                                "No records with a mapped address yet."
                            },
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        geometryFailed -> Text(
                            "Map outline unavailable. Places are listed below.",
                            color = MaterialTheme.field.body,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        geometry == null -> Text(
                            "Drawing the outline…",
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall
                        )

                        else -> IndiaMapCanvas(
                            geometry = geometry!!,
                            stateBorders = stateBorders,
                            districtBorders = districtBorders,
                            level = drawnLevel,
                            points = points,
                            focusKeys = focusKeys,
                            selectedKey = selectedKey,
                            fitBox = fitBox,
                            onSelect = { key -> onSelectPoint(key) }
                        )
                    }
                    if (points.isNotEmpty()) {
                        Text(
                            "Tap a pin to see it in the list.",
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall
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
                    childLevel = current.childLevel,
                    expandedKeys = expandedKeys,
                    flashKey = flashKey,
                    flashNonce = flashNonce,
                    revealKey = revealKey,
                    revealNonce = revealNonce,
                    listScroll = listScroll,
                    onSelect = { key -> onSelectPoint(key) },
                    onToggleExpanded = { key ->
                        expandedKeys = if (expandedKeys.contains(key)) expandedKeys - key else expandedKeys + key
                    },
                    onDrillDown = { child ->
                        val target = child.level
                        if (!target.isNullOrBlank()) {
                            if (target == level) {
                                selectedKey = child.key
                                reveal(child.key)
                            } else {
                                drillTarget = child.key to target
                                level = target
                            }
                        }
                    }
                )
            }
        }

        if (loading && current != null) {
            Text("Placing the records…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailLevelControl(
    levels: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Detail", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
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
                    label = { Text(levelLabel(option), style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
        levelHint(selected)?.let { hint ->
            Text(hint, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "International border as depicted by the Government of India. 43 newer districts have no published border; their records sit in the parent district.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

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
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp)) {
            Text("Clear", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private val PIN_MIN_RADIUS = 11.dp

private val PIN_MAX_RADIUS = 22.dp

private val PIN_GAP = 2.dp

private val PIN_MAX_SHIFT = 34.dp

private val MAP_PADDING = 10.dp

private val PIN_TAP_SLOP = 10.dp

private const val PIN_LAYOUT_PASSES = 60

private fun uncertaintyKilometres(precision: String): Float = when (precision) {
    "DISTRICT" -> 45f
    "STATE" -> 220f
    else -> 0f
}

internal fun placesStateNames(points: List<MapPointDto>): List<String> =
    points.flatMap { point ->
        listOf(point.state) + point.children.map { child -> child.state ?: child.label.takeIf { child.level == "STATE" } }
    }
        .mapNotNull { name -> name?.trim()?.takeIf { it.isNotEmpty() } }
        .distinct()
        .sortedBy { it.lowercase(Locale.ROOT) }

internal fun placesInState(points: List<MapPointDto>, state: String): List<MapPointDto> =
    if (state.isEmpty()) points else points.filter { it.state.isNullOrBlank() || it.state == state }

internal fun placesStateBox(
    geometry: IndiaGeometry,
    shapes: List<DistrictShape>?,
    points: List<MapPointDto>,
    state: String,
): Rect? {
    if (state.isEmpty()) return null
    val worlds = points.flatMap { point ->
        val own = if (point.state == state) listOf(geometry.world(point.latitude, point.longitude)) else emptyList()
        own + point.children
            .filter { it.state == state || (it.level == "STATE" && it.label == state) }
            .map { geometry.world(it.latitude, it.longitude) }
    }
    if (worlds.isEmpty()) return null
    val all = shapes.orEmpty()
    val stateLgds = worlds.mapNotNull { w -> all.firstOrNull { it.contains(w) }?.stateLgd }.toSet()
    val boxes = stateLgds.mapNotNull { lgd -> stateBounds(all, lgd) }
    if (boxes.isNotEmpty()) {
        return boxes.reduce { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
    }
    return Rect(worlds.minOf { it.x }, worlds.minOf { it.y }, worlds.maxOf { it.x }, worlds.maxOf { it.y })
}

private class MapFrame(val geometry: IndiaGeometry, val widthPx: Float, padPx: Float) {
    val contentWidth: Float = max(1f, widthPx - padPx * 2f)

    val heightPx: Float = widthPx * geometry.aspect

    val offsetX: Float = padPx

    val offsetY: Float = padPx * geometry.aspect

    fun toCanvas(latitude: Double, longitude: Double): Offset {
        val world = geometry.world(latitude, longitude)
        return Offset(offsetX + world.x * contentWidth, offsetY + world.y * contentWidth)
    }

    val pxPerKilometre: Float = run {
        val latitude = 22.5
        val left = geometry.world(latitude, 78.0)
        val right = geometry.world(latitude, 79.0)
        val kilometres = haversineMetres(latitude, 78.0, latitude, 79.0) / 1000.0
        (((right.x - left.x) * contentWidth) / kilometres).toFloat()
    }
}

private class PlacedPin(
    val point: MapPointDto,
    val x: Float,
    val y: Float,
    val anchorX: Float,
    val anchorY: Float,
    val radius: Float,
    val displaced: Boolean,
    val uncertainty: Float,
    val count: TextLayoutResult
)

private fun layoutPins(
    points: List<MapPointDto>,
    frame: MapFrame,
    density: Density,
    measurer: TextMeasurer,
    originCountColor: Color,
    captureCountColor: Color
): List<PlacedPin> {
    val minRadius = with(density) { PIN_MIN_RADIUS.toPx() }
    val maxRadius = with(density) { PIN_MAX_RADIUS.toPx() }
    val gap = with(density) { PIN_GAP.toPx() }
    val maxShift = with(density) { PIN_MAX_SHIFT.toPx() }

    val busiest = points.fold(0) { most, point -> max(most, point.total) }

    val ordered = points.sortedWith(compareByDescending<MapPointDto> { it.total }.thenBy { it.key })

    val placed = ArrayList<PlacedPin>(ordered.size)
    for (point in ordered) {
        val anchor = frame.toCanvas(point.latitude, point.longitude)
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
                val angle = (stableHash(point.key) % 360) * (PI / 180.0)
                dx = cos(angle).toFloat()
                dy = sin(angle).toFloat()
                distance = 1f
            }
            val push = (needed - distance) / distance
            x += dx * push
            y += dy * push

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

    return placed.sortedByDescending { it.radius }
}

private fun measureCount(
    total: Int,
    radius: Float,
    density: Density,
    measurer: TextMeasurer,
    color: Color
): TextLayoutResult {
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

private fun stableHash(text: String): Int {
    var value = 0
    for (index in text.indices) {
        value = value * 31 + text[index].code
    }
    return value and 0x7FFFFFFF
}

@Composable
private fun IndiaMapCanvas(
    geometry: IndiaGeometry,
    stateBorders: IndiaGeometry?,
    districtBorders: IndiaGeometry?,
    level: String,
    points: List<MapPointDto>,
    focusKeys: List<String>,
    selectedKey: String?,
    fitBox: Rect? = null,
    onSelect: (String) -> Unit
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    val primary = MaterialTheme.colorScheme.primary
    val land = primary.copy(alpha = 0.08f)
    val coast = primary.copy(alpha = 0.38f)
    val stateBorderInk = MaterialTheme.field.muted.copy(alpha = 0.65f)
    val districtBorderInk = MaterialTheme.field.placeholder.copy(alpha = 0.60f)
    val pinRim = MaterialTheme.colorScheme.surface
    val haloFill = primary.copy(alpha = 0.06f)
    val haloEdge = primary.copy(alpha = 0.30f)
    val leader = primary.copy(alpha = 0.55f)
    val scaleInk = MaterialTheme.field.muted
    val originCount = MaterialTheme.colorScheme.onPrimary
    val captureCount = primary
    val labelBackground = MaterialTheme.colorScheme.onSurface
    val labelInk = MaterialTheme.colorScheme.surface

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        if (!widthPx.isFinite() || widthPx <= 0f) return@BoxWithConstraints
        val padPx = with(density) { MAP_PADDING.toPx() }
        val frame = remember(geometry, widthPx, padPx) { MapFrame(geometry, widthPx, padPx) }

        val pins = remember(points, frame, density, measurer, originCount, captureCount) {
            layoutPins(points, frame, density, measurer, originCount, captureCount)
        }

        val zoom = rememberMapZoomState()
        val reducedMotion = LocalAppPreferences.current.reducedMotion
        LaunchedEffect(fitBox, frame) {
            val box = fitBox
            if (box == null) {
                zoom.reset()
            } else {
                val canvas = Size(frame.widthPx, frame.heightPx)
                val inFrame = Rect(
                    frame.offsetX + box.left * frame.contentWidth,
                    frame.offsetY + box.top * frame.contentWidth,
                    frame.offsetX + box.right * frame.contentWidth,
                    frame.offsetY + box.bottom * frame.contentWidth
                )
                val (scale, offset) = MapZoomMath.fitTarget(inFrame, canvas)
                zoom.animateTo(scale, offset, canvas, instant = reducedMotion)
            }
        }
        val bucketScale by remember(frame.contentWidth) {
            derivedStateOf { outlineBucketScale(frame.contentWidth * zoom.scale) }
        }
        val outline = remember(geometry, bucketScale) {
            buildOutlinePath(geometry, bucketScale).apply {
                fillType = PathFillType.EvenOdd
            }
        }

        val showStateBorders = level == "STATE" || level == "DISTRICT"
        val showDistrictBorders = level == "DISTRICT"

        val stateBorderPath = remember(stateBorders, bucketScale, showStateBorders) {
            if (!showStateBorders) null else stateBorders?.let { buildBorderPath(it, bucketScale) }
        }
        val districtBorderPath = remember(districtBorders, bucketScale, showDistrictBorders) {
            if (!showDistrictBorders) null else districtBorders?.let { buildBorderPath(it, bucketScale) }
        }

        val focused = remember(focusKeys) { focusKeys.toSet() }
        val slopPx = with(density) { PIN_TAP_SLOP.toPx() }
        val hairlinePx = with(density) { 1.dp.toPx() }

        val onTap = rememberUpdatedState { tap: Offset ->
            pins
                .filter { hypot(it.x - tap.x, it.y - tap.y) * zoom.scale <= it.radius + slopPx }
                .minByOrNull { hypot(it.x - tap.x, it.y - tap.y) }
                ?.let { onSelect(it.point.key) }
        }

        Box {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f / max(0.1f, geometry.aspect))
                .semantics {
                    contentDescription = "Map of India with ${points.size} " + (if (points.size == 1) "point" else "points") +
                        ". Places are listed below."
                }
                .testTag("places-map")
                .mapZoomGestures(zoom, Size(frame.widthPx, frame.heightPx), reducedMotion) { tap -> onTap.value(tap) }
        ) {
            val zoomScale = zoom.scale
            val residual = frame.contentWidth * zoomScale / bucketScale
            val screenPins = pins.map { it.through(zoom) }
            translate(
                left = zoom.offset.x + frame.offsetX * zoomScale,
                top = zoom.offset.y + frame.offsetY * zoomScale
            ) {
                scale(scaleX = residual, scaleY = residual, pivot = Offset.Zero) {
                    drawPath(outline, color = land, style = Fill)
                    drawPath(outline, color = coast, style = Stroke(width = hairlinePx / residual))

                    if (stateBorderPath != null || districtBorderPath != null) {
                        clipPath(outline) {
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

            screenPins.filter { it.uncertainty > 3f }.forEach { pin ->
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

            screenPins.filter { it.displaced }.forEach { pin ->
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

            screenPins.forEach { pin ->
                drawPin(
                    pin = pin,
                    primary = primary,
                    rim = pinRim,
                    isFocused = focused.contains(pin.point.key),
                    isSelected = pin.point.key == selectedKey,
                    hairlinePx = hairlinePx
                )
            }

            screenPins.firstOrNull { it.point.key == selectedKey }?.let { pin ->
                val ordinal = points.indexOfFirst { it.key == pin.point.key } + 1
                if (ordinal > 0) {
                    drawPinLabel(
                        pin = pin,
                        ordinal = ordinal,
                        measurer = measurer,
                        density = density,
                        background = labelBackground,
                        ink = labelInk
                    )
                }
            }

            drawScaleBar(frame = frame, measurer = measurer, density = density, ink = scaleInk, zoomScale = zoomScale)
        }
        MapZoomResetButton(zoom, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
    }
}

private fun PlacedPin.through(zoom: MapZoomState): PlacedPin {
    if (!zoom.isZoomed && zoom.offset == Offset.Zero) return this
    val at = zoom.toScreen(Offset(x, y))
    val anchor = zoom.toScreen(Offset(anchorX, anchorY))
    return PlacedPin(
        point = point,
        x = at.x,
        y = at.y,
        anchorX = anchor.x,
        anchorY = anchor.y,
        radius = radius,
        displaced = displaced,
        uncertainty = uncertainty * zoom.scale,
        count = count
    )
}

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

    drawText(
        pin.count,
        topLeft = Offset(
            pin.x - pin.count.size.width / 2f,
            pin.y - pin.count.size.height / 2f
        )
    )
}

private fun DrawScope.drawPinLabel(
    pin: PlacedPin,
    ordinal: Int,
    measurer: TextMeasurer,
    density: Density,
    background: Color,
    ink: Color
) {
    val text = "$ordinal · ${pin.point.label}"
    val measured = measurer.measure(
        AnnotatedString(text),
        style = TextStyle(fontFamily = FieldBodyFontFamily, fontSize = 11.sp, color = ink)
    )
    val padX = with(density) { 7.dp.toPx() }
    val padY = with(density) { 4.dp.toPx() }
    val boxWidth = measured.size.width + padX * 2
    val boxHeight = measured.size.height + padY * 2
    val gap = with(density) { 6.dp.toPx() }

    val left = (pin.x - boxWidth / 2f).coerceIn(0f, max(0f, size.width - boxWidth))
    val above = pin.y - pin.radius - gap - boxHeight >= 0f
    val top = if (above) pin.y - pin.radius - gap - boxHeight else pin.y + pin.radius + gap

    drawRoundRect(
        color = background,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(boxWidth, boxHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(with(density) { 6.dp.toPx() })
    )
    drawText(measured, topLeft = Offset(left + padX, top + padY))
}

private fun DrawScope.drawScaleBar(
    frame: MapFrame,
    measurer: TextMeasurer,
    density: Density,
    ink: Color,
    zoomScale: Float = 1f
) {
    val perKilometre = frame.pxPerKilometre * zoomScale
    val kilometres = listOf(500, 200, 100, 50, 20, 10).firstOrNull { it * perKilometre <= frame.contentWidth * 0.6f }
        ?: return
    val length = kilometres * perKilometre
    if (length <= 0f) return

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
                else -> "Outline: international border"
            },
            color = MaterialTheme.field.placeholder,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun LegendEntry(label: String, swatch: DrawScope.(Float) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Canvas(modifier = Modifier.size(11.dp)) { swatch(size.minDimension) }
        Text(label, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
    }
}

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
            style = MaterialTheme.typography.bodySmall
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
                        "The counts overlap — a record can be from one place and recorded in another."
                    )
                    if (summary.originExcludes.isNotEmpty()) {
                        append(" ")
                        append(
                            summary.originExcludes.joinToString(", ") {
                                it.replaceFirstChar { c -> c.uppercaseChar() }
                            }
                        )
                        append(
                            " are placed where they were captured."
                        )
                    }
                    if (summary.captureTruncated) {
                        append(" Showing the busiest GPS points.")
                    }
                    if (summary.clusterKilometres > 0) {
                        append(" Points within ${summary.clusterKilometres} km share a pin.")
                    }
                },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
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
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${address.withState} with a state · ${address.withDistrict} with a district · " +
                        "${address.withPincode} with a pincode · ${address.withSubjectPin} with a " +
                        "pin on the subject’s place.",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall
                )
                if (address.withDistrict < address.locations) {
                    Text(
                        "Records without a district sit at the state capital.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "The map can place ${summary.anchoredDistricts} " +
                        (if (summary.anchoredDistricts == 1) "district" else "districts") +
                        " from the ${summary.anchorPins} " +
                        (if (summary.anchorPins == 1) "pin" else "pins") + " people have dropped.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
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
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            value.toString(),
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MapPlaceListCard(
    points: List<MapPointDto>,
    unplaced: List<MapUnplacedDto>,
    focusKeys: List<String>,
    selectedKey: String?,
    childLevel: String?,
    expandedKeys: Set<String>,
    flashKey: String?,
    flashNonce: Int,
    revealKey: String?,
    revealNonce: Int,
    listScroll: ScrollState,
    onSelect: (String) -> Unit,
    onToggleExpanded: (String) -> Unit,
    onDrillDown: (MapPointChildDto) -> Unit
) {
    MapCard(title = "Every place, as a list") {
        Text(
            "Numbered to match the pins. Choose a place to see its records" +
                (childLevel?.let { ", or open a row for the ${it.lowercase(Locale.ROOT)}s inside it" } ?: "") +
                ".",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        if (points.isEmpty() && unplaced.isEmpty()) {
            Text("Nothing to place.", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            return@MapCard
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = LIST_MAX_HEIGHT)
                .verticalScroll(listScroll)
        ) {
            points.forEachIndexed { index, point ->
                PlaceRow(
                    point = point,
                    ordinal = index + 1,
                    isSelected = point.key == selectedKey,
                    isFocused = focusKeys.contains(point.key),
                    isFlashing = point.key == flashKey,
                    flashNonce = flashNonce,
                    shouldReveal = point.key == revealKey,
                    revealNonce = revealNonce,
                    childLevel = childLevel,
                    isExpanded = expandedKeys.contains(point.key),
                    onClick = { onSelect(point.key) },
                    onToggleExpanded = { onToggleExpanded(point.key) },
                    onDrillDown = onDrillDown
                )
            }

            if (unplaced.isNotEmpty()) UnplacedBlock(unplaced)
        }
    }
}

private val LIST_MAX_HEIGHT = 440.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(
    point: MapPointDto,
    ordinal: Int,
    isSelected: Boolean,
    isFocused: Boolean,
    isFlashing: Boolean,
    flashNonce: Int,
    shouldReveal: Boolean,
    revealNonce: Int,
    childLevel: String?,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onToggleExpanded: () -> Unit,
    onDrillDown: (MapPointChildDto) -> Unit
) {
    val buckets = point.counts.byBucket().filter { it.second > 0 }
    val reduceMotion = LocalAppPreferences.current.reducedMotion

    val revealRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(revealNonce, shouldReveal) {
        if (shouldReveal) revealRequester.bringIntoView()
    }

    val flash = remember { Animatable(0f) }
    LaunchedEffect(flashNonce, isFlashing) {
        if (!isFlashing) {
            flash.snapTo(0f)
            return@LaunchedEffect
        }
        if (reduceMotion) {
            flash.snapTo(0.45f)
        } else {
            flash.snapTo(0f)
            flash.animateTo(1f, animationSpec = tween(durationMillis = 160))
            flash.animateTo(0f, animationSpec = tween(durationMillis = 900))
        }
    }
    val flashWash = MaterialTheme.colorScheme.primary
    val rowShape = MaterialTheme.shapes.small

    Column(
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(revealRequester)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.field.surface50,
                rowShape
            )
            .drawBehind {
                val alpha = flash.value
                if (alpha > 0f) {
                    drawRoundRect(
                        color = flashWash.copy(alpha = 0.22f * alpha),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.06f)
                    )
                }
            }
            .border(
                if (isFlashing) 2.dp else 1.dp,
                when {
                    isFlashing -> MaterialTheme.colorScheme.primary
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.field.hairline
                },
                rowShape
            )
    ) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                if (point.layer == "CAPTURE") Icons.Filled.GpsFixed else Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                ordinal.toString(),
                display = true,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$ordinal. ${point.label}",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                MapPill(precisionBadge(point.precision))
                if (isFocused) MapPill("This record", emphasised = true)
            }

            Text(point.region, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)

            Text(
                "${point.total} " + (if (point.total == 1) "record" else "records") +
                    (
                        if (buckets.isEmpty()) "" else buckets.joinToString(
                            separator = ", ",
                            prefix = " — "
                        ) { (bucket, count) -> countLabel(bucket, count) }
                        ),
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall
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
                    if (point.layer == "ORIGIN" && point.places.isNotEmpty()) {
                        append(" · covers ")
                        append(point.places.take(3).joinToString(", ") { "“$it”" })
                        if (point.places.size > 3) append(" and ${point.places.size - 3} more")
                    }
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
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

        if (point.children.size > 1) {
            HorizontalDivider(color = MaterialTheme.field.hairline)
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleExpanded)
                        .heightIn(min = 40.dp)
                ) {
                    Icon(
                        if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        childSummary(point, childLevel),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (isExpanded) {
                    point.children.forEachIndexed { childIndex, child ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                                .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.small)
                                .clickable { onDrillDown(child) }
                                .heightIn(min = 44.dp)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "$ordinal.${childIndex + 1}",
                                display = true,
                                color = MaterialTheme.field.placeholder,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    child.label,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(child.region, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                child.total.toString(),
                                color = MaterialTheme.field.body,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (point.childrenTruncated) {
                        Text(
                            "Showing the busiest. Switch to " +
                                (childLevel ?: "DISTRICT").lowercase(Locale.ROOT) + " detail for all.",
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "Choosing one moves the map to " +
                            (childLevel ?: "DISTRICT").lowercase(Locale.ROOT) + " detail.",
                        color = MaterialTheme.field.placeholder,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun childSummary(point: MapPointDto, childLevel: String?): String {
    val count = point.children.size
    if (point.layer == "CAPTURE") {
        return "$count separate recording ${if (count == 1) "place" else "places"} inside this pin"
    }
    val noun = when (childLevel) {
        "NATION" -> if (count == 1) "place" else "places"
        "STATE" -> if (count == 1) "state" else "states"
        else -> if (count == 1) "district" else "districts"
    }
    return "$count $noun inside this point"
}

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
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "No state or recognisable place, so no pin. Add a state to the record to map it.",
            color = MaterialTheme.field.onWarningContainer,
            style = MaterialTheme.typography.bodySmall
        )
        unplaced.forEach { entry ->
            Text(
                "${entry.label} — ${entry.total} " + (if (entry.total == 1) "record" else "records"),
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

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
        Text(point.region, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)

        if (point.places.isNotEmpty()) {
            Text(
                "Covers ${point.places.take(4).joinToString(", ")}" +
                    (if (point.places.size > 4) " and ${point.places.size - 4} more" else "") + ".",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (point.layer == "ORIGIN" && point.total > 0) {
            Text(
                when {
                    point.pinnedRecords == point.total ->
                        "All placed by pin."
                    point.pinnedRecords == 0 ->
                        "Placed by address, not a pin."
                    else ->
                        "${point.pinnedRecords} of ${point.total} placed by pin."
                },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        val rows = items
        when {
            panelError != null -> Text(
                panelError.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )

            rows == null -> Text(
                "Loading the records here…",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )

            rows.isEmpty() -> Text(
                "No records to list at this point.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )

            else -> {
                rows.forEach { row -> PointRecordRow(row, onOpenRecord) }
                if (truncated) {
                    Text(
                        "Most recent of each type. See Browse records for all.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
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
            .let { base ->
                if (onOpenRecord == null) base
                else base
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button, onClickLabel = "Open ${row.title.ifBlank { "this record" }}") {
                        onOpenRecord.invoke(singularRecordType(row.type), row.id)
                    }
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            row.type.uppercase(Locale.ROOT),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.width(62.dp)
        )
        Text(
            row.title.ifBlank { "Untitled" },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (row.status.isNotBlank()) MapPill(statusLabel(row.status))
    }
}

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
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun MapPill(text: String, emphasised: Boolean = false) {
    val background =
        if (emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.field.surface100
    val foreground =
        if (emphasised) MaterialTheme.colorScheme.onPrimary else MaterialTheme.field.muted
    Text(
        text,
        color = foreground,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .background(background, CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}
