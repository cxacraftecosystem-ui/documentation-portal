package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.data.Naming
import android.content.Context
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import com.fieldrepository.app.recording.RecordingDraft
import com.fieldrepository.app.recording.RecordingDrafts
import com.fieldrepository.app.recording.Remuxer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.Instant

/**
 * The questionnaire v2 pieces for one process: the typed API, the session outbox, the coverage cache
 * and the server's switches. Old screens stay in charge until `GET /questionnaire/config` says
 * `v2Enabled` (BUILD_PLAN §3.3: "Works with QUESTIONNAIRE_V2=false (old screens) and true").
 */
class QuestionnaireV2 private constructor(context: Context) {
    private val app = context.applicationContext
    val appContext: Context get() = app
    // The portal's own HTTP stack (QuestionnairePortal.retrofit): its retry, session and clock headers.
    val api: QuestionnaireApi = QuestionnairePortal.retrofit(app).create(QuestionnaireApi::class.java)
    // The sync pass reads upload state, which must never come from a stale copy.
    private val liveApi: QuestionnaireApi = api
    // A landed take's draft record goes with its audio (audit M23: uploaded audio was never deleted).
    val outbox = SessionOutbox(FileSessionUnitStore(File(app.filesDir, "questionnaire-outbox"))) { clipKey ->
        RecordingDrafts.read(app.filesDir, clipKey)?.let { RecordingDrafts.delete(app.filesDir, it, keepAudio = false) }
    }
    val coverage = CoverageCache(File(app.noBackupFilesDir, "questionnaire-coverage"))
    val filesDir: File = app.filesDir

    // The last switches the server gave, so a cold start with no signal opens the same screens.
    private val prefs = app.getSharedPreferences("questionnaire-v2", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(
        prefs.getString("config", null)?.let { runCatching { outboxJson.decodeFromString(QConfig.serializer(), it) }.getOrNull() }
    )
    val config: StateFlow<QConfig?> = _config

    val transport: SessionTransport = QuestionnaireTransport(
        liveApi,
        { key, bodyJson -> QuestionnairePortal.createArtisan(app, key, bodyJson) },
        v2SwitchedOff = ::v2SwitchedOff,
        formIdOf = { id -> QuestionnairePortal.resolveFormId(app, id) }
    )

    /** Who is signed in on this phone: the outbox replays only their units. */
    fun meId(): String? = QuestionnairePortal.userId(app)

    /** Ask for a background pass now (the portal's scheduler); every queue write calls it. */
    fun requestSync() = QuestionnairePortal.requestSync(app)

    /**
     * Asks the server, live, whether v2 is off (audit H20). A 404 from a switched-off v2 is a switch,
     * so queued steps wait for it; with no answer at all the step waits too (true).
     */
    suspend fun v2SwitchedOff(): Boolean {
        val live = runCatching { liveApi.config() }
        live.getOrNull()?.let { fresh ->
            prefs.edit().putString("config", outboxJson.encodeToString(QConfig.serializer(), fresh)).apply()
            _config.value = fresh
            return !fresh.v2Enabled
        }
        // The config route itself answering 404 is a server without v2.
        return true
    }

    /** Reads the switches. A server without v2 (404, or no signal on first run) reads as v2 off. */
    suspend fun refreshConfig(): QConfig {
        val fetched = runCatching { api.config() }
        // A server without the route (404) has no v2: that is an answer, not a missing signal.
        val absent = (fetched.exceptionOrNull() as? retrofit2.HttpException)?.code() == 404
        val next = fetched.getOrNull() ?: if (absent) QConfig(v2Enabled = false) else (_config.value ?: QConfig(v2Enabled = false))
        if (fetched.isSuccess || absent) prefs.edit().putString("config", outboxJson.encodeToString(QConfig.serializer(), next)).apply()
        _config.value = next
        return next
    }

    /**
     * A finished (or recovered) take into the session outbox: remux if needed, hash the uploaded bytes,
     * freeze the complete body. The app never names the file; the server mints the name at presign.
     */
    /**
     * The signed URL a take plays from: `GET /recordings/{id}/download` answers 302, and the redirect
     * is read rather than followed so the audio streams straight from storage.
     */
    suspend fun playbackUrl(recordingId: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val client = okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
            val request = okhttp3.Request.Builder()
                .url(QuestionnairePortal.apiBaseUrl.trimEnd('/') + "/questionnaire/recordings/$recordingId/download")
                .apply { QuestionnairePortal.token(app)?.let { header("Authorization", "Bearer $it") } }
                .build()
            client.newCall(request).execute().use { it.header("Location") }
        }.getOrNull()
    }

    private val takeLock = kotlinx.coroutines.sync.Mutex()

    suspend fun queueTake(draft: RecordingDraft): RecordingDraft = takeLock.withLock { queueTakeLocked(draft) }

    private suspend fun queueTakeLocked(draft: RecordingDraft): RecordingDraft {
        // "Answered by" may have been chosen while the take was still being remuxed; the disk copy has it.
        var current = RecordingDrafts.read(filesDir, draft.clientClipKey)?.let { draft.copy(subjects = it.subjects) } ?: draft
        if (current.m4aPath == null || current.sha256 == null) {
            val m4a = File(current.adtsPath.removeSuffix(".aac") + ".m4a")
            val result = Remuxer.remux(File(current.adtsPath), m4a)
            current = current.copy(finished = true, m4aPath = m4a.absolutePath, durationMs = result.durationMs, sha256 = result.sha256, sizeBytes = result.sizeBytes)
            RecordingDrafts.write(filesDir, current)
            File(current.adtsPath).delete()
        }
        if (!current.queued) {
            outbox.addClip(current.sessionKey, clipFrom(current))
            current = current.copy(queued = true)
            RecordingDrafts.write(filesDir, current)
        }
        return current
    }

    enum class AnsweredOutcome { SAVED, REFILED, FAILED }

    /**
     * The "Answered by" chips after Stop (§3.4), whenever they are confirmed: before the take reaches
     * the outbox the choice goes into its draft (and [queueTake] picks it up), while it waits in the
     * outbox the queued body is changed, and once it has landed the take is refiled online with
     * `PATCH /recordings/{id}`. FAILED only when the refile could not reach the server.
     */
    suspend fun setAnsweredBy(sessionKey: String, clientClipKey: String, answeredIds: Set<String>): AnsweredOutcome = takeLock.withLock {
        val draft = RecordingDrafts.read(filesDir, clientClipKey)
        if (draft != null && !draft.queued) {
            RecordingDrafts.write(filesDir, draft.copy(subjects = SessionOutbox.withAnswered(draft.subjects, answeredIds)))
            return@withLock AnsweredOutcome.SAVED
        }
        if (outbox.updateClipSubjects(sessionKey, clientClipKey, answeredIds)) return@withLock AnsweredOutcome.SAVED
        val unit = outbox.unit(sessionKey) ?: return@withLock AnsweredOutcome.FAILED
        val step = unit.steps.firstOrNull { it.clip?.clientClipKey == clientClipKey } ?: return@withLock AnsweredOutcome.FAILED
        val recordingId = step.resultId ?: return@withLock AnsweredOutcome.FAILED
        val sessionId = unit.sessionId ?: return@withLock AnsweredOutcome.FAILED
        val subjects = SessionOutbox.withAnswered(step.clip!!.recording.subjects, answeredIds)
        runCatching {
            val version = api.session(sessionId).recordings.firstOrNull { it.id == recordingId }?.version ?: 1
            api.patchRecording(recordingId, QRecordingPatch(version, subjects.map { QSubjectPatch(it.artisanId, it.role) }))
        }.fold({ AnsweredOutcome.REFILED }, { AnsweredOutcome.FAILED })
    }

    // ── Read once, kept for offline (A16): forms, form options, workshops ─────────────────────

    @kotlinx.serialization.Serializable
    data class Cached(val ownerId: String? = null, val fetchedAt: String, val body: String)

    private val cacheDir = File(app.noBackupFilesDir, "questionnaire-cache")
    private val cacheLock = kotlinx.coroutines.sync.Mutex()

    /**
     * [fetch] online; its answer is kept on the phone stamped with who read it. With no signal the
     * kept copy is returned, but only to the account that read it (DR audit M15): a colleague signed in
     * on the same phone never sees another account's forms. Null when neither is there.
     */
    suspend fun <T> cachedRead(key: String, serializer: kotlinx.serialization.KSerializer<T>, fetch: suspend () -> T): T? {
        val me = meId()
        val file = File(cacheDir, key.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".json")
        val fresh = runCatching { fetch() }
        fresh.getOrNull()?.let { value ->
            runCatching {
                cacheLock.withLock {
                    writeFileAtomically(file, outboxJson.encodeToString(Cached.serializer(), Cached(me, Instant.now().toString(), outboxJson.encodeToString(serializer, value))).toByteArray())
                }
            }
            return value
        }
        val kept = cacheLock.withLock { file.takeIf { it.isFile }?.let { runCatching { outboxJson.decodeFromString(Cached.serializer(), it.readText()) }.getOrNull() } }
            ?: return null
        if (kept.ownerId != null && kept.ownerId != me) return null
        return runCatching { outboxJson.decodeFromString(serializer, kept.body) }.getOrNull()
    }

    // ── Recording chips and the shared filters (owner, 31 Oct 2026) ───────────────────────────

    /** Each list's filters, kept across navigation and a restart, for the account that set them. */
    val filterStore: FilterStore by lazy { FilterStore(prefs, ::meId) }

    /** The rows last read, so a list still filters with no signal (owner-stamped). */
    val offlineRows: OfflineRows by lazy { OfflineRows(cacheDir, ::meId) }

    /** One page of a list, and whether it came from this phone because there was no signal. */
    data class Paged<T>(val items: List<T>, val nextCursor: String?, val offline: Boolean)

    /** The sessions list: the server's page, or with no signal this account's kept rows, filtered here. */
    suspend fun sessionsPage(filters: QuestionnaireFilters, cursor: String? = null, limit: Int = 30): Paged<QSession> =
        runCatching { api.sessionsFiltered(filters.toParams(), cursor, limit) }.fold(
            { page -> runCatching { offlineRows.keepSessions(page.items) }; Paged(page.items, page.nextCursor, false) },
            { if (cursor != null) throw it else Paged(offlineRows.sessions(filters), null, true) }
        )

    /** The recordings list: the server's page, or with no signal this account's kept takes, filtered here. */
    suspend fun recordingsPage(filters: QuestionnaireFilters, cursor: String? = null, limit: Int = 30): Paged<QRecording> =
        runCatching { api.recordings(filters.toParams(), cursor, limit) }.fold(
            { page -> runCatching { offlineRows.keepRecordings(page.items) }; Paged(page.items, page.nextCursor, false) },
            { if (cursor != null) throw it else Paged(offlineRows.recordings(filters), null, true) }
        )

    /** One filter's values for a searchable select; null with no signal. */
    suspend fun filterOptions(facet: String, q: String, questionnaireIds: List<String>): QFilterOptions? = runCatching {
        api.filterOptions(field = facet, q = q.trim().ifBlank { null }, questionnaireId = questionnaireIds.joinToString(",").ifBlank { null })
    }.getOrNull()

    /** A form with its sections and questions, online or from this account's kept copy. */
    suspend fun form(questionnaireId: String): QFormDetail? = cachedRead("form-$questionnaireId", QFormDetail.serializer()) { api.form(questionnaireId) }

    /** The forms a session can start on (A16: kept for offline). */
    suspend fun formOptions(): List<QFormOption> =
        cachedRead("form-options", kotlinx.serialization.builtins.ListSerializer(QFormOption.serializer())) { api.formOptions() }.orEmpty()

    /** The workshops a session can file under, from the portal (kept for offline). */
    suspend fun workshops(): List<QWorkshopOption> =
        cachedRead("workshops", kotlinx.serialization.builtins.ListSerializer(QWorkshopOption.serializer())) { QuestionnairePortal.workshops(app) }.orEmpty()

    /** The workshop the portal names as this account's most recent, when it names one. */
    suspend fun defaultWorkshopId(): String? =
        cachedRead("default-workshop", String.serializer()) { QuestionnairePortal.defaultWorkshopId(app) ?: error("none") }

    // ── Offline artisan registration: the names of artisans registered on this phone ──────────

    private val localArtisanSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())

    /** Local id → name, for artisans registered on this phone whose create has not landed yet. */
    fun localArtisanNames(): Map<String, String> =
        prefs.getString("localArtisans", null)?.let { runCatching { outboxJson.decodeFromString(localArtisanSerializer, it) }.getOrNull() }.orEmpty()

    fun rememberLocalArtisan(localId: String, name: String) {
        prefs.edit().putString("localArtisans", outboxJson.encodeToString(localArtisanSerializer, localArtisanNames() + (localId to name))).apply()
    }

    // ── Consent (§3.11): asked inline, never blocking. Append-only; offline answers wait here. ──

    @kotlinx.serialization.Serializable
    data class PendingConsent(val artisanId: String, val body: QConsentPost)

    private val consentFile = File(app.filesDir, "questionnaire-consent-queue.json")
    private val consentLock = kotlinx.coroutines.sync.Mutex()
    private val consentList = kotlinx.serialization.builtins.ListSerializer(PendingConsent.serializer())

    /** Consent the app knows of this run, so the start sheet stops asking once it is answered. */
    val knownConsent = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Records [state] for [artisanId]: queued on the phone first, then sent. A queued answer goes out
     * with the next sync pass, which is scheduled here so an offline refusal does not wait for some
     * other upload. Returns false only when the server refused this answer for good; the caller says
     * so, because the phone no longer shows it as recorded.
     */
    suspend fun recordConsent(artisanId: String, state: String): Boolean {
        val body = QConsentPost(state = state, clientKey = SessionOutbox.newKey())
        knownConsent[artisanId] = state
        consentLock.withLockCompat {
            val queue = readConsents() + PendingConsent(artisanId, body)
            writeFileAtomically(consentFile, outboxJson.encodeToString(consentList, queue).toByteArray())
        }
        val rejected = flushConsents()
        if (readConsents().any { it.body.clientKey == body.clientKey }) requestSync()
        return body.clientKey !in rejected
    }

    /**
     * Sends every queued consent. An answer waits on a missing signal and on any answer a retry can
     * change (408, 429, 5xx), the session outbox's rule; it is dropped only on a refusal no retry can
     * change, and then this run stops showing it. Returns the client keys the server refused.
     */
    suspend fun flushConsents(): Set<String> = consentLock.withLockCompat {
        val queue = readConsents()
        val rejected = mutableSetOf<String>()
        val left = queue.filterIndexed { index, pending ->
            val failure = try {
                liveApi.recordConsent(pending.body.clientKey, pending.artisanId, pending.body)
                null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                e
            }
            when {
                failure == null -> false
                consentRetryable(failure) -> true
                else -> {
                    rejected += pending.body.clientKey
                    // Forget it, unless a later answer for the same artisan is still queued.
                    val later = queue.drop(index + 1).any { it.artisanId == pending.artisanId }
                    if (!later) knownConsent.remove(pending.artisanId, pending.body.state)
                    false
                }
            }
        }
        writeFileAtomically(consentFile, outboxJson.encodeToString(consentList, left).toByteArray())
        rejected
    }

    /** Whether an answer for [artisanId] is still queued on this phone, waiting for a signal. */
    suspend fun consentWaiting(artisanId: String): Boolean = consentLock.withLockCompat { readConsents().any { it.artisanId == artisanId } }

    /** The current consent state, from the server when reachable, else what this run recorded. */
    suspend fun consentState(artisanId: String): String? =
        knownConsent[artisanId] ?: runCatching { api.consent(artisanId).state }.getOrNull()?.also { knownConsent[artisanId] = it }

    private fun readConsents(): List<PendingConsent> =
        consentFile.takeIf { it.isFile }?.let { runCatching { outboxJson.decodeFromString(consentList, it.readText()) }.getOrNull() }.orEmpty()

    private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockCompat(block: suspend () -> T): T {
        lock(); try { return block() } finally { unlock() }
    }

    companion object {
        @Volatile private var instance: QuestionnaireV2? = null
        fun get(context: Context): QuestionnaireV2 =
            instance ?: synchronized(this) { instance ?: QuestionnaireV2(context).also { instance = it } }

        fun clipFrom(draft: RecordingDraft): ClipPayload = ClipPayload(
            clientClipKey = draft.clientClipKey,
            localPath = draft.m4aPath ?: draft.adtsPath,
            sizeBytes = draft.sizeBytes,
            contentSha256 = draft.sha256,
            contentType = draft.contentType,
            extension = draft.extension,
            label = "${draft.label} (${QuestionnaireCopy.day(Instant.parse(draft.deviceCapturedAt).atZone(Naming.IST).toLocalDate())})",
            // Subject ids: artisans and guests (`R:<id>`); the presign names the artisans only.
            subjectArtisanIds = draft.subjects.map { it.artisanId },
            recording = QCompleteRecording(
                targets = draft.targets,
                subjects = draft.subjects,
                deviceCapturedAt = draft.deviceCapturedAt,
                clockOffsetMs = draft.clockOffsetMs,
                clockOffsetMeasuredAt = draft.clockOffsetMeasuredAt,
                capturedAtSource = draft.capturedAtSource,
                durationMs = draft.durationMs,
                rosterCount = draft.subjects.size.coerceAtLeast(1),
                contentSha256 = draft.sha256,
                continuationOfId = draft.continuationOfId,
                replacesRecordingId = draft.replacesRecordingId,
                ackedRecordingIds = draft.ackedRecordingIds
            )
        )
    }
}

/** A consent POST that may succeed later: no signal, or 408, 429 or 5xx (QuestionnaireTransport's rule). */
internal fun consentRetryable(failure: Throwable): Boolean = when (failure) {
    is java.io.IOException -> true
    is retrofit2.HttpException -> failure.code().let { it == 408 || it == 429 || it >= 500 }
    else -> false
}

/**
 * One background pass (DESIGN §6.1.6): entries an older build queued go first, through the shim
 * endpoints the legacy outbox already calls; the v2 queue starts only once no legacy QUESTIONNAIRE
 * entry is still waiting. Entries the server refused wait for a person and do not hold the queue.
 */
object QuestionnaireSync {
    data class Outcome(val legacyWaiting: Int, val v2Ran: Boolean, val v2Remaining: Int)

    /**
     * The v2 queue waits while older entries are still draining, or while there is no signal. One
     * entry the server keeps answering without taking (it neither lands nor is refused) no longer
     * holds every v2 take behind it (audit M23): when the server answered and the old queue moved
     * not at all, the v2 queue runs.
     */
    suspend fun pass(
        legacyDrain: suspend () -> Unit,
        legacyQuestionnaireWaiting: suspend () -> Int,
        v2: suspend () -> SessionOutbox.PassResult,
        serverAnswering: () -> Boolean = { false }
    ): Outcome {
        val before = legacyQuestionnaireWaiting()
        legacyDrain()
        val waiting = legacyQuestionnaireWaiting()
        val draining = waiting < before
        if (waiting > 0 && (draining || !serverAnswering())) return Outcome(waiting, v2Ran = false, v2Remaining = -1)
        val result = v2()
        return Outcome(waiting, v2Ran = true, v2Remaining = result.remaining)
    }

    /**
     * One pass for this app: the old questionnaire entries through the portal's own outbox first
     * ([legacyDrain]), then consent answers and the session queue. The portal's background job and
     * its foreground loop both call this, after their own outbox pass.
     */
    suspend fun run(context: Context, legacyDrain: suspend () -> Unit, serverAnswering: () -> Boolean): Outcome {
        val v2 = QuestionnaireV2.get(context)
        val me = v2.meId()
        return pass(
            legacyDrain = legacyDrain,
            legacyQuestionnaireWaiting = { QuestionnairePortal.legacyQuestionnaireWaiting(context) },
            v2 = { runCatching { v2.flushConsents() }; v2.outbox.sync(v2.transport, me) },
            serverAnswering = serverAnswering
        )
    }
}
