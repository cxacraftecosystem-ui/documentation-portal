package com.fieldrepository.app.explorer.ui

import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerAudioItem
import com.fieldrepository.app.explorer.data.ExplorerAudioLine
import com.fieldrepository.app.explorer.data.ExplorerAudioLines
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.ui.speakerColor
import com.fieldrepository.app.ui.field

internal fun audioLines(view: Transcript?): List<ExplorerAudioLine> =
    view?.turns.orEmpty().sortedBy { it.startMs }.map { t ->
        val who = view?.speakers?.firstOrNull { it.speakerKey == t.speakerKey }
        ExplorerAudioLine(t.startMs, t.endMs, t.speakerName.ifBlank { who?.name ?: who?.label.orEmpty() }.ifBlank { null }, t.text)
    }

internal fun audioItem(entry: ExplorerEntry, url: String, lines: List<ExplorerAudioLine>): ExplorerAudioItem? {
    val id = entry.mediaId ?: return null
    return ExplorerAudioItem(mediaId = id, title = entry.name, subtitle = fileMeta(entry), url = url, lines = lines)
}

internal fun audioSource(entry: ExplorerEntry): String? = ExplorerPaths.preview(entry) ?: entry.url

internal fun isolatedSource(mediaId: String): String = "/api/data/media/$mediaId/preview?variant=isolated"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.AudioPane(entry: ExplorerEntry, view: Transcript?, startSeconds: Double, english: Boolean) {
    val context = LocalContext.current
    val audio by ExplorerAudio.state.collectAsState()
    val lines = audioLines(view)
    val mine = audio.item?.mediaId == entry.mediaId
    LaunchedEffect(entry.key) {
        val source = audioSource(entry) ?: return@LaunchedEffect
        val item = audioItem(entry, source, lines) ?: return@LaunchedEffect
        if (ExplorerAudio.state.value.item?.mediaId != item.mediaId) {
            ExplorerAudio.play(context, item, (startSeconds * 1000).toLong())
        } else if (startSeconds > 0) {
            ExplorerAudio.seekTo((startSeconds * 1000).toLong())
        }
    }
    val wave by produceState(initialValue = com.fieldrepository.app.explorer.data.ExplorerPeaks(), entry.mediaId) {
        val id = entry.mediaId ?: return@produceState
        value = runCatching { ExplorerMoreApi.of(context).peaks(id) }.getOrDefault(com.fieldrepository.app.explorer.data.ExplorerPeaks())
    }
    val peaks = wave.peaks
    var clear by androidx.compose.runtime.saveable.rememberSaveable(entry.key) { androidx.compose.runtime.mutableStateOf(false) }
    val position = if (mine) audio.positionMs else 0L
    val duration = if (mine) audio.durationMs else (view?.durationMs ?: 0L)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        if (peaks.isNotEmpty()) {
            Waveform(peaks, fraction, lines, duration) { f -> if (duration > 0) ExplorerAudio.seekTo((f * duration).toLong()) }
        } else {
            Slider(value = fraction, onValueChange = { f -> if (duration > 0) ExplorerAudio.seekTo((f * duration).toLong()) }, enabled = mine && duration > 0, modifier = Modifier.fillMaxWidth())
        }
        Row {
            Text(ExplorerAudioLines.clock(position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(ExplorerAudioLines.clock(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { ExplorerAudio.setSpeed(ExplorerAudio.nextSpeed(audio.speed)) }, enabled = mine, modifier = Modifier.heightIn(min = 48.dp).testTag("explorer.audio.speed")) {
                Text(speedLabel(audio.speed))
            }
            IconButton(onClick = { ExplorerAudio.seekBy(-ExplorerAudio.STEP_MS) }, enabled = mine, modifier = Modifier.size(56.dp).testTag("explorer.audio.back")) {
                Icon(Icons.Filled.Replay10, contentDescription = AudioText.back10, modifier = Modifier.size(30.dp))
            }
            FilledIconButton(onClick = { ExplorerAudio.toggle() }, enabled = mine, modifier = Modifier.size(72.dp).testTag("explorer.audio.play")) {
                Icon(if (audio.playing && mine) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (audio.playing && mine) ExplorerUiText.pause else ExplorerUiText.play, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { ExplorerAudio.seekBy(ExplorerAudio.STEP_MS) }, enabled = mine, modifier = Modifier.size(56.dp).testTag("explorer.audio.forward")) {
                Icon(Icons.Filled.Forward10, contentDescription = AudioText.forward10, modifier = Modifier.size(30.dp))
            }
        }
        if (wave.isolated && entry.mediaId != null) {
            com.fieldrepository.app.ui.AppToggle(
                label = AudioText.clearVoices,
                checked = clear,
                onCheckedChange = { on ->
                    clear = on
                    val source = if (on) isolatedSource(entry.mediaId) else audioSource(entry)
                    val item = source?.let { audioItem(entry, it, lines) }
                    if (item != null) ExplorerAudio.play(context, item, if (mine) audio.positionMs else 0L)
                },
                description = AudioText.clearVoicesNote,
                modifier = Modifier.fillMaxWidth().testTag("explorer.audio.clear"),
            )
        }
        if (mine && audio.failed) StatusCard(ExplorerStatus.FAILED, AudioText.failed)
        val speakers = view?.speakers.orEmpty()
        if (speakers.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                speakers.forEach { s ->
                    Row(
                        Modifier.background(MaterialTheme.field.surface50, MaterialTheme.shapes.extraLarge).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(8.dp).background(speakerColor(s.colorKey), MaterialTheme.shapes.extraLarge))
                        Text(s.name?.ifBlank { null } ?: s.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
        if (lines.isEmpty()) {
            HintLine(ExplorerUiText.noTranscript)
        } else {
            val active = if (mine) ExplorerAudioLines.index(lines, position) else -1
            val list = rememberLazyListState()
            LaunchedEffect(active) { if (active >= 0) runCatching { list.animateScrollToItem(active) } }
            val (markBg, _) = explorerMark()
            val turns = view?.turns.orEmpty().sortedBy { it.startMs }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("explorer.audio.lines"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(lines) { i, line ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (i == active) markBg else Color.Transparent, MaterialTheme.shapes.medium)
                            .clickable(role = Role.Button) { ExplorerAudio.seekTo(line.startMs) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(ExplorerAudioLines.clock(line.startMs), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(44.dp))
                        Column(Modifier.weight(1f)) {
                            line.speaker?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = speakerColor(turns.getOrNull(i)?.colorKey)) }
                            val text = if (english) turns.getOrNull(i)?.translation?.text?.ifBlank { null } ?: ExplorerUiText.noEnglish else line.text
                            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Waveform(peaks: List<Float>, played: Float, lines: List<ExplorerAudioLine>, durationMs: Long, onSeek: (Float) -> Unit) {
    val playedColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.field.surface200
    Canvas(
        Modifier.fillMaxWidth().height(64.dp)
            .semantics { contentDescription = AudioText.waveform }
            .pointerInput(peaks) { detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) } }
            .testTag("explorer.audio.waveform"),
    ) {
        val n = peaks.size
        val gap = 1.dp.toPx()
        val bar = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        peaks.forEachIndexed { i, p ->
            val h = (p.coerceIn(0.04f, 1f) * size.height)
            val x = i * (bar + gap)
            drawRoundRect(
                color = if (x / size.width <= played) playedColor else restColor,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2f, bar / 2f),
            )
        }
    }
}

private fun speedLabel(speed: Float): String = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

object AudioText {
    const val back10 = "Back 10 seconds"
    const val forward10 = "Forward 10 seconds"
    const val waveform = "Waveform. Tap to jump."
    const val failed = "This recording didn't play. Try again."
    const val stop = "Stop playing"
    const val playing = "Playing"
    const val clearVoices = "Clear voices"
    const val clearVoicesNote = "Plays the copy with background noise taken out"
}

@Composable
internal fun MiniPlayer(onOpen: () -> Unit) {
    val audio by ExplorerAudio.state.collectAsState()
    val item = audio.item ?: return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.large)
            .testTag("explorer.miniplayer"),
    ) {
        Column {
            val fraction = if (audio.durationMs > 0) (audio.positionMs.toFloat() / audio.durationMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.field.surface200)) {
                Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(MaterialTheme.colorScheme.primary))
            }
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                    Text("♪", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(item.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        ExplorerAudioLines.spoken(audio.line) ?: "${AudioText.playing} · ${ExplorerAudioLines.clock(audio.positionMs)} of ${ExplorerAudioLines.clock(audio.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("explorer.miniplayer.line"),
                    )
                }
                IconButton(onClick = { ExplorerAudio.seekBy(-ExplorerAudio.STEP_MS) }, modifier = Modifier.testTag("explorer.miniplayer.back")) {
                    Icon(Icons.Filled.Replay10, contentDescription = AudioText.back10)
                }
                IconButton(onClick = { ExplorerAudio.toggle() }, modifier = Modifier.testTag("explorer.miniplayer.play")) {
                    Icon(if (audio.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (audio.playing) ExplorerUiText.pause else ExplorerUiText.play)
                }
                IconButton(onClick = { ExplorerAudio.stop() }, modifier = Modifier.testTag("explorer.miniplayer.stop")) {
                    Icon(Icons.Filled.Close, contentDescription = AudioText.stop)
                }
            }
        }
    }
}
