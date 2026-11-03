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

const val APP_SEARCH_NAME = "Field Repository"

enum class AppSearchSettingTarget { APPEARANCE, AI_KEYS, TWO_STEP }

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

private class UnifiedKind(val group: AppSearchGroup, val icon: ImageVector, val recordType: String?)

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

fun appSearchErrorLine(error: Throwable): String =
    error.userMessage("Can't search online. Showing what's on this phone.")

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

fun recordingSearchItems(
    hits: List<com.fieldrepository.app.questionnaire.data.RecordingHit>,
    open: (com.fieldrepository.app.questionnaire.data.QuestionnaireFilters) -> Unit
): List<AppSearchItem> = hits.map { hit ->
    AppSearchItem(
        AppSearchGroup.RECORDINGS, hit.id, hit.title, Icons.Filled.PlayArrow,
        subtitle = hit.subtitle, chips = hit.chips
    ) { open(hit.filters) }
}
