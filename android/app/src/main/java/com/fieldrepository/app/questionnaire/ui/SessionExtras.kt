package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.questionnaire.data.QArtisanOption
import com.fieldrepository.app.questionnaire.data.QDirectoryUser
import com.fieldrepository.app.questionnaire.data.QRecordingEvent
import com.fieldrepository.app.questionnaire.data.QResearcherRef
import com.fieldrepository.app.questionnaire.data.QRevision
import com.fieldrepository.app.questionnaire.data.QSession
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.data.ResearcherRole
import com.fieldrepository.app.questionnaire.data.SessionOutbox
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * The session actions the designer portal adds to Field Repository's client (UNIFY §1): Add
 * researcher (B6), an answer's History (B21), a take's History (B40), registering a guest as an
 * artisan (U6), and the content admins' Deleted list with Restore (A14).
 */

/** "7 Jul 10:42" for an instant, in IST. */
internal fun dayAndTime(raw: String?): String? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let {
    "${QuestionnaireCopy.day(it.atZone(Naming.IST).toLocalDate())} ${QuestionnaireCopy.clock(it)}"
}

/** One revision as History lists it: "Nandini · 7 Jul 10:42 · Typed". */
internal fun revisionLine(r: QRevision): String = listOfNotNull(
    r.actor?.name?.ifBlank { null }?.let(QuestionnaireCopy::firstName),
    dayAndTime(r.createdAt),
    r.origin?.let(::originLabel),
    QuestionnaireCopy.s("answer.current").takeIf { r.current }
).joinToString(" · ")

/** The answer origins in the words the record uses. */
internal fun originLabel(origin: String): String = when (origin) {
    "TYPED" -> QuestionnaireCopy.s("mark.typed")
    "TRANSCRIPT", "MACHINE_SUGGESTED", "MACHINE_SUGGESTED_EDITED" -> QuestionnaireCopy.s("record.fromRecording")
    "PREFILL" -> "Prefilled"
    "WORKBOOK" -> "Workbook"
    else -> origin.lowercase().replaceFirstChar { it.uppercase() }
}

/** One event of a take's history: "Moved · Nandini · 7 Jul 10:42". */
internal fun eventLine(e: QRecordingEvent): String = listOfNotNull(
    e.kind.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
    e.actor?.name?.ifBlank { null }?.let(QuestionnaireCopy::firstName),
    dayAndTime(e.at)
).joinToString(" · ")

private fun JsonElement?.shortText(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content.ifBlank { null }
    else -> toString().take(120)
}

/** B21: every revision of one answer, newest first, with who wrote it. Online. */
@Composable
fun AnswerHistoryDialog(v2: QuestionnaireV2, answerId: String, heading: String, onDismiss: () -> Unit) {
    var revisions by remember { mutableStateOf<List<QRevision>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(answerId, attempt) {
        failed = false
        runCatching { v2.api.answerRevisions(answerId).revisions }.onSuccess { revisions = it }.onFailure { failed = true }
    }
    QuestionnairePromptContent(
        title = QuestionnaireCopy.s("answer.history") + " · " + heading,
        onDismiss = onDismiss,
        content = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = revisions
                when {
                    failed -> Text(QuestionnaireCopy.s("error.needsSignal"), style = MaterialTheme.typography.bodyMedium)
                    list == null -> FieldLinearProgressIndicator(Modifier.fillMaxWidth())
                    list.isEmpty() -> Text(QuestionnaireCopy.s("answer.noHistory"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
                    else -> list.forEach { r ->
                        Text(revisionLine(r), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.field.muted)
                        Text(r.text.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                        r.note?.ifBlank { null }?.let { Text(QuestionnaireCopy.s("answer.note") + ": " + it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body) }
                        HorizontalDivider(color = MaterialTheme.field.hairline)
                    }
                }
            }
        },
        buttons = listOfNotNull(
            if (failed) PromptButton(QuestionnaireCopy.s("record.tryAgain")) { attempt++ } else null,
            PromptButton(QuestionnaireCopy.s("p5.ok"), primary = true, onClick = onDismiss)
        )
    )
}

/** B40: a take's history, oldest first: moves, refilings, decisions, removals, restores. Online. */
@Composable
fun TakeHistoryDialog(v2: QuestionnaireV2, recordingId: String, onDismiss: () -> Unit) {
    var events by remember { mutableStateOf<List<QRecordingEvent>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(recordingId) {
        runCatching { v2.api.recordingEvents(recordingId).events }.onSuccess { events = it }.onFailure { failed = true }
    }
    QuestionnairePromptContent(
        title = QuestionnaireCopy.s("take.history"),
        onDismiss = onDismiss,
        content = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val list = events
                when {
                    failed -> Text(QuestionnaireCopy.s("error.needsSignal"), style = MaterialTheme.typography.bodyMedium)
                    list == null -> FieldLinearProgressIndicator(Modifier.fillMaxWidth())
                    list.isEmpty() -> Text(QuestionnaireCopy.s("take.noHistory"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
                    else -> list.forEach { e ->
                        Text(eventLine(e), style = MaterialTheme.typography.bodyMedium)
                        val change = listOfNotNull(e.before.shortText(), e.after.shortText()).joinToString(" → ")
                        if (change.isNotBlank()) Text(change, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                    }
                }
            }
        },
        buttons = listOf(PromptButton(QuestionnaireCopy.s("p5.ok"), primary = true, onClick = onDismiss))
    )
}

/**
 * B6: add a researcher to the session, a colleague (Researcher) or a volunteer, who may record into it
 * but never start one. Online: the server keeps the session's researchers.
 */
@Composable
fun AddResearcherDialog(v2: QuestionnaireV2, sessionId: String, already: Set<String>, onDismiss: () -> Unit, onAdded: (QSession) -> Unit, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<List<QDirectoryUser>?>(null) }
    var who by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ResearcherRole.CONTRIBUTOR.wire) }
    var busy by remember { mutableStateOf(false) }
    val key = remember { SessionOutbox.newKey() }
    LaunchedEffect(Unit) {
        people = runCatching { v2.api.userDirectory().filter { it.id !in already } }.getOrElse { onError(QuestionnaireCopy.s("error.needsSignal")); emptyList() }
    }
    QuestionnairePromptContent(
        title = QuestionnaireCopy.s("researcher.add"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (people == null) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
                SearchableSelectField(
                    label = QuestionnaireCopy.s("researcher.pick"),
                    options = people.orEmpty().map { SelectOption(it.id, it.name.ifBlank { it.email ?: it.id }, it.email) },
                    selectedValue = who, includeNone = false, searchable = true
                ) { who = it }
                SegmentedChoice(
                    label = QuestionnaireCopy.s("researcher.role"),
                    options = listOf(
                        SelectOption(ResearcherRole.CONTRIBUTOR.wire, QuestionnaireCopy.s("researcher.roleContributor")),
                        SelectOption(ResearcherRole.VOLUNTEER.wire, QuestionnaireCopy.s("researcher.roleVolunteer"))
                    ),
                    selectedValue = role
                ) { role = it }
            }
        },
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("session.cancel"), onClick = onDismiss),
            PromptButton(QuestionnaireCopy.s("researcher.add"), primary = true) {
                if (who.isBlank() || busy) return@PromptButton
                busy = true
                scope.launch {
                    runCatching { v2.api.addResearcher(key, sessionId, QResearcherRef(who, role)) }
                        .onSuccess(onAdded)
                        .onFailure { onError(it.userSentence()) }
                    busy = false
                }
            }
        )
    )
}

/** U6: a guest turns out to be a registered artisan; their answers and takes move to that record. */
@Composable
fun RegisterGuestDialog(name: String, artisans: List<QArtisanOption>, onDismiss: () -> Unit, onRegister: (artisanId: String) -> Unit) {
    var pick by remember { mutableStateOf("") }
    QuestionnairePromptContent(
        title = QuestionnaireCopy.s("guest.register"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                SearchableSelectField(
                    label = QuestionnaireCopy.s("guest.registerPick"),
                    options = artisans.filterNot { it.id.startsWith("local-") }.map { SelectOption(it.id, it.name, it.place.ifBlank { null }) },
                    selectedValue = pick, includeNone = false, searchable = true
                ) { pick = it }
            }
        },
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("session.cancel"), onClick = onDismiss),
            PromptButton(QuestionnaireCopy.s("guest.register"), primary = true) { if (pick.isNotBlank()) onRegister(pick) }
        )
    )
}

/**
 * A14: the content admins' Deleted sessions, newest first, each with Restore. A delete is soft, so a
 * restore brings back the session with its takes and answers.
 */
@Composable
fun DeletedSessionsList(v2: QuestionnaireV2, onRestored: (QSession) -> Unit, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<QSession>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        items = runCatching { v2.api.sessions(deleted = true, limit = 100).items }.getOrElse { onError(it.userSentence()); emptyList() }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val list = items
        if (list == null) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
        else if (list.isEmpty()) Text(QuestionnaireCopy.s("sessions.deletedEmpty"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
        list.orEmpty().forEach { s ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.title, style = MaterialTheme.typography.bodyLarge)
                Text(serverRow(s).meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body)
                PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("session.restore"), primary = true) {
                    if (busy != null) return@PromptButton
                    busy = s.id
                    scope.launch {
                        runCatching { v2.api.restoreSession(s.id) }
                            .onSuccess { onRestored(it); attempt++ }
                            .onFailure { onError(it.userSentence()) }
                        busy = null
                    }
                }))
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)
        }
    }
}
