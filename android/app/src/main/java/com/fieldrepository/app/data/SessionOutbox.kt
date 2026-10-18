package com.fieldrepository.app.data

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

/**
 * Questionnaire v2 outbox: ONE ordered unit per session (DESIGN §6.1.5).
 *
 * Steps run in this order: `artisan(localId)*` → `session(clientKey)` → `participant*` and `clip*`
 * by device time → `answers`. Online is the same queue running at once; nothing has a second path.
 *
 * - Every step is ticked off as it lands and never sent again ([StepState.DONE]).
 * - Every create carries the Idempotency-Key minted when it was queued, and its body is frozen then.
 * - A failed clip blocks nothing: the next clips and the answers still go. A failed artisan or
 *   session step stops the unit, because everything after it needs the server's id.
 * - Clips between two non-clip steps upload in parallel, at most 3 at once, and 1 once the measured
 *   throughput falls below about 50 kbps.
 * - Above the multipart threshold a clip uploads in parts. Each part's ETag is written to the unit as
 *   it lands, so a dropped network resumes at the next part instead of starting over.
 * - A clip whose file is gone fails with "The file for Section D (7 Jul) is missing on this device."
 *   and is never ticked.
 */
object StepKind {
    const val ARTISAN = "ARTISAN"
    const val SESSION = "SESSION"
    const val PARTICIPANT = "PARTICIPANT"
    const val CLIP = "CLIP"
    const val ANSWERS = "ANSWERS"
    /** Finish, queued last so every take and answer lands before the session closes. */
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
    /** For copy only ("Section D (7 Jul)"); never sent as a file name. */
    val label: String,
    val recording: QCompleteRecording,
    val subjectArtisanIds: List<String> = emptyList(),
    // Upload progress, written back as it lands.
    val objectKey: String? = null,
    val uploadId: String? = null,
    val partSize: Long? = null,
    val partsDone: List<QPartDone> = emptyList(),
    val uploaded: Boolean = false
)

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
    /** Device time the step was made; orders participant and clip steps against each other. */
    val deviceTime: String,
    val idempotencyKey: String,
    /** The frozen request body (artisan create, session create, answers). */
    val bodyJson: String? = null,
    val localArtisanId: String? = null,
    val participant: ParticipantChange? = null,
    val clip: ClipPayload? = null,
    val state: String = StepState.PENDING,
    val failure: String? = null,
    val attempts: Int = 0,
    val resultId: String? = null
)

@Serializable
data class SessionUnit(
    val clientKey: String,
    val ownerUserId: String? = null,
    val createdAt: String,
    val questionnaireId: String,
    val workshopId: String? = null,
    val steps: List<SessionStep> = emptyList(),
    /** The server's session id once the session step has landed. */
    val sessionId: String? = null,
    /** Offline-registered artisans: local id → server id, filled as their steps land. */
    val artisanIds: Map<String, String> = emptyMap()
) {
    fun resolve(artisanId: String): String = artisanIds[artisanId] ?: artisanId

    /** Steps in replay order (§6.1.5). */
    fun ordered(): List<SessionStep> = steps.sortedWith(
        compareBy<SessionStep> { rank(it.kind) }.thenBy { if (rank(it.kind) == 2) it.deviceTime else "" }
    )

    val pendingClips: List<SessionStep> get() = steps.filter { it.kind == StepKind.CLIP && it.state != StepState.DONE }
    val isDone: Boolean get() = steps.all { it.state == StepState.DONE }

    private fun rank(kind: String) = when (kind) {
        StepKind.ARTISAN -> 0
        StepKind.SESSION -> 1
        StepKind.PARTICIPANT, StepKind.CLIP -> 2
        StepKind.ANSWERS -> 3
        else -> 4
    }
}

/** Where units live. A file per unit on the handset; a map in tests. */
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
            .mapNotNull { runCatching { outboxJson.decodeFromString(SessionUnit.serializer(), it.readText()) }.getOrNull() }
            .sortedBy { it.createdAt }
    }

    override suspend fun read(clientKey: String): SessionUnit? = mutex.withLock {
        file(clientKey).takeIf { it.isFile }?.let {
            runCatching { outboxJson.decodeFromString(SessionUnit.serializer(), it.readText()) }.getOrNull()
        }
    }

    override suspend fun write(unit: SessionUnit) = mutex.withLock {
        dir.mkdirs()
        writeFileAtomically(file(unit.clientKey), outboxJson.encodeToString(SessionUnit.serializer(), unit).toByteArray())
    }

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

/** The network primitives the engine drives. [QuestionnaireTransport] implements them for real. */
interface SessionTransport {
    suspend fun createArtisan(idempotencyKey: String, bodyJson: String): String
    suspend fun createSession(idempotencyKey: String, body: QSessionCreate): QSession
    suspend fun addParticipant(idempotencyKey: String, sessionId: String, artisanId: String)
    suspend fun patchParticipant(sessionId: String, artisanId: String, patch: QParticipantPatch)
    suspend fun uploadState(clientClipKey: String): QUploadState?
    suspend fun presign(body: QPresignRequest): QPresignResponse
    suspend fun multipartCreate(body: QPresignRequest): QMultipartCreateResponse
    suspend fun presignParts(objectKey: String, uploadId: String, partNumbers: List<Int>): Map<Int, String>
    /** PUT [length] bytes of [file] from [offset] to [url]; returns the ETag. */
    suspend fun put(url: String, headers: Map<String, String>, file: File, offset: Long, length: Long, contentType: String): String
    suspend fun multipartComplete(objectKey: String, uploadId: String, parts: List<QPartDone>)
    suspend fun complete(idempotencyKey: String, body: QCompleteRequest): QRecording
    suspend fun putAnswers(body: QAnswersPut): QAnswersPutResponse
    suspend fun finish(sessionId: String)
}

/** A refusal that no retry will change (4xx other than 408/429). */
class PermanentStepFailure(message: String, val code: String? = null) : Exception(message)

class SessionOutbox(
    private val store: SessionUnitStore,
    private val clock: () -> Instant = Instant::now,
    private val multipartThresholdBytes: Long = 5L * 1024 * 1024,
    private val defaultPartSize: Long = 8L * 1024 * 1024
) {
    companion object {
        const val MAX_PARALLEL = 3
        /** About 50 kbps. Below this the clips go one at a time. */
        const val SLOW_LINK_BYTES_PER_SECOND = 6_250.0

        fun missingFileMessage(label: String) = "The file for $label is missing on this device."
        fun newKey(): String = UUID.randomUUID().toString()
    }

    /** Two passes over one queue would send every step twice. */
    private val passLock = Mutex()
    private val unitLocks = HashMap<String, Mutex>()
    private fun lockFor(clientKey: String) = synchronized(unitLocks) { unitLocks.getOrPut(clientKey) { Mutex() } }

    /** Bytes per second over the last upload, or null before anything was measured. */
    @Volatile
    var measuredThroughput: Double? = null
        private set

    fun concurrency(): Int = measuredThroughput?.let { if (it < SLOW_LINK_BYTES_PER_SECOND) 1 else MAX_PARALLEL } ?: MAX_PARALLEL

    // ── Enqueue (frozen bodies, keys minted once) ────────────────────────────────────────────

    suspend fun startSession(body: QSessionCreate, ownerUserId: String?, localArtisans: List<Pair<String, String>> = emptyList()): SessionUnit {
        val now = clock().toString()
        val steps = localArtisans.map { (localId, artisanBody) ->
            SessionStep(newKey(), StepKind.ARTISAN, now, newKey(), bodyJson = artisanBody, localArtisanId = localId)
        } + SessionStep(newKey(), StepKind.SESSION, now, newKey(), bodyJson = outboxJson.encodeToString(QSessionCreate.serializer(), body))
        val unit = SessionUnit(
            clientKey = body.clientKey, ownerUserId = ownerUserId, createdAt = now,
            questionnaireId = body.questionnaireId, workshopId = body.workshopId, steps = steps
        )
        store.write(unit)
        return unit
    }

    /** For a session that already exists on the server (opened from the list, or joined online). */
    suspend fun adoptSession(session: QSession, questionnaireId: String, ownerUserId: String?): SessionUnit {
        val key = session.clientKey ?: session.id
        store.read(key)?.let { return it }
        val unit = SessionUnit(
            clientKey = key, ownerUserId = ownerUserId, createdAt = clock().toString(),
            questionnaireId = questionnaireId, workshopId = session.workshop?.id, sessionId = session.id
        )
        store.write(unit)
        return unit
    }

    suspend fun addParticipantChange(clientKey: String, change: ParticipantChange) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps + SessionStep(newKey(), StepKind.PARTICIPANT, change.at ?: clock().toString(), newKey(), participant = change))
    }

    suspend fun addClip(clientKey: String, clip: ClipPayload) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps + SessionStep(newKey(), StepKind.CLIP, clip.recording.deviceCapturedAt, newKey(), clip = clip))
    }

    suspend fun addAnswers(clientKey: String, body: QAnswersPut) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps + SessionStep(newKey(), StepKind.ANSWERS, clock().toString(), newKey(), bodyJson = outboxJson.encodeToString(QAnswersPut.serializer(), body)))
    }

    suspend fun finishSession(clientKey: String) = mutate(clientKey) { unit ->
        if (unit.steps.any { it.kind == StepKind.FINISH }) unit
        else unit.copy(steps = unit.steps + SessionStep(newKey(), StepKind.FINISH, clock().toString(), newKey()))
    }

    /**
     * The "Answered by" chips after Stop: an unticked artisan becomes PRESENT, never deleted
     * (§3.4). Only while the clip has not been completed; after that the take is refiled online.
     * Returns false when the clip already landed.
     */
    suspend fun updateClipSubjects(clientKey: String, clientClipKey: String, answeredIds: Set<String>): Boolean {
        var changed = false
        mutate(clientKey) { unit ->
            unit.copy(steps = unit.steps.map { step ->
                val clip = step.clip
                if (clip == null || clip.clientClipKey != clientClipKey || step.state == StepState.DONE) step
                else {
                    changed = true
                    step.copy(clip = clip.copy(recording = clip.recording.copy(subjects = clip.recording.subjects.map {
                        it.copy(role = if (it.artisanId in answeredIds) SubjectRole.ANSWERED else SubjectRole.PRESENT, named = answeredIds.size < clip.recording.subjects.size && it.artisanId in answeredIds)
                    })))
                }
            })
        }
        return changed
    }

    suspend fun units(): List<SessionUnit> = store.all()
    suspend fun unit(clientKey: String): SessionUnit? = store.read(clientKey)

    /** "Send again" on a failed step: clears the failure, keeps the key and the body. */
    suspend fun retryStep(clientKey: String, stepId: String) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps.map { if (it.id == stepId) it.copy(state = StepState.PENDING, failure = null) else it })
    }

    /** Discard a failed clip after the researcher confirms; its file goes too. */
    suspend fun discardStep(clientKey: String, stepId: String) = mutate(clientKey) { unit ->
        unit.steps.firstOrNull { it.id == stepId }?.clip?.localPath?.let { File(it).delete() }
        unit.copy(steps = unit.steps.filterNot { it.id == stepId })
    }

    private suspend fun mutate(clientKey: String, change: (SessionUnit) -> SessionUnit): SessionUnit = lockFor(clientKey).withLock {
        val current = store.read(clientKey) ?: throw IllegalStateException("No queued session $clientKey")
        change(current).also { store.write(it) }
    }

    private suspend fun updateStep(clientKey: String, stepId: String, change: (SessionStep) -> SessionStep): SessionUnit =
        mutate(clientKey) { unit -> unit.copy(steps = unit.steps.map { if (it.id == stepId) change(it) else it }) }

    // ── Replay ───────────────────────────────────────────────────────────────────────────────

    data class PassResult(val sent: Int, val failed: Int, val remaining: Int)

    /** One pass over every unit [ownerUserId] may replay. Units finish independently. */
    suspend fun sync(transport: SessionTransport, ownerUserId: String?): PassResult = passLock.withLock {
        var sent = 0; var failed = 0; var remaining = 0
        for (unit in store.all()) {
            if (unit.ownerUserId != null && ownerUserId != null && unit.ownerUserId != ownerUserId) continue
            val result = syncUnit(transport, unit.clientKey)
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
                // A run of clips between two other steps goes up in parallel.
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
            else if (unit.steps.firstOrNull { it.id == step.id }?.state == StepState.PENDING) break // transient: try later
        }
        unit = store.read(clientKey) ?: return PassResult(sent, 0, 0)
        val failed = unit.steps.count { it.state == StepState.FAILED }
        val remaining = unit.steps.count { it.state == StepState.PENDING }
        if (unit.isDone) store.delete(clientKey)
        return PassResult(sent, failed, remaining)
    }

    private suspend fun runOne(transport: SessionTransport, clientKey: String, stepId: String): Boolean {
        val unit = store.read(clientKey) ?: return false
        val step = unit.steps.first { it.id == stepId }
        return try {
            when (step.kind) {
                StepKind.ARTISAN -> {
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
                    mutate(clientKey) { u ->
                        u.copy(sessionId = session.id, steps = u.steps.map { if (it.id == stepId) it.copy(state = StepState.DONE, resultId = session.id) else it })
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
                    // Answers typed before the session synced name it by its clientKey.
                    val body = QAnswersPut(frozen.items.map {
                        it.copy(
                            artisanId = unit.resolve(it.artisanId),
                            sessionId = if (it.sessionId.isBlank() || it.sessionId == unit.clientKey) sessionId else it.sessionId
                        )
                    })
                    val response = transport.putAnswers(body)
                    val conflicts = response.results.filter { it.status == "CONFLICT" || it.status == "FORBIDDEN" }
                    updateStep(clientKey, stepId) {
                        if (conflicts.isEmpty()) it.copy(state = StepState.DONE)
                        // Per item, never as a whole: the saved ones stay saved; the tray asks P9 for the rest.
                        else it.copy(
                            state = StepState.FAILED, failure = "CONFLICT",
                            bodyJson = outboxJson.encodeToString(QAnswersPut.serializer(), QAnswersPut(frozen.items.filter { item -> conflicts.any { c -> c.questionLineageId == item.questionLineageId && c.artisanId == unit.resolve(item.artisanId) } }))
                        )
                    }
                }
                StepKind.FINISH -> {
                    val sessionId = unit.sessionId ?: return false
                    // Wait for every take and answer first; a failed one does not hold the finish.
                    if (unit.steps.any { it.kind != StepKind.FINISH && it.state == StepState.PENDING }) return false
                    transport.finish(sessionId)
                    updateStep(clientKey, stepId) { it.copy(state = StepState.DONE) }
                }
                else -> return false
            }
            true
        } catch (refused: PermanentStepFailure) {
            updateStep(clientKey, stepId) { it.copy(state = StepState.FAILED, failure = refused.message, attempts = it.attempts + 1) }
            false
        } catch (transient: IOException) {
            updateStep(clientKey, stepId) { it.copy(attempts = it.attempts + 1) }
            false
        }
    }

    /** Upload then complete one clip. True when it landed. */
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
            true
        } catch (refused: PermanentStepFailure) {
            updateStep(clientKey, stepId) { it.copy(state = StepState.FAILED, failure = refused.message, attempts = it.attempts + 1) }
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
            targets = clip.recording.targets, subjectArtisanIds = clip.subjectArtisanIds.map(unit::resolve),
            deviceCapturedAt = clip.recording.deviceCapturedAt, clockOffsetMs = clip.recording.clockOffsetMs
        )
        suspend fun save(next: ClipPayload): ClipPayload {
            updateStep(clientKey, stepId) { it.copy(clip = next) }
            return next
        }
        val startedAt = System.nanoTime()
        var bytesSent = 0L
        if (clip.sizeBytes <= multipartThresholdBytes) {
            val signed = transport.presign(presign)
            transport.put(signed.url, signed.headers, file, 0, clip.sizeBytes, clip.contentType)
            bytesSent = clip.sizeBytes
            clip = save(clip.copy(objectKey = signed.objectKey, uploaded = true))
        } else {
            // Resume: the server's record of the parts wins over ours when it has one.
            if (clip.uploadId == null) {
                val known = transport.uploadState(clip.clientClipKey)
                clip = if (known?.multipartUploadId != null && !known.closed) {
                    save(clip.copy(objectKey = known.objectKey, uploadId = known.multipartUploadId, partSize = known.partSize, partsDone = known.partsDone))
                } else {
                    val created = transport.multipartCreate(presign)
                    save(clip.copy(objectKey = created.objectKey, uploadId = created.uploadId, partSize = created.partSize, partsDone = created.partsDone))
                }
            }
            val partSize = clip.partSize ?: defaultPartSize
            val partCount = ((clip.sizeBytes + partSize - 1) / partSize).toInt()
            val missing = (1..partCount).filter { n -> clip.partsDone.none { it.partNumber == n } }
            if (missing.isNotEmpty()) {
                val urls = transport.presignParts(clip.objectKey!!, clip.uploadId!!, missing)
                for (n in missing) {
                    val offset = (n - 1) * partSize
                    val length = minOf(partSize, clip.sizeBytes - offset)
                    val etag = transport.put(urls.getValue(n), emptyMap(), file, offset, length, clip.contentType)
                    bytesSent += length
                    clip = save(clip.copy(partsDone = clip.partsDone + QPartDone(n, etag)))
                }
            }
            transport.multipartComplete(clip.objectKey!!, clip.uploadId!!, clip.partsDone.sortedBy { it.partNumber })
            clip = save(clip.copy(uploaded = true))
        }
        val seconds = (System.nanoTime() - startedAt) / 1e9
        if (bytesSent > 0 && seconds > 0.05) measuredThroughput = bytesSent / seconds
        return clip
    }
}

/** Serialise a list of units for the tray's export of a stuck queue. */
internal fun encodeUnits(units: List<SessionUnit>): String = outboxJson.encodeToString(ListSerializer(SessionUnit.serializer()), units)
