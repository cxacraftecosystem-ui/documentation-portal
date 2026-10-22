package com.fieldrepository.app.forms

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.fieldrepository.app.data.ConnectivityObserver
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.questionnaire.data.QWorkshopOption
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.ui.PromptButton
import com.fieldrepository.app.questionnaire.ui.PromptButtons
import com.fieldrepository.app.questionnaire.ui.QuestionnairePrompt
import com.fieldrepository.app.questionnaire.ui.serverRow
import com.fieldrepository.app.ui.AppToggle
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.SearchableMultiSelectField
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/** What the form detail offers this viewer (UNIFY 4.2, owner decision 5 and amendment of 22 Oct). */
data class FormActions(
    val edit: Boolean,
    val delete: Boolean,
    val restore: Boolean,
    val share: Boolean,
    val setDefault: Boolean,
    val bind: Boolean,
    val typeDefault: Boolean,
    val reuse: Boolean,
    val reupload: Boolean,
    val corpus: Boolean
) {
    companion object {
        fun of(user: UserDto?, form: QForm, local: Boolean): FormActions {
            val change = FormRights.mayChange(user, form) || local
            val admin = FormRights.mayAdminAct(user) && !local && !form.deleted
            return FormActions(
                edit = change && !form.deleted,
                delete = FormRights.mayDelete(user, form) && !local && !form.deleted,
                restore = FormRights.mayRestore(user) && form.deleted,
                share = admin,
                setDefault = FormRights.maySetDefault(user) && !local && !form.deleted,
                bind = admin,
                typeDefault = admin,
                reuse = FormRights.mayCreate(user) && !form.deleted,
                reupload = change && !local && !form.deleted,
                corpus = FormRights.mayExportCorpus(user) && !local
            )
        }
    }
}

/** The builder's view of one form: the server's sections, then what this phone queued under them. */
fun mergedSections(server: List<QFormSection>, pending: List<QFormSection>): List<QFormSection> =
    server.filter { it.isActive }.sortedBy { it.sortOrder } + pending

/** A reword never edits in place once answered: the server supersedes. The client sends the new words only when they changed. */
fun questionPatch(before: QFormQuestion, prompt: String, helpText: String, required: Boolean): QQuestionPatch? {
    val p = prompt.trim().takeIf { it != before.prompt }
    val h = helpText.trim().takeIf { it != before.helpText.orEmpty() }
    val r = required.takeIf { it != before.isRequired }
    return if (p == null && h == null && r == null) null else QQuestionPatch(prompt = p, helpText = h, isRequired = r)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormDetailScreen(
    v2: FormsV2,
    user: UserDto?,
    formId: String,
    workshops: List<QWorkshopOption>,
    onBack: () -> Unit,
    onOpenForm: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onError: (String) -> Unit,
    /** D3, A9: start a session on this form (null while the server has sessions off). */
    onStartSession: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val local = FormQueue.isLocal(formId)
    var form by remember { mutableStateOf<QForm?>(null) }
    var pending by remember { mutableStateOf<List<QFormSection>>(emptyList()) }
    var bindings by remember { mutableStateOf<List<QBinding>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<com.fieldrepository.app.questionnaire.data.QSession>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingDetails by remember { mutableStateOf(false) }
    var addingSection by remember { mutableStateOf(false) }
    var addingQuestionTo by remember { mutableStateOf<QFormSection?>(null) }
    var editingQuestion by remember { mutableStateOf<QFormQuestion?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingBindings by remember { mutableStateOf(false) }
    val online = ConnectivityObserver.isOnline(context)

    LaunchedEffect(formId, refresh) {
        loading = true
        if (local) {
            form = v2.outbox.localForms().firstOrNull { it.id == formId }
            // It may have landed since: open the server's copy.
            if (form == null) v2.outbox.resolveFormId(formId)?.let { onOpenForm(it); return@LaunchedEffect }
        } else {
            val fetched = runCatching { v2.api.form(formId) }.getOrNull()
            if (fetched != null && user != null) v2.cache.putDetail(user.id, fetched)
            form = fetched ?: v2.cache.detail(user?.id, formId)
            pending = v2.outbox.pendingSectionsOf(formId)
            bindings = runCatching { v2.api.bindings(formId).bindings }.getOrDefault(bindings)
            sessions = runCatching { QuestionnaireV2.get(context).api.sessions(questionnaireId = formId).items }.getOrDefault(sessions)
        }
        loading = false
    }

    fun act(failure: String, block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onSuccess { refresh++ }.onFailure { onError(errorText(it, failure)) } }
    }

    val reupload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        act("Couldn't upload that workbook.") { v2.api.reuploadWorkbook(formId, workbookPart(context, uri)); message = FormsCopy.UPLOADED }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ " + FormsCopy.FORMS) }
        if (loading && form == null) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
        val f = form ?: return@Column
        val actions = FormActions.of(user, f, local)
        Text(f.title, style = MaterialTheme.typography.titleLarge)
        f.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body) }
        Text(
            (listOf(FormLibrary.counts(f)) + FormLibrary.badges(f) + listOfNotNull(FormsCopy.ON_PHONE.takeIf { local })).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted
        )
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body) }
        if (!actions.edit && !f.deleted) Text(FormsCopy.READ_ONLY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)

        // ── Actions: pills (more than two) ──
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (actions.edit && !local) Pill("Edit details") { editingDetails = true }
            if (!local) Pill(FormsCopy.WORKBOOK_EXPORT, enabled = online) {
                act("Couldn't download the workbook.") {
                    FormDownloads.save(context, v2.api.workbook(formId), XLSX_MIME, Naming.exportFilename(entity = "Questionnaire", what = "Export", ext = "xlsx", subject = f.title, portal = "FieldRepository"))
                    message = FormsCopy.SAVED_TO
                }
            }
            if (!local) Pill(FormsCopy.QUESTION_SET, enabled = online) {
                act("Couldn't download the questions.") {
                    FormDownloads.save(context, v2.api.questionSet(formId), XLSX_MIME, Naming.exportFilename(entity = "Questionnaire", what = "Questions", ext = "xlsx", subject = f.title, portal = "FieldRepository"))
                    message = FormsCopy.SAVED_TO
                }
            }
            if (actions.reupload) Pill(FormsCopy.REUPLOAD, enabled = online) { reupload.launch(arrayOf(XLSX_MIME, "application/octet-stream")) }
            if (actions.reuse) Pill(FormsCopy.REUSE) {
                scope.launch {
                    val copy = FormBundles.of(f).let { it.copy(form = it.form.copy(title = f.title + " (copy)")) }
                    val id = v2.outbox.queueBundle(copy, user?.id, bundleId = null)
                    OutboxSyncWorker.schedule(context)
                    if (ConnectivityObserver.isOnline(context)) runCatching { v2.sync() }
                    message = FormsCopy.REUSED
                    id?.let { v2.outbox.resolveFormId(it) ?: it }?.let(onOpenForm)
                }
            }
            if (onStartSession != null && !local && !f.deleted) Pill(FormsCopy.START_SESSION) { onStartSession(formId) }
            Pill(FormsCopy.SEND_TO_PHONE) { runCatching { shareBundle(context, f) }.onFailure { onError("Couldn't prepare the file.") } }
            if (actions.corpus) {
                Pill(FormsCopy.CORPUS_ANSWERS, enabled = online) {
                    act("Couldn't download the corpus.") {
                        FormDownloads.save(context, v2.api.corpusCsv(formId, "answers"), "text/csv"); message = FormsCopy.SAVED_TO
                    }
                }
                Pill(FormsCopy.CORPUS_RECORDINGS, enabled = online) {
                    act("Couldn't download the corpus.") {
                        FormDownloads.save(context, v2.api.corpusCsv(formId, "recordings"), "text/csv"); message = FormsCopy.SAVED_TO
                    }
                }
            }
            if (actions.restore) Pill(FormsCopy.RESTORE, enabled = online) {
                act("Couldn't restore the form.") {
                    runCatching { v2.api.restoreForm(formId) }.getOrElse { v2.api.patchForm(formId, buildJsonObject { put("isActive", JsonPrimitive(true)) }) }
                    message = FormsCopy.RESTORED
                }
            }
            if (actions.delete) Pill(FormsCopy.DELETE, enabled = online) { confirmDelete = true }
        }
        if (!online) Text(FormsCopy.NEEDS_CONNECTION + " for workbooks, sharing and deletes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)

        // ── Kind (author or content admin) ──
        if (actions.edit && !local) SearchableSelectField(
            label = FormsCopy.FORM_KIND, options = FormKinds.LABELS.map { (k, v) -> SelectOption(k, v) },
            selectedValue = f.kind.orEmpty(), placeholder = FormKinds.NOT_STATED, includeNone = true, enabled = online
        ) { kind -> act("Couldn't change the kind.") { v2.api.patchForm(formId, buildJsonObject { put("kind", if (kind.isBlank()) kotlinx.serialization.json.JsonNull else JsonPrimitive(kind)) }) } }

        // ── Admin authoring (owner decision 5: served on Field Repository Android) ──
        if (actions.share) AppToggle(
            label = FormsCopy.SHARE_ALL, description = FormsCopy.SHARE_ALL_BODY, checked = f.isShared, enabled = online,
            onCheckedChange = { on -> act("Couldn't change sharing.") { v2.api.patchForm(formId, buildJsonObject { put("isShared", JsonPrimitive(on)) }) } }
        )
        if (actions.setDefault) AppToggle(
            label = FormsCopy.SET_DEFAULT, description = FormsCopy.SET_DEFAULT_BODY, checked = f.isDefault, enabled = online,
            onCheckedChange = { on -> act("Couldn't change the default.") { v2.api.setDefault(formId, QDefaultPut(on)) } }
        )
        if (actions.typeDefault) SearchableSelectField(
            label = FormsCopy.TYPE_DEFAULT,
            options = listOf(SelectOption("DESIGN_PROTOTYPE", "Design prototype workshops"), SelectOption("OTHER", "Other workshops")),
            selectedValue = "", placeholder = "Choose a workshop type", includeNone = false, enabled = online
        ) { type -> act("Couldn't set the type's default.") { v2.api.putTypeDefault(QTypeDefaultPut(formId, type)); message = "Default for that workshop type." } }
        if (actions.bind || bindings.isNotEmpty()) {
            Text(FormsCopy.BINDINGS, style = MaterialTheme.typography.titleMedium)
            Text(FormsCopy.BINDINGS_BODY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
            bindings.forEach { b ->
                val title = workshops.firstOrNull { it.id == b.workshopId }?.title ?: b.title ?: b.workshopId.orEmpty()
                Text(title + if (b.isPrimary) " · " + FormsCopy.PRIMARY else "", style = MaterialTheme.typography.bodyMedium)
            }
            if (actions.bind) PromptButtons(listOf(PromptButton(FormsCopy.BINDINGS, primary = false) { editingBindings = true }))
        }

        // ── Sections and questions (the builder) ──
        Text(FormsCopy.SECTIONS, style = MaterialTheme.typography.titleMedium)
        mergedSections(f.sections, pending).forEach { section ->
            SectionBlock(
                section = section, canEdit = actions.edit, local = FormQueue.isLocal(section.id),
                onAddQuestion = { addingQuestionTo = section },
                onEditQuestion = { editingQuestion = it },
                onDeleteSection = { act("Couldn't remove the section.") { v2.api.deleteSection(section.id).let { if (!it.isSuccessful) throw retrofit2.HttpException(it) } } },
                onDeleteQuestion = { q -> act("Couldn't remove the question.") { v2.api.deleteQuestion(q.id).let { if (!it.isSuccessful) throw retrofit2.HttpException(it) } } }
            )
        }
        if (actions.edit) PromptButtons(listOf(PromptButton(FormsCopy.ADD_SECTION, primary = true) { addingSection = true }))

        // ── Sessions on this form ──
        if (sessions.isNotEmpty()) {
            Text(FormsCopy.SESSIONS_ON_FORM, style = MaterialTheme.typography.titleMedium)
            sessions.forEach { s ->
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpenSession(s.id) }.padding(vertical = 6.dp)) {
                    Text(s.title, style = MaterialTheme.typography.bodyMedium)
                    Text(serverRow(s).meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                }
                HorizontalDivider(color = MaterialTheme.field.hairline)
            }
        }
    }

    val f = form
    if (editingDetails && f != null) FormDetailsDialog(f, onDismiss = { editingDetails = false }) { title, description ->
        editingDetails = false
        act("Couldn't save the form.") {
            v2.api.patchForm(formId, buildJsonObject {
                if (title != f.title) put("title", JsonPrimitive(title))
                if (description != f.description.orEmpty()) put("description", JsonPrimitive(description))
            })
        }
    }
    if (addingSection) SectionDialog(onDismiss = { addingSection = false }) { code, title ->
        addingSection = false
        scope.launch {
            val sent = !local && ConnectivityObserver.isOnline(context) &&
                runCatching { v2.api.createSection(java.util.UUID.randomUUID().toString(), QSectionCreate(formId, code, title)) }.isSuccess
            if (!sent) { v2.outbox.queueSection(formId, code, title, null); OutboxSyncWorker.schedule(context) }
            refresh++
        }
    }
    addingQuestionTo?.let { section ->
        QuestionDialog(null, onDismiss = { addingQuestionTo = null }) { prompt, help, required ->
            addingQuestionTo = null
            scope.launch {
                val sent = !FormQueue.isLocal(section.id) && ConnectivityObserver.isOnline(context) &&
                    runCatching { v2.api.createQuestion(java.util.UUID.randomUUID().toString(), QQuestionCreate(section.id, prompt, help.ifBlank { null }, required)) }.isSuccess
                if (!sent) { v2.outbox.queueQuestion(section.id, prompt, help, required, null); OutboxSyncWorker.schedule(context) }
                refresh++
            }
        }
    }
    editingQuestion?.let { q ->
        QuestionDialog(q, onDismiss = { editingQuestion = null }) { prompt, help, required ->
            editingQuestion = null
            val patch = questionPatch(q, prompt, help, required) ?: return@QuestionDialog
            act("Couldn't save the question.") { v2.api.patchQuestion(q.id, patch) }
        }
    }
    if (confirmDelete) DeleteFormPrompt(onCancel = { confirmDelete = false }) {
        confirmDelete = false
        scope.launch {
            runCatching { v2.api.deleteForm(formId).let { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }
                .onSuccess { onBack() }.onFailure { onError(errorText(it, "Couldn't delete the form.")) }
        }
    }
    if (editingBindings) BindingsDialog(workshops, bindings, onDismiss = { editingBindings = false }) { next ->
        editingBindings = false
        act("Couldn't save the workshops.") { bindings = v2.api.putBindings(formId, FormBindings.body(next)).bindings }
    }
}

@Composable
private fun Pill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

@Composable
fun SectionBlock(
    section: QFormSection,
    canEdit: Boolean,
    local: Boolean,
    onAddQuestion: () -> Unit,
    onEditQuestion: (QFormQuestion) -> Unit,
    onDeleteSection: () -> Unit,
    onDeleteQuestion: (QFormQuestion) -> Unit
) {
    var open by remember(section.id) { mutableStateOf(false) }
    Surface(color = MaterialTheme.field.surface100, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${section.code} · ${section.title}", style = MaterialTheme.typography.bodyLarge)
                    val n = section.questions.count { it.isActive && it.retiredAt == null }
                    Text("$n ${if (n == 1) "question" else "questions"}" + if (local) " · " + FormsCopy.ON_PHONE else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                }
                Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelLarge)
            }
            if (open) {
                section.questions.filter { it.isActive && it.retiredAt == null && it.supersededById == null }.sortedBy { it.sortOrder }.forEach { q ->
                    Column(Modifier.fillMaxWidth().testTag("question-${q.id}")) {
                        Text((q.displayCode?.let { "$it  " } ?: "") + q.prompt + if (q.isRequired) " *" else "", style = MaterialTheme.typography.bodyMedium)
                        q.helpText?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
                        if (canEdit && !FormQueue.isLocal(q.id)) PromptButtons(listOf(
                            PromptButton("Edit") { onEditQuestion(q) },
                            PromptButton("Remove", destructive = true) { onDeleteQuestion(q) }
                        ))
                    }
                }
                if (canEdit) PromptButtons(listOfNotNull(
                    PromptButton(FormsCopy.ADD_QUESTION, primary = true, onClick = onAddQuestion),
                    PromptButton("Remove section", destructive = true, onClick = onDeleteSection).takeIf { !local }
                ))
            }
        }
    }
}

/** P16: "Delete this form?" Admins can restore it from Deleted. Cancel · Delete. */
@Composable
fun DeleteFormPrompt(onCancel: () -> Unit, onDelete: () -> Unit) {
    QuestionnairePrompt(
        title = FormsCopy.P16_TITLE, body = FormsCopy.P16_BODY,
        buttons = listOf(PromptButton(FormsCopy.CANCEL, onClick = onCancel), PromptButton(FormsCopy.DELETE, primary = true, destructive = true, onClick = onDelete)),
        onDismiss = onCancel
    )
}

@Composable
fun FormDetailsDialog(form: QForm, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(form.title) }
    var description by remember { mutableStateOf(form.description.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit details", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(220) }, label = { Text(FormsCopy.FORM_TITLE) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(2000) }, label = { Text(FormsCopy.FORM_DESCRIPTION) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { PromptButtons(listOf(PromptButton(FormsCopy.CANCEL, onClick = onDismiss), PromptButton(FormsCopy.SAVE, primary = true) { if (title.isNotBlank()) onSave(title.trim(), description.trim()) })) }
    )
}

@Composable
fun SectionDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FormsCopy.ADD_SECTION, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // A section code is one to three letters (media names are built from it), never prose.
                OutlinedTextField(code, { v -> code = v.filter { it.isLetter() }.take(3).uppercase() }, label = { Text(FormsCopy.SECTION_CODE) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(title, { title = it.take(220) }, label = { Text(FormsCopy.SECTION_TITLE) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { PromptButtons(listOf(PromptButton(FormsCopy.CANCEL, onClick = onDismiss), PromptButton(FormsCopy.SAVE, primary = true) { if (code.isNotBlank() && title.isNotBlank()) onSave(code, title.trim()) })) }
    )
}

@Composable
fun QuestionDialog(question: QFormQuestion?, onDismiss: () -> Unit, onSave: (String, String, Boolean) -> Unit) {
    var prompt by remember { mutableStateOf(question?.prompt.orEmpty()) }
    var help by remember { mutableStateOf(question?.helpText.orEmpty()) }
    var required by remember { mutableStateOf(question?.isRequired ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (question == null) FormsCopy.ADD_QUESTION else "Edit question", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(prompt, { prompt = it.take(4000) }, label = { Text(FormsCopy.QUESTION_PROMPT) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(help, { help = it.take(2000) }, label = { Text(FormsCopy.QUESTION_HELP) }, modifier = Modifier.fillMaxWidth())
                AppToggle(label = FormsCopy.REQUIRED, checked = required, onCheckedChange = { required = it })
            }
        },
        confirmButton = { PromptButtons(listOf(PromptButton(FormsCopy.CANCEL, onClick = onDismiss), PromptButton(FormsCopy.SAVE, primary = true) { if (prompt.isNotBlank()) onSave(prompt.trim(), help.trim(), required) })) }
    )
}

/** Bindings (A9): the workshops this form is offered in, each with an optional Primary. */
@Composable
fun BindingsDialog(workshops: List<QWorkshopOption>, current: List<QBinding>, onDismiss: () -> Unit, onSave: (List<QBinding>) -> Unit) {
    var next by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FormsCopy.BINDINGS, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(FormsCopy.BINDINGS_BODY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                SearchableMultiSelectField(
                    label = FormsCopy.BINDINGS,
                    options = workshops.map { SelectOption(it.id, it.title.ifBlank { it.id }) },
                    selected = next.mapNotNull { it.workshopId }.toSet()
                ) { ids ->
                    next = ids.map { id -> next.firstOrNull { it.workshopId == id } ?: QBinding(workshopId = id) }
                }
                next.forEach { b ->
                    val title = workshops.firstOrNull { it.id == b.workshopId }?.title ?: b.workshopId.orEmpty()
                    AppToggle(label = "${FormsCopy.PRIMARY}: $title", checked = b.isPrimary, onCheckedChange = { on ->
                        next = FormBindings.set(next, b.workshopId!!, bound = true, primary = on)
                    })
                }
            }
        },
        confirmButton = { PromptButtons(listOf(PromptButton(FormsCopy.CANCEL, onClick = onDismiss), PromptButton(FormsCopy.SAVE, primary = true) { onSave(next) })) }
    )
}

/** "Send to a phone": the `.frwq` through the share sheet (Quick Share, Bluetooth), no connection needed. */
fun shareBundle(context: Context, form: QForm) {
    val bundle = FormBundles.of(form)
    val dir = File(context.cacheDir, "handoff").apply { mkdirs() }
    val file = File(dir, FormBundles.fileName(form.title))
    file.writeBytes(FormBundles.encode(bundle))
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).setType(FormBundles.MIME).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, FormsCopy.SEND_TO_PHONE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
