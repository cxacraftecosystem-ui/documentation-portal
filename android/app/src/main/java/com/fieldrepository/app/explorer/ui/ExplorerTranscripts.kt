package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.explorer.data.ExplorerSort
import com.fieldrepository.app.explorer.data.ExplorerSortMemory
import com.fieldrepository.app.explorer.data.ExplorerSortStore
import com.fieldrepository.app.explorer.data.ExplorerSorts
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.DataRecording
import com.fieldrepository.app.transcript.data.DataTranscriptFilters
import com.fieldrepository.app.transcript.data.TranscriptBackend
import com.fieldrepository.app.transcript.data.TranscriptFormat
import com.fieldrepository.app.transcript.data.TranscriptNames
import com.fieldrepository.app.transcript.portal.TranscriptPortal
import com.fieldrepository.app.transcript.ui.shareDir
import com.fieldrepository.app.transcript.ui.shareFiles
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.fieldrepository.app.transcript.data.PipelineStatus
import com.fieldrepository.app.transcript.data.TranscriptSummary
import com.fieldrepository.app.explorer.data.ExplorerMore
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerTranscriptItem
import com.fieldrepository.app.explorer.data.ExplorerTranscriptList

enum class TranscriptStatus { READY, TRANSLATING, TRANSCRIBING, FAILED }

data class TranscriptRowModel(
    val mediaId: String,
    val name: String,
    val mediaType: String,
    val line: String?,
    val languages: List<String>,
    val speakers: Int,
    val durationMs: Long?,
    val status: TranscriptStatus,
    val percent: Int?,
    val hasText: Boolean,
    val message: String? = null,
)

object ExplorerTranscriptStatus {
    fun countLabel(label: String, count: Int?, more: Boolean = false): String {
        if (count == null) return "$label …"
        val n = String.format(java.util.Locale.ROOT, "%,d", count)
        return if (more) "$label $n+" else "$label $n"
    }

    private val pending = setOf("PENDING", "QUEUED", "RUNNING", "TRANSLATING")
    private val finished = setOf("COMPLETED", "READY", "SKIPPED_DUPLICATE")

    fun of(r: DataRecording): Pair<TranscriptStatus, Int?> {
        val status = r.transcriptStatus?.uppercase()
        if (status == "FAILED") return TranscriptStatus.FAILED to null
        if (status != null && status !in finished && r.turns.isEmpty()) return TranscriptStatus.TRANSCRIBING to null
        val waiting = r.turns.count { it.translationStatus?.uppercase() in pending }
        if (r.turns.isNotEmpty() && waiting > 0) return TranscriptStatus.TRANSLATING to ((r.turns.size - waiting) * 100 / r.turns.size)
        return TranscriptStatus.READY to null
    }

    fun of(pipeline: String?): TranscriptStatus = when (pipeline?.uppercase()) {
        PipelineStatus.READY, null -> TranscriptStatus.READY
        PipelineStatus.TRANSLATING -> TranscriptStatus.TRANSLATING
        PipelineStatus.NEEDS_ATTENTION, "FAILED" -> TranscriptStatus.FAILED
        else -> TranscriptStatus.TRANSCRIBING
    }

    fun row(r: DataRecording): TranscriptRowModel {
        val (status, percent) = of(r)
        return TranscriptRowModel(
            mediaId = r.mediaId,
            name = r.file?.ifBlank { null } ?: r.mediaId,
            mediaType = r.mediaType.uppercase(),
            line = r.turns.firstOrNull()?.text?.take(80)?.let { "“…$it…”" },
            languages = r.languages.map { it.label.ifBlank { it.code } },
            speakers = r.speakers.size,
            durationMs = r.durationMs,
            status = status,
            percent = percent,
            hasText = r.turns.isNotEmpty(),
        )
    }

    fun row(item: ExplorerTranscriptItem, summary: TranscriptSummary?): TranscriptRowModel = TranscriptRowModel(
        mediaId = item.mediaId,
        name = item.name.ifBlank { item.mediaId },
        mediaType = item.mediaType.uppercase(),
        line = item.snippet?.takeIf { it.isNotBlank() }?.let { "“…$it…”" },
        languages = summary?.languages.orEmpty().map { it.label.ifBlank { it.code } },
        speakers = summary?.speakerCount ?: 0,
        durationMs = summary?.durationMs,
        status = if (summary == null && item.hasText) TranscriptStatus.READY else of(summary?.status),
        percent = summary?.translation?.percent,
        hasText = item.hasText,
        message = summary?.statusMessage?.takeIf { it.isNotBlank() },
    )
}

private class TranscriptPage(val rows: List<TranscriptRowModel>, val cursor: String?, val folder: Boolean, val nextOffset: Int? = null, val sorts: List<String> = emptyList(), val sent: String? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExplorerTranscriptsTab(
    path: String,
    initialQuery: String = "",
    status: String? = null,
    online: Boolean,
    offeredLanguages: List<Pair<String, String>>,
    onRead: (TranscriptRowModel) -> Unit,
    onBundle: (() -> Unit)?,
    onMessage: (String) -> Unit,
    backend: TranscriptBackend? = null,
    more: ExplorerMore? = null,
    sortStore: ExplorerSortStore? = null,
) {
    val context = LocalContext.current
    val server = remember(backend) { backend ?: TranscriptPortal.backend(context) }
    val sorts = remember(ExplorerIdentity(sortStore)) { sortStore ?: ExplorerSortMemory(context, ExplorerPortal.userId(context)) }
    var chosen by remember(sorts) { mutableStateOf(runCatching { sorts.read(ExplorerSorts.TRANSCRIPTS_LEVEL) }.getOrNull()) }
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    val extras = remember(ExplorerIdentity(more)) { more ?: ExplorerMoreApi.of(context) }
    val scope = rememberCoroutineScope()
    var q by rememberSaveable(path, initialQuery) { mutableStateOf(initialQuery) }
    var language by rememberSaveable { mutableStateOf("") }
    var shownStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var page by remember(path) { mutableStateOf<TranscriptPage?>(null) }
    var loading by remember(path) { mutableStateOf(true) }
    var failed by remember(path) { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    val filters = DataTranscriptFilters(q = q, language = language).toQuery()

    suspend fun folderRows(list: ExplorerTranscriptList): List<TranscriptRowModel> {
        val summaries = list.items.map { it.mediaId }.chunked(50).flatMap { ids -> runCatching { server.batch(ids) }.getOrDefault(emptyList()) }.associateBy { it.mediaId }
        val rows = list.items.map { ExplorerTranscriptStatus.row(it, summaries[it.mediaId]) }
        return rows.filter { r -> language.isBlank() || summaries[r.mediaId]?.languages?.any { it.code == language } == true }
    }

    LaunchedEffect(path, status, filters, attempt, chosen) {
        if (q.isNotBlank()) delay(350)
        loading = true
        failed = false
        try {
            page = try {
                val sorted = ExplorerSorts.fetch(chosen, ExplorerSorts.TRANSCRIPTS, { l: ExplorerTranscriptList -> l.sorts }) { sort ->
                    extras.transcripts(path, q.trim().ifBlank { null }, status, sort = sort)
                }
                val list = sorted.value
                TranscriptPage(folderRows(list), null, true, list.nextOffset, ExplorerSorts.offered(list.sorts), sorted.sent)
            } catch (missing: retrofit2.HttpException) {
                if (missing.code() != 404) throw missing
                val data = server.dataPage(filters, null)
                TranscriptPage(data.items.map { ExplorerTranscriptStatus.row(it) }, data.nextCursor, false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failed = true
        }
        loading = false
    }

    fun docx(r: TranscriptRowModel) {
        busy = r.mediaId
        scope.launch {
            val got = runCatching {
                withContext(Dispatchers.IO) {
                    val file = server.download(r.mediaId, "docx", CopyTrack.BOTH)
                    File(shareDir(context), file.name ?: TranscriptNames.fileName(TranscriptNames.whatFor("docx"), "docx")).also { it.writeBytes(file.bytes) }
                }
            }
            busy = null
            got.onSuccess { shareFiles(context, listOf(it), null) }.onFailure { if (it !is CancellationException) onMessage(ExplorerUiText.transcriptsFailed) }
        }
    }

    fun retry(r: TranscriptRowModel) {
        busy = r.mediaId
        scope.launch {
            val got = runCatching { server.retry(r.mediaId) }
            busy = null
            got.onSuccess { onMessage(ExplorerUiText.retryAsked); attempt++ }.onFailure { if (it !is CancellationException) onMessage(ExplorerUiText.retryFailed) }
        }
    }

    fun export(kind: String) {
        busy = kind
        scope.launch {
            val got = runCatching {
                withContext(Dispatchers.IO) {
                    val tmp = File(shareDir(context), "dataset-$kind.csv.part")
                    val what = mapOf("recordings" to "Transcripts", "turns" to "Transcript-Turns", "glossary" to "Transcript-Glossary").getValue(kind)
                    val name = server.dataFile(kind, "csv", filters, tmp) ?: Naming.fileName(entity = "Dataset", what = what, ext = "csv", date = LocalDate.now().toString())
                    File(shareDir(context), name).also { out -> out.delete(); tmp.renameTo(out) }
                }
            }
            busy = null
            got.onSuccess { shareFiles(context, listOf(it), null) }.onFailure { if (it !is CancellationException) onMessage(ExplorerUiText.transcriptsFailed) }
        }
    }

    val rows = page?.rows.orEmpty()
    val sortKeys = page?.sorts.orEmpty()
    val sort = ExplorerSorts.resolve(chosen, sortKeys, ExplorerSorts.TRANSCRIPTS)
    val counts = TranscriptStatus.entries.associateWith { s -> rows.count { it.status == s } }
    val countsKnown = !loading && page != null
    val moreToLoad = page?.folder == true && page?.nextOffset != null
    fun pillCount(status: TranscriptStatus): Int? = if (countsKnown) counts[status] ?: 0 else null
    val shown = rows.filter { shownStatus == null || it.status.name == shownStatus }

    Column(Modifier.fillMaxWidth().testTag("explorer.transcripts"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { menu = true }, enabled = online && busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("explorer.transcripts.export")) {
                    Text(ExplorerUiText.exportCsv, maxLines = 1)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DataTranscriptFilters.KINDS.forEach { kind ->
                        DropdownMenuItem(text = { Text("${kind.replaceFirstChar { it.uppercase() }} (.csv)") }, onClick = { menu = false; export(kind) }, modifier = Modifier.testTag("explorer.transcripts.export.$kind"))
                    }
                }
            }
            if (onBundle != null) {
                Button(onClick = onBundle, enabled = online && rows.isNotEmpty(), modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("explorer.transcripts.zip")) {
                    Text(if (countsKnown) ExplorerUiText.zipCount(rows.size) else ExplorerUiText.zipPending, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            singleLine = true,
            placeholder = { Text(ExplorerUiText.transcriptsSearch) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            modifier = Modifier.fillMaxWidth().testTag("explorer.transcripts.q"),
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(ExplorerTranscriptStatus.countLabel(ExplorerUiText.all, if (countsKnown) rows.size else null, moreToLoad), shownStatus == null, "all") { shownStatus = null }
            Pill(ExplorerTranscriptStatus.countLabel(ExplorerUiText.ready, pillCount(TranscriptStatus.READY)), shownStatus == TranscriptStatus.READY.name, "ready") { shownStatus = TranscriptStatus.READY.name }
            Pill(ExplorerTranscriptStatus.countLabel(ExplorerUiText.translating, pillCount(TranscriptStatus.TRANSLATING)), shownStatus == TranscriptStatus.TRANSLATING.name, "translating") { shownStatus = TranscriptStatus.TRANSLATING.name }
            Pill(ExplorerTranscriptStatus.countLabel(ExplorerUiText.failed, pillCount(TranscriptStatus.FAILED)), shownStatus == TranscriptStatus.FAILED.name, "failed") { shownStatus = TranscriptStatus.FAILED.name }
            var open by remember { mutableStateOf(false) }
            Box {
                Pill((offeredLanguages.firstOrNull { it.first == language }?.second ?: ExplorerUiText.language) + " ▾", language.isNotBlank(), "language") { open = true }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(ExplorerUiText.anyLanguage) }, onClick = { open = false; language = "" })
                    offeredLanguages.forEach { (code, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { open = false; language = code }, modifier = Modifier.testTag("transcript.data.language.$code"))
                    }
                }
            }
        }
        if (sortKeys.isNotEmpty() && rows.isNotEmpty()) ExplorerSortLine(sort, sortKeys, onClick = { sortOpen = true }, tag = "explorer.transcripts.sort")
        if (loading) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) StatusCard(ExplorerStatus.FAILED, ExplorerUiText.transcriptsFailed, action = ExplorerUiText.tryNow, tag = "explorer.transcripts.failed") { attempt++ }
        if (!loading && !failed && shown.isEmpty()) HintLine(ExplorerUiText.noTranscripts)
        shown.forEach { r -> TranscriptRow(r, busy == r.mediaId, online, onRead = { onRead(r) }, onDocx = { docx(r) }, onRetry = { retry(r) }) }
        val after = page?.nextOffset
        if (after != null && page?.folder == true) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val sent = page?.sent
                        runCatching { extras.transcripts(path, q.trim().ifBlank { null }, status, after, sort = sent) }
                            .onSuccess { got ->
                                if (page?.sent != sent) return@onSuccess
                                val have = rows.map { it.mediaId }.toSet()
                                page = TranscriptPage(rows + folderRows(got).filter { it.mediaId !in have }, null, true, got.nextOffset, sortKeys, sent)
                            }
                            .onFailure { if (it is CancellationException) throw it; onMessage(ExplorerUiText.transcriptsFailed) }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("transcript.folder.more"),
            ) { Text(ExplorerUiText.moreTranscripts) }
        }
        val next = page?.cursor
        if (next != null && page?.folder == false) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        runCatching { server.dataPage(filters, next) }
                            .onSuccess { got ->
                                val have = rows.map { it.mediaId }.toSet()
                                page = TranscriptPage(rows + got.items.filter { it.mediaId !in have }.map { ExplorerTranscriptStatus.row(it) }, got.nextCursor, false)
                            }
                            .onFailure { if (it is CancellationException) throw it; onMessage(ExplorerUiText.transcriptsFailed) }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("transcript.data.more"),
            ) { Text(ExplorerUiText.moreTranscripts) }
        }
    }
    if (sortOpen && sortKeys.isNotEmpty()) {
        ExplorerSortSheet(
            current = sort,
            keys = sortKeys,
            onPick = { next: ExplorerSort ->
                chosen = next
                runCatching { sorts.write(ExplorerSorts.TRANSCRIPTS_LEVEL, next) }
            },
            onDismiss = { sortOpen = false },
        )
    }
}

@Composable
private fun Pill(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
        contentColor = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.heightIn(min = 36.dp).border(if (on) 0.dp else 1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.extraLarge)
            .clickable(onClick = onClick).testTag("explorer.transcripts.pill.$tag"),
    ) {
        Box(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptRow(
    r: TranscriptRowModel,
    busy: Boolean,
    online: Boolean,
    onRead: () -> Unit,
    onDocx: () -> Unit,
    onRetry: () -> Unit,
) {
    val tone = explorerTone(if (r.status == TranscriptStatus.FAILED) ExplorerStatus.FAILED else ExplorerStatus.NEUTRAL)
    val content = tone?.content ?: MaterialTheme.colorScheme.onSurface
    val muted = tone?.muted ?: MaterialTheme.colorScheme.onSurfaceVariant
    val warn = explorerWarnTone()
    Column(
        Modifier.fillMaxWidth()
            .background(tone?.container ?: MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(1.dp, tone?.border ?: MaterialTheme.field.hairline, MaterialTheme.shapes.large)
            .padding(12.dp)
            .testTag("transcript.data.row.${r.mediaId}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val video = r.mediaType == "VIDEO"
            Box(
                Modifier.size(44.dp).background(if (tone != null) MaterialTheme.colorScheme.surface else if (video) MaterialTheme.colorScheme.primaryContainer else warn.container, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (video) "VID" else "AUD", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = tone?.accent ?: if (video) MaterialTheme.colorScheme.onPrimaryContainer else warn.muted)
            }
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val line = when (r.status) {
                    TranscriptStatus.FAILED -> r.message ?: ExplorerUiText.noSpeech
                    TranscriptStatus.TRANSLATING -> ExplorerUiText.translating
                    TranscriptStatus.TRANSCRIBING -> ExplorerUiText.transcribing
                    TranscriptStatus.READY -> listOfNotNull(
                        r.line,
                        r.languages.joinToString(" → ").ifBlank { null },
                        r.speakers.takeIf { it > 0 }?.let { ExplorerUiText.speakers(it) },
                    ).joinToString(" · ")
                }
                if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            r.durationMs?.let { Text(TranscriptFormat.length(it), style = MaterialTheme.typography.labelMedium, color = content) }
        }
        if (r.status == TranscriptStatus.TRANSLATING) {
            val percent = r.percent
            if (percent != null) FieldLinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
            else FieldLinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(r.status, r.percent)
            when (r.status) {
                TranscriptStatus.FAILED -> Button(
                    onClick = onRetry,
                    enabled = online && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = tone?.accent ?: MaterialTheme.colorScheme.error, contentColor = tone?.onAccent ?: MaterialTheme.colorScheme.onError),
                    modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.transcripts.retry.${r.mediaId}"),
                ) { Text(ExplorerUiText.tryAgain) }
                TranscriptStatus.TRANSLATING, TranscriptStatus.TRANSCRIBING -> OutlinedButton(onClick = onRead, enabled = r.hasText, modifier = Modifier.heightIn(min = 40.dp)) { Text(ExplorerUiText.readOriginal) }
                TranscriptStatus.READY -> {
                    OutlinedButton(onClick = onRead, modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.transcripts.read.${r.mediaId}")) { Text(ExplorerUiText.read) }
                    OutlinedButton(onClick = onDocx, enabled = online && !busy, modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.transcripts.docx.${r.mediaId}")) { Text(ExplorerUiText.docx) }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: TranscriptStatus, percent: Int?) {
    val saved = explorerTone(ExplorerStatus.SAVED)!!
    val failed = explorerTone(ExplorerStatus.FAILED)!!
    val warn = explorerWarnTone()
    val (bg, fg, label) = when (status) {
        TranscriptStatus.READY -> Triple(saved.container, saved.accent, "✓ " + ExplorerUiText.ready)
        TranscriptStatus.TRANSLATING -> Triple(warn.container, warn.muted, percent?.let { ExplorerUiText.translatingPercent(it) } ?: ExplorerUiText.translating)
        TranscriptStatus.TRANSCRIBING -> Triple(warn.container, warn.muted, ExplorerUiText.transcribing)
        TranscriptStatus.FAILED -> Triple(MaterialTheme.colorScheme.surface, failed.accent, ExplorerUiText.failed)
    }
    Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.background(bg, MaterialTheme.shapes.extraLarge).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = fg)
        }
    }
}
