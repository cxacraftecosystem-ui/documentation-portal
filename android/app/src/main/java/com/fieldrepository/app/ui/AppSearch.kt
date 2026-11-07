package com.fieldrepository.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

enum class AppSearchGroup(val label: String) {
    SCREENS("Screens"),
    SETTINGS("Settings"),
    ARTISANS("Artisans"),
    CRAFTS("Crafts"),
    CLUSTERS("Clusters"),
    WORKSHOPS("Workshops"),
    DESIGN_WORKSHOPS("Design workshops"),
    PRODUCTS("Products"),
    TOOLS("Tools"),
    PROCESSES("Processes"),
    INTERVIEWS("Interviews"),
    TRANSCRIPTS("Transcripts"),
    REPORTS("Reports"),
    QUESTIONNAIRES("Questionnaires"),
    MEDIA("Media"),
    RECORDINGS("Recordings")
}

class AppSearchItem(
    val group: AppSearchGroup,
    val key: String,
    val title: String,
    val icon: ImageVector,
    val subtitle: String? = null,
    val keywords: List<String> = emptyList(),
    val portal: String? = null,
    val chips: com.fieldrepository.app.questionnaire.data.QChips? = null,
    val onOpen: () -> Unit
)

fun appSearchPortalLabel(portal: String): String = when (portal) {
    "fieldRepository" -> "Field Repository"
    "designer" -> "Designer portal"
    else -> portal
}

@Stable
class AppSearchSession {
    val queryState = mutableStateOf("")
    val fullState = mutableStateOf<String?>(null)
    val remoteState = mutableStateOf<List<AppSearchItem>>(emptyList())
    var remoteQuery: String? = null
    var openedResult by mutableStateOf(false)
        private set
    private var restoring = false

    val query: String get() = queryState.value
    val full: String? get() = fullState.value

    fun markOpened() {
        openedResult = true
    }

    fun clearOpened() {
        openedResult = false
    }

    fun start() {
        queryState.value = ""
        fullState.value = null
        remoteState.value = emptyList()
        remoteQuery = null
        openedResult = false
        restoring = false
    }

    fun restore() {
        restoring = true
        openedResult = false
    }

    fun consumeRestore(): Boolean {
        val was = restoring
        restoring = false
        return was
    }
}

@Stable
class AppSearchReturn<S : Any> {
    var origin by mutableStateOf<S?>(null)
        private set
    var target by mutableStateOf<S?>(null)
        private set

    fun begin(current: S) {
        origin = current
        target = null
    }

    fun observe(current: S, session: AppSearchSession) {
        val from = origin ?: return
        if (session.openedResult && current != from) {
            target = current
            session.clearOpened()
        } else if (current == from) {
            target = null
        }
    }

    fun backTo(current: S): S? {
        val from = origin ?: return null
        return if (target != null && current == target) from else null
    }

    fun returned() {
        target = null
    }
}

class AppSearchSection(val group: AppSearchGroup, val items: List<AppSearchItem>, val total: Int)

const val APP_SEARCH_MIN_REMOTE_CHARS = 2
const val APP_SEARCH_DEBOUNCE_MS = 300L
const val APP_SEARCH_GROUP_CAP = 5
const val APP_SEARCH_RECENT_MAX = 8

fun appSearchScore(title: String, query: String): Int {
    val t = title.trim().lowercase()
    val q = query.trim().lowercase()
    if (q.isEmpty()) return 4
    return when {
        t == q -> 0
        t.startsWith(q) -> 1
        t.split(Regex("[^\\p{L}\\p{N}]+")).any { it.isNotEmpty() && it.startsWith(q) } -> 2
        t.contains(q) -> 3
        else -> 4
    }
}

fun appSearchLocalMatch(item: AppSearchItem, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return false
    return item.title.lowercase().contains(q) ||
        item.subtitle?.lowercase()?.contains(q) == true ||
        item.keywords.any { it.lowercase().contains(q) }
}

fun appSearchAllLine(query: String): String = "See all records for “${query.trim()}”"

fun groupAppSearchResults(
    items: List<AppSearchItem>,
    query: String,
    cap: Int = APP_SEARCH_GROUP_CAP
): List<AppSearchSection> {
    val seen = HashSet<Triple<AppSearchGroup, String?, String>>()
    val unique = items.filter { seen.add(Triple(it.group, it.portal, it.key)) }
    return AppSearchGroup.entries.mapNotNull { group ->
        val inGroup = unique.filter { it.group == group }
        if (inGroup.isEmpty()) {
            null
        } else {
            val ranked = inGroup.sortedBy { appSearchScore(it.title, query) }
            AppSearchSection(group, ranked.take(cap), inGroup.size)
        }
    }
}

fun pushRecentSearch(recents: List<String>, query: String, max: Int = APP_SEARCH_RECENT_MAX): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return recents.take(max)
    return (listOf(q) + recents.filterNot { it.equals(q, ignoreCase = true) }).take(max)
}

fun removeRecentSearch(recents: List<String>, query: String): List<String> =
    recents.filterNot { it.equals(query.trim(), ignoreCase = true) }

class AppSearchRecentStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_search", Context.MODE_PRIVATE)

    fun read(): List<String> =
        prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }?.take(APP_SEARCH_RECENT_MAX).orEmpty()

    fun write(recents: List<String>) {
        prefs.edit().putString(KEY, recents.joinToString("\n") { it.replace('\n', ' ') }).apply()
    }

    private companion object {
        const val KEY = "recent"
    }
}

@Composable
fun AppSearchHeader(
    appName: String,
    logo: @Composable (Modifier) -> Unit,
    onOpenSearch: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = false
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (elevated) 6.dp else 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.field.hairline)
    ) {
        Box(
            modifier = Modifier
                .clickable(onClickLabel = "Search", role = Role.Button, onClick = onOpenSearch)
                .semantics(mergeDescendants = true) { contentDescription = "$appName. Search" }
                .heightIn(min = 60.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                logo(Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    appName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            TopBarScanButton(onClick = onScan, modifier = Modifier.align(Alignment.CenterStart))
        }
    }
}

@Composable
fun TopBarScanButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).testTag("top-bar-scan")) {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan a code", tint = MaterialTheme.colorScheme.primary)
    }
}

data class QuickReturnStep(val shown: Boolean, val travelPx: Float = 0f)

const val QUICK_RETURN_SLIDE_MS = 180

fun quickReturnSlideMillis(reducedMotion: Boolean): Int = if (reducedMotion) 0 else QUICK_RETURN_SLIDE_MS

fun quickReturnStep(
    state: QuickReturnStep,
    consumedY: Float,
    scrollOffsetPx: Int,
    headerHeightPx: Int,
    hideAfterPx: Float,
    pinned: Boolean
): QuickReturnStep = when {
    pinned || scrollOffsetPx <= 0 -> QuickReturnStep(shown = true)
    consumedY > 0f -> QuickReturnStep(shown = true)
    consumedY < 0f -> {
        val travel = state.travelPx - consumedY
        val hide = travel >= hideAfterPx && scrollOffsetPx > headerHeightPx
        QuickReturnStep(shown = state.shown && !hide, travelPx = if (hide) 0f else travel)
    }
    else -> state
}

private val QUICK_RETURN_HIDE_AFTER = 24.dp
private val QUICK_RETURN_GAP = 8.dp

@Stable
class QuickReturnHeaderState internal constructor(
    private val scrollOffset: () -> Int,
    private val hideAfterPx: Float,
    private val gapPx: Int
) {
    var step by mutableStateOf(QuickReturnStep(shown = true))
        private set
    var heightPx by mutableIntStateOf(0)
        internal set
    internal var pinned: Boolean = false
        set(value) {
            field = value
            if (value) step = QuickReturnStep(shown = true)
        }

    val shown: Boolean get() = step.shown

    val reservePx: Int get() = if (heightPx > 0) heightPx + gapPx else 0

    internal fun offsetNow(): Int = scrollOffset()

    internal fun report(consumedY: Float) {
        step = quickReturnStep(step, consumedY, scrollOffset(), heightPx, hideAfterPx, pinned)
    }

    internal fun atTop() {
        step = QuickReturnStep(shown = true)
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed.y != 0f) report(consumed.y)
            return Offset.Zero
        }
    }
}

@Composable
fun rememberQuickReturnHeader(scrollState: ScrollState, pinned: Boolean): QuickReturnHeaderState {
    val density = LocalDensity.current
    val state = remember(scrollState, density) {
        QuickReturnHeaderState(
            scrollOffset = { scrollState.value },
            hideAfterPx = with(density) { QUICK_RETURN_HIDE_AFTER.toPx() },
            gapPx = with(density) { QUICK_RETURN_GAP.roundToPx() }
        )
    }
    state.pinned = pinned
    val atTop by remember(scrollState) { derivedStateOf { scrollState.value == 0 } }
    LaunchedEffect(atTop) { if (atTop) state.atTop() }
    return state
}

fun lazyPageScrollOffset(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Int =
    if (firstVisibleItemIndex > 0) Int.MAX_VALUE / 4 else firstVisibleItemScrollOffset

@Composable
fun rememberQuickReturnHeader(listState: LazyListState, pinned: Boolean): QuickReturnHeaderState {
    val density = LocalDensity.current
    val state = remember(listState, density) {
        QuickReturnHeaderState(
            scrollOffset = { lazyPageScrollOffset(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) },
            hideAfterPx = with(density) { QUICK_RETURN_HIDE_AFTER.toPx() },
            gapPx = with(density) { QUICK_RETURN_GAP.roundToPx() }
        )
    }
    state.pinned = pinned
    val atTop by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    LaunchedEffect(atTop) { if (atTop) state.atTop() }
    return state
}

@Composable
fun QuickReturnHeader(
    state: QuickReturnHeaderState,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    content: @Composable (elevated: Boolean) -> Unit
) {
    val reducedMotion = LocalAppPreferences.current.reducedMotion
    val show = state.shown && !hidden
    val slide by animateIntAsState(
        targetValue = if (show) 0 else -(state.heightPx + 1),
        animationSpec = if (reducedMotion) snap() else tween(quickReturnSlideMillis(reducedMotion)),
        label = "quickReturnHeader"
    )
    val overContent by remember(state) { derivedStateOf { state.offsetNow() > 0 } }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { state.heightPx = it.height }
            .offset { IntOffset(0, slide) }
            .graphicsLayer {
                alpha = if (state.heightPx > 0) (1f + slide.toFloat() / state.heightPx).coerceIn(0f, 1f) else 1f
            }
            .then(if (show) Modifier else Modifier.clearAndSetSemantics { })
    ) {
        content(overContent)
    }
}

@Composable
fun AppSearchPanel(
    appName: String,
    logo: @Composable (Modifier) -> Unit,
    localItems: List<AppSearchItem>,
    remoteSearch: suspend (String) -> List<AppSearchItem>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
    fullSearch: (@Composable (query: String, onBack: () -> Unit) -> Unit)? = null,
    session: AppSearchSession = remember { AppSearchSession() }
) {
    val context = LocalContext.current
    val store = remember(context) { AppSearchRecentStore(context) }
    var recents by remember { mutableStateOf(store.read()) }
    var query by session.queryState
    var remote by session.remoteState
    var loading by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun saveRecent(q: String) {
        recents = pushRecentSearch(recents, q)
        store.write(recents)
    }

    var full by session.fullState
    BackHandler(onBack = onClose)
    BackHandler(enabled = full != null) { full = null }
    LaunchedEffect(Unit) {
        if (session.consumeRestore()) {
            keyboard?.hide()
        } else {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (session.remoteQuery == q && q.isNotEmpty()) return@LaunchedEffect
        session.remoteQuery = null
        if (q.length < APP_SEARCH_MIN_REMOTE_CHARS) {
            remote = emptyList()
            loading = false
            return@LaunchedEffect
        }
        delay(APP_SEARCH_DEBOUNCE_MS)
        loading = true
        try {
            remote = remoteSearch(q)
            session.remoteQuery = q
            offline = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            remote = emptyList()
            offline = true
        } catch (e: Exception) {
            remote = emptyList()
            onError(appSearchErrorLine(e))
        } finally {
            loading = false
        }
    }

    val local = localItems.filter { appSearchLocalMatch(it, query) }
    val sections = groupAppSearchResults(remote + local, query)

    val fullQuery = full
    if (fullQuery != null && fullSearch != null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding()
                .semantics { paneTitle = "Search" }
        ) { fullSearch(fullQuery) { full = null } }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .semantics { paneTitle = "Search" }
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        ) {
            Row(
                modifier = Modifier.heightIn(min = 52.dp).padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Close search",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            "Search $appName",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.field.muted,
                            maxLines = 1
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            saveRecent(query)
                            keyboard?.hide()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .semantics { contentDescription = "Search $appName" }
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = MaterialTheme.field.body)
                    }
                } else {
                    logo(Modifier.padding(horizontal = 12.dp).size(24.dp))
                }
            }
        }

        if (loading) {
            FieldLinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
        }
        if (offline && query.trim().length >= APP_SEARCH_MIN_REMOTE_CHARS) {
            Text(
                "Offline — showing what's on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + (LocalBannerClearance.current?.value ?: 0.dp))
        ) {
            if (query.isBlank()) {
                if (recents.isNotEmpty()) {
                    item(key = "recents-heading") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppSearchHeading("Recent searches", Modifier.weight(1f))
                            TextButton(onClick = { recents = emptyList(); store.write(recents) }) { Text("Clear") }
                        }
                    }
                    recents.forEach { recent ->
                        item(key = "recent-$recent") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable(role = Role.Button) { query = recent }
                                    .padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.field.muted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(14.dp))
                                Text(recent, color = MaterialTheme.field.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    recents = removeRecentSearch(recents, recent)
                                    store.write(recents)
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove $recent from recent searches", tint = MaterialTheme.field.muted)
                                }
                            }
                        }
                    }
                } else {
                    item(key = "hint") {
                        Text(
                            "Find records, screens and settings.",
                            color = MaterialTheme.field.muted,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                sections.forEach { section ->
                    item(key = "h-${section.group.name}") {
                        AppSearchHeading(
                            if (section.total > section.items.size) {
                                "${section.group.label} · ${section.items.size} of ${section.total}"
                            } else {
                                section.group.label
                            }
                        )
                    }
                    section.items.forEach { result ->
                        item(key = "r-${section.group.name}-${result.key}") {
                            AppSearchRow(result) {
                                saveRecent(query)
                                keyboard?.hide()
                                session.markOpened()
                                onClose()
                                result.onOpen()
                            }
                        }
                    }
                }
                if (fullSearch != null && query.trim().length >= APP_SEARCH_MIN_REMOTE_CHARS) {
                    item(key = "all") {
                        TextButton(
                            onClick = {
                                saveRecent(query)
                                full = query.trim()
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("app.search.all")
                        ) { Text(appSearchAllLine(query)) }
                    }
                }
                if (sections.isEmpty() && !loading) {
                    item(key = "empty") {
                        Text(
                            "No matches for “${query.trim()}”.",
                            color = MaterialTheme.field.muted,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSearchHeading(label: String, modifier: Modifier = Modifier) {
    Text(
        label.uppercase(),
        style = FieldTextStyles.FieldLabel,
        color = MaterialTheme.field.muted,
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
            .semantics { heading() }
    )
}

@Composable
private fun AppSearchRow(item: AppSearchItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            item.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.field.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            item.chips?.let { com.fieldrepository.app.questionnaire.ui.RecordingChips(it) }
        }
        item.portal?.let { portal ->
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text(
                    appSearchPortalLabel(portal),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}
