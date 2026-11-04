package com.fieldrepository.app.explorer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.data.ExplorerCache
import com.fieldrepository.app.explorer.data.ExplorerDownloads
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerEnvironment
import com.fieldrepository.app.explorer.data.ExplorerItem
import com.fieldrepository.app.explorer.data.ExplorerJobKind
import com.fieldrepository.app.explorer.data.ExplorerNetwork
import com.fieldrepository.app.explorer.data.ExplorerNotifications
import com.fieldrepository.app.explorer.data.ExplorerOpen
import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.explorer.data.ExplorerPlace
import com.fieldrepository.app.explorer.data.ExplorerRequest
import com.fieldrepository.app.explorer.data.ExplorerSaver
import com.fieldrepository.app.explorer.data.ExplorerSize
import com.fieldrepository.app.explorer.data.ExplorerSource
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.fieldrepository.app.transcript.ui.TranscriptDataPanel
import com.fieldrepository.app.transcript.ui.TranscriptFileChips
import com.fieldrepository.app.ui.CachedCopyNote
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface DownloadTarget {
    data class Folder(val path: String, val name: String) : DownloadTarget
    data object Selection : DownloadTarget
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
            cache = ExplorerCache(java.io.File(context.filesDir, "explorer-cache"), owner),
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
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val explorerApi = remember(api) { api ?: ExplorerEnvironment.api(app) }
    val state = rememberExplorerState(explorerApi)
    val scope = rememberCoroutineScope()
    val owner = remember { ExplorerPortal.userId(app) }
    val link by produceState(initialValue = ExplorerNetwork.now(app)) { ExplorerNetwork.watch(app).collect { value = it } }
    val jobs by ExplorerDownloads.store(app).jobs.collectAsState()
    var target by remember { mutableStateOf<DownloadTarget?>(null) }
    var size by remember { mutableStateOf<SizeLoad>(SizeLoad.Loading) }
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ExplorerEntry?>(null) }
    var transcriptsOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(state) {
        state.start(startPath?.takeIf { it.isNotBlank() } ?: ExplorerPlace.read(app, owner))
        withContext(Dispatchers.IO) { runCatching { ExplorerDownloads.sweep(app) } }
    }
    val systemActive = jobs.any { it.active && it.systemId != null }
    LaunchedEffect(systemActive) {
        while (systemActive) {
            runCatching { ExplorerDownloads.refreshSystem(app) }
            delay(1_000)
        }
    }
    LaunchedEffect(state.path) { listState.scrollToItem(0) }

    fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ExplorerNotifications.allowed(app)) {
            runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    fun begin(request: ExplorerRequest) {
        ensureNotifications()
        ExplorerDownloads.start(app, request)
        onMessage(ExplorerText.keepsGoing)
    }

    fun saveEntry(entry: ExplorerEntry) {
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
        )
        ensureNotifications()
        scope.launch {
            try {
                ExplorerDownloads.saveFile(app, request, entry.mediaId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                state.dismissError()
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

    fun openDownload(next: DownloadTarget) {
        target = next
        size = SizeLoad.Loading
        scope.launch {
            size = try {
                val stats = when (next) {
                    is DownloadTarget.Folder -> explorerApi.stats(next.path)
                    DownloadTarget.Selection -> explorerApi.selectionStats(state.selectionStatsBody())
                }
                SizeLoad.Known(ExplorerSize.of(stats))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                SizeLoad.Unknown
            }
        }
    }

    fun startDownload(next: DownloadTarget, choice: DownloadChoice, wifiOnly: Boolean) {
        val known = (size as? SizeLoad.Known)?.size
        when (next) {
            is DownloadTarget.Folder -> when (choice) {
                DownloadChoice.EVERYTHING -> begin(
                    ExplorerRequest(
                        title = next.name,
                        fileName = Naming.exportFilename("Dataset", "Export", "zip", withTime = true, subject = next.name),
                        kind = ExplorerJobKind.FOLDER,
                        sources = listOf(ExplorerSource(next.path)),
                        wifiOnly = wifiOnly,
                        expectedBytes = known?.bytes,
                    ),
                )
                DownloadChoice.TRANSCRIPTS -> begin(
                    ExplorerRequest(
                        title = "${next.name} · ${ExplorerText.transcripts}",
                        fileName = Naming.exportFilename("Dataset", "Transcripts", "zip", withTime = true, subject = next.name),
                        kind = ExplorerJobKind.TRANSCRIPTS,
                        single = true,
                        mimeType = "application/zip",
                        wifiOnly = wifiOnly,
                        items = listOf(ExplorerItem(path = "", url = ExplorerPaths.transcriptBundle(next.path))),
                    ),
                )
                DownloadChoice.REPORT -> begin(
                    ExplorerRequest(
                        title = "${next.name} · ${ExplorerText.report}",
                        fileName = Naming.exportFilename("Dataset", "Report", "xlsx", withTime = true, subject = next.name),
                        kind = ExplorerJobKind.REPORT,
                        single = true,
                        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        wifiOnly = wifiOnly,
                        items = listOf(ExplorerItem(path = "", url = ExplorerPaths.report(next.path))),
                    ),
                )
            }
            DownloadTarget.Selection -> {
                val count = state.selected.size
                begin(
                    ExplorerRequest(
                        title = ExplorerText.selected(count),
                        fileName = Naming.exportFilename("Dataset", "Export", "zip", withTime = true, subject = "Selection ${state.folderName}"),
                        kind = ExplorerJobKind.SELECTION,
                        selection = state.selectionBody(),
                        wifiOnly = wifiOnly,
                        expectedBytes = known?.bytes,
                    ),
                )
                state.endSelect()
            }
        }
        target = null
        showDownloads = true
    }

    BackHandler(enabled = true) {
        when {
            viewing != null -> viewing = null
            state.selecting -> state.endSelect()
            state.canGoUp && state.goUp() -> Unit
            else -> onBack()
        }
    }

    val activeCount = jobs.count { it.active }
    val saveJobs = remember(jobs) {
        jobs.filter { it.active && it.request.kind == ExplorerJobKind.FILE }
            .associateBy { it.request.items.firstOrNull()?.let { item -> item.mediaId ?: item.path }.orEmpty() }
    }
    val transcriptJobs = remember(jobs) {
        jobs.filter { it.active && it.request.kind == ExplorerJobKind.TRANSCRIPTS }.mapNotNull { job ->
            job.request.items.firstOrNull()?.url?.substringAfter("/transcripts/", "")?.substringBefore('/')?.takeIf { it.isNotBlank() }
        }.toSet()
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("explorer.screen")) {
        TopBar(
            selecting = state.selecting,
            selectedCount = state.selected.size,
            loading = state.loading,
            activeDownloads = activeCount,
            onBack = {
                if (state.selecting) state.endSelect() else if (!state.canGoUp || !state.goUp()) onBack()
            },
            onDownloads = { showDownloads = true },
            onReload = state::reload,
            onSelectAll = state::selectAllHere,
        )
        if (state.restricted) {
            Restricted()
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("explorer.tree"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            stickyHeader("toolbar") {
                Toolbar(
                    search = { search { type, id, title -> state.locate(type, id, title) } },
                    taxonomies = state.taxonomies.map { it.id to it.name },
                    activeTaxonomy = state.activeTaxonomy?.id,
                    onTaxonomy = { id -> state.taxonomies.firstOrNull { it.id == id }?.let { state.open(it.path) } },
                    statuses = ExplorerPortal.statusOptions,
                    status = state.status,
                    onStatus = state::changeStatus,
                )
            }
            item("crumbs") {
                Crumbs(state)
            }
            if (state.locating != null) item("locating") { LoadingLine(ExplorerText.preparing) }
            state.unfiled?.let { text -> item("unfiled") { Notice(text, warn = true) } }
            state.cachedNote?.let { text -> item("cached") { CachedCopyNote(text) } }
            state.error?.let { text ->
                item("error") {
                    ErrorRow(text, onRetry = { state.dismissError(); state.reload() }, onDismiss = state::dismissError)
                }
            }
            when {
                state.tree == null && state.loading -> item("loading") { LoadingLine(ExplorerText.loadingFolder) }
                state.tree != null && state.entries.isEmpty() -> item("empty") { HintLine(ExplorerText.folderEmpty) }
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
            items(state.files, key = { it.key }) { file ->
                val saveKey = file.mediaId ?: file.name
                FileRow(
                    entry = file,
                    selecting = state.selecting,
                    selected = file.key in state.selected,
                    textOpen = file.key in state.openText,
                    transcriptOpen = file.key in state.openTranscripts,
                    transcript = state.transcriptFor(file),
                    saveJob = saveJobs[saveKey],
                    online = link.online,
                    onOpen = {
                        val mediaId = file.mediaId
                        when {
                            mediaId != null && onOpenMedia != null -> onOpenMedia(mediaId)
                            file.mediaType in ExplorerPaths.PREVIEWABLE -> viewing = file
                            else -> state.toggleText(file)
                        }
                    },
                    onLongPress = { state.beginSelect(file) },
                    onToggle = { state.toggleSelect(file) },
                    onText = { state.toggleText(file) },
                    onTranscript = { state.toggleTranscript(file) },
                    onPreview = { viewing = file },
                    onSave = { saveEntry(file) },
                    onCancelSave = { saveJobs[saveKey]?.let { ExplorerDownloads.cancel(app, it.id) } },
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
            state.info?.takeIf { info -> info.fields.any { it.value.isNotBlank() } }?.let { info ->
                item("info") {
                    Section(title = ExplorerText.details, open = state.showInfo, onToggle = { state.showInfo = !state.showInfo }, tag = "explorer.details") {
                        Text(info.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        RecordInfoTable(info)
                    }
                }
            }
            item("transcripts") {
                Section(title = ExplorerText.transcripts, open = transcriptsOpen, onToggle = { transcriptsOpen = !transcriptsOpen }, tag = "data.transcripts.toggle") {
                    val languages by produceState(initialValue = emptyList<Pair<String, String>>(), explorerApi) {
                        value = runCatching { explorerApi.languages().languages.map { it.code to it.label.ifBlank { it.code } } }.getOrDefault(emptyList())
                    }
                    TranscriptDataPanel(onMessage = onMessage, offeredLanguages = languages)
                }
            }
        }
        BottomBar(
            selecting = state.selecting,
            selectedCount = state.selected.size,
            folderName = state.folderName,
            online = link.online,
            ready = state.tree != null,
            onDownload = {
                if (state.selecting) openDownload(DownloadTarget.Selection) else openDownload(DownloadTarget.Folder(state.path, state.folderName))
            },
            onClear = state::endSelect,
        )
    }

    target?.let { next ->
        DownloadSheet(
            title = when (next) {
                is DownloadTarget.Folder -> next.name
                DownloadTarget.Selection -> ExplorerText.selected(state.selected.size)
            },
            selection = next is DownloadTarget.Selection,
            size = size,
            metered = link.metered,
            online = link.online,
            freeBytes = remember(next) { ExplorerSaver.freeBytes(app) },
            onStart = { choice, wifiOnly -> startDownload(next, choice, wifiOnly) },
            onDismiss = { target = null },
        )
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
        )
    }

    viewing?.let { entry ->
        ExplorerPreviewDialog(
            entry = entry,
            onSave = if (entry.mediaId != null) ({ saveEntry(entry) }) else null,
            onDismiss = { viewing = null },
        )
    }
}

@Composable
private fun TopBar(
    selecting: Boolean,
    selectedCount: Int,
    loading: Boolean,
    activeDownloads: Int,
    onBack: () -> Unit,
    onDownloads: () -> Unit,
    onReload: () -> Unit,
    onSelectAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("explorer.back")) {
            Icon(if (selecting) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (selecting) ExplorerText.clear else ExplorerText.back, tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            if (selecting) ExplorerText.selected(selectedCount) else ExplorerText.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (selecting) {
            TextButton(onClick = onSelectAll, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.selectall")) { Text("Select all") }
            return@Row
        }
        if (loading) FieldCircularProgressIndicator(modifier = Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
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
private fun Toolbar(
    search: @Composable () -> Unit,
    taxonomies: List<Pair<String, String>>,
    activeTaxonomy: String?,
    onTaxonomy: (String) -> Unit,
    statuses: List<Pair<String, String>>,
    status: String?,
    onStatus: (String?) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().testTag("explorer.toolbar")) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            search()
            if (taxonomies.size > 1 || statuses.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    taxonomies.forEach { (id, name) ->
                        FilterChip(
                            selected = id == activeTaxonomy,
                            onClick = { onTaxonomy(id) },
                            label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.taxonomy.$id"),
                        )
                    }
                    if (statuses.isNotEmpty() && taxonomies.isNotEmpty()) Spacer(Modifier.width(4.dp))
                    statuses.forEach { (value, label) ->
                        FilterChip(
                            selected = value == status,
                            onClick = { onStatus(value) },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.status.$value"),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Crumbs(state: ExplorerState) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(Modifier.fillMaxWidth().testTag("explorer.crumbs"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            state.crumbs.forEachIndexed { index, crumb ->
                if (index > 0) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).align(Alignment.CenterVertically))
                }
                if (index == state.crumbs.lastIndex) {
                    Text(crumb.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.align(Alignment.CenterVertically).padding(vertical = 10.dp))
                } else {
                    Text(
                        crumb.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterVertically).heightIn(min = 44.dp).clickable { state.open(crumb.path) }.padding(vertical = 12.dp, horizontal = 2.dp),
                    )
                }
            }
        }
        if (state.tree != null) {
            val line = buildString {
                append(ExplorerText.folderCount(state.folders.size, state.files.size))
                val total = state.total
                if (total != null && total > state.entries.size) append(" · ").append(ExplorerText.showing(state.entries.size, total))
            }
            Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("explorer.count"))
        }
    }
}

@Composable
private fun Section(title: String, open: Boolean, onToggle: () -> Unit, tag: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 44.dp).testTag(tag)) { Text(if (open) ExplorerText.hide else ExplorerText.show) }
            }
            if (open) {
                content()
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

@Composable
private fun ErrorRow(text: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small).padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("explorer.error"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text(ExplorerText.dismiss) }
            Button(onClick = onRetry, modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("explorer.error.retry")) { Text(ExplorerText.retry) }
        }
    }
}

@Composable
private fun BottomBar(
    selecting: Boolean,
    selectedCount: Int,
    folderName: String,
    online: Boolean,
    ready: Boolean,
    onDownload: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!online) Text(ExplorerText.offline, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.onWarningContainer, modifier = Modifier.testTag("explorer.offline"))
            if (selecting) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(ExplorerText.clear) }
                    Button(onClick = onDownload, enabled = online && selectedCount > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("explorer.download")) {
                        Text(ExplorerText.downloadSelected(selectedCount), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Button(onClick = onDownload, enabled = online && ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("explorer.download")) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${ExplorerText.download} $folderName", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
