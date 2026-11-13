package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.data.AadhaarAtRest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

object StepKind {
    const val ARTISAN = "ARTISAN"
    const val SESSION = "SESSION"
    const val RESPONDENT = "RESPONDENT"
    const val PARTICIPANT = "PARTICIPANT"
    const val CLIP = "CLIP"
    const val ANSWERS = "ANSWERS"
    const val FINISH = "FINISH"
}

object StepState {
    const val PENDING = "PENDING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}

@Serializable
data class ClipPayload(
    val clientClipKey: String,
    val localPath: String,
    val sizeBytes: Long,
    val contentSha256: String? = null,
    val contentType: String = "audio/mp4",
    val extension: String = "m4a",
    val label: String,
    val recording: QCompleteRecording,
    val subjectArtisanIds: List<String> = emptyList(),
    val objectKey: String? = null,
    val uploadId: String? = null,
    val partSize: Long? = null,
    val partsDone: List<QPartDone> = emptyList(),
    val uploaded: Boolean = false,
    val completeFailures: Int = 0
) {
    val uploadedPercent: Int get() = when {
        uploaded -> 100
        sizeBytes <= 0 -> 0
        else -> ((partsDone.size.toLong() * (partSize ?: 0L)).coerceAtMost(sizeBytes) * 100 / sizeBytes).toInt()
    }
}

@Serializable
data class ParticipantChange(val artisanId: String, val action: String, val at: String? = null) {
    companion object {
        const val ADD = "add"
        const val LEAVE = "leave"
        const val JOIN = "join"
    }
}

@Serializable
data class SessionStep(
    val id: String,
    val kind: String,
    val deviceTime: String,
    val idempotencyKey: String,
    val bodyJson: String? = null,
    val localArtisanId: String? = null,
    val participant: ParticipantChange? = null,
    val clip: ClipPayload? = null,
    val state: String = StepState.PENDING,
    val failure: String? = null,
    val attempts: Int = 0,
    val resultId: String? = null,
    val failureCode: String? = null,
    val conflicts: List<QAnswerResult> = emptyList(),
    val respondentLocalIds: List<String> = emptyList()
)

@Serializable
data class SessionUnit(
    val clientKey: String,
    val ownerUserId: String? = null,
    val createdAt: String,
    val questionnaireId: String,
    val workshopId: String? = null,
    val steps: List<SessionStep> = emptyList(),
    val sessionId: String? = null,
    val artisanIds: Map<String, String> = emptyMap(),
    val touchedAt: String? = null,
    val savedAnswerVersions: Map<String, Int> = emptyMap(),
    val respondentIds: Map<String, String> = emptyMap()
) {
    fun resolve(subjectId: String): String = artisanIds[subjectId] ?: respondentIds[subjectId] ?: subjectId

    fun ordered(): List<SessionStep> = steps.sortedWith(
        compareBy<SessionStep> { rank(it.kind) }.thenBy { if (rank(it.kind) == 2 || rank(it.kind) == 3) it.deviceTime else "" }
    )

    val pendingClips: List<SessionStep> get() = steps.filter { it.kind == StepKind.CLIP && it.state != StepState.DONE }
    val uploadingClips: List<SessionStep> get() = steps.filter { it.kind == StepKind.CLIP && it.state == StepState.PENDING }
    val failedSteps: List<SessionStep> get() = steps.filter { it.state == StepState.FAILED }
    val isDone: Boolean get() = steps.all { it.state == StepState.DONE }
    val isFinished: Boolean get() = isDone && steps.any { it.kind == StepKind.FINISH && it.state == StepState.DONE }

    private fun rank(kind: String) = when (kind) {
        StepKind.ARTISAN -> 0
        StepKind.SESSION -> 1
        StepKind.RESPONDENT -> 2
        StepKind.PARTICIPANT, StepKind.CLIP -> 3
        StepKind.ANSWERS -> 4
        else -> 5
    }
}

interface SessionUnitStore {
    suspend fun all(): List<SessionUnit>
    suspend fun read(clientKey: String): SessionUnit?
    suspend fun write(unit: SessionUnit)
    suspend fun delete(clientKey: String)
}

class FileSessionUnitStore(private val dir: File) : SessionUnitStore {
    private val mutex = Mutex()
    private fun file(clientKey: String) = File(dir, clientKey.filter { it.isLetterOrDigit() || it == '-' } + ".json")

    override suspend fun all(): List<SessionUnit> = mutex.withLock {
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { opened(outboxJson.decodeFromString(SessionUnit.serializer(), it.readText())) }.getOrNull() }
            .sortedBy { it.createdAt }
    }

    override suspend fun read(clientKey: String): SessionUnit? = mutex.withLock {
        file(clientKey).takeIf { it.isFile }?.let {
            runCatching { opened(outboxJson.decodeFromString(SessionUnit.serializer(), it.readText())) }.getOrNull()
        }
    }

    override suspend fun write(unit: SessionUnit) = mutex.withLock {
        dir.mkdirs()
        writeFileAtomically(file(unit.clientKey), outboxJson.encodeToString(SessionUnit.serializer(), sealed(unit)).toByteArray())
    }

    private fun sealed(unit: SessionUnit): SessionUnit =
        unit.copy(steps = unit.steps.map { step -> step.bodyJson?.let { step.copy(bodyJson = AadhaarAtRest.sealPayload(it)) } ?: step })

    private fun opened(unit: SessionUnit): SessionUnit =
        unit.copy(steps = unit.steps.map { step -> step.bodyJson?.let { step.copy(bodyJson = AadhaarAtRest.openPayload(it)) } ?: step })

    override suspend fun delete(clientKey: String) {
        mutex.withLock { file(clientKey).delete() }
    }
}

class InMemorySessionUnitStore : SessionUnitStore {
    private val units = LinkedHashMap<String, SessionUnit>()
    private val mutex = Mutex()
    override suspend fun all() = mutex.withLock { units.values.sortedBy { it.createdAt } }
    override suspend fun read(clientKey: String) = mutex.withLock { units[clientKey] }
    override suspend fun write(unit: SessionUnit) = mutex.withLock { units[unit.clientKey] = unit }
    override suspend fun delete(clientKey: String) { mutex.withLock { units.remove(clientKey) } }
}

internal val outboxJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

interface SessionTransport {
    suspend fun createArtisan(idempotencyKey: String, bodyJson: String): String
    suspend fun createSession(idempotencyKey: String, body: QSessionCreate): QSession
    suspend fun addRespondents(idempotencyKey: String, sessionId: String, respondents: List<QRespondentInput>): List<QRespondent>
    suspend fun addParticipant(idempotencyKey: String, sessionId: String, artisanId: String)
    suspend fun patchParticipant(sessionId: String, artisanId: String, patch: QParticipantPatch)
    suspend fun uploadState(clientClipKey: String): QUploadState?
    suspend fun presign(body: QPresignRequest): QPresignResponse
    suspend fun multipartCreate(body: QPresignRequest): QMultipartCreateResponse
    suspend fun presignParts(objectKey: String, uploadId: String, partNumbers: List<Int>): Map<Int, String>
    suspend fun put(url: String, headers: Map<String, String>, file: File, offset: Long, length: Long, contentType: String): String
    suspend fun multipartComplete(objectKey: String, uploadId: String, parts: List<QPartDone>)
    suspend fun complete(idempotencyKey: String, body: QCompleteRequest): QRecording
    suspend fun putAnswers(body: QAnswersPut): QAnswersPutResponse
    suspend fun finish(sessionId: String)
}

class PermanentStepFailure(message: String, val code: String? = null) : Exception(message)

class SignedUrlExpired(message: String) : IOException(message)

class UploadMissing(message: String) : IOException(message) {
    companion object { const val REASON = "UPLOAD_MISSING" }
}

class MissingSessionUnit(clientKey: String) : IllegalStateException("No queued session $clientKey")

class SessionOutbox(
    private val store: SessionUnitStore,
    private val clock: () -> Instant = Instant::now,
    private val multipartThresholdBytes: Long = 5L * 1024 * 1024,
    private val defaultPartSize: Long = 8L * 1024 * 1024,
    private val onClipLanded: (clientClipKey: String) -> Unit = {}
) {
    companion object {
        const val MAX_PARALLEL = 3
        const val SLOW_LINK_BYTES_PER_SECOND = 6_250.0
        val STALE_AFTER: java.time.Duration = java.time.Duration.ofDays(14)
        const val COMPLETE_RESTART_AFTER = 3

        fun missingFileMessage(label: String): String {
            val open = label.lastIndexOf(" (")
            val (target, date) = if (open > 0 && label.endsWith(")")) label.substring(0, open) to label.substring(open + 2, label.length - 1) else label to ""
            val text = QuestionnaireCopy.t("missingOutboxFile", "target" to target, "date" to date)
            return if (date.isEmpty()) text.replace(" ()", "") else text
        }

        fun matchRespondents(localIds: List<String>, inputs: List<QRespondentInput>, server: List<QRespondent>, claimed: Set<String> = emptySet()): Map<String, String> {
            val taken = claimed.toMutableSet()
            val out = LinkedHashMap<String, String>()
            localIds.zip(inputs).forEach { (localId, input) ->
                val name = tidyName(input.name)
                val row = server.firstOrNull { r ->
                    r.id !in taken && r.kind == input.kind && (input.kind == RespondentKind.SELF.wire || tidyName(r.name) == name)
                } ?: return@forEach
                taken += row.id
                out[localId] = row.subjectId
            }
            return out
        }

        fun tidyName(name: String?): String = name.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

        fun withAnswered(subjects: List<QCompleteSubject>, answeredIds: Set<String>): List<QCompleteSubject> = subjects.map {
            it.copy(
                role = if (it.artisanId in answeredIds) SubjectRole.ANSWERED.wire else SubjectRole.PRESENT.wire,
                named = answeredIds.size < subjects.size && it.artisanId in answeredIds
            )
        }
        fun newKey(): String = UUID.randomUUID().toString()

        fun answerKey(artisanId: String, questionLineageId: String) = "$artisanId|$questionLineageId"
    }

    private val passLock = Mutex()
    private val unitLocks = HashMap<String, Mutex>()
    private fun lockFor(clientKey: String) = synchronized(unitLocks) { unitLocks.getOrPut(clientKey) { Mutex() } }

    @Volatile
    var measuredThroughput: Double? = null
        private set

    fun concurrency(): Int = measuredThroughput?.let { if (it < SLOW_LINK_BYTES_PER_SECOND) 1 else MAX_PARALLEL } ?: MAX_PARALLEL

    suspend fun startSession(
        body: QSessionCreate,
        ownerUserId: String?,
        localArtisans: List<Pair<String, String>> = emptyList(),
        guestIds: List<String> = emptyList()
    ): SessionUnit {
        require(guestIds.size == body.respondents.size) { "one local id per guest" }
        val now = clock().toString()
        val steps = localArtisans.map { (localId, artisanBody) ->
            SessionStep(newKey(), StepKind.ARTISAN, now, newKey(), bodyJson = artisanBody, localArtisanId = localId)
        } + SessionStep(
            newKey(), StepKind.SESSION, now, newKey(),
            bodyJson = outboxJson.encodeToString(QSessionCreate.serializer(), body), respondentLocalIds = guestIds
        )
        val unit = SessionUnit(
            clientKey = body.clientKey, ownerUserId = ownerUserId, createdAt = now,
            questionnaireId = body.questionnaireId, workshopId = body.workshopId, steps = steps
        )
        store.write(unit)
        return unit
    }

    suspend fun adoptSession(session: QSession, questionnaireId: String, ownerUserId: String?): SessionUnit {
        val key = session.clientKey ?: session.id
        store.read(key)?.let { return it }
        val unit = SessionUnit(
            clientKey = key, ownerUserId = ownerUserId, createdAt = clock().toString(),
            questionnaireId = questionnaireId, workshopId = session.workshop?.id, sessionId = session.id,
            touchedAt = clock().toString()
        )
        store.write(unit)
        return unit
    }

    suspend fun reopen(known: SessionUnit): SessionUnit = lockFor(known.clientKey).withLock {
        store.read(known.clientKey) ?: known.copy(
            steps = known.steps.map { it.copy(state = StepState.DONE) },
            touchedAt = clock().toString()
        ).also { store.write(it) }
    }

    suspend fun addRespondent(clientKey: String, localId: String, input: QRespondentInput) = mutate(clientKey) { unit ->
        val sessionStep = unit.steps.firstOrNull { it.kind == StepKind.SESSION && it.state == StepState.PENDING }
        if (sessionStep != null) {
            val frozen = outboxJson.decodeFromString(QSessionCreate.serializer(), sessionStep.bodyJson!!)
            unit.copy(steps = unit.steps.map {
                if (it.id != sessionStep.id) it
                else it.copy(
                    bodyJson = outboxJson.encodeToString(QSessionCreate.serializer(), frozen.copy(respondents = frozen.respondents + input)),
                    respondentLocalIds = it.respondentLocalIds + localId
                )
            })
        } else {
            unit.copy(steps = unit.steps + SessionStep(
                newKey(), StepKind.RESPONDENT, clock().toString(), newKey(),
                bodyJson = outboxJson.encodeToString(QRespondentsAdd.serializer(), QRespondentsAdd(listOf(input))),
                respondentLocalIds = listOf(localId)
            ))
        }
    }

    fun guestsOf(unit: SessionUnit): List<Pair<String, String>> = unit.steps.flatMap { step ->
        val inputs = when (step.kind) {
            StepKind.SESSION -> step.bodyJson?.let { runCatching { outboxJson.decodeFromString(QSessionCreate.serializer(), it).respondents }.getOrNull() }
            StepKind.RESPONDENT -> step.bodyJson?.let { runCatching { outboxJson.decodeFromString(QRespondentsAdd.serializer(), it).respondents }.getOrNull() }
            else -> null
        }.orEmpty()
        step.respondentLocalIds.zip(inputs).map { (id, input) -> unit.resolve(id) to tidyName(input.name) }
    }

    suspend fun addParticipantChange(clientKey: String, change: ParticipantChange) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps + SessionStep(newKey(), StepKind.PARTICIPANT, change.at ?: clock().toString(), newKey(), participant = change))
    }

    suspend fun addClip(clientKey: String, clip: ClipPayload) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps + SessionStep(newKey(), StepKind.CLIP, clip.recording.deviceCapturedAt, newKey(), clip = clip))
    }

    suspend fun removePendingParticipant(clientKey: String, artisanId: String): Boolean {
        var changed = false
        mutate(clientKey) { unit ->
            val sessionStep = unit.steps.firstOrNull { it.kind == StepKind.SESSION && it.state == StepState.PENDING } ?: return@mutate unit
            val frozen = outboxJson.decodeFromString(QSessionCreate.serializer(), sessionStep.bodyJson!!)
            val left = frozen.participants.filterNot { it.artisanId == artisanId }
            if (left.isEmpty()) return@mutate unit
            changed = true
            unit.copy(steps = unit.steps.mapNotNull { step ->
                when {
                    step.id == sessionStep.id -> step.copy(bodyJson = outboxJson.encodeToString(QSessionCreate.serializer(), frozen.copy(participants = left)))
                    step.kind == StepKind.PARTICIPANT && step.state != StepState.DONE && step.participant?.artisanId == artisanId -> null
                    else -> step
                }
            })
        }
        return changed
    }

    suspend fun editPendingSession(clientKey: String, edit: (QSessionCreate) -> QSessionCreate): Boolean {
        var changed = false
        mutate(clientKey) { unit ->
            val sessionStep = unit.steps.firstOrNull { it.kind == StepKind.SESSION && it.state == StepState.PENDING } ?: return@mutate unit
            val frozen = outboxJson.decodeFromString(QSessionCreate.serializer(), sessionStep.bodyJson!!)
            changed = true
            unit.copy(steps = unit.steps.map {
                if (it.id == sessionStep.id) it.copy(bodyJson = outboxJson.encodeToString(QSessionCreate.serializer(), edit(frozen))) else it
            })
        }
        return changed
    }

    suspend fun forget(clientKey: String) = lockFor(clientKey).withLock { store.delete(clientKey) }

    suspend fun addAnswers(clientKey: String, body: QAnswersPut) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps + SessionStep(newKey(), StepKind.ANSWERS, clock().toString(), newKey(), bodyJson = outboxJson.encodeToString(QAnswersPut.serializer(), body)))
    }

    suspend fun finishSession(clientKey: String) = mutate(clientKey) { unit ->
        if (unit.steps.any { it.kind == StepKind.FINISH }) unit
        else unit.copy(steps = unit.steps + SessionStep(newKey(), StepKind.FINISH, clock().toString(), newKey()))
    }

    suspend fun updateClipSubjects(clientKey: String, clientClipKey: String, answeredIds: Set<String>): Boolean {
        var changed = false
        mutate(clientKey) { unit ->
            unit.copy(steps = unit.steps.map { step ->
                val clip = step.clip
                if (clip == null || clip.clientClipKey != clientClipKey || step.state == StepState.DONE) step
                else {
                    changed = true
                    step.copy(clip = clip.copy(recording = clip.recording.copy(subjects = withAnswered(clip.recording.subjects, answeredIds))))
                }
            })
        }
        return changed
    }

    suspend fun units(): List<SessionUnit> = store.all()
    suspend fun unit(clientKey: String): SessionUnit? = store.read(clientKey)

    suspend fun retryStep(clientKey: String, stepId: String) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps.map {
            if (it.id == stepId) it.copy(state = StepState.PENDING, failure = null, failureCode = null, attempts = 0) else it
        })
    }

    suspend fun discardStep(clientKey: String, stepId: String) = mutate(clientKey) { unit ->
        unit.steps.firstOrNull { it.id == stepId }?.clip?.localPath?.let { File(it).delete() }
        unit.copy(steps = unit.steps.filterNot { it.id == stepId })
    }

    suspend fun resolveConflict(clientKey: String, stepId: String, artisanId: String, questionLineageId: String, keepMine: Boolean) =
        mutate(clientKey) { unit ->
            val step = unit.steps.firstOrNull { it.id == stepId } ?: return@mutate unit
            val body = step.bodyJson?.let { runCatching { outboxJson.decodeFromString(QAnswersPut.serializer(), it) }.getOrNull() }
                ?: return@mutate unit
            fun same(a: String, lineage: String) = unit.resolve(a) == unit.resolve(artisanId) && lineage == questionLineageId
            val current = step.conflicts.firstOrNull { same(it.artisanId, it.questionLineageId) }?.current
            val items = body.items.mapNotNull { item ->
                when {
                    !same(item.artisanId, item.questionLineageId) -> item
                    keepMine -> item.copy(baseVersion = current?.version)
                    else -> null
                }
            }
            val left = step.conflicts.filterNot { same(it.artisanId, it.questionLineageId) }
            val encoded = outboxJson.encodeToString(QAnswersPut.serializer(), QAnswersPut(items))
            val next = when {
                items.isEmpty() -> null
                left.isNotEmpty() -> step.copy(bodyJson = encoded, conflicts = left)
                else -> step.copy(bodyJson = encoded, conflicts = emptyList(), state = StepState.PENDING, failure = null, failureCode = null, attempts = 0)
            }
            unit.copy(steps = unit.steps.mapNotNull { if (it.id == stepId) next else it })
        }

    suspend fun dropConflicts(clientKey: String, stepId: String) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps.filterNot { it.id == stepId && it.kind == StepKind.ANSWERS && it.state == StepState.FAILED })
    }

    private suspend fun mutate(clientKey: String, change: (SessionUnit) -> SessionUnit): SessionUnit = lockFor(clientKey).withLock {
        val current = store.read(clientKey) ?: throw MissingSessionUnit(clientKey)
        change(current).copy(touchedAt = clock().toString()).also { store.write(it) }
    }

    private suspend fun updateStep(clientKey: String, stepId: String, change: (SessionStep) -> SessionStep): SessionUnit =
        mutate(clientKey) { unit -> unit.copy(steps = unit.steps.map { if (it.id == stepId) change(it) else it }) }

    data class PassResult(val sent: Int, val failed: Int, val remaining: Int)

    suspend fun sync(transport: SessionTransport, ownerUserId: String?): PassResult = passLock.withLock {
        var sent = 0; var failed = 0; var remaining = 0
        for (unit in store.all()) {
            if (unit.ownerUserId != null && ownerUserId != null && unit.ownerUserId != ownerUserId) continue
            val result = try {
                syncUnit(transport, unit.clientKey)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (other: Exception) {
                PassResult(0, 0, unit.steps.count { it.state == StepState.PENDING }.coerceAtLeast(1))
            }
            sent += result.sent; failed += result.failed; remaining += result.remaining
        }
        PassResult(sent, failed, remaining)
    }

    suspend fun syncUnit(transport: SessionTransport, clientKey: String): PassResult {
        var sent = 0
        var unit = store.read(clientKey) ?: return PassResult(0, 0, 0)
        val ordered = unit.ordered()
        var index = 0
        while (index < ordered.size) {
            val step = ordered[index]
            if (step.kind == StepKind.CLIP) {
                val run = ordered.drop(index).takeWhile { it.kind == StepKind.CLIP }
                index += run.size
                val todo = run.filter { it.state == StepState.PENDING }
                if (todo.isEmpty()) continue
                if (unit.sessionId == null) break
                val gate = Semaphore(concurrency())
                val results = coroutineScope {
                    todo.map { clipStep -> async { gate.withPermit { runClip(transport, clientKey, clipStep.id) } } }.awaitAll()
                }
                sent += results.count { it }
                unit = store.read(clientKey) ?: return PassResult(sent, 0, 0)
                continue
            }
            index++
            if (step.state != StepState.PENDING) {
                if (step.state == StepState.FAILED && (step.kind == StepKind.ARTISAN || step.kind == StepKind.SESSION)) break
                continue
            }
            val ok = runOne(transport, clientKey, step.id)
            unit = store.read(clientKey) ?: return PassResult(sent, 0, 0)
            if (ok) sent++
            else if (step.kind == StepKind.ARTISAN || step.kind == StepKind.SESSION) break
            else if (unit.steps.firstOrNull { it.id == step.id }?.state == StepState.PENDING) break
        }
        unit = store.read(clientKey) ?: return PassResult(sent, 0, 0)
        val failed = unit.steps.count { it.state == StepState.FAILED }
        val remaining = unit.steps.count { it.state == StepState.PENDING }
        if (settled(unit)) store.delete(clientKey)
        return PassResult(sent, failed, remaining)
    }

    internal fun settled(unit: SessionUnit): Boolean {
        if (!unit.isDone) return false
        if (unit.isFinished) return true
        val last = runCatching { Instant.parse(unit.touchedAt ?: unit.createdAt) }.getOrNull() ?: return false
        return java.time.Duration.between(last, clock()) > STALE_AFTER
    }

    private suspend fun runOne(transport: SessionTransport, clientKey: String, stepId: String): Boolean {
        val unit = store.read(clientKey) ?: return false
        val step = unit.steps.first { it.id == stepId }
        return try {
            when (step.kind) {
                StepKind.ARTISAN -> {
                    if (AadhaarAtRest.numberWithheld(step.bodyJson!!)) throw PermanentStepFailure(AadhaarAtRest.RETYPE_REASON)
                    val serverId = transport.createArtisan(step.idempotencyKey, step.bodyJson!!)
                    mutate(clientKey) { u ->
                        u.copy(
                            artisanIds = u.artisanIds + (step.localArtisanId!! to serverId),
                            steps = u.steps.map { if (it.id == stepId) it.copy(state = StepState.DONE, resultId = serverId) else it }
                        )
                    }
                }
                StepKind.SESSION -> {
                    val frozen = outboxJson.decodeFromString(QSessionCreate.serializer(), step.bodyJson!!)
                    val body = frozen.copy(participants = frozen.participants.map { QArtisanRef(unit.resolve(it.artisanId)) })
                    val session = transport.createSession(step.idempotencyKey, body)
                    val guests = matchRespondents(step.respondentLocalIds, frozen.respondents, session.respondents)
                    mutate(clientKey) { u ->
                        u.copy(
                            sessionId = session.id, respondentIds = u.respondentIds + guests,
                            steps = u.steps.map { if (it.id == stepId) it.copy(state = StepState.DONE, resultId = session.id) else it }
                        )
                    }
                }
                StepKind.RESPONDENT -> {
                    val sessionId = unit.sessionId ?: return false
                    val frozen = outboxJson.decodeFromString(QRespondentsAdd.serializer(), step.bodyJson!!)
                    val rows = transport.addRespondents(step.idempotencyKey, sessionId, frozen.respondents)
                    val claimed = unit.respondentIds.values.mapNotNull(QSubjects::respondentOf).toSet()
                    val guests = matchRespondents(step.respondentLocalIds, frozen.respondents, rows, claimed)
                    mutate(clientKey) { u ->
                        u.copy(respondentIds = u.respondentIds + guests, steps = u.steps.map { if (it.id == stepId) it.copy(state = StepState.DONE) else it })
                    }
                }
                StepKind.PARTICIPANT -> {
                    val sessionId = unit.sessionId ?: return false
                    val change = step.participant!!
                    val artisanId = unit.resolve(change.artisanId)
                    when (change.action) {
                        ParticipantChange.ADD -> transport.addParticipant(step.idempotencyKey, sessionId, artisanId)
                        ParticipantChange.LEAVE -> transport.patchParticipant(sessionId, artisanId, QParticipantPatch(leftAt = change.at))
                        else -> transport.patchParticipant(sessionId, artisanId, QParticipantPatch(joinedAt = change.at))
                    }
                    updateStep(clientKey, stepId) { it.copy(state = StepState.DONE) }
                }
                StepKind.ANSWERS -> {
                    val sessionId = unit.sessionId ?: return false
                    val frozen = outboxJson.decodeFromString(QAnswersPut.serializer(), step.bodyJson!!)
                    val body = QAnswersPut(frozen.items.map {
                        val artisanId = unit.resolve(it.artisanId)
                        it.copy(
                            artisanId = artisanId,
                            sessionId = if (it.sessionId.isBlank() || it.sessionId == unit.clientKey) sessionId else it.sessionId,
                            baseVersion = listOfNotNull(unit.savedAnswerVersions[answerKey(artisanId, it.questionLineageId)], it.baseVersion).maxOrNull()
                        )
                    })
                    val response = transport.putAnswers(body)
                    val saved = response.results.filter { (it.status == "SAVED" || it.status == "UNCHANGED") && it.answer != null }
                    if (saved.isNotEmpty()) mutate(clientKey) { u ->
                        u.copy(savedAnswerVersions = u.savedAnswerVersions + saved.associate { answerKey(it.artisanId, it.questionLineageId) to it.answer!!.version })
                    }
                    val conflicts = response.results.filter { it.status == "CONFLICT" || it.status == "FORBIDDEN" }
                    updateStep(clientKey, stepId) {
                        if (conflicts.isEmpty()) it.copy(state = StepState.DONE)
                        else it.copy(
                            state = StepState.FAILED, failure = "CONFLICT",
                            failureCode = if (conflicts.all { c -> c.status == "FORBIDDEN" }) "FORBIDDEN" else "CONFLICT",
                            conflicts = conflicts,
                            bodyJson = outboxJson.encodeToString(QAnswersPut.serializer(), QAnswersPut(frozen.items.filter { item -> conflicts.any { c -> c.questionLineageId == item.questionLineageId && c.artisanId == unit.resolve(item.artisanId) } }))
                        )
                    }
                }
                StepKind.FINISH -> {
                    val sessionId = unit.sessionId ?: return false
                    if (unit.steps.any { it.kind != StepKind.FINISH && it.state == StepState.PENDING }) return false
                    transport.finish(sessionId)
                    updateStep(clientKey, stepId) { it.copy(state = StepState.DONE) }
                }
                else -> return false
            }
            true
        } catch (refused: PermanentStepFailure) {
            updateStep(clientKey, stepId) { it.copy(state = StepState.FAILED, failure = refused.message, failureCode = refused.code, attempts = it.attempts + 1) }
            false
        } catch (transient: IOException) {
            updateStep(clientKey, stepId) { it.copy(attempts = it.attempts + 1) }
            false
        }
    }

    private suspend fun runClip(transport: SessionTransport, clientKey: String, stepId: String): Boolean {
        val unit = store.read(clientKey) ?: return false
        val sessionId = unit.sessionId ?: return false
        val step = unit.steps.first { it.id == stepId }
        var clip = step.clip ?: return false
        val file = File(clip.localPath)
        if (!file.isFile) {
            updateStep(clientKey, stepId) { it.copy(state = StepState.FAILED, failure = missingFileMessage(clip.label)) }
            return false
        }
        return try {
            if (!clip.uploaded) {
                clip = upload(transport, clientKey, stepId, sessionId, unit, clip, file)
            }
            val recording = clip.recording.copy(
                subjects = clip.recording.subjects.map { it.copy(artisanId = unit.resolve(it.artisanId)) }
            )
            val done = transport.complete(
                step.idempotencyKey,
                QCompleteRequest(objectKey = clip.objectKey!!, clientClipKey = clip.clientClipKey, linkedRecordId = sessionId, recording = recording)
            )
            updateStep(clientKey, stepId) { it.copy(state = StepState.DONE, resultId = done.id) }
            File(clip.localPath).delete()
            onClipLanded(clip.clientClipKey)
            true
        } catch (refused: PermanentStepFailure) {
            updateStep(clientKey, stepId) { it.copy(state = StepState.FAILED, failure = refused.message, failureCode = refused.code, attempts = it.attempts + 1) }
            false
        } catch (missing: UploadMissing) {
            updateStep(clientKey, stepId) {
                it.copy(
                    attempts = it.attempts + 1,
                    clip = it.clip?.copy(uploaded = false, objectKey = null, uploadId = null, partSize = null, partsDone = emptyList(), completeFailures = 0)
                )
            }
            false
        } catch (transient: IOException) {
            updateStep(clientKey, stepId) { it.copy(attempts = it.attempts + 1) }
            false
        }
    }

    private suspend fun upload(
        transport: SessionTransport, clientKey: String, stepId: String, sessionId: String,
        unit: SessionUnit, start: ClipPayload, file: File
    ): ClipPayload {
        var clip = start
        val presign = QPresignRequest(
            sessionId = sessionId, clientClipKey = clip.clientClipKey, sizeBytes = clip.sizeBytes,
            contentType = clip.contentType, extension = clip.extension, contentSha256 = clip.contentSha256,
            targets = clip.recording.targets, subjectArtisanIds = QSubjects.artisans(clip.subjectArtisanIds.map(unit::resolve)),
            deviceCapturedAt = clip.recording.deviceCapturedAt, clockOffsetMs = clip.recording.clockOffsetMs,
            clockOffsetMeasuredAt = clip.recording.clockOffsetMeasuredAt, continuationOfId = clip.recording.continuationOfId
        )
        suspend fun save(next: ClipPayload): ClipPayload {
            updateStep(clientKey, stepId) { it.copy(clip = next) }
            return next
        }
        val startedAt = System.nanoTime()
        var bytesSent = 0L
        if (clip.sizeBytes <= multipartThresholdBytes) {
            var signed = transport.presign(presign)
            if (!signed.closed) {
                signAgainOnce(
                    { transport.put(signed.url, signed.headers, file, 0, clip.sizeBytes, clip.contentType) },
                    { signed = transport.presign(presign); transport.put(signed.url, signed.headers, file, 0, clip.sizeBytes, clip.contentType) }
                )
                bytesSent = clip.sizeBytes
            }
            clip = save(clip.copy(objectKey = signed.objectKey, uploaded = true))
        } else {
            if (clip.uploadId == null) {
                val known = transport.uploadState(clip.clientClipKey)
                clip = if (known?.multipartUploadId != null && !known.closed) {
                    save(clip.copy(objectKey = known.objectKey, uploadId = known.multipartUploadId, partSize = known.partSize, partsDone = known.partsDone))
                } else {
                    val created = transport.multipartCreate(presign)
                    if (created.closed) return save(clip.copy(objectKey = created.objectKey, uploaded = true))
                    save(clip.copy(objectKey = created.objectKey, uploadId = created.uploadId, partSize = created.partSize, partsDone = created.partsDone))
                }
            }
            val partSize = clip.partSize ?: defaultPartSize
            val partCount = ((clip.sizeBytes + partSize - 1) / partSize).toInt()
            val missing = (1..partCount).filter { n -> clip.partsDone.none { it.partNumber == n } }
            for (n in missing) {
                val offset = (n - 1) * partSize
                val length = minOf(partSize, clip.sizeBytes - offset)
                val objectKey = clip.objectKey!!
                val uploadId = clip.uploadId!!
                val putPart: suspend () -> String = {
                    transport.put(transport.presignParts(objectKey, uploadId, listOf(n)).getValue(n), emptyMap(), file, offset, length, clip.contentType)
                }
                val etag = signAgainOnce(putPart, putPart)
                bytesSent += length
                clip = save(clip.copy(partsDone = clip.partsDone + QPartDone(n, etag)))
            }
            try {
                transport.multipartComplete(clip.objectKey!!, clip.uploadId!!, clip.partsDone.sortedBy { it.partNumber })
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is PermanentStepFailure) throw failure
                val tries = clip.completeFailures + 1
                save(
                    if (tries >= COMPLETE_RESTART_AFTER) clip.copy(uploadId = null, partsDone = emptyList(), partSize = null, completeFailures = 0)
                    else clip.copy(completeFailures = tries)
                )
                throw IOException(failure.message, failure)
            }
            clip = save(clip.copy(uploaded = true, completeFailures = 0))
        }
        val seconds = (System.nanoTime() - startedAt) / 1e9
        if (bytesSent > 0 && seconds > 0.05) measuredThroughput = bytesSent / seconds
        return clip
    }

    private suspend fun <T> signAgainOnce(first: suspend () -> T, again: suspend () -> T): T = try {
        first()
    } catch (expired: SignedUrlExpired) {
        try {
            again()
        } catch (still: SignedUrlExpired) {
            throw PermanentStepFailure(still.message ?: "The upload link expired. Try again.")
        }
    }
}

internal fun encodeUnits(units: List<SessionUnit>): String = outboxJson.encodeToString(ListSerializer(SessionUnit.serializer()), units)
