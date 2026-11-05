package com.fieldrepository.app.explorer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fieldrepository.app.explorer.data.ExplorerBytes
import com.fieldrepository.app.explorer.data.ExplorerDownloads
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptNames
import com.fieldrepository.app.transcript.portal.TranscriptPortal
import com.fieldrepository.app.transcript.ui.TranscriptVideoSurface
import com.fieldrepository.app.transcript.ui.rememberTranscriptPlayback
import com.fieldrepository.app.transcript.ui.shareDir
import com.fieldrepository.app.transcript.ui.shareFiles
import com.fieldrepository.app.transcript.ui.speakerColor
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class PreviewTab { TRANSCRIPT, ENGLISH, DETAILS }

private sealed interface TranscriptState {
    data object Loading : TranscriptState
    data object None : TranscriptState
    data class Ready(val view: Transcript) : TranscriptState
}

internal fun playable(entry: ExplorerEntry): Boolean =
    entry.mediaType in setOf("VIDEO", "AUDIO", "IMAGE") && (entry.mediaId != null || !entry.url.isNullOrBlank())

@Composable
internal fun ExplorerMediaDialog(
    entries: List<ExplorerEntry>,
    start: Int,
    startSeconds: Double = 0.0,
    total: Int = entries.size,
    onSave: ((ExplorerEntry) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var index by rememberSaveable { mutableStateOf(start.coerceIn(0, (entries.size - 1).coerceAtLeast(0))) }
    val entry = entries.getOrNull(index) ?: return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("explorer.preview.media"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                MediaPane(
                    entry = entry,
                    position = ExplorerUiText.positionOf(entries.indexOf(entry) + 1, maxOf(total, entries.size)),
                    startSeconds = if (index == start) startSeconds else 0.0,
                    onSave = onSave?.let { save -> { save(entry) } },
                    onDismiss = onDismiss,
                    canPrevious = index > 0,
                    canNext = index < entries.lastIndex,
                    onPrevious = { if (index > 0) index-- },
                    onNext = { if (index < entries.lastIndex) index++ },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.MediaPane(
    entry: ExplorerEntry,
    position: String,
    startSeconds: Double,
    onSave: (() -> Unit)?,
    onDismiss: () -> Unit,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val url by produceState<String?>(initialValue = null, entry.key) {
        value = withContext(Dispatchers.IO) {
            ExplorerPaths.preview(entry)?.let { ExplorerDownloads.resolveSigned(context, it) } ?: entry.url?.takeIf { it.isNotBlank() }
        }
    }
    val transcript by produceState<TranscriptState>(initialValue = TranscriptState.Loading, entry.key) {
        val id = entry.mediaId
        value = if (id == null || !entry.transcriptAvailable) {
            TranscriptState.None
        } else {
            try {
                withContext(Dispatchers.IO) { TranscriptPortal.repository(context)?.load(id)?.view }
                    ?.takeIf { it.turns.isNotEmpty() }?.let { TranscriptState.Ready(it) } ?: TranscriptState.None
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                TranscriptState.None
            }
        }
    }
    var tab by rememberSaveable(entry.key) { mutableStateOf(PreviewTab.TRANSCRIPT) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var exporting by remember(entry.key) { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("explorer.preview.close")) { Icon(Icons.Filled.Close, contentDescription = ExplorerUiText.close) }
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(fileMeta(entry), position).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onSave != null) {
            OutlinedButton(onClick = onSave, modifier = Modifier.heightIn(min = 40.dp).padding(end = 8.dp).testTag("explorer.preview.save")) { Text(ExplorerUiText.save) }
        }
    }

    val ready = transcript as? TranscriptState.Ready
    val turns = remember(ready) { ready?.view?.turns?.sortedBy { it.startMs }.orEmpty() }
    val mediaId = entry.mediaId
    val bottomBar: @Composable () -> Unit = {
    NavBar(
        canPrevious, canNext, onPrevious, onNext,
        onDocx = if (ready != null && mediaId != null && !exporting) ({
            exporting = true
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val got = TranscriptPortal.backend(context).download(mediaId, "docx", CopyTrack.BOTH)
                        File(shareDir(context), got.name ?: TranscriptNames.fileName(TranscriptNames.whatFor("docx"), "docx")).also { it.writeBytes(got.bytes) }
                    }
                }.onSuccess { shareFiles(context, listOf(it), null) }
                exporting = false
            }
        }) else null,
        onShare = if (ready != null) ({
            val text = turns.joinToString("\n") { "[${ExplorerUiText.clock(it.startMs / 1000.0)}] ${it.speakerName}: ${it.text}" }
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, entry.name); putExtra(Intent.EXTRA_TEXT, text) }
            runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) else null,
    )
    }
    val source = url
    if (entry.mediaType == "IMAGE") {
        Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
            if (source == null) FieldCircularProgressIndicator() else coil3.compose.AsyncImage(model = source, contentDescription = entry.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        NavBar(canPrevious, canNext, onPrevious, onNext, null, null)
        return
    }
    if (entry.mediaType == "AUDIO") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { Tabs(tab) { tab = it } }
        when {
            transcript is TranscriptState.Loading -> Box(Modifier.fillMaxWidth().weight(1f)) { LoadingLine(ExplorerUiText.loadingTranscript) }
            tab == PreviewTab.DETAILS -> Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) { Details(entry, ready?.view) }
            else -> AudioPane(entry, ready?.view, startSeconds, english = tab == PreviewTab.ENGLISH)
        }
        bottomBar()
        return
    }
    val playback = rememberTranscriptPlayback(source?.let { Uri.parse(it) })
    LaunchedEffect(entry.key) { if (com.fieldrepository.app.explorer.data.ExplorerAudio.state.value.playing) com.fieldrepository.app.explorer.data.ExplorerAudio.toggle() }
    LaunchedEffect(playback, startSeconds) {
        if (startSeconds > 0 && playback.available) playback.seek(startSeconds, play = true)
    }
    val positionMs = (playback.positionSeconds * 1000).toLong()
    val active = turns.indexOfLast { it.startMs <= positionMs }.takeIf { it >= 0 && positionMs < turns[it].endMs + 1_500 }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entry.mediaType == "VIDEO") {
            if (source == null) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) { FieldCircularProgressIndicator() }
            } else {
                TranscriptVideoSurface(playback)
            }
        } else {
            Box(Modifier.fillMaxWidth().height(96.dp).background(MaterialTheme.field.surface100, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Audiotrack, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = playback::toggle, enabled = playback.available, modifier = Modifier.testTag("explorer.preview.play")) {
                Icon(if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playback.playing) ExplorerUiText.pause else ExplorerUiText.play, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                active?.let { Text(turns[it].text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.field.body) }
                val duration = playback.durationSeconds
                Slider(
                    value = if (duration > 0) (playback.positionSeconds / duration).toFloat().coerceIn(0f, 1f) else 0f,
                    onValueChange = { f -> if (duration > 0) playback.seek(f * duration, play = playback.playing) },
                    enabled = playback.available && duration > 0,
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                )
                Row {
                    Text(ExplorerUiText.clock(playback.positionSeconds), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(ExplorerUiText.clock(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Tabs(tab) { tab = it }
        if (ready != null && tab != PreviewTab.DETAILS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ready.view.speakers.take(4).forEach { s ->
                    Box(Modifier.size(8.dp).background(speakerColor(s.colorKey), MaterialTheme.shapes.extraLarge))
                    Text(s.name?.ifBlank { null } ?: s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text(if (follow) "✓ " + ExplorerUiText.followsVideo else ExplorerUiText.follow) }, modifier = Modifier.testTag("explorer.preview.follow"))
            }
        }
    }
    Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
        when {
            tab == PreviewTab.DETAILS -> Details(entry, ready?.view)
            transcript is TranscriptState.Loading -> LoadingLine(ExplorerUiText.loadingTranscript)
            ready == null -> HintLine(ExplorerUiText.noTranscript)
            else -> {
                val list = rememberLazyListState()
                LaunchedEffect(active, follow) {
                    if (follow && active != null) runCatching { list.animateScrollToItem(active) }
                }
                val (markBg, _) = explorerMark()
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("explorer.preview.segments"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(turns, key = { _, t -> t.id }) { i, turn ->
                        val on = i == active
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (on) markBg else Color.Transparent, MaterialTheme.shapes.medium)
                                .clickable(role = Role.Button) { playback.seek(turn.startMs / 1000.0, play = true) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(ExplorerUiText.clock(turn.startMs / 1000.0), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(44.dp))
                            Column(Modifier.weight(1f)) {
                                val who = ready.view.speakers.firstOrNull { it.speakerKey == turn.speakerKey }
                                Text(turn.speakerName.ifBlank { who?.name ?: who?.label.orEmpty() }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = speakerColor(turn.colorKey ?: who?.colorKey))
                                val text = if (tab == PreviewTab.ENGLISH) turn.translation.text?.ifBlank { null } ?: ExplorerUiText.noEnglish else turn.text
                                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
                            }
                        }
                    }
                }
            }
        }
    }
    bottomBar()
}

@Composable
private fun Tabs(current: PreviewTab, onPick: (PreviewTab) -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.medium).padding(3.dp)) {
        listOf(PreviewTab.TRANSCRIPT to ExplorerUiText.transcriptTab, PreviewTab.ENGLISH to ExplorerUiText.englishTab, PreviewTab.DETAILS to ExplorerUiText.detailsTab).forEach { (tab, label) ->
            val on = tab == current
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.small)
                    .clickable { onPick(tab) }
                    .semantics { role = Role.Tab; selected = on }
                    .testTag("explorer.preview.tab.${tab.name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun Details(entry: ExplorerEntry, view: Transcript?) {
    val rows = listOfNotNull(
        "Name" to entry.name,
        "Type" to fileMeta(entry),
        ExplorerBytes.format(entry.sizeBytes)?.let { "Size" to it },
        view?.durationMs?.let { "Length" to ExplorerUiText.clock(it / 1000.0) },
        view?.languages?.takeIf { it.isNotEmpty() }?.let { l -> "Languages" to l.joinToString(", ") { it.label.ifBlank { it.code } } },
        view?.speakers?.takeIf { it.isNotEmpty() }?.let { s -> "Speakers" to s.joinToString(", ") { it.name?.ifBlank { null } ?: it.label } },
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("explorer.preview.details"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (k, v) ->
            Column {
                Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun NavBar(canPrevious: Boolean, canNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, onDocx: (() -> Unit)?, onShare: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onPrevious, enabled = canPrevious, modifier = Modifier.testTag("explorer.preview.previous")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = ExplorerUiText.previous)
        }
        if (onDocx != null) {
            Button(onClick = onDocx, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("explorer.preview.docx")) { Text(ExplorerUiText.transcriptDocx, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onShare != null) {
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("explorer.preview.share")) { Text(ExplorerUiText.share) }
        }
        IconButton(onClick = onNext, enabled = canNext, modifier = Modifier.testTag("explorer.preview.next")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = ExplorerUiText.next)
        }
    }
}
