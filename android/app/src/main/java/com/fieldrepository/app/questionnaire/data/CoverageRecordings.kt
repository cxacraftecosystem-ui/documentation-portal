package com.fieldrepository.app.questionnaire.data

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * WHAT A COVERAGE DOT OPENS (0.0.6): the takes of one artisan (or guest) for one section. One take
 * opens its recording straight away, several open a short chooser, none means the dot does nothing
 * and draws no tap at all.
 *
 * The server lists a cell's takes on the cell (`QGridCell.recordings`, transcript contract
 * `CoverageRecording`). A grid read from a server older than that carries only the count, so the
 * takes are then asked of the recordings list with the cell's own filters ([filters], [fromList]).
 */
object CoverageRecordings {
    private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

    /** One take a dot can open. */
    data class Take(val recordingId: String, val mediaFileId: String, val capturedAt: String?, val researcher: String?, val durationMs: Long?)

    /** What the dot opens: nothing, one take, or a choice. */
    sealed interface Opens {
        data object Nothing : Opens
        data class One(val take: Take) : Opens
        data class Choose(val takes: List<Take>) : Opens
    }

    fun opens(takes: List<Take>): Opens = when (takes.size) {
        0 -> Opens.Nothing
        1 -> Opens.One(takes.single())
        else -> Opens.Choose(takes)
    }

    /** A cell's own takes, in the server's order; one with no audio file cannot be opened. */
    fun ofCell(cell: QGridCell): List<Take> = cell.recordings.mapNotNull { r ->
        r.mediaFileId?.let { Take(r.recordingId, it, r.capturedAt, r.recordedBy?.name?.ifBlank { null }, r.durationMs) }
    }

    /** Whether the dot has takes behind it at all (and so answers a tap). */
    fun hasTakes(cell: QGridCell?): Boolean = cell != null && (cell.recordings.isNotEmpty() || cell.takes > 0 || cell.recordedCount > 0)

    /** The list query for a dot on an older server: the form, the section, the artisan, the workshop. */
    fun filters(questionnaireId: String, sectionId: String, subjectId: String, workshopId: String?, designWorkshop: Boolean): QuestionnaireFilters {
        var f = QuestionnaireFilters()
            .with("questionnaireId", listOf(questionnaireId))
            .with("sectionId", listOf(sectionId))
        QSubjects.artisanOf(subjectId)?.let { f = f.with("artisanId", listOf(it)) }
        workshopId?.ifBlank { null }?.let { f = f.with(if (designWorkshop) "designWorkshopId" else "workshopId", listOf(it)) }
        return f
    }

    /** The listed takes a dot stands for, oldest first: live, with audio, of this subject, on this section. */
    fun fromList(rows: List<QRecording>, sectionId: String, subjectId: String): List<Take> =
        rows.filter { r ->
            r.removedAt == null && r.mediaFileId != null &&
                r.allSubjects.any { it.artisanId == subjectId } &&
                r.targets.any { it.sectionId == sectionId || it.targetKey == "S:$sectionId" }
        }.sortedBy { it.capturedAt.orEmpty() }.map { r ->
            Take(r.id, r.mediaFileId!!, r.capturedAt, r.recordedBy?.name?.ifBlank { null }, r.durationMs.takeIf { it > 0 })
        }

    /** "7 Jul 2026, 10:42 · Nandini · 14 min": when, who recorded it, how long. */
    fun label(take: Take, zone: ZoneId = ZoneId.systemDefault()): String = listOfNotNull(
        take.capturedAt?.let { at -> runCatching { OffsetDateTime.parse(at).atZoneSameInstant(zone).format(WHEN) }.getOrNull() },
        take.researcher?.let(QuestionnaireCopy::firstName),
        take.durationMs?.takeIf { it > 0 }?.let(QuestionnaireCopy::minutes),
    ).joinToString(" · ")
}
