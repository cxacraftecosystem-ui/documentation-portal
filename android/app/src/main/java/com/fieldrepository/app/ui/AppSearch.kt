package com.fieldrepository.app.ui

import com.fieldrepository.app.data.userMessage
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/*
 * THE SEARCH HEADER AND THE SEARCH PANEL — one app-wide way to find a record, a screen or a setting.
 *
 * ── WHAT THE OWNER ASKED FOR (2026-10-16) ───────────────────────────────────────────────────────
 *
 * The top of every main screen carries the app's name and logo again, styled as the island's brand
 * end was before the island moved to the bottom. It is part of the PAGE, not chrome: it scrolls
 * away with the page and comes back at the top, and it never collapses into anything. Tapping it,
 * or the search glyph inside it, turns it into a search field ([AppSearchPanel]), which finds:
 *
 *  - SCREENS — the destinations this account's menu offers, from the same `visibleNavItems`, so a
 *    search can no more offer a screen the role may not open than the menu can;
 *  - SETTINGS — the rows of the settings screens, by name and by the words people use for them;
 *  - RECORDS — through the server's existing `GET /search` and the list endpoints' own `search`
 *    parameter. Offline, the records this phone already holds stand in for the server.
 *
 * ── THE RULES ARE PURE AND SHARED ───────────────────────────────────────────────────────────────
 *
 * [appSearchScore], [groupAppSearchResults] and [pushRecentSearch] are the ranking, grouping and
 * recent-search rules, unit-tested in `AppSearchTest`, and the web's `frontend/lib/globalSearch.ts`
 * implements the same ones: group order [AppSearchGroup], score 0 exact / 1 prefix / 2 word prefix /
 * 3 substring / 4 any other match, stable within a score, five per group, eight recents.
 *
 * This file is shared VERBATIM, package line aside, by the designer portal's and the documentation
 * portal's Android apps. What differs between the two apps (which entities exist, which screens a
 * result opens) lives in each app's `AppSearchSources.kt`.
 */

/** The result groups, in the order the panel shows them. */
enum class AppSearchGroup(val label: String) {
    SCREENS("Screens"),
    SETTINGS("Settings"),
    ARTISANS("Artisans"),
    CRAFTS("Crafts"),
    WORKSHOPS("Workshops"),
    DESIGN_WORKSHOPS("Design workshops"),
    PRODUCTS("Products"),
    TOOLS("Tools"),
    PROCESSES("Processes"),
    INTERVIEWS("Interviews"),
    QUESTIONNAIRES("Questionnaires"),
    MEDIA("Media")
}

/**
 * One thing a search can find.
 *
 * [key] identifies it within its [group] — a record id, or a destination name — so the same record
 * arriving from the server and from this phone's cache is shown once. [keywords] are the other words
 * a local item answers to ("dark mode" for the theme row); a server result never needs them, because
 * the server already decided it matched.
 */
class AppSearchItem(
    val group: AppSearchGroup,
    val key: String,
    val title: String,
    val icon: ImageVector,
    val subtitle: String? = null,
    val keywords: List<String> = emptyList(),
    val onOpen: () -> Unit
)

/** A group as the panel draws it: the best [items] and how many matched in all. */
class AppSearchSection(val group: AppSearchGroup, val items: List<AppSearchItem>, val total: Int)

/** Server sources are asked only from this many characters on; screens and settings from one. */
const val APP_SEARCH_MIN_REMOTE_CHARS = 2
/** How long typing must pause before the server is asked. */
const val APP_SEARCH_DEBOUNCE_MS = 300L
/** Rows per group in the panel. */
const val APP_SEARCH_GROUP_CAP = 5
/** Recent searches kept. */
const val APP_SEARCH_RECENT_MAX = 8

/**
 * How well [title] answers [query], lower is better: 0 exact, 1 the title starts with it, 2 some word
 * of the title starts with it, 3 the title contains it, 4 anything else (the server, or a keyword,
 * matched on something the title does not show). Case-insensitive, whitespace-trimmed.
 */
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

/** Whether a LOCAL item (screen, setting, cached record) answers [query] at all. */
fun appSearchLocalMatch(item: AppSearchItem, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return false
    return item.title.lowercase().contains(q) ||
        item.subtitle?.lowercase()?.contains(q) == true ||
        item.keywords.any { it.lowercase().contains(q) }
}

/**
 * The panel's sections: duplicates (same group and key) dropped keeping the first, each group ranked
 * by [appSearchScore] with ties left in arrival order, cut to [cap], and the groups laid out in
 * [AppSearchGroup] order with empty ones left out.
 */
fun groupAppSearchResults(
    items: List<AppSearchItem>,
    query: String,
    cap: Int = APP_SEARCH_GROUP_CAP
): List<AppSearchSection> {
    val seen = HashSet<Pair<AppSearchGroup, String>>()
    val unique = items.filter { seen.add(it.group to it.key) }
    return AppSearchGroup.entries.mapNotNull { group ->
        val inGroup = unique.filter { it.group == group }
        if (inGroup.isEmpty()) {
            null
        } else {
            // sortedBy is stable, which is what keeps ties in the server's own order.
            val ranked = inGroup.sortedBy { appSearchScore(it.title, query) }
            AppSearchSection(group, ranked.take(cap), inGroup.size)
        }
    }
}

/** [query] put at the front of [recents], de-duplicated case-insensitively, at most [max] kept. */
fun pushRecentSearch(recents: List<String>, query: String, max: Int = APP_SEARCH_RECENT_MAX): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return recents.take(max)
    return (listOf(q) + recents.filterNot { it.equals(q, ignoreCase = true) }).take(max)
}

/** [recents] without [query] (case-insensitive). */
fun removeRecentSearch(recents: List<String>, query: String): List<String> =
    recents.filterNot { it.equals(query.trim(), ignoreCase = true) }

/**
 * Where the recent searches live: this phone only, in SharedPreferences. A search history is the
 * person's own and never leaves the device.
 */
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

// ---------------------------------------------------------------------------------------------
// The header
// ---------------------------------------------------------------------------------------------

/**
 * The app's name and logo at the top of the page, in the pill the island's brand end used to wear.
 *
 * Put it FIRST inside the page's scrolling column — never above the scroller — so it scrolls away
 * with the page and is there again at the top. One tap target, the whole pill: tapping the name or
 * the search glyph both open [AppSearchPanel].
 */
@Composable
fun AppSearchHeader(
    appName: String,
    logo: @Composable (Modifier) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.field.hairline)
    ) {
        Row(
            modifier = Modifier
                .clickable(onClickLabel = "Search", role = Role.Button, onClick = onOpenSearch)
                .semantics(mergeDescendants = true) { contentDescription = "$appName. Search" }
                .heightIn(min = 52.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            logo(Modifier.size(24.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                appName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The panel
// ---------------------------------------------------------------------------------------------

/**
 * The header turned into a search field, with the results under it.
 *
 * Draw it OVER the page (a later child of the same Box), so the page underneath keeps its scroll
 * position and any half-filled form keeps its state. Back closes it; the keyboard's Search key
 * records the query as a recent search; opening a result records it too and closes the panel.
 *
 * @param localItems screens, settings and the records this phone already holds; filtered here.
 * @param remoteSearch asks the server; throws [IOException] when the phone cannot reach it, which is
 *   how the panel knows to say it is showing only what is on the phone.
 */
@Composable
fun AppSearchPanel(
    appName: String,
    logo: @Composable (Modifier) -> Unit,
    localItems: List<AppSearchItem>,
    remoteSearch: suspend (String) -> List<AppSearchItem>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * A server failure that is NOT "no network", in words (`Throwable.userMessage`), for the app's
     * one message surface (`AppMessageBanner`). It used to be swallowed, so a refused search read
     * exactly like a search with no matches. "No network" stays the inline note below, because it
     * explains the list under it rather than reporting a fault.
     */
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val store = remember(context) { AppSearchRecentStore(context) }
    var recents by remember { mutableStateOf(store.read()) }
    var query by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf<List<AppSearchItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun saveRecent(q: String) {
        recents = pushRecentSearch(recents, q)
        store.write(recents)
    }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // Restarted on every keystroke, which cancels the request in flight — the newest query wins.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < APP_SEARCH_MIN_REMOTE_CHARS) {
            remote = emptyList()
            loading = false
            return@LaunchedEffect
        }
        delay(APP_SEARCH_DEBOUNCE_MS)
        loading = true
        try {
            remote = remoteSearch(q)
            offline = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            remote = emptyList()
            offline = true
        } catch (e: Exception) {
            remote = emptyList()
            onError(e.userMessage("Search could not reach the server. Showing what is saved on this phone."))
        } finally {
            loading = false
        }
    }

    val local = localItems.filter { appSearchLocalMatch(it, query) }
    val sections = groupAppSearchResults(remote + local, query)

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
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
        }
        if (offline && query.trim().length >= APP_SEARCH_MIN_REMOTE_CHARS) {
            Text(
                "You're offline. Showing screens, settings and records saved on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
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
                                onClose()
                                result.onOpen()
                            }
                        }
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
        }
    }
}
