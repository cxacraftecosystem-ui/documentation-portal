package com.fieldrepository.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Normalizer

/**
 * The craft list every picker reads — `GET /crafts/catalogue` (REQUIREMENTS_2026-10-20 §2.3-§2.4).
 *
 * Identical in designer-portal and documentation-portal apart from the package line; a parity test
 * in each repo pins it. It imports nothing from either app, so the two copies cannot drift through a
 * dependency. Each app hands [refresh] a fetch that knows its own API.
 *
 * - Kept in `filesDir/craft_catalogue.json` with the server's ETag; a 304 costs no body.
 * - Refreshed at sign-in, on app start when older than 24 h, and when a picker opens online.
 * - Works fully offline from the file. A first run offline with no file has no list, and the picker
 *   says [OFFLINE_FIRST_RUN].
 * - The search rules (fold, rank, group) are the web's `lib/craftCatalogue.ts`, rule for rule.
 */
object CraftCatalogueCache {
    const val FILE_NAME = "craft_catalogue.json"
    const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

    /** The 28 states and 8 union territories, as the master list spells them, then "All India". */
    val STATE_OPTIONS: List<String> = listOf(
        "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa", "Gujarat", "Haryana",
        "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur",
        "Meghalaya", "Mizoram", "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana",
        "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal", "Andaman and Nicobar Islands", "Chandigarh",
        "Dadra and Nagar Haveli and Daman and Diu", "Delhi", "Jammu and Kashmir", "Ladakh", "Lakshadweep",
        "Puducherry", "All India",
    )

    const val OFFLINE_FIRST_RUN = "Connect once to load the craft list."
    const val NOT_IN_LIST = "Pick a craft from the list."
    fun noMatch(query: String) = "No craft matches “$query”."

    /** What one request answered: the stored copy still holds, or a new body with its tag. */
    sealed interface Fetch {
        data object NotModified : Fetch
        data class Fresh(val body: String, val etag: String?) : Fetch
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val lock = Mutex()
    private val state = MutableStateFlow<CraftCatalogue?>(null)

    /** The catalogue on hand, for a composable to collect. Null until something has been read. */
    val catalogue: StateFlow<CraftCatalogue?> = state.asStateFlow()

    @Volatile
    private var stored: StoredCatalogue? = null

    /** Whether the last [refresh] reached the server (an answer, 200 or 304) rather than the file. */
    @Volatile
    var lastRefreshReachedServer: Boolean = false
        private set

    /** Epoch millis the device's copy was last confirmed by the server; null without a copy. */
    fun fetchedAt(): Long? = stored?.fetchedAt

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** The catalogue already on this device, without a request. Loads the file once. */
    fun cached(context: Context): CraftCatalogue? = cached(file(context))

    /** [cached] against an explicit file — the JVM tests' door (this module has no Robolectric). */
    fun cached(target: File): CraftCatalogue? {
        stored?.let { return it.catalogue }
        val read = runCatching { json.decodeFromString(StoredCatalogue.serializer(), target.readText()) }.getOrNull()
        if (read != null) {
            stored = read
            state.value = read.catalogue
        }
        return read?.catalogue
    }

    /** True when the device's copy is missing or older than [MAX_AGE_MILLIS]. */
    fun isStale(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        cached(context)
        val at = stored?.fetchedAt ?: return true
        return now - at > MAX_AGE_MILLIS
    }

    /**
     * Revalidate against the server. Returns the newest catalogue available — the fresh one, or the
     * stored one when the server said 304 or could not be reached. Throws only when there is no
     * stored copy at all and the fetch failed.
     */
    suspend fun refresh(context: Context, fetch: suspend (etag: String?) -> Fetch): CraftCatalogue? = refresh(file(context), fetch)

    /** [refresh] against an explicit file — the JVM tests' door. */
    suspend fun refresh(target: File, fetch: suspend (etag: String?) -> Fetch): CraftCatalogue? = lock.withLock {
        withContext(Dispatchers.IO) {
            val current = stored ?: cached(target).let { stored }
            val answer = runCatching { fetch(current?.etag) }
            val result = answer.getOrNull()
            lastRefreshReachedServer = result != null
            when (result) {
                is Fetch.Fresh -> {
                    val parsed = parse(result.body) ?: return@withContext current?.catalogue
                    val next = StoredCatalogue(etag = result.etag, fetchedAt = System.currentTimeMillis(), catalogue = parsed)
                    write(target, next)
                    parsed
                }
                Fetch.NotModified -> {
                    if (current != null) write(target, current.copy(fetchedAt = System.currentTimeMillis()))
                    current?.catalogue
                }
                null -> {
                    if (current == null) throw answer.exceptionOrNull() ?: IllegalStateException(OFFLINE_FIRST_RUN)
                    current.catalogue
                }
            }
        }
    }

    /** After a craft write: the next refresh goes to the server whatever the age. */
    fun invalidate() {
        stored = stored?.copy(fetchedAt = 0L)
    }

    /** Drop what is held in memory (not the file) — sign-out, and the tests. */
    fun forget() {
        stored = null
        state.value = null
        lastRefreshReachedServer = false
    }

    /** A body read defensively: a missing array is empty, never a crash. Null when unreadable. */
    fun parse(body: String): CraftCatalogue? = runCatching { json.decodeFromString(CraftCatalogue.serializer(), body) }.getOrNull()

    private fun write(target: File, next: StoredCatalogue) {
        stored = next
        state.value = next.catalogue
        runCatching {
            val temp = File(target.parentFile, "$FILE_NAME.tmp")
            temp.writeText(json.encodeToString(StoredCatalogue.serializer(), next))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Pure rules — the web's `lib/craftCatalogue.ts`, rule for rule
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** Case- and accent-folded, `&` read as "and", punctuation and hyphens as spaces, runs collapsed. */
    fun fold(text: String?): String {
        if (text == null) return ""
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        return decomposed.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /** 0 exact, 1 prefix, 2 word prefix, 3 substring, 4 no match — the web's `appSearchScore`. */
    fun score(title: String, query: String): Int {
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

    fun categoryName(catalogue: CraftCatalogue, craft: CatalogueCraft): String {
        val index = index(catalogue)
        val categoryId = craft.categoryId ?: craft.subCategoryId?.let { index.subCategoryParents[it] }
        return categoryId?.let { index.categoryNames[it] }.orEmpty()
    }

    fun subCategoryName(catalogue: CraftCatalogue, craft: CatalogueCraft): String =
        craft.subCategoryId?.let { index(catalogue).subCategoryNames[it] }.orEmpty()

    /**
     * One craft's searchable text, folded ONCE PER CATALOGUE rather than once per keystroke. A picker
     * search used to re-run [fold] (a Unicode normalisation and three regexes) on every name, alias,
     * state and category of ~455 crafts for each letter typed, on the main thread of a field handset.
     * Each string keeps its words, split once, for [score]'s word-prefix rule.
     */
    private class FoldedText(val text: String) {
        val words: List<String> = text.split(' ').filter { it.isNotEmpty() }
    }

    private class FoldedCraft(
        val name: FoldedText,
        val second: List<FoldedText>,
        val third: List<FoldedText>,
        val haystack: String,
    )

    /** The category tree as maps, and every craft folded, for one catalogue (by identity). */
    private class SearchIndex(
        val catalogue: CraftCatalogue,
        val categoryNames: Map<String, String>,
        val subCategoryNames: Map<String, String>,
        val subCategoryParents: Map<String, String>,
    ) {
        @Volatile
        var crafts: List<FoldedCraft>? = null
    }

    @Volatile
    private var searchIndex: SearchIndex? = null

    private fun index(catalogue: CraftCatalogue): SearchIndex {
        searchIndex?.takeIf { it.catalogue === catalogue }?.let { return it }
        // First match wins, as the list scans these maps replace did.
        val categoryNames = HashMap<String, String>()
        val subCategoryNames = HashMap<String, String>()
        val subCategoryParents = HashMap<String, String>()
        for (category in catalogue.categories) {
            categoryNames.putIfAbsent(category.id, category.name)
            for (sub in category.subCategories) {
                subCategoryNames.putIfAbsent(sub.id, sub.name)
                subCategoryParents.putIfAbsent(sub.id, category.id)
            }
        }
        return SearchIndex(catalogue, categoryNames, subCategoryNames, subCategoryParents).also { searchIndex = it }
    }

    private fun foldCraft(catalogue: CraftCatalogue, craft: CatalogueCraft): FoldedCraft {
        val sub = subCategoryName(catalogue, craft)
        val category = categoryName(catalogue, craft)
        return FoldedCraft(
            name = FoldedText(fold(craft.name)),
            second = (listOf(craft.localName, craft.slug) + craft.aliases).map { FoldedText(fold(it)) },
            third = (craft.stateOfPractice + craft.stateOfOrigin + listOf(sub, category)).map { FoldedText(fold(it)) },
            haystack = fold(
                (listOf(craft.name, craft.localName.orEmpty(), craft.slug) + craft.aliases + craft.stateOfPractice + craft.stateOfOrigin +
                    listOf(sub, category)).joinToString(" ")
            ),
        )
    }

    private fun foldedCrafts(catalogue: CraftCatalogue): List<FoldedCraft> {
        val index = index(catalogue)
        index.crafts?.let { return it }
        return catalogue.crafts.map { foldCraft(catalogue, it) }.also { index.crafts = it }
    }

    /** [score] on text already folded: the same four rules, with the words split once. */
    private fun scoreFolded(title: FoldedText, q: String): Int {
        val t = title.text
        return when {
            t == q -> 0
            t.startsWith(q) -> 1
            title.words.any { it.startsWith(q) } -> 2
            t.contains(q) -> 3
            else -> 4
        }
    }

    /** [craftScore] against a folded craft and a folded, non-empty query. */
    private fun scoreCraft(craft: FoldedCraft, q: String): Int? {
        val name = scoreFolded(craft.name, q)
        if (name < 4) return name
        val second = craft.second.minOfOrNull { scoreFolded(it, q) } ?: 4
        if (second < 4) return 10 + second
        val third = craft.third.minOfOrNull { scoreFolded(it, q) } ?: 4
        if (third < 4) return 20 + third
        val words = q.split(" ")
        if (words.size > 1 && words.all { craft.haystack.contains(it) }) return 30
        return null
    }

    /** "Category › Sub-category" — the heading a craft is listed under. */
    fun path(catalogue: CraftCatalogue, craft: CatalogueCraft): String {
        val category = categoryName(catalogue, craft)
        val sub = subCategoryName(catalogue, craft)
        return when {
            category.isNotEmpty() && sub.isNotEmpty() -> "$category › $sub"
            category.isNotEmpty() -> category
            sub.isNotEmpty() -> sub
            else -> "Not on the list"
        }
    }

    /** How well [craft] answers [query]; lower is better, null is no match. See the web twin. */
    fun craftScore(catalogue: CraftCatalogue, craft: CatalogueCraft, query: String): Int? {
        val q = fold(query)
        if (q.isEmpty()) return 0
        return scoreCraft(foldCraft(catalogue, craft), q)
    }

    /**
     * The crafts that answer [query], best first; catalogue order breaks ties. Empty query = all.
     * The query is folded once and each craft's text was folded once for this catalogue.
     */
    fun search(catalogue: CraftCatalogue, query: String): List<CatalogueCraft> {
        val q = fold(query)
        if (q.isEmpty()) return catalogue.crafts
        val folded = foldedCrafts(catalogue)
        return catalogue.crafts.withIndex()
            .mapNotNull { (at, craft) -> scoreCraft(folded[at], q)?.let { Triple(craft, it, at) } }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }
    }

    data class Group(val key: String, val heading: String, val crafts: List<CatalogueCraft>)

    /** [crafts] under their "Category › Sub-category" headings, each group where its first member is. */
    fun group(catalogue: CraftCatalogue, crafts: List<CatalogueCraft>): List<Group> {
        val groups = LinkedHashMap<String, MutableList<CatalogueCraft>>()
        val headings = HashMap<String, String>()
        for (craft in crafts) {
            val key = craft.subCategoryId ?: craft.categoryId ?: "none"
            groups.getOrPut(key) { mutableListOf() }.add(craft)
            headings.getOrPut(key) { path(catalogue, craft) }
        }
        return groups.map { (key, rows) -> Group(key, headings[key].orEmpty(), rows) }
    }

    /** The one craft whose name, slug or alias folds to [text]; two or more is no answer. */
    fun findByText(catalogue: CraftCatalogue, text: String?): CatalogueCraft? {
        val q = fold(text)
        if (q.isEmpty()) return null
        val exact = catalogue.crafts.filter { fold(it.name) == q || fold(it.slug) == q }
        if (exact.size == 1) return exact.first()
        if (exact.size > 1) return null
        val alias = catalogue.crafts.filter { craft -> craft.aliases.any { fold(it) == q } }
        return alias.singleOrNull()
    }
}

@Serializable
data class CraftCatalogue(
    val version: String = "",
    val categories: List<CraftCatalogueCategory> = emptyList(),
    val crafts: List<CatalogueCraft> = emptyList(),
) {
    fun craft(id: String?): CatalogueCraft? = if (id.isNullOrBlank()) null else crafts.firstOrNull { it.id == id }

    fun subCategoryParent(subCategoryId: String?): String? =
        categories.firstOrNull { category -> category.subCategories.any { it.id == subCategoryId } }?.id
}

@Serializable
data class CraftCatalogueCategory(
    val id: String,
    val slug: String = "",
    val name: String,
    val sortOrder: Int = 0,
    val subCategories: List<CraftCatalogueSubCategory> = emptyList(),
)

@Serializable
data class CraftCatalogueSubCategory(
    val id: String,
    val slug: String = "",
    val name: String,
    val sortOrder: Int = 0,
    val craftCount: Int = 0,
)

@Serializable
data class CatalogueCraft(
    val id: String,
    val slug: String = "",
    val name: String,
    val localName: String? = null,
    val categoryId: String? = null,
    val subCategoryId: String? = null,
    val sourceSerial: Int? = null,
    val stateOfOrigin: List<String> = emptyList(),
    val stateOfPractice: List<String> = emptyList(),
    val giStatus: String? = null,
    val isCatchAll: Boolean = false,
    val craftCode: String? = null,
    val aliases: List<String> = emptyList(),
    val status: String? = null,
    val mergedIntoId: String? = null,
    val referenceCount: Int? = null,
) {
    val isGi: Boolean get() = giStatus == "GI"
}

@Serializable
private data class StoredCatalogue(
    val etag: String? = null,
    val fetchedAt: Long = 0L,
    val catalogue: CraftCatalogue,
)
