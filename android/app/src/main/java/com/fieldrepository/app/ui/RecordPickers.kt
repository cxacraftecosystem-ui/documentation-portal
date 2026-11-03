package com.fieldrepository.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fieldrepository.app.data.ArtisanCardCode
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.PageResponse
import kotlinx.coroutines.CancellationException

fun craftChangeClearsArtisan(
    nextCraftId: String,
    artisanId: String,
    artisans: List<ArtisanDto>
): Boolean {
    if (nextCraftId.isBlank() || artisanId.isBlank()) return false
    val known = artisans.firstOrNull { it.id == artisanId } ?: return false
    return known.craftId != nextCraftId
}

fun craftsChangeClearsArtisans(
    nextCraftIds: List<String>,
    removedCraftIds: List<String>,
    artisanIds: List<String>,
    artisans: List<ArtisanDto>
): List<String> = artisanIds.filter { id ->
    if (id.isBlank()) return@filter false
    val known = artisans.firstOrNull { it.id == id } ?: return@filter false
    val craftId = known.craftId
    if (craftId.isNullOrBlank()) return@filter false
    craftId in removedCraftIds && craftId !in nextCraftIds
}

fun craftNameFor(artisan: ArtisanDto, selectedCrafts: List<CraftDto>): String {
    val hydrated = artisan.craft?.name.orEmpty()
    if (hydrated.isNotBlank()) return hydrated
    val id = artisan.craftId
    if (id.isNullOrBlank()) return ""
    return selectedCrafts.firstOrNull { it.id == id }?.name.orEmpty()
}

private fun sortFold(value: String): String = value.trim().lowercase()

fun artisansByCraftThenName(
    artisans: List<ArtisanDto>,
    selectedCrafts: List<CraftDto>
): List<ArtisanDto> {
    val craftKeys = artisans.associate { it.id to sortFold(craftNameFor(it, selectedCrafts)) }
    return artisans.sortedWith(
        compareBy(
            { a: ArtisanDto -> if (craftKeys[a.id].isNullOrEmpty()) 1 else 0 },
            { a: ArtisanDto -> craftKeys[a.id].orEmpty() },
            { a: ArtisanDto -> sortFold(a.name) },
            { a: ArtisanDto -> a.name },
            { a: ArtisanDto -> a.id },
        )
    )
}

enum class ListCutReach {
    NONE,

    PAGER,

    SEARCH
}

fun listCutNotice(
    loaded: Int,
    total: Int,
    noun: String,
    reach: ListCutReach = ListCutReach.NONE
): String? {
    if (total <= loaded) return null
    if (loaded == 0) {
        return "None of the $total $noun could be loaded. Try again."
    }
    return when (reach) {
        ListCutReach.PAGER ->
            "Showing $loaded of $total $noun — use the pager to reach the rest, which are not " +
                "searched by the box above."
        ListCutReach.SEARCH -> "Showing $loaded of $total $noun — type to search all $total."
        ListCutReach.NONE ->
            "Showing $loaded of $total $noun. To find another, use Browse records from the menu."
    }
}

fun artisanScopeNoun(workshopId: String): String = if (workshopId.isNotBlank()) "artisans at this workshop" else "artisans"

fun artisansNotAtWorkshop(
    selectedIds: Collection<String>,
    offeredIds: Collection<String>,
    loadedForWorkshop: String?,
    workshopId: String,
    cut: String?
): List<String> {
    if (loadedForWorkshop != workshopId) return emptyList()
    if (cut != null) return emptyList()
    val offered = offeredIds.toHashSet()
    return selectedIds.filter { it.isNotBlank() && !offered.contains(it) }
}

const val OUT_OF_WORKSHOP_NAMES_SHOWN = 4

fun outOfWorkshopNotice(names: List<String>): String? {
    val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
    if (clean.isEmpty()) return null
    val shown = clean.take(OUT_OF_WORKSHOP_NAMES_SHOWN)
    val remainder = clean.size - shown.size
    val list = shown.joinToString(", ") + if (remainder > 0) " and $remainder more" else ""
    val subject = if (clean.size == 1) "$list is" else "$list are"
    return "$subject not yet linked to this workshop. Saving links them \u2014 untick anyone who shouldn't be here."
}

fun mergeArtisansById(previous: List<ArtisanDto>, incoming: List<ArtisanDto>): List<ArtisanDto> {
    if (incoming.isEmpty()) return previous
    val seen = previous.mapTo(HashSet()) { it.id }
    val added = incoming.filterNot { seen.contains(it.id) }
    return if (added.isEmpty()) previous else previous + added
}

@Composable
fun rememberCraftOptions(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    craftIds: List<String>,
    hydrated: List<CraftDto> = emptyList()
): List<CraftDto> {
    var offPage by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    val attempted = remember { mutableSetOf<String>() }
    val selected = craftIds.filter { it.isNotBlank() }.distinct()
    val embedded = hydrated
        .filter { craft -> craft.id in selected && crafts.none { it.id == craft.id } }
        .distinctBy { it.id }
    val missing = selected.filterNot { id ->
        crafts.any { it.id == id } || embedded.any { it.id == id }
    }

    LaunchedEffect(missing.joinToString(",")) {
        for (id in missing) {
            if (id in attempted) continue
            val fetched = try {
                repository.craft(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            attempted.add(id)
            if (fetched != null) offPage = offPage + fetched
        }
    }

    val rescued = offPage.filter { it.id in missing }
    return if (embedded.isEmpty() && rescued.isEmpty()) crafts else crafts + embedded + rescued
}

@Composable
fun rememberCraftOptions(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    craftId: String
): List<CraftDto> = rememberCraftOptions(repository, crafts, listOfNotNull(craftId.ifBlank { null }))

data class ArtisanPickerState(
    val options: List<ArtisanDto>,
    val search: ServerSearchState<ArtisanDto>,
    val loadedForCraft: String?
) {
    fun isLoadedFor(craftIds: List<String>): Boolean =
        loadedForCraft != null && loadedForCraft == craftScopeKey(craftIds)
}

private fun craftScopeKey(craftIds: List<String>): String =
    craftIds.filter { it.isNotBlank() }.distinct().sorted().joinToString(",")

fun artisanSearchText(artisan: ArtisanDto): String =
    listOfNotNull(artisan.name, artisan.place, artisan.craft?.name).joinToString(" ")

fun artisanSelectOption(artisan: ArtisanDto): SelectOption =
    SelectOption(artisan.id, "${artisan.name} · ${artisan.place}")

fun artisanFirstOption(artisan: ArtisanDto): SelectOption =
    SelectOption(
        artisan.id,
        listOf(artisan.name, artisan.craft?.name.orEmpty(), artisan.place)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    )

const val ARTISAN_PICK_SENTENCE = "Pick the artisan, or turn on Artisan not on record."

@Composable
fun rememberArtisanPicker(
    repository: FieldRepository,
    artisans: List<ArtisanDto>,
    craftIds: List<String>,
    artisanIds: List<String>,
    hydrated: List<ArtisanDto> = emptyList(),
    seed: PageResponse<ArtisanDto>? = null
): ArtisanPickerState {
    var offPage by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    val attempted = remember { mutableSetOf<String>() }

    val scopeKey = craftScopeKey(craftIds)
    val wanted = craftIds.filter { it.isNotBlank() }.distinct()
    val search = rememberServerSearch(
        key = scopeKey,
        cached = if (wanted.isEmpty()) artisans else artisans.filter { it.craftId in wanted },
        seed = if (wanted.isEmpty()) seed else null,
        idOf = { it.id },
        textOf = ::artisanSearchText
    ) { term, page ->
        if (wanted.isEmpty()) repository.artisansSearchPage(term, page)
        else repository.artisansForCraftsPage(wanted, term, page)
    }
    val roster = if (scopeKey.isNotEmpty() && search.browseKey == scopeKey) search.browse else emptyList()
    val loadedForCraft = if (scopeKey.isNotEmpty() && search.browseLoaded && search.browseKey == scopeKey) scopeKey else null

    val wantedArtisans = artisanIds.filter { it.isNotBlank() && !ArtisanCardCode.isPending(it) }.distinct()
    val embedded = hydrated.filter { it.id in wantedArtisans }
    val rescued = offPage.filter { it.id in wantedArtisans }
    val known = mergeArtisansById(
        mergeArtisansById(
            mergeArtisansById(mergeArtisansById(artisans, roster), embedded),
            rescued
        ),
        search.seen
    )

    val missing = wantedArtisans.filterNot { id -> known.any { it.id == id } }
    LaunchedEffect(missing.joinToString(",")) {
        for (id in missing) {
            if (id in attempted) continue
            val detail = try {
                repository.artisan(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            attempted.add(id)
            if (detail != null) {
                offPage = offPage + ArtisanDto(
                    id = detail.id,
                    name = detail.name,
                    place = detail.place,
                    status = detail.status,
                    craftId = detail.craftId,
                    craft = detail.craft,
                    location = detail.location,
                    createdById = detail.createdById,
                    createdAt = detail.createdAt
                )
            }
        }
    }

    return ArtisanPickerState(options = known, search = search, loadedForCraft = loadedForCraft)
}

@Composable
fun rememberArtisanPicker(
    repository: FieldRepository,
    artisans: List<ArtisanDto>,
    craftId: String,
    artisanId: String,
    seed: PageResponse<ArtisanDto>? = null
): ArtisanPickerState = rememberArtisanPicker(
    repository = repository,
    artisans = artisans,
    craftIds = listOfNotNull(craftId.ifBlank { null }),
    artisanIds = listOfNotNull(artisanId.ifBlank { null }),
    seed = seed
)

data class CraftPickerState(val options: List<CraftDto>, val search: ServerSearchState<CraftDto>)

fun craftSearchText(craft: CraftDto): String =
    listOfNotNull(craft.name, craft.place).joinToString(" ")

@Composable
fun rememberCraftPicker(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    craftIds: List<String>,
    hydrated: List<CraftDto> = emptyList(),
    seed: PageResponse<CraftDto>? = null
): CraftPickerState {
    val search = rememberServerSearch(
        key = Unit,
        cached = crafts,
        seed = seed,
        idOf = { it.id },
        textOf = ::craftSearchText
    ) { term, page -> repository.craftsSearchPage(term, page) }
    val fromSearch = search.seen.filter { craft -> crafts.none { it.id == craft.id } }
    val base = if (fromSearch.isEmpty()) crafts else crafts + fromSearch
    return CraftPickerState(rememberCraftOptions(repository, base, craftIds, hydrated), search)
}

@Composable
fun rememberCraftPicker(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    craftId: String,
    seed: PageResponse<CraftDto>? = null
): CraftPickerState =
    rememberCraftPicker(repository, crafts, listOfNotNull(craftId.ifBlank { null }), seed = seed)

fun carryScopeStateFor(vararg pages: PageResponse<*>?): CarryScopeState = when {
    pages.any { it == null } -> CarryScopeState.UNAVAILABLE
    pages.all { it!!.items.size >= it.total } -> CarryScopeState.LOADED
    else -> CarryScopeState.UNAVAILABLE
}
