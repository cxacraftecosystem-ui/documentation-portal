package com.fieldrepository.app.transcript.data

/**
 * The few labels the Android viewer needs that `shared/strings/transcript.json` does not carry
 * (its controls, not its sentences). Everything a person reads about a transcript itself comes
 * from [TranscriptStrings].
 */
object TranscriptUiText {
    const val open = "Transcript"
    const val close = "Close"
    const val share = "Share"
    const val done = "Done"
    const val play = "Play"
    const val pause = "Pause"
    const val more = "More"
    const val accept = "Accept"
    const val dismiss = "Dismiss"
    const val name = "Name"
    const val role = "Role"
    const val couldNotLoad = "Couldn't open the transcript. Check the connection and try again."
    const val tryAgain = "Try again"
    const val noTurns = "No speech in this recording."
    const val glossaryEmpty = "No craft terms heard yet."
    const val shareCard = "Share card and clip"

    fun waiting(n: Int) = if (n == 1) "1 change waiting to send" else "$n changes waiting to send"

    fun roleLabel(role: String?): String = when (role) {
        SpeakerRole.RESEARCHER -> "Researcher"
        SpeakerRole.ARTISAN -> "Artisan"
        SpeakerRole.OTHER -> "Other"
        else -> ""
    }

    fun status(status: String): String = when (status) {
        PipelineStatus.WAITING_FOR_CONSENT -> TranscriptStrings.statusWaitingForConsent
        PipelineStatus.QUEUED -> TranscriptStrings.statusQueued
        PipelineStatus.TRANSCRIBING -> TranscriptStrings.statusTranscribing
        PipelineStatus.TRANSLATING -> TranscriptStrings.statusTranslating
        PipelineStatus.READY -> TranscriptStrings.statusReady
        else -> TranscriptStrings.statusNeedsAttention
    }

    /** The calm sentence under a status: the server's own, else the queue's reason. */
    fun statusLine(view: Transcript): String? = view.statusMessage?.ifBlank { null } ?: when {
        view.status == PipelineStatus.WAITING_FOR_CONSENT -> TranscriptStrings.consentRefused
        view.status != PipelineStatus.QUEUED -> null
        view.queueReason == "PAUSED" -> TranscriptStrings.queuePaused
        view.queueReason == "BUDGET_PAUSED" -> TranscriptStrings.queueBudgetPaused
        view.queueReason == "WINDOW" -> TranscriptStrings.queueWindow
        view.queueReason == "WAITING" -> TranscriptStrings.queueWaiting
        else -> null
    }

    fun skipReason(reason: String): String = when (reason) {
        "LOCKED" -> TranscriptStrings.autodraftSkippedLocked
        "RECORDING_PREFERRED" -> TranscriptStrings.autodraftSkippedRecordingPreferred
        else -> ""
    }

    fun usd(value: Double): String = "$" + "%.2f".format(java.util.Locale.ENGLISH, value)
}
