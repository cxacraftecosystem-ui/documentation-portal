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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import com.fieldrepository.app.explorer.data.ExplorerFilters
import com.fieldrepository.app.explorer.data.ExplorerFilterKey
import androidx.compose.runtime.compositionLocalOf
import com.fieldrepository.app.explorer.data.ExplorerFacetKeys
import com.fieldrepository.app.explorer.data.ExplorerFacets
import com.fieldrepository.app.explorer.data.ExplorerFilterApi
import com.fieldrepository.app.explorer.data.ExplorerFilterApis
import com.fieldrepository.app.explorer.data.ExplorerViewFilters
import androidx.compose.runtime.rememberUpdatedState
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
import com.fieldrepository.app.explorer.data.ExplorerSortMemory
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

val LocalExplorerFilters = compositionLocalOf { ExplorerFilters() }

private const val ROOMY_CONTENT_DP = 640f

val LocalExplorerPeerSearch = compositionLocalOf<(@Composable (q: String) -> Unit)?> { null }

private sealed interface DownloadTarget {
    data class Folder(val path: String, val name: String) : DownloadTarget
    data object Selection : DownloadTarget
}

private class Pending(val title: String, val plan: ExplorerPreflightPlan, val start: (wifiOnly: Boolean, include: String?) -> Unit, val replan: () -> ExplorerPreflightPlan)

private class Viewing(val entries: List<ExplorerEntry>, val index: Int, val startSeconds: Double = 0.0, val total: Int = entries.size, val find: String = "")

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
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val owner = remember { ExplorerPortal.userId(context) }
    return remember(ExplorerIdentity(api)) {
        ExplorerState(
            api = api,
            scope = scope,
            cache = ExplorerCache(java.io.File(context.filesDir, ExplorerCache.DIR), owner),
            rootName = ExplorerPortal.ROOT_NAME,
            defaultStatus = ExplorerPortal.defaultStatus,
            statusParamOf = ExplorerPortal::statusParam,
            online = { ExplorerNetwork.now(context).online },
            onPlace = { ExplorerPlace.write(context, owner, it) },
            sortStore = ExplorerSortMemory(context, owner),
        )
    }
}

@Composable
fun rememberDataExplorerState(): ExplorerState {
    val app = LocalContext.current.applicationContext
    val api = remember { ExplorerEnvironment.api(app) }
    return rememberExplorerState(api)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    onError: (String) -> Unit = onMessage,
    onOpenMedia: ((mediaId: String) -> Unit)? = null,
    search: @Composable (onOpen: (recordType: String, recordId: String, title: String) -> Unit) -> Unit = {},
    api: ExplorerApi? = null,
    startPath: String? = null,
    searchScreen: (@Composable (query: String, onOpenInData: (type: String, id: String, title: String) -> Unit, onPlay: (mediaId: String, title: String, mediaType: String?, startSeconds: Double) -> Unit, onClose: () -> Unit) -> Unit)? = null,
    onRecordNow: ((folderPath: String, folderName: String) -> Unit)? = null,
    onOpenSection: ((artisanId: String, sectionKey: String) -> Unit)? = null,
    onOpenRecord: ((kind: String, id: String) -> Unit)? = null,
    more: ExplorerMore? = null,
    held: ExplorerState? = null,
    onPathChange: (String) -> Unit = {},
    onOpenRemote: ((type: String, id: String) -> Unit)? = null,
    filterApi: ExplorerFilterApi? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val explorerApi = remember(ExplorerIdentity(api), ExplorerIdentity(held)) { held?.api ?: api ?: ExplorerEnvironment.api(app) }
    val extras = remember(ExplorerIdentity(more)) { more ?: ExplorerMoreApi.of(app) }
    val filtersApi = remember(ExplorerIdentity(filterApi), ExplorerIdentity(api)) { filterApi ?: ExplorerFilterApis.override ?: if (api != null) ExplorerFilterApis.NONE else ExplorerFilterApis.of(app) }
    val state = held ?: rememberExplorerState(explorerApi)
    val scope = rememberCoroutineScope()
    val owner = remember { ExplorerPortal.userId(app) }
    val tables = remember(ExplorerIdentity(explorerApi)) { ExplorerTablesState(explorerApi, scope, owner) }
    val link by produceState(initialValue = ExplorerNetwork.now(app)) { ExplorerNetwork.watch(app).collect { value = it } }
    val jobs by ExplorerDownloads.store(app).jobs.collectAsState()
    val tap by ExplorerDownloadsTap.requests.collectAsState()
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ExplorerEntry?>(null) }
    var media by remember { mutableStateOf<Viewing?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var reportOpen by rememberSaveable { mutableStateOf(false) }
    var sortOpen by rememberSaveable { mutableStateOf(false) }
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
    var resume by rememberSaveable(stateSaver = ExplorerResume.saver) { mutableStateOf<ExplorerResume?>(null) }
    var keptIndex by rememberSaveable { mutableIntStateOf(0) }
    var keptOffset by rememberSaveable { mutableIntStateOf(0) }
    val entryScroll = remember { (keptIndex to keptOffset).takeIf { state.tree == null && it.first > 0 } }
    var paneWidth by rememberSaveable { mutableFloatStateOf(ExplorerPaneWidth.read(app) ?: ExplorerPaneWidth.DEFAULT) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ExplorerFolder.write(app, uri)
            pending = pending?.let { p -> Pending(p.title, p.replan(), p.start, p.replan) }
        }
    }
    val languages by produceState(initialValue = emptyList<Pair<String, String>>(), ExplorerIdentity(explorerApi)) {
        value = attemptOrNull { explorerApi.languages().languages.map { it.code to it.label.ifBlank { it.code } } }.orEmpty()
    }

    var facets by remember { mutableStateOf<ExplorerFacets?>(null) }
    val filterQuery = state.filterQuery
    LaunchedEffect(ExplorerIdentity(filtersApi), filterQuery) {
        attemptOrNull { filtersApi.filters(null, filterQuery) }?.let { got ->
            facets = got
            val names = got.facets.flatMap { f -> f.options.map { it.value to it.label } }.toMap()
            if (names.isNotEmpty()) state.changeFilters(state.filters.withLabels(names))
        }
    }
    LaunchedEffect(filterQuery) { tables.filter(filterQuery) }
    LaunchedEffect(state.appliedFilters) {
        val names = ExplorerFacetKeys.labels(state.appliedFilters)
        if (names.isNotEmpty()) state.changeFilters(state.filters.withLabels(names))
    }
    val currentFacets by rememberUpdatedState(facets)
    val optionSource: ExplorerOptionSource = remember(ExplorerIdentity(filtersApi)) {
        val source: ExplorerOptionSource = { key, _ ->
            val have = currentFacets ?: filtersApi.filters(null, state.filterQuery).also { facets = it }
            ExplorerFacetKeys.options(have, key)
        }
        source
    }
    val filterBar: @Composable () -> Unit = {
        ExplorerFilterBar(
            filters = state.filters,
            keys = ExplorerFacetKeys.keys(facets, state.filters),
            source = optionSource,
            onChange = { state.changeFilters(it) },
        )
    }
    val statsKey: (String) -> String = { at -> at + "|" + state.filters.key() }

    LaunchedEffect(state.recordToOpen) {
        val record = state.recordToOpen ?: return@LaunchedEffect
        val open = onOpenRecord
        state.recordHandled(open != null)
        open?.invoke(record.kind, record.id)
    }
    LaunchedEffect(state) {
        state.start(startPath?.takeIf { it.isNotBlank() } ?: ExplorerPlace.read(app, owner), resume)
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
            attemptOrNull { ExplorerDownloads.refreshSystem(app) }
            delay(1_000)
        }
    }
    LaunchedEffect(state.path, state.tab, state.status, state.filters) {
        if (state.started && state.path.isNotEmpty()) {
            resume = ExplorerResume(state.path, state.tab, state.status, state.filters)
            onPathChange(state.path)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) -> keptIndex = index; keptOffset = offset }
    }
    LaunchedEffect(state.path) {
        if (lastPath != null && lastPath != state.path) {
            listState.scrollToItem(0)
            kindFilter = null
            showAllInfo = false
        }
        lastPath = state.path
    }
    LaunchedEffect(state.unfiled, state.error) {
        if (state.unfiled != null || state.error != null) listState.animateScrollToItem(0)
    }
    val loaded = state.tree != null
    LaunchedEffect(loaded) {
        if (loaded && entryScroll != null) listState.scrollToItem(entryScroll.first, entryScroll.second)
    }
    LaunchedEffect(state.path, loaded, filterQuery) {
        if (!loaded) return@LaunchedEffect
        val at = state.path
        val sk = statsKey(at)
        if (stats[sk] !is SizeLoad.Known && (state.crumbs.size >= 3 || state.info != null)) {
            stats[sk] = SizeLoad.Loading
            stats[sk] = try {
                SizeLoad.Known(ExplorerSize.of(explorerApi.stats(at, filters = filterQuery)))
            } catch (cancelled: CancellationException) {
                stats.remove(sk)
                throw cancelled
            } catch (failure: Throwable) {
                SizeLoad.Unknown
            }
        }
        if (visits[at] == null) attemptOrNull { extras.visit(ExplorerVisitBody(at)) }?.let { visits[at] = it }
        if (isWorkshopFolder(at) && overviews[at] == null) {
            attemptOrNull { extras.overview(at, state.statusParam) }?.let { overviews[at] = it }
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
            selectionSize = attemptOrNull { explorerApi.selectionStats(state.selectionStatsBody()).bytes }
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
                totalBytes = ExplorerSaver.totalBytes(app),
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
                onError(ExplorerText.transcriptFailed)
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
                onError(ExplorerText.fileFailed)
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
                items = listOf(ExplorerItem(path = "", url = ExplorerPaths.withQueries(ExplorerPaths.transcriptBundle(state.path), filterQuery))),
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
        val known = (stats[statsKey(path)] as? SizeLoad.Known)?.size?.let { includedSize(it, include) }
        val chosen = includeParam(include)
        val narrowed = state.filters.narrowing
        val filterParams = filterQuery
        preflightOr(name, known, fileName) { wifiOnly, smaller ->
            begin(
                ExplorerRequest(
                    title = name,
                    fileName = fileName,
                    kind = ExplorerJobKind.FOLDER,
                    sources = listOf(ExplorerSource(path, include = smaller ?: chosen, manifestUrl = if (narrowed) ExplorerPaths.manifest(path, smaller ?: chosen, filterParams) else null)),
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
                items = listOf(ExplorerItem(path = "", url = ExplorerMoreApi.reportUrl(state.path, options, tables.query.ifBlank { null }, file, status, filters = filterQuery))),
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
                items = listOf(ExplorerItem(path = "", url = ExplorerMoreApi.reportUrl(state.path, options, tables.query.ifBlank { null }, file, status, pack = true, filters = filterQuery))),
            ),
        )
        reportOpen = false
    }

    val openRecordOf: ((ExplorerEntry) -> Unit)? = onOpenMedia?.let { open ->
        { entry: ExplorerEntry ->
            entry.mediaId?.let { id ->
                media = null
                viewing = null
                open(id)
            }
        }
    }

    fun showMedia(entry: ExplorerEntry, seconds: Double?, find: String = "") {
        if (entry.mediaType in setOf("VIDEO", "AUDIO", "IMAGE")) media = Viewing(listOf(entry), 0, seconds ?: 0.0, find = find) else viewing = entry
    }

    suspend fun previewMedia(mediaId: String, seconds: Double?, find: String = "", kind: String? = null) {
        val known = state.files.firstOrNull { it.mediaId == mediaId }
        val entry = known ?: run {
            val got = attemptOrNull { extras.goMedia(mediaId) }
            val name = got?.fileName ?: mediaId
            ExplorerEntry(name = name, path = mediaId, mediaId = mediaId, mediaType = ExplorerViewLinks.mediaType(got?.mediaType ?: kind, name), transcriptAvailable = true)
        }
        showMedia(entry, seconds, find)
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

    suspend fun followGo(parts: List<String>, handOff: () -> Unit) {
        when (parts.firstOrNull()) {
            "media" -> {
                val id = parts.getOrNull(1) ?: return
                val got = extras.goMedia(id)
                val name = got.fileName ?: id
                saveEntry(ExplorerEntry(name = name, path = id, mediaId = id, mediaType = ExplorerViewLinks.mediaType(got.mediaType, name)))
            }
            "open" -> {
                val kind = parts.getOrNull(1) ?: return
                val id = parts.getOrNull(2) ?: return
                val got = extras.goOpen(kind, id)
                when {
                    got.kind == "media" && onOpenMedia != null -> { handOff(); onOpenMedia(got.id.ifBlank { id }) }
                    got.kind == "media" -> previewMedia(got.id.ifBlank { id }, null)
                    onOpenRecord != null -> { handOff(); onOpenRecord(got.kind.ifBlank { kind }, got.id.ifBlank { id }) }
                    else -> state.locate(got.kind.ifBlank { kind }, got.id.ifBlank { id }, "").join()
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
            else -> onError(ExplorerUiText.linkUnknown)
        }
    }

    fun showView(path: String, view: String?, sheet: String?, q: String?) {
        viewQuery = q?.takeIf { it.isNotBlank() }?.let { path to it }
        state.tab = when (view) {
            "tables" -> ExplorerTab.TABLES
            "transcripts" -> ExplorerTab.TRANSCRIPTS
            else -> ExplorerTab.FILES
        }
        if (view == "tables") q?.takeIf { it.isNotBlank() }?.let { query -> scope.launch { delay(50); tables.show(path); tables.search(query) } }
        sheet?.takeIf { it.isNotBlank() }?.let { tables.pick(it) }
    }

    fun openView(view: ExplorerView) {
        reportOpen = false
        state.changeFilters(ExplorerViewFilters.of(view.state.filters, state.filters))
        state.open(view.path)
        showView(view.path, view.state.view, view.state.sheet, view.state.q)
    }

    val linkRequest by ExplorerViewLinks.requests.collectAsState()
    var linkSave by remember { mutableStateOf<ExplorerLinkRequest?>(null) }
    linkSave?.let { pending ->
        ExplorerLinkSaveConfirm(
            onConfirm = {
                linkSave = null
                scope.launch {
                    try {
                        if (pending.go.isNotEmpty()) followGo(pending.go) {} else pending.bundle?.let { startBundle(it) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        onError(ExplorerUiText.linkFailed)
                    }
                }
            },
            onCancel = { linkSave = null },
        )
    }
    LaunchedEffect(linkRequest) {
        val request = linkRequest ?: return@LaunchedEffect
        if (explorerLinkSavesFiles(request)) {
            ExplorerViewLinks.consume(request)
            linkSave = request
            return@LaunchedEffect
        }
        ExplorerViewLinks.serve(request, onFailure = { onError(ExplorerUiText.linkFailed) }) { r ->
            val id = r.viewId
            when {
                r.go.isNotEmpty() -> followGo(r.go) { ExplorerViewLinks.consume(r) }
                r.bundle != null -> startBundle(r.bundle)
                r.preview != null -> {
                    r.path?.let { state.open(it) }
                    previewMedia(r.preview, r.seconds, r.hit.orEmpty(), r.kind)
                }
                id != null -> {
                    val view = try {
                        extras.view(id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        onError(ExplorerViewLinks.viewFailure(failure))
                        null
                    }
                    if (view != null) openView(view) else r.path?.let { state.open(it) }
                }
                r.path != null -> {
                    if (r.filters.isNotEmpty()) state.changeFilters(ExplorerViewFilters.of(r.filters, state.filters))
                    state.open(r.path)
                    if (r.view != null || r.sheet != null || r.q != null) showView(r.path, r.view, r.sheet, r.q)
                }
            }
        }
    }
    fun tableCsv(sheet: String) {
        begin(
            ExplorerRequest(
                title = "${state.folderName} · $sheet",
                fileName = Naming.exportFilename("Dataset", "Export", "csv", withTime = true, subject = "${state.folderName} $sheet"),
                kind = ExplorerJobKind.REPORT,
                single = true,
                mimeType = "text/csv",
                items = listOf(ExplorerItem(path = "", url = ExplorerPaths.withQueries(ExplorerPaths.reportCsv(state.path, sheet), filterQuery))),
            ),
        )
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
            state.tab == ExplorerTab.TABLES -> state.tab = ExplorerTab.FILES
            state.canGoUp && state.goUp() -> Unit
            else -> onBack()
        }
    }

    val headline = jobs.firstOrNull { (it.active || it.state == ExplorerJobState.PAUSED) && it.request.kind != ExplorerJobKind.FILE }
    val finished = jobs.filter {
        !it.active && it.state != ExplorerJobState.PAUSED && it.state != ExplorerJobState.CANCELLED &&
            (it.request.kind != ExplorerJobKind.FILE || it.state == ExplorerJobState.FAILED) && it.createdAt >= openedAt && it.id !in hidden
    }.take(3).takeIf { headline == null }.orEmpty()
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
    val size = stats[statsKey(state.path)]
    val large = LocalDensity.current.fontScale >= 1.5f
    val network = if (link.metered) ExplorerUiText.mobileData else ExplorerUiText.wifi
    fun fileQueryNow(): String? = viewQuery?.takeIf { it.first == state.path && state.tab == ExplorerTab.FILES }?.second
    fun visibleFiles(): List<ExplorerEntry> {
        val query = fileQueryNow()
        val kind = kindFilter
        return state.files.filter { f ->
            (kind == null || ExplorerKind.ofMediaType(f.mediaType).token == kind) &&
                (query == null || f.name.contains(query, ignoreCase = true))
        }
    }
    val shownFiles = visibleFiles()

    val audio by com.fieldrepository.app.explorer.data.ExplorerAudio.state.collectAsState()
    val playingLine: (ExplorerEntry) -> String? = { file ->
        val item = audio.item
        if (item != null && file.mediaId != null && item.mediaId == file.mediaId) {
            ExplorerUiText.playing(com.fieldrepository.app.explorer.data.ExplorerAudioLines.clock(audio.positionMs), audio.durationMs.takeIf { it > 0 }?.let { com.fieldrepository.app.explorer.data.ExplorerAudioLines.clock(it) })
        } else {
            null
        }
    }

    val fileRow: @Composable (ExplorerEntry, Boolean, Boolean, Boolean) -> Unit = { file, first, last, cell ->
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
                    file.mediaType in ExplorerPaths.PREVIEWABLE -> play(state.files, file)
                    mediaId != null && onOpenMedia != null -> onOpenMedia(mediaId)
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
            first = first,
            last = last,
            cell = cell,
            playing = playingLine(file),
            extra = {
                val mediaId = file.mediaId
                if (mediaId != null && (file.transcriptFiles.isNotEmpty() || file.transcriptAvailable)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
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

    val known = (size as? SizeLoad.Known)?.size
    val folderCard: @Composable (Modifier) -> Unit = { m ->
        FolderCard(
            state = state,
            size = size,
            visit = visits[state.path].takeIf { overviews[state.path] == null },
            showAll = showAllInfo,
            onToggleAll = { showAllInfo = !showAllInfo },
            large = large,
            modifier = m,
        )
    }
    val downloadCard: @Composable (Modifier) -> Unit = { m ->
        DownloadCard(
            size = known,
            include = include,
            onInclude = { token -> include = if (token in include) include - token else include + token },
            online = link.online,
            ready = state.tree != null,
            large = large,
            onReport = { reportOpen = true; tables.show(state.path) },
            onDownload = ::downloadFolder,
            modifier = m,
        )
    }
    val jobCards: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            headline?.let { job ->
                ProgressCard(
                    job,
                    network = network,
                    large = large,
                    onPause = { ExplorerDownloads.pause(app, job.id) },
                    onResume = { ExplorerDownloads.resume(app, job.id) },
                    onCancel = { ExplorerDownloads.cancel(app, job.id) },
                )
                RecentFilesCard(job, onAll = { allFilesFor = job.id })
                Text(
                    ExplorerText.savingTo(ExplorerSaver.location(job.request.fileName)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("explorer.progress.where"),
                )
            }
            finished.forEach { job ->
                ResultCard(
                    job,
                    onOpen = { if (!ExplorerOpen.open(context, job)) onMessage(ExplorerText.previewFailed) },
                    onRetry = { ExplorerDownloads.retry(app, job.id); hidden = hidden + job.id },
                )
            }
        }
    }

    fun ExplorerRowPlan.block(key: String, content: @Composable () -> Unit) {
        item(key) { Box(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { content() } }
    }

    val content: ExplorerRowPlan.(Boolean, Boolean) -> Unit = { wide, roomy ->
        if (state.locating != null) block("locating") { LoadingLine(ExplorerText.preparing) }
        state.unfiled?.let { text -> block("unfiled") { Notice(text, warn = true, tag = "explorer.unfiled") } }
        if (!link.online && state.tree != null) {
            block("offline") { OfflineCard(state.cachedAt?.let { ExplorerUiText.ago((System.currentTimeMillis() - it) / 60_000) }) }
        } else {
            state.cachedNote?.let { text -> block("cached") { CachedNote(text) } }
        }
        val error = state.error
        when {
            error != null && state.tree == null -> block("error") { LoadFailedCard(error + state.path, onRetry = { state.dismissError(); state.reload() }) }
            error != null -> block("error") { StatusCard(ExplorerStatus.FAILED, error, action = ExplorerText.retry, tag = "explorer.error") { state.dismissError(); state.reload() } }
        }
        if (state.tree == null && error == null) {
            block("loading") { FolderSkeleton() }
        }
        if (state.tree != null) {
            if (!state.selecting) {
                if (!roomy) {
                    block("folder") { folderCard(Modifier.fillMaxWidth()) }
                    block("download") { downloadCard(Modifier.fillMaxWidth()) }
                } else {
                    block("cards") {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            folderCard(Modifier.weight(1f).fillMaxHeight())
                            downloadCard(Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
                if (headline != null || finished.isNotEmpty()) block("jobs") { jobCards() }
                overviews[state.path]?.let { overview -> block("overview") { OverviewCard(overview, visit = visits[state.path], onOpen = { state.open(it) }, onSection = onOpenSection) } }
                block("tabs") {
                    Tabs(
                        tab = state.tab,
                        files = known?.records ?: state.total ?: state.entries.size,
                        tables = tables.ready?.takeIf { it.path == state.path }?.report?.let { report -> ExplorerTableColumns.visibleSheets(report.sheets, null, false).size }?.takeIf { it > 0 },
                        transcripts = known?.byKind?.get(ExplorerKind.TRANSCRIPTS)?.files,
                        onTab = { state.tab = it },
                    )
                }
            }
            when (state.tab) {
                ExplorerTab.FILES -> {
                    val localOff = !state.showsLocal
                    when {
                        localOff -> block("empty") { FilteredEmptyCard("", localOff = true) { state.changeFilters(state.filters.cleared()) } }
                        state.entries.isEmpty() && state.filters.narrowing -> block("empty") {
                            FilteredEmptyCard(ExplorerFacetKeys.summary(state.appliedFilters, state.filters), localOff = false) { state.changeFilters(state.filters.cleared()) }
                        }
                        state.entries.isEmpty() -> block("empty") { EmptyFolderCard(state.folderName, onRecordNow?.let { record -> { record(state.path, state.folderName) } }) }
                    }
                    if (state.filters.includesOther) {
                        block("peer") { PeerResults(filtersApi, state.path, state.filters, onOpenRemote) }
                    }
                    val fileQuery = fileQueryNow()
                    if (fileQuery != null) {
                        block("filequery") {
                            ExplorerPill(
                                text = "${ExplorerUiText.filteredBy(fileQuery)}  ✕",
                                selected = true,
                                onClick = { viewQuery = null },
                                modifier = Modifier.testTag("explorer.filequery"),
                            )
                        }
                    }
                    if (state.selecting) {
                        block("kinds") { KindPills(state.files, kindFilter) { kindFilter = it } }
                    }
                    if (state.sortKeys.isNotEmpty() && state.entries.isNotEmpty()) {
                        block("sort") { ExplorerSortLine(state.sort, state.sortKeys, onClick = { sortOpen = true }) }
                    }
                    val folders = if (localOff) emptyList() else state.folders
                    val listed = if (localOff) emptyList() else visibleFiles()
                    val more = !localOff && state.cursor != null
                    if (!wide) {
                        val total = folders.size + listed.size
                        itemsIndexed(folders, key = { _, f -> f.key }) { index, folder ->
                            Box(Modifier.fillMaxWidth().padding(bottom = if (index == total - 1) 12.dp else 0.dp)) {
                                FolderRow(
                                    folder = folder,
                                    selecting = state.selecting,
                                    selected = folder.key in state.selected,
                                    onOpen = { state.open(folder.path) },
                                    onLongPress = { state.beginSelect(folder) },
                                    onToggle = { state.toggleSelect(folder) },
                                    first = index == 0,
                                    last = index == total - 1,
                                )
                            }
                        }
                        itemsIndexed(listed, key = { _, f -> f.key }) { index, file ->
                            val at = folders.size + index
                            Box(Modifier.fillMaxWidth().padding(bottom = if (at == total - 1) 12.dp else 0.dp)) {
                                fileRow(file, at == 0, at == total - 1, true)
                            }
                        }
                    } else {
                        itemsIndexed(folders, key = { _, f -> "content:" + f.key }) { index, folder ->
                            Box(Modifier.fillMaxWidth().padding(bottom = if (index == folders.lastIndex) 12.dp else 0.dp)) {
                                FolderRow(
                                    folder = folder,
                                    selecting = state.selecting,
                                    selected = folder.key in state.selected,
                                    onOpen = { state.open(folder.path) },
                                    onLongPress = { state.beginSelect(folder) },
                                    onToggle = { state.toggleSelect(folder) },
                                    first = index == 0,
                                    last = index == folders.lastIndex,
                                    tag = "explorer.content.folder.${folder.path}",
                                )
                            }
                        }
                        val pairs = fileRowGroups(listed, if (roomy) 2 else 1) { f -> f.content != null || f.key in state.openText || f.key in state.openTranscripts }
                        itemsIndexed(pairs, key = { _, pair -> pair.joinToString("|") { it.key } }) { index, pair ->
                            val first = index == 0
                            val last = index == pairs.lastIndex
                            Box(Modifier.fillMaxWidth().padding(bottom = if (last) 12.dp else 0.dp)) {
                                Row(Modifier.fillMaxWidth().explorerCell(first, last), verticalAlignment = Alignment.CenterVertically) {
                                    pair.forEach { file -> Box(Modifier.weight(1f)) { fileRow(file, first, last, false) } }
                                }
                            }
                        }
                    }
                    val total = state.total.takeIf { !localOff }
                    if (total != null && total > state.entries.size) {
                        block("shown") {
                            Text(
                                ExplorerText.showing(state.entries.size, total),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().testTag("explorer.shown"),
                            )
                        }
                    }
                    if (more) {
                        block("more") {
                            LaunchedEffect(state.entries.size) { state.loadMore() }
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                ExplorerSecondaryButton(
                                    text = if (state.loadingMore) ExplorerText.preparing else ExplorerText.loadMore,
                                    onClick = state::loadMore,
                                    enabled = !state.loadingMore,
                                    modifier = Modifier.testTag("explorer.more"),
                                )
                            }
                        }
                    }
                }
                ExplorerTab.TABLES -> if (!state.showsLocal) block("tables") { FilteredEmptyCard("", localOff = true) { state.changeFilters(state.filters.cleared()) } } else block("tables") {
                    ExplorerTables(
                        state = tables,
                        online = link.online,
                        onCsv = ::tableCsv,
                        onReport = { reportOpen = true },
                    )
                }
                ExplorerTab.TRANSCRIPTS -> if (!state.showsLocal) block("transcripts") { FilteredEmptyCard("", localOff = true) { state.changeFilters(state.filters.cleared()) } } else if (ExplorerFilters.TRANSCRIPT_NO in state.filters[ExplorerFilterKey.HAS_TRANSCRIPT]) block("transcripts") {
                    FilteredEmptyCard(ExplorerFacetKeys.summary(state.appliedFilters, state.filters), localOff = false) { state.changeFilters(state.filters.cleared()) }
                } else block("transcripts") {
                    ExplorerTranscriptsTab(
                        path = state.path,
                        filterQuery = filterQuery,
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

    val searchSlot: @Composable () -> Unit = { search { type, id, title -> state.locate(type, id, title) } }
    val refresh: () -> Unit = { state.reload(); tables.reload() }

    val peerSearch: @Composable (String) -> Unit = { q -> PeerResults(filtersApi, state.path, state.filters, onOpenRemote, q = q) }
    CompositionLocalProvider(
        LocalExplorerSearchAll provides searchScreen?.let { { q: String -> searchQuery = q } },
        LocalExplorerFilters provides state.filters,
        LocalExplorerPeerSearch provides peerSearch,
    ) {
        BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("explorer.screen")) {
            val wide = maxWidth >= 840.dp
            val screenDp = maxWidth.value
            val tablesScreen = !wide && state.tab == ExplorerTab.TABLES && state.tree != null && !state.selecting
            Column(Modifier.fillMaxSize()) {
                if (state.restricted) {
                    TopBar(selecting = false, selectedCount = 0, selectedSize = null, selectableCount = 0, loading = state.loading, activeDownloads = activeCount, onBack = onBack, onDownloads = { showDownloads = true }, onSelectAll = {})
                    Restricted()
                    return@Column
                }
                if (tablesScreen) {
                    TablesScreen(
                        folder = state.folderName,
                        tables = tables,
                        online = link.online,
                        onBack = { state.tab = ExplorerTab.FILES },
                        onCsv = ::tableCsv,
                        onReport = { reportOpen = true },
                        filterBar = filterBar,
                        localOff = !state.showsLocal,
                        onClearFilters = { state.changeFilters(state.filters.cleared()) },
                        modifier = Modifier.weight(1f),
                    )
                } else if (!wide) {
                    TopBar(
                        selecting = state.selecting,
                        selectedCount = state.selected.size,
                        selectedSize = ExplorerBytes.format((selectionSize ?: state.selectedBytes).takeIf { it > 0 }),
                        selectableCount = state.entries.count { ExplorerState.selectable(it) },
                        loading = state.loading,
                        activeDownloads = activeCount,
                        onBack = { if (state.selecting) state.endSelect() else if (!state.canGoUp || !state.goUp()) onBack() },
                        onDownloads = { showDownloads = true },
                        onSelectAll = { state.selectAll(if (kindFilter == null) state.entries else shownFiles) },
                    )
                    val phoneRows = ExplorerRowPlan.build {
                        if (!state.selecting) {
                            block("toolbar") { Toolbar(search = searchSlot) }
                            block("filters") { filterBar() }
                            if (hasOrganise(state)) block("organise") { OrganiseCard(state) }
                        }
                        content(false, false)
                    }
                    PullToRefreshBox(isRefreshing = state.loading && state.tree != null, onRefresh = refresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag("explorer.tree"),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (state.selecting) 10.dp else 0.dp, bottom = 24.dp),
                        ) { phoneRows.replay(this) }
                    }
                } else {
                    val pane = ExplorerPaneWidth.clamp(paneWidth, screenDp)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Row(Modifier.fillMaxSize().testTag("explorer.tablet")) {
                            TreePane(
                                state = state,
                                api = explorerApi,
                                activeDownloads = activeCount,
                                onDownloads = { showDownloads = true },
                                onBack = { if (!state.canGoUp || !state.goUp()) onBack() },
                                search = searchSlot,
                                filterBar = filterBar,
                                modifier = Modifier.width(pane.dp).fillMaxHeight().clipToBounds(),
                            )
                            PaneDivider(
                                width = pane,
                                screen = screenDp,
                                onWidth = { paneWidth = it },
                                onSettled = { ExplorerPaneWidth.write(app, paneWidth) },
                            )
                            Column(Modifier.weight(1f).widthIn(min = 0.dp).fillMaxHeight().clipToBounds().testTag("explorer.content")) {
                                if (state.selecting) {
                                    TopBar(
                                        selecting = true,
                                        selectedCount = state.selected.size,
                                        selectedSize = ExplorerBytes.format((selectionSize ?: state.selectedBytes).takeIf { it > 0 }),
                                        selectableCount = state.entries.count { ExplorerState.selectable(it) },
                                        loading = state.loading,
                                        activeDownloads = activeCount,
                                        onBack = { state.endSelect() },
                                        onDownloads = { showDownloads = true },
                                        onSelectAll = { state.selectAll(if (kindFilter == null) state.entries else shownFiles) },
                                    )
                                }
                                val wideRows = ExplorerRowPlan.build { content(true, ExplorerPaneWidth.content(paneWidth, screenDp) >= ROOMY_CONTENT_DP) }
                                PullToRefreshBox(isRefreshing = state.loading && state.tree != null, onRefresh = refresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize().testTag("explorer.tree"),
                                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                                    ) { wideRows.replay(this) }
                                }
                            }
                        }
                    }
                }
                if (media == null && !state.selecting && !tablesScreen) {
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
                } else if (!link.online && state.tree == null) {
                    Text(
                        ExplorerText.offline,
                        style = MaterialTheme.typography.bodySmall,
                        color = explorerWarnTone().content,
                        modifier = Modifier.fillMaxWidth().background(explorerWarnTone().container).padding(horizontal = 16.dp, vertical = 8.dp).testTag("explorer.offline"),
                    )
                }
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

    if (sortOpen && state.sortKeys.isNotEmpty()) {
        ExplorerSortSheet(current = state.sort, keys = state.sortKeys, onPick = { state.changeSort(it) }, onDismiss = { sortOpen = false })
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
            filters = state.filters,
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
            onOpenRecord = openRecordOf?.let { open -> { open(entry) } },
        )
    }

    media?.let { v ->
        ExplorerMediaDialog(
            entries = v.entries,
            start = v.index,
            startSeconds = v.startSeconds,
            total = v.total,
            find = v.find,
            onSave = { saveEntry(it) },
            onDismiss = { media = null },
            onOpenRecord = openRecordOf,
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
                            ?: ExplorerEntry(name = title, path = mediaId, mediaId = mediaId, mediaType = ExplorerViewLinks.mediaType(mediaType, title), transcriptAvailable = true)
                        showMedia(entry, seconds, query.trim())
                    },
                    { searchQuery = null },
                )
            }
        }
    }
}

@Composable
private fun DownloadsButton(count: Int, size: Dp, onClick: () -> Unit) {
    Box(Modifier.padding(top = 6.dp, end = 6.dp)) {
        Box(
            Modifier.size(size)
                .clip(ExplorerShapes.control)
                .background(MaterialTheme.colorScheme.surface, ExplorerShapes.control)
                .border(1.dp, explorerControlLine(), ExplorerShapes.control)
                .clickable(role = Role.Button, onClickLabel = ExplorerText.downloads, onClick = onClick)
                .semantics { contentDescription = if (count > 0) "${ExplorerText.downloads}, $count running" else ExplorerText.downloads }
                .testTag("explorer.downloads"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.field.body, modifier = Modifier.size(18.dp))
        }
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).heightIn(min = 18.dp).widthIn(min = 18.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$count", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
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
    onBack: () -> Unit,
    onDownloads: () -> Unit,
    onSelectAll: () -> Unit,
) {
    if (selecting) {
        val line = if (MaterialTheme.field.isDark) com.fieldrepository.app.ui.FieldPalette.Purple800 else com.fieldrepository.app.ui.FieldPalette.Purple300
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .drawBehind { drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)
                .testTag("explorer.selectbar.top"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp).testTag("explorer.back")) {
                Icon(Icons.Filled.Close, contentDescription = ExplorerUiText.stopSelecting, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
            }
            val large = LocalDensity.current.fontScale >= 1.5f
            Column(Modifier.weight(1f)) {
                Text(ExplorerText.selected(selectedCount), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
                Text(listOfNotNull(selectedSize, ExplorerUiText.selectHint).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (large) ExplorerLinkButton(ExplorerUiText.selectAll(selectableCount), onClick = onSelectAll, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.selectall"))
            }
            if (!large) ExplorerLinkButton(ExplorerUiText.selectAll(selectableCount), onClick = onSelectAll, modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = 4.dp).testTag("explorer.selectall"))
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp).testTag("explorer.back")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = ExplorerText.back, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(26.dp))
        }
        Text(
            ExplorerText.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (loading) FieldCircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        DownloadsButton(activeDownloads, 44.dp, onDownloads)
    }
}

@Composable
private fun Toolbar(search: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("explorer.toolbar"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        search()
    }
}

private fun hasOrganise(state: ExplorerState): Boolean = state.taxonomies.size > 1

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = 0.06.em, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrganiseCard(state: ExplorerState) {
    val taxonomies = state.taxonomies
    Column(
        Modifier.fillMaxWidth().explorerCard().padding(14.dp).testTag("explorer.organise"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (taxonomies.size > 1) {
            Eyebrow(ExplorerUiText.organiseBy)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                taxonomies.forEach { t ->
                    ExplorerPill(
                        text = t.name,
                        selected = t.id == state.activeTaxonomy?.id,
                        onClick = { state.open(t.path) },
                        role = Role.RadioButton,
                        modifier = Modifier.testTag("explorer.taxonomy.${t.id}"),
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
    }
}

@Composable
private fun reviewColor(value: String): Color? {
    val v = value.trim().lowercase()
    return when {
        v.startsWith("approved") -> explorerTone(ExplorerStatus.SAVED)?.accent
        v.startsWith("rejected") -> explorerTone(ExplorerStatus.FAILED)?.accent
        v.startsWith("pending") || v.startsWith("needs") || v.startsWith("in review") -> explorerWarnTone().muted
        else -> null
    }
}

@Composable
private fun DetailCell(label: String, value: String, modifier: Modifier, large: Boolean) {
    val tint = reviewColor(value)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = tint ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = if (tint != null) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = if (large) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderCard(state: ExplorerState, size: SizeLoad?, visit: ExplorerVisit?, showAll: Boolean, onToggleAll: () -> Unit, large: Boolean, modifier: Modifier) {
    val info: ExplorerInfo? = state.info
    val fields = info?.fields.orEmpty().filter { it.value.isNotBlank() }
    Column(
        modifier.explorerCard().padding(14.dp).testTag("explorer.folder.card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val trail = state.crumbs.dropLast(1).filter { it.name.isNotBlank() }.let { all -> if (all.size > 1 && all.first().path.isEmpty()) all.drop(1) else all }
        if (trail.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth().testTag("explorer.crumbs"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                trail.forEachIndexed { index, crumb ->
                    if (index > 0) Text(" › ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        crumb.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(role = Role.Button) { state.open(crumb.path) },
                    )
                }
            }
        }
        val title: @Composable (Modifier) -> Unit = { m ->
            Text(
                info?.title?.ifBlank { null } ?: state.folderName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = m.semantics { heading() },
            )
        }
        val line = listOfNotNull(
            when (size) {
                is SizeLoad.Known -> ExplorerUiText.filesSize(size.size.files, size.size.bytes)
                else -> ExplorerText.folderCount(state.folders.size, state.files.size)
            },
            info?.counts?.tools?.takeIf { it > 0 }?.let { ExplorerBytes.count(it, "tool") },
        ).joinToString(" · ")
        val count: @Composable (Modifier) -> Unit = { m ->
            Text(line, style = if (large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall, color = if (large) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = m.testTag("explorer.count"))
        }
        if (large) {
            title(Modifier)
            count(Modifier)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                title(Modifier.weight(1f))
                count(Modifier)
            }
        }
        val total = state.total
        if (total != null && total > state.entries.size) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(ExplorerText.showing(state.entries.size, total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).testTag("explorer.showing"))
                if (state.cursor != null) {
                    ExplorerLinkButton(
                        if (state.loadingMore) ExplorerText.preparing else ExplorerText.loadMore,
                        onClick = { state.tab = ExplorerTab.FILES; state.loadMore() },
                        modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.showing.more"),
                    )
                }
            }
        }
        NewSinceChip(visit)
        if (fields.isNotEmpty()) {
            if (showAll) {
                RecordInfoTable(info!!)
            } else {
                val shown = fields.take(4)
                Column(Modifier.fillMaxWidth().testTag("explorer.details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (large) {
                        shown.forEach { f -> DetailCell(f.label, f.value, Modifier.fillMaxWidth(), true) }
                    } else {
                        shown.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { f -> DetailCell(f.label, f.value, Modifier.weight(1f), false) }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (fields.size > 4 || showAll) {
                ExplorerLinkButton(
                    if (showAll) ExplorerUiText.showFewer else ExplorerUiText.showAllFields(fields.size),
                    onClick = onToggleAll,
                    modifier = Modifier.testTag("explorer.details.all"),
                )
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
    modifier: Modifier,
) {
    Column(
        modifier.explorerCard().padding(14.dp).testTag("explorer.download.card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ExplorerUiText.download, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).semantics { heading() })
            size?.let { s ->
                ExplorerBytes.format(includedSize(s, include).bytes.takeIf { it > 0 })?.let {
                    Text(ExplorerUiText.selectedSize(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("explorer.download.selected"))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("explorer.include")) {
            INCLUDE_ORDER.forEach { kind ->
                val on = kind.token in include
                val stats: ExplorerTypeStats? = size?.byKind?.get(kind)
                val bytes = stats?.bytes?.takeIf { it > 0 && kind in setOf(ExplorerKind.IMAGES, ExplorerKind.VIDEOS, ExplorerKind.AUDIOS) }?.let { ExplorerBytes.format(it) }
                ExplorerPill(
                    text = listOfNotNull(if (on) "✓ ${kind.label}" else kind.label, bytes).joinToString(" "),
                    selected = on,
                    onClick = { onInclude(kind.token) },
                    minHeight = 34.dp,
                    style = MaterialTheme.typography.bodySmall,
                    horizontal = 10.dp,
                    modifier = Modifier.testTag("explorer.include.${kind.token}"),
                )
            }
        }
        val report: @Composable (Modifier) -> Unit = { m ->
            ExplorerSecondaryButton(ExplorerUiText.report, onClick = onReport, enabled = online && ready, modifier = m.testTag("explorer.report"))
        }
        val folder: @Composable (Modifier) -> Unit = { m ->
            ExplorerPrimaryButton(ExplorerUiText.downloadFolder, onClick = onDownload, enabled = online && ready && include.isNotEmpty(), modifier = m.testTag("explorer.download"))
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
    }
}

@Composable
private fun Tabs(tab: ExplorerTab, files: Int, tables: Int?, transcripts: Int?, onTab: (ExplorerTab) -> Unit) {
    val line = MaterialTheme.field.hairline
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).drawBehind { drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) },
    ) {
        listOf(
            Triple(ExplorerTab.FILES, "${ExplorerUiText.files} $files", "explorer.tab.files"),
            Triple(ExplorerTab.TABLES, listOfNotNull(ExplorerText.tables, tables?.toString()).joinToString(" "), "explorer.tables.toggle"),
            Triple(ExplorerTab.TRANSCRIPTS, listOfNotNull(ExplorerText.transcripts, transcripts?.takeIf { it > 0 }?.toString()).joinToString(" "), "data.transcripts.toggle"),
        ).forEach { (key, label, tag) ->
            val on = key == tab
            val accent = MaterialTheme.colorScheme.primary
            Box(
                Modifier.heightIn(min = 48.dp)
                    .clickable(role = Role.Tab) { onTab(key) }
                    .semantics { selected = on }
                    .drawBehind { if (on) drawRect(accent, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
                    .padding(horizontal = 16.dp)
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun KindPills(files: List<ExplorerEntry>, current: String?, onPick: (String?) -> Unit) {
    val counts = files.groupingBy { ExplorerKind.ofMediaType(it.mediaType) }.eachCount()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExplorerPill("${ExplorerUiText.all} ${files.size}", selected = current == null, onClick = { onPick(null) }, ink = true, minHeight = 32.dp, style = MaterialTheme.typography.bodySmall, role = Role.RadioButton, modifier = Modifier.testTag("explorer.kind.all"))
        listOf(ExplorerKind.VIDEOS, ExplorerKind.IMAGES, ExplorerKind.AUDIOS, ExplorerKind.DOCUMENTS, ExplorerKind.OTHER).forEach { kind ->
            val n = counts[kind] ?: 0
            if (n > 0) ExplorerPill("${kind.label} $n", selected = current == kind.token, onClick = { onPick(kind.token) }, ink = true, minHeight = 32.dp, style = MaterialTheme.typography.bodySmall, role = Role.RadioButton, modifier = Modifier.testTag("explorer.kind.${kind.token}"))
        }
    }
}

@Composable
private fun SelectBar(count: Int, breakdown: String, size: String?, online: Boolean, large: Boolean, onShare: () -> Unit, onDownload: () -> Unit) {
    val dark = MaterialTheme.field.isDark
    val bar = if (dark) Color(0xFF2A2640) else ExplorerInk.lightInk
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .shadow(16.dp, RoundedCornerShape(20.dp), ambientColor = ExplorerInk.lightInk, spotColor = ExplorerInk.lightInk)
                .background(bar, RoundedCornerShape(20.dp))
                .padding(12.dp)
                .testTag("explorer.selectbar"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (breakdown.isNotBlank()) Text(breakdown, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCFCBE0), modifier = Modifier.testTag("explorer.select.breakdown"))
            if (!online) Text(ExplorerText.offline, style = MaterialTheme.typography.bodySmall, color = ExplorerTones.warnDark.content)
            val share: @Composable (Modifier) -> Unit = { m ->
                ExplorerSecondaryButton(ExplorerUiText.share, onClick = onShare, enabled = online && count > 0, line = ExplorerInk.darkControlLine, content = Color.White, container = Color.Transparent, modifier = m.testTag("explorer.select.share"))
            }
            val download: @Composable (Modifier) -> Unit = { m ->
                ExplorerPrimaryButton(ExplorerUiText.downloadN(count, size), onClick = onDownload, enabled = online && count > 0, container = com.fieldrepository.app.ui.FieldPalette.Purple400, content = com.fieldrepository.app.ui.FieldPalette.OnPurple400Dark, bold = true, modifier = m.testTag("explorer.select.download"))
            }
            if (large) {
                download(Modifier.fillMaxWidth())
                share(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    share(Modifier.weight(1f))
                    download(Modifier.weight(2f))
                }
            }
        }
    }
}

@Composable
private fun TreePane(
    state: ExplorerState,
    api: ExplorerApi,
    activeDownloads: Int,
    onDownloads: () -> Unit,
    onBack: () -> Unit,
    search: @Composable () -> Unit,
    filterBar: @Composable () -> Unit,
    modifier: Modifier,
) {
    val outline = remember(ExplorerIdentity(api)) { mutableStateMapOf<String, List<ExplorerEntry>>() }
    val chain = state.crumbs.map { it.path }.filter { it.isNotEmpty() }
    val status = state.statusParam
    val narrowing = state.filterQuery
    LaunchedEffect(narrowing) { outline.clear() }
    LaunchedEffect(chain, status, narrowing) {
        chain.dropLast(1).forEach { at ->
            if (outline[at] == null) {
                attemptOrNull { api.tree(at, status, null, filters = narrowing) }?.let { t -> outline[at] = t.entries.filter { it.isFolder } }
            }
        }
    }
    val levelOf: (Int) -> List<ExplorerEntry>? = { level ->
        val at = chain.getOrNull(level)
        when {
            at == null -> null
            at == state.path -> state.folders
            else -> outline[at]
        }
    }
    Column(
        modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp).testTag("explorer.pane.back")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = ExplorerText.back, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(26.dp))
            }
            Text(ExplorerText.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).semantics { heading() })
            if (state.loading) FieldCircularProgressIndicator(modifier = Modifier.size(16.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            DownloadsButton(activeDownloads, 40.dp, onDownloads)
        }
        LazyColumn(Modifier.fillMaxSize().testTag("explorer.pane.tree"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            item("search") { Box(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { search() } }
            item("filters") { Box(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { filterBar() } }
            if (state.taxonomies.size > 1) {
                item("segments") {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp).border(1.dp, explorerControlLine(), ExplorerShapes.control).padding(4.dp).testTag("explorer.organise"),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.taxonomies.forEach { t ->
                            val on = t.id == state.activeTaxonomy?.id
                            Box(
                                Modifier.weight(1f).heightIn(min = 40.dp)
                                    .clip(ExplorerShapes.tile)
                                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, ExplorerShapes.tile)
                                    .clickable(role = Role.RadioButton) { state.open(t.path) }
                                    .semantics { selected = on }
                                    .testTag("explorer.taxonomy.${t.id}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(t.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
            fun LazyListScope.level(index: Int) {
                val list = (if (state.showsLocal) levelOf(index) else emptyList()) ?: return
                list.forEach { folder ->
                    val open = chain.getOrNull(index + 1) == folder.path
                    val here = folder.path == state.path
                    item("tree:${folder.key}") {
                        TreeRow(folder, depth = index, open = open, here = here, branch = index + 1 < chain.size || here || folder.recordType !in setOf("artisan", "record", "interview")) { state.open(folder.path) }
                    }
                    if (open) level(index + 1)
                }
            }
            level(0)
        }
    }
}

@Composable
private fun TreeRow(folder: ExplorerEntry, depth: Int, open: Boolean, here: Boolean, branch: Boolean, onOpen: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(vertical = 4.dp)
            .clip(ExplorerShapes.tile)
            .background(if (here) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, ExplorerShapes.tile)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { selected = here }
            .padding(start = 12.dp + (depth * 20).dp, end = 12.dp)
            .testTag("explorer.folder.${folder.path}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (branch && depth == 0) Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        Text(
            folder.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (open || here) FontWeight.SemiBold else FontWeight.Normal,
            color = if (here) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        folder.count?.let {
            Text(String.format(java.util.Locale.ROOT, "%,d", it), style = MaterialTheme.typography.bodySmall, fontWeight = if (here || open) FontWeight.SemiBold else FontWeight.Normal, color = if (here) accent else MaterialTheme.colorScheme.onSurfaceVariant)
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
        Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, ExplorerShapes.card), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Text(ExplorerText.restrictedTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Text(ExplorerText.restrictedBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
