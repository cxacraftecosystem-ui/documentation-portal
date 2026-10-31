package com.fieldrepository.app.transcript.data

/**
 * KARAOKE (DESIGN §10.1): which turn and which word the player is on, and where each word sits in
 * its turn's text, from Scribe's word timestamps (`Turn.words`, each with its `offset` into the
 * text). Pure, so the JVM tests hold it.
 */
object TranscriptKaraoke {

    /** How long a word stays lit after it ends, while the next has not begun. */
    const val LINGER_MS = 600L

    data class Position(val turn: Int, val word: Int)

    /** The turn being spoken at [ms]: the last turn that started at or before it, or -1 before the first. */
    fun turnAt(turns: List<TranscriptTurn>, ms: Long): Int {
        var lo = 0
        var hi = turns.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (turns[mid].startMs <= ms) {
                found = mid; lo = mid + 1
            } else hi = mid - 1
        }
        // In the gap after a turn, the turn that just ended stays lit, so the page does not flicker.
        return found
    }

    /** The word sounding at [ms] in [turn], or -1 when none is (a pause, or no word timings). */
    fun wordAt(turn: TranscriptTurn, ms: Long): Int {
        val words = turn.words
        var lo = 0
        var hi = words.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val w = words[mid]
            when {
                ms < w.startMs -> hi = mid - 1
                ms >= w.endMs -> lo = mid + 1
                else -> return mid
            }
        }
        // Between two words: the one just said stays lit until the next begins.
        return if (hi >= 0 && ms - words[hi].endMs < LINGER_MS) hi else -1
    }

    fun at(turns: List<TranscriptTurn>, ms: Long): Position {
        val t = turnAt(turns, ms)
        if (t < 0) return Position(-1, -1)
        return Position(t, wordAt(turns[t], ms))
    }

    /**
     * Each word's character range in the turn's text (end exclusive), from its offset; null for a
     * word the text no longer holds there.
     */
    fun wordRanges(turn: TranscriptTurn): List<IntRange?> = turn.words.map { w ->
        val end = w.offset + w.text.length
        if (w.offset < 0 || end > turn.text.length || w.text.isEmpty()) null else w.offset until end
    }

    /** The word under a tapped character offset, or null when the tap fell between words. */
    fun wordAtOffset(turn: TranscriptTurn, offset: Int): Int? =
        wordRanges(turn).indexOfFirst { it != null && offset in it }.takeIf { it >= 0 }

    /** Where a tap on a character should seek to: its word's start, else the turn's. */
    fun seekFor(turn: TranscriptTurn, offset: Int): Long =
        wordAtOffset(turn, offset)?.let { turn.words[it].startMs } ?: turn.startMs

    /** The low-confidence spans as character ranges inside the text, dropping any that do not fit. */
    fun doubts(turn: TranscriptTurn): List<IntRange> = turn.lowConfidence.mapNotNull { pair ->
        val start = pair.getOrNull(0) ?: return@mapNotNull null
        val end = pair.getOrNull(1) ?: return@mapNotNull null
        val s = codePointToIndex(turn.text, start)
        val e = codePointToIndex(turn.text, end)
        if (s == null || e == null || s >= e) null else s until e
    }

    /** Code-point offsets are UTF-16 offsets for every Indic script; this keeps an emoji honest too. */
    private fun codePointToIndex(text: String, codePoints: Int): Int? =
        runCatching { text.offsetByCodePoints(0, codePoints) }.getOrNull()
}
