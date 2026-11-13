package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.questionnaire.data.QContinueRequest
import com.fieldrepository.app.questionnaire.data.QProposal
import com.fieldrepository.app.questionnaire.data.QSession
import com.fieldrepository.app.questionnaire.data.QSessionCreate
import com.fieldrepository.app.questionnaire.data.QSessionDetail
import com.fieldrepository.app.questionnaire.data.QSessionLocation
import com.fieldrepository.app.questionnaire.data.QSessionPatch
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.data.SessionOutbox
import com.fieldrepository.app.questionnaire.data.SessionPurpose
import com.fieldrepository.app.ui.AppToggle
import com.fieldrepository.app.ui.CalendarField
import com.fieldrepository.app.ui.SearchableMultiSelectField
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.questionnaire.ui.SegmentedChoice
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SessionEdit(
    val date: LocalDate?,
    val purpose: String,
    val place: String,
    val languageCode: String,
    val languageOther: String,
    val note: String,
    val location: QSessionLocation? = null,
    val crafts: List<String> = emptyList(),
    val craftsByHand: List<String> = emptyList()
) {
    fun patch(version: Int, before: SessionEdit): QSessionPatch = QSessionPatch(
        version = version,
        sessionDate = date?.takeIf { it != before.date }?.toString(),
        purpose = purpose.takeIf { it != before.purpose },
        place = place.trim().takeIf { it != before.place },
        languageCode = languageCode.takeIf { it != before.languageCode }?.ifBlank { null },
        languageOther = languageOther.trim().takeIf { languageCode == "other" && it != before.languageOther },
        note = note.trim().take(80).takeIf { it != before.note },
        location = location?.takeIf { it != before.location },
        clearLocation = true.takeIf { location == null && before.location != null },
        crafts = crafts.takeIf { it != before.crafts },
        craftsByHand = crafts.filter { it in before.craftsByHand || it !in before.crafts }.takeIf { crafts != before.crafts }
    )

    fun applyTo(create: QSessionCreate): QSessionCreate = create.copy(
        sessionDate = (date ?: runCatching { LocalDate.parse(create.sessionDate) }.getOrNull())?.toString() ?: create.sessionDate,
        purpose = purpose,
        place = place.trim().ifBlank { null },
        languageCode = languageCode.ifBlank { null },
        languageOther = languageOther.trim().ifBlank { null }?.takeIf { languageCode == "other" },
        note = note.trim().take(80).ifBlank { null },
        location = location,
        crafts = crafts,
        craftsByHand = craftsByHand
    )

    companion object {
        fun of(
            date: String?, purpose: String?, place: String?, languageCode: String?, languageOther: String?, note: String?,
            location: QSessionLocation? = null, crafts: List<String> = emptyList(), craftsByHand: List<String> = emptyList()
        ) = SessionEdit(
            date = date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
            purpose = purpose ?: SessionPurpose.FIRST_SITTING.wire,
            place = place.orEmpty(), languageCode = languageCode.orEmpty(), languageOther = languageOther.orEmpty(), note = note.orEmpty(),
            location = location, crafts = crafts, craftsByHand = craftsByHand
        )
    }
}

enum class SessionEditField(val labelKey: String) {
    DATE("start.date"), PURPOSE("start.purpose"), PLACE("start.place"), LOCATION("start.place"),
    LANGUAGE("start.language"), NOTE("start.note"), CRAFTS("start.crafts")
}

data class SessionFieldDiff(val field: SessionEditField, val mine: String, val theirs: String)

sealed interface SessionEditResult {
    data object Saved : SessionEditResult
    data class Conflict(val fields: List<SessionFieldDiff>) : SessionEditResult
}

sealed interface StaleOutcome {
    data class Retry(val patch: QSessionPatch?) : StaleOutcome
    data class Conflict(val fields: List<SessionFieldDiff>) : StaleOutcome
}

object SessionStaleRetry {
    fun changed(a: SessionEdit, b: SessionEdit): Set<SessionEditField> = SessionEditField.entries.filter { a.valueOf(it) != b.valueOf(it) }.toSet()

    fun merged(server: SessionEdit, mine: SessionEdit, edited: Set<SessionEditField>): SessionEdit = server.copy(
        date = if (SessionEditField.DATE in edited) mine.date else server.date,
        purpose = if (SessionEditField.PURPOSE in edited) mine.purpose else server.purpose,
        place = if (SessionEditField.PLACE in edited) mine.place else server.place,
        location = if (SessionEditField.LOCATION in edited) mine.location else server.location,
        languageCode = if (SessionEditField.LANGUAGE in edited) mine.languageCode else server.languageCode,
        languageOther = if (SessionEditField.LANGUAGE in edited) mine.languageOther else server.languageOther,
        note = if (SessionEditField.NOTE in edited) mine.note else server.note,
        crafts = if (SessionEditField.CRAFTS in edited) mine.crafts else server.crafts
    )

    fun resolve(initial: SessionEdit, mine: SessionEdit, server: SessionEdit, serverVersion: Int): StaleOutcome {
        val edited = changed(mine, initial)
        val moved = changed(server, initial)
        val clashes = (edited intersect moved).filter { mine.valueOf(it) != server.valueOf(it) }
        if (clashes.isNotEmpty()) return StaleOutcome.Conflict(diff(clashes, mine, server))
        val next = merged(server, mine, edited)
        return StaleOutcome.Retry(if (next == server) null else next.patch(serverVersion, server))
    }

    fun diff(fields: Collection<SessionEditField>, mine: SessionEdit, server: SessionEdit): List<SessionFieldDiff> =
        SessionEditField.entries.filter { it in fields }.map { SessionFieldDiff(it, mine.valueOf(it), server.valueOf(it)) }

    suspend fun save(
        version: Int,
        initial: SessionEdit,
        mine: SessionEdit,
        patch: suspend (QSessionPatch) -> Unit,
        fetch: suspend () -> Pair<Int, SessionEdit>,
        isStale: (Throwable) -> Boolean
    ): SessionEditResult {
        try {
            patch(mine.patch(version, initial))
            return SessionEditResult.Saved
        } catch (first: Exception) {
            if (first is kotlinx.coroutines.CancellationException || !isStale(first)) throw first
        }
        val (serverVersion, server) = fetch()
        return when (val outcome = resolve(initial, mine, server, serverVersion)) {
            is StaleOutcome.Conflict -> SessionEditResult.Conflict(outcome.fields)
            is StaleOutcome.Retry -> {
                val body = outcome.patch ?: return SessionEditResult.Saved
                try {
                    patch(body)
                    SessionEditResult.Saved
                } catch (second: Exception) {
                    if (second is kotlinx.coroutines.CancellationException || !isStale(second)) throw second
                    val latest = runCatching { fetch().second }.getOrDefault(server)
                    val fields = changed(mine, initial).ifEmpty { SessionEditField.entries.toSet() }
                    SessionEditResult.Conflict(diff(fields, mine, latest).filter { it.mine != it.theirs })
                }
            }
        }
    }
}

fun SessionEdit.valueOf(field: SessionEditField): String = when (field) {
    SessionEditField.DATE -> date?.toString().orEmpty()
    SessionEditField.PURPOSE -> QuestionnaireCopy.purposeLabel(purpose)
    SessionEditField.PLACE -> place.trim()
    SessionEditField.LOCATION -> location?.let { it.placeName ?: it.address ?: "${it.latitude}, ${it.longitude}" }.orEmpty()
    SessionEditField.LANGUAGE -> if (languageCode == "other") languageOther.trim() else languageCode
    SessionEditField.NOTE -> note.trim()
    SessionEditField.CRAFTS -> crafts.joinToString(", ")
}

fun Throwable.isStaleVersion(): Boolean {
    val http = this as? retrofit2.HttpException ?: return false
    if (http.code() != 409) return false
    val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull().orEmpty()
    return runCatching {
        com.fieldrepository.app.questionnaire.portal.QuestionnairePortal.json.decodeFromString(com.fieldrepository.app.questionnaire.data.QErrorBody.serializer(), body).detail?.code
    }.getOrNull() == com.fieldrepository.app.questionnaire.data.QErrorCodes.STALE_VERSION
}

fun QSessionDetail.asEdit(): SessionEdit = SessionEdit.of(sessionDate, purpose, place, languageCode, languageOther, note, location, crafts, craftsByHand)

@Composable
fun SessionConflictPrompt(fields: List<SessionFieldDiff>, onDismiss: () -> Unit) {
    QuestionnairePromptContent(
        title = QuestionnaireCopy.s("session.conflictTitle"),
        onDismiss = onDismiss,
        content = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(QuestionnaireCopy.s("session.conflictBody"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
                fields.forEach { f ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(QuestionnaireCopy.s(f.field.labelKey), style = MaterialTheme.typography.labelLarge)
                        Text("${QuestionnaireCopy.s("tray.mine")}: ${f.mine.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body)
                        Text("${QuestionnaireCopy.s("tray.theirs")}: ${f.theirs.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                    }
                }
            }
        },
        buttons = listOf(PromptButton(QuestionnaireCopy.s("ok"), primary = true, onClick = onDismiss))
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditSessionDialog(initial: SessionEdit, onDismiss: () -> Unit, onSave: (SessionEdit) -> Unit) {
    var form by remember { mutableStateOf(initial) }
    QuestionnairePromptContent(
        title = QuestionnaireCopy.s("session.edit"),
        onDismiss = onDismiss,
        content = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CalendarField(label = QuestionnaireCopy.s("start.date"), value = form.date, onValueChange = { form = form.copy(date = it) }, clearable = false)
                Text(QuestionnaireCopy.s("start.purpose"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionPurpose.entries.map { it.wire }.forEach { p ->
                        FilterChip(selected = form.purpose == p, onClick = { form = form.copy(purpose = p) }, label = { Text(QuestionnaireCopy.purposeLabel(p)) })
                    }
                }
                SessionPlaceField(
                    value = SessionWhere(form.place, form.location),
                    label = QuestionnaireCopy.s("start.place"),
                    onChange = { where -> form = form.copy(place = where.place, location = where.location) },
                    editing = true
                )
                SearchableMultiSelectField(
                    label = QuestionnaireCopy.s("start.crafts"),
                    options = form.crafts.map { SelectOption(it, it) },
                    selected = form.crafts.toSet()
                ) { chosen -> form = form.copy(crafts = form.crafts.filter { it in chosen }) }
                SearchableSelectField(
                    label = QuestionnaireCopy.s("start.language"), options = SESSION_LANGUAGES, selectedValue = form.languageCode, includeNone = false, searchable = true
                ) { form = form.copy(languageCode = it) }
                if (form.languageCode == "other") OutlinedTextField(
                    value = form.languageOther, onValueChange = { form = form.copy(languageOther = it.take(40)) },
                    label = { Text(QuestionnaireCopy.s("start.languageOther")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.note, onValueChange = { form = form.copy(note = it.take(80)) },
                    label = { Text(QuestionnaireCopy.s("start.note")) }, supportingText = { Text("${form.note.length}/80") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("session.cancel"), onClick = onDismiss),
            PromptButton(QuestionnaireCopy.s("save"), primary = true) { onSave(form) }
        )
    )
}

@Composable
fun MergeSessionDialog(v2: QuestionnaireV2, sessionId: String, questionnaireId: String, onDismiss: () -> Unit, onMerge: (QSession) -> Unit) {
    var options by remember { mutableStateOf<List<QSession>?>(null) }
    var target by remember { mutableStateOf("") }
    LaunchedEffect(sessionId) {
        options = runCatching { v2.everySession(questionnaireId).filter { it.id != sessionId } }.getOrDefault(emptyList())
    }
    QuestionnairePromptContent(
        title = QuestionnaireCopy.s("session.merge"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(QuestionnaireCopy.s("session.mergeBody"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
                val list = options
                if (list != null && list.isEmpty()) Text(QuestionnaireCopy.s("session.noOtherSessions"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                if (!list.isNullOrEmpty()) SearchableSelectField(
                    label = QuestionnaireCopy.s("inbox.fileSession"),
                    options = list.map { SelectOption(it.id, it.title.ifBlank { it.id }, it.sessionDate?.take(10)) },
                    selectedValue = target, includeNone = false, searchable = true
                ) { target = it }
            }
        },
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("session.cancel"), onClick = onDismiss),
            PromptButton(QuestionnaireCopy.s("p5.merge"), primary = true, destructive = true) { options?.firstOrNull { it.id == target }?.let(onMerge) }
        )
    )
}

@Composable
fun ContinueWithDialog(
    v2: QuestionnaireV2,
    title: String,
    fromSessionId: String,
    people: List<Pair<String, String>>,
    preselected: Set<String>,
    onDismiss: () -> Unit,
    onOpened: (QSession) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var ids by remember { mutableStateOf(preselected) }
    var split by remember { mutableStateOf("ONE") }
    var date by remember { mutableStateOf<LocalDate?>(LocalDate.now(Naming.IST)) }
    var busy by remember { mutableStateOf(false) }
    val key = remember { SessionOutbox.newKey() }
    QuestionnairePromptContent(
        title = title,
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchableMultiSelectField(
                    label = QuestionnaireCopy.s("start.artisans"),
                    options = people.map { (id, name) -> SelectOption(id, name) },
                    selected = ids
                ) { ids = it }
                if (ids.size > 1) SegmentedChoice(
                    label = "",
                    options = listOf(SelectOption("ONE", QuestionnaireCopy.s("continue.one")), SelectOption("EACH", QuestionnaireCopy.s("continue.each"))),
                    selectedValue = split
                ) { split = it }
                CalendarField(label = QuestionnaireCopy.s("start.date"), value = date, onValueChange = { date = it }, clearable = false)
            }
        },
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("session.cancel"), onClick = onDismiss),
            PromptButton(QuestionnaireCopy.s("start.submit"), primary = true) {
                val day = date ?: return@PromptButton
                if (ids.isEmpty() || busy) return@PromptButton
                busy = true
                scope.launch {
                    runCatching {
                        v2.api.continueSessions(key, QContinueRequest.of(fromSessionId, ids.toList(), split, day.toString(), clientKey = key))
                    }.onSuccess { out ->
                        out.sessions.firstOrNull()?.let(onOpened) ?: onDismiss()
                    }.onFailure { onError(it.userSentence()) }
                    busy = false
                }
            }
        )
    )
}

@Composable
fun ProposalsDialog(
    proposals: List<QProposal>,
    roster: List<HeaderParticipant>,
    codeOf: (String) -> String,
    onDismiss: () -> Unit,
    onAccept: (QProposal, Set<String>) -> Unit
) {
    var accepted by remember { mutableStateOf(emptySet<String>()) }
    var whose by remember { mutableStateOf(answerForOptions(roster).firstOrNull()?.value.orEmpty()) }
    QuestionnairePromptContent(
        title = QuestionnaireCopy.s("take.propose"),
        onDismiss = onDismiss,
        content = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (proposals.isEmpty()) Text(QuestionnaireCopy.s("take.noProposals"), style = MaterialTheme.typography.bodyMedium)
                if (proposals.any { it.artisanIds == null } && roster.size > 1) SearchableSelectField(
                    label = QuestionnaireCopy.s("take.whose"), options = answerForOptions(roster), selectedValue = whose, includeNone = false
                ) { whose = it }
                proposals.filter { it.questionLineageId !in accepted }.forEach { p ->
                    Text(codeOf(p.questionLineageId), style = MaterialTheme.typography.labelLarge)
                    Text(p.text, style = MaterialTheme.typography.bodySmall)
                    p.existing.firstOrNull()?.text?.takeIf { it.isNotBlank() }?.let {
                        Text(QuestionnaireCopy.s("p8.keepCurrent") + ": " + it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted)
                    }
                    PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("take.accept"), primary = true) {
                        val ids = p.artisanIds?.toSet() ?: answerForIds(roster, whose)
                        accepted = accepted + p.questionLineageId
                        onAccept(p, ids)
                    }))
                }
            }
        },
        buttons = listOf(PromptButton(QuestionnaireCopy.s("p5.ok"), primary = true, onClick = onDismiss))
    )
}

data class NewArtisan(
    val name: String = "",
    val place: String = "",
    val aadhaar: String = "",
    val aadhaarNotShared: Boolean = false,
    val dos: String = "",
    val donts: String = ""
) {
    val aadhaarDigits: String get() = aadhaar.filter(Char::isDigit)
    val valid: Boolean get() = name.isNotBlank() && place.isNotBlank() && dos.isNotBlank() && donts.isNotBlank() &&
        (aadhaarNotShared || aadhaarDigits.length == 12)
}

@Composable
fun RegisterArtisanDialog(onDismiss: () -> Unit, onRegister: (NewArtisan) -> Unit) {
    var form by remember { mutableStateOf(NewArtisan()) }
    QuestionnairePromptContent(
        title = QuestionnaireCopy.s("artisan.register"),
        onDismiss = onDismiss,
        content = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = form.name, onValueChange = { form = form.copy(name = it) }, label = { Text(QuestionnaireCopy.s("artisan.name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = form.place, onValueChange = { form = form.copy(place = it) }, label = { Text(QuestionnaireCopy.s("artisan.place")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppToggle(label = QuestionnaireCopy.s("artisan.aadhaarNotShared"), checked = form.aadhaarNotShared, onCheckedChange = { form = form.copy(aadhaarNotShared = it) })
                if (!form.aadhaarNotShared) OutlinedTextField(
                    value = form.aadhaar, onValueChange = { form = form.copy(aadhaar = it.filter { c -> c.isDigit() || c == ' ' }.take(14)) },
                    label = { Text(QuestionnaireCopy.s("artisan.aadhaar")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(value = form.dos, onValueChange = { form = form.copy(dos = it) }, label = { Text(QuestionnaireCopy.s("artisan.dos")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = form.donts, onValueChange = { form = form.copy(donts = it) }, label = { Text(QuestionnaireCopy.s("artisan.donts")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("session.cancel"), onClick = onDismiss),
            PromptButton(QuestionnaireCopy.s("artisan.register"), primary = true) { if (form.valid) onRegister(form) }
        )
    )
}
