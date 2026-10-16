package com.fieldrepository.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
        runCatching { repository.syncOutbox(context) }
        val waiting = OfflineOutbox.all(context)
            .replayableBy(tokenStore.getUser()?.id)
            .count { it.failure == null }
        // Entries the server refused wait for a person, not for the network, so they never keep the
        // worker retrying. Anything else left means the pass stopped on a transient failure.
        return if (waiting > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "outbox-sync"

        /**
         * Ask for a sync pass as soon as there is a network. Idempotent: a pass already waiting is
         * kept, and a pass already running retries itself while anything is left.
         */
        fun schedule(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            }
        }

        /** Where [OfflineReadCache] keeps its answers: persistent and excluded from backups. */
        fun readCacheDir(context: Context): File = File(context.noBackupFilesDir, "http-read-cache")
    }
}
