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

class QuestionnaireV2 private constructor(context: Context) {
    private val app = context.applicationContext
    val appContext: Context get() = app
    val api: QuestionnaireApi = QuestionnairePortal.retrofit(app).create(QuestionnaireApi::class.java)
    private val liveApi: QuestionnaireApi = api
    val outbox = SessionOutbox(FileSessionUnitStore(File(app.filesDir, "questionnaire-outbox"))) { clipKey ->
        RecordingDrafts.read(app.filesDir, clipKey)?.let { RecordingDrafts.delete(app.filesDir, it, keepAudio = false) }
    }
    val coverage = CoverageCache(File(app.noBackupFilesDir, "questionnaire-coverage"))
    val filesDir: File = app.filesDir

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

    fun meId(): String? = QuestionnairePortal.userId(app)

    fun requestSync() = QuestionnairePortal.requestSync(app)

    suspend fun v2SwitchedOff(): Boolean {
        val live = runCatching { liveApi.config() }
        live.getOrNull()?.let { fresh ->
            prefs.edit().putString("config", outboxJson.encodeToString(QConfig.serializer(), fresh)).apply()
            _config.value = fresh
            return !fresh.v2Enabled
        }
        return true
    }

    suspend fun refreshConfig(): QConfig {
        val fetched = runCatching { api.config() }
        val absent = (fetched.exceptionOrNull() as? retrofit2.HttpException)?.code() == 404
        val next = fetched.getOrNull() ?: if (absent) QConfig(v2Enabled = false) else (_config.value ?: QConfig(v2Enabled = false))
        if (fetched.isSuccess || absent) prefs.edit().putString("config", outboxJson.encodeToString(QConfig.serializer(), next)).apply()
        _config.value = next
        return next
    }

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

    @kotlinx.serialization.Serializable
    data class Cached(val ownerId: String? = null, val fetchedAt: String, val body: String)

    private val cacheDir = File(app.noBackupFilesDir, "questionnaire-cache")
    private val cacheLock = kotlinx.coroutines.sync.Mutex()

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

    val filterStore: FilterStore by lazy { FilterStore(prefs, ::meId) }

    val offlineRows: OfflineRows by lazy { OfflineRows(cacheDir, ::meId) }

    data class Paged<T>(val items: List<T>, val nextCursor: String?, val offline: Boolean)

    suspend fun sessionsPage(filters: QuestionnaireFilters, cursor: String? = null, limit: Int = 30): Paged<QSession> =
        runCatching { api.sessionsFiltered(filters.toParams(), cursor, limit) }.fold(
            { page -> runCatching { offlineRows.keepSessions(page.items) }; Paged(page.items, page.nextCursor, false) },
            { if (cursor != null) throw it else Paged(offlineRows.sessions(filters), null, true) }
        )

    suspend fun everySession(questionnaireId: String? = null): List<QSession> =
        QuestionnairePages.every { cursor -> api.sessions(questionnaireId = questionnaireId, cursor = cursor, limit = QuestionnairePages.PAGE).let { it.items to it.nextCursor } }

    suspend fun everyRecording(filters: QuestionnaireFilters): List<QRecording> =
        QuestionnairePages.every { cursor -> recordingsPage(filters, cursor, QuestionnairePages.PAGE).let { it.items to it.nextCursor } }

    suspend fun recordingsPage(filters: QuestionnaireFilters, cursor: String? = null, limit: Int = 30): Paged<QRecording> =
        runCatching { api.recordings(filters.toParams(), cursor, limit) }.fold(
            { page -> runCatching { offlineRows.keepRecordings(page.items) }; Paged(page.items, page.nextCursor, false) },
            { if (cursor != null) throw it else Paged(offlineRows.recordings(filters), null, true) }
        )

    suspend fun searchRecordings(query: String, limit: Int = 10): List<RecordingHit> =
        RecordingSearch.hits(recordingsPage(QuestionnaireFilters().withQ(query), limit = limit).items.take(limit), query)

    suspend fun filterOptions(facet: String, q: String, questionnaireIds: List<String>): QFilterOptions? = runCatching {
        api.filterOptions(field = facet, q = q.trim().ifBlank { null }, questionnaireId = questionnaireIds.joinToString(",").ifBlank { null })
    }.getOrNull()

    suspend fun form(questionnaireId: String): QFormDetail? = cachedRead("form-$questionnaireId", QFormDetail.serializer()) { api.form(questionnaireId) }

    suspend fun formOptions(): List<QFormOption> =
        cachedRead("form-options", kotlinx.serialization.builtins.ListSerializer(QFormOption.serializer())) { api.formOptions() }.orEmpty()

    suspend fun workshops(): List<QWorkshopOption> =
        cachedRead("workshops", kotlinx.serialization.builtins.ListSerializer(QWorkshopOption.serializer())) { QuestionnairePortal.workshops(app) }.orEmpty()

    suspend fun defaultWorkshopId(): String? =
        cachedRead("default-workshop", String.serializer()) { QuestionnairePortal.defaultWorkshopId(app) ?: error("none") }

    private val localArtisanSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())

    fun localArtisanNames(): Map<String, String> =
        prefs.getString("localArtisans", null)?.let { runCatching { outboxJson.decodeFromString(localArtisanSerializer, it) }.getOrNull() }.orEmpty()

    fun rememberLocalArtisan(localId: String, name: String) {
        prefs.edit().putString("localArtisans", outboxJson.encodeToString(localArtisanSerializer, localArtisanNames() + (localId to name))).apply()
    }

    @kotlinx.serialization.Serializable
    data class PendingConsent(val artisanId: String, val body: QConsentPost, val ownerId: String? = null)

    private val consentFile = File(app.filesDir, "questionnaire-consent-queue.json")
    private val consentLock = kotlinx.coroutines.sync.Mutex()
    private val consentList = kotlinx.serialization.builtins.ListSerializer(PendingConsent.serializer())

    val knownConsent = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun recordConsent(artisanId: String, state: String): Boolean {
        val body = QConsentPost(state = state, clientKey = SessionOutbox.newKey())
        knownConsent[artisanId] = state
        consentLock.withLockCompat {
            val queue = readConsents() + PendingConsent(artisanId, body, meId())
            writeFileAtomically(consentFile, outboxJson.encodeToString(consentList, queue).toByteArray())
        }
        val rejected = flushConsents()
        if (readConsents().any { it.body.clientKey == body.clientKey }) requestSync()
        return body.clientKey !in rejected
    }

    suspend fun consentsHeldForAnotherAccount(): Int {
        val me = meId() ?: return 0
        return consentLock.withLockCompat { readConsents().count { consentHeldForAnotherAccount(it.ownerId, me) } }
    }

    suspend fun discardConsentsHeldForAnotherAccount() {
        val me = meId() ?: return
        consentLock.withLockCompat {
            val left = readConsents().filterNot { consentHeldForAnotherAccount(it.ownerId, me) }
            writeFileAtomically(consentFile, outboxJson.encodeToString(consentList, left).toByteArray())
        }
    }

    suspend fun flushConsents(): Set<String> = consentLock.withLockCompat {
        val queue = readConsents()
        val me = meId()
        val rejected = mutableSetOf<String>()
        val left = queue.filterIndexed { index, pending ->
            if (consentHeldForAnotherAccount(pending.ownerId, me)) return@filterIndexed true
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
                    val later = queue.drop(index + 1).any { it.artisanId == pending.artisanId }
                    if (!later) knownConsent.remove(pending.artisanId, pending.body.state)
                    false
                }
            }
        }
        writeFileAtomically(consentFile, outboxJson.encodeToString(consentList, left).toByteArray())
        rejected
    }

    suspend fun consentWaiting(artisanId: String): Boolean = consentLock.withLockCompat { readConsents().any { it.artisanId == artisanId } }

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

internal fun consentHeldForAnotherAccount(ownerId: String?, signedInUserId: String?): Boolean =
    signedInUserId == null || ownerId == null || ownerId != signedInUserId

internal fun consentRetryable(failure: Throwable): Boolean = when (failure) {
    is java.io.IOException -> true
    is retrofit2.HttpException -> failure.code().let { it == 408 || it == 429 || it >= 500 }
    else -> false
}

object QuestionnaireSync {
    data class Outcome(val legacyWaiting: Int, val v2Ran: Boolean, val v2Remaining: Int)

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
