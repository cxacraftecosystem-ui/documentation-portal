package com.fieldrepository.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.COVERAGE_BUCKETS
import com.fieldrepository.app.data.COVERAGE_DEPICTION_CAPTION
import com.fieldrepository.app.data.CoverageCounts
import com.fieldrepository.app.data.CoverageLevel
import com.fieldrepository.app.data.CoverageMetric
import com.fieldrepository.app.data.CoveragePolygon
import com.fieldrepository.app.data.CoverageQuery
import com.fieldrepository.app.data.CoverageResponse
import com.fieldrepository.app.data.CoverageShapeKey
import com.fieldrepository.app.data.CoverageUnit
import com.fieldrepository.app.data.CoverageUnitRecords
import com.fieldrepository.app.data.CoverageCopy
import com.fieldrepository.app.data.CoverageStateRow
import com.fieldrepository.app.data.CoverageSummary
import com.fieldrepository.app.data.coverageSummary
import com.fieldrepository.app.data.binLabels
import com.fieldrepository.app.data.coverageMetricValue
import com.fieldrepository.app.data.fillsFor
import com.fieldrepository.app.data.noWorkshopFor
import com.fieldrepository.app.data.quantileBreaks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * THE COVERAGE SECTION OF THE MAP SCREEN — the web's Coverage view (`/map?view=coverage`) on a phone.
 *
 * Top to bottom, as the requirement lays it out: a Filters button (a sheet with Crafts, Scheme,
 * Financial year, State, Detail and Colour by — the web's values in the web's order), the legend, the
 * canvas ([CoverageMapCanvas], pinch and double tap to zoom), the depiction caption word for word, the
 * gaps card and the list of every place with a record. Tapping a district opens a sheet named after
 * it with its counts and "Show records", which lists the records; each row opens the record.
 *
 * The data source is passed in, so the same screen serves both portals' repositories. The Field
 * Repository adds one card the designer portal has not got: the per-state Coverage list it drew before
 * the map existed ("12 of 33 districts with a workshop"), folded from the same answer on the client;
 * tapping a state focuses the map on it.
 */

/**
 * What a record row opens on the phone, in the search screen's record vocabulary: by the bucket it was
 * listed under, since a media link names the library rather than the file.
 */
fun coverageBucketTarget(bucket: String, id: String): Pair<String, String>? {
    if (id.isBlank()) return null
    val type = when (bucket) {
        "artisans" -> "artisan"
        "workshops" -> "workshop"
        "products" -> "product"
        "tools" -> "tool"
        "media" -> "media"
        else -> return null
    }
    return type to id
}

/** What a coverage record link opens on the phone: the search screen's record vocabulary. */
fun coverageRecordTarget(href: String): Pair<String, String>? {
    val parts = href.substringBefore('?').trim('/').split('/')
    if (parts.size != 2 || parts[1].isBlank()) return null
    val type = when (parts[0]) {
        "artisans" -> "artisan"
        "products" -> "product"
        "tools" -> "tool"
        "media" -> "media"
        "workshops" -> "workshop"
        "design-workshops" -> "designWorkshop"
        else -> return null
    }
    return type to parts[1]
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CoverageScreen(
    fetchCoverage: suspend (CoverageQuery) -> CoverageResponse,
    fetchUnitRecords: suspend (query: CoverageQuery, stateLgd: Int, districtLgd: Int?, bucket: String?, cursor: String?) -> CoverageUnitRecords,
    errorMessage: (Throwable, String) -> String,
    onError: (String) -> Unit,
    onOpenRecord: ((recordType: String, recordId: String) -> Unit)?
) {
    val context = LocalContext.current
    var outline by remember { mutableStateOf<IndiaGeometry?>(null) }
    var stateBorders by remember { mutableStateOf<IndiaGeometry?>(null) }
    var shapes by remember { mutableStateOf<List<DistrictShape>?>(null) }
    var shapesFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                Triple(loadIndiaGeometry(context), loadStateBorders(context), loadDistrictShapes(context.resources))
            }
        }.onSuccess { (o, s, d) ->
            outline = o
            stateBorders = s
            shapes = d
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            shapesFailed = true
        }
    }

    var query by remember { mutableStateOf(CoverageQuery()) }
    var metric by rememberSaveable { mutableStateOf(CoverageMetric.WORKSHOPS) }
    var data by remember { mutableStateOf<CoverageResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    var stateNames by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CoverageUnit?>(null) }

    LaunchedEffect(query, retry) {
        loading = true
        error = null
        runCatching { fetchCoverage(query) }
            .onSuccess { body ->
                data = body
                val names = stateNames.toMutableMap()
                body.units.forEach { names[it.stateLgd] = it.state }
                body.gaps.districtsWithoutWorkshop.byState.forEach { names[it.stateLgd] = it.state }
                body.gaps.statesWithoutWorkshop.forEach { row -> row.lgd?.let { names[it] = row.name } }
                stateNames = names
                loading = false
            }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                val message = errorMessage(failure, "Coverage could not be loaded.")
                error = message
                onError(message)
                loading = false
            }
    }

    val current = data
    val level = current?.filters?.level ?: query.level
    val units = current?.units.orEmpty()
    val keys = remember(shapes) { shapes.orEmpty().map { CoverageShapeKey(it.lgd, it.stateLgd) } }
    val fills = remember(units, level, metric, keys) { fillsFor(units, level, metric, keys) }
    val breaks = remember(fills) { quantileBreaks(fills.values.map { it.value }) }
    val noWorkshop = remember(current, level, keys) { current?.let { noWorkshopFor(it, level, keys) } ?: emptySet() }
    val districtNames = remember(current) {
        buildMap {
            current?.gaps?.districtsWithoutWorkshop?.byState?.forEach { row ->
                row.without.forEach { d -> d.lgd?.let { put(it, d.name) } }
            }
            current?.units?.forEach { u -> if (u.districtLgd != null && u.district != null) put(u.districtLgd, u.district) }
        }
    }

    fun selectDistrict(lgd: Int) {
        val shape = shapes?.firstOrNull { it.lgd == lgd } ?: return
        selected = if (level == CoverageLevel.STATE) {
            units.firstOrNull { it.stateLgd == shape.stateLgd }
                ?: CoverageUnit(stateLgd = shape.stateLgd, state = stateNames[shape.stateLgd].orEmpty(), polygon = CoveragePolygon.STATE)
        } else {
            units.firstOrNull { it.districtLgd == lgd } ?: units.firstOrNull { it.parentLgd == lgd }
                ?: CoverageUnit(
                    stateLgd = shape.stateLgd,
                    districtLgd = lgd,
                    state = stateNames[shape.stateLgd].orEmpty(),
                    district = districtNames[lgd]
                )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        CoverageCard(title = "Coverage") {
            OutlinedButton(
                onClick = { filtersOpen = true },
                modifier = Modifier.heightIn(min = 48.dp).testTag("coverage-filters")
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(coverageFilterSummary(query, metric, stateNames))
            }
            CoverageLegendRow(breaks = breaks, metric = metric, showParent = fills.values.any { it.hostsChild })
            when {
                error != null -> LoadFailedCard(message = error.orEmpty(), onRetry = { retry++ })
                shapesFailed -> Text(
                    "Couldn't draw the map. Every place is listed below.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                current == null || outline == null || shapes == null -> Text(
                    "Counting the records…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                else -> CoverageMapCanvas(
                    outline = outline!!,
                    stateBorders = stateBorders,
                    shapes = shapes!!,
                    fills = fills,
                    breaks = breaks,
                    noWorkshop = noWorkshop,
                    markers = current.markers,
                    selectedLgd = selected?.let { it.districtLgd ?: it.parentLgd },
                    focusStateLgd = query.stateLgd,
                    onSelectDistrict = ::selectDistrict
                )
            }
            Text(
                COVERAGE_DEPICTION_CAPTION,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            if (current?.markersTruncated == true) {
                Text("Showing 500 workshop markers.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            if (current?.truncated == true) {
                Text("Showing the first 20,000 records of each kind.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            if (loading && current != null) {
                Text("Updating…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }

        if (current != null) {
            CoverageGapsCard(current)
            CoverageStatesCard(
                summary = remember(current) { coverageSummary(current) },
                onOpenState = { stateLgd ->
                    selected = null
                    query = query.copy(stateLgd = stateLgd)
                }
            )
            CoveragePlacesCard(units = units, level = level, onOpen = { selected = it })
        }
    }

    if (filtersOpen) {
        ModalBottomSheet(
            onDismissRequest = { filtersOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            CoverageFilterSheet(
                query = query,
                metric = metric,
                data = current,
                stateNames = stateNames,
                onQuery = { query = it; selected = null },
                onMetric = { metric = it },
                onDone = { filtersOpen = false }
            )
        }
    }

    selected?.let { unit ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = Modifier.testTag("coverage-unit-sheet")
        ) {
            CoverageUnitSheet(
                unit = unit,
                query = query,
                level = level,
                parentName = if (unit.polygon == CoveragePolygon.PARENT) unit.parentLgd?.let { districtNames[it] } else null,
                fetchUnitRecords = fetchUnitRecords,
                errorMessage = errorMessage,
                onOpenRecord = onOpenRecord
            )
        }
    }
}

private fun coverageFilterSummary(query: CoverageQuery, metric: String, stateNames: Map<Int, String>): String {
    val parts = mutableListOf<String>()
    parts += query.stateLgd?.let { stateNames[it] } ?: "All of India"
    if (query.craftCode.isNotEmpty()) parts += if (query.craftCode.size == 1) "1 craft" else "${query.craftCode.size} crafts"
    if (query.fy.size == 1) parts += "FY ${query.fy.first()}-${((query.fy.first() + 1) % 100).toString().padStart(2, '0')}"
    parts += CoverageMetric.label(metric)
    return "Filters · " + parts.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoverageFilterSheet(
    query: CoverageQuery,
    metric: String,
    data: CoverageResponse?,
    stateNames: Map<Int, String>,
    onQuery: (CoverageQuery) -> Unit,
    onMetric: (String) -> Unit,
    onDone: () -> Unit
) {
    val catalogue = data?.catalogue
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding()
    ) {
        Text("Filters", display = true, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        SearchableMultiSelectField(
            label = "Crafts",
            options = catalogue?.crafts.orEmpty().map { SelectOption(it.code, it.name) },
            selected = query.craftCode.toSet(),
            placeholder = "All crafts",
            emptyMessage = "No crafts to choose from yet.",
            onSelectedChange = { onQuery(query.copy(craftCode = it.toList())) }
        )
        if (!catalogue?.schemes.isNullOrEmpty()) {
            Text("Workshop type", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                catalogue!!.schemes.forEach { scheme ->
                    val on = scheme.value in query.scheme
                    FilterChip(
                        selected = on,
                        onClick = {
                            onQuery(query.copy(scheme = if (on) query.scheme - scheme.value else query.scheme + scheme.value))
                        },
                        label = { Text(scheme.label) }
                    )
                }
            }
        }
        SearchableSelectField(
            label = "Financial year",
            options = catalogue?.years.orEmpty().map { SelectOption(it.fy.toString(), "FY ${it.label}") },
            selectedValue = query.fy.singleOrNull()?.toString().orEmpty(),
            placeholder = "All years",
            onSelect = { value -> onQuery(query.copy(fy = value.toIntOrNull()?.let { listOf(it) } ?: emptyList())) }
        )
        SearchableSelectField(
            label = "State",
            options = stateNames.entries.sortedBy { it.value }.map { SelectOption(it.key.toString(), it.value) },
            selectedValue = query.stateLgd?.toString().orEmpty(),
            placeholder = "All of India",
            searchable = true,
            onSelect = { value -> onQuery(query.copy(stateLgd = value.toIntOrNull())) }
        )
        Text("Detail", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(CoverageLevel.DISTRICT to "District", CoverageLevel.STATE to "State").forEach { (value, label) ->
                FilterChip(selected = query.level == value, onClick = { onQuery(query.copy(level = value)) }, label = { Text(label) })
            }
        }
        Text("Colour by", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoverageMetric.OPTIONS.forEach { (value, label) ->
                FilterChip(selected = metric == value, onClick = { onMetric(value) }, label = { Text(label) })
            }
        }
        ActionRow {
            Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoverageLegendRow(breaks: List<Int>, metric: String, showParent: Boolean) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val bins = coverageBinColors(dark)
    val base = if (dark) FieldPalette.Surface100Dark else FieldPalette.Surface100Light
    val edge = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(CoverageMetric.label(metric), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        LegendSwatch("None") { drawRoundRect(base, cornerRadius = CornerRadius(3f)); drawRoundRect(edge, cornerRadius = CornerRadius(3f), style = Stroke(1f)) }
        binLabels(breaks).forEachIndexed { index, label ->
            LegendSwatch(label) { drawRoundRect(bins[index.coerceAtMost(bins.size - 1)], cornerRadius = CornerRadius(3f)) }
        }
        LegendSwatch("No workshop") {
            drawRoundRect(base, cornerRadius = CornerRadius(3f))
            var x = -size.height
            while (x < size.width) {
                drawLine(ink.copy(alpha = 0.6f), start = androidx.compose.ui.geometry.Offset(x, size.height), end = androidx.compose.ui.geometry.Offset(x + size.height, 0f), strokeWidth = 1.5f)
                x += 5f
            }
        }
        if (showParent) {
            LegendSwatch("Includes a district shown inside its parent") {
                drawRoundRect(
                    ink,
                    cornerRadius = CornerRadius(3f),
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 3f)))
                )
            }
        }
    }
}

@Composable
private fun LegendSwatch(label: String, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).drawBehind(draw))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CoverageGapsCard(data: CoverageResponse) {
    var open by remember { mutableStateOf<Set<Int>>(emptySet()) }
    CoverageCard(title = "Gaps") {
        val crafts = data.gaps.craftsNeverDocumented
        Text("Crafts never documented · ${crafts.size}", style = MaterialTheme.typography.labelLarge)
        Text(
            if (crafts.isEmpty()) "Every shared craft has a record." else crafts.joinToString(", ") { it.name },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Districts without a workshop · ${data.gaps.districtsWithoutWorkshop.count}", style = MaterialTheme.typography.labelLarge)
        data.gaps.districtsWithoutWorkshop.byState.forEach { row ->
            val expanded = row.stateLgd in open
            Column(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable { open = if (expanded) open - row.stateLgd else open + row.stateLgd }
                ) {
                    Text(row.state, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${row.without.size} of ${row.total}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                }
                if (expanded) {
                    Text(
                        row.without.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (data.gaps.statesWithoutWorkshop.isNotEmpty()) {
            Text(
                "States and UTs with no workshop: ${data.gaps.statesWithoutWorkshop.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The list fallback: every place with a record, as the web's table. Tapping a row opens its sheet. */
@Composable
private fun CoveragePlacesCard(units: List<CoverageUnit>, level: String, onOpen: (CoverageUnit) -> Unit) {
    CoverageCard(title = "Every place with a record") {
        if (units.isEmpty()) {
            Text("No records under these filters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        units.forEach { unit ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable { onOpen(unit) }
            ) {
                Text(
                    if (level == CoverageLevel.STATE) unit.state else unit.title,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${coverageMetricValue(unit.counts, CoverageMetric.WORKSHOPS)} workshops · ${unit.counts.artisans} artisans",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CoverageUnitSheet(
    unit: CoverageUnit,
    query: CoverageQuery,
    level: String,
    parentName: String?,
    fetchUnitRecords: suspend (CoverageQuery, Int, Int?, String?, String?) -> CoverageUnitRecords,
    errorMessage: (Throwable, String) -> String,
    onOpenRecord: ((String, String) -> Unit)?
) {
    val scope = rememberCoroutineScope()
    var records by remember(unit) { mutableStateOf<CoverageUnitRecords?>(null) }
    var loading by remember(unit) { mutableStateOf(false) }
    var error by remember(unit) { mutableStateOf<String?>(null) }
    val district = if (level == CoverageLevel.STATE) null else unit.districtLgd
    val title = if (level == CoverageLevel.STATE || unit.district.isNullOrBlank()) unit.state else unit.title

    fun load(bucket: String? = null, cursor: String? = null) {
        loading = true
        error = null
        scope.launch {
            runCatching { fetchUnitRecords(query, unit.stateLgd, district, bucket, cursor) }
                .onSuccess { page ->
                    val previous = records
                    records = if (bucket == null || previous == null) {
                        page
                    } else {
                        val old = previous.buckets[bucket]
                        val next = page.buckets[bucket]
                        if (next == null) previous else previous.copy(
                            buckets = previous.buckets + (bucket to next.copy(items = old?.items.orEmpty() + next.items))
                        )
                    }
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    error = errorMessage(failure, "Couldn't load these records.")
                }
            loading = false
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding()
    ) {
        Text(title, display = true, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        parentName?.let {
            Text("Shown inside $it (parent)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CountsGrid(unit.counts)
        unit.peer?.takeIf { query.peer }?.let { peer ->
            Text(
                "Field Repository: ${peer.workshops} workshops · ${peer.artisans} artisans · ${peer.products + peer.tools + peer.media} records",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val page = records
        if (page == null) {
            ActionRow {
                Button(
                    onClick = { load() },
                    enabled = !loading,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("coverage-show-records")
                ) { Text(if (loading) "Loading…" else "Show records") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (page != null) {
            val hasDesign = page.buckets.containsKey("designWorkshops")
            val shown = COVERAGE_BUCKETS.filter { (key, _) -> (page.buckets[key]?.total ?: 0) > 0 }
            if (shown.isEmpty()) {
                Text("No records under these filters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shown.forEach { (key, baseLabel) ->
                val bucket = page.buckets.getValue(key)
                val label = if (key == "workshops" && hasDesign) "Field workshops" else baseLabel
                Text("$label · ${bucket.total}", style = MaterialTheme.typography.labelLarge)
                bucket.items.forEach { item ->
                    val target = coverageBucketTarget(key, item.id) ?: coverageRecordTarget(item.href)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .then(
                                if (target != null && onOpenRecord != null) {
                                    Modifier.clickable { onOpenRecord(target.first, target.second) }
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        Text(
                            item.title,
                            color = if (target != null && onOpenRecord != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        item.fy?.let {
                            Text("FY $it-${((it + 1) % 100).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (bucket.nextCursor != null) {
                    TextButton(onClick = { load(key, bucket.nextCursor) }, enabled = !loading) {
                        Text("Show more (${bucket.total - bucket.items.size} left)")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountsGrid(counts: CoverageCounts) {
    val tiles = buildList {
        add("Workshops" to coverageMetricValue(counts, CoverageMetric.WORKSHOPS))
        if (counts.plannedWorkshops > 0) add("Planned" to counts.plannedWorkshops)
        add("Artisans" to counts.artisans)
        add("Crafts" to counts.crafts)
        add("Products" to counts.products)
        add("Tools" to counts.tools)
        add("Media" to counts.media)
    }
    val ground = MaterialTheme.colorScheme.surfaceVariant
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { (label, value) ->
            Column(
                Modifier
                    .width(96.dp)
                    .drawBehind { drawRoundRect(color = ground, cornerRadius = CornerRadius(12f)) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value.toString(), display = true, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CoverageCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, display = true, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** How many states the Coverage list shows before "Show all". */
private const val COVERAGE_STATE_ROWS = 8

/**
 * The Field Repository's per-state list: how many of each state's districts have a workshop and how
 * many records it holds, best covered first. Tapping a state focuses the map on it.
 */
@Composable
private fun CoverageStatesCard(summary: CoverageSummary, onOpenState: (Int) -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    CoverageCard(title = "By state") {
        if (summary.isEmpty) {
            Text("No records placed in a district yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(CoverageCopy.crafts(summary.craftsNeverDocumented), style = MaterialTheme.typography.labelLarge)
        val rows = if (showAll) summary.states else summary.states.take(COVERAGE_STATE_ROWS)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { row -> CoverageStateRowItem(row = row, onClick = { onOpenState(row.stateLgd) }) }
        }
        if (summary.states.size > COVERAGE_STATE_ROWS) {
            ActionRow {
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (showAll) "Show fewer" else "Show all ${summary.states.size} states")
                }
            }
        }
        if (summary.truncated) {
            Text("Some counts are capped.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CoverageStateRowItem(row: CoverageStateRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(CoverageCopy.districts(row), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row.records > 0) {
            Spacer(Modifier.width(8.dp))
            Text(CoverageCopy.records(row.records), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
