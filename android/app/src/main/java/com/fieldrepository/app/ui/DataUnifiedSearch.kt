package com.fieldrepository.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.UnifiedSearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

private sealed interface DataSearchLoad {
    data object Idle : DataSearchLoad
    data object Loading : DataSearchLoad
    data class Ready(val response: UnifiedSearchResponse) : DataSearchLoad
    data class Failed(val offline: Boolean) : DataSearchLoad
}

object DataSearchText {
    const val title = "Search everything"
    const val hint = "Search records, transcripts, tools"
    const val close = "Close search"
    const val clear = "Clear"
    const val thisApp = "This app"
    const val bothPortals = "Both portals"
    const val openInData = "Open in Data"
    const val showMore = "Show 20 more"
    const val searching = "Searching…"
    const val tryNow = "Try now"
    const val offlineTitle = "You're offline"
    const val offlineBody = "Search needs a connection. It tries again by itself when you're back online."
    const val failedTitle = "Search didn't load"
    const val searchBoth = "Search both portals"
    const val allTypes = "Show all types"
    const val signInNote = "Opening a Design Workshop result signs you in there if you have access."

    fun playFrom(clock: String) = "Play from $clock"
    fun openIn(portal: String) = "Open in $portal ↗"
    fun showAll(n: Int, label: String) = "Show all $n ${label.lowercase()}"
    fun noMatches(query: String) = "No matches for “$query”"
    fun didYouMean(word: String) = "Did you mean $word?"
    fun retryIn(seconds: Int) = "Trying again in $seconds s."
}

@Composable
private fun portalColors(portal: String): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    return if (portal == DataSearchModel.OTHER) {
        if (dark) Color(0xFF2A0F47) to Color(0xFFC090F5) else Color(0xFFF2E9FE) to Color(0xFF5F2491)
    } else {
        if (dark) Color(0xFF0F3B37) to Color(0xFF5EEAD4) else Color(0xFFCCFBF1) to Color(0xFF115E59)
    }
}

@Composable
private fun PortalBadge(portal: String, short: Boolean = false) {
    val (bg, fg) = portalColors(portal)
    val label = if (short) (if (portal == DataSearchModel.OTHER) "DW" else "FR") else DataSearchModel.portalName(portal)
    Text(
        "● $label",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
internal fun highlighted(text: String, ranges: List<IntRange>): AnnotatedString {
    val mark = SpanStyle(background = MaterialTheme.field.surface200, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        var at = 0
        ranges.sortedBy { it.first }.forEach { range ->
            if (range.first < at) return@forEach
            append(text.substring(at, range.first))
            withStyle(mark) { append(text.substring(range.first, range.last + 1)) }
            at = range.last + 1
        }
        if (at < text.length) append(text.substring(at))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataUnifiedSearch(
    repository: FieldRepository,
    initialQuery: String,
    onOpenInData: (type: String, id: String, title: String) -> Unit,
    onPlay: (mediaId: String, title: String, startSeconds: Double) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var both by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var otherReachable by rememberSaveable { mutableStateOf(true) }
    var load by remember { mutableStateOf<DataSearchLoad>(DataSearchLoad.Idle) }
    var hits by remember { mutableStateOf<List<DataSearchHit>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var loadingMore by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var legacy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    suspend fun fetch(term: String, wantBoth: Boolean, wantType: String?, at: Int): UnifiedSearchResponse {
        if (!legacy) {
            try {
                val limit = if (wantType == null) DataSearchModel.PREVIEW else DataSearchModel.PAGE
                return repository.unifiedSearchPage(term, wantBoth, wantType, at, limit)
            } catch (missing: HttpException) {
                if (missing.code() != 404) throw missing
                legacy = true
            }
        }
        val results = repository.search(q = term, page = at, pageSize = DataSearchModel.PAGE)
        val shaped = DataSearchModel.legacy(term, results)
        return if (wantType == null) shaped else shaped.copy(groups = shaped.groups.filter { it.type == wantType })
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    LaunchedEffect(query, both, type, attempt) {
        val term = query.trim()
        countdown = null
        if (term.length < 2) {
            load = DataSearchLoad.Idle
            hits = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        if (load !is DataSearchLoad.Ready) load = DataSearchLoad.Loading
        try {
            val response = fetch(term, both && otherReachable, type, 1)
            if (both && !DataSearchModel.searchesOther(response.remote)) otherReachable = false
            page = 1
            hits = DataSearchModel.hits(response, type)
            load = DataSearchLoad.Ready(response)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val offline = failure is IOException
            load = DataSearchLoad.Failed(offline)
            if (!offline) {
                for (left in 10 downTo 1) {
                    countdown = left
                    delay(1_000)
                }
                countdown = null
                attempt++
            }
        }
    }

    fun loadMore() {
        val wanted = type ?: return
        if (loadingMore) return
        scope.launch {
            loadingMore = true
            try {
                val next = fetch(query.trim(), both && otherReachable, wanted, page + 1)
                page += 1
                hits = DataSearchModel.more(hits, DataSearchModel.hits(next, wanted))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                load = DataSearchLoad.Failed(failure is IOException)
            } finally {
                loadingMore = false
            }
        }
    }

    fun openOther(hit: DataSearchHit) {
        val url = hit.openUrl ?: hit.alsoIn.firstOrNull()?.openUrl ?: return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("data.search.screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("data.search.close")) {
                Icon(Icons.Filled.Close, contentDescription = DataSearchText.close, tint = MaterialTheme.colorScheme.primary)
            }
            Text(DataSearchText.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; type = null },
                singleLine = true,
                placeholder = { Text(DataSearchText.hint) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; type = null }) { Icon(Icons.Filled.Close, contentDescription = DataSearchText.clear, modifier = Modifier.size(18.dp)) }
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("data.search.field"),
            )
            if (otherReachable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScopeChip(DataSearchText.thisApp, !both, "data.search.scope.here", Modifier.weight(1f)) { both = false; type = null }
                    ScopeChip(DataSearchText.bothPortals, both, "data.search.scope.both", Modifier.weight(1f)) { both = true; type = null }
                }
            }
            val ready = load as? DataSearchLoad.Ready
            if (ready != null) {
                if (both && otherReachable) {
                    val (here, other) = DataSearchModel.portalCounts(ready.response)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("data.search.portals")) {
                        PortalCount(DataSearchModel.OTHER, other)
                        PortalCount(DataSearchModel.HERE, here)
                    }
                }
                val pills = DataSearchModel.pills(ready.response)
                if (pills.isNotEmpty() && (type == null || pills.size > 2)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pills.forEach { pill ->
                            FilterChip(
                                selected = pill.type == type,
                                onClick = { type = pill.type },
                                label = { Text("${pill.label} ${pill.count}") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier.heightIn(min = 40.dp).testTag("data.search.pill.${pill.type ?: "all"}"),
                            )
                        }
                    }
                }
            }
        }
        when (val now = load) {
            DataSearchLoad.Idle -> Unit
            DataSearchLoad.Loading -> SearchSkeleton()
            is DataSearchLoad.Failed -> SearchProblem(
                offline = now.offline,
                countdown = countdown,
                onTryNow = { attempt++ },
            )
            is DataSearchLoad.Ready -> {
                if (hits.isEmpty()) {
                    NoMatches(
                        query = query.trim(),
                        suggestion = now.response.suggestion,
                        filtered = type != null,
                        canSearchBoth = !both && otherReachable,
                        onSuggestion = { query = it; type = null },
                        onClearType = { type = null },
                        onSearchBoth = { both = true; type = null },
                    )
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().testTag("data.search.results"),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(hits, key = { it.key }) { hit ->
                            ResultCard(
                                hit = hit,
                                showBadge = both,
                                onPlay = hit.startMs?.takeIf { hit.local && hit.type == "transcripts" }?.let { ms -> { onPlay(hit.id, hit.title, ms / 1000.0) } },
                                onOpen = if (hit.local) DataSearchModel.locateType(hit.type)?.let { kind -> { onOpenInData(kind, hit.id, hit.title) } } else null,
                                onOther = if (!hit.local || hit.inBoth) ({ openOther(hit) }) else null,
                            )
                        }
                        val wanted = type
                        if (wanted == null) {
                            val groups = DataSearchModel.groups(now.response).filter { it.total > it.items.size }
                            items(groups, key = { "more:${it.type}" }) { group ->
                                TextButton(onClick = { type = group.type }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("data.search.all.${group.type}")) {
                                    Text(DataSearchText.showAll(group.total, group.label.ifBlank { DataSearchModel.typeLabel(group.type) }))
                                }
                            }
                        } else {
                            val total = DataSearchModel.groups(now.response).firstOrNull { it.type == wanted }?.total ?: 0
                            if (total > hits.size) {
                                item("more") {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        OutlinedButton(onClick = ::loadMore, enabled = !loadingMore, modifier = Modifier.heightIn(min = 48.dp).testTag("data.search.more")) {
                                            Text(if (loadingMore) DataSearchText.searching else DataSearchText.showMore)
                                        }
                                    }
                                }
                            }
                        }
                        if (both && hits.any { !it.local || it.inBoth }) {
                            item("note") {
                                Text(DataSearchText.signInNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier.heightIn(min = 44.dp).testTag(tag),
    )
}

@Composable
private fun PortalCount(portal: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PortalBadge(portal)
        Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(
    hit: DataSearchHit,
    showBadge: Boolean,
    onPlay: (() -> Unit)?,
    onOpen: (() -> Unit)?,
    onOther: (() -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(16.dp)).testTag("data.search.hit.${hit.type}.${hit.id}"),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hit.inBoth) {
                    PortalBadge(DataSearchModel.OTHER, short = true)
                    PortalBadge(DataSearchModel.HERE, short = true)
                } else if (showBadge) {
                    PortalBadge(hit.portal)
                }
                Text(DataSearchModel.headline(hit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
            }
            Text(hit.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            hit.snippet?.let { snippet ->
                Text(highlighted("“…$snippet…”", hit.highlights.map { (it.first + 2)..(it.last + 2) }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
            }
            (DataSearchModel.bothLine(hit) ?: hit.subtitle)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val actions = listOfNotNull(
                onPlay?.let { Triple(DataSearchText.playFrom(DataSearchModel.clock(hit.startMs ?: 0)), it, "play") },
                onOpen?.let { Triple(DataSearchText.openInData, it, "open") },
                onOther?.let { Triple(DataSearchText.openIn(DataSearchModel.OTHER_LABEL), it, "other") },
            )
            if (actions.isNotEmpty()) {
                if (actions.size <= 2) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        actions.forEachIndexed { index, (label, action, tag) ->
                            ActionButton(label, primary = index == 0 && onPlay != null, tag = "data.search.$tag.${hit.id}", modifier = Modifier.weight(1f), onClick = action)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        actions.forEachIndexed { index, (label, action, tag) ->
                            ActionButton(label, primary = index == 0 && onPlay != null, tag = "data.search.$tag.${hit.id}", modifier = Modifier.fillMaxWidth(), onClick = action)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, primary: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    if (primary) {
        Button(onClick = onClick, modifier = modifier.heightIn(min = 44.dp).testTag(tag)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 44.dp).testTag(tag)) { Text(label) }
    }
}

@Composable
private fun SearchSkeleton() {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("data.search.loading"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.field.surface50, RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth(0.35f).heightIn(min = 10.dp).background(MaterialTheme.field.surface200, RoundedCornerShape(6.dp)))
                Box(Modifier.fillMaxWidth(0.7f).heightIn(min = 16.dp).background(MaterialTheme.field.surface200, RoundedCornerShape(6.dp)))
                Box(Modifier.fillMaxWidth().heightIn(min = 12.dp).background(MaterialTheme.field.surface100, RoundedCornerShape(6.dp)))
            }
        }
    }
}

@Composable
private fun StateCard(icon: String, title: String, body: String?, tint: Color, ink: Color, tag: String, actions: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp).background(tint, RoundedCornerShape(20.dp)).padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(icon, style = MaterialTheme.typography.headlineSmall, color = ink)
        Text(title, style = MaterialTheme.typography.titleMedium, color = ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        actions()
    }
}

@Composable
private fun SearchProblem(offline: Boolean, countdown: Int?, onTryNow: () -> Unit) {
    if (offline) {
        StateCard("⌁", DataSearchText.offlineTitle, DataSearchText.offlineBody, MaterialTheme.field.warningContainer, MaterialTheme.field.onWarningContainer, "data.search.offline") {
            OutlinedButton(onClick = onTryNow, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text(DataSearchText.tryNow) }
        }
    } else {
        StateCard("!", DataSearchText.failedTitle, countdown?.let { DataSearchText.retryIn(it) }, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "data.search.failed") {
            Button(onClick = onTryNow, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("data.search.trynow")) { Text(DataSearchText.tryNow) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoMatches(
    query: String,
    suggestion: String?,
    filtered: Boolean,
    canSearchBoth: Boolean,
    onSuggestion: (String) -> Unit,
    onClearType: () -> Unit,
    onSearchBoth: () -> Unit,
) {
    StateCard("⌕", DataSearchText.noMatches(query), null, MaterialTheme.field.surface50, MaterialTheme.colorScheme.onSurface, "data.search.empty") {
        suggestion?.takeIf { it.isNotBlank() && !it.equals(query, ignoreCase = true) }?.let { word ->
            Text(
                DataSearchText.didYouMean(word),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.heightIn(min = 44.dp).clickable { onSuggestion(word) }.padding(vertical = 12.dp).testTag("data.search.suggestion"),
            )
        }
        val actions = listOfNotNull(
            if (filtered) DataSearchText.allTypes to onClearType else null,
            if (canSearchBoth) DataSearchText.searchBoth to onSearchBoth else null,
        )
        when (actions.size) {
            0 -> Unit
            1 -> OutlinedButton(onClick = actions[0].second, modifier = Modifier.heightIn(min = 44.dp)) { Text(actions[0].first) }
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEach { (label, action) -> OutlinedButton(onClick = action, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text(label) } }
            }
        }
    }
}
