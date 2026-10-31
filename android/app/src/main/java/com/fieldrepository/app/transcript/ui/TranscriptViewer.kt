package com.fieldrepository.app.transcript.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.transcript.data.AskAnswer
import com.fieldrepository.app.transcript.data.AutoDraft
import com.fieldrepository.app.transcript.data.AutoDraftResult
import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.GlossaryTerm
import com.fieldrepository.app.transcript.data.PipelineStatus
import com.fieldrepository.app.transcript.data.QuoteCard
import com.fieldrepository.app.transcript.data.SpeakerMapping
import com.fieldrepository.app.transcript.data.SpeakerSuggestion
import com.fieldrepository.app.transcript.data.TextSource
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptFormat
import com.fieldrepository.app.transcript.data.TranscriptKaraoke
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.TranscriptTurn
import com.fieldrepository.app.transcript.data.TranscriptUiText
import com.fieldrepository.app.transcript.data.TranslationStatus
import com.fieldrepository.app.transcript.data.TurnHistoryEntry
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch

enum class TranscriptTab { ORIGINAL, ENGLISH, SIDE_BY_SIDE }

/**
 * What the viewer asks of whoever mounts it. [TranscriptScreen] implements it over the repository,
 * the clipboard and the share sheet; the Compose tests over plain lists. Every member has a quiet
 * default so a test overrides only what it watches.
 */
open class TranscriptHost {
    /** Puts [text] on the clipboard; the viewer then says "Copied". */
    open fun copy(text: String) {}
    open fun download(format: String, track: CopyTrack) {}
    open fun statusAction() {}
    open fun editTurn(turn: TranscriptTurn, text: String?, english: String?) {}
    open fun retranslate(turn: TranscriptTurn) {}
    open fun mapSpeakers(mappings: List<SpeakerMapping>) {}
    open suspend fun suggestions(): List<SpeakerSuggestion> = emptyList()
    open suspend fun history(turn: TranscriptTurn): List<TurnHistoryEntry> = emptyList()
    open suspend fun ask(question: String): AskAnswer = AskAnswer()
    open suspend fun autodraft(): AutoDraftResult = AutoDraftResult()
    /** Accept (or accept edited) one drafted answer into the questionnaire; null when this mount has none. */
    open val acceptDraft: ((AutoDraft, String) -> Unit)? = null
    open suspend fun glossary(): List<GlossaryTerm> = emptyList()
    open suspend fun quoteCard(turnIds: List<String>): QuoteCard = QuoteCard()
    open fun shareQuoteCard(card: QuoteCard) {}
}

private enum class Sheet { NONE, SPEAKERS, ASK, FILL, GLOSSARY }

/**
 * THE TRANSCRIPT VIEWER (DESIGN §3, §4, §10), the same on both portals.
 *
 * Header with badges and the status's one action; tabs Original · English · Side by side (stacked
 * pairs on a phone, two columns from 600 dp); per turn the speaker, a timestamp that plays from
 * there, the copy menu and the edit menu; Copy all, Select turns → Copy selected, downloads through
 * the share sheet; karaoke while the audio plays. Every sheet the turns open lives in
 * `TranscriptSheets.kt`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TranscriptViewer(
    view: Transcript,
    playback: TranscriptPlayback,
    host: TranscriptHost,
    modifier: Modifier = Modifier,
    offline: Boolean = false,
    waitingEdits: Int = 0,
    failures: List<String> = emptyList(),
    /** The mount's own chips for the recording (a take's RecordingChips), drawn under the badges. */
    headerExtra: (@Composable () -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableStateOf(TranscriptTab.ORIGINAL) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var editing by remember { mutableStateOf<TranscriptTurn?>(null) }
    var historyOf by remember { mutableStateOf<TranscriptTurn?>(null) }
    var quoteFor by remember { mutableStateOf<List<String>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val turns = remember(view.turns) { view.turns.sortedBy { it.index } }
    val listState = rememberLazyListState()

    fun copied(text: String) {
        host.copy(text)
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(TranscriptStrings.copied) }
    }

    val positionMs = (playback.positionSeconds * 1000).toLong()
    val karaoke = if (playback.available && (playback.playing || positionMs > 0)) TranscriptKaraoke.at(turns, positionMs) else TranscriptKaraoke.Position(-1, -1)
    // Follow the voice: while it plays, the turn being said is kept on screen.
    LaunchedEffect(karaoke.turn, playback.playing) {
        if (playback.playing && karaoke.turn >= 0) {
            val item = karaoke.turn + HEADER_ITEMS
            val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (item !in visible) listState.animateScrollToItem(item)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag("transcript.viewer")) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "header") {
                    TranscriptHeader(view, offline, waitingEdits, failures, headerExtra, onAction = host::statusAction)
                }
                item(key = "toolbar") {
                    if (view.status == PipelineStatus.READY && turns.isNotEmpty()) {
                        Column {
                            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                                TranscriptTab.entries.forEach { t ->
                                    Tab(
                                        selected = tab == t,
                                        onClick = { tab = t },
                                        modifier = Modifier.testTag("transcript.tab.${t.name.lowercase()}"),
                                        text = {
                                            Text(
                                                when (t) {
                                                    TranscriptTab.ORIGINAL -> TranscriptStrings.tabOriginal
                                                    TranscriptTab.ENGLISH -> TranscriptStrings.tabEnglish
                                                    TranscriptTab.SIDE_BY_SIDE -> TranscriptStrings.tabSideBySide
                                                },
                                                maxLines = 1
                                            )
                                        }
                                    )
                                }
                            }
                            Toolbar(
                                view = view,
                                selecting = selecting,
                                onCopyAll = { track -> copied(TranscriptFormat.all(view, track)) },
                                onSelect = { selecting = true },
                                onDownload = host::download,
                                onSheet = { sheet = it },
                                hasFill = host.acceptDraft != null && view.permissions.autodraft,
                            )
                        }
                    }
                }
                if (view.status == PipelineStatus.READY && turns.isEmpty()) {
                    item(key = "empty") {
                        Text(TranscriptUiText.noTurns, color = MaterialTheme.field.muted, modifier = Modifier.padding(20.dp))
                    }
                }
                if (view.status == PipelineStatus.READY || turns.isNotEmpty()) {
                    itemsIndexed(turns, key = { _, t -> t.id }) { index, turn ->
                        TurnRow(
                            turn = turn,
                            tab = tab,
                            wide = wide,
                            active = index == karaoke.turn,
                            activeWord = if (index == karaoke.turn) karaoke.word else -1,
                            selecting = selecting,
                            selected = turn.id in selected,
                            canEdit = view.permissions.edit,
                            canName = view.permissions.mapSpeakers,
                            onToggle = { if (turn.id in selected) selected.remove(turn.id) else selected.add(turn.id) },
                            onSeek = { ms -> playback.seek(ms / 1000.0) },
                            onCopy = { track -> copied(TranscriptFormat.body(turn, track)) },
                            onEdit = { editing = turn },
                            onRetranslate = { host.retranslate(turn) },
                            onHistory = { historyOf = turn },
                            onSpeaker = { sheet = Sheet.SPEAKERS },
                        )
                    }
                }
            }
            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    canQuote = view.permissions.quoteCard,
                    onCopy = { track -> copied(TranscriptFormat.selected(view, selected.toList(), track)) },
                    onQuote = { quoteFor = turns.filter { it.id in selected }.map { it.id }.take(6) },
                    onCancel = { selecting = false; selected.clear() },
                )
            }
            if (playback.available) PlayerBar(playback)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    when (sheet) {
        Sheet.SPEAKERS -> SpeakersSheet(view, host, onDismiss = { sheet = Sheet.NONE })
        Sheet.ASK -> AskSheet(view, host, onSeek = { ms -> playback.seek(ms / 1000.0) }, onDismiss = { sheet = Sheet.NONE })
        Sheet.FILL -> FillSheet(view, host, onSeek = { ms -> playback.seek(ms / 1000.0) }, onDismiss = { sheet = Sheet.NONE })
        Sheet.GLOSSARY -> GlossarySheet(view, host, onSeek = { ms -> playback.seek(ms / 1000.0) }, onDismiss = { sheet = Sheet.NONE })
        Sheet.NONE -> Unit
    }
    editing?.let { turn -> EditTurnDialog(turn, onSave = { text, english -> host.editTurn(turn, text, english); editing = null }, onDismiss = { editing = null }) }
    historyOf?.let { turn -> HistoryDialog(turn, host, onDismiss = { historyOf = null }) }
    quoteFor?.let { ids -> QuoteCardDialog(ids, host, onDismiss = { quoteFor = null; selecting = false; selected.clear() }) }
}

/** The header and the toolbar come before the turns in the list. */
private const val HEADER_ITEMS = 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptHeader(
    view: Transcript,
    offline: Boolean,
    waitingEdits: Int,
    failures: List<String>,
    extra: (@Composable () -> Unit)?,
    onAction: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(view.title.ifBlank { TranscriptUiText.open }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(view.status)
            if (view.languages.isNotEmpty()) Pill(view.languages.joinToString(", ") { it.label.ifBlank { it.code } })
            view.durationMs?.let { Pill(TranscriptFormat.length(it)) }
            if (view.speakerCount > 0) Pill("${TranscriptStrings.headerSpeakers} ${view.speakerCount}")
            view.badges.forEach { Pill(it.label.ifBlank { it.model }, tone = PillTone.MODEL) }
            if (offline) Pill(TranscriptStrings.offlineCached, tone = PillTone.QUIET)
            view.cost?.let { Pill("${TranscriptStrings.spendPerRecording} ${TranscriptUiText.usd(it.totalUsd)}", tone = PillTone.QUIET) }
        }
        extra?.invoke()
        TranscriptUiText.statusLine(view)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted) }
        if (waitingEdits > 0) Text(TranscriptUiText.waiting(waitingEdits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        failures.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val action = when {
            view.status == PipelineStatus.QUEUED && view.permissions.transcribeNow -> TranscriptStrings.actionTranscribeNow
            view.status == PipelineStatus.NEEDS_ATTENTION && view.permissions.retry -> TranscriptStrings.actionRetry
            else -> null
        }
        // One action, centred (the design bar's single-button rule).
        if (action != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(onClick = onAction, modifier = Modifier.testTag("transcript.statusAction")) { Text(action) }
        }
        if (view.summary.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                view.summary.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body) }
            }
        }
    }
}

internal enum class PillTone { PLAIN, MODEL, QUIET }

@Composable
internal fun Pill(text: String, tone: PillTone = PillTone.PLAIN, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        PillTone.PLAIN -> MaterialTheme.field.surface100 to MaterialTheme.field.body
        PillTone.MODEL -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) to MaterialTheme.colorScheme.primary
        PillTone.QUIET -> MaterialTheme.field.surface50 to MaterialTheme.field.muted
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        maxLines = 1,
        modifier = modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun StatusPill(status: String) {
    val (bg, fg) = when (status) {
        PipelineStatus.READY -> MaterialTheme.field.successContainer to MaterialTheme.field.onSuccessContainer
        PipelineStatus.NEEDS_ATTENTION -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        PipelineStatus.WAITING_FOR_CONSENT -> MaterialTheme.field.warningContainer to MaterialTheme.field.onWarningContainer
        else -> MaterialTheme.field.surface200 to MaterialTheme.field.body
    }
    Row(
        Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp).testTag("transcript.status"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(fg, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(TranscriptUiText.status(status), style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Toolbar(
    view: Transcript,
    selecting: Boolean,
    onCopyAll: (CopyTrack) -> Unit,
    onSelect: () -> Unit,
    onDownload: (String, CopyTrack) -> Unit,
    onSheet: (Sheet) -> Unit,
    hasFill: Boolean,
) {
    var copyMenu by remember { mutableStateOf(false) }
    var downloadMenu by remember { mutableStateOf(false) }
    // Many actions: pills that wrap (the design bar's rule for more than two).
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box {
            AssistChip(
                onClick = { copyMenu = true },
                label = { Text(TranscriptStrings.copyAll) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.testTag("transcript.copyAll")
            )
            DropdownMenu(expanded = copyMenu, onDismissRequest = { copyMenu = false }) {
                listOf(
                    CopyTrack.ORIGINAL to TranscriptStrings.copyAllOriginal,
                    CopyTrack.ENGLISH to TranscriptStrings.copyAllEnglish,
                    CopyTrack.BOTH to TranscriptStrings.copyAllBoth,
                ).forEach { (track, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { copyMenu = false; onCopyAll(track) },
                        modifier = Modifier.testTag("transcript.copyAll.${track.wire}")
                    )
                }
            }
        }
        if (!selecting) AssistChip(onClick = onSelect, label = { Text(TranscriptStrings.selectTurns) }, modifier = Modifier.testTag("transcript.select"))
        Box {
            AssistChip(onClick = { downloadMenu = true }, label = { Text(TranscriptStrings.download) }, modifier = Modifier.testTag("transcript.download"))
            DropdownMenu(expanded = downloadMenu, onDismissRequest = { downloadMenu = false }) {
                listOf(
                    Triple("md", CopyTrack.BOTH, TranscriptStrings.downloadMarkdown),
                    Triple("docx", CopyTrack.BOTH, TranscriptStrings.downloadWord),
                    Triple("srt", CopyTrack.ORIGINAL, TranscriptStrings.downloadSrt),
                    Triple("vtt", CopyTrack.ORIGINAL, TranscriptStrings.downloadVtt),
                ).forEach { (format, track, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { downloadMenu = false; onDownload(format, track) })
                }
            }
        }
        if (view.permissions.mapSpeakers) AssistChip(onClick = { onSheet(Sheet.SPEAKERS) }, label = { Text(TranscriptStrings.speakers) })
        if (view.permissions.ask) AssistChip(onClick = { onSheet(Sheet.ASK) }, label = { Text(TranscriptStrings.askTitle) }, modifier = Modifier.testTag("transcript.ask"))
        if (hasFill) AssistChip(onClick = { onSheet(Sheet.FILL) }, label = { Text(TranscriptStrings.autodraftTitle) }, modifier = Modifier.testTag("transcript.fill"))
        AssistChip(onClick = { onSheet(Sheet.GLOSSARY) }, label = { Text(TranscriptStrings.glossaryTitle) })
    }
    HorizontalDivider(color = MaterialTheme.field.hairline)
}

@Composable
private fun TurnRow(
    turn: TranscriptTurn,
    tab: TranscriptTab,
    wide: Boolean,
    active: Boolean,
    activeWord: Int,
    selecting: Boolean,
    selected: Boolean,
    canEdit: Boolean,
    canName: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onCopy: (CopyTrack) -> Unit,
    onEdit: () -> Unit,
    onRetranslate: () -> Unit,
    onHistory: () -> Unit,
    onSpeaker: () -> Unit,
) {
    val lit = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (active) lit else MaterialTheme.colorScheme.surface)
            .then(if (selecting) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            .testTag("turn.${turn.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = { onToggle() }, modifier = Modifier.testTag("turn.select.${turn.id}"))
            Text(
                TranscriptFormat.cleanSpeaker(turn.speakerName),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false).then(if (canName && !selecting) Modifier.clickable(onClick = onSpeaker) else Modifier)
            )
            Spacer(Modifier.width(8.dp))
            val time = TranscriptFormat.timestamp(turn.startMs)
            Text(
                time,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                    .clickable { onSeek(turn.startMs) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .semantics { contentDescription = TranscriptStrings.playFrom(time) }
                    .testTag("turn.time.${turn.id}")
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (turn.textSource == TextSource.EDITED) TranscriptStrings.sourceEdited else TranscriptStrings.sourceMachine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.field.muted
            )
            TurnCopyMenu(turn, onCopy)
            if (canEdit) TurnMoreMenu(turn, onEdit, onRetranslate, onHistory)
        }
        val original: @Composable () -> Unit = { KaraokeText(turn, activeWord, onSeek) }
        val english: @Composable () -> Unit = { EnglishPane(turn, active, canEdit, onRetranslate) }
        when (tab) {
            TranscriptTab.ORIGINAL -> original()
            TranscriptTab.ENGLISH -> english()
            TranscriptTab.SIDE_BY_SIDE -> if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f)) { original() }
                    Box(Modifier.weight(1f)) { english() }
                }
            } else {
                // A phone stacks the pair: the original, then its English in a tinted block.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    original()
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp)).padding(10.dp)) { english() }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.field.hairline, modifier = Modifier.padding(start = 20.dp))
}

@Composable
private fun EnglishPane(turn: TranscriptTurn, active: Boolean, canEdit: Boolean, onRetranslate: () -> Unit) {
    val english = TranscriptFormat.englishOf(turn)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (english != null) {
            Text(
                english,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.field.body,
                modifier = Modifier
                    .then(if (active) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(4.dp)) else Modifier)
                    .testTag("turn.english.${turn.id}")
            )
        }
        val note = when (turn.translation.status) {
            TranslationStatus.ALREADY_ENGLISH -> TranscriptStrings.alreadyEnglish
            TranslationStatus.STALE -> TranscriptStrings.translationStale
            TranslationStatus.PENDING -> TranscriptStrings.translationPending
            TranslationStatus.FAILED -> TranscriptStrings.translationFailed
            else -> null
        }
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted) }
        if (canEdit && (turn.translation.status == TranslationStatus.STALE || turn.translation.status == TranslationStatus.FAILED)) {
            TextButton(onClick = onRetranslate, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(TranscriptStrings.retranslateTurn) }
        }
    }
}

@Composable
private fun TurnCopyMenu(turn: TranscriptTurn, onCopy: (CopyTrack) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("turn.copy.${turn.id}")) {
            Icon(Icons.Filled.ContentCopy, contentDescription = TranscriptStrings.copy, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                CopyTrack.ORIGINAL to TranscriptStrings.copyOriginal,
                CopyTrack.ENGLISH to TranscriptStrings.copyEnglish,
                CopyTrack.BOTH to TranscriptStrings.copyBoth,
            ).forEach { (track, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onCopy(track) })
            }
        }
    }
}

@Composable
private fun TurnMoreMenu(turn: TranscriptTurn, onEdit: () -> Unit, onRetranslate: () -> Unit, onHistory: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("turn.more.${turn.id}")) {
            Icon(Icons.Filled.MoreVert, contentDescription = TranscriptUiText.more, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(TranscriptStrings.editTurn) }, onClick = { open = false; onEdit() })
            if (turn.translation.status != TranslationStatus.ALREADY_ENGLISH) {
                DropdownMenuItem(text = { Text(TranscriptStrings.retranslateTurn) }, onClick = { open = false; onRetranslate() })
            }
            DropdownMenuItem(text = { Text(TranscriptStrings.history) }, onClick = { open = false; onHistory() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(count: Int, canQuote: Boolean, onCopy: (CopyTrack) -> Unit, onQuote: () -> Unit, onCancel: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            Text(TranscriptStrings.selectedCount(count), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
            Box {
                Button(onClick = { menu = true }, enabled = count > 0, modifier = Modifier.testTag("transcript.copySelected")) { Text(TranscriptStrings.copySelected) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(
                        CopyTrack.ORIGINAL to TranscriptStrings.trackOriginal,
                        CopyTrack.ENGLISH to TranscriptStrings.trackEnglish,
                        CopyTrack.BOTH to TranscriptStrings.trackBoth,
                    ).forEach { (track, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { menu = false; onCopy(track) },
                            modifier = Modifier.testTag("transcript.copySelected.${track.wire}")
                        )
                    }
                }
            }
            if (canQuote) AssistChip(onClick = onQuote, enabled = count in 1..6, label = { Text(TranscriptStrings.quoteCard) })
            TextButton(onClick = onCancel) { Text(TranscriptStrings.cancel) }
        }
    }
}

@Composable
private fun PlayerBar(playback: TranscriptPlayback) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = playback::toggle, modifier = Modifier.testTag("transcript.play")) {
                Icon(
                    if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playback.playing) TranscriptUiText.pause else TranscriptUiText.play,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            val duration = playback.durationSeconds
            Slider(
                value = if (duration > 0) (playback.positionSeconds / duration).toFloat().coerceIn(0f, 1f) else 0f,
                onValueChange = { f -> if (duration > 0) playback.seek(f * duration, play = false) },
                enabled = duration > 0,
                modifier = Modifier.weight(1f)
            )
            Text(
                TranscriptFormat.length((playback.positionSeconds * 1000).toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.field.muted,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}
