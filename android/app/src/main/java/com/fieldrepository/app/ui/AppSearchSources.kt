package com.fieldrepository.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Security
import androidx.compose.ui.graphics.vector.ImageVector
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.UnifiedSearchItemDto
import com.fieldrepository.app.data.UnifiedSearchResponse
import com.fieldrepository.app.data.knownItems
import com.fieldrepository.app.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException
import java.io.IOException

/*
 * WHAT THIS APP'S SEARCH PANEL SEARCHES — the documentation portal's half of `AppSearch.kt`.
 *
 * The shared file holds the rules and the UI; this one names this app's sources:
 *
 *  - RECORDS: `GET /search/unified` first (docs/SEARCH.md): one ranked answer across this portal
 *    and, for the roles allowed, the designer portal, whose rows carry a portal chip and open
 *    [RemoteRecordScreen]. Where it answers 404 (switched off on the server), the older fan-out:
 *    `GET /search` (artisans, workshops, products, tools and media), plus the `search` filter of
 *    `GET /crafts`, `GET /processes` and `GET /questionnaire/interviews`. Each source that fails is
 *    skipped; only when EVERY source fails for want of a network does the panel say it is offline.
 *  - OFFLINE: the artisans and crafts this screen already holds.
 *  - SCREENS and SETTINGS: below.
 *
 * The designer portal's copy has the same function names and adds design workshops and authored
 * questionnaires, which this app does not have.
 */

/** The name the page header and the search field carry — the old island wordmark's words. */
const val APP_SEARCH_NAME = "Field Repository"

/** The settings rows a search can open, mapped to screens by the host. */
enum class AppSearchSettingTarget { APPEARANCE, AI_KEYS, TWO_STEP }

/** Settings by name and by the words people use for them. */
fun appSearchSettingItems(open: (AppSearchSettingTarget) -> Unit): List<AppSearchItem> = listOf(
    setting("appearance", "Appearance & accessibility", Icons.Filled.Palette, AppSearchSettingTarget.APPEARANCE,
        listOf("theme", "dark mode", "light mode", "colour", "color", "accessibility"), open),
    setting("theme", "Theme", Icons.Filled.Palette, AppSearchSettingTarget.APPEARANCE,
        listOf("dark", "light", "system", "night"), open),
    setting("larger-text", "Larger text", Icons.Filled.Palette, AppSearchSettingTarget.APPEARANCE,
        listOf("font size", "text size", "zoom", "bigger"), open),
    setting("reduced-motion", "Reduced motion", Icons.Filled.Palette, AppSearchSettingTarget.APPEARANCE,
        listOf("animation", "motion", "transitions"), open),
    setting("ai-keys", "My AI keys", Icons.Filled.Key, AppSearchSettingTarget.AI_KEYS,
        listOf("api key", "openai", "gemini", "anthropic", "ai"), open),
    setting("two-step", "Two-step verification", Icons.Filled.Security, AppSearchSettingTarget.TWO_STEP,
        listOf("2fa", "two factor", "authenticator", "otp", "security", "verification code"), open),
)

private fun setting(
    key: String,
    title: String,
    icon: ImageVector,
    target: AppSearchSettingTarget,
    keywords: List<String>,
    open: (AppSearchSettingTarget) -> Unit
) = AppSearchItem(AppSearchGroup.SETTINGS, key, title, icon, subtitle = "Settings", keywords = keywords) { open(target) }

/** The screens this account's menu offers — exactly [entries], which the host takes from `visibleNavItems`. */
fun appSearchScreenItems(entries: List<NavEntry>, open: (NavDestination) -> Unit): List<AppSearchItem> =
    entries.map { entry ->
        AppSearchItem(
            AppSearchGroup.SCREENS,
            entry.destination.name,
            entry.label,
            entry.icon,
            subtitle = entry.group?.label,
            keywords = listOfNotNull(entry.group?.label)
        ) { open(entry.destination) }
    }

/** The records this screen already holds, which is what the panel searches without a network. */
fun appSearchCachedItems(
    artisans: List<ArtisanDto>,
    crafts: List<CraftDto>,
    openRecord: (recordType: String, recordId: String) -> Unit
): List<AppSearchItem> =
    artisans.map { a ->
        AppSearchItem(AppSearchGroup.ARTISANS, a.id, a.name, Icons.Filled.Person, subtitle = a.place.ifBlank { null }) {
            openRecord(SearchRecordTypes.ARTISAN, a.id)
        }
    } + crafts.map { c ->
        AppSearchItem(
            AppSearchGroup.CRAFTS, c.id, c.name, Icons.Filled.Brush,
            subtitle = c.localName ?: c.place,
            keywords = listOfNotNull(c.localName)
        ) { openRecord(SearchRecordTypes.CRAFT, c.id) }
    }

/** How each unified type is drawn here: its group, its icon, and the record type this app opens. */
private class UnifiedKind(val group: AppSearchGroup, val icon: ImageVector, val recordType: String?)

// Clusters, design workshops and reports exist only on the designer portal: they arrive here only as
// remote rows, which open the read-only card, so they name no screen of this app.
private val UNIFIED_KINDS: Map<String, UnifiedKind> = mapOf(
    "artisans" to UnifiedKind(AppSearchGroup.ARTISANS, Icons.Filled.Person, SearchRecordTypes.ARTISAN),
    "crafts" to UnifiedKind(AppSearchGroup.CRAFTS, Icons.Filled.Brush, SearchRecordTypes.CRAFT),
    "clusters" to UnifiedKind(AppSearchGroup.CLUSTERS, Icons.Filled.Hub, null),
    "workshops" to UnifiedKind(AppSearchGroup.WORKSHOPS, Icons.Filled.Groups, SearchRecordTypes.WORKSHOP),
    "designWorkshops" to UnifiedKind(AppSearchGroup.DESIGN_WORKSHOPS, Icons.Filled.DesignServices, null),
    "interviews" to UnifiedKind(AppSearchGroup.INTERVIEWS, Icons.Filled.Quiz, SearchRecordTypes.QUESTIONNAIRE),
    "transcripts" to UnifiedKind(AppSearchGroup.TRANSCRIPTS, Icons.Filled.Mic, SearchRecordTypes.MEDIA),
    "reports" to UnifiedKind(AppSearchGroup.REPORTS, Icons.Filled.Description, null),
    "products" to UnifiedKind(AppSearchGroup.PRODUCTS, Icons.Filled.Inventory2, SearchRecordTypes.PRODUCT),
    "tools" to UnifiedKind(AppSearchGroup.TOOLS, Icons.Filled.Build, SearchRecordTypes.TOOL),
    "processes" to UnifiedKind(AppSearchGroup.PROCESSES, Icons.Filled.AccountTree, SearchRecordTypes.PROCESS),
    "media" to UnifiedKind(AppSearchGroup.MEDIA, Icons.Filled.PermMedia, SearchRecordTypes.MEDIA),
)

/**
 * The panel's items for a unified answer, in the server's group and rank order. A local item opens
 * this app's own screen; a remote one carries [AppSearchItem.portal] and opens the read-only card.
 */
fun unifiedSearchItems(
    response: UnifiedSearchResponse,
    openRecord: (recordType: String, recordId: String) -> Unit,
    openRemote: (type: String, id: String) -> Unit
): List<AppSearchItem> = response.knownItems().mapNotNull { item: UnifiedSearchItemDto ->
    val kind = UNIFIED_KINDS[item.type] ?: return@mapNotNull null
    val remote = !item.local
    val recordType = kind.recordType
    if (!remote && recordType == null) return@mapNotNull null
    AppSearchItem(
        kind.group,
        item.id,
        item.title.ifBlank { item.id },
        kind.icon,
        subtitle = item.subtitle?.ifBlank { null } ?: item.place,
        portal = if (remote) item.portal else null
    ) {
        if (remote || recordType == null) openRemote(item.type, item.id) else openRecord(recordType, item.id)
    }
}

/**
 * Every server source for [query]. `GET /search/unified` is asked first; only its 404 (switched off)
 * brings in the older sources. Throws [IOException] only when every source failed and at least one
 * of them for want of a network, which is the panel's cue to say it is offline.
 */
suspend fun appSearchRemoteItems(
    repository: FieldRepository,
    query: String,
    openRecord: (recordType: String, recordId: String) -> Unit,
    openRemote: (type: String, id: String) -> Unit = { _, _ -> }
): List<AppSearchItem> {
    val unified = try {
        repository.unifiedSearch(query, limit = APP_SEARCH_GROUP_CAP)
    } catch (e: HttpException) {
        if (e.code() == 404) null else throw e
    }
    if (unified != null) return unifiedSearchItems(unified, openRecord, openRemote)
    return appSearchLegacyItems(repository, query, openRecord)
}

/** The fan-out the panel used before `GET /search/unified`, kept for a server that answers it 404. */
private suspend fun appSearchLegacyItems(
    repository: FieldRepository,
    query: String,
    openRecord: (recordType: String, recordId: String) -> Unit
): List<AppSearchItem> = coroutineScope {
    val search = async { runCatching { repository.search(q = query, pageSize = 10) } }
    val crafts = async { runCatching { repository.searchCrafts(query) } }
    val processes = async { runCatching { repository.searchProcesses(query) } }
    val interviews = async { runCatching { repository.searchInterviews(query) } }
    val all = listOf(search.await(), crafts.await(), processes.await(), interviews.await())
    all.forEach { r -> (r.exceptionOrNull() as? CancellationException)?.let { throw it } }
    if (all.all { it.isFailure } && all.any { it.exceptionOrNull() is IOException }) {
        throw IOException("No search source could be reached")
    }

    val items = mutableListOf<AppSearchItem>()
    search.await().getOrNull()?.let { r ->
        r.artisans.forEach { a ->
            items += AppSearchItem(AppSearchGroup.ARTISANS, a.id, a.name, Icons.Filled.Person, subtitle = a.place.ifBlank { null }) {
                openRecord(SearchRecordTypes.ARTISAN, a.id)
            }
        }
        r.workshops.forEach { w ->
            items += AppSearchItem(AppSearchGroup.WORKSHOPS, w.id, w.title, Icons.Filled.Groups, subtitle = w.place.ifBlank { null }) {
                openRecord(SearchRecordTypes.WORKSHOP, w.id)
            }
        }
        r.products.forEach { p ->
            items += AppSearchItem(
                AppSearchGroup.PRODUCTS, p.id, p.productName, Icons.Filled.Inventory2,
                subtitle = listOf(p.artisanName, p.place).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
            ) { openRecord(SearchRecordTypes.PRODUCT, p.id) }
        }
        r.tools.forEach { t ->
            items += AppSearchItem(
                AppSearchGroup.TOOLS, t.id, t.toolkitName, Icons.Filled.Build,
                subtitle = listOf(t.artisanName, t.place).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
            ) { openRecord(SearchRecordTypes.TOOL, t.id) }
        }
        r.media.forEach { m ->
            items += AppSearchItem(
                AppSearchGroup.MEDIA, m.id, m.originalFilename, Icons.Filled.PermMedia,
                subtitle = m.caption?.ifBlank { null } ?: m.mediaType
            ) { openRecord(SearchRecordTypes.MEDIA, m.id) }
        }
    }
    crafts.await().getOrNull()?.forEach { c ->
        items += AppSearchItem(AppSearchGroup.CRAFTS, c.id, c.name, Icons.Filled.Brush, subtitle = c.localName ?: c.place) {
            openRecord(SearchRecordTypes.CRAFT, c.id)
        }
    }
    processes.await().getOrNull()?.forEach { p ->
        items += AppSearchItem(AppSearchGroup.PROCESSES, p.id, p.name, Icons.Filled.AccountTree, subtitle = p.product?.productName) {
            openRecord(SearchRecordTypes.PROCESS, p.id)
        }
    }
    interviews.await().getOrNull()?.forEach { i ->
        items += AppSearchItem(AppSearchGroup.INTERVIEWS, i.id, i.title.ifBlank { "Untitled interview" }, Icons.Filled.Quiz, subtitle = i.place) {
            openRecord(SearchRecordTypes.QUESTIONNAIRE, i.id)
        }
    }
    items
}

/** A search failure that is not "no network", in the words this app uses for API errors. */
fun appSearchErrorLine(error: Throwable): String =
    error.userMessage("Can't search online. Showing what's on this phone.")

/**
 * The Recordings group: questionnaire takes matching [query] on what their chips say (section,
 * form, artisans, workshop, transcript), each drawn with its chips; a tap opens the recordings list
 * searched for the same words. With no signal it reads this account's kept takes; a failure is an
 * empty group, never the whole panel's.
 */
suspend fun appSearchRecordingItems(
    context: android.content.Context,
    query: String,
    open: (com.fieldrepository.app.questionnaire.data.QuestionnaireFilters) -> Unit
): List<AppSearchItem> {
    val hits = try {
        com.fieldrepository.app.questionnaire.data.QuestionnaireV2.get(context).searchRecordings(query)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (other: Exception) {
        emptyList()
    }
    return recordingSearchItems(hits, open)
}

/** The Recordings group's rows for [hits]: titled by what each take answers, with its chips. */
fun recordingSearchItems(
    hits: List<com.fieldrepository.app.questionnaire.data.RecordingHit>,
    open: (com.fieldrepository.app.questionnaire.data.QuestionnaireFilters) -> Unit
): List<AppSearchItem> = hits.map { hit ->
    AppSearchItem(
        AppSearchGroup.RECORDINGS, hit.id, hit.title, Icons.Filled.PlayArrow,
        subtitle = hit.subtitle, chips = hit.chips
    ) { open(hit.filters) }
}
