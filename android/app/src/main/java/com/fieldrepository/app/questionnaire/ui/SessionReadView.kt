package com.fieldrepository.app.questionnaire.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QReviewAction
import com.fieldrepository.app.questionnaire.data.QSession
import com.fieldrepository.app.questionnaire.data.QSessionDetail
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.data.SharedLanguages
import com.fieldrepository.app.ui.AudioPlayer
import com.fieldrepository.app.transcript.data.TranscriptUiText
import com.fieldrepository.app.transcript.ui.TranscriptDialog
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Why Approve is disabled (DESIGN §3.10), in the order the reviewer fixes them; null when it may go. */
internal fun approveBlocker(detail: QSessionDetail): String? {
    val decisions = detail.recordings.count { it.removedAt == null && it.filing == "NEEDS_DECISION" }
    val people = detail.participants.size + detail.respondents.count { it.artisanId == null }
    return when {
        people == 0 -> QuestionnaireCopy.s("read.blockedNoParticipants")
        detail.recordings.none { it.removedAt == null } && detail.answers.none { it.text.isNotBlank() } -> QuestionnaireCopy.s("read.blockedEmpty")
        decisions > 0 -> QuestionnaireCopy.approveBlockedDecide(decisions)
        else -> null
    }
}

/** The review's flags line: "2 repeats · 1 needs a decision · dates estimated". */
internal fun reviewFlags(detail: QSessionDetail): String {
    val live = detail.recordings.filter { it.removedAt == null }
    val repeats = live.count { r -> r.subjects.any { (it.position ?: 1) >= 2 } }
    val decisions = live.count { it.filing == "NEEDS_DECISION" }
    return QuestionnaireCopy.flagsLine(repeats, decisions, estimated = live.any { it.estimated } || detail.sessionDate == null)
}

/**
 * The read view and review (DESIGN §4.8, B27): the session's fields, its recordings grouped by section
 * in the form's order, its answers by section. A reviewer sees the flags and Return · Approve; Approve
 * is disabled with its reason, and approving cascades to the session's live takes and answers on the
 * server. Update (centred) opens the workspace.
 */
@Composable
fun SessionReadView(
    v2: QuestionnaireV2,
    sessionId: String,
    canReview: Boolean,
    loadInstrument: suspend (questionnaireId: String) -> Instrument?,
    onBack: () -> Unit,
    onUpdate: (QSession) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<QSessionDetail?>(null) }
    var summary by remember { mutableStateOf<QSession?>(null) }
    var instrument by remember { mutableStateOf<Instrument?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var returning by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(sessionId, attempt) {
        failed = false
        runCatching { v2.api.session(sessionId) }.onSuccess { d ->
            detail = d
            instrument = d.questionnaire?.id?.let { loadInstrument(it) }
        }.onFailure { failed = true }
        summary = runCatching { v2.api.sessionSummary(sessionId) }.getOrNull()
    }

    fun review(work: suspend () -> Unit, done: String) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { work() }.onSuccess { onError(done); attempt++ }.onFailure { onError(it.userSentence()) }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ " + QuestionnaireCopy.s("sessions.title")) }
        val d = detail
        when {
            failed -> {
                Text(QuestionnaireCopy.s("record.error"), style = MaterialTheme.typography.bodyMedium)
                PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("record.tryAgain"), primary = true) { attempt++ }))
            }
            d == null -> FieldLinearProgressIndicator(Modifier.fillMaxWidth())
            else -> {
                Text(d.title, style = MaterialTheme.typography.titleLarge)
                d.legacyTitle?.ifBlank { null }?.let { Text(QuestionnaireCopy.earlierTitle(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
                d.note?.ifBlank { null }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
                val people = d.participants.map { it.name } + d.respondents.filter { it.artisanId == null }.map { it.name + " · " + QuestionnaireCopy.s("guest.label") }
                listOfNotNull(
                    d.questionnaire?.title,
                    d.sessionDate?.let { runCatching { QuestionnaireCopy.day(LocalDate.parse(it.take(10))) }.getOrNull() },
                    QuestionnaireCopy.mode(people.size),
                    people.joinToString(", ").ifBlank { null },
                    d.researchers.joinToString(", ") { it.name }.ifBlank { null },
                    d.place?.ifBlank { null },
                    d.languageCode?.let { SharedLanguages.labels[it] ?: it }?.let { if (it == "Other") d.languageOther ?: it else it }
                ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body) }
                LocalSessionReaders.current?.invoke(sessionId, attempt)
                HorizontalDivider(color = MaterialTheme.field.hairline)
                val ins = instrument
                val live = d.recordings.filter { it.removedAt == null }
                ins?.sections.orEmpty().forEach { section ->
                    val keys = setOf("S:${section.id}") + section.questions.map { "Q:${it.lineageId}" }
                    val takes = live.filter { r -> r.targets.any { it.targetKey in keys } }.sortedBy { it.capturedAt.orEmpty() }
                    val answered = section.questions.mapNotNull { q ->
                        d.answers.filter { it.questionLineageId == q.lineageId && it.text.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { q to it }
                    }
                    if (takes.isEmpty() && answered.isEmpty()) return@forEach
                    Text("${section.code}  ${section.title}", style = MaterialTheme.typography.titleMedium)
                    takes.forEach { r -> TakeLine(r) { scope.launch { playing = v2.playbackUrl(r.id)?.let(Uri::parse) } } }
                    answered.forEach { (q, list) ->
                        Text(q.displayCode + "  " + q.prompt, style = MaterialTheme.typography.labelLarge)
                        list.forEach { a ->
                            Text((d.nameOf(a.subjectId)?.let { QuestionnaireCopy.firstName(it) + ": " } ?: "") + a.text, style = MaterialTheme.typography.bodySmall)
                            a.note?.ifBlank { null }?.let { Text(QuestionnaireCopy.s("answer.note") + ": " + it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted) }
                        }
                    }
                }
                live.filter { it.targets.isEmpty() }.takeIf { it.isNotEmpty() }?.let { whole ->
                    Text(QuestionnaireCopy.s("record.wholeSession"), style = MaterialTheme.typography.titleMedium)
                    whole.forEach { r -> TakeLine(r) { scope.launch { playing = v2.playbackUrl(r.id)?.let(Uri::parse) } } }
                }
                playing?.let { AudioPlayer(it) }
                if (canReview && d.status != "APPROVED") {
                    HorizontalDivider(color = MaterialTheme.field.hairline)
                    Text(reviewFlags(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                    val blocker = approveBlocker(d)
                    blocker?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    PromptButtons(listOf(
                        PromptButton(QuestionnaireCopy.s("read.return")) { returning = true },
                        PromptButton(QuestionnaireCopy.s("read.approve"), primary = true) {
                            if (blocker == null) review({ v2.api.approveSession(sessionId, QReviewAction()) }, QuestionnaireCopy.s("read.approved"))
                        }
                    ))
                } else if (d.status == "APPROVED") {
                    Text(QuestionnaireCopy.s("read.approved"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                }
                summary?.let { s -> PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("p14.update"), primary = !canReview) { onUpdate(s) })) }
            }
        }
    }
    if (returning) {
        var notes by remember { mutableStateOf("") }
        QuestionnairePromptContent(
            title = QuestionnaireCopy.s("read.return"),
            onDismiss = { returning = false },
            content = {
                OutlinedTextField(value = notes, onValueChange = { notes = it.take(2000) }, label = { Text(QuestionnaireCopy.s("read.returnNote")) }, modifier = Modifier.fillMaxWidth())
            },
            buttons = listOf(
                PromptButton(QuestionnaireCopy.s("session.cancel")) { returning = false },
                PromptButton(QuestionnaireCopy.s("read.return"), primary = true) {
                    if (notes.isBlank()) return@PromptButton
                    returning = false
                    review({ v2.api.returnSession(sessionId, QReviewAction(notes.trim())) }, QuestionnaireCopy.s("read.returned"))
                }
            )
        )
    }
}

@Composable
private fun TakeLine(r: QRecording, onPlay: () -> Unit) {
    var transcript by remember { mutableStateOf(false) }
    if (transcript) r.mediaFileId?.let { id -> TranscriptDialog(id, audio = null, onDismiss = { transcript = false }) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOfNotNull(
                ArtisanTimeline.targetsLabel(r.targets), QuestionnaireCopy.minutes(r.durationMs), r.recordedBy?.name?.ifBlank { null }?.let(QuestionnaireCopy::firstName),
                dayAndTime(r.capturedAt), QuestionnaireCopy.setting(r.rosterCount), QuestionnaireCopy.s("record.needsDecision").takeIf { r.filing == "NEEDS_DECISION" }
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
        )
        if (r.mediaFileId != null) TextButton(onClick = { transcript = true }) { Text(TranscriptUiText.open, style = MaterialTheme.typography.labelSmall) }
        Text("▶", style = MaterialTheme.typography.bodySmall)
    }
    // The take's chips (owner, 31 Oct 2026); a tap opens the recordings list filtered by it.
    RecordingChips(r.chips, onChip = QuestionnaireV2Nav::openRecordings)
}
