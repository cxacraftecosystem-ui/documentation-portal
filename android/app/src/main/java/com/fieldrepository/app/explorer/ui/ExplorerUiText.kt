package com.fieldrepository.app.explorer.ui

import com.fieldrepository.app.explorer.data.ExplorerBytes

object ExplorerUiText {
    const val jumpHint = "Jump to a record"
    const val searchEverything = "Search everything"
    const val organiseBy = "Organise by"
    const val organiseHint = "Pick how the records are grouped."
    const val reviewStatus = "Review status"
    const val download = "Download"
    const val downloadFolder = "Download folder"
    const val report = "Report (.xlsx)"
    const val allTranscripts = "All transcripts, every format (.zip)"
    const val files = "Files"
    const val folders = "Folders"
    const val downloaded = "Downloaded"
    const val filesLabel = "Files"
    const val speed = "Speed"
    const val timeLeft = "Time left"
    const val now = "Now"
    const val recentFiles = "Recent files"
    const val retrying = "retrying"
    const val failed = "Failed"
    const val saved = "Saved"
    const val open = "Open"
    const val share = "Share"
    const val retry = "Retry"
    const val dismiss = "Dismiss"
    const val wifi = "Wi-Fi"
    const val mobileData = "mobile data"
    const val pickOne = "Pick at least one type to download."
    const val showFewer = "Show fewer"
    const val sheet = "Sheet"
    const val thisTable = "This table (.csv)"
    const val fullReport = "Full report (.xlsx)"
    const val refreshing = "Refreshing…"
    const val searchRows = "Search every row"
    const val emptyTitle = "Nothing recorded here yet"
    const val recordNow = "Record now"
    const val offlineTitle = "You're offline"
    const val savedStillOpen = "Saved files still open"
    const val failTitle = "This folder didn't load"
    const val tryNow = "Try now"
    const val selectHint = "long-press or tap boxes"
    const val stopSelecting = "Stop selecting"
    const val transcriptTab = "Transcript"
    const val englishTab = "English"
    const val detailsTab = "Details"
    const val followsVideo = "Follows video"
    const val follow = "Follow video"
    const val transcriptDocx = "Transcript (.docx)"
    const val previous = "Previous file"
    const val next = "Next file"
    const val close = "Close"
    const val save = "Save"
    const val noTranscript = "No transcript for this recording."
    const val loadingTranscript = "Loading the transcript…"
    const val noEnglish = "No English yet."
    const val play = "Play"
    const val pause = "Pause"
    const val transcriptsSearch = "Search inside these transcripts"
    const val all = "All"
    const val ready = "Ready"
    const val translating = "Translating"
    const val transcribing = "Transcribing"
    const val read = "Read"
    const val readOriginal = "Read original"
    const val docx = ".docx"
    const val tryAgain = "Try again"
    const val language = "Language"
    const val anyLanguage = "Any language"
    const val noTranscripts = "No transcripts match."
    const val transcriptsFailed = "The transcripts didn't load."
    const val retryAsked = "Asked to transcribe again."
    const val retryFailed = "That didn't start. Try again."
    const val exportCsv = "Export"
    const val moreTranscripts = "Show more"
    const val noSpeech = "No speech found in the recording"
    const val loadingFolder = "Loading folder"
    const val noFolderPicker = "This phone has no folder picker. Files go to Downloads."
    const val allFiles = "All files"
    const val noFilesYet = "No files finished yet."
    const val moreActions = "More actions"
    const val hideTranscript = "Hide transcript"
    const val downloading = "Downloading"
    const val sortedLeast = "Least complete first"
    const val overviewNote = "Tap a square to jump to that section."
    const val overviewArtisans = "Artisans · what's been captured"
    const val complete = "Complete"
    const val partial = "Partial"
    const val missing = "Missing"

    fun fileOf(done: Int, total: Int) = "File ${String.format(java.util.Locale.ROOT, "%,d", done)} of ${String.format(java.util.Locale.ROOT, "%,d", total)}"

    fun playing(at: String, total: String?) = "Playing · $at" + (total?.let { " of $it" } ?: "")

    fun allN(n: Int) = "All ${String.format(java.util.Locale.ROOT, "%,d", n)}"

    fun pickingUp(n: Int) = "Picking up at file ${String.format(java.util.Locale.ROOT, "%,d", n)}"
    const val reportPack = "Report pack (.zip)"
    const val linkUnknown = "This link isn't one the app can open."
    const val linkFailed = "That link didn't open. Try again, or open it on the web."
    const val viewMissing = "That saved view isn't available to you."

    fun filteredBy(q: String) = "Filtered: “$q”"

    fun filesSize(files: Int, bytes: Long?): String =
        listOfNotNull(ExplorerBytes.count(files, "file"), bytes?.takeIf { it > 0 }?.let { ExplorerBytes.format(it) }).joinToString(" · ")

    fun selectedSize(size: String) = "$size selected"

    fun showAllFields(n: Int) = "Show all $n fields"

    fun downloading(title: String) = "Downloading $title"

    fun percent(n: Int) = "$n%"

    fun of(done: String, total: String) = "$done of $total"

    fun allColumns(n: Int) = "All $n columns"

    fun row(n: Int) = "Row $n"

    fun matchCount(matched: Int, total: Int) = "$matched of $total"

    fun showingRows(shown: Int, total: Int) = "Showing $shown of $total"

    fun moreRows(n: Int) = "Show $n more rows"

    const val loadingRows = "Loading rows…"

    const val rowsFailed = "More rows didn't load."

    fun emptyBody(name: String) = "Files appear as soon as someone records for $name."

    fun offlineBody(age: String?) =
        (if (age != null) "Showing what was loaded $age. " else "Showing what was loaded before. ") +
            "Downloads pause and pick up again by themselves when you're back online."

    fun retryIn(seconds: Int) = "Trying again in $seconds s. Your selection and filters are kept."

    fun selectAll(n: Int) = "Select all $n"

    fun downloadN(n: Int, size: String?) = "Download $n" + (size?.let { " · $it" } ?: "")

    fun shareN(n: Int) = if (n > 1) "Share $n" else "Share"

    fun ago(minutes: Long): String = when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }

    fun positionOf(index: Int, total: Int) = "$index of $total"

    fun zipCount(n: Int) = "Transcripts (.zip) · $n"

    fun speakers(n: Int) = ExplorerBytes.count(n, "speaker")

    fun translatingPercent(n: Int) = "Translating $n%"

    fun typeCount(n: Int, one: String, many: String) = ExplorerBytes.count(n, one, many)

    fun plusTranscripts(n: Int) = "+ " + ExplorerBytes.count(n, "transcript")

    fun clock(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        val h = s / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }
}
