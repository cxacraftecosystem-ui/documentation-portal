package com.fieldrepository.app.questionnaire.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.BuildConfig
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.questionnaire.data.ChipFilter
import com.fieldrepository.app.questionnaire.data.FilterStore
import com.fieldrepository.app.questionnaire.data.QArtisanOption
import com.fieldrepository.app.questionnaire.data.QFormOption
import com.fieldrepository.app.questionnaire.data.QSession
import com.fieldrepository.app.questionnaire.data.QSessionCreate
import com.fieldrepository.app.questionnaire.data.QWorkshopOption
import com.fieldrepository.app.questionnaire.data.QuestionnaireFilters
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.data.SessionUnit
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.questionnaire.data.outboxJson
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import com.fieldrepository.app.recording.RecordingDraft
import com.fieldrepository.app.recording.RecordingDrafts
import com.fieldrepository.app.recording.RecordingService
import com.fieldrepository.app.recording.RecoveryScanner
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.LocalMayDelete
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch
import java.time.LocalDate

private sealed interface QRoute {
    data object List : QRoute
    data class Workspace(val clientKey: String) : QRoute
    data object Inbox : QRoute
    data object Join : QRoute
    data object Coverage : QRoute
    data class ReadView(val sessionId: String) : QRoute
    /** The recordings list, opened from the menu or by a chip tapped on another screen. */
    data class Recordings(val initial: QuestionnaireFilters? = null) : QRoute
}

/** One row of the sessions list: a server session, or one still only on this phone. */
data class SessionListRow(val key: String, val title: String, val note: String?, val meta: String, val local: Boolean, val session: QSession?)

internal fun localRow(unit: SessionUnit, names: Map<String, String>): SessionListRow? {
    val body = unit.steps.firstOrNull { it.kind == StepKind.SESSION }?.bodyJson
        ?.let { runCatching { outboxJson.decodeFromString(QSessionCreate.serializer(), it) }.getOrNull() } ?: return null
    val ids = body.participants.map { it.artisanId }
    val title = Naming.sessionTitle(
        Naming.SessionFields(
            sessionDate = runCatching { LocalDate.parse(body.sessionDate) }.getOrNull(), place = body.place,
            participantNames = ids.map { names[it].orEmpty() } + body.respondents.map { it.name.orEmpty() }
        )
    )
    return SessionListRow(unit.clientKey, title, body.note, listOf(QuestionnaireCopy.mode(ids.size + body.respondents.size), QuestionnaireCopy.s("upload.onDevice")).joinToString(" · "), true, null)
}

internal fun serverRow(s: QSession): SessionListRow = SessionListRow(
    key = s.clientKey ?: s.id,
    title = s.title,
    note = s.note,
    meta = listOfNotNull(
        s.sessionDate?.let { runCatching { QuestionnaireCopy.day(LocalDate.parse(it.take(10))) }.getOrNull() },
        QuestionnaireCopy.mode(s.participantCount.coerceAtLeast(s.participants.size) + s.respondents.count { it.artisanId == null }),
        s.questionnaire?.title,
        s.researchers.joinToString(", ") { QuestionnaireCopy.firstName(it.name) }.ifBlank { null },
        "${s.counts.sections} ${if (s.counts.sections == 1) "section" else "sections"} · ${s.counts.takes} ${if (s.counts.takes == 1) "take" else "takes"}",
        s.status.lowercase().replaceFirstChar { it.uppercase() }
    ).joinToString(" · "),
    local = false,
    session = s
)

/** The sessions list's overflow menu: Recordings, Join and Workshop coverage for everyone. */
internal fun sessionsMenu(): List<String> = listOf("recordings", "sessions.join", "coverage.title")

/** The next page joins the list; a session already listed (a local row, or an earlier page) is not repeated. */
internal fun appendPage(rows: List<SessionListRow>, page: List<QSession>): List<SessionListRow> =
    rows + page.map(::serverRow).filter { s -> rows.none { it.key == s.key } }

/** The home's two tabs (UNIFY §4.2 Android): Sessions | Forms, with one FAB matching the tab. */
enum class QuestionnaireTab { SESSIONS, FORMS }

/**
 * The questionnaire home on Android (UNIFY §4.2): a segmented Sessions | Forms with one FAB that
 * matches the tab (New session, New form). Sessions: the server-paged, searchable, filtered list,
 * the start sheet, the workspace, the read view and review, the inbox (badge), joining, the workshop
 * coverage page, and for content admins the Deleted sessions. Forms: the portal's forms library,
 * drawn by [formsTab].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionnaireHome(
    artisans: List<QArtisanOption>,
    meId: String?,
    meName: String? = null,
    prefillArtisanIds: List<String> = emptyList(),
    /** Professor and above (FR) or the portal's equivalent: "Make main take" on a take. */
    canMakePrimary: Boolean = false,
    /** A content admin: changes answers others wrote (U9) and reviews. */
    isAdmin: Boolean = false,
    /** May approve or return a session in the read view (B27). */
    canReview: Boolean = false,
    /** The forms library, given "start a session on this form" (A9, D3). */
    formsTab: @Composable (onStartSession: ((questionnaireId: String) -> Unit)?) -> Unit,
    onNewForm: () -> Unit,
    onUpdateApp: () -> Unit,
    onError: (String) -> Unit,
    initialTab: QuestionnaireTab = QuestionnaireTab.SESSIONS,
    /** The host's way out, when the home owns the whole viewport (its own back control). */
    onBack: (() -> Unit)? = null,
    /** The home's box: the host gives it a bounded height when it sits in a page that scrolls. */
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val scope = rememberCoroutineScope()
    val mayDelete = LocalMayDelete.current
    var tab by remember { mutableStateOf(initialTab) }
    var route by remember { mutableStateOf<QRoute>(QRoute.List) }
    // "Continue with…" / "Start session" from an artisan's record or a form: held HERE, because the
    // caller clears its request as soon as this screen takes it (audit M23: it used to be lost).
    var prefill by remember { mutableStateOf(prefillArtisanIds) }
    var prefillForm by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(prefillArtisanIds.isNotEmpty()) }
    LaunchedEffect(prefillArtisanIds) {
        if (prefillArtisanIds.isNotEmpty()) { prefill = prefillArtisanIds; starting = true; route = QRoute.List; tab = QuestionnaireTab.SESSIONS }
    }
    var forms by remember { mutableStateOf<List<QFormOption>>(emptyList()) }
    var workshops by remember { mutableStateOf<List<QWorkshopOption>>(emptyList()) }
    var defaultWorkshop by remember { mutableStateOf<String?>(null) }
    // The start sheet reads its defaults (form, workshop) once, when it opens; it waits for the lists.
    var listsLoaded by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<SessionListRow>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var capped by remember { mutableStateOf(false) }
    // B28 and the shared filters (owner, 31 Oct 2026): kept per list across navigation and restarts.
    val (filters, setFilters) = rememberKeptFilters(v2, FilterStore.SESSIONS)
    var offline by remember { mutableStateOf(false) }
    var showDeleted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var inboxCount by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var recovered by remember { mutableStateOf<RecordingDraft?>(null) }
    var instrument by remember { mutableStateOf<Instrument?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var batteryAsk by remember { mutableStateOf(false) }
    var unsent by remember { mutableStateOf<List<String>>(emptyList()) }
    val names = remember(artisans) { artisans.associate { it.id to it.name } + v2.localArtisanNames() }
    val config by v2.config.collectAsState()

    LaunchedEffect(Unit) {
        runCatching { v2.refreshConfig() }
        forms = v2.formOptions()
        workshops = v2.workshops()
        defaultWorkshop = v2.defaultWorkshopId()
        listsLoaded = true
        // P13: a take cut short by a kill comes back first.
        val active = (RecordingService.state.value as? RecordingService.State.Recording)?.draft?.clientClipKey
        val drafts = RecordingDrafts.all(v2.filesDir)
        RecoveryScanner.empty(drafts, active).forEach { RecordingDrafts.delete(v2.filesDir, it, keepAudio = false) }
        recovered = RecoveryScanner.recoverable(drafts, active).firstOrNull()
        batteryAsk = shouldAskBattery(context)
    }

    suspend fun page(after: String?) = v2.sessionsPage(filters, after)

    LaunchedEffect(refresh, route, filters, tab) {
        if (route != QRoute.List || tab != QuestionnaireTab.SESSIONS) return@LaunchedEffect
        loading = true
        val units = v2.outbox.units()
        val search = filters.q
        val local = if (!filters.without("q").isEmpty) emptyList() else units.filter { it.sessionId == null }.mapNotNull { localRow(it, names) }
            .filter { search.isBlank() || it.title.contains(search, ignoreCase = true) || it.note.orEmpty().contains(search, ignoreCase = true) }
        val result = runCatching { page(null) }.getOrNull()
        rows = appendPage(local, result?.items.orEmpty())
        cursor = result?.nextCursor
        offline = result?.offline == true
        capped = false
        // The fix tray's way in: sessions with work this phone could not send (audit H20).
        unsent = units.filter { it.failedSteps.isNotEmpty() }.map { it.clientKey }
        inboxCount = runCatching { v2.api.inbox().counts.total }.getOrDefault(0)
        loading = false
    }

    suspend fun loadInstrument(qid: String): Instrument? = v2.form(qid)?.let(Instrument::from)

    suspend fun openUnit(clientKey: String) {
        val unit = v2.outbox.unit(clientKey)
        val qid = unit?.questionnaireId ?: return
        instrument = loadInstrument(qid)
        route = QRoute.Workspace(clientKey)
    }

    /** A server session (from the inbox, a merge, "Continue with…"): adopted into the outbox, then opened. */
    suspend fun openServer(session: QSession) {
        openUnit(v2.outbox.adoptSession(session, session.questionnaire?.id.orEmpty(), meId).clientKey)
    }

    suspend fun openServerId(sessionId: String) {
        runCatching { v2.api.sessionSummary(sessionId) }
            .onSuccess { openServer(it) }
            .onFailure { onError(QuestionnaireCopy.s("record.error")) }
    }

    // A session the top-bar scanner joined: opened once, then forgotten.
    val scannedSession by QuestionnaireV2Nav.openSession.collectAsState()
    LaunchedEffect(scannedSession) {
        val key = scannedSession ?: return@LaunchedEffect
        QuestionnaireV2Nav.openSession.value = null
        tab = QuestionnaireTab.SESSIONS
        openUnit(key)
    }
    // A session the server opened elsewhere ("Continue with…" on an artisan's record).
    val serverSession by QuestionnaireV2Nav.openServerSession.collectAsState()
    LaunchedEffect(serverSession) {
        val id = serverSession ?: return@LaunchedEffect
        QuestionnaireV2Nav.openServerSession.value = null
        tab = QuestionnaireTab.SESSIONS
        openServerId(id)
    }
    // A chip tapped on another screen: the recordings list, filtered by it.
    val chipToShow by QuestionnaireV2Nav.showRecordings.collectAsState()
    LaunchedEffect(chipToShow) {
        val f = chipToShow ?: return@LaunchedEffect
        QuestionnaireV2Nav.showRecordings.value = null
        tab = QuestionnaireTab.SESSIONS
        route = QRoute.Recordings(f)
    }
    // A form's "Start session" from elsewhere in the app.
    val formToStart by QuestionnaireV2Nav.startOnForm.collectAsState()
    LaunchedEffect(formToStart) {
        val id = formToStart ?: return@LaunchedEffect
        QuestionnaireV2Nav.startOnForm.value = null
        tab = QuestionnaireTab.SESSIONS; route = QRoute.List; prefillForm = id; starting = true
    }

    if (route != QRoute.List) BackHandler { route = QRoute.List; refresh++ }
    else if (onBack != null) BackHandler { onBack() }

    Box(modifier.fillMaxSize()) {
        when (val r = route) {
            QRoute.List -> Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = QuestionnaireCopy.s("home.back"))
                    }
                    SegmentedChoice(
                        label = "",
                        options = listOf(SelectOption(QuestionnaireTab.SESSIONS.name, QuestionnaireCopy.s("home.sessions")), SelectOption(QuestionnaireTab.FORMS.name, QuestionnaireCopy.s("home.forms"))),
                        selectedValue = tab.name
                    ) { tab = QuestionnaireTab.valueOf(it) }
                    if (config?.updateRequired(BuildConfig.VERSION_CODE) == true) UpdateBanner(onUpdateApp)
                }
                if (tab == QuestionnaireTab.FORMS) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    formsTab { questionnaireId -> prefillForm = questionnaireId; tab = QuestionnaireTab.SESSIONS; starting = true }
                    Box(Modifier.padding(bottom = 72.dp))
                } else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(QuestionnaireCopy.s("sessions.title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        BadgedBox(badge = { if (inboxCount > 0) Badge { Text("$inboxCount") } }) {
                            TextButton(onClick = { route = QRoute.Inbox }) { Text(QuestionnaireCopy.s("sessions.inbox")) }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = QuestionnaireCopy.s("session.more")) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                sessionsMenu().forEach { key ->
                                    DropdownMenuItem(text = { Text(if (key == "recordings") QuestionnaireCopy.t("recordingsTitle") else QuestionnaireCopy.s(key)) }, onClick = {
                                        menu = false
                                        route = when (key) {
                                            "coverage.title" -> QRoute.Coverage
                                            "recordings" -> QRoute.Recordings()
                                            else -> QRoute.Join
                                        }
                                    })
                                }
                            }
                        }
                    }
                    if (unsent.isNotEmpty()) UnsentBanner(unsent.size) { scope.launch { openUnit(unsent.first()) } }
                    FilterBar(
                        filters, setFilters, QuestionnaireCopy.t("filterSearchSessions"),
                        options = { facet, q, ids -> v2.filterOptions(facet, q, ids) }, offline = offline
                    )
                    // Deleted sessions are the content admins' (owner decision 3), with Restore.
                    if (mayDelete) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = showDeleted, onClick = { showDeleted = !showDeleted }, label = { Text(QuestionnaireCopy.s("sessions.deleted")) })
                    }
                    if (showDeleted) {
                        DeletedSessionsList(v2, onRestored = { onError(QuestionnaireCopy.s("session.restored")); refresh++ }, onError = onError)
                    } else {
                        if (loading || (starting && !listsLoaded)) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
                        if (!loading && rows.isEmpty()) Text(QuestionnaireCopy.s("sessions.empty"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
                        rows.forEach { row ->
                            Column(Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    val s = row.session
                                    if (s != null && v2.outbox.unit(row.key) == null) v2.outbox.adoptSession(s, s.questionnaire?.id.orEmpty(), meId)
                                    openUnit(row.key)
                                }
                            }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(row.title, style = MaterialTheme.typography.bodyLarge)
                                row.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
                                Text(row.meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body)
                                row.session?.chips?.let { chips ->
                                    RecordingChips(chips, session = true, filters = filters, onChip = { f, label -> setFilters(filters.applyChip(f, label)) })
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.field.hairline)
                        }
                        // Paging on the server (audit M23: only the first page ever showed).
                        if (cursor != null) PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("sessions.loadMore"), primary = true) {
                            if (loadingMore) return@PromptButton
                            loadingMore = true
                            scope.launch {
                                runCatching { page(cursor) }
                                    .onSuccess { next -> rows = appendPage(rows, next.items); cursor = next.nextCursor }
                                    .onFailure { onError(QuestionnaireCopy.s("error.needsSignal")) }
                                loadingMore = false
                            }
                        }))
                        if (capped) Text(QuestionnaireCopy.s("sessions.capped"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                    }
                    Box(Modifier.padding(bottom = 72.dp))
                }
            }
            is QRoute.Workspace -> SessionWorkspace(
                v2 = v2, clientKey = r.clientKey, instrument = instrument, artisans = artisans, meId = meId,
                canMakePrimary = canMakePrimary, isAdmin = isAdmin,
                onBack = { route = QRoute.List; refresh++ },
                onOpenSession = { session -> scope.launch { openServer(session) } },
                onOpenReadView = { id -> route = QRoute.ReadView(id) },
                onError = onError
            )
            is QRoute.ReadView -> SessionReadView(
                v2 = v2, sessionId = r.sessionId, canReview = canReview,
                loadInstrument = { loadInstrument(it) },
                onBack = { route = QRoute.List; refresh++ },
                onUpdate = { session -> scope.launch { openServer(session) } },
                onError = onError
            )
            QRoute.Inbox -> Column {
                TextButton(onClick = { route = QRoute.List; refresh++ }) { Text("‹ " + QuestionnaireCopy.s("sessions.title")) }
                InboxScreen(
                    v2, artisans, loadInstrument = { loadInstrument(it) }, onError = onError,
                    onOpenSession = { sessionId -> scope.launch { openServerId(sessionId) } }
                )
            }
            QRoute.Join -> Column {
                TextButton(onClick = { route = QRoute.List }) { Text("‹ " + QuestionnaireCopy.s("sessions.title")) }
                JoinScanner(v2, meId, onJoined = { key -> scope.launch { openUnit(key) } }, onError = onError)
            }
            is QRoute.Recordings -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                TextButton(onClick = { route = QRoute.List; refresh++ }) { Text("‹ " + QuestionnaireCopy.s("sessions.title")) }
                RecordingsList(
                    v2, initial = r.initial, onError = onError,
                    onOpenSession = { sessionId -> scope.launch { openServerId(sessionId) } }
                )
            }
            QRoute.Coverage -> Column {
                TextButton(onClick = { route = QRoute.List }) { Text("‹ " + QuestionnaireCopy.s("sessions.title")) }
                WorkshopCoverageScreen(v2, workshops, forms, loadInstrument = { loadInstrument(it) }, onError = onError)
            }
        }
        if (route == QRoute.List) {
            ExtendedFloatingActionButton(
                onClick = { if (tab == QuestionnaireTab.FORMS) onNewForm() else starting = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(QuestionnaireCopy.s(if (tab == QuestionnaireTab.FORMS) "home.newForm" else "sessions.new")) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }
    }

    if (starting && listsLoaded) SessionStartSheet(
        v2 = v2, workshops = workshops, forms = forms, artisans = artisans, meId = meId, meName = meName,
        prefillArtisanIds = prefill, prefillFormId = prefillForm, defaultWorkshopId = defaultWorkshop,
        onDismiss = { starting = false; prefill = emptyList(); prefillForm = null },
        onStarted = { key -> starting = false; prefill = emptyList(); prefillForm = null; scope.launch { openUnit(key) } },
        onJoined = { session ->
            starting = false
            prefill = emptyList()
            prefillForm = null
            scope.launch { openServer(session) }
        },
        onError = onError
    )
    recovered?.let { draft ->
        RecoveredRecordingPrompt(
            minutes = RecoveryScanner.minutes(draft), label = draft.label,
            onDiscard = {
                recovered = null
                scope.launch {
                    // Read again now: a take queued since the prompt opened is the outbox's upload (audit M23).
                    val current = RecordingDrafts.read(v2.filesDir, draft.clientClipKey)
                    val queued = v2.outbox.units().flatMap { it.steps }.mapNotNull { it.clip?.clientClipKey }.toSet()
                    if (current != null && RecoveryScanner.safeToDiscard(current, queued)) RecordingDrafts.delete(v2.filesDir, current, keepAudio = false)
                }
            },
            onKeep = {
                recovered = null
                scope.launch {
                    runCatching { v2.queueTake(draft) }.onFailure { onError(QuestionnaireCopy.s("error.recover")) }
                    v2.requestSync()
                }
            }
        )
    }
    if (batteryAsk && recovered == null) {
        QuestionnairePrompt(
            title = QuestionnaireCopy.s("battery.title"),
            body = QuestionnaireCopy.s("battery.reason"),
            buttons = listOf(PromptButton(QuestionnaireCopy.s("p5.ok"), primary = true) {
                batteryAsk = false
                markBatteryAsked(context)
                requestBatteryExemption(context)
            }),
            onDismiss = { batteryAsk = false; markBatteryAsked(context) }
        )
    }
}

/** Work this phone could not send (audit H20): opens the first such session, where the fix tray is. */
@Composable
private fun UnsentBanner(sessions: Int, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.field.surface100, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Text(
            QuestionnaireCopy.s("tray.title") + " (" + sessions + ")",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)
        )
    }
}

private const val PREFS = "questionnaire-v2"
private const val BATTERY_ASKED = "batteryAsked"

private fun shouldAskBattery(context: Context): Boolean {
    if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(BATTERY_ASKED, false)) return false
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return !power.isIgnoringBatteryOptimizations(context.packageName)
}

private fun markBatteryAsked(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(BATTERY_ASKED, true).apply()
}

/** The one-time request, with the reason already shown: "So long recordings aren't stopped." */
@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Arrivals from elsewhere in the app, held until the questionnaire home takes them. */
object QuestionnaireV2Nav {
    /** "Continue with…" from an artisan's record: the artisan to prefill in a new session. */
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** A session to open on arrival: the top-bar scanner joined it (its `clientKey`). */
    val openSession = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** A server session to open on arrival, by id ("Continue with…" opened it on the server). */
    val openServerSession = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** A form's "Start session" (A9, D3): the start sheet opens on this form. */
    val startOnForm = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** A chip tapped where there is no list to filter: the recordings list opens filtered by it. */
    val showRecordings = kotlinx.coroutines.flow.MutableStateFlow<QuestionnaireFilters?>(null)

    /** [RecordingChips]' usual `onChip` on a screen with no list of its own. */
    fun openRecordings(filter: ChipFilter, label: String) {
        showRecordings.value = QuestionnaireFilters().applyChip(filter, label)
    }
}

/** P14's Update: the web app's page that serves the current APK. */
fun openAppDownloadPage(context: Context) {
    val site = QuestionnairePortal.apiBaseUrl.substringBefore("/api")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
