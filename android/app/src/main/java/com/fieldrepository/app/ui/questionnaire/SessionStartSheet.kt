package com.fieldrepository.app.ui.questionnaire

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.ArtisanCreateRequest
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.data.QArtisanRef
import com.fieldrepository.app.data.QResearcherRef
import com.fieldrepository.app.data.QSession
import com.fieldrepository.app.data.QSessionCreate
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.data.QuestionnaireDto
import com.fieldrepository.app.data.QuestionnaireV2
import com.fieldrepository.app.data.SessionOutbox
import com.fieldrepository.app.data.SessionPurpose
import com.fieldrepository.app.data.SubjectRole
import com.fieldrepository.app.data.WorkshopDetailDto
import com.fieldrepository.app.ui.ActionRow
import com.fieldrepository.app.ui.CalendarField
import com.fieldrepository.app.ui.SearchableMultiSelectField
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SegmentedChoice
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import com.fieldrepository.app.ui.mostRecentRecordableWorkshop
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/** The language list of BUILD_PLAN §0.1 (`shared/languages.json`, owned by `feature/s-standardise`). */
internal val SESSION_LANGUAGES: List<SelectOption> = listOf(
    "as" to "Assamese", "bn" to "Bengali", "en" to "English", "gu" to "Gujarati", "hi" to "Hindi",
    "kn" to "Kannada", "ml" to "Malayalam", "mr" to "Marathi", "ne" to "Nepali", "or" to "Odia",
    "pa" to "Punjabi", "sat" to "Santali", "ta" to "Tamil", "te" to "Telugu", "ur" to "Urdu", "other" to "Other"
).map { SelectOption(it.first, it.second) }

/** The start sheet's fields, everything a title preview and a create need. */
data class StartForm(
    val workshopId: String = "",
    val questionnaireId: String = "",
    val date: LocalDate? = LocalDate.now(Naming.IST),
    val group: Boolean = false,
    val artisanIds: List<String> = emptyList(),
    val purpose: String = SessionPurpose.FIRST_SITTING.wire,
    val place: String = "",
    val languageCode: String = "",
    val languageOther: String = "",
    val note: String = "",
    /** "Someone else": guest respondents by name (owner decision 1). A session may have only guests. */
    val guests: List<String> = emptyList()
) {
    val canStart: Boolean get() = questionnaireId.isNotBlank() && date != null && (artisanIds.isNotEmpty() || guests.any { it.isNotBlank() })

    /** Pick [w]: its bound instrument, and its place when none is typed yet. */
    fun withWorkshop(w: WorkshopDetailDto): StartForm = copy(
        workshopId = w.id,
        questionnaireId = w.questionnaireId ?: questionnaireId,
        place = place.ifBlank { w.place }
    )

    fun titlePreview(workshop: WorkshopDetailDto?, artisans: Map<String, ArtisanDto>): String = Naming.sessionTitle(
        Naming.SessionFields(
            sessionDate = date,
            place = place.ifBlank { null },
            workshopDistrict = workshop?.place?.ifBlank { null },
            workshopCraft = workshop?.crafts?.singleOrNull()?.craft?.name,
            participantCrafts = artisanIds.map { artisans[it]?.craft?.name },
            participantNames = artisanIds.map { artisans[it]?.name.orEmpty() }
        )
    )

    fun toCreate(clientKey: String, me: String?, now: Instant): QSessionCreate = QSessionCreate(
        clientKey = clientKey,
        questionnaireId = questionnaireId,
        workshopId = workshopId.ifBlank { null },
        sessionDate = date!!.toString(),
        startedAt = now.toString(),
        purpose = purpose,
        place = place.ifBlank { null },
        languageCode = languageCode.ifBlank { null },
        languageOther = languageOther.ifBlank { null }?.takeIf { languageCode == "other" },
        note = note.trim().take(80).ifBlank { null },
        participants = artisanIds.map { QArtisanRef(it) },
        researchers = listOfNotNull(me?.let { QResearcherRef(it, "LEAD") }),
        respondents = guests.map { it.trim() }.filter { it.isNotBlank() }.map { com.fieldrepository.app.data.QRespondentInput(name = it.take(120)) }
    )

    /** The guests' local keys, in the order [toCreate] lists them; the outbox maps them to server ids. */
    fun guestKeys(): List<Pair<String, String>> = guests.map { it.trim() }.filter { it.isNotBlank() }.map { "guest-" + SessionOutbox.newKey() to it.take(120) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionStartSheet(
    v2: QuestionnaireV2,
    workshops: List<WorkshopDetailDto>,
    questionnaires: List<QuestionnaireDto>,
    artisans: List<ArtisanDto>,
    meId: String?,
    prefillArtisanIds: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onStarted: (clientKey: String) -> Unit,
    onJoined: (session: QSession) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    // Artisans registered on this phone in this sheet (offline registration): local id → the form.
    var registered by remember { mutableStateOf<List<Pair<String, NewArtisan>>>(emptyList()) }
    var registering by remember { mutableStateOf(false) }
    val byId = remember(artisans, registered) {
        artisans.associateBy { it.id } + registered.associate { (id, a) -> id to ArtisanDto(id = id, name = a.name.trim(), place = a.place.trim(), status = "PENDING") }
    }
    val defaultInstrument = questionnaires.firstOrNull { it.isDefault }?.id ?: questionnaires.firstOrNull()?.id.orEmpty()
    var form by remember {
        mutableStateOf(StartForm(questionnaireId = defaultInstrument, artisanIds = prefillArtisanIds, group = prefillArtisanIds.size > 1))
    }
    val consent = remember { mutableStateMapOf<String, String?>() }
    var openMatch by remember { mutableStateOf<QSession?>(null) }
    var starting by remember { mutableStateOf(false) }
    val workshop = workshops.firstOrNull { it.id == form.workshopId }
    var workshopTouched by remember { mutableStateOf(false) }
    var formTouched by remember { mutableStateOf(false) }
    var addingGuest by remember { mutableStateOf(false) }
    var resolved by remember { mutableStateOf<com.fieldrepository.app.data.QFormFor?>(null) }

    // B31, B32, D2: the workshop's primary binding, its only binding, its type's default, the global
    // default, in that order, from the server; offline, the cached default. A pick is never replaced.
    LaunchedEffect(form.workshopId) {
        val r = runCatching { v2.forms.formFor(form.workshopId.ifBlank { null }) }.getOrNull()
        resolved = r
        if (formTouched) return@LaunchedEffect
        val live = questionnaires.filter { it.isActive }.map { com.fieldrepository.app.data.QForm(id = it.id, title = it.title, isDefault = it.isDefault) }
        com.fieldrepository.app.data.FormResolution.pick(null, r, live)?.let { form = form.copy(questionnaireId = it) }
    }

    // The most recent workshop the researcher may record in is the default, from the cached list
    // when offline; it stays changeable, and a pick already made is never replaced.
    LaunchedEffect(workshops) {
        if (workshopTouched || form.workshopId.isNotBlank()) return@LaunchedEffect
        mostRecentRecordableWorkshop(workshops)?.let { form = form.withWorkshop(it) }
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
                id to ApiClient.json.encodeToString(ArtisanCreateRequest.serializer(), a.request(form.workshopId))
            }
            val guests = form.guestKeys()
            val body = form.copy(guests = guests.map { it.second }).toCreate(key, meId, Instant.now())
            runCatching { v2.outbox.startSession(body, meId, localArtisans = locals, guests = guests) }
                .onSuccess {
                    registered.filter { (id, _) -> id in form.artisanIds }.forEach { (id, a) -> v2.rememberLocalArtisan(id, a.name.trim()) }
                    OutboxSyncWorker.schedule(v2App(v2)); onStarted(key)
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
                options = listOf(SelectOption("", QuestionnaireCopy.s("start.fieldVisit"))) + workshops.map { SelectOption(it.id, it.title, it.place.ifBlank { null }) },
                selectedValue = form.workshopId,
                includeNone = false,
                searchable = true
            ) { id ->
                workshopTouched = true
                val w = workshops.firstOrNull { it.id == id }
                form = if (w != null) form.withWorkshop(w) else form.copy(workshopId = id)
            }
            val bound = resolved?.bound.orEmpty().map { it.questionnaireId }.toSet()
            SearchableSelectField(
                label = QuestionnaireCopy.s("start.instrument"),
                // The workshop's own forms first ("For this workshop"); any form the researcher can see stays pickable.
                options = questionnaires.filter { it.isActive }.sortedBy { if (it.id in bound) 0 else 1 }
                    .map { SelectOption(it.id, it.title, if (it.id in bound) "For this workshop" else null) },
                selectedValue = form.questionnaireId,
                includeNone = false
            ) { formTouched = true; form = form.copy(questionnaireId = it) }
            CalendarField(label = QuestionnaireCopy.s("start.date"), value = form.date, onValueChange = { form = form.copy(date = it) }, clearable = false)
            SegmentedChoice(
                label = QuestionnaireCopy.s("start.mode"),
                options = listOf(SelectOption("I", QuestionnaireCopy.s("start.mode.individual")), SelectOption("G", QuestionnaireCopy.s("start.mode.group"))),
                selectedValue = if (form.group) "G" else "I"
            ) { v -> form = form.copy(group = v == "G", artisanIds = if (v == "I") form.artisanIds.take(1) else form.artisanIds) }
            val pool = workshop?.artisans?.mapNotNull { link -> byId[link.artisanId] ?: link.artisan }?.takeIf { it.isNotEmpty() } ?: artisans
            val artisanOptions = pool.map { SelectOption(it.id, it.name, it.place.ifBlank { null }) } +
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
                ) { form = form.copy(artisanIds = listOf(it)) }
            }
            ActionRow(Modifier.fillMaxWidth()) {
                TextButton(onClick = { registering = true }) { Text(QuestionnaireCopy.s("artisan.new")) }
                TextButton(onClick = { addingGuest = true }) { Text("+ " + com.fieldrepository.app.data.FormsCopy.SOMEONE_ELSE) }
            }
            if (form.guests.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                form.guests.forEach { g ->
                    FilterChip(selected = true, onClick = { form = form.copy(guests = form.guests - g) }, label = { Text("$g ×") })
                }
            }
            coverageLine(v2, form, byId, questionnaires.firstOrNull { it.id == form.questionnaireId }?.sectionCount)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
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
                label = { Text(QuestionnaireCopy.s("start.place")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
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
            Text(form.titlePreview(workshop, byId), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.field.body)
            ActionRow(Modifier.fillMaxWidth()) {
                Button(enabled = form.canStart && !starting, onClick = {
                    starting = true
                    scope.launch {
                        if (form.artisanIds.isEmpty()) { create(); return@launch }
                        val match = runCatching {
                            v2.api.openMatch(form.questionnaireId, form.workshopId.ifBlank { null }, form.artisanIds.joinToString(","), form.date.toString()).items.firstOrNull()
                        }.getOrNull()
                        if (match != null) { openMatch = match; starting = false } else create()
                    }
                }) { Text(QuestionnaireCopy.s("start.submit")) }
            }
        }
    }

    if (addingGuest) GuestNameDialog(onDismiss = { addingGuest = false }) { name ->
        addingGuest = false
        if (name.isNotBlank()) form = form.copy(guests = form.guests + name.trim())
    }

    if (registering) RegisterArtisanDialog(onDismiss = { registering = false }) { artisan ->
        registering = false
        val localId = "local-" + SessionOutbox.newKey()
        registered = registered + (localId to artisan)
        form = form.copy(artisanIds = if (form.group) form.artisanIds + localId else listOf(localId))
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

private fun v2App(v2: QuestionnaireV2) = v2.appContext

/** §4.2's coverage line from the offline snapshot, with "· Checked 2 h ago" when it is not fresh. */
private fun coverageLine(v2: QuestionnaireV2, form: StartForm, byId: Map<String, ArtisanDto>, sectionsTotal: Int?): String? {
    if (form.artisanIds.isEmpty() || form.questionnaireId.isBlank()) return null
    val cached = v2.coverage.get(form.questionnaireId, form.workshopId.ifBlank { null }) ?: return null
    val total = sectionsTotal?.takeIf { it > 0 } ?: return null
    val people = form.artisanIds.map { id ->
        val covered = cached.snapshot.takes.filter { it.artisanId == id && it.role == SubjectRole.ANSWERED.wire && it.targetKey.startsWith("S:") }
            .map { it.targetKey }.distinct().size
        QuestionnaireCopy.ArtisanCoverage(QuestionnaireCopy.firstName(byId[id]?.name.orEmpty()), covered)
    }
    return QuestionnaireCopy.coverageLine(people, total, v2.coverage.ageLabel(form.questionnaireId, form.workshopId.ifBlank { null }))
}
