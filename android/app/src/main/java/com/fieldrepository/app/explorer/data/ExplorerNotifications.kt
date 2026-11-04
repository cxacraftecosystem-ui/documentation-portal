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

    private fun launch(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_OPEN_DOWNLOADS, true)
        } ?: return null
        return PendingIntent.getActivity(context, 0x4E01, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun progress(context: Context, job: ExplorerJob, cancel: PendingIntent?): Notification {
        ensureChannels(context)
        val fraction = job.fraction
        val text = when {
            job.busySeconds != null -> ExplorerText.busy(job.busySeconds)
            job.totalBytes > 0 -> ExplorerBytes.progress(job.doneBytes, job.totalBytes) + (ExplorerText.eta(job)?.let { " · $it" } ?: "")
            job.totalFiles > 0 -> ExplorerText.fetching(job.doneFiles, job.totalFiles)
            else -> ExplorerText.preparing
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job.request.title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
        launch(context)?.let { builder.setContentIntent(it) }
        cancel?.let { builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, ExplorerText.cancel, it) }
        return builder.build()
    }

    fun finished(context: Context, job: ExplorerJob) {
        if (!allowed(context)) return
        ensureChannels(context)
        val done = job.state == ExplorerJobState.DONE
        val builder = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(job.request.title)
            .setContentText(
                if (done) ExplorerText.doneLine(job.result) else job.error ?: ExplorerText.fileFailed,
            )
            .setAutoCancel(true)
        val uri = job.result?.let { ExplorerSaver.shareUri(context, it.location, it.uri) }
        val mime = job.result?.mimeType ?: job.request.mimeType
        if (done && uri != null) {
            val view = ExplorerOpen.viewIntent(uri, mime)
            builder.setContentIntent(PendingIntent.getActivity(context, idFor(job.id), view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            builder.addAction(0, ExplorerText.open, PendingIntent.getActivity(context, idFor(job.id) + 1, view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            builder.addAction(0, ExplorerText.share, PendingIntent.getActivity(context, idFor(job.id) + 2, ExplorerOpen.shareIntent(uri, mime), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            launch(context)?.let { builder.setContentIntent(it) }
        }
        runCatching { NotificationManagerCompat.from(context).notify(idFor(job.id) + DONE_OFFSET, builder.build()) }
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
