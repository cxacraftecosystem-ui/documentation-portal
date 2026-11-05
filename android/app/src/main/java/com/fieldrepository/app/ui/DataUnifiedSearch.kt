package com.fieldrepository.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.UnifiedSearchGroupDto
import com.fieldrepository.app.data.UnifiedSearchItemDto
import com.fieldrepository.app.data.UnifiedSearchResponse
import com.fieldrepository.app.explorer.ui.ExplorerStatus
import com.fieldrepository.app.explorer.ui.explorerMark
import com.fieldrepository.app.explorer.ui.explorerTone
import com.fieldrepository.app.explorer.ui.explorerWarnTone
import com.fieldrepository.app.explorer.ui.marked
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

object DataSearchText {
    const val hint = "Search everything"
    const val thisApp = "This app"
    const val bothPortals = "Both portals"
    const val all = "All"
    const val openInData = "Open in data"
    const val play = "Play"
    const val searching = "Searching…"
    const val failed = "Search didn't load."
    const val tryAgain = "Try again"
    const val tryNow = "Try now"
    const val offline = "You're offline. Search runs again when you're back online."

    fun retryIn(seconds: Int) = "Trying again in $seconds s."
    const val clearFilter = "Clear filter"
    const val inBoth = "in both"
    const val sameInBoth = "Same person in both portals"
    const val ours = "Field Repository"
    const val theirs = "Design Workshop"
    const val ourShort = "FR"
    const val theirShort = "DW"
    const val typeMore = "Type at least 2 letters."
    const val footnote = "Opening a $theirs result signs you in there if you have access."
    const val openThere = "Open in Design Workshop ↗"

    fun playFrom(clock: String) = "Play from $clock"
    fun noMatches(q: String) = "No matches for “$q”"
    fun didYouMean(s: String) = "Did you mean $s?"
    fun showMore(n: Int) = "Show $n more"
    const val loadMore = "Load more"
    fun showAll(total: Int, label: String) = "Show all $total ${label.lowercase()}"
    fun shownOf(shown: Int, total: Int) = if (shown >= total) "$total" else "$shown of $total"
    fun shownLine(shown: Int, total: Int) = if (shown >= total) "All $shown shown" else "$shown of $total shown"
    fun searchBoth(n: Int?) = if (n != null && n > 0) "Search both portals · $n" else "Search both portals"
}

private val TYPE_LABELS = mapOf(
    "artisans" to "Artisan", "crafts" to "Craft", "clusters" to "Cluster", "workshops" to "Workshop",
    "designWorkshops" to "Design workshop", "interviews" to "Interview", "transcripts" to "Transcript",
    "reports" to "Report", "products" to "Product", "tools" to "Tool", "processes" to "Process", "media" to "Media",
)

private val LOCATE_TYPES = mapOf(
    "artisans" to "artisan", "crafts" to "craft", "workshops" to "workshop", "designWorkshops" to "designWorkshop",
    "interviews" to "interview", "transcripts" to "media", "media" to "media", "products" to "product",
    "tools" to "tool", "processes" to "process", "reports" to "designWorkshop",
)

internal object DataSearchRules {
    fun locateType(type: String): String? = LOCATE_TYPES[type]

    fun label(type: String, server: String?): String = TYPE_LABELS[type] ?: server?.ifBlank { null } ?: type

    fun startSeconds(item: UnifiedSearchItemDto): Double? {
        item.startMs?.let { return it / 1000.0 }
        val m = Regex("^\\s*\\[?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?]?").find(item.snippet.orEmpty()) ?: return null
        val (a, b, c) = m.destructured
        return if (c.isNotEmpty()) a.toDouble() * 3600 + b.toDouble() * 60 + c.toDouble() else a.toDouble() * 60 + b.toDouble()
    }

    fun clock(seconds: Double): String {
        val s = seconds.toLong()
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    fun alsoIn(item: UnifiedSearchItemDto): JsonObject? = when (val v: JsonElement? = item.alsoIn) {
        is JsonObject -> v
        is JsonArray -> v.firstOrNull() as? JsonObject
        else -> null
    }

    fun openId(item: UnifiedSearchItemDto): String = item.id

    fun ranges(item: UnifiedSearchItemDto, text: String): List<IntRange> =
        item.highlights.mapNotNull { pair -> if (pair.size >= 2 && pair[1] > pair[0]) pair[0] until minOf(pair[1], text.length) else null }

    fun date(raw: String?): String? = raw?.let { runCatching { OffsetDateTime.parse(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) }.getOrNull() }

    fun localTotal(r: UnifiedSearchResponse) = r.groups.sumOf { it.localTotal.takeIf { n -> n > 0 } ?: it.items.count { i -> i.local } }

    fun remoteTotal(r: UnifiedSearchResponse) = r.groups.sumOf { it.remoteTotal }

    fun crossAvailable(r: UnifiedSearchResponse?) = r != null && r.remote.status != "off" && r.remote.status != "notPermitted"

    fun crossBlocked(scope: String, r: UnifiedSearchResponse): Boolean = scope == "all" && !crossAvailable(r)

    const val RETRY_SECONDS = 10

    const val ALL_LIMIT = 5

    const val TYPE_LIMIT = 20

    fun limitFor(type: String?) = if (type == null) ALL_LIMIT else TYPE_LIMIT

    fun usable(r: UnifiedSearchResponse, type: String?): List<UnifiedSearchItemDto> =
        r.groups.filter { type == null || it.type == type }.flatMap { g -> g.items.filter { it.id.isNotBlank() } }

    fun key(item: UnifiedSearchItemDto) = "${item.portal}:${item.type}:${item.id}"

    fun loaded(items: List<UnifiedSearchItemDto>, type: String) = items.count { it.type == type }

    fun remaining(group: UnifiedSearchGroupDto, items: List<UnifiedSearchItemDto>) = (group.total - loaded(items, group.type)).coerceAtLeast(0)

    fun more(type: String?, groups: List<UnifiedSearchGroupDto>, items: List<UnifiedSearchItemDto>): Boolean =
        groups.filter { type == null || it.type == type }.any { remaining(it, items) > 0 }

    fun nextBatch(type: String?, groups: List<UnifiedSearchGroupDto>, items: List<UnifiedSearchItemDto>): Int {
        val left = groups.filter { type == null || it.type == type }.sumOf { remaining(it, items) }
        return if (type == null) left else minOf(TYPE_LIMIT, left)
    }

    fun sections(groups: List<UnifiedSearchGroupDto>, items: List<UnifiedSearchItemDto>): List<Pair<UnifiedSearchGroupDto, List<UnifiedSearchItemDto>>> =
        groups.mapNotNull { g -> items.filter { it.type == g.type }.takeIf { it.isNotEmpty() }?.let { g to it } }
}

private sealed interface Found {
    data object Idle : Found
    data object Loading : Found
    data class Ready(val response: UnifiedSearchResponse, val groups: List<UnifiedSearchGroupDto>, val items: List<UnifiedSearchItemDto>, val more: Boolean, val type: String? = null, val page: Int = 1) : Found
    data object Legacy : Found
    data class Failed(val offline: Boolean) : Found
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DataUnifiedSearch(
    repository: FieldRepository,
    initial: String,
    onOpenInData: (type: String, id: String, title: String) -> Unit,
    onPlay: (mediaId: String, title: String, mediaType: String?, startSeconds: Double) -> Unit,
    onOpenRemote: ((type: String, id: String) -> Unit)?,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initial) }
    var scope by rememberSaveable { mutableStateOf("local") }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf(1) }
    var attempt by remember { mutableStateOf(0) }
    var found by remember { mutableStateOf<Found>(Found.Idle) }
    var last by remember { mutableStateOf<UnifiedSearchResponse?>(null) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    LaunchedEffect(query, scope, type, page, attempt) {
        val q = query.trim()
        if (q.length < 2) {
            found = Found.Idle
            return@LaunchedEffect
        }
        if (page == 1) delay(300)
        val previous = (found as? Found.Ready)?.takeIf { page > 1 && it.type == type && it.page == page - 1 }
        if (previous == null) found = Found.Loading
        found = try {
            val r = repository.unifiedSearchIn(q, limit = DataSearchRules.limitFor(type), scope = scope, type = type, page = page)
            last = r
            if (DataSearchRules.crossBlocked(scope, r)) {
                blocked = true
                scope = "local"
            }
            val groups = previous?.groups ?: r.groups
            val merged = (previous?.items.orEmpty() + DataSearchRules.usable(r, type)).distinctBy(DataSearchRules::key)
            val progressed = previous == null || merged.size > previous.items.size
            Found.Ready(r, groups, merged, progressed && DataSearchRules.more(type, groups, merged), type, page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: HttpException) {
            if (e.code() == 404) Found.Legacy else if (e.code() == 422) Found.Idle else Found.Failed(offline = false)
        } catch (e: java.io.IOException) {
            Found.Failed(offline = true)
        } catch (e: Throwable) {
            Found.Failed(offline = false)
        }
        val failed = found as? Found.Failed
        if (failed != null && !failed.offline) {
            for (left in DataSearchRules.RETRY_SECONDS downTo 1) {
                countdown = left
                delay(1_000)
            }
            countdown = null
            attempt++
        } else {
            countdown = null
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp).testTag("data.search"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.size(44.dp).testTag("data.search.back")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(28.dp))
            }
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier.weight(1f)
                    .border(4.dp, if (focused) DataSearchStyle.halo() else Color.Transparent, RoundedCornerShape(16.dp))
                    .padding(4.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; page = 1 },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    placeholder = { Text(DataSearchText.hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.field.placeholder) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.field.surface50).clickable(role = Role.Button) { query = ""; page = 1 }.semantics { contentDescription = "Clear" },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)) }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.field.hairline,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }.focusRequester(focus).testTag("data.search.q"),
                )
            }
        }
        val response = (found as? Found.Ready)?.response ?: last
        val groups = (found as? Found.Ready)?.groups ?: response?.groups.orEmpty()
        if (!blocked) {
            ScopeSwitch(scope) { scope = it; page = 1 }
        }
        if (scope == "all" && response != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PortalBadge(local = true, label = "● ${DataSearchText.ours} ${DataSearchRules.localTotal(response)}")
                PortalBadge(local = false, label = "● ${DataSearchText.theirs} ${DataSearchRules.remoteTotal(response)}")
            }
        }
        if (response != null && found !is Found.Legacy) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypePill("${DataSearchText.all} ${groups.sumOf { it.total }}", type == null, "all") { type = null; page = 1 }
                groups.filter { it.total > 0 }.forEach { g ->
                    TypePill("${g.label.ifBlank { DataSearchRules.label(g.type, null) }} ${g.total}", type == g.type, g.type) { type = g.type; page = 1 }
                }
            }
        }
        when (val now = found) {
            Found.Idle -> if (query.isNotBlank()) Text(DataSearchText.typeMore, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Found.Loading -> Text(DataSearchText.searching, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("data.search.loading"))
            is Found.Failed -> {
                val tone = if (now.offline) explorerWarnTone() else explorerTone(ExplorerStatus.FAILED)!!
                Row(Modifier.fillMaxWidth().background(tone.container, MaterialTheme.shapes.large).border(1.dp, tone.border, MaterialTheme.shapes.large).padding(12.dp).testTag(if (now.offline) "data.search.offline" else "data.search.failed"), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (now.offline) DataSearchText.offline else listOfNotNull(DataSearchText.failed, countdown?.let { DataSearchText.retryIn(it) }).joinToString(" "),
                        color = tone.content,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("data.search.retry")) { Text(DataSearchText.tryNow, color = tone.content) }
                }
            }
            Found.Legacy -> Column(Modifier.fillMaxWidth().testTag("data.search.legacy")) {
                LegacyHits(repository, query) { t, id, title -> onOpenInData(t, id, title) }
            }
            is Found.Ready -> {
                if (now.items.isEmpty()) {
                    NoMatches(
                        query = query.trim(),
                        suggestion = now.response.suggestion,
                        filtered = type != null,
                        canWiden = scope == "local" && !blocked,
                        onSuggest = { query = it; page = 1 },
                        onClear = { type = null; page = 1 },
                        onWiden = { scope = "all"; page = 1 },
                    )
                } else {
                    val total = now.groups.filter { type == null || it.type == type }.sumOf { it.total }
                    Text(
                        DataSearchText.shownLine(now.items.size, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.field.muted,
                        modifier = Modifier.testTag("data.search.shown"),
                    )
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("data.search.results"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (type == null) {
                            DataSearchRules.sections(now.groups, now.items).forEach { (g, shown) ->
                                val label = g.label.ifBlank { DataSearchRules.label(g.type, null) }
                                item("head.${g.type}") {
                                    Row(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("data.search.group.${g.type}"), verticalAlignment = Alignment.CenterVertically) {
                                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).semantics { heading() })
                                        Text(DataSearchText.shownOf(shown.size, g.total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                                    }
                                }
                                items(shown, key = DataSearchRules::key) { item ->
                                    ResultCard(item, scope == "all", onOpenInData, onPlay, onOpenRemote)
                                }
                                if (shown.size < g.total) {
                                    item("all.${g.type}") {
                                        TextButton(onClick = { type = g.type; page = 1 }, modifier = Modifier.heightIn(min = 44.dp).testTag("data.search.all.${g.type}")) {
                                            Text(DataSearchText.showAll(g.total, label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        } else {
                            items(now.items, key = DataSearchRules::key) { item ->
                                ResultCard(item, scope == "all", onOpenInData, onPlay, onOpenRemote)
                            }
                        }
                        if (now.more) {
                            item("more") {
                                LaunchedEffect(now.items.size, now.type, now.page) { if (now.type == type && now.page == page) page = now.page + 1 }
                                OutlinedButton(
                                    onClick = { if (now.type == type && now.page == page) page = now.page + 1 },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.field.hairline),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("data.search.more"),
                                ) {
                                    Text(if (type == null) DataSearchText.loadMore else DataSearchText.showMore(DataSearchRules.nextBatch(type, now.groups, now.items)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        if (scope == "all") {
                            item("footnote") {
                                Text(
                                    DataSearchText.footnote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.field.muted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("data.search.footnote"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeSwitch(scope: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("local" to DataSearchText.thisApp, "all" to DataSearchText.bothPortals).forEach { (key, label) ->
            val on = key == scope
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onPick(key) }
                    .testTag("data.search.scope.$key"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.field.body,
                )
            }
        }
    }
}

@Composable
private fun TypePill(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val dark = MaterialTheme.field.isDark
    val fill = if (on) (if (dark) DataSearchStyle.pillOnDark else DataSearchStyle.pillOn) else MaterialTheme.colorScheme.surface
    val ink = if (on) (if (dark) DataSearchStyle.pillOnInkDark else Color.White) else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(fill)
            .border(1.dp, if (on) Color.Transparent else MaterialTheme.field.hairline, RoundedCornerShape(50))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag("data.search.type.$tag"),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = ink)
    }
}

@Composable
private fun PortalBadge(local: Boolean, label: String) {
    val dark = MaterialTheme.field.isDark
    val (bg, fg) = if (!local) explorerMark() else if (dark) Color(0xFF0F3D3A) to Color(0xFF99F6E4) else Color(0xFFCCFBF1) to Color(0xFF115E59)
    Box(Modifier.heightIn(min = 22.dp).background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp).testTag(if (local) "data.search.badge.fr" else "data.search.badge.dw"), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = fg)
    }
}

internal object DataSearchStyle {
    val pillOn = Color(0xFF1E1B2E)
    val pillOnDark = Color(0xFFF2F0F9)
    val pillOnInkDark = Color(0xFF14121F)

    @Composable
    fun halo(): Color = if (MaterialTheme.field.isDark) Color(0xFF3B1F5C) else Color(0xFFF2E9FE)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(
    item: UnifiedSearchItemDto,
    both: Boolean,
    onOpenInData: (type: String, id: String, title: String) -> Unit,
    onPlay: (mediaId: String, title: String, mediaType: String?, startSeconds: Double) -> Unit,
    onOpenRemote: ((type: String, id: String) -> Unit)?,
) {
    val also = DataSearchRules.alsoIn(item)
    val (markBg, markFg) = explorerMark()
    val start = if (item.type == "transcripts") DataSearchRules.startSeconds(item) else null
    val locate = DataSearchRules.locateType(item.type)
    val openLocal: (() -> Unit)? = if (item.local && locate != null) ({ onOpenInData(locate, DataSearchRules.openId(item), item.title) }) else null
    val openRemote: (() -> Unit)? = if (!item.local && onOpenRemote != null) ({ onOpenRemote(item.type, item.id) }) else null
    val tap = if (item.type == "transcripts") null else openLocal ?: openRemote
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .let { m -> if (also != null) m.dashedBorder(MaterialTheme.field.hairline, 16.dp) else m.border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.large) }
            .clip(MaterialTheme.shapes.large)
            .let { m -> if (tap != null) m.clickable(role = Role.Button, onClick = tap) else m }
            .padding(14.dp)
            .testTag("data.search.item.${item.type}.${item.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (also != null) {
                PortalBadge(local = true, label = "● ${DataSearchText.ourShort}")
                PortalBadge(local = false, label = "● ${DataSearchText.theirShort}")
            } else if (both) {
                PortalBadge(local = item.local, label = "● " + if (item.local) DataSearchText.ours else DataSearchText.theirs)
            }
            Text(
                listOfNotNull(DataSearchRules.label(item.type, null), start?.let { DataSearchRules.clock(it) }, DataSearchText.inBoth.takeIf { also != null }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted,
                textAlign = if (also != null || both) TextAlign.End else TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            DataSearchRules.date(item.recordDate)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
        }
        Text(item.title.ifBlank { item.id }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
            Text(
                buildAnnotatedString {
                    append("“")
                    append(marked(snippet, DataSearchRules.ranges(item, snippet), markBg, markFg))
                    append("”")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.field.body,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val sub = if (also != null) {
            listOfNotNull(DataSearchText.sameInBoth, item.subtitle, (also["subtitle"] as? JsonPrimitive)?.content).joinToString(" · ")
        } else {
            listOfNotNull(item.subtitle?.ifBlank { null }, item.place?.ifBlank { null }).joinToString(" · ")
        }
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        if (item.type == "transcripts") {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.local) {
                    CardAction(start?.let { DataSearchText.playFrom(DataSearchRules.clock(it)) } ?: DataSearchText.play, "data.search.play.${item.id}") {
                        onPlay(item.id, item.title, item.mediaType, start ?: 0.0)
                    }
                    openLocal?.let { open -> CardAction(DataSearchText.openInData, "data.search.open.${item.id}", open) }
                } else if (openRemote != null) {
                    CardAction(DataSearchText.openThere, null, openRemote)
                }
            }
        }
    }
}

@Composable
private fun RowScope.CardAction(label: String, tag: String?, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.field.hairline),
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).let { m -> if (tag != null) m.testTag(tag) else m },
    ) { Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

@Composable
private fun NoMatches(query: String, suggestion: String?, filtered: Boolean, canWiden: Boolean, onSuggest: (String) -> Unit, onClear: () -> Unit, onWiden: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("data.search.none"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(56.dp).background(MaterialTheme.field.surface50, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.field.muted, modifier = Modifier.size(26.dp))
        }
        Text(DataSearchText.noMatches(query), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        suggestion?.takeIf { it.isNotBlank() && !it.equals(query, ignoreCase = true) }?.let { s ->
            val line = DataSearchText.didYouMean(s)
            val at = line.indexOf(s)
            TextButton(onClick = { onSuggest(s) }, modifier = Modifier.heightIn(min = 44.dp).testTag("data.search.suggestion")) {
                Text(
                    buildAnnotatedString {
                        append(line)
                        if (at >= 0) addStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold), at, at + s.length)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.field.body,
                )
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filtered) NoMatchAction(DataSearchText.clearFilter, null, onClear)
            if (canWiden) NoMatchAction(DataSearchText.searchBoth(null), "data.search.widen", onWiden)
        }
    }
}

@Composable
private fun NoMatchAction(label: String, tag: String?, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.field.hairline),
        modifier = Modifier.heightIn(min = 44.dp).let { m -> if (tag != null) m.testTag(tag) else m },
    ) { Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun LegacyHits(repository: FieldRepository, query: String, onOpen: (String, String, String) -> Unit) {
    val filters = remember(query.trim()) { SearchFilters(query = query.trim()) }
    val state = remember(filters) { SearchAllState(filters, quickSearchFetch(repository, filters)) }
    LaunchedEffect(state) {
        if (filters.query.length < QUICK_SEARCH_MIN_CHARS) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MILLIS)
        state.start()
    }
    SearchAllList(state = state, onOpen = onOpen, modifier = Modifier.fillMaxSize())
}
