package com.fieldrepository.app.transcript.data

import com.fieldrepository.app.data.Naming
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object TranscriptNames {
    fun whatFor(format: String): String = when (format) {
        "srt", "vtt" -> "Subtitles-With-Speakers"
        "png", "m4a" -> "Quote-Card"
        else -> "Transcript"
    }

    fun fileName(
        what: String,
        ext: String,
        entity: String = "Interview",
        portal: String? = null,
        date: String? = null,
        time: Boolean = false,
        place: String? = null,
        craft: String? = null,
        subject: String? = null,
        sequence: String? = null,
        n: Int? = null,
    ): String = Naming.fileName(
        entity = entity, what = what, ext = ext, portal = portal, date = date, time = time,
        place = place, craft = craft, subject = subject, sequence = sequence, n = n,
    )

    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun forView(view: Transcript, format: String): String {
        val parts = view.title.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        val day = parts.firstNotNullOfOrNull { p -> runCatching { LocalDate.parse(p, DAY) }.getOrNull() }
        val entity = when (parts.firstOrNull()?.lowercase()) {
            "group interview", "group-interview" -> "Group-Interview"
            else -> "Interview"
        }
        val subject = parts.drop(1).firstOrNull { p -> runCatching { LocalDate.parse(p, DAY) }.isFailure }
        return fileName(what = whatFor(format), ext = format, entity = entity, date = day?.toString(), subject = subject)
    }
}
