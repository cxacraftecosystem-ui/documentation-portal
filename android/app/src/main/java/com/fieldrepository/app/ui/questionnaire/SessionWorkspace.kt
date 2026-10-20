package com.fieldrepository.app.ui.questionnaire

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.ClientClock
import com.fieldrepository.app.data.CoverageCache
import com.fieldrepository.app.data.JoinQr
import com.fieldrepository.app.data.JoinQrPayload
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.data.OverlayTake
import com.fieldrepository.app.data.ParticipantChange
import com.fieldrepository.app.data.QAnswerItem
import com.fieldrepository.app.data.QAnswersPut
import com.fieldrepository.app.data.QCompleteSubject
import com.fieldrepository.app.data.QCoverageCheckRequest
import com.fieldrepository.app.data.QCoverageCheckResponse
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
import com.fieldrepository.app.data.outboxJson
import com.fieldrepository.app.recording.RecordingDraft
import com.fieldrepository.app.recording.RecordingDrafts
import com.fieldrepository.app.recording.RecordingService
import com.fieldrepository.app.ui.MICROPHONE_PERMISSION
import com.fieldrepository.app.ui.MICROPHONE_REFUSED_SENTENCE
import com.fieldrepository.app.ui.SearchableMultiSelectField
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SearchableSelectSheet
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.AppToggle
import com.fieldrepository.app.ui.field
import com.fieldrepository.app.ui.rememberPermissionGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A pending Record request, held while P2 or P3 is on screen. */
private data class RecordRequest(val target: QTargetRef?, val label: String, val code: String, val pickedFile: PickedFile? = null)

private data class PickedFile(val path: String, val sha256: String, val size: Long, val mime: String, val ext: String, val metadataTime: Instant?, val durationMs: Long)

/**
 * The session workspace (DESIGN §4.3): header, section strip, coverage grid, the open section with
 * a Record control per question, the recorder, and the take sheet. Works offline from the session
 * outbox and the coverage snapshot; online it adds the server's session and its recordings.
 */
@Composable
fun SessionWorkspace(
    v2: QuestionnaireV2,
    clientKey: String,
    instrument: Instrument?,
    artisans: List<ArtisanDto>,
    meId: String?,
    onBack: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val names = remember(artisans) { artisans.associate { it.id to it.name } }
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
    var takeSheet by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var playing by remember { mutableStateOf<Uri?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var addingArtisan by remember { mutableStateOf(false) }
    var differentFor by remember { mutableStateOf<RecordRequest?>(null) }
    var removeTake by remember { mutableStateOf<TakeRow?>(null) }
    var pendingPick by remember { mutableStateOf<RecordRequest?>(null) }
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

    LaunchedEffect(clientKey) {
        while (true) {
            reload()
            delay((v2.config.value?.snapshotPollSeconds ?: 60) * 1000L)
        }
    }
    LaunchedEffect(recorderState) {
        if (recorderState is RecordingService.State.Finished) { delay(600); reload() }
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
    val pendingCount = unit?.pendingClips?.size ?: 0

    fun startRecording(req: RecordRequest, reasons: Map<String, String>, acked: List<String>, continuationOf: String? = null, replaces: String? = null) {
        val u = unit ?: return
        val subjects = roster.map { QCompleteSubject(it.artisanId, SubjectRole.ANSWERED.wire, named = false, repeatReason = reasons[it.artisanId]) }
        val key = SessionOutbox.newKey()
        val picked = req.pickedFile
        if (picked != null) {
            // A picked file: its metadata time, else the session date at 12:00 IST (§3.9).
            val sessionNoon = frozen?.sessionDate?.let { runCatching { LocalDate.parse(it).atTime(12, 0).atZone(Naming.IST).toInstant() }.getOrNull() }
            val draft = RecordingDraft(
                clientClipKey = key, sessionKey = u.clientKey, questionnaireId = u.questionnaireId, adtsPath = picked.path,
                deviceCapturedAt = (picked.metadataTime ?: sessionNoon ?: Instant.now()).toString(),
                clockOffsetMs = 0, capturedAtSource = if (picked.metadataTime != null) "FILE_METADATA" else "SESSION_DATE",
                targets = listOfNotNull(req.target), subjects = subjects, label = req.label,
                contentType = picked.mime, extension = picked.ext, continuationOfId = continuationOf, replacesRecordingId = replaces,
                ackedRecordingIds = acked, finished = true, m4aPath = picked.path, durationMs = picked.durationMs,
                sha256 = picked.sha256, sizeBytes = picked.size
            )
            scope.launch {
                RecordingDrafts.write(v2.filesDir, draft)
                v2.queueTake(draft)
                OutboxSyncWorker.schedule(context)
                reload()
            }
            return
        }
        micGate.run(MICROPHONE_PERMISSION, MICROPHONE_REFUSED_SENTENCE) {
            val draft = RecordingDraft(
                clientClipKey = key, sessionKey = u.clientKey, questionnaireId = u.questionnaireId,
                adtsPath = File(RecordingDrafts.dir(v2.filesDir), "$key.aac").absolutePath,
                deviceCapturedAt = Instant.now().toString(), clockOffsetMs = ClientClock.offsetMs(),
                capturedAtSource = ClientClock.captureSource(), targets = listOfNotNull(req.target), subjects = subjects,
                label = req.label, continuationOfId = continuationOf, replacesRecordingId = replaces, ackedRecordingIds = acked
            )
            // Written BEFORE the recorder starts, so a kill mid-take still knows what it was (§6.1.2).
            RecordingDrafts.write(v2.filesDir, draft)
            RecordingService.start(context, draft)
        }
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
            // P5: the identical file is already uploaded, so nothing is sent.
            val identical = response.identical
            if (identical != null || (req.pickedFile != null && overlay.isNotEmpty() && v2.outbox.units().any { un -> un.pendingClips.any { it.clip?.contentSha256 == req.pickedFile.sha256 } })) {
                File(req.pickedFile?.path ?: "").takeIf { req.pickedFile != null }?.delete()
                p5 = QuestionnaireCopy.p5Body(participantName(ids.first()).let(QuestionnaireCopy::firstName), LocalDate.now(Naming.IST))
                return@launch
            }
            val model = AlreadyRecorded.model(
                response, req.label, req.code, ids.associateWith(participantName), ids,
                frozen?.purpose ?: "FIRST_SITTING", filePick = req.pickedFile != null, now = Instant.now()
            )
            if (model == null) startRecording(req, emptyMap(), emptyList()) else p2 = req to model
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val req = pendingPick
        pendingPick = null
        if (uri == null || req == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { runCatching { copyPicked(context, uri, File(RecordingDrafts.dir(v2.filesDir), SessionOutbox.newKey())) }.getOrNull() }
            if (picked == null) onError("Couldn't read that file.") else requestRecord(req.copy(pickedFile = picked))
        }
    }

    BackHandler {
        if (pendingCount > 0 || recorderState is RecordingService.State.Recording) leaving = true else onBack()
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SessionHeader(
            title = title, note = detail?.note ?: frozen?.note, joinCode = detail?.joinCode, joinQr = joinQr,
            participants = headerParticipants, locked = locked,
            onTogglePresent = { id, isPresent ->
                present[id] = isPresent
                scope.launch {
                    v2.outbox.addParticipantChange(clientKey, ParticipantChange(id, if (isPresent) ParticipantChange.JOIN else ParticipantChange.LEAVE, Instant.now().toString()))
                    OutboxSyncWorker.schedule(context)
                }
            },
            onAddArtisan = { addingArtisan = true },
            onFinish = { finishing = true }
        )
        (recorderState as? RecordingService.State.Recording)?.takeIf { it.draft.sessionKey == clientKey }?.let { RecorderControl(it) }
        (recorderState as? RecordingService.State.Finished)?.takeIf { it.draft.sessionKey == clientKey && roster.size > 1 }?.let { done ->
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
        if (pendingCount > 0) Text(QuestionnaireCopy.takesUploading(pendingCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
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
                        onRecord = { requestRecord(RecordRequest(QTargetRef(questionLineageId = q.lineageId), q.displayCode, q.displayCode)) },
                        onOpenTakes = { takeSheet = section.id to null },
                        onSaveAnswer = { text, forIds ->
                            scope.launch {
                                // "All N" is one group answer; picking one artisan is that artisan's own.
                                val shared = forIds.size > 1
                                v2.outbox.addAnswers(clientKey, QAnswersPut(roster.filter { it.artisanId in forIds }.map {
                                    QAnswerItem(it.artisanId, q.lineageId, text, origin = "TYPED", attribution = if (shared) "GROUP_SHARED" else "INDIVIDUAL", sessionId = unit?.sessionId ?: clientKey)
                                }))
                                OutboxSyncWorker.schedule(context)
                            }
                        }
                    )
                }
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
                P2Choice.Replace -> startRecording(req, roster.associate { it.artisanId to "REDO" }, model.ackedRecordingIds, replaces = model.latestRecordingId)
                P2Choice.DifferentQuestion -> differentFor = req
            }
        }
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
    takeSheet?.let { (sid, artisanId) ->
        val mayRemoveTakes = com.fieldrepository.app.ui.LocalContentAdminChrome.current
        val section = instrument?.section(sid)
        val keys = setOf("S:$sid") + section?.questions?.map { "Q:${it.lineageId}" }.orEmpty()
        val rows = known.filter { it.targetKey in keys && (artisanId == null || it.artisanId == artisanId) && it.role == SubjectRole.ANSWERED.wire }
            .distinctBy { it.recordingId }.sortedBy { it.capturedAt ?: "" }
            .map { t -> takeRow(t, detail, paused, mayRemoveTakes) } + overlay.filter { it.targetKey in keys && (artisanId == null || it.artisanId == artisanId) }
            .distinctBy { it.clientClipKey }.map { o ->
                TakeRow("local:${o.clientClipKey}", 0, QuestionnaireCopy.minutes(o.durationMs),
                    if (o.uploading) QuestionnaireCopy.uploading(50) else QuestionnaireCopy.s("upload.onDevice"), null, local = true, removable = false)
            }
        TakeSheet(
            heading = listOfNotNull("Section ${section?.code.orEmpty()}", artisanId?.let { QuestionnaireCopy.firstName(participantName(it)) }).joinToString(" · "),
            takes = rows, locked = locked, playing = playing,
            onPlay = { row ->
                scope.launch {
                    playing = if (row.local) {
                        val key = row.recordingId.removePrefix("local:")
                        v2.outbox.units().flatMap { it.steps }.firstOrNull { it.clip?.clientClipKey == key }?.clip?.localPath?.let { Uri.fromFile(File(it)) }
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
            onDismiss = { takeSheet = null; playing = null }
        )
    }
    removeTake?.let { row ->
        RemoveTakePrompt(row.position.coerceAtLeast(1), instrument?.section(takeSheet?.first ?: "")?.let { "Section ${it.code}" } ?: "this take",
            onCancel = { removeTake = null },
            onRemove = {
                removeTake = null
                scope.launch { runCatching { v2.api.removeRecording(row.recordingId) }.onFailure { onError("Couldn't remove the take. Try again with a signal.") }; reload() }
            })
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
                scope.launch {
                    chosen.forEach { v2.outbox.addParticipantChange(clientKey, ParticipantChange(it, ParticipantChange.ADD, Instant.now().toString())) }
                    OutboxSyncWorker.schedule(context)
                    reload()
                }
            })
        )
    }
    if (leaving) LeaveSessionPrompt(pendingCount.coerceAtLeast(1), onStay = { leaving = false }) {
        leaving = false
        val s = RecordingService.state.value
        if (s is RecordingService.State.Recording && s.draft.sessionKey == clientKey) RecordingService.stop(context, "leave")
        onBack()
    }
    if (finishing) {
        val sections = known.filter { it.sessionId == unit?.sessionId || it.sessionId == clientKey }.map { it.targetKey }.distinct().count { it.startsWith("S:") }
        FinishSessionPrompt(sections, participantIds.size, pendingCount, onDismiss = { finishing = false }) {
            finishing = false
            scope.launch {
                val s = RecordingService.state.value
                if (s is RecordingService.State.Recording && s.draft.sessionKey == clientKey) RecordingService.stop(context)
                v2.outbox.finishSession(clientKey)
                OutboxSyncWorker.schedule(context)
                onBack()
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
    onRecord: () -> Unit,
    onOpenTakes: () -> Unit,
    onSaveAnswer: (text: String, forArtisanIds: Set<String>) -> Unit
) {
    var answer by remember(question.lineageId) { mutableStateOf("") }
    var answerFor by remember(question.lineageId) { mutableStateOf(ALL_ARTISANS) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${question.displayCode}  ${question.prompt}", style = MaterialTheme.typography.bodyMedium)
        val marks = roster.map { CoverageGridModel.questionMark(question, it.artisanId, known, overlay) }
        val line = roster.zip(marks).filter { it.second.mark != CellMark.NONE }
            .joinToString(" · ") { (p, m) -> "${QuestionnaireCopy.firstName(p.name)} ${m.glyph}" }
        if (line.isNotBlank()) TextButton(onClick = onOpenTakes) { Text(line, style = MaterialTheme.typography.bodySmall) }
        if (!locked) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRecord) { Text(QuestionnaireCopy.s("workspace.record")) }
        }
        if (typeAnswers) {
            if (roster.size > 1) SearchableSelectField(
                label = QuestionnaireCopy.answerForLabel(),
                options = answerForOptions(roster),
                selectedValue = answerFor,
                includeNone = false,
                onSelect = { answerFor = it }
            )
            OutlinedTextField(value = answer, onValueChange = { answer = it }, modifier = Modifier.fillMaxWidth())
            if (answer.isNotBlank()) PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("save"), primary = true) {
                onSaveAnswer(answer.trim(), answerForIds(roster, answerFor))
                answer = ""
            }))
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

private fun takeRow(t: QSnapshotTake, detail: QSessionDetail?, paused: Boolean, mayRemove: Boolean): TakeRow {
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
        // Removing a take is a delete: content admins in admin view only (owner's ruling, 2026-10-20).
        removable = mayRemove
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
        confirmButton = { PromptButtons(buttons) }
    )
}
