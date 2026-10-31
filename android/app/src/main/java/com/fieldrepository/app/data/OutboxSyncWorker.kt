package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.data.QuestionnaireSync
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One lock for every outbox sync pass in the process, whichever [FieldRepository] instance runs it.
 * Two passes over the same snapshot would POST every queued record twice.
 */
internal val outboxSyncMutex = Mutex()

/**
 * Drains the offline outbox in the background, so a record saved in a courtyard with no signal
 * uploads when the phone next finds a network, even if the app is never opened again.
 *
 * Before this, the only sync triggers lived inside the running UI (a timer and a network callback in
 * `RepositoryApp`). Swipe the app away, or reboot, and the queue sat on the device until somebody
 * opened the app again. WorkManager keeps the request across process death and reboots and runs it
 * only once a network is connected.
 *
 * It replays only the signed-in account's entries, through the same [FieldRepository.syncOutbox]
 * the foreground uses, and shares its lock ([outboxSyncMutex]). Signed out, it does nothing: the
 * next sign-in schedules it again.
 */
class OutboxSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val tokenStore = TokenStore(context)
        if (tokenStore.getToken().isNullOrBlank()) return Result.success()
        // No read cache here: the activity's client owns that directory, and a sync pass only writes.
        val repository = FieldRepository(ApiClient.create(tokenStore), tokenStore)
        val userId = tokenStore.getUser()?.id
        // Transcript edits, speaker names and re-translations made offline (transcript/data).
        runCatching { com.fieldrepository.app.transcript.portal.TranscriptPortal.repository(context)?.drain() }
        // Questionnaire v2: older-format entries drain through the shims first, then the
        // per-session queue runs (data/QuestionnaireV2.kt, DESIGN §6.1.6).
        val outcome = try {
            // The shared questionnaire's pass: this app's outbox first, then its forms written offline
            // (A15: form, section, question, so a session on a new form finds it), then the sessions.
            QuestionnaireSync.run(
                context,
                legacyDrain = {
                    runCatching { repository.syncOutbox(context) }
                    runCatching { com.fieldrepository.app.forms.FormsV2.get(context).sync() }
                },
                serverAnswering = { !ServerReachability.unreachable.value }
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            // A pass that threw left its work where it was: try again, never end quietly (audit M23).
            return Result.retry()
        }
        return resultFor(OfflineOutbox.all(context).replayableBy(userId).count { it.failure == null }, outcome)
    }

    companion object {
        private const val UNIQUE_NAME = "outbox-sync"

        /**
         * Retry while anything replayable is left: the legacy entries still waiting, or v2 steps still
         * pending. Entries the server refused wait for a person, not for the network, so they never
         * keep the worker retrying. A v2 queue that did not run because older entries hold it counts
         * as waiting too.
         */
        internal fun resultFor(legacyWaiting: Int, outcome: QuestionnaireSync.Outcome): androidx.work.ListenableWorker.Result {
            val v2Waiting = if (outcome.v2Ran) outcome.v2Remaining.coerceAtLeast(0) else 1
            return if (legacyWaiting > 0 || v2Waiting > 0) androidx.work.ListenableWorker.Result.retry()
            else androidx.work.ListenableWorker.Result.success()
        }

        /**
         * Ask for a sync pass as soon as there is a network.
         *
         * A pass that is RUNNING is kept: it retries itself while anything is left, and replacing it
         * would cancel an upload part-way. Anything else is REPLACED. KEEP there would leave a pass
         * that is waiting out an exponential backoff (up to five hours after a run of failures) in
         * charge, so a record saved now, with a good signal, would sit until that backoff ended.
         * Off the main thread, because reading the current state is a blocking WorkManager query.
         */
        fun schedule(context: Context) {
            val app = context.applicationContext
            AppScope.io.launch {
                runCatching {
                    val workManager = WorkManager.getInstance(app)
                    val running = workManager.getWorkInfosForUniqueWork(UNIQUE_NAME).get()
                        .any { it.state == WorkInfo.State.RUNNING }
                    val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                        .build()
                    workManager.enqueueUniqueWork(UNIQUE_NAME, policyFor(running), request)
                }
            }
        }

        /** KEEP a pass that is running; REPLACE one that is only waiting (see [schedule]). */
        internal fun policyFor(running: Boolean): ExistingWorkPolicy =
            if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE

        /** Where [OfflineReadCache] keeps its answers: persistent and excluded from backups. */
        fun readCacheDir(context: Context): File = File(context.noBackupFilesDir, "http-read-cache")
    }
}
