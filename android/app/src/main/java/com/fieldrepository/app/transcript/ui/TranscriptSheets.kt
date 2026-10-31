package com.fieldrepository.app.transcript.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.transcript.data.AskAnswer
import com.fieldrepository.app.transcript.data.AutoDraft
import com.fieldrepository.app.transcript.data.AutoDraftResult
import com.fieldrepository.app.transcript.data.Citation
import com.fieldrepository.app.transcript.data.GlossaryTerm
import com.fieldrepository.app.transcript.data.QuoteCard
import com.fieldrepository.app.transcript.data.SpeakerMapping
import com.fieldrepository.app.transcript.data.SpeakerRole
import com.fieldrepository.app.transcript.data.SpeakerSuggestion
import com.fieldrepository.app.transcript.data.TextSource
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptFormat
import com.fieldrepository.app.transcript.data.TranscriptSpeaker
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.TranscriptTurn
import com.fieldrepository.app.transcript.data.TranscriptUiText
import com.fieldrepository.app.transcript.data.TurnHistoryEntry
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A sheet's frame: title, scrolling body, and room for the navigation bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscriptSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Busy() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        FieldCircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

/** A timestamp chip that plays the audio from a cited moment. */
@Composable
internal fun CitationChip(startMs: Long, onSeek: (Long) -> Unit) {
    val time = TranscriptFormat.timestamp(startMs)
    Text(
        time,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(50))
            .clickable { onSeek(startMs) }
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("citation.$startMs")
    )
}

private fun Throwable.rethrowCancel() { if (this is CancellationException) throw this }

// ── Speaker names (§4.1) ───────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeakersSheet(
    view: Transcript,
    host: TranscriptHost,
    onDismiss: () -> Unit,
    /** Plays a few seconds of a speaker (their `sample`); null when there is no audio. */
    onPlaySample: ((TranscriptSpeaker) -> Unit)? = null,
    playingKey: String? = null,
) {
    val names = remember { mutableStateMapOf<String, String>().apply { view.speakers.forEach { put(it.speakerKey, it.name.orEmpty()) } } }
    val roles = remember { mutableStateMapOf<String, String?>().apply { view.speakers.forEach { put(it.speakerKey, it.role) } } }
    val people = remember { mutableStateMapOf<String, String?>().apply { view.speakers.forEach { put(it.speakerKey, it.personId) } } }
    var suggestions by remember { mutableStateOf<List<SpeakerSuggestion>>(emptyList()) }
    LaunchedEffect(view.mediaId) { suggestions = runCatching { host.suggestions() }.onFailure { it.rethrowCancel() }.getOrDefault(emptyList()) }
    TranscriptSheet(TranscriptStrings.speakers, onDismiss) {
        Text(TranscriptStrings.applyToAll, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        view.speakers.forEachIndexed { i, speaker ->
            val key = speaker.speakerKey
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    TranscriptStrings.speakerDefault(i + 1) + " · " + TranscriptStrings.mapSpeaker,
                    style = MaterialTheme.typography.labelLarge
                )
                if (speaker.suggested) Text(TranscriptStrings.suggested, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted)
                if (onPlaySample != null && speaker.sample != null) {
                    val playing = playingKey == key
                    OutlinedButton(onClick = { onPlaySample(speaker) }, modifier = Modifier.testTag("speaker.sample.$key")) {
                        Text(if (playing) TranscriptStrings.stopSample else TranscriptStrings.playSample)
                    }
                }
                OutlinedTextField(
                    value = names[key].orEmpty(),
                    onValueChange = { names[key] = it.take(80); people[key] = null },
                    label = { Text(TranscriptUiText.name) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("speaker.name.$key")
                )
                val candidates = suggestions.firstOrNull { it.speakerKey == key }?.candidates.orEmpty()
                if (candidates.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    candidates.forEach { c ->
                        FilterChip(
                            selected = names[key] == c.name,
                            onClick = { names[key] = c.name; roles[key] = c.role; people[key] = c.personId },
                            label = { Text(c.name) }
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpeakerRole.all.forEach { role ->
                        FilterChip(selected = roles[key] == role, onClick = { roles[key] = if (roles[key] == role) null else role }, label = { Text(TranscriptUiText.roleLabel(role)) })
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(onClick = {
                host.mapSpeakers(view.speakers.map { s ->
                    SpeakerMapping(s.speakerKey, names[s.speakerKey]?.trim()?.ifBlank { null }, roles[s.speakerKey], people[s.speakerKey])
                })
                onDismiss()
            }, modifier = Modifier.testTag("speakers.save")) { Text(TranscriptStrings.save) }
        }
    }
}

// ── Corrections (§4.2) ─────────────────────────────────────────────────────────────────────────

@Composable
internal fun EditTurnDialog(turn: TranscriptTurn, onSave: (String?, String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(turn.text) }
    var english by remember { mutableStateOf(turn.translation.text.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(TranscriptStrings.editTurn) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${TranscriptFormat.cleanSpeaker(turn.speakerName)} · ${TranscriptFormat.timestamp(turn.startMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.field.muted
                )
                OutlinedTextField(text, { text = it.take(20000) }, label = { Text(TranscriptStrings.editOriginal) }, modifier = Modifier.fillMaxWidth().testTag("edit.original"))
                OutlinedTextField(english, { english = it.take(20000) }, label = { Text(TranscriptStrings.editEnglish) }, modifier = Modifier.fillMaxWidth().testTag("edit.english"))
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onSave(text.takeIf { it != turn.text }, english.takeIf { it != turn.translation.text.orEmpty() })
                }
            ) { Text(TranscriptStrings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(TranscriptStrings.cancel) } }
    )
}

@Composable
internal fun HistoryDialog(turn: TranscriptTurn, host: TranscriptHost, onDismiss: () -> Unit) {
    var items by remember { mutableStateOf<List<TurnHistoryEntry>?>(null) }
    LaunchedEffect(turn.id) { items = runCatching { host.history(turn) }.onFailure { it.rethrowCancel() }.getOrDefault(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(TranscriptStrings.history) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val list = items
                if (list == null) Busy() else list.forEach { h ->
                    Text(
                        listOfNotNull(
                            if (h.source == TextSource.EDITED) TranscriptStrings.sourceEdited else TranscriptStrings.sourceMachine,
                            if (h.field == "ENGLISH") TranscriptStrings.editEnglish else TranscriptStrings.editOriginal,
                            h.byName,
                            h.at.take(10),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.field.muted
                    )
                    Text(h.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(TranscriptUiText.done) } }
    )
}

// ── Ask this interview (§10.2) ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskSheet(view: Transcript, host: TranscriptHost, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    var question by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val answers = remember { mutableStateListOf<Pair<String, AskAnswer>>() }
    val scope = rememberCoroutineScope()
    TranscriptSheet(TranscriptStrings.askTitle, onDismiss) {
        OutlinedTextField(
            value = question,
            onValueChange = { question = it.take(500) },
            placeholder = { Text(TranscriptStrings.askPlaceholder) },
            modifier = Modifier.fillMaxWidth().testTag("ask.question"),
            minLines = 2
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(
                enabled = !busy && question.trim().length >= 3,
                onClick = {
                    val q = question.trim()
                    busy = true; error = null
                    scope.launch {
                        runCatching { host.ask(q) }
                            .onSuccess { answers.add(0, q to it); question = "" }
                            .onFailure { it.rethrowCancel(); error = TranscriptUiText.couldNotLoad }
                        busy = false
                    }
                },
                modifier = Modifier.testTag("ask.send")
            ) { Text(TranscriptStrings.askSend) }
        }
        if (busy) Busy()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        answers.forEach { (q, a) ->
            Column(
                Modifier.fillMaxWidth().border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(q, style = MaterialTheme.typography.labelLarge)
                if (a.covered) TranscriptMarkdownText(a.answer, Modifier.testTag("ask.answer"))
                else Text(a.answer.ifBlank { TranscriptStrings.askNotCovered }, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.field.muted)
                if (a.citations.isNotEmpty()) {
                    Text(TranscriptStrings.askFromTurns, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        a.citations.forEach { c -> CitationChip(c.startMs, onSeek) }
                    }
                }
            }
        }
    }
}

// ── Fill the questionnaire from the recording (§10.3) ──────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FillSheet(view: Transcript, host: TranscriptHost, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<AutoDraftResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val decided = remember { mutableStateMapOf<String, String>() }
    var editing by remember { mutableStateOf<AutoDraft?>(null) }
    val scope = rememberCoroutineScope()
    val accept = host.acceptDraft
    TranscriptSheet(TranscriptStrings.autodraftTitle, onDismiss) {
        val r = result
        if (r == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(enabled = !busy, onClick = {
                    busy = true; error = null
                    scope.launch {
                        runCatching { host.autodraft() }.onSuccess { result = it }.onFailure { it.rethrowCancel(); error = TranscriptUiText.couldNotLoad }
                        busy = false
                    }
                }, modifier = Modifier.testTag("fill.run")) { Text(TranscriptStrings.autodraftAction) }
            }
            if (busy) Busy()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } else {
            Text(
                if (r.drafted.isEmpty()) TranscriptStrings.autodraftNone else TranscriptStrings.autodraftDone(r.drafted.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.field.muted
            )
            r.drafted.forEach { d ->
                val state = decided[d.questionId]
                Column(
                    Modifier.fillMaxWidth().border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(12.dp)).padding(12.dp).testTag("fill.draft.${d.questionId}"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(d.answerOriginal, style = MaterialTheme.typography.bodyMedium)
                    if (d.answerEnglish.isNotBlank() && d.answerEnglish != d.answerOriginal) {
                        Text(d.answerEnglish, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                    }
                    if (d.citations.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.citations.forEach { c: Citation -> CitationChip(c.startMs, onSeek) }
                    }
                    when {
                        state != null -> Text(state, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.field.success)
                        accept != null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { accept(d, d.answerOriginal); decided[d.questionId] = TranscriptUiText.accept }) { Text(TranscriptUiText.accept) }
                            OutlinedButton(onClick = { editing = d }) { Text(TranscriptStrings.editTurn) }
                            TextButton(onClick = { decided[d.questionId] = TranscriptUiText.dismiss }) { Text(TranscriptUiText.dismiss) }
                        }
                    }
                }
            }
            r.skipped.filter { TranscriptUiText.skipReason(it.reason).isNotEmpty() }.groupBy { it.reason }.forEach { (reason, items) ->
                Text("${TranscriptUiText.skipReason(reason)} · ${items.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted)
            }
        }
    }
    editing?.let { d ->
        var text by remember(d.questionId) { mutableStateOf(d.answerOriginal) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(TranscriptStrings.editTurn) },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    accept?.invoke(d, text.trim()); decided[d.questionId] = TranscriptStrings.sourceEdited; editing = null
                }) { Text(TranscriptStrings.save) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(TranscriptStrings.cancel) } }
        )
    }
}

// ── Craft glossary (§10.4) ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlossarySheet(view: Transcript, host: TranscriptHost, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    var terms by remember { mutableStateOf<List<GlossaryTerm>?>(null) }
    LaunchedEffect(view.mediaId) { terms = runCatching { host.glossary() }.onFailure { it.rethrowCancel() }.getOrDefault(emptyList()) }
    TranscriptSheet(TranscriptStrings.glossaryTitle, onDismiss) {
        val list = terms
        when {
            list == null -> Busy()
            list.isEmpty() -> Text(TranscriptUiText.glossaryEmpty, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodyMedium)
            else -> list.forEach { t ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.term, style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic, modifier = Modifier.weight(1f))
                        t.craftName?.let { Pill(it, PillTone.QUIET) }
                    }
                    t.gloss?.let { TranscriptMarkdownText(it) }
                    Text(TranscriptStrings.glossaryHeard(t.occurrences), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted)
                    val here = t.sightings.filter { it.mediaId == view.mediaId && it.startMs != null }
                    if (here.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        here.forEach { s -> CitationChip(s.startMs!!, onSeek) }
                    }
                }
                HorizontalDivider(color = MaterialTheme.field.hairline)
            }
        }
    }
}

// ── Quote card (§10.5) ─────────────────────────────────────────────────────────────────────────

@Composable
internal fun QuoteCardDialog(turnIds: List<String>, host: TranscriptHost, onDismiss: () -> Unit) {
    var card by remember { mutableStateOf<QuoteCard?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(turnIds) {
        runCatching { host.quoteCard(turnIds) }
            .onSuccess { card = it }
            .onFailure { it.rethrowCancel(); error = (it as? QuoteCardRefused)?.sentence ?: TranscriptUiText.couldNotLoad }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(TranscriptStrings.quoteCard) },
        text = {
            val c = card
            when {
                error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium)
                c == null -> Busy()
                else -> QuoteCardFace(c, Modifier.verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = {
            val c = card
            if (c != null) Button(onClick = { host.shareQuoteCard(c); onDismiss() }, modifier = Modifier.testTag("quote.share")) { Text(TranscriptUiText.shareCard) }
            else TextButton(onClick = onDismiss) { Text(TranscriptUiText.close) }
        },
        dismissButton = { if (card != null) TextButton(onClick = onDismiss) { Text(TranscriptStrings.cancel) } }
    )
}

/** The card as it is drawn on screen; [QuoteCardImage] paints the same layout into the PNG. */
@Composable
internal fun QuoteCardFace(card: QuoteCard, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.field.brandTile, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("“${card.original}”", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.field.onBrandTile)
        if (card.english.isNotBlank() && card.english != card.original) {
            Text(card.english, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.field.onBrandTileMuted)
        }
        Text(
            listOfNotNull(card.artisanName ?: card.speakerName.ifBlank { null }, card.craft, card.date.ifBlank { null }).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.field.accentOnBrandTile
        )
    }
}

/** A refusal the quote card route gives in words a person can act on (a withdrawn artisan). */
class QuoteCardRefused(val sentence: String) : Exception(sentence)
