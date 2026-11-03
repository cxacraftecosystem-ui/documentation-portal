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

internal val outboxSyncMutex = Mutex()

class OutboxSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val tokenStore = TokenStore(context)
        if (tokenStore.getToken().isNullOrBlank()) return Result.success()
        val repository = FieldRepository(ApiClient.create(tokenStore), tokenStore)
        val userId = tokenStore.getUser()?.id
        runCatching { com.fieldrepository.app.transcript.portal.TranscriptPortal.repository(context)?.drain() }
        val outcome = try {
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
            return Result.retry()
        }
        return resultFor(OfflineOutbox.all(context).replayableBy(userId).count { it.failure == null }, outcome)
    }

    companion object {
        private const val UNIQUE_NAME = "outbox-sync"

        internal fun resultFor(legacyWaiting: Int, outcome: QuestionnaireSync.Outcome): androidx.work.ListenableWorker.Result {
            val v2Waiting = if (outcome.v2Ran) outcome.v2Remaining.coerceAtLeast(0) else 1
            return if (legacyWaiting > 0 || v2Waiting > 0) androidx.work.ListenableWorker.Result.retry()
            else androidx.work.ListenableWorker.Result.success()
        }

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

        internal fun policyFor(running: Boolean): ExistingWorkPolicy =
            if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE

        fun readCacheDir(context: Context): File = File(context.noBackupFilesDir, "http-read-cache")
    }
}
