package com.fieldrepository.app.ui.questionnaire

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.ClientClock
import com.fieldrepository.app.data.CoverageCache
import com.fieldrepository.app.data.JoinQr
import com.fieldrepository.app.data.JoinQrPayload
import com.fieldrepository.app.data.MissingSessionUnit
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.data.OverlayTake
import com.fieldrepository.app.data.ParticipantChange
import com.fieldrepository.app.data.QAnswersPut
import com.fieldrepository.app.data.QCompleteSubject
import com.fieldrepository.app.data.QCoverageCheckRequest
import com.fieldrepository.app.data.QCoverageCheckResponse
import com.fieldrepository.app.data.QErrorBody
import com.fieldrepository.app.data.QErrorCodes
import com.fieldrepository.app.data.QMakePrimaryRequest
import com.fieldrepository.app.data.QProposal
import com.fieldrepository.app.data.QRecording
import com.fieldrepository.app.data.QSession
import com.fieldrepository.app.data.QSessionCreate
import com.fieldrepository.app.data.QSessionDetail
import com.fieldrepository.app.data.QSnapshotTake
import com.fieldrepository.app.data.QTargetRef
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.data.QuestionnaireV2
import com.fieldrepository.app.data.SessionOutbox
import com.fieldrepository.app.data.SessionUnit
import com.fieldrepository.app.data.StepKind
import com.fieldrepository.app.data.SubjectRole
import com.fieldrepository.app.data.outboxDiscardSentence
import com.fieldrepository.app.data.outboxJson
import com.fieldrepository.app.recording.RecordingDraft
import com.fieldrepository.app.recording.RecordingDrafts
import com.fieldrepository.app.recording.RecordingService
import com.fieldrepository.app.ui.AppToggle
import com.fieldrepository.app.ui.LocalMayDelete
import com.fieldrepository.app.ui.MICROPHONE_PERMISSION
import com.fieldrepository.app.ui.MICROPHONE_REFUSED_SENTENCE
import com.fieldrepository.app.ui.SearchableMultiSelectField
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SearchableSelectSheet
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import com.fieldrepository.app.ui.rememberPermissionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A pending Record request, held while P2 or P3 is on screen. */
private data class RecordRequest(val target: QTargetRef?, val label: String, val code: String, val pickedFile: PickedFile? = null)

private data class PickedFile(val path: String, val sha256: String, val size: Long, val mime: String, val ext: String, val metadataTime: Instant?, val durationMs: Long)

/** P8 waiting for the researcher: the answer to save, once they confirm the recorded one is replaced. */
private data class PendingAnswer(val question: InstrumentQuestion, val text: String, val forIds: Set<String>, val replacing: String)

/** A tray Discard waiting for its confirmation. */
private data class PendingDiscard(val row: TrayRow)

/**
 * The session workspace (DESIGN §4.3): header, section strip, coverage grid, the open section with
 * a Record control per question, the recorder, the take sheet and the fix tray. Works offline from
 * the session outbox and the coverage snapshot; online it adds the server's session and its takes.
 *
 * EVERY OUTBOX CALL GOES THROUGH [withUnit] (audit C2, 21 Oct 2026): a unit that is missing is put
 * back from the copy this screen holds, and any other failure is said, never a crash.
 */
@Composable
fun SessionWorkspace(
    v2: QuestionnaireV2,
    clientKey: String,
    instrument: Instrument?,
    artisans: List<ArtisanDto>,
    meId: String?,
    canMakePrimary: Boolean = false,
    onBack: () -> Unit,
    onOpenSession: (QSession) -> Unit = {},
    onError: (String) -> Unit,
    /** The viewer: the answer lock, review, researchers (UNIFY U4). */
    user: com.fieldrepository.app.data.UserDto? = null,
    /** Who may be added as a researcher: the session's workshop team (user id to name). */
    team: List<Pair<String, String>> = emptyList()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mayDelete = LocalMayDelete.current
    val names = remember(artisans) { artisans.associate { it.id to it.name } + v2.localArtisanNames() }
    var unit by remember { mutableStateOf<SessionUnit?>(null) }
    var detail by remember { mutableStateOf<QSessionDetail?>(null) }
    var overlay by remember { mutableStateOf<List<OverlayTake>>(emptyList()) }
    var snapshotTakes by remember { mutableStateOf<List<QSnapshotTake>>(emptyList()) }
    var sectionId by remember { mutableStateOf<String?>(null) }
    var typeAnswers by remember { mutableStateOf(false) }
    val present = remember { mutableStateMapOf<String, Boolean>() }
    var p2 by remember { mutableStateOf<Pair<RecordRequest, P2Model>?>(null) }
    var p3 by remember { mutableStateOf<RecordRequest?>(null) }
    var p5 by remember { mutableStateOf<String?>(null) }
    var p7 by remember { mutableStateOf<Pair<RecordRequest, P2Model>?>(null) }
    var p8 by remember { mutableStateOf<PendingAnswer?>(null) }
    var takeSheet by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var playing by remember { mutableStateOf<Uri?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var addingArtisan by remember { mutableStateOf(false) }
    var differentFor by remember { mutableStateOf<RecordRequest?>(null) }
    var removeTake by remember { mutableStateOf<TakeRow?>(null) }
    var pendingPick by remember { mutableStateOf<RecordRequest?>(null) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var continuing by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var removingArtisan by remember { mutableStateOf(false) }
    var p10 by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var consentFor by remember { mutableStateOf<String?>(null) }
    var proposals by remember { mutableStateOf<Pair<String, List<QProposal>>?>(null) }
    var discard by remember { mutableStateOf<PendingDiscard?>(null) }
    var deleteHint by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var researchersOpen by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<com.fieldrepository.app.data.QRevision>?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var takeHistory by remember { mutableStateOf<List<com.fieldrepository.app.data.QRecordingEvent>?>(null) }
    var takeHistoryOpen by remember { mutableStateOf(false) }
    var editingGuest by remember { mutableStateOf<GuestModel?>(null) }
    var requiredLeft by remember { mutableIntStateOf(0) }
    // Takes removed in this visit, so "Show removed" can restore them (the session lists live takes only).
    val removedHere = remember { mutableStateListOf<QRecording>() }
    val lastAnswered = remember { mutableStateMapOf<String, Set<String>>() }
    val recorderState by RecordingService.state.collectAsState()
    val micGate = rememberPermissionGate { onError(it) }
    val paused = v2.config.collectAsState().value?.transcriptionPaused ?: true

    suspend fun reload() {
        val u = v2.outbox.unit(clientKey) ?: unit
        unit = u
        val qid = u?.questionnaireId ?: instrument?.id ?: return
        val workshopId = u?.workshopId
        val sessionId = u?.sessionId ?: detail?.id
        if (sessionId != null) runCatching { v2.api.session(sessionId) }.onSuccess { detail = it }
        runCatching {
            val since = v2.coverage.get(qid, workshopId)?.snapshot?.asOfSeq
            v2.coverage.apply(qid, workshopId, v2.api.snapshot(qid, workshopId, since), delta = since != null)
        }
        snapshotTakes = v2.coverage.get(qid, workshopId)?.snapshot?.takes.orEmpty()
        overlay = CoverageCache.overlayFrom(v2.outbox.units(), qid)
    }

    /**
     * Runs an outbox change for this session (audit C2). A unit that is gone is put back from the
     * copy this screen holds ([SessionOutbox.reopen]) and the change runs again; anything else is
     * said in plain words instead of ending the app. Then the next sync is asked for.
     */
    fun withUnit(work: suspend (key: String) -> Unit) {
        scope.launch {
            val ok = try {
                work(clientKey); true
            } catch (missing: MissingSessionUnit) {
                val known = unit
                if (known == null) false else runCatching { v2.outbox.reopen(known); work(clientKey) }.isSuccess
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (other: Exception) {
                false
            }
            if (!ok) onError(QuestionnaireCopy.s("error.saveOnPhone"))
            OutboxSyncWorker.schedule(context)
            reload()
        }
    }

    /** An online-only change: a signal is needed, a refusal is said in the server's words. */
    fun online(work: suspend () -> Unit) {
        scope.launch {
            runCatching { work() }.onFailure { onError(it.userSentence()) }
            reload()
        }
    }

    LaunchedEffect(clientKey) {
        while (true) {
            reload()
            delay((v2.config.value?.snapshotPollSeconds ?: 60) * 1000L)
        }
    }
    LaunchedEffect(recorderState) {
        val state = recorderState
        if (state is RecordingService.State.Finished && state.draft.sessionKey == clientKey) {
            // Never silent (audit M23 and C2): a recorder that could not start, a take that could not queue.
            when (state.reason) {
                RecordingService.REASON_START_FAILED -> { onError(QuestionnaireCopy.s("error.recorderStart")); RecordingService.clearFinished() }
                RecordingService.REASON_QUEUE_FAILED -> { onError(QuestionnaireCopy.s("error.queueTake")); RecordingService.clearFinished() }
            }
        }
        if (state is RecordingService.State.Finished) { delay(600); reload() }
    }
    // Leaving the workspace (Back, dispose) finalises a running take; it is never dropped.
    DisposableEffect(clientKey) {
        onDispose {
            val s = RecordingService.state.value
            if (s is RecordingService.State.Recording && s.draft.sessionKey == clientKey) RecordingService.stop(context, "leave")
            // A group take waits for its "Answered by" chips before it uploads; leaving sends it as it is.
            OutboxSyncWorker.schedule(context)
        }
    }

    val frozen = remember(unit) {
        unit?.steps?.firstOrNull { it.kind == StepKind.SESSION }?.bodyJson?.let { runCatching { outboxJson.decodeFromString(QSessionCreate.serializer(), it) }.getOrNull() }
    }
    val participantIds: List<String> = remember(unit, detail) {
        val base = detail?.participants?.map { it.artisanId } ?: frozen?.participants?.map { unit!!.resolve(it.artisanId) }.orEmpty()
        val added = unit?.steps?.filter { it.kind == StepKind.PARTICIPANT && it.participant?.action == ParticipantChange.ADD }?.map { unit!!.resolve(it.participant!!.artisanId) }.orEmpty()
        (base + added).distinct()
    }
    val participantName: (String) -> String = { id -> detail?.participants?.firstOrNull { it.artisanId == id }?.name?.ifBlank { null } ?: names[id].orEmpty() }
    val headerParticipants = participantIds.map { HeaderParticipant(it, participantName(it), present[it] ?: (detail?.participants?.firstOrNull { p -> p.artisanId == it }?.leftAt == null)) }
    val roster = headerParticipants.filter { it.present }
    val locked = detail?.state == "CLOSED"
    val sessionTakes = CoverageGridModel.takesOf(detail?.recordings.orEmpty())
    val known = CoverageGridModel.merge(snapshotTakes, sessionTakes)
    val answers = detail?.answers.orEmpty()
    val title = detail?.title?.ifBlank { null } ?: frozen?.let { f ->
        Naming.sessionTitle(Naming.SessionFields(
            sessionDate = runCatching { LocalDate.parse(f.sessionDate) }.getOrNull(), place = f.place,
            participantCrafts = participantIds.map { id -> artisans.firstOrNull { it.id == id }?.craft?.name },
            participantNames = participantIds.map(participantName)
        ))
    }.orEmpty()
    val joinQr = detail?.joinQr ?: unit?.let { u ->
        frozen?.let { f -> JoinQr.encode(JoinQrPayload(clientKey = u.clientKey, questionnaireId = u.questionnaireId, workshopId = u.workshopId, sessionDate = f.sessionDate, participants = participantIds)) }
    }
    // Uploading means queued and not refused; a refused take waits in the tray (audit H20).
    val pendingCount = unit?.uploadingClips?.size ?: 0
    val trayRows = unit?.let { u -> OutboxTrayModel.rows(u, participantName) { lineage -> instrument?.question(lineage)?.displayCode ?: lineage } }.orEmpty()
    val serverSessionId = unit?.sessionId ?: detail?.id
    // The one answer-lock rule (U9): the server's per-item ANSWER_LOCKED, and the author when it says.
    val isLead = detail?.researchers.orEmpty().any { it.userId == meId && it.role == "LEAD" }
    val contentAdmin = com.fieldrepository.app.ui.FieldPermissions.isContentAdmin(user)
    val serverLocked = unit?.failedSteps.orEmpty().flatMap { it.conflicts }.filter { com.fieldrepository.app.data.AnswerLock.isLockedResult(it) }
    val lockLine: (String, Set<String>) -> String? = { lineage, ids ->
        ids.firstNotNullOfOrNull { id ->
            val a = AnswerModel.current(answers, id, lineage)
            when {
                a != null && com.fieldrepository.app.data.AnswerLock.locked(a.text, a.origin, a.actorId, meId, isLead, contentAdmin) -> com.fieldrepository.app.data.AnswerLock.message(a.actorName)
                serverLocked.any { it.questionLineageId == lineage && it.artisanId == id } -> com.fieldrepository.app.data.AnswerLock.message(serverLocked.first { it.questionLineageId == lineage && it.artisanId == id }.current?.actorName)
                else -> null
            }
        }
    }
    val guests = GuestRoster.of(unit, detail)

    fun startRecording(req: RecordRequest, reasons: Map<String, String>, acked: List<String>, continuationOf: String? = null, replaces: String? = null) {
        val u = unit ?: return
        val subjects = roster.map { QCompleteSubject(it.artisanId, SubjectRole.ANSWERED.wire, named = false, repeatReason = reasons[it.artisanId]) }
        val key = SessionOutbox.newKey()
        val picked = req.pickedFile
        if (picked != null) {
            // A picked file: its metadata time, else the session date at 12:00 IST (§3.9).
            val sessionNoon = (frozen?.sessionDate ?: detail?.sessionDate)?.let { runCatching { LocalDate.parse(it.take(10)).atTime(12, 0).atZone(Naming.IST).toInstant() }.getOrNull() }
            val draft = RecordingDraft(
                clientClipKey = key, sessionKey = u.clientKey, questionnaireId = u.questionnaireId, adtsPath = picked.path,
                deviceCapturedAt = (picked.metadataTime ?: sessionNoon ?: Instant.now()).toString(),
                clockOffsetMs = 0, capturedAtSource = if (picked.metadataTime != null) "FILE_METADATA" else "SESSION_DATE",
                targets = listOfNotNull(req.target), subjects = subjects, label = req.label,
                contentType = picked.mime, extension = picked.ext, continuationOfId = continuationOf, replacesRecordingId = replaces,
                ackedRecordingIds = acked, finished = true, m4aPath = picked.path, durationMs = picked.durationMs,
                sha256 = picked.sha256, sizeBytes = picked.size
            )
            withUnit {
                RecordingDrafts.write(v2.filesDir, draft)
                v2.queueTake(draft)
            }
            return
        }
        micGate.run(MICROPHONE_PERMISSION, MICROPHONE_REFUSED_SENTENCE) {
            val draft = RecordingDraft(
                clientClipKey = key, sessionKey = u.clientKey, questionnaireId = u.questionnaireId,
                adtsPath = File(RecordingDrafts.dir(v2.filesDir), "$key.aac").absolutePath,
                deviceCapturedAt = Instant.now().toString(), clockOffsetMs = ClientClock.offsetMs(), clockOffsetMeasuredAt = ClientClock.measuredAt(),
                capturedAtSource = ClientClock.captureSource(), targets = listOfNotNull(req.target), subjects = subjects,
                label = req.label, continuationOfId = continuationOf, replacesRecordingId = replaces, ackedRecordingIds = acked
            )
            // Written BEFORE the recorder starts, so a kill mid-take still knows what it was (§6.1.2).
            RecordingDrafts.write(v2.filesDir, draft)
            RecordingService.start(context, draft)
        }
    }

    /** P5 names the session that already holds the file (audit M23: it named this one, today). */
    suspend fun p5Body(identicalSessionId: String?, sha: String?): String {
        val elsewhere = identicalSessionId?.let { id -> runCatching { v2.api.sessionSummary(id) }.getOrNull() }
        if (elsewhere != null) {
            val name = elsewhere.participants.firstOrNull()?.name.orEmpty()
            val day = elsewhere.sessionDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: LocalDate.now(Naming.IST)
            return QuestionnaireCopy.p5Body(QuestionnaireCopy.firstName(name), day)
        }
        // On this phone: the queued session the identical file waits in.
        val owner = sha?.let { s -> v2.outbox.units().firstOrNull { un -> un.steps.any { it.clip?.contentSha256 == s } } }
        val body = owner?.steps?.firstOrNull { it.kind == StepKind.SESSION }?.bodyJson?.let { runCatching { outboxJson.decodeFromString(QSessionCreate.serializer(), it) }.getOrNull() }
        val name = body?.participants?.firstOrNull()?.artisanId?.let { names[owner?.resolve(it) ?: it] ?: names[it] } ?: roster.firstOrNull()?.name.orEmpty()
        val day = body?.sessionDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(Naming.IST)
        return QuestionnaireCopy.p5Body(QuestionnaireCopy.firstName(name), day)
    }

    fun requestRecord(req: RecordRequest) {
        val running = RecordingService.state.value
        if (running is RecordingService.State.Recording && req.pickedFile == null) { p3 = req; return }
        val u = unit ?: return
        val ids = roster.map { it.artisanId }
        if (req.target == null || ids.isEmpty()) { startRecording(req, emptyMap(), emptyList()); return }
        scope.launch {
            val request = QCoverageCheckRequest(u.questionnaireId, ids, listOf(req.target), u.sessionId, req.pickedFile?.sha256)
            val online: QCoverageCheckResponse? = withTimeoutOrNull(4_000) { runCatching { v2.api.coverageCheck(request) }.getOrNull() }
            val response = online ?: v2.coverage.check(
                request, u.workshopId, CoverageCache.overlayFrom(v2.outbox.units(), u.questionnaireId),
                CoverageCache.CheckContext(
                    sessionId = u.sessionId, sessionKey = u.clientKey, purpose = frozen?.purpose ?: "FIRST_SITTING",
                    rosterCount = ids.size, myUserId = meId,
                    sectionOfQuestion = instrument?.sectionOfQuestion.orEmpty(), questionsOfSection = instrument?.questionsOfSection.orEmpty(),
                    labels = instrument?.labels.orEmpty()
                )
            )
            // P5: the identical file is already uploaded (or queued), so nothing is sent.
            val identical = response.identical
            val queuedHere = req.pickedFile != null && v2.outbox.units().any { un -> un.pendingClips.any { it.clip?.contentSha256 == req.pickedFile.sha256 } }
            if (identical != null || queuedHere) {
                req.pickedFile?.let { File(it.path).delete() }
                p5 = p5Body(identical?.sessionId, req.pickedFile?.sha256)
                return@launch
            }
            val model = AlreadyRecorded.model(
                response, req.label, req.code, ids.associateWith(participantName), ids,
                frozen?.purpose ?: detail?.purpose ?: "FIRST_SITTING", filePick = req.pickedFile != null, now = Instant.now()
            )
            if (model == null) startRecording(req, emptyMap(), emptyList()) else p2 = req to model
        }
    }

    /** H19: save an answer with the version it replaces; P8 first when that answer came from a recording. */
    fun saveAnswer(question: InstrumentQuestion, text: String, forIds: Set<String>, confirmed: Boolean = false) {
        if (!confirmed) {
            AnswerModel.replacesRecordedAnswer(answers, forIds, question.lineageId)?.let { current ->
                p8 = PendingAnswer(question, text, forIds, current.sourceRecordingId.orEmpty())
                return
            }
        }
        val items = AnswerModel.items(text, forIds, question.lineageId, unit?.sessionId ?: clientKey, answers)
        withUnit { key -> v2.outbox.addAnswers(key, QAnswersPut(items)) }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val req = pendingPick
        pendingPick = null
        if (uri == null || req == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { runCatching { copyPicked(context, uri, File(RecordingDrafts.dir(v2.filesDir), SessionOutbox.newKey())) }.getOrNull() }
            if (picked == null) onError(QuestionnaireCopy.s("error.readFile")) else requestRecord(req.copy(pickedFile = picked))
        }
    }

    BackHandler {
        if (pendingCount > 0 || recorderState is RecordingService.State.Recording) leaving = true else onBack()
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (pendingCount > 0 || recorderState is RecordingService.State.Recording) leaving = true else onBack() }) {
                Text("‹ " + QuestionnaireCopy.s("sessions.title"))
            }
            Box(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = QuestionnaireCopy.s("session.more")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!locked) DropdownMenuItem(text = { Text(QuestionnaireCopy.s("session.edit")) }, onClick = { menu = false; editing = true })
                    if (serverSessionId != null && participantIds.isNotEmpty()) {
                        DropdownMenuItem(text = { Text(QuestionnaireCopy.s("session.continue")) }, onClick = { menu = false; continuing = true })
                    }
                    if (!locked && participantIds.size > 1) DropdownMenuItem(text = { Text(QuestionnaireCopy.s("session.removeArtisan")) }, onClick = { menu = false; removingArtisan = true })
                    // Merging and deleting a session are content admins' acts (owner, 2026-10-21).
                    if (mayDelete && serverSessionId != null) {
                        DropdownMenuItem(text = { Text(QuestionnaireCopy.s("session.merge")) }, onClick = { menu = false; merging = true })
                        DropdownMenuItem(text = { Text(QuestionnaireCopy.s("session.delete")) }, onClick = { menu = false; deleting = true })
                    }
                    if (detail != null) {
                        DropdownMenuItem(text = { Text(com.fieldrepository.app.data.FormsCopy.READ_VIEW) }, onClick = { menu = false; reading = true })
                        DropdownMenuItem(text = { Text(com.fieldrepository.app.data.FormsCopy.RESEARCHERS) }, onClick = { menu = false; researchersOpen = true })
                    }
                    if (!locked) DropdownMenuItem(text = { Text("+ " + com.fieldrepository.app.data.FormsCopy.SOMEONE_ELSE) }, onClick = { menu = false; editingGuest = GuestModel("", "", null, null) })
                }
            }
        }
        SessionHeader(
            title = title, note = detail?.note ?: frozen?.note, joinCode = detail?.joinCode, joinQr = joinQr,
            participants = headerParticipants, locked = locked,
            onTogglePresent = { id, isPresent ->
                present[id] = isPresent
                withUnit { key -> v2.outbox.addParticipantChange(key, ParticipantChange(id, if (isPresent) ParticipantChange.JOIN else ParticipantChange.LEAVE, Instant.now().toString())) }
            },
            onAddArtisan = { addingArtisan = true },
            onFinish = { finishing = true }
        )
        if (trayRows.isNotEmpty()) OutboxTray(
            rows = trayRows,
            onSendAgain = { row -> withUnit { key -> v2.outbox.retryStep(key, row.stepId) } },
            onDiscard = { row -> discard = PendingDiscard(row) },
            onKeepTheirs = { row -> row.conflict?.let { c -> withUnit { key -> v2.outbox.resolveConflict(key, row.stepId, c.artisanId, c.questionLineageId, keepMine = false) } } },
            onKeepMine = { row -> row.conflict?.let { c -> withUnit { key -> v2.outbox.resolveConflict(key, row.stepId, c.artisanId, c.questionLineageId, keepMine = true) } } }
        )
        (recorderState as? RecordingService.State.Recording)?.takeIf { it.draft.sessionKey == clientKey }?.let { RecorderControl(it) }
        (recorderState as? RecordingService.State.Finished)?.takeIf { it.draft.sessionKey == clientKey && roster.size > 1 && !it.reason.startsWith("error:") }?.let { done ->
            val sectionKey = done.draft.targets.firstOrNull()?.let { instrument?.sectionOfQuestion?.get(it.questionLineageId) ?: it.sectionId } ?: "whole"
            AnsweredByChips(roster, lastAnswered[sectionKey].orEmpty()) { chosen ->
                lastAnswered[sectionKey] = chosen
                scope.launch {
                    // Saved whatever stage the take is at: its draft, the outbox, or refiled online.
                    if (v2.setAnsweredBy(clientKey, done.draft.clientClipKey, chosen) == QuestionnaireV2.AnsweredOutcome.FAILED) onError(QuestionnaireCopy.s("upload.waiting"))
                    RecordingService.clearFinished()
                    OutboxSyncWorker.schedule(context)
                    reload()
                }
            }
        }
        if (pendingCount > 0) {
            val percent = unit?.uploadingClips?.mapNotNull { it.clip?.uploadedPercent }?.let { if (it.isEmpty()) 0 else it.sum() / it.size } ?: 0
            Text(QuestionnaireCopy.takesUploading(pendingCount) + " · " + OutboxTrayModel.uploadLine(percent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        }
        AppToggle(label = QuestionnaireCopy.s("workspace.typeAnswers"), checked = typeAnswers, onCheckedChange = { typeAnswers = it })

        if (instrument != null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                instrument.sections.forEach { s ->
                    FilterChip(selected = s.id == sectionId, onClick = { sectionId = s.id }, label = { Text(s.code) })
                }
            }
            val rows = CoverageGridModel.rows(instrument, roster.map { it.artisanId }, known, overlay)
            CoverageGrid(rows, names = participantIds.associateWith(participantName), currentSectionId = sectionId,
                onOpenSection = { sectionId = it }, onOpenCell = { s, a -> takeSheet = s to a })
            HorizontalDivider(color = MaterialTheme.field.hairline)
            instrument.section(sectionId ?: "")?.let { section ->
                Text("${section.code}  ${section.title}", style = MaterialTheme.typography.titleMedium)
                if (!locked) OutlinedButton(onClick = {
                    requestRecord(RecordRequest(QTargetRef(sectionId = section.id), "Section ${section.code}", section.code))
                }) { Text(QuestionnaireCopy.s("workspace.recordSection")) }
                section.questions.forEach { q ->
                    QuestionBlock(
                        question = q, roster = roster, known = known, overlay = overlay, locked = locked, typeAnswers = typeAnswers,
                        answerLines = AnswerModel.lines(roster.map { it.artisanId }, q.lineageId, answers, unit) { recId -> detail?.recordings?.firstOrNull { it.id == recId }?.rosterCount },
                        currentText = { forIds -> forIds.map { id -> AnswerModel.queued(unit, id, q.lineageId) ?: AnswerModel.current(answers, id, q.lineageId)?.text.orEmpty() }.distinct().singleOrNull().orEmpty() },
                        nameOf = participantName,
                        onRecord = { requestRecord(RecordRequest(QTargetRef(questionLineageId = q.lineageId), q.displayCode, q.displayCode)) },
                        onOpenTakes = { takeSheet = section.id to null },
                        onSaveAnswer = { text, forIds -> saveAnswer(q, text, forIds) },
                        lockLine = { ids -> lockLine(q.lineageId, ids) },
                        answerOf = { id -> AnswerModel.current(answers, id, q.lineageId) },
                        onHistory = { a ->
                            history = null; historyOpen = true
                            scope.launch { history = runCatching { v2.forms.revisions(a.id).revisions }.getOrElse { onError(QuestionnaireCopy.s("error.needsSignal")); emptyList() } }
                        }
                    )
                }
                GuestsPanel(
                    guests = guests, questions = section.questions,
                    answerOf = { g, lineage -> GuestRoster.answer(unit, detail, g, lineage) },
                    locked = locked, online = com.fieldrepository.app.data.ConnectivityObserver.isOnline(context),
                    onSave = { g, q, text, note ->
                        withUnit { key -> v2.outbox.addGuestAnswers(key, guestAnswers(GuestRoster.item(g, q.lineageId, text, unit?.sessionId ?: clientKey, note, answers))) }
                    },
                    onEdit = { editingGuest = it }
                )
            }
        }
        if (!locked) TextButton(onClick = { requestRecord(RecordRequest(null, QuestionnaireCopy.s("record.wholeSession"), "")) }) {
            Text(QuestionnaireCopy.s("workspace.record") + " · " + QuestionnaireCopy.s("record.wholeSession"))
        }
    }

    // ── Sheets and prompts ──────────────────────────────────────────────────────────────────

    p2?.let { (req, model) ->
        AlreadyRecordedSheet(model, onDismiss = { p2 = null; req.pickedFile?.let { File(it.path).delete() } }) { choice ->
            p2 = null
            when (choice) {
                is P2Choice.Again -> {
                    val reasons = model.lines.map { it.artisanId }.ifEmpty { roster.map { it.artisanId } }.associateWith { choice.reason }
                    startRecording(req, reasons, model.ackedRecordingIds)
                }
                P2Choice.PartTwo -> startRecording(req, emptyMap(), model.ackedRecordingIds, continuationOf = model.latestRecordingId)
                // P7 first when the earlier take will be removed (audit M23: not wired). Removing a take
                // is a content admin's act: for anyone else the server keeps both and marks the new
                // one as the redo, so there is nothing to confirm.
                P2Choice.Replace -> {
                    if (replaceRemovesEarlier(mayDelete)) {
                        p7 = req to model
                    } else {
                        startRecording(req, roster.associate { it.artisanId to "REDO" }, model.ackedRecordingIds, replaces = model.latestRecordingId)
                    }
                }
                P2Choice.DifferentQuestion -> differentFor = req
            }
        }
    }
    p7?.let { (req, model) ->
        val position = known.firstOrNull { it.recordingId == model.latestRecordingId }?.position ?: 1
        QuestionnairePrompt(
            title = QuestionnaireCopy.p7Title(req.label, position),
            body = QuestionnaireCopy.p7Body(position),
            buttons = listOf(
                PromptButton(QuestionnaireCopy.s("p7.cancel")) { p7 = null; req.pickedFile?.let { File(it.path).delete() } },
                PromptButton(QuestionnaireCopy.s("p7.replace"), primary = true) {
                    p7 = null
                    startRecording(req, roster.associate { it.artisanId to "REDO" }, model.ackedRecordingIds, replaces = model.latestRecordingId)
                }
            ),
            onDismiss = { p7 = null; req.pickedFile?.let { File(it.path).delete() } }
        )
    }
    differentFor?.let { req ->
        val all = instrument?.sections.orEmpty().flatMap { s -> listOf(SelectOption("S:${s.id}", "Section ${s.code} · ${s.title}")) + s.questions.map { SelectOption("Q:${it.lineageId}", "${it.displayCode} · ${it.prompt}") } }
        val uncoveredFirst = all.sortedBy { o -> if (known.any { it.targetKey == o.value && it.artisanId in roster.map { r -> r.artisanId } }) 1 else 0 }
        SearchableSelectSheet(title = QuestionnaireCopy.s("p2.different"), options = uncoveredFirst, onDismiss = { differentFor = null }) { key ->
            differentFor = null
            val target = if (key.startsWith("Q:")) QTargetRef(questionLineageId = key.removePrefix("Q:")) else QTargetRef(sectionId = key.removePrefix("S:"))
            val label = instrument?.label(key).orEmpty()
            requestRecord(req.copy(target = target, label = label, code = label.removePrefix("Section ")))
        }
    }
    p3?.let { req ->
        val running = recorderState as? RecordingService.State.Recording
        if (running == null) { p3 = null } else StopCurrentPrompt(
            currentLabel = running.draft.label, elapsedMs = running.elapsedMs(), nextCode = req.code.ifBlank { req.label },
            onKeep = { p3 = null },
            onStopAndRecord = {
                p3 = null
                RecordingService.stop(context)
                scope.launch { while (RecordingService.state.value is RecordingService.State.Recording) delay(100); requestRecord(req) }
            }
        )
    }
    p5?.let { body -> AlreadyUploadedPrompt(body) { p5 = null } }
    p8?.let { pending ->
        val source = detail?.recordings?.firstOrNull { it.id == pending.replacing }
        val who = QuestionnaireCopy.firstName(participantName(pending.forIds.first()))
        val researcher = source?.recordedBy?.name?.let(QuestionnaireCopy::firstName).orEmpty()
        val day = source?.capturedAt?.let { runCatching { Instant.parse(it).atZone(Naming.IST).toLocalDate() }.getOrNull() }
        QuestionnairePrompt(
            title = QuestionnaireCopy.p8Title(who, pending.question.displayCode),
            body = if (day != null && researcher.isNotBlank()) QuestionnaireCopy.p8Body(researcher, day) else QuestionnaireCopy.t("answerExists", "name" to who),
            buttons = listOf(
                PromptButton(QuestionnaireCopy.s("p8.keepCurrent")) { p8 = null },
                PromptButton(QuestionnaireCopy.s("p8.replace"), primary = true) {
                    p8 = null
                    saveAnswer(pending.question, pending.text, pending.forIds, confirmed = true)
                }
            ),
            onDismiss = { p8 = null }
        )
    }
    takeSheet?.let { (sid, artisanId) ->
        val section = instrument?.section(sid)
        val keys = setOf("S:$sid") + section?.questions?.map { "Q:${it.lineageId}" }.orEmpty()
        val rows = known.filter { it.targetKey in keys && (artisanId == null || it.artisanId == artisanId) && it.role == SubjectRole.ANSWERED.wire }
            .distinctBy { it.recordingId }.sortedBy { it.capturedAt ?: "" }
            .map { t -> takeRow(t, detail, paused, mayDelete && !locked, canMakePrimary && artisanId != null) } +
            overlay.filter { it.targetKey in keys && (artisanId == null || it.artisanId == artisanId) }
                .distinctBy { it.clientClipKey }.map { o ->
                    val clip = unit?.steps?.firstOrNull { it.clip?.clientClipKey == o.clientClipKey }?.clip
                    TakeRow("local:${o.clientClipKey}", 0, QuestionnaireCopy.minutes(o.durationMs),
                        if (o.uploading) OutboxTrayModel.uploadLine(clip?.uploadedPercent ?: 0) else QuestionnaireCopy.s("upload.onDevice"), null, local = true, removable = false)
                } +
            removedHere.filter { r -> r.targets.any { it.targetKey in keys } && (artisanId == null || r.subjects.any { it.artisanId == artisanId }) }.map { r ->
                TakeRow(r.id, 0, listOfNotNull(QuestionnaireCopy.minutes(r.durationMs), r.recordedBy?.name?.ifBlank { null }).joinToString("  "), "", null, local = false, removable = false, removed = true)
            }
        TakeSheet(
            heading = listOfNotNull("Section ${section?.code.orEmpty()}", artisanId?.let { QuestionnaireCopy.firstName(participantName(it)) }).joinToString(" · "),
            takes = rows, locked = locked, playing = playing,
            onPlay = { row ->
                scope.launch {
                    playing = if (row.local) {
                        val key = row.recordingId.removePrefix("local:")
                        v2.outbox.units().flatMap { it.steps }.firstOrNull { it.clip?.clientClipKey == key }?.clip?.localPath?.let { File(it) }?.takeIf { it.isFile }?.let { Uri.fromFile(it) }
                    } else v2.playbackUrl(row.recordingId)?.let(Uri::parse)
                }
            },
            onRemove = { removeTake = it },
            onRecord = {
                takeSheet = null
                section?.let { requestRecord(RecordRequest(QTargetRef(sectionId = it.id), "Section ${it.code}", it.code)) }
            },
            onUpload = {
                takeSheet = null
                section?.let { pendingPick = RecordRequest(QTargetRef(sectionId = it.id), "Section ${it.code}", it.code); pickLauncher.launch("audio/*") }
            },
            onDismiss = { takeSheet = null; playing = null },
            onRetry = { row -> online { v2.api.transcribe(row.recordingId) } },
            onRecordConsent = { row ->
                detail?.recordings?.firstOrNull { it.id == row.recordingId }?.let { rec -> consentFor = ParticipantModel.refusingSubject(rec, detail) }
            },
            onRestore = { row ->
                online {
                    v2.api.restoreRecording(row.recordingId)
                    removedHere.removeAll { it.id == row.recordingId }
                }
            },
            onMakePrimary = { row ->
                val who = artisanId ?: return@TakeSheet
                val target = row.targetKey ?: return@TakeSheet
                online {
                    v2.api.makePrimary(row.recordingId, QMakePrimaryRequest(who, target))
                    onError(QuestionnaireCopy.s("take.madePrimary"))
                }
            },
            onPropose = { row -> online { proposals = row.recordingId to v2.api.answerProposals(row.recordingId).proposals } }
        )
    }
    removeTake?.let { row ->
        RemoveTakePrompt(row.position.coerceAtLeast(1), instrument?.section(takeSheet?.first ?: "")?.let { "Section ${it.code}" } ?: "this take",
            onCancel = { removeTake = null },
            onRemove = {
                removeTake = null
                scope.launch {
                    runCatching { v2.api.removeRecording(row.recordingId) }
                        .onSuccess { removedHere += it }
                        .onFailure { onError(QuestionnaireCopy.s("error.removeTake")) }
                    reload()
                }
            })
    }
    consentFor?.let { artisanId ->
        QuestionnairePromptContent(
            title = QuestionnaireCopy.s("consent.record"),
            onDismiss = { consentFor = null },
            content = {
                ConsentQuestion(participantName(artisanId), refused = true, onAnswer = { state ->
                    consentFor = null
                    if (state != null) scope.launch {
                        if (!v2.recordConsent(artisanId, state)) onError(com.fieldrepository.app.data.RecordingConsentCopy.SAVE_FAILED)
                        reload()
                    }
                })
            },
            buttons = emptyList()
        )
    }
    proposals?.let { (recordingId, list) ->
        ProposalsDialog(
            proposals = list, roster = roster,
            codeOf = { lineage -> instrument?.question(lineage)?.displayCode ?: lineage },
            onDismiss = { proposals = null },
            onAccept = { p, ids ->
                val items = AnswerModel.items(p.text, ids, p.questionLineageId, unit?.sessionId ?: clientKey, answers + p.existing, origin = p.origin, sourceRecordingId = recordingId)
                withUnit { key -> v2.outbox.addAnswers(key, QAnswersPut(items)) }
            }
        )
    }
    if (addingArtisan) {
        var chosen by remember { mutableStateOf(emptySet<String>()) }
        QuestionnairePromptContent(
            title = QuestionnaireCopy.s("workspace.addArtisan").removePrefix("+ "),
            onDismiss = { addingArtisan = false },
            content = {
                SearchableMultiSelectField(
                    label = QuestionnaireCopy.s("start.artisans"),
                    options = artisans.filter { it.id !in participantIds }.map { SelectOption(it.id, it.name, it.place.ifBlank { null }) },
                    selected = chosen
                ) { chosen = it }
            },
            buttons = listOf(PromptButton(QuestionnaireCopy.s("p5.ok"), primary = true) {
                addingArtisan = false
                val ids = chosen
                withUnit { key -> ids.forEach { v2.outbox.addParticipantChange(key, ParticipantChange(it, ParticipantChange.ADD, Instant.now().toString())) } }
            })
        )
    }
    if (removingArtisan) {
        SearchableSelectSheet(
            title = QuestionnaireCopy.s("session.removeArtisan"),
            options = headerParticipants.map { SelectOption(it.artisanId, it.name) },
            onDismiss = { removingArtisan = false }
        ) { id ->
            removingArtisan = false
            val takes = ParticipantModel.takesOf(detail, id)
            if (takes > 0) p10 = id to takes else removeParticipant(v2, clientKey, serverSessionId, id, detach = false, ::withUnit, ::online)
        }
    }
    p10?.let { (id, takes) ->
        RemoveParticipantPrompt(
            name = QuestionnaireCopy.firstName(participantName(id)), takes = takes,
            onCancel = { p10 = null },
            onRemove = {
                p10 = null
                removeParticipant(v2, clientKey, serverSessionId, id, detach = true, ::withUnit, ::online)
            }
        )
    }
    if (editing) {
        val initial = SessionEdit.of(
            detail?.sessionDate ?: frozen?.sessionDate, detail?.purpose ?: frozen?.purpose, detail?.place ?: frozen?.place,
            detail?.languageCode ?: frozen?.languageCode, frozen?.languageOther, detail?.note ?: frozen?.note
        )
        EditSessionDialog(initial, onDismiss = { editing = false }) { edit ->
            editing = false
            val d = detail
            if (d != null) online {
                try {
                    v2.api.patchSession(d.id, edit.patch(d.version, initial))
                } catch (http: retrofit2.HttpException) {
                    if (http.errorCode() == QErrorCodes.STALE_VERSION) { onError(QuestionnaireCopy.s("error.changedElsewhere")); return@online }
                    throw http
                }
            } else withUnit { key ->
                if (!v2.outbox.editPendingSession(key, edit::applyTo)) onError(QuestionnaireCopy.s("error.needsSignal"))
            }
        }
    }
    if (continuing && serverSessionId != null) {
        ContinueWithDialog(
            v2 = v2, title = QuestionnaireCopy.s("session.continue"), fromSessionId = serverSessionId,
            people = headerParticipants.map { it.artisanId to it.name }, preselected = participantIds.toSet(),
            onDismiss = { continuing = false },
            onOpened = { session -> continuing = false; onOpenSession(session) },
            onError = onError
        )
    }
    if (merging && serverSessionId != null) {
        MergeSessionDialog(v2, serverSessionId, unit?.questionnaireId ?: detail?.questionnaire?.id.orEmpty(), onDismiss = { merging = false }) { target ->
            merging = false
            scope.launch {
                runCatching { v2.api.mergeInto(serverSessionId, target.id) }
                    .onSuccess { v2.outbox.forget(clientKey); onOpenSession(target) }
                    .onFailure { onError(it.userSentence()) }
            }
        }
    }
    if (deleting && serverSessionId != null) {
        QuestionnairePrompt(
            title = QuestionnaireCopy.s("session.deleteTitle"),
            // A restorable soft delete for content admins (owner decision 3).
            body = deleteHint ?: com.fieldrepository.app.data.FormsCopy.SESSION_DELETE_BODY,
            buttons = listOf(
                PromptButton(QuestionnaireCopy.s("session.cancel")) { deleting = false; deleteHint = null },
                PromptButton(QuestionnaireCopy.s("session.delete"), primary = true, destructive = true) {
                    scope.launch {
                        val response = runCatching { v2.api.deleteSession(serverSessionId) }.getOrNull()
                        when {
                            response == null -> { deleting = false; onError(QuestionnaireCopy.s("error.needsSignal")) }
                            response.isSuccessful -> { deleting = false; v2.outbox.forget(clientKey); onBack() }
                            else -> {
                                // P11: a session with recordings is not deleted; say how many to move first.
                                val detailBody = runCatching { com.fieldrepository.app.data.ApiClient.json.decodeFromString(QErrorBody.serializer(), response.errorBody()?.string().orEmpty()).detail }.getOrNull()
                                deleteHint = if (detailBody?.code == QErrorCodes.HAS_RECORDINGS && detailBody.count != null) QuestionnaireCopy.p11(detailBody.count)
                                else detailBody?.message?.ifBlank { null } ?: QuestionnaireCopy.s("upload.waiting")
                            }
                        }
                    }
                }
            ),
            onDismiss = { deleting = false; deleteHint = null }
        )
    }
    discard?.let { (row) ->
        QuestionnairePrompt(
            title = QuestionnaireCopy.s("tray.discardTitle"),
            body = outboxDiscardSentence(row.title, row.files, clash = false),
            buttons = listOf(
                PromptButton(QuestionnaireCopy.s("session.cancel")) { discard = null },
                PromptButton(QuestionnaireCopy.s("tray.discard"), primary = true, destructive = true) {
                    discard = null
                    val c = row.conflict
                    withUnit { key ->
                        // One conflicted answer goes alone; anything else is the whole step.
                        if (c != null) v2.outbox.resolveConflict(key, row.stepId, c.artisanId, c.questionLineageId, keepMine = false)
                        else v2.outbox.discardStep(key, row.stepId)
                    }
                }
            ),
            onDismiss = { discard = null }
        )
    }
    if (historyOpen) AnswerHistoryDialog(history, onDismiss = { historyOpen = false })
    if (takeHistoryOpen) RecordingHistoryDialog(takeHistory, onDismiss = { takeHistoryOpen = false })
    val d = detail
    if (researchersOpen && d != null) ResearchersDialog(v2, d, team, onDismiss = { researchersOpen = false }, onAdded = { researchersOpen = false; scope.launch { reload() } }, onError = onError)
    editingGuest?.let { g ->
        GuestDialog(initialName = g.name, initialNotes = g.notes.orEmpty(), title = if (g.key.isBlank()) com.fieldrepository.app.data.FormsCopy.SOMEONE_ELSE else com.fieldrepository.app.data.FormsCopy.EDIT_RESPONDENT,
            withNotes = g.key.isNotBlank(), onDismiss = { editingGuest = null }) { name, notes ->
            editingGuest = null
            val sid = serverSessionId
            if (g.key.isBlank()) {
                // A new guest: on the server at once when it can, else named on this phone until the session lands.
                val localKey = "guest-" + SessionOutbox.newKey()
                withUnit { key ->
                    val created = sid?.let { runCatching { v2.forms.addRespondents(it, com.fieldrepository.app.data.QRespondentsAdd(listOf(com.fieldrepository.app.data.QRespondentInput(name)))).respondents.firstOrNull() }.getOrNull() }
                    if (sid != null && created == null) { onError(QuestionnaireCopy.s("error.needsSignal")); return@withUnit }
                    if (sid == null && !v2.outbox.editPendingSession(key) { it.copy(respondents = it.respondents + com.fieldrepository.app.data.QRespondentInput(name)) }) return@withUnit
                    v2.outbox.rememberGuest(key, localKey, name, created?.id)
                }
            } else if (sid != null) online { saveRespondent(v2, sid, g, name, notes) }
        }
    }
    if (reading && d != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { reading = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            androidx.compose.material3.Surface(Modifier.fillMaxWidth()) {
                SessionReadView(
                    lines = ReadViewModel.lines(instrument, d), title = d.title, status = d.status,
                    mayApprove = com.fieldrepository.app.data.FormRights.mayReview(user),
                    onApprove = {
                        online {
                            v2.forms.approveSession(SessionOutbox.newKey(), d.id)
                            reading = false
                        }
                    },
                    onBack = { reading = false }
                )
            }
        }
    }
    if (leaving) LeaveSessionPrompt(pendingCount.coerceAtLeast(1), onStay = { leaving = false }) {
        leaving = false
        val s = RecordingService.state.value
        if (s is RecordingService.State.Recording && s.draft.sessionKey == clientKey) RecordingService.stop(context, "leave")
        onBack()
    }
    if (finishing) {
        val sections = known.filter { it.sessionId == unit?.sessionId || it.sessionId == clientKey }.map { it.targetKey }.distinct().count { it.startsWith("S:") }
        // A19: reported, never blocking.
        LaunchedEffect(Unit) {
            val qid = unit?.questionnaireId ?: detail?.questionnaire?.id
            val form = qid?.let { id -> runCatching { v2.forms.form(id) }.getOrNull() ?: v2.formCache.detail(meId, id) }
            requiredLeft = RequiredModel.unanswered(form, participantIds, answers, known)
        }
        FinishSessionPrompt(sections, participantIds.size, pendingCount, onDismiss = { finishing = false }, extra = com.fieldrepository.app.data.RequiredQuestions.line(com.fieldrepository.app.data.QRequiredUnanswered(requiredLeft))) {
            finishing = false
            val s = RecordingService.state.value
            if (s is RecordingService.State.Recording && s.draft.sessionKey == clientKey) RecordingService.stop(context)
            withUnit { key -> v2.outbox.finishSession(key) }
            onBack()
        }
    }
}

/**
 * P10 (audit §1, "remove participant" was missing): before the session reaches the server, the
 * frozen create loses the artisan; after, `DELETE …/participants/{id}`, detaching their takes when
 * P10 was confirmed.
 */
private fun removeParticipant(
    v2: QuestionnaireV2, clientKey: String, sessionId: String?, artisanId: String, detach: Boolean,
    withUnit: (suspend (String) -> Unit) -> Unit, online: (suspend () -> Unit) -> Unit
) {
    if (sessionId == null) {
        withUnit { key -> v2.outbox.removePendingParticipant(key, artisanId) }
        return
    }
    online { v2.api.removeParticipant(sessionId, artisanId, detach = if (detach) true else null) }
}

/** The contract code of a refusal, when it sent one. */
private fun retrofit2.HttpException.errorCode(): String? = runCatching {
    com.fieldrepository.app.data.ApiClient.json.decodeFromString(QErrorBody.serializer(), response()?.errorBody()?.string().orEmpty()).detail?.code
}.getOrNull()

/**
 * The fix tray (audit H20): everything this phone could not send, with Send again and Discard, and
 * P9 (Keep theirs · Keep mine) for an answer changed on another device.
 */
@Composable
internal fun OutboxTray(
    rows: List<TrayRow>,
    onSendAgain: (TrayRow) -> Unit,
    onDiscard: (TrayRow) -> Unit,
    onKeepTheirs: (TrayRow) -> Unit,
    onKeepMine: (TrayRow) -> Unit
) {
    Surface(color = MaterialTheme.field.surface100, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(QuestionnaireCopy.s("tray.title") + " (${rows.size})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            rows.forEach { row ->
                Text(row.title, style = MaterialTheme.typography.bodyMedium)
                if (row.conflict != null) {
                    Text(QuestionnaireCopy.s("p9.title"), style = MaterialTheme.typography.labelLarge)
                    Text(QuestionnaireCopy.s("tray.theirs") + ": " + row.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body)
                    row.mine?.let { Text(QuestionnaireCopy.s("tray.mine") + ": " + it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body) }
                    PromptButtons(listOf(
                        PromptButton(QuestionnaireCopy.s("p9.theirs")) { onKeepTheirs(row) },
                        PromptButton(QuestionnaireCopy.s("p9.mine"), primary = true) { onKeepMine(row) }
                    ))
                } else {
                    Text(row.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                    PromptButtons(listOfNotNull(
                        PromptButton(QuestionnaireCopy.s("tray.discard")) { onDiscard(row) },
                        if (row.sendAgain) PromptButton(QuestionnaireCopy.s("tray.sendAgain"), primary = true) { onSendAgain(row) } else null
                    ))
                }
                HorizontalDivider(color = MaterialTheme.field.hairline)
            }
        }
    }
}

@Composable
private fun QuestionBlock(
    question: InstrumentQuestion,
    roster: List<HeaderParticipant>,
    known: List<QSnapshotTake>,
    overlay: List<OverlayTake>,
    locked: Boolean,
    typeAnswers: Boolean,
    answerLines: List<AnswerLine>,
    currentText: (Set<String>) -> String,
    nameOf: (String) -> String,
    onRecord: () -> Unit,
    onOpenTakes: () -> Unit,
    onSaveAnswer: (text: String, forArtisanIds: Set<String>) -> Unit,
    /** P15 when the answer this save would replace is someone else's (the answer lock, U9). */
    lockLine: (Set<String>) -> String? = { null },
    answerOf: (String) -> com.fieldrepository.app.data.QAnswer? = { null },
    onHistory: (com.fieldrepository.app.data.QAnswer) -> Unit = {}
) {
    var answerFor by remember(question.lineageId) { mutableStateOf(ALL_ARTISANS) }
    // The box opens on the answer it would replace (audit H19: a saved answer used to vanish).
    var answer by remember(question.lineageId, answerFor) { mutableStateOf(currentText(answerForIds(roster, answerFor))) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${question.displayCode}  ${question.prompt}", style = MaterialTheme.typography.bodyMedium)
        val marks = roster.map { CoverageGridModel.questionMark(question, it.artisanId, known, overlay) }
        val line = roster.zip(marks).filter { it.second.mark != CellMark.NONE }
            .joinToString(" · ") { (p, m) -> "${QuestionnaireCopy.firstName(p.name)} ${m.glyph}" }
        if (line.isNotBlank()) TextButton(onClick = onOpenTakes) { Text(line, style = MaterialTheme.typography.bodySmall) }
        answerLines.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(QuestionnaireCopy.firstName(nameOf(a.artisanId)) + ": " + a.text, a.badge).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (a.onPhone) MaterialTheme.field.muted else MaterialTheme.field.body,
                    modifier = Modifier.weight(1f)
                )
                // B21: every answer's earlier versions, with who wrote each.
                answerOf(a.artisanId)?.takeIf { !a.onPhone && it.id.isNotBlank() }?.let { stored ->
                    TextButton(onClick = { onHistory(stored) }) { Text(com.fieldrepository.app.data.FormsCopy.HISTORY, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        if (!locked) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRecord) { Text(QuestionnaireCopy.s("workspace.record")) }
        }
        if (typeAnswers && !locked) {
            if (roster.size > 1) SearchableSelectField(
                label = QuestionnaireCopy.answerForLabel(),
                options = answerForOptions(roster),
                selectedValue = answerFor,
                includeNone = false,
                onSelect = { answerFor = it }
            )
            val ids = answerForIds(roster, answerFor)
            val lockedBy = lockLine(ids)
            if (lockedBy != null) LockedAnswerNote(lockedBy)
            else {
                OutlinedTextField(value = answer, onValueChange = { answer = it }, modifier = Modifier.fillMaxWidth())
                if (answer.isNotBlank() && answer.trim() != currentText(ids)) PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("save"), primary = true) {
                    onSaveAnswer(answer.trim(), ids)
                }))
            }
        }
    }
}

private const val ALL_ARTISANS = "*"

/** "All N" first, then each artisan by name. */
internal fun answerForOptions(roster: List<HeaderParticipant>): List<SelectOption> =
    listOf(SelectOption(ALL_ARTISANS, QuestionnaireCopy.t("answerForAll", "count" to roster.size))) +
        roster.map { SelectOption(it.artisanId, it.name) }

internal fun answerForIds(roster: List<HeaderParticipant>, choice: String): Set<String> =
    if (choice == ALL_ARTISANS || roster.none { it.artisanId == choice }) roster.map { it.artisanId }.toSet() else setOf(choice)

private fun takeRow(t: QSnapshotTake, detail: QSessionDetail?, paused: Boolean, mayRemove: Boolean, mayMakePrimary: Boolean): TakeRow {
    val rec = detail?.recordings?.firstOrNull { it.id == t.recordingId }
    val at = t.capturedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val first = listOfNotNull(
        QuestionnaireCopy.take(t.position),
        t.repeatReason?.let(QuestionnaireCopy::reasonLabel),
        QuestionnaireCopy.minutes(rec?.durationMs ?: t.durationMs),
        (rec?.recordedBy ?: t.recordedBy)?.name?.ifBlank { null },
        at?.let { "${QuestionnaireCopy.day(it.atZone(Naming.IST).toLocalDate())} ${QuestionnaireCopy.clock(it)}" }
    ).joinToString("  ")
    val second = QuestionnaireCopy.setting(rec?.rosterCount ?: t.rosterCount)
    return TakeRow(
        t.recordingId, t.position, first, second,
        rec?.let { TranscriptStatus.line(it, paused) }, local = false,
        // Removing a take is a delete: content admins only (owner's ruling, 2026-10-20).
        removable = mayRemove,
        targetKey = t.targetKey,
        canMakePrimary = mayMakePrimary,
        canPropose = rec?.transcript?.status == "COMPLETED"
    )
}

/** Copy a picked file into private storage, hashing it on the way (§6.1.4). */
private fun copyPicked(context: Context, uri: Uri, target: File): PickedFile {
    val mime = context.contentResolver.getType(uri) ?: "audio/mp4"
    val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "m4a"
    val file = File(target.parentFile, "${target.name}.$ext")
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    context.contentResolver.openInputStream(uri)!!.use { input ->
        FileOutputStream(file).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
                size += n
            }
        }
    }
    var metadata: Instant? = null
    var duration = 0L
    runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        metadata = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::parseMetadataDate)
        retriever.release()
    }
    return PickedFile(file.absolutePath, digest.digest().joinToString("") { "%02x".format(it) }, size, mime, ext, metadata, duration)
}

/** `20260701T125400.000Z` (the form MediaMetadataRetriever reports). */
internal fun parseMetadataDate(raw: String): Instant? = runCatching {
    val d = raw.trim()
    val date = LocalDate.of(d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt())
    if (date.year < 2000) return null
    val time = if (d.length >= 15) java.time.LocalTime.of(d.substring(9, 11).toInt(), d.substring(11, 13).toInt(), d.substring(13, 15).toInt()) else java.time.LocalTime.NOON
    date.atTime(time).toInstant(ZoneOffset.UTC)
}.getOrNull()

/** A prompt whose body is a control rather than a sentence. */
@Composable
fun QuestionnairePromptContent(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit, buttons: List<PromptButton>) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { content() },
        confirmButton = { if (buttons.isNotEmpty()) PromptButtons(buttons) }
    )
}
