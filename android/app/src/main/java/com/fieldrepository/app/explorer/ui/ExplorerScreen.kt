package com.fieldrepository.app.explorer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.data.ExplorerBytes
import com.fieldrepository.app.explorer.data.ExplorerCache
import com.fieldrepository.app.explorer.data.ExplorerDownloads
import com.fieldrepository.app.explorer.data.ExplorerDownloadsTap
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerEnvironment
import com.fieldrepository.app.explorer.data.ExplorerFolder
import com.fieldrepository.app.explorer.data.ExplorerInfo
import com.fieldrepository.app.explorer.data.ExplorerItem
import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerJobKind
import com.fieldrepository.app.explorer.data.ExplorerJobState
import com.fieldrepository.app.explorer.data.ExplorerKind
import com.fieldrepository.app.explorer.data.ExplorerMore
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerNetwork
import com.fieldrepository.app.explorer.data.ExplorerNotifications
import com.fieldrepository.app.explorer.data.ExplorerOpen
import com.fieldrepository.app.explorer.data.ExplorerOverview
import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.explorer.data.ExplorerPlace
import com.fieldrepository.app.explorer.data.ExplorerPreflight
import com.fieldrepository.app.explorer.data.ExplorerPreflightPlan
import com.fieldrepository.app.explorer.data.ExplorerReportOptions
import com.fieldrepository.app.explorer.data.ExplorerRequest
import com.fieldrepository.app.explorer.data.ExplorerSaver
import com.fieldrepository.app.explorer.data.ExplorerSelectionBody
import com.fieldrepository.app.explorer.data.ExplorerSize
import com.fieldrepository.app.explorer.data.ExplorerSource
import com.fieldrepository.app.explorer.data.ExplorerSpeedMemory
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.explorer.data.ExplorerTypeStats
import com.fieldrepository.app.explorer.data.ExplorerView
import com.fieldrepository.app.explorer.data.ExplorerVisit
import com.fieldrepository.app.explorer.data.ExplorerVisitBody
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.fieldrepository.app.transcript.ui.TranscriptFileChips
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LocalExplorerSearchAll = staticCompositionLocalOf<((String) -> Unit)?> { null }

private sealed interface DownloadTarget {
    data class Folder(val path: String, val name: String) : DownloadTarget
    data object Selection : DownloadTarget
}

private class Pending(val title: String, val plan: ExplorerPreflightPlan, val start: (wifiOnly: Boolean, include: String?) -> Unit, val replan: () -> ExplorerPreflightPlan)

private class Viewing(val entries: List<ExplorerEntry>, val index: Int, val startSeconds: Double = 0.0, val total: Int = entries.size)

internal object ExplorerTapMemory {
    @Volatile var handled: Long = 0
}

internal val INCLUDE_ORDER = listOf(
    ExplorerKind.TEXT, ExplorerKind.IMAGES, ExplorerKind.VIDEOS, ExplorerKind.AUDIOS,
    ExplorerKind.TRANSCRIPTS, ExplorerKind.DOCUMENTS, ExplorerKind.OTHER,
)

internal fun includedSize(size: ExplorerSize, include: Set<String>): ExplorerSize {
    if (include.size == INCLUDE_ORDER.size) return size
    val kept = size.byKind.filterKeys { it.token in include }
    return ExplorerSize(
        files = kept.values.sumOf { it.files },
        bytes = kept.values.sumOf { it.bytes },
        unknown = 0,
        byKind = kept,
        largest = minOf(size.largest, kept.values.maxOfOrNull { it.bytes } ?: 0),
        truncated = size.truncated,
    )
}

internal fun includeParam(include: Set<String>): String? =
    if (include.size == INCLUDE_ORDER.size) null else INCLUDE_ORDER.filter { it.token in include }.joinToString(",") { it.token }

internal fun selectionBreakdown(entries: Collection<ExplorerEntry>): String {
    val files = entries.filterNot { it.isFolder }
    val parts = ArrayList<String>()
    val folders = entries.count { it.isFolder }
    if (folders > 0) parts += ExplorerBytes.count(folders, "folder")
    val by = files.groupBy { ExplorerKind.ofMediaType(it.mediaType) }
    listOf(ExplorerKind.VIDEOS to ("video" to "videos"), ExplorerKind.IMAGES to ("image" to "images"), ExplorerKind.AUDIOS to ("audio" to "audio"), ExplorerKind.DOCUMENTS to ("document" to "documents"), ExplorerKind.OTHER to ("file" to "files"))
        .forEach { (kind, words) -> by[kind]?.size?.takeIf { it > 0 }?.let { parts += ExplorerUiText.typeCount(it, words.first, words.second) } }
    val transcripts = files.count { it.transcriptAvailable }
    if (transcripts > 0) parts += ExplorerUiText.plusTranscripts(transcripts)
    return parts.joinToString(" · ")
}

@Composable
fun rememberExplorerState(api: ExplorerApi): ExplorerState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val owner = remember { ExplorerPortal.userId(context) }
    return remember(api) {
        ExplorerState(
            api = api,
            scope = scope,
            cache = ExplorerCache(java.io.File(context.filesDir, ExplorerCache.DIR), owner),
            rootName = ExplorerPortal.ROOT_NAME,
            defaultStatus = ExplorerPortal.defaultStatus,
            statusParamOf = ExplorerPortal::statusParam,
            online = { ExplorerNetwork.now(context).online },
            onPlace = { ExplorerPlace.write(context, owner, it) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ExplorerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    onOpenMedia: ((mediaId: String) -> Unit)? = null,
    search: @Composable (onOpen: (recordType: String, recordId: String, title: String) -> Unit) -> Unit = {},
    api: ExplorerApi? = null,
    startPath: String? = null,
    searchScreen: (@Composable (query: String, onOpenInData: (type: String, id: String, title: String) -> Unit, onPlay: (mediaId: String, title: String, mediaType: String?, startSeconds: Double) -> Unit, onClose: () -> Unit) -> Unit)? = null,
    onRecordNow: ((folderPath: String, folderName: String) -> Unit)? = null,
    onOpenSection: ((artisanId: String, sectionKey: String) -> Unit)? = null,
    onOpenRecord: ((kind: String, id: String) -> Unit)? = null,
    more: ExplorerMore? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val explorerApi = remember(api) { api ?: ExplorerEnvironment.api(app) }
    val extras = remember(more) { more ?: ExplorerMoreApi.of(app) }
    val state = rememberExplorerState(explorerApi)
    val scope = rememberCoroutineScope()
    val owner = remember { ExplorerPortal.userId(app) }
    val tables = remember(explorerApi) { ExplorerTablesState(explorerApi, scope, owner) }
    val link by produceState(initialValue = ExplorerNetwork.now(app)) { ExplorerNetwork.watch(app).collect { value = it } }
    val jobs by ExplorerDownloads.store(app).jobs.collectAsState()
    val tap by ExplorerDownloadsTap.requests.collectAsState()
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ExplorerEntry?>(null) }
    var media by remember { mutableStateOf<Viewing?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var reportOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var include by rememberSaveable { mutableStateOf(INCLUDE_ORDER.map { it.token }.toSet()) }
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showAllInfo by rememberSaveable { mutableStateOf(false) }
    var allFilesFor by rememberSaveable { mutableStateOf<String?>(null) }
    var viewQuery by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    val stats = remember { mutableStateMapOf<String, SizeLoad>() }
    val visits = remember { mutableStateMapOf<String, ExplorerVisit>() }
    val overviews = remember { mutableStateMapOf<String, ExplorerOverview>() }
    var selectionSize by remember { mutableStateOf<Long?>(null) }
    var shareIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var hidden by rememberSaveable { mutableStateOf(setOf<String>()) }
    val openedAt = rememberSaveable { System.currentTimeMillis() }
    var lastPath by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ExplorerFolder.write(app, uri)
            pending = pending?.let { p -> Pending(p.title, p.replan(), p.start, p.replan) }
        }
    }
    val languages by produceState(initialValue = emptyList<Pair<String, String>>(), explorerApi) {
        value = runCatching { explorerApi.languages().languages.map { it.code to it.label.ifBlank { it.code } } }.getOrDefault(emptyList())
    }

    LaunchedEffect(state) {
        state.start(startPath?.takeIf { it.isNotBlank() } ?: ExplorerPlace.read(app, owner))
        withContext(Dispatchers.IO) { runCatching { ExplorerDownloads.sweep(app) } }
    }
    LaunchedEffect(tap) {
        if (tap > 0 && tap > ExplorerTapMemory.handled) {
            ExplorerTapMemory.handled = tap
            showDownloads = true
        }
    }
    val systemActive = jobs.any { it.active && it.systemId != null }
    LaunchedEffect(systemActive) {
        while (systemActive) {
            runCatching { ExplorerDownloads.refreshSystem(app) }
            delay(1_000)
        }
    }
    LaunchedEffect(state.path) {
        if (lastPath != null && lastPath != state.path) {
            listState.scrollToItem(0)
            kindFilter = null
            showAllInfo = false
        }
        lastPath = state.path
    }
    val loaded = state.tree != null
    LaunchedEffect(state.path, loaded) {
        if (!loaded) return@LaunchedEffect
        val at = state.path
        if (stats[at] !is SizeLoad.Known && (state.crumbs.size >= 3 || state.info != null)) {
            stats[at] = SizeLoad.Loading
            stats[at] = try {
                SizeLoad.Known(ExplorerSize.of(explorerApi.stats(at)))
            } catch (cancelled: CancellationException) {
                stats.remove(at)
                throw cancelled
            } catch (failure: Throwable) {
                SizeLoad.Unknown
            }
        }
        runCatching { extras.visit(ExplorerVisitBody(at)) }.getOrNull()?.let { visits[at] = it }
        if (isWorkshopFolder(at) && overviews[at] == null) {
            runCatching { extras.overview(at, state.statusParam) }.getOrNull()?.let { overviews[at] = it }
        }
    }
    LaunchedEffect(state.tab, state.path) {
        if (state.tab == ExplorerTab.TABLES) tables.show(state.path)
    }
    val selectedKeys = state.selected.keys
    LaunchedEffect(selectedKeys) {
        selectionSize = null
        if (state.selected.values.any { it.isFolder }) {
            delay(400)
            selectionSize = runCatching { explorerApi.selectionStats(state.selectionStatsBody()).bytes }.getOrNull()
        }
    }
    LaunchedEffect(jobs, shareIds) {
        val ready = jobs.filter { it.id in shareIds && it.state == ExplorerJobState.DONE }
        if (ready.isNotEmpty()) {
            ready.forEach { ExplorerOpen.share(context, it) }
            shareIds = shareIds - ready.map { it.id }.toSet()
        }
        val gone = shareIds.filter { id -> jobs.none { it.id == id && (it.active || it.state == ExplorerJobState.DONE) } }
        if (gone.isNotEmpty()) shareIds = shareIds - gone.toSet()
    }

    fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ExplorerNotifications.allowed(app)) {
            runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    fun begin(request: ExplorerRequest): String {
        ensureNotifications()
        val id = ExplorerDownloads.start(app, request)
        onMessage(ExplorerText.keepsGoing)
        return id
    }

    fun preflightOr(title: String, size: ExplorerSize?, fileName: String, start: (wifiOnly: Boolean, include: String?) -> Unit) {
        if (size == null) {
            start(false, null)
            return
        }
        val replan = {
            ExplorerPreflight.of(
                size = size,
                link = link,
                freeBytes = ExplorerSaver.freeBytes(app),
                speedBps = ExplorerSpeedMemory.read(app, link.metered),
                location = ExplorerSaver.location(fileName),
            )
        }
        val plan = replan()
        if (plan.show) pending = Pending(title, plan, start, replan) else start(false, null)
    }

    fun saveText(entry: ExplorerEntry) {
        scope.launch {
            val text = try {
                state.transcriptText(entry)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                null
            }
            if (text.isNullOrBlank()) {
                onMessage(ExplorerText.transcriptFailed)
                return@launch
            }
            begin(
                ExplorerRequest(
                    title = entry.name,
                    fileName = entry.name,
                    kind = ExplorerJobKind.FILE,
                    single = true,
                    source = entry.key,
                    items = listOf(ExplorerItem(path = entry.name, content = text)),
                ),
            )
        }
    }

    fun saveEntry(entry: ExplorerEntry) {
        if (entry.isTranscriptFile) {
            saveText(entry)
            return
        }
        val request = ExplorerRequest(
            title = entry.name,
            fileName = entry.name,
            kind = ExplorerJobKind.FILE,
            single = true,
            items = listOf(
                ExplorerItem(
                    path = entry.name,
                    mediaId = entry.mediaId,
                    content = if (entry.mediaId == null) entry.content else null,
                    mediaType = entry.mediaType,
                    sizeBytes = entry.sizeBytes,
                ),
            ),
            expectedBytes = entry.sizeBytes,
            source = entry.key,
        )
        ensureNotifications()
        scope.launch {
            try {
                ExplorerDownloads.saveFile(app, request, entry.mediaId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                onMessage(ExplorerText.fileFailed)
            }
        }
    }

    fun transcriptZip(entry: ExplorerEntry) {
        val mediaId = entry.mediaId ?: return
        begin(
            ExplorerRequest(
                title = "${entry.name} · ${ExplorerText.allFormats}",
                fileName = Naming.exportFilename("Dataset", "Transcript", "zip", subject = entry.name.substringBeforeLast('.')),
                kind = ExplorerJobKind.TRANSCRIPTS,
                single = true,
                mimeType = "application/zip",
                items = listOf(ExplorerItem(path = "", url = ExplorerPaths.transcriptZip(mediaId))),
            ),
        )
    }

    fun transcriptBundle() {
        val name = state.folderName
        begin(
            ExplorerRequest(
                title = "${name} · ${ExplorerText.transcripts}",
                fileName = Naming.exportFilename("Dataset", "Transcripts", "zip", withTime = true, subject = name),
                kind = ExplorerJobKind.TRANSCRIPTS,
                single = true,
                mimeType = "application/zip",
                items = listOf(ExplorerItem(path = "", url = ExplorerPaths.transcriptBundle(state.path))),
            ),
        )
    }

    fun downloadFolder() {
        if (include.isEmpty()) {
            onMessage(ExplorerUiText.pickOne)
            return
        }
        val path = state.path
        val name = state.folderName
        val fileName = Naming.exportFilename("Dataset", "Export", "zip", withTime = true, subject = name)
        val known = (stats[path] as? SizeLoad.Known)?.size?.let { includedSize(it, include) }
        val chosen = includeParam(include)
        preflightOr(name, known, fileName) { wifiOnly, smaller ->
            begin(
                ExplorerRequest(
                    title = name,
                    fileName = fileName,
                    kind = ExplorerJobKind.FOLDER,
                    sources = listOf(ExplorerSource(path, include = smaller ?: chosen)),
                    wifiOnly = wifiOnly,
                    expectedBytes = known?.bytes,
                ),
            )
            pending = null
        }
    }

    fun downloadSelection(share: Boolean) {
        val count = state.selected.size
        if (count == 0) return
        val body = state.selectionBody()
        val fileName = Naming.exportFilename("Dataset", "Export", "zip", withTime = true, subject = "Selection ${state.folderName}")
        val bytes = selectionSize ?: state.selectedBytes
        val size = ExplorerSize(files = state.selected.size, bytes = bytes, largest = state.selected.values.maxOfOrNull { it.sizeBytes ?: 0 } ?: 0)
        preflightOr(ExplorerText.selected(count), size.takeIf { !share }, fileName) { wifiOnly, smaller ->
            val id = begin(
                ExplorerRequest(
                    title = ExplorerText.selected(count),
                    fileName = fileName,
                    kind = ExplorerJobKind.SELECTION,
                    selection = smaller?.let { ExplorerSelectionBody(body.paths, body.mediaIds, it.split(','), body.stream) } ?: body,
                    wifiOnly = wifiOnly,
                    expectedBytes = bytes.takeIf { it > 0 },
                ),
            )
            if (share) shareIds = shareIds + id
            state.endSelect()
            pending = null
        }
    }

    fun makeReport(options: ExplorerReportOptions, name: String, status: String?) {
        val folder = state.folderName
        val file = name.trim().ifBlank { Naming.exportFilename("Dataset", "Report", "xlsx", withTime = true, subject = folder) }.let { if (it.endsWith(".xlsx")) it else "$it.xlsx" }
        begin(
            ExplorerRequest(
                title = "$folder · ${ExplorerText.report}",
                fileName = file,
                kind = ExplorerJobKind.REPORT,
                single = true,
                mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                items = listOf(ExplorerItem(path = "", url = ExplorerMoreApi.reportUrl(state.path, options, tables.query.ifBlank { null }, file, status))),
            ),
        )
        reportOpen = false
    }

    fun makePack(options: ExplorerReportOptions, name: String, status: String?) {
        val folder = state.folderName
        val file = name.trim().removeSuffix(".xlsx").ifBlank { Naming.exportFilename("Dataset", "Report", "zip", withTime = true, subject = folder).removeSuffix(".zip") }.let { if (it.endsWith(".zip")) it else "$it.zip" }
        begin(
            ExplorerRequest(
                title = "$folder · ${ExplorerUiText.reportPack}",
                fileName = file,
                kind = ExplorerJobKind.REPORT,
                single = true,
                mimeType = "application/zip",
                items = listOf(ExplorerItem(path = "", url = ExplorerMoreApi.reportUrl(state.path, options, tables.query.ifBlank { null }, file, status, pack = true))),
            ),
        )
        reportOpen = false
    }

    suspend fun previewMedia(mediaId: String, seconds: Double?) {
        val known = state.files.firstOrNull { it.mediaId == mediaId }
        val entry = known ?: run {
            val got = runCatching { extras.goMedia(mediaId) }.getOrNull()
            val name = got?.fileName ?: mediaId
            ExplorerEntry(name = name, path = mediaId, mediaId = mediaId, mediaType = mediaTypeOf(name) ?: "VIDEO", transcriptAvailable = true)
        }
        if (entry.mediaType in setOf("VIDEO", "AUDIO", "IMAGE")) media = Viewing(listOf(entry), 0, seconds ?: 0.0) else viewing = entry
    }

    suspend fun startBundle(id: String) {
        val b = extras.bundle(id)
        val label = b.label.ifBlank { state.folderName }
        begin(
            ExplorerRequest(
                title = label,
                fileName = Naming.exportFilename("Dataset", "Export", "zip", withTime = true, subject = label),
                kind = ExplorerJobKind.FOLDER,
                sources = listOf(ExplorerSource(path = b.path, manifestUrl = b.manifestPath ?: "/api/data/bundles/$id/manifest?stream=1")),
                expectedBytes = b.bytes.takeIf { it > 0 },
            ),
        )
        showDownloads = true
    }

    suspend fun followGo(parts: List<String>) {
        when (parts.firstOrNull()) {
            "media" -> {
                val id = parts.getOrNull(1) ?: return
                val got = extras.goMedia(id)
                val name = got.fileName ?: id
                saveEntry(ExplorerEntry(name = name, path = id, mediaId = id, mediaType = mediaTypeOf(name)))
            }
            "open" -> {
                val kind = parts.getOrNull(1) ?: return
                val id = parts.getOrNull(2) ?: return
                val got = extras.goOpen(kind, id)
                when {
                    got.kind == "media" && onOpenMedia != null -> onOpenMedia(got.id.ifBlank { id })
                    got.kind == "media" -> previewMedia(got.id.ifBlank { id }, null)
                    onOpenRecord != null -> onOpenRecord(got.kind.ifBlank { kind }, got.id.ifBlank { id })
                    else -> state.locate(got.kind.ifBlank { kind }, got.id.ifBlank { id }, "")
                }
            }
            "transcript" -> {
                val id = parts.getOrNull(1) ?: return
                val format = parts.getOrNull(2)?.lowercase()?.takeIf { it == "docx" || it == "srt" } ?: return
                val file = withContext(Dispatchers.IO) {
                    val got = com.fieldrepository.app.transcript.portal.TranscriptPortal.backend(app).download(id, format, com.fieldrepository.app.transcript.data.CopyTrack.BOTH)
                    java.io.File(com.fieldrepository.app.transcript.ui.shareDir(app), got.name ?: "transcript.$format").also { it.writeBytes(got.bytes) }
                }
                com.fieldrepository.app.transcript.ui.shareFiles(context, listOf(file), null)
            }
            else -> onMessage(ExplorerUiText.linkUnknown)
        }
    }

    fun openView(view: ExplorerView) {
        reportOpen = false
        view.state.filters["status"]?.takeIf { it != state.status && ExplorerPortal.statusOptions.any { o -> o.first == it } }?.let { state.changeStatus(it) }
        state.open(view.path)
        viewQuery = view.state.q?.takeIf { it.isNotBlank() }?.let { view.path to it }
        state.tab = when (view.state.view) {
            "tables" -> ExplorerTab.TABLES
            "transcripts" -> ExplorerTab.TRANSCRIPTS
            else -> ExplorerTab.FILES
        }
        if (view.state.view == "tables") view.state.q?.let { q -> scope.launch { delay(50); tables.show(view.path); tables.search(q) } }
        view.state.sheet?.let { tables.pick(it) }
    }

    val linkRequest by ExplorerViewLinks.requests.collectAsState()
    LaunchedEffect(linkRequest) {
        val request = linkRequest ?: return@LaunchedEffect
        ExplorerViewLinks.consume()
        val id = request.viewId
        try {
            when {
                request.go.isNotEmpty() -> { followGo(request.go); return@LaunchedEffect }
                request.bundle != null -> { startBundle(request.bundle); return@LaunchedEffect }
                request.preview != null -> { request.path?.let { state.open(it) }; previewMedia(request.preview, request.seconds); return@LaunchedEffect }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onMessage(ExplorerUiText.linkFailed)
            return@LaunchedEffect
        }
        when {
            id != null -> try {
                openView(extras.view(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                onMessage(ExplorerUiText.viewMissing)
                request.path?.let { state.open(it) }
            }
            request.path != null -> state.open(request.path)
        }
    }
    fun play(entries: List<ExplorerEntry>, entry: ExplorerEntry) {
        val list = entries.filter(::playable)
        val index = list.indexOfFirst { it.key == entry.key }
        if (index < 0) viewing = entry else media = Viewing(list, index)
    }

    BackHandler(enabled = true) {
        when {
            media != null -> media = null
            viewing != null -> viewing = null
            searchQuery != null -> searchQuery = null
            state.selecting -> state.endSelect()
            state.canGoUp && state.goUp() -> Unit
            else -> onBack()
        }
    }

    val headline = jobs.firstOrNull { (it.active || it.state == ExplorerJobState.PAUSED) && it.request.kind != ExplorerJobKind.FILE }
    val finished = jobs.firstOrNull { !it.active && it.state != ExplorerJobState.PAUSED && it.state != ExplorerJobState.CANCELLED && it.request.kind != ExplorerJobKind.FILE && it.createdAt >= openedAt && it.id !in hidden }
        ?.takeIf { headline == null }
    val activeCount = jobs.count { it.active }
    val saveJobs = remember(jobs) { jobs.filter { it.active && it.request.kind == ExplorerJobKind.FILE }.associateBy { it.request.source.orEmpty() } }
    val savedJobs = remember(jobs) {
        jobs.filter { !it.active && it.request.kind == ExplorerJobKind.FILE && (it.state == ExplorerJobState.DONE || it.state == ExplorerJobState.FAILED) }
            .groupBy { it.request.source.orEmpty() }.mapValues { (_, list) -> list.maxBy { it.createdAt } }
    }
    val transcriptJobs = remember(jobs) {
        jobs.filter { it.active && it.request.kind == ExplorerJobKind.TRANSCRIPTS }.mapNotNull { job ->
            job.request.items.firstOrNull()?.url?.substringAfter("/transcripts/", "")?.substringBefore('/')?.takeIf { it.isNotBlank() }
        }.toSet()
    }
    val size = stats[state.path]
    val large = LocalDensity.current.fontScale >= 1.5f
    val network = if (link.metered) ExplorerUiText.mobileData else ExplorerUiText.wifi
    val fileQuery = viewQuery?.takeIf { it.first == state.path && state.tab == ExplorerTab.FILES }?.second
    val shownFiles = state.files.filter { f ->
        (kindFilter == null || ExplorerKind.ofMediaType(f.mediaType).token == kindFilter) &&
            (fileQuery == null || f.name.contains(fileQuery, ignoreCase = true))
    }

    val fileRow: @Composable (ExplorerEntry) -> Unit = { file ->
        FileRow(
            entry = file,
            selecting = state.selecting,
            selected = file.key in state.selected,
            textOpen = file.key in state.openText,
            transcriptOpen = file.key in state.openTranscripts,
            transcript = state.transcriptFor(file),
            saveJob = saveJobs[file.key],
            online = link.online,
            onOpen = {
                val mediaId = file.mediaId
                when {
                    file.isTranscriptFile -> state.toggleText(file)
                    mediaId != null && onOpenMedia != null -> onOpenMedia(mediaId)
                    file.mediaType in ExplorerPaths.PREVIEWABLE -> play(state.files, file)
                    else -> state.toggleText(file)
                }
            },
            onLongPress = { state.beginSelect(file) },
            onToggle = { state.toggleSelect(file) },
            onText = { state.toggleText(file) },
            onTranscript = { state.toggleTranscript(file) },
            onPreview = { play(state.files, file) },
            onSave = { saveEntry(file) },
            onCancelSave = { saveJobs[file.key]?.let { ExplorerDownloads.cancel(app, it.id) } },
            savedJob = savedJobs[file.key],
            onOpenSaved = { savedJobs[file.key]?.let { if (!ExplorerOpen.open(context, it)) onMessage(ExplorerText.previewFailed) } },
            extra = {
                val mediaId = file.mediaId
                if (mediaId != null && (file.transcriptFiles.isNotEmpty() || file.transcriptAvailable)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AllFormatsChip(mediaId, busy = mediaId in transcriptJobs, enabled = link.online) { transcriptZip(file) }
                        TranscriptFileChips(mediaId, file.transcriptFiles, onMessage = onMessage)
                    }
                }
            },
        )
    }

    val folderCard: @Composable (Modifier) -> Unit = { m ->
        FolderCard(
            state = state,
            size = size,
            visit = visits[state.path],
            showAll = showAllInfo,
            onToggleAll = { showAllInfo = !showAllInfo },
            modifier = m,
        )
    }
    val downloadCard: @Composable (Modifier) -> Unit = { m ->
        DownloadCard(
            size = (size as? SizeLoad.Known)?.size,
            include = include,
            onInclude = { token -> include = if (token in include) include - token else include + token },
            online = link.online,
            ready = state.tree != null,
            large = large,
            onReport = { reportOpen = true; tables.show(state.path) },
            onDownload = ::downloadFolder,
            onTranscripts = if ((size as? SizeLoad.Known)?.size?.byKind?.get(ExplorerKind.TRANSCRIPTS)?.files?.let { it > 0 } != false) ({ transcriptBundle() }) else null,
            modifier = m,
        )
    }
    val jobCards: @Composable () -> Unit = {
        headline?.let { job ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProgressCard(
                    job,
                    network = network,
                    onPause = { ExplorerDownloads.pause(app, job.id) },
                    onResume = { ExplorerDownloads.resume(app, job.id) },
                    onCancel = { ExplorerDownloads.cancel(app, job.id) },
                )
                RecentFilesCard(job, onAll = { allFilesFor = job.id })
                Text(
                    ExplorerText.savingTo(ExplorerSaver.location(job.request.fileName)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("explorer.progress.where"),
                )
            }
        }
        finished?.let { job ->
            ResultCard(
                job,
                onOpen = { if (!ExplorerOpen.open(context, job)) onMessage(ExplorerText.previewFailed) },
                onShare = { if (!ExplorerOpen.share(context, job)) onMessage(ExplorerText.previewFailed) },
                onRetry = { ExplorerDownloads.retry(app, job.id) },
                onDismiss = { hidden = hidden + job.id },
            )
        }
    }

    val content: LazyListScope.(Boolean) -> Unit = { wide ->
        if (state.locating != null) item("locating") { LoadingLine(ExplorerText.preparing) }
        state.unfiled?.let { text -> item("unfiled") { Notice(text, warn = true) } }
        if (!link.online && state.tree != null) {
            item("offline") { OfflineCard(state.cachedAt?.let { ExplorerUiText.ago((System.currentTimeMillis() - it) / 60_000) }) }
        } else {
            state.cachedNote?.let { text -> item("cached") { CachedNote(text) } }
        }
        val error = state.error
        when {
            error != null && state.tree == null -> item("error") { LoadFailedCard(error + state.path, onRetry = { state.dismissError(); state.reload() }) }
            error != null -> item("error") { StatusCard(ExplorerStatus.FAILED, error, action = ExplorerText.retry, tag = "explorer.error") { state.dismissError(); state.reload() } }
        }
        if (state.tree == null && state.loading && error == null) {
            item("loading") { FolderSkeleton() }
        }
        if (state.tree != null) {
            if (!wide) {
                item("folder") { folderCard(Modifier.fillMaxWidth()) }
                item("download") { downloadCard(Modifier.fillMaxWidth()) }
            } else {
                item("cards") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        folderCard(Modifier.weight(1f))
                        downloadCard(Modifier.weight(1f))
                    }
                }
            }
            if (headline != null || finished != null) item("jobs") { jobCards() }
            overviews[state.path]?.let { overview -> item("overview") { OverviewCard(overview, onOpen = { state.open(it) }, onSection = onOpenSection) } }
            item("tabs") {
                Tabs(
                    tab = state.tab,
                    files = state.total ?: state.entries.size,
                    tables = tables.ready?.report?.sheets?.size,
                    onTab = { state.tab = it },
                )
            }
            when (state.tab) {
                ExplorerTab.FILES -> {
                    if (state.entries.isEmpty()) {
                        item("empty") { EmptyFolderCard(state.folderName, onRecordNow?.let { record -> { record(state.path, state.folderName) } }) }
                    }
                    if (fileQuery != null) {
                        item("filequery") {
                            FilterChip(
                                selected = true,
                                onClick = { viewQuery = null },
                                label = { Text(ExplorerUiText.filteredBy(fileQuery)) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = ExplorerText.clear, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.filequery"),
                            )
                        }
                    }
                    if (state.selecting) {
                        item("kinds") { KindPills(state.files, kindFilter) { kindFilter = it } }
                    }
                    if (!wide) {
                        items(state.folders, key = { it.key }) { folder ->
                            FolderRow(
                                folder = folder,
                                selecting = state.selecting,
                                selected = folder.key in state.selected,
                                onOpen = { state.open(folder.path) },
                                onLongPress = { state.beginSelect(folder) },
                                onToggle = { state.toggleSelect(folder) },
                            )
                        }
                        items(shownFiles, key = { it.key }) { file -> fileRow(file) }
                    } else {
                        val pairs = shownFiles.chunked(2)
                        items(pairs, key = { pair -> pair.joinToString("|") { it.key } }) { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { file -> Box(Modifier.weight(1f)) { fileRow(file) } }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (state.cursor != null) {
                        item("more") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                OutlinedButton(onClick = state::loadMore, enabled = !state.loadingMore, modifier = Modifier.heightIn(min = 48.dp).testTag("explorer.more")) {
                                    if (state.loadingMore) {
                                        FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(ExplorerText.loadMore)
                                }
                            }
                        }
                    }
                    if (state.truncated) item("truncated") { Notice(ExplorerText.partlyShown, warn = true, tag = "explorer.truncated") }
                }
                ExplorerTab.TABLES -> item("tables") {
                    ExplorerTables(
                        state = tables,
                        online = link.online,
                        onCsv = { sheet ->
                            begin(
                                ExplorerRequest(
                                    title = "${state.folderName} · $sheet",
                                    fileName = Naming.exportFilename("Dataset", "Export", "csv", withTime = true, subject = "${state.folderName} $sheet"),
                                    kind = ExplorerJobKind.REPORT,
                                    single = true,
                                    mimeType = "text/csv",
                                    items = listOf(ExplorerItem(path = "", url = ExplorerPaths.reportCsv(state.path, sheet))),
                                ),
                            )
                        },
                        onReport = { reportOpen = true },
                    )
                }
                ExplorerTab.TRANSCRIPTS -> item("transcripts") {
                    ExplorerTranscriptsTab(
                        path = state.path,
                        initialQuery = viewQuery?.takeIf { it.first == state.path }?.second.orEmpty(),
                        status = state.statusParam,
                        online = link.online,
                        offeredLanguages = languages,
                        onRead = { r ->
                            val entry = state.files.firstOrNull { it.mediaId == r.mediaId }
                                ?: ExplorerEntry(name = r.name, path = r.mediaId, mediaId = r.mediaId, mediaType = r.mediaType.takeIf { it == "VIDEO" || it == "AUDIO" } ?: "AUDIO", transcriptAvailable = true)
                            media = Viewing(listOf(entry), 0)
                        },
                        onBundle = ::transcriptBundle,
                        onMessage = onMessage,
                        more = extras,
                    )
                }
            }
        }
    }

    CompositionLocalProvider(LocalExplorerSearchAll provides searchScreen?.let { { q: String -> searchQuery = q } }) {
        Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("explorer.screen")) {
            TopBar(
                selecting = state.selecting,
                selectedCount = state.selected.size,
                selectedSize = ExplorerBytes.format((selectionSize ?: state.selectedBytes).takeIf { it > 0 }),
                selectableCount = state.entries.count { ExplorerState.selectable(it) },
                loading = state.loading,
                activeDownloads = activeCount,
                canSearch = searchScreen != null,
                onBack = { if (state.selecting) state.endSelect() else if (!state.canGoUp || !state.goUp()) onBack() },
                onDownloads = { showDownloads = true },
                onSearch = { searchQuery = "" },
                onReload = { state.reload(); tables.reload() },
                onSelectAll = { state.selectAll(if (kindFilter == null) state.entries else shownFiles) },
            )
            if (state.restricted) {
                Restricted()
                return@Column
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 840.dp
                if (!wide) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("explorer.tree"),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        stickyHeader("toolbar") { Toolbar(search = { search { type, id, title -> state.locate(type, id, title) } }) }
                        item("organise") { OrganiseCard(state) }
                        content(false)
                    }
                } else {
                    Row(Modifier.fillMaxSize().testTag("explorer.tablet")) {
                        LazyColumn(
                            modifier = Modifier.width(360.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).testTag("explorer.pane.tree"),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item("toolbar") { Toolbar(search = { search { type, id, title -> state.locate(type, id, title) } }) }
                            item("organise") { OrganiseCard(state) }
                            if (state.canGoUp) {
                                item("up") {
                                    TextButton(onClick = { state.goUp() }, modifier = Modifier.heightIn(min = 44.dp)) {
                                        Text("‹ " + (state.crumbs.getOrNull(state.crumbs.size - 2)?.name ?: ExplorerText.back))
                                    }
                                }
                            }
                            items(state.folders, key = { it.key }) { folder ->
                                FolderRow(
                                    folder = folder,
                                    selecting = state.selecting,
                                    selected = folder.key in state.selected,
                                    onOpen = { state.open(folder.path) },
                                    onLongPress = { state.beginSelect(folder) },
                                    onToggle = { state.toggleSelect(folder) },
                                )
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag("explorer.tree"),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) { content(true) }
                    }
                }
            }
            if (media == null && !state.selecting) {
                MiniPlayer(onOpen = {
                    val item = com.fieldrepository.app.explorer.data.ExplorerAudio.state.value.item
                    if (item != null) {
                        val entry = state.files.firstOrNull { it.mediaId == item.mediaId }
                            ?: ExplorerEntry(name = item.title, path = item.mediaId, mediaId = item.mediaId, mediaType = "AUDIO", transcriptAvailable = item.lines.isNotEmpty())
                        media = Viewing(listOf(entry), 0)
                    }
                })
            }
            if (state.selecting) {
                SelectBar(
                    count = state.selected.size,
                    breakdown = selectionBreakdown(state.selected.values),
                    size = ExplorerBytes.format((selectionSize ?: state.selectedBytes).takeIf { it > 0 }),
                    online = link.online,
                    large = large,
                    onShare = { downloadSelection(share = true) },
                    onDownload = { downloadSelection(share = false) },
                )
            } else if (!link.online) {
                Text(
                    ExplorerText.offline,
                    style = MaterialTheme.typography.labelSmall,
                    color = explorerWarnTone().content,
                    modifier = Modifier.fillMaxWidth().background(explorerWarnTone().container).padding(horizontal = 16.dp, vertical = 8.dp).testTag("explorer.offline"),
                )
            }
        }
    }

    pending?.let { p ->
        PreflightSheet(
            title = p.title,
            plan = p.plan,
            onStart = { wifi, smaller -> p.start(wifi, smaller) },
            onDismiss = { pending = null },
            onChangeFolder = { runCatching { pickFolder.launch(ExplorerFolder.read(app)) }.onFailure { onMessage(ExplorerUiText.noFolderPicker) } },
            onResetFolder = if (ExplorerFolder.label(app) != null) ({
                ExplorerFolder.write(app, null)
                pending = Pending(p.title, p.replan(), p.start, p.replan)
            }) else null,
        )
    }

    if (reportOpen) {
        ReportOptionsSheet(
            folderTitle = state.crumbs.drop(1).joinToString(" · ") { it.name }.ifBlank { state.folderName },
            path = state.path,
            fileName = Naming.exportFilename("Dataset", "Report", "xlsx", withTime = true, subject = state.folderName),
            sheets = tables.ready?.takeIf { it.path == state.path }?.report?.sheets.orEmpty(),
            query = tables.query,
            tab = state.tab,
            more = extras,
            onMake = ::makeReport,
            onMakePack = ::makePack,
            status = state.statusParam,
            onOpenView = ::openView,
            onMessage = onMessage,
            onDismiss = { reportOpen = false },
        )
    }

    allFilesFor?.let { id ->
        jobs.firstOrNull { it.id == id }?.let { job -> AllFilesSheet(job, onDismiss = { allFilesFor = null }) }
    }

    if (showDownloads) {
        DownloadsSheet(
            jobs = jobs,
            onCancel = { ExplorerDownloads.cancel(app, it.id) },
            onRetry = { ExplorerDownloads.retry(app, it.id) },
            onOpen = { if (!ExplorerOpen.open(context, it)) onMessage(ExplorerText.previewFailed) },
            onShare = { if (!ExplorerOpen.share(context, it)) onMessage(ExplorerText.previewFailed) },
            onDismissJob = { ExplorerDownloads.dismiss(app, it.id) },
            onClearFinished = { ExplorerDownloads.clearFinished(app) },
            onDismiss = { showDownloads = false },
            onPause = { ExplorerDownloads.pause(app, it.id) },
            onResume = { ExplorerDownloads.resume(app, it.id) },
        )
    }

    viewing?.let { entry ->
        ExplorerPreviewDialog(
            entry = entry,
            onSave = if (entry.mediaId != null) ({ saveEntry(entry) }) else null,
            onDismiss = { viewing = null },
        )
    }

    media?.let { v ->
        ExplorerMediaDialog(
            entries = v.entries,
            start = v.index,
            startSeconds = v.startSeconds,
            total = v.total,
            onSave = { saveEntry(it) },
            onDismiss = { media = null },
        )
    }

    val query = searchQuery
    if (query != null && searchScreen != null) {
        Dialog(onDismissRequest = { searchQuery = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize().testTag("explorer.search.screen"), color = MaterialTheme.colorScheme.background) {
                searchScreen(
                    query,
                    { type, id, title -> searchQuery = null; state.locate(type, id, title) },
                    { mediaId, title, mediaType, seconds ->
                        val entry = state.files.firstOrNull { it.mediaId == mediaId }
                            ?: ExplorerEntry(name = title, path = mediaId, mediaId = mediaId, mediaType = mediaType?.uppercase()?.takeIf { it == "VIDEO" || it == "AUDIO" } ?: "VIDEO", transcriptAvailable = true)
                        media = Viewing(listOf(entry), 0, seconds)
                    },
                    { searchQuery = null },
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    selecting: Boolean,
    selectedCount: Int,
    selectedSize: String?,
    selectableCount: Int,
    loading: Boolean,
    activeDownloads: Int,
    canSearch: Boolean,
    onBack: () -> Unit,
    onDownloads: () -> Unit,
    onSearch: () -> Unit,
    onReload: () -> Unit,
    onSelectAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("explorer.back")) {
            Icon(if (selecting) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (selecting) ExplorerUiText.stopSelecting else ExplorerText.back, tint = MaterialTheme.colorScheme.primary)
        }
        if (selecting) {
            Column(Modifier.weight(1f)) {
                Text(ExplorerText.selected(selectedCount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
                Text(listOfNotNull(selectedSize, ExplorerUiText.selectHint).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onSelectAll, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.selectall")) { Text(ExplorerUiText.selectAll(selectableCount)) }
            return@Row
        }
        Text(
            ExplorerText.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (loading) FieldCircularProgressIndicator(modifier = Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
        if (canSearch) {
            IconButton(onClick = onSearch, modifier = Modifier.testTag("explorer.searchall")) {
                Icon(Icons.Filled.Search, contentDescription = ExplorerUiText.searchEverything, tint = MaterialTheme.colorScheme.primary)
            }
        }
        IconButton(onClick = onDownloads, modifier = Modifier.testTag("explorer.downloads")) {
            BadgedBox(badge = { if (activeDownloads > 0) Badge { Text("$activeDownloads") } }) {
                Icon(Icons.Filled.Download, contentDescription = ExplorerText.downloads, tint = MaterialTheme.colorScheme.primary)
            }
        }
        IconButton(onClick = onReload, modifier = Modifier.testTag("explorer.reload")) {
            Icon(Icons.Filled.Refresh, contentDescription = ExplorerText.reload, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Toolbar(search: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().testTag("explorer.toolbar")) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            search()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrganiseCard(state: ExplorerState) {
    val taxonomies = state.taxonomies
    val statuses = ExplorerPortal.statusOptions
    if (taxonomies.size <= 1 && statuses.isEmpty()) return
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.large)
            .padding(14.dp)
            .testTag("explorer.organise"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (taxonomies.size > 1) {
            Text(ExplorerUiText.organiseBy.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                taxonomies.forEach { t ->
                    FilterChip(
                        selected = t.id == state.activeTaxonomy?.id,
                        onClick = { state.open(t.path) },
                        label = { Text(t.name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.taxonomy.${t.id}"),
                    )
                }
            }
            Text(
                state.activeTaxonomy?.description?.takeIf { it.isNotBlank() } ?: ExplorerUiText.organiseHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("explorer.organise.about"),
            )
        }
        if (statuses.isNotEmpty()) {
            Text(ExplorerUiText.reviewStatus.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                statuses.forEach { (value, label) ->
                    FilterChip(
                        selected = value == state.status,
                        onClick = { state.changeStatus(value) },
                        label = { Text(label) },
                        modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.status.$value"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderCard(state: ExplorerState, size: SizeLoad?, visit: ExplorerVisit?, showAll: Boolean, onToggleAll: () -> Unit, modifier: Modifier) {
    val info: ExplorerInfo? = state.info
    val fields = info?.fields.orEmpty().filter { it.value.isNotBlank() }
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.large)
            .padding(14.dp)
            .testTag("explorer.folder.card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(Modifier.fillMaxWidth().testTag("explorer.crumbs"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            state.crumbs.dropLast(1).forEachIndexed { index, crumb ->
                if (index > 0) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp).align(Alignment.CenterVertically))
                Text(
                    crumb.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterVertically).heightIn(min = 40.dp).clickable(role = Role.Button) { state.open(crumb.path) }.padding(vertical = 10.dp, horizontal = 2.dp),
                )
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info?.title?.ifBlank { null } ?: state.folderName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
            val line = when (size) {
                is SizeLoad.Known -> ExplorerUiText.filesSize(size.size.files, size.size.bytes)
                else -> ExplorerText.folderCount(state.folders.size, state.files.size)
            }
            Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically).testTag("explorer.count"))
        }
        val total = state.total
        if (total != null && total > state.entries.size) {
            Text(ExplorerText.showing(state.entries.size, total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NewSinceChip(visit)
        if (fields.isNotEmpty()) {
            if (showAll) {
                RecordInfoTable(info!!)
            } else {
                FlowRow(Modifier.fillMaxWidth().testTag("explorer.details"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fields.take(4).forEach { f ->
                        Column(Modifier.width(150.dp)) {
                            Text(f.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(f.value, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (fields.size > 4 || showAll) {
                TextButton(onClick = onToggleAll, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.details.all")) {
                    Text(if (showAll) ExplorerUiText.showFewer else ExplorerUiText.showAllFields(fields.size))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadCard(
    size: ExplorerSize?,
    include: Set<String>,
    onInclude: (String) -> Unit,
    online: Boolean,
    ready: Boolean,
    large: Boolean,
    onReport: () -> Unit,
    onDownload: () -> Unit,
    onTranscripts: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.large)
            .padding(14.dp)
            .testTag("explorer.download.card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ExplorerUiText.download, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
            size?.let { s ->
                ExplorerBytes.format(includedSize(s, include).bytes.takeIf { it > 0 })?.let {
                    Text(ExplorerUiText.selectedSize(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("explorer.download.selected"))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("explorer.include")) {
            INCLUDE_ORDER.forEach { kind ->
                val on = kind.token in include
                val stats: ExplorerTypeStats? = size?.byKind?.get(kind)
                val bytes = stats?.bytes?.takeIf { it > 0 && kind in setOf(ExplorerKind.IMAGES, ExplorerKind.VIDEOS, ExplorerKind.AUDIOS) }?.let { ExplorerBytes.format(it) }
                FilterChip(
                    selected = on,
                    onClick = { onInclude(kind.token) },
                    label = { Text(listOfNotNull(if (on) "✓ ${kind.label}" else kind.label, bytes).joinToString(" ")) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
                    modifier = Modifier.heightIn(min = 40.dp).semantics { role = Role.Checkbox; selected = on }.testTag("explorer.include.${kind.token}"),
                )
            }
        }
        val report: @Composable (Modifier) -> Unit = { m ->
            OutlinedButton(onClick = onReport, enabled = online && ready, modifier = m.heightIn(min = 48.dp).testTag("explorer.report")) { Text(ExplorerUiText.report, maxLines = if (large) 3 else 1, overflow = TextOverflow.Ellipsis) }
        }
        val folder: @Composable (Modifier) -> Unit = { m ->
            Button(onClick = onDownload, enabled = online && ready && include.isNotEmpty(), modifier = m.heightIn(min = 48.dp).testTag("explorer.download")) {
                Text(ExplorerUiText.downloadFolder, maxLines = if (large) 3 else 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (large) {
            folder(Modifier.fillMaxWidth())
            report(Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                report(Modifier.weight(1f))
                folder(Modifier.weight(1f))
            }
        }
        if (onTranscripts != null) {
            TextButton(onClick = onTranscripts, enabled = online && ready, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.download.transcripts")) { Text(ExplorerUiText.allTranscripts) }
        }
    }
}

@Composable
private fun Tabs(tab: ExplorerTab, files: Int, tables: Int?, onTab: (ExplorerTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium).border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.medium).padding(3.dp),
    ) {
        listOf(
            Triple(ExplorerTab.FILES, "${ExplorerUiText.files} $files", "explorer.tab.files"),
            Triple(ExplorerTab.TABLES, listOfNotNull(ExplorerText.tables, tables?.toString()).joinToString(" "), "explorer.tables.toggle"),
            Triple(ExplorerTab.TRANSCRIPTS, ExplorerText.transcripts, "data.transcripts.toggle"),
        ).forEach { (key, label, tag) ->
            val on = key == tab
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.small)
                    .clickable { onTab(key) }
                    .semantics { role = Role.Tab; selected = on }
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun KindPills(files: List<ExplorerEntry>, current: String?, onPick: (String?) -> Unit) {
    val counts = files.groupingBy { ExplorerKind.ofMediaType(it.mediaType) }.eachCount()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = current == null, onClick = { onPick(null) }, label = { Text("${ExplorerUiText.all} ${files.size}") }, modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.kind.all"))
        listOf(ExplorerKind.VIDEOS, ExplorerKind.IMAGES, ExplorerKind.AUDIOS, ExplorerKind.DOCUMENTS, ExplorerKind.OTHER).forEach { kind ->
            val n = counts[kind] ?: 0
            if (n > 0) FilterChip(selected = current == kind.token, onClick = { onPick(kind.token) }, label = { Text("${kind.label} $n") }, modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.kind.${kind.token}"))
        }
    }
}

@Composable
private fun SelectBar(count: Int, breakdown: String, size: String?, online: Boolean, large: Boolean, onShare: () -> Unit, onDownload: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().testTag("explorer.selectbar")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (breakdown.isNotBlank()) Text(breakdown, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("explorer.select.breakdown"))
            if (!online) Text(ExplorerText.offline, style = MaterialTheme.typography.labelSmall, color = explorerWarnTone().content)
            val share: @Composable (Modifier) -> Unit = { m ->
                OutlinedButton(onClick = onShare, enabled = online && count > 0, modifier = m.heightIn(min = 48.dp).testTag("explorer.select.share")) { Text(ExplorerUiText.share) }
            }
            val download: @Composable (Modifier) -> Unit = { m ->
                Button(onClick = onDownload, enabled = online && count > 0, modifier = m.heightIn(min = 48.dp).testTag("explorer.select.download")) {
                    Text(ExplorerUiText.downloadN(count, size), maxLines = if (large) 3 else 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (large) {
                download(Modifier.fillMaxWidth())
                share(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    share(Modifier.weight(1f))
                    download(Modifier.weight(2f))
                }
            }
        }
    }
}

@Composable
private fun Restricted() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 40.dp).testTag("explorer.restricted"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Text(ExplorerText.restrictedTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(ExplorerText.restrictedBody, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

internal fun mediaTypeOf(name: String): String? {
    val mime = com.fieldrepository.app.explorer.data.ExplorerEngine.mimeFor(name)
    return when {
        mime.startsWith("video/") -> "VIDEO"
        mime.startsWith("audio/") -> "AUDIO"
        mime.startsWith("image/") -> "IMAGE"
        mime == "application/pdf" -> "PDF"
        else -> null
    }
}
