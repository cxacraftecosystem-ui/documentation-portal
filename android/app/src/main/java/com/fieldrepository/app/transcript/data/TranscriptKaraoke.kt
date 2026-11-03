package com.fieldrepository.app.transcript.data

object TranscriptKaraoke {

    const val LINGER_MS = 600L

    data class Position(val turn: Int, val word: Int)

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
        return found
    }

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
        return if (hi >= 0 && ms - words[hi].endMs < LINGER_MS) hi else -1
    }

    fun at(turns: List<TranscriptTurn>, ms: Long): Position {
        val t = turnAt(turns, ms)
        if (t < 0) return Position(-1, -1)
        return Position(t, wordAt(turns[t], ms))
    }

    fun wordRanges(turn: TranscriptTurn): List<IntRange?> = turn.words.map { w ->
        val end = w.offset + w.text.length
        if (w.offset < 0 || end > turn.text.length || w.text.isEmpty()) null else w.offset until end
    }

    fun wordAtOffset(turn: TranscriptTurn, offset: Int): Int? =
        wordRanges(turn).indexOfFirst { it != null && offset in it }.takeIf { it >= 0 }

    fun seekFor(turn: TranscriptTurn, offset: Int): Long =
        wordAtOffset(turn, offset)?.let { turn.words[it].startMs } ?: turn.startMs

    fun doubts(turn: TranscriptTurn): List<IntRange> = turn.lowConfidence.mapNotNull { pair ->
        val start = pair.getOrNull(0) ?: return@mapNotNull null
        val end = pair.getOrNull(1) ?: return@mapNotNull null
        val s = codePointToIndex(turn.text, start)
        val e = codePointToIndex(turn.text, end)
        if (s == null || e == null || s >= e) null else s until e
    }

    private fun codePointToIndex(text: String, codePoints: Int): Int? =
        runCatching { text.offsetByCodePoints(0, codePoints) }.getOrNull()
}
