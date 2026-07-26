package com.fieldrepository.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fieldrepository.app.data.DataCrumbDto
import com.fieldrepository.app.data.DataFolderInfoDto
import com.fieldrepository.app.data.DataManifestFileDto
import com.fieldrepository.app.data.DataTaxonomyDto
import com.fieldrepository.app.data.DataTreeDto
import com.fieldrepository.app.data.DataTreeEntryDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import retrofit2.HttpException

/*
 * Data browser — the Android twin of the web `/data` page.
 *
 * The repository is a VIRTUAL file system served one level at a time by `GET /data/tree?path=`.
 * Three taxonomies (by-workshop, by-uploader, by-type) re-root the same data; the switcher at the
 * top is what makes that legible, because before it existed the three roots looked like three
 * unrelated data sets rather than three views of one.
 *
 * Differences from the web, all forced by the form factor:
 *   - There is no split pane. The web keeps a sticky lazy TREE on the left and the folder contents
 *     on the right; a phone gets ONE column, so navigation is "tap a folder to descend" plus the
 *     server-resolved breadcrumbs to jump back up. System Back walks up the crumbs before it
 *     leaves the screen.
 *   - The web's "Data tables" accordion renders /data/report?format=json inline. There is no
 *     readable way to put a 27-column sheet on a phone, so this keeps only the half that matters
 *     in the field: the .xlsx download of the same subtree.
 *   - Zipping is done by FieldRepository.downloadDataFolder (straight into Downloads) instead of
 *     JSZip in the page.
 */

// ---------------------------------------------------------------------------------------------
// Content-type filters — the manifest `include` CSV, in the web's order and with its labels.
// ---------------------------------------------------------------------------------------------

private data class IncludeOption(val key: String, val label: String)

private val INCLUDE_OPTIONS = listOf(
    IncludeOption("text", "Text"),
    IncludeOption("images", "Images"),
    IncludeOption("videos", "Videos"),
    IncludeOption("audios", "Audios"),
    IncludeOption("transcripts", "Transcripts"),
    IncludeOption("documents", "Documents"),
    IncludeOption("other", "Other files")
)

// ---------------------------------------------------------------------------------------------
// Icons — one per record type, mirroring RECORD_ICONS in frontend/app/(protected)/data/page.tsx.
// ---------------------------------------------------------------------------------------------

private fun recordIcon(recordType: String?): ImageVector = when (recordType) {
    "workshop" -> Icons.Filled.Groups
    "craft" -> Icons.Filled.Brush
    "artisan" -> Icons.Filled.Person
    "product" -> Icons.Filled.Inventory2
    "tool" -> Icons.Filled.Build
    "process" -> Icons.Filled.AccountTree
    "interview" -> Icons.AutoMirrored.Filled.Assignment
    "user" -> Icons.Filled.AccountCircle
    "taxonomy" -> Icons.Filled.Layers
    else -> Icons.Filled.Folder
}

private fun fileIcon(entry: DataTreeEntryDto): ImageVector = when (entry.mediaType) {
    "IMAGE" -> Icons.Filled.Image
    "VIDEO" -> Icons.Filled.Movie
    "AUDIO" -> Icons.Filled.Audiotrack
    "PDF", "DOCUMENT" -> Icons.Filled.Description
    else -> if (entry.content != null) Icons.Filled.Description else Icons.Filled.Attachment
}

/** "1.2 MB" — the web's `bytes()` helper, same 1024 base and same one-decimal rounding. */
private fun formatBytes(size: Long?): String? {
    if (size == null || size < 0) return null
    if (size < 1024) return "$size B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = size.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format("%.1f %s", value, units[unit])
}

/** Filename minus its extension — how a transcript is paired with the media it came from. */
private fun stem(name: String): String = name.substringBeforeLast('.', name)

// ---------------------------------------------------------------------------------------------
// State holder
// ---------------------------------------------------------------------------------------------

/**
 * Everything the browser needs to remember across recompositions: which folder is open, the levels
 * already fetched, the per-folder transcript manifest, and the two "restricted"/"error" terminal
 * states.
 *
 * Fetches are single-flight — opening a new folder cancels the in-flight one, so a fast tapper
 * never lands on a level they already navigated away from. A cancelled load deliberately leaves
 * [loading] true because the load that replaced it has already set it.
 */
@Stable
class DataBrowserState(
    private val repository: FieldRepository,
    private val scope: CoroutineScope
) {
    /** The folder currently open. "" is the taxonomy chooser, not a folder listing. */
    var currentPath by mutableStateOf("")
        private set

    /** The level served for [currentPath], or null before the first response arrives. */
    var tree by mutableStateOf<DataTreeDto?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    /** A message worth showing the user; cleared by [dismissError] and by the next navigation. */
    var error by mutableStateOf<String?>(null)
        private set

    /** HTTP 403 from /data/tree: this account lacks the dataset-download permission. */
    var restricted by mutableStateOf(false)
        private set

    /** The id of the search hit being resolved to a folder, if any. */
    var locating by mutableStateOf<String?>(null)
        private set

    /** Set when a search hit turned out to have no folder at all; cleared by the next navigation. */
    var unfiled by mutableStateOf<String?>(null)
        private set

    /** Entry paths whose inline `content` is expanded. */
    var openInline by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Entry paths whose transcript is expanded. */
    var openTranscripts by mutableStateOf<Set<String>>(emptySet())
        private set

    private val levels = mutableStateMapOf<String, DataTreeDto>()
    private val transcriptsByFolder = mutableStateMapOf<String, List<DataManifestFileDto>>()
    private var transcriptFolderLoading by mutableStateOf<String?>(null)
    private var loadJob: Job? = null
    private var locateJob: Job? = null

    // The bare root is only a chooser, so the first resolution drops the user straight into the
    // default taxonomy. One-shot, so navigating back to the root still shows the chooser.
    private var landed = false

    /** Served with every level, so the switcher is available wherever the user is. */
    val taxonomies: List<DataTaxonomyDto>
        get() = tree?.taxonomies ?: levels[""]?.taxonomies ?: emptyList()

    /** Which taxonomy [currentPath] sits in; null at the root. */
    val activeTaxonomy: DataTaxonomyDto?
        get() = tree?.taxonomy?.let { id -> taxonomies.firstOrNull { it.id == id } }

    /** Server-resolved breadcrumbs — clean names, never path segments. */
    val crumbs: List<DataCrumbDto>
        get() {
            val served = tree?.crumbs.orEmpty()
            return if (served.any { it.path.isEmpty() }) served else listOf(DataCrumbDto("Repository", "")) + served
        }

    val info: DataFolderInfoDto? get() = tree?.info

    val folders: List<DataTreeEntryDto> get() = tree?.entries.orEmpty().filter { it.isFolder }

    val files: List<DataTreeEntryDto> get() = tree?.entries.orEmpty().filterNot { it.isFolder }

    val truncated: Boolean get() = tree?.truncated == true

    /** The current folder's own name — what the .zip and the .xlsx are named after. */
    val folderName: String get() = crumbs.lastOrNull()?.name?.takeIf { it.isNotBlank() } ?: "dataset"

    /** True while the transcript manifest for the open folder is in flight. */
    val transcriptLoading: Boolean get() = transcriptFolderLoading == currentPath

    /** There is an ancestor to step up to (the root has only its own crumb). */
    val canGoUp: Boolean get() = crumbs.size >= 2

    fun dismissError() {
        error = null
    }

    /** Open [path], painting any cached level immediately and refreshing it from the server. */
    fun open(path: String) {
        if (path != currentPath) {
            openInline = emptySet()
            openTranscripts = emptySet()
        }
        error = null
        unfiled = null
        loadJob?.cancel()
        loadJob = scope.launch {
            var target = path
            loading = true
            currentPath = target
            tree = levels[target]
            while (true) {
                val level = fetch(target) ?: break
                // Root resolved for the first time: fall through into the default taxonomy rather
                // than leaving the user on a chooser with three identical-looking folders.
                if (target.isEmpty() && !landed) {
                    val fallback = level.taxonomies.firstOrNull { it.isDefault } ?: level.taxonomies.firstOrNull()
                    if (fallback != null && fallback.path.isNotEmpty()) {
                        landed = true
                        target = fallback.path
                        currentPath = target
                        tree = levels[target]
                        continue
                    }
                    landed = true
                }
                break
            }
            loading = false
        }
    }

    /**
     * Open the folder that files a search hit, named [title] so the "no folder" answer can say WHICH
     * record it is about. `GET /data/locate` returns a null path for a record nothing files yet — a
     * product whose artisan was never attached to a workshop — and that is reported rather than
     * approximated, because the nearest folder would be somebody else's.
     */
    fun locate(recordType: String, recordId: String, title: String) {
        locateJob?.cancel()
        locateJob = scope.launch {
            locating = recordId
            unfiled = null
            error = null
            try {
                val path = repository.locateRecord(recordType, recordId)
                if (path == null) {
                    unfiled = "$title is not filed under any workshop yet, so it has no folder to open."
                } else {
                    open(path)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.apiErrorMessage("Unable to locate this record")
            }
            locating = null
        }
    }

    /** Re-fetch the open folder, bypassing the cached copy. */
    fun reload() {
        levels.remove(currentPath)
        transcriptsByFolder.remove(currentPath)
        open(currentPath)
    }

    /** Step to the parent folder. Returns false when there is nowhere left to go (caller exits). */
    fun goUp(): Boolean {
        val parent = crumbs.getOrNull(crumbs.size - 2) ?: return false
        open(parent.path)
        return true
    }

    fun toggleInline(entry: DataTreeEntryDto) {
        openInline = openInline.toMutableSet().apply { if (!add(entry.path)) remove(entry.path) }
    }

    /**
     * Show/hide one file's transcript. Bodies are not on the tree entry — they arrive in the
     * manifest as `.transcript.md` content — so the first open fetches the whole folder's
     * transcripts once and every later toggle is free.
     */
    fun toggleTranscript(entry: DataTreeEntryDto) {
        val opening = entry.path !in openTranscripts
        openTranscripts = openTranscripts.toMutableSet().apply { if (!add(entry.path)) remove(entry.path) }
        val folder = currentPath
        if (!opening || transcriptsByFolder.containsKey(folder) || transcriptFolderLoading == folder) return
        scope.launch {
            transcriptFolderLoading = folder
            try {
                val manifest = repository.dataManifest(folder, "transcripts")
                transcriptsByFolder[folder] = manifest.files
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.apiErrorMessage("Unable to load transcripts")
            }
            transcriptFolderLoading = null
        }
    }

    /** This file's transcript body: matched on media id first, then on the shared filename stem. */
    fun transcriptFor(entry: DataTreeEntryDto): String? {
        val files = transcriptsByFolder[currentPath] ?: return null
        entry.mediaId?.let { id ->
            files.firstOrNull { it.mediaId == id && it.content != null }?.content?.let { return it }
        }
        val nameStem = stem(entry.name)
        return files.firstOrNull { it.content != null && it.path.contains(nameStem) }?.content
    }

    /** Report a failure raised by a download run from the screen (which owns the Context). */
    fun reportError(message: String) {
        error = message
    }

    private suspend fun fetch(target: String): DataTreeDto? = try {
        val level = repository.dataTree(target)
        levels[target] = level
        if (currentPath == target) {
            tree = level
            restricted = false
        }
        level
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        if (failure is HttpException && failure.code() == 403) {
            restricted = true
        } else {
            error = failure.apiErrorMessage("Unable to load folder")
        }
        null
    }
}

/** A [DataBrowserState] scoped to this composition, loading the root on first appearance. */
@Composable
fun rememberDataBrowserState(repository: FieldRepository): DataBrowserState {
    val scope = rememberCoroutineScope()
    val state = remember(repository) { DataBrowserState(repository, scope) }
    LaunchedEffect(state) { state.open("") }
    return state
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

/**
 * The data browser. Walks the virtual tree, previews generated text and transcripts, and downloads
 * either the open subtree as a filtered .zip or its relational report as .xlsx.
 *
 * [onBack] is called when the user is already at the top of the tree and presses Back; anywhere
 * deeper, Back walks one folder up instead. [onMessage] receives download confirmations so the host
 * can raise its own snackbar; the screen also shows them inline, so wiring it is optional.
 */
@Composable
fun DataBrowserScreen(
    repository: FieldRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {}
) {
    val state = rememberDataBrowserState(repository)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var includes by remember { mutableStateOf(INCLUDE_OPTIONS.map { it.key }.toSet()) }
    var zipping by remember { mutableStateOf(false) }
    var zipDone by remember { mutableStateOf(0) }
    var zipTotal by remember { mutableStateOf(0) }
    var downloadNote by remember { mutableStateOf<String?>(null) }
    var reporting by remember { mutableStateOf(false) }
    var savingMediaId by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<DataTreeEntryDto?>(null) }
    var jumpQuery by remember { mutableStateOf("") }
    val jump = rememberQuickSearch(repository, jumpQuery)

    BackHandler(enabled = true) { if (!state.canGoUp || !state.goUp()) onBack() }

    fun note(text: String) {
        downloadNote = text
        onMessage(text)
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DataBrowserTopBar(
            onBack = { if (!state.canGoUp || !state.goUp()) onBack() },
            loading = state.loading,
            onReload = state::reload
        )

        if (state.restricted) {
            RestrictedPanel()
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item("intro") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "Data Browser",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Text(
                        "Browse the repository as a directory tree, preview media and transcripts, " +
                            "and download any folder as a zip with content-type filters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.error?.let { message ->
                item("error") { ErrorBanner(message, onDismiss = state::dismissError) }
            }

            // Above the browser, not inside it: walking down four levels of folders to reach one
            // known artisan is the slowest thing this screen asks of a researcher.
            item("jump") {
                JumpToRecordPanel(
                    query = jumpQuery,
                    onQueryChange = { jumpQuery = it },
                    search = jump,
                    locating = state.locating,
                    unfiled = state.unfiled,
                    onOpenHit = { hit -> state.locate(hit.recordType, hit.id, hit.title) }
                )
            }

            if (state.taxonomies.isNotEmpty()) {
                item("taxonomies") {
                    TaxonomySwitcher(
                        taxonomies = state.taxonomies,
                        active = state.activeTaxonomy,
                        onSelect = { state.open(it.path) }
                    )
                }
            }

            item("crumbs") {
                BrowserPanel {
                    Breadcrumbs(crumbs = state.crumbs, onOpen = state::open)
                    val folders = state.folders.size
                    val files = state.files.size
                    if (state.tree != null) {
                        Text(
                            "$folders folder${if (folders == 1) "" else "s"} · " +
                                "$files file${if (files == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.truncated) {
                        NoticeBox(
                            "This listing was truncated at the server cap — open subfolders to see " +
                                "everything it holds.",
                            container = MaterialTheme.field.warningContainer,
                            content = MaterialTheme.field.onWarningContainer
                        )
                    }
                }
            }

            state.info?.let { info ->
                item("info") { RecordInfoCard(info) }
            }

            item("tables") {
                BrowserPanel {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.TableChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Data tables",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        "Spreadsheet view of everything under ${state.folderName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                reporting = true
                                val path = state.currentPath
                                runCatching { repository.downloadReport(context, path) }
                                    .onSuccess { note("Report saved to $it") }
                                    .onFailure {
                                        state.reportError(it.apiErrorMessage("Unable to download the report"))
                                    }
                                reporting = false
                            }
                        },
                        enabled = !reporting,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (reporting) "Preparing report…" else "Download report (.xlsx)")
                    }
                }
            }

            item("download") {
                BrowserPanel {
                    Text(
                        "Include",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IncludeChips(selected = includes, onToggle = { key ->
                        includes = includes.toMutableSet().apply { if (!add(key)) remove(key) }
                    })
                    Button(
                        onClick = {
                            val include = INCLUDE_OPTIONS.filter { it.key in includes }.joinToString(",") { it.key }
                            if (include.isEmpty()) {
                                state.reportError("Pick at least one content type to include in the download.")
                                return@Button
                            }
                            scope.launch {
                                zipping = true
                                zipDone = 0
                                zipTotal = 0
                                downloadNote = null
                                val path = state.currentPath
                                val name = state.folderName
                                runCatching {
                                    repository.downloadDataFolder(
                                        context = context,
                                        path = path,
                                        include = include,
                                        folderName = name,
                                        onProgress = { done, total -> zipDone = done; zipTotal = total }
                                    )
                                }.onSuccess { result ->
                                    note(
                                        when {
                                            result.total == 0 ->
                                                "Nothing in this folder matches the selected filters."
                                            result.failed > 0 ->
                                                "Archive saved with ${result.saved} of ${result.total} files — " +
                                                    "${result.failed} failed. Saved to ${result.displayLocation}"
                                            else ->
                                                "Archive saved — ${result.total} " +
                                                    "file${if (result.total == 1) "" else "s"}. " +
                                                    "Saved to ${result.displayLocation}"
                                        }
                                    )
                                }.onFailure {
                                    state.reportError(it.apiErrorMessage("Unable to download this folder"))
                                }
                                zipping = false
                            }
                        },
                        enabled = !zipping,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (zipping) "Preparing zip…" else "Download this folder")
                    }
                    if (zipping) {
                        val fraction = if (zipTotal > 0) zipDone.toFloat() / zipTotal.toFloat() else 0f
                        Text(
                            if (zipTotal > 0) {
                                "Fetching file ${minOf(zipDone + 1, zipTotal)} of $zipTotal"
                            } else {
                                "Reading the folder manifest…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                    }
                    downloadNote?.let { text ->
                        NoticeBox(
                            text,
                            container = MaterialTheme.field.successContainer,
                            content = MaterialTheme.field.onSuccessContainer
                        )
                    }
                }
            }

            when {
                state.tree == null && state.loading -> item("loading") {
                    BrowserPanel { LoadingRow("Loading folder…") }
                }
                state.tree == null -> item("empty-select") {
                    BrowserPanel {
                        Text(
                            "Select a folder to browse.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                state.folders.isEmpty() && state.files.isEmpty() -> item("empty-folder") {
                    BrowserPanel {
                        Text(
                            "Empty folder",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "This folder has no files or subfolders.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(state.folders, key = { "folder:${it.path}" }) { folder ->
                FolderRow(folder = folder, onOpen = { state.open(folder.path) })
            }

            items(state.files, key = { "file:${it.path}" }) { file ->
                FileRow(
                    entry = file,
                    inlineOpen = file.path in state.openInline,
                    transcriptOpen = file.path in state.openTranscripts,
                    transcriptLoading = state.transcriptLoading,
                    transcript = if (file.path in state.openTranscripts) state.transcriptFor(file) else null,
                    saving = savingMediaId == file.mediaId && file.mediaId != null,
                    onToggleInline = { state.toggleInline(file) },
                    onToggleTranscript = { state.toggleTranscript(file) },
                    onPreview = { viewing = file },
                    onSave = {
                        val mediaId = file.mediaId ?: return@FileRow
                        scope.launch {
                            savingMediaId = mediaId
                            runCatching { repository.downloadDataMedia(context, mediaId, file.name) }
                                .onSuccess { note("Saved to $it") }
                                .onFailure {
                                    state.reportError(it.apiErrorMessage("Unable to save this file"))
                                }
                            savingMediaId = null
                        }
                    }
                )
            }
        }
    }

    viewing?.let { entry ->
        val url = entry.url
        if (url.isNullOrBlank()) {
            viewing = null
        } else {
            MediaViewerDialog(
                uri = Uri.parse(url),
                mediaType = entry.mediaType.orEmpty(),
                onSave = entry.mediaId?.let { mediaId ->
                    {
                        scope.launch {
                            savingMediaId = mediaId
                            runCatching { repository.downloadDataMedia(context, mediaId, entry.name) }
                                .onSuccess { note("Saved to $it") }
                                .onFailure {
                                    state.reportError(it.apiErrorMessage("Unable to save this file"))
                                }
                            savingMediaId = null
                        }
                    }
                },
                onDismiss = { viewing = null }
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun DataBrowserTopBar(onBack: () -> Unit, loading: Boolean, onReload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Local copy of MainActivity's BackPill: that one is file-private, and reaching into the
        // 10k-line activity for a 10-line widget is not worth the coupling.
        OutlinedButton(
            onClick = onBack,
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back")
        }
        Spacer(Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onReload) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Reload current folder",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** A card in the browser's single column. Every panel on this screen uses the same shell. */
@Composable
private fun BrowserPanel(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoticeBox(text: String, container: Color, content: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = content,
        modifier = Modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/**
 * The 403 state. The wording is the web's verbatim, because "ask an admin" is only actionable if
 * the user is told WHICH permission is missing.
 */
@Composable
private fun RestrictedPanel() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            "Dataset access is restricted",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Browsing and downloading the dataset is available to professors and above, or to " +
                "accounts that have been granted the dataset-download permission. Ask an admin to " +
                "grant you access if you need it for your research.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Search straight to a folder. The rows are the Search screen's own — same debounce, same shape —
 * but tapping one resolves the record to a tree path and navigates the browser there instead of
 * opening an edit form, because this screen is where the record's FILES live.
 */
@Composable
private fun JumpToRecordPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    search: QuickSearchState,
    locating: String?,
    unfiled: String?,
    onOpenHit: (SearchRow) -> Unit
) {
    BrowserPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "Jump to a record",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search the repository") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Find an artisan, workshop, product, tool or media file and open the folder that holds it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        search.error?.let { message ->
            NoticeBox(
                message,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        if (search.loading && search.hits.isEmpty()) LoadingRow("Searching…")
        if (search.searched && !search.loading && search.hits.isEmpty() && search.error == null) {
            Text(
                "No matching records.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        search.hits.forEach { hit ->
            SearchResultRow(row = hit, onOpen = { onOpenHit(hit) })
        }
        if (locating != null) LoadingRow("Opening the folder…")
        unfiled?.let { message ->
            NoticeBox(
                message,
                container = MaterialTheme.field.warningContainer,
                content = MaterialTheme.field.onWarningContainer
            )
        }
    }
}

/**
 * The same repository, re-rooted three ways. Kept visible at every level (not only at the root)
 * because the taxonomies ride along with every /data/tree response.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaxonomySwitcher(
    taxonomies: List<DataTaxonomyDto>,
    active: DataTaxonomyDto?,
    onSelect: (DataTaxonomyDto) -> Unit
) {
    BrowserPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Filled.Layers,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "Organise by",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            taxonomies.forEach { taxonomy ->
                val selected = taxonomy.id == active?.id
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(taxonomy) },
                    label = { Text(taxonomy.name) },
                    shape = MaterialTheme.shapes.small,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
        // The descriptions are the only place the folder shapes are spelled out.
        Text(
            active?.description ?: "Pick how the repository should be grouped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IncludeChips(selected: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        INCLUDE_OPTIONS.forEach { option ->
            FilterChip(
                selected = option.key in selected,
                onClick = { onToggle(option.key) },
                label = { Text(option.label) },
                shape = MaterialTheme.shapes.small
            )
        }
    }
}

/** Server-resolved breadcrumbs; every ancestor is tappable, the open folder is not. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Breadcrumbs(crumbs: List<DataCrumbDto>, onOpen: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp).align(Alignment.CenterVertically)
                )
            }
            if (index == crumbs.lastIndex) {
                Text(
                    crumb.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            } else {
                Text(
                    crumb.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .clickable { onOpen(crumb.path) }
                        .padding(vertical = 2.dp)
                )
            }
        }
    }
}

/** The record folder's own fields (artisan bio, workshop dates, …) as served in `info`. */
@Composable
private fun RecordInfoCard(info: DataFolderInfoDto) {
    val fields = info.fields.filter { it.value.isNotBlank() }
    if (fields.isEmpty()) return
    BrowserPanel {
        Text(
            info.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        HorizontalDivider(color = MaterialTheme.field.hairline)
        fields.forEach { field ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    field.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    field.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.field.body
                )
            }
        }
    }
}

@Composable
private fun FolderRow(folder: DataTreeEntryDto, onOpen: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.field.brandTile, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    recordIcon(folder.recordType),
                    contentDescription = null,
                    tint = MaterialTheme.field.onBrandTile,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                folder.recordType?.takeIf { it.isNotBlank() }?.let { type ->
                    Text(
                        type.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun FileRow(
    entry: DataTreeEntryDto,
    inlineOpen: Boolean,
    transcriptOpen: Boolean,
    transcriptLoading: Boolean,
    transcript: String?,
    saving: Boolean,
    onToggleInline: () -> Unit,
    onToggleTranscript: () -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit
) {
    val meta = listOfNotNull(
        entry.mediaType ?: if (entry.content != null) "TEXT" else "FILE",
        formatBytes(entry.sizeBytes)
    ).joinToString(" · ")

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // An image previews itself; everything else gets its type glyph.
                if (entry.mediaType == "IMAGE" && !entry.url.isNullOrBlank()) {
                    AsyncImage(
                        model = entry.url,
                        contentDescription = entry.name,
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.field.surface100, MaterialTheme.shapes.small)
                            .clickable(onClick = onPreview)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.field.surface100, MaterialTheme.shapes.small),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            fileIcon(entry),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FileActions(
                entry = entry,
                inlineOpen = inlineOpen,
                transcriptOpen = transcriptOpen,
                saving = saving,
                onToggleInline = onToggleInline,
                onToggleTranscript = onToggleTranscript,
                onPreview = onPreview,
                onSave = onSave
            )

            val body = entry.content
            if (inlineOpen && body != null) {
                ScrollableTextBox { PlainTextBody(body) }
            }

            if (transcriptOpen) {
                when {
                    transcriptLoading && transcript == null -> Text(
                        "Loading transcript…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    transcript != null -> ScrollableTextBox { TranscriptBody(transcript) }
                    else -> Text(
                        "No transcript content found for this file.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileActions(
    entry: DataTreeEntryDto,
    inlineOpen: Boolean,
    transcriptOpen: Boolean,
    saving: Boolean,
    onToggleInline: () -> Unit,
    onToggleTranscript: () -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit
) {
    // Playback is opt-in rather than inline: the web can afford <audio preload="none"> on every
    // row, but a Compose AudioPlayer prepares its MediaPlayer the moment it composes, which would
    // start buffering every recording a folder holds as the list scrolls past on field data.
    val previewable = entry.mediaType in setOf("IMAGE", "VIDEO", "AUDIO") && !entry.url.isNullOrBlank()
    if (entry.content == null && !entry.transcriptAvailable && !previewable && entry.mediaId == null) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (entry.content != null) {
            TextButton(onClick = onToggleInline) { Text(if (inlineOpen) "Hide" else "View") }
        }
        if (entry.transcriptAvailable) {
            TextButton(onClick = onToggleTranscript) {
                Text(if (transcriptOpen) "Hide transcript" else "View transcript")
            }
        }
        if (previewable) {
            TextButton(onClick = onPreview) { Text("Open") }
        }
        if (entry.mediaId != null) {
            TextButton(onClick = onSave, enabled = !saving) { Text(if (saving) "Saving…" else "Save") }
        }
    }
}

/** A bounded, scrollable well for a long body — capped so it never swallows the whole page. */
@Composable
private fun ScrollableTextBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        SelectionContainer { content() }
    }
}

@Composable
private fun PlainTextBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.field.body,
        lineHeight = 18.sp
    )
}

/**
 * Transcripts are stored as Markdown (the refinement pass emits headings, bold speaker labels and
 * bullets). There is no Markdown renderer on this client and pulling one in for a read-only panel
 * is not worth the dependency, so the handful of constructs the pipeline actually produces are
 * rendered here: ATX headings, `**bold**`, `*italic*`, `` `code` ``, bullets and rules.
 */
@Composable
private fun TranscriptBody(markdown: String) {
    val lines = remember(markdown) { markdown.replace("\r\n", "\n").split("\n") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                line.trimStart().startsWith("---") && line.trim().all { it == '-' } ->
                    HorizontalDivider(color = MaterialTheme.field.hairline)
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    Text(
                        inlineMarkdown(line.drop(level).trim()),
                        style = if (level <= 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body)
                    Text(
                        inlineMarkdown(line.trimStart().drop(2)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.field.body,
                        lineHeight = 18.sp
                    )
                }
                else -> Text(
                    inlineMarkdown(line),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.field.body,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

private val INLINE_MARKDOWN = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`")

/** `**bold**`, `*italic*` and `` `code` `` inside one line; everything else is copied verbatim. */
private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    INLINE_MARKDOWN.findAll(text).forEach { match ->
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val bold = match.groupValues[1]
        val italic = match.groupValues[2]
        val code = match.groupValues[3]
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FieldBodyFontFamily)) { append(code) }
            else -> append(match.value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}
