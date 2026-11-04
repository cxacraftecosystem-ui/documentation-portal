package com.fieldrepository.app.explorer.data

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ExplorerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext
    private var lastNotified = 0L
    private var lastSaved = 0L

    override suspend fun doWork(): Result {
        val id = inputData.getString(ExplorerDownloads.KEY_ID) ?: return Result.failure()
        val store = ExplorerDownloads.store(app)
        val job = store.get(id) ?: return Result.failure()
        if (job.state == ExplorerJobState.CANCELLED || job.state == ExplorerJobState.DONE) return Result.success()
        val started = System.currentTimeMillis()
        store.change(id) { it.copy(state = ExplorerJobState.RUNNING, startedAt = started, error = null, doneBytes = 0, doneFiles = 0, busySeconds = null) }
        runCatching { setForeground(foreground(store.get(id) ?: job)) }
        val engine = ExplorerDownloads.engine(app, ExplorerEnvironment.api(app))
        return try {
            val result = engine.run(id, job.request) { tick -> onTick(id, tick) }
            store.change(id) {
                it.copy(
                    state = ExplorerJobState.DONE,
                    result = result,
                    doneBytes = maxOf(it.doneBytes, it.totalBytes),
                    doneFiles = result.saved,
                    current = null,
                    busySeconds = null,
                )
            }
            store.get(id)?.let { ExplorerNotifications.finished(app, it) }
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val now = store.get(id)
                val byUser = now?.state == ExplorerJobState.CANCELLED
                if (byUser) {
                    store.change(id) { it.copy(state = ExplorerJobState.CANCELLED, current = null, busySeconds = null) }
                    ExplorerNotifications.cancel(app, id)
                } else {
                    store.change(id) { it.copy(state = ExplorerJobState.QUEUED, current = ExplorerText.resuming, busySeconds = null) }
                }
            }
            throw cancelled
        } catch (failure: Throwable) {
            val transient = failure is java.io.IOException && failure !is TransferFailed
            if (transient && runAttemptCount < MAX_RETRIES) {
                store.change(id) { it.copy(state = ExplorerJobState.QUEUED, current = ExplorerText.resuming, busySeconds = null) }
                return Result.retry()
            }
            store.change(id) {
                it.copy(state = ExplorerJobState.FAILED, error = ExplorerText.failureOf(failure), current = null, busySeconds = null)
            }
            store.get(id)?.let { ExplorerNotifications.finished(app, it) }
            Result.failure()
        }
    }

    private fun onTick(id: String, tick: ExplorerTick) {
        val store = ExplorerDownloads.store(app)
        store.progress(id) {
            it.copy(
                doneBytes = tick.doneBytes,
                totalBytes = tick.totalBytes,
                doneFiles = tick.doneFiles,
                totalFiles = tick.totalFiles,
                current = tick.current,
                busySeconds = tick.busySeconds,
            )
        }
        val now = System.currentTimeMillis()
        if (now - lastSaved > SAVE_MS) {
            lastSaved = now
            store.flush()
        }
        if (now - lastNotified > NOTIFY_MS) {
            lastNotified = now
            store.get(id)?.let { job -> runCatching { setForegroundAsync(foreground(job)) } }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getString(ExplorerDownloads.KEY_ID).orEmpty()
        val job = ExplorerDownloads.store(app).get(id)
            ?: ExplorerJob(id = id, request = ExplorerRequest(title = ExplorerText.downloads, fileName = ""), createdAt = 0)
        return foreground(job)
    }

    private fun foreground(job: ExplorerJob): ForegroundInfo {
        val cancel = ExplorerCancelReceiver.intent(app, job.id)
        val notification = ExplorerNotifications.progress(app, job, cancel)
        val nid = ExplorerNotifications.idFor(job.id)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(nid, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(nid, notification)
        }
    }

    companion object {
        const val MAX_RETRIES = 3
        const val NOTIFY_MS = 1_000L
        const val SAVE_MS = 3_000L
    }
}
