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
import kotlinx.coroutines.launch
import java.time.LocalDate

private sealed interface QRoute {
    data object List : QRoute
    data class Workspace(val clientKey: String) : QRoute
    data object Inbox : QRoute
    data object Join : QRoute
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

/**
 * Questionnaire v2 on Android (DESIGN §4.1): the sessions list with one New session button, the
 * start sheet, the workspace, the inbox and joining. The builder moves to the overflow menu.
 */
@Composable
fun QuestionnaireV2Screen(
    repository: FieldRepository,
    artisans: List<ArtisanDto>,
    meId: String?,
    prefillArtisanIds: List<String> = emptyList(),
    onOpenBuilder: () -> Unit,
    onUpdateApp: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf<QRoute>(QRoute.List) }
    var starting by remember { mutableStateOf(prefillArtisanIds.isNotEmpty()) }
    var questionnaires by remember { mutableStateOf<List<QuestionnaireDto>>(emptyList()) }
    var workshops by remember { mutableStateOf<List<WorkshopDetailDto>>(emptyList()) }
    // The start sheet reads its defaults (instrument, workshop) once, when it opens; it waits for both lists.
    var listsLoaded by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<SessionListRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var inboxCount by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var recovered by remember { mutableStateOf<RecordingDraft?>(null) }
    var instrument by remember { mutableStateOf<Instrument?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var batteryAsk by remember { mutableStateOf(false) }
    val names = remember(artisans) { artisans.associate { it.id to it.name } }
    val config by v2.config.collectAsState()

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
    LaunchedEffect(refresh, route) {
        if (route != QRoute.List) return@LaunchedEffect
        loading = true
        val local = v2.outbox.units().filter { it.sessionId == null }.mapNotNull { localRow(it, names) }
        val server = runCatching { v2.api.sessions().items.map(::serverRow) }.getOrDefault(emptyList())
        rows = local + server.filter { s -> local.none { it.key == s.key } }
        inboxCount = runCatching { v2.api.inbox().counts.total }.getOrDefault(0)
        loading = false
    }

    suspend fun openUnit(clientKey: String) {
        val unit = v2.outbox.unit(clientKey)
        val qid = unit?.questionnaireId ?: return
        instrument = runCatching {
            Instrument.from(qid, questionnaires.firstOrNull { it.id == qid }?.title.orEmpty(), repository.questionnaireSections(qid))
        }.getOrNull()
        route = QRoute.Workspace(clientKey)
    }

    // A session the top-bar scanner joined: opened once, then forgotten.
    val scannedSession by QuestionnaireV2Nav.openSession.collectAsState()
    LaunchedEffect(scannedSession) {
        val key = scannedSession ?: return@LaunchedEffect
        QuestionnaireV2Nav.openSession.value = null
        openUnit(key)
    }

    Box(Modifier.fillMaxSize()) {
        when (val r = route) {
            QRoute.List -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(QuestionnaireCopy.s("sessions.title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    BadgedBox(badge = { if (inboxCount > 0) Badge { Text("$inboxCount") } }) {
                        TextButton(onClick = { route = QRoute.Inbox }) { Text(QuestionnaireCopy.s("sessions.inbox")) }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(QuestionnaireCopy.s("sessions.join")) }, onClick = { menu = false; route = QRoute.Join })
                            DropdownMenuItem(text = { Text(QuestionnaireCopy.s("sessions.builder")) }, onClick = { menu = false; onOpenBuilder() })
                        }
                    }
                }
                val minVersion = config?.minAppVersionForCreate
                if (minVersion != null && versionBelow(BuildConfig.VERSION_NAME, minVersion)) UpdateBanner(onUpdateApp)
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
                Box(Modifier.padding(bottom = 72.dp))
            }
            is QRoute.Workspace -> SessionWorkspace(
                v2 = v2, clientKey = r.clientKey, instrument = instrument, artisans = artisans, meId = meId,
                onBack = { route = QRoute.List; refresh++ }, onError = onError
            )
            QRoute.Inbox -> Column {
                TextButton(onClick = { route = QRoute.List }) { Text("‹ " + QuestionnaireCopy.s("sessions.title")) }
                InboxScreen(v2, onError, onOpenSession = { sessionId ->
                    scope.launch {
                        runCatching { v2.api.sessionSummary(sessionId) }
                            .onSuccess { s -> openUnit(v2.outbox.adoptSession(s, s.questionnaire?.id.orEmpty(), meId).clientKey) }
                            .onFailure { onError(QuestionnaireCopy.s("record.error")) }
                    }
                })
            }
            QRoute.Join -> Column {
                TextButton(onClick = { route = QRoute.List }) { Text("‹ " + QuestionnaireCopy.s("sessions.title")) }
                JoinScanner(v2, meId, onJoined = { key -> scope.launch { openUnit(key) } }, onError = onError)
            }
        }
        if (route == QRoute.List) {
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
        prefillArtisanIds = prefillArtisanIds,
        onDismiss = { starting = false },
        onStarted = { key -> starting = false; scope.launch { openUnit(key) } },
        onJoined = { session ->
            starting = false
            scope.launch {
                val unit = v2.outbox.adoptSession(session, session.questionnaire?.id.orEmpty(), meId)
                openUnit(unit.clientKey)
            }
        },
        onError = onError
    )
    recovered?.let { draft ->
        RecoveredRecordingPrompt(
            minutes = RecoveryScanner.minutes(draft), label = draft.label,
            onDiscard = { RecordingDrafts.delete(v2.filesDir, draft, keepAudio = false); recovered = null },
            onKeep = {
                recovered = null
                scope.launch {
                    runCatching { v2.queueTake(draft) }.onFailure { onError("Couldn't recover that recording.") }
                    com.fieldrepository.app.data.OutboxSyncWorker.schedule(context)
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

/** "0.0.13" < "0.0.14". Non-numeric parts compare as 0. */
internal fun versionBelow(current: String, minimum: String): Boolean {
    val a = current.split('.').map { it.toIntOrNull() ?: 0 }
    val b = minimum.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
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
}

/** P14's Update: the web app's page that serves the current APK. */
fun openAppDownloadPage(context: Context) {
    val site = BuildConfig.DEFAULT_API_BASE_URL.substringBefore("/api")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
