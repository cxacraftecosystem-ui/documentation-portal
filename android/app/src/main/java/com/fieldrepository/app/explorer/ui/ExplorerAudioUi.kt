package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerAudioItem
import com.fieldrepository.app.explorer.data.ExplorerAudioLine
import com.fieldrepository.app.explorer.data.ExplorerAudioLines
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.explorer.data.ExplorerPeaks
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.ui.speakerColor
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.flow.first

internal fun audioLines(view: Transcript?): List<ExplorerAudioLine> =
    view?.turns.orEmpty().sortedBy { it.startMs }.map { t ->
        val who = view?.speakers?.firstOrNull { it.speakerKey == t.speakerKey }
        ExplorerAudioLine(t.startMs, t.endMs, t.speakerName.ifBlank { who?.name ?: who?.label.orEmpty() }.ifBlank { null }, t.translation.text?.ifBlank { null } ?: t.text)
    }

internal fun audioItem(entry: ExplorerEntry, url: String, lines: List<ExplorerAudioLine>): ExplorerAudioItem? {
    val id = entry.mediaId ?: return null
    return ExplorerAudioItem(mediaId = id, title = ExplorerNames.pretty(entry.name), subtitle = fileMeta(entry), url = url, lines = lines)
}

internal fun audioSource(entry: ExplorerEntry): String? = ExplorerPaths.preview(entry) ?: entry.url

internal fun isolatedSource(mediaId: String): String = "/api/data/media/$mediaId/preview?variant=isolated"

object ExplorerNames {
    fun base(name: String): String = name.replace(Regex("\\.[A-Za-z0-9]{1,5}$"), "")

    fun pretty(name: String): String {
        val base = base(name).trim()
        if (!base.contains('_') && !base.contains('-')) return base.ifBlank { name }
        if (base.any { it.isWhitespace() }) return base
        return base.split('_').map { it.replace('-', ' ').trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
    }
}

object AudioColors {
    val track = Color(0xFFD9C4E9)
    val trackDark = Color(0xFF4C3B63)
    val hit = Color(0xFFFBBF24)
    val markLight = Color(0xFFFDE68A)
    val onMarkLight = Color(0xFF78350F)
    val markDark = Color(0xFF78350F)
    val onMarkDark = Color(0xFFFDE68A)
    val cardTop = Color(0xFFF2E9FE)
    val cardBottom = Color(0xFFFFF7ED)
    val cardTopDark = Color(0xFF2A1A40)
    val cardBottomDark = Color(0xFF1E1B2E)
    val follow = Color(0xFF15803D)
    val followDark = Color(0xFF86EFAC)
}

internal enum class AudioTab { ORIGINAL, ENGLISH, FIND }

internal data class AudioFind(val needle: String, val hits: List<Int>, val at: Int) {
    val current: Int? get() = hits.getOrNull(at)
    val label: String get() = if (needle.isBlank()) "" else if (hits.isEmpty()) "No matches" else "${at + 1} of ${hits.size}"
}

internal fun findIn(texts: List<String>, needle: String, at: Int): AudioFind {
    val wanted = needle.trim()
    if (wanted.isEmpty()) return AudioFind(needle, emptyList(), 0)
    val hits = texts.indices.filter { texts[it].contains(wanted, ignoreCase = true) }
    return AudioFind(needle, hits, if (hits.isEmpty()) 0 else ((at % hits.size) + hits.size) % hits.size)
}

@Composable
internal fun audioHighlight(text: String, needle: String, mark: Color, onMark: Color): AnnotatedString {
    val wanted = needle.trim()
    if (wanted.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        var hit = text.indexOf(wanted, 0, ignoreCase = true)
        while (hit >= 0) {
            append(text.substring(cursor, hit))
            withStyle(SpanStyle(background = mark, color = onMark)) { append(text.substring(hit, hit + wanted.length)) }
            cursor = hit + wanted.length
            hit = text.indexOf(wanted, cursor, ignoreCase = true)
        }
        append(text.substring(cursor))
    }
}

@Composable
internal fun ColumnScope.AudioPane(entry: ExplorerEntry, view: Transcript?, startSeconds: Double, find: String = "") {
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
    val wave by produceState(initialValue = ExplorerPeaks(), entry.mediaId) {
        val id = entry.mediaId ?: return@produceState
        value = runCatching { ExplorerMoreApi.of(context).peaks(id) }.getOrDefault(ExplorerPeaks())
    }
    var clear by rememberSaveable(entry.key) { mutableStateOf(wave.isolated) }
    LaunchedEffect(wave.isolated) { if (wave.isolated) clear = true }
    var tab by rememberSaveable(entry.key) { mutableStateOf(if (view?.turns?.any { !it.translation.text.isNullOrBlank() } == true) AudioTab.ENGLISH else AudioTab.ORIGINAL) }
    var text by rememberSaveable(entry.key) { mutableStateOf(tab) }
    var needle by rememberSaveable(entry.key) { mutableStateOf(find) }
    var stepped by rememberSaveable(entry.key) { mutableStateOf(false) }
    var hitAt by rememberSaveable(entry.key) { mutableIntStateOf(0) }
    val position = if (mine) audio.positionMs else 0L
    val duration = if (mine && audio.durationMs > 0) audio.durationMs else (view?.durationMs ?: 0L)
    val turns = view?.turns.orEmpty().sortedBy { it.startMs }
    val english = text == AudioTab.ENGLISH
    val texts = turns.map { if (english) it.translation.text?.ifBlank { null } ?: it.text else it.text }
    val found = findIn(texts, needle, hitAt)
    val markers = if (duration > 0) found.hits.map { (turns[it].startMs.toFloat() / duration).coerceIn(0f, 1f) } else emptyList()
    val active = if (mine) ExplorerAudioLines.index(lines, position) else -1
    val speaking = lines.getOrNull(active)?.speaker
    val big = LocalDensity.current.fontScale >= 1.6f
    val dark = MaterialTheme.field.isDark
    val isolated = wave.isolated && entry.mediaId != null

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(if (dark) listOf(AudioColors.cardTopDark, AudioColors.cardBottomDark) else listOf(AudioColors.cardTop, AudioColors.cardBottom)))
            .padding(14.dp)
            .testTag("explorer.audio.card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Waveform(wave.peaks, fraction, markers, 48.dp) { f -> if (duration > 0) ExplorerAudio.seekTo((f * duration).toLong()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ExplorerAudioLines.clock(position), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                speaking?.let { AudioText.speaking(it) }.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = if (big) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(ExplorerAudioLines.clock(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val speed: @Composable () -> Unit = {
            Pill(speedLabel(audio.speed), filled = false, enabled = mine, tag = "explorer.audio.speed") { ExplorerAudio.setSpeed(ExplorerAudio.nextSpeed(audio.speed)) }
        }
        val clearPill: @Composable () -> Unit = {
            if (isolated) {
                Pill(if (clear) "✓ ${AudioText.clear}" else AudioText.clear, filled = clear, enabled = true, tag = "explorer.audio.clear", role = Role.Switch) {
                    val on = !clear
                    clear = on
                    val source = if (on) isolatedSource(entry.mediaId!!) else audioSource(entry)
                    val item = source?.let { audioItem(entry, it, lines) }
                    if (item != null) ExplorerAudio.play(context, item, if (mine) audio.positionMs else 0L)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (!big) speed()
            RoundButton(AudioText.back10Label, 48.dp, AudioText.back10, enabled = mine, tag = "explorer.audio.back") { ExplorerAudio.seekBy(-ExplorerAudio.STEP_MS) }
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    .clickable(enabled = mine, role = Role.Button) { ExplorerAudio.toggle() }
                    .semantics { contentDescription = if (audio.playing && mine) ExplorerUiText.pause else ExplorerUiText.play }
                    .testTag("explorer.audio.play"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (audio.playing && mine) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
            }
            RoundButton(AudioText.forward10Label, 48.dp, AudioText.forward10, enabled = mine, tag = "explorer.audio.forward") { ExplorerAudio.seekBy(ExplorerAudio.STEP_MS) }
            if (!big) clearPill() else Spacer(Modifier.width(1.dp))
        }
        if (big) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                speed()
                clearPill()
            }
        }
        if (mine && audio.failed) StatusCard(ExplorerStatus.FAILED, AudioText.failed)
    }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val original = view?.languages?.firstOrNull { it.code != "en" }?.label?.ifBlank { null } ?: ExplorerUiText.transcriptTab
        val hasEnglish = turns.any { !it.translation.text.isNullOrBlank() }
        val options = listOfNotNull(
            AudioTab.ORIGINAL to original,
            (AudioTab.ENGLISH to ExplorerUiText.englishTab).takeIf { hasEnglish },
            (AudioTab.FIND to AudioText.findTab).takeIf { turns.isNotEmpty() },
        )
        SegmentTabs(options, tab, "explorer.audio.tab") { picked ->
            if (picked != AudioTab.FIND) text = picked
            tab = picked
        }
        if (tab == AudioTab.FIND) {
            FindField(needle, found.label, onChange = { needle = it; hitAt = 0; stepped = true }, onStep = { delta -> hitAt += delta; stepped = true })
        }
        val speakers = view?.speakers.orEmpty()
        if (speakers.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                speakers.forEach { s ->
                    val tint = speakerColor(s.colorKey)
                    Row(
                        Modifier.heightIn(min = 28.dp).clip(CircleShape).background(tint.copy(alpha = if (dark) 0.22f else 0.14f)).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(Modifier.size(8.dp).background(tint, CircleShape))
                        Text(s.name?.ifBlank { null } ?: s.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tint)
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp)) {
        if (lines.isEmpty()) {
            HintLine(ExplorerUiText.noTranscript)
        } else {
            val list = rememberLazyListState()
            val focus = (if (stepped) found.current else null) ?: active
            LaunchedEffect(focus, lines.size) {
                if (focus < 0) return@LaunchedEffect
                snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 }
                runCatching { list.scrollToItem((focus - 1).coerceAtLeast(0)) }
                withFrameNanos { }
                runCatching { list.scrollToItem((focus - 1).coerceAtLeast(0)) }
            }
            val (markBg, _) = explorerMark()
            val mark = if (dark) AudioColors.markDark to AudioColors.onMarkDark else AudioColors.markLight to AudioColors.onMarkLight
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("explorer.audio.lines"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(turns) { i, turn ->
                    Segment(
                        clock = ExplorerAudioLines.clock(turn.startMs),
                        speaker = lines.getOrNull(i)?.speaker,
                        speakerColor = speakerColor(turn.colorKey ?: view?.speakers?.firstOrNull { it.speakerKey == turn.speakerKey }?.colorKey),
                        text = audioHighlight(texts.getOrElse(i) { turn.text }, needle, mark.first, mark.second),
                        current = i == active,
                        markBg = markBg,
                        large = true,
                        onClick = { ExplorerAudio.seekTo(turn.startMs) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun Segment(clock: String, speaker: String?, speakerColor: Color, text: AnnotatedString, current: Boolean, markBg: Color, large: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (current) markBg else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(clock, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 40.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            speaker?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = speakerColor) }
            Text(text, style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
internal fun <T> SegmentTabs(options: List<Pair<T, String>>, current: T, tagPrefix: String, onPick: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.field.surface50)
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(12.dp))
            .padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == current
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onPick(value) }
                    .semantics { role = Role.Tab; selected = on }
                    .testTag("$tagPrefix.${value.toString().lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
internal fun FindField(needle: String, label: String, onChange: (String) -> Unit, onStep: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp)
            .testTag("explorer.audio.find"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Box(Modifier.weight(1f)) {
            if (needle.isEmpty()) Text(AudioText.findHint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.placeholder)
            BasicTextField(
                value = needle,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = AudioText.findHint },
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("↑", modifier = Modifier.clickable(role = Role.Button) { onStep(-1) }.padding(6.dp).semantics { contentDescription = AudioText.previousMatch })
        Text("↓", modifier = Modifier.clickable(role = Role.Button) { onStep(1) }.padding(6.dp).semantics { contentDescription = AudioText.nextMatch })
    }
}

@Composable
private fun Pill(label: String, filled: Boolean, enabled: Boolean, tag: String, role: Role = Role.Button, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .then(if (filled) Modifier else Modifier.border(1.dp, MaterialTheme.field.hairline, CircleShape))
            .clickable(enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = 10.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal, color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RoundButton(description: String, size: Dp, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.field.hairline, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun Waveform(peaks: List<Float>, played: Float, markers: List<Float>, height: Dp, onSeek: (Float) -> Unit) {
    val playedColor = MaterialTheme.colorScheme.primary
    val restColor = if (MaterialTheme.field.isDark) AudioColors.trackDark else AudioColors.track
    val head = MaterialTheme.colorScheme.onSurface
    Canvas(
        Modifier.fillMaxWidth().height(height)
            .semantics { contentDescription = AudioText.waveform }
            .pointerInput(peaks) { detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) } }
            .testTag("explorer.audio.waveform"),
    ) {
        val bar = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val n = ((size.width + gap) / (bar + gap)).toInt().coerceAtLeast(1)
        val values = if (peaks.isEmpty()) List(n) { 0.2f } else List(n) { i ->
            val from = i * peaks.size / n
            val to = maxOf(from + 1, (i + 1) * peaks.size / n)
            (from until minOf(to, peaks.size)).maxOf { peaks[it] }
        }
        val hot = markers.flatMap { m -> val b = (m * n).toInt().coerceIn(0, n - 1); listOf(b, (b + 1).coerceAtMost(n - 1)) }.toSet()
        values.forEachIndexed { i, p ->
            val h = (p.coerceIn(0.14f, 1f) * size.height)
            val x = i * (bar + gap)
            val color = when {
                i in hot -> AudioColors.hit
                (i + 0.5f) / n <= played -> playedColor
                else -> restColor
            }
            drawRoundRect(color = color, topLeft = Offset(x, (size.height - h) / 2f), size = Size(bar, h), cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()))
        }
        if (peaks.isNotEmpty()) {
            val x = (played * size.width).coerceIn(0f, size.width - 2.dp.toPx())
            drawRect(head, topLeft = Offset(x, 0f), size = Size(2.dp.toPx(), size.height))
        }
    }
}

private fun speedLabel(speed: Float): String = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

object AudioText {
    const val back10 = "↺10"
    const val forward10 = "10↻"
    const val back10Label = "Back 10 seconds"
    const val forward10Label = "Forward 10 seconds"
    const val waveform = "Waveform. Tap to jump."
    const val failed = "This recording didn't play. Try again."
    const val stop = "Stop playing"
    const val playing = "Playing"
    const val clear = "Clear"
    const val clearVoices = "Clear voices"
    const val findTab = "Find ⌕"
    const val findHint = "Find in transcript"
    const val previousMatch = "Previous match"
    const val nextMatch = "Next match"
    fun speaking(name: String) = "$name speaking"
}

@Composable
internal fun MiniPlayer(onOpen: () -> Unit) {
    val audio by ExplorerAudio.state.collectAsState()
    val item = audio.item ?: return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1E1B2E))
            .testTag("explorer.miniplayer"),
    ) {
        val fraction = if (audio.durationMs > 0) (audio.positionMs.toFloat() / audio.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF3A3651))) {
            Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(Color(0xFFC090F5)))
        }
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFFEF3C7)), contentAlignment = Alignment.Center) {
                Text("♪", style = MaterialTheme.typography.labelLarge, color = Color(0xFF92400E), fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    ExplorerAudioLines.spoken(audio.line) ?: "${AudioText.playing} · ${ExplorerAudioLines.clock(audio.positionMs)} of ${ExplorerAudioLines.clock(audio.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFCFCBE0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("explorer.miniplayer.line"),
                )
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { ExplorerAudio.seekBy(-ExplorerAudio.STEP_MS) }
                    .semantics { contentDescription = AudioText.back10Label }.testTag("explorer.miniplayer.back"),
                contentAlignment = Alignment.Center,
            ) { Text(AudioText.back10, style = MaterialTheme.typography.labelMedium, color = Color.White) }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFC090F5)).clickable(role = Role.Button) { ExplorerAudio.toggle() }
                    .semantics { contentDescription = if (audio.playing) ExplorerUiText.pause else ExplorerUiText.play }.testTag("explorer.miniplayer.play"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (audio.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF2A0F47), modifier = Modifier.size(20.dp))
            }
            if (!audio.playing) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { ExplorerAudio.stop() }
                        .semantics { contentDescription = AudioText.stop }.testTag("explorer.miniplayer.stop"),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", style = MaterialTheme.typography.labelLarge, color = Color.White) }
            }
        }
    }
}
