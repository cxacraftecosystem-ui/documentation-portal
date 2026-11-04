package com.fieldrepository.app.explorer.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment

data class ExplorerSystemStatus(
    val status: Int,
    val downloaded: Long,
    val total: Long,
    val uri: String?,
    val mimeType: String?,
) {
    val done: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
    val failed: Boolean get() = status == DownloadManager.STATUS_FAILED
    val waiting: Boolean get() = status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_PENDING
}

object ExplorerSystemDownloads {
    private fun manager(context: Context): DownloadManager? = context.getSystemService(DownloadManager::class.java)

    fun enqueue(context: Context, url: String, name: String, title: String, wifiOnly: Boolean): Long? = runCatching {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(!wifiOnly)
            .setMimeType(ExplorerEngine.mimeFor(name))
        manager(context)?.enqueue(request)
    }.getOrNull()

    fun remove(context: Context, id: Long) {
        runCatching { manager(context)?.remove(id) }
    }

    fun query(context: Context, id: Long): ExplorerSystemStatus? = runCatching {
        val dm = manager(context) ?: return@runCatching null
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return@use null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val mime = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE))
            val uri = if (status == DownloadManager.STATUS_SUCCESSFUL) dm.getUriForDownloadedFile(id)?.toString() else null
            ExplorerSystemStatus(status, done, total, uri, mime)
        }
    }.getOrNull()
}

class ExplorerSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val systemId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L).takeIf { it >= 0 } ?: return
        val app = context.applicationContext
        val job = ExplorerDownloads.store(app).jobs.value.firstOrNull { it.systemId == systemId && it.active } ?: return
        val status = ExplorerSystemDownloads.query(app, systemId)
        if (status == null) {
            ExplorerDownloads.store(app).change(job.id) { it.copy(state = ExplorerJobState.CANCELLED) }
            return
        }
        ExplorerDownloads.applySystem(app, job.id, status)
    }
}
