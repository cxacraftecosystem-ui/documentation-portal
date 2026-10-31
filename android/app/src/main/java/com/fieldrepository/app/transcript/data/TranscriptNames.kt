package com.fieldrepository.app.transcript.data

import com.fieldrepository.app.data.Naming
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * THE NAMES OF THE FILES THE VIEWER WRITES (the naming skill; `shared/naming-golden.json`).
 *
 * Online, a download keeps the server's `Content-Disposition` name. The phone names a file itself
 * only for what it builds with no connection (.md, .srt, .vtt) and for the quote card and its clip;
 * every one goes through [Naming.fileName], the app's single helper, and `TranscriptNamesTest` holds
 * the golden table's transcript rows (`Transcript`, `Subtitles-With-Speakers`, `Quote-Card`) to it.
 */
object TranscriptNames {
    /** The `What` field for a download format. */
    fun whatFor(format: String): String = when (format) {
        "srt", "vtt" -> "Subtitles-With-Speakers"
        "png", "m4a" -> "Quote-Card"
        else -> "Transcript"
    }

    /** The golden table's grammar, field for field. */
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

    /**
     * A name from what a transcript view carries: its title, "Interview · Giriraj Prasad Chhipa ·
     * 24 Jun 2026", gives the entity, the subject and the day. Place and craft are not on the view,
     * so they are left out (the convention: an unknown field is omitted, never guessed).
     */
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
