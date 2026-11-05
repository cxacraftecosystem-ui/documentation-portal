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
    private val rate = ExplorerRate()

    override suspend fun doWork(): Result {
        val id = inputData.getString(ExplorerDownloads.KEY_ID) ?: return Result.failure()
        val store = ExplorerDownloads.store(app)
        val job = store.get(id) ?: return Result.failure()
        if (job.state == ExplorerJobState.CANCELLED || job.state == ExplorerJobState.DONE || job.state == ExplorerJobState.PAUSED) return Result.success()
        val resume = job.current == ExplorerText.resuming
        val started = System.currentTimeMillis()
        store.change(id) {
            it.copy(
                state = ExplorerJobState.RUNNING,
                startedAt = started,
                error = null,
                doneBytes = 0,
                doneFiles = 0,
                busySeconds = null,
                speedBps = null,
                current = null,
                currentBytes = 0,
                currentTotal = 0,
                retrying = 0,
                recent = if (resume) it.recent else emptyList(),
            )
        }
        runCatching { setForeground(foreground(store.get(id) ?: job)) }
        val engine = ExplorerDownloads.engine(app, ExplorerEnvironment.api(app))
        return try {
            val result = engine.run(id, job.request, resume) { tick -> onTick(id, tick) }
            store.change(id) {
                it.copy(
                    state = ExplorerJobState.DONE,
                    result = result,
                    doneBytes = maxOf(it.doneBytes, it.totalBytes),
                    doneFiles = result.saved,
                    current = null,
                    busySeconds = null,
                    speedBps = null,
                    currentBytes = 0,
                    currentTotal = 0,
                    retrying = 0,
                )
            }
            store.get(id)?.let { done ->
                val elapsed = System.currentTimeMillis() - started
                if (elapsed > 5_000 && result.bytes > 8L * 1024 * 1024) {
                    runCatching { ExplorerSpeedMemory.write(app, ExplorerNetwork.now(app).metered, result.bytes * 1000 / elapsed) }
                }
                ExplorerNotifications.finished(app, done)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val now = store.get(id)
                val byUser = now?.state == ExplorerJobState.CANCELLED
                val paused = now?.state == ExplorerJobState.PAUSED
                if (byUser) {
                    store.change(id) { it.copy(state = ExplorerJobState.CANCELLED, current = null, busySeconds = null, speedBps = null) }
                    ExplorerNotifications.cancel(app, id)
                } else if (paused) {
                    store.change(id) { it.copy(speedBps = null, busySeconds = null) }
                    store.get(id)?.let { ExplorerNotifications.paused(app, it) }
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
        val now = System.currentTimeMillis()
        val speed = rate.sample(now, tick.doneBytes)
        store.progress(id) {
            val name = tick.current
            val recent = when {
                tick.finished != null -> ExplorerProgress.finished(it.recent, tick.finished.name, tick.finished.sizeBytes, tick.finished.status)
                name != null && tick.currentTotal > 0 -> ExplorerProgress.running(it.recent, name, tick.currentTotal, (tick.currentBytes.toFloat() / tick.currentTotal).coerceIn(0f, 1f))
                name != null -> ExplorerProgress.running(it.recent, name, null, null)
                else -> it.recent
            }
            it.copy(
                doneBytes = tick.doneBytes,
                totalBytes = tick.totalBytes,
                doneFiles = tick.doneFiles,
                totalFiles = tick.totalFiles,
                current = tick.current ?: it.current?.takeIf { tick.totalFiles > 1 },
                busySeconds = tick.busySeconds,
                currentBytes = if (tick.current != null) tick.currentBytes else it.currentBytes,
                currentTotal = if (tick.current != null) tick.currentTotal else it.currentTotal,
                speedBps = speed ?: it.speedBps,
                retrying = tick.retrying,
                recent = recent,
            )
        }
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
