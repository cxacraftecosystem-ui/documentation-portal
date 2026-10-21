package com.fieldrepository.app.recording

import java.io.File

/**
 * P13 on launch (DESIGN §6.1.2): a take whose draft says it never finished, whose ADTS file has
 * bytes, and which no running recorder owns, was cut short by a kill. It is offered back as
 * "Recovered a recording" with Keep (remux and queue) and Discard.
 *
 * Pure over the drafts and the file system, so [RecoveryScannerTest] covers it on the JVM.
 */
object RecoveryScanner {
    /** Drafts to offer, oldest first. [activeClipKey] is the take the service is recording now. */
    fun recoverable(drafts: List<RecordingDraft>, activeClipKey: String?): List<RecordingDraft> =
        drafts.filter { draft ->
            draft.clientClipKey != activeClipKey && !draft.queued && hasAudio(draft)
        }.sortedBy { it.deviceCapturedAt }

    /** Drafts with nothing left to offer (no audio, never started): safe to forget. */
    fun empty(drafts: List<RecordingDraft>, activeClipKey: String?): List<RecordingDraft> =
        drafts.filter { it.clientClipKey != activeClipKey && !it.queued && !hasAudio(it) }

    private fun hasAudio(draft: RecordingDraft): Boolean {
        val m4a = draft.m4aPath?.let(::File)
        if (m4a != null && m4a.isFile && m4a.length() > 0) return true
        val adts = File(draft.adtsPath)
        return adts.isFile && adts.length() > 0
    }

    /**
     * P13 Discard deletes the audio only of a take nothing has queued (audit M23). [current] is the
     * draft as it is on disk NOW, not as it was when the prompt opened: a take queued in between (the
     * service finishing late, or Keep on another screen) is in the outbox, and its file is the upload.
     */
    fun safeToDiscard(current: RecordingDraft?, queuedClipKeys: Set<String>): Boolean =
        current != null && !current.queued && current.clientClipKey !in queuedClipKeys

    /** Minutes for the P13 sentence: the remuxed duration when known, else the file at 64 kbps. */
    fun minutes(draft: RecordingDraft): Long {
        if (draft.durationMs > 0) return (draft.durationMs / 60_000).coerceAtLeast(1)
        val bytes = File(draft.adtsPath).length()
        return (bytes * 8 / 64_000 / 60).coerceAtLeast(1)
    }
}
