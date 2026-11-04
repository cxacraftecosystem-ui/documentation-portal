package com.fieldrepository.app.explorer.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

object ExplorerText {
    const val title = "Data"
    const val back = "Back"
    const val reload = "Reload"
    const val searchHint = "Search records"
    const val organiseBy = "Organise by"
    const val reviewStatus = "Review status"
    const val folderEmpty = "This folder is empty."
    const val loadingFolder = "Loading folder…"
    const val partlyShown = "Showing part of this folder. Open subfolders for the rest."
    const val loadMore = "Load more"
    const val restrictedTitle = "Dataset access is restricted"
    const val restrictedBody = "Ask an admin for dataset access."
    const val retry = "Retry"
    const val dismiss = "Dismiss"
    const val details = "Details"
    const val transcripts = "Transcripts"
    const val show = "Show"
    const val hide = "Hide"
    const val download = "Download"
    const val downloads = "Downloads"
    const val downloadFolder = "Download folder"
    const val everything = "Everything"
    const val transcriptsZip = "Transcripts, all formats"
    const val transcriptsZipNote = "Every transcript here as .md, .docx, .srt and .vtt"
    const val allFormats = "All formats"
    const val report = "Report (.xlsx)"
    const val reportNote = "Spreadsheet of every record here"
    const val start = "Start download"
    const val waitForWifi = "Wait for Wi-Fi"
    const val useMobileData = "Use mobile data"
    const val cancel = "Cancel"
    const val open = "Open"
    const val share = "Share"
    const val save = "Save"
    const val saving = "Saving…"
    const val preview = "Preview"
    const val view = "View"
    const val transcript = "Transcript"
    const val select = "Select"
    const val clear = "Clear"
    const val clearFinished = "Clear finished"
    const val noDownloads = "No downloads yet."
    const val queued = "Waiting to start"
    const val waitingForWifi = "Waiting for Wi-Fi"
    const val waitingForNetwork = "Waiting for a connection"
    const val resuming = "Resuming…"
    const val preparing = "Preparing…"
    const val counting = "Counting files…"
    const val cancelled = "Cancelled"
    const val nothingToSave = "Nothing here to download."
    const val saveFailed = "The file could not be saved to Downloads."
    const val manifestFailed = "The file list could not be loaded. Try again."
    const val fileFailed = "The file could not be downloaded. Try again."
    const val folderFailed = "This folder could not be loaded."
    const val locateFailed = "This record could not be found."
    const val transcriptFailed = "The transcript could not be loaded."
    const val transcriptNone = "No transcript for this file."
    const val offline = "You're offline. Downloads need a connection."
    const val previewFailed = "This file can't be previewed. Download it instead."
    const val sizeUnknown = "Size is counted when the download starts."
    const val notificationChannel = "Downloads"
    const val notificationChannelDone = "Finished downloads"
    const val metered = "You're on mobile data."
    const val partOfArchive = "Too large for one archive. Some files were left out."
    const val failedFilesTitle = "Not downloaded"
    const val keepsGoing = "Keeps going if you leave the app."
    const val page = "Page"

    fun cachedCopy(stamp: String?) = "Offline copy" + (stamp?.let { " from $it" } ?: "") + ". It may be out of date."

    fun busy(seconds: Int) = "Busy, retrying in $seconds s"

    fun selected(n: Int) = "$n selected"

    fun downloadSelected(n: Int) = "Download $n selected"

    fun noSpace(needed: String?, free: String?) = "Not enough space: needs ${needed ?: "more"}, ${free ?: "less"} free."

    fun meteredSize(size: String) = "You're on mobile data. This is $size."

    fun unfiled(title: String) = "$title isn't in a folder yet."

    fun showing(shown: Int, total: Int) = "Showing $shown of $total"

    fun savedTo(location: String) = "Saved to $location"

    fun result(saved: Int, total: Int, failed: Int): String = when {
        total == 0 -> nothingToSave
        failed > 0 -> "Saved $saved of $total. $failed failed."
        else -> "Saved ${ExplorerBytes.count(total, "file")}."
    }

    fun doneLine(result: ExplorerResult?): String {
        if (result == null) return savedTo(downloads)
        result.message?.let { return it }
        val head = if (result.total > 1 || result.failedCount > 0) result(result.saved, result.total, result.failedCount) else null
        val where = result.location?.let { savedTo(it) }
        return listOfNotNull(head, where).joinToString(" ").ifBlank { savedTo(downloads) }
    }

    fun failedFiles(n: Int) = "$n file${if (n == 1) "" else "s"} failed"

    fun fetching(done: Int, total: Int) = "File ${minOf(done + 1, total)} of $total"

    fun timeLeft(seconds: Long): String = when {
        seconds < 60 -> "Under a minute left"
        seconds < 3600 -> "${seconds / 60} min left"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min left"
    }

    fun eta(job: ExplorerJob, now: Long = System.currentTimeMillis()): String? {
        val started = job.startedAt ?: return null
        val elapsed = now - started
        if (job.doneBytes <= 0 || job.totalBytes <= job.doneBytes || elapsed < 3_000) return null
        val rate = job.doneBytes.toDouble() / elapsed
        val left = ((job.totalBytes - job.doneBytes) / rate / 1000.0).toLong()
        return timeLeft(left)
    }

    fun folderCount(folders: Int, files: Int) =
        "${ExplorerBytes.count(folders, "folder")} · ${ExplorerBytes.count(files, "file")}"

    fun failureOf(failure: Throwable): String = when (failure) {
        is ExplorerSpaceException -> failure.message ?: fileFailed
        is ExplorerBusyException -> failure.message ?: fileFailed
        is IllegalStateException -> failure.message?.takeIf { it.isNotBlank() } ?: fileFailed
        else -> fileFailed
    }

    fun errorDetail(body: String?, json: Json): String? = runCatching {
        val detail = json.parseToJsonElement(body ?: return null).jsonObject["detail"]
        when (detail) {
            is JsonPrimitive -> detail.content
            is JsonObject -> (detail["message"] as? JsonPrimitive)?.content
            else -> null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
