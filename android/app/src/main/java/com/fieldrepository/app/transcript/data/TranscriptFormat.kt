package com.fieldrepository.app.transcript.data

enum class CopyTrack(val wire: String) {
    ORIGINAL("original"), ENGLISH("english"), BOTH("both");

    companion object {
        fun of(wire: String): CopyTrack = entries.first { it.wire == wire }
    }
}

object TranscriptFormat {

    fun timestamp(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    fun length(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        val h = s / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun cleanSpeaker(name: String): String = name.filterNot { it == '*' || it == '`' || it == '|' }.trim()

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

    const val ENGLISH_HEADING = "## English"

    fun all(view: Transcript, track: CopyTrack, timestamps: Boolean = true): String {
        val manual = view.manualEnglish?.trim().orEmpty()
        if (manual.isNotEmpty() && track == CopyTrack.ENGLISH) return header(view) + "\n\n" + manual
        val parts = listOf(header(view)) + view.turns.sortedBy { it.index }.map { block(it, track, timestamps) }
        val withManual = if (manual.isNotEmpty() && track == CopyTrack.BOTH) parts + listOf(ENGLISH_HEADING, manual) else parts
        return withManual.joinToString("\n\n")
    }

    data class EnglishPlan(val paragraphs: Int, val turns: Int, val aligned: Boolean)

    fun englishPlan(turns: List<TranscriptTurn>, text: String): EnglishPlan {
        val paragraphs = text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).count { it.isNotBlank() }
        val needing = turns.count { it.translation.status != TranslationStatus.ALREADY_ENGLISH }
        return EnglishPlan(paragraphs, needing, paragraphs > 0 && (paragraphs == needing || paragraphs == turns.size))
    }

    fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }

    fun selected(view: Transcript, turnIds: Collection<String>, track: CopyTrack): String {
        val wanted = turnIds.toSet()
        return view.turns.filter { it.id in wanted }.sortedBy { it.index }.joinToString("\n\n") { block(it, track) }
    }

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

    fun speakerLabel(name: String?, role: String?, number: Int): String {
        val n = name?.trim()?.ifBlank { null } ?: return TranscriptStrings.speakerDefault(number)
        return if (role == SpeakerRole.RESEARCHER) TranscriptStrings.speakerResearcherSuffix(n) else n
    }
}
