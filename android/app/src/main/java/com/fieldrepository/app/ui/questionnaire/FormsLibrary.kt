package com.fieldrepository.app.ui.questionnaire

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.fieldrepository.app.data.ConnectivityObserver
import com.fieldrepository.app.data.FormBundle
import com.fieldrepository.app.data.FormBundleInbox
import com.fieldrepository.app.data.FormKinds
import com.fieldrepository.app.data.FormLibrary
import com.fieldrepository.app.data.FormRights
import com.fieldrepository.app.data.FormTab
import com.fieldrepository.app.data.FormsCopy
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.data.QForm
import com.fieldrepository.app.data.QFormCreate
import com.fieldrepository.app.data.QuestionnaireExport
import com.fieldrepository.app.data.QuestionnaireV2
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.WorkshopDetailDto
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SegmentedChoice
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** The library's action pills, in order, for this viewer (UNIFY 4.2). */
fun libraryActions(user: UserDto?): List<String> = listOfNotNull(
    FormsCopy.NEW_FORM.takeIf { FormRights.mayCreate(user) },
    FormsCopy.UPLOAD_WORKBOOK.takeIf { FormRights.mayCreate(user) },
    FormsCopy.DOWNLOAD_PRO_FORMA,
    FormsCopy.OPEN_BUNDLE.takeIf { FormRights.mayCreate(user) }
)

/** One row of the library. */
@Composable
fun FormRow(form: QForm, local: Boolean, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpen).padding(vertical = 8.dp).testTag("form-row-${form.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(form.title, style = MaterialTheme.typography.bodyLarge)
        val badges = FormLibrary.badges(form) + listOfNotNull(FormsCopy.ON_PHONE.takeIf { local })
        Text((listOf(FormLibrary.counts(form)) + badges).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body)
    }
    HorizontalDivider(color = MaterialTheme.field.hairline)
}

/** The library list itself: tabs, kind filter, rows. Pure state in, events out, so a device test draws it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormsLibraryList(
    forms: List<QForm>,
    localForms: List<QForm>,
    user: UserDto?,
    tab: FormTab,
    kind: String?,
    online: Boolean,
    onTab: (FormTab) -> Unit,
    onKind: (String?) -> Unit,
    onAction: (String) -> Unit,
    onOpen: (QForm) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SegmentedChoice(
            label = FormsCopy.FORMS,
            options = FormTab.visible(user).map { SelectOption(it.wire, it.label) },
            selectedValue = tab.wire
        ) { wire -> FormTab.entries.firstOrNull { it.wire == wire }?.let(onTab) }
        SearchableSelectField(
            label = FormsCopy.FORM_KIND,
            options = FormKinds.LABELS.map { (k, v) -> SelectOption(k, v) },
            selectedValue = kind.orEmpty(),
            placeholder = "All kinds",
            includeNone = true
        ) { onKind(it.ifBlank { null }) }
        // More than two actions: pills that wrap (the design bar).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            libraryActions(user).forEach { action ->
                val needsSignal = action == FormsCopy.UPLOAD_WORKBOOK || action == FormsCopy.DOWNLOAD_PRO_FORMA
                OutlinedButton(onClick = { onAction(action) }, enabled = online || !needsSignal, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
            }
        }
        if (!online) Text(FormsCopy.NEEDS_CONNECTION + " for workbooks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        val shown = FormLibrary.tab(forms, tab, user?.id, kind)
        val local = if (tab == FormTab.MINE) localForms.filter { kind == null || it.kind == kind } else emptyList()
        if (shown.isEmpty() && local.isEmpty()) Text(FormsCopy.NO_FORMS, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
        local.forEach { FormRow(it, local = true) { onOpen(it) } }
        shown.forEach { FormRow(it, local = false) { onOpen(it) } }
    }
}

/**
 * The Forms tab of the questionnaire home (UNIFY 4.2): the library, the form detail, workbooks,
 * received bundles (P17). Read offline from the owner-stamped cache (A16); written offline through
 * the form outbox (A15).
 */
@Composable
fun FormsScreen(
    user: UserDto?,
    workshops: List<WorkshopDetailDto>,
    header: @Composable () -> Unit,
    onOpenSession: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val scope = rememberCoroutineScope()
    var openId by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(FormTab.MINE) }
    var kind by remember { mutableStateOf<String?>(null) }
    var forms by remember { mutableStateOf<List<QForm>>(emptyList()) }
    var localForms by remember { mutableStateOf<List<QForm>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val online = ConnectivityObserver.isOnline(context)
    val received by FormBundleInbox.pending.collectAsState()

    LaunchedEffect(refresh) {
        loading = true
        val fetched = runCatching { v2.forms.forms(activeOnly = false) }.getOrNull()
        if (fetched != null && user != null) v2.formCache.putList(user.id, fetched)
        forms = fetched ?: v2.formCache.read(user?.id)?.forms.orEmpty()
        localForms = v2.formOutbox.localForms()
        loading = false
    }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { v2.forms.uploadWorkbook(workbookPart(context, uri)) }
                .onSuccess { report -> message = FormsCopy.UPLOADED; refresh++; report.questionnaire?.id?.let { openId = it } }
                .onFailure { onError(errorText(it, "Couldn't upload that workbook.")) }
        }
    }
    val openBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        FormBundleInbox.pending.value = FormBundleInbox.receive(context, uri)
    }

    val id = openId
    if (id != null) {
        FormDetailScreen(
            v2 = v2, user = user, formId = id, workshops = workshops,
            onBack = { openId = null; refresh++ },
            onOpenForm = { openId = it },
            onOpenSession = onOpenSession,
            onError = onError
        )
    } else Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            header()
            if (loading) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body) }
            FormsLibraryList(
                forms = forms, localForms = localForms, user = user, tab = tab, kind = kind, online = online,
                onTab = { tab = it }, onKind = { kind = it },
                onAction = { action ->
                    when (action) {
                        FormsCopy.NEW_FORM -> creating = true
                        FormsCopy.UPLOAD_WORKBOOK -> upload.launch(arrayOf(XLSX_MIME, "application/octet-stream"))
                        FormsCopy.OPEN_BUNDLE -> openBundle.launch(arrayOf("*/*"))
                        FormsCopy.DOWNLOAD_PRO_FORMA -> scope.launch {
                            runCatching {
                                QuestionnaireExport.saveResponse(
                                    context, v2.forms.proForma(), XLSX_MIME,
                                    Naming.exportFilename(entity = "Questionnaire", what = "Pro-Forma", ext = "xlsx", dated = false, portal = "FieldRepository")
                                )
                            }.onSuccess { message = FormsCopy.SAVED_TO }.onFailure { onError(errorText(it, "Couldn't download the pro-forma.")) }
                        }
                    }
                },
                onOpen = { openId = it.id }
            )
            Box(Modifier.padding(bottom = 72.dp))
        }
        if (FormRights.mayCreate(user)) ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(FormsCopy.NEW_FORM) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }

    if (creating) NewFormDialog(onDismiss = { creating = false }) { body ->
        creating = false
        scope.launch {
            // Online: created at once. Offline: queued with a local id and opened from the phone (A15).
            val created = if (ConnectivityObserver.isOnline(context)) runCatching { v2.forms.createForm(java.util.UUID.randomUUID().toString(), body) }.getOrNull() else null
            if (created != null) { refresh++; openId = created.id }
            else runCatching { v2.formOutbox.queueForm(body, user?.id) }
                .onSuccess { localId -> OutboxSyncWorker.schedule(context); refresh++; openId = localId }
                .onFailure { onError("Couldn't save that on this phone.") }
        }
    }

    received?.let { r ->
        ReceivedFormPrompt(
            received = r,
            onDismiss = { FormBundleInbox.pending.value = null },
            onAdd = { bundle ->
                FormBundleInbox.pending.value = null
                scope.launch {
                    val localId = v2.formOutbox.queueBundle(bundle, user?.id)
                    if (localId == null) message = FormsCopy.BUNDLE_ALREADY
                    else {
                        message = FormsCopy.BUNDLE_ADDED
                        OutboxSyncWorker.schedule(context)
                        if (ConnectivityObserver.isOnline(context)) runCatching { v2.formOutbox.sync(v2.formTransport, user?.id) }
                        refresh++
                    }
                }
            }
        )
    }
}

/** P17: "Received a form". Not now · Add. A file that could not be read says why, with one OK. */
@Composable
fun ReceivedFormPrompt(received: FormBundleInbox.Received, onDismiss: () -> Unit, onAdd: (FormBundle) -> Unit) {
    val bundle = received.bundle
    if (bundle == null) {
        QuestionnairePrompt(
            title = FormsCopy.P17_TITLE,
            body = received.refusal ?: "That file could not be read.",
            buttons = listOf(PromptButton("OK", primary = true, onClick = onDismiss)),
            onDismiss = onDismiss
        )
        return
    }
    QuestionnairePrompt(
        title = FormsCopy.P17_TITLE,
        body = FormsCopy.p17(null, bundle.form.title, bundle.sections.size) + " You'll be its author.",
        buttons = listOf(PromptButton(FormsCopy.NOT_NOW, onClick = onDismiss), PromptButton(FormsCopy.ADD, primary = true) { onAdd(bundle) }),
        onDismiss = onDismiss
    )
}

@Composable
fun NewFormDialog(onDismiss: () -> Unit, onCreate: (QFormCreate) -> Unit) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FormsCopy.NEW_FORM, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(220) }, label = { Text(FormsCopy.FORM_TITLE) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(2000) }, label = { Text(FormsCopy.FORM_DESCRIPTION) }, modifier = Modifier.fillMaxWidth())
                SearchableSelectField(
                    label = FormsCopy.FORM_KIND, options = FormKinds.LABELS.map { (k, v) -> SelectOption(k, v) },
                    selectedValue = kind, placeholder = FormKinds.NOT_STATED, includeNone = true
                ) { kind = it }
            }
        },
        confirmButton = {
            PromptButtons(listOf(
                PromptButton(FormsCopy.CANCEL, onClick = onDismiss),
                PromptButton(FormsCopy.CREATE, primary = true) {
                    if (title.isNotBlank()) onCreate(QFormCreate(title.trim(), description.trim().ifBlank { null }, kind.ifBlank { null }))
                }
            ))
        }
    )
}

/** The picked .xlsx as the multipart `file` part. */
fun workbookPart(context: Context, uri: Uri): MultipartBody.Part {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Couldn't read that file.")
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "workbook.xlsx"
    return MultipartBody.Part.createFormData("file", Naming.asciiLeaf(name, "workbook.xlsx"), bytes.toRequestBody(XLSX_MIME.toMediaTypeOrNull()))
}

/** The server's sentence when it gave one, else [fallback]. */
fun errorText(error: Throwable, fallback: String): String =
    (error as? retrofit2.HttpException)?.let { com.fieldrepository.app.data.apiDetail(it) } ?: fallback
