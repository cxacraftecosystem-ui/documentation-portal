package com.fieldrepository.app.explorer.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object ExplorerDownloads {
    const val TAG = "explorer-download"
    const val KEY_ID = "jobId"
    const val WORK_DIR = "explorer"
    const val PREVIEW_DIR = "explorer-preview"

    @Volatile private var storeInstance: ExplorerJobStore? = null

    fun store(context: Context): ExplorerJobStore =
        storeInstance ?: synchronized(this) {
            storeInstance ?: ExplorerJobStore(File(File(context.applicationContext.filesDir, WORK_DIR), "jobs.json")).also { storeInstance = it }
        }

    fun workRoot(context: Context): File = File(context.applicationContext.cacheDir, WORK_DIR)

    fun engine(context: Context, api: ExplorerApi = ExplorerEnvironment.api(context)): ExplorerEngine {
        val app = context.applicationContext
        return ExplorerEngine(
            api = api,
            json = ExplorerJson,
            workRoot = workRoot(app),
            saver = { name, mime -> ExplorerSaver.open(app, name, mime) },
            freeBytes = { ExplorerSaver.freeBytes(app) },
        )
    }

    fun start(context: Context, request: ExplorerRequest): String {
        val id = UUID.randomUUID().toString()
        store(context).put(ExplorerJob(id = id, request = request, createdAt = System.currentTimeMillis()))
        enqueue(context, id, request)
        return id
    }

    suspend fun saveFile(context: Context, request: ExplorerRequest, mediaId: String?): String {
        val app = context.applicationContext
        val direct = if (mediaId != null) withContext(Dispatchers.IO) { resolveSigned(app, ExplorerPaths.mediaDownload(mediaId)) } else null
        if (direct == null) return start(app, request)
        val id = UUID.randomUUID().toString()
        val systemId = ExplorerSystemDownloads.enqueue(app, direct, request.fileName, request.title, request.wifiOnly)
            ?: return start(app, request)
        store(app).put(
            ExplorerJob(
                id = id,
                request = request,
                createdAt = System.currentTimeMillis(),
                state = ExplorerJobState.RUNNING,
                systemId = systemId,
                startedAt = System.currentTimeMillis(),
                totalBytes = request.expectedBytes ?: 0,
                totalFiles = 1,
            ),
        )
        return id
    }

    private fun enqueue(context: Context, id: String, request: ExplorerRequest) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (request.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val work = OneTimeWorkRequestBuilder<ExplorerWorker>()
            .setInputData(workDataOf(KEY_ID to id))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, work)
    }

    fun pause(context: Context, id: String) {
        val app = context.applicationContext
        val job = store(app).get(id) ?: return
        if (!job.pausable) return
        store(app).change(id) { it.copy(state = ExplorerJobState.PAUSED, busySeconds = null, speedBps = null) }
        WorkManager.getInstance(app).cancelUniqueWork(id)
        store(app).get(id)?.let { ExplorerNotifications.paused(app, it) }
    }

    fun resume(context: Context, id: String) {
        val app = context.applicationContext
        val job = store(app).get(id) ?: return
        if (job.state != ExplorerJobState.PAUSED) return
        store(app).change(id) { it.copy(state = ExplorerJobState.QUEUED, current = ExplorerText.resuming, busySeconds = null, error = null) }
        ExplorerNotifications.cancel(app, id)
        enqueue(app, id, job.request)
    }

    fun cancel(context: Context, id: String) {
        val app = context.applicationContext
        val job = store(app).get(id) ?: return
        if (!job.active && job.state != ExplorerJobState.PAUSED) return
        store(app).change(id) { it.copy(state = ExplorerJobState.CANCELLED, busySeconds = null, current = null) }
        val systemId = job.systemId
        if (systemId != null) {
            ExplorerSystemDownloads.remove(app, systemId)
        } else {
            WorkManager.getInstance(app).cancelUniqueWork(id)
        }
        ExplorerNotifications.cancel(app, id)
    }

    fun retry(context: Context, id: String) {
        val app = context.applicationContext
        val job = store(app).get(id) ?: return
        if (job.active) return
        store(app).change(id) {
            it.copy(
                state = ExplorerJobState.QUEUED,
                result = null,
                error = null,
                doneBytes = 0,
                doneFiles = 0,
                current = if (it.request.single && !it.request.rebuilt) ExplorerText.resuming else null,
                busySeconds = null,
                systemId = null,
                speedBps = null,
                currentBytes = 0,
                currentTotal = 0,
                retrying = 0,
            )
        }
        ExplorerNotifications.cancel(app, id)
        enqueue(app, id, job.request)
    }

    fun dismiss(context: Context, id: String) {
        val app = context.applicationContext
        val job = store(app).get(id) ?: return
        if (job.active) return
        store(app).remove(id)
        ExplorerNotifications.cancel(app, id)
    }

    fun forgetFiles(filesDir: File, cacheDir: File) {
        listOf(File(filesDir, ExplorerCache.DIR), File(cacheDir, WORK_DIR), File(cacheDir, PREVIEW_DIR)).forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
    }

    fun forgetAll(context: Context) {
        val app = context.applicationContext
        val jobs = store(app).jobs.value
        jobs.forEach { job ->
            val systemId = job.systemId
            if (job.active && systemId != null) runCatching { ExplorerSystemDownloads.remove(app, systemId) }
            runCatching { ExplorerNotifications.cancel(app, job.id) }
        }
        runCatching { WorkManager.getInstance(app).cancelAllWorkByTag(TAG) }
        store(app).clear()
        forgetFiles(app.filesDir, app.cacheDir)
    }

    fun clearFinished(context: Context) {
        val app = context.applicationContext
        store(app).jobs.value.filterNot { it.active }.forEach { ExplorerNotifications.cancel(app, it.id) }
        store(app).clearFinished()
    }

    suspend fun refreshSystem(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        store(app).jobs.value.filter { it.active && it.systemId != null }.forEach { job ->
            val status = ExplorerSystemDownloads.query(app, job.systemId!!) ?: run {
                store(app).change(job.id) { it.copy(state = ExplorerJobState.CANCELLED) }
                return@forEach
            }
            applySystem(app, job.id, status)
        }
    }

    fun applySystem(context: Context, id: String, status: ExplorerSystemStatus) {
        val store = store(context)
        when {
            status.done -> store.change(id) {
                it.copy(
                    state = ExplorerJobState.DONE,
                    doneBytes = status.total.takeIf { t -> t > 0 } ?: status.done(it),
                    totalBytes = status.total.takeIf { t -> t > 0 } ?: it.totalBytes,
                    doneFiles = 1,
                    result = ExplorerResult(location = ExplorerSaver.location(it.request.fileName), uri = status.uri, mimeType = status.mimeType, saved = 1, total = 1),
                )
            }
            status.failed -> store.change(id) { it.copy(state = ExplorerJobState.FAILED, error = ExplorerText.fileFailed) }
            else -> store.progress(id) {
                it.copy(
                    state = ExplorerJobState.RUNNING,
                    doneBytes = status.downloaded,
                    totalBytes = if (status.total > 0) status.total else it.totalBytes,
                    busySeconds = null,
                    current = if (status.waiting) ExplorerText.waitingForNetwork else null,
                )
            }
        }
    }

    private fun ExplorerSystemStatus.done(job: ExplorerJob): Long = maxOf(downloaded, job.doneBytes)

    fun resolveSigned(context: Context, path: String): String? = runCatching {
        val client = ExplorerPortal.client(context).newBuilder().followRedirects(false).followSslRedirects(false).build()
        val url = ExplorerPaths.resolve(ExplorerPortal.baseUrl(), path)
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (response.code !in 300..399) return@use null
            val location = response.header("Location") ?: return@use null
            location.takeIf { ExplorerPaths.absolute(it) }
        }
    }.getOrNull()

    fun sweep(context: Context) {
        val app = context.applicationContext
        val running = store(app).jobs.value.filter { it.active || it.state == ExplorerJobState.PAUSED || it.state == ExplorerJobState.FAILED }.map { it.id }.toSet()
        workRoot(app).listFiles()?.forEach { dir -> if (dir.name !in running) dir.deleteRecursively() }
        ExplorerSaver.sweep(app)
    }
}

object ExplorerEnvironment {
    @Volatile var apiOverride: ExplorerApi? = null

    fun api(context: Context): ExplorerApi = apiOverride ?: ExplorerPortal.api(context)
}

class ExplorerCancelReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        val id = intent.getStringExtra(ExplorerDownloads.KEY_ID) ?: return
        val app = context.applicationContext
        when (intent.action) {
            ACTION_PAUSE -> ExplorerDownloads.pause(app, id)
            ACTION_RESUME -> ExplorerDownloads.resume(app, id)
            ACTION_RETRY -> ExplorerDownloads.retry(app, id)
            else -> ExplorerDownloads.cancel(app, id)
        }
    }

    companion object {
        const val ACTION = "explorer.CANCEL"
        const val ACTION_PAUSE = "explorer.PAUSE"
        const val ACTION_RESUME = "explorer.RESUME"
        const val ACTION_RETRY = "explorer.RETRY"

        fun intent(context: Context, id: String): android.app.PendingIntent = action(context, id, ACTION)

        fun pause(context: Context, id: String): android.app.PendingIntent = action(context, id, ACTION_PAUSE)

        fun resume(context: Context, id: String): android.app.PendingIntent = action(context, id, ACTION_RESUME)

        fun retry(context: Context, id: String): android.app.PendingIntent = action(context, id, ACTION_RETRY)

        private fun action(context: Context, id: String, action: String): android.app.PendingIntent {
            val intent = android.content.Intent(context, ExplorerCancelReceiver::class.java)
                .setAction(action)
                .putExtra(ExplorerDownloads.KEY_ID, id)
            return android.app.PendingIntent.getBroadcast(
                context,
                ExplorerNotifications.idFor(id),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

object ExplorerDownloadsTap {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val requests: kotlinx.coroutines.flow.StateFlow<Long> = state

    fun request() {
        state.value = System.currentTimeMillis()
    }

    fun take(intent: android.content.Intent?): Boolean {
        if (intent?.getBooleanExtra(ExplorerNotifications.EXTRA_OPEN_DOWNLOADS, false) != true) return false
        intent.removeExtra(ExplorerNotifications.EXTRA_OPEN_DOWNLOADS)
        request()
        return true
    }
}
