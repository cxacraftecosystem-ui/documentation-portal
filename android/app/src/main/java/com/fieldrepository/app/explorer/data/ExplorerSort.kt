package com.fieldrepository.app.explorer.data

import android.content.Context
import kotlinx.serialization.Serializable
import retrofit2.HttpException

@Serializable
data class ExplorerSortApplied(val key: String = "", val dir: String = "") {
    fun toSort(): ExplorerSort? = ExplorerSort.of(key, dir)
}

data class ExplorerSort(val key: String, val dir: String) {
    val param: String get() = "$key:$dir"

    companion object {
        const val ASC = "asc"
        const val DESC = "desc"

        fun of(key: String?, dir: String?): ExplorerSort? {
            val k = key?.trim()?.lowercase().orEmpty()
            val d = dir?.trim()?.lowercase().orEmpty()
            if (k !in ExplorerSorts.KEYS || d !in ExplorerSorts.directions(k)) return null
            return ExplorerSort(k, d)
        }

        fun parse(raw: String?): ExplorerSort? {
            val text = raw?.trim().orEmpty()
            val at = text.indexOf(':')
            if (at <= 0 || at == text.lastIndex) return null
            return of(text.substring(0, at), text.substring(at + 1))
        }
    }
}

object ExplorerSorts {
    val KEYS = listOf("relevance", "name", "recorded", "added", "modified", "size", "duration", "type", "workshop", "artisan", "craft", "status", "folder")

    val TREE = ExplorerSort("name", ExplorerSort.ASC)
    val TRANSCRIPTS = ExplorerSort("folder", ExplorerSort.ASC)
    val BROWSE = ExplorerSort("added", ExplorerSort.DESC)
    val BROWSE_MEDIA = ExplorerSort("added", ExplorerSort.DESC)
    val SEARCH = ExplorerSort("relevance", ExplorerSort.DESC)

    const val TITLE = "Sort by"
    const val DONE = "Done"
    const val ORDER = "Order"

    private val LABELS = mapOf(
        "relevance" to "Best match",
        "name" to "Name",
        "recorded" to "Date recorded",
        "added" to "Date added",
        "modified" to "Last modified",
        "size" to "Size",
        "duration" to "Duration",
        "type" to "Type",
        "workshop" to "Workshop",
        "artisan" to "Artisan",
        "craft" to "Craft",
        "status" to "Review status",
        "folder" to "Folder",
    )

    private val DATES = setOf("recorded", "added", "modified")

    fun label(key: String): String = LABELS[key] ?: key

    fun defaultDir(key: String): String = when (key) {
        "relevance", "recorded", "added", "modified", "size", "duration" -> ExplorerSort.DESC
        else -> ExplorerSort.ASC
    }

    fun directions(key: String): List<String> = when (key) {
        "relevance" -> listOf(ExplorerSort.DESC)
        else -> if (defaultDir(key) == ExplorerSort.DESC) listOf(ExplorerSort.DESC, ExplorerSort.ASC) else listOf(ExplorerSort.ASC, ExplorerSort.DESC)
    }

    fun word(key: String, dir: String): String {
        val asc = dir == ExplorerSort.ASC
        return when {
            key == "relevance" -> "Best first"
            key in DATES -> if (asc) "Oldest" else "Newest"
            key == "size" -> if (asc) "Smallest" else "Largest"
            key == "duration" -> if (asc) "Shortest" else "Longest"
            else -> if (asc) "A–Z" else "Z–A"
        }
    }

    fun short(sort: ExplorerSort): String = "${label(sort.key)} · ${word(sort.key, sort.dir)}"

    fun spoken(sort: ExplorerSort): String = "Sort: ${label(sort.key)}, ${word(sort.key, sort.dir)}"

    fun pick(key: String): ExplorerSort = ExplorerSort(key, defaultDir(key))

    fun offered(keys: List<String>): List<String> = keys.map { it.trim().lowercase() }.filter { it in KEYS }.distinct()

    fun resolve(remembered: ExplorerSort?, keys: List<String>, fallback: ExplorerSort): ExplorerSort {
        val offered = offered(keys)
        if (remembered != null && remembered.key in offered) return remembered
        return fallback
    }

    fun param(choice: ExplorerSort?, fallback: ExplorerSort): String? = choice?.takeIf { it != fallback }?.param

    fun depth(path: String): Int = path.split('/').count { it.isNotEmpty() }

    fun treeLevel(path: String): String {
        val first = path.split('/').firstOrNull { it.isNotEmpty() } ?: "root"
        return "tree:$first:${depth(path)}"
    }

    const val TRANSCRIPTS_LEVEL = "transcripts"

    fun browseLevel(type: String): String = "browse:$type"

    fun browseMediaLevel(type: String): String = "browse-media:$type"

    fun searchLevel(type: String?): String = "search:${type?.takeIf { it.isNotBlank() } ?: "all"}"

    suspend fun <T> fetch(wanted: ExplorerSort?, fallback: ExplorerSort, keysOf: (T) -> List<String>, load: suspend (sort: String?) -> T): Sorted<T> {
        val sent = param(wanted, fallback) ?: return Sorted(load(null), null)
        val first = try {
            load(sent)
        } catch (refused: HttpException) {
            if (refused.code() != 422) throw refused
            return Sorted(load(null), null)
        }
        val keys = offered(keysOf(first))
        if (keys.isNotEmpty() && wanted!!.key !in keys) return Sorted(load(null), null)
        return Sorted(first, sent)
    }

    data class Sorted<T>(val value: T, val sent: String?)
}

interface ExplorerSortStore {
    fun read(level: String): ExplorerSort?
    fun write(level: String, sort: ExplorerSort)
}

class ExplorerSortMap(initial: Map<String, ExplorerSort> = emptyMap()) : ExplorerSortStore {
    val saved = HashMap(initial)

    override fun read(level: String): ExplorerSort? = saved[level]

    override fun write(level: String, sort: ExplorerSort) {
        saved[level] = sort
    }
}

class ExplorerSortMemory(context: Context, private val viewer: String?) : ExplorerSortStore {
    private val app = context.applicationContext

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(level: String): ExplorerSort? = runCatching { ExplorerSort.parse(prefs().getString(key(viewer, level), null)) }.getOrNull()

    override fun write(level: String, sort: ExplorerSort) {
        runCatching { prefs().edit().putString(key(viewer, level), sort.param).apply() }
    }

    companion object {
        const val PREFS = "explorer-sort"

        fun key(viewer: String?, level: String): String = "dataExplorer.sort.${viewer?.trim()?.takeIf { it.isNotEmpty() } ?: "guest"}.$level"

        fun clear(context: Context) {
            runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit() }
        }
    }
}
