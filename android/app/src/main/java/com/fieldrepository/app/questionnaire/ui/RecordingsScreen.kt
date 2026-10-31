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
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QSession
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireFilters
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.ui.AudioPlayer
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch

/**
 * A list's filters, kept per screen ([FilterStore]) for this account: what a screen reads on its first
 * frame and writes on every change, so the list comes back as it was left after navigating away or
 * a restart. [initial] (a chip tapped elsewhere) replaces what was kept.
 */
@Composable
fun rememberKeptFilters(v2: QuestionnaireV2, screen: String, initial: QuestionnaireFilters? = null): Pair<QuestionnaireFilters, (QuestionnaireFilters) -> Unit> {
    var filters by remember(screen) { mutableStateOf(initial ?: v2.filterStore.load(screen)) }
    LaunchedEffect(screen, initial) { if (initial != null) { filters = initial; v2.filterStore.save(screen, initial) } }
    return filters to { next: QuestionnaireFilters -> filters = next; v2.filterStore.save(screen, next) }
}

/**
 * The recordings list and search (owner, 31 Oct 2026): every take matching the shared filters,
 * newest first, each with its chips; tapping a chip filters by it. With no signal it filters the
 * takes this phone last read and says so.
 *
 * @param initial filters to open with (a chip tapped on another screen), replacing the kept ones.
 * @param fixed filters this host sets itself (e.g. one form), never shown or cleared here.
 */
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
    var rows by remember { mutableStateOf<List<QRecording>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(applied) {
        loading = true
        val page = runCatching { v2.recordingsPage(applied) }.getOrNull()
        rows = page?.items.orEmpty(); cursor = page?.nextCursor; offline = page?.offline == true
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
                Modifier.fillMaxWidth().clickable { scope.launch { playing = v2.playbackUrl(r.id)?.let(Uri::parse) } }.padding(vertical = 6.dp).testTag("recording-row"),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                r.chips?.session?.title?.ifBlank { null }?.let { title ->
                    Text(
                        title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { r.sessionId?.let(onOpenSession) }
                    )
                }
                RecordingChips(r.chips, filters = applied, onChip = onChip)
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)
        }
        if (cursor != null) PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("sessions.loadMore"), primary = true) {
            val at = cursor ?: return@PromptButton
            scope.launch {
                runCatching { v2.recordingsPage(applied, at) }
                    .onSuccess { next -> rows = rows + next.items.filter { n -> rows.none { it.id == n.id } }; cursor = next.nextCursor }
                    .onFailure { onError(QuestionnaireCopy.s("error.needsSignal")) }
            }
        }))
        playing?.let { AudioPlayer(it) }
    }
}

/** [filters] with the host's own [fixed] values on top. */
internal fun merged(filters: QuestionnaireFilters, fixed: QuestionnaireFilters): QuestionnaireFilters =
    fixed.values.entries.fold(filters) { f, (k, v) -> f.with(k, v) }

/**
 * The sessions held on one form (UNIFY 4.2, form detail), with the shared search and filters, its
 * form fixed. Each row carries its chips; tapping one filters this list.
 */
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
    var rows by remember { mutableStateOf<List<QSession>>(emptyList()) }
    var offline by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(applied) {
        loading = true
        val page = runCatching { v2.sessionsPage(applied, limit = 50) }.getOrNull()
        rows = page?.items.orEmpty(); offline = page?.offline == true
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
    }
}

/**
 * The recordings list over a screen that has none (an artisan's record): opened by a tapped chip,
 * filtered by it, closed with Done. Its filters are this dialog's own, so the main list's are kept.
 */
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
