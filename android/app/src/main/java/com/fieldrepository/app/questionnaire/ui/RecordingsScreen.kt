package com.fieldrepository.app.questionnaire.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.questionnaire.data.ChipFilter
import com.fieldrepository.app.questionnaire.data.FilterStore
import com.fieldrepository.app.questionnaire.data.KeptPages
import com.fieldrepository.app.questionnaire.data.ListPaging
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QSession
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireFilters
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.transcript.ui.TranscriptPreviewText
import com.fieldrepository.app.ui.AudioExpand
import com.fieldrepository.app.ui.AudioPlayer
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch

@Composable
fun rememberKeptFilters(v2: QuestionnaireV2, screen: String, initial: QuestionnaireFilters? = null): Pair<QuestionnaireFilters, (QuestionnaireFilters) -> Unit> {
    val applied = rememberKept("$screen.initialApplied") { null as QuestionnaireFilters? }
    var filters by remember(screen) { mutableStateOf(initial?.takeIf { it != applied.value } ?: v2.filterStore.load(screen)) }
    LaunchedEffect(screen, initial) { if (initial != null && initial != applied.value) { applied.value = initial; filters = initial; v2.filterStore.save(screen, initial) } }
    return filters to { next: QuestionnaireFilters -> filters = next; v2.filterStore.save(screen, next) }
}

@Composable
fun RecordingsList(
    v2: QuestionnaireV2,
    modifier: Modifier = Modifier,
    initial: QuestionnaireFilters? = null,
    fixed: QuestionnaireFilters = QuestionnaireFilters(),
    screen: String = FilterStore.RECORDINGS,
    onOpenSession: (sessionId: String) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val (filters, setFilters) = rememberKeptFilters(v2, screen, initial)
    val applied = merged(filters, fixed)
    var kept by rememberKept("$screen.pages") { KeptPages<QRecording>() }
    val rows = kept.rows
    val cursor = kept.cursor
    var offline by rememberKept("$screen.offline") { false }
    var loading by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf<Uri?>(null) }
    var expandFor by remember { mutableStateOf<AudioExpand?>(null) }
    LaunchedEffect(applied) {
        loading = true
        val page = runCatching { v2.recordingsPage(applied) }.getOrNull()
        kept = ListPaging.arrive(kept, applied, page?.items, page?.nextCursor) { it.id }; offline = page?.offline == true
        loading = false
    }
    val onChip = { f: ChipFilter, label: String -> setFilters(filters.applyChip(f, label)) }
    Column(modifier.fillMaxWidth().testTag("recordings-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(QuestionnaireCopy.t("recordingsTitle"), style = MaterialTheme.typography.titleLarge)
        FilterBar(
            filters, setFilters, QuestionnaireCopy.t("filterSearchRecordings"),
            options = { facet, q, forms -> v2.filterOptions(facet, q, forms) },
            fixed = fixed.values.keys, offline = offline
        )
        if (loading) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && rows.isEmpty()) Text(QuestionnaireCopy.t("filterNoMatches"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
        rows.forEach { r ->
            Column(
                Modifier.fillMaxWidth().clickable { scope.launch { playing = v2.playbackUrl(r.id)?.let(Uri::parse); expandFor = r.audioExpand() } }.padding(vertical = 6.dp).testTag("recording-row"),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                r.chips?.session?.title?.ifBlank { null }?.let { title ->
                    Text(
                        title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { r.sessionId?.let(onOpenSession) }
                    )
                }
                r.transcript?.shownPreview()?.let { TranscriptPreviewText(it, maxLines = 3) }
                RecordingChips(r.chips, filters = applied, onChip = onChip)
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)
        }
        if (cursor != null) PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("sessions.loadMore"), primary = true) {
            val at = cursor ?: return@PromptButton
            scope.launch {
                runCatching { v2.recordingsPage(applied, at) }
                    .onSuccess { next -> kept = ListPaging.more(kept, next.items, next.nextCursor) { it.id } }
                    .onFailure { onError(QuestionnaireCopy.s("error.needsSignal")) }
            }
        }))
        playing?.let { AudioPlayer(it, expand = expandFor) }
    }
}

internal fun QRecording.audioExpand(): AudioExpand? =
    mediaFileId?.takeIf { it.isNotBlank() }?.let { AudioExpand(it, downloadName.orEmpty()) }

internal fun merged(filters: QuestionnaireFilters, fixed: QuestionnaireFilters): QuestionnaireFilters =
    fixed.values.entries.fold(filters) { f, (k, v) -> f.with(k, v) }

@Composable
fun FormSessionsList(
    v2: QuestionnaireV2,
    questionnaireId: String,
    modifier: Modifier = Modifier,
    onOpenSession: (QSession) -> Unit = {}
) {
    val fixed = remember(questionnaireId) { QuestionnaireFilters().with("questionnaireId", listOf(questionnaireId)) }
    val (filters, setFilters) = rememberKeptFilters(v2, FilterStore.formSessions(questionnaireId))
    val applied = merged(filters, fixed)
    var rows by rememberKept("form-sessions-$questionnaireId.rows") { emptyList<QSession>() }
    var offline by rememberKept("form-sessions-$questionnaireId.offline") { false }
    var cursor by rememberKept("form-sessions-$questionnaireId.cursor") { null as String? }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(applied) {
        loading = true
        val page = runCatching { v2.sessionsPage(applied, limit = 50) }.getOrNull()
        if (page != null || rows.isEmpty()) {
            rows = page?.items.orEmpty()
            cursor = page?.nextCursor
        }
        offline = page?.offline == true
        loading = false
    }
    Column(modifier.fillMaxWidth().testTag("form-sessions"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(QuestionnaireCopy.s("home.sessions"), style = MaterialTheme.typography.titleMedium)
        FilterBar(
            filters, setFilters, QuestionnaireCopy.t("filterSearchSessions"),
            options = { facet, q, forms -> v2.filterOptions(facet, q, forms.ifEmpty { listOf(questionnaireId) }) },
            fixed = setOf("questionnaireId", "kind"), offline = offline
        )
        if (loading) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && rows.isEmpty()) Text(QuestionnaireCopy.t("filterNoMatches"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        rows.forEach { s ->
            Column(Modifier.fillMaxWidth().clickable { onOpenSession(s) }.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.title, style = MaterialTheme.typography.bodyMedium)
                RecordingChips(s.chips, session = true, filters = applied, onChip = { f, label -> setFilters(filters.applyChip(f, label)) })
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)
        }
        val after = cursor
        if (!loading && after != null) PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("sessions.loadMore"), primary = true) {
            if (loadingMore) return@PromptButton
            loadingMore = true
            scope.launch {
                runCatching { v2.sessionsPage(applied, after, 50) }.onSuccess { next ->
                    val seen = rows.map { it.id }.toHashSet()
                    rows = rows + next.items.filter { it.id !in seen }
                    cursor = next.nextCursor
                }
                loadingMore = false
            }
        }))
    }
}

@Composable
fun RecordingsDialog(v2: QuestionnaireV2, initial: QuestionnaireFilters, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                TextButton(onClick = onDismiss) { Text(QuestionnaireCopy.t("filterDone")) }
                RecordingsList(v2, initial = initial, screen = "recordings-over")
            }
        }
    }
}
