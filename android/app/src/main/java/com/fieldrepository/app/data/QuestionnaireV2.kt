package com.fieldrepository.app.data

import android.content.Context
import com.fieldrepository.app.recording.RecordingDraft
import com.fieldrepository.app.recording.RecordingDrafts
import com.fieldrepository.app.recording.Remuxer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
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
    val tokenStore = TokenStore(app)
    val api: QuestionnaireApi = ApiClient.retrofit(tokenStore).create(QuestionnaireApi::class.java)
    private val legacyApi: FieldRepositoryApi = ApiClient.create(tokenStore)
    val outbox = SessionOutbox(FileSessionUnitStore(File(app.filesDir, "questionnaire-outbox")))
    val coverage = CoverageCache(File(app.noBackupFilesDir, "questionnaire-coverage"))
    val filesDir: File = app.filesDir

    // The last switches the server gave, so a cold start with no signal opens the same screens.
    private val prefs = app.getSharedPreferences("questionnaire-v2", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(
        prefs.getString("config", null)?.let { runCatching { outboxJson.decodeFromString(QConfig.serializer(), it) }.getOrNull() }
    )
    val config: StateFlow<QConfig?> = _config

    val transport: SessionTransport = QuestionnaireTransport(api) { key, bodyJson ->
        legacyApi.createArtisan(key, ApiClient.json.decodeFromString(ArtisanCreateRequest.serializer(), bodyJson)).id
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
                .url(com.fieldrepository.app.BuildConfig.DEFAULT_API_BASE_URL.trimEnd('/') + "/questionnaire/recordings/$recordingId/download")
                .apply { tokenStore.getToken()?.let { header("Authorization", "Bearer $it") } }
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

    // ── Consent (§3.11): asked inline, never blocking. Append-only; offline answers wait here. ──

    @kotlinx.serialization.Serializable
    data class PendingConsent(val artisanId: String, val body: QConsentPost)

    private val consentFile = File(app.filesDir, "questionnaire-consent-queue.json")
    private val consentLock = kotlinx.coroutines.sync.Mutex()
    private val consentList = kotlinx.serialization.builtins.ListSerializer(PendingConsent.serializer())

    /** Consent the app knows of this run, so the start sheet stops asking once it is answered. */
    val knownConsent = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun recordConsent(artisanId: String, state: String) {
        val body = QConsentPost(state = state, clientKey = SessionOutbox.newKey())
        knownConsent[artisanId] = state
        consentLock.withLockCompat {
            val queue = readConsents() + PendingConsent(artisanId, body)
            writeFileAtomically(consentFile, outboxJson.encodeToString(consentList, queue).toByteArray())
        }
        flushConsents()
    }

    suspend fun flushConsents() = consentLock.withLockCompat {
        val left = readConsents().filter { pending ->
            val sent = runCatching { api.recordConsent(pending.body.clientKey, pending.artisanId, pending.body) }
            // A refusal no retry can change is dropped; only a missing signal keeps it.
            sent.isFailure && sent.exceptionOrNull() is java.io.IOException
        }
        writeFileAtomically(consentFile, outboxJson.encodeToString(consentList, left).toByteArray())
    }

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
            subjectArtisanIds = draft.subjects.map { it.artisanId },
            recording = QCompleteRecording(
                targets = draft.targets,
                subjects = draft.subjects,
                deviceCapturedAt = draft.deviceCapturedAt,
                clockOffsetMs = draft.clockOffsetMs,
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

/**
 * One background pass (DESIGN §6.1.6): entries an older build queued go first, through the shim
 * endpoints the legacy outbox already calls; the v2 queue starts only once no legacy QUESTIONNAIRE
 * entry is still waiting. Entries the server refused wait for a person and do not hold the queue.
 */
object QuestionnaireSync {
    data class Outcome(val legacyWaiting: Int, val v2Ran: Boolean, val v2Remaining: Int)

    suspend fun pass(
        legacyDrain: suspend () -> Unit,
        legacyQuestionnaireWaiting: suspend () -> Int,
        v2: suspend () -> SessionOutbox.PassResult
    ): Outcome {
        legacyDrain()
        val waiting = legacyQuestionnaireWaiting()
        if (waiting > 0) return Outcome(waiting, v2Ran = false, v2Remaining = -1)
        val result = v2()
        return Outcome(0, v2Ran = true, v2Remaining = result.remaining)
    }

    /** Legacy entries that are questionnaire interviews and still replayable. */
    fun legacyQuestionnaireEntries(entries: List<PendingEntry>): Int =
        entries.count { it.failure == null && !it.needsReview && (it.type == "questionnaire" || it.type == "questionnaireinterview") }
}
