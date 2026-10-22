package com.fieldrepository.app.ui.questionnaire

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.data.QSession
import com.fieldrepository.app.data.QSessionCreate
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.data.QuestionnaireDto
import com.fieldrepository.app.data.QuestionnaireV2
import com.fieldrepository.app.data.SessionUnit
import com.fieldrepository.app.data.StepKind
import com.fieldrepository.app.data.WorkshopDetailDto
import com.fieldrepository.app.data.outboxJson
import com.fieldrepository.app.recording.RecordingDraft
import com.fieldrepository.app.recording.RecordingDrafts
import com.fieldrepository.app.recording.RecordingService
import com.fieldrepository.app.recording.RecoveryScanner
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private sealed interface QRoute {
    data object List : QRoute
    data class Workspace(val clientKey: String) : QRoute
    data object Inbox : QRoute
    data object Join : QRoute
    data object Deleted : QRoute
    data object Coverage : QRoute
}

/** The home's two halves (UNIFY 4.2): Sessions | Forms, one FAB matching the tab. */
enum class QuestionnaireTab(val label: String) { SESSIONS(com.fieldrepository.app.data.FormsCopy.SESSIONS), FORMS(com.fieldrepository.app.data.FormsCopy.FORMS) }

/** The sessions list's overflow menu for this viewer (Join, Builder, Workshop coverage, Deleted sessions). */
internal fun sessionsMenu(canBuild: Boolean, user: com.fieldrepository.app.data.UserDto?): List<String> =
    sessionsMenu(canBuild) + listOfNotNull("sessions.coverage", "sessions.deleted".takeIf { com.fieldrepository.app.data.FormRights.mayDeleteSession(user) })

internal fun menuLabel(key: String): String = when (key) {
    "sessions.coverage" -> com.fieldrepository.app.data.FormsCopy.COVERAGE
    "sessions.deleted" -> com.fieldrepository.app.data.FormsCopy.DELETED_SESSIONS
    else -> QuestionnaireCopy.s(key)
}

/** One row of the sessions list: a server session, or one still only on this phone. */
data class SessionListRow(val key: String, val title: String, val note: String?, val meta: String, val local: Boolean, val session: QSession?)

internal fun localRow(unit: SessionUnit, names: Map<String, String>): SessionListRow? {
    val body = unit.steps.firstOrNull { it.kind == StepKind.SESSION }?.bodyJson
        ?.let { runCatching { outboxJson.decodeFromString(QSessionCreate.serializer(), it) }.getOrNull() } ?: return null
    val ids = body.participants.map { it.artisanId }
    val title = com.fieldrepository.app.data.Naming.sessionTitle(
        com.fieldrepository.app.data.Naming.SessionFields(
            sessionDate = runCatching { LocalDate.parse(body.sessionDate) }.getOrNull(), place = body.place,
            participantNames = ids.map { names[it].orEmpty() }
        )
    )
    return SessionListRow(unit.clientKey, title, body.note, listOf(QuestionnaireCopy.mode(ids.size), QuestionnaireCopy.s("upload.onDevice")).joinToString(" · "), true, null)
}

internal fun serverRow(s: QSession): SessionListRow = SessionListRow(
    key = s.clientKey ?: s.id,
    title = s.title,
    note = s.note,
    meta = listOfNotNull(
        s.sessionDate?.let { runCatching { QuestionnaireCopy.day(LocalDate.parse(it.take(10))) }.getOrNull() },
        QuestionnaireCopy.mode(s.participantCount.coerceAtLeast(s.participants.size)),
        s.questionnaire?.title,
        s.researchers.joinToString(", ") { QuestionnaireCopy.firstName(it.name) }.ifBlank { null },
        "${s.counts.sections} ${if (s.counts.sections == 1) "section" else "sections"} · ${s.counts.takes} ${if (s.counts.takes == 1) "take" else "takes"}",
        s.status.lowercase().replaceFirstChar { it.uppercase() }
    ).joinToString(" · "),
    local = false,
    session = s
)

/** What the Questionnaire entry draws (audit H15). */
enum class QuestionnaireEntry { LEGACY_FORM, V2_SESSIONS, V2_BUILDER }

/**
 * v2 off: the legacy form, unchanged. v2 on: the sessions; the builder only for a questionnaire
 * manager who opened it, and then the builder alone (never the legacy capture form, audit H15).
 */
fun questionnaireEntry(v2Enabled: Boolean, builderOpen: Boolean, canBuild: Boolean): QuestionnaireEntry = when {
    !v2Enabled -> QuestionnaireEntry.LEGACY_FORM
    builderOpen && canBuild -> QuestionnaireEntry.V2_BUILDER
    else -> QuestionnaireEntry.V2_SESSIONS
}

/** The sessions list's overflow menu: Join for everyone, Builder for questionnaire managers only. */
internal fun sessionsMenu(canBuild: Boolean): List<String> = listOfNotNull("sessions.join", "sessions.builder".takeIf { canBuild })

/** The next page joins the list; a session already listed (a local row, or an earlier page) is not repeated. */
internal fun appendPage(rows: List<SessionListRow>, page: List<QSession>): List<SessionListRow> =
    rows + page.map(::serverRow).filter { s -> rows.none { it.key == s.key } }

/**
 * Questionnaire v2 on Android (DESIGN §4.1): the sessions list (server-paged, searchable) with one
 * New session button, the start sheet, the workspace, the inbox and joining. The builder sits in the
 * overflow menu for questionnaire managers only (audit H15).
 */
@Composable
fun QuestionnaireV2Screen(
    repository: FieldRepository,
    artisans: List<ArtisanDto>,
    meId: String?,
    prefillArtisanIds: List<String> = emptyList(),
    /** Questionnaire managers only (audit H15): everyone else never sees Builder. */
    canBuild: Boolean = false,
    /** Professor and above: "Make main take" on a take. */
    canMakePrimary: Boolean = false,
    onOpenBuilder: () -> Unit,
    onUpdateApp: () -> Unit,
    onError: (String) -> Unit,
    /** The viewer: forms library rights, the answer lock, review (UNIFY U4). */
    user: com.fieldrepository.app.data.UserDto? = null,
    /** The workshop team, for "Add researcher". */
    loadTeam: suspend (workshopId: String) -> List<Pair<String, String>> = { emptyList() }
) {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf<QRoute>(QRoute.List) }
    // "Continue with…" / "Start session" from an artisan's record: the artisan is held HERE, because
    // the caller clears its request as soon as this screen takes it (audit M23: it used to be lost).
    var prefill by remember { mutableStateOf(prefillArtisanIds) }
    var starting by remember { mutableStateOf(prefillArtisanIds.isNotEmpty()) }
    LaunchedEffect(prefillArtisanIds) {
        if (prefillArtisanIds.isNotEmpty()) { prefill = prefillArtisanIds; starting = true; route = QRoute.List }
    }
    var questionnaires by remember { mutableStateOf<List<QuestionnaireDto>>(emptyList()) }
    var workshops by remember { mutableStateOf<List<WorkshopDetailDto>>(emptyList()) }
    // The start sheet reads its defaults (instrument, workshop) once, when it opens; it waits for both lists.
    var listsLoaded by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<SessionListRow>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var capped by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
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
    var tab by remember { mutableStateOf(QuestionnaireTab.SESSIONS) }
    var team by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    // A form another phone sent opens the Forms tab, where P17 asks.
    val receivedForm by com.fieldrepository.app.data.FormBundleInbox.pending.collectAsState()
    LaunchedEffect(receivedForm) { if (receivedForm != null) { tab = QuestionnaireTab.FORMS; route = QRoute.List } }
    val homeHeader: @Composable () -> Unit = {
        com.fieldrepository.app.ui.SegmentedChoice(
            label = QuestionnaireCopy.s("sessions.title") + " · " + com.fieldrepository.app.data.FormsCopy.FORMS,
            options = QuestionnaireTab.entries.map { com.fieldrepository.app.ui.SelectOption(it.name, it.label) },
            selectedValue = tab.name
        ) { tab = QuestionnaireTab.valueOf(it) }
    }

    LaunchedEffect(Unit) {
        runCatching { repository.questionnaires() }.onSuccess { questionnaires = it }
        runCatching { repository.workshops() }.onSuccess { workshops = it }
        listsLoaded = true
        // P13: a take cut short by a kill comes back first.
        val active = (RecordingService.state.value as? RecordingService.State.Recording)?.draft?.clientClipKey
        val drafts = RecordingDrafts.all(v2.filesDir)
        RecoveryScanner.empty(drafts, active).forEach { RecordingDrafts.delete(v2.filesDir, it, keepAudio = false) }
        recovered = RecoveryScanner.recoverable(drafts, active).firstOrNull()
        batteryAsk = shouldAskBattery(context)
    }
    LaunchedEffect(refresh, route, search) {
        if (route != QRoute.List) return@LaunchedEffect
        if (search.isNotEmpty()) delay(350)
        loading = true
        val units = v2.outbox.units()
        val local = units.filter { it.sessionId == null }.mapNotNull { localRow(it, names) }
            .filter { search.isBlank() || it.title.contains(search, ignoreCase = true) || it.note.orEmpty().contains(search, ignoreCase = true) }
        val page = runCatching { v2.api.sessions(search = search.trim().ifBlank { null }) }.getOrNull()
        rows = appendPage(local, page?.items.orEmpty())
        cursor = page?.nextCursor
        capped = page?.capped == true
        // The fix tray's way in: sessions with work this phone could not send (audit H20).
        unsent = units.filter { it.failedSteps.isNotEmpty() }.map { it.clientKey }
        inboxCount = runCatching { v2.api.inbox().counts.total }.getOrDefault(0)
        loading = false
    }

    suspend fun loadInstrument(qid: String): Instrument? = runCatching {
        Instrument.from(qid, questionnaires.firstOrNull { it.id == qid }?.title.orEmpty(), repository.questionnaireSections(qid))
    }.getOrNull()

    suspend fun openUnit(clientKey: String) {
        val unit = v2.outbox.unit(clientKey)
        val qid = unit?.questionnaireId ?: return
        instrument = loadInstrument(qid)
        team = unit?.workshopId?.let { runCatching { loadTeam(it) }.getOrNull() }.orEmpty()
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
        openUnit(key)
    }
    // A session the server opened elsewhere ("Continue with…" on an artisan's record).
    val serverSession by QuestionnaireV2Nav.openServerSession.collectAsState()
    LaunchedEffect(serverSession) {
        val id = serverSession ?: return@LaunchedEffect
        QuestionnaireV2Nav.openServerSession.value = null
        openServerId(id)
    }

    Box(Modifier.fillMaxSize()) {
        when (val r = route) {
            QRoute.List -> if (tab == QuestionnaireTab.FORMS) FormsScreen(
                user = user, workshops = workshops, header = homeHeader,
                onOpenSession = { id -> tab = QuestionnaireTab.SESSIONS; scope.launch { openServerId(id) } },
                onError = onError
            ) else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                homeHeader()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(QuestionnaireCopy.s("sessions.title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    BadgedBox(badge = { if (inboxCount > 0) Badge { Text("$inboxCount") } }) {
                        TextButton(onClick = { route = QRoute.Inbox }) { Text(QuestionnaireCopy.s("sessions.inbox")) }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = QuestionnaireCopy.s("session.more")) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            sessionsMenu(canBuild, user).forEach { key ->
                                DropdownMenuItem(text = { Text(menuLabel(key)) }, onClick = {
                                    menu = false
                                    when (key) {
                                        "sessions.builder" -> onOpenBuilder()
                                        "sessions.coverage" -> route = QRoute.Coverage
                                        "sessions.deleted" -> route = QRoute.Deleted
                                        else -> route = QRoute.Join
                                    }
                                })
                            }
                        }
                    }
                }
                if (config?.updateRequired(BuildConfig.VERSION_CODE) == true) UpdateBanner(onUpdateApp)
                if (unsent.isNotEmpty()) UnsentBanner(unsent.size) { scope.launch { openUnit(unsent.first()) } }
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text(QuestionnaireCopy.s("sessions.search")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
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
                    }
                    HorizontalDivider(color = MaterialTheme.field.hairline)
                }
                // Paging on the server (audit M23: only the first page ever showed).
                if (cursor != null) PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("sessions.loadMore"), primary = true) {
                    if (loadingMore) return@PromptButton
                    loadingMore = true
                    scope.launch {
                        runCatching { v2.api.sessions(search = search.trim().ifBlank { null }, cursor = cursor) }
                            .onSuccess { page -> rows = appendPage(rows, page.items); cursor = page.nextCursor; capped = page.capped }
                            .onFailure { onError(QuestionnaireCopy.s("error.needsSignal")) }
                        loadingMore = false
                    }
                }))
                if (capped) Text(QuestionnaireCopy.s("sessions.capped"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                Box(Modifier.padding(bottom = 72.dp))
            }
            is QRoute.Workspace -> SessionWorkspace(
                v2 = v2, clientKey = r.clientKey, instrument = instrument, artisans = artisans, meId = meId,
                canMakePrimary = canMakePrimary,
                onBack = { route = QRoute.List; refresh++ },
                onOpenSession = { session -> scope.launch { openServer(session) } },
                onError = onError,
                user = user, team = team
            )
            QRoute.Deleted -> DeletedSessionsScreen(v2, onBack = { route = QRoute.List; refresh++ }, onError = onError)
            QRoute.Coverage -> WorkshopCoverageScreen(
                v2, user, workshops, questionnaires.filter { it.isActive }.map { it.id to it.title },
                onBack = { route = QRoute.List }, onError = onError
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
        }
        if (route == QRoute.List && tab == QuestionnaireTab.SESSIONS) {
            ExtendedFloatingActionButton(
                onClick = { starting = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(QuestionnaireCopy.s("sessions.new")) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }
    }

    if (starting && listsLoaded) SessionStartSheet(
        v2 = v2, workshops = workshops, questionnaires = questionnaires, artisans = artisans, meId = meId,
        prefillArtisanIds = prefill,
        onDismiss = { starting = false; prefill = emptyList() },
        onStarted = { key -> starting = false; prefill = emptyList(); scope.launch { openUnit(key) } },
        onJoined = { session ->
            starting = false
            prefill = emptyList()
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
                    OutboxSyncWorker.schedule(context)
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

/** "Continue with…" from an artisan's record: the artisan to prefill in a new session. */
object QuestionnaireV2Nav {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** A session to open on arrival: the top-bar scanner joined it (its `clientKey`). */
    val openSession = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** A server session to open on arrival, by id ("Continue with…" opened it on the server). */
    val openServerSession = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

/** P14's Update: the web app's page that serves the current APK. */
fun openAppDownloadPage(context: Context) {
    val site = BuildConfig.DEFAULT_API_BASE_URL.substringBefore("/api")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
