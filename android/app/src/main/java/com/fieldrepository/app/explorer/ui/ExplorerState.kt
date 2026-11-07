package com.fieldrepository.app.explorer.ui

import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.data.ExplorerBusy
import com.fieldrepository.app.explorer.data.ExplorerCache
import com.fieldrepository.app.explorer.data.ExplorerCrumb
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerInfo
import com.fieldrepository.app.explorer.data.ExplorerJson
import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.explorer.data.ExplorerSelectionBody
import com.fieldrepository.app.explorer.data.ExplorerStatsBody
import com.fieldrepository.app.explorer.data.ExplorerTaxonomy
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.explorer.data.ExplorerTranscript
import com.fieldrepository.app.explorer.data.ExplorerTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed interface TranscriptLoad {
    data object Loading : TranscriptLoad
    data class Loaded(val text: String) : TranscriptLoad
    data object None : TranscriptLoad
    data object Failed : TranscriptLoad
}

enum class ExplorerTab { FILES, TABLES, TRANSCRIPTS }

data class ExplorerResume(val path: String, val tab: ExplorerTab, val status: String?) {
    companion object {
        private const val NONE = "\u0000"

        val saver: Saver<ExplorerResume?, Any> = Saver(
            save = { r -> r?.let { arrayListOf(it.path, it.tab.name, it.status ?: NONE) } },
            restore = { saved ->
                (saved as? List<*>)?.let { parts ->
                    val tab = runCatching { ExplorerTab.valueOf(parts[1] as String) }.getOrDefault(ExplorerTab.FILES)
                    ExplorerResume(parts[0] as String, tab, (parts[2] as String).takeIf { it != NONE })
                }
            },
        )
    }
}

private class Level(val tree: ExplorerTree, val entries: List<ExplorerEntry>, val cursor: String?, val cachedAt: Long?)

@Stable
class ExplorerState(
    internal val api: ExplorerApi,
    private val scope: CoroutineScope,
    private val cache: ExplorerCache?,
    private val rootName: String,
    defaultStatus: String?,
    private val statusParamOf: (String?) -> String?,
    private val online: () -> Boolean = { true },
    private val onPlace: (String) -> Unit = {},
    private val fallbackMs: Long = FALLBACK_MS,
) : RememberObserver {
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + lifetime)

    val closed: Boolean get() = lifetime.isCancelled

    var path by mutableStateOf("")
        private set
    var tree by mutableStateOf<ExplorerTree?>(null)
        private set
    var entries by mutableStateOf<List<ExplorerEntry>>(emptyList())
        private set
    var cursor by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var cachedNote by mutableStateOf<String?>(null)
        private set
    var restricted by mutableStateOf(false)
        private set
    var status by mutableStateOf(defaultStatus)
        private set
    var locating by mutableStateOf<String?>(null)
        private set
    var unfiled by mutableStateOf<String?>(null)
        private set
    var recordToOpen by mutableStateOf<ExplorerRecordRef?>(null)
        private set
    var selecting by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Map<String, ExplorerEntry>>(emptyMap())
        private set
    var openTranscripts by mutableStateOf<Set<String>>(emptySet())
        private set
    var openText by mutableStateOf<Set<String>>(emptySet())
        private set
    var showInfo by mutableStateOf(false)
    var tab by mutableStateOf(ExplorerTab.FILES)
    var cachedAt by mutableStateOf<Long?>(null)
        private set
    var loadingTarget: String? = null
        private set
    var loads = 0
        private set

    val transcripts = mutableStateMapOf<String, TranscriptLoad>()

    private val levels = HashMap<String, Level>()
    private val history = ArrayDeque<String>()
    private val visited = HashSet<String>()
    private var loadJob: Job? = null
    private var locateJob: Job? = null
    private var landed = false
    var started = false
        private set

    val statusParam: String? get() = statusParamOf(status)

    val taxonomies: List<ExplorerTaxonomy>
        get() = tree?.taxonomies?.takeIf { it.isNotEmpty() } ?: levels[key("")]?.tree?.taxonomies.orEmpty()

    val activeTaxonomy: ExplorerTaxonomy?
        get() = tree?.taxonomy?.let { id -> taxonomies.firstOrNull { it.id == id } }

    val crumbs: List<ExplorerCrumb>
        get() {
            val served = tree?.crumbs.orEmpty()
            return if (served.any { it.path.isEmpty() }) served else listOf(ExplorerCrumb(rootName, "")) + served
        }

    val info: ExplorerInfo? get() = tree?.info

    val folders: List<ExplorerEntry> get() = entries.filter { it.isFolder }

    val files: List<ExplorerEntry> get() = entries.filterNot { it.isFolder }


    val total: Int? get() = tree?.total

    val folderName: String get() = crumbs.lastOrNull()?.name?.takeIf { it.isNotBlank() } ?: rootName

    val atTop: Boolean get() = path.isEmpty() || taxonomies.any { it.path == path }

    val canGoUp: Boolean get() = history.any { it != path } || (!atTop && crumbs.size >= 2)

    private fun topPath(): String? =
        (activeTaxonomy ?: taxonomies.firstOrNull { it.isDefault } ?: taxonomies.firstOrNull())?.path?.takeIf { it.isNotEmpty() }

    fun close() {
        lifetime.cancel()
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = close()

    override fun onAbandoned() = close()

    private fun key(target: String) = ExplorerCache.key(target, statusParam)

    fun dismissError() {
        error = null
    }

    fun start(saved: String?, resume: ExplorerResume? = null) {
        if (started) return
        started = true
        if (resume != null) {
            status = resume.status
            landed = resume.path.isNotEmpty()
            open(resume.path)
            tab = resume.tab
            return
        }
        if (!saved.isNullOrBlank()) {
            landed = true
            open(saved)
        } else {
            open("")
        }
    }

    fun changeStatus(next: String?) {
        if (next == status) return
        status = next
        transcripts.clear()
        openTranscripts = emptySet()
        open(path)
    }

    fun open(requested: String) = open(requested, remember = true)

    private fun open(requested: String, remember: Boolean) {
        if (closed) return
        val target = if (requested.isEmpty()) topPath() ?: requested else requested
        if (target == path && loadingTarget == target && loadJob?.isActive == true) return
        if (target != path) {
            openTranscripts = emptySet()
            openText = emptySet()
            showInfo = false
            tab = ExplorerTab.FILES
        }
        error = null
        unfiled = null
        restricted = false
        loadJob?.cancel()
        val moved = target != path
        if (moved && remember && path.isNotEmpty() && history.lastOrNull() != path) {
            history.addLast(path)
            while (history.size > HISTORY_LIMIT) history.removeFirst()
        }
        visited += target
        path = target
        if (moved) onPlace(target)
        val memory = levels[key(target)]
        if (memory != null) show(memory) else {
            tree = null
            entries = emptyList()
            cursor = null
            cachedNote = null
            cachedAt = null
        }
        loadingTarget = target
        loadJob = work.launch {
            try {
                load(target, memory != null)
            } finally {
                if (loadingTarget == target) loadingTarget = null
            }
        }
    }

    private fun show(level: Level) {
        cachedNote = level.cachedAt?.let { ExplorerText.cachedCopy(stamp(it)) }
        cachedAt = level.cachedAt
        cursor = level.cursor
        tree = level.tree
        entries = level.entries
    }

    private suspend fun load(target: String, haveMemory: Boolean) {
        loading = true
        val k = key(target)
        val disk = if (haveMemory) null else withContext(Dispatchers.IO) { runCatching { cache?.read(k) }.getOrNull() }
        if (disk != null && !online()) {
            val level = Level(disk.tree, disk.tree.entries, null, disk.fetchedAt)
            levels[k] = level
            if (path == target) show(level)
            loading = false
            return
        }
        val got = coroutineScope {
            loads++
            val fetch = async {
                try {
                    Result.success(api.tree(target, statusParam, null))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            }
            val early = if (disk != null) withTimeoutOrNull(fallbackMs) { fetch.await() } else fetch.await()
            if (early == null && disk != null && path == target) show(Level(disk.tree, disk.tree.entries, null, disk.fetchedAt))
            early ?: fetch.await()
        }
        got.onSuccess { fresh ->
            val level = Level(fresh, fresh.entries, fresh.nextCursor, null)
            levels[k] = level
            withContext(Dispatchers.IO) { runCatching { cache?.write(k, fresh) } }
            if (path == target) {
                show(level)
                restricted = false
            }
            if (target.isEmpty() && !landed) {
                landed = true
                val fallback = fresh.taxonomies.firstOrNull { it.isDefault } ?: fresh.taxonomies.firstOrNull()
                if (fallback != null && fallback.path.isNotEmpty() && path == target) {
                    loading = false
                    open(fallback.path)
                    return
                }
            }
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            when {
                failure is HttpException && failure.code() == 403 -> restricted = true
                disk != null && path == target -> show(Level(disk.tree, disk.tree.entries, null, disk.fetchedAt))
                tree != null && path == target -> Unit
                ExplorerBusy.of(failure) != null -> error = ExplorerBusy.of(failure)?.message
                else -> error = ExplorerText.folderFailed
            }
        }
        loading = false
    }

    fun reload() {
        levels.remove(key(path))
        loadJob?.cancel()
        loadingTarget = null
        open(path)
    }

    fun selectAll(entries: List<ExplorerEntry>) {
        selected = selected + entries.filter { selectable(it) }.associateBy { it.key }
    }

    val selectedBytes: Long get() = selected.values.sumOf { it.sizeBytes ?: 0L }

    val selectedFolders: Int get() = selected.values.count { it.isFolder }

    fun loadMore() {
        val at = cursor ?: return
        if (loadingMore) return
        val target = path
        work.launch {
            loadingMore = true
            try {
                val next = api.tree(target, statusParam, at)
                if (path == target) {
                    val seen = entries.map { it.key }.toHashSet()
                    entries = entries + next.entries.filter { it.key !in seen }
                    cursor = next.nextCursor
                    levels[key(target)]?.let { levels[key(target)] = Level(it.tree, entries, cursor, it.cachedAt) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = ExplorerText.folderFailed
            } finally {
                loadingMore = false
            }
        }
    }

    fun goUp(): Boolean {
        while (history.isNotEmpty()) {
            val previous = history.removeLast()
            if (previous != path) {
                open(previous, remember = false)
                return true
            }
        }
        if (atTop || crumbs.size < 2) return false
        val parent = crumbs.dropLast(1).lastOrNull { !isGrouping(it.path) || it.path in visited }
            ?: crumbs.getOrNull(crumbs.size - 2)
            ?: return false
        open(parent.path, remember = false)
        return true
    }

    fun locate(recordType: String, recordId: String, title: String): Job {
        locateJob?.cancel()
        return work.launch {
            locating = recordId
            unfiled = null
            error = null
            try {
                val body = api.locate(recordType, recordId) as? JsonObject
                val found = (body?.get("path") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
                val record = if (found == null) ExplorerRecordRef.of(body, title) else null
                when {
                    found != null -> open(found)
                    record != null -> recordToOpen = record
                    else -> unfiled = ExplorerText.unfiled(title)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = ExplorerText.locateFailed
            } finally {
                locating = null
            }
        }.also { locateJob = it }
    }

    fun recordHandled(opened: Boolean) {
        val record = recordToOpen ?: return
        recordToOpen = null
        if (!opened) unfiled = ExplorerText.unfiled(record.title)
    }

    fun toggleText(entry: ExplorerEntry) {
        openText = openText.toMutableSet().apply { if (!add(entry.key)) remove(entry.key) }
        if (entry.key in openText && entry.content == null && entry.transcriptPath != null) fetchTranscript(entry)
    }

    fun toggleTranscript(entry: ExplorerEntry) {
        openTranscripts = openTranscripts.toMutableSet().apply { if (!add(entry.key)) remove(entry.key) }
        if (entry.key in openTranscripts) fetchTranscript(entry)
    }

    fun transcriptFor(entry: ExplorerEntry): TranscriptLoad? = transcripts[entry.key]

    private fun fetchTranscript(entry: ExplorerEntry) {
        val k = entry.key
        val now = transcripts[k]
        if (now is TranscriptLoad.Loading || now is TranscriptLoad.Loaded || now is TranscriptLoad.None) return
        transcripts[k] = TranscriptLoad.Loading
        work.launch {
            transcripts[k] = try {
                val got = loadTranscript(entry)
                if (got.isNullOrBlank()) TranscriptLoad.None else TranscriptLoad.Loaded(got)
            } catch (cancelled: CancellationException) {
                transcripts.remove(k)
                throw cancelled
            } catch (failure: HttpException) {
                if (failure.code() == 404) entry.content?.let { TranscriptLoad.Loaded(it) } ?: TranscriptLoad.None else TranscriptLoad.Failed
            } catch (failure: Throwable) {
                entry.content?.let { TranscriptLoad.Loaded(it) } ?: TranscriptLoad.Failed
            }
        }
    }

    suspend fun transcriptText(entry: ExplorerEntry): String? {
        (transcripts[entry.key] as? TranscriptLoad.Loaded)?.let { return it.text }
        val text = entry.content ?: loadTranscript(entry)
        if (!text.isNullOrBlank()) transcripts[entry.key] = TranscriptLoad.Loaded(text)
        return text
    }

    private suspend fun loadTranscript(entry: ExplorerEntry): String? {
        val route = entry.transcriptPath
        if (route != null) {
            val response = api.path(ExplorerPaths.api(route))
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                runCatching { response.errorBody()?.close() }
                if (response.code() == 404) return entry.content
                throw IOException("HTTP ${response.code()}")
            }
            val text = withContext(Dispatchers.IO) { body.use { it.string() } }
            return runCatching { ExplorerJson.decodeFromString(ExplorerTranscript.serializer(), text).content }.getOrNull() ?: text
        }
        val mediaId = entry.mediaId ?: return entry.content
        return api.transcript(mediaId).content
    }

    fun beginSelect(entry: ExplorerEntry? = null) {
        selecting = true
        if (entry != null && selectable(entry)) selected = selected + (entry.key to entry)
    }

    fun toggleSelect(entry: ExplorerEntry) {
        if (!selectable(entry)) return
        selected = if (entry.key in selected) selected - entry.key else selected + (entry.key to entry)
    }

    fun selectAllHere() {
        selected = selected + entries.filter { selectable(it) }.associateBy { it.key }
    }

    fun endSelect() {
        selecting = false
        selected = emptyMap()
    }

    fun selectionBody(): ExplorerSelectionBody = ExplorerSelectionBody(
        paths = selected.values.filter { it.isFolder }.map { it.path },
        mediaIds = selected.values.filter { !it.isFolder }.mapNotNull { it.mediaId }.distinct(),
        include = null,
        stream = true,
    )

    fun selectionStatsBody(): ExplorerStatsBody = selectionBody().let { ExplorerStatsBody(it.paths, it.mediaIds, null) }

    companion object {
        const val FALLBACK_MS = 6_000L
        const val HISTORY_LIMIT = 64

        private val GROUPINGS = setOf("crafts", "artisans", "products", "tools", "processes", "questionnaire", "stages", "designworkshops")

        fun isGrouping(path: String): Boolean {
            val segments = path.split('/').filter { it.isNotEmpty() }
            return segments.size >= 2 && segments.last() in GROUPINGS
        }

        fun selectable(entry: ExplorerEntry): Boolean = entry.isFolder || (entry.mediaId != null && !entry.isTranscriptFile)

        fun stamp(millis: Long): String? = runCatching {
            DateTimeFormatter.ofPattern("d MMM, HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        }.getOrNull()
    }
}

data class ExplorerRecordRef(val kind: String, val id: String, val title: String) {
    companion object {
        fun of(body: JsonObject?, title: String): ExplorerRecordRef? {
            val record = body?.get("record") as? JsonObject ?: return null
            val kind = (record["kind"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" } ?: return null
            val id = (record["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" } ?: return null
            return ExplorerRecordRef(kind, id, title)
        }
    }
}
