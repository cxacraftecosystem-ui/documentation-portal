package com.fieldrepository.app.questionnaire.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.questionnaire.data.ClientClock
import com.fieldrepository.app.questionnaire.data.CoverageCache
import com.fieldrepository.app.questionnaire.data.JoinQr
import com.fieldrepository.app.questionnaire.data.JoinQrPayload
import com.fieldrepository.app.questionnaire.data.MissingSessionUnit
import com.fieldrepository.app.questionnaire.data.OverlayTake
import com.fieldrepository.app.questionnaire.data.ParticipantChange
import com.fieldrepository.app.questionnaire.data.QAnswersPut
import com.fieldrepository.app.questionnaire.data.QArtisanOption
import com.fieldrepository.app.questionnaire.data.QCompleteSubject
import com.fieldrepository.app.questionnaire.data.QCoverageCheckRequest
import com.fieldrepository.app.questionnaire.data.QCoverageCheckResponse
import com.fieldrepository.app.questionnaire.data.QMakePrimaryRequest
import com.fieldrepository.app.questionnaire.data.QProposal
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QRespondentInput
import com.fieldrepository.app.questionnaire.data.QRespondentPatch
import com.fieldrepository.app.questionnaire.data.QRespondentRegister
import com.fieldrepository.app.questionnaire.data.QSession
import com.fieldrepository.app.questionnaire.data.QSessionCreate
import com.fieldrepository.app.questionnaire.data.QSessionDetail
import com.fieldrepository.app.questionnaire.data.QSnapshotTake
import com.fieldrepository.app.questionnaire.data.QSubjects
import com.fieldrepository.app.questionnaire.data.QTargetRef
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.data.RecordBy
import com.fieldrepository.app.questionnaire.data.RespondentKind
import com.fieldrepository.app.questionnaire.data.SessionOutbox
import com.fieldrepository.app.questionnaire.data.SessionUnit
import com.fieldrepository.app.questionnaire.data.SharedLanguages
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.transcript.data.SpokenLanguageMemory
import com.fieldrepository.app.transcript.ui.SpokenLanguageField
import com.fieldrepository.app.transcript.ui.rememberSpokenLanguageChoice
import com.fieldrepository.app.questionnaire.data.SubjectRole
import com.fieldrepository.app.questionnaire.data.outboxJson
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import com.fieldrepository.app.recording.RecordingDraft
import com.fieldrepository.app.recording.RecordingDrafts
import com.fieldrepository.app.transcript.ui.TranscriptDialog
import com.fieldrepository.app.recording.RecordingService
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate

private data class RecordRequest(val target: QTargetRef?, val label: String, val code: String, val pickedFiles: List<PickedFile> = emptyList()) {
    val upload: Boolean get() = pickedFiles.isNotEmpty()
    fun discardFiles() = pickedFiles.forEach { File(it.path).delete() }
}

private data class PendingAnswer(val question: InstrumentQuestion, val text: String, val forIds: Set<String>, val replacing: String, val note: String?, val richText: String?)

private data class PendingDiscard(val row: TrayRow)

private data class HistoryOf(val answerId: String, val heading: String)

private data class UploadOpen(val sectionId: String?, val lineageId: String?)

@Composable
fun SessionWorkspace(
    v2: QuestionnaireV2,
    clientKey: String,
    instrument: Instrument?,
    artisans: List<QArtisanOption>,
    meId: String?,
    canMakePrimary: Boolean = false,
    isAdmin: Boolean = false,
    initialSection: String? = null,
    onBack: () -> Unit,
    onOpenSession: (QSession) -> Unit = {},
    onOpenReadView: (sessionId: String) -> Unit = {},
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mayDelete = LocalMayDelete.current
    val names = remember(artisans) { artisans.associate { it.id to it.name } + v2.localArtisanNames() }
    var unit by remember { mutableStateOf<SessionUnit?>(null) }
    var detail by remember { mutableStateOf<QSessionDetail?>(null) }
    var overlay by remember { mutableStateOf<List<OverlayTake>>(emptyList()) }
    var snapshotTakes by remember { mutableStateOf<List<QSnapshotTake>>(emptyList()) }
    var openSection by rememberSaveable(clientKey) { mutableStateOf(initialSection) }
    var reviewing by rememberSaveable(clientKey) { mutableStateOf(false) }
    var typing by rememberSaveable(clientKey) { mutableStateOf<String?>(null) }
    val present = remember { mutableStateMapOf<String, Boolean>() }
    var p2 by remember { mutableStateOf<Pair<RecordRequest, P2Model>?>(null) }
    var p3 by remember { mutableStateOf<RecordRequest?>(null) }
    var p7 by remember { mutableStateOf<Pair<RecordRequest, P2Model>?>(null) }
    var p8 by remember { mutableStateOf<PendingAnswer?>(null) }
    var takeSheet by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var playing by remember { mutableStateOf<Uri?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var addingArtisan by remember { mutableStateOf(false) }
    var addingGuest by remember { mutableStateOf(false) }
    var editingGuest by remember { mutableStateOf<String?>(null) }
    var addingResearcher by remember { mutableStateOf(false) }
    var differentFor by remember { mutableStateOf<RecordRequest?>(null) }
    var removeTake by remember { mutableStateOf<TakeRow?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var trayOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<List<SessionFieldDiff>?>(null) }
    var continuing by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var removingArtisan by remember { mutableStateOf(false) }
    var p10 by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var consentFor by remember { mutableStateOf<String?>(null) }
    var proposals by remember { mutableStateOf<Pair<String, List<QProposal>>?>(null) }
    var transcriptOf by remember { mutableStateOf<TakeRow?>(null) }
    var discard by remember { mutableStateOf<PendingDiscard?>(null) }
    var historyOf by remember { mutableStateOf<HistoryOf?>(null) }
    var takeHistory by remember { mutableStateOf<String?>(null) }
    var micOff by remember { mutableStateOf<UploadOpen?>(null) }
    var uploadOpen by remember { mutableStateOf<UploadOpen?>(null) }
    val uploadRows = remember { mutableStateListOf<UploadRow>() }
    var uploadChecking by remember { mutableStateOf(0) }
    var uploadAttach by remember { mutableStateOf(AttachKind.WHOLE) }
    var uploadQuestion by remember { mutableStateOf<String?>(null) }
    var pendingMic by remember { mutableStateOf<UploadOpen?>(null) }
    val removed = remember { mutableStateListOf<QRecording>() }
    val lastAnswered = remember { mutableStateMapOf<String, Set<String>>() }
    val recorderState by RecordingService.state.collectAsState()
    val micGate = rememberPermissionGate { micOff = pendingMic ?: UploadOpen(openSection, null) }
    val paused = v2.config.collectAsState().value?.transcriptionPaused ?: true
    val listState = rememberLazyListState()

    suspend fun reload() {
        val u = v2.outbox.unit(clientKey) ?: unit
        unit = u
        val qid = u?.questionnaireId ?: instrument?.id ?: return
        val workshopId = u?.workshopId
        val sessionId = u?.sessionId ?: detail?.id
        if (sessionId != null) {
            runCatching { v2.api.session(sessionId) }.onSuccess { detail = it }
            runCatching { v2.api.removedRecordings(sessionId = sessionId).items }.onSuccess { list ->
                removed.removeAll { r -> list.none { it.id == r.id } && r.removedAt != null }
                list.forEach { r -> if (removed.none { it.id == r.id }) removed += r }
            }
        }
        runCatching {
            val since = v2.coverage.get(qid, workshopId)?.snapshot?.asOfSeq
            v2.coverage.apply(qid, workshopId, v2.api.snapshot(qid, workshopId, since), delta = since != null)
        }
        snapshotTakes = v2.coverage.get(qid, workshopId)?.snapshot?.takes.orEmpty()
        overlay = CoverageCache.overlayFrom(v2.outbox.units(), qid)
    }

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
            v2.requestSync()
            reload()
        }
    }

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
            when (state.reason) {
                RecordingService.REASON_START_FAILED -> { onError(QuestionnaireCopy.s("error.recorderStart")); RecordingService.clearFinished() }
                RecordingService.REASON_QUEUE_FAILED -> { onError(QuestionnaireCopy.s("error.queueTake")); RecordingService.clearFinished() }
                else -> RecordingService.failureSentence(state.reason)?.let { onError(it); RecordingService.clearFinished() }
            }
        }
        if (state is RecordingService.State.Finished) { delay(600); reload() }
    }
    DisposableEffect(clientKey) {
        onDispose {
            val s = RecordingService.state.value
            if (s is RecordingService.State.Recording && s.draft.sessionKey == clientKey) RecordingService.stop(context, "leave")
            v2.requestSync()
        }
    }

    val frozen = remember(unit) {
        unit?.steps?.firstOrNull { it.kind == StepKind.SESSION }?.bodyJson?.let { runCatching { outboxJson.decodeFromString(QSessionCreate.serializer(), it) }.getOrNull() }
    }
    val guestNames: Map<String, String> = remember(unit, detail) {
        unit?.let { v2.outbox.guestsOf(it) }.orEmpty().toMap() +
            detail?.respondents.orEmpty().filter { it.artisanId == null }.associate { it.subjectId to it.name }
    }
    val participantIds: List<String> = remember(unit, detail) {
        val base = detail?.participants?.map { it.artisanId } ?: frozen?.participants?.map { unit!!.resolve(it.artisanId) }.orEmpty()
        val added = unit?.steps?.filter { it.kind == StepKind.PARTICIPANT && it.participant?.action == ParticipantChange.ADD }?.map { unit!!.resolve(it.participant!!.artisanId) }.orEmpty()
        val guests = detail?.respondents.orEmpty().filter { it.artisanId == null }.map { it.subjectId } +
            unit?.let { v2.outbox.guestsOf(it) }.orEmpty().map { it.first }
        (base + added + guests).distinct()
    }
    val participantName: (String) -> String = { id ->
        detail?.nameOf(id) ?: guestNames[id]?.ifBlank { null } ?: names[id].orEmpty()
    }
    val headerParticipants = participantIds.map { id ->
        HeaderParticipant(
            id, participantName(id),
            present[id] ?: (detail?.participants?.firstOrNull { p -> p.artisanId == id }?.leftAt == null),
            guest = QSubjects.isGuest(id)
        )
    }
    val roster = headerParticipants.filter { it.present }
    val spoken = rememberSpokenLanguageChoice(participantIds.filterNot { QSubjects.isGuest(it) })
    val locked = detail?.state == "CLOSED"
    val sessionTakes = CoverageGridModel.takesOf(detail?.recordings.orEmpty())
    val known = CoverageGridModel.merge(snapshotTakes, sessionTakes)
    val answers = detail?.answers.orEmpty()
    val typed = remember(answers, unit) { AnswerModel.typedPairs(answers, unit) }
    val sessionKeys = setOfNotNull(clientKey, unit?.sessionId, detail?.id)
    val sessionIds = sessionTakes.map { it.recordingId }.toSet()
    val evidence = SessionEvidence(
        participantIds.toSet(),
        sessionTakes + snapshotTakes.filter { it.sessionId in sessionKeys && it.recordingId !in sessionIds },
        overlay.filter { it.sessionKey in sessionKeys },
        typed
    )
    val tally = instrument?.let(evidence::tally) ?: SessionTally(0, 0, 0, 0)
    val leadOrAdmin = isAdmin || detail?.researchers.orEmpty().any { it.userId == meId && it.role == "LEAD" }
    val recordBy = v2.outbox.queuedRecordBy(unit) ?: detail?.recordBy ?: frozen?.recordBy ?: RecordBy.SECTION.wire
    val byQuestion = recordBy == RecordBy.QUESTION.wire
    val title = detail?.title?.ifBlank { null } ?: frozen?.let { f ->
        Naming.sessionTitle(Naming.SessionFields(
            sessionDate = runCatching { LocalDate.parse(f.sessionDate) }.getOrNull(), place = f.place,
            participantCrafts = participantIds.map { id -> artisans.firstOrNull { it.id == id }?.craft },
            participantNames = participantIds.map(participantName)
        ))
    }.orEmpty()
    val people = headerParticipants.map { it.name }.filter { it.isNotBlank() }
    val headerTitle = when {
        people.size == 1 -> people.single()
        people.isNotEmpty() -> people.joinToString(", ") { QuestionnaireCopy.firstName(it) }
        else -> title
    }
    val sessionDay = (detail?.sessionDate ?: frozen?.sessionDate)?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    val crafts = (detail?.crafts ?: frozen?.crafts).orEmpty().ifEmpty { participantIds.mapNotNull { id -> artisans.firstOrNull { it.id == id }?.craft } }.distinct()
    val language = (detail?.languageCode ?: frozen?.languageCode)?.let { code ->
        if (code == "other") detail?.languageOther ?: frozen?.languageOther else SharedLanguages.labels[code] ?: code
    }
    val headerSubline = listOfNotNull(crafts.joinToString(", ").ifBlank { null }, language?.ifBlank { null }, sessionDay?.let(QuestionnaireCopy::day)).joinToString(" · ")
    val joinQr = detail?.joinQr ?: unit?.let { u ->
        frozen?.let { f -> JoinQr.encode(JoinQrPayload(clientKey = u.clientKey, questionnaireId = u.questionnaireId, workshopId = u.workshopId, sessionDate = f.sessionDate, participants = QSubjects.artisans(participantIds))) }
    }
    val pendingCount = unit?.uploadingClips?.size ?: 0
    val trayRows = unit?.let { u -> OutboxTrayModel.rows(u, participantName) { lineage -> instrument?.question(lineage)?.displayCode ?: lineage } }.orEmpty()
    val serverSessionId = unit?.sessionId ?: detail?.id
    val pill = SavePillModel.of(unit)
    val recording = (recorderState as? RecordingService.State.Recording)?.takeIf { it.draft.sessionKey == clientKey }

    fun startRecording(req: RecordRequest, reasons: Map<String, String>, acked: List<String>, continuationOf: String? = null, replaces: String? = null) {
        val u = unit ?: return
        val subjects = roster.map { QCompleteSubject(it.artisanId, SubjectRole.ANSWERED.wire, named = false, repeatReason = reasons[it.artisanId]) }
        if (req.upload) {
            val sessionNoon = sessionDay?.atTime(12, 0)?.atZone(Naming.IST)?.toInstant()
            val drafts = req.pickedFiles.map { picked ->
                RecordingDraft(
                    clientClipKey = SessionOutbox.newKey(), sessionKey = u.clientKey, questionnaireId = u.questionnaireId, adtsPath = picked.path,
                    deviceCapturedAt = (picked.recordedAt ?: sessionNoon ?: Instant.now()).toString(),
                    clockOffsetMs = 0, capturedAtSource = if (picked.recordedAt != null) "FILE_METADATA" else "SESSION_DATE",
                    targets = listOfNotNull(req.target), subjects = subjects, label = req.label,
                    contentType = picked.mime, extension = picked.ext, continuationOfId = continuationOf, replacesRecordingId = replaces,
                    ackedRecordingIds = acked, finished = true, m4aPath = picked.path, durationMs = picked.durationMs,
                    sha256 = picked.sha256, sizeBytes = picked.size, spokenLanguage = spoken.value
                )
            }
            SpokenLanguageMemory.remember(context, spoken.value)
            withUnit {
                drafts.forEach { draft ->
                    RecordingDrafts.write(v2.filesDir, draft)
                    v2.queueTake(draft)
                }
            }
            return
        }
        pendingMic = UploadOpen(req.target?.sectionId ?: req.target?.questionLineageId?.let { instrument?.sectionOfQuestion?.get(it) }, req.target?.questionLineageId)
        micGate.run(MICROPHONE_PERMISSION, MICROPHONE_REFUSED_SENTENCE) {
            val key = SessionOutbox.newKey()
            val draft = RecordingDraft(
                clientClipKey = key, sessionKey = u.clientKey, questionnaireId = u.questionnaireId,
                adtsPath = File(RecordingDrafts.dir(v2.filesDir), "$key.aac").absolutePath,
                deviceCapturedAt = Instant.now().toString(), clockOffsetMs = ClientClock.offsetMs(), clockOffsetMeasuredAt = ClientClock.measuredAt(),
                capturedAtSource = ClientClock.captureSource(), targets = listOfNotNull(req.target), subjects = subjects,
                label = req.label, continuationOfId = continuationOf, replacesRecordingId = replaces, ackedRecordingIds = acked,
                spokenLanguage = spoken.value
            )
            SpokenLanguageMemory.remember(context, spoken.value)
            RecordingDrafts.write(v2.filesDir, draft)
            RecordingService.start(context, draft)
        }
    }

    fun requestRecord(req: RecordRequest) {
        val running = RecordingService.state.value
        if (running is RecordingService.State.Recording && !req.upload) { p3 = req; return }
        val u = unit ?: return
        val ids = QSubjects.artisans(roster.map { it.artisanId })
        if (req.target == null || ids.isEmpty()) { startRecording(req, emptyMap(), emptyList()); return }
        scope.launch {
            val request = QCoverageCheckRequest(u.questionnaireId, ids, listOf(req.target), u.sessionId, null)
            val online: QCoverageCheckResponse? = withTimeoutOrNull(4_000) { runCatching { v2.api.coverageCheck(request) }.getOrNull() }
            val response = online ?: v2.coverage.check(
                request, u.workshopId, CoverageCache.overlayFrom(v2.outbox.units(), u.questionnaireId),
                CoverageCache.CheckContext(
                    sessionId = u.sessionId, sessionKey = u.clientKey, purpose = frozen?.purpose ?: "FIRST_SITTING",
                    rosterCount = roster.size, myUserId = meId,
                    sectionOfQuestion = instrument?.sectionOfQuestion.orEmpty(), questionsOfSection = instrument?.questionsOfSection.orEmpty(),
                    labels = instrument?.labels.orEmpty()
                )
            )
            val model = AlreadyRecorded.model(
                response, req.label, req.code, ids.associateWith(participantName), ids,
                frozen?.purpose ?: detail?.purpose ?: "FIRST_SITTING", filePick = req.upload, now = Instant.now()
            )
            if (model == null) startRecording(req, emptyMap(), emptyList()) else p2 = req to model
        }
    }

    fun saveAnswer(question: InstrumentQuestion, text: String, forIds: Set<String>, note: String?, richText: String?, confirmed: Boolean = false) {
        if (!confirmed) {
            AnswerModel.replacesRecordedAnswer(answers, forIds, question.lineageId)?.let { current ->
                p8 = PendingAnswer(question, text, forIds, current.sourceRecordingId.orEmpty(), note, richText)
                return
            }
        }
        val items = AnswerModel.items(text, forIds, question.lineageId, unit?.sessionId ?: clientKey, answers, note = note, richText = richText)
        typing = null
        withUnit { key -> v2.outbox.addAnswers(key, QAnswersPut(items)) }
    }

    fun sectionRequest(section: InstrumentSection) = RecordRequest(QTargetRef(sectionId = section.id), "Section ${section.code}", section.code)
    fun questionRequest(q: InstrumentQuestion) = RecordRequest(QTargetRef(questionLineageId = q.lineageId), q.displayCode, q.displayCode)

    fun openUpload(sectionId: String?, lineageId: String?) {
        uploadRows.forEach { File(it.file.path).delete() }
        uploadRows.clear()
        uploadAttach = UploadPlan.defaultKind(sectionId, lineageId)
        uploadQuestion = lineageId
        uploadOpen = UploadOpen(sectionId, lineageId)
    }

    fun closeUpload(keepFiles: Boolean) {
        if (!keepFiles) uploadRows.forEach { File(it.file.path).delete() }
        uploadRows.clear()
        uploadOpen = null
    }

    suspend fun duplicateOn(picked: PickedFile): LocalDate? {
        val units = v2.outbox.units()
        units.firstOrNull { un -> un.steps.any { it.clip?.contentSha256 == picked.sha256 } }?.let { owner ->
            val body = owner.steps.firstOrNull { it.kind == StepKind.SESSION }?.bodyJson?.let { runCatching { outboxJson.decodeFromString(QSessionCreate.serializer(), it) }.getOrNull() }
            return body?.sessionDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: LocalDate.now(Naming.IST)
        }
        if (uploadRows.any { it.file.sha256 == picked.sha256 }) return LocalDate.now(Naming.IST)
        val u = unit ?: return null
        val ids = QSubjects.artisans(participantIds)
        val probe = instrument?.sections?.firstOrNull()?.let { QTargetRef(sectionId = it.id) } ?: return null
        if (ids.isEmpty()) return null
        val response = withTimeoutOrNull(6_000) {
            runCatching { v2.api.coverageCheck(QCoverageCheckRequest(u.questionnaireId, ids, listOf(probe), u.sessionId, picked.sha256)) }.getOrNull()
        } ?: return null
        val identical = response.identical ?: return null
        val elsewhere = identical.sessionId?.let { id -> runCatching { v2.api.sessionSummary(id) }.getOrNull() }
        return elsewhere?.sessionDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: LocalDate.now(Naming.IST)
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uploadChecking += uris.size
        uris.forEach { uri ->
            scope.launch {
                val picked = withContext(Dispatchers.IO) { runCatching { copyPicked(context, uri, File(RecordingDrafts.dir(v2.filesDir), SessionOutbox.newKey())) }.getOrNull() }
                if (picked == null) onError(QuestionnaireCopy.s("error.readFile"))
                else if (uploadOpen == null) File(picked.path).delete()
                else uploadRows += UploadRow(picked, duplicateOn(picked))
                uploadChecking -= 1
            }
        }
    }

    fun queueUpload() {
        val files = UploadPlan.uploading(uploadRows)
        if (files.isEmpty()) return
        val open = uploadOpen
        val req = when (uploadAttach) {
            AttachKind.SECTION -> instrument?.section(open?.sectionId ?: "")?.let(::sectionRequest)
            AttachKind.QUESTION -> uploadQuestion?.let { instrument?.question(it) }?.let(::questionRequest)
            AttachKind.WHOLE -> RecordRequest(null, QuestionnaireCopy.s("record.wholeSession"), "")
        } ?: return
        uploadRows.filter { it.skipped }.forEach { File(it.file.path).delete() }
        closeUpload(keepFiles = true)
        requestRecord(req.copy(pickedFiles = files))
    }

    fun setRecordBy(value: String) {
        if (value == recordBy) return
        val before = detail?.recordBy ?: frozen?.recordBy ?: RecordBy.SECTION.wire
        withUnit { key -> v2.outbox.setRecordBy(key, value, before) }
    }

    fun leave() {
        if (pendingCount > 0 || recording != null) leaving = true else onBack()
    }

    BackHandler {
        when {
            reviewing -> reviewing = false
            else -> leave()
        }
    }

    ScrollOpenSectionToTop(listState, openSection, instrument?.sections?.indexOfFirst { it.id == openSection }?.takeIf { it >= 0 }?.let { WORKSPACE_LEAD_ITEMS + it })

    if (reviewing && instrument != null) {
        val model = ReviewModel.of(instrument, evidence, people, pendingCount, trayRows)
        ReviewScreen(
            model = model, locked = locked,
            onBack = { reviewing = false },
            onNeedsYou = { row ->
                val step = row.stepId
                if (step != null) withUnit { key -> v2.outbox.retryStep(key, step) }
                else row.code?.let { code ->
                    val section = instrument.sections.firstOrNull { it.code == code } ?: instrument.sections.firstOrNull { s -> s.questions.any { it.displayCode == code } }
                    section?.let { reviewing = false; openSection = it.id; takeSheet = it.id to null }
                }
            },
            onOpenSection = { id -> reviewing = false; openSection = id },
            onFinish = {
                reviewing = false
                val s = RecordingService.state.value
                if (s is RecordingService.State.Recording && s.draft.sessionKey == clientKey) RecordingService.stop(context)
                withUnit { key -> v2.outbox.finishSession(key) }
                onBack()
            }
        )
    } else Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SessionTopBar(
                title = headerTitle, subline = headerSubline, showReview = instrument != null,
                onBack = { leave() }, onReview = { reviewing = true }, onMenu = { menu = true }
            )
        },
        bottomBar = {
            val open = instrument?.section(openSection ?: "")
            when {
                recording != null -> RecorderControl(recording)
                !locked && !byQuestion && open != null -> SectionDock(
                    code = open.code,
                    onRecord = { requestRecord(sectionRequest(open)) },
                    onUpload = { openUpload(open.id, null) }
                )
            }
        }
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("questionnaire.workspace"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding() + 24.dp)
        ) {
            item("top") {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    if (locked) FinishedBanner(
                        detail?.closedAt?.let { runCatching { Instant.parse(it).atZone(Naming.IST).toLocalDate() }.getOrNull() }?.let(QuestionnaireCopy::day)
                            ?: sessionDay?.let(QuestionnaireCopy::day).orEmpty()
                    )
                    SessionProgressBlock(
                        tally = tally, pill = pill, onOpenUploads = { trayOpen = true },
                        onRetry = { pill.failedSteps.forEach { id -> withUnit { key -> v2.outbox.retryStep(key, id) } } }
                    )
                    PeopleRow(
                        participants = headerParticipants, locked = locked,
                        onTogglePresent = { id, isPresent ->
                            present[id] = isPresent
                            if (!QSubjects.isGuest(id)) withUnit { key -> v2.outbox.addParticipantChange(key, ParticipantChange(id, if (isPresent) ParticipantChange.JOIN else ParticipantChange.LEAVE, Instant.now().toString())) }
                        },
                        onOpenGuest = { id -> editingGuest = id },
                        onAddArtisan = { addingArtisan = true },
                        onAddGuest = { addingGuest = true }
                    )
                }
            }
            instrument?.sections?.forEach { section ->
                item("sec-${section.id}") {
                    val status = evidence.section(section)
                    SectionCard(
                        section = section, status = status, open = section.id == openSection, byQuestion = byQuestion,
                        onToggle = { openSection = if (openSection == section.id) null else section.id },
                        pair = {
                            if (!locked && !byQuestion && recording == null) RecordUploadPair(
                                recordLabel = QuestionnaireCopy.s("workspace.recordSection"), uploadLabel = QuestionnaireCopy.t("upload"),
                                onRecord = { requestRecord(sectionRequest(section)) }, onUpload = { openUpload(section.id, null) },
                                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                            )
                        }
                    ) {
                        section.questions.forEach { q ->
                            val qStatus = evidence.question(q)
                            val lines = AnswerModel.lines(roster.map { it.artisanId }, q.lineageId, answers, unit, meId, leadOrAdmin) { recId -> detail?.recordings?.firstOrNull { it.id == recId }?.rosterCount }
                            val overflow = buildList {
                                if (!locked && !byQuestion) {
                                    add(OverflowItem(QuestionnaireCopy.t("recordThisQuestion"), Icons.Filled.Mic) { requestRecord(questionRequest(q)) })
                                    if (recording == null) add(OverflowItem(QuestionnaireCopy.t("uploadForThisQuestion"), Icons.Filled.FileUpload) { openUpload(section.id, q.lineageId) })
                                }
                                if (!locked) add(OverflowItem(QuestionnaireCopy.t("typeAnAnswer"), Icons.Filled.Keyboard) { typing = q.lineageId })
                                if (!locked && q.artisanField != null && roster.any { !it.guest }) add(OverflowItem(QuestionnaireCopy.s("answer.prefill"), Icons.Filled.AutoFixHigh) {
                                    val items = AnswerModel.prefillItems(roster.map { it.artisanId }.toSet(), q.lineageId, unit?.sessionId ?: clientKey, answers)
                                    if (items.isNotEmpty()) withUnit { key -> v2.outbox.addAnswers(key, QAnswersPut(items)) }
                                })
                            }
                            QuestionRow(
                                question = q, status = qStatus, byQuestion = byQuestion, locked = locked,
                                typedPreview = lines.firstOrNull()?.text, typing = typing == q.lineageId,
                                overflow = overflow, showPair = !locked && byQuestion, showUpload = recording == null,
                                onRecord = { requestRecord(questionRequest(q)) },
                                onUpload = { openUpload(section.id, q.lineageId) },
                                onOpenTyped = { typing = if (typing == q.lineageId) null else q.lineageId },
                                onOpenTakes = { takeSheet = section.id to null }
                            ) {
                                AnswerEditor(
                                    question = q, roster = roster, answerLines = lines,
                                    currentText = { forIds -> forIds.map { id -> AnswerModel.queued(unit, id, q.lineageId) ?: AnswerModel.current(answers, id, q.lineageId)?.let { it.richText ?: it.text }.orEmpty() }.distinct().singleOrNull().orEmpty() },
                                    lockedFor = { forIds -> forIds.mapNotNull { id -> AnswerModel.current(answers, id, q.lineageId)?.takeIf { AnswerModel.locked(it, meId, leadOrAdmin) } }.firstOrNull()?.let { QuestionnaireCopy.locked(it.authorName) } },
                                    nameOf = participantName,
                                    onCancel = { typing = null },
                                    onSaveAnswer = { text, forIds, note, rich -> saveAnswer(q, text, forIds, note, rich) },
                                    onHistory = { line -> line.answerId?.let { historyOf = HistoryOf(it, "${q.displayCode} · ${QuestionnaireCopy.firstName(participantName(line.artisanId))}") } }
                                )
                            }
                        }
                    }
                }
            }
            if (!locked && instrument != null) item("whole") {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { requestRecord(RecordRequest(null, QuestionnaireCopy.s("record.wholeSession"), "")) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(QuestionnaireCopy.s("workspace.record") + " · " + QuestionnaireCopy.s("record.wholeSession"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            accordionTail(listState, openSection != null)
        }
    }

    if (menu) SessionMenuSheet(
        recordBy = recordBy, recordByEnabled = !locked, onRecordBy = { setRecordBy(it) },
        items = listOfNotNull(
            if (!locked) SessionMenuItem(QuestionnaireCopy.s("session.edit"), SessionMenuIcons.edit) { editing = true } else null,
            if (serverSessionId != null && !locked) SessionMenuItem(QuestionnaireCopy.s("researcher.add"), SessionMenuIcons.researcher) { addingResearcher = true } else null,
            if (joinQr != null) SessionMenuItem(QuestionnaireCopy.t("joinCodeAndQr"), SessionMenuIcons.join) { showQr = true } else null,
            if (serverSessionId != null) SessionMenuItem(QuestionnaireCopy.t("readView"), SessionMenuIcons.read) { onOpenReadView(serverSessionId) } else null,
            if (serverSessionId != null && participantIds.any { !QSubjects.isGuest(it) }) SessionMenuItem(QuestionnaireCopy.s("session.continue"), SessionMenuIcons.continueDay) { continuing = true } else null,
            if (!locked && participantIds.size > 1) SessionMenuItem(QuestionnaireCopy.t("removeArtisan"), SessionMenuIcons.removeArtisan, destructive = true) { removingArtisan = true } else null,
            if (mayDelete && serverSessionId != null) SessionMenuItem(QuestionnaireCopy.t("mergeIntoAnotherSession"), SessionMenuIcons.merge, destructive = true) { merging = true } else null,
            if (mayDelete && serverSessionId != null) SessionMenuItem(QuestionnaireCopy.s("session.delete"), SessionMenuIcons.delete, destructive = true) { deleting = true } else null
        ),
        onDismiss = { menu = false }
    )
    if (showQr) joinQr?.let { JoinQrDialog(it, detail?.joinCode) { showQr = false } }
    if (trayOpen) OutboxTraySheet(
        pill = pill,
        rows = trayRows,
        onDismiss = { trayOpen = false },
        onSendAgain = { row -> withUnit { key -> v2.outbox.retryStep(key, row.stepId) } },
        onDiscard = { row -> discard = PendingDiscard(row) },
        onKeepTheirs = { row -> row.conflict?.let { c -> withUnit { key -> v2.outbox.resolveConflict(key, row.stepId, c.artisanId, c.questionLineageId, keepMine = false) } } },
        onKeepMine = { row -> row.conflict?.let { c -> withUnit { key -> v2.outbox.resolveConflict(key, row.stepId, c.artisanId, c.questionLineageId, keepMine = true) } } },
        onSendWithoutConflicts = { stepId -> withUnit { key -> v2.outbox.dropConflicts(key, stepId) } }
    )
    uploadOpen?.let { open ->
        val section = instrument?.section(open.sectionId ?: "")
        val questions = (section?.questions ?: instrument?.sections?.flatMap { it.questions }.orEmpty())
        UploadSheet(
            rows = uploadRows.toList(), checking = uploadChecking > 0, attach = uploadAttach,
            sectionCode = section?.code, sectionQuestions = section?.questions?.size ?: 0,
            questionOptions = questions.map { SelectOption(it.lineageId, "${it.displayCode} · ${it.prompt}") },
            questionId = uploadQuestion, sessionDate = sessionDay,
            onAttach = { uploadAttach = it }, onQuestion = { uploadQuestion = it },
            onChoose = { pickLauncher.launch(arrayOf("audio/*", "video/*")) },
            onRemove = { row -> uploadRows.remove(row); File(row.file.path).delete() },
            onUpload = { queueUpload() },
            onDismiss = { closeUpload(keepFiles = false) }
        )
    }
    micOff?.let { target ->
        MicOffPrompt(
            onUploadInstead = { micOff = null; openUpload(target.sectionId, target.lineageId) },
            onOpenSettings = {
                micOff = null
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            onDismiss = { micOff = null }
        )
    }
    (recorderState as? RecordingService.State.Finished)?.takeIf { it.draft.sessionKey == clientKey && roster.size > 1 && !it.reason.startsWith("error:") }?.let { done ->
        val sectionKey = done.draft.targets.firstOrNull()?.let { instrument?.sectionOfQuestion?.get(it.questionLineageId) ?: it.sectionId } ?: "whole"
        AnsweredBySheet(
            participants = headerParticipants.filterNot { it.guest && !it.present },
            takeLine = done.draft.label + " · " + QuestionnaireCopy.elapsed(done.draft.durationMs),
            initial = lastAnswered[sectionKey].orEmpty(),
            onNotNow = {
                RecordingService.clearFinished()
                v2.requestSync()
                scope.launch { reload() }
            },
            onSave = { chosen ->
                lastAnswered[sectionKey] = chosen
                scope.launch {
                    if (v2.setAnsweredBy(clientKey, done.draft.clientClipKey, chosen) == QuestionnaireV2.AnsweredOutcome.FAILED) onError(QuestionnaireCopy.s("upload.waiting"))
                    RecordingService.clearFinished()
                    v2.requestSync()
                    reload()
                }
            }
        )
    }

    p2?.let { (req, model) ->
        AlreadyRecordedSheet(model, onDismiss = { p2 = null; req.discardFiles() }) { choice ->
            p2 = null
            when (choice) {
                is P2Choice.Again -> {
                    val reasons = model.lines.map { it.artisanId }.ifEmpty { roster.map { it.artisanId } }.associateWith { choice.reason }
                    startRecording(req, reasons, model.ackedRecordingIds)
                }
                P2Choice.PartTwo -> startRecording(req, emptyMap(), model.ackedRecordingIds, continuationOf = model.latestRecordingId)
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
                PromptButton(QuestionnaireCopy.s("p7.cancel")) { p7 = null; req.discardFiles() },
                PromptButton(QuestionnaireCopy.s("p7.replace"), primary = true) {
                    p7 = null
                    startRecording(req, roster.associate { it.artisanId to "REDO" }, model.ackedRecordingIds, replaces = model.latestRecordingId)
                }
            ),
            onDismiss = { p7 = null; req.discardFiles() }
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
                    saveAnswer(pending.question, pending.text, pending.forIds, pending.note, pending.richText, confirmed = true)
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
            .map { t -> takeRow(t, detail, paused, mayDelete && !locked, canMakePrimary && artisanId != null && !QSubjects.isGuest(artisanId)) } +
            overlay.filter { it.targetKey in keys && (artisanId == null || it.artisanId == artisanId) }
                .distinctBy { it.clientClipKey }.map { o ->
                    val clip = unit?.steps?.firstOrNull { it.clip?.clientClipKey == o.clientClipKey }?.clip
                    TakeRow("local:${o.clientClipKey}", 0, QuestionnaireCopy.minutes(o.durationMs),
                        if (o.uploading) OutboxTrayModel.uploadLine(clip?.uploadedPercent ?: 0) else QuestionnaireCopy.s("upload.onDevice"), null, local = true, removable = false)
                } +
            removed.filter { r -> r.targets.any { it.targetKey in keys } && (artisanId == null || r.allSubjects.any { it.artisanId == artisanId }) }.map { r ->
                TakeRow(r.id, 0, listOfNotNull(QuestionnaireCopy.minutes(r.durationMs), r.recordedBy?.name?.ifBlank { null }).joinToString("  "), "", null, local = false, removable = mayDelete, removed = true)
            }
        val missing = section?.let { s -> s.questions.count { q -> artisanId != null && (artisanId to q.lineageId) in typed } }
        TakeSheet(
            heading = listOfNotNull("Section ${section?.code.orEmpty()}", artisanId?.let { QuestionnaireCopy.firstName(participantName(it)) }).joinToString(" · "),
            takes = rows, locked = locked, playing = playing,
            note = if (rows.none { !it.removed } && (missing ?: 0) > 0) QuestionnaireCopy.s("mark.addRecording") else null,
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
                section?.let { openUpload(it.id, null) }
            },
            onDismiss = { takeSheet = null; playing = null },
            onRetry = { row -> online { v2.api.transcribe(row.recordingId) } },
            onRecordConsent = { row ->
                detail?.recordings?.firstOrNull { it.id == row.recordingId }?.let { rec -> consentFor = ParticipantModel.refusingSubject(rec, detail) }
            },
            onRestore = { row ->
                online {
                    v2.api.restoreRecording(row.recordingId)
                    removed.removeAll { it.id == row.recordingId }
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
            onPropose = { row -> online { proposals = row.recordingId to v2.api.answerProposals(row.recordingId).proposals } },
            onHistory = { row -> takeHistory = row.recordingId },
            onTranscript = { row -> transcriptOf = row }
        )
    }
    removeTake?.let { row ->
        RemoveTakePrompt(row.position.coerceAtLeast(1), instrument?.section(takeSheet?.first ?: "")?.let { "Section ${it.code}" } ?: "this take",
            onCancel = { removeTake = null },
            onRemove = {
                removeTake = null
                scope.launch {
                    runCatching { v2.api.removeRecording(row.recordingId) }
                        .onSuccess { removed += it }
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
                    if (state != null && !QSubjects.isGuest(artisanId)) scope.launch {
                        if (!v2.recordConsent(artisanId, state)) onError(QuestionnaireCopy.s("error.saveOnPhone"))
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
    transcriptOf?.let { row ->
        val mediaId = row.mediaFileId ?: return@let
        TranscriptDialog(mediaId, audio = null, onDismiss = { transcriptOf = null }, acceptDraft = { draft, text ->
            val rec = detail?.recordings?.firstOrNull { it.id == row.recordingId }
            val onTake = rec?.allSubjects?.map { it.artisanId }?.filter { id -> roster.any { it.artisanId == id } }.orEmpty().toSet()
            val ids = onTake.ifEmpty { roster.map { it.artisanId }.toSet() }
            val origin = if (text == draft.answerOriginal) "MACHINE_SUGGESTED" else "MACHINE_SUGGESTED_EDITED"
            val items = AnswerModel.items(text, ids, draft.questionId, unit?.sessionId ?: clientKey, answers, origin = origin, sourceRecordingId = row.recordingId)
            withUnit { key -> v2.outbox.addAnswers(key, QAnswersPut(items)) }
        })
    }
    historyOf?.let { h -> AnswerHistoryDialog(v2, h.answerId, h.heading) { historyOf = null } }
    takeHistory?.let { id -> TakeHistoryDialog(v2, id) { takeHistory = null } }
    if (addingResearcher && serverSessionId != null) {
        AddResearcherDialog(
            v2, serverSessionId, already = detail?.researchers.orEmpty().map { it.userId }.toSet(),
            onDismiss = { addingResearcher = false },
            onAdded = { addingResearcher = false; onError(QuestionnaireCopy.s("researcher.added")); scope.launch { reload() } },
            onError = onError
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
    if (addingGuest) GuestDialog(
        title = QuestionnaireCopy.s("guest.add").removePrefix("+ "), initialName = "", initialNotes = "",
        onDismiss = { addingGuest = false }
    ) { name, notes ->
        addingGuest = false
        val input = QRespondentInput(SessionOutbox.tidyName(name), RespondentKind.GUEST.wire, notes.trim().ifBlank { null })
        withUnit { key -> v2.outbox.addRespondent(key, QSubjects.newLocalGuest(), input) }
    }
    editingGuest?.let { subjectId ->
        val respondent = detail?.respondents?.firstOrNull { it.subjectId == subjectId }
        var mode by remember(subjectId) { mutableStateOf("menu") }
        when {
            respondent == null -> LaunchedEffect(subjectId) { editingGuest = null; onError(QuestionnaireCopy.s("error.needsSignal")) }
            mode == "edit" -> GuestDialog(
                title = QuestionnaireCopy.s("guest.edit"), initialName = respondent.name, initialNotes = respondent.notes.orEmpty(),
                onDismiss = { editingGuest = null }
            ) { name, notes ->
                editingGuest = null
                val sid = serverSessionId
                if (sid != null) online { v2.api.patchRespondent(sid, respondent.id, QRespondentPatch(name = name.trim(), notes = notes.trim())) }
            }
            mode == "register" -> RegisterGuestDialog(respondent.name, artisans, onDismiss = { editingGuest = null }) { artisanId ->
                editingGuest = null
                online {
                    v2.api.registerRespondent(respondent.id, QRespondentRegister(artisanId))
                    onError(QuestionnaireCopy.s("guest.registered"))
                }
            }
            else -> QuestionnairePrompt(
                title = respondent.name.ifBlank { QuestionnaireCopy.s("guest.label") },
                body = respondent.notes?.ifBlank { null } ?: QuestionnaireCopy.s("guest.label"),
                buttons = listOfNotNull(
                    PromptButton(QuestionnaireCopy.s("guest.edit")) { mode = "edit" },
                    if (respondent.kind == RespondentKind.GUEST.wire && respondent.artisanId == null) PromptButton(QuestionnaireCopy.s("guest.register"), primary = true) { mode = "register" } else null
                ),
                onDismiss = { editingGuest = null }
            )
        }
    }
    if (removingArtisan) {
        SearchableSelectSheet(
            title = QuestionnaireCopy.s("session.removeArtisan"),
            options = headerParticipants.filterNot { it.guest }.map { SelectOption(it.artisanId, it.name) },
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
    conflict?.let { fields -> SessionConflictPrompt(fields) { conflict = null } }
    if (editing) {
        val initial = SessionEdit.of(
            detail?.sessionDate ?: frozen?.sessionDate, detail?.purpose ?: frozen?.purpose, detail?.place ?: frozen?.place,
            detail?.languageCode ?: frozen?.languageCode, detail?.languageOther ?: frozen?.languageOther, detail?.note ?: frozen?.note,
            detail?.location ?: frozen?.location,
            detail?.crafts ?: frozen?.crafts.orEmpty(),
            detail?.craftsByHand ?: frozen?.craftsByHand.orEmpty(),
            recordBy
        )
        EditSessionDialog(initial, onDismiss = { editing = false }, extra = { SpokenLanguageField(spoken) }) { edit ->
            editing = false
            val d = detail
            if (d != null) online {
                val result = SessionStaleRetry.save(
                    version = d.version, initial = initial, mine = edit,
                    patch = { body -> v2.api.patchSession(d.id, body) },
                    fetch = { v2.api.session(d.id).let { fresh -> detail = fresh; fresh.version to fresh.asEdit() } },
                    isStale = { error -> error.isStaleVersion() }
                )
                if (result is SessionEditResult.Conflict) conflict = result.fields
            } else withUnit { key ->
                if (!v2.outbox.editPendingSession(key, edit::applyTo)) onError(QuestionnaireCopy.s("error.needsSignal"))
            }
        }
    }
    if (continuing && serverSessionId != null) {
        ContinueWithDialog(
            v2 = v2, title = QuestionnaireCopy.s("session.continue"), fromSessionId = serverSessionId,
            people = headerParticipants.filterNot { it.guest }.map { it.artisanId to it.name }, preselected = QSubjects.artisans(participantIds).toSet(),
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
            body = QuestionnaireCopy.s("session.softDeleteBody"),
            buttons = listOf(
                PromptButton(QuestionnaireCopy.s("session.cancel")) { deleting = false },
                PromptButton(QuestionnaireCopy.s("session.delete"), primary = true, destructive = true) {
                    scope.launch {
                        val response = runCatching { v2.api.deleteSession(serverSessionId) }.getOrNull()
                        deleting = false
                        when {
                            response == null -> onError(QuestionnaireCopy.s("error.needsSignal"))
                            response.isSuccessful -> { v2.outbox.forget(clientKey); onBack() }
                            else -> {
                                val raw = runCatching { response.errorBody()?.string() }.getOrNull()
                                onError(com.fieldrepository.app.data.httpRefusalSentence(response.code(), raw, QuestionnaireCopy.s("upload.waiting")))
                            }
                        }
                    }
                }
            ),
            onDismiss = { deleting = false }
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
                        if (c != null) v2.outbox.resolveConflict(key, row.stepId, c.artisanId, c.questionLineageId, keepMine = false)
                        else v2.outbox.discardStep(key, row.stepId)
                    }
                }
            ),
            onDismiss = { discard = null }
        )
    }
    if (leaving) LeaveSessionPrompt(pendingCount.coerceAtLeast(1), onStay = { leaving = false }) {
        leaving = false
        val s = RecordingService.state.value
        if (s is RecordingService.State.Recording && s.draft.sessionKey == clientKey) RecordingService.stop(context, "leave")
        onBack()
    }
}

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

@Composable
internal fun OutboxTray(
    rows: List<TrayRow>,
    onSendAgain: (TrayRow) -> Unit,
    onDiscard: (TrayRow) -> Unit,
    onKeepTheirs: (TrayRow) -> Unit,
    onKeepMine: (TrayRow) -> Unit,
    onSendWithoutConflicts: (stepId: String) -> Unit = {}
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
            OutboxTrayModel.conflictSteps(rows).forEach { stepId ->
                PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("tray.sendWithout")) { onSendWithoutConflicts(stepId) }))
            }
        }
    }
}

internal const val WORKSPACE_LEAD_ITEMS = 1

internal fun LazyListScope.accordionTail(listState: LazyListState, open: Boolean) {
    item("accordion-tail") {
        val density = LocalDensity.current
        val viewport = with(density) { listState.layoutInfo.viewportSize.height.toDp() }
        Spacer(Modifier.fillMaxWidth().height(if (open) viewport else 0.dp))
    }
}

@Composable
internal fun ScrollOpenSectionToTop(listState: LazyListState, openSection: String?, target: Int?) {
    LaunchedEffect(openSection, target) {
        val index = target ?: return@LaunchedEffect
        if (openSection == null) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > index }
        listState.animateScrollToItem(index)
    }
}

@Composable
internal fun SectionDock(code: String, onRecord: () -> Unit, onUpload: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.field.hairline),
        modifier = Modifier.fillMaxWidth().shadow(24.dp, shape, ambientColor = MaterialTheme.field.shadow, spotColor = MaterialTheme.field.shadow)
            .semantics { contentDescription = QuestionnaireCopy.t("sectionActions", "code" to code) }
            .testTag("questionnaire.dock")
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                QuestionnaireCopy.t("chipSection", "code" to code), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, color = MaterialTheme.field.muted
            )
            RecordUploadPair(
                recordLabel = QuestionnaireCopy.s("workspace.recordSection"), uploadLabel = QuestionnaireCopy.t("upload"),
                onRecord = onRecord, onUpload = onUpload
            )
        }
    }
}

@Composable
internal fun SectionLetter(code: String, tile: SectionTile, open: Boolean = false, size: Dp = 40.dp, corner: Dp = 12.dp) {
    val shape = RoundedCornerShape(corner)
    val (bg, fg) = when {
        open -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        tile == SectionTile.DONE -> MaterialTheme.field.successContainer to MaterialTheme.field.success
        tile == SectionTile.STARTED -> MaterialTheme.field.surface200 to MaterialTheme.colorScheme.onBackground
        else -> Color.Transparent to MaterialTheme.field.body
    }
    Box(
        Modifier.size(size).clip(shape).background(bg).then(if (!open && tile == SectionTile.FRESH) Modifier.border(1.dp, MaterialTheme.field.surface300, shape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
    }
}

@Composable
internal fun SectionCard(
    section: InstrumentSection,
    status: SectionStatus,
    open: Boolean,
    byQuestion: Boolean,
    onToggle: () -> Unit,
    pair: @Composable () -> Unit,
    body: @Composable () -> Unit
) {
    val visible = remember(section.id) { MutableTransitionState(open) }
    visible.targetState = open
    val carded = open || visible.currentState || !visible.isIdle
    val shape = RoundedCornerShape(if (byQuestion) 16.dp else 20.dp)
    val cardModifier = if (carded) {
        Modifier.padding(top = 8.dp).clip(shape)
            .background(if (byQuestion) MaterialTheme.field.surface100 else MaterialTheme.colorScheme.surface)
            .then(if (byQuestion) Modifier else Modifier.border(1.dp, MaterialTheme.field.surface300, shape))
    } else Modifier
    val label = listOf(section.code, section.title, status.subline()).joinToString(", ") + if (open) ". " + QuestionnaireCopy.t("closeSection") else ""
    Column(Modifier.fillMaxWidth().then(cardModifier).testTag("questionnaire.section.${section.code}")) {
        if (carded) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).background(MaterialTheme.field.surface100).clickable(onClick = onToggle)
                    .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = if (open) "expanded" else "collapsed" }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionLetter(section.code, status.tile, open = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        status.subline(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = if (status.done) MaterialTheme.field.success else MaterialTheme.colorScheme.primary
                    )
                }
                Icon(Icons.Filled.ExpandLess, contentDescription = null, tint = MaterialTheme.field.body)
            }
            if (byQuestion) HorizontalDivider(color = MaterialTheme.field.surface300)
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onToggle)
                    .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = "collapsed" }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionLetter(section.code, status.tile)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        section.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(status.subline(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
                }
                Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.field.muted)
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)
        }
        AnimatedVisibility(visibleState = visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 4.dp)) {
                pair()
                body()
            }
        }
    }
}

@Composable
internal fun QuestionRow(
    question: InstrumentQuestion,
    status: QuestionStatus,
    byQuestion: Boolean,
    locked: Boolean,
    typedPreview: String?,
    typing: Boolean,
    overflow: List<OverflowItem>,
    showPair: Boolean,
    showUpload: Boolean,
    onRecord: () -> Unit,
    onUpload: () -> Unit,
    onOpenTyped: () -> Unit,
    onOpenTakes: () -> Unit,
    editor: @Composable () -> Unit
) {
    val answered = status.answered
    val tappable = status.hasTake || status.state == QuestionState.IN_SECTION_TAKE
    Column(
        Modifier.fillMaxWidth().testTag("questionnaire.question.${question.displayCode}")
            .padding(top = if (byQuestion) 14.dp else 8.dp, bottom = if (byQuestion) 14.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (byQuestion) 10.dp else 4.dp)
    ) {
        if (byQuestion) {
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.field.body))
                    append(question.displayCode)
                    pop()
                    append(" · " + status.line())
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (answered) MaterialTheme.field.success else MaterialTheme.field.muted,
                modifier = if (tappable) Modifier.clickable(onClick = onOpenTakes) else Modifier
            )
        } else Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                question.displayCode,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (answered) MaterialTheme.field.successContainer else MaterialTheme.field.surface200)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = if (answered) MaterialTheme.field.success else MaterialTheme.field.body
            )
            Text(
                status.line(), modifier = Modifier.weight(1f).then(if (tappable) Modifier.clickable(onClick = onOpenTakes) else Modifier),
                style = MaterialTheme.typography.bodyMedium,
                color = if (answered) MaterialTheme.field.success else MaterialTheme.field.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            QuestionOverflow(question.displayCode, overflow, Modifier.offset(x = 12.dp))
        }
        Text(
            question.prompt + if (question.required) " *" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        question.helpText?.ifBlank { null }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
        if (typedPreview != null && !typing) Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpenTyped)
                .semantics(mergeDescendants = true) { contentDescription = QuestionnaireCopy.t("typedPreview", "text" to typedPreview) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Keyboard, contentDescription = null, tint = MaterialTheme.field.muted, modifier = Modifier.size(18.dp))
            Text(
                QuestionnaireCopy.t("typedPreview", "text" to typedPreview), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (typing) editor()
        if (byQuestion) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showPair) RecordUploadPair(
                recordLabel = QuestionnaireCopy.s("workspace.record"), uploadLabel = QuestionnaireCopy.t("upload"),
                onRecord = onRecord, onUpload = onUpload, height = 48.dp, showUpload = showUpload, modifier = Modifier.weight(1f)
            ) else Box(Modifier.weight(1f))
            QuestionOverflow(question.displayCode, overflow)
        }
    }
    HorizontalDivider(color = if (byQuestion) MaterialTheme.field.surface300 else MaterialTheme.field.hairline)
}

@Composable
internal fun AnswerEditor(
    question: InstrumentQuestion,
    roster: List<HeaderParticipant>,
    answerLines: List<AnswerLine>,
    currentText: (Set<String>) -> String,
    lockedFor: (Set<String>) -> String?,
    nameOf: (String) -> String,
    onCancel: () -> Unit,
    onSaveAnswer: (text: String, forArtisanIds: Set<String>, note: String?, richText: String?) -> Unit,
    onHistory: (AnswerLine) -> Unit
) {
    var answerFor by remember(question.lineageId) { mutableStateOf(ALL_ARTISANS) }
    var answer by remember(question.lineageId, answerFor) { mutableStateOf(currentText(answerForIds(roster, answerFor))) }
    var note by remember(question.lineageId, answerFor) { mutableStateOf("") }
    var noting by remember(question.lineageId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("questionnaire.answer.${question.displayCode}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        answerLines.forEach { a ->
            Text(
                listOfNotNull(QuestionnaireCopy.firstName(nameOf(a.artisanId)) + ": " + a.text, a.badge).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (a.onPhone) MaterialTheme.field.muted else MaterialTheme.field.body
            )
            a.note?.let { Text(QuestionnaireCopy.s("answer.note") + ": " + it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted) }
            a.lockedLine?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted) }
            if (a.answerId != null) TextButton(onClick = { onHistory(a) }) { Text(QuestionnaireCopy.s("answer.history"), style = MaterialTheme.typography.labelMedium) }
        }
        if (roster.size > 1) SearchableSelectField(
            label = QuestionnaireCopy.answerForLabel(),
            options = answerForOptions(roster),
            selectedValue = answerFor,
            includeNone = false,
            onSelect = { answerFor = it }
        )
        val ids = answerForIds(roster, answerFor)
        val lockedLine = lockedFor(ids)
        if (lockedLine != null) {
            Text(lockedLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
            PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("session.cancel"), onClick = onCancel)))
        } else {
            QuestionnairePortal.AnswerField(label = null, value = answer, onValueChange = { answer = it }, modifier = Modifier.fillMaxWidth())
            if (noting) OutlinedTextField(
                value = note, onValueChange = { note = it.take(2000) },
                label = { Text(QuestionnaireCopy.s("answer.note")) }, modifier = Modifier.fillMaxWidth()
            ) else TextButton(onClick = { noting = true }) { Text(QuestionnaireCopy.s("answer.noteAdd"), style = MaterialTheme.typography.labelMedium) }
            val parts = QuestionnairePortal.answerParts(answer)
            val changed = parts.text.isNotBlank() && (answer.trim() != currentText(ids) || note.isNotBlank())
            PromptButtons(listOfNotNull(
                PromptButton(QuestionnaireCopy.s("session.cancel"), onClick = onCancel),
                PromptButton(QuestionnaireCopy.s("save"), primary = true) {
                    if (changed) onSaveAnswer(parts.text.trim(), ids, note.trim().ifBlank { null }, parts.richText) else onCancel()
                }
            ))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutboxTraySheet(
    pill: SavePillState,
    rows: List<TrayRow>,
    onDismiss: () -> Unit,
    onSendAgain: (TrayRow) -> Unit,
    onDiscard: (TrayRow) -> Unit,
    onKeepTheirs: (TrayRow) -> Unit,
    onKeepMine: (TrayRow) -> Unit,
    onSendWithoutConflicts: (stepId: String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(QuestionnaireCopy.t("openUploads"), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            SavePill(pill, onOpen = {}, onRetry = { rows.filter { it.sendAgain }.forEach(onSendAgain) })
            if (rows.isNotEmpty()) OutboxTray(rows, onSendAgain, onDiscard, onKeepTheirs, onKeepMine, onSendWithoutConflicts)
        }
    }
}

private const val ALL_ARTISANS = "*"

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
        removable = mayRemove,
        targetKey = t.targetKey,
        chips = rec?.chips,
        canMakePrimary = mayMakePrimary,
        canPropose = rec?.transcript?.status == "COMPLETED",
        mediaFileId = rec?.mediaFileId
    )
}

@Composable
fun QuestionnairePromptContent(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit, buttons: List<PromptButton>) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { content() },
        confirmButton = { if (buttons.isNotEmpty()) PromptButtons(buttons) }
    )
}
