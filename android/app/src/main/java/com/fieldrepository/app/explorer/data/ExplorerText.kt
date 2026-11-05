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
    const val tables = "Tables"
    const val searchRows = "Search every row"
    const val tablesFailed = "The tables could not be loaded."
    const val noRows = "No tables here."
    const val csv = "CSV"
    const val pause = "Pause"
    const val resume = "Resume"
    const val paused = "Paused"
    const val reportFailed = "The report couldn't be built. Try again."
    const val retryingNow = "retrying"
    const val failedShort = "Failed"
    const val savedShort = "Saved"
    const val now = "Now"
    const val recentFiles = "Recent files"
    const val downloaded = "Downloaded"
    const val filesLabel = "Files"
    const val speedLabel = "Speed"
    const val timeLeftLabel = "Time left"
    const val connectionLost = "connection lost"
    const val mobileDataTitle = "You're on mobile data"
    const val waitForWifiNote = "Starts by itself when you're on Wi-Fi"
    const val phoneStorage = "Phone storage"
    const val enoughSpace = "Enough space"
    const val notEnoughSpace = "Not enough space"
    const val saveTo = "Save to"
    const val smallerDownload = "Smaller download"
    const val notNow = "Not now"
    const val downloadOnWifi = "Download on Wi-Fi"
    const val downloadNow = "Download now"
    const val thisDownload = "This download"

    fun preflightLine(files: Int, bytes: Long, seconds: Long?, metered: Boolean): String =
        listOfNotNull(
            ExplorerBytes.line(files, bytes),
            seconds?.let { "${aboutLeft(it)} on ${if (metered) "mobile data" else "this Wi-Fi"}" },
        ).joinToString(" · ")

    fun mobileDataBody(size: String) = "$size could use most of a monthly pack."

    fun freeAfter(size: String, after: String) = "$size · $after free after"

    fun skip(kind: ExplorerKind, size: String?, seconds: Long?) =
        listOfNotNull("Skip ${kind.label.lowercase()}", size, seconds?.let { aboutLeft(it).removePrefix("about ") }).joinToString(" · ")

    fun cachedCopy(stamp: String?) = "Offline copy" + (stamp?.let { " from $it" } ?: "") + ". It may be out of date."

    fun busy(seconds: Int) = "Busy, retrying in $seconds s"

    fun selected(n: Int) = "$n selected"

    fun rows(n: Int) = ExplorerBytes.count(n, "row")

    fun matches(matched: Int, total: Int) = "$matched of ${ExplorerBytes.count(total, "row")} match"

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

    fun speed(bps: Long): String = (ExplorerBytes.format(bps.coerceAtLeast(0)) ?: "0 B") + "/s"

    fun aboutLeft(seconds: Long): String = when {
        seconds < 60 -> "under a minute"
        seconds < 3600 -> "about ${(seconds + 30) / 60} min"
        else -> "about ${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }

    fun filesOf(done: Int, total: Int) = "${String.format(java.util.Locale.ROOT, "%,d", done)} of ${String.format(java.util.Locale.ROOT, "%,d", total)}"

    fun retryNote(n: Int) = if (n == 1) "1 file failed and will retry at the end." else "$n files failed and will retry at the end."

    fun savedFiles(n: Int) = "Saved ${ExplorerBytes.count(n, "file")}"

    fun savingTo(location: String) = "Saving to $location"

    fun didntDownload(name: String) = "$name didn't download"

    fun percentOf(title: String, percent: Int?) = if (percent == null) title else "$title · $percent%"

    fun progressLine(job: ExplorerJob, now: Long = System.currentTimeMillis()): String {
        val parts = ArrayList<String>()
        if (job.totalBytes > 0) parts += ExplorerBytes.progress(job.doneBytes, job.totalBytes)
        if (job.totalFiles > 1) parts += "${filesOf(minOf(job.doneFiles, job.totalFiles), job.totalFiles)} files"
        job.secondsLeft(now)?.let { parts += timeLeft(it).replaceFirstChar { c -> c.lowercase() } }
        return parts.joinToString(" · ").ifBlank { preparing }
    }

    fun failedFiles(n: Int) = "$n file${if (n == 1) "" else "s"} failed"

    fun fetching(done: Int, total: Int) = "File ${minOf(done + 1, total)} of $total"

    fun timeLeft(seconds: Long): String = when {
        seconds < 60 -> "Under a minute left"
        seconds < 3600 -> "${seconds / 60} min left"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min left"
    }

    fun eta(job: ExplorerJob, now: Long = System.currentTimeMillis()): String? = job.secondsLeft(now)?.let { timeLeft(it) }

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
