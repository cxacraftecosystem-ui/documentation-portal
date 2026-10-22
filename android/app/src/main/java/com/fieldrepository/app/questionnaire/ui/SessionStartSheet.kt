package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.questionnaire.data.QArtisanOption
import com.fieldrepository.app.questionnaire.data.QArtisanRef
import com.fieldrepository.app.questionnaire.data.QFormOption
import com.fieldrepository.app.questionnaire.data.QResearcherRef
import com.fieldrepository.app.questionnaire.data.QRespondentInput
import com.fieldrepository.app.questionnaire.data.QSession
import com.fieldrepository.app.questionnaire.data.QSessionCreate
import com.fieldrepository.app.questionnaire.data.QSubjects
import com.fieldrepository.app.questionnaire.data.QWorkshopOption
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireKind
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.data.RespondentKind
import com.fieldrepository.app.questionnaire.data.SessionOutbox
import com.fieldrepository.app.questionnaire.data.SessionPurpose
import com.fieldrepository.app.questionnaire.data.SharedLanguages
import com.fieldrepository.app.questionnaire.data.SubjectRole
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import com.fieldrepository.app.ui.ActionRow
import com.fieldrepository.app.ui.CalendarField
import com.fieldrepository.app.ui.SearchableMultiSelectField
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate

/** The language list of `shared/languages.json`, with "Other" last. */
internal val SESSION_LANGUAGES: List<SelectOption> = SharedLanguages.labels.map { (code, label) -> SelectOption(code, label) }

/** A guest typed on the start sheet (UNIFY U6): a named person, or the researcher themself. */
data class StartGuest(val localId: String, val name: String, val kind: String = RespondentKind.GUEST.wire, val notes: String = "") {
    fun input(): QRespondentInput = QRespondentInput(
        name = SessionOutbox.tidyName(name).ifBlank { null },
        kind = kind,
        notes = notes.trim().ifBlank { null }
    )
}

/** The start sheet's fields, everything a title preview and a create need. */
data class StartForm(
    val workshopId: String = "",
    val questionnaireId: String = "",
    val date: LocalDate? = LocalDate.now(Naming.IST),
    val group: Boolean = false,
    val artisanIds: List<String> = emptyList(),
    /** "Someone else" and "Myself" (UNIFY U6): guests who are not registered artisans. */
    val guests: List<StartGuest> = emptyList(),
    val purpose: String = SessionPurpose.FIRST_SITTING.wire,
    val place: String = "",
    val languageCode: String = "",
    val languageOther: String = "",
    val note: String = ""
) {
    /** A form, a date and somebody to ask: artisans, or a guest with a name (or the researcher). */
    val canStart: Boolean get() = questionnaireId.isNotBlank() && date != null && (artisanIds.isNotEmpty() || guests.any { it.input().name != null || it.kind == RespondentKind.SELF.wire })

    /** Everyone the session is about, in subject ids. */
    val subjectIds: List<String> get() = artisanIds + guests.map { it.localId }

    /** Pick [w]: its place when none is typed yet. The form comes from the server's resolution. */
    fun withWorkshop(w: QWorkshopOption): StartForm = copy(workshopId = w.id, place = place.ifBlank { w.place.orEmpty() })

    fun titlePreview(workshop: QWorkshopOption?, artisans: Map<String, QArtisanOption>, meName: String?): String = Naming.sessionTitle(
        Naming.SessionFields(
            sessionDate = date,
            place = place.ifBlank { null },
            workshopDistrict = workshop?.district ?: workshop?.place,
            workshopCraft = workshop?.craft,
            participantCrafts = artisanIds.map { artisans[it]?.craft },
            participantNames = artisanIds.map { artisans[it]?.name.orEmpty() } +
                guests.map { g -> if (g.kind == RespondentKind.SELF.wire) meName.orEmpty() else g.name.trim() }
        )
    )

    fun toCreate(clientKey: String, me: String?, now: Instant): QSessionCreate = QSessionCreate(
        clientKey = clientKey,
        questionnaireId = questionnaireId,
        // The designer portal files a session under a DESIGN workshop; Field Repository under a workshop.
        workshopId = workshopId.ifBlank { null }.takeUnless { QuestionnairePortal.FILES_UNDER_DESIGN_WORKSHOP },
        designWorkshopId = workshopId.ifBlank { null }.takeIf { QuestionnairePortal.FILES_UNDER_DESIGN_WORKSHOP },
        sessionDate = date!!.toString(),
        startedAt = now.toString(),
        purpose = purpose,
        place = place.ifBlank { null },
        languageCode = languageCode.ifBlank { null },
        languageOther = languageOther.ifBlank { null }?.takeIf { languageCode == "other" },
        note = note.trim().take(80).ifBlank { null },
        participants = artisanIds.map { QArtisanRef(it) },
        researchers = listOfNotNull(me?.let { QResearcherRef(it, "LEAD") }),
        respondents = guests.map { it.input() }
    )
}

/**
 * The form a new session opens with (UNIFY §2.3 rule 5, owner amendment 2): what the server's
 * resolution names for this workshop, else the one picked before, else the default form, else the
 * first. The researcher can always pick any other form they can see.
 */
internal fun startFormFor(resolved: String?, current: String, forms: List<QFormOption>): String = when {
    resolved != null && forms.any { it.id == resolved } -> resolved
    current.isNotBlank() && forms.any { it.id == current } -> current
    else -> forms.firstOrNull { it.isDefault }?.id ?: forms.firstOrNull()?.id.orEmpty()
}

/** The form picker's rows: grouped by kind (Workshop interview, Market survey), bound forms marked. */
internal fun formOptions(forms: List<QFormOption>, bound: Set<String>): List<SelectOption> =
    forms.sortedWith(compareBy<QFormOption> { kindOrder(it.kind) }.thenBy { it.id !in bound }.thenBy { it.title.lowercase() }).map { f ->
        SelectOption(f.id, f.title, listOfNotNull(f.kindLabel.ifBlank { null }, QuestionnaireCopy.s("form.bound").takeIf { f.id in bound }).joinToString(" · ").ifBlank { null })
    }

private fun kindOrder(kind: String?): Int = QuestionnaireKind.entries.indexOfFirst { it.wire == kind }.let { if (it < 0) Int.MAX_VALUE else it }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionStartSheet(
    v2: QuestionnaireV2,
    workshops: List<QWorkshopOption>,
    forms: List<QFormOption>,
    artisans: List<QArtisanOption>,
    meId: String?,
    meName: String? = null,
    prefillArtisanIds: List<String> = emptyList(),
    /** A form chosen before the sheet opened (a form's "Start session"). */
    prefillFormId: String? = null,
    defaultWorkshopId: String? = null,
    onDismiss: () -> Unit,
    onStarted: (clientKey: String) -> Unit,
    onJoined: (session: QSession) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    // Artisans registered on this phone in this sheet (offline registration): local id → the form.
    var registered by remember { mutableStateOf<List<Pair<String, NewArtisan>>>(emptyList()) }
    var registering by remember { mutableStateOf(false) }
    var addingGuest by remember { mutableStateOf(false) }
    val byId = remember(artisans, registered) {
        artisans.associateBy { it.id } + registered.associate { (id, a) -> id to QArtisanOption(id = id, name = a.name.trim(), place = a.place.trim()) }
    }
    var form by remember {
        mutableStateOf(StartForm(
            questionnaireId = startFormFor(prefillFormId, "", forms),
            artisanIds = prefillArtisanIds, group = prefillArtisanIds.size > 1
        ))
    }
    var bound by remember { mutableStateOf(emptySet<String>()) }
    val consent = remember { mutableStateMapOf<String, String?>() }
    var openMatch by remember { mutableStateOf<QSession?>(null) }
    var starting by remember { mutableStateOf(false) }
    val workshop = workshops.firstOrNull { it.id == form.workshopId }
    var workshopTouched by remember { mutableStateOf(false) }
    var formTouched by remember { mutableStateOf(prefillFormId != null) }

    // The most recent workshop the researcher may record in is the default (B30); a pick is never replaced.
    LaunchedEffect(workshops, defaultWorkshopId) {
        if (workshopTouched || form.workshopId.isNotBlank()) return@LaunchedEffect
        mostRecentRecordableWorkshop(workshops, defaultWorkshopId)?.let { form = form.withWorkshop(it) }
    }
    // The workshop's form (B31): its primary binding, its only binding, the default for its type, the
    // default form. Online only; offline the sheet keeps the form it has.
    LaunchedEffect(form.workshopId) {
        val resolution = withTimeoutOrNull(4_000) {
            runCatching {
                if (QuestionnairePortal.FILES_UNDER_DESIGN_WORKSHOP) v2.api.formFor(designWorkshopId = form.workshopId.ifBlank { null })
                else v2.api.formFor(workshopId = form.workshopId.ifBlank { null })
            }.getOrNull()
        }
        bound = resolution?.bound?.map { it.questionnaireId }?.toSet().orEmpty()
        if (!formTouched) form = form.copy(questionnaireId = startFormFor(resolution?.questionnaireId, form.questionnaireId, forms))
    }

    LaunchedEffect(form.artisanIds) {
        // An artisan registered on this phone has no server record to hold consent yet.
        form.artisanIds.filter { id -> registered.none { it.first == id } }.forEach { id -> if (!consent.containsKey(id)) consent[id] = v2.consentState(id) }
    }

    fun create() {
        val key = SessionOutbox.newKey()
        scope.launch {
            // Offline registrations go first in the session's unit; the server's ids replace the local ones.
            val locals = registered.filter { (id, _) -> id in form.artisanIds }.map { (id, a) ->
                id to QuestionnairePortal.artisanCreateJson(a, form.workshopId)
            }
            runCatching {
                v2.outbox.startSession(form.toCreate(key, meId, Instant.now()), meId, localArtisans = locals, guestIds = form.guests.map { it.localId })
            }
                .onSuccess {
                    registered.filter { (id, _) -> id in form.artisanIds }.forEach { (id, a) -> v2.rememberLocalArtisan(id, a.name.trim()) }
                    v2.requestSync(); onStarted(key)
                }
                .onFailure { onError(QuestionnaireCopy.s("error.startSession")) }
            starting = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(QuestionnaireCopy.s("sessions.new"), style = MaterialTheme.typography.titleLarge)
            SearchableSelectField(
                label = QuestionnaireCopy.s("start.workshop"),
                options = listOf(SelectOption("", QuestionnaireCopy.s("start.fieldVisit"))) + workshops.map { SelectOption(it.id, it.title, it.place) },
                selectedValue = form.workshopId,
                includeNone = false,
                searchable = true
            ) { id ->
                workshopTouched = true
                val w = workshops.firstOrNull { it.id == id }
                form = if (w != null) form.withWorkshop(w) else form.copy(workshopId = id)
            }
            SearchableSelectField(
                label = QuestionnaireCopy.s("start.instrument"),
                options = formOptions(forms, bound),
                selectedValue = form.questionnaireId,
                includeNone = false,
                searchable = true
            ) { formTouched = true; form = form.copy(questionnaireId = it) }
            CalendarField(label = QuestionnaireCopy.s("start.date"), value = form.date, onValueChange = { form = form.copy(date = it) }, clearable = false)
            SegmentedChoice(
                label = QuestionnaireCopy.s("start.mode"),
                options = listOf(SelectOption("I", QuestionnaireCopy.s("start.mode.individual")), SelectOption("G", QuestionnaireCopy.s("start.mode.group"))),
                selectedValue = if (form.group) "G" else "I"
            ) { v -> form = form.copy(group = v == "G", artisanIds = if (v == "I") form.artisanIds.take(1) else form.artisanIds, guests = if (v == "I" && form.artisanIds.isNotEmpty()) emptyList() else form.guests) }
            val artisanOptions = artisans.map { SelectOption(it.id, it.name, it.place.ifBlank { null }) } +
                registered.map { (id, a) -> SelectOption(id, a.name.trim(), QuestionnaireCopy.s("artisan.onPhone")) }
            if (form.group) {
                SearchableMultiSelectField(
                    label = QuestionnaireCopy.s("start.artisans"),
                    options = artisanOptions,
                    selected = form.artisanIds.toSet()
                ) { chosen -> form = form.copy(artisanIds = form.artisanIds.filter { it in chosen } + chosen.filter { it !in form.artisanIds }) }
            } else {
                SearchableSelectField(
                    label = QuestionnaireCopy.s("start.artisan"),
                    options = artisanOptions,
                    selectedValue = form.artisanIds.firstOrNull().orEmpty(),
                    includeNone = false,
                    searchable = true
                ) { form = form.copy(artisanIds = listOf(it), guests = emptyList()) }
            }
            // Guests (UNIFY U6): someone who is not a registered artisan, or the researcher answering
            // their own form. They answer like anyone else and are never an artisan record.
            if (form.guests.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                form.guests.forEach { g ->
                    InputChip(
                        selected = true,
                        onClick = { form = form.copy(guests = form.guests - g) },
                        label = { Text(if (g.kind == RespondentKind.SELF.wire) QuestionnaireCopy.s("guest.myself") else g.name.trim()) }
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { registering = true }) { Text(QuestionnaireCopy.s("artisan.new")) }
                TextButton(onClick = { addingGuest = true }) { Text(QuestionnaireCopy.s("guest.someoneElse")) }
                if (form.guests.none { it.kind == RespondentKind.SELF.wire }) TextButton(onClick = {
                    val me = StartGuest(QSubjects.newLocalGuest(), meName.orEmpty(), RespondentKind.SELF.wire)
                    form = if (form.group) form.copy(guests = form.guests + me) else form.copy(artisanIds = emptyList(), guests = listOf(me))
                }) { Text(QuestionnaireCopy.s("guest.myself")) }
            }
            coverageLine(v2, form, byId, forms.firstOrNull { it.id == form.questionnaireId }?.sectionCount)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
            // Consent is assumed by default: a notice with Switch off for no answer, Switch back on after a refusal.
            form.artisanIds.filter { consent.containsKey(it) && consent[it] in setOf(null, "NOT_RECORDED", "REFUSED") }.take(3).forEach { id ->
                ConsentQuestion(byId[id]?.name.orEmpty(), refused = consent[id] == "REFUSED", onAnswer = { state ->
                    if (state == null) consent[id] = "SKIPPED" else {
                        consent[id] = "SKIPPED"
                        scope.launch { v2.recordConsent(id, state) }
                    }
                })
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(QuestionnaireCopy.s("start.purpose"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionPurpose.entries.map { it.wire }.forEach { p ->
                        FilterChip(selected = form.purpose == p, onClick = { form = form.copy(purpose = p) }, label = { Text(QuestionnaireCopy.purposeLabel(p)) })
                    }
                }
            }
            OutlinedTextField(
                value = form.place, onValueChange = { form = form.copy(place = it) },
                label = { Text(QuestionnaireCopy.s("start.place")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { PlaceFromDevice(onPlace = { form = form.copy(place = it) }, onError = onError) }
            )
            SearchableSelectField(
                label = QuestionnaireCopy.s("start.language"),
                options = SESSION_LANGUAGES,
                selectedValue = form.languageCode,
                includeNone = false,
                searchable = true
            ) { form = form.copy(languageCode = it) }
            if (form.languageCode == "other") {
                OutlinedTextField(
                    value = form.languageOther, onValueChange = { form = form.copy(languageOther = it.take(40)) },
                    label = { Text(QuestionnaireCopy.s("start.languageOther")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = form.note, onValueChange = { form = form.copy(note = it.take(80)) },
                label = { Text(QuestionnaireCopy.s("start.note")) },
                placeholder = { Text(QuestionnaireCopy.s("start.notePlaceholder")) },
                supportingText = { Text("${form.note.length}/80") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            // No title field: the generated title, as it will be saved.
            Text(QuestionnaireCopy.s("start.titlePreview"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted)
            Text(form.titlePreview(workshop, byId, meName), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.field.body)
            ActionRow(Modifier.fillMaxWidth()) {
                Button(enabled = form.canStart && !starting, onClick = {
                    starting = true
                    scope.launch {
                        // P1 asks about the artisans; a session only of guests never matches an open one.
                        val match = if (form.artisanIds.isEmpty()) null else withTimeoutOrNull(4_000) {
                            runCatching {
                                v2.api.openMatch(form.questionnaireId, form.workshopId.ifBlank { null }.takeUnless { QuestionnairePortal.FILES_UNDER_DESIGN_WORKSHOP }, form.artisanIds.joinToString(","), form.date.toString()).items.firstOrNull()
                            }.getOrNull()
                        }
                        if (match != null) { openMatch = match; starting = false } else create()
                    }
                }) { Text(QuestionnaireCopy.s("start.submit")) }
            }
        }
    }

    if (registering) RegisterArtisanDialog(onDismiss = { registering = false }) { artisan ->
        registering = false
        val localId = "local-" + SessionOutbox.newKey()
        registered = registered + (localId to artisan)
        form = form.copy(artisanIds = if (form.group) form.artisanIds + localId else listOf(localId), guests = if (form.group) form.guests else emptyList())
    }

    if (addingGuest) GuestDialog(
        title = QuestionnaireCopy.s("guest.someoneElse"),
        initialName = "", initialNotes = "",
        onDismiss = { addingGuest = false }
    ) { name, notes ->
        addingGuest = false
        val guest = StartGuest(QSubjects.newLocalGuest(), name, RespondentKind.GUEST.wire, notes)
        form = if (form.group) form.copy(guests = form.guests + guest) else form.copy(artisanIds = emptyList(), guests = listOf(guest))
    }

    openMatch?.let { match ->
        val lead = match.researchers.firstOrNull()?.name?.let(QuestionnaireCopy::firstName).orEmpty()
        val started = match.startedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
        OpenMatchPrompt(
            body = QuestionnaireCopy.p1Body(lead, match.participants.map { QuestionnaireCopy.firstName(it.name) }, started),
            onNew = { openMatch = null; starting = true; create() },
            onJoin = { openMatch = null; onJoined(match) },
            onDismiss = { openMatch = null }
        )
    }
}

/** A guest's name and notes: "Someone else" on the start sheet, "+ Add someone else" and A17's edit. */
@Composable
fun GuestDialog(title: String, initialName: String, initialNotes: String, onDismiss: () -> Unit, onSave: (name: String, notes: String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var notes by remember { mutableStateOf(initialNotes) }
    var missing by remember { mutableStateOf(false) }
    QuestionnairePromptContent(
        title = title,
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(120); missing = false },
                    label = { Text(QuestionnaireCopy.s("guest.name")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = missing, supportingText = { if (missing) Text(QuestionnaireCopy.s("guest.needsName")) }
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it.take(2000) },
                    label = { Text(QuestionnaireCopy.s("guest.notes")) }, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("session.cancel"), onClick = onDismiss),
            PromptButton(QuestionnaireCopy.s("save"), primary = true) {
                if (SessionOutbox.tidyName(name).isBlank() && initialName.isBlank()) missing = true else onSave(name, notes)
            }
        )
    )
}

/** §4.2's coverage line from the offline snapshot, with "· Checked 2 h ago" when it is not fresh. */
private fun coverageLine(v2: QuestionnaireV2, form: StartForm, byId: Map<String, QArtisanOption>, sectionsTotal: Int?): String? {
    if (form.artisanIds.isEmpty() || form.questionnaireId.isBlank()) return null
    val workshopKey = form.workshopId.ifBlank { null }.takeUnless { QuestionnairePortal.FILES_UNDER_DESIGN_WORKSHOP }
    val cached = v2.coverage.get(form.questionnaireId, workshopKey) ?: return null
    val total = sectionsTotal?.takeIf { it > 0 } ?: return null
    val people = form.artisanIds.map { id ->
        val covered = cached.snapshot.takes.filter { it.artisanId == id && it.role == SubjectRole.ANSWERED.wire && it.targetKey.startsWith("S:") }
            .map { it.targetKey }.distinct().size
        QuestionnaireCopy.ArtisanCoverage(QuestionnaireCopy.firstName(byId[id]?.name.orEmpty()), covered)
    }
    return QuestionnaireCopy.coverageLine(people, total, v2.coverage.ageLabel(form.questionnaireId, workshopKey))
}
