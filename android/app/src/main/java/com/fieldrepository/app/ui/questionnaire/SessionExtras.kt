package com.fieldrepository.app.ui.questionnaire

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.CoverageMarks
import com.fieldrepository.app.data.FormRights
import com.fieldrepository.app.data.FormsCopy
import com.fieldrepository.app.data.QAnswer
import com.fieldrepository.app.data.QForm
import com.fieldrepository.app.data.QGrid
import com.fieldrepository.app.data.QGridCell
import com.fieldrepository.app.data.QGuestAnswerItem
import com.fieldrepository.app.data.QGuestAnswersPut
import com.fieldrepository.app.data.QRecording
import com.fieldrepository.app.data.QRecordingEvent
import com.fieldrepository.app.data.QRespondent
import com.fieldrepository.app.data.QRespondentPatch
import com.fieldrepository.app.data.QResearcherAdd
import com.fieldrepository.app.data.QRevision
import com.fieldrepository.app.data.QSession
import com.fieldrepository.app.data.QSessionDetail
import com.fieldrepository.app.data.QSnapshotTake
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.data.QuestionnaireV2
import com.fieldrepository.app.data.SectionOverride
import com.fieldrepository.app.data.SessionUnit
import com.fieldrepository.app.data.StepKind
import com.fieldrepository.app.data.StepState
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.WorkshopDetailDto
import com.fieldrepository.app.data.outboxJson
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch

// ── Pure models (JVM-tested) ─────────────────────────────────────────────────────────────────

/** A guest respondent as the workspace draws them: their key (server id once landed) and name. */
data class GuestModel(val key: String, val name: String, val notes: String?, val serverId: String?)

object GuestRoster {
    /**
     * The session's guests: the server's (artisan-less respondents) and the ones this phone named
     * that have not landed yet. A guest registered as an artisan has left the guest list.
     */
    fun of(unit: SessionUnit?, detail: QSessionDetail?): List<GuestModel> {
        val server = detail?.respondents.orEmpty().filter { it.artisanId == null }.map { GuestModel(it.id, it.name, it.notes, it.id) }
        val mapped = unit?.artisanIds.orEmpty()
        val local = unit?.guests.orEmpty().filter { (key, _) -> mapped[key] == null || server.none { it.serverId == mapped[key] } }
            .map { (key, name) -> GuestModel(key, name, null, mapped[key]) }
        return server + local.filter { l -> server.none { it.serverId == l.serverId && l.serverId != null } }
    }

    /** The text a guest has for [lineageId]: queued on this phone first, else the server's. */
    fun answer(unit: SessionUnit?, detail: QSessionDetail?, guest: GuestModel, lineageId: String): String? {
        val queued = unit?.steps.orEmpty().filter { it.kind == StepKind.GUEST_ANSWERS && it.state != StepState.DONE }.sortedBy { it.deviceTime }
            .mapNotNull { s -> s.bodyJson?.let { runCatching { outboxJson.decodeFromString(QGuestAnswersPut.serializer(), it) }.getOrNull() } }
            .flatMap { it.items }.lastOrNull { (it.respondentId == guest.key || it.respondentId == guest.serverId) && it.questionLineageId == lineageId }?.text
        return queued ?: detail?.answers.orEmpty().firstOrNull { it.respondentId != null && it.respondentId == guest.serverId && it.questionLineageId == lineageId }?.text?.ifBlank { null }
    }

    fun item(guest: GuestModel, lineageId: String, text: String, sessionId: String, note: String?, answers: List<QAnswer>): QGuestAnswerItem =
        QGuestAnswerItem(
            respondentId = guest.serverId ?: guest.key, questionLineageId = lineageId, text = text, sessionId = sessionId,
            baseVersion = answers.firstOrNull { it.respondentId == guest.serverId && it.questionLineageId == lineageId }?.version,
            note = note?.trim()?.ifBlank { null }
        )
}

/** B24: prefill from the artisan's record (`artisanField`, origin PREFILL). The server fills the value it holds. */
object PrefillModel {
    const val LABEL = "Fill from artisan records"

    /** Items for every mapped question an artisan has no answer to yet; nothing that would overwrite. */
    fun items(form: QForm?, artisanIds: List<String>, sessionId: String, answers: List<QAnswer>, valueOf: (String, String) -> String?): List<com.fieldrepository.app.data.QAnswerItem> {
        form ?: return emptyList()
        val mapped = form.sections.filter { it.isActive }.flatMap { it.questions }.filter { it.isActive && it.retiredAt == null && it.supersededById == null && !it.artisanField.isNullOrBlank() }
        return artisanIds.flatMap { a ->
            mapped.mapNotNull { q ->
                val lineage = q.lineageId ?: q.id
                if (answers.any { it.artisanId == a && it.questionLineageId == lineage && it.text.isNotBlank() }) return@mapNotNull null
                com.fieldrepository.app.data.QAnswerItem(a, lineage, valueOf(a, q.artisanField!!).orEmpty(), origin = "PREFILL", sessionId = sessionId)
            }
        }
    }

    /** What this phone already knows; the server replaces it with its own copy of the record. */
    fun localValue(artisan: com.fieldrepository.app.data.ArtisanDto, field: String): String? = when (field) {
        "name" -> artisan.name
        "place" -> artisan.place
        "craft" -> artisan.craft?.name
        else -> null
    }?.ifBlank { null }
}

object RequiredModel {
    /**
     * A19, offline: the required questions (by lineage) that [subjects] have neither answered in text
     * nor been recorded on (a question take, or a take on its section). Never blocks Finish.
     */
    fun unanswered(form: QForm?, subjects: List<String>, answers: List<QAnswer>, takes: List<QSnapshotTake>): Int {
        form ?: return 0
        val required = form.sections.filter { it.isActive }.flatMap { s ->
            s.questions.filter { it.isRequired && it.isActive && it.retiredAt == null && it.supersededById == null }.map { (it.lineageId ?: it.id) to s.id }
        }
        if (required.isEmpty()) return 0
        return subjects.sumOf { subject ->
            required.count { (lineage, section) ->
                val typed = answers.any { it.artisanId == subject && it.questionLineageId == lineage && it.text.isNotBlank() }
                val recorded = takes.any { it.artisanId == subject && it.filing != "NEEDS_DECISION" && (it.targetKey == "Q:$lineage" || it.targetKey == "S:$section") }
                !typed && !recorded
            }
        }
    }
}

/** The read view (B27): every answer and take of a session, grouped by section, read-only. */
data class ReadLine(val code: String, val prompt: String, val answers: List<String>, val takes: Int)

object ReadViewModel {
    fun lines(instrument: Instrument?, detail: QSessionDetail?): List<ReadLine> {
        instrument ?: return emptyList()
        val names = detail?.participants.orEmpty().associate { it.artisanId to it.name } + detail?.respondents.orEmpty().associate { it.id to it.name }
        val recordings = detail?.recordings.orEmpty().filter { it.removedAt == null }
        return instrument.sections.flatMap { s ->
            s.questions.map { q ->
                val answers = detail?.answers.orEmpty().filter { it.questionLineageId == q.lineageId && it.text.isNotBlank() }
                    .map { a -> (names[a.artisanId ?: a.respondentId]?.let { QuestionnaireCopy.firstName(it) + ": " } ?: "") + a.text }
                val takes = recordings.count { r -> r.targets.any { it.questionLineageId == q.lineageId || it.sectionId == s.id } }
                ReadLine(q.displayCode, q.prompt, answers, takes)
            }
        }
    }
}

/** "Edited by Nandini · 7 Jul 14:20 · current" for one revision. */
fun revisionLine(r: QRevision): String = listOfNotNull(
    r.actor.name.ifBlank { null },
    r.createdAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }?.let { "${QuestionnaireCopy.day(it.atZone(com.fieldrepository.app.data.Naming.IST).toLocalDate())} ${QuestionnaireCopy.clock(it)}" },
    r.origin?.lowercase()?.replaceFirstChar { it.uppercase() },
    "Current".takeIf { r.current }
).joinToString(" · ")

fun eventLine(e: QRecordingEvent): String = listOfNotNull(
    e.kind.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
    e.actor?.name?.ifBlank { null },
    e.at?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }?.let { "${QuestionnaireCopy.day(it.atZone(com.fieldrepository.app.data.Naming.IST).toLocalDate())} ${QuestionnaireCopy.clock(it)}" }
).joinToString(" · ")

// ── Dialogs ──────────────────────────────────────────────────────────────────────────────────

@Composable
fun GuestDialog(initialName: String = "", initialNotes: String = "", title: String = FormsCopy.SOMEONE_ELSE, withNotes: Boolean = false, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var notes by remember { mutableStateOf(initialNotes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(120) }, label = { Text(FormsCopy.GUEST_NAME) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (withNotes) OutlinedTextField(notes, { notes = it.take(2000) }, label = { Text(FormsCopy.GUEST_NOTES) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { PromptButtons(listOf(PromptButton(FormsCopy.CANCEL, onClick = onDismiss), PromptButton(FormsCopy.SAVE, primary = true) { if (name.isNotBlank()) onSave(name.trim(), notes.trim()) })) }
    )
}

/** Overload kept for the start sheet: a name only. */
@Composable
fun GuestNameDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) = GuestDialog(onDismiss = onDismiss, onSave = { name, _ -> onSave(name) })

/** B21: an answer's earlier versions, newest first, with who wrote each. */
@Composable
fun AnswerHistoryDialog(revisions: List<QRevision>?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FormsCopy.HISTORY, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    revisions == null -> FieldLinearProgressIndicator(Modifier.fillMaxWidth())
                    revisions.isEmpty() -> Text(FormsCopy.NO_HISTORY, style = MaterialTheme.typography.bodyMedium)
                    else -> revisions.forEach { r ->
                        Column(Modifier.testTag("revision-${r.id}")) {
                            Text(r.text.ifBlank { "(empty)" }, style = MaterialTheme.typography.bodyMedium)
                            Text(revisionLine(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                            r.note?.takeIf { it.isNotBlank() }?.let { Text("${FormsCopy.ANSWER_NOTE}: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = { PromptButtons(listOf(PromptButton("OK", primary = true, onClick = onDismiss))) }
    )
}

/** B40: a take's history (moves, refilings, removals, restores). */
@Composable
fun RecordingHistoryDialog(events: List<QRecordingEvent>?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FormsCopy.RECORDING_HISTORY, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    events == null -> FieldLinearProgressIndicator(Modifier.fillMaxWidth())
                    events.isEmpty() -> Text(FormsCopy.NO_HISTORY, style = MaterialTheme.typography.bodyMedium)
                    else -> events.forEach { Text(eventLine(it), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = { PromptButtons(listOf(PromptButton("OK", primary = true, onClick = onDismiss))) }
    )
}

/** B6: add a researcher from the workshop's team. */
@Composable
fun ResearchersDialog(v2: QuestionnaireV2, session: QSessionDetail, people: List<Pair<String, String>>, onDismiss: () -> Unit, onAdded: () -> Unit, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var userId by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("CONTRIBUTOR") }
    val already = session.researchers.map { it.userId }.toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FormsCopy.RESEARCHERS, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                session.researchers.forEach { Text("${it.name} · ${it.role.lowercase().replaceFirstChar { c -> c.uppercase() }}", style = MaterialTheme.typography.bodyMedium) }
                SearchableSelectField(
                    label = FormsCopy.ADD_RESEARCHER, options = people.filter { it.first !in already }.map { SelectOption(it.first, it.second) },
                    selectedValue = userId, includeNone = false
                ) { userId = it }
                SearchableSelectField(
                    label = "Role", options = listOf(SelectOption("CONTRIBUTOR", "Contributor"), SelectOption("LEAD", "Lead")),
                    selectedValue = role, includeNone = false
                ) { role = it }
            }
        },
        confirmButton = {
            PromptButtons(listOf(
                PromptButton(FormsCopy.CANCEL, onClick = onDismiss),
                PromptButton(FormsCopy.ADD, primary = true) {
                    if (userId.isBlank()) return@PromptButton
                    scope.launch {
                        runCatching { v2.forms.addResearcher(session.id, QResearcherAdd(userId, role)) }
                            .onSuccess { onAdded() }.onFailure { onError(errorText(it, QuestionnaireCopy.s("error.needsSignal"))) }
                    }
                }
            ))
        }
    )
}

/** The guests of a session, each with typed answers to the current section (owner decision 1; A17, A18). */
@Composable
fun GuestsPanel(
    guests: List<GuestModel>,
    questions: List<InstrumentQuestion>,
    answerOf: (GuestModel, String) -> String?,
    locked: Boolean,
    online: Boolean,
    onSave: (GuestModel, InstrumentQuestion, String, String?) -> Unit,
    onEdit: (GuestModel) -> Unit
) {
    if (guests.isEmpty()) return
    Text("Guests", style = MaterialTheme.typography.titleMedium)
    guests.forEach { g ->
        Surface(color = MaterialTheme.field.surface100, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(g.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (g.serverId != null && online && !locked) TextButton(onClick = { onEdit(g) }) { Text(FormsCopy.EDIT_RESPONDENT) }
                }
                g.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
                questions.forEach { q ->
                    val current = answerOf(g, q.lineageId).orEmpty()
                    var text by remember(g.key, q.lineageId, current) { mutableStateOf(current) }
                    var note by remember(g.key, q.lineageId) { mutableStateOf("") }
                    Text("${q.displayCode}  ${q.prompt}", style = MaterialTheme.typography.bodySmall)
                    if (locked) Text(current.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                    else {
                        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().testTag("guest-${g.key}-${q.lineageId}"))
                        if (text.isNotBlank() && text.trim() != current) {
                            OutlinedTextField(note, { note = it.take(2000) }, label = { Text(FormsCopy.ANSWER_NOTE) }, modifier = Modifier.fillMaxWidth())
                            PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("save"), primary = true) { onSave(g, q, text.trim(), note) }))
                        }
                    }
                }
            }
        }
    }
}

/** P15 inline: the answer is read-only for this viewer. */
@Composable
fun LockedAnswerNote(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted, modifier = Modifier.testTag("p15"))
}

// ── Read view and review (B27) ───────────────────────────────────────────────────────────────

@Composable
fun SessionReadView(lines: List<ReadLine>, title: String, status: String, mayApprove: Boolean, onApprove: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ " + QuestionnaireCopy.s("sessions.title")) }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(status.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        lines.forEach { l ->
            Column(Modifier.fillMaxWidth()) {
                Text("${l.code}  ${l.prompt}", style = MaterialTheme.typography.bodyMedium)
                if (l.answers.isEmpty() && l.takes == 0) Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                l.answers.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body) }
                if (l.takes > 0) Text("${CoverageMarks.RECORDED} ${l.takes} ${if (l.takes == 1) "take" else "takes"}", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)
        }
        if (mayApprove && status != "APPROVED") PromptButtons(listOf(PromptButton(FormsCopy.APPROVE, primary = true, onClick = onApprove)))
    }
}

// ── Deleted sessions (A14) ───────────────────────────────────────────────────────────────────

@Composable
fun DeletedSessionsScreen(v2: QuestionnaireV2, onBack: () -> Unit, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<QSession>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { rows = runCatching { v2.forms.deletedSessions().items }.getOrElse { onError(errorText(it, QuestionnaireCopy.s("error.needsSignal"))); emptyList() } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ " + QuestionnaireCopy.s("sessions.title")) }
        Text(FormsCopy.DELETED_SESSIONS, style = MaterialTheme.typography.titleLarge)
        val list = rows
        if (list == null) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
        else if (list.isEmpty()) Text(FormsCopy.NOTHING_DELETED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
        list.orEmpty().forEach { s ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.bodyMedium)
                    Text(serverRow(s).meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                }
                TextButton(onClick = {
                    scope.launch { runCatching { v2.forms.restoreSession(s.id) }.onSuccess { refresh++ }.onFailure { onError(errorText(it, "Couldn't restore it.")) } }
                }) { Text(FormsCopy.RESTORE) }
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)
        }
    }
}

// ── Workshop coverage (B12, B13, D1, D4) ─────────────────────────────────────────────────────

/** One cell of the workshop grid: ● recorded, ✎ typed, the count, and the admin's override. */
@Composable
fun CoverageCellView(cell: QGridCell, artisan: String, section: String, onClick: (() -> Unit)?) {
    val text = listOf(CoverageMarks.mark(cell), CoverageMarks.count(cell)).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "○" }
    Column(
        Modifier.width(96.dp).heightIn(min = 48.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .semantics { contentDescription = CoverageMarks.accessibility(cell, artisan, section) }.padding(4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        cell.override?.let { Text(SectionOverride.label(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        CoverageMarks.prompt(cell)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted) }
    }
}

@Composable
fun WorkshopCoverageGrid(grid: QGrid, mayOverride: Boolean, onOverride: (QGridCell, String) -> Unit) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            Text("", Modifier.width(120.dp))
            grid.artisans.forEach { Text(QuestionnaireCopy.firstName(it.name), Modifier.width(96.dp).padding(4.dp), style = MaterialTheme.typography.labelLarge) }
        }
        grid.sections.forEach { s ->
            Row {
                Text("${s.code} ${s.title}", Modifier.width(120.dp).padding(4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                grid.artisans.forEach { a ->
                    val cell = s.cells.firstOrNull { it.artisanId == a.artisanId } ?: QGridCell(artisanId = a.artisanId)
                    CoverageCellView(cell, a.name, s.code, onClick = if (mayOverride) ({ onOverride(cell, s.sectionId) }) else null)
                }
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)
        }
        Text("${CoverageMarks.RECORDED} Recorded   ${CoverageMarks.TYPED} Typed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The admin's override: Marked complete · Needs review · Needs redo, or Clear (stacked: more than two). */
@Composable
fun OverrideDialog(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FormsCopy.OVERRIDE, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionOverride.STATES.forEach { (state, label) ->
                    androidx.compose.material3.OutlinedButton(onClick = { onPick(state) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (state == current) "$label ✓" else label)
                    }
                }
                if (current != null) TextButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) { Text(FormsCopy.CLEAR_OVERRIDE) }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun WorkshopCoverageScreen(v2: QuestionnaireV2, user: UserDto?, workshops: List<WorkshopDetailDto>, forms: List<Pair<String, String>>, onBack: () -> Unit, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var workshopId by remember { mutableStateOf(com.fieldrepository.app.ui.mostRecentRecordableWorkshop(workshops)?.id.orEmpty()) }
    var formId by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf<QGrid?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var overriding by remember { mutableStateOf<Pair<QGridCell, String>?>(null) }
    LaunchedEffect(workshopId) {
        if (workshopId.isBlank() || formId.isNotBlank()) return@LaunchedEffect
        formId = runCatching { v2.forms.formFor(workshopId).questionnaireId }.getOrNull() ?: forms.firstOrNull()?.first.orEmpty()
    }
    LaunchedEffect(workshopId, formId, refresh) {
        grid = null
        if (workshopId.isBlank() || formId.isBlank()) return@LaunchedEffect
        grid = runCatching { v2.forms.coverage(formId, workshopId) }.getOrElse { onError(errorText(it, QuestionnaireCopy.s("error.needsSignal"))); null }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ " + QuestionnaireCopy.s("sessions.title")) }
        Text(FormsCopy.COVERAGE, style = MaterialTheme.typography.titleLarge)
        SearchableSelectField(label = QuestionnaireCopy.s("start.workshop"), options = workshops.map { SelectOption(it.id, it.title, it.place.ifBlank { null }) }, selectedValue = workshopId, includeNone = false, searchable = true) { workshopId = it; formId = "" }
        SearchableSelectField(label = QuestionnaireCopy.s("start.instrument"), options = forms.map { SelectOption(it.first, it.second) }, selectedValue = formId, includeNone = false) { formId = it }
        val g = grid
        when {
            workshopId.isBlank() || formId.isBlank() -> Text(FormsCopy.COVERAGE_PICK, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
            g == null -> FieldLinearProgressIndicator(Modifier.fillMaxWidth())
            else -> WorkshopCoverageGrid(g, FormRights.mayOverride(user)) { cell, sectionId -> overriding = cell to sectionId }
        }
    }
    overriding?.let { (cell, sectionId) ->
        OverrideDialog(cell.override, onDismiss = { overriding = null }) { state ->
            overriding = null
            scope.launch {
                runCatching { v2.forms.setCompletion(SectionOverride.body(cell.artisanId, sectionId, state)) }
                    .onSuccess { refresh++ }.onFailure { onError(errorText(it, QuestionnaireCopy.s("error.needsSignal"))) }
            }
        }
    }
}

/** Respondent edit (A17): name and notes, online, so personal data can be retracted. */
suspend fun saveRespondent(v2: QuestionnaireV2, sessionId: String, guest: GuestModel, name: String, notes: String): QRespondent =
    v2.forms.patchRespondent(sessionId, guest.serverId!!, QRespondentPatch(name = name, notes = notes))

/** Recording ids of a session that carry history worth reading. */
fun historyTakes(detail: QSessionDetail?): List<QRecording> = detail?.recordings.orEmpty()

/** One guest answer as its own outbox step body. */
fun guestAnswers(item: QGuestAnswerItem): QGuestAnswersPut = QGuestAnswersPut(listOf(item))
