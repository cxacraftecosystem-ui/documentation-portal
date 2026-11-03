package com.fieldrepository.app.recording

import java.io.File

object RecoveryScanner {
    fun recoverable(drafts: List<RecordingDraft>, activeClipKey: String?): List<RecordingDraft> =
        drafts.filter { draft ->
            draft.clientClipKey != activeClipKey && !draft.queued && hasAudio(draft)
        }.sortedBy { it.deviceCapturedAt }

    fun empty(drafts: List<RecordingDraft>, activeClipKey: String?): List<RecordingDraft> =
        drafts.filter { it.clientClipKey != activeClipKey && !it.queued && !hasAudio(it) }

    private fun hasAudio(draft: RecordingDraft): Boolean {
        val m4a = draft.m4aPath?.let(::File)
        if (m4a != null && m4a.isFile && m4a.length() > 0) return true
        val adts = File(draft.adtsPath)
        return adts.isFile && adts.length() > 0
    }

    fun safeToDiscard(current: RecordingDraft?, queuedClipKeys: Set<String>): Boolean =
        current != null && !current.queued && current.clientClipKey !in queuedClipKeys

    fun minutes(draft: RecordingDraft): Long {
        if (draft.durationMs > 0) return (draft.durationMs / 60_000).coerceAtLeast(1)
        val bytes = File(draft.adtsPath).length()
        return (bytes * 8 / 64_000 / 60).coerceAtLeast(1)
    }
}
