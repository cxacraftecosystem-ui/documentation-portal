package com.fieldrepository.app.explorer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerAudioLines
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

interface ExplorerMediaLoader {
    suspend fun source(entry: ExplorerEntry): String?
    suspend fun transcript(mediaId: String): Transcript?
}

@Composable
internal fun ExplorerMediaDialog(
    entries: List<ExplorerEntry>,
    start: Int,
    startSeconds: Double = 0.0,
    total: Int = entries.size,
    onSave: ((ExplorerEntry) -> Unit)?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ExplorerMediaContent(entries, start, startSeconds, total, onSave, onDismiss, Modifier.safeDrawingPadding())
    }
}

@Composable
internal fun ExplorerMediaContent(
    entries: List<ExplorerEntry>,
    start: Int,
    startSeconds: Double = 0.0,
    total: Int = entries.size,
    onSave: ((ExplorerEntry) -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    loader: ExplorerMediaLoader? = null,
) {
    var index by rememberSaveable { mutableStateOf(start.coerceIn(0, (entries.size - 1).coerceAtLeast(0))) }
    val entry = entries.getOrNull(index) ?: return
    Surface(Modifier.fillMaxSize().testTag("explorer.preview.media"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().then(modifier)) {
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
                loader = loader,
            )
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
    loader: ExplorerMediaLoader? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val url by produceState<String?>(initialValue = null, entry.key) {
        value = if (loader != null) loader.source(entry) else withContext(Dispatchers.IO) {
            ExplorerPaths.preview(entry)?.let { ExplorerDownloads.resolveSigned(context, it) } ?: entry.url?.takeIf { it.isNotBlank() }
        }
    }
    val transcript by produceState<TranscriptState>(initialValue = TranscriptState.Loading, entry.key) {
        val id = entry.mediaId
        value = if (id == null || !entry.transcriptAvailable) {
            TranscriptState.None
        } else {
            try {
                (if (loader != null) loader.transcript(id) else withContext(Dispatchers.IO) { TranscriptPortal.repository(context)?.load(id)?.view })
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

    val ready = transcript as? TranscriptState.Ready
    val turns = remember(ready) { ready?.view?.turns?.sortedBy { it.startMs }.orEmpty() }
    val mediaId = entry.mediaId
    val audioType = entry.mediaType == "AUDIO"
    val title = if (audioType) ExplorerNames.pretty(entry.name) else ExplorerNames.base(entry.name)
    val length = if (audioType) (ready?.view?.durationMs ?: 0L).takeIf { it > 0 }?.let { ExplorerAudioLines.clock(it) } else null
    val meta = listOfNotNull(entry.mediaType?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "File", ExplorerBytes.format(entry.sizeBytes), length, position).joinToString(" · ")

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = ExplorerUiText.close }.testTag("explorer.preview.close"),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onSave != null) {
            Box(
                Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary)
                    .clickable(role = Role.Button, onClick = onSave).padding(horizontal = 14.dp).testTag("explorer.preview.save"),
                contentAlignment = Alignment.Center,
            ) { Text(ExplorerUiText.save, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary) }
        }
    }

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
    if (audioType) {
        if (transcript is TranscriptState.Loading) {
            Box(Modifier.fillMaxWidth().weight(1f)) { LoadingLine(ExplorerUiText.loadingTranscript) }
        } else {
            AudioPane(entry, ready?.view, startSeconds)
        }
        bottomBar()
        return
    }
    val playback = rememberTranscriptPlayback(source?.let { Uri.parse(it) })
    LaunchedEffect(entry.key) { if (ExplorerAudio.state.value.playing) ExplorerAudio.toggle() }
    LaunchedEffect(playback, startSeconds) {
        if (startSeconds > 0 && playback.available) playback.seek(startSeconds, play = true)
    }
    var started by remember(entry.key) { mutableStateOf(false) }
    LaunchedEffect(playback.playing) { if (playback.playing) started = true }
    val positionMs = (playback.positionSeconds * 1000).toLong()
    val active = turns.indexOfLast { it.startMs <= positionMs }.takeIf { it >= 0 && positionMs < turns[it].endMs + 1_500 }
    val duration = playback.durationSeconds.takeIf { it > 0 } ?: ((ready?.view?.durationMs ?: 0L) / 1000.0)
    val english = tab == PreviewTab.ENGLISH

    Box(Modifier.fillMaxWidth().height(219.dp).background(Color(0xFF12101C)).testTag("explorer.preview.stage")) {
        if (source != null) TranscriptVideoSurface(playback, Modifier.fillMaxSize())
        if (!started) Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF4B3A2E), Color(0xFF1A1520)))))
        if (!playback.playing) {
            Box(
                Modifier.align(Alignment.Center).size(60.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
                    .clickable(enabled = playback.available, role = Role.Button, onClick = playback::toggle)
                    .semantics { contentDescription = ExplorerUiText.play }.testTag("explorer.preview.play"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp)) }
        } else {
            Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClick = playback::toggle).semantics { contentDescription = ExplorerUiText.pause }.testTag("explorer.preview.pause"))
        }
        active?.let { i ->
            val line = if (english) turns[i].translation.text?.ifBlank { null } ?: turns[i].text else turns[i].text
            Text(
                line,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = Color(0xFFF2F0F9),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 34.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xC712101C)).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ExplorerUiText.clock(playback.positionSeconds), color = Color(0xFFCFCBE0), fontSize = 11.sp)
            val fraction = if (duration > 0) (playback.positionSeconds / duration).toFloat().coerceIn(0f, 1f) else 0f
            Box(
                Modifier.weight(1f).height(16.dp).pointerInput(duration) {
                    detectTapGestures { offset -> if (duration > 0) playback.seek((offset.x / size.width).coerceIn(0f, 1f) * duration, play = playback.playing) }
                }.testTag("explorer.preview.seek"),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0xFF3A3651)))
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(CircleShape).background(Color(0xFFC090F5)))
            }
            Text(ExplorerUiText.clock(duration), color = Color(0xFFCFCBE0), fontSize = 11.sp)
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SegmentTabs(
            listOf(PreviewTab.TRANSCRIPT to ExplorerUiText.transcriptTab, PreviewTab.ENGLISH to ExplorerUiText.englishTab, PreviewTab.DETAILS to ExplorerUiText.detailsTab),
            tab,
            "explorer.preview.tab",
        ) { tab = it }
        if (ready != null && tab != PreviewTab.DETAILS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ready.view.speakers.take(4).forEach { s ->
                    Box(Modifier.size(10.dp).background(speakerColor(s.colorKey), CircleShape))
                    Text(s.name?.ifBlank { null } ?: s.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (follow) "✓ " + ExplorerUiText.followsVideo else ExplorerUiText.follow,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = if (follow) (if (MaterialTheme.field.isDark) AudioColors.followDark else AudioColors.follow) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(role = Role.Switch) { follow = !follow }.padding(vertical = 6.dp).testTag("explorer.preview.follow"),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp)) {
        when {
            tab == PreviewTab.DETAILS -> Box(Modifier.padding(horizontal = 8.dp)) { Details(entry, ready?.view) }
            transcript is TranscriptState.Loading -> LoadingLine(ExplorerUiText.loadingTranscript)
            ready == null -> HintLine(ExplorerUiText.noTranscript)
            else -> {
                val list = rememberLazyListState()
                LaunchedEffect(active, follow) {
                    if (follow && active != null) runCatching { list.animateScrollToItem((active - 1).coerceAtLeast(0)) }
                }
                val (markBg, _) = explorerMark()
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("explorer.preview.segments"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(turns, key = { _, t -> t.id }) { i, turn ->
                        val who = ready.view.speakers.firstOrNull { it.speakerKey == turn.speakerKey }
                        val text = if (english) turn.translation.text?.ifBlank { null } ?: ExplorerUiText.noEnglish else turn.text
                        Segment(
                            clock = ExplorerUiText.clock(turn.startMs / 1000.0),
                            speaker = turn.speakerName.ifBlank { who?.name ?: who?.label.orEmpty() },
                            speakerColor = speakerColor(turn.colorKey ?: who?.colorKey),
                            text = AnnotatedString(text),
                            current = i == active,
                            markBg = markBg,
                            textSize = 14,
                            onClick = { playback.seek(turn.startMs / 1000.0, play = true) },
                        )
                    }
                }
            }
        }
    }
    bottomBar()
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
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.field.hairline))
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SquareButton(ExplorerUiText.previous, "‹", canPrevious, "explorer.preview.previous", onPrevious)
        if (onDocx != null) BarButton(ExplorerUiText.transcriptDocx, "explorer.preview.docx", onDocx) else Spacer(Modifier.weight(1f))
        if (onShare != null) BarButton(ExplorerUiText.share, "explorer.preview.share", onShare)
        SquareButton(ExplorerUiText.next, "›", canNext, "explorer.preview.next", onNext)
    }
}

@Composable
private fun RowScope.BarButton(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center) }
}

@Composable
private fun SquareButton(description: String, glyph: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = 18.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) }
}
