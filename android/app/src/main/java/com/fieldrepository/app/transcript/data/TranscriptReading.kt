package com.fieldrepository.app.transcript.data

/**
 * READING MODE (0.0.6): the conversation as one long transcript, no timestamps, each speaker's run
 * of turns one flowing paragraph under their label. Original, English (the original where a turn has
 * none) or Both. Copy and download of this view are the existing "without timestamps" ones.
 */
object TranscriptReading {

    data class Paragraph(
        val speaker: String,
        val colorKey: String,
        val turnIds: List<String>,
        val startMs: Long,
        val endMs: Long,
        val original: String,
        /** Null when no turn in the run has English to show, or every one is already English. */
        val english: String?,
    )

    /** Consecutive turns of one speaker joined into a paragraph, in transcript order. */
    fun paragraphs(turns: List<TranscriptTurn>): List<Paragraph> {
        val runs = mutableListOf<MutableList<TranscriptTurn>>()
        for (turn in turns.sortedBy { it.index }) {
            val last = runs.lastOrNull()?.last()
            if (last != null && sameSpeaker(last, turn)) runs.last() += turn else runs += mutableListOf(turn)
        }
        return runs.map { run ->
            val english = run.map { TranscriptFormat.englishOf(it) }
            val allAlready = run.all { it.translation.status == TranslationStatus.ALREADY_ENGLISH }
            Paragraph(
                speaker = TranscriptFormat.cleanSpeaker(run.first().speakerName),
                colorKey = TranscriptPreview.colorKeyOf(run.first()),
                turnIds = run.map { it.id },
                startMs = run.first().startMs,
                endMs = run.last().endMs,
                original = join(run.map { it.text }),
                english = if (allAlready || english.all { it == null }) null else join(run.mapIndexed { i, t -> english[i] ?: t.text }),
            )
        }
    }

    /** The paragraph holding [turnId], or -1. */
    fun paragraphOf(paragraphs: List<Paragraph>, turnId: String?): Int =
        if (turnId == null) -1 else paragraphs.indexOfFirst { turnId in it.turnIds }

    /** What a paragraph shows for [track]: English falls back to the original. */
    fun text(p: Paragraph, track: CopyTrack): String = when (track) {
        CopyTrack.ORIGINAL -> p.original
        CopyTrack.ENGLISH -> p.english ?: p.original
        CopyTrack.BOTH -> p.original
    }

    private fun sameSpeaker(a: TranscriptTurn, b: TranscriptTurn): Boolean =
        if (a.speakerKey != null || b.speakerKey != null) a.speakerKey == b.speakerKey
        else TranscriptFormat.cleanSpeaker(a.speakerName) == TranscriptFormat.cleanSpeaker(b.speakerName)

    private fun join(parts: List<String>): String = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}
