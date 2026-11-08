package com.fieldrepository.app.explorer.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object ExplorerNotifications {
    const val CHANNEL_PROGRESS = "explorer-downloads"
    const val CHANNEL_DONE = "explorer-downloads-done"

    fun idFor(jobId: String): Int = 0x4E00000 or (jobId.hashCode() and 0xFFFFF)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, ExplorerText.notificationChannel, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, ExplorerText.notificationChannelDone, NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun fileAction(context: Context, requestCode: Int, jobId: String, action: ExplorerOpenAction): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            ExplorerOpenActivity.intent(context, jobId, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun launch(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_OPEN_DOWNLOADS, true)
        } ?: return null
        return PendingIntent.getActivity(context, 0x4E01, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun progressText(job: ExplorerJob, now: Long = System.currentTimeMillis()): String = when {
        job.busySeconds != null -> ExplorerText.busy(job.busySeconds)
        job.totalBytes > 0 || job.totalFiles > 1 -> ExplorerText.progressLine(job, now)
        job.totalFiles > 0 -> ExplorerText.fetching(job.doneFiles, job.totalFiles)
        else -> ExplorerText.preparing
    }

    fun currentText(job: ExplorerJob): String? {
        val name = job.current?.takeIf { it != ExplorerText.resuming && it != ExplorerText.waitingForNetwork }?.let { ExplorerProgress.leaf(it) }
        val speed = job.speedBps?.takeIf { it > 0 }?.let { ExplorerText.speed(it) }
        return listOfNotNull(name, speed).joinToString(" · ").ifBlank { null }
    }

    fun progress(context: Context, job: ExplorerJob, cancel: PendingIntent?): Notification {
        ensureChannels(context)
        val fraction = job.fraction
        val text = progressText(job)
        val detail = currentText(job)
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ExplorerText.percentOf(job.request.title, ExplorerProgress.percent(job)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text, detail).joinToString("\n")))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
        val open = launch(context)
        open?.let { builder.setContentIntent(it) }
        if (job.pausable) builder.addAction(0, ExplorerText.pause, ExplorerCancelReceiver.pause(context, job.id))
        cancel?.let { builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, ExplorerText.cancel, it) }
        open?.let { builder.addAction(0, ExplorerText.open, it) }
        return builder.build()
    }

    fun paused(context: Context, job: ExplorerJob) {
        if (!allowed(context)) return
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(ExplorerText.percentOf(job.request.title, ExplorerProgress.percent(job)))
            .setContentText("${ExplorerText.paused} · ${progressText(job)}")
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(1000, ((job.fraction ?: 0f) * 1000).toInt(), false)
            .addAction(0, ExplorerText.resume, ExplorerCancelReceiver.resume(context, job.id))
            .addAction(0, ExplorerText.cancel, ExplorerCancelReceiver.intent(context, job.id))
        launch(context)?.let { builder.setContentIntent(it) }
        try {
            NotificationManagerCompat.from(context).notify(idFor(job.id) + DONE_OFFSET, builder.build())
        } catch (denied: SecurityException) {
            return
        }
    }

    fun finishedTitle(job: ExplorerJob): String =
        if (job.state == ExplorerJobState.DONE) "${ExplorerText.savedShort} ${job.request.title}" else job.request.title

    fun finishedText(job: ExplorerJob): String {
        val result = job.result
        if (job.state != ExplorerJobState.DONE) {
            val name = if (job.request.single) job.request.fileName else null
            return name?.let { ExplorerText.didntDownload(it) } ?: job.error ?: ExplorerText.fileFailed
        }
        if (result == null) return ExplorerText.doneLine(null)
        result.message?.let { return it }
        val size = ExplorerBytes.format(result.bytes.takeIf { it > 0 } ?: job.totalBytes.takeIf { it > 0 })
        val head = listOfNotNull(ExplorerBytes.count(result.saved, "file"), size).joinToString(" · ")
        val failed = if (result.failedCount > 0) ExplorerText.failedFiles(result.failedCount) else null
        return listOfNotNull(head, failed).joinToString(" · ")
    }

    fun finished(context: Context, job: ExplorerJob) {
        if (!allowed(context)) return
        ensureChannels(context)
        val done = job.state == ExplorerJobState.DONE
        val builder = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(finishedTitle(job))
            .setContentText(finishedText(job))
            .setAutoCancel(true)
        val uri = job.result?.let { ExplorerSaver.shareUri(context, it.location, it.uri) }
        if (done && uri != null) {
            val view = fileAction(context, idFor(job.id), job.id, ExplorerOpenAction.VIEW)
            builder.setContentIntent(view)
            builder.addAction(0, ExplorerText.open, fileAction(context, idFor(job.id) + 1, job.id, ExplorerOpenAction.VIEW))
            builder.addAction(0, ExplorerText.share, fileAction(context, idFor(job.id) + 2, job.id, ExplorerOpenAction.SHARE))
        } else {
            val open = launch(context)
            open?.let { builder.setContentIntent(it) }
            if (!done) {
                builder.addAction(0, ExplorerText.retry, ExplorerCancelReceiver.retry(context, job.id))
                open?.let { builder.addAction(0, ExplorerText.details, it) }
            }
        }
        try {
            NotificationManagerCompat.from(context).notify(idFor(job.id) + DONE_OFFSET, builder.build())
        } catch (denied: SecurityException) {
            return
        }
    }

    fun cancel(context: Context, jobId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(idFor(jobId))
            NotificationManagerCompat.from(context).cancel(idFor(jobId) + DONE_OFFSET)
        }
    }

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    const val EXTRA_OPEN_DOWNLOADS = "explorer.openDownloads"
    private const val DONE_OFFSET = 0x80000

}

object ExplorerOpen {
    fun viewIntent(uri: Uri, mime: String?): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime?.takeIf { it.isNotBlank() } ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(uri: Uri, mime: String?): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime?.takeIf { it.isNotBlank() } ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun open(context: Context, job: ExplorerJob): Boolean = start(context, job) { uri, mime -> viewIntent(uri, mime) }

    fun share(context: Context, job: ExplorerJob): Boolean = start(context, job) { uri, mime -> shareIntent(uri, mime) }

    private fun start(context: Context, job: ExplorerJob, build: (Uri, String?) -> Intent): Boolean {
        val result = job.result ?: return false
        val uri = ExplorerSaver.shareUri(context, result.location, result.uri) ?: return false
        return runCatching { context.startActivity(build(uri, result.mimeType ?: job.request.mimeType)); true }.getOrDefault(false)
    }
}
