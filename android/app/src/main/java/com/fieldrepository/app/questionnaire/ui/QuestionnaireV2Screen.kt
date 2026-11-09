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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.ScrollState
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
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
import com.fieldrepository.app.questionnaire.data.KeptPages
import com.fieldrepository.app.questionnaire.data.ListPaging
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
    data class Recordings(val initial: QuestionnaireFilters? = null) : QRoute
}

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
        s.crafts.joinToString(", ").ifBlank { null },
        s.researchers.joinToString(", ") { QuestionnaireCopy.firstName(it.name) }.ifBlank { null },
        "${s.counts.sections} ${if (s.counts.sections == 1) "section" else "sections"} · ${s.counts.takes} ${if (s.counts.takes == 1) "take" else "takes"}",
        s.status.lowercase().replaceFirstChar { it.uppercase() }
    ).joinToString(" · "),
    local = false,
    session = s
)

internal fun sessionsMenu(): List<String> = listOf("recordings", "sessions.join", "coverage.title")

internal fun appendPage(rows: List<SessionListRow>, page: List<QSession>): List<SessionListRow> =
    rows + page.map(::serverRow).filter { s -> rows.none { it.key == s.key } }

enum class QuestionnaireTab { SESSIONS, FORMS }

internal val HOME_FAB_MARGIN = 16.dp

internal fun homeFabClearance(fabHeight: Dp): Dp = fabHeight.coerceAtLeast(56.dp) + HOME_FAB_MARGIN * 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionnaireHome(
    artisans: List<QArtisanOption>,
    meId: String?,
    meName: String? = null,
    prefillArtisanIds: List<String> = emptyList(),
    canMakePrimary: Boolean = false,
    isAdmin: Boolean = false,
    canReview: Boolean = false,
    formsTab: @Composable (onStartSession: ((questionnaireId: String) -> Unit)?) -> Unit,
    onNewForm: () -> Unit,
    onUpdateApp: () -> Unit,
    onError: (String) -> Unit,
    initialTab: QuestionnaireTab = QuestionnaireTab.SESSIONS,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onLeaveSession: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val scope = rememberCoroutineScope()
    val mayDelete = LocalMayDelete.current
    var tab by rememberKept("q.tab") { initialTab }
    var route by remember { mutableStateOf<QRoute>(QRoute.List) }
    val origin = remember { SessionOrigin<QRoute>(QRoute.List) }
    val homeMemory = LocalScreenMemory.current ?: remember { ScreenMemory() }
    val sessionsScroll = rememberKeptValue("q.sessions.scroll") { ScrollState(0) }
    val formsScroll = rememberKeptValue("q.forms.scroll") { ScrollState(0) }
    var prefill by remember { mutableStateOf(prefillArtisanIds) }
    var prefillForm by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(prefillArtisanIds.isNotEmpty()) }
    LaunchedEffect(prefillArtisanIds) {
        if (prefillArtisanIds.isNotEmpty()) { prefill = prefillArtisanIds; starting = true; route = QRoute.List; tab = QuestionnaireTab.SESSIONS }
    }
    var forms by remember { mutableStateOf<List<QFormOption>>(emptyList()) }
    var workshops by remember { mutableStateOf<List<QWorkshopOption>>(emptyList()) }
    var defaultWorkshop by remember { mutableStateOf<String?>(null) }
    var listsLoaded by remember { mutableStateOf(false) }
    var kept by rememberKept("q.sessions.pages") { KeptPages<SessionListRow>() }
    val rows = kept.rows
    val cursor = kept.cursor
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
    val density = LocalDensity.current
    var fabHeightPx by remember { mutableIntStateOf(0) }
    val fabClearance = homeFabClearance(with(density) { fabHeightPx.toDp() })

    LaunchedEffect(Unit) {
        runCatching { v2.refreshConfig() }
        forms = v2.formOptions()
        workshops = v2.workshops()
        defaultWorkshop = v2.defaultWorkshopId()
        listsLoaded = true
        val active = (RecordingService.state.value as? RecordingService.State.Recording)?.draft?.clientClipKey
        val drafts = RecordingDrafts.all(v2.filesDir)
        RecoveryScanner.empty(drafts, active).forEach { RecordingDrafts.delete(v2.filesDir, it, keepAudio = false) }
        recovered = RecoveryScanner.recoverable(drafts, active).firstOrNull()
    }
    LaunchedEffect(Unit) { batteryAsk = shouldAskBattery(context) }

    suspend fun page(after: String?) = v2.sessionsPage(filters, after)

    LaunchedEffect(refresh, route, filters, tab) {
        if (route != QRoute.List || tab != QuestionnaireTab.SESSIONS) return@LaunchedEffect
        loading = true
        val units = v2.outbox.units()
        val search = filters.q
        val local = if (!filters.without("q").isEmpty) emptyList() else units.filter { it.sessionId == null }.mapNotNull { localRow(it, names) }
            .filter { search.isBlank() || it.title.contains(search, ignoreCase = true) || it.note.orEmpty().contains(search, ignoreCase = true) }
        val result = runCatching { page(null) }.getOrNull()
        kept = ListPaging.arrive(kept, filters, appendPage(local, result?.items.orEmpty()), result?.nextCursor) { it.key }
        offline = result?.offline == true
        unsent = units.filter { it.failedSteps.isNotEmpty() }.map { it.clientKey }
        inboxCount = runCatching { v2.api.inbox().counts.total }.getOrDefault(0)
        loading = false
    }

    suspend fun loadInstrument(qid: String): Instrument? = v2.form(qid)?.let(Instrument::from)

    fun noteOrigin() = origin.note(route, tab, inSession = route is QRoute.Workspace || route is QRoute.ReadView)

    fun backFromSession() {
        when (val back = origin.back()) {
            SessionOrigin.Back.Host -> {
                route = QRoute.List
                onLeaveSession?.invoke()
            }
            is SessionOrigin.Back.To -> {
                route = back.route
                back.tab?.let { tab = it }
            }
        }
        refresh++
    }

    suspend fun openUnit(clientKey: String) {
        val unit = v2.outbox.unit(clientKey)
        val qid = unit?.questionnaireId ?: return
        instrument = loadInstrument(qid)
        noteOrigin()
        route = QRoute.Workspace(clientKey)
    }

    suspend fun openServer(session: QSession) {
        openUnit(v2.outbox.adoptSession(session, session.questionnaire?.id.orEmpty(), meId).clientKey)
    }

    suspend fun openServerId(sessionId: String) {
        runCatching { v2.api.sessionSummary(sessionId) }
            .onSuccess { openServer(it) }
            .onFailure { onError(QuestionnaireCopy.s("record.error")) }
    }

    val scannedSession by QuestionnaireV2Nav.openSession.collectAsState()
    LaunchedEffect(scannedSession) {
        val key = scannedSession ?: return@LaunchedEffect
        QuestionnaireV2Nav.openSession.value = null
        tab = QuestionnaireTab.SESSIONS
        openUnit(key)
    }
    val serverSession by QuestionnaireV2Nav.openServerSession.collectAsState()
    LaunchedEffect(serverSession) {
        val id = serverSession ?: return@LaunchedEffect
        QuestionnaireV2Nav.openServerSession.value = null
        noteOrigin()
        if (onLeaveSession != null) origin.noteHost()
        tab = QuestionnaireTab.SESSIONS
        openServerId(id)
    }
    val chipToShow by QuestionnaireV2Nav.showRecordings.collectAsState()
    LaunchedEffect(chipToShow) {
        val f = chipToShow ?: return@LaunchedEffect
        QuestionnaireV2Nav.showRecordings.value = null
        tab = QuestionnaireTab.SESSIONS
        homeMemory.forgetChild("recordings")
        route = QRoute.Recordings(f)
    }
    val formToStart by QuestionnaireV2Nav.startOnForm.collectAsState()
    LaunchedEffect(formToStart) {
        val id = formToStart ?: return@LaunchedEffect
        QuestionnaireV2Nav.startOnForm.value = null
        tab = QuestionnaireTab.SESSIONS; route = QRoute.List; prefillForm = id; starting = true
    }

    if (route is QRoute.Workspace || route is QRoute.ReadView) BackHandler { backFromSession() }
    else if (route != QRoute.List) BackHandler { route = QRoute.List; refresh++ }
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
                if (tab == QuestionnaireTab.FORMS) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(formsScroll).padding(16.dp).testTag("questionnaire.forms")) {
                    CompositionLocalProvider(LocalScreenMemory provides homeMemory.child("forms"), LocalFormsScroll provides formsScroll) {
                        formsTab { questionnaireId -> prefillForm = questionnaireId; tab = QuestionnaireTab.SESSIONS; starting = true }
                    }
                    Spacer(Modifier.height(fabClearance).testTag("questionnaire.listEnd"))
                } else Column(Modifier.fillMaxWidth().verticalScroll(sessionsScroll).padding(16.dp).testTag("questionnaire.sessions"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                            "recordings" -> QRoute.Recordings().also { homeMemory.forgetChild("recordings") }
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
                        if (cursor != null) PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("sessions.loadMore"), primary = true) {
                            if (loadingMore) return@PromptButton
                            loadingMore = true
                            scope.launch {
                                runCatching { page(cursor) }
                                    .onSuccess { next -> kept = ListPaging.more(kept, next.items.map(::serverRow), next.nextCursor) { it.key } }
                                    .onFailure { onError(QuestionnaireCopy.s("error.needsSignal")) }
                                loadingMore = false
                            }
                        }))
                    }
                    Spacer(Modifier.height(fabClearance).testTag("questionnaire.listEnd"))
                }
            }
            is QRoute.Workspace -> SessionWorkspace(
                v2 = v2, clientKey = r.clientKey, instrument = instrument, artisans = artisans, meId = meId,
                canMakePrimary = canMakePrimary, isAdmin = isAdmin,
                onBack = { backFromSession() },
                onOpenSession = { session -> scope.launch { openServer(session) } },
                onOpenReadView = { id -> noteOrigin(); route = QRoute.ReadView(id) },
                onError = onError
            )
            is QRoute.ReadView -> SessionReadView(
                v2 = v2, sessionId = r.sessionId, canReview = canReview,
                loadInstrument = { loadInstrument(it) },
                onBack = { backFromSession() },
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
            is QRoute.Recordings -> CompositionLocalProvider(LocalScreenMemory provides homeMemory.child("recordings")) {
                Column(Modifier.verticalScroll(rememberKeptValue("scroll") { ScrollState(0) }).padding(16.dp).testTag("questionnaire.recordings")) {
                    TextButton(onClick = { route = QRoute.List; refresh++ }) { Text("‹ " + QuestionnaireCopy.s("sessions.title")) }
                    RecordingsList(
                        v2, initial = r.initial, onError = onError,
                        onOpenSession = { sessionId -> scope.launch { openServerId(sessionId) } }
                    )
                }
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
                modifier = Modifier.align(Alignment.BottomCenter).padding(HOME_FAB_MARGIN)
                    .onSizeChanged { fabHeightPx = it.height }.testTag("questionnaire.fab")
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
            buttons = listOf(
                PromptButton(QuestionnaireCopy.s("battery.notNow")) {
                    batteryAsk = false
                    markBatteryAsked(context)
                },
                PromptButton(QuestionnaireCopy.s("p5.ok"), primary = true) {
                    batteryAsk = false
                    markBatteryAsked(context)
                    requestBatteryExemption(context)
                },
            ),
            onDismiss = { batteryAsk = false; markBatteryAsked(context) }
        )
    }
}

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

internal fun shouldAskBattery(context: Context): Boolean {
    if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(BATTERY_ASKED, false)) return false
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return !power.isIgnoringBatteryOptimizations(context.packageName)
}

private fun markBatteryAsked(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(BATTERY_ASKED, true).apply()
}

@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    runCatching {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        context.startActivity(if (context is android.app.Activity) ask else ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

object QuestionnaireV2Nav {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val openSession = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val openServerSession = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val startOnForm = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val showRecordings = kotlinx.coroutines.flow.MutableStateFlow<QuestionnaireFilters?>(null)

    fun openRecordings(filter: ChipFilter, label: String) {
        showRecordings.value = QuestionnaireFilters().applyChip(filter, label)
    }
}

fun openAppDownloadPage(context: Context) {
    val site = QuestionnairePortal.apiBaseUrl.substringBefore("/api")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
