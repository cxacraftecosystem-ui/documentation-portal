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
 * - A unit whose steps have all landed STAYS while its session is open (audit C2, 21 Oct 2026). It
 *   used to be deleted after the first take uploaded, and every later take, Present/Away, answer,
 *   added artisan and Finish then hit a missing unit. A unit goes once its Finish has landed, or
 *   [SessionOutbox.STALE_AFTER] after its last change with nothing left to send. A landed take's
 *   audio is deleted from the phone as it lands.
 */
object StepKind {
    const val ARTISAN = "ARTISAN"
    const val SESSION = "SESSION"
    const val PARTICIPANT = "PARTICIPANT"
    const val CLIP = "CLIP"
    const val ANSWERS = "ANSWERS"
    /** Finish, queued last so every take and answer lands before the session closes. */
    const val FINISH = "FINISH"
    /** Typed answers of a session's guest respondents (owner decision 1; UNIFY U6, A18). */
    const val GUEST_ANSWERS = "GUEST_ANSWERS"
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
    val uploaded: Boolean = false,
    /** Multipart completes that failed in a row; at [SessionOutbox.COMPLETE_RESTART_AFTER] the upload restarts. */
    val completeFailures: Int = 0
) {
    /** Percent of the bytes on the server, for "Uploading 40%" (audit M23: it read a fixed 50%). */
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
    val resultId: String? = null,
    /** The server's stable code for [failure], when it sent one (`STALE_VERSION`, `NOT_FOUND`…). */
    val failureCode: String? = null,
    /** An answers step the server refused per item: what each conflicted answer is now (P9). */
    val conflicts: List<QAnswerResult> = emptyList()
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
    val artisanIds: Map<String, String> = emptyMap(),
    /** The last change to this unit; a settled unit untouched for [SessionOutbox.STALE_AFTER] goes. */
    val touchedAt: String? = null,
    /**
     * The version each answer reached when THIS phone saved it ("artisanId|lineageId" → version). A
     * second save typed before the first one synced was based on the version before it; it is sent on
     * top of this phone's own first save instead, so it is not a conflict with itself (audit H19).
     */
    val savedAnswerVersions: Map<String, Int> = emptyMap(),
    /**
     * Guest respondents named on this phone (owner decision 1): local key to name, in the order the
     * session create lists them. Once the session lands each key maps to its server respondent in
     * [artisanIds], the same table offline artisans use, so [resolve] answers for both.
     */
    val guests: Map<String, String> = emptyMap()
) {
    fun resolve(artisanId: String): String = artisanIds[artisanId] ?: artisanId

    /** Steps in replay order (§6.1.5). */
    fun ordered(): List<SessionStep> = steps.sortedWith(
        compareBy<SessionStep> { rank(it.kind) }.thenBy { if (rank(it.kind) == 2) it.deviceTime else "" }
    )

    val pendingClips: List<SessionStep> get() = steps.filter { it.kind == StepKind.CLIP && it.state != StepState.DONE }
    /** Takes still on their way up: queued and not refused (a refused one waits in the fix tray). */
    val uploadingClips: List<SessionStep> get() = steps.filter { it.kind == StepKind.CLIP && it.state == StepState.PENDING }
    /** Steps the server refused for good: the fix tray's rows (Send again, Discard, P9). */
    val failedSteps: List<SessionStep> get() = steps.filter { it.state == StepState.FAILED }
    val isDone: Boolean get() = steps.all { it.state == StepState.DONE }
    /** The session's Finish has landed: it is closed and the unit has nothing left to do. */
    val isFinished: Boolean get() = isDone && steps.any { it.kind == StepKind.FINISH && it.state == StepState.DONE }

    private fun rank(kind: String) = when (kind) {
        StepKind.ARTISAN -> 0
        StepKind.SESSION -> 1
        StepKind.PARTICIPANT, StepKind.CLIP -> 2
        StepKind.ANSWERS, StepKind.GUEST_ANSWERS -> 3
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
    /** Guest answers go to the same `PUT /answers`, keyed by `respondentId`. */
    suspend fun putGuestAnswers(body: QGuestAnswersPut): QAnswersPutResponse =
        throw PermanentStepFailure("This build can't send guest answers.")
}

/** A refusal that no retry will change (4xx other than 408/429). */
class PermanentStepFailure(message: String, val code: String? = null) : Exception(message)

/** Storage refused a signed URL as expired (403): sign it again rather than fail the take. */
class SignedUrlExpired(message: String) : IOException(message)

/** `/complete` answered 404 `UPLOAD_MISSING` (retry: true): the bytes never reached storage, so upload again. */
class UploadMissing(message: String) : IOException(message) {
    companion object { const val REASON = "UPLOAD_MISSING" }
}

/** The session's unit is not on this phone (it settled, or was never adopted); see [SessionOutbox.reopen]. */
class MissingSessionUnit(clientKey: String) : IllegalStateException("No queued session $clientKey")

class SessionOutbox(
    private val store: SessionUnitStore,
    /**
     * A form written on this phone has a local id until its create lands (A15): the session create
     * waits for the real id (null) and is sent with it. Server ids pass through unchanged.
     */
    private val resolveFormId: suspend (String) -> String? = { it },
    private val clock: () -> Instant = Instant::now,
    private val multipartThresholdBytes: Long = 5L * 1024 * 1024,
    private val defaultPartSize: Long = 8L * 1024 * 1024,
    /** A take has landed; the app forgets its draft record (the outbox has already deleted the audio). */
    private val onClipLanded: (clientClipKey: String) -> Unit = {}
) {
    companion object {
        const val MAX_PARALLEL = 3
        /** About 50 kbps. Below this the clips go one at a time. */
        const val SLOW_LINK_BYTES_PER_SECOND = 6_250.0
        /** A unit with nothing left to send and no change for this long is forgotten (its session is on the server). */
        val STALE_AFTER: java.time.Duration = java.time.Duration.ofDays(14)
        /**
         * A multipart complete that keeps failing (a dead or expired upload) restarts the upload after
         * this many tries instead of retrying the same dead id for ever (audit Low, 21 Oct 2026).
         */
        const val COMPLETE_RESTART_AFTER = 3

        /** "The file for Section D (7 Jul) is missing on this device." from the shared table. */
        fun missingFileMessage(label: String): String {
            val open = label.lastIndexOf(" (")
            val (target, date) = if (open > 0 && label.endsWith(")")) label.substring(0, open) to label.substring(open + 2, label.length - 1) else label to ""
            val text = QuestionnaireCopy.t("missingOutboxFile", "target" to target, "date" to date)
            return if (date.isEmpty()) text.replace(" ()", "") else text
        }

        /** An unticked artisan becomes PRESENT, never removed; a subset that answered is `named`. */
        fun withAnswered(subjects: List<QCompleteSubject>, answeredIds: Set<String>): List<QCompleteSubject> = subjects.map {
            it.copy(
                role = if (it.artisanId in answeredIds) SubjectRole.ANSWERED.wire else SubjectRole.PRESENT.wire,
                named = answeredIds.size < subjects.size && it.artisanId in answeredIds
            )
        }
        fun newKey(): String = UUID.randomUUID().toString()

        fun answerKey(artisanId: String, questionLineageId: String) = "$artisanId|$questionLineageId"

        /**
         * The server's respondents for this phone's guest keys: matched by name, in order, so two
         * guests with one name still map one to one. A guest the server did not return stays local.
         */
        fun matchGuests(guests: Map<String, String>, respondents: List<QRespondent>): Map<String, String> {
            val left = respondents.filter { it.artisanId == null }.toMutableList()
            val out = LinkedHashMap<String, String>()
            for ((key, name) in guests) {
                val hit = left.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) } ?: continue
                left.remove(hit)
                out[key] = hit.id
            }
            return out
        }
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

    suspend fun startSession(
        body: QSessionCreate,
        ownerUserId: String?,
        localArtisans: List<Pair<String, String>> = emptyList(),
        /** Guest respondents: local key to name; their names are in [body]'s `respondents` in this order. */
        guests: List<Pair<String, String>> = emptyList()
    ): SessionUnit {
        val now = clock().toString()
        val steps = localArtisans.map { (localId, artisanBody) ->
            SessionStep(newKey(), StepKind.ARTISAN, now, newKey(), bodyJson = artisanBody, localArtisanId = localId)
        } + SessionStep(newKey(), StepKind.SESSION, now, newKey(), bodyJson = outboxJson.encodeToString(QSessionCreate.serializer(), body))
        val unit = SessionUnit(
            clientKey = body.clientKey, ownerUserId = ownerUserId, createdAt = now,
            questionnaireId = body.questionnaireId, workshopId = body.workshopId, steps = steps,
            guests = guests.toMap()
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
            questionnaireId = questionnaireId, workshopId = session.workshop?.id, sessionId = session.id,
            touchedAt = clock().toString()
        )
        store.write(unit)
        return unit
    }

    /**
     * Puts [known] back when its unit is gone (audit C2): the workspace still holds the copy it read,
     * and a unit only ever goes once everything in it has landed, so every step comes back ticked.
     * A unit that is still there is returned as it is.
     */
    suspend fun reopen(known: SessionUnit): SessionUnit = lockFor(known.clientKey).withLock {
        store.read(known.clientKey) ?: known.copy(
            steps = known.steps.map { it.copy(state = StepState.DONE) },
            touchedAt = clock().toString()
        ).also { store.write(it) }
    }

    suspend fun addParticipantChange(clientKey: String, change: ParticipantChange) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps + SessionStep(newKey(), StepKind.PARTICIPANT, change.at ?: clock().toString(), newKey(), participant = change))
    }

    suspend fun addClip(clientKey: String, clip: ClipPayload) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps + SessionStep(newKey(), StepKind.CLIP, clip.recording.deviceCapturedAt, newKey(), clip = clip))
    }

    /**
     * Pending removals of a participant on a session that has not reached the server yet: the frozen
     * create loses them, and so do their queued joins. Returns false once the session has landed (the
     * server's `DELETE …/participants/{id}` is the only way then).
     */
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

    /**
     * Edits a session that has not reached the server yet: its frozen create body changes. Returns
     * false once the session has landed (then `PATCH /sessions/{id}` with its version).
     */
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

    /** Forget a unit (its session was deleted or merged away on the server). */
    suspend fun forget(clientKey: String) = lockFor(clientKey).withLock { store.delete(clientKey) }

    suspend fun addAnswers(clientKey: String, body: QAnswersPut) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps + SessionStep(newKey(), StepKind.ANSWERS, clock().toString(), newKey(), bodyJson = outboxJson.encodeToString(QAnswersPut.serializer(), body)))
    }

    /** Typed answers for guest respondents, in order with the session's other answers. */
    suspend fun addGuestAnswers(clientKey: String, body: QGuestAnswersPut) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps + SessionStep(newKey(), StepKind.GUEST_ANSWERS, clock().toString(), newKey(), bodyJson = outboxJson.encodeToString(QGuestAnswersPut.serializer(), body)))
    }

    /** A guest added once the session is on the server: their server id joins the id table. */
    suspend fun rememberGuest(clientKey: String, localKey: String, name: String, respondentId: String?) = mutate(clientKey) { unit ->
        unit.copy(guests = unit.guests + (localKey to name), artisanIds = if (respondentId != null) unit.artisanIds + (localKey to respondentId) else unit.artisanIds)
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
                    step.copy(clip = clip.copy(recording = clip.recording.copy(subjects = withAnswered(clip.recording.subjects, answeredIds))))
                }
            })
        }
        return changed
    }

    suspend fun units(): List<SessionUnit> = store.all()
    suspend fun unit(clientKey: String): SessionUnit? = store.read(clientKey)

    /** "Send again" on a failed step: clears the failure, keeps the key and the body. */
    suspend fun retryStep(clientKey: String, stepId: String) = mutate(clientKey) { unit ->
        unit.copy(steps = unit.steps.map {
            if (it.id == stepId) it.copy(state = StepState.PENDING, failure = null, failureCode = null, attempts = 0) else it
        })
    }

    /** Discard a failed step after the researcher confirms; a clip's file goes too. */
    suspend fun discardStep(clientKey: String, stepId: String) = mutate(clientKey) { unit ->
        unit.steps.firstOrNull { it.id == stepId }?.clip?.localPath?.let { File(it).delete() }
        unit.copy(steps = unit.steps.filterNot { it.id == stepId })
    }

    /**
     * P9 on one conflicted answer of an answers step. [keepMine] resends this phone's text on top of
     * the answer the server now holds (its version becomes the base); otherwise the server's answer
     * stands and this item is dropped. The step goes back to the queue once no conflict is left, and
     * away when no item is.
     */
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
                // Other conflicts still wait for a person: the step stays in the tray.
                left.isNotEmpty() -> step.copy(bodyJson = encoded, conflicts = left)
                else -> step.copy(bodyJson = encoded, conflicts = emptyList(), state = StepState.PENDING, failure = null, failureCode = null, attempts = 0)
            }
            unit.copy(steps = unit.steps.mapNotNull { if (it.id == stepId) next else it })
        }

    private suspend fun mutate(clientKey: String, change: (SessionUnit) -> SessionUnit): SessionUnit = lockFor(clientKey).withLock {
        val current = store.read(clientKey) ?: throw MissingSessionUnit(clientKey)
        change(current).copy(touchedAt = clock().toString()).also { store.write(it) }
    }

    private suspend fun updateStep(clientKey: String, stepId: String, change: (SessionStep) -> SessionStep): SessionUnit =
        mutate(clientKey) { unit -> unit.copy(steps = unit.steps.map { if (it.id == stepId) change(it) else it }) }

    // ── Replay ───────────────────────────────────────────────────────────────────────────────

    data class PassResult(val sent: Int, val failed: Int, val remaining: Int)

    /**
     * One pass over every unit [ownerUserId] may replay. Units finish independently: an exception in
     * one unit leaves that unit waiting for the next pass and never ends the pass (audit M23).
     */
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
        if (settled(unit)) store.delete(clientKey)
        return PassResult(sent, failed, remaining)
    }

    /**
     * Whether [unit] can go (audit C2): its Finish has landed, or nothing is left to send and nothing
     * has touched it for [STALE_AFTER]. A unit whose session is still open keeps its steps, so the next
     * take, answer or participant change has somewhere to go.
     */
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
                    // A form still only on this phone: the session waits for its create to land (A15).
                    val formId = resolveFormId(frozen.questionnaireId) ?: return false
                    val body = frozen.copy(questionnaireId = formId, participants = frozen.participants.map { QArtisanRef(unit.resolve(it.artisanId)) })
                    val session = transport.createSession(step.idempotencyKey, body)
                    val guestIds = matchGuests(unit.guests, session.respondents)
                    mutate(clientKey) { u ->
                        u.copy(
                            sessionId = session.id, questionnaireId = formId, artisanIds = u.artisanIds + guestIds,
                            steps = u.steps.map { if (it.id == stepId) it.copy(state = StepState.DONE, resultId = session.id) else it }
                        )
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
                        val artisanId = unit.resolve(it.artisanId)
                        it.copy(
                            artisanId = artisanId,
                            sessionId = if (it.sessionId.isBlank() || it.sessionId == unit.clientKey) sessionId else it.sessionId,
                            // The newer of the two: this phone's own last save, or the version the item
                            // was based on (P9 "Keep mine" bases it on the server's current one).
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
                        // Per item, never as a whole: the saved ones stay saved; the tray asks P9 for the rest.
                        else it.copy(
                            state = StepState.FAILED, failure = "CONFLICT",
                            failureCode = if (conflicts.all { c -> c.status == "FORBIDDEN" }) "FORBIDDEN" else "CONFLICT",
                            conflicts = conflicts,
                            bodyJson = outboxJson.encodeToString(QAnswersPut.serializer(), QAnswersPut(frozen.items.filter { item -> conflicts.any { c -> c.questionLineageId == item.questionLineageId && c.artisanId == unit.resolve(item.artisanId) } }))
                        )
                    }
                }
                StepKind.GUEST_ANSWERS -> {
                    val sessionId = unit.sessionId ?: return false
                    val frozen = outboxJson.decodeFromString(QGuestAnswersPut.serializer(), step.bodyJson!!)
                    // A guest whose server id is not known yet (added on this phone): wait for it.
                    if (frozen.items.any { it.respondentId in unit.guests && unit.artisanIds[it.respondentId] == null }) return false
                    val body = QGuestAnswersPut(frozen.items.map {
                        it.copy(respondentId = unit.resolve(it.respondentId), sessionId = if (it.sessionId.isBlank() || it.sessionId == unit.clientKey) sessionId else it.sessionId)
                    })
                    val response = transport.putGuestAnswers(body)
                    val refused = response.results.filter { it.status == "CONFLICT" || it.status == "FORBIDDEN" }
                    updateStep(clientKey, stepId) {
                        if (refused.isEmpty()) it.copy(state = StepState.DONE)
                        else it.copy(
                            state = StepState.FAILED,
                            failure = if (refused.any { r -> r.code == "ANSWER_LOCKED" }) FormsCopy.p15(null) else QuestionnaireCopy.s("tray.notAllowed"),
                            failureCode = refused.first().code ?: refused.first().status,
                            bodyJson = outboxJson.encodeToString(
                                QGuestAnswersPut.serializer(),
                                QGuestAnswersPut(frozen.items.filter { item -> refused.any { r -> r.questionLineageId == item.questionLineageId && r.respondentId == unit.resolve(item.respondentId) } })
                            )
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
            updateStep(clientKey, stepId) { it.copy(state = StepState.FAILED, failure = refused.message, failureCode = refused.code, attempts = it.attempts + 1) }
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
            // The server holds the take now; the phone's copy only filled the disk (audit M23).
            File(clip.localPath).delete()
            onClipLanded(clip.clientClipKey)
            true
        } catch (refused: PermanentStepFailure) {
            updateStep(clientKey, stepId) { it.copy(state = StepState.FAILED, failure = refused.message, failureCode = refused.code, attempts = it.attempts + 1) }
            false
        } catch (missing: UploadMissing) {
            // The server holds no bytes for this take: forget what the phone thought had landed, so
            // the next pass uploads it again from the file, which is still here.
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
            targets = clip.recording.targets, subjectArtisanIds = clip.subjectArtisanIds.map(unit::resolve),
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
            // A signed URL that expired while the phone waited is refused with a 403: sign again once.
            var signed = transport.presign(presign)
            // A closed intent means the bytes already landed (a replay after a lost answer): go to complete.
            if (!signed.closed) {
                signAgainOnce(
                    { transport.put(signed.url, signed.headers, file, 0, clip.sizeBytes, clip.contentType) },
                    { signed = transport.presign(presign); transport.put(signed.url, signed.headers, file, 0, clip.sizeBytes, clip.contentType) }
                )
                bytesSent = clip.sizeBytes
            }
            clip = save(clip.copy(objectKey = signed.objectKey, uploaded = true))
        } else {
            // Resume: the server's record of the parts wins over ours when it has one.
            if (clip.uploadId == null) {
                val known = transport.uploadState(clip.clientClipKey)
                clip = if (known?.multipartUploadId != null && !known.closed) {
                    save(clip.copy(objectKey = known.objectKey, uploadId = known.multipartUploadId, partSize = known.partSize, partsDone = known.partsDone))
                } else {
                    val created = transport.multipartCreate(presign)
                    // A closed intent: the bytes already landed, so nothing is uploaded again; complete next.
                    if (created.closed) return save(clip.copy(objectKey = created.objectKey, uploaded = true))
                    save(clip.copy(objectKey = created.objectKey, uploadId = created.uploadId, partSize = created.partSize, partsDone = created.partsDone))
                }
            }
            val partSize = clip.partSize ?: defaultPartSize
            val partCount = ((clip.sizeBytes + partSize - 1) / partSize).toInt()
            val missing = (1..partCount).filter { n -> clip.partsDone.none { it.partNumber == n } }
            // Each part is signed just before it goes (audit M23). A part URL lives an hour, and on a
            // slow link the last parts of a long take used to meet an expired URL and fail for good.
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
                // A dead upload answers the same way every time: after a few tries start it again (the
                // next pass asks the server, then opens a fresh upload) instead of looping for ever.
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

    /**
     * Storage refusing a signed URL (403, usually because it expired while the phone waited) signs it
     * again once; a second refusal is a real one and fails the take into the fix tray.
     */
    private suspend fun <T> signAgainOnce(first: suspend () -> T, again: suspend () -> T): T = try {
        first()
    } catch (expired: SignedUrlExpired) {
        try {
            again()
        } catch (still: SignedUrlExpired) {
            throw PermanentStepFailure(still.message ?: "Storage refused the upload (403).")
        }
    }
}

/** Serialise a list of units for the tray's export of a stuck queue. */
internal fun encodeUnits(units: List<SessionUnit>): String = outboxJson.encodeToString(ListSerializer(SessionUnit.serializer()), units)
