package com.fieldrepository.app.transcript.data

/** `copyTrack`: what a copy carries. */
enum class CopyTrack(val wire: String) {
    ORIGINAL("original"), ENGLISH("english"), BOTH("both");

    companion object {
        fun of(wire: String): CopyTrack = entries.first { it.wire == wire }
    }
}

/**
 * THE TEXT EVERY COPY CARRIES, BUILT ON THE PHONE (DESIGN §3, "Markdown shape").
 *
 * The contract allows a client to copy locally only if it reproduces `fixtures.copy` byte for byte,
 * and `TranscriptFormatTest` holds this file to every fixture in `shared/transcript-contract.json`.
 * Built here, not asked of `POST /copy`, so Copy works with no connection on a cached transcript.
 * The rules, from the contract:
 *
 * - Copy all: the header, a blank line, then each turn; turns are separated by a blank line.
 * - Copy selected: the chosen turns only, in transcript order, with no header.
 * - A turn's own copy: its text only, with no speaker line.
 * - "Both" puts the English under the original as a quote, every line with `> `; a turn that is
 *   ALREADY_ENGLISH prints once, and a turn with no English text prints nothing under it.
 * - "English" of a turn with no English text prints its original. STALE English still prints.
 * - Turn text is never escaped (it is what was said); speaker names lose `*`, `` ` `` and `|`.
 * - Line ends are `\n` and there is no trailing newline.
 */
object TranscriptFormat {

    /** `HH:MM:SS`, floored to the second, always two-digit hours. */
    fun timestamp(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /** `M:SS` under an hour, `H:MM:SS` from an hour. */
    fun length(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        val h = s / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun cleanSpeaker(name: String): String = name.filterNot { it == '*' || it == '`' || it == '|' }.trim()

    /** The English a turn shows and copies, or null when there is none to show. */
    fun englishOf(turn: TranscriptTurn): String? = when {
        turn.translation.status == TranslationStatus.ALREADY_ENGLISH -> turn.text
        turn.translation.text.isNullOrEmpty() -> null
        else -> turn.translation.text
    }

    fun header(view: Transcript): String {
        val cells = buildList {
            if (view.languages.isNotEmpty()) {
                add(TranscriptStrings.headerLanguages); add(view.languages.joinToString(", ") { it.label.ifBlank { it.code } })
            }
            view.durationMs?.let { add(TranscriptStrings.headerLength); add(length(it)) }
            add(TranscriptStrings.headerSpeakers); add(view.speakerCount.toString())
        }
        return "# ${view.title}\n| ${cells.joinToString(" | ")} |"
    }

    /** One turn's text for [track], with no speaker line: the per-turn copy. */
    fun body(turn: TranscriptTurn, track: CopyTrack): String {
        val english = englishOf(turn)
        return when (track) {
            CopyTrack.ORIGINAL -> turn.text
            CopyTrack.ENGLISH -> english ?: turn.text
            CopyTrack.BOTH ->
                if (english == null || turn.translation.status == TranslationStatus.ALREADY_ENGLISH) turn.text
                else turn.text + "\n" + english.split("\n").joinToString("\n") { "> $it" }
        }
    }

    fun block(turn: TranscriptTurn, track: CopyTrack, timestamps: Boolean = true): String =
        if (!timestamps) "**${cleanSpeaker(turn.speakerName)}**\n" + body(turn, track)
        else "**${cleanSpeaker(turn.speakerName)}** · `${timestamp(turn.startMs)}`\n" + body(turn, track)

    /** Copy all. [timestamps] false keeps only the speaker on each turn (0.0.5). */
    fun all(view: Transcript, track: CopyTrack, timestamps: Boolean = true): String =
        (listOf(header(view)) + view.turns.sortedBy { it.index }.map { block(it, track, timestamps) }).joinToString("\n\n")

    /** Copy selected: no header, transcript order whatever order the turns were ticked in. */
    fun selected(view: Transcript, turnIds: Collection<String>, track: CopyTrack): String {
        val wanted = turnIds.toSet()
        return view.turns.filter { it.id in wanted }.sortedBy { it.index }.joinToString("\n\n") { block(it, track) }
    }

    // ── Subtitles (original or English), for sharing a cached transcript with no connection ───

    private fun cue(ms: Long, separator: Char): String {
        val v = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d%c%03d".format(v / 3_600_000, (v % 3_600_000) / 60_000, (v % 60_000) / 1000, separator, v % 1000)
    }

    private fun subtitleText(turn: TranscriptTurn, track: CopyTrack): String =
        if (track == CopyTrack.ENGLISH) englishOf(turn) ?: turn.text else turn.text

    fun srt(view: Transcript, track: CopyTrack = CopyTrack.ORIGINAL): String =
        view.turns.sortedBy { it.index }.mapIndexed { i, t ->
            "${i + 1}\n${cue(t.startMs, ',')} --> ${cue(t.endMs, ',')}\n${cleanSpeaker(t.speakerName)}: ${subtitleText(t, track)}"
        }.joinToString("\n\n")

    fun vtt(view: Transcript, track: CopyTrack = CopyTrack.ORIGINAL): String =
        "WEBVTT\n\n" + view.turns.sortedBy { it.index }.mapIndexed { i, t ->
            "${i + 1}\n${cue(t.startMs, '.')} --> ${cue(t.endMs, '.')}\n<v ${cleanSpeaker(t.speakerName)}>${subtitleText(t, track)}"
        }.joinToString("\n\n")

    /** A speaker's label as the server builds it: the name, with "(researcher)" for a researcher. */
    fun speakerLabel(name: String?, role: String?, number: Int): String {
        val n = name?.trim()?.ifBlank { null } ?: return TranscriptStrings.speakerDefault(number)
        return if (role == SpeakerRole.RESEARCHER) TranscriptStrings.speakerResearcherSuffix(n) else n
    }
}
